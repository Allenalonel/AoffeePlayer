package com.webdav.player.domain.session

import androidx.annotation.VisibleForTesting
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
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
import com.webdav.player.domain.storage.StorageReachabilityTracker
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Domain playback session orchestrator and single authoritative commit spine for
 * session state, engine invocation, server resolution, reachability reporting, and persistence.
 */
class MusicPlayerAppSessionImpl(
    private val playerEngine: AudioPlayerEngine,
    private val serverRepository: ServerRepository? = null,
    trackMetadataRepository: TrackMetadataRepository? = null,
    sessionStore: PlaybackSessionStore? = null,
    private val playlistRepository: PlaylistRepository? = null,
    private val storageReachabilityTracker: StorageReachabilityTracker? = null,
    private val coroutineScope: CoroutineScope = CoroutineScope(Dispatchers.Main + SupervisorJob()),
    progressDispatcher: CoroutineDispatcher =
        (coroutineScope.coroutineContext[kotlin.coroutines.ContinuationInterceptor] as? CoroutineDispatcher)?.takeIf {
            it !is kotlinx.coroutines.MainCoroutineDispatcher
        } ?: Dispatchers.Default,
    periodicDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val networkConnectivityObserver: NetworkConnectivityObserver? = null,
) : MusicPlayerAppSession {
    private val _sessionState = MutableStateFlow(PlayerSessionState())
    override val sessionState: StateFlow<PlayerSessionState> = _sessionState.asStateFlow()
    private val _playbackProgress = MutableStateFlow(PlaybackProgress.ZERO)
    override val playbackProgress: StateFlow<PlaybackProgress> = _playbackProgress.asStateFlow()
    private var hasStreamLoaded = false

    private val persistenceCoordinator =
        SessionPersistenceCoordinator(
            sessionStore,
            serverRepository,
            trackMetadataRepository,
            playerEngine,
            _sessionState,
            _playbackProgress,
            coroutineScope,
            periodicDispatcher,
        )
    override val isRestored: StateFlow<Boolean> = persistenceCoordinator.isRestored
    private val queueManager = PlaybackQueueManager()
    private val faultTolerance = PlaybackFaultTolerance(coroutineScope)
    private val metadataCoordinator = PlaybackMetadataCoordinator(trackMetadataRepository, playerEngine, _sessionState, coroutineScope)

    @VisibleForTesting val isPrerollAppended = AtomicBoolean(false)
    private val timelineCoordinator =
        PlaybackTimelineCoordinator(
            playerEngine,
            coroutineScope,
            progressDispatcher,
            { _sessionState.value.currentTrack?.durationMs ?: 0L },
            ::handlePlaybackStateChanged,
            ::handleTrackTransition,
            ::handleProgressUpdated,
            ::handleDurationChanged,
        )
    private val _pausedByTransientAudioFocus = AtomicBoolean(false)
    override val pausedByTransientAudioFocus: Boolean get() = _pausedByTransientAudioFocus.get()

    @VisibleForTesting val lastBufferingTimestampMs: AtomicLong get() = faultTolerance.lastBufferingTimestampMs

    private fun clearPrerollIfActive() {
        if (isPrerollAppended.compareAndSet(true, false)) playerEngine.clearPrerollTrack()
    }

    private fun haltEngineAndClearProgress() {
        resetPlaybackState()
        faultTolerance.cancelWatchdog()
        playerEngine.stop()
        _playbackProgress.value = PlaybackProgress.ZERO
    }

    private fun canSkipTrack(track: AudioTrack): Boolean = queueManager.recordFailedTrack(track.id, _sessionState.value.queue.size)

    private suspend fun resolveServerForTrack(
        track: AudioTrack,
        preferred: WebDavServer? = null,
        fallback: WebDavServer? = _sessionState.value.activeServer,
    ): WebDavServer? {
        if (track.isLocal || track.serverId == LOCAL_STORAGE_ID) return WebDavServer.createLocalStorage()
        if (preferred?.id == track.serverId) return preferred
        if (fallback?.id == track.serverId) return fallback
        val repo = serverRepository ?: return preferred ?: fallback
        return try {
            repo.getServerById(track.serverId)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Throwable) {
            null
        } ?: preferred ?: fallback
    }

    private fun awaitRestoredAndRun(
        requireTrack: Boolean = true,
        action: () -> Unit,
    ) {
        if (isRestored.value || _sessionState.value.queue.isNotEmpty) return action()
        coroutineScope.launch {
            isRestored.first { it }
            val ready = if (requireTrack) _sessionState.value.hasTrack else _sessionState.value.queue.isNotEmpty
            if (ready) action()
        }
    }

    private fun applyFaultDecision(decision: FaultDecision) {
        if (decision is FaultDecision.PauseForUnsupportedRange) {
            playerEngine.pause()
            persistenceCoordinator.stopPeriodicFlush()
        }
        decision.unavailableTrack?.let { (s, p, r) -> storageReachabilityTracker?.reportTrackUnavailable(s, p, r) }
        decision.unavailableServer?.let { (s, r) -> storageReachabilityTracker?.reportServerUnavailable(s, r) }
        if (decision is FaultDecision.SkipTrack) return skipToNext()
        if (decision !is FaultDecision.Ignored) _sessionState.update { faultTolerance.reduceStateForDecision(it, decision) }
    }

    private fun loadTrackIntoEngine(
        server: WebDavServer?,
        track: AudioTrack,
        startPositionMs: Long = 0L,
    ) {
        clearKilledBySystemFlag()
        _pausedByTransientAudioFocus.set(false)
        if (track.isLocal) faultTolerance.cancelWatchdog()

        fun dispatch(target: WebDavServer) {
            _sessionState.update { it.copy(activeServer = target) }
            hasStreamLoaded = true
            try {
                playerEngine.loadTrack(target, track, startPositionMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                return applyFaultDecision(faultTolerance.handleLoadException(e, track, ::canSkipTrack))
            }
            coroutineScope.launch { flushSessionWithCleanExit(isCleanExit = false) }
        }
        val immediate =
            when {
                track.isLocal -> WebDavServer.createLocalStorage()
                server != null && server.id == track.serverId -> server
                serverRepository == null -> server
                else -> null
            }
        if (immediate != null) return dispatch(immediate)
        coroutineScope.launch {
            resolveServerForTrack(track, server, server)?.let(::dispatch)
                ?: applyFaultDecision(faultTolerance.handleServerUnavailable(track, ::canSkipTrack))
        }
    }

    private fun dispatchQueueLoad(
        server: WebDavServer?,
        request: QueueLoadRequest,
    ) {
        faultTolerance.cancelWatchdog()
        persistenceCoordinator.markRestored()
        queueManager.clearFailedTracks()
        _playbackProgress.value = PlaybackProgress(0L, request.targetTrack.durationMs, 0L)
        _sessionState.update { it.copy(queue = request.queue, durationMs = request.targetTrack.durationMs, errorMessage = null) }
        loadTrackIntoEngine(server, request.targetTrack, request.startPositionMs)
        flushSessionAsync()
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
            it
                .withClearedNetworkFlags(isUnsupportedRange = false)
                .copy(queue = queue.copy(currentIndex = targetIndex), durationMs = targetTrack.durationMs)
        }
        coroutineScope.launch {
            loadTrackIntoEngine(resolveServerForTrack(targetTrack, server), targetTrack, 0L)
            flushSession()
        }
    }

    private fun advanceQueueToNext(server: WebDavServer?) {
        val state = _sessionState.value
        val next = queueManager.advanceToNext(state.queue, state.playbackMode) ?: return
        next.reshuffledQueue?.let { q -> _sessionState.update { it.copy(queue = q) } }
        transitionToQueueIndex(server, next.targetIndex)
    }

    private fun resetPlaybackState() {
        clearPrerollIfActive()
        persistenceCoordinator.stopPeriodicFlush()
        hasStreamLoaded = false
    }

    private fun handlePlaybackStateChanged(state: PlaybackState) {
        if (state is PlaybackState.Ended) return handlePlaybackEnded()
        if (state is PlaybackState.Error) {
            return applyFaultDecision(faultTolerance.handlePlaybackError(state, _sessionState.value.currentTrack, ::canSkipTrack))
        }
        if (state is PlaybackState.Buffering) {
            clearPrerollIfActive()
            faultTolerance.onBufferingStarted(_sessionState.value.currentTrack?.isLocal == true) { autoPause ->
                if (autoPause) playerEngine.pause()
                _sessionState.update { it.withWeakNetwork(true, autoPause, if (autoPause) PlaybackState.Paused else it.playbackState) }
            }
        } else if (state is PlaybackState.Playing) {
            val decision = faultTolerance.onPlayingStarted(_sessionState.value)
            if (decision.clearWeakNetworkFlags) _sessionState.update { it.withClearedNetworkFlags() }
            decision.reachableServerId?.let { storageReachabilityTracker?.reportServerReachable(it) }
            persistenceCoordinator.startPeriodicFlush()
        } else {
            faultTolerance.cancelWatchdog()
            persistenceCoordinator.stopPeriodicFlush()
        }
        _sessionState.update { cur ->
            val target = if (state is PlaybackState.Idle && cur.isPaused && cur.hasTrack && !hasStreamLoaded) cur.playbackState else state
            cur.copy(playbackState = target)
        }
    }

    private fun handlePlaybackEnded() {
        isPrerollAppended.set(false)
        val pos = playerEngine.currentPositionMs.value
        val dur = playerEngine.durationMs.value
        when (val decision = faultTolerance.evaluatePlaybackEnded(_sessionState.value, pos, dur)) {
            is PlaybackEndedDecision.PrematureEof -> {
                _sessionState.update { it.copy(playbackState = PlaybackState.Buffering) }
                playerEngine.seekTo(decision.seekPositionMs)
                playerEngine.play()
            }
            is PlaybackEndedDecision.Ended -> _sessionState.update { it.copy(playbackState = PlaybackState.Ended) }
            is PlaybackEndedDecision.AdvanceQueue -> advanceQueueToNext(decision.server)
            is PlaybackEndedDecision.SingleLoopReplay -> {
                _playbackProgress.value = PlaybackProgress(0L, decision.durationMs, 0L)
                playerEngine.seekTo(0L)
                playerEngine.play()
                flushSessionAsync()
            }
        }
    }

    private suspend fun handleTrackTransition() {
        isPrerollAppended.set(false)
        val state = _sessionState.value
        val next = queueManager.advanceOnTrackTransition(state.queue, state.playbackMode) ?: return
        val track = next.nextTrack
        val cur = state.activeServer
        val changed = cur == null || track.serverId != cur.id || track.isLocal != cur.isLocal
        val resolved = if (changed) resolveServerForTrack(track, null, null)?.also(playerEngine::setActiveServer) else null
        _playbackProgress.value = PlaybackProgress(0L, track.durationMs, 0L)
        _sessionState.update { it.copy(queue = next.queue, durationMs = track.durationMs, activeServer = resolved ?: it.activeServer) }
        persistenceCoordinator.flushSession()
    }

    private fun handleProgressUpdated(progress: PlaybackProgress) {
        _playbackProgress.value = progress
        val state = _sessionState.value
        val plan = faultTolerance.evaluatePreroll(progress, state, isPrerollAppended.get())
        if (plan !is PrerollPlan.ScheduleNext || !isPrerollAppended.compareAndSet(false, true)) return
        val nextTrack = state.queue.tracks[queueManager.calculateNextQueueIndex(state.queue, state.playbackMode)]
        coroutineScope.launch(Dispatchers.Main) {
            val latest = _sessionState.value
            if (isPrerollAppended.get() && latest.playbackState is PlaybackState.Playing &&
                latest.currentTrack?.id == plan.expectedTrackId
            ) {
                val nextServer = resolveServerForTrack(nextTrack, plan.fallbackServer, plan.fallbackServer) ?: plan.fallbackServer
                playerEngine.prerollTrack(nextServer, nextTrack)
            } else {
                isPrerollAppended.set(false)
            }
        }
    }

    private fun handleDurationChanged(durationMs: Long) =
        _sessionState.update { it.copy(durationMs = durationMs, queue = queueManager.updateCurrentTrackDuration(it.queue, durationMs)) }

    private val networkObservationJob: Job? =
        networkConnectivityObserver?.let { observer ->
            coroutineScope.launch {
                observer.networkStatus.map { it.isConnected }.distinctUntilChanged().drop(1).collect { isConnected ->
                    if (!isConnected) return@collect
                    if (faultTolerance.onNetworkRestored(_sessionState.value) == NetworkRestoreDecision.AutoResume) {
                        _sessionState.update { it.withClearedNetworkFlags(errorMessage = null) }
                        resumeCurrentTrack()
                    }
                    storageReachabilityTracker?.triggerReachabilitySelfHealing()
                }
            }
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val serverDeletionObservationJob: Job? =
        serverRepository?.let { repo ->
            coroutineScope.launch {
                _sessionState
                    .map { it.activeServer?.takeIf { s -> !s.isLocal }?.id }
                    .distinctUntilChanged()
                    .flatMapLatest { id ->
                        id?.let { repo.getAllServers().catch { e -> if (e is CancellationException) throw e } }
                            ?: emptyFlow()
                    }.collect { handleServerListUpdated(it) }
            }
        }

    private fun handleServerListUpdated(servers: List<WebDavServer>) {
        val current = _sessionState.value
        val active = current.activeServer ?: return
        if (!active.isLocal && servers.none { it.id == active.id }) {
            _sessionState.update { it.withClearedQueue(clearActiveServer = true) }
            if (current.hasTrack || current.playbackState !is PlaybackState.Idle) {
                stop()
            } else {
                flushSessionAsync()
            }
        }
    }

    init {
        coroutineScope.launch { restoreSession() }
        timelineCoordinator.startObservations()
        metadataCoordinator.startMetadataObservation()
        metadataCoordinator.startLyricsObservation()
    }

    override fun playDirectoryTrack(
        server: WebDavServer,
        directory: RemoteDirectory,
        selectedFile: RemoteFile,
        initialMetadata: Map<String, TrackMetadata>,
    ) {
        val req =
            queueManager.buildDirectoryQueue(directory, selectedFile, initialMetadata, server, _sessionState.value.playbackMode) ?: return
        dispatchQueueLoad(server, req)
        metadataCoordinator.enrichQueueTracksFromCache(server, req.queue)
    }

    override fun playTrack(track: AudioTrack) {
        coroutineScope.launch {
            val effectiveServer = resolveServerForTrack(track) ?: return@launch
            dispatchQueueLoad(effectiveServer, queueManager.buildTrackQueue(track))
            metadataCoordinator.enrichSingleTrackMetadata(effectiveServer, track)
        }
    }

    override fun playNext(track: AudioTrack) {
        val current = _sessionState.value
        if (current.activeServer == null && serverRepository == null) return
        val decision = queueManager.playNext(current.queue, track)
        if (decision is PlayNextDecision.PlayAsNew) return playTrack(decision.track)
        if (decision is PlayNextDecision.QueueUpdated && current.activeServer != null) {
            _sessionState.update { it.copy(queue = decision.queue) }
            flushSessionAsync()
        }
    }

    override fun playNextAndAdvance(track: AudioTrack) {
        val current = _sessionState.value
        if (current.activeServer == null && serverRepository == null) return
        val result = queueManager.playNextAndAdvance(current.queue, track)
        if (result is PlayNextAndAdvanceResult.PlayAsNew) return playTrack(result.track)
        if (result is PlayNextAndAdvanceResult.QueueUpdatedAndAdvance) {
            _sessionState.update { it.copy(queue = result.queue) }
            transitionToQueueIndex(current.activeServer, result.targetIndex)
        }
    }

    private fun resumeCurrentTrack() {
        val current = _sessionState.value
        loadTrackIntoEngine(current.activeServer ?: return, current.currentTrack ?: return, _playbackProgress.value.currentPositionMs)
    }

    private fun isPlayingOrBuffering(): Boolean = _sessionState.value.isPlaying || _sessionState.value.isBuffering

    override fun togglePlayPause() = awaitRestoredAndRun(requireTrack = true) { if (isPlayingOrBuffering()) pause() else play() }

    override fun play() {
        clearKilledBySystemFlag()
        _pausedByTransientAudioFocus.set(false)
        awaitRestoredAndRun(requireTrack = true) {
            val cur = _sessionState.value
            if (cur.isPaused && hasStreamLoaded && playerEngine.playbackState.value !is PlaybackState.Idle) {
                playerEngine.play()
            } else if (cur.hasTrack && cur.activeServer != null) {
                resumeCurrentTrack()
            }
        }
    }

    override fun pause() {
        _pausedByTransientAudioFocus.set(false)
        pauseInternal()
    }

    private fun pauseInternal() {
        clearPrerollIfActive()
        faultTolerance.cancelWatchdog()
        if (hasStreamLoaded) playerEngine.pause()
        _sessionState.update { it.withClearedNetworkFlags() }
        persistenceCoordinator.flushCleanExitAsync(saveCurrentPosition = true)
    }

    override fun onAudioFocusTransientLoss() {
        val active = isPlayingOrBuffering()
        _pausedByTransientAudioFocus.set(active)
        if (active) pauseInternal()
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
            val request =
                queueManager.buildPlaylistQueue(entries, startIndex, _sessionState.value.playbackMode)
                    ?: return@launch _sessionState.update { it.copy(queue = PlaybackQueue.EMPTY, errorMessage = null) }
            dispatchQueueLoad(resolveServerForTrack(request.targetTrack), request)
        }
    }

    override fun skipToNext() {
        _pausedByTransientAudioFocus.set(false)
        clearPrerollIfActive()
        awaitRestoredAndRun(requireTrack = false) { advanceQueueToNext(_sessionState.value.activeServer) }
    }

    override fun skipToPrevious() {
        _pausedByTransientAudioFocus.set(false)
        clearPrerollIfActive()
        awaitRestoredAndRun(requireTrack = false) {
            val queue = _sessionState.value.queue
            if (queue.isEmpty) return@awaitRestoredAndRun
            if (playerEngine.currentPositionMs.value > 3000L) return@awaitRestoredAndRun seekTo(0L)
            transitionToQueueIndex(
                _sessionState.value.activeServer,
                queueManager.calculatePreviousQueueIndex(queue, _sessionState.value.playbackMode),
            )
        }
    }

    override fun playQueueIndex(index: Int) {
        _pausedByTransientAudioFocus.set(false)
        clearPrerollIfActive()
        if (index in _sessionState.value.queue.tracks.indices) {
            queueManager.clearFailedTracks()
            transitionToQueueIndex(_sessionState.value.activeServer, index)
        }
    }

    override fun cyclePlaybackMode() =
        applyPlaybackModeUpdate(queueManager.cyclePlaybackMode(_sessionState.value.queue, _sessionState.value.playbackMode))

    override fun setPlaybackMode(mode: PlaybackMode) =
        applyPlaybackModeUpdate(queueManager.setPlaybackMode(_sessionState.value.queue, mode))

    private fun applyPlaybackModeUpdate(update: PlaybackModeUpdate) {
        _sessionState.update { it.copy(playbackMode = update.playbackMode, queue = update.queue) }
        flushSessionAsync()
    }

    override fun removeQueueTrack(index: Int) {
        clearPrerollIfActive()
        val result = queueManager.removeQueueTrack(_sessionState.value.queue, index)
        if (result is RemoveQueueTrackResult.Unchanged) return
        if (result is RemoveQueueTrackResult.Cleared) {
            _sessionState.update { it.withClearedQueue(playbackState = PlaybackState.Idle) }
            haltEngineAndClearProgress()
        } else if (result is RemoveQueueTrackResult.CurrentTrackChanged) {
            _sessionState.update { it.copy(queue = result.queue, durationMs = result.nextTrack.durationMs) }
            _playbackProgress.value = PlaybackProgress(0L, result.nextTrack.durationMs, 0L)
            coroutineScope.launch { resolveServerForTrack(result.nextTrack)?.let { loadTrackIntoEngine(it, result.nextTrack, 0L) } }
        } else if (result is RemoveQueueTrackResult.QueueUpdated) {
            _sessionState.update { it.copy(queue = result.queue) }
        }
        flushSessionAsync()
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
    ) = _sessionState.update { it.withWeakNetwork(isWeakNetwork, canManualRetry) }

    override fun stop() {
        _pausedByTransientAudioFocus.set(false)
        haltEngineAndClearProgress()
        _sessionState.update { it.withClearedNetworkFlags(playbackState = PlaybackState.Idle) }
        persistenceCoordinator.flushCleanExitAsync(saveCurrentPosition = false)
    }

    override fun clearKilledBySystemFlag() {
        if (_sessionState.value.wasKilledBySystem) _sessionState.update { it.copy(wasKilledBySystem = false) }
    }

    override fun confirmUnsupportedRangeReplay() {
        _sessionState.update { it.copy(isUnsupportedRange = false, errorMessage = null) }
        val server = _sessionState.value.activeServer ?: return
        val track = _sessionState.value.currentTrack ?: return
        _playbackProgress.value = PlaybackProgress(0L, track.durationMs, 0L)
        loadTrackIntoEngine(server, track, 0L)
    }

    override fun dismissUnsupportedRange() = _sessionState.update { it.copy(isUnsupportedRange = false) }

    override fun release() {
        resetPlaybackState()
        timelineCoordinator.cancelObservations()
        networkObservationJob?.cancel()
        serverDeletionObservationJob?.cancel()
        playerEngine.release()
        _playbackProgress.value = PlaybackProgress.ZERO
    }

    override fun flushSessionAsync() = persistenceCoordinator.flushSessionAsync()

    override suspend fun restoreSession() = persistenceCoordinator.restoreSession()

    override suspend fun flushSession() = persistenceCoordinator.flushSession()

    suspend fun flushSessionWithCleanExit(isCleanExit: Boolean) = persistenceCoordinator.flushSessionWithCleanExit(isCleanExit)
}
