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
import com.webdav.player.data.metadata.MediaFilenameParser
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.data.remote.WebDavXmlParser
import com.webdav.player.domain.model.AudioFormat
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
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * Single authoritative media library repository implementation consolidating
 * SWR directory navigation, pure B-Tree search, incremental sync, search history,
 * and cascading invalidation into a deep domain subsystem.
 */
class MediaLibraryRepositoryImpl internal constructor(
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
    context: Context? = null,
    prefsName: String = InternalSearchHistoryStore.DEFAULT_PREFS_NAME,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    searchCoordinator: MediaLibrarySearchCoordinator? = null,
    searchHistoryStore: InternalSearchHistoryStore? = null,
    browsingCursorStore: InternalBrowsingCursorStore? = null,
) : MediaLibraryRepository {

    companion object {
        private const val MAX_MEMORY_CACHE_SIZE = 100
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
            WebDavSyncScanner(webDavClient, mediaTrackIndexDao, directorySyncCheckpointDao, ioDispatcher)
        } else null

    private val localStorageIndexScanner: LocalStorageIndexScanner? =
        localStorageIndexScanner ?: if (context != null && mediaTrackIndexDao != null && directorySyncCheckpointDao != null) {
            LocalStorageIndexScanner(context, mediaTrackIndexDao, directorySyncCheckpointDao, localStorageFolderDao, ioDispatcher = ioDispatcher)
        } else null

    // Internalized search collaborators
    private val searchCoordinator: MediaLibrarySearchCoordinator =
        searchCoordinator ?: MediaLibrarySearchCoordinator(mediaTrackIndexDao, directorySyncCheckpointDao, trackMetadataDao)

    private val searchHistoryStore: InternalSearchHistoryStore =
        searchHistoryStore ?: InternalSearchHistoryStore(context, prefsName)

    private val browsingCursorStore: InternalBrowsingCursorStore =
        browsingCursorStore ?: InternalBrowsingCursorStore(context)

    // Sync state tracking
    private val _syncState = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
    private val _syncStatesByServer = MutableStateFlow<Map<Long, LibrarySyncState>>(emptyMap())

    private fun updateSyncState(serverId: Long, state: LibrarySyncState) {
        _syncState.value = state
        _syncStatesByServer.update { it + (serverId to state) }
    }

    // =========================================================================
    // SWR Directory Browsing
    // =========================================================================

    override suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory? = withContext(ioDispatcher) {
        val normalizedPath = RemoteDirectory.normalizePath(path)
        val cacheKey = "${server.id}:$normalizedPath"

        // 1. Check authoritative L1 memory cache
        val inMemory = synchronized(cacheLock) { memoryCache[cacheKey] }
        if (inMemory != null) return@withContext inMemory

        // 2. Check L2 Room persistent cache
        val cachedEntity = directoryCacheDao?.getCache(server.id, normalizedPath)
        if (cachedEntity != null) {
            val deserialized = RemoteDirectoryJsonSerializer.deserialize(cachedEntity.dataJson)
            if (deserialized != null) {
                synchronized(cacheLock) { memoryCache[cacheKey] = deserialized }
                return@withContext deserialized
            }
        }

        null
    }

    override fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean,
    ): Flow<DirectoryContent> = flow {
        val normalizedPath = RemoteDirectory.normalizePath(path)
        val cachedDir = if (!forceRefresh) getCachedDirectory(server, normalizedPath) else null

        // 1. Cache hit emission (0ms)
        if (cachedDir != null) {
            val initialMeta = getCachedMetadataMap(server.id, cachedDir)
            emit(DirectoryContent(directory = cachedDir, metadataMap = initialMeta, isEnriching = true))
        }

        val isLocal = server.isLocal || server.id == LOCAL_STORAGE_ID || normalizedPath.startsWith("/local/")
        if (isLocal) {
            observeLocalDirectoryContent(server, normalizedPath, cachedDir, forceRefresh)
            return@flow
        }

        // 2. WebDAV Remote Query
        val remoteResult = try {
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
                    emit(DirectoryContent(directory = remoteDir, metadataMap = currentMeta, isEnriching = true))
                }

                // 3. Background enrichment
                val enriched = enrichDirectoryContent(server, remoteDir, forceRefresh)
                emit(enriched)
            }

            is ListDirectoryResult.Failure -> {
                if (cachedDir != null && !forceRefresh) {
                    // Suppress transient network failure and finish enrichment lifecycle cleanly
                    val finalMeta = getCachedMetadataMap(server.id, cachedDir)
                    emit(DirectoryContent(directory = cachedDir, metadataMap = finalMeta, isEnriching = false))
                } else {
                    throw IllegalStateException(remoteResult.message)
                }
            }
        }
    }.flowOn(ioDispatcher)

    private suspend fun FlowCollector<DirectoryContent>.observeLocalDirectoryContent(
        server: WebDavServer,
        normalizedPath: String,
        cachedDir: RemoteDirectory?,
        forceRefresh: Boolean,
    ) {
        val resolver = localStorageDirectoryResolver
            ?: throw IllegalStateException("LocalStorageDirectoryResolver is not configured")

        val localResult = try {
            resolver.resolve(normalizedPath)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            null
        }

        if (localResult != null) {
            val remoteDir = localResult.directory
            val remoteDiffersFromCache = (cachedDir == null || remoteDir != cachedDir)

            persistCache(server.id, normalizedPath, remoteDir)
            if (remoteDiffersFromCache) {
                val currentMeta = getCachedMetadataMap(server.id, remoteDir)
                emit(
                    DirectoryContent(
                        directory = remoteDir,
                        metadataMap = if (currentMeta.isNotEmpty()) currentMeta else localResult.metadataMap,
                        isEnriching = true,
                    ),
                )
            }

            val enriched = enrichDirectoryContent(
                server = server,
                directory = remoteDir,
                forceRefresh = forceRefresh,
                resolvedMetadataMap = localResult.metadataMap,
            )
            emit(enriched)
        } else {
            if (cachedDir != null && !forceRefresh) {
                val finalMeta = getCachedMetadataMap(server.id, cachedDir)
                emit(DirectoryContent(directory = cachedDir, metadataMap = finalMeta, isEnriching = false))
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
        val repo = trackMetadataRepository ?: return emptyMap()
        if (audioFiles.isEmpty()) return emptyMap()
        return audioFiles.mapNotNull { file ->
            repo.getCachedMetadata(serverId, file.path)?.let { file.path to it }
        }.toMap()
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
        val metadataMap = when {
            resolvedMetadataMap == null -> cachedMetadata
            cachedMetadata.isEmpty() -> resolvedMetadataMap
            else -> {
                val merged = resolvedMetadataMap.toMutableMap()
                for ((path, cached) in cachedMetadata) {
                    val fallback = resolvedMetadataMap[path]
                    merged[path] = if (fallback != null) {
                        cached.copy(
                            coverThumbnailPath = cached.coverThumbnailPath ?: fallback.coverThumbnailPath,
                            lyrics = cached.lyrics ?: fallback.lyrics,
                        )
                    } else {
                        cached
                    }
                }
                merged
            }
        }

        return DirectoryContent(directory = directory, metadataMap = metadataMap, isEnriching = false)
    }

    // =========================================================================
    // Write-Through Indexing & Caching
    // =========================================================================

    private suspend fun persistCache(
        serverId: Long,
        normalizedPath: String,
        directory: RemoteDirectory,
    ) = withContext(ioDispatcher) {
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
        syncDirectoryToCheckpoints(serverId, normalizedPath, directory)
    }

    private suspend fun syncDirectoryToMediaIndex(
        serverId: Long,
        normalizedPath: String,
        directory: RemoteDirectory,
    ) {
        val indexDao = mediaTrackIndexDao ?: return
        val isLocal = serverId == LOCAL_STORAGE_ID || normalizedPath.startsWith("/local/")
        val effectiveServerId = if (isLocal) LOCAL_STORAGE_ID else serverId
        val discoveredTracks = directory.files.mapNotNull { file ->
            val audioFormat = (file.fileType as? RemoteFileType.Audio)?.format
                ?: AudioFormat.fromFileName(file.name)
            if (file.isAudio || audioFormat != null) {
                val format = audioFormat ?: AudioFormat.MP3
                val parsed = MediaFilenameParser.parse(file.name)
                val lastMod = file.lastModified?.toLongOrNull()
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
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Non-fatal index sync failure
        }
    }

    private suspend fun syncDirectoryToCheckpoints(
        serverId: Long,
        normalizedPath: String,
        directory: RemoteDirectory,
    ) {
        val cpDao = directorySyncCheckpointDao ?: return
        val isLocal = serverId == LOCAL_STORAGE_ID || normalizedPath.startsWith("/local/")
        val effectiveServerId = if (isLocal) LOCAL_STORAGE_ID else serverId
        val now = System.currentTimeMillis()
        try {
            cpDao.upsertCheckpoint(
                DirectorySyncCheckpointEntity(
                    serverId = effectiveServerId,
                    path = normalizedPath,
                    lastModified = directory.lastModified,
                    etag = directory.etag,
                    lastScannedMs = now,
                ),
            )
            for (sub in directory.subDirectories) {
                val subPath = RemoteDirectory.normalizePath(sub.path)
                cpDao.upsertCheckpoint(
                    DirectorySyncCheckpointEntity(
                        serverId = effectiveServerId,
                        path = subPath,
                        lastModified = sub.lastModified,
                        etag = sub.etag,
                        lastScannedMs = now,
                    ),
                )
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Non-fatal checkpoint sync failure
        }
    }

    // =========================================================================
    // Pure B-Tree Search (Delegated to MediaLibrarySearchCoordinator)
    // =========================================================================

    override fun search(
        query: String,
        serverId: Long?,
    ): Flow<LibrarySearchResult> = searchCoordinator.search(query, serverId)

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
    ): LibrarySyncResult = withContext(ioDispatcher) {
        if (server.isLocal || server.id == LOCAL_STORAGE_ID) {
            updateSyncState(
                LOCAL_STORAGE_ID,
                LibrarySyncState.Scanning(progress = 0f, currentPath = "local", tracksFound = 0),
            )
            try {
                val count = localStorageIndexScanner?.indexAllMountedFolders(
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
                updateSyncState(LOCAL_STORAGE_ID, LibrarySyncState.Failed(errorMsg))
                LibrarySyncResult.Error(errorMsg, e)
            }
        } else {
            if (webDavSyncScanner != null) {
                updateSyncState(
                    server.id,
                    LibrarySyncState.Scanning(progress = 0f, currentPath = "/", tracksFound = 0),
                )
                try {
                    val result = webDavSyncScanner.sync(
                        server = server,
                        force = force,
                        onProgress = { updateSyncState(server.id, it) },
                    )
                    when (result) {
                        is LibrarySyncResult.Success -> {
                            val total = mediaTrackIndexDao?.countByServerId(server.id)
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

    override suspend fun rebuildIndex(server: WebDavServer): LibrarySyncResult = withContext(ioDispatcher) {
        val targetServerId = if (server.isLocal || server.id == LOCAL_STORAGE_ID) LOCAL_STORAGE_ID else server.id
        try {
            directorySyncCheckpointDao?.deleteByServerId(targetServerId)
            mediaTrackIndexDao?.deleteByServerId(targetServerId)
        } catch (ce: CancellationException) {
            throw ce
        } catch (_: Exception) {
            // Non-fatal
        }
        syncServer(server, force = true)
    }

    // =========================================================================
    // Search History Management (Delegated to InternalSearchHistoryStore)
    // =========================================================================

    override fun getSearchHistory(): List<String> = searchHistoryStore.getSearchHistory()

    override fun recordSearchQuery(query: String): List<String> = searchHistoryStore.recordSearchQuery(query)

    override fun removeSearchHistory(query: String): List<String> = searchHistoryStore.removeSearchHistory(query)

    override fun clearSearchHistory() = searchHistoryStore.clearSearchHistory()

    // =========================================================================
    // Browsing Cursor Retention (Delegated to InternalBrowsingCursorStore)
    // =========================================================================

    override fun getLastBrowsingPath(serverId: Long): String = browsingCursorStore.getLastBrowsingPath(serverId)

    override fun setLastBrowsingPath(serverId: Long, path: String) {
        browsingCursorStore.setLastBrowsingPath(serverId, path)
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
            browsingCursorStore.clearCursor(serverId)
        }
    }

    override suspend fun clearLocalFolderData(uriString: String) {
        withContext(ioDispatcher) {
            val localRoot = try {
                LocalStorageIndexScanner.buildLocalRootPath(uriString)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                ""
            }
            synchronized(cacheLock) {
                val keysToRemove = memoryCache.keys.filter { key ->
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
                } catch (ce: CancellationException) {
                    throw ce
                } catch (_: Exception) {
                    // Non-fatal
                }
            }
            try {
                mediaTrackIndexDao?.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, uriString)
                directorySyncCheckpointDao?.deleteByServerAndPathPrefix(LOCAL_STORAGE_ID, uriString)
            } catch (ce: CancellationException) {
                throw ce
            } catch (_: Exception) {
                // Non-fatal
            }
            directoryCacheDao?.deleteCacheByServerId(LOCAL_STORAGE_ID)

            val currentCursor = browsingCursorStore.getLastBrowsingPath(LOCAL_STORAGE_ID)
            if ((localRoot.isNotEmpty() && (currentCursor.startsWith(localRoot) || currentCursor.contains(localRoot))) ||
                (uriString.isNotEmpty() && currentCursor.contains(uriString))
            ) {
                browsingCursorStore.setLastBrowsingPath(LOCAL_STORAGE_ID, "/")
            }
        }
    }

    override suspend fun clearAllCaches() {
        withContext(ioDispatcher) {
            clearMemoryCache()
            directoryCacheDao?.clearAll()
        }
    }

    override fun clearMemoryCache() {
        synchronized(cacheLock) { memoryCache.clear() }
    }
}
