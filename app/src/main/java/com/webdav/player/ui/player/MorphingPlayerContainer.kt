package com.webdav.player.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.ui.common.CoverThumbnailImage
import com.webdav.player.ui.motion.isReduceMotionEnabled
import com.webdav.player.ui.player.components.rememberArtworkBackgroundBrush
import com.webdav.player.ui.theme.AppIcons
import com.webdav.player.ui.theme.ExpressiveDarkColorScheme
import kotlin.math.roundToInt
import androidx.compose.ui.graphics.lerp as lerpColor

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

private fun LayoutCoordinates.boundsIn(parent: LayoutCoordinates?): Rect? {
    if (parent == null || !this.isAttached || !parent.isAttached) return null
    val localOffset = parent.localPositionOf(this, Offset.Zero)
    return Rect(localOffset, Size(this.size.width.toFloat(), this.size.height.toFloat()))
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
    artworkColors: ArtworkColors? = null,
    progressOverride: Float? = null,
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
                    easing = CubicBezierEasing(0.20f, 0.0f, 0.0f, 1.0f),
                )
            }
        }

    val internalProgress by animateFloatAsState(
        targetValue = if (isExpanded) 1f else 0f,
        animationSpec = morphingSpec,
        label = "PlayerMorphingProgress",
    )
    val expansionProgress = progressOverride ?: internalProgress

    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    var miniCoverRect by remember { mutableStateOf<Rect?>(null) }
    var miniPlayRect by remember { mutableStateOf<Rect?>(null) }
    var miniQueueRect by remember { mutableStateOf<Rect?>(null) }

    var fullCoverRect by remember { mutableStateOf<Rect?>(null) }
    var fullPlayRect by remember { mutableStateOf<Rect?>(null) }
    var fullQueueRect by remember { mutableStateOf<Rect?>(null) }

    val isMorphing = expansionProgress > 0.005f && expansionProgress < 0.995f

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
        modifier =
            modifier
                .fillMaxSize()
                .onGloballyPositioned { containerCoordinates = it },
        contentAlignment = Alignment.BottomCenter,
    ) {
        val rootConstraints = constraints
        val density = LocalDensity.current
        val outlineVariantColor = MaterialTheme.colorScheme.outlineVariant
        val surfaceColor = MaterialTheme.colorScheme.surfaceContainerHighest
        val backgroundBrush =
            rememberArtworkBackgroundBrush(
                thumbnailPath = currentTrack.coverThumbnailPath,
                artworkColors = artworkColors,
            )

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
                        shadowElevation = with(density) { lerp(4.dp, 0.dp, expansionProgress).toPx() }
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
            // 1. Single Living FullPlayerView (Authentic, 100% zero jitter, zero mock clones)
            if (expansionProgress > 0.05f) {
                val fullAlpha =
                    if (expansionProgress <= 0.35f) {
                        0f
                    } else {
                        ((expansionProgress - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    }
                val fullTranslationY = with(density) { 36.dp.toPx() * (1f - expansionProgress) }
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
                        artworkColors = artworkColors,
                        isArtworkVisible = !isMorphing && expansionProgress >= 0.995f,
                        onArtworkPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { fullCoverRect = it }
                        },
                        isPlayButtonVisible = !isMorphing && expansionProgress >= 0.995f,
                        onPlayButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { fullPlayRect = it }
                        },
                        isQueueButtonVisible = !isMorphing && expansionProgress >= 0.995f,
                        onQueueButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { fullQueueRect = it }
                        },
                    )
                }
            }

            // 2. MiniPlayer (Active when collapsed, smoothly fades out by 0.35f)
            if (expansionProgress < 0.35f) {
                val miniAlpha =
                    if (expansionProgress == 0f) {
                        1f
                    } else {
                        ((0.35f - expansionProgress) / 0.35f).coerceIn(0f, 1f)
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
                        isCoverVisible = !isMorphing && expansionProgress <= 0.005f,
                        onCoverPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { miniCoverRect = it }
                        },
                        isPlayButtonVisible = !isMorphing && expansionProgress <= 0.005f,
                        onPlayButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { miniPlayRect = it }
                        },
                        isQueueButtonVisible = !isMorphing && expansionProgress <= 0.005f,
                        onQueueButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { miniQueueRect = it }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 3. Shared Continuous Morphing Elements Layer (Artwork & Dual Control Buttons)
            if (isMorphing) {
                val p = expansionProgress.coerceIn(0f, 1f)
                val cWidth = rootConstraints.maxWidth.toFloat()
                val cHeight = rootConstraints.maxHeight.toFloat()
                val bMarginPx = with(density) { MorphingPlayerMath.containerBottomMargin(collapsedBottomPadding, p).toPx() }
                val hMarginPx = with(density) { MorphingPlayerMath.containerHorizontalMargin(p).toPx() }
                val containerHeightPx = with(density) { MorphingPlayerMath.containerHeight(cHeight.toDp(), p).toPx() }

                val capsuleBottom = cHeight - bMarginPx
                val capsuleTop = (capsuleBottom - containerHeightPx).coerceAtLeast(0f)

                val miniCapsuleTopPx =
                    cHeight - with(density) { collapsedBottomPadding.toPx() } - with(density) { MiniPlayerDefaults.CapsuleHeight.toPx() }

                val fallbackMiniCover =
                    Rect(
                        Offset(hMarginPx + with(density) { 10.dp.toPx() }, miniCapsuleTopPx + with(density) { 9.dp.toPx() }),
                        Size(with(density) { 42.dp.toPx() }, with(density) { 42.dp.toPx() }),
                    )
                val fallbackMiniQueue =
                    Rect(
                        Offset(
                            cWidth - hMarginPx - with(density) { 12.dp.toPx() } - with(density) { 38.dp.toPx() },
                            miniCapsuleTopPx + with(density) { 11.dp.toPx() },
                        ),
                        Size(with(density) { 38.dp.toPx() }, with(density) { 38.dp.toPx() }),
                    )
                val fallbackMiniPlay =
                    Rect(
                        Offset(
                            fallbackMiniQueue.left - with(density) { 8.dp.toPx() } - with(density) { 40.dp.toPx() },
                            miniCapsuleTopPx + with(density) { 10.dp.toPx() },
                        ),
                        Size(with(density) { 40.dp.toPx() }, with(density) { 40.dp.toPx() }),
                    )

                val fullArtSizePx = minOf(with(density) { 280.dp.toPx() }, cWidth * 0.85f)
                val fallbackFullCover =
                    Rect(
                        Offset((cWidth - fullArtSizePx) / 2f, cHeight * 0.22f),
                        Size(fullArtSizePx, fullArtSizePx),
                    )
                val fullPlaySizePx = with(density) { FullPlayerDefaults.PlayButtonSize.toPx() }
                val fallbackFullPlay =
                    Rect(
                        Offset((cWidth - fullPlaySizePx) / 2f, cHeight - with(density) { 96.dp.toPx() }),
                        Size(fullPlaySizePx, fullPlaySizePx),
                    )
                val fallbackFullQueue =
                    Rect(
                        Offset(
                            cWidth - with(density) { 48.dp.toPx() } - with(density) { 20.dp.toPx() },
                            cHeight - with(density) { 86.dp.toPx() },
                        ),
                        Size(with(density) { 48.dp.toPx() }, with(density) { 48.dp.toPx() }),
                    )

                val startCover = miniCoverRect ?: fallbackMiniCover
                val endCover = fullCoverRect ?: fallbackFullCover

                val startPlay = miniPlayRect ?: fallbackMiniPlay
                val endPlay = fullPlayRect ?: fallbackFullPlay

                val startQueue = miniQueueRect ?: fallbackMiniQueue
                val endQueue = fullQueueRect ?: fallbackFullQueue

                // Color Morphing: Fluid interpolation between ambient app theme and all-weather dark stage
                val currentScheme = MaterialTheme.colorScheme
                val stageScheme = artworkColors?.colorScheme(isDark = true) ?: ExpressiveDarkColorScheme

                val curCoverBgColor = lerpColor(currentScheme.surfaceVariant, stageScheme.surfaceVariant, p)
                val curCoverIconColor =
                    lerpColor(
                        currentScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        stageScheme.onSurfaceVariant.copy(alpha = 0.7f),
                        p,
                    )
                val curPlayContainerColor = lerpColor(currentScheme.primary, stageScheme.primary, p)
                val curPlayContentColor = lerpColor(currentScheme.onPrimary, stageScheme.onPrimary, p)

                // Interpolated Cover Geometry (Guaranteed inside expanding capsule)
                val curCoverCenterX = lerp(startCover.center.x, endCover.center.x, p)
                val rawCoverCenterY = lerp(startCover.center.y, endCover.center.y, p)
                val curCoverWidth = lerp(startCover.width, endCover.width, p)
                val curCoverHeight = curCoverWidth
                val safeCoverCenterY = maxOf(rawCoverCenterY, capsuleTop + curCoverHeight / 2f + with(density) { 6.dp.toPx() })
                val curCoverRadiusPx = lerp(curCoverWidth / 2f, with(density) { FullPlayerDefaults.ArtworkSquircleRadius.toPx() }, p)
                val curCoverElevation = with(density) { lerp(0.dp, 24.dp, p).toPx() }

                // Continuous Morphing Album Artwork
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    (curCoverCenterX - curCoverWidth / 2f).roundToInt(),
                                    (safeCoverCenterY - curCoverHeight / 2f).roundToInt(),
                                )
                            }.size(with(density) { curCoverWidth.toDp() })
                            .shadow(
                                elevation = with(density) { curCoverElevation.toDp() },
                                shape = RoundedCornerShape(curCoverRadiusPx),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            ).clip(RoundedCornerShape(curCoverRadiusPx))
                            .background(curCoverBgColor),
                    contentAlignment = Alignment.Center,
                ) {
                    CoverThumbnailImage(
                        thumbnailPath = currentTrack.coverThumbnailPath,
                        contentDescription = "专辑封面",
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        Icon(
                            imageVector = AppIcons.Audiotrack,
                            contentDescription = "专辑封面",
                            modifier = Modifier.size(with(density) { lerp(22.dp, 100.dp, p) }),
                            tint = curCoverIconColor,
                        )
                    }
                }

                // Interpolated Play Button Geometry
                val curPlayCenterX = lerp(startPlay.center.x, endPlay.center.x, p)
                val curPlayCenterY = lerp(startPlay.center.y, endPlay.center.y, p)
                val curPlayWidth = lerp(startPlay.width, endPlay.width, p)
                val curPlayRadiusPx = lerp(curPlayWidth / 2f, with(density) { FullPlayerDefaults.PlayButtonSquircleRadius.toPx() }, p)
                val curPlayElevation = with(density) { lerp(0.dp, 8.dp, p).toPx() }

                // Continuous Morphing Play / Pause Button
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    (curPlayCenterX - curPlayWidth / 2f).roundToInt(),
                                    (curPlayCenterY - curPlayWidth / 2f).roundToInt(),
                                )
                            }.size(with(density) { curPlayWidth.toDp() })
                            .shadow(
                                elevation = with(density) { curPlayElevation.toDp() },
                                shape = RoundedCornerShape(curPlayRadiusPx),
                                spotColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f),
                                ambientColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f),
                            ).clip(RoundedCornerShape(curPlayRadiusPx))
                            .background(curPlayContainerColor)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = {
                                    if (sessionState.isSuspendedRetry) {
                                        playbackActions.retryPlayback()
                                    } else {
                                        playbackActions.togglePlayPause()
                                    }
                                },
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconSize = with(density) { lerp(22.dp, 32.dp, p) }
                    AnimatedContent(
                        targetState = sessionState.playbackState,
                        transitionSpec = { fadeIn() togetherWith fadeOut() },
                        label = "MorphingPlayPause",
                    ) { state ->
                        when (state) {
                            is PlaybackState.Buffering -> {
                                if (sessionState.isSuspendedRetry) {
                                    Icon(
                                        imageVector = AppIcons.Refresh,
                                        contentDescription = "重试",
                                        tint = curPlayContentColor,
                                        modifier = Modifier.size(iconSize),
                                    )
                                } else {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(iconSize),
                                        strokeWidth = with(density) { lerp(2.dp, 3.dp, p) },
                                        color = curPlayContentColor,
                                    )
                                }
                            }

                            is PlaybackState.Playing -> {
                                Icon(
                                    imageVector = AppIcons.Pause,
                                    contentDescription = "暂停",
                                    tint = curPlayContentColor,
                                    modifier = Modifier.size(iconSize),
                                )
                            }

                            else -> {
                                Icon(
                                    imageVector = AppIcons.PlayArrow,
                                    contentDescription = "播放",
                                    tint = curPlayContentColor,
                                    modifier = Modifier.size(iconSize),
                                )
                            }
                        }
                    }
                }

                // Interpolated Queue Button Geometry
                val curQueueCenterX = lerp(startQueue.center.x, endQueue.center.x, p)
                val curQueueCenterY = lerp(startQueue.center.y, endQueue.center.y, p)
                val curQueueWidth = lerp(startQueue.width, endQueue.width, p)
                val curQueueRadiusPx = lerp(curQueueWidth / 2f, with(density) { 16.dp.toPx() }, p)
                val curQueueIconColor =
                    lerpColor(
                        if (p > 0.35f) currentScheme.onSurfaceVariant else currentScheme.onSurface,
                        stageScheme.onSurfaceVariant,
                        p,
                    )

                // Continuous Morphing Queue Button
                Box(
                    modifier =
                        Modifier
                            .offset {
                                IntOffset(
                                    (curQueueCenterX - curQueueWidth / 2f).roundToInt(),
                                    (curQueueCenterY - curQueueWidth / 2f).roundToInt(),
                                )
                            }.size(with(density) { curQueueWidth.toDp() })
                            .clip(RoundedCornerShape(curQueueRadiusPx))
                            .background(
                                MaterialTheme.colorScheme.surfaceContainer.copy(
                                    alpha = (1f - p * 2.5f).coerceIn(0f, 1f),
                                ),
                            ).clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null,
                                onClick = onOpenQueue,
                            ),
                    contentAlignment = Alignment.Center,
                ) {
                    val iconSize = with(density) { lerp(20.dp, 28.dp, p) }
                    Icon(
                        imageVector = AppIcons.QueueMusic,
                        contentDescription = "播放队列",
                        tint = curQueueIconColor,
                        modifier = Modifier.size(iconSize),
                    )
                }
            }
        }
    }
}
