package com.webdav.player.domain.session

import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.player.AudioPlayerEngine
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Internal collaborator strictly focused on observing [AudioPlayerEngine] streams
 * and forwarding timeline/state events to the session commit spine.
 */
internal class PlaybackTimelineCoordinator(
    private val playerEngine: AudioPlayerEngine,
    private val coroutineScope: CoroutineScope,
    private val progressDispatcher: CoroutineDispatcher,
    private val fallbackTrackDurationMs: () -> Long = { 0L },
    private val onPlaybackStateChanged: (PlaybackState) -> Unit = {},
    private val onTrackTransition: suspend () -> Unit = {},
    private val onProgressUpdated: (PlaybackProgress) -> Unit = {},
    private val onDurationChanged: (Long) -> Unit = {}
) {
    private val observationJobs = CopyOnWriteArrayList<Job>()

    fun startObservations() {
        observationJobs +=
            coroutineScope.launch {
                playerEngine.playbackState.collect { onPlaybackStateChanged(it) }
            }
        observationJobs +=
            coroutineScope.launch {
                playerEngine.trackTransitions.collect { onTrackTransition() }
            }
        observationJobs +=
            coroutineScope.launch(progressDispatcher) {
                combine(
                    playerEngine.currentPositionMs,
                    playerEngine.durationMs,
                    playerEngine.bufferedPositionMs
                ) { pos, dur, buf ->
                    mapEngineProgress(pos, dur, buf, fallbackTrackDurationMs())
                }.distinctUntilChanged().collect { progress ->
                    if (playerEngine.playbackState.value !is PlaybackState.Idle) {
                        onProgressUpdated(progress)
                    }
                }
            }
        observationJobs +=
            coroutineScope.launch {
                playerEngine.durationMs.collect { dur ->
                    if (playerEngine.playbackState.value !is PlaybackState.Idle && dur > 0L) {
                        onDurationChanged(dur)
                    }
                }
            }
    }

    fun cancelObservations() {
        observationJobs.forEach { it.cancel() }
        observationJobs.clear()
    }

    fun mapEngineProgress(
        pos: Long,
        dur: Long,
        buf: Long,
        fallbackDurationMs: Long = 0L
    ): PlaybackProgress = PlaybackProgress(pos, if (dur > 0L) dur else fallbackDurationMs, buf)
}
