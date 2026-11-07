package com.webdav.player.data.cue

import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.metadata.MediaFilenameParser
import com.webdav.player.domain.cue.CueSheetResolver
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * Resolves CUE sheets into virtual track entries in [MediaTrackIndexEntity]
 * so that single-file rips and cues are directly indexed and searchable.
 */
open class CueVirtualTrackIndexer(
    private val cueSheetResolver: CueSheetResolver,
    private val mediaTrackIndexDao: MediaTrackIndexDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    open suspend fun indexDirectoryCues(
        server: WebDavServer,
        directory: RemoteDirectory,
        parentDurationsByPath: Map<String, Long> = emptyMap(),
    ): List<MediaTrackIndexEntity> =
        withContext(ioDispatcher) {
            val cueFiles = directory.files.filter { it.isCue }
            val audioFiles = directory.files.filter { it.isAudio }
            if (cueFiles.isEmpty() || audioFiles.isEmpty()) {
                return@withContext emptyList()
            }

            val albums = cueSheetResolver.resolveDirectoryCueAlbums(server, directory, parentDurationsByPath)
            if (albums.isEmpty()) {
                return@withContext emptyList()
            }

            val indexedEntities = mutableListOf<MediaTrackIndexEntity>()

            for (album in albums) {
                val matchedAudio = album.audioFile ?: continue
                if (album.tracks.isEmpty()) continue

                val lastModified = parseLastModified(matchedAudio.lastModified)
                val formatExt =
                    (matchedAudio.fileType as? RemoteFileType.Audio)?.format?.extension?.uppercase()
                        ?: matchedAudio.name
                            .substringAfterLast('.', "")
                            .uppercase()
                            .ifBlank { "FLAC" }
                val parentArtist = MediaFilenameParser.parse(matchedAudio.name).artist

                for (vt in album.tracks) {
                    val remotePath = "${matchedAudio.path}#cue_${vt.trackNumber}"
                    val fileName = vt.title.ifBlank { "${matchedAudio.name}#cue_${vt.trackNumber}" }
                    val parsedArtist = vt.performer?.takeIf { it.isNotBlank() } ?: parentArtist

                    val entity =
                        MediaTrackIndexEntity(
                            serverId = server.id,
                            remotePath = remotePath,
                            fileName = fileName,
                            fileSize = matchedAudio.size,
                            lastModified = lastModified,
                            format = formatExt,
                            isCue = true,
                            parentPath = directory.path,
                            parsedTitle = vt.title,
                            parsedArtist = parsedArtist,
                        )
                    indexedEntities.add(entity)
                }
            }

            if (indexedEntities.isNotEmpty()) {
                mediaTrackIndexDao.upsertTracks(indexedEntities)
            }

            indexedEntities
        }

    private fun parseLastModified(lastModifiedStr: String?): Long {
        if (lastModifiedStr.isNullOrBlank()) return 0L
        lastModifiedStr.toLongOrNull()?.let { return it }
        return try {
            val format = SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss zzz", Locale.US)
            format.parse(lastModifiedStr)?.time ?: 0L
        } catch (_: Exception) {
            0L
        }
    }
}
