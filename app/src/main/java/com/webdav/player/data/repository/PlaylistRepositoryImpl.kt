package com.webdav.player.data.repository

import com.webdav.player.data.local.PlaylistDao
import com.webdav.player.data.local.PlaylistEntity
import com.webdav.player.data.local.PlaylistTrackEntity
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.Playlist
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.repository.PlaylistRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import com.webdav.player.domain.storage.StorageReachabilityTracker
import com.webdav.player.domain.storage.TrackKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.File

class PlaylistRepositoryImpl(
    private val playlistDao: PlaylistDao,
    private val trackMetadataRepository: TrackMetadataRepository? = null,
    private val storageReachabilityTracker: StorageReachabilityTracker? = null,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
    private val coroutineScope: CoroutineScope =
        CoroutineScope(
            SupervisorJob() + Dispatchers.IO + kotlinx.coroutines.CoroutineExceptionHandler { _, _ -> },
        ),
) : PlaylistRepository {
    private val inFlightCoverHealing = java.util.concurrent.ConcurrentHashMap<Long, Job>()

    private fun isCoverDeadOnDisk(path: String?): Boolean {
        if (path.isNullOrBlank()) return false
        if (path.startsWith("content://")) return false
        val clean = if (path.startsWith("file://")) path.removePrefix("file://") else path
        val file = File(clean)
        return file.isAbsolute && !file.exists() && (file.path.contains("covers") || file.path.contains("cache"))
    }

    private fun triggerBackgroundCoverHealing(
        trackEntryId: Long,
        serverId: Long,
        remotePath: String,
    ) {
        if (trackMetadataRepository == null) return
        val isLocalTrack =
            serverId == LOCAL_STORAGE_ID ||
                remotePath.startsWith("content://") ||
                remotePath.startsWith("file://") ||
                remotePath.startsWith("/local/")
        if (!isLocalTrack) return
        val activeJob = inFlightCoverHealing[trackEntryId]
        if (activeJob != null && activeJob.isActive) return

        val effectiveServerId = LOCAL_STORAGE_ID
        val job =
            coroutineScope.launch {
                try {
                    val cached = trackMetadataRepository.getCachedMetadata(effectiveServerId, remotePath)
                    if (!cached?.coverThumbnailPath.isNullOrBlank()) {
                        playlistDao.updateTrackCover(trackEntryId, cached!!.coverThumbnailPath)
                        return@launch
                    }
                    val server = WebDavServer.createLocalStorage()
                    val fileName = remotePath.substringAfterLast('/').ifBlank { "track" }
                    val file = RemoteFile(name = fileName, path = remotePath)
                    val resolved = trackMetadataRepository.resolveSingleTrackMetadata(server, file)
                    if (!resolved.coverThumbnailPath.isNullOrBlank()) {
                        playlistDao.updateTrackCover(trackEntryId, resolved.coverThumbnailPath)
                    }
                } catch (_: Throwable) {
                    // Ignore transient errors during background self-healing
                } finally {
                    inFlightCoverHealing.remove(trackEntryId)
                }
            }
        inFlightCoverHealing[trackEntryId] = job
    }

    override fun getAllPlaylistsFlow(): Flow<List<Playlist>> =
        playlistDao.getAllPlaylistsWithTrackCountFlow().map { list ->
            list.map { it.toDomain() }
        }

    override suspend fun getAllPlaylists(): List<Playlist> = playlistDao.getAllPlaylistsWithTrackCount().map { it.toDomain() }

    override fun getPlaylistFlow(playlistId: Long): Flow<Playlist?> =
        playlistDao.getPlaylistWithTrackCountFlow(playlistId).map { it?.toDomain() }

    override suspend fun getPlaylist(playlistId: Long): Playlist? = playlistDao.getPlaylistWithTrackCount(playlistId)?.toDomain()

    override suspend fun createPlaylist(name: String): Long {
        val trimmed = name.trim()
        require(trimmed.isNotEmpty()) { "歌单名称不能为空" }
        val existing = playlistDao.getPlaylistByName(trimmed)
        require(existing == null) { "歌单 “$trimmed” 已存在" }

        val now = timeProvider()
        val entity =
            PlaylistEntity(
                name = trimmed,
                createdAtMs = now,
                updatedAtMs = now,
            )
        return playlistDao.insertPlaylist(entity)
    }

    override suspend fun renamePlaylist(
        playlistId: Long,
        newName: String,
    ) {
        val trimmed = newName.trim()
        require(trimmed.isNotEmpty()) { "歌单名称不能为空" }
        val existing = playlistDao.getPlaylistByName(trimmed)
        if (existing != null && existing.id != playlistId) {
            throw IllegalArgumentException("歌单 “$trimmed” 已存在")
        }

        val now = timeProvider()
        playlistDao.renamePlaylist(playlistId, trimmed, now)
    }

    override suspend fun deletePlaylist(playlistId: Long) {
        playlistDao.deleteTracksByPlaylistId(playlistId)
        playlistDao.deletePlaylistById(playlistId)
    }

    override suspend fun isPlaylistNameExists(
        name: String,
        excludePlaylistId: Long?,
    ): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        val existing = playlistDao.getPlaylistByName(trimmed)
        return existing != null && (excludePlaylistId == null || existing.id != excludePlaylistId)
    }

    override fun getTracksForPlaylistFlow(playlistId: Long): Flow<List<PlaylistTrackEntry>> {
        val inactiveFlow = storageReachabilityTracker?.inactiveTracks ?: flowOf(emptyMap())
        val serverFlow = storageReachabilityTracker?.serverReachability ?: flowOf(emptyMap())
        val reasonsFlow = storageReachabilityTracker?.serverReasons ?: flowOf(emptyMap())

        return combine(
            playlistDao.getTracksForPlaylistFlow(playlistId),
            inactiveFlow,
            serverFlow,
            reasonsFlow,
        ) { entities, inactiveMap, serverReachability, serverReasons ->
            reconcilePlaylistTracks(entities, inactiveMap, serverReachability, serverReasons)
        }
    }

    override suspend fun getTracksForPlaylist(playlistId: Long): List<PlaylistTrackEntry> {
        val entities = playlistDao.getTracksForPlaylist(playlistId)
        val inactiveMap = storageReachabilityTracker?.inactiveTracks?.value ?: emptyMap()
        val serverReachability = storageReachabilityTracker?.serverReachability?.value ?: emptyMap()
        val serverReasons = storageReachabilityTracker?.serverReasons?.value ?: emptyMap()
        return reconcilePlaylistTracks(entities, inactiveMap, serverReachability, serverReasons)
    }

    private suspend fun reconcilePlaylistTracks(
        entities: List<PlaylistTrackEntity>,
        inactiveMap: Map<TrackKey, InactiveReason>,
        serverReachability: Map<Long, Boolean>,
        serverReasons: Map<Long, InactiveReason>,
    ): List<PlaylistTrackEntry> {
        val seenKeys = mutableSetOf<TrackKey>()
        val duplicateIds = mutableListOf<Long>()
        val uniqueEntities = mutableListOf<PlaylistTrackEntity>()
        for (entity in entities) {
            val key = TrackKey(entity.serverId, entity.remotePath)
            if (seenKeys.add(key)) {
                uniqueEntities.add(entity)
            } else {
                duplicateIds.add(entity.id)
            }
        }
        if (duplicateIds.isNotEmpty()) {
            coroutineScope.launch {
                try {
                    playlistDao.deleteTracksByIds(duplicateIds)
                } catch (_: Throwable) {
                    // Ignore transient errors when database is closing
                }
            }
        }

        val reconciled =
            uniqueEntities.map { entity ->
                val domain = entity.toDomain()
                val trackReason = inactiveMap[TrackKey(domain.serverId, domain.remotePath)]
                val isServerUnreachable = serverReachability[domain.serverId] == false

                when {
                    trackReason != null -> {
                        domain.copy(isInactive = true, inactiveReason = trackReason)
                    }

                    isServerUnreachable -> {
                        val serverReason = serverReasons[domain.serverId] ?: InactiveReason.SERVER_UNREACHABLE
                        domain.copy(isInactive = true, inactiveReason = serverReason)
                    }

                    else -> {
                        domain.copy(isInactive = false, inactiveReason = null)
                    }
                }
            }

        return if (trackMetadataRepository != null) {
            reconciled.map { track ->
                val effectiveServerId =
                    if (track.serverId == LOCAL_STORAGE_ID ||
                        track.remotePath.startsWith("content://") ||
                        track.remotePath.startsWith("file://") ||
                        track.remotePath.startsWith("/local/")
                    ) {
                        LOCAL_STORAGE_ID
                    } else {
                        track.serverId
                    }
                val cachedMeta = trackMetadataRepository.getCachedMetadata(effectiveServerId, track.remotePath)
                val isDead = isCoverDeadOnDisk(track.coverThumbnailPath)

                when {
                    cachedMeta?.coverThumbnailPath != null && cachedMeta.coverThumbnailPath != track.coverThumbnailPath -> {
                        coroutineScope.launch {
                            try {
                                playlistDao.updateTrackCover(track.id, cachedMeta.coverThumbnailPath)
                            } catch (_: Throwable) {
                                // Ignore transient errors when database is closing
                            }
                        }
                        track.copy(coverThumbnailPath = cachedMeta.coverThumbnailPath)
                    }

                    track.coverThumbnailPath.isNullOrBlank() || isDead -> {
                        if (cachedMeta?.coverThumbnailPath != null) {
                            coroutineScope.launch {
                                try {
                                    playlistDao.updateTrackCover(track.id, cachedMeta.coverThumbnailPath)
                                } catch (_: Throwable) {
                                    // Ignore transient errors when database is closing
                                }
                            }
                            track.copy(coverThumbnailPath = cachedMeta.coverThumbnailPath)
                        } else {
                            if (!track.isInactive) {
                                triggerBackgroundCoverHealing(track.id, effectiveServerId, track.remotePath)
                            }
                            track.copy(coverThumbnailPath = null)
                        }
                    }

                    else -> {
                        track
                    }
                }
            }
        } else {
            reconciled
        }
    }

    override suspend fun addTrackToPlaylist(
        playlistId: Long,
        track: AudioTrack,
    ): Long {
        val existing =
            playlistDao.findTrackInPlaylist(
                playlistId = playlistId,
                serverId = track.serverId,
                remotePath = track.remotePath,
            )
        if (existing != null) {
            return -1L
        }
        val currentMaxSort = playlistDao.getMaxSortOrder(playlistId) ?: -1
        val newSortOrder = currentMaxSort + 1

        val effectiveCover =
            if (!track.coverThumbnailPath.isNullOrBlank() && !isCoverDeadOnDisk(track.coverThumbnailPath)) {
                track.coverThumbnailPath
            } else {
                trackMetadataRepository?.getCachedMetadata(track.serverId, track.remotePath)?.coverThumbnailPath
            }

        val entry =
            PlaylistTrackEntry.fromAudioTrack(
                playlistId = playlistId,
                track = track.copy(coverThumbnailPath = effectiveCover),
                sortOrder = newSortOrder,
            )
        val insertedId = playlistDao.insertTrack(PlaylistTrackEntity.fromDomain(entry))
        playlistDao.updatePlaylistTimestamp(playlistId, timeProvider())
        return insertedId
    }

    override suspend fun addTracksToPlaylist(
        playlistId: Long,
        tracks: List<AudioTrack>,
    ): List<Long> {
        if (tracks.isEmpty()) return emptyList()
        val existing = playlistDao.getTracksForPlaylist(playlistId)
        val seenKeys = existing.map { "${it.serverId}:${it.remotePath}" }.toMutableSet()
        val distinctNewTracks =
            tracks.filter { track ->
                seenKeys.add("${track.serverId}:${track.remotePath}")
            }
        if (distinctNewTracks.isEmpty()) return emptyList()

        val startSort = (playlistDao.getMaxSortOrder(playlistId) ?: -1) + 1
        val entities =
            distinctNewTracks.mapIndexed { index, track ->
                val effectiveCover =
                    if (!track.coverThumbnailPath.isNullOrBlank() && !isCoverDeadOnDisk(track.coverThumbnailPath)) {
                        track.coverThumbnailPath
                    } else {
                        trackMetadataRepository?.getCachedMetadata(track.serverId, track.remotePath)?.coverThumbnailPath
                    }
                val entry =
                    PlaylistTrackEntry.fromAudioTrack(
                        playlistId = playlistId,
                        track = track.copy(coverThumbnailPath = effectiveCover),
                        sortOrder = startSort + index,
                    )
                PlaylistTrackEntity.fromDomain(entry)
            }
        val insertedIds = playlistDao.insertTracks(entities)
        playlistDao.updatePlaylistTimestamp(playlistId, timeProvider())
        return insertedIds
    }

    override suspend fun removeTrackFromPlaylist(trackEntryId: Long) {
        val track = playlistDao.getTrackById(trackEntryId)
        playlistDao.deleteTrackById(trackEntryId)
        if (track != null) {
            playlistDao.updatePlaylistTimestamp(track.playlistId, timeProvider())
        }
    }

    override suspend fun removeTracksFromPlaylist(
        playlistId: Long,
        trackEntryIds: List<Long>,
    ) {
        if (trackEntryIds.isEmpty()) return
        playlistDao.deleteTracksByIds(trackEntryIds)
        playlistDao.updatePlaylistTimestamp(playlistId, timeProvider())
    }

    override suspend fun updateTrackCover(
        trackEntryId: Long,
        coverThumbnailPath: String?,
    ) {
        playlistDao.updateTrackCover(trackEntryId, coverThumbnailPath)
    }

    override suspend fun updateTrackOrders(
        playlistId: Long,
        orderedTrackIds: List<Long>,
    ) {
        val currentTracks = playlistDao.getTracksForPlaylist(playlistId)
        val trackMap = currentTracks.associateBy { it.id }
        val updatedEntities =
            orderedTrackIds.mapIndexedNotNull { index, id ->
                trackMap[id]?.copy(sortOrder = index)
            }
        playlistDao.updateTracks(updatedEntities)
        playlistDao.updatePlaylistTimestamp(playlistId, timeProvider())
    }
}
