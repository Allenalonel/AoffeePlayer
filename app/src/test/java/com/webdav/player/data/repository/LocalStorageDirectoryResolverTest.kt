package com.webdav.player.data.repository

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LocalStorageFolder
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.ServerRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.net.URLEncoder

@RunWith(RobolectricTestRunner::class)
class LocalStorageDirectoryResolverTest {
    companion object {
        const val AUTHORITY = "com.webdav.player.test.documents"
        const val TREE_URI_STRING = "content://$AUTHORITY/tree/primary%3AMusic"
    }

    class TestDocumentsProvider : DocumentsProvider() {
        companion object {
            val childrenMap = mutableMapOf<String, List<DocEntry>>()
            val fileContents = mutableMapOf<String, String>()
            val fileBytes = mutableMapOf<String, ByteArray>()
            lateinit var tempDir: File

            fun reset(dir: File) {
                childrenMap.clear()
                fileContents.clear()
                fileBytes.clear()
                tempDir = dir
            }
        }

        data class DocEntry(
            val docId: String,
            val displayName: String,
            val mimeType: String,
            val size: Long = 0L,
            val lastModified: Long = 0L,
        )

        override fun onCreate(): Boolean = true

        override fun queryRoots(projection: Array<out String>?): Cursor {
            val cols =
                projection ?: arrayOf(
                    DocumentsContract.Root.COLUMN_ROOT_ID,
                    DocumentsContract.Root.COLUMN_DOCUMENT_ID,
                )
            return MatrixCursor(cols).apply {
                addRow(arrayOf("root", "primary:Music"))
            }
        }

        override fun queryDocument(
            documentId: String?,
            projection: Array<out String>?,
        ): Cursor {
            val cols =
                projection ?: arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                )
            return MatrixCursor(cols).apply {
                addRow(arrayOf(documentId, documentId ?: "root", Document.MIME_TYPE_DIR))
            }
        }

        override fun queryChildDocuments(
            parentDocumentId: String?,
            projection: Array<out String>?,
            sortOrder: String?,
        ): Cursor {
            val defaultCols =
                arrayOf(
                    Document.COLUMN_DOCUMENT_ID,
                    Document.COLUMN_DISPLAY_NAME,
                    Document.COLUMN_MIME_TYPE,
                    Document.COLUMN_SIZE,
                    Document.COLUMN_LAST_MODIFIED,
                )
            val cols = projection ?: defaultCols
            val cursor = MatrixCursor(cols)
            val entries = childrenMap[parentDocumentId] ?: emptyList()
            for (entry in entries) {
                val row =
                    cols.map { col ->
                        when (col) {
                            Document.COLUMN_DOCUMENT_ID -> entry.docId
                            Document.COLUMN_DISPLAY_NAME -> entry.displayName
                            Document.COLUMN_MIME_TYPE -> entry.mimeType
                            Document.COLUMN_SIZE -> entry.size
                            Document.COLUMN_LAST_MODIFIED -> entry.lastModified
                            else -> null
                        }
                    }.toTypedArray()
                cursor.addRow(row)
            }
            return cursor
        }

        override fun isChildDocument(
            parentDocumentId: String?,
            documentId: String?,
        ): Boolean = true

        override fun openDocument(
            documentId: String?,
            mode: String?,
            signal: CancellationSignal?,
        ): ParcelFileDescriptor {
            val bytes =
                fileBytes[documentId]
                    ?: fileBytes.entries.firstOrNull {
                        documentId != null && (documentId.endsWith(it.key) || it.key.endsWith(documentId))
                    }?.value
            val tempFile = File(tempDir, "temp_${documentId?.replace('/', '_') ?: "doc"}")
            if (bytes != null) {
                tempFile.writeBytes(bytes)
            } else {
                val content =
                    fileContents[documentId]
                        ?: fileContents.entries.firstOrNull {
                            documentId != null && (documentId.endsWith(it.key) || it.key.endsWith(documentId))
                        }?.value
                        ?: ""
                tempFile.writeText(content, Charsets.UTF_8)
            }
            return ParcelFileDescriptor.open(tempFile, ParcelFileDescriptor.MODE_READ_ONLY)
        }
    }

    private class FakeServerRepository(
        private val mountedFolders: List<LocalStorageFolder>,
    ) : ServerRepository {
        override fun getAllServers(): Flow<List<WebDavServer>> = flowOf(emptyList())

        override fun getActiveServer(): Flow<WebDavServer?> = flowOf(null)

        override suspend fun getServerById(id: Long): WebDavServer? = null

        override suspend fun saveServer(server: WebDavServer): Long = 0L

        override suspend fun deleteServer(id: Long) {}

        override suspend fun setActiveServer(id: Long) {}

        override fun getMountedLocalFolders(): Flow<List<LocalStorageFolder>> = flowOf(mountedFolders)

        override suspend fun addMountedLocalFolder(
            uriString: String,
            displayName: String,
        ) {}

        override suspend fun removeMountedLocalFolder(uriString: String) {}
    }

    private lateinit var context: Context
    private lateinit var resolver: LocalStorageDirectoryResolver
    private val mountedFolders =
        listOf(
            LocalStorageFolder(TREE_URI_STRING, "音乐库"),
        )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        TestDocumentsProvider.reset(context.cacheDir)
        val providerInfo =
            ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
        Robolectric.buildContentProvider(TestDocumentsProvider::class.java).create(providerInfo)

        val serverRepo = FakeServerRepository(mountedFolders)
        resolver = LocalStorageDirectoryResolver(context, serverRepo)
    }

    @Test
    fun resolve_root_emitsMountedFoldersAsSubDirectories() =
        runTest {
            val result = resolver.resolve("/")
            val dir = result.directory

            assertEquals("/", dir.path)
            assertEquals("本地存储", dir.name)
            assertEquals(LOCAL_STORAGE_ID, dir.serverId)
            assertEquals(1, dir.subDirectories.size)
            assertEquals("音乐库", dir.subDirectories[0].name)

            val expectedEncoded = URLEncoder.encode(TREE_URI_STRING, "UTF-8")
            assertEquals("/local/$expectedEncoded/", dir.subDirectories[0].path)
            assertTrue(dir.files.isEmpty())
            assertTrue(result.cueAlbums.isEmpty())
        }

    @Test
    fun resolve_childDirectory_enumeratesSubDirectoriesAndAudioFiles() =
        runTest {
            val rootDocId = "primary:Music"
            TestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock",
                        displayName = "Rock",
                        mimeType = Document.MIME_TYPE_DIR,
                    ),
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Song.flac",
                        displayName = "Song.flac",
                        mimeType = "audio/flac",
                        size = 20480000L,
                    ),
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Song.lrc",
                        displayName = "Song.lrc",
                        mimeType = "text/plain",
                        size = 512L,
                    ),
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/cover.jpg",
                        displayName = "cover.jpg",
                        mimeType = "image/jpeg",
                        size = 40960L,
                    ),
                )

            val encodedTreeUri = URLEncoder.encode(TREE_URI_STRING, "UTF-8")
            val result = resolver.resolve("/local/$encodedTreeUri/")
            val dir = result.directory

            assertEquals("/local/$encodedTreeUri/", dir.path)
            assertEquals("音乐库", dir.name)
            assertEquals(1, dir.subDirectories.size)
            assertEquals("Rock", dir.subDirectories[0].name)
            assertEquals("/local/$encodedTreeUri/Rock/", dir.subDirectories[0].path)

            assertEquals(3, dir.files.size)
            val audioFile = dir.files.firstOrNull { it.name == "Song.flac" }
            assertNotNull(audioFile)
            assertTrue(audioFile!!.isAudio)
            assertEquals(RemoteFileType.Audio(com.webdav.player.domain.model.AudioFormat.FLAC), audioFile.fileType)

            // Companion lyrics & cover artwork attached in metadata
            val audioMeta = result.metadataMap[audioFile.path]
            assertNotNull(audioMeta)
            assertEquals("Song", audioMeta!!.title)
            assertNotNull(audioMeta.coverThumbnailPath)
            assertTrue(audioMeta.coverThumbnailPath!!.contains("cover.jpg"))
            assertNotNull(audioMeta.lyrics)
            assertTrue(audioMeta.lyrics!!.contains("Song.lrc"))
        }

    @Test
    fun resolve_nestedSubfolder_navigatesDownHierarchy() =
        runTest {
            val rootDocId = "primary:Music"
            TestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock",
                        displayName = "Rock",
                        mimeType = Document.MIME_TYPE_DIR,
                    ),
                )

            TestDocumentsProvider.childrenMap["primary:Music/Rock"] =
                listOf(
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock/Queen.mp3",
                        displayName = "Queen.mp3",
                        mimeType = "audio/mpeg",
                        size = 5000000L,
                    ),
                )

            val encodedTreeUri = URLEncoder.encode(TREE_URI_STRING, "UTF-8")
            val result = resolver.resolve("/local/$encodedTreeUri/Rock/")
            val dir = result.directory

            assertEquals("/local/$encodedTreeUri/Rock/", dir.path)
            assertEquals("Rock", dir.name)
            assertEquals(0, dir.subDirectories.size)
            assertEquals(1, dir.files.size)
            assertEquals("Queen.mp3", dir.files[0].name)
            assertTrue(dir.files[0].isAudio)
        }

    @Test
    fun resolve_cueSheetWithCompanionAudio_emitsCompanionAudioDirectlyAndLeavesCueAlbumsEmpty() =
        runTest {
            val rootDocId = "primary:Music"
            val cueDocId = "primary:Music/Album.cue"
            val audioDocId = "primary:Music/Album.flac"

            TestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    TestDocumentsProvider.DocEntry(
                        docId = cueDocId,
                        displayName = "Album.cue",
                        mimeType = "application/x-cue",
                        size = 1000L,
                    ),
                    TestDocumentsProvider.DocEntry(
                        docId = audioDocId,
                        displayName = "Album.flac",
                        mimeType = "audio/flac",
                        size = 50000000L,
                    ),
                )

            val encodedTreeUri = URLEncoder.encode(TREE_URI_STRING, "UTF-8")
            val result = resolver.resolve("/local/$encodedTreeUri/")

            assertTrue(result.cueAlbums.isEmpty())
            assertEquals(2, result.directory.files.size)
            val audioFile = result.directory.files.firstOrNull { it.name == "Album.flac" }
            assertNotNull(audioFile)
            assertTrue(audioFile!!.isAudio)
            assertTrue(result.metadataMap.containsKey(audioFile.path))
        }

    @Test
    fun resolve_companionCover_withCoverArtStorage_savesPhysicalThumbnail() =
        runTest {
            val coverArtStorage = com.webdav.player.data.local.CoverArtStorageImpl(context)
            val resolverWithStorage =
                LocalStorageDirectoryResolver(
                    context = context,
                    serverRepository = FakeServerRepository(mountedFolders),
                    coverArtStorage = coverArtStorage,
                )

            val rootDocId = "primary:Music"
            TestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Song.flac",
                        displayName = "Song.flac",
                        mimeType = "audio/flac",
                        size = 20480000L,
                    ),
                    TestDocumentsProvider.DocEntry(
                        docId = "primary:Music/cover.jpg",
                        displayName = "cover.jpg",
                        mimeType = "image/jpeg",
                        size = 22L,
                    ),
                )
            // Valid minimal JPEG bytes: SOI (FF D8) ... EOI (FF D9)
            val fakeJpeg =
                byteArrayOf(
                    0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(),
                    0x00, 0x10, 0x4A, 0x46, 0x49, 0x46, 0x00, 0x01,
                    0x01, 0x00, 0x00, 0x01, 0x00, 0x01, 0x00, 0x00,
                    0xFF.toByte(), 0xD9.toByte(),
                )
            TestDocumentsProvider.fileBytes["primary:Music/cover.jpg"] = fakeJpeg

            val encodedTreeUri = URLEncoder.encode(TREE_URI_STRING, "UTF-8")
            val result = resolverWithStorage.resolve("/local/$encodedTreeUri/")
            val audioFile = result.directory.files.first { it.name == "Song.flac" }
            val audioMeta = result.metadataMap[audioFile.path]
            assertNotNull(audioMeta)
            assertNotNull(audioMeta?.coverThumbnailPath)
            assertTrue(audioMeta?.coverThumbnailPath!!.startsWith(context.cacheDir.absolutePath))
            assertTrue(coverArtStorage.isValidThumbnailFile(audioMeta.coverThumbnailPath))
        }
}
