package com.webdav.player.ui.player

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.ui.motion.isReduceMotionEnabled
import com.webdav.player.ui.player.components.rememberArtworkBackgroundBrush

/**
 * Default dimension tokens and transition thresholds for [MorphingPlayerContainer].
 */
object MorphingPlayerDefaults {
    val WindowCornerRadius: Dp = 36.dp
    val FullPlayerArtworkSize: Dp = 280.dp
    val FullPlayerArtworkRadius: Dp = 28.dp
    val FullPlayerPlayButtonSize: Dp = 68.dp
    val FullPlayerPlayButtonRadius: Dp = 26.dp
    val FullPlayerQueueButtonSize: Dp = 48.dp
    val CapsuleHorizontalMargin: Dp = 14.dp

    internal const val FullPlayerFadeStartProgress: Float = 0.15f
    internal const val MiniPlayerFadeEndProgress: Float = 0.40f
}

/**
 * Pure mathematical interpolation functions for container morphing.
 *
 * Implements smooth continuous geometry transitions between the 60dp floating capsule
 * and the edge-to-edge full screen player view.
 */
object MorphingPlayerMath {
    /**
     * Interpolates container height from [MiniPlayerDefaults.CapsuleHeight] (60dp) to [screenHeight].
     */
    fun containerHeight(
        screenHeight: Dp,
        progress: Float,
    ): Dp {
        val p = progress.coerceIn(0f, 1f)
        return lerp(MiniPlayerDefaults.CapsuleHeight, screenHeight, p)
    }

    /**
     * Interpolates container horizontal margin from 14dp to 0dp edge-to-edge.
     */
    fun containerHorizontalMargin(progress: Float): Dp {
        val p = progress.coerceIn(0f, 1f)
        return lerp(MorphingPlayerDefaults.CapsuleHorizontalMargin, 0.dp, p)
    }

    /**
     * Interpolates container bottom margin from [collapsedBottomPadding] to 0dp.
     */
    fun containerBottomMargin(
        collapsedBottomPadding: Dp,
        progress: Float,
    ): Dp {
        val p = progress.coerceIn(0f, 1f)
        return lerp(collapsedBottomPadding, 0.dp, p)
    }

    /**
     * Interpolates container corner radius from [MiniPlayerDefaults.CapsuleCornerRadius] (30dp)
     * to [MorphingPlayerDefaults.WindowCornerRadius] (36dp).
     */
    fun containerCornerRadius(progress: Float): Dp {
        val p = progress.coerceIn(0f, 1f)
        return lerp(MiniPlayerDefaults.CapsuleCornerRadius, MorphingPlayerDefaults.WindowCornerRadius, p)
    }
}

/**
 * Unified continuous morphing player container.
 *
 * Unifies [MiniPlayer] and [FullPlayerView] into a single living entity driven by [isExpanded]
 * and animated smoothly via Material 3 Expressive easing curves.
 *
 * Fully respects zero UI mutation: when [isExpanded] is false (progress = 0f), 100% of
 * existing [MiniPlayer] layout, paddings, and actions are preserved. When [isExpanded] is true
 * (progress = 1f), 100% of existing [FullPlayerView] is preserved.
 *
 * Single living view architecture: [FullPlayerView] is the authentic full-screen view throughout
 * expansion without any fake clones or end-state swaps, eliminating layout shift and component jitter.
 */
@Composable
fun MorphingPlayerContainer(
    sessionState: PlayerSessionState,
    playbackProgress: PlaybackProgress,
    playbackActions: PlaybackActions,
    isExpanded: Boolean,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    collapsedBottomPadding: Dp,
    modifier: Modifier = Modifier,
    onOpenQueue: () -> Unit = {},
    onAddToPlaylist: (AudioTrack) -> Unit = {},
    onDismissBatteryBanner: () -> Unit = {},
    onConfirmUnsupportedRangeReplay: () -> Unit = {},
    onDismissUnsupportedRange: () -> Unit = {},
) {
    val currentTrack = sessionState.currentTrack ?: return
    val isReduceMotion = isReduceMotionEnabled()

    val morphingSpec =
        remember(isReduceMotion) {
            if (isReduceMotion) {
                snap()
            } else {
                tween<Float>(
                    durationMillis = 480,
                    easing = CubicBezierEasing(0.18f, 0.94f, 0.28f, 1.05f),
                )
            }
        }

    val expansionProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = morphingSpec,
        label = "PlayerMorphingProgress",
    )

    // Downward swipe gesture to collapse player sheet smoothly
    val downwardSwipeModifier =
        Modifier.pointerInput(isExpanded) {
            if (!isExpanded) return@pointerInput
            var downwardDrag = 0f
            detectVerticalDragGestures(
                onVerticalDrag = { change, dragAmount ->
                    downwardDrag += dragAmount
                    if (downwardDrag > 24f) {
                        change.consume()
                        onCollapse()
                        downwardDrag = 0f
                    }
                },
                onDragEnd = { downwardDrag = 0f },
                onDragCancel = { downwardDrag = 0f },
            )
        }

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val density = LocalDensity.current
        val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val backgroundBrush = rememberArtworkBackgroundBrush(currentTrack.coverThumbnailPath)

        val morphingShape =
            remember(collapsedBottomPadding, expansionProgress) {
                object : Shape {
                    override fun createOutline(
                        size: Size,
                        layoutDirection: LayoutDirection,
                        density: Density,
                    ): Outline {
                        val p = expansionProgress.coerceIn(0f, 1f)
                        with(density) {
                            val hMargin = MorphingPlayerMath.containerHorizontalMargin(p).toPx()
                            val bMargin = MorphingPlayerMath.containerBottomMargin(collapsedBottomPadding, p).toPx()
                            val cHeight = MorphingPlayerMath.containerHeight(size.height.toDp(), p).toPx()
                            val radius = MorphingPlayerMath.containerCornerRadius(p).toPx()

                            val left = hMargin
                            val right = size.width - hMargin
                            val bottom = size.height - bMargin
                            val top = (bottom - cHeight).coerceAtLeast(0f)

                            return if (p >= 0.99f) {
                                Outline.Rounded(
                                    RoundRect(
                                        left = left,
                                        top = top,
                                        right = right,
                                        bottom = bottom,
                                        topLeftCornerRadius = CornerRadius(radius, radius),
                                        topRightCornerRadius = CornerRadius(radius, radius),
                                        bottomLeftCornerRadius = CornerRadius.Zero,
                                        bottomRightCornerRadius = CornerRadius.Zero,
                                    ),
                                )
                            } else {
                                Outline.Rounded(
                                    RoundRect(
                                        left = left,
                                        top = top,
                                        right = right,
                                        bottom = bottom,
                                        cornerRadius = CornerRadius(radius, radius),
                                    ),
                                )
                            }
                        }
                    }
                }
            }

        Box(
            modifier =
                Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        shape = morphingShape
                        clip = true
                        shadowElevation = with(density) { lerp(10.dp, 0.dp, expansionProgress).toPx() }
                    }.drawBehind {
                        // 1. Container base surface
                        drawRect(surfaceColor)

                        // 2. Artwork ambient gradient background
                        if (expansionProgress > 0.05f) {
                            drawRect(backgroundBrush)
                        }

                        // 3. Mini player border stroke (fades out as expansion reaches 0.95f)
                        if (expansionProgress < 0.95f) {
                            val p = expansionProgress.coerceIn(0f, 1f)
                            val hMargin = MorphingPlayerMath.containerHorizontalMargin(p).toPx()
                            val bMargin = MorphingPlayerMath.containerBottomMargin(collapsedBottomPadding, p).toPx()
                            val cHeight = MorphingPlayerMath.containerHeight(size.height.toDp(), p).toPx()
                            val radius = MorphingPlayerMath.containerCornerRadius(p).toPx()

                            val strokeWidth = MiniPlayerDefaults.SurfaceBorderWidth.toPx()
                            val halfStroke = strokeWidth / 2f
                            val left = hMargin + halfStroke
                            val right = size.width - hMargin - halfStroke
                            val bottom = size.height - bMargin - halfStroke
                            val top = (bottom - cHeight + strokeWidth).coerceAtLeast(halfStroke)
                            val r = (radius - halfStroke).coerceAtLeast(0f)

                            val borderAlpha = MiniPlayerDefaults.SurfaceBorderAlpha * (1f - p)
                            val borderColor = outlineVariantColor.copy(alpha = borderAlpha)

                            drawRoundRect(
                                color = borderColor,
                                topLeft = Offset(left, top),
                                size = Size(right - left, bottom - top),
                                cornerRadius = CornerRadius(r, r),
                                style = Stroke(width = strokeWidth),
                            )
                        }
                    }.then(downwardSwipeModifier),
        ) {
            // 1. Single Living FullPlayerView (Active as container expands past FullPlayerFadeStartProgress)
            if (expansionProgress > MorphingPlayerDefaults.FullPlayerFadeStartProgress) {
                val fullAlpha =
                    (
                        (expansionProgress - MorphingPlayerDefaults.FullPlayerFadeStartProgress) /
                            (1f - MorphingPlayerDefaults.FullPlayerFadeStartProgress)
                    ).coerceIn(0f, 1f)
                val fullTranslationY = with(density) { 40.dp.toPx() * (1f - expansionProgress) }
                Box(
                    modifier =
                        Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                alpha = fullAlpha
                                translationY = fullTranslationY
                            },
                ) {
                    FullPlayerView(
                        sessionState = sessionState,
                        playbackProgress = playbackProgress,
                        playbackActions = playbackActions,
                        onCollapse = onCollapse,
                        onDismissBatteryBanner = onDismissBatteryBanner,
                        onConfirmUnsupportedRangeReplay = onConfirmUnsupportedRangeReplay,
                        onDismissUnsupportedRange = onDismissUnsupportedRange,
                        onAddToPlaylist = onAddToPlaylist,
                    )
                }
            }

            // 2. MiniPlayer (Active when collapsed, smoothly fades out as container expands)
            if (expansionProgress < MorphingPlayerDefaults.MiniPlayerFadeEndProgress) {
                val miniAlpha =
                    if (expansionProgress == 0f) {
                        1f
                    } else {
                        (
                            (MorphingPlayerDefaults.MiniPlayerFadeEndProgress - expansionProgress) /
                                MorphingPlayerDefaults.MiniPlayerFadeEndProgress
                        ).coerceIn(0f, 1f)
                    }
                val miniTranslationY = with(density) { (collapsedBottomPadding * expansionProgress).toPx() }
                Box(
                    modifier =
                        Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = collapsedBottomPadding)
                            .padding(horizontal = MorphingPlayerDefaults.CapsuleHorizontalMargin)
                            .fillMaxWidth()
                            .height(MiniPlayerDefaults.CapsuleHeight)
                            .graphicsLayer {
                                alpha = miniAlpha
                                translationY = miniTranslationY
                            },
                ) {
                    MiniPlayer(
                        sessionState = sessionState,
                        playbackActions = playbackActions,
                        playbackProgress = playbackProgress,
                        onMiniPlayerClick = onExpand,
                        onOpenQueue = onOpenQueue,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
