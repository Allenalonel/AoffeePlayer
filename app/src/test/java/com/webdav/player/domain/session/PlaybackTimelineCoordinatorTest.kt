package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.player.AudioPlayerEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackTimelineCoordinatorTest {
    private val testDispatcher = StandardTestDispatcher()
    private val coordinatorJob = SupervisorJob()
    private val coordinatorScope = CoroutineScope(testDispatcher + coordinatorJob)

    private lateinit var engineStub: StreamEngineStub
    private lateinit var coordinator: PlaybackTimelineCoordinator

    private var fallbackDurationMs = 180_000L
    private val observedStates = mutableListOf<PlaybackState>()
    private var transitionCount = 0
    private val observedProgress = mutableListOf<PlaybackProgress>()
    private val observedDurations = mutableListOf<Long>()

    @Before
    fun setUp() {
        engineStub = StreamEngineStub()
        observedStates.clear()
        transitionCount = 0
        observedProgress.clear()
        observedDurations.clear()

        coordinator =
            PlaybackTimelineCoordinator(
                playerEngine = engineStub,
                coroutineScope = coordinatorScope,
                progressDispatcher = testDispatcher,
                fallbackTrackDurationMs = { fallbackDurationMs },
                onPlaybackStateChanged = { observedStates.add(it) },
                onTrackTransition = { transitionCount++ },
                onProgressUpdated = { observedProgress.add(it) },
                onDurationChanged = { observedDurations.add(it) },
            )
        coordinator.startObservations()
    }

    @After
    fun tearDown() {
        coordinator.cancelObservations()
        coordinatorJob.cancel()
    }

    @Test
    fun forwardsPlaybackStateAndTrackTransitionsToCallbacks() =
        runTest(testDispatcher) {
            engineStub.playbackState.value = PlaybackState.Buffering
            advanceUntilIdle()
            engineStub.playbackState.value = PlaybackState.Playing
            advanceUntilIdle()
            engineStub.trackTransitions.emit(Unit)
            advanceUntilIdle()

            assertTrue(observedStates.contains(PlaybackState.Buffering))
            assertTrue(observedStates.contains(PlaybackState.Playing))
            assertEquals(1, transitionCount)
        }

    @Test
    fun progressPipeline_mapsWithFallbackDuration_andSuppressesWhileIdle() =
        runTest(testDispatcher) {
            engineStub.playbackState.value = PlaybackState.Idle
            engineStub.currentPositionMs.value = 10_000L
            advanceUntilIdle()
            assertTrue("Progress must not emit while engine is Idle", observedProgress.isEmpty())

            engineStub.playbackState.value = PlaybackState.Playing
            engineStub.currentPositionMs.value = 25_000L
            engineStub.bufferedPositionMs.value = 60_000L
            advanceUntilIdle()

            assertEquals(PlaybackProgress(25_000L, 180_000L, 60_000L), observedProgress.last())

            engineStub.durationMs.value = 210_000L
            advanceUntilIdle()
            assertEquals(PlaybackProgress(25_000L, 210_000L, 60_000L), observedProgress.last())
            assertEquals(listOf(210_000L), observedDurations)
        }

    @Test
    fun cancelObservations_stopsAllStreamForwarding() =
        runTest(testDispatcher) {
            advanceUntilIdle()
            coordinator.cancelObservations()
            val statesBefore = observedStates.size

            engineStub.playbackState.value = PlaybackState.Playing
            engineStub.trackTransitions.emit(Unit)
            engineStub.durationMs.value = 300_000L
            advanceUntilIdle()

            assertEquals(statesBefore, observedStates.size)
            assertEquals(0, transitionCount)
            assertTrue(observedDurations.isEmpty())
        }

    @Test
    fun mapEngineProgress_prefersPositiveEngineDuration_andFallsBackOtherwise() {
        assertEquals(
            PlaybackProgress(5_000L, 240_000L, 10_000L),
            coordinator.mapEngineProgress(5_000L, 240_000L, 10_000L, fallbackDurationMs = 180_000L),
        )
        assertEquals(
            PlaybackProgress(5_000L, 180_000L, 10_000L),
            coordinator.mapEngineProgress(5_000L, 0L, 10_000L, fallbackDurationMs = 180_000L),
        )
    }

    private class StreamEngineStub : AudioPlayerEngine {
        override val playbackState = MutableStateFlow<PlaybackState>(PlaybackState.Idle)
        override val currentPositionMs = MutableStateFlow(0L)
        override val durationMs = MutableStateFlow(0L)
        override val bufferedPositionMs = MutableStateFlow(0L)
        override val trackTransitions = MutableSharedFlow<Unit>()

        override fun loadTrack(
            server: WebDavServer,
            track: AudioTrack,
            startPositionMs: Long,
        ) {}

        override fun prerollTrack(
            server: WebDavServer,
            track: AudioTrack,
        ) {}

        override fun clearPrerollTrack() {}

        override fun updateTrackMetadata(track: AudioTrack) {}

        override fun setActiveServer(server: WebDavServer) {}

        override fun play() {}

        override fun pause() {}

        override fun seekTo(positionMs: Long) {}

        override fun stop() {}

        override fun release() {}

        override fun setVolume(volume: Float) {}

        override fun getVolume(): Float = 1f
    }
}
