package com.webdav.player.data.repository

import android.content.Context
import com.webdav.player.data.local.DirectoryCacheDao
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.LocalStorageFolderDao
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.RemoteDirectoryJsonSerializer
import com.webdav.player.data.local.TrackMetadataDao
import com.webdav.player.data.local.TrackMetadataEntity
import com.webdav.player.data.metadata.MediaFilenameParser
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.data.remote.WebDavXmlParser
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.LibrarySearchResult
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.repository.MediaLibraryRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import org.json.JSONArray

/**
 * Single authoritative media library repository implementation consolidating
 * SWR directory navigation, pure B-Tree search, incremental sync, search history,
 * and cascading invalidation into a deep domain subsystem.
 */
class MediaLibraryRepositoryImpl(
    private val webDavClient: WebDavClient,
    private val directoryCacheDao: DirectoryCacheDao? = null,
    private val trackMetadataDao: TrackMetadataDao? = null,
    private val mediaTrackIndexDao: MediaTrackIndexDao? = null,
    private val directorySyncCheckpointDao: DirectorySyncCheckpointDao? = null,
    private val trackMetadataRepository: TrackMetadataRepository? = null,
    private val localStorageDirectoryResolver: LocalStorageDirectoryResolver? = null,
    private val localStorageFolderDao: LocalStorageFolderDao? = null,
    webDavSyncScanner: WebDavSyncScanner? = null,
    localStorageIndexScanner: LocalStorageIndexScanner? = null,
    private val context: Context? = null,
    private val prefsName: String = "webdav_search_history",
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : MediaLibraryRepository {

    companion object {
        private const val MAX_MEMORY_CACHE_SIZE = 100
        private const val MAX_HISTORY_SIZE = 20
        private const val KEY_HISTORY_JSON = "history_json"
    }

    // Consolidated single authoritative L1 LRU memory cache
    private val cacheLock = Any()
    private val memoryCache =
        object : LinkedHashMap<String, RemoteDirectory>(MAX_MEMORY_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteDirectory>?): Boolean =
                size > MAX_MEMORY_CACHE_SIZE
        }

    // Internalized scanners
    private val webDavSyncScanner: WebDavSyncScanner? =
        webDavSyncScanner ?: if (mediaTrackIndexDao != null && directorySyncCheckpointDao != null) {
            WebDavSyncScanner(
                webDavClient = webDavClient,
                mediaTrackIndexDao = mediaTrackIndexDao,
                directorySyncCheckpointDao = directorySyncCheckpointDao,
                ioDispatcher = ioDispatcher,
            )
        } else {
            null
        }

    private val localStorageIndexScanner: LocalStorageIndexScanner? =
        localStorageIndexScanner ?: if (context != null && mediaTrackIndexDao != null && directorySyncCheckpointDao != null) {
            LocalStorageIndexScanner(
                context = context,
                mediaTrackIndexDao = mediaTrackIndexDao,
                checkpointDao = directorySyncCheckpointDao,
                localStorageFolderDao = localStorageFolderDao,
                ioDispatcher = ioDispatcher,
            )
        } else {
            null
        }

    // Search history backing
    private val inMemoryHistory = mutableListOf<String>()
    private val prefs by lazy {
        context?.getSharedPreferences(prefsName, Context.MODE_PRIVATE)
    }

    // Sync state tracking
    private val _syncState = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
    private val _syncStatesByServer = MutableStateFlow<Map<Long, LibrarySyncState>>(emptyMap())

    private fun updateSyncState(
        serverId: Long,
        state: LibrarySyncState,
    ) {
        _syncState.value = state
        _syncStatesByServer.update { it + (serverId to state) }
    }

    // =========================================================================
    // SWR Directory Browsing
    // =========================================================================

    override suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory? =
        withContext(ioDispatcher) {
            val normalizedPath = RemoteDirectory.normalizePath(path)
            val cacheKey = "${server.id}:$normalizedPath"

            // 1. Check authoritative L1 memory cache
            val inMemory =
                synchronized(cacheLock) {
                    memoryCache[cacheKey]
                }
            if (inMemory != null) {
                return@withContext inMemory
            }

            // 2. Check L2 Room persistent cache
            val cachedEntity = directoryCacheDao?.getCache(server.id, normalizedPath)
            if (cachedEntity != null) {
                val deserialized = RemoteDirectoryJsonSerializer.deserialize(cachedEntity.dataJson)
                if (deserialized != null) {
                    synchronized(cacheLock) {
                        memoryCache[cacheKey] = deserialized
                    }
                    return@withContext deserialized
                }
            }

            null
        }

    override fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean,
    ): Flow<DirectoryContent> =
        flow {
            val normalizedPath = RemoteDirectory.normalizePath(path)
            val cachedDir = if (!forceRefresh) getCachedDirectory(server, normalizedPath) else null

            // 1. Cache hit emission (0ms)
            if (cachedDir != null) {
                val initialMeta = getCachedMetadataMap(server.id, cachedDir)
                emit(
                    DirectoryContent(
                        directory = cachedDir,
                        metadataMap = initialMeta,
                        isEnriching = true,
                    ),
                )
            }

            val isLocal = server.isLocal || server.id == LOCAL_STORAGE_ID || normalizedPath.startsWith("/local/")
            if (isLocal) {
                observeLocalDirectoryContent(server, normalizedPath, cachedDir, forceRefresh)
                return@flow
            }

            // 2. WebDAV Remote Query
            val remoteResult =
                try {
                    webDavClient.listDirectory(server, normalizedPath)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    ListDirectoryResult.Failure(e.message ?: "Unknown error")
                }

            when (remoteResult) {
                is ListDirectoryResult.Success -> {
                    val remoteDir = remoteResult.directory
                    val remoteDiffersFromCache = (cachedDir == null || remoteDir != cachedDir)

                    if (remoteDiffersFromCache) {
                        persistCache(server.id, normalizedPath, remoteDir)
                        val currentMeta = getCachedMetadataMap(server.id, remoteDir)
                        emit(
                            DirectoryContent(
                                directory = remoteDir,
                                metadataMap = currentMeta,
                                isEnriching = true,
                            ),
                        )
                    }

                    // 3. Background enrichment
                    val enriched = enrichDirectoryContent(server, remoteDir, forceRefresh)
                    emit(enriched)
                }

                is ListDirectoryResult.Failure -> {
                    if (cachedDir != null && !forceRefresh) {
                        // Suppress transient network failure and finish enrichment lifecycle cleanly
                        val finalMeta = getCachedMetadataMap(server.id, cachedDir)
                        emit(
                            DirectoryContent(
                                directory = cachedDir,
                                metadataMap = finalMeta,
                                isEnriching = false,
                            ),
                        )
                    } else {
                        throw IllegalStateException(remoteResult.message)
                    }
                }
            }
        }

    private suspend fun FlowCollector<DirectoryContent>.observeLocalDirectoryContent(
        server: WebDavServer,
        normalizedPath: String,
        cachedDir: RemoteDirectory?,
        forceRefresh: Boolean,
    ) {
        val resolver =
            localStorageDirectoryResolver
                ?: throw IllegalStateException("LocalStorageDirectoryResolver is not configured")

        val localResult =
            try {
                resolver.resolve(normalizedPath)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: Exception) {
                null
            }

        if (localResult != null) {
            val remoteDir = localResult.directory
            val remoteDiffersFromCache = (cachedDir == null || remoteDir != cachedDir)

            if (remoteDiffersFromCache) {
                persistCache(server.id, normalizedPath, remoteDir)
                val currentMeta = getCachedMetadataMap(server.id, remoteDir)
                emit(
                    DirectoryContent(
                        directory = remoteDir,
                        metadataMap = if (currentMeta.isNotEmpty()) currentMeta else localResult.metadataMap,
                        isEnriching = true,
                    ),
                )
            } else {
                persistCache(server.id, normalizedPath, remoteDir)
            }

            val enriched =
                enrichDirectoryContent(
                    server = server,
                    directory = remoteDir,
                    forceRefresh = forceRefresh,
                    resolvedMetadataMap = localResult.metadataMap,
                )
            emit(enriched)
        } else {
            if (cachedDir != null && !forceRefresh) {
                val finalMeta = getCachedMetadataMap(server.id, cachedDir)
                emit(
                    DirectoryContent(
                        directory = cachedDir,
                        metadataMap = finalMeta,
                        isEnriching = false,
                    ),
                )
            } else {
                throw IllegalStateException("Failed to resolve local directory: $normalizedPath")
            }
        }
    }

    private suspend fun getCachedMetadataMap(
        serverId: Long,
        directory: RemoteDirectory,
    ): Map<String, TrackMetadata> {
        val audioFiles = directory.files.filter { it.isAudio }
        if (audioFiles.isEmpty() || trackMetadataRepository == null) return emptyMap()
        val metaMap = mutableMapOf<String, TrackMetadata>()
        for (file in audioFiles) {
            val meta = trackMetadataRepository.getCachedMetadata(serverId, file.path)
            if (meta != null) {
                metaMap[file.path] = meta
            }
        }
        return metaMap
    }

    private suspend fun enrichDirectoryContent(
        server: WebDavServer,
        directory: RemoteDirectory,
        forceRefresh: Boolean,
        resolvedMetadataMap: Map<String, TrackMetadata>? = null,
    ): DirectoryContent {
        val audioFiles = directory.files.filter { it.isAudio }

        if (audioFiles.isNotEmpty() && trackMetadataRepository != null) {
            trackMetadataRepository.resolveMetadata(
                server = server,
                files = audioFiles,
                forceRefresh = forceRefresh,
                directoryFiles = directory.files,
            )
        }

        val cachedMetadata = getCachedMetadataMap(server.id, directory)
        val metadataMap =
            if (resolvedMetadataMap != null && cachedMetadata.isEmpty()) {
                resolvedMetadataMap
            } else if (resolvedMetadataMap != null) {
                val merged = resolvedMetadataMap.toMutableMap()
                for ((path, cached) in cachedMetadata) {
                    val fallback = resolvedMetadataMap[path]
                    merged[path] =
                        if (fallback != null) {
                            cached.copy(
                                coverThumbnailPath = cached.coverThumbnailPath ?: fallback.coverThumbnailPath,
                                lyrics = cached.lyrics ?: fallback.lyrics,
                            )
                        } else {
                            cached
                        }
                }
                merged
            } else {
                cachedMetadata
            }

        return DirectoryContent(
            directory = directory,
            metadataMap = metadataMap,
            isEnriching = false,
        )
    }

    // =========================================================================
    // Write-Through Indexing & Caching
    // =========================================================================

    private suspend fun persistCache(
        serverId: Long,
        normalizedPath: String,
        directory: RemoteDirectory,
    ) {
        val cacheKey = "$serverId:$normalizedPath"
        synchronized(cacheLock) {
            memoryCache[cacheKey] = directory
        }
        val json = RemoteDirectoryJsonSerializer.serialize(directory)
        directoryCacheDao?.insertOrUpdate(
            DirectoryCacheEntity(
                serverId = serverId,
                path = normalizedPath,
                dataJson = json,
                lastUpdatedMs = System.currentTimeMillis(),
            ),
        )
        syncDirectoryToMediaIndex(serverId, normalizedPath, directory)
    }

    private suspend fun syncDirectoryToMediaIndex(
        serverId: Long,
        normalizedPath: String,
        directory: RemoteDirectory,
    ) {
        val indexDao = mediaTrackIndexDao ?: return
        val isLocal = serverId == LOCAL_STORAGE_ID || normalizedPath.startsWith("/local/")
        val effectiveServerId = if (isLocal) LOCAL_STORAGE_ID else serverId
        val discoveredTracks =
            directory.files.mapNotNull { file ->
                val audioFormat =
                    (file.fileType as? RemoteFileType.Audio)?.format
                        ?: AudioFormat.fromFileName(file.name)
                if (file.isAudio || audioFormat != null) {
                    val format = audioFormat ?: AudioFormat.MP3
                    val parsed = MediaFilenameParser.parse(file.name)
                    val lastMod =
                        file.lastModified?.toLongOrNull()
                            ?: WebDavXmlParser.parseHttpDate(file.lastModified)
                    MediaTrackIndexEntity(
                        serverId = effectiveServerId,
                        remotePath = file.path,
                        fileName = file.name,
                        fileSize = file.size,
                        lastModified = lastMod,
                        format = format.extension.uppercase(),
                        parentPath = normalizedPath,
                        parsedTitle = parsed.title,
                        parsedArtist = parsed.artist,
                    )
                } else {
                    null
                }
            }
        try {
            if (discoveredTracks.isNotEmpty()) {
                indexDao.upsertTracks(discoveredTracks)
            }
            val existing = indexDao.getTracksByParentPath(effectiveServerId, normalizedPath)
            val discoveredPaths = discoveredTracks.map { it.remotePath }.toSet()
            val staleTracks = existing.filter { it.remotePath !in discoveredPaths }
            if (staleTracks.isNotEmpty()) {
                indexDao.deleteByServerAndPaths(effectiveServerId, staleTracks.map { it.remotePath })
            }
        } catch (_: Exception) {
            // Non-fatal index sync failure
        }
    }

    // =========================================================================
    // Pure B-Tree Search (Zero dataJson Scanning)
    // =========================================================================

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
                val isLocal = itemServerId == LOCAL_STORAGE_ID || itemRemotePath.startsWith("content://") || itemRemotePath.startsWith("/local/")
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
                val effectiveServerId =
                    if (entity.remotePath.startsWith("content://") || entity.remotePath.startsWith("/local/") || entity.serverId == LOCAL_STORAGE_ID) {
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

            for (meta in metaEntities) {
                if (!isAccepted(meta.serverId, meta.remotePath)) continue
                val dedupeKey = trackDedupeKey(meta.serverId, meta.remotePath)
                if (!matchedTracks.containsKey(dedupeKey) && isMatch(cleanQuery, meta.title, meta.artist, meta.album, meta.remotePath)) {
                    val fileName = meta.remotePath.substringAfterLast('/')
                    val format = (RemoteFileType.fromFileName(fileName) as? RemoteFileType.Audio)?.format ?: AudioFormat.MP3
                    val effectiveServerId =
                        if (meta.remotePath.startsWith("content://") || meta.remotePath.startsWith("/local/") || meta.serverId == LOCAL_STORAGE_ID) {
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

    // =========================================================================
    // Sync Orchestration & Progress
    // =========================================================================

    override fun observeSyncState(serverId: Long?): Flow<LibrarySyncState> {
        if (serverId != null) {
            return _syncStatesByServer
                .map { it[serverId] ?: LibrarySyncState.Idle }
                .distinctUntilChanged()
        }
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
        withContext(ioDispatcher) {
            if (server.isLocal || server.id == LOCAL_STORAGE_ID) {
                updateSyncState(
                    LOCAL_STORAGE_ID,
                    LibrarySyncState.Scanning(
                        progress = 0f,
                        currentPath = "local",
                        tracksFound = 0,
                    ),
                )
                try {
                    val count =
                        localStorageIndexScanner?.indexAllMountedFolders(
                            force = force,
                            onProgress = { updateSyncState(LOCAL_STORAGE_ID, it) },
                        ) ?: 0
                    val total = mediaTrackIndexDao?.countByServerId(LOCAL_STORAGE_ID) ?: count
                    val state = LibrarySyncState.Completed(totalTracks = total)
                    updateSyncState(LOCAL_STORAGE_ID, state)
                    LibrarySyncResult.Success(totalTracksIndexed = count)
                } catch (ce: CancellationException) {
                    throw ce
                } catch (e: Exception) {
                    val errorMsg = e.message ?: "Local storage scan failed"
                    val state = LibrarySyncState.Failed(errorMsg)
                    updateSyncState(LOCAL_STORAGE_ID, state)
                    LibrarySyncResult.Error(errorMsg, e)
                }
            } else {
                if (webDavSyncScanner != null) {
                    updateSyncState(
                        server.id,
                        LibrarySyncState.Scanning(
                            progress = 0f,
                            currentPath = server.pathPrefix.ifBlank { "/" },
                            tracksFound = 0,
                        ),
                    )
                    try {
                        val result =
                            webDavSyncScanner.sync(
                                server = server,
                                force = force,
                                onProgress = { updateSyncState(server.id, it) },
                            )
                        when (result) {
                            is LibrarySyncResult.Success -> {
                                val total =
                                    mediaTrackIndexDao?.countByServerId(server.id)
                                        ?: result.totalTracksIndexed
                                updateSyncState(server.id, LibrarySyncState.Completed(totalTracks = total))
                            }

                            is LibrarySyncResult.Error -> {
                                updateSyncState(server.id, LibrarySyncState.Failed(result.message))
                            }
                        }
                        result
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (e: Exception) {
                        val errorMsg = e.message ?: "WebDAV sync failed"
                        updateSyncState(server.id, LibrarySyncState.Failed(errorMsg))
                        LibrarySyncResult.Error(errorMsg, e)
                    }
                } else {
                    LibrarySyncResult.Success(totalTracksIndexed = 0)
                }
            }
        }

    override suspend fun rebuildIndex(server: WebDavServer): LibrarySyncResult =
        withContext(ioDispatcher) {
            val targetServerId = if (server.isLocal || server.id == LOCAL_STORAGE_ID) LOCAL_STORAGE_ID else server.id
            try {
                directorySyncCheckpointDao?.deleteByServerId(targetServerId)
                mediaTrackIndexDao?.deleteByServerId(targetServerId)
            } catch (_: Exception) {
            }
            syncServer(server, force = true)
        }

    // =========================================================================
    // Search History Management
    // =========================================================================

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
        } catch (_: Exception) {
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
        prefs?.edit()?.remove(KEY_HISTORY_JSON)?.apply()
        synchronized(inMemoryHistory) {
            inMemoryHistory.clear()
        }
    }

    // =========================================================================
    // Storage Deletion Cascading Invalidation
    // =========================================================================

    override suspend fun clearStorageData(serverId: Long) {
        withContext(ioDispatcher) {
            val prefix = "$serverId:"
            synchronized(cacheLock) {
                val keysToRemove = memoryCache.keys.filter { it.startsWith(prefix) }
                keysToRemove.forEach { memoryCache.remove(it) }
            }
            directoryCacheDao?.deleteCacheByServerId(serverId)
            mediaTrackIndexDao?.deleteByServerId(serverId)
            directorySyncCheckpointDao?.deleteByServerId(serverId)
            _syncStatesByServer.update { it - serverId }
        }
    }

    override suspend fun clearLocalFolderData(uriString: String) {
        withContext(ioDispatcher) {
            val localRoot =
                try {
                    LocalStorageIndexScanner.buildLocalRootPath(uriString)
                } catch (_: Exception) {
                    ""
                }
            synchronized(cacheLock) {
                val keysToRemove =
                    memoryCache.keys.filter { key ->
                        key.startsWith("$LOCAL_STORAGE_ID:") && (
                            (localRoot.isNotEmpty() && key.contains(localRoot)) ||
                                key.contains(uriString) ||
                                key == "$LOCAL_STORAGE_ID:/" ||
                                key == "$LOCAL_STORAGE_ID:/local/"
                        )
                    }
                keysToRemove.forEach { memoryCache.remove(it) }
            }
            if (localRoot.isNotEmpty()) {
                try {
                    mediaTrackIndexDao?.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, localRoot)
                    directorySyncCheckpointDao?.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, localRoot)
                } catch (_: Exception) {
                }
            }
            try {
                mediaTrackIndexDao?.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, uriString)
                directorySyncCheckpointDao?.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, uriString)
            } catch (_: Exception) {
            }
            directoryCacheDao?.deleteCacheByServerId(LOCAL_STORAGE_ID)
        }
    }

    override suspend fun clearAllCaches() {
        withContext(ioDispatcher) {
            clearMemoryCache()
            directoryCacheDao?.clearAll()
        }
    }

    override fun clearMemoryCache() {
        synchronized(cacheLock) {
            memoryCache.clear()
        }
    }
}
