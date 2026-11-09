package com.webdav.player.data.repository

import com.webdav.player.data.local.DirectoryCacheDao
import com.webdav.player.data.local.DirectoryCacheEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.local.RemoteDirectoryJsonSerializer
import com.webdav.player.data.metadata.MediaFilenameParser
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.data.remote.WebDavXmlParser
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.DirectoryContent
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFileType
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.repository.DirectoryRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow

class DirectoryRepositoryImpl(
    private val webDavClient: WebDavClient,
    private val directoryCacheDao: DirectoryCacheDao? = null,
    private val trackMetadataRepository: TrackMetadataRepository? = null,
    private val localStorageDirectoryResolver: LocalStorageDirectoryResolver? = null,
    private val mediaTrackIndexDao: MediaTrackIndexDao? = null,
) : DirectoryRepository {
    companion object {
        private const val MAX_DIRECTORY_CACHE_SIZE = 50
    }

    private val cacheLock = Any()
    private val memoryCache =
        object : LinkedHashMap<String, RemoteDirectory>(MAX_DIRECTORY_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RemoteDirectory>?): Boolean =
                size > MAX_DIRECTORY_CACHE_SIZE
        }

    override suspend fun getCachedDirectory(
        server: WebDavServer,
        path: String,
    ): RemoteDirectory? {
        val normalizedPath = RemoteDirectory.normalizePath(path)
        val cacheKey = "${server.id}:$normalizedPath"

        // 1. Check L1 memory cache
        val inMemory =
            synchronized(cacheLock) {
                memoryCache[cacheKey]
            }
        if (inMemory != null) {
            return inMemory
        }

        // 2. Check L2 Room persistent cache
        val cachedEntity = directoryCacheDao?.getCache(server.id, normalizedPath)
        if (cachedEntity != null) {
            val deserialized = RemoteDirectoryJsonSerializer.deserialize(cachedEntity.dataJson)
            if (deserialized != null) {
                synchronized(cacheLock) {
                    memoryCache[cacheKey] = deserialized
                }
                return deserialized
            }
        }

        return null
    }

    override fun observeDirectoryContent(
        server: WebDavServer,
        path: String,
        forceRefresh: Boolean,
    ): Flow<DirectoryContent> =
        flow {
            val normalizedPath = RemoteDirectory.normalizePath(path)
            val cachedDir = if (!forceRefresh) getCachedDirectory(server, normalizedPath) else null

            // 1. Cache hit (0ms)
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

            val isLocal = server.isLocal || server.id == LOCAL_STORAGE_ID
            if (isLocal) {
                observeLocalDirectoryContent(server, normalizedPath, cachedDir, forceRefresh)
                return@flow
            }

            // 2. Network query
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

    override suspend fun clearCache() {
        synchronized(cacheLock) {
            memoryCache.clear()
        }
        directoryCacheDao?.clearAll()
    }

    override fun clearMemoryCache() {
        synchronized(cacheLock) {
            memoryCache.clear()
        }
    }

    override suspend fun clearCacheForServer(serverId: Long) {
        val prefix = "$serverId:"
        synchronized(cacheLock) {
            val keysToRemove = memoryCache.keys.filter { it.startsWith(prefix) }
            keysToRemove.forEach { memoryCache.remove(it) }
        }
        directoryCacheDao?.deleteCacheByServerId(serverId)
    }

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
        val effectiveServerId = if (normalizedPath.startsWith("/local/")) LOCAL_STORAGE_ID else serverId
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
}
