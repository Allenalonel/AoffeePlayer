package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.QueueOrigin
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer

/**
 * Pure domain decision collaborator managing playback queue state transitions,
 * next/previous index calculations, shuffle order generation, ad-hoc queue modifications,
 * and dead-track cycle prevention.
 */
internal class PlaybackQueueManager {
    private val failedTrackIdsInCurrentCycle = mutableSetOf<String>()

    val failedTrackCount: Int
        get() = failedTrackIdsInCurrentCycle.size

    fun hasFailedTracks(): Boolean = failedTrackIdsInCurrentCycle.isNotEmpty()

    fun clearFailedTracks() {
        failedTrackIdsInCurrentCycle.clear()
    }

    /**
     * Records a failed track ID and determines whether to proceed with skipping to next track
     * without causing an infinite cycle. Returns true if skipping should proceed; false if
     * the cycle is exhausted and error state must be displayed.
     */
    fun recordFailedTrack(
        trackId: String,
        queueSize: Int,
    ): Boolean {
        failedTrackIdsInCurrentCycle.add(trackId)
        return if (queueSize > 1 && failedTrackIdsInCurrentCycle.size < queueSize) {
            true
        } else {
            failedTrackIdsInCurrentCycle.clear()
            false
        }
    }

    /**
     * Calculates the next track index in the queue under the active playback mode,
     * guarding against infinite loops in SINGLE_LOOP mode if tracks have failed.
     */
    fun calculateNextQueueIndex(
        queue: PlaybackQueue,
        mode: PlaybackMode,
    ): Int {
        if (queue.tracks.size <= 1) return 0
        if (failedTrackIdsInCurrentCycle.isNotEmpty() && mode == PlaybackMode.SINGLE_LOOP) {
            return (queue.currentIndex + 1) % queue.tracks.size
        }
        return queue.getNextIndex(mode) ?: 0
    }

    /**
     * Calculates the previous track index in the queue under the active playback mode.
     */
    fun calculatePreviousQueueIndex(
        queue: PlaybackQueue,
        mode: PlaybackMode,
    ): Int {
        if (queue.tracks.size <= 1) return 0
        return queue.getPreviousIndex(mode) ?: 0
    }

    /**
     * Computes the next queue progression step, handling deterministic end-of-cycle reshuffling
     * when in [PlaybackMode.SHUFFLE]. Returns null when [queue] is empty.
     */
    fun advanceToNext(
        queue: PlaybackQueue,
        mode: PlaybackMode,
    ): AdvanceQueueResult? {
        if (queue.isEmpty) return null
        return if (mode == PlaybackMode.SHUFFLE && queue.currentIndex >= queue.tracks.lastIndex) {
            AdvanceQueueResult(targetIndex = 0, reshuffledQueue = queue.reshuffle())
        } else {
            AdvanceQueueResult(targetIndex = calculateNextQueueIndex(queue, mode), reshuffledQueue = null)
        }
    }

    /**
     * Computes updated playback mode and shuffled/unshuffled queue transformation.
     */
    fun setPlaybackMode(
        queue: PlaybackQueue,
        mode: PlaybackMode,
    ): PlaybackModeUpdate {
        val updatedQueue =
            when {
                mode == PlaybackMode.SHUFFLE && !queue.isShuffled && queue.tracks.isNotEmpty() -> {
                    queue.toShuffled()
                }

                mode != PlaybackMode.SHUFFLE && queue.isShuffled -> {
                    queue.toUnshuffled()
                }

                else -> {
                    queue
                }
            }
        return PlaybackModeUpdate(playbackMode = mode, queue = updatedQueue)
    }

    /**
     * Cycles to the next playback mode in order (LIST_LOOP -> SINGLE_LOOP -> SHUFFLE -> LIST_LOOP).
     */
    fun cyclePlaybackMode(
        queue: PlaybackQueue,
        currentMode: PlaybackMode,
    ): PlaybackModeUpdate = setPlaybackMode(queue, currentMode.next())

    /**
     * Computes the decision for inserting a track immediately after the active track.
     */
    fun playNext(
        queue: PlaybackQueue,
        track: AudioTrack,
    ): PlayNextDecision =
        if (queue.isEmpty) {
            PlayNextDecision.PlayAsNew(track)
        } else {
            PlayNextDecision.QueueUpdated(queue.insertNext(track))
        }

    /**
     * Computes the decision for inserting a track immediately after the active track and advancing to it.
     */
    fun playNextAndAdvance(
        queue: PlaybackQueue,
        track: AudioTrack,
    ): PlayNextAndAdvanceResult {
        if (queue.isEmpty) return PlayNextAndAdvanceResult.PlayAsNew(track)
        val targetIndex = (queue.currentIndex + 1).coerceAtMost(queue.tracks.size)
        return PlayNextAndAdvanceResult.QueueUpdatedAndAdvance(
            queue = queue.insertNext(track),
            targetIndex = targetIndex,
        )
    }

    /**
     * Computes the outcome of removing a track at [index] from [queue].
     */
    fun removeQueueTrack(
        queue: PlaybackQueue,
        index: Int,
    ): RemoveQueueTrackResult {
        if (index !in queue.tracks.indices) return RemoveQueueTrackResult.Unchanged
        val wasCurrentTrack = (index == queue.currentIndex)
        val updatedQueue = queue.removeTrackAt(index)
        return when {
            updatedQueue.isEmpty -> {
                RemoveQueueTrackResult.Cleared
            }

            wasCurrentTrack && updatedQueue.currentTrack != null -> {
                RemoveQueueTrackResult.CurrentTrackChanged(updatedQueue, updatedQueue.currentTrack!!)
            }

            else -> {
                RemoveQueueTrackResult.QueueUpdated(updatedQueue)
            }
        }
    }

    fun advanceOnTrackTransition(
        queue: PlaybackQueue,
        mode: PlaybackMode,
    ): TrackTransitionUpdate? {
        if (queue.isEmpty) return null
        val nextIndex = calculateNextQueueIndex(queue, mode)
        val nextTrack = queue.tracks[nextIndex]
        return TrackTransitionUpdate(queue = queue.copy(currentIndex = nextIndex), nextTrack = nextTrack)
    }

    /**
     * Updates the current track's duration in [queue] if changed.
     */
    fun updateCurrentTrackDuration(
        queue: PlaybackQueue,
        durationMs: Long,
    ): PlaybackQueue {
        val curIdx = queue.currentIndex
        val updated =
            queue.tracks.mapIndexed { idx, trk ->
                if (idx == curIdx && trk.durationMs != durationMs) trk.copy(durationMs = durationMs) else trk
            }
        return queue.copy(tracks = updated)
    }

    fun buildDirectoryQueue(
        directory: RemoteDirectory,
        selectedFile: RemoteFile,
        initialMetadata: Map<String, TrackMetadata>,
        server: WebDavServer,
        playbackMode: PlaybackMode = PlaybackMode.LIST_LOOP,
    ): QueueLoadRequest? {
        val audioFiles = directory.files.filter { it.isAudio }
        if (audioFiles.isEmpty()) return null
        val tracks = audioFiles.mapNotNull { AudioTrack.fromRemoteFile(server, it, initialMetadata[it.path]) }
        if (tracks.isEmpty()) return null

        val selectedIndex = tracks.indexOfFirst { it.remotePath == selectedFile.path }.takeIf { it >= 0 } ?: 0
        val rawQueue = PlaybackQueue.fromTracks(tracks = tracks, selectedIndex = selectedIndex)
        val queue = if (playbackMode == PlaybackMode.SHUFFLE) rawQueue.toShuffled() else rawQueue
        val selectedTrack = queue.currentTrack ?: return null

        return QueueLoadRequest(
            queue = queue,
            targetTrack = selectedTrack,
            startPositionMs = 0L,
        )
    }

    fun buildTrackQueue(track: AudioTrack): QueueLoadRequest {
        val queue = PlaybackQueue(tracks = listOf(track), currentIndex = 0, origin = QueueOrigin.AD_HOC)
        return QueueLoadRequest(
            queue = queue,
            targetTrack = track,
            startPositionMs = 0L,
        )
    }

    fun buildPlaylistQueue(
        entries: List<PlaylistTrackEntry>,
        startIndex: Int,
        playbackMode: PlaybackMode = PlaybackMode.LIST_LOOP,
    ): QueueLoadRequest? {
        if (entries.isEmpty()) return null
        val tracks = entries.map { it.toAudioTrack() }
        val safeStartIndex = startIndex.coerceIn(0, tracks.lastIndex)
        val rawQueue = PlaybackQueue.fromTracks(tracks = tracks, selectedIndex = safeStartIndex, origin = QueueOrigin.PLAYLIST)
        val queue = if (playbackMode == PlaybackMode.SHUFFLE) rawQueue.toShuffled() else rawQueue
        val currentTrack = queue.currentTrack ?: tracks[safeStartIndex]
        return QueueLoadRequest(
            queue = queue,
            targetTrack = currentTrack,
            startPositionMs = 0L,
        )
    }
}

internal data class QueueLoadRequest(
    val queue: PlaybackQueue,
    val targetTrack: AudioTrack,
    val startPositionMs: Long = 0L,
)

internal data class PlaybackModeUpdate(
    val playbackMode: PlaybackMode,
    val queue: PlaybackQueue,
)

internal sealed interface PlayNextDecision {
    data class PlayAsNew(
        val track: AudioTrack,
    ) : PlayNextDecision

    data class QueueUpdated(
        val queue: PlaybackQueue,
    ) : PlayNextDecision
}

internal sealed interface PlayNextAndAdvanceResult {
    data class PlayAsNew(
        val track: AudioTrack,
    ) : PlayNextAndAdvanceResult

    data class QueueUpdatedAndAdvance(
        val queue: PlaybackQueue,
        val targetIndex: Int,
    ) : PlayNextAndAdvanceResult
}

internal sealed interface RemoveQueueTrackResult {
    data object Unchanged : RemoveQueueTrackResult

    data object Cleared : RemoveQueueTrackResult

    data class QueueUpdated(
        val queue: PlaybackQueue,
    ) : RemoveQueueTrackResult

    data class CurrentTrackChanged(
        val queue: PlaybackQueue,
        val nextTrack: AudioTrack,
    ) : RemoveQueueTrackResult
}

internal data class AdvanceQueueResult(
    val targetIndex: Int,
    val reshuffledQueue: PlaybackQueue? = null,
)

internal data class TrackTransitionUpdate(
    val queue: PlaybackQueue,
    val nextTrack: AudioTrack,
)
