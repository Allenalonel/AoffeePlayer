package com.webdav.player.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.LocalStorageFolderDao
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.metadata.MediaFilenameParser
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URLEncoder

/**
 * Fast indexing engine for Android Storage Access Framework (SAF) local media storage.
 * Traverses mounted document trees using cursor projection on DocumentsContract child documents,
 * populating media_track_index and recording checkpoints for incremental rescans.
 */
class LocalStorageIndexScanner(
    private val context: Context,
    private val mediaTrackIndexDao: MediaTrackIndexDao,
    private val checkpointDao: DirectorySyncCheckpointDao,
    private val localStorageFolderDao: LocalStorageFolderDao? = null,
    private val contentResolver: ContentResolver = context.contentResolver,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    companion object {
        private val NOISE_DIRECTORIES =
            setOf(
                ".@eaDir",
                "#recycle",
                ".Trash",
                "LOST.DIR",
                ".git",
                ".thumbnails",
                ".cache",
                "Android",
            )

        private val PROJECTION =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE,
                DocumentsContract.Document.COLUMN_LAST_MODIFIED,
            )
    }

    private data class DirectoryWorkItem(
        val docId: String,
        val path: String,
        val lastModified: Long,
    )

    private fun isNoiseDirectory(name: String): Boolean {
        if (name.startsWith(".") && name != "." && name != "..") return true
        if (NOISE_DIRECTORIES.contains(name)) return true
        if (name.startsWith(".Trash-", ignoreCase = true)) return true
        return false
    }

    private fun resolveRootDocId(treeUri: Uri): String =
        try {
            DocumentsContract.getTreeDocumentId(treeUri)
        } catch (_: Exception) {
            null
        } ?: try {
            DocumentsContract.getDocumentId(treeUri)
        } catch (_: Exception) {
            null
        } ?: (treeUri.lastPathSegment ?: "root")

    suspend fun indexFolder(
        treeUri: Uri,
        force: Boolean = false,
    ): Int =
        withContext(ioDispatcher) {
            val rootDocId = resolveRootDocId(treeUri)
            val encodedTreeUri = URLEncoder.encode(treeUri.toString(), "UTF-8")
            val rootPath = "/local/$encodedTreeUri/"

            val queue = ArrayDeque<DirectoryWorkItem>()
            queue.add(DirectoryWorkItem(docId = rootDocId, path = rootPath, lastModified = 0L))

            var totalIndexedTracks = 0

            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()

                if (!force && current.lastModified > 0L) {
                    val checkpoint = checkpointDao.getCheckpoint(LOCAL_STORAGE_ID, current.path)
                    if (checkpoint != null && checkpoint.lastModified == current.lastModified) {
                        continue
                    }
                }

                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, current.docId)
                val discoveredTracks = mutableListOf<MediaTrackIndexEntity>()

                try {
                    contentResolver.query(childrenUri, PROJECTION, Bundle.EMPTY, null)?.use { cursor ->
                        val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                        val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                        val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                        val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                        val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                        while (cursor.moveToNext()) {
                            val docId = if (idIdx >= 0) cursor.getString(idIdx) else null ?: continue
                            val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null ?: continue
                            val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null ?: ""
                            val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                            val lastModified = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L

                            if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                                if (isNoiseDirectory(displayName)) continue
                                val subPath = "${current.path}$displayName/"
                                queue.add(DirectoryWorkItem(docId = docId, path = subPath, lastModified = lastModified))
                            } else {
                                val ext = displayName.substringAfterLast('.', "").lowercase()
                                val audioFormat = AudioFormat.fromExtension(ext)
                                val isCue = ext == "cue"

                                if (audioFormat != null || isCue) {
                                    val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                                    val parsed = MediaFilenameParser.parse(displayName)
                                    val entity =
                                        MediaTrackIndexEntity(
                                            serverId = LOCAL_STORAGE_ID,
                                            remotePath = docUri.toString(),
                                            fileName = displayName,
                                            fileSize = size,
                                            lastModified = lastModified,
                                            format = audioFormat?.extension?.uppercase() ?: if (isCue) "CUE" else "MP3",
                                            isCue = isCue,
                                            parentPath = current.path,
                                            parsedTitle = parsed.title,
                                            parsedArtist = parsed.artist,
                                        )
                                    discoveredTracks.add(entity)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Graceful handling of directory read failures without failing the entire scan
                }

                if (discoveredTracks.isNotEmpty()) {
                    mediaTrackIndexDao.insertAll(discoveredTracks)
                    totalIndexedTracks += discoveredTracks.size
                }

                checkpointDao.insertOrUpdate(
                    DirectorySyncCheckpointEntity(
                        serverId = LOCAL_STORAGE_ID,
                        path = current.path,
                        lastModified = current.lastModified,
                        etag = null,
                        lastScannedMs = System.currentTimeMillis(),
                    ),
                )
            }

            totalIndexedTracks
        }

    suspend fun indexAllMountedFolders(force: Boolean = false): Int =
        withContext(ioDispatcher) {
            val folders = localStorageFolderDao?.getAllFoldersFlow()?.first() ?: emptyList()
            var count = 0
            for (folder in folders) {
                count += indexFolder(Uri.parse(folder.uriString), force)
            }
            count
        }

    suspend fun purgeFolder(treeUri: Uri) =
        withContext(ioDispatcher) {
            val encoded = URLEncoder.encode(treeUri.toString(), "UTF-8")
            val prefix = "/local/$encoded/"
            mediaTrackIndexDao.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, prefix)
            checkpointDao.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, prefix)
        }
}
