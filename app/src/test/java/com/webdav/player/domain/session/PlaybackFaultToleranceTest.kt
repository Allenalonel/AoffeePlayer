package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.PlaybackErrorKind
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.domain.model.WebDavServer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.FileNotFoundException
import java.net.ConnectException

@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackFaultToleranceTest {
    private val testDispatcher = StandardTestDispatcher()
    private val testScope = TestScope(testDispatcher + SupervisorJob())

    private lateinit var faultTolerance: PlaybackFaultTolerance

    private val testServer =
        WebDavServer(
            id = 1L,
            name = "NAS",
            url = "http://nas.local/webdav",
            username = "u",
            password = "p",
        )

    private val t1 =
        AudioTrack(
            id = "1:/song1.mp3",
            serverId = 1L,
            remotePath = "/song1.mp3",
            title = "Song 1",
            durationMs = 200_000L,
            format = AudioFormat.MP3,
        )

    private val t2 =
        AudioTrack(
            id = "1:/song2.mp3",
            serverId = 1L,
            remotePath = "/song2.mp3",
            title = "Song 2",
            durationMs = 200_000L,
            format = AudioFormat.MP3,
        )

    @Before
    fun setUp() {
        faultTolerance = PlaybackFaultTolerance(coroutineScope = testScope)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun bufferingWatchdog_triggersWeakNetworkAt15sAndAutoPauseAt30s() =
        testScope.runTest {
            val timeoutEvents = mutableListOf<Boolean>()
            faultTolerance.onBufferingStarted(isLocalTrack = false) { autoPause ->
                timeoutEvents.add(autoPause)
            }

            advanceTimeBy(14_000L)
            assertTrue(timeoutEvents.isEmpty())

            advanceTimeBy(1_500L)
            assertEquals(listOf(false), timeoutEvents)

            advanceTimeBy(15_000L)
            assertEquals(listOf(false, true), timeoutEvents)
        }

    @Test
    fun bufferingWatchdog_ignoredForLocalTracks_andCancelledOnPlayingStarted() =
        testScope.runTest {
            val timeoutEvents = mutableListOf<Boolean>()
            faultTolerance.onBufferingStarted(isLocalTrack = true) { timeoutEvents.add(it) }
            advanceTimeBy(35_000L)
            assertTrue(timeoutEvents.isEmpty())

            faultTolerance.onBufferingStarted(isLocalTrack = false) { timeoutEvents.add(it) }
            advanceTimeBy(16_000L)
            assertEquals(listOf(false), timeoutEvents)

            val playingDecision =
                faultTolerance.onPlayingStarted(
                    PlayerSessionState(
                        queue = PlaybackQueue(listOf(t1, t2), currentIndex = 0),
                        isWeakNetwork = true,
                        isReconnecting = true,
                    ),
                )
            assertTrue(playingDecision.clearWeakNetworkFlags)
            assertEquals(t1.serverId, playingDecision.reachableServerId)

            advanceTimeBy(20_000L)
            assertEquals(listOf(false), timeoutEvents)
        }

    @Test
    fun errorClassification_accuratelyCategorizesByPlaybackErrorKindWithoutStringMatching() {
        assertTrue(FileNotFoundException("missing").is404Exception())
        assertTrue(SecurityException("revoked").is404Exception())
        assertFalse(RuntimeException("HTTP 404 Not Found").is404Exception())

        assertTrue(faultTolerance.is404Error(PlaybackState.Error("Error", kind = PlaybackErrorKind.FILE_NOT_FOUND)))
        assertFalse(faultTolerance.is404Error(PlaybackState.Error("Resource 404 Not Found", cause = FileNotFoundException("404"))))

        assertTrue(faultTolerance.isAuthError(PlaybackState.Error("Error", kind = PlaybackErrorKind.AUTH_INVALID)))
        assertFalse(faultTolerance.isAuthError(PlaybackState.Error("401 Unauthorized / 403 Forbidden")))

        assertTrue(faultTolerance.isServerUnreachableException(ConnectException("Connection refused")))
        assertFalse(faultTolerance.isServerUnreachableException(RuntimeException("Connection refused")))
        assertTrue(faultTolerance.isServerUnreachableError(PlaybackState.Error("Error", kind = PlaybackErrorKind.SERVER_UNREACHABLE)))
        assertFalse(faultTolerance.isServerUnreachableError(PlaybackState.Error("ConnectException: Failed to connect")))

        assertTrue(faultTolerance.isUnsupportedRangeError(PlaybackState.Error("Error", kind = PlaybackErrorKind.UNSUPPORTED_RANGE)))
        assertFalse(faultTolerance.isUnsupportedRangeError(PlaybackState.Error("UnsupportedRangeReconnectionException")))
    }

    @Test
    fun handlePlaybackError_returnsPureFaultDecisionsByPlaybackErrorKind() {
        val fileNotFound =
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Not found", kind = PlaybackErrorKind.FILE_NOT_FOUND),
                currentTrack = t1,
                canSkip = { true },
            )
        assertEquals(
            FaultDecision.SkipTrack(unavailableTrack = Triple(t1.serverId, t1.remotePath, InactiveReason.FILE_NOT_FOUND)),
            fileNotFound,
        )

        val authSkip =
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Auth failed", kind = PlaybackErrorKind.AUTH_INVALID),
                currentTrack = t1,
                canSkip = { true },
            )
        assertEquals(
            FaultDecision.SkipTrack(unavailableServer = t1.serverId to InactiveReason.SERVER_CREDENTIAL_INVALID),
            authSkip,
        )

        val unreachableExhausted =
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Timeout", kind = PlaybackErrorKind.SERVER_UNREACHABLE),
                currentTrack = t2,
                canSkip = { false },
            )
        assertTrue(unreachableExhausted is FaultDecision.ShowError)
        val err = unreachableExhausted as FaultDecision.ShowError
        assertEquals(PlaybackErrorKind.SERVER_UNREACHABLE, err.error.kind)
        assertEquals(t2.serverId to InactiveReason.SERVER_UNREACHABLE, err.unavailableServer)

        val rangeDecision =
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Range unsupported", kind = PlaybackErrorKind.UNSUPPORTED_RANGE),
                currentTrack = t1,
            )
        assertEquals(FaultDecision.PauseForUnsupportedRange, rangeDecision)

        val unknownState = PlaybackState.Error("HTTP 404 in message only", kind = PlaybackErrorKind.UNKNOWN)
        val unknownDecision = faultTolerance.handlePlaybackError(unknownState, currentTrack = t1)
        assertEquals(FaultDecision.RetryableError(unknownState), unknownDecision)

        val reduced = faultTolerance.reduceStateForDecision(PlayerSessionState(), unknownDecision)
        assertEquals(unknownState, reduced.playbackState)
        assertTrue(reduced.isWeakNetwork)
        assertTrue(reduced.canManualRetry)
    }

    @Test
    fun handleLoadException_mapsExceptionsToFaultDecisions() {
        val notFound = faultTolerance.handleLoadException(FileNotFoundException("gone"), t1) { true }
        assertEquals(
            FaultDecision.SkipTrack(unavailableTrack = Triple(t1.serverId, t1.remotePath, InactiveReason.FILE_NOT_FOUND)),
            notFound,
        )

        val unreachable = faultTolerance.handleLoadException(ConnectException("refused"), t1) { false }
        assertTrue(unreachable is FaultDecision.ShowError)
        assertEquals(PlaybackErrorKind.SERVER_UNREACHABLE, (unreachable as FaultDecision.ShowError).error.kind)

        val generic = faultTolerance.handleLoadException(IllegalStateException("bad stream"), t1)
        assertTrue(generic is FaultDecision.ShowError)
        assertEquals("bad stream", (generic as FaultDecision.ShowError).error.message)
    }

    @Test
    fun handle404Failure_returnsErrorDecisionWhenQueueExhausted() {
        val decision = faultTolerance.handle404Failure(t2, "File deleted") { false }
        assertTrue(decision is FaultDecision.ShowError)
        val showError = decision as FaultDecision.ShowError
        assertEquals("File deleted", showError.error.message)
        assertEquals(PlaybackErrorKind.FILE_NOT_FOUND, showError.error.kind)
        assertEquals(Triple(t2.serverId, t2.remotePath, InactiveReason.FILE_NOT_FOUND), showError.unavailableTrack)
    }

    @Test
    fun prematureEofAndEvaluatePlaybackEnded_coverAllOutcomes() {
        assertFalse(faultTolerance.isPrematureEof(currentPos = 196_000L, duration = 200_000L))
        assertFalse(faultTolerance.isPrematureEof(currentPos = 191_000L, duration = 200_000L))
        assertTrue(faultTolerance.isPrematureEof(currentPos = 100_000L, duration = 200_000L))

        val baseState =
            PlayerSessionState(
                activeServer = testServer,
                queue = PlaybackQueue(listOf(t1, t2), currentIndex = 0),
                playbackMode = PlaybackMode.LIST_LOOP,
                durationMs = 200_000L,
            )

        assertEquals(
            PlaybackEndedDecision.PrematureEof(100_000L),
            faultTolerance.evaluatePlaybackEnded(baseState, currentPos = 100_000L, engineDurationMs = 200_000L),
        )
        assertEquals(
            PlaybackEndedDecision.AdvanceQueue(testServer),
            faultTolerance.evaluatePlaybackEnded(baseState, currentPos = 198_000L, engineDurationMs = 200_000L),
        )
        assertEquals(
            PlaybackEndedDecision.SingleLoopReplay(200_000L),
            faultTolerance.evaluatePlaybackEnded(
                baseState.copy(playbackMode = PlaybackMode.SINGLE_LOOP),
                currentPos = 198_000L,
                engineDurationMs = 200_000L,
            ),
        )
        assertEquals(
            PlaybackEndedDecision.Ended,
            faultTolerance.evaluatePlaybackEnded(
                baseState.copy(queue = PlaybackQueue.EMPTY),
                currentPos = 198_000L,
                engineDurationMs = 200_000L,
            ),
        )
    }

    @Test
    fun onNetworkRestored_evaluatesAutoResumeStrictly() {
        val suspendedState =
            PlayerSessionState(
                queue = PlaybackQueue(listOf(t1, t2), currentIndex = 0),
                isWeakNetwork = true,
                canManualRetry = true,
                isReconnecting = true,
                errorMessage = "Network timed out",
            )
        assertEquals(NetworkRestoreDecision.AutoResume, faultTolerance.onNetworkRestored(suspendedState))

        assertEquals(
            NetworkRestoreDecision.Ignore,
            faultTolerance.onNetworkRestored(
                suspendedState.copy(playbackState = PlaybackState.Paused, isWeakNetwork = false, canManualRetry = false),
            ),
        )
        assertEquals(
            NetworkRestoreDecision.Ignore,
            faultTolerance.onNetworkRestored(suspendedState.copy(queue = PlaybackQueue.EMPTY)),
        )
        assertEquals(
            NetworkRestoreDecision.Ignore,
            faultTolerance.onNetworkRestored(suspendedState.copy(canManualRetry = false)),
        )
    }

    @Test
    fun evaluatePreroll_schedulesOnlyWhenPlayingHealthyAndWithinTriggerWindow() {
        val playingState =
            PlayerSessionState(
                activeServer = testServer,
                queue = PlaybackQueue(listOf(t1, t2), currentIndex = 0),
                playbackState = PlaybackState.Playing,
                playbackMode = PlaybackMode.LIST_LOOP,
            )
        val inWindow = PlaybackProgress(currentPositionMs = 192_000L, durationMs = 200_000L, bufferedPositionMs = 195_000L)

        assertEquals(
            PrerollPlan.ScheduleNext(expectedTrackId = t1.id, fallbackServer = testServer),
            faultTolerance.evaluatePreroll(inWindow, playingState, isPrerollAlreadyAppended = false),
        )
        assertEquals(
            PrerollPlan.None,
            faultTolerance.evaluatePreroll(inWindow, playingState, isPrerollAlreadyAppended = true),
        )
        assertEquals(
            PrerollPlan.None,
            faultTolerance.evaluatePreroll(
                PlaybackProgress(currentPositionMs = 100_000L, durationMs = 200_000L, bufferedPositionMs = 120_000L),
                playingState,
                isPrerollAlreadyAppended = false,
            ),
        )
        assertEquals(
            PrerollPlan.None,
            faultTolerance.evaluatePreroll(
                inWindow,
                playingState.copy(playbackState = PlaybackState.Paused),
                isPrerollAlreadyAppended = false,
            ),
        )
    }
}
