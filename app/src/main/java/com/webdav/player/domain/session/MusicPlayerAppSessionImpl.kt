package com.webdav.player.domain.session

import androidx.annotation.VisibleForTesting
import com.webdav.player.data.player.StreamingHardwareLockManager
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.domain.model.Playlist
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.network.NetworkConnectivityObserver
import com.webdav.player.domain.player.AudioPlayerEngine
import com.webdav.player.domain.repository.PlaybackSessionStore
import com.webdav.player.domain.repository.PlaylistRepository
import com.webdav.player.domain.repository.ServerRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Domain playback session orchestrator managing queues, streaming resilience, and error state.
 */
class MusicPlayerAppSessionImpl(
    private val playerEngine: AudioPlayerEngine,
    private val serverRepository: ServerRepository? = null,
    trackMetadataRepository: TrackMetadataRepository? = null,
    sessionStore: PlaybackSessionStore? = null,
    private val playlistRepository: PlaylistRepository? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    progressDispatcher: CoroutineDispatcher =
        (coroutineScope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher)?.takeIf {
            it !is kotlinx.coroutines.MainCoroutineDispatcher
        } ?: Dispatchers.Default,
    periodicDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val networkConnectivityObserver: NetworkConnectivityObserver? = null,
    private val hardwareLockManager: StreamingHardwareLockManager? = null,
) : MusicPlayerAppSession {
    private val _sessionState = MutableStateFlow(PlayerSessionState())
    override val sessionState: StateFlow<PlayerSessionState> = _sessionState.asStateFlow()

    private val _playbackProgress = MutableStateFlow(PlaybackProgress.ZERO)
    override val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()

    private var hasStreamLoaded = false

    private val persistenceCoordinator: SessionPersistenceCoordinator =
        SessionPersistenceCoordinator(
            sessionStore = sessionStore,
            serverRepository = serverRepository,
            trackMetadataRepository = trackMetadataRepository,
            playerEngine = playerEngine,
            sessionStateFlow = _sessionState,
            playbackProgressFlow = _playbackProgress,
            coroutineScope = coroutineScope,
            periodicDispatcher = periodicDispatcher,
        )
    override val isRestored: StateFlow<Boolean> = persistenceCoordinator.isRestored

    private val queueManager =
        PlaybackQueueManager(_sessionState, serverRepository) { coroutineScope.launch { flushSession() } }
    private val faultTolerance = PlaybackFaultTolerance(_sessionState, playerEngine, playlistRepository, queueManager, coroutineScope)
    private val metadataCoordinator = PlaybackMetadataCoordinator(trackMetadataRepository, playerEngine, _sessionState, coroutineScope)

    @VisibleForTesting val isPrerollAppended = AtomicBoolean(false)

    private val timelineCoordinator: PlaybackTimelineCoordinator =
        PlaybackTimelineCoordinator(
            playerEngine = playerEngine,
            serverRepository = serverRepository,
            sessionStateFlow = _sessionState,
            playbackProgressFlow = _playbackProgress,
            faultTolerance = faultTolerance,
            queueManager = queueManager,
            persistenceCoordinator = persistenceCoordinator,
            coroutineScope = coroutineScope,
            progressDispatcher = progressDispatcher,
            isPrerollAppended = isPrerollAppended,
            getHasStreamLoaded = { hasStreamLoaded },
            clearPrerollIfActive = { clearPrerollIfActive() },
            onRestoreSession = { restoreSession() },
            onSetActiveServer = { setActiveServer(it, resetPlayback = false) },
            onSkipToNext = { skipToNext() },
            onTransitionToQueueIndex = { s, idx -> transitionToQueueIndex(s, idx) },
        )

    private val _pausedByTransientAudioFocus = AtomicBoolean(false)
    override val pausedByTransientAudioFocus: Boolean get() = _pausedByTransientAudioFocus.get()

    @VisibleForTesting val lastBufferingTimestampMs: AtomicLong get() = faultTolerance.lastBufferingTimestampMs

    private fun clearTransientAudioFocusFlag() = _pausedByTransientAudioFocus.set(false)

    private fun clearPrerollIfActive() {
        if (isPrerollAppended.compareAndSet(true, false)) playerEngine.clearPrerollTrack()
    }

    private fun resetPlaybackState() {
        clearPrerollIfActive()
        stopPeriodicFlush()
        hasStreamLoaded = false
    }

    private fun loadTrackIntoEngine(
        server: WebDavServer?,
        track: AudioTrack,
        startPositionMs: Long = 0L,
    ) {
        clearKilledBySystemFlag()
        clearTransientAudioFocusFlag()

        val isLocalTrack = track.isLocal

        if (isLocalTrack) {
            faultTolerance.cancelWatchdog()
            hardwareLockManager?.acquireWakeLockOnly()
        } else {
            hardwareLockManager?.acquireLocks()
        }

        fun dispatch(target: WebDavServer) {
            _sessionState.update { it.copy(activeServer = target) }
            hasStreamLoaded = true
            try {
                playerEngine.loadTrack(target, track, startPositionMs)
            } catch (e: Throwable) {
                faultTolerance.handleLoadException(e, track) { skipToNext() }
                return
            }
            coroutineScope.launch { flushSessionWithCleanExit(isCleanExit = false) }
        }
        when {
            server != null && server.id == track.serverId -> {
                dispatch(server)
            }

            isLocalTrack -> {
                dispatch(WebDavServer.createLocalStorage())
            }

            serverRepository != null -> {
                coroutineScope.launch {
                    serverRepository.getServerById(track.serverId)?.let { dispatch(it) }
                        ?: faultTolerance.handleServerUnavailable(track) { skipToNext() }
                }
            }

            server != null -> {
                dispatch(server)
            }

            else -> {
                coroutineScope.launch { faultTolerance.handleServerUnavailable(track) { skipToNext() } }
            }
        }
    }

    private fun dispatchQueueLoad(
        server: WebDavServer,
        request: QueueLoadRequest,
        directoryPath: String? = null,
    ) {
        clearKilledBySystemFlag()
        clearTransientAudioFocusFlag()
        faultTolerance.cancelWatchdog()
        persistenceCoordinator.markRestored()
        queueManager.clearFailedTracks()
        _playbackProgress.value = PlaybackProgress(0L, request.targetTrack.durationMs, 0L)
        _sessionState.update {
            it.copy(
                queue = request.queue,
                durationMs = request.targetTrack.durationMs,
                currentDirectoryPath = directoryPath ?: it.currentDirectoryPath,
                errorMessage = null,
            )
        }
        loadTrackIntoEngine(server, request.targetTrack, request.startPositionMs)
        coroutineScope.launch { flushSession() }
    }

    private fun transitionToQueueIndex(
        server: WebDavServer?,
        targetIndex: Int,
    ) {
        faultTolerance.cancelWatchdog()
        val queue = _sessionState.value.queue
        val targetTrack = queue.tracks.getOrNull(targetIndex) ?: return
        _playbackProgress.value = PlaybackProgress(0L, targetTrack.durationMs, 0L)
        _sessionState.update {
            it.copy(
                queue = queue.copy(currentIndex = targetIndex),
                durationMs = targetTrack.durationMs,
                isUnsupportedRange = false,
                isWeakNetwork = false,
                canManualRetry = false,
                isReconnecting = false,
            )
        }
        coroutineScope.launch {
            val isLocal = targetTrack.isLocal
            val effectiveServer =
                when {
                    isLocal -> {
                        WebDavServer.createLocalStorage()
                    }

                    server != null && server.id == targetTrack.serverId -> {
                        server
                    }

                    serverRepository != null -> {
                        serverRepository.getServerById(targetTrack.serverId) ?: server
                            ?: _sessionState.value.activeServer
                    }

                    else -> {
                        server ?: _sessionState.value.activeServer
                    }
                }
            loadTrackIntoEngine(effectiveServer, targetTrack, 0L)
            flushSession()
        }
    }

    private val networkObservationJob: Job? =
        networkConnectivityObserver?.let { observer ->
            coroutineScope.launch {
                observer.networkStatus
                    .map { it.isConnected }
                    .distinctUntilChanged()
                    .drop(1)
                    .collect { isConnected ->
                        if (isConnected) {
                            faultTolerance.onNetworkRestored {
                                resumeCurrentTrack()
                            }
                            playlistRepository?.triggerReachabilitySelfHealing()
                        }
                    }
            }
        }

    init {
        timelineCoordinator.startObservations()
        metadataCoordinator.startMetadataObservation()
        metadataCoordinator.startLyricsObservation()
    }

    override fun getLastDirectoryForServer(serverId: Long): String = persistenceCoordinator.getLastDirectoryForServer(serverId)

    override fun setActiveServer(server: WebDavServer?) {
        setActiveServer(server, resetPlayback = true)
    }

    override fun setActiveServer(
        server: WebDavServer?,
        resetPlayback: Boolean,
    ) {
        _sessionState.update { current ->
            if (current.activeServer?.id == server?.id) return@update current.copy(activeServer = server)
            val lastPath = if (server != null) getLastDirectoryForServer(server.id) else "/"
            if (current.hasTrack && !resetPlayback) {
                // Do not disrupt ongoing playback or wipe queue when switching active browsing storage/server
                return@update current.copy(
                    activeServer = server,
                    currentDirectoryPath = lastPath,
                )
            }
            if (current.queue.isPlaylistQueue) return@update current.copy(activeServer = server)

            if (current.playbackState !is PlaybackState.Idle) playerEngine.stop()
            resetPlaybackState()
            _playbackProgress.value = PlaybackProgress.ZERO
            current.copy(
                activeServer = server,
                queue = PlaybackQueue.EMPTY,
                playbackState = PlaybackState.Idle,
                durationMs = 0L,
                currentDirectoryPath = lastPath,
                errorMessage = null,
                lyrics = null,
                isLoadingLyrics = false,
            )
        }
        if (server != null) {
            playerEngine.setActiveServer(server)
        }
    }

    override fun playDirectoryTrack(
        directory: RemoteDirectory,
        selectedFile: RemoteFile,
        initialMetadata: Map<String, TrackMetadata>,
    ) {
        val server = _sessionState.value.activeServer ?: return
        val request = queueManager.buildDirectoryQueue(directory, selectedFile, initialMetadata, server = server) ?: return
        dispatchQueueLoad(server, request, directory.path)
        metadataCoordinator.enrichQueueTracksFromCache(server, request.queue)
    }

    override fun playTrack(track: AudioTrack) {
        val isLocalTrack = track.isLocal
        coroutineScope.launch {
            val effectiveServer =
                when {
                    isLocalTrack -> WebDavServer.createLocalStorage()
                    serverRepository != null -> serverRepository.getServerById(track.serverId) ?: _sessionState.value.activeServer
                    else -> _sessionState.value.activeServer
                } ?: return@launch
            val request = queueManager.buildTrackQueue(track)
            dispatchQueueLoad(effectiveServer, request)
            metadataCoordinator.enrichSingleTrackMetadata(effectiveServer, track)
        }
    }

    override fun playNext(track: AudioTrack) =
        queueManager.playNext(track, _sessionState.value.activeServer != null || serverRepository != null) { playTrack(it) }

    override fun playNextAndAdvance(track: AudioTrack) {
        queueManager.playNextAndAdvance(
            track = track,
            hasServerOrRepo = (_sessionState.value.activeServer != null || serverRepository != null),
            onPlayTrack = { playTrack(it) },
            onTransitionToIndex = { targetIndex -> transitionToQueueIndex(_sessionState.value.activeServer, targetIndex) },
        )
    }

    private fun resumeCurrentTrack() {
        val current = _sessionState.value
        val server = current.activeServer ?: return
        val currentTrack = current.currentTrack ?: return
        loadTrackIntoEngine(server, currentTrack, _playbackProgress.value.currentPositionMs)
    }

    override fun togglePlayPause() {
        if (_sessionState.value.isPlaying || _sessionState.value.isBuffering) pause() else play()
    }

    override fun play() {
        clearKilledBySystemFlag()
        clearTransientAudioFocusFlag()
        val current = _sessionState.value
        if (current.isPaused) {
            if ((!hasStreamLoaded || playerEngine.playbackState.value is PlaybackState.Idle) && current.currentTrack != null &&
                current.activeServer != null
            ) {
                resumeCurrentTrack()
            } else {
                playerEngine.play()
            }
        } else if (current.currentTrack != null && current.activeServer != null) {
            resumeCurrentTrack()
        }
    }

    override fun pause() {
        clearTransientAudioFocusFlag()
        pauseInternal()
    }

    private fun pauseInternal() {
        clearPrerollIfActive()
        faultTolerance.cancelWatchdog()
        if (hasStreamLoaded) playerEngine.pause()
        _sessionState.update {
            it.copy(
                isWeakNetwork = false,
                canManualRetry = false,
                isReconnecting = false,
            )
        }
        coroutineScope.launch {
            val pos = playerEngine.currentPositionMs.value
            if (pos > 0L) {
                persistenceCoordinator.savePosition(pos, isCleanExit = true)
            }
            flushSessionWithCleanExit(isCleanExit = true)
        }
    }

    override fun onAudioFocusTransientLoss() {
        if (_sessionState.value.isPlaying || _sessionState.value.isBuffering) {
            _pausedByTransientAudioFocus.set(true)
            pauseInternal()
        } else {
            _pausedByTransientAudioFocus.set(false)
        }
    }

    override fun onAudioFocusGain() {
        if (_pausedByTransientAudioFocus.compareAndSet(true, false)) play()
    }

    override fun onAudioFocusLoss() {
        _pausedByTransientAudioFocus.set(false)
        pauseInternal()
    }

    override fun seekTo(positionMs: Long) {
        clearPrerollIfActive()
        val clamped = positionMs.coerceAtLeast(0L)
        _playbackProgress.update { it.copy(currentPositionMs = clamped) }
        if (playerEngine.playbackState.value !is PlaybackState.Idle) playerEngine.seekTo(clamped)
        persistenceCoordinator.savePosition(clamped)
    }

    override fun playPlaylist(
        playlist: Playlist,
        startIndex: Int,
    ) {
        coroutineScope.launch {
            val entries = playlistRepository?.getTracksForPlaylist(playlist.id) ?: emptyList()
            val request = queueManager.buildPlaylistQueue(entries, startIndex)
            if (request == null) {
                _sessionState.update { it.copy(queue = PlaybackQueue.EMPTY, errorMessage = null) }
                return@launch
            }
            val target = request.targetTrack
            val isLocal = target.isLocal
            val active =
                when {
                    isLocal -> WebDavServer.createLocalStorage()
                    _sessionState.value.activeServer?.id == target.serverId -> _sessionState.value.activeServer
                    serverRepository != null -> serverRepository.getServerById(target.serverId) ?: _sessionState.value.activeServer
                    else -> _sessionState.value.activeServer
                } ?: return@launch
            dispatchQueueLoad(active, request)
        }
    }

    override fun skipToNext() {
        clearTransientAudioFocusFlag()
        clearPrerollIfActive()
        val queue = _sessionState.value.queue
        if (!queue.isEmpty) {
            val mode = _sessionState.value.playbackMode
            if (mode == PlaybackMode.SHUFFLE && queue.currentIndex >= queue.tracks.lastIndex) {
                val reshuffled = queue.reshuffle()
                _sessionState.update { it.copy(queue = reshuffled) }
                transitionToQueueIndex(_sessionState.value.activeServer, 0)
            } else {
                transitionToQueueIndex(
                    _sessionState.value.activeServer,
                    queueManager.calculateNextQueueIndex(queue, mode),
                )
            }
        }
    }

    override fun skipToPrevious() {
        clearTransientAudioFocusFlag()
        clearPrerollIfActive()
        val queue = _sessionState.value.queue
        if (queue.isEmpty) return
        if (playerEngine.currentPositionMs.value > 3000L) {
            seekTo(0L)
            return
        }
        transitionToQueueIndex(
            _sessionState.value.activeServer,
            queueManager.calculatePreviousQueueIndex(queue, _sessionState.value.playbackMode),
        )
    }

    override fun playQueueIndex(index: Int) {
        clearTransientAudioFocusFlag()
        clearPrerollIfActive()
        if (index in _sessionState.value.queue.tracks.indices) {
            queueManager.clearFailedTracks()
            transitionToQueueIndex(_sessionState.value.activeServer, index)
        }
    }

    override fun cyclePlaybackMode() = queueManager.cyclePlaybackMode()

    override fun setPlaybackMode(mode: PlaybackMode) = queueManager.setPlaybackMode(mode)

    override fun removeQueueTrack(index: Int) {
        clearPrerollIfActive()
        queueManager.removeQueueTrack(
            index = index,
            onQueueCleared = {
                resetPlaybackState()
                playerEngine.stop()
                _playbackProgress.value = PlaybackProgress.ZERO
            },
            onCurrentTrackChanged = { nextTrack ->
                _playbackProgress.value = PlaybackProgress(0L, nextTrack.durationMs, 0L)
                val isLocal = nextTrack.isLocal
                coroutineScope.launch {
                    val effectiveServer =
                        when {
                            isLocal -> {
                                WebDavServer.createLocalStorage()
                            }

                            _sessionState.value.activeServer?.id == nextTrack.serverId -> {
                                _sessionState.value.activeServer
                            }

                            serverRepository != null -> {
                                serverRepository.getServerById(nextTrack.serverId)
                                    ?: _sessionState.value.activeServer
                            }

                            else -> {
                                _sessionState.value.activeServer
                            }
                        }
                    effectiveServer?.let { loadTrackIntoEngine(it, nextTrack, 0L) }
                }
            },
        )
    }

    override fun retryPlayback() {
        faultTolerance.cancelWatchdog()
        _sessionState.update { it.copy(isWeakNetwork = false, canManualRetry = false, isReconnecting = true) }
        resumeCurrentTrack()
    }

    @VisibleForTesting
    fun setWeakNetworkState(
        isWeakNetwork: Boolean,
        canManualRetry: Boolean,
    ) = faultTolerance.setWeakNetworkState(isWeakNetwork, canManualRetry)

    override fun stop() {
        clearTransientAudioFocusFlag()
        resetPlaybackState()
        faultTolerance.cancelWatchdog()
        playerEngine.stop()
        _playbackProgress.value = PlaybackProgress.ZERO
        _sessionState.update {
            it.copy(
                playbackState = PlaybackState.Idle,
                isWeakNetwork = false,
                canManualRetry = false,
                isReconnecting = false,
            )
        }
        coroutineScope.launch { flushSessionWithCleanExit(isCleanExit = true) }
    }

    override fun clearKilledBySystemFlag() {
        if (_sessionState.value.wasKilledBySystem) _sessionState.update { it.copy(wasKilledBySystem = false) }
    }

    override fun confirmUnsupportedRangeReplay() {
        faultTolerance.confirmUnsupportedRangeReplay onReplay@{
            val server = _sessionState.value.activeServer ?: return@onReplay
            val track = _sessionState.value.currentTrack ?: return@onReplay
            _playbackProgress.value = PlaybackProgress(0L, track.durationMs, 0L)
            loadTrackIntoEngine(server, track, 0L)
        }
    }

    override fun dismissUnsupportedRange() = faultTolerance.dismissUnsupportedRange()

    override fun release() {
        resetPlaybackState()
        timelineCoordinator.cancelObservations()
        networkObservationJob?.cancel()
        playerEngine.release()
        _playbackProgress.value = PlaybackProgress.ZERO
    }

    override fun flushSessionAsync() = persistenceCoordinator.flushSessionAsync()

    private fun stopPeriodicFlush(): Unit = persistenceCoordinator.stopPeriodicFlush()

    override fun setCurrentDirectoryPath(path: String) = persistenceCoordinator.setCurrentDirectoryPath(path)

    override suspend fun restoreSession() = persistenceCoordinator.restoreSession()

    override suspend fun flushSession() = persistenceCoordinator.flushSession()

    suspend fun flushSessionWithCleanExit(isCleanExit: Boolean) = persistenceCoordinator.flushSessionWithCleanExit(isCleanExit)
}
