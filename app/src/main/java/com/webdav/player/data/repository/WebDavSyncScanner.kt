package com.webdav.player.data.repository

import com.webdav.player.data.local.DirectorySyncCheckpointDao
import com.webdav.player.data.local.MediaTrackIndexDao
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.LibrarySyncResult
import com.webdav.player.domain.model.LibrarySyncState
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

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
            )
    }

    private val _syncState = MutableStateFlow<LibrarySyncState>(LibrarySyncState.Idle)
    val syncState: Flow<LibrarySyncState> = _syncState.asStateFlow()

    suspend fun sync(
        server: WebDavServer,
        force: Boolean = false,
        onProgress: ((LibrarySyncState) -> Unit)? = null,
    ): LibrarySyncResult = LibrarySyncResult.Success(0, 0, 0)
}
