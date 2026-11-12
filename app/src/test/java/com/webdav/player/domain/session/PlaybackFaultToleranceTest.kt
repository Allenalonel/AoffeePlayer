package com.webdav.player.domain.session

import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.PlaybackErrorKind
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.domain.player.FakeAudioPlayerEngine
import com.webdav.player.domain.storage.FakeStorageReachabilityTracker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
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

    private lateinit var fakeEngine: FakeAudioPlayerEngine
    private lateinit var sessionStateFlow: MutableStateFlow<PlayerSessionState>
    private lateinit var queueManager: PlaybackQueueManager
    private lateinit var faultTolerance: PlaybackFaultTolerance
    private lateinit var fakeStorageReachabilityTracker: FakeStorageReachabilityTracker

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
        fakeEngine = FakeAudioPlayerEngine()
        fakeStorageReachabilityTracker = FakeStorageReachabilityTracker()
        sessionStateFlow =
            MutableStateFlow(
                PlayerSessionState(
                    queue = PlaybackQueue(listOf(t1, t2), currentIndex = 0),
                    playbackMode = PlaybackMode.LIST_LOOP,
                ),
            )
        queueManager = PlaybackQueueManager(sessionStateFlow)
        faultTolerance =
            PlaybackFaultTolerance(
                sessionStateFlow = sessionStateFlow,
                playerEngine = fakeEngine,
                storageReachabilityTracker = fakeStorageReachabilityTracker,
                queueManager = queueManager,
                coroutineScope = testScope,
            )
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    @Test
    fun bufferingWatchdog_triggersWeakNetworkAt15sAndPausesAt30s() =
        testScope.runTest {
            faultTolerance.onBufferingStarted()

            // 14 seconds: nothing triggered yet
            advanceTimeBy(14_000L)
            assertFalse(sessionStateFlow.value.isWeakNetwork)
            assertFalse(sessionStateFlow.value.canManualRetry)

            // 15 seconds: weak network flag set, reconnecting
            advanceTimeBy(1_500L)
            assertTrue(sessionStateFlow.value.isWeakNetwork)
            assertTrue(sessionStateFlow.value.isReconnecting)
            assertFalse(sessionStateFlow.value.canManualRetry)

            // 30 seconds total: engine paused, manual retry enabled
            advanceTimeBy(15_000L)
            assertTrue(sessionStateFlow.value.isWeakNetwork)
            assertTrue(sessionStateFlow.value.canManualRetry)
            assertEquals(PlaybackState.Paused, sessionStateFlow.value.playbackState)
            assertEquals(1, fakeEngine.pauseCount)
        }

    @Test
    fun onPlayingStarted_resetsWatchdogAndWeakNetworkFlags() =
        testScope.runTest {
            faultTolerance.onBufferingStarted()
            advanceTimeBy(16_000L)
            assertTrue(sessionStateFlow.value.isWeakNetwork)

            faultTolerance.onPlayingStarted()
            assertFalse(sessionStateFlow.value.isWeakNetwork)
            assertFalse(sessionStateFlow.value.canManualRetry)
            assertFalse(sessionStateFlow.value.isReconnecting)
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
    fun handlePlaybackError_dispatchesByPlaybackErrorKind() {
        var skippedCount = 0
        var unsupportedRangeCallbacks = 0

        assertTrue(
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Not found", kind = PlaybackErrorKind.FILE_NOT_FOUND),
                currentTrack = t1,
                onUnsupportedRange = { unsupportedRangeCallbacks++ },
                onSkipToNext = { skippedCount++ },
            ),
        )
        assertEquals(1, skippedCount)
        assertEquals(
            Triple(t1.serverId, t1.remotePath, InactiveReason.FILE_NOT_FOUND),
            fakeStorageReachabilityTracker.reportedUnavailableTracks.last(),
        )

        assertTrue(
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Auth failed", kind = PlaybackErrorKind.AUTH_INVALID),
                currentTrack = t1,
                onUnsupportedRange = { unsupportedRangeCallbacks++ },
                onSkipToNext = { skippedCount++ },
            ),
        )
        assertEquals(2, skippedCount)
        assertEquals(
            t1.serverId to InactiveReason.SERVER_CREDENTIAL_INVALID,
            fakeStorageReachabilityTracker.reportedUnavailableServers.last(),
        )

        assertTrue(
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Timeout", kind = PlaybackErrorKind.SERVER_UNREACHABLE),
                currentTrack = t2,
                onUnsupportedRange = { unsupportedRangeCallbacks++ },
                onSkipToNext = { skippedCount++ },
            ),
        )
        assertEquals(2, skippedCount) // all 2 tracks now failed -> queue exhausted
        assertEquals(
            t2.serverId to InactiveReason.SERVER_UNREACHABLE,
            fakeStorageReachabilityTracker.reportedUnavailableServers.last(),
        )
        assertEquals(
            PlaybackErrorKind.SERVER_UNREACHABLE,
            (sessionStateFlow.value.playbackState as PlaybackState.Error).kind,
        )

        assertTrue(
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("Range unsupported", kind = PlaybackErrorKind.UNSUPPORTED_RANGE),
                currentTrack = t1,
                onUnsupportedRange = { unsupportedRangeCallbacks++ },
                onSkipToNext = { skippedCount++ },
            ),
        )
        assertEquals(1, unsupportedRangeCallbacks)
        assertEquals(PlaybackState.Paused, sessionStateFlow.value.playbackState)
        assertTrue(sessionStateFlow.value.isUnsupportedRange)

        assertFalse(
            faultTolerance.handlePlaybackError(
                state = PlaybackState.Error("HTTP 404 in message only", kind = PlaybackErrorKind.UNKNOWN),
                currentTrack = t1,
                onUnsupportedRange = { unsupportedRangeCallbacks++ },
                onSkipToNext = { skippedCount++ },
            ),
        )
        assertEquals(PlaybackErrorKind.UNKNOWN, (sessionStateFlow.value.playbackState as PlaybackState.Error).kind)
        assertTrue(sessionStateFlow.value.isWeakNetwork)
        assertTrue(sessionStateFlow.value.canManualRetry)
    }

    @Test
    fun handle404Failure_marksInactiveAndSkipsWhenQueueAvailable() {
        var skipped = false
        faultTolerance.handle404Failure(t1, "Missing file") { skipped = true }

        assertTrue(skipped)
        assertEquals(1, fakeStorageReachabilityTracker.reportedUnavailableTracks.size)
        assertEquals(
            Triple(t1.serverId, t1.remotePath, InactiveReason.FILE_NOT_FOUND),
            fakeStorageReachabilityTracker.reportedUnavailableTracks[0],
        )
    }

    @Test
    fun handleAuthFailure_reportsServerUnavailable() {
        var skipped = false
        faultTolerance.handleAuthFailure(t1, "Auth error") { skipped = true }

        assertTrue(skipped)
        assertEquals(1, fakeStorageReachabilityTracker.reportedUnavailableServers.size)
        assertEquals(
            t1.serverId to InactiveReason.SERVER_CREDENTIAL_INVALID,
            fakeStorageReachabilityTracker.reportedUnavailableServers[0],
        )
    }

    @Test
    fun handle404Failure_setsErrorStateWhenQueueExhausted() {
        // Mark both tracks failed
        queueManager.recordFailedTrack(t1.id, 2)
        var skipped = false
        faultTolerance.handle404Failure(t2, "File deleted") { skipped = true }

        assertFalse(skipped)
        assertTrue(sessionStateFlow.value.playbackState is PlaybackState.Error)
        assertEquals("File deleted", sessionStateFlow.value.errorMessage)
    }

    @Test
    fun unsupportedRangeDialogue_dismissAndConfirm() {
        sessionStateFlow.update { it.copy(isUnsupportedRange = true) }

        faultTolerance.dismissUnsupportedRange()
        assertFalse(sessionStateFlow.value.isUnsupportedRange)

        sessionStateFlow.update { it.copy(isUnsupportedRange = true, errorMessage = "Range error") }
        var replayCalled = false
        faultTolerance.confirmUnsupportedRangeReplay { replayCalled = true }

        assertFalse(sessionStateFlow.value.isUnsupportedRange)
        assertEquals(null, sessionStateFlow.value.errorMessage)
        assertTrue(replayCalled)
    }

    @Test
    fun prematureEofArbitration_differentiatesGenuineCompletionFromPrematureCutoff() {
        // Genuine completion by physical tolerance: within 5000ms
        assertFalse(faultTolerance.isPrematureEof(currentPos = 196_000L, duration = 200_000L))

        // Genuine completion by 95% progress threshold
        assertFalse(faultTolerance.isPrematureEof(currentPos = 191_000L, duration = 200_000L))

        // Premature EOF: cut off at 50%
        assertTrue(faultTolerance.isPrematureEof(currentPos = 100_000L, duration = 200_000L))
    }

    @Test
    fun onNetworkRestored_whenSuspendedInWeakNetwork_triggersAutoResumeAndClearsFlags() {
        sessionStateFlow.update {
            it.copy(
                isWeakNetwork = true,
                canManualRetry = true,
                isReconnecting = true,
                errorMessage = "Network timed out",
            )
        }
        var autoResumeCalled = false
        val result =
            faultTolerance.onNetworkRestored {
                autoResumeCalled = true
            }

        assertTrue(result)
        assertTrue(autoResumeCalled)
        assertFalse(sessionStateFlow.value.isWeakNetwork)
        assertFalse(sessionStateFlow.value.canManualRetry)
        assertFalse(sessionStateFlow.value.isReconnecting)
        assertEquals(null, sessionStateFlow.value.errorMessage)
    }

    @Test
    fun onNetworkRestored_whenUserPaused_suppressesAutoResume() {
        sessionStateFlow.update {
            it.copy(
                playbackState = PlaybackState.Paused,
                isWeakNetwork = false,
                canManualRetry = false,
            )
        }
        var autoResumeCalled = false
        val result =
            faultTolerance.onNetworkRestored {
                autoResumeCalled = true
            }

        assertFalse(result)
        assertFalse(autoResumeCalled)
    }

    @Test
    fun onNetworkRestored_whenNoTrackInQueue_suppressesAutoResume() {
        sessionStateFlow.update {
            it.copy(
                queue = PlaybackQueue.EMPTY,
                isWeakNetwork = true,
                canManualRetry = true,
            )
        }
        var autoResumeCalled = false
        val result =
            faultTolerance.onNetworkRestored {
                autoResumeCalled = true
            }

        assertFalse(result)
        assertFalse(autoResumeCalled)
    }

    @Test
    fun onNetworkRestored_whenWeakNetworkWithoutRetry_suppressesAutoResume() {
        sessionStateFlow.update {
            it.copy(
                isWeakNetwork = true,
                canManualRetry = false,
            )
        }
        var autoResumeCalled = false
        val result =
            faultTolerance.onNetworkRestored {
                autoResumeCalled = true
            }

        assertFalse(result)
        assertFalse(autoResumeCalled)
    }

    @Test
    fun handleServerUnavailable_marksBothTrackAndServerInactive() {
        faultTolerance.handleServerUnavailable(t1) {}

        assertEquals(1, fakeStorageReachabilityTracker.reportedUnavailableServers.size)
        assertEquals(
            t1.serverId to InactiveReason.SERVER_UNREACHABLE,
            fakeStorageReachabilityTracker.reportedUnavailableServers[0],
        )
    }

    @Test
    fun onPlayingStarted_clearsServerUnreachableStatusForCurrentTrack() =
        testScope.runTest {
            sessionStateFlow.update {
                it.copy(
                    queue = PlaybackQueue(listOf(t1), currentIndex = 0),
                    isWeakNetwork = true,
                    canManualRetry = true,
                )
            }

            faultTolerance.onPlayingStarted()
            testScope.testScheduler.advanceUntilIdle()

            assertTrue(fakeStorageReachabilityTracker.reportedReachableServers.contains(t1.serverId))
            val current = sessionStateFlow.value
            assertFalse(current.isWeakNetwork)
            assertFalse(current.canManualRetry)
        }

    @Test
    fun playbackFaultTolerance_hasNoPlaylistRepositoryDependency() {
        val constructors = PlaybackFaultTolerance::class.java.declaredConstructors
        assertTrue(constructors.none { constructor ->
            constructor.parameterTypes.any { it.name.contains("PlaylistRepository") }
        })
    }
}
