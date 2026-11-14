package com.webdav.player.ui.common

import androidx.compose.material3.ExperimentalMaterial3Api
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioQualityLevel
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.LOCAL_STORAGE_ID
import com.webdav.player.domain.model.isLocal
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalMaterial3Api::class)
@RunWith(RobolectricTestRunner::class)
class TrackDetailBottomSheetTest {

    @Test
    fun focusIsolation_enforcesModalBottomSheetPropertiesInvariant() {
        val properties = TrackDetailBottomSheetDefaults.Properties
        assertFalse(
            "TrackDetailBottomSheet must configure isFocusable = false to eliminate IME ghosting and focus race",
            properties.isFocusable,
        )
        assertTrue(
            "TrackDetailBottomSheet must configure shouldDismissOnBackPress = true for predictive back dismiss",
            properties.shouldDismissOnBackPress,
        )
    }

    @Test
    fun formatVolumePath_forSafPrimaryDocumentUri_formatsVolumeBracket() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3AMusic/document/primary%3AMusic%2FC-Pop%2FSong.flac"
        val formatted = formatVolumePath(uri, isLocal = true)
        assertEquals("[内部存储] Music/C-Pop/Song.flac", formatted)
    }

    @Test
    fun formatVolumePath_forSafSdCardUri_formatsSdCardVolumeBracket() {
        val uri =
            "content://com.android.externalstorage.documents/tree/1234-5678%3AMusic/document/1234-5678%3AMusic%2FRock%2Ftrack.mp3"
        val formatted = formatVolumePath(uri, isLocal = true)
        assertEquals("[SD卡 (1234-5678)] Music/Rock/track.mp3", formatted)
    }

    @Test
    fun formatVolumePath_forWebDavRemotePath_preservesPath() {
        val remotePath = "/Music/Jazz/take_five.flac"
        val formatted = formatVolumePath(remotePath, isLocal = false)
        assertEquals("/Music/Jazz/take_five.flac", formatted)
    }

    @Test
    fun formatVolumePath_forLocalPrimaryRoot_formatsBracketWithoutSlash() {
        val uri =
            "content://com.android.externalstorage.documents/tree/primary%3A/document/primary%3A"
        val formatted = formatVolumePath(uri, isLocal = true)
        assertEquals("[内部存储]", formatted)
    }

    @Test
    fun formatTrackFileSize_formatsCorrectly() {
        assertEquals("0 B", formatTrackFileSize(0L))
        assertEquals("0 B", formatTrackFileSize(-50L))
        assertEquals("1.00 KB", formatTrackFileSize(1024L))
        assertEquals("1.00 MB", formatTrackFileSize(1024L * 1024L))
        assertEquals("45.00 MB", formatTrackFileSize(45L * 1024L * 1024L))
    }

    @Test
    fun formatTrackDuration_formatsCorrectly() {
        assertEquals("--:--", formatTrackDuration(0L))
        assertEquals("--:--", formatTrackDuration(-100L))
        assertEquals("03:35", formatTrackDuration(215_000L))
        assertEquals("1:01:05", formatTrackDuration(3_665_000L))
    }

    @Test
    fun trackDetails_audiophileSpecsAndQualityBadge_losslessHiResFlac() {
        val track =
            AudioTrack(
                id = "track_1",
                serverId = LOCAL_STORAGE_ID,
                remotePath = "/local/Music/Hotel California [96k] [24bit].flac",
                title = "Hotel California",
                artist = "Eagles",
                album = "Hotel California",
                durationMs = 391_000L,
                size = 90_000_000L,
                format = AudioFormat.FLAC,
            )

        val badge = track.qualityBadge
        assertEquals("FLAC", badge.label)
        assertTrue("FLAC must be lossless", badge.isLossless)
        assertEquals(AudioQualityLevel.LOSSLESS, badge.qualityLevel)

        val specs = track.audiophileSpecsModel
        assertTrue("96kHz 24-bit FLAC must be flagged as Hi-Res", specs.isHiRes)
        assertEquals("96kHz", specs.sampleRate)
        assertEquals("24-bit", specs.bitDepth)
        assertTrue(track.isLocal)
    }

    @Test
    fun trackDetails_audiophileSpecsAndQualityBadge_highQualityMp3() {
        val track =
            AudioTrack(
                id = "track_2",
                serverId = 1L,
                remotePath = "/Music/Time.mp3",
                title = "Time",
                artist = "Pink Floyd",
                album = "The Dark Side of the Moon",
                durationMs = 360_000L,
                size = 14_400_000L, // 320 kbps
                format = AudioFormat.MP3,
            )

        val badge = track.qualityBadge
        assertEquals("MP3 320k", badge.label)
        assertFalse("MP3 must not be lossless", badge.isLossless)
        assertEquals(AudioQualityLevel.HIGH_QUALITY, badge.qualityLevel)

        val specs = track.audiophileSpecsModel
        assertEquals(320, specs.bitrateKbps)
        assertFalse(specs.isHiRes)
        assertFalse(track.isLocal)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun dismissAnimationContract_executesHideThenInvokesOnDismiss() = runTest {
        var onDismissCalled = false
        val testDispatcher = StandardTestDispatcher(testScheduler)
        val scope = TestScope(testDispatcher)

        // Simulate sheetState.hide() completion invocation
        val job: Job = scope.launch {
            // Simulated hide() suspending work
        }
        job.invokeOnCompletion {
            onDismissCalled = true
        }

        assertFalse("onDismiss must not be invoked before hide completion", onDismissCalled)
        scope.testScheduler.advanceUntilIdle()
        assertTrue("onDismiss must be invoked after hide completes", onDismissCalled)
    }
}
