package com.webdav.player.domain.session

import com.webdav.player.data.player.StreamingHardwareLockManager
import com.webdav.player.data.player.UnsupportedRangeReconnectionException
import com.webdav.player.data.remote.ConnectionResult
import com.webdav.player.data.remote.WebDavClient
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.InactiveReason
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.ListDirectoryResult
import com.webdav.player.domain.model.NetworkStatus
import com.webdav.player.domain.model.PlaybackMode
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlaybackSessionData
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.domain.model.Playlist
import com.webdav.player.domain.model.PlaylistTrackEntry
import com.webdav.player.domain.model.QueueOrigin
import com.webdav.player.domain.model.RemoteDirectory
import com.webdav.player.domain.model.RemoteFile
import com.webdav.player.domain.model.TrackMetadata
import com.webdav.player.domain.model.WebDavServer
import com.webdav.player.domain.network.FakeNetworkConnectivityObserver
import com.webdav.player.domain.player.FakeAudioPlayerEngine
import com.webdav.player.domain.repository.PlaylistRepository
import com.webdav.player.domain.repository.TrackMetadataRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * State and lifecycle test suite for MusicPlayerAppSessionImpl.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MusicPlayerAppSessionTest {
    private val testDispatcher = StandardTestDispatcher()
    private lateinit var fakeEngine: FakeAudioPlayerEngine
    private lateinit var sessionJob: kotlinx.coroutines.CompletableJob
    private lateinit var sessionScope: kotlinx.coroutines.CoroutineScope
    private lateinit var session: MusicPlayerAppSessionImpl

    private class FakeStreamingHardwareLockManager : StreamingHardwareLockManager {
        override var isWakeLockHeld: Boolean = false
        override var isWifiLockHeld: Boolean = false

        override fun acquireLocks() {
            isWakeLockHeld = true
            isWifiLockHeld = true
        }

        override fun acquireWakeLockOnly() {
            isWakeLockHeld = true
            isWifiLockHeld = false
        }

        override fun releaseWifiLock() {
            isWifiLockHeld = false
        }

        override fun releaseLocks() {
            isWakeLockHeld = false
            isWifiLockHeld = false
        }

        override fun release() {
            releaseLocks()
        }
    }

    private val testServer =
        WebDavServer(
            id = 1L,
            name = "Home NAS",
            url = "http://192.168.1.100:8080/webdav",
            port = 8080,
            pathPrefix = "/webdav",
            username = "admin",
            password = "password123",
        )

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        fakeEngine = FakeAudioPlayerEngine()
        sessionJob = SupervisorJob()
        sessionScope = kotlinx.coroutines.CoroutineScope(testDispatcher + sessionJob)
        session =
            MusicPlayerAppSessionImpl(
                playerEngine = fakeEngine,
                serverRepository = null,
                coroutineScope = sessionScope,
            )
    }

    @After
    fun tearDown() {
        session.release()
        sessionJob.cancel()
        Dispatchers.resetMain()
    }

    @Test
    fun initialState_isEmptyAndIdle() =
        runTest(testDispatcher) {
            val state = session.sessionState.value
            assertNull(state.activeServer)
            assertTrue(state.queue.isEmpty)
            assertTrue(state.isIdle)
            assertFalse(state.isPlaying)
            assertNull(state.currentTrack)
            assertEquals(PlaybackMode.LIST_LOOP, state.playbackMode)
        }

    @Test
    fun setActiveServer_updatesSessionState() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            assertEquals(testServer, session.sessionState.value.activeServer)
        }

    @Test
    fun playDirectoryTrack_populatesQueueWithOnlyAudioFilesAndStartsPlayback() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            val track1 = RemoteFile(name = "01 - Intro.mp3", path = "/Music/Album/01 - Intro.mp3", size = 1000)
            val lrc = RemoteFile(name = "01 - Intro.lrc", path = "/Music/Album/01 - Intro.lrc", size = 200)
            val track2 = RemoteFile(name = "02 - Main Song.flac", path = "/Music/Album/02 - Main Song.flac", size = 5000)
            val cover = RemoteFile(name = "cover.jpg", path = "/Music/Album/cover.jpg", size = 400)
            val track3 = RemoteFile(name = "03 - Outro.wav", path = "/Music/Album/03 - Outro.wav", size = 8000)

            val directory =
                RemoteDirectory(
                    path = "/Music/Album/",
                    name = "Album",
                    files = listOf(track1, lrc, track2, cover, track3),
                )

            // User clicks track 2
            session.playDirectoryTrack(directory, track2)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(3, state.queue.size)
            assertEquals(1, state.queue.currentIndex)
            assertEquals("02 - Main Song.flac", state.currentTrack?.title)
            assertEquals(AudioFormat.FLAC, state.currentTrack?.format)

            // Verify fake player received loadTrack for selected track
            assertEquals(testServer, fakeEngine.lastServer)
            assertEquals(state.currentTrack, fakeEngine.loadedTrack)
            assertTrue(fakeEngine.loadTrackCount >= 1)
            assertTrue(state.isPlaying)
        }

    @Test
    fun playDirectoryTrack_withInitialMetadata_immediatelyPopulatesArtworkAndMetadata() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            val track1 = RemoteFile(name = "01 - Intro.mp3", path = "/Music/Album/01 - Intro.mp3", size = 1000)
            val track2 = RemoteFile(name = "02 - Main Song.flac", path = "/Music/Album/02 - Main Song.flac", size = 5000)
            val directory = RemoteDirectory(path = "/Music/Album/", name = "Album", files = listOf(track1, track2))

            val metadataMap =
                mapOf(
                    track2.path to
                        TrackMetadata(
                            serverId = testServer.id,
                            remotePath = track2.path,
                            title = "Enriched Main Song",
                            artist = "Enriched Artist",
                            album = "Enriched Album",
                            durationMs = 215000L,
                            coverThumbnailPath = "/covers/enriched_thumb.jpg",
                        ),
                )

            session.playDirectoryTrack(directory, track2, initialMetadata = metadataMap)

            val immediateTrack = session.sessionState.value.currentTrack
            assertNotNull(immediateTrack)
            assertEquals("Enriched Main Song", immediateTrack?.title)
            assertEquals("Enriched Artist", immediateTrack?.artist)
            assertEquals("Enriched Album", immediateTrack?.album)
            assertEquals(215000L, immediateTrack?.durationMs)
            assertEquals("/covers/enriched_thumb.jpg", immediateTrack?.coverThumbnailPath)

            assertEquals(215000L, session.playbackProgress.value.durationMs)
            assertEquals("/covers/enriched_thumb.jpg", fakeEngine.loadedTrack?.coverThumbnailPath)
        }

    @Test
    fun playDirectoryTrack_withoutInitialMetadata_immediatelyEnrichesFromRoomCache() =
        runTest(testDispatcher) {
            val fakeMetadataRepo = FakeTrackMetadataRepo()
            val sessionWithRepo =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    trackMetadataRepository = fakeMetadataRepo,
                    coroutineScope = sessionScope,
                )
            sessionWithRepo.setActiveServer(testServer)
            advanceUntilIdle()

            val track = RemoteFile(name = "cached.mp3", path = "/Music/cached.mp3", size = 3000)
            val directory = RemoteDirectory(path = "/Music/", name = "Music", files = listOf(track))

            val cachedMetadata =
                TrackMetadata(
                    serverId = testServer.id,
                    remotePath = track.path,
                    title = "Cached Song Title",
                    artist = "Cached Artist",
                    durationMs = 180000L,
                    coverThumbnailPath = "/cache/covers/room_art.jpg",
                )
            fakeMetadataRepo.emit(listOf(cachedMetadata))
            advanceUntilIdle()

            sessionWithRepo.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            val currentTrack = sessionWithRepo.sessionState.value.currentTrack
            assertNotNull(currentTrack)
            assertEquals("Cached Song Title", currentTrack?.title)
            assertEquals("Cached Artist", currentTrack?.artist)
            assertEquals("/cache/covers/room_art.jpg", currentTrack?.coverThumbnailPath)
            assertEquals(180000L, currentTrack?.durationMs)
        }

    @Test
    fun playDirectoryTrack_withWmaFile_populatesQueueWithWmaAudioTrackAndMimeType() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            val wmaTrack = RemoteFile(name = "01 - Classic.wma", path = "/Music/01 - Classic.wma", size = 1500)
            val mp3Track = RemoteFile(name = "02 - Pop.mp3", path = "/Music/02 - Pop.mp3", size = 2500)
            val directory =
                RemoteDirectory(
                    path = "/Music/",
                    name = "Music",
                    files = listOf(wmaTrack, mp3Track),
                )

            session.playDirectoryTrack(directory, wmaTrack)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(2, state.queue.size)
            assertEquals(0, state.queue.currentIndex)
            assertEquals(AudioFormat.WMA, state.currentTrack?.format)
            assertEquals("audio/x-ms-wma", state.currentTrack?.format?.mimeType)

            assertEquals(testServer, fakeEngine.lastServer)
            assertEquals(AudioFormat.WMA, fakeEngine.loadedTrack?.format)
        }

    @Test
    fun togglePlayPause_pausesWhenPlaying_andResumesWhenPaused() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            val track = RemoteFile(name = "test.mp3", path = "/test.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            assertTrue(session.sessionState.value.isPlaying)

            // Toggle to pause
            session.togglePlayPause()
            advanceUntilIdle()
            assertEquals(1, fakeEngine.pauseCount)
            assertTrue(session.sessionState.value.isPaused)

            // Toggle to resume
            session.togglePlayPause()
            advanceUntilIdle()
            assertEquals(1, fakeEngine.playCount)
            assertTrue(session.sessionState.value.isPlaying)
        }

    @Test
    fun sessionSkipToNext_updatesQueueCurrentIndexAndLoadsTrack() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)

            // Session skips to next track
            session.skipToNext()
            advanceUntilIdle()

            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals("02.mp3", fakeEngine.loadedTrack?.title)
        }

    @Test
    fun engineError_updatesErrorMessageAndState() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            fakeEngine._playbackState.value = PlaybackState.Error("Network timeout")
            advanceUntilIdle()

            val state = session.sessionState.value
            assertTrue(state.playbackState is PlaybackState.Error)
            assertEquals("Network timeout", state.errorMessage)
        }

    @Test
    fun unsupportedRangeError_setsUnsupportedRangeFlagAndPausesPlayback_preservingQueueIndexAndPosition() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // Simulate playback progressing to 45 seconds (45,000 ms)
            fakeEngine._currentPositionMs.value = 45_000L
            advanceUntilIdle()

            // Trigger UnsupportedRangeReconnectionException via playerEngine playback error
            val unsupportedException =
                UnsupportedRangeReconnectionException(
                    "Remote server does not support partial content (HTTP 200 returned on Range request)",
                )
            fakeEngine._playbackState.value =
                PlaybackState.Error(
                    message = "Remote server does not support partial content (HTTP 200 returned on Range request)",
                    cause = unsupportedException,
                )
            advanceUntilIdle()

            val state = session.sessionState.value
            assertTrue("isUnsupportedRange must be true", state.isUnsupportedRange)
            assertEquals("Playback state must be Paused", PlaybackState.Paused, state.playbackState)
            assertTrue("Player engine pause must be called", fakeEngine.pauseCount >= 1)
            assertEquals("Queue current index must be preserved", 0, state.queue.currentIndex)
            assertEquals("Playback position must be preserved", 45_000L, session.playbackProgress.value.currentPositionMs)
        }

    @Test
    fun confirmUnsupportedRangeReplay_clearsUnsupportedRangeFlag_reloadsTrackFromBeginningAndPlays() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            fakeEngine._currentPositionMs.value = 60_000L
            advanceUntilIdle()

            val unsupportedException =
                UnsupportedRangeReconnectionException(
                    "Remote server does not support partial content (HTTP 200 returned on Range request)",
                )
            fakeEngine._playbackState.value =
                PlaybackState.Error(
                    message = "Remote server does not support partial content (HTTP 200 returned on Range request)",
                    cause = unsupportedException,
                )
            advanceUntilIdle()

            assertTrue(session.sessionState.value.isUnsupportedRange)
            val initialLoadCount = fakeEngine.loadTrackCount

            // User confirms replay from beginning
            session.confirmUnsupportedRangeReplay()
            advanceUntilIdle()

            assertFalse("isUnsupportedRange must be cleared", session.sessionState.value.isUnsupportedRange)
            assertTrue("PlayerEngine loadTrack must be called to reload from 0L", fakeEngine.loadTrackCount > initialLoadCount)
            assertEquals("LoadTrack startPositionMs must be 0L", 0L, fakeEngine.lastStartPositionMs)
        }

    @Test
    fun dismissUnsupportedRange_clearsUnsupportedRangeFlag_retainsCutOffPositionAndRemainsPaused() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            fakeEngine._currentPositionMs.value = 75_000L
            advanceUntilIdle()

            val unsupportedException =
                UnsupportedRangeReconnectionException(
                    "Remote server does not support partial content (HTTP 200 returned on Range request)",
                )
            fakeEngine._playbackState.value =
                PlaybackState.Error(
                    message = "Remote server does not support partial content (HTTP 200 returned on Range request)",
                    cause = unsupportedException,
                )
            advanceUntilIdle()

            assertTrue(session.sessionState.value.isUnsupportedRange)
            val playCountBeforeDismiss = fakeEngine.playCount

            // User dismisses dialog
            session.dismissUnsupportedRange()
            advanceUntilIdle()

            assertFalse("isUnsupportedRange must be cleared", session.sessionState.value.isUnsupportedRange)
            assertEquals("Playback state must remain Paused", PlaybackState.Paused, session.sessionState.value.playbackState)
            assertEquals("play must not be called upon dismiss", playCountBeforeDismiss, fakeEngine.playCount)
            assertEquals("Position must be retained at cut-off point", 75_000L, session.playbackProgress.value.currentPositionMs)
        }

    @Test
    fun unsupportedRangeError_recognizesNestedTypedCause() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // Nested causal chain: Exception -> IOException -> UnsupportedRangeReconnectionException
            val rootException = UnsupportedRangeReconnectionException("HTTP 200 on Range")
            val wrappedException = java.lang.RuntimeException("Playback failed", java.io.IOException("Stream error", rootException))

            fakeEngine._playbackState.value =
                PlaybackState.Error(
                    message = "Unspecified playback error",
                    cause = wrappedException,
                )
            advanceUntilIdle()

            assertTrue(
                "Typed causal chain traversal must identify UnsupportedRangeReconnectionException",
                session.sessionState.value.isUnsupportedRange,
            )
            assertEquals(PlaybackState.Paused, session.sessionState.value.playbackState)
        }

    @Test
    fun skipToNext_whenUnsupportedRangeActive_resetsFlagAndCancelsLingeringWatchdog() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            val unsupportedException = UnsupportedRangeReconnectionException("HTTP 200 on Range")
            fakeEngine._playbackState.value =
                PlaybackState.Error(
                    message = "HTTP 200 on Range",
                    cause = unsupportedException,
                )
            advanceUntilIdle()

            assertTrue(session.sessionState.value.isUnsupportedRange)

            // User skips to next track via external control / notification
            session.skipToNext()
            advanceUntilIdle()

            assertFalse("isUnsupportedRange must be reset to false upon queue transition", session.sessionState.value.isUnsupportedRange)
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
        }

    @Test
    fun switchingActiveServer_resetsPlaybackQueueAndStopsPlayback() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            assertTrue(session.sessionState.value.hasTrack)

            val otherServer = WebDavServer(id = 2L, name = "Other", url = "http://example.com")
            session.setActiveServer(otherServer)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(otherServer, state.activeServer)
            assertTrue(state.queue.isEmpty)
            assertTrue(state.isIdle)
            assertEquals(1, fakeEngine.stopCount)
        }

    @Test
    fun switchingActiveServer_withResetPlaybackFalse_preservesOngoingPlaybackAndQueue() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            assertTrue(session.sessionState.value.hasTrack)

            val otherServer = WebDavServer(id = 2L, name = "Other", url = "http://example.com")
            session.setActiveServer(otherServer, resetPlayback = false)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(otherServer, state.activeServer)
            assertTrue("Queue must be preserved when resetPlayback is false", state.hasTrack)
            assertEquals("01.mp3", state.currentTrack?.title)
            assertEquals(0, fakeEngine.stopCount)
        }

    @Test
    fun playbackControls_dispatchToPlayerEngine() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            session.pause()
            advanceUntilIdle()
            assertEquals(1, fakeEngine.pauseCount)

            session.play()
            advanceUntilIdle()
            assertEquals(1, fakeEngine.playCount)

            session.seekTo(5000L)
            advanceUntilIdle()
            assertEquals(5000L, fakeEngine.seekToPosition)
            assertEquals(5000L, session.playbackProgress.value.currentPositionMs)

            session.skipToNext()
            advanceUntilIdle()
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals("02.mp3", fakeEngine.loadedTrack?.title)

            session.skipToPrevious()
            advanceUntilIdle()
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals("01.mp3", fakeEngine.loadedTrack?.title)

            session.playQueueIndex(1)
            advanceUntilIdle()
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals("02.mp3", fakeEngine.loadedTrack?.title)

            session.stop()
            advanceUntilIdle()
            assertEquals(1, fakeEngine.stopCount)
            assertTrue(session.sessionState.value.isIdle)
        }

    @Test
    fun cyclePlaybackMode_cyclesThroughAllModes() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            assertEquals(PlaybackMode.LIST_LOOP, session.sessionState.value.playbackMode)

            // LIST_LOOP -> SINGLE_LOOP
            session.cyclePlaybackMode()
            advanceUntilIdle()
            assertEquals(PlaybackMode.SINGLE_LOOP, session.sessionState.value.playbackMode)

            // SINGLE_LOOP -> SHUFFLE
            session.cyclePlaybackMode()
            advanceUntilIdle()
            assertEquals(PlaybackMode.SHUFFLE, session.sessionState.value.playbackMode)

            // SHUFFLE -> LIST_LOOP
            session.cyclePlaybackMode()
            advanceUntilIdle()
            assertEquals(PlaybackMode.LIST_LOOP, session.sessionState.value.playbackMode)
        }

    @Test
    fun removeQueueTrack_removesNonActiveTrackAndKeepsCurrentTrack() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val track3 = RemoteFile(name = "03.mp3", path = "/03.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2, track3))
            session.playDirectoryTrack(directory, track2) // Playing track 2 (index 1)
            advanceUntilIdle()

            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )

            // Remove track 0 (track 1)
            session.removeQueueTrack(0)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(2, state.queue.size)
            assertEquals(0, state.queue.currentIndex)
            assertEquals("02.mp3", state.currentTrack?.title)
        }

    @Test
    fun removeQueueTrack_removesActiveTrack_andAdvancesToNext() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val track3 = RemoteFile(name = "03.mp3", path = "/03.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2, track3))
            session.playDirectoryTrack(directory, track2) // Playing track 2 (index 1)
            advanceUntilIdle()

            // Remove current track (index 1)
            session.removeQueueTrack(1)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(2, state.queue.size)
            assertEquals(1, state.queue.currentIndex)
            assertEquals("03.mp3", state.currentTrack?.title)
        }

    @Test
    fun removeQueueTrack_removesOnlyTrack_stopsAndClearsQueue() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(1, session.sessionState.value.queue.size)

            // Remove only track
            session.removeQueueTrack(0)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertTrue(state.queue.isEmpty)
            assertTrue(state.isIdle)
            assertEquals(1, fakeEngine.stopCount)
        }

    @Test
    fun playbackModeTransitions_listLoopWraparound() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track2) // index 1 (last track)
            advanceUntilIdle()

            session.setPlaybackMode(PlaybackMode.LIST_LOOP)
            advanceUntilIdle()

            // Next at last track wraps to 0
            session.skipToNext()
            advanceUntilIdle()
            assertEquals("01.mp3", fakeEngine.loadedTrack?.title)
            assertEquals(0, session.sessionState.value.queue.currentIndex)

            // Simulate track completion wraps to 1 then to 0
            fakeEngine.simulateTrackCompletion()
            advanceUntilIdle()
            assertEquals(1, session.sessionState.value.queue.currentIndex)

            fakeEngine.simulateTrackCompletion()
            advanceUntilIdle()
            assertEquals(0, session.sessionState.value.queue.currentIndex)
        }

    @Test
    fun playbackModeTransitions_singleLoopReplaysCurrentTrack() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1) // index 0
            advanceUntilIdle()

            session.setPlaybackMode(PlaybackMode.SINGLE_LOOP)
            fakeEngine.seekTo(45000L)
            advanceUntilIdle()

            assertEquals(45000L, fakeEngine._currentPositionMs.value)
            assertEquals("01.mp3", fakeEngine.loadedTrack?.title)

            // Track completes -> replays same track from 0L
            fakeEngine.simulateTrackCompletion()
            advanceUntilIdle()

            assertEquals(0L, fakeEngine._currentPositionMs.value)
            assertEquals(0, session.sessionState.value.queue.currentIndex)
        }

    @Test
    fun playbackModeTransitions_shuffleFollowsPermutation() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val track3 = RemoteFile(name = "03.mp3", path = "/03.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2, track3))
            session.playDirectoryTrack(directory, track1) // index 0
            advanceUntilIdle()

            session.setPlaybackMode(PlaybackMode.SHUFFLE)
            advanceUntilIdle()

            val shuffledQueue = session.sessionState.value.queue
            assertTrue(shuffledQueue.isShuffled)
            assertEquals(0, shuffledQueue.currentIndex)
            assertEquals("01.mp3", shuffledQueue.currentTrack?.title)

            // Track completion moves 0 -> 1 in the shuffled queue
            fakeEngine.simulateTrackCompletion()
            advanceUntilIdle()
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(shuffledQueue.tracks[1].title, fakeEngine.loadedTrack?.title)

            // Next completion moves 1 -> 2
            fakeEngine.simulateTrackCompletion()
            advanceUntilIdle()
            assertEquals(2, session.sessionState.value.queue.currentIndex)
            assertEquals(shuffledQueue.tracks[2].title, fakeEngine.loadedTrack?.title)

            // Next completion at end of shuffle cycle triggers reshuffle back to 0
            fakeEngine.simulateTrackCompletion()
            advanceUntilIdle()
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertNotNull(fakeEngine.loadedTrack)
        }

    @Test
    fun metadataResolution_enrichesPlaybackQueueTracksReactively() =
        runTest(testDispatcher) {
            val fakeMetadataRepo = FakeTrackMetadataRepo()
            val sessionWithRepo =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    trackMetadataRepository = fakeMetadataRepo,
                    coroutineScope = sessionScope,
                )

            sessionWithRepo.setActiveServer(testServer)
            val file1 = RemoteFile(name = "song1.mp3", path = "/music/song1.mp3")
            val file2 = RemoteFile(name = "song2.flac", path = "/music/song2.flac")
            val dir = RemoteDirectory(path = "/music/", name = "music", files = listOf(file1, file2))

            sessionWithRepo.playDirectoryTrack(dir, file1)
            advanceUntilIdle()

            // Initially tracks have file names
            val initialTracks = sessionWithRepo.sessionState.value.queue.tracks
            assertEquals(2, initialTracks.size)
            assertEquals("song1.mp3", initialTracks[0].title)
            assertNull(initialTracks[0].artist)

            // Metadata arrives
            fakeMetadataRepo.emit(
                listOf(
                    TrackMetadata(
                        serverId = testServer.id,
                        remotePath = "/music/song1.mp3",
                        title = "Rich Song One",
                        artist = "Awesome Artist",
                        album = "Great Album",
                        trackNumber = 1,
                        durationMs = 180000L,
                        coverThumbnailPath = "/cache/cover1.jpg",
                    ),
                ),
            )
            advanceUntilIdle()

            val enrichedTracks = sessionWithRepo.sessionState.value.queue.tracks
            assertEquals("Rich Song One", enrichedTracks[0].title)
            assertEquals("Awesome Artist", enrichedTracks[0].artist)
            assertEquals("Great Album", enrichedTracks[0].album)
            assertEquals(180000L, enrichedTracks[0].durationMs)
            assertEquals("/cache/cover1.jpg", enrichedTracks[0].coverThumbnailPath)

            sessionWithRepo.release()
        }

    @Test
    fun metadataResolution_onlyUpdatesTracksWhoseMetadataActuallyChanged() =
        runTest(testDispatcher) {
            val fakeMetadataRepo = FakeTrackMetadataRepo()
            val sessionWithRepo =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    trackMetadataRepository = fakeMetadataRepo,
                    coroutineScope = sessionScope,
                )

            sessionWithRepo.setActiveServer(testServer)
            val file1 = RemoteFile(name = "song1.mp3", path = "/music/song1.mp3")
            val file2 = RemoteFile(name = "song2.flac", path = "/music/song2.flac")
            val file3 = RemoteFile(name = "song3.wav", path = "/music/song3.wav")
            val dir = RemoteDirectory(path = "/music/", name = "music", files = listOf(file1, file2, file3))

            sessionWithRepo.playDirectoryTrack(dir, file1)
            advanceUntilIdle()

            fakeEngine.updateTrackMetadataCalls = 0

            // Metadata arrives for ONLY song1
            fakeMetadataRepo.emit(
                listOf(
                    TrackMetadata(
                        serverId = testServer.id,
                        remotePath = "/music/song1.mp3",
                        title = "Rich Song One",
                        artist = "Awesome Artist",
                        album = "Great Album",
                    ),
                ),
            )
            advanceUntilIdle()

            // Should update metadata for currently playing song1 (index 0)
            assertEquals(1, fakeEngine.updateTrackMetadataCalls)

            // Now metadata for song2 arrives: Room emits [song1, song2]
            fakeMetadataRepo.emit(
                listOf(
                    TrackMetadata(
                        serverId = testServer.id,
                        remotePath = "/music/song1.mp3",
                        title = "Rich Song One",
                        artist = "Awesome Artist",
                        album = "Great Album",
                    ),
                    TrackMetadata(
                        serverId = testServer.id,
                        remotePath = "/music/song2.flac",
                        title = "Rich Song Two",
                        artist = "Awesome Artist",
                        album = "Great Album",
                    ),
                ),
            )
            advanceUntilIdle()

            // song1 did NOT change! song2 changed in queue, but since song2 is not playing, engine is not disrupted.
            assertEquals(1, fakeEngine.updateTrackMetadataCalls)
            assertEquals(
                "Rich Song Two",
                sessionWithRepo.sessionState.value.queue.tracks[1]
                    .title,
            )

            sessionWithRepo.release()
        }

    @Test
    fun metadataResolution_preservesPhysicalDuration_whenPhysicalDurationAlreadyProbed() =
        runTest(testDispatcher) {
            val fakeMetadataRepo = FakeTrackMetadataRepo()
            val sessionWithRepo =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    trackMetadataRepository = fakeMetadataRepo,
                    coroutineScope = sessionScope,
                )

            sessionWithRepo.setActiveServer(testServer)
            val file1 = RemoteFile(name = "song1.mp3", path = "/music/song1.mp3")
            val dir = RemoteDirectory(path = "/music/", name = "music", files = listOf(file1))

            sessionWithRepo.playDirectoryTrack(dir, file1)
            advanceUntilIdle()

            // Decoder establishes true physical duration of 200_000L
            fakeEngine._durationMs.value = 200_000L
            advanceUntilIdle()

            assertEquals(200_000L, sessionWithRepo.sessionState.value.durationMs)
            assertEquals(
                200_000L,
                sessionWithRepo.sessionState.value.currentTrack
                    ?.durationMs,
            )

            // Room / static tag metadata arrives with inflated duration of 215_000L
            fakeMetadataRepo.emit(
                listOf(
                    TrackMetadata(
                        serverId = testServer.id,
                        remotePath = "/music/song1.mp3",
                        title = "Song One Enriched",
                        artist = "True Artist",
                        album = "Real Album",
                        durationMs = 215_000L,
                    ),
                ),
            )
            advanceUntilIdle()

            // Spec Story 9: Physical duration takes precedence over tag metadata
            assertEquals(
                "Song One Enriched",
                sessionWithRepo.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(
                "True Artist",
                sessionWithRepo.sessionState.value.currentTrack
                    ?.artist,
            )
            assertEquals(200_000L, sessionWithRepo.sessionState.value.durationMs)
            assertEquals(
                200_000L,
                sessionWithRepo.sessionState.value.currentTrack
                    ?.durationMs,
            )
            assertEquals(200_000L, sessionWithRepo.playbackProgress.value.durationMs)

            sessionWithRepo.release()
        }

    @Test
    fun playDirectoryTrack_doesNotTriggerRedundantMetadataResolution() =
        runTest(testDispatcher) {
            val fakeMetadataRepo = FakeTrackMetadataRepo()
            val sessionWithRepo =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    trackMetadataRepository = fakeMetadataRepo,
                    coroutineScope = sessionScope,
                )

            sessionWithRepo.setActiveServer(testServer)
            val file1 = RemoteFile(name = "song1.mp3", path = "/music/song1.mp3")
            val file2 = RemoteFile(name = "song2.flac", path = "/music/song2.flac")
            val dir = RemoteDirectory(path = "/music/", name = "music", files = listOf(file1, file2))

            sessionWithRepo.playDirectoryTrack(dir, file1)
            advanceUntilIdle()

            // Verification: resolveMetadata must NOT be triggered during on-demand playback,
            // as metadata resolution is strictly owned and coordinated by directory load completion.
            assertEquals(0, fakeMetadataRepo.resolveMetadataCalls)

            sessionWithRepo.release()
        }

    @Test
    fun sessionState_lyricsLoadedAutomatically_whenTrackStartsPlaying() =
        runTest(testDispatcher) {
            val fakeMetadataRepo = FakeTrackMetadataRepo()
            val expectedLyrics =
                com.webdav.player.domain.model.Lyrics(
                    lines =
                        listOf(
                            com.webdav.player.domain.model
                                .LyricLine(1000L, "Hello world"),
                        ),
                    isSynchronized = true,
                )
            fakeMetadataRepo.lyricsMap["/music/song1.mp3"] = expectedLyrics

            val sessionWithLyrics =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    trackMetadataRepository = fakeMetadataRepo,
                    coroutineScope = sessionScope,
                )

            sessionWithLyrics.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "1:/music/song1.mp3",
                    serverId = testServer.id,
                    remotePath = "/music/song1.mp3",
                    title = "song1.mp3",
                    format = AudioFormat.MP3,
                )

            sessionWithLyrics.playTrack(track)
            advanceUntilIdle()

            val state = sessionWithLyrics.sessionState.value
            assertNotNull(state.lyrics)
            assertEquals(1, state.lyrics?.lines?.size)
            assertEquals(
                "Hello world",
                state.lyrics
                    ?.lines
                    ?.get(0)
                    ?.text,
            )
            assertFalse(state.isLoadingLyrics)

            sessionWithLyrics.release()
        }

    private class FakeTrackMetadataRepo : TrackMetadataRepository {
        val flow = MutableStateFlow<List<TrackMetadata>>(emptyList())
        var resolveMetadataCalls = 0
        var resolvedFiles: List<RemoteFile> = emptyList()
        val lyricsMap = mutableMapOf<String, com.webdav.player.domain.model.Lyrics>()

        override suspend fun resolveLyrics(
            server: WebDavServer,
            track: AudioTrack,
        ): com.webdav.player.domain.model.Lyrics = lyricsMap[track.remotePath] ?: com.webdav.player.domain.model.Lyrics.EMPTY

        fun emit(list: List<TrackMetadata>) {
            flow.value = list
        }

        override fun getAllMetadataFlow(serverId: Long): Flow<List<TrackMetadata>> = flow.asStateFlow()

        override fun getMetadataForPathsFlow(
            serverId: Long,
            remotePaths: List<String>,
        ): Flow<List<TrackMetadata>> = flow.map { list -> list.filter { it.remotePath in remotePaths } }

        override fun getMetadataFlow(
            serverId: Long,
            remotePath: String,
        ): Flow<TrackMetadata?> = flow.map { list -> list.firstOrNull { it.remotePath == remotePath } }

        override suspend fun getCachedMetadata(
            serverId: Long,
            remotePath: String,
        ): TrackMetadata? = flow.value.firstOrNull { it.remotePath == remotePath }

        override suspend fun resolveMetadata(
            server: WebDavServer,
            files: List<RemoteFile>,
            forceRefresh: Boolean,
        ) {
            resolveMetadataCalls++
            resolvedFiles = files
        }

        override suspend fun resolveSingleTrackMetadata(
            server: WebDavServer,
            file: RemoteFile,
        ): TrackMetadata = TrackMetadata(serverId = server.id, remotePath = file.path, title = file.name)
    }

    @Test
    fun highFrequencyProgressTicks_produceZeroEmissionsOnStructuralSessionStateStream() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val dir = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(dir, track)
            advanceUntilIdle()

            var sessionStateEmissions = 0
            val collectJob =
                sessionScope.launch {
                    session.sessionState.collect {
                        sessionStateEmissions++
                    }
                }
            advanceUntilIdle()
            val initialEmissions = sessionStateEmissions

            // Simulate rapid sub-second progress updates from engine
            for (pos in 100L..2000L step 100L) {
                fakeEngine._currentPositionMs.value = pos
                advanceUntilIdle()
            }

            // Structural sessionState must have produced zero emissions
            assertEquals(
                "Structural sessionState must produce zero emissions on high frequency progress updates",
                initialEmissions,
                sessionStateEmissions,
            )
            // Dedicated playbackProgress stream must reflect latest progress
            assertEquals(2000L, session.playbackProgress.value.currentPositionMs)

            collectJob.cancel()
        }

    @Test
    fun playbackProgress_updatesPositionDurationAndBufferedProgress() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track = RemoteFile(name = "song.flac", path = "/song.flac")
            val dir = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(dir, track)
            advanceUntilIdle()

            fakeEngine._durationMs.value = 180000L
            fakeEngine._bufferedPositionMs.value = 60000L
            fakeEngine._currentPositionMs.value = 30000L
            advanceUntilIdle()

            val progress = session.playbackProgress.value
            assertEquals(30000L, progress.currentPositionMs)
            assertEquals(180000L, progress.durationMs)
            assertEquals(60000L, progress.bufferedPositionMs)
            assertEquals(30000f / 180000f, progress.progressFraction, 0.001f)
            assertEquals(60000f / 180000f, progress.bufferedFraction, 0.001f)
        }

    @Test
    fun seekTo_updatesProgressStreamDirectly() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val dir = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            session.playDirectoryTrack(dir, track)
            advanceUntilIdle()

            session.seekTo(42000L)
            advanceUntilIdle()

            assertEquals(42000L, session.playbackProgress.value.currentPositionMs)
            assertEquals(42000L, fakeEngine.seekToPosition)
        }

    @Test
    fun coldStart_restoresBothStructuralStateAndPlaybackProgress() =
        runTest(testDispatcher) {
            val fakeStore = FakePlaybackSessionStore()
            val savedTrack =
                AudioTrack(
                    id = "1:/saved.mp3",
                    serverId = testServer.id,
                    remotePath = "/saved.mp3",
                    title = "Saved Song",
                    durationMs = 240000L,
                    format = AudioFormat.MP3,
                )
            val savedSession =
                PlaybackSessionData(
                    activeServerId = testServer.id,
                    currentDirectoryPath = "/SavedFolder/",
                    queueTracks = listOf(savedTrack),
                    currentTrackIndex = 0,
                    positionMs = 88000L,
                    playbackMode = PlaybackMode.SINGLE_LOOP,
                )
            fakeStore.savedSession = savedSession

            val restoredSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = TestServerRepository(listOf(testServer)),
                    sessionStore = fakeStore,
                    coroutineScope = sessionScope,
                )
            advanceUntilIdle()

            val state = restoredSession.sessionState.value
            assertEquals(testServer, state.activeServer)
            assertEquals(1, state.queue.size)
            assertEquals(savedTrack, state.currentTrack)
            assertTrue(state.isPaused)
            assertEquals(PlaybackMode.SINGLE_LOOP, state.playbackMode)
            assertEquals("/SavedFolder/", state.currentDirectoryPath)

            val progress = restoredSession.playbackProgress.value
            assertEquals(88000L, progress.currentPositionMs)
            assertEquals(240000L, progress.durationMs)

            restoredSession.release()
        }

    @Test
    fun physicalTrackPlayback_loadsNativeStreamDirectlyAndExposesNativeProgress() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            val track =
                AudioTrack(
                    id = "1:/Music/album.flac",
                    serverId = testServer.id,
                    remotePath = "/Music/album.flac",
                    title = "album.flac",
                    artist = "Album Artist",
                    album = "Great Album",
                    durationMs = 600_000L,
                    size = 50_000_000L,
                    format = AudioFormat.FLAC,
                )

            session.playTrack(track)
            advanceUntilIdle()

            // Physical engine loaded track directly starting at 0L
            assertEquals(1, fakeEngine.loadTrackCount)
            assertEquals("/Music/album.flac", fakeEngine.loadedTrack?.remotePath)
            assertEquals(0L, fakeEngine.lastStartPositionMs)

            val state = session.sessionState.value
            assertEquals(1, state.queue.size)
            assertEquals(0, state.queue.currentIndex)
            assertEquals("album.flac", state.currentTrack?.title)
            assertEquals(600_000L, state.currentTrack?.durationMs)

            // Native position tracking directly reflects engine
            fakeEngine._currentPositionMs.value = 45_000L
            advanceUntilIdle()

            val progress = session.playbackProgress.value
            assertEquals(45_000L, progress.currentPositionMs)
            assertEquals(600_000L, progress.durationMs)
        }

    @Test
    fun durationGuard_interceptsPrematureEof_andResumesInPlace() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)
            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 45_000L
            val initialPlayCount = fakeEngine.playCount

            // Simulate premature Ended event due to network stream cutoff at 45s of 100s track
            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Duration Guard must prevent advancing to track 2
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )

            // Must trigger in-place resumption from 45,000ms
            assertEquals(45_000L, fakeEngine.seekToPosition)
            assertTrue(fakeEngine.playCount > initialPlayCount)
        }

    @Test
    fun completionArbitration_prematureCutOffUnder95Percent_haltsQueueProgressionAndEntersBuffering() =
        runTest(testDispatcher) {
            val localEngine = FakeAudioPlayerEngine()
            val bufferingEngine =
                object : com.webdav.player.domain.player.AudioPlayerEngine by localEngine {
                    override fun play() {
                        localEngine.playCount++
                        // Simulates streaming decoder staying in Buffering during in-place network recovery
                    }
                }
            val testSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = bufferingEngine,
                    serverRepository = null,
                    coroutineScope = sessionScope,
                )
            testSession.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            testSession.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, testSession.sessionState.value.queue.currentIndex)
            localEngine._durationMs.value = 100_000L
            localEngine._currentPositionMs.value = 90_000L // 90% (<95% and >5s remaining physical tolerance)
            val initialPlayCount = localEngine.playCount

            localEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Premature cutoff: queue index preserved, track preserved
            assertEquals(0, testSession.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                testSession.sessionState.value.currentTrack
                    ?.title,
            )

            // Seeks to cutoff position, resumes in place, and enters Buffering
            assertEquals(90_000L, localEngine.seekToPosition)
            assertTrue(localEngine.playCount > initialPlayCount)
            assertEquals(PlaybackState.Buffering, testSession.sessionState.value.playbackState)
        }

    @Test
    fun completionArbitration_tailReleaseAt95PercentOrAbove_advancesQueueDespiteInflatedDuration() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)
            // Inflated metadata duration: 200s (e.g. ID3 TLEN or VBR estimation)
            fakeEngine._durationMs.value = 200_000L
            // Stream naturally ends at 191s (95.5% progress, but delta of 9s > 5s tolerance)
            fakeEngine._currentPositionMs.value = 191_000L

            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Tier 2 confirms natural completion and advances queue to track 2
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
        }

    @Test
    fun completionArbitration_physicalMatch_advancesQueueEvenWhenProgressUnder95Percent() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)
            // 50s duration track
            fakeEngine._durationMs.value = 50_000L
            // Ends at 46s (4s remaining <= 5s tolerance; progress is 46/50 = 92% < 95%)
            fakeEngine._currentPositionMs.value = 46_000L

            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Tier 1 physical match confirms natural completion even when progress is 92%
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
        }

    @Test
    fun completionArbitration_singleLoop_replaysTrackFromZeroOn95PercentTailReleaseWithInflatedDuration() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            session.setPlaybackMode(PlaybackMode.SINGLE_LOOP)
            // Inflated metadata duration: 200s
            fakeEngine._durationMs.value = 200_000L
            // Natural EOF at 191s (95.5% progress)
            fakeEngine._currentPositionMs.value = 191_000L
            val initialPlayCount = fakeEngine.playCount

            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Single loop: stays on track 0, seeks to 0L, and replays from beginning
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(0L, fakeEngine.seekToPosition)
            assertTrue(fakeEngine.playCount > initialPlayCount)
        }

    @Test
    fun completionArbitration_shuffleMode_advancesToNextShuffledTrackOnNaturalCompletion() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val track3 = RemoteFile(name = "03.mp3", path = "/03.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2, track3))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            session.setPlaybackMode(PlaybackMode.SHUFFLE)
            val expectedNextTrack = session.sessionState.value.queue.tracks[1]

            fakeEngine._durationMs.value = 200_000L
            fakeEngine._currentPositionMs.value = 192_000L // 96% progress

            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Next in shuffled queue is index 1
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                expectedNextTrack.title,
                session.sessionState.value.currentTrack
                    ?.title,
            )
        }

    @Test
    fun durationGuard_allowsLegitimateCompletion_andAdvancesQueue() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)
            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 98_500L // within 3000ms tolerance

            // Genuine Ended event
            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Successfully advanced to track 2
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
        }

    @Test
    fun durationGuard_folderRing_loopsBackToBeginningAtEndOfQueue() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            // Start at track 2 (last track)
            session.playDirectoryTrack(directory, track2)
            advanceUntilIdle()

            assertEquals(1, session.sessionState.value.queue.currentIndex)
            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 99_500L

            // Genuine Ended event on last track
            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Folder Ring: loops back to index 0, never leaves directory
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
        }

    @Test
    fun durationGuard_singleLoop_replaysTrackWhenLegitimatelyCompleted() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            session.setPlaybackMode(PlaybackMode.SINGLE_LOOP)
            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 99_000L
            val initialPlayCount = fakeEngine.playCount

            fakeEngine._playbackState.value = PlaybackState.Ended
            advanceUntilIdle()

            // Single loop: stays on track 0, seeks to 0L, and replays
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(0L, fakeEngine.seekToPosition)
            assertTrue(fakeEngine.playCount > initialPlayCount)
        }

    @Test
    fun adaptivePreroll_triggered_whenRemainingTimeWithin10Seconds_andNetworkHealthy() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            fakeEngine._durationMs.value = 100_000L
            // Position at 92s (8s remaining <= 10s)
            fakeEngine._currentPositionMs.value = 92_000L
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertTrue("Pre-roll must be active when remaining <= 10s on healthy network", fakeEngine.isPrerollActive)
            assertEquals("02.mp3", fakeEngine.prerolledTrack?.title)
        }

    @Test
    fun adaptivePreroll_suppressed_whenRecentBufferingOccurred() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // Mark recent buffering (5 seconds ago)
            session.lastBufferingTimestampMs.set(System.currentTimeMillis() - 5_000L)

            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 92_000L
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertFalse("Pre-roll must be suppressed when buffering occurred recently", fakeEngine.isPrerollActive)
            assertNull(fakeEngine.prerolledTrack)
        }

    @Test
    fun adaptivePreroll_dynamicallyCancelled_whenBufferingOccursDuringPrerollWindow() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 92_000L
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertTrue("Pre-roll should initially be active", fakeEngine.isPrerollActive)

            // Network hiccup triggers buffering
            fakeEngine._playbackState.value = PlaybackState.Buffering
            advanceUntilIdle()

            assertFalse("Pre-roll must be dynamically cancelled upon buffering", fakeEngine.isPrerollActive)
            assertTrue("Clear preroll must be dispatched to engine", fakeEngine.clearPrerollCount > 0)
        }

    @Test
    fun userPreemption_pauseAndSkip_instantlyCancelsPreroll() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 92_000L
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertTrue(fakeEngine.isPrerollActive)

            // User clicks pause
            session.pause()
            advanceUntilIdle()

            assertFalse("Pre-roll must be cancelled on pause", fakeEngine.isPrerollActive)
            assertTrue("Clear preroll must be called", fakeEngine.clearPrerollCount > 0)
            assertTrue(session.sessionState.value.isPaused)
        }

    @Test
    fun weakNetwork_stateAndRetryFlag_exposedAndClearedOnResume() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // 1. Enter buffering
            fakeEngine._playbackState.value = PlaybackState.Buffering
            advanceUntilIdle()

            // Weak network reached (>= 15s buffering)
            session.setWeakNetworkState(isWeakNetwork = true, canManualRetry = false)
            assertTrue(session.sessionState.value.isWeakNetwork)
            assertFalse(session.sessionState.value.canManualRetry)
            assertTrue(session.sessionState.value.isReconnecting)
            assertEquals("网络受阻，正在重连...", PlayerSessionState.WEAK_NETWORK_MESSAGE)

            // 2. Manual retry available (>= 30s buffering)
            session.setWeakNetworkState(isWeakNetwork = true, canManualRetry = true)
            assertTrue(session.sessionState.value.isWeakNetwork)
            assertTrue(session.sessionState.value.canManualRetry)

            // 3. Network recovers and transitions back to Playing
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertFalse("Weak network must clear upon playing resume", session.sessionState.value.isWeakNetwork)
            assertFalse("Manual retry must clear upon playing resume", session.sessionState.value.canManualRetry)
            assertFalse("Reconnecting flag must clear", session.sessionState.value.isReconnecting)
        }

    @Test
    fun manualRetry_reconnectsInPlace_preservingCurrentPosition() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            session.seekTo(42_000L)
            advanceUntilIdle()

            val initialLoadTrackCount = fakeEngine.loadTrackCount

            // User triggers manual retry
            session.retryPlayback()
            advanceUntilIdle()

            assertTrue(fakeEngine.loadTrackCount > initialLoadTrackCount)
            assertEquals(42_000L, fakeEngine.lastStartPositionMs)
            assertEquals("01.mp3", fakeEngine.loadedTrack?.title)
        }

    @Test
    fun bufferingWatchdog_exhaustsQuietWindow_flagsWeakNetworkAndManualRetry_preservingQueueState() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3", size = 1000)
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3", size = 2000)
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // Seek playback to 35,000 ms
            session.seekTo(35_000L)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(35_000L, session.playbackProgress.value.currentPositionMs)

            // Transition engine to Buffering state
            fakeEngine._playbackState.value = PlaybackState.Buffering
            runCurrent()

            assertFalse("Initial buffering should not immediately flag weak network", session.sessionState.value.isWeakNetwork)
            assertFalse("Initial buffering should not immediately flag manual retry", session.sessionState.value.canManualRetry)

            // Advance virtual time by 15 seconds (first quiet threshold)
            advanceTimeBy(15_001L)
            runCurrent()
            assertTrue("Buffering for 15s must flag weak network", session.sessionState.value.isWeakNetwork)
            assertFalse("Buffering for 15s must not flag manual retry yet", session.sessionState.value.canManualRetry)
            assertTrue("Buffering for 15s must flag reconnecting", session.sessionState.value.isReconnecting)

            // Queue and position must remain completely intact
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(35_000L, session.playbackProgress.value.currentPositionMs)

            // Advance virtual time by another 15 seconds (total 30s quiet window exhaustion)
            advanceTimeBy(15_000L)
            runCurrent()
            assertTrue("Buffering for 30s must flag weak network", session.sessionState.value.isWeakNetwork)
            assertTrue("Quiet window exhaustion must flag canManualRetry = true", session.sessionState.value.canManualRetry)
            assertTrue("Suspended state helper isSuspendedRetry must be true", session.sessionState.value.isSuspendedRetry)
            assertTrue("Reconnecting flag must remain true", session.sessionState.value.isReconnecting)
            assertEquals(
                "Playback must stay paused upon quiet window exhaustion",
                PlaybackState.Paused,
                session.sessionState.value.playbackState,
            )
            assertTrue("Player engine pause must be called", fakeEngine.pauseCount >= 1)

            // Queue state MUST be preserved without skipping tracks or altering position
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(2, session.sessionState.value.queue.tracks.size)
            assertEquals(35_000L, session.playbackProgress.value.currentPositionMs)

            // User triggers manual retry
            val initialLoadTrackCount = fakeEngine.loadTrackCount
            session.retryPlayback()
            advanceUntilIdle()

            // Verify in-place resumption from preserved timestamp
            assertTrue(fakeEngine.loadTrackCount > initialLoadTrackCount)
            assertEquals(35_000L, fakeEngine.lastStartPositionMs)
            assertEquals("01.mp3", fakeEngine.loadedTrack?.title)
            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(2, session.sessionState.value.queue.tracks.size)

            // When playback recovers to Playing, flags clear
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertFalse(session.sessionState.value.isWeakNetwork)
            assertFalse(session.sessionState.value.canManualRetry)
            assertFalse(session.sessionState.value.isSuspendedRetry)
            assertFalse(session.sessionState.value.isReconnecting)
        }

    @Test
    fun pause_duringBuffering_cancelsWatchdog_andTransitionsToPausedWithoutGhostResumption() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3", size = 1000)
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // Transition engine to Buffering state
            fakeEngine._playbackState.value = PlaybackState.Buffering
            runCurrent()

            // User pauses while buffering (e.g. via BecomingNoisy or UI pause)
            session.pause()
            runCurrent()

            // Verify pause dispatch
            assertEquals(1, fakeEngine.pauseCount)
            assertEquals(PlaybackState.Paused, session.sessionState.value.playbackState)

            // Advance time past the 15s/30s watchdog windows
            advanceTimeBy(35_000L)
            runCurrent()

            // Watchdog must NOT fire because it was cancelled on pause
            assertFalse("Watchdog must be cancelled; isWeakNetwork should be false", session.sessionState.value.isWeakNetwork)
            assertFalse("canManualRetry should be false", session.sessionState.value.canManualRetry)
            assertFalse("isReconnecting should be false", session.sessionState.value.isReconnecting)
            assertEquals("Playback state must remain Paused", PlaybackState.Paused, session.sessionState.value.playbackState)
        }

    @Test
    fun adaptivePreroll_cancelledBeforeMainThreadExecution_isSafelyDiscarded() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            // Simulate progress entering pre-roll window
            fakeEngine._durationMs.value = 60_000L
            fakeEngine._currentPositionMs.value = 52_000L
            fakeEngine._playbackState.value = PlaybackState.Playing

            // User immediately pauses before main dispatcher executes the scheduled pre-roll
            session.pause()
            advanceUntilIdle()

            assertFalse("Pre-roll must not be executed if cancelled before main thread execution", fakeEngine.isPrerollActive)
            assertNull("Prerolled track must remain null", fakeEngine.prerolledTrack)
        }

    @Test
    fun seamlessPreroll_trackTransitions_advancesQueueWithoutCallingLoadTrackIntoEngine() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val track2 = RemoteFile(name = "02.mp3", path = "/02.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track1, track2))
            session.playDirectoryTrack(directory, track1)
            advanceUntilIdle()

            assertEquals(0, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "01.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            val initialLoadCount = fakeEngine.loadTrackCount
            assertEquals(1, initialLoadCount)

            // Trigger preroll window
            fakeEngine._durationMs.value = 100_000L
            fakeEngine._currentPositionMs.value = 92_000L
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            assertTrue("Preroll should be active", fakeEngine.isPrerollActive)
            assertTrue("isPrerollAppended should be true", session.isPrerollAppended.get())

            // Simulate seamless ExoPlayer track transition
            fakeEngine.simulateTrackTransition()
            advanceUntilIdle()

            assertFalse("isPrerollAppended must reset to false after transition", session.isPrerollAppended.get())
            assertEquals(1, session.sessionState.value.queue.currentIndex)
            assertEquals(
                "02.mp3",
                session.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(
                "loadTrack must NOT be called again because engine transitioned seamlessly",
                initialLoadCount,
                fakeEngine.loadTrackCount,
            )
            assertEquals(0L, session.playbackProgress.value.currentPositionMs)
        }

    @Test
    fun seamlessPreroll_crossServerTrackTransition_atomicallyUpdatesSessionAndEngineActiveServer() =
        runTest(testDispatcher) {
            val serverA = testServer.copy(id = 1L, name = "Server A", url = "http://server-a.test/webdav")
            val serverB = testServer.copy(id = 2L, name = "Server B", url = "http://server-b.test/webdav")
            val serverRepo = TestServerRepository(listOf(serverA, serverB))

            val playlist = Playlist(id = 100L, name = "Cross Server", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 2)
            val entry1 =
                PlaylistTrackEntry(
                    id = 1L,
                    playlistId = 100L,
                    serverId = 1L,
                    remotePath = "/track-a.mp3",
                    sortOrder = 0,
                    title = "Track A",
                    format = AudioFormat.MP3,
                    durationMs = 100_000L,
                )
            val entry2 =
                PlaylistTrackEntry(
                    id = 2L,
                    playlistId = 100L,
                    serverId = 2L,
                    remotePath = "/track-b.mp3",
                    sortOrder = 1,
                    title = "Track B",
                    format = AudioFormat.MP3,
                    durationMs = 120_000L,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(entry1, entry2))

            val crossServerSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )
            crossServerSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            assertEquals(serverA, crossServerSession.sessionState.value.activeServer)
            assertEquals(serverA, fakeEngine.lastServer)
            assertEquals(0, crossServerSession.sessionState.value.queue.currentIndex)

            fakeEngine.simulateTrackTransition()
            advanceUntilIdle()

            assertEquals(1, crossServerSession.sessionState.value.queue.currentIndex)
            assertEquals(
                "Track B",
                crossServerSession.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals(serverB, crossServerSession.sessionState.value.activeServer)
            assertEquals(serverB, fakeEngine.lastServer)
            assertTrue("setActiveServer must be called on engine", fakeEngine.activeServerCalls > 0)

            crossServerSession.release()
        }

    @Test
    fun audioFocus_transientLoss_pausesSession_andSetsFlag() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    durationMs = 200_000L,
                    format = AudioFormat.MP3,
                    size = 10_000_000L,
                )
            session.playTrack(track)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            // Transient focus loss (e.g. phone call)
            session.onAudioFocusTransientLoss()
            advanceUntilIdle()

            assertTrue("Flag must be set on transient focus loss", session.pausedByTransientAudioFocus)
            assertEquals(1, fakeEngine.pauseCount)
        }

    @Test
    fun audioFocus_ghostResumptionGuard_userPauseClearsFlag() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    durationMs = 200_000L,
                    format = AudioFormat.MP3,
                    size = 10_000_000L,
                )
            session.playTrack(track)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            // Call arrives -> transient focus loss
            session.onAudioFocusTransientLoss()
            advanceUntilIdle()
            assertTrue(session.pausedByTransientAudioFocus)

            // User explicitly pauses during the phone call
            session.pause()
            advanceUntilIdle()
            assertFalse("User active pause must clear transient resumption flag", session.pausedByTransientAudioFocus)

            val playCountBefore = fakeEngine.playCount
            // Call ends -> system grants focus back
            session.onAudioFocusGain()
            advanceUntilIdle()

            assertEquals("Ghost resumption must be blocked", playCountBefore, fakeEngine.playCount)
            assertFalse(session.pausedByTransientAudioFocus)
        }

    @Test
    fun audioFocus_ghostResumptionGuard_userToggleClearsFlag() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    durationMs = 200_000L,
                    format = AudioFormat.MP3,
                    size = 10_000_000L,
                )
            session.playTrack(track)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            session.onAudioFocusTransientLoss()
            advanceUntilIdle()
            assertTrue(session.pausedByTransientAudioFocus)

            // User triggers togglePlayPause during the phone call
            session.togglePlayPause()
            advanceUntilIdle()
            assertFalse("User active toggle must clear transient resumption flag", session.pausedByTransientAudioFocus)

            val playCountBefore = fakeEngine.playCount
            session.onAudioFocusGain()
            advanceUntilIdle()

            assertEquals("Ghost resumption must be blocked", playCountBefore, fakeEngine.playCount)
        }

    @Test
    fun audioFocus_ghostResumptionGuard_userStopClearsFlag() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    durationMs = 200_000L,
                    format = AudioFormat.MP3,
                    size = 10_000_000L,
                )
            session.playTrack(track)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            session.onAudioFocusTransientLoss()
            advanceUntilIdle()
            assertTrue(session.pausedByTransientAudioFocus)

            session.stop()
            advanceUntilIdle()
            assertFalse("User active stop must clear transient resumption flag", session.pausedByTransientAudioFocus)

            val playCountBefore = fakeEngine.playCount
            session.onAudioFocusGain()
            advanceUntilIdle()

            assertEquals("Ghost resumption must be blocked", playCountBefore, fakeEngine.playCount)
        }

    @Test
    fun audioFocus_ghostResumptionGuard_playDirectoryTrackClearsFlag() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track1 =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    format = AudioFormat.MP3,
                    durationMs = 120_000L,
                    size = 1024L,
                )
            val dir =
                RemoteDirectory(
                    path = "/music/",
                    name = "music",
                    files =
                        listOf(
                            RemoteFile(name = "1.mp3", path = "/music/1.mp3"),
                            RemoteFile(name = "2.mp3", path = "/music/2.mp3"),
                        ),
                )
            session.playTrack(track1)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            // Call interrupts -> transient loss sets flag
            session.onAudioFocusTransientLoss()
            advanceUntilIdle()
            assertTrue(session.pausedByTransientAudioFocus)

            // User starts playing a new directory track during call
            session.playDirectoryTrack(dir, RemoteFile(name = "2.mp3", path = "/music/2.mp3"))
            advanceUntilIdle()

            // User active action MUST clear the transient resumption flag
            assertFalse(
                "User active playDirectoryTrack must clear transient resumption flag",
                session.pausedByTransientAudioFocus,
            )
        }

    @Test
    fun audioFocus_gain_resumesPlayback_whenFlagIsSet() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    durationMs = 200_000L,
                    format = AudioFormat.MP3,
                    size = 10_000_000L,
                )
            session.playTrack(track)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            val initialPlayCount = fakeEngine.playCount

            // Transient loss -> pause
            session.onAudioFocusTransientLoss()
            advanceUntilIdle()
            assertTrue(session.pausedByTransientAudioFocus)

            // Call ends without user interaction -> focus gain
            session.onAudioFocusGain()
            advanceUntilIdle()

            assertFalse(session.pausedByTransientAudioFocus)
            assertEquals("Playback should resume smoothly", initialPlayCount + 1, fakeEngine.playCount)
        }

    @Test
    fun audioFocus_permanentLoss_pausesSession_andClearsFlag() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            val track =
                AudioTrack(
                    id = "track-1",
                    serverId = 1L,
                    remotePath = "/music/1.mp3",
                    title = "Song 1",
                    durationMs = 200_000L,
                    format = AudioFormat.MP3,
                    size = 10_000_000L,
                )
            session.playTrack(track)
            advanceUntilIdle()
            fakeEngine._playbackState.value = PlaybackState.Playing
            advanceUntilIdle()

            // Permanent focus loss
            session.onAudioFocusLoss()
            advanceUntilIdle()

            assertFalse(session.pausedByTransientAudioFocus)
            assertEquals(1, fakeEngine.pauseCount)

            // Regaining focus later should NOT resume
            val playCountBefore = fakeEngine.playCount
            session.onAudioFocusGain()
            advanceUntilIdle()
            assertEquals(playCountBefore, fakeEngine.playCount)
        }

    @Test
    fun playPlaylist_loadsPlaylistIntoQueueWithPlaylistOriginAndStartsPlayback() =
        runTest(testDispatcher) {
            val playlist = Playlist(id = 100L, name = "精选歌单", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 3)
            val entry1 =
                PlaylistTrackEntry(
                    id = 1L,
                    playlistId = 100L,
                    serverId = 1L,
                    remotePath = "/song1.mp3",
                    sortOrder = 0,
                    title = "Song 1",
                    format = AudioFormat.MP3,
                    durationMs = 120_000L,
                )
            val entry2 =
                PlaylistTrackEntry(
                    id = 2L,
                    playlistId = 100L,
                    serverId = 1L,
                    remotePath = "/song2.mp3",
                    sortOrder = 1,
                    title = "Song 2",
                    format = AudioFormat.MP3,
                    durationMs = 180_000L,
                )
            val entry3 =
                PlaylistTrackEntry(
                    id = 3L,
                    playlistId = 100L,
                    serverId = 1L,
                    remotePath = "/song3.mp3",
                    sortOrder = 2,
                    title = "Song 3",
                    format = AudioFormat.MP3,
                    durationMs = 200_000L,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(entry1, entry2, entry3))
            val serverRepo = TestServerRepository(listOf(testServer))

            val playlistSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )

            playlistSession.playPlaylist(playlist, startIndex = 1)
            advanceUntilIdle()

            val state = playlistSession.sessionState.value
            assertEquals(3, state.queue.size)
            assertEquals(QueueOrigin.PLAYLIST, state.queue.origin)
            assertTrue(state.queue.isPlaylistQueue)
            assertEquals(1, state.queue.currentIndex)
            assertEquals("Song 2", state.currentTrack?.title)
            assertEquals("Song 2", fakeEngine.loadedTrack?.title)
            assertEquals(testServer, fakeEngine.lastServer)

            playlistSession.release()
        }

    @Test
    fun crossServerPlayback_dynamicallySwitchesServerContextOnTransition() =
        runTest(testDispatcher) {
            val server2 =
                WebDavServer(
                    id = 2L,
                    name = "Work Cloud",
                    url = "https://cloud.work.com:443/dav",
                    port = 443,
                    pathPrefix = "/dav",
                    username = "work_user",
                    password = "work_password",
                )
            val playlist = Playlist(id = 200L, name = "跨服混排", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 2)
            val entry1 =
                PlaylistTrackEntry(
                    id = 10L,
                    playlistId = 200L,
                    serverId = testServer.id,
                    remotePath = "/home.mp3",
                    sortOrder = 0,
                    title = "Home Track",
                    format = AudioFormat.MP3,
                )
            val entry2 =
                PlaylistTrackEntry(
                    id = 20L,
                    playlistId = 200L,
                    serverId = server2.id,
                    remotePath = "/work.flac",
                    sortOrder = 1,
                    title = "Work Track",
                    format = AudioFormat.FLAC,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(entry1, entry2))
            val serverRepo = TestServerRepository(listOf(testServer, server2))

            val playlistSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )

            playlistSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            assertEquals(testServer, playlistSession.sessionState.value.activeServer)
            assertEquals(testServer, fakeEngine.lastServer)
            assertEquals("Home Track", fakeEngine.loadedTrack?.title)

            // Transition to track 2 belonging to server 2
            playlistSession.skipToNext()
            advanceUntilIdle()

            assertEquals(server2, playlistSession.sessionState.value.activeServer)
            assertEquals(server2, fakeEngine.lastServer)
            assertEquals("Work Track", fakeEngine.loadedTrack?.title)

            playlistSession.release()
        }

    @Test
    fun serverUnavailable_gracefullyAutoSkipsToNextValidTrack() =
        runTest(testDispatcher) {
            val playlist = Playlist(id = 300L, name = "容错歌单", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 2)
            val missingServerEntry =
                PlaylistTrackEntry(
                    id = 31L,
                    playlistId = 300L,
                    serverId = 9999L,
                    remotePath = "/offline.mp3",
                    sortOrder = 0,
                    title = "Offline Server Song",
                    format = AudioFormat.MP3,
                )
            val validServerEntry =
                PlaylistTrackEntry(
                    id = 32L,
                    playlistId = 300L,
                    serverId = testServer.id,
                    remotePath = "/online.mp3",
                    sortOrder = 1,
                    title = "Online Server Song",
                    format = AudioFormat.MP3,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(missingServerEntry, validServerEntry))
            val serverRepo = TestServerRepository(listOf(testServer)) // Only testServer exists, 9999 is missing!

            val playlistSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )

            playlistSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            // Missing server 9999 is automatically skipped, player loaded the valid server track!
            assertEquals(1, playlistSession.sessionState.value.queue.currentIndex)
            assertEquals(
                "Online Server Song",
                playlistSession.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals("Online Server Song", fakeEngine.loadedTrack?.title)
            assertEquals(testServer, fakeEngine.lastServer)

            playlistSession.release()
        }

    @Test
    fun stream404Error_autoSkipsToNextValidTrack_andMarksTrackInactive() =
        runTest(testDispatcher) {
            session.release()
            val playlist = Playlist(id = 400L, name = "404歌单", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 2)
            val notFoundEntry =
                PlaylistTrackEntry(
                    id = 41L,
                    playlistId = 400L,
                    serverId = testServer.id,
                    remotePath = "/deleted.mp3",
                    sortOrder = 0,
                    title = "Deleted Song",
                    format = AudioFormat.MP3,
                )
            val normalEntry =
                PlaylistTrackEntry(
                    id = 42L,
                    playlistId = 400L,
                    serverId = testServer.id,
                    remotePath = "/normal.mp3",
                    sortOrder = 1,
                    title = "Normal Song",
                    format = AudioFormat.MP3,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(notFoundEntry, normalEntry))
            val serverRepo = TestServerRepository(listOf(testServer))

            val playlistSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )

            playlistSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            assertEquals("Deleted Song", fakeEngine.loadedTrack?.title)

            // Simulate 404 error
            val http404Exception = java.io.FileNotFoundException("HTTP 404: Remote file not found")
            fakeEngine._playbackState.value = PlaybackState.Error("HTTP 404 error", http404Exception)
            advanceUntilIdle()

            // Verify track 1 was marked inactive as FILE_NOT_FOUND in repo
            assertTrue(playlistRepo.markedInactive.contains(testServer.id to "/deleted.mp3"))

            // Verify smoothly skipped to track 2 ("Normal Song") without hanging
            assertEquals(1, playlistSession.sessionState.value.queue.currentIndex)
            assertEquals(
                "Normal Song",
                playlistSession.sessionState.value.currentTrack
                    ?.title,
            )
            assertEquals("Normal Song", fakeEngine.loadedTrack?.title)

            playlistSession.release()
        }

    @Test
    fun stream404Error_allTracksFail_stopsWithoutInfiniteLoop() =
        runTest(testDispatcher) {
            session.release()
            val playlist = Playlist(id = 500L, name = "全失效歌单", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 2)
            val notFoundEntry1 =
                PlaylistTrackEntry(
                    id = 51L,
                    playlistId = 500L,
                    serverId = testServer.id,
                    remotePath = "/del1.mp3",
                    sortOrder = 0,
                    title = "Del 1",
                    format = AudioFormat.MP3,
                )
            val notFoundEntry2 =
                PlaylistTrackEntry(
                    id = 52L,
                    playlistId = 500L,
                    serverId = testServer.id,
                    remotePath = "/del2.mp3",
                    sortOrder = 1,
                    title = "Del 2",
                    format = AudioFormat.MP3,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(notFoundEntry1, notFoundEntry2))
            val serverRepo = TestServerRepository(listOf(testServer))

            val playlistSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )

            playlistSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            // Track 1 fails with 404
            fakeEngine._playbackState.value = PlaybackState.Error("404", java.io.FileNotFoundException("File not found 404"))
            advanceUntilIdle()

            // Auto-skipped to Track 2
            assertEquals(1, playlistSession.sessionState.value.queue.currentIndex)

            // Track 2 also fails with 404
            fakeEngine._playbackState.value = PlaybackState.Error("404", java.io.FileNotFoundException("File not found 404"))
            advanceUntilIdle()

            // Both tracks failed: session stops gracefully at Error without infinite loop
            assertTrue(playlistSession.sessionState.value.playbackState is PlaybackState.Error)
            assertEquals("404", playlistSession.sessionState.value.errorMessage)

            playlistSession.release()
        }

    @Test
    fun playTrack_singleTrack_setsAdHocQueueOrigin() =
        runTest(testDispatcher) {
            session.setActiveServer(testServer)
            advanceUntilIdle()

            val track =
                AudioTrack(
                    id = "1:/search/single.mp3",
                    serverId = testServer.id,
                    remotePath = "/search/single.mp3",
                    title = "Search Single",
                    format = AudioFormat.MP3,
                )

            session.playTrack(track)
            advanceUntilIdle()

            val state = session.sessionState.value
            assertEquals(1, state.queue.size)
            assertEquals(QueueOrigin.AD_HOC, state.queue.origin)
            assertTrue(state.queue.isAdHocQueue)
        }

    @Test
    fun insertNext_onEmptyQueue_setsAdHocQueueOrigin() {
        val emptyQueue = PlaybackQueue.EMPTY
        val track =
            AudioTrack(
                id = "1:/ad_hoc.mp3",
                serverId = 1L,
                remotePath = "/ad_hoc.mp3",
                title = "Ad Hoc",
                format = AudioFormat.MP3,
            )
        val updated = emptyQueue.insertNext(track)
        assertEquals(QueueOrigin.AD_HOC, updated.origin)
        assertTrue(updated.isAdHocQueue)
    }

    private class TestPlaylistRepository(
        private val tracks: MutableList<PlaylistTrackEntry> = mutableListOf(),
    ) : PlaylistRepository {
        val markedInactive = mutableListOf<Pair<Long, String>>()

        override fun getAllPlaylistsFlow(): Flow<List<Playlist>> = kotlinx.coroutines.flow.flowOf(emptyList())

        override suspend fun getAllPlaylists(): List<Playlist> = emptyList()

        override fun getPlaylistFlow(playlistId: Long): Flow<Playlist?> = kotlinx.coroutines.flow.flowOf(null)

        override suspend fun getPlaylist(playlistId: Long): Playlist? = null

        override suspend fun createPlaylist(name: String): Long = 1L

        override suspend fun renamePlaylist(
            playlistId: Long,
            newName: String,
        ) {}

        override suspend fun deletePlaylist(playlistId: Long) {}

        override suspend fun isPlaylistNameExists(
            name: String,
            excludePlaylistId: Long?,
        ): Boolean = false

        override fun getTracksForPlaylistFlow(playlistId: Long): Flow<List<PlaylistTrackEntry>> = kotlinx.coroutines.flow.flowOf(tracks)

        override suspend fun getTracksForPlaylist(playlistId: Long): List<PlaylistTrackEntry> = tracks

        override suspend fun addTrackToPlaylist(
            playlistId: Long,
            track: AudioTrack,
        ): Long = 1L

        override suspend fun addTracksToPlaylist(
            playlistId: Long,
            tracks: List<AudioTrack>,
        ): List<Long> = emptyList()

        override suspend fun removeTrackFromPlaylist(trackEntryId: Long) {}

        override suspend fun removeTracksFromPlaylist(
            playlistId: Long,
            trackEntryIds: List<Long>,
        ) {}

        override suspend fun updateTrackOrders(
            playlistId: Long,
            orderedTrackIds: List<Long>,
        ) {}

        override fun markTrackInactive(
            serverId: Long,
            remotePath: String,
            reason: InactiveReason,
        ) {
            markedInactive.add(serverId to remotePath)
        }
    }

    private class TestServerRepository(
        private val servers: List<WebDavServer> = emptyList(),
    ) : com.webdav.player.domain.repository.ServerRepository {
        override fun getAllServers(): Flow<List<WebDavServer>> = kotlinx.coroutines.flow.flowOf(servers)

        override fun getActiveServer(): Flow<WebDavServer?> = kotlinx.coroutines.flow.flowOf(servers.firstOrNull())

        override suspend fun getServerById(id: Long): WebDavServer? = servers.firstOrNull { it.id == id }

        override suspend fun saveServer(server: WebDavServer): Long = server.id

        override suspend fun deleteServer(id: Long) {}

        override suspend fun setActiveServer(id: Long) {}
    }

    @Test
    fun networkRestored_whenSuspendedInWeakNetwork_autoResumesPlaybackAndDismissesBanner() =
        runTest(testDispatcher) {
            val fakeNetwork = FakeNetworkConnectivityObserver(NetworkStatus(isConnected = true, isWifi = true))
            val netSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    coroutineScope = sessionScope,
                    networkConnectivityObserver = fakeNetwork,
                )
            netSession.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            netSession.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            // Simulate playback progressing to 20 seconds
            fakeEngine._currentPositionMs.value = 20_000L
            advanceUntilIdle()

            // Simulate stream entering weak-network suspended pause
            fakeEngine.pause()
            netSession.setWeakNetworkState(isWeakNetwork = true, canManualRetry = true)
            advanceUntilIdle()

            assertTrue(netSession.sessionState.value.isWeakNetwork)
            assertTrue(netSession.sessionState.value.canManualRetry)
            assertEquals(PlaybackState.Paused, netSession.sessionState.value.playbackState)

            val initialLoads = fakeEngine.loadTrackCount

            // Network drops
            fakeNetwork.emitStatus(NetworkStatus.DISCONNECTED)
            advanceUntilIdle()

            // Network reconnects
            fakeNetwork.emitStatus(NetworkStatus(isConnected = true, isWifi = true))
            advanceUntilIdle()

            // Playback should automatically resume
            val state = netSession.sessionState.value
            assertFalse("isWeakNetwork must be cleared", state.isWeakNetwork)
            assertFalse("canManualRetry must be cleared", state.canManualRetry)
            assertTrue("loadTrack must be called to resume playback", fakeEngine.loadTrackCount > initialLoads)
            assertEquals("Engine must resume playing", PlaybackState.Playing, fakeEngine.playbackState.value)

            netSession.release()
        }

    @Test
    fun networkRestored_whenUserInitiatedPause_strictlyRemainsPaused() =
        runTest(testDispatcher) {
            val fakeNetwork = FakeNetworkConnectivityObserver(NetworkStatus(isConnected = true, isWifi = true))
            val netSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    coroutineScope = sessionScope,
                    networkConnectivityObserver = fakeNetwork,
                )
            netSession.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            netSession.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            // User explicitly pauses playback
            netSession.pause()
            advanceUntilIdle()

            assertFalse(netSession.sessionState.value.isWeakNetwork)
            assertEquals(PlaybackState.Paused, netSession.sessionState.value.playbackState)

            val initialLoads = fakeEngine.loadTrackCount

            // Network drops and reconnects
            fakeNetwork.emitStatus(NetworkStatus.DISCONNECTED)
            advanceUntilIdle()
            fakeNetwork.emitStatus(NetworkStatus(isConnected = true, isWifi = true))
            advanceUntilIdle()

            // Ghost resumption suppression: music must strictly remain paused
            val state = netSession.sessionState.value
            assertEquals("Must remain paused", PlaybackState.Paused, state.playbackState)
            assertEquals("Load track count must not increase", initialLoads, fakeEngine.loadTrackCount)

            netSession.release()
        }

    @Test
    fun networkRestored_whenBecomingNoisyPaused_strictlyRemainsPaused() =
        runTest(testDispatcher) {
            val fakeNetwork = FakeNetworkConnectivityObserver(NetworkStatus(isConnected = true, isWifi = true))
            val netSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = null,
                    coroutineScope = sessionScope,
                    networkConnectivityObserver = fakeNetwork,
                )
            netSession.setActiveServer(testServer)
            val track = RemoteFile(name = "01.mp3", path = "/01.mp3")
            val directory = RemoteDirectory(path = "/", name = "root", files = listOf(track))
            netSession.playDirectoryTrack(directory, track)
            advanceUntilIdle()

            // Becoming noisy pauses playback
            netSession.pause()
            advanceUntilIdle()

            val initialLoads = fakeEngine.loadTrackCount

            // Network transitions from disconnected to connected
            fakeNetwork.emitStatus(NetworkStatus.DISCONNECTED)
            advanceUntilIdle()
            fakeNetwork.emitStatus(NetworkStatus(isConnected = true, isCellular = true, isMetered = true))
            advanceUntilIdle()

            assertEquals("Playback must strictly remain paused", PlaybackState.Paused, netSession.sessionState.value.playbackState)
            assertEquals("No auto-resumption on noisy pause", initialLoads, fakeEngine.loadTrackCount)

            netSession.release()
        }

    @Test
    fun localTrack_playback_bypassesWatchdog_andReleasesWifiLockWhileRetainingWakeLock() =
        runTest(testDispatcher) {
            val localEngine = FakeAudioPlayerEngine()
            val fakeLockManager = FakeStreamingHardwareLockManager()
            val localSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = localEngine,
                    serverRepository = null,
                    coroutineScope = sessionScope,
                    hardwareLockManager = fakeLockManager,
                )

            val localTrack =
                AudioTrack(
                    id = "0:content://media/external/audio/1",
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/1",
                    title = "Local Audio",
                    format = AudioFormat.FLAC,
                    durationMs = 180_000L,
                )

            localSession.playTrack(localTrack)
            advanceUntilIdle()

            // Verify local server synthesized and wakeLock held while wifiLock not held
            assertTrue("WakeLock must be retained for local playback", fakeLockManager.isWakeLockHeld)
            assertFalse("WifiLock must not be held for local playback", fakeLockManager.isWifiLockHeld)
            assertEquals(
                LOCAL_STORAGE_ID,
                localSession.sessionState.value.activeServer
                    ?.id,
            )

            // Simulate buffering state on local track
            localEngine._playbackState.value = PlaybackState.Buffering
            runCurrent()

            // Advance time past the 15s/30s watchdog windows
            advanceTimeBy(35_000L)
            runCurrent()

            // Watchdog must NOT fire for local tracks
            assertFalse("Local track buffering must not trigger weak network", localSession.sessionState.value.isWeakNetwork)
            assertFalse("Local track buffering must not enable manual retry", localSession.sessionState.value.canManualRetry)
            assertNotEquals(
                "Local track must not be auto-paused by watchdog",
                PlaybackState.Paused,
                localSession.sessionState.value.playbackState,
            )

            localSession.release()
        }

    @Test
    fun skipping_fromLocalTrack_toWebDavTrack_reEnablesWifiLockAndWatchdog() =
        runTest(testDispatcher) {
            val fakeLockManager = FakeStreamingHardwareLockManager()
            val serverRepo = TestServerRepository(listOf(testServer))

            val playlist = Playlist(id = 100L, name = "Mixed", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 2)
            val entry1 =
                PlaylistTrackEntry(
                    id = 1L,
                    playlistId = 100L,
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/1",
                    sortOrder = 0,
                    title = "Local Audio",
                    format = AudioFormat.FLAC,
                    durationMs = 180_000L,
                )
            val entry2 =
                PlaylistTrackEntry(
                    id = 2L,
                    playlistId = 100L,
                    serverId = testServer.id,
                    remotePath = "/remote.mp3",
                    sortOrder = 1,
                    title = "Remote Audio",
                    format = AudioFormat.MP3,
                    durationMs = 240_000L,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(entry1, entry2))

            val mixedSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                    hardwareLockManager = fakeLockManager,
                )

            mixedSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            // Track 0 (Local): WakeLock held, WifiLock released
            assertTrue("WakeLock must be held on local track", fakeLockManager.isWakeLockHeld)
            assertFalse("WifiLock must be released on local track", fakeLockManager.isWifiLockHeld)

            // Skip to Track 1 (WebDAV)
            mixedSession.skipToNext()
            advanceUntilIdle()

            // Track 1 (WebDAV): WifiLock re-enabled alongside WakeLock
            assertTrue("WakeLock must be held for WebDAV track", fakeLockManager.isWakeLockHeld)
            assertTrue("WifiLock must be re-enabled for WebDAV track", fakeLockManager.isWifiLockHeld)

            // Buffering on WebDav track must trigger watchdog
            fakeEngine._playbackState.value = PlaybackState.Buffering
            runCurrent()
            advanceTimeBy(16_000L)
            runCurrent()
            assertTrue("Weak network must trigger after 15s on WebDAV buffering", mixedSession.sessionState.value.isWeakNetwork)

            advanceTimeBy(16_000L)
            runCurrent()
            assertEquals(
                "Watchdog must auto-pause WebDAV track after 30s buffering",
                PlaybackState.Paused,
                mixedSession.sessionState.value.playbackState,
            )

            mixedSession.release()
        }

    @Test
    fun mixedPlaylist_transitionsSeamlesslyAcrossWebDavAndLocalStorage_skipToNextAndPreviousAndShuffle() =
        runTest(testDispatcher) {
            val serverRepo = TestServerRepository(listOf(testServer))
            val playlist = Playlist(id = 200L, name = "Mixed Queue", createdAtMs = 1000L, updatedAtMs = 1000L, trackCount = 3)
            val entry1 =
                PlaylistTrackEntry(
                    id = 101L,
                    playlistId = 200L,
                    serverId = testServer.id,
                    remotePath = "/cloud1.mp3",
                    sortOrder = 0,
                    title = "Cloud 1",
                    format = AudioFormat.MP3,
                    durationMs = 200_000L,
                )
            val entry2 =
                PlaylistTrackEntry(
                    id = 102L,
                    playlistId = 200L,
                    serverId = LOCAL_STORAGE_ID,
                    remotePath = "content://media/external/audio/local2",
                    sortOrder = 1,
                    title = "Local 2",
                    format = AudioFormat.FLAC,
                    durationMs = 180_000L,
                )
            val entry3 =
                PlaylistTrackEntry(
                    id = 103L,
                    playlistId = 200L,
                    serverId = testServer.id,
                    remotePath = "/cloud3.mp3",
                    sortOrder = 2,
                    title = "Cloud 3",
                    format = AudioFormat.MP3,
                    durationMs = 220_000L,
                )
            val playlistRepo = TestPlaylistRepository(mutableListOf(entry1, entry2, entry3))

            val mixedSession =
                MusicPlayerAppSessionImpl(
                    playerEngine = fakeEngine,
                    serverRepository = serverRepo,
                    playlistRepository = playlistRepo,
                    coroutineScope = sessionScope,
                )

            // 1. Play first track (WebDAV)
            mixedSession.playPlaylist(playlist, startIndex = 0)
            advanceUntilIdle()

            assertEquals(
                testServer.id,
                mixedSession.sessionState.value.activeServer
                    ?.id,
            )
            assertEquals(testServer.id, fakeEngine.loadedTrack?.serverId)
            assertEquals("/cloud1.mp3", fakeEngine.loadedTrack?.remotePath)
            assertEquals(0, mixedSession.sessionState.value.queue.currentIndex)

            // 2. Skip to next track (Local Storage)
            mixedSession.skipToNext()
            advanceUntilIdle()

            assertEquals(
                LOCAL_STORAGE_ID,
                mixedSession.sessionState.value.activeServer
                    ?.id,
            )
            assertEquals(LOCAL_STORAGE_ID, fakeEngine.loadedTrack?.serverId)
            assertEquals("content://media/external/audio/local2", fakeEngine.loadedTrack?.remotePath)
            assertEquals(1, mixedSession.sessionState.value.queue.currentIndex)

            // 3. Skip to next track (WebDAV)
            mixedSession.skipToNext()
            advanceUntilIdle()

            assertEquals(
                testServer.id,
                mixedSession.sessionState.value.activeServer
                    ?.id,
            )
            assertEquals(testServer.id, fakeEngine.loadedTrack?.serverId)
            assertEquals("/cloud3.mp3", fakeEngine.loadedTrack?.remotePath)
            assertEquals(2, mixedSession.sessionState.value.queue.currentIndex)

            // 4. Skip to previous track (Local Storage)
            fakeEngine._currentPositionMs.value = 500L // within 3s threshold
            mixedSession.skipToPrevious()
            advanceUntilIdle()

            assertEquals(
                LOCAL_STORAGE_ID,
                mixedSession.sessionState.value.activeServer
                    ?.id,
            )
            assertEquals(LOCAL_STORAGE_ID, fakeEngine.loadedTrack?.serverId)
            assertEquals("content://media/external/audio/local2", fakeEngine.loadedTrack?.remotePath)
            assertEquals(1, mixedSession.sessionState.value.queue.currentIndex)

            // 5. Shuffle progression
            mixedSession.setPlaybackMode(PlaybackMode.SHUFFLE)
            advanceUntilIdle()

            mixedSession.skipToNext()
            advanceUntilIdle()

            val currentTrack = mixedSession.sessionState.value.currentTrack
            assertNotNull(currentTrack)
            val currentServer = mixedSession.sessionState.value.activeServer
            assertNotNull(currentServer)
            if (currentTrack!!.serverId == LOCAL_STORAGE_ID) {
                assertEquals(LOCAL_STORAGE_ID, currentServer!!.id)
            } else {
                assertEquals(testServer.id, currentServer!!.id)
            }

            mixedSession.release()
        }
}
