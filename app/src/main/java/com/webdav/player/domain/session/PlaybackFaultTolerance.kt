package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.PlaybackErrorKind
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.domain.model.WebDavServer
import java.io.FileNotFoundException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun Throwable?.isFileNotFoundOrDeniedException(): Boolean {
    if (this == null) return false
    return generateSequence(this) { current -> current.cause?.takeIf { it !== current } }.any { cause ->
        cause is FileNotFoundException || cause is SecurityException
    }
}

/**
 * Pure domain decision & buffering watchdog scheduler for streaming fault tolerance,
 * dead-track skip decisions, premature EOF arbitration, and adaptive pre-roll planning.
 */
internal class PlaybackFaultTolerance(
    private val coroutineScope: CoroutineScope
) {
    val bufferingWatchdogJobRef = AtomicReference<Job?>(null)
    val lastBufferingTimestampMs = AtomicLong(0L)

    fun onBufferingStarted(
        isLocalTrack: Boolean,
        onTimeout: (autoPause: Boolean) -> Unit
    ) {
        if (isLocalTrack) return
        lastBufferingTimestampMs.set(System.currentTimeMillis())
        if (bufferingWatchdogJobRef.get()?.isActive != true) {
            val job =
                coroutineScope.launch {
                    delay(WATCHDOG_WEAK_NETWORK_TIMEOUT_MS)
                    if (isActive) {
                        onTimeout(false)
                        delay(WATCHDOG_AUTO_PAUSE_TIMEOUT_MS)
                        if (isActive) {
                            onTimeout(true)
                        }
                    }
                }
            bufferingWatchdogJobRef.set(job)
        }
    }

    fun onPlayingStarted(state: PlayerSessionState): PlayingStartedDecision {
        cancelWatchdog()
        val clearFlags = state.isWeakNetwork || state.canManualRetry || state.isReconnecting
        return PlayingStartedDecision(
            clearWeakNetworkFlags = clearFlags,
            reachableServerId = state.currentTrack?.serverId
        )
    }

    fun cancelWatchdog() {
        bufferingWatchdogJobRef.getAndSet(null)?.cancel()
    }

    fun isHealthyNetwork(quietPeriodMs: Long = HEALTHY_NETWORK_QUIET_PERIOD_MS): Boolean =
        (System.currentTimeMillis() - lastBufferingTimestampMs.get()) >= quietPeriodMs

    fun onNetworkRestored(state: PlayerSessionState): NetworkRestoreDecision {
        val canResume = state.isWeakNetwork && state.canManualRetry && state.hasTrack
        if (!canResume) return NetworkRestoreDecision.Ignore
        cancelWatchdog()
        return NetworkRestoreDecision.AutoResume
    }

    fun isServerUnreachableException(e: Throwable): Boolean =
        generateSequence(e) { current -> current.cause?.takeIf { it !== current } }.any { c ->
            c is ConnectException || c is UnknownHostException || c is SocketTimeoutException
        }

    fun handle404Failure(
        track: AudioTrack?,
        errorMessage: String? = null,
        canSkip: (AudioTrack) -> Boolean = { false }
    ): FaultDecision {
        cancelWatchdog()
        val unavailableTrack = track?.let { Triple(it.serverId, it.remotePath, InactiveReason.FILE_NOT_FOUND) }
        if (track != null && canSkip(track)) {
            return FaultDecision.SkipTrack(unavailableTrack = unavailableTrack)
        }
        val msg = errorMessage ?: "音频文件已被重命名或删除"
        return FaultDecision.ShowError(
            error = PlaybackState.Error(msg, kind = PlaybackErrorKind.FILE_NOT_FOUND),
            unavailableTrack = unavailableTrack
        )
    }

    fun handleAuthFailure(
        track: AudioTrack?,
        errorMessage: String? = null,
        canSkip: (AudioTrack) -> Boolean = { false }
    ): FaultDecision {
        cancelWatchdog()
        val unavailableServer = track?.let { it.serverId to InactiveReason.SERVER_CREDENTIAL_INVALID }
        if (track != null && canSkip(track)) {
            return FaultDecision.SkipTrack(unavailableServer = unavailableServer)
        }
        val msg = errorMessage ?: "服务器认证凭据失效"
        return FaultDecision.ShowError(
            error = PlaybackState.Error(msg, kind = PlaybackErrorKind.AUTH_INVALID),
            unavailableServer = unavailableServer
        )
    }

    fun handleServerUnavailable(
        track: AudioTrack,
        canSkip: (AudioTrack) -> Boolean = { false }
    ): FaultDecision {
        cancelWatchdog()
        val unavailableServer = track.serverId to InactiveReason.SERVER_UNREACHABLE
        return if (canSkip(track)) {
            FaultDecision.SkipTrack(unavailableServer = unavailableServer)
        } else {
            FaultDecision.ShowError(
                error = PlaybackState.Error("所属服务器无法连接", kind = PlaybackErrorKind.SERVER_UNREACHABLE),
                unavailableServer = unavailableServer
            )
        }
    }

    fun handlePlaybackError(
        state: PlaybackState.Error,
        currentTrack: AudioTrack?,
        canSkip: (AudioTrack) -> Boolean = { false }
    ): FaultDecision =
        when (state.kind) {
            PlaybackErrorKind.UNSUPPORTED_RANGE -> FaultDecision.PauseForUnsupportedRange
            PlaybackErrorKind.FILE_NOT_FOUND -> handle404Failure(currentTrack, state.message, canSkip)
            PlaybackErrorKind.AUTH_INVALID -> handleAuthFailure(currentTrack, state.message, canSkip)
            PlaybackErrorKind.SERVER_UNREACHABLE -> currentTrack?.let { handleServerUnavailable(it, canSkip) } ?: FaultDecision.Ignored
            PlaybackErrorKind.UNKNOWN -> FaultDecision.RetryableError(state)
        }

    fun handleLoadException(
        e: Throwable,
        track: AudioTrack,
        canSkip: (AudioTrack) -> Boolean = { false }
    ): FaultDecision =
        when {
            e.isFileNotFoundOrDeniedException() -> handle404Failure(track, canSkip = canSkip)
            isServerUnreachableException(e) -> handleServerUnavailable(track, canSkip = canSkip)
            else -> FaultDecision.ShowError(PlaybackState.Error(e.message ?: "加载音频失败"))
        }

    fun isPrematureEof(
        currentPos: Long,
        duration: Long
    ): Boolean {
        val isPhysicalMatch = duration <= 0L || currentPos >= (duration - DURATION_COMPLETION_TOLERANCE_MS)
        val isProgressMatch =
            duration > 0L && (currentPos.toDouble() / duration.toDouble()) >= COMPLETION_PROGRESS_THRESHOLD
        return !isPhysicalMatch && !isProgressMatch
    }

    fun evaluatePreroll(
        progress: PlaybackProgress,
        state: PlayerSessionState,
        isPrerollAlreadyAppended: Boolean
    ): PrerollPlan {
        if (isPrerollAlreadyAppended || state.queue.tracks.size <= 1) return PrerollPlan.None
        if (state.playbackMode == PlaybackMode.SHUFFLE && state.queue.currentIndex >= state.queue.tracks.lastIndex) {
            return PrerollPlan.None
        }
        val remainingMs = progress.durationMs - progress.currentPositionMs
        if (remainingMs !in 1..PREROLL_TRIGGER_WINDOW_MS) return PrerollPlan.None
        if (state.playbackState !is PlaybackState.Playing) return PrerollPlan.None
        if (!isHealthyNetwork(HEALTHY_NETWORK_QUIET_PERIOD_MS)) return PrerollPlan.None
        val server = state.activeServer ?: return PrerollPlan.None
        return PrerollPlan.ScheduleNext(
            expectedTrackId = state.currentTrack?.id,
            fallbackServer = server
        )
    }

    fun evaluatePlaybackEnded(
        state: PlayerSessionState,
        currentPos: Long,
        engineDurationMs: Long
    ): PlaybackEndedDecision {
        val duration = engineDurationMs.takeIf { it > 0L } ?: state.currentTrack?.durationMs ?: state.durationMs
        if (isPrematureEof(currentPos, duration)) {
            return PlaybackEndedDecision.PrematureEof(currentPos)
        }
        val server = state.activeServer
        if (server == null || state.queue.isEmpty) {
            return PlaybackEndedDecision.Ended
        }
        return if (state.playbackMode == PlaybackMode.SINGLE_LOOP) {
            PlaybackEndedDecision.SingleLoopReplay(duration)
        } else {
            PlaybackEndedDecision.AdvanceQueue(server)
        }
    }

    fun reduceStateForDecision(
        current: PlayerSessionState,
        decision: FaultDecision
    ): PlayerSessionState =
        when (decision) {
            is FaultDecision.PauseForUnsupportedRange -> {
                current.copy(playbackState = PlaybackState.Paused, isUnsupportedRange = true)
            }

            is FaultDecision.ShowError -> {
                current.copy(
                    playbackState = decision.error,
                    errorMessage = decision.error.message,
                    canManualRetry = false,
                    isWeakNetwork = false
                )
            }

            is FaultDecision.RetryableError -> {
                current.copy(
                    playbackState = decision.error,
                    errorMessage = decision.error.message,
                    isWeakNetwork = true,
                    canManualRetry = true,
                    isReconnecting = false
                )
            }

            is FaultDecision.SkipTrack,
            is FaultDecision.Ignored
            -> {
                current
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

internal data class PlayingStartedDecision(
    val clearWeakNetworkFlags: Boolean,
    val reachableServerId: Long?
)

internal enum class NetworkRestoreDecision {
    Ignore,
    AutoResume
}

internal sealed interface FaultDecision {
    val unavailableTrack: Triple<Long, String, InactiveReason>? get() = null
    val unavailableServer: Pair<Long, InactiveReason>? get() = null

    data object PauseForUnsupportedRange : FaultDecision

    data object Ignored : FaultDecision

    data class SkipTrack(
        override val unavailableTrack: Triple<Long, String, InactiveReason>? = null,
        override val unavailableServer: Pair<Long, InactiveReason>? = null
    ) : FaultDecision

    data class ShowError(
        val error: PlaybackState.Error,
        override val unavailableTrack: Triple<Long, String, InactiveReason>? = null,
        override val unavailableServer: Pair<Long, InactiveReason>? = null
    ) : FaultDecision

    data class RetryableError(
        val error: PlaybackState.Error
    ) : FaultDecision
}

internal sealed interface PrerollPlan {
    data object None : PrerollPlan

    data class ScheduleNext(
        val expectedTrackId: String?,
        val fallbackServer: WebDavServer
    ) : PrerollPlan
}

internal sealed interface PlaybackEndedDecision {
    data class PrematureEof(
        val seekPositionMs: Long
    ) : PlaybackEndedDecision

    data object Ended : PlaybackEndedDecision

    data class SingleLoopReplay(
        val durationMs: Long
    ) : PlaybackEndedDecision

    data class AdvanceQueue(
        val server: WebDavServer
    ) : PlaybackEndedDecision
}
