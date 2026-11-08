package com.webdav.player.data.repository

import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.DirectorySyncCheckpointEntity
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.local.MediaTrackIndexEntity
import com.webdav.player.data.metadata.MediaFilenameParser
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.data.remote.WebDavXmlParser
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/**
 * Concurrency-limited WebDAV BFS directory synchronization scanner.
 * Traverses remote WebDAV trees with a concurrency ceiling (max 3 concurrent requests),
 * skips unchanged directory subtrees via timestamps and ETags, filters noise folders,
 * and batch-upserts discovered audio files into [MediaTrackIndexDao].
 */
class WebDavSyncScanner(
    private val webDavClient: WebDavClient,
    private val mediaTrackIndexDao: MediaTrackIndexDao,
    private val directorySyncCheckpointDao: DirectorySyncCheckpointDao,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val maxConcurrency: Int = DEFAULT_MAX_CONCURRENCY,
) {
    companion object {
        const val DEFAULT_MAX_CONCURRENCY = 3
        val EXCLUDED_DIR_NAMES =
            setOf(
                ".@eadir",
                "#recycle",
                "lost.dir",
                ".git",
                ".thumbnails",
                ".cache",
                "android",
                "__macosx",
            )

        fun isExcludedDirectory(name: String): Boolean {
            val clean = name.trim().trimEnd('/')
            if (clean.isEmpty() || clean == "." || clean == "..") return true
            val lower = clean.lowercase()
            if (lower.startsWith(".")) return true
            if (lower.startsWith("#recycle")) return true
            if (lower.startsWith(".trash-")) return true
            if (lower == "lost.dir") return true
            if (EXCLUDED_DIR_NAMES.any { it.equals(lower, ignoreCase = true) }) return true
            return false
        }
    }

    private data class DirectoryWorkItem(
        val path: String,
        val lastModified: Long = 0L,
        val etag: String? = null,
    )

    private val _syncState = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
    val syncState: Flow<LibrarySyncState> = _syncState.asStateFlow()

    suspend fun sync(
        server: WebDavServer,
        force: Boolean = false,
        onProgress: ((LibrarySyncState) -> Unit)? = null,
        rootPath: String = server.pathPrefix.ifBlank { "/" },
    ): LibrarySyncResult =
        withContext(ioDispatcher) {
            val normalizedRoot = RemoteDirectory.normalizePath(rootPath)

            val directoriesScanned = AtomicInteger(0)
            val directoriesSkipped = AtomicInteger(0)
            val totalIndexedTracks = AtomicInteger(0)
            val totalDiscoveredDirs = AtomicInteger(1)
            val partialErrors = mutableListOf<String>()

            fun emitProgress(currentPath: String) {
                val total = totalDiscoveredDirs.get()
                val completed = directoriesScanned.get() + directoriesSkipped.get()
                val progress = if (total > 0) (completed.toFloat() / total.toFloat()).coerceIn(0f, 0.99f) else 0f
                val state =
                    LibrarySyncState.Scanning(
                        progress = progress,
                        currentPath = currentPath,
                        tracksFound = totalIndexedTracks.get(),
                    )
                _syncState.value = state
                onProgress?.invoke(state)
            }

            _syncState.value =
                LibrarySyncState.Scanning(
                    progress = 0f,
                    currentPath = normalizedRoot,
                    tracksFound = 0,
                )
            onProgress?.invoke(_syncState.value)

            val concurrencyLimit = maxConcurrency.coerceAtLeast(1)
            val semaphore = Semaphore(concurrencyLimit)
            val channel = Channel<DirectoryWorkItem>(Channel.UNLIMITED)
            val activeTasks = AtomicInteger(0)

            activeTasks.incrementAndGet()
            channel.send(DirectoryWorkItem(path = normalizedRoot))

            coroutineScope {
                val workers =
                    (1..concurrencyLimit).map {
                        launch {
                            for (item in channel) {
                                try {
                                    emitProgress(item.path)

                                    // 1. Checkpoint skip check prior to network call if timestamp or etag is already known
                                    if (!force) {
                                        val checkpoint = directorySyncCheckpointDao.getCheckpoint(server.id, item.path)
                                        if (checkpoint != null) {
                                            val etagMatches =
                                                item.etag != null && checkpoint.etag != null && item.etag == checkpoint.etag
                                            val timestampMatches =
                                                item.lastModified > 0L && checkpoint.lastModified > 0L &&
                                                    item.lastModified == checkpoint.lastModified
                                            if (etagMatches || timestampMatches) {
                                                directoriesSkipped.incrementAndGet()
                                                emitProgress(item.path)
                                                continue
                                            }
                                        }
                                    }

                                    // 2. Perform concurrency-limited remote listDirectory call
                                    val listResult =
                                        try {
                                            semaphore.withPermit {
                                                webDavClient.listDirectory(server, item.path)
                                            }
                                        } catch (ce: CancellationException) {
                                            throw ce
                                        } catch (e: Exception) {
                                            ListDirectoryResult.Failure(e.message ?: "Network error", cause = e)
                                        }

                                    when (listResult) {
                                        is ListDirectoryResult.Failure -> {
                                            synchronized(partialErrors) {
                                                partialErrors.add("${item.path}: ${listResult.message}")
                                            }
                                        }

                                        is ListDirectoryResult.Success -> {
                                            val remoteDir = listResult.directory

                                            // Checkpoint skip check post-fetch if directory was not pre-validated
                                            if (!force) {
                                                val checkpoint =
                                                    directorySyncCheckpointDao.getCheckpoint(server.id, item.path)
                                                if (checkpoint != null) {
                                                    val etagMatches =
                                                        remoteDir.etag != null && checkpoint.etag != null &&
                                                            remoteDir.etag == checkpoint.etag
                                                    val timestampMatches =
                                                        remoteDir.lastModified > 0L && checkpoint.lastModified > 0L &&
                                                            remoteDir.lastModified == checkpoint.lastModified
                                                    if (etagMatches || timestampMatches) {
                                                        directoriesSkipped.incrementAndGet()
                                                        for (subDir in remoteDir.subDirectories) {
                                                            if (!isExcludedDirectory(subDir.name)) {
                                                                totalDiscoveredDirs.incrementAndGet()
                                                                activeTasks.incrementAndGet()
                                                                channel.send(
                                                                    DirectoryWorkItem(
                                                                        path = RemoteDirectory.normalizePath(subDir.path),
                                                                        lastModified = subDir.lastModified,
                                                                        etag = subDir.etag,
                                                                    ),
                                                                )
                                                            }
                                                        }
                                                        emitProgress(item.path)
                                                        continue
                                                    }
                                                }
                                            }

                                            directoriesScanned.incrementAndGet()

                                            // 3. Batch upsert discovered audio tracks
                                            val discoveredTracks = mutableListOf<MediaTrackIndexEntity>()
                                            for (file in remoteDir.files) {
                                                val ext = file.name.substringAfterLast('.', "").lowercase()
                                                val audioFormat =
                                                    AudioFormat.fromExtension(ext)
                                                        ?: AudioFormat.fromFileName(file.name)

                                                if (file.isAudio || audioFormat != null) {
                                                    val format = audioFormat ?: AudioFormat.MP3
                                                    val parsed = MediaFilenameParser.parse(file.name)
                                                    val lastMod = WebDavXmlParser.parseHttpDate(file.lastModified)
                                                    val entity =
                                                        MediaTrackIndexEntity(
                                                            serverId = server.id,
                                                            remotePath = file.path,
                                                            fileName = file.name,
                                                            fileSize = file.size,
                                                            lastModified = lastMod,
                                                            format = format.extension.uppercase(),
                                                            parentPath = item.path,
                                                            parsedTitle = parsed.title,
                                                            parsedArtist = parsed.artist,
                                                        )
                                                    discoveredTracks.add(entity)
                                                }
                                            }

                                            if (discoveredTracks.isNotEmpty()) {
                                                mediaTrackIndexDao.upsertTracks(discoveredTracks)
                                                totalIndexedTracks.addAndGet(discoveredTracks.size)
                                            }

                                            // 4. Clean up deleted tracks in this scanned directory
                                            try {
                                                val existing =
                                                    mediaTrackIndexDao.getTracksByParentPath(server.id, item.path)
                                                val discoveredPaths = discoveredTracks.map { it.remotePath }.toSet()
                                                val staleTracks = existing.filter { it.remotePath !in discoveredPaths }
                                                if (staleTracks.isNotEmpty()) {
                                                    mediaTrackIndexDao.deleteByServerAndPaths(
                                                        server.id,
                                                        staleTracks.map { it.remotePath },
                                                    )
                                                }
                                            } catch (ce: CancellationException) {
                                                throw ce
                                            } catch (_: Exception) {
                                                // Non-fatal cleanup issue
                                            }

                                            // 5. Update directory sync checkpoint
                                            val effectiveLastMod =
                                                if (remoteDir.lastModified > 0L) remoteDir.lastModified else item.lastModified
                                            val effectiveEtag = remoteDir.etag ?: item.etag
                                            directorySyncCheckpointDao.upsertCheckpoint(
                                                DirectorySyncCheckpointEntity(
                                                    serverId = server.id,
                                                    path = item.path,
                                                    lastModified = effectiveLastMod,
                                                    etag = effectiveEtag,
                                                    lastScannedMs = System.currentTimeMillis(),
                                                ),
                                            )

                                            // 6. BFS enqueue valid subdirectories
                                            for (subDir in remoteDir.subDirectories) {
                                                if (isExcludedDirectory(subDir.name)) {
                                                    continue
                                                }
                                                totalDiscoveredDirs.incrementAndGet()
                                                activeTasks.incrementAndGet()
                                                channel.send(
                                                    DirectoryWorkItem(
                                                        path = RemoteDirectory.normalizePath(subDir.path),
                                                        lastModified = subDir.lastModified,
                                                        etag = subDir.etag,
                                                    ),
                                                )
                                            }

                                            emitProgress(item.path)
                                        }
                                    }
                                } catch (ce: CancellationException) {
                                    throw ce
                                } catch (e: Exception) {
                                    synchronized(partialErrors) {
                                        partialErrors.add("${item.path}: ${e.message}")
                                    }
                                } finally {
                                    if (activeTasks.decrementAndGet() == 0) {
                                        channel.close()
                                    }
                                }
                            }
                        }
                    }
                workers.joinAll()
            }

            if (directoriesScanned.get() == 0 && directoriesSkipped.get() == 0 && partialErrors.isNotEmpty()) {
                val errorMsg = partialErrors.firstOrNull() ?: "Synchronization failed"
                val state = LibrarySyncState.Failed(errorMsg)
                _syncState.value = state
                onProgress?.invoke(state)
                LibrarySyncResult.Error(errorMsg)
            } else {
                val serverTotalTracks = mediaTrackIndexDao.countByServerId(server.id)
                val state = LibrarySyncState.Completed(totalTracks = serverTotalTracks)
                _syncState.value = state
                onProgress?.invoke(state)
                LibrarySyncResult.Success(
                    totalTracksIndexed = totalIndexedTracks.get(),
                    directoriesScanned = directoriesScanned.get(),
                    directoriesSkipped = directoriesSkipped.get(),
                )
            }
        }
}
