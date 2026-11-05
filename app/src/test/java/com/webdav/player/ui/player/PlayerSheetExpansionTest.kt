package com.webdav.player.ui.player

import androidx.compose.ui.unit.dp
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackQueue
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.ui.navigation.MainNavigationCoordinator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class PlayerSheetExpansionTest {
    private lateinit var coordinator: MainNavigationCoordinator

    private val sampleTrack =
        AudioTrack(
            id = "1:/music/track1.flac",
            serverId = 1L,
            remotePath = "/music/track1.flac",
            title = "Shine On You Crazy Diamond",
            artist = "Pink Floyd",
            album = "Wish You Were Here",
            format = AudioFormat.FLAC,
        )

    private fun sessionWithTrack(track: AudioTrack?): PlayerSessionState =
        if (track == null) {
            PlayerSessionState()
        } else {
            PlayerSessionState(queue = PlaybackQueue(listOf(track), 0))
        }

    @Before
    fun setUp() {
        coordinator = MainNavigationCoordinator()
    }

    @Test
    fun initialState_sheetIsCollapsed() {
        assertFalse(coordinator.uiState.value.isFullPlayerExpanded)
        assertFalse(coordinator.uiState.value.isMiniPlayerVisible)
    }

    @Test
    fun miniPlayerClickOrSwipeUp_expandsSheet_whenTrackIsPresent() {
        coordinator.onSessionStateChanged(sessionWithTrack(sampleTrack))
        assertTrue(coordinator.uiState.value.isMiniPlayerVisible)
        assertFalse(coordinator.uiState.value.isFullPlayerExpanded)

        // User taps or swipes up on Docked Mini-Player
        coordinator.expandFullPlayer()
        assertTrue(coordinator.uiState.value.isFullPlayerExpanded)
    }

    @Test
    fun collapseArrowOrDownwardSwipe_dismissesSheet() {
        coordinator.onSessionStateChanged(sessionWithTrack(sampleTrack))
        coordinator.expandFullPlayer()
        assertTrue(coordinator.uiState.value.isFullPlayerExpanded)

        // User taps collapse arrow or swipes downward
        coordinator.collapseFullPlayer()
        assertFalse(coordinator.uiState.value.isFullPlayerExpanded)
    }

    @Test
    fun clearTrack_automaticallyCollapsesExpandedSheet() {
        coordinator.onSessionStateChanged(sessionWithTrack(sampleTrack))
        coordinator.expandFullPlayer()
        assertTrue(coordinator.uiState.value.isFullPlayerExpanded)

        // Track ends or is cleared
        coordinator.onSessionStateChanged(sessionWithTrack(null))
        assertFalse(coordinator.uiState.value.isFullPlayerExpanded)
        assertFalse(coordinator.uiState.value.isMiniPlayerVisible)
    }

    @Test
    fun expandRequest_ignoredWhenNoTrackIsPlaying() {
        coordinator.expandFullPlayer()
        assertFalse(coordinator.uiState.value.isFullPlayerExpanded)
    }

    @Test
    fun formatTrackArtistAndFormat_withValidTrack_formatsProperly() {
        val formatted = formatTrackArtistAndFormat(sampleTrack)
        assertEquals("Pink Floyd · FLAC", formatted)
    }

    @Test
    fun formatTrackArtistAndFormat_withNullArtist_usesFallback() {
        val trackWithoutArtist = sampleTrack.copy(artist = null, format = AudioFormat.MP3)
        val formatted = formatTrackArtistAndFormat(trackWithoutArtist)
        assertEquals("未知艺术家 · MP3", formatted)
    }

    @Test
    fun formatTrackArtistAndFormat_withNullTrack_returnsDefault() {
        val formatted = formatTrackArtistAndFormat(null)
        assertEquals("未知艺术家", formatted)
    }

    // --- Morphing Interpolation Math Tests ---

    @Test
    fun morphingMath_containerHeight_interpolatesSmoothly() {
        val screenHeight = 840.dp
        assertEquals(60.dp, MorphingPlayerMath.containerHeight(screenHeight, 0.0f))
        assertEquals(840.dp, MorphingPlayerMath.containerHeight(screenHeight, 1.0f))
        assertEquals(450.dp, MorphingPlayerMath.containerHeight(screenHeight, 0.5f))

        // Clamping check
        assertEquals(60.dp, MorphingPlayerMath.containerHeight(screenHeight, -0.2f))
        assertEquals(840.dp, MorphingPlayerMath.containerHeight(screenHeight, 1.5f))
    }

    @Test
    fun morphingMath_containerMargins_interpolatesFromCapsuleToEdgeToEdge() {
        val collapsedBottomPadding = 68.dp
        // Horizontal margins
        assertEquals(14.dp, MorphingPlayerMath.containerHorizontalMargin(0.0f))
        assertEquals(0.dp, MorphingPlayerMath.containerHorizontalMargin(1.0f))
        assertEquals(7.dp, MorphingPlayerMath.containerHorizontalMargin(0.5f))

        // Bottom padding
        assertEquals(68.dp, MorphingPlayerMath.containerBottomMargin(collapsedBottomPadding, 0.0f))
        assertEquals(0.dp, MorphingPlayerMath.containerBottomMargin(collapsedBottomPadding, 1.0f))
        assertEquals(34.dp, MorphingPlayerMath.containerBottomMargin(collapsedBottomPadding, 0.5f))
    }

    @Test
    fun morphingMath_containerCornerRadius_interpolatesFromCapsuleToTopWindowRadius() {
        assertEquals(30.dp, MorphingPlayerMath.containerCornerRadius(0.0f))
        assertEquals(36.dp, MorphingPlayerMath.containerCornerRadius(1.0f))
        assertEquals(33.dp, MorphingPlayerMath.containerCornerRadius(0.5f))
    }

    @Test
    fun morphingMath_clampingGuarantees_acrossAllGeometryHelpers() {
        val screenHeight = 800.dp
        val bottomPadding = 64.dp

        // Negative progress clamps to collapsed baseline (0.0f)
        assertEquals(60.dp, MorphingPlayerMath.containerHeight(screenHeight, -1.0f))
        assertEquals(14.dp, MorphingPlayerMath.containerHorizontalMargin(-1.0f))
        assertEquals(bottomPadding, MorphingPlayerMath.containerBottomMargin(bottomPadding, -1.0f))
        assertEquals(30.dp, MorphingPlayerMath.containerCornerRadius(-1.0f))

        // Overshoot progress clamps to expanded ceiling (1.0f)
        assertEquals(screenHeight, MorphingPlayerMath.containerHeight(screenHeight, 2.0f))
        assertEquals(0.dp, MorphingPlayerMath.containerHorizontalMargin(2.0f))
        assertEquals(0.dp, MorphingPlayerMath.containerBottomMargin(bottomPadding, 2.0f))
        assertEquals(36.dp, MorphingPlayerMath.containerCornerRadius(2.0f))
    }

    @Test
    fun morphingDefaults_internalizedFadeThresholds_arePreserved() {
        assertEquals(0.35f, MorphingPlayerDefaults.SecondaryControlsFadeStartProgress, 0.0001f)
        assertEquals(0.25f, MorphingPlayerDefaults.MiniControlsFadeEndProgress, 0.0001f)
    }

    @Test
    fun downwardSwipeGestureContract_thresholdSimulation() {
        // Contract: downward vertical drag exceeding 24f triggers collapse
        var collapsedCalled = false
        var downwardDrag = 0f
        val onCollapse = { collapsedCalled = true }

        val handleDrag = { dragAmount: Float ->
            downwardDrag += dragAmount
            if (downwardDrag > 24f) {
                onCollapse()
                downwardDrag = 0f
            }
        }

        // Sub-threshold drag does not trigger collapse
        handleDrag(10f)
        assertFalse(collapsedCalled)
        handleDrag(10f)
        assertFalse(collapsedCalled)

        // Crossing 24f triggers collapse
        handleDrag(5f)
        assertTrue(collapsedCalled)
        assertEquals(0f, downwardDrag)
    }
}
