package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.PlaybackErrorKind
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.model.isLocal
import com.webdav.player.domain.player.AudioPlayerEngine
import com.webdav.player.domain.storage.StorageReachabilityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

fun Throwable?.is404Exception(): Boolean {
    if (this == null) return false
    return generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.any { cause ->
        cause is FileNotFoundException || cause is SecurityException
    }
}

/**
 * Internal collaborator managing stream buffering watchdog timers, error classification,
 * dead-track skip handling, premature EOF arbitration, and unsupported Range replay dialogues.
 */
internal class PlaybackFaultTolerance(
    private val sessionStateFlow: MutableStateFlow<PlayerSessionState>,
    private val playerEngine: AudioPlayerEngine,
    private val storageReachabilityTracker: StorageReachabilityTracker?,
    private val queueManager: PlaybackQueueManager,
    private val coroutineScope: CoroutineScope,
) {
    val bufferingWatchdogJobRef = AtomicReference<Job?>(null)
    val lastBufferingTimestampMs = AtomicLong(0L)

    fun onBufferingStarted() {
        val currentTrack = sessionStateFlow.value.currentTrack
        val isLocalTrack = currentTrack?.isLocal == true
        if (isLocalTrack) {
            return
        }

        lastBufferingTimestampMs.set(System.currentTimeMillis())
        if (bufferingWatchdogJobRef.get()?.isActive != true) {
            val job =
                coroutineScope.launch {
                    delay(WATCHDOG_WEAK_NETWORK_TIMEOUT_MS)
                    if (isActive) {
                        sessionStateFlow.update {
                            it.copy(isWeakNetwork = true, canManualRetry = false, isReconnecting = true)
                        }
                        delay(WATCHDOG_AUTO_PAUSE_TIMEOUT_MS)
                        if (isActive) {
                            playerEngine.pause()
                            sessionStateFlow.update {
                                it.copy(
                                    playbackState = PlaybackState.Paused,
                                    isWeakNetwork = true,
                                    canManualRetry = true,
                                    isReconnecting = true,
                                )
                            }
                        }
                    }
                }
            bufferingWatchdogJobRef.set(job)
        }
    }

    fun onPlayingStarted() {
        cancelWatchdog()
        val current = sessionStateFlow.value
        if (current.isWeakNetwork || current.canManualRetry || current.isReconnecting) {
            sessionStateFlow.update {
                it.copy(isWeakNetwork = false, canManualRetry = false, isReconnecting = false)
            }
        }
        val currentTrack = current.currentTrack
        if (currentTrack != null) {
            storageReachabilityTracker?.reportServerReachable(currentTrack.serverId)
        }
    }

    fun cancelWatchdog() {
        bufferingWatchdogJobRef.getAndSet(null)?.cancel()
    }

    fun isHealthyNetwork(quietPeriodMs: Long = HEALTHY_NETWORK_QUIET_PERIOD_MS): Boolean =
        (System.currentTimeMillis() - lastBufferingTimestampMs.get()) >= quietPeriodMs

    fun setWeakNetworkState(
        isWeakNetwork: Boolean,
        canManualRetry: Boolean,
    ) {
        sessionStateFlow.update {
            it.copy(isWeakNetwork = isWeakNetwork, canManualRetry = canManualRetry, isReconnecting = isWeakNetwork)
        }
    }

    fun onNetworkRestored(onAutoResume: () -> Unit): Boolean {
        val current = sessionStateFlow.value
        val canResume = current.isWeakNetwork && current.canManualRetry && current.hasTrack
        if (canResume) {
            cancelWatchdog()
            sessionStateFlow.update {
                it.copy(
                    isWeakNetwork = false,
                    canManualRetry = false,
                    isReconnecting = false,
                    errorMessage = null,
                )
            }
            onAutoResume()
            return true
        }
        return false
    }

    fun isServerUnreachableException(e: Throwable): Boolean =
        generateSequence(e) { current -> current.cause?.takeIf { it !== current } }.any { c ->
            c is ConnectException ||
                c is UnknownHostException ||
                c is SocketTimeoutException
        }

    fun is404Error(state: PlaybackState.Error): Boolean = state.kind == PlaybackErrorKind.FILE_NOT_FOUND

    fun isAuthError(state: PlaybackState.Error): Boolean = state.kind == PlaybackErrorKind.AUTH_INVALID

    fun isServerUnreachableError(state: PlaybackState.Error): Boolean = state.kind == PlaybackErrorKind.SERVER_UNREACHABLE

    fun isUnsupportedRangeError(state: PlaybackState.Error): Boolean = state.kind == PlaybackErrorKind.UNSUPPORTED_RANGE

    fun handle404Failure(
        track: AudioTrack?,
        errorMessage: String? = null,
        onSkipToNext: () -> Unit,
    ) {
        cancelWatchdog()
        val queueSize = sessionStateFlow.value.queue.size
        val shouldSkip =
            if (track != null) {
                storageReachabilityTracker?.reportTrackUnavailable(track.serverId, track.remotePath, InactiveReason.FILE_NOT_FOUND)
                queueManager.recordFailedTrack(track.id, queueSize)
            } else {
                false
            }
        if (shouldSkip) {
            onSkipToNext()
        } else {
            val msg = errorMessage ?: "音频文件已被重命名或删除"
            sessionStateFlow.update {
                it.copy(
                    playbackState = PlaybackState.Error(msg, kind = PlaybackErrorKind.FILE_NOT_FOUND),
                    errorMessage = msg,
                    canManualRetry = false,
                    isWeakNetwork = false,
                )
            }
        }
    }

    fun handleAuthFailure(
        track: AudioTrack?,
        errorMessage: String? = null,
        onSkipToNext: () -> Unit,
    ) {
        cancelWatchdog()
        val queueSize = sessionStateFlow.value.queue.size
        val shouldSkip =
            if (track != null) {
                storageReachabilityTracker?.reportServerUnavailable(track.serverId, InactiveReason.SERVER_CREDENTIAL_INVALID)
                queueManager.recordFailedTrack(track.id, queueSize)
            } else {
                false
            }
        if (shouldSkip) {
            onSkipToNext()
        } else {
            val msg = errorMessage ?: "服务器认证凭据失效"
            sessionStateFlow.update {
                it.copy(
                    playbackState = PlaybackState.Error(msg, kind = PlaybackErrorKind.AUTH_INVALID),
                    errorMessage = msg,
                    canManualRetry = false,
                    isWeakNetwork = false,
                )
            }
        }
    }

    fun handleServerUnavailable(
        track: AudioTrack,
        onSkipToNext: () -> Unit,
    ) {
        cancelWatchdog()
        storageReachabilityTracker?.reportServerUnavailable(track.serverId, InactiveReason.SERVER_UNREACHABLE)
        val queueSize = sessionStateFlow.value.queue.size
        if (queueManager.recordFailedTrack(track.id, queueSize)) {
            onSkipToNext()
        } else {
            sessionStateFlow.update {
                it.copy(
                    playbackState = PlaybackState.Error("所属服务器无法连接", kind = PlaybackErrorKind.SERVER_UNREACHABLE),
                    errorMessage = "所属服务器无法连接",
                    canManualRetry = false,
                    isWeakNetwork = false,
                )
            }
        }
    }

    fun handlePlaybackError(
        state: PlaybackState.Error,
        currentTrack: AudioTrack?,
        onUnsupportedRange: () -> Unit,
        onSkipToNext: () -> Unit,
    ): Boolean =
        when (state.kind) {
            PlaybackErrorKind.UNSUPPORTED_RANGE -> {
                sessionStateFlow.update { it.copy(playbackState = PlaybackState.Paused, isUnsupportedRange = true) }
                onUnsupportedRange()
                true
            }

            PlaybackErrorKind.FILE_NOT_FOUND -> {
                handle404Failure(currentTrack, state.message, onSkipToNext)
                true
            }

            PlaybackErrorKind.AUTH_INVALID -> {
                handleAuthFailure(currentTrack, state.message, onSkipToNext)
                true
            }

            PlaybackErrorKind.SERVER_UNREACHABLE -> {
                if (currentTrack != null) {
                    handleServerUnavailable(currentTrack, onSkipToNext)
                }
                true
            }

            PlaybackErrorKind.UNKNOWN -> {
                sessionStateFlow.update {
                    it.copy(
                        playbackState = state,
                        errorMessage = state.message,
                        isWeakNetwork = true,
                        canManualRetry = true,
                        isReconnecting = false,
                    )
                }
                false
            }
        }

    fun handleLoadException(
        e: Throwable,
        track: AudioTrack,
        onSkipToNext: () -> Unit,
    ) {
        if (e.is404Exception()) {
            handle404Failure(track, onSkipToNext = onSkipToNext)
        } else if (isServerUnreachableException(e)) {
            handleServerUnavailable(track, onSkipToNext = onSkipToNext)
        } else {
            sessionStateFlow.update {
                it.copy(
                    playbackState = PlaybackState.Error(e.message ?: "加载音频失败"),
                    errorMessage = e.message ?: "加载音频失败",
                    canManualRetry = false,
                    isWeakNetwork = false,
                )
            }
        }
    }

    fun dismissUnsupportedRange() {
        sessionStateFlow.update { it.copy(isUnsupportedRange = false) }
    }

    fun confirmUnsupportedRangeReplay(onReplay: () -> Unit) {
        sessionStateFlow.update { it.copy(isUnsupportedRange = false, errorMessage = null) }
        onReplay()
    }

    fun isPrematureEof(
        currentPos: Long,
        duration: Long,
    ): Boolean {
        val isPhysicalMatch = duration <= 0L || currentPos >= (duration - DURATION_COMPLETION_TOLERANCE_MS)
        val isProgressMatch =
            duration > 0L && (currentPos.toDouble() / duration.toDouble()) >= COMPLETION_PROGRESS_THRESHOLD
        return !isPhysicalMatch && !isProgressMatch
    }

    fun checkAndTriggerAdaptivePreroll(
        progress: com.webdav.player.domain.model.PlaybackProgress,
        queue: com.webdav.player.domain.model.PlaybackQueue,
        playbackMode: com.webdav.player.domain.model.PlaybackMode,
        activeServer: com.webdav.player.domain.model.WebDavServer?,
        currentTrackId: String?,
        isPrerollAppended: java.util.concurrent.atomic.AtomicBoolean,
        onPreroll: (server: com.webdav.player.domain.model.WebDavServer, track: AudioTrack) -> Unit,
    ) {
        if (isPrerollAppended.get() || queue.tracks.size <= 1) return
        if (playbackMode == PlaybackMode.SHUFFLE && queue.currentIndex >= queue.tracks.lastIndex) return
        val remainingMs = progress.durationMs - progress.currentPositionMs

        if (remainingMs in 1..PREROLL_TRIGGER_WINDOW_MS) {
            val isPlaying = sessionStateFlow.value.playbackState is PlaybackState.Playing
            if (isPlaying && isHealthyNetwork(HEALTHY_NETWORK_QUIET_PERIOD_MS)) {
                if (isPrerollAppended.compareAndSet(false, true)) {
                    val server =
                        activeServer ?: run {
                            isPrerollAppended.set(false)
                            return
                        }
                    val nextIndex = queueManager.calculateNextQueueIndex(queue, playbackMode)
                    val nextTrack = queue.tracks[nextIndex]
                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                        if (isPrerollAppended.get() &&
                            sessionStateFlow.value.playbackState is PlaybackState.Playing &&
                            sessionStateFlow.value.currentTrack?.id == currentTrackId
                        ) {
                            val nextServer = queueManager.resolveServerForTrack(nextTrack, server, server) ?: server
                            onPreroll(nextServer, nextTrack)
                        } else {
                            isPrerollAppended.set(false)
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val PREROLL_TRIGGER_WINDOW_MS: Long = 10_000L
        const val WATCHDOG_WEAK_NETWORK_TIMEOUT_MS: Long = 15_000L
        const val WATCHDOG_AUTO_PAUSE_TIMEOUT_MS: Long = 15_000L
        const val DURATION_COMPLETION_TOLERANCE_MS: Long = 5000L
        const val COMPLETION_PROGRESS_THRESHOLD: Double = 0.95
        const val HEALTHY_NETWORK_QUIET_PERIOD_MS: Long = 30_000L
    }
}
