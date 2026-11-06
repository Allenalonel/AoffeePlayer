package com.webdav.player.data.repository

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.DocumentsContract
import com.webdav.player.data.cue.CueParser
import com.webdav.player.domain.model.CueAlbumItem
import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LocalStorageFolder
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.repository.ServerRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Resolves local directories mounted via Android's Storage Access Framework (SAF).
 *
 * Enumerate tree URIs using DocumentsContract child documents cursor.
 * For root path "/": emits RemoteDirectory of all mounted folders.
 * For "/local/<encodedUri>/...": resolves children into directories, audio files, CUE sheets,
 * companion lyrics (.lrc), and companion artwork (cover.jpg/png, folder.jpg/png).
 */
class LocalStorageDirectoryResolver(
    private val context: Context,
    private val serverRepository: ServerRepository,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val contentResolver: ContentResolver = context.contentResolver,
) {
    suspend fun resolve(path: String): DirectoryContent =
        withContext(ioDispatcher) {
            val normalizedPath = RemoteDirectory.normalizePath(path)
            val folders = serverRepository.getMountedLocalFolders().first()

            if (normalizedPath == "/" || normalizedPath == "/local/") {
                return@withContext resolveRoot(folders)
            }

            if (!normalizedPath.startsWith("/local/")) {
                throw IllegalArgumentException("Unsupported local storage path: $path")
            }

            val relative = normalizedPath.removePrefix("/local/").trim('/')
            val segments = relative.split('/').filter { it.isNotBlank() }
            if (segments.isEmpty()) {
                return@withContext resolveRoot(folders)
            }

            val encodedTreeUri = segments[0]
            val treeUriString = URLDecoder.decode(encodedTreeUri, "UTF-8")
            val treeUri = Uri.parse(treeUriString)
            val subpathSegments = segments.drop(1)

            val mountedFolder = folders.firstOrNull { it.uriString == treeUriString }
            val rootDocId = resolveRootDocId(treeUri)

            var currentDocId = rootDocId
            for (segment in subpathSegments) {
                val decoded = URLDecoder.decode(segment, "UTF-8")
                val nextDocId = findChildDirectoryDocId(treeUri, currentDocId, decoded)
                currentDocId = nextDocId ?: segment
            }
            val targetDocId = currentDocId

            val dirName =
                if (subpathSegments.isEmpty()) {
                    mountedFolder?.displayName ?: treeUri.lastPathSegment ?: "本地存储"
                } else {
                    URLDecoder.decode(subpathSegments.last(), "UTF-8")
                }

            val subDirs = mutableListOf<RemoteDirectory>()
            val files = mutableListOf<RemoteFile>()

            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, targetDocId)
            val projection =
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                    DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                )

            try {
                contentResolver.query(childrenUri, projection, Bundle.EMPTY, null)?.use { cursor ->
                    val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    val sizeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
                    val modIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                    while (cursor.moveToNext()) {
                        val docId = if (idIdx >= 0) cursor.getString(idIdx) else null ?: continue
                        val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null ?: "Unknown"
                        val mimeType = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null ?: ""
                        val size = if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) cursor.getLong(sizeIdx) else 0L
                        val lastModified = if (modIdx >= 0 && !cursor.isNull(modIdx)) cursor.getLong(modIdx) else 0L

                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                            val childPath = "${normalizedPath}$displayName/"
                            subDirs.add(
                                RemoteDirectory(
                                    path = childPath,
                                    name = displayName,
                                    subDirectories = emptyList(),
                                    files = emptyList(),
                                    serverId = LOCAL_STORAGE_ID,
                                ),
                            )
                        } else {
                            val docUri = DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
                            val remoteFile =
                                RemoteFile(
                                    name = displayName,
                                    path = docUri.toString(),
                                    size = size,
                                    lastModified = if (lastModified > 0L) lastModified.toString() else null,
                                    contentType = mimeType.ifBlank { null },
                                )
                            files.add(remoteFile)
                        }
                    }
                }
            } catch (e: Exception) {
                throw IllegalStateException("Failed to query child documents for path $normalizedPath: ${e.message}", e)
            }

            val sortedSubDirs = subDirs.sortedBy { it.name.lowercase() }
            val sortedFiles = files.sortedBy { it.name.lowercase() }

            val directory =
                RemoteDirectory(
                    path = normalizedPath,
                    name = dirName,
                    subDirectories = sortedSubDirs,
                    files = sortedFiles,
                    serverId = LOCAL_STORAGE_ID,
                )

            val cueFiles = sortedFiles.filter { it.isCue }
            val audioFiles = sortedFiles.filter { it.isAudio }
            val cueAlbums = resolveCueAlbums(cueFiles, audioFiles)

            val imageFiles =
                sortedFiles.filter { file ->
                    val ext = file.name.substringAfterLast('.', "").lowercase()
                    ext in listOf("jpg", "jpeg", "png", "webp")
                }
            val companionCover =
                imageFiles.firstOrNull { file ->
                    val base = file.name.substringBeforeLast('.').lowercase()
                    base in listOf("cover", "folder", "front", "album")
                } ?: imageFiles.firstOrNull()

            val metadataMap = mutableMapOf<String, TrackMetadata>()
            for (audio in audioFiles) {
                val audioBase = audio.name.substringBeforeLast('.')
                val companionLrc =
                    sortedFiles.firstOrNull {
                        it.isLyrics && it.name.substringBeforeLast('.').equals(audioBase, ignoreCase = true)
                    }
                metadataMap[audio.path] =
                    TrackMetadata(
                        serverId = LOCAL_STORAGE_ID,
                        remotePath = audio.path,
                        title = audioBase,
                        artist = null,
                        album = null,
                        durationMs = 0L,
                        coverThumbnailPath = companionCover?.path,
                        lyrics = companionLrc?.path,
                    )
            }

            DirectoryContent(
                directory = directory,
                cueAlbums = cueAlbums,
                metadataMap = metadataMap,
                isEnriching = false,
            )
        }

    suspend fun resolveDirectory(path: String): RemoteDirectory = resolve(path).directory

    private fun resolveRoot(folders: List<LocalStorageFolder>): DirectoryContent {
        val subDirs =
            folders.map { folder ->
                val encoded = URLEncoder.encode(folder.uriString, "UTF-8")
                RemoteDirectory(
                    path = "/local/$encoded/",
                    name = folder.displayName,
                    subDirectories = emptyList(),
                    files = emptyList(),
                    serverId = LOCAL_STORAGE_ID,
                )
            }
        val rootDir =
            RemoteDirectory(
                path = "/",
                name = "本地存储",
                subDirectories = subDirs,
                files = emptyList(),
                serverId = LOCAL_STORAGE_ID,
            )
        return DirectoryContent(
            directory = rootDir,
            cueAlbums = emptyList(),
            metadataMap = emptyMap(),
            isEnriching = false,
        )
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

    private fun findChildDirectoryDocId(
        treeUri: Uri,
        parentDocId: String,
        targetName: String,
    ): String? {
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, parentDocId)
        val projection =
            arrayOf(
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
            )
        return try {
            contentResolver.query(childrenUri, projection, Bundle.EMPTY, null)?.use { cursor ->
                val idIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE)

                while (cursor.moveToNext()) {
                    val mime = if (mimeIdx >= 0) cursor.getString(mimeIdx) else null
                    val isDir = mime == DocumentsContract.Document.MIME_TYPE_DIR
                    val displayName = if (nameIdx >= 0) cursor.getString(nameIdx) else null
                    if (isDir && displayName.equals(targetName, ignoreCase = true)) {
                        return@use if (idIdx >= 0) cursor.getString(idIdx) else null
                    }
                }
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun resolveCueAlbums(
        cueFiles: List<RemoteFile>,
        audioFiles: List<RemoteFile>,
    ): List<CueAlbumItem> {
        if (cueFiles.isEmpty()) return emptyList()

        return cueFiles.map { cueFile ->
            val cueContent =
                try {
                    contentResolver.openInputStream(Uri.parse(cueFile.path))?.bufferedReader(Charsets.UTF_8)?.use {
                        it.readText()
                    }
                } catch (_: Exception) {
                    null
                }

            if (cueContent.isNullOrBlank()) {
                CueAlbumItem(
                    cueFile = cueFile,
                    audioFile = null,
                    tracks = emptyList(),
                    isExpanded = false,
                    isLoading = false,
                    errorMessage = "无法读取 CUE 文件内容",
                )
            } else {
                val referencedFiles = CueParser.extractReferencedFiles(cueContent)
                val matchedAudio =
                    findMatchingAudioFile(
                        cueFile = cueFile,
                        audioFiles = audioFiles,
                        referencedFileNames = referencedFiles,
                        totalCueFilesCount = cueFiles.size,
                    )

                if (matchedAudio == null) {
                    CueAlbumItem(
                        cueFile = cueFile,
                        audioFile = null,
                        tracks = emptyList(),
                        isExpanded = false,
                        isLoading = false,
                        errorMessage = "未找到 CUE 指向的目标音频文件",
                    )
                } else {
                    val tracks =
                        CueParser.parse(
                            content = cueContent,
                            parentAudioPath = matchedAudio.path,
                            allowMissingFileTag = true,
                        )

                    if (tracks.isEmpty()) {
                        CueAlbumItem(
                            cueFile = cueFile,
                            audioFile = matchedAudio,
                            tracks = emptyList(),
                            isExpanded = false,
                            isLoading = false,
                            errorMessage = "CUE 文件未包含有效分轨",
                        )
                    } else {
                        CueAlbumItem(
                            cueFile = cueFile,
                            audioFile = matchedAudio,
                            tracks = tracks,
                            isExpanded = false,
                            isLoading = false,
                            errorMessage = null,
                        )
                    }
                }
            }
        }
    }

    private fun findMatchingAudioFile(
        cueFile: RemoteFile,
        audioFiles: List<RemoteFile>,
        referencedFileNames: List<String> = emptyList(),
        totalCueFilesCount: Int = 1,
    ): RemoteFile? {
        if (audioFiles.isEmpty()) return null

        for (ref in referencedFileNames) {
            val cleanRef = ref.trim().replace('\\', '/').substringAfterLast('/')
            val exact = audioFiles.firstOrNull { it.name.equals(cleanRef, ignoreCase = true) }
            if (exact != null) return exact

            val refBase = cleanRef.substringBeforeLast('.')
            val baseMatch =
                audioFiles.firstOrNull {
                    it.name.substringBeforeLast('.').equals(refBase, ignoreCase = true)
                }
            if (baseMatch != null) return baseMatch
        }

        val cueBase = cueFile.name.substringBeforeLast('.')
        val sameBaseAudio =
            audioFiles.firstOrNull {
                it.name.substringBeforeLast('.').equals(cueBase, ignoreCase = true)
            }
        if (sameBaseAudio != null) return sameBaseAudio

        if (referencedFileNames.isEmpty() && totalCueFilesCount == 1 && audioFiles.size == 1) {
            return audioFiles.first()
        }

        if (referencedFileNames.isEmpty()) {
            val prefixMatch =
                audioFiles.firstOrNull {
                    val audioBase = it.name.substringBeforeLast('.')
                    cueBase.startsWith(audioBase, ignoreCase = true) || audioBase.startsWith(cueBase, ignoreCase = true)
                }
            if (prefixMatch != null) return prefixMatch
        }

        return null
    }
}
