package com.webdav.player.ui.player

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlaybackProgress
import com.webdav.player.domain.model.PlaybackState
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.ui.common.CoverThumbnailImage
import com.webdav.player.ui.player.components.rememberArtworkBackgroundBrush
import com.webdav.player.ui.theme.AppIcons
import com.webdav.player.ui.theme.ExpressiveDarkColorScheme
import androidx.compose.ui.graphics.lerp as lerpColor

/**
 * Default dimension tokens and transition thresholds for [MorphingPlayerContainer].
 */
object MorphingPlayerDefaults {
    val WindowCornerRadius: Dp = 36.dp
    val FullPlayerArtworkSize: Dp = 280.dp
    val FullPlayerArtworkRadius: Dp = 28.dp
    val FullPlayerArtworkIconSize: Dp = 100.dp
    val FullPlayerPlayButtonSize: Dp = 68.dp
    val FullPlayerPlayButtonRadius: Dp = 26.dp
    val FullPlayerPlayIconSize: Dp = 32.dp
    val FullPlayerQueueButtonSize: Dp = 48.dp
    val FullPlayerQueueIconSize: Dp = 28.dp
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
    progress: Float,
    onExpand: () -> Unit,
    onCollapse: () -> Unit,
    collapsedBottomPadding: Dp,
    modifier: Modifier = Modifier,
    isExpanded: Boolean = progress > 0.5f,
    onOpenQueue: () -> Unit = {},
    onAddToPlaylist: (AudioTrack) -> Unit = {},
    onDismissBatteryBanner: () -> Unit = {},
    onConfirmUnsupportedRangeReplay: () -> Unit = {},
    onDismissUnsupportedRange: () -> Unit = {},
    artworkColors: ArtworkColors? = null,
) {
    val currentTrack = sessionState.currentTrack ?: return
    val expansionProgress = progress.coerceIn(0f, 1f)

    var containerCoordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }

    var stableMiniCoverRect by remember { mutableStateOf<Rect?>(null) }
    var stableMiniPlayRect by remember { mutableStateOf<Rect?>(null) }
    var stableMiniQueueRect by remember { mutableStateOf<Rect?>(null) }

    var stableFullCoverRect by remember { mutableStateOf<Rect?>(null) }
    var stableFullPlayRect by remember { mutableStateOf<Rect?>(null) }
    var stableFullQueueRect by remember { mutableStateOf<Rect?>(null) }

    // Downward swipe gesture to collapse player sheet smoothly
    val downwardSwipeModifier =
        Modifier.pointerInput(isExpanded) {
            if (!isExpanded) return@pointerInput
            var totalDragX = 0f
            var totalDragY = 0f
            var isVerticalCollapse = false
            detectDragGestures(
                onDragStart = {
                    totalDragX = 0f
                    totalDragY = 0f
                    isVerticalCollapse = false
                },
                onDrag = { change, dragAmount ->
                    totalDragX += dragAmount.x
                    totalDragY += dragAmount.y

                    // Determine vertical collapse intent: must be predominantly downward
                    if (!isVerticalCollapse && totalDragY > 0f && totalDragY > kotlin.math.abs(totalDragX) * 1.5f && totalDragY > 32f) {
                        isVerticalCollapse = true
                    }

                    if (isVerticalCollapse && totalDragY > 96f) {
                        change.consume()
                        onCollapse()
                        totalDragY = 0f
                        isVerticalCollapse = false
                    }
                },
                onDragEnd = {
                    totalDragX = 0f
                    totalDragY = 0f
                    isVerticalCollapse = false
                },
                onDragCancel = {
                    totalDragX = 0f
                    totalDragY = 0f
                    isVerticalCollapse = false
                },
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
                        isArtworkVisible = false,
                        onArtworkPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { raw ->
                                stableFullCoverRect = Rect(Offset(raw.left, raw.top - fullTranslationY), raw.size)
                            }
                        },
                        isPlayButtonVisible = false,
                        onPlayButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { raw ->
                                stableFullPlayRect = Rect(Offset(raw.left, raw.top - fullTranslationY), raw.size)
                            }
                        },
                        isQueueButtonVisible = false,
                        onQueueButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { raw ->
                                stableFullQueueRect = Rect(Offset(raw.left, raw.top - fullTranslationY), raw.size)
                            }
                        },
                        renderBackground = false,
                        onOpenQueue = onOpenQueue,
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
                        isCoverVisible = false,
                        onCoverPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { stableMiniCoverRect = it }
                        },
                        isPlayButtonVisible = false,
                        onPlayButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { stableMiniPlayRect = it }
                        },
                        isQueueButtonVisible = false,
                        onQueueButtonPositioned = { coords ->
                            coords.boundsIn(containerCoordinates)?.let { stableMiniQueueRect = it }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            // 3. Single Living Real Entities Host (Artwork & Dual Control Buttons: Fixed Base Geometry, GPU Draw Phase Motion)
            val p = expansionProgress.coerceIn(0f, 1f)
            val cWidth = rootConstraints.maxWidth.toFloat()
            val cHeight = rootConstraints.maxHeight.toFloat()
            val hMarginPx = with(density) { MorphingPlayerMath.containerHorizontalMargin(p).toPx() }

            val miniCapsuleCenterY =
                cHeight - with(density) { collapsedBottomPadding.toPx() } - with(density) { (MiniPlayerDefaults.CapsuleHeight / 2f).toPx() }

            val fallbackMiniCover =
                Rect(
                    center = Offset(hMarginPx + with(density) { (10.dp + 21.dp).toPx() }, miniCapsuleCenterY),
                    radius = with(density) { 21.dp.toPx() },
                )
            val fallbackMiniQueue =
                Rect(
                    center = Offset(cWidth - hMarginPx - with(density) { (12.dp + 19.dp).toPx() }, miniCapsuleCenterY),
                    radius = with(density) { 19.dp.toPx() },
                )
            val fallbackMiniPlay =
                Rect(
                    center = Offset(fallbackMiniQueue.center.x - with(density) { (19.dp + 8.dp + 20.dp).toPx() }, miniCapsuleCenterY),
                    radius = with(density) { 20.dp.toPx() },
                )

            val fullArtSizePx = minOf(with(density) { MorphingPlayerDefaults.FullPlayerArtworkSize.toPx() }, cWidth * 0.85f)
            val fallbackFullCover =
                Rect(
                    Offset((cWidth - fullArtSizePx) / 2f, cHeight * 0.25f),
                    Size(fullArtSizePx, fullArtSizePx),
                )
            val fullPlaySizePx = with(density) { MorphingPlayerDefaults.FullPlayerPlayButtonSize.toPx() }
            val fallbackFullPlay =
                Rect(
                    Offset((cWidth - fullPlaySizePx) / 2f, cHeight - with(density) { (collapsedBottomPadding + 80.dp).toPx() }),
                    Size(fullPlaySizePx, fullPlaySizePx),
                )
            val fullQueueSizePx = with(density) { MorphingPlayerDefaults.FullPlayerQueueButtonSize.toPx() }
            val fallbackFullQueue =
                Rect(
                    Offset(
                        cWidth - fullQueueSizePx - with(density) { 20.dp.toPx() },
                        cHeight - with(density) { (collapsedBottomPadding + 70.dp).toPx() },
                    ),
                    Size(fullQueueSizePx, fullQueueSizePx),
                )

            // Ensure mini starting anchors strictly align to the capsule vertical center
            val rawCover = stableMiniCoverRect ?: fallbackMiniCover
            val startCover = Rect(center = Offset(rawCover.center.x, miniCapsuleCenterY), radius = rawCover.width / 2f)
            val endCover = stableFullCoverRect ?: fallbackFullCover

            val rawPlay = stableMiniPlayRect ?: fallbackMiniPlay
            val startPlay = Rect(center = Offset(rawPlay.center.x, miniCapsuleCenterY), radius = rawPlay.width / 2f)
            val endPlay = stableFullPlayRect ?: fallbackFullPlay

            val rawQueue = stableMiniQueueRect ?: fallbackMiniQueue
            val startQueue = Rect(center = Offset(rawQueue.center.x, miniCapsuleCenterY), radius = rawQueue.width / 2f)
            val endQueue = stableFullQueueRect ?: fallbackFullQueue

            // Base Geometry Dimensions (Static expanded base dimensions, zero per-frame remeasure)
            val baseArtWidthPx = with(density) { MorphingPlayerDefaults.FullPlayerArtworkSize.toPx() }
            val basePlayWidthPx = with(density) { MorphingPlayerDefaults.FullPlayerPlayButtonSize.toPx() }
            val baseQueueWidthPx = with(density) { MorphingPlayerDefaults.FullPlayerQueueButtonSize.toPx() }

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

            // 3.1 Album Artwork: DisplayList Matrix Transform (scale, translation, shape, elevation in GPU draw phase)
            val coverScale = lerp(startCover.width / baseArtWidthPx, endCover.width / baseArtWidthPx, p)
            val curCoverCenterX = lerp(startCover.center.x, endCover.center.x, p)
            val curCoverCenterY = lerp(startCover.center.y, endCover.center.y, p)
            val curPhysicalRadius = lerp(startCover.width / 2f, with(density) { FullPlayerDefaults.ArtworkSquircleRadius.toPx() }, p)
            val curLocalRadius = if (coverScale > 0.001f) curPhysicalRadius / coverScale else curPhysicalRadius
            val curCoverElevationPx = lerp(0f, with(density) { 24.dp.toPx() }, p)

            val coverClickModifier =
                if (p < 0.85f) {
                    Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onExpand,
                    )
                } else {
                    Modifier
                }

            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .size(MorphingPlayerDefaults.FullPlayerArtworkSize)
                        .graphicsLayer {
                            scaleX = coverScale
                            scaleY = coverScale
                            translationX = curCoverCenterX - baseArtWidthPx / 2f
                            translationY = curCoverCenterY - baseArtWidthPx / 2f
                            shape = RoundedCornerShape(curLocalRadius)
                            clip = true
                            shadowElevation = curCoverElevationPx
                            spotShadowColor = currentScheme.primary.copy(alpha = 0.35f)
                            ambientShadowColor = currentScheme.primary.copy(alpha = 0.25f)
                        }.background(curCoverBgColor)
                        .then(coverClickModifier),
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
                        modifier = Modifier.size(MorphingPlayerDefaults.FullPlayerArtworkIconSize),
                        tint = curCoverIconColor,
                    )
                }
            }

            // 3.2 Play / Pause Button: DisplayList Matrix Transform
            val playScale = lerp(startPlay.width / basePlayWidthPx, endPlay.width / basePlayWidthPx, p)
            val curPlayCenterX = lerp(startPlay.center.x, endPlay.center.x, p)
            val curPlayCenterY = lerp(startPlay.center.y, endPlay.center.y, p)
            val curPlayPhysicalRadius = lerp(startPlay.width / 2f, with(density) { FullPlayerDefaults.PlayButtonSquircleRadius.toPx() }, p)
            val curPlayLocalRadius = if (playScale > 0.001f) curPlayPhysicalRadius / playScale else curPlayPhysicalRadius
            val curPlayElevationPx = lerp(0f, with(density) { 8.dp.toPx() }, p)

            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .size(MorphingPlayerDefaults.FullPlayerPlayButtonSize)
                        .graphicsLayer {
                            scaleX = playScale
                            scaleY = playScale
                            translationX = curPlayCenterX - basePlayWidthPx / 2f
                            translationY = curPlayCenterY - basePlayWidthPx / 2f
                            shape = RoundedCornerShape(curPlayLocalRadius)
                            clip = true
                            shadowElevation = curPlayElevationPx
                            spotShadowColor = currentScheme.primary.copy(alpha = 0.35f)
                            ambientShadowColor = currentScheme.primary.copy(alpha = 0.25f)
                        }.background(curPlayContainerColor)
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
                                    modifier = Modifier.size(MorphingPlayerDefaults.FullPlayerPlayIconSize),
                                )
                            } else {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(MorphingPlayerDefaults.FullPlayerPlayIconSize),
                                    strokeWidth = 3.dp,
                                    color = curPlayContentColor,
                                )
                            }
                        }

                        is PlaybackState.Playing -> {
                            Icon(
                                imageVector = AppIcons.Pause,
                                contentDescription = "暂停",
                                tint = curPlayContentColor,
                                modifier = Modifier.size(MorphingPlayerDefaults.FullPlayerPlayIconSize),
                            )
                        }

                        else -> {
                            Icon(
                                imageVector = AppIcons.PlayArrow,
                                contentDescription = "播放",
                                tint = curPlayContentColor,
                                modifier = Modifier.size(MorphingPlayerDefaults.FullPlayerPlayIconSize),
                            )
                        }
                    }
                }
            }

            // 3.3 Queue Button: DisplayList Matrix Transform
            val queueScale = lerp(startQueue.width / baseQueueWidthPx, endQueue.width / baseQueueWidthPx, p)
            val curQueueCenterX = lerp(startQueue.center.x, endQueue.center.x, p)
            val curQueueCenterY = lerp(startQueue.center.y, endQueue.center.y, p)
            val curQueuePhysicalRadius = lerp(startQueue.width / 2f, with(density) { 16.dp.toPx() }, p)
            val curQueueLocalRadius = if (queueScale > 0.001f) curQueuePhysicalRadius / queueScale else curQueuePhysicalRadius
            val curQueueIconColor =
                lerpColor(
                    if (p > 0.35f) currentScheme.onSurfaceVariant else currentScheme.onSurface,
                    stageScheme.onSurfaceVariant,
                    p,
                )

            Box(
                modifier =
                    Modifier
                        .align(Alignment.TopStart)
                        .size(MorphingPlayerDefaults.FullPlayerQueueButtonSize)
                        .graphicsLayer {
                            scaleX = queueScale
                            scaleY = queueScale
                            translationX = curQueueCenterX - baseQueueWidthPx / 2f
                            translationY = curQueueCenterY - baseQueueWidthPx / 2f
                            shape = RoundedCornerShape(curQueueLocalRadius)
                            clip = true
                        }.background(
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
                Icon(
                    imageVector = AppIcons.QueueMusic,
                    contentDescription = "播放队列",
                    tint = curQueueIconColor,
                    modifier = Modifier.size(MorphingPlayerDefaults.FullPlayerQueueIconSize),
                )
            }
        }
    }
}
