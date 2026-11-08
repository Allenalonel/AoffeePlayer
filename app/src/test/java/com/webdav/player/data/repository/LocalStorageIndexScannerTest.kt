package com.webdav.player.data.repository

import android.content.Context
import android.content.pm.ProviderInfo
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.CancellationSignal
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.provider.DocumentsProvider
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.webdav.player.data.local.AppDatabase
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.LocalStorageFolderDao
import com.webdav.player.data.local.LocalStorageFolderEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
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
class LocalStorageIndexScannerTest {
    companion object {
        const val AUTHORITY = "com.webdav.player.test.scanner.documents"
        const val TREE_URI_STRING = "content://$AUTHORITY/tree/primary%3AMusic"
    }

    class ScannerTestDocumentsProvider : DocumentsProvider() {
        companion object {
            val childrenMap = mutableMapOf<String, List<DocEntry>>()
            var queryCount = 0

            fun reset() {
                childrenMap.clear()
                queryCount = 0
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
            queryCount++
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
                    cols
                        .map { col ->
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
        ): ParcelFileDescriptor = throw UnsupportedOperationException()
    }

    private lateinit var database: AppDatabase
    private lateinit var mediaTrackIndexDao: MediaTrackIndexDao
    private lateinit var checkpointDao: DirectorySyncCheckpointDao
    private lateinit var folderDao: LocalStorageFolderDao
    private lateinit var scanner: LocalStorageIndexScanner

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        ScannerTestDocumentsProvider.reset()
        val providerInfo =
            ProviderInfo().apply {
                authority = AUTHORITY
                exported = true
                grantUriPermissions = true
                readPermission = android.Manifest.permission.MANAGE_DOCUMENTS
                writePermission = android.Manifest.permission.MANAGE_DOCUMENTS
            }
        Robolectric.buildContentProvider(ScannerTestDocumentsProvider::class.java).create(providerInfo)

        database =
            Room
                .inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()

        mediaTrackIndexDao = database.mediaTrackIndexDao()
        checkpointDao = database.directorySyncCheckpointDao()
        folderDao = database.localStorageFolderDao()

        scanner =
            LocalStorageIndexScanner(
                context = context,
                mediaTrackIndexDao = mediaTrackIndexDao,
                checkpointDao = checkpointDao,
                localStorageFolderDao = folderDao,
            )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun indexFolder_traversesTreeAndIndexesAudioAndCueFiles_skippingNoiseDirectories() =
        runTest {
            val rootDocId = "primary:Music"
            ScannerTestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/01 - Queen - Bohemian Rhapsody.flac",
                        displayName = "01 - Queen - Bohemian Rhapsody.flac",
                        mimeType = "audio/flac",
                        size = 35000000L,
                        lastModified = 1700000000L,
                    ),
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Album.cue",
                        displayName = "Album.cue",
                        mimeType = "application/x-cue",
                        size = 1024L,
                        lastModified = 1700000000L,
                    ),
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock",
                        displayName = "Rock",
                        mimeType = Document.MIME_TYPE_DIR,
                        lastModified = 1700000100L,
                    ),
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/readme.txt",
                        displayName = "readme.txt",
                        mimeType = "text/plain",
                        size = 200L,
                    ),
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/.@eaDir",
                        displayName = ".@eaDir",
                        mimeType = Document.MIME_TYPE_DIR,
                    ),
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/#recycle",
                        displayName = "#recycle",
                        mimeType = Document.MIME_TYPE_DIR,
                    ),
                )

            ScannerTestDocumentsProvider.childrenMap["primary:Music/Rock"] =
                listOf(
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock/Hotel California.mp3",
                        displayName = "Hotel California.mp3",
                        mimeType = "audio/mpeg",
                        size = 9000000L,
                        lastModified = 1700000200L,
                    ),
                )

            val count = scanner.indexFolder(Uri.parse(TREE_URI_STRING))
            assertEquals(3, count)
            assertEquals(3, mediaTrackIndexDao.countByServerId(LOCAL_STORAGE_ID))

            // Verify Bohemian Rhapsody parsed heuristics
            val tracks = mediaTrackIndexDao.getTracksByServerId(LOCAL_STORAGE_ID)
            val queenTrack = tracks.firstOrNull { it.fileName.contains("Queen") }
            assertNotNull(queenTrack)
            assertEquals("Bohemian Rhapsody", queenTrack?.parsedTitle)
            assertEquals("Queen", queenTrack?.parsedArtist)
            assertEquals("FLAC", queenTrack?.format)

            // Verify Hotel California parent path
            val hotelTrack = tracks.firstOrNull { it.fileName.contains("Hotel") }
            assertNotNull(hotelTrack)
            assertEquals("Hotel California", hotelTrack?.parsedTitle)
            assertTrue(hotelTrack!!.parentPath.contains("/Rock/"))

            // Verify checkpoints were created
            val checkpoints = checkpointDao.getCheckpointsByServerId(LOCAL_STORAGE_ID)
            assertEquals(2, checkpoints.size)
        }

    @Test
    fun indexFolder_incrementalRescan_skipsUnchangedDirectory() =
        runTest {
            val rootDocId = "primary:Music"
            ScannerTestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock",
                        displayName = "Rock",
                        mimeType = Document.MIME_TYPE_DIR,
                        lastModified = 5555L,
                    ),
                )
            ScannerTestDocumentsProvider.childrenMap["primary:Music/Rock"] =
                listOf(
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Rock/Song.mp3",
                        displayName = "Song.mp3",
                        mimeType = "audio/mpeg",
                        size = 1000L,
                        lastModified = 5555L,
                    ),
                )

            // Initial scan: queries root and Rock (2 queries)
            scanner.indexFolder(Uri.parse(TREE_URI_STRING))
            val initialQueries = ScannerTestDocumentsProvider.queryCount
            assertEquals(2, initialQueries)

            // Rescan without force: Rock lastModified is unchanged, so Rock is skipped (1 query for root)
            scanner.indexFolder(Uri.parse(TREE_URI_STRING), force = false)
            assertEquals(initialQueries + 1, ScannerTestDocumentsProvider.queryCount)

            // Force rescan: Rock is re-queried (2 queries)
            scanner.indexFolder(Uri.parse(TREE_URI_STRING), force = true)
            assertEquals(initialQueries + 1 + 2, ScannerTestDocumentsProvider.queryCount)
        }

    @Test
    fun purgeFolder_removesIndexedTracksAndCheckpointsForMountedUri() =
        runTest {
            val rootDocId = "primary:Music"
            ScannerTestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Song.mp3",
                        displayName = "Song.mp3",
                        mimeType = "audio/mpeg",
                        size = 1000L,
                    ),
                )

            scanner.indexFolder(Uri.parse(TREE_URI_STRING))
            assertEquals(1, mediaTrackIndexDao.countByServerId(LOCAL_STORAGE_ID))
            assertEquals(1, checkpointDao.getCheckpointsByServerId(LOCAL_STORAGE_ID).size)

            scanner.purgeFolder(Uri.parse(TREE_URI_STRING))
            assertEquals(0, mediaTrackIndexDao.countByServerId(LOCAL_STORAGE_ID))
            assertEquals(0, checkpointDao.getCheckpointsByServerId(LOCAL_STORAGE_ID).size)
        }

    @Test
    fun indexAllMountedFolders_indexesEveryMountedFolder() =
        runTest {
            val rootDocId = "primary:Music"
            ScannerTestDocumentsProvider.childrenMap[rootDocId] =
                listOf(
                    ScannerTestDocumentsProvider.DocEntry(
                        docId = "primary:Music/Song.flac",
                        displayName = "Song.flac",
                        mimeType = "audio/flac",
                        size = 1000L,
                    ),
                )

            folderDao.insertFolder(
                LocalStorageFolderEntity(
                    uriString = TREE_URI_STRING,
                    displayName = "Music",
                    dateAdded = System.currentTimeMillis(),
                ),
            )

            val count = scanner.indexAllMountedFolders()
            assertEquals(1, count)
            assertEquals(1, mediaTrackIndexDao.countByServerId(LOCAL_STORAGE_ID))
        }
}
