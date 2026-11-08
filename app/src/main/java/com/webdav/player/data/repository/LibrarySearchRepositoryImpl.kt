package com.webdav.player.data.repository

import android.content.Context
import com.webdav.player.data.local.DirectoryCacheDao
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.RemoteDirectoryJsonSerializer
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.repository.LibrarySearchRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import org.json.JSONArray

class LibrarySearchRepositoryImpl(
    private val directoryCacheDao: DirectoryCacheDao,
    private val trackMetadataDao: TrackMetadataDao,
    private val mediaTrackIndexDao: MediaTrackIndexDao? = null,
    private val directorySyncCheckpointDao: DirectorySyncCheckpointDao? = null,
    private val webDavSyncScanner: WebDavSyncScanner? = null,
    private val localStorageIndexScanner: LocalStorageIndexScanner? = null,
    private val context: Context? = null,
    private val prefsName: String = "webdav_search_history",
) : LibrarySearchRepository {
    companion object {
        private const val MAX_HISTORY_SIZE = 20
        private const val KEY_HISTORY_JSON = "history_json"
        private const val MAX_CACHE_ENTRIES = 100
    }

    private val inMemoryHistory = mutableListOf<String>()
    private val _syncState = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)

    override fun observeSyncState(serverId: Long?): Flow<LibrarySyncState> {
        val scannerFlow = webDavSyncScanner?.syncState ?: flowOf(LibrarySyncState.Idle)
        return combine(_syncState, scannerFlow) { internalState, webDavState ->
            when {
                webDavState is LibrarySyncState.Scanning -> webDavState
                internalState is LibrarySyncState.Scanning -> internalState
                webDavState is LibrarySyncState.Failed -> webDavState
                internalState is LibrarySyncState.Failed -> internalState
                webDavState is LibrarySyncState.Completed -> webDavState
                internalState is LibrarySyncState.Completed -> internalState
                else -> LibrarySyncState.Idle
            }
        }.distinctUntilChanged()
    }

    override suspend fun syncServer(
        server: WebDavServer,
        force: Boolean,
    ): LibrarySyncResult =
        if (server.isLocal || server.id == LOCAL_STORAGE_ID) {
            _syncState.value =
                LibrarySyncState.Scanning(
                    progress = 0f,
                    currentPath = "local",
                    tracksFound = 0,
                )
            try {
                val count =
                    localStorageIndexScanner?.indexAllMountedFolders(
                        force = force,
                        onProgress = { _syncState.value = it },
                    ) ?: 0
                val total = mediaTrackIndexDao?.countByServerId(LOCAL_STORAGE_ID) ?: count
                val state = LibrarySyncState.Completed(totalTracks = total)
                _syncState.value = state
                LibrarySyncResult.Success(totalTracksIndexed = count)
            } catch (e: Exception) {
                val errorMsg = e.message ?: "Local storage scan failed"
                val state = LibrarySyncState.Failed(errorMsg)
                _syncState.value = state
                LibrarySyncResult.Error(errorMsg, e)
            }
        } else {
            if (webDavSyncScanner != null) {
                _syncState.value =
                    LibrarySyncState.Scanning(
                        progress = 0f,
                        currentPath = server.pathPrefix.ifBlank { "/" },
                        tracksFound = 0,
                    )
                try {
                    val result =
                        webDavSyncScanner.sync(
                            server = server,
                            force = force,
                            onProgress = { _syncState.value = it },
                        )
                    when (result) {
                        is LibrarySyncResult.Success -> {
                            val total = mediaTrackIndexDao?.countByServerId(server.id) ?: result.totalTracksIndexed
                            _syncState.value = LibrarySyncState.Completed(totalTracks = total)
                        }

                        is LibrarySyncResult.Error -> {
                            _syncState.value = LibrarySyncState.Failed(result.message)
                        }
                    }
                    result
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "WebDAV sync failed"
                    _syncState.value = LibrarySyncState.Failed(errorMsg)
                    LibrarySyncResult.Error(errorMsg, e)
                }
            } else {
                LibrarySyncResult.Success(totalTracksIndexed = 0)
            }
        }

    private val prefs by lazy {
        context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    private val directoryCache =
        object : LinkedHashMap<String, RemoteDirectory>(MAX_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteDirectory>?): Boolean = size > MAX_CACHE_ENTRIES
        }

    private fun getOrDeserialize(dirEntity: DirectoryCacheEntity): RemoteDirectory? {
        val cacheKey = "${dirEntity.serverId}:${dirEntity.path}:${dirEntity.lastUpdatedMs}:${dirEntity.dataJson.length}"
        synchronized(directoryCache) {
            directoryCache[cacheKey]?.let { return it }
        }
        val deserialized = RemoteDirectoryJsonSerializer.deserialize(dirEntity.dataJson) ?: return null
        synchronized(directoryCache) {
            directoryCache[cacheKey] = deserialized
        }
        return deserialized
    }

    private fun saveHistory(history: List<String>) {
        val sharedPrefs = prefs
        if (sharedPrefs != null) {
            sharedPrefs.edit().putString(KEY_HISTORY_JSON, JSONArray(history).toString()).apply()
        } else {
            synchronized(inMemoryHistory) {
                inMemoryHistory.clear()
                inMemoryHistory.addAll(history)
            }
        }
    }

    override fun getSearchHistory(): List<String> {
        val sharedPrefs = prefs ?: return synchronized(inMemoryHistory) { inMemoryHistory.toList() }
        val raw = sharedPrefs.getString(KEY_HISTORY_JSON, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }

    override fun recordSearchQuery(query: String): List<String> {
        val trimmed = query.trim()
        if (trimmed.isBlank()) return getSearchHistory()
        val current = getSearchHistory().filter { it != trimmed }
        val updated = (listOf(trimmed) + current).take(MAX_HISTORY_SIZE)
        saveHistory(updated)
        return updated
    }

    override fun removeSearchHistory(query: String): List<String> {
        val updated = getSearchHistory().filter { it != query }
        saveHistory(updated)
        return updated
    }

    override fun clearSearchHistory() {
        val sharedPrefs = prefs
        if (sharedPrefs != null) {
            sharedPrefs.edit().remove(KEY_HISTORY_JSON).apply()
        } else {
            synchronized(inMemoryHistory) {
                inMemoryHistory.clear()
            }
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

    override fun search(
        query: String,
        serverId: Long?,
    ): Flow<LibrarySearchResult> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) {
            return flowOf(LibrarySearchResult())
        }

        val indexFlow: Flow<List<MediaTrackIndexEntity>> =
            mediaTrackIndexDao?.search(cleanQuery, serverId) ?: flowOf(emptyList())
        val checkpointFlow: Flow<List<DirectorySyncCheckpointEntity>> =
            directorySyncCheckpointDao?.searchCheckpoints(cleanQuery, serverId) ?: flowOf(emptyList())
        val dirFlow = directoryCacheDao.searchDirectoryCacheFlow(cleanQuery, serverId)
        val metaFlow = trackMetadataDao.searchMetadataFlow(cleanQuery, serverId)

        return combine(
            indexFlow,
            checkpointFlow,
            dirFlow,
            metaFlow,
        ) {
            indexedTracks: List<MediaTrackIndexEntity>,
            checkpoints: List<DirectorySyncCheckpointEntity>,
            dirEntities: List<DirectoryCacheEntity>,
            metaEntities: List<TrackMetadataEntity>,
            ->
            val metadataMap = metaEntities.associateBy { "${it.serverId}:${it.remotePath}" }
            val matchedDirs = mutableMapOf<String, RemoteDirectory>()
            val matchedTracks = mutableMapOf<String, AudioTrack>()

            fun isAccepted(
                itemServerId: Long,
                itemPath: String,
            ): Boolean {
                val isLocal = itemServerId == LOCAL_STORAGE_ID || itemPath.startsWith("content://") || itemPath.startsWith("/local/")
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
                val isLocal = itemServerId == LOCAL_STORAGE_ID || itemRemotePath.startsWith("content://")
                return if (isLocal) "local:$itemRemotePath" else "$itemServerId:$itemRemotePath"
            }

            fun dirDedupeKey(
                itemServerId: Long,
                itemDirPath: String,
            ): String {
                val isLocal = itemServerId == LOCAL_STORAGE_ID || itemDirPath.startsWith("/local/")
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
                val meta = metadataMap[metaKey]
                val format =
                    AudioFormat.fromExtension(entity.format)
                        ?: AudioFormat.fromFileName(entity.fileName)
                        ?: AudioFormat.MP3
                val effectiveServerId = if (entity.remotePath.startsWith("content://")) LOCAL_STORAGE_ID else entity.serverId
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

                if (entity.parentPath.contains(cleanQuery, ignoreCase = true) && isAccepted(entity.serverId, entity.parentPath)) {
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

            for (dirEntity in dirEntities) {
                if (!isAccepted(dirEntity.serverId, dirEntity.path)) continue

                val matchesPath = dirEntity.path.contains(cleanQuery, ignoreCase = true)
                val matchesJson = dirEntity.dataJson.contains(cleanQuery, ignoreCase = true)
                if (!matchesPath && !matchesJson) {
                    continue
                }

                val remoteDir = getOrDeserialize(dirEntity) ?: continue

                if (isMatch(cleanQuery, remoteDir.name, remoteDir.path)) {
                    val dirKey = dirDedupeKey(dirEntity.serverId, remoteDir.path)
                    matchedDirs[dirKey] =
                        remoteDir.copy(serverId = if (remoteDir.path.startsWith("/local/")) LOCAL_STORAGE_ID else dirEntity.serverId)
                }

                for (sub in remoteDir.subDirectories) {
                    if (isAccepted(dirEntity.serverId, sub.path) && isMatch(cleanQuery, sub.name, sub.path)) {
                        val subKey = dirDedupeKey(dirEntity.serverId, sub.path)
                        matchedDirs[subKey] =
                            sub.copy(serverId = if (sub.path.startsWith("/local/")) LOCAL_STORAGE_ID else dirEntity.serverId)
                    }
                }

                val audioFiles = remoteDir.files.filter { it.isAudio }

                for (file in audioFiles) {
                    if (!isAccepted(dirEntity.serverId, file.path)) continue
                    val dedupeKey = trackDedupeKey(dirEntity.serverId, file.path)
                    if (matchedTracks.containsKey(dedupeKey)) continue

                    val metaKey = "${dirEntity.serverId}:${file.path}"
                    val meta = metadataMap[metaKey]
                    val format = (file.fileType as? RemoteFileType.Audio)?.format ?: AudioFormat.MP3
                    val effectiveServerId = if (file.path.startsWith("content://")) LOCAL_STORAGE_ID else dirEntity.serverId
                    val track = createAudioTrack(effectiveServerId, file.path, file.name, file.size, format, meta)

                    if (isMatch(cleanQuery, track.title, track.artist, track.album, file.name, file.path)) {
                        matchedTracks[dedupeKey] = track
                    }
                }
            }

            for (meta in metaEntities) {
                if (!isAccepted(meta.serverId, meta.remotePath)) continue
                val dedupeKey = trackDedupeKey(meta.serverId, meta.remotePath)
                if (!matchedTracks.containsKey(dedupeKey) && isMatch(cleanQuery, meta.title, meta.artist, meta.album, meta.remotePath)) {
                    val fileName = meta.remotePath.substringAfterLast('/')
                    val format = (RemoteFileType.fromFileName(fileName) as? RemoteFileType.Audio)?.format ?: AudioFormat.MP3
                    val effectiveServerId = if (meta.remotePath.startsWith("content://")) LOCAL_STORAGE_ID else meta.serverId
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
}
