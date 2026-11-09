package com.webdav.player.data.repository

import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFileType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf

/**
 * Internal collaborator coordinating pure B-Tree search over Room indices,
 * directory checkpoints, and metadata caches with strict source isolation
 * and deduplication.
 */
class MediaLibrarySearchCoordinator(
    private val mediaTrackIndexDao: MediaTrackIndexDao?,
    private val directorySyncCheckpointDao: DirectorySyncCheckpointDao?,
    private val trackMetadataDao: TrackMetadataDao?,
) {
    fun search(
        query: String,
        serverId: Long? = null,
    ): Flow<LibrarySearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return flowOf(LibrarySearchResult())
        }

        val indexFlow: Flow<List<MediaTrackIndexEntity>> =
            mediaTrackIndexDao?.search(cleanQuery, serverId) ?: flowOf(emptyList())
        val checkpointFlow: Flow<List<DirectorySyncCheckpointEntity>> =
            directorySyncCheckpointDao?.searchCheckpoints(cleanQuery, serverId) ?: flowOf(emptyList())
        val metaFlow: Flow<List<TrackMetadataEntity>> =
            trackMetadataDao?.searchMetadataFlow(cleanQuery, serverId) ?: flowOf(emptyList())

        return combine(
            indexFlow,
            checkpointFlow,
            metaFlow,
        ) { indexedTracks, checkpoints, metaEntities ->
            val metadataMap = metaEntities.associateBy { "${it.serverId}:${it.remotePath}" }
            val matchedDirs = mutableMapOf<String, RemoteDirectory>()
            val matchedTracks = mutableMapOf<String, AudioTrack>()

            fun isAccepted(
                itemServerId: Long,
                itemPath: String,
            ): Boolean {
                val isLocal =
                    itemServerId == LOCAL_STORAGE_ID ||
                        itemPath.startsWith("content://") ||
                        itemPath.startsWith("/local/")
                return when {
                    serverId == null -> true
                    serverId == LOCAL_STORAGE_ID -> isLocal
                    else -> !isLocal && itemServerId == serverId
                }
            }

            fun trackDedupeKey(
                itemServerId: Long,
                itemRemotePath: String,
            ): String {
                val isLocal =
                    itemServerId == LOCAL_STORAGE_ID ||
                        itemRemotePath.startsWith("content://") ||
                        itemRemotePath.startsWith("/local/")
                return if (isLocal) "local:$itemRemotePath" else "$itemServerId:$itemRemotePath"
            }

            fun dirDedupeKey(
                itemServerId: Long,
                itemDirPath: String,
            ): String {
                val isLocal =
                    itemServerId == LOCAL_STORAGE_ID ||
                        itemDirPath.startsWith("/local/")
                return if (isLocal) "local:$itemDirPath" else "$itemServerId:$itemDirPath"
            }

            for (cp in checkpoints) {
                if (!isAccepted(cp.serverId, cp.path)) continue
                if (isMatch(cleanQuery, cp.path)) {
                    val dirName = cp.path.trimEnd('/').substringAfterLast('/')
                    if (dirName.isNotBlank()) {
                        val key = dirDedupeKey(cp.serverId, cp.path)
                        matchedDirs[key] =
                            RemoteDirectory(
                                path = cp.path,
                                name = dirName,
                                subDirectories = emptyList(),
                                files = emptyList(),
                                serverId = if (cp.path.startsWith("/local/")) LOCAL_STORAGE_ID else cp.serverId,
                            )
                    }
                }
            }

            for (entity in indexedTracks) {
                if (!isAccepted(entity.serverId, entity.remotePath)) continue

                val metaKey = "${entity.serverId}:${entity.remotePath}"
                val effectiveServerId =
                    if (entity.remotePath.startsWith("content://") ||
                        entity.remotePath.startsWith("/local/") ||
                        entity.serverId == LOCAL_STORAGE_ID
                    ) {
                        LOCAL_STORAGE_ID
                    } else {
                        entity.serverId
                    }
                val meta = metadataMap[metaKey] ?: metadataMap["$LOCAL_STORAGE_ID:${entity.remotePath}"]
                val format =
                    AudioFormat.fromExtension(entity.format)
                        ?: AudioFormat.fromFileName(entity.fileName)
                        ?: AudioFormat.MP3
                val track =
                    createAudioTrack(
                        serverId = effectiveServerId,
                        remotePath = entity.remotePath,
                        fileName = entity.fileName,
                        fileSize = entity.fileSize,
                        format = format,
                        metadata = meta,
                        titleFallback = entity.parsedTitle,
                        artistFallback = entity.parsedArtist,
                    )
                matchedTracks[trackDedupeKey(effectiveServerId, entity.remotePath)] = track

                if (entity.parentPath.contains(cleanQuery, ignoreCase = true) &&
                    isAccepted(entity.serverId, entity.parentPath)
                ) {
                    val dirName = entity.parentPath.trimEnd('/').substringAfterLast('/')
                    val dirKey = dirDedupeKey(entity.serverId, entity.parentPath)
                    if (!matchedDirs.containsKey(dirKey) && dirName.isNotBlank()) {
                        matchedDirs[dirKey] =
                            RemoteDirectory(
                                path = entity.parentPath,
                                name = dirName,
                                subDirectories = emptyList(),
                                files = emptyList(),
                                serverId = if (entity.parentPath.startsWith("/local/")) LOCAL_STORAGE_ID else entity.serverId,
                            )
                    }
                }
            }

            for (meta in metaEntities) {
                if (!isAccepted(meta.serverId, meta.remotePath)) continue
                val dedupeKey = trackDedupeKey(meta.serverId, meta.remotePath)
                if (!matchedTracks.containsKey(dedupeKey) &&
                    isMatch(cleanQuery, meta.title, meta.artist, meta.album, meta.remotePath)
                ) {
                    val fileName = meta.remotePath.substringAfterLast('/')
                    val format = (RemoteFileType.fromFileName(fileName) as? RemoteFileType.Audio)?.format ?: AudioFormat.MP3
                    val effectiveServerId =
                        if (meta.remotePath.startsWith("content://") ||
                            meta.remotePath.startsWith("/local/") ||
                            meta.serverId == LOCAL_STORAGE_ID
                        ) {
                            LOCAL_STORAGE_ID
                        } else {
                            meta.serverId
                        }
                    matchedTracks[dedupeKey] =
                        createAudioTrack(
                            serverId = effectiveServerId,
                            remotePath = meta.remotePath,
                            fileName = fileName,
                            fileSize = 0L,
                            format = format,
                            metadata = meta,
                        )
                }
            }

            LibrarySearchResult(
                matchedDirectories = matchedDirs.values.toList(),
                matchedTracks = matchedTracks.values.toList(),
            )
        }
    }

    private fun isMatch(
        query: String,
        vararg candidates: String?,
    ): Boolean = candidates.any { it?.contains(query, ignoreCase = true) == true }

    private fun createAudioTrack(
        serverId: Long,
        remotePath: String,
        fileName: String,
        fileSize: Long,
        format: AudioFormat,
        metadata: TrackMetadataEntity?,
        titleFallback: String? = null,
        artistFallback: String? = null,
        albumFallback: String? = null,
    ): AudioTrack =
        AudioTrack(
            id = "$serverId:$remotePath",
            serverId = serverId,
            remotePath = remotePath,
            title = metadata?.title?.takeIf { it.isNotBlank() } ?: titleFallback?.takeIf { it.isNotBlank() } ?: fileName,
            artist = metadata?.artist?.takeIf { it.isNotBlank() } ?: artistFallback,
            album = metadata?.album?.takeIf { it.isNotBlank() } ?: albumFallback,
            durationMs = metadata?.durationMs ?: 0L,
            size = fileSize,
            format = format,
            coverThumbnailPath = metadata?.coverThumbnailPath,
        )
}
