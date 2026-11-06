package com.webdav.player.ui.player.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.webdav.player.domain.model.AudioFormat
import com.webdav.player.domain.model.AudioTrack
import com.webdav.player.domain.model.PlayerSessionState
import com.webdav.player.ui.common.CoverThumbnailImage
import com.webdav.player.ui.player.ArtworkColorExtractor
import com.webdav.player.ui.player.ArtworkColors
import com.webdav.player.ui.player.FullPlayerDefaults
import com.webdav.player.ui.player.rememberArtworkColors
import com.webdav.player.ui.theme.AppIcons
import com.webdav.player.ui.theme.FLUID_THEME_DURATION_MS
import com.webdav.player.ui.theme.FluidThemeAnimationEasing

/**
 * Dynamic atmospheric multi-stop ambient glow gradient sampled from cover art with MD3 fallback.
 * Blends vibrantColor, dominantColor, and mutedColor into a soft luminous halo behind the artwork card
 * and seamlessly melts into background/surface at the bottom.
 */
@Composable
internal fun rememberArtworkBackgroundBrush(
    thumbnailPath: String?,
    artworkColors: ArtworkColors? = null,
    defaultSurfaceColor: Color = MaterialTheme.colorScheme.surfaceContainer,
    defaultBackgroundColor: Color = MaterialTheme.colorScheme.surface,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
): Brush {
    val activeColors =
        artworkColors
            ?: rememberArtworkColors(
                thumbnailPath = thumbnailPath,
                defaultSurfaceColor = defaultSurfaceColor,
                defaultBackgroundColor = defaultBackgroundColor,
                isDarkTheme = isDarkTheme,
            ).value

    val targetStops =
        ArtworkColorExtractor.computeAmbientGlowStops(
            artworkColors = activeColors,
            isDarkTheme = isDarkTheme,
            surfaceColor = defaultSurfaceColor,
            backgroundColor = defaultBackgroundColor,
        )

    val animatedTopColor by animateColorAsState(
        targetValue = targetStops.topColor,
        animationSpec = tween(durationMillis = FLUID_THEME_DURATION_MS, easing = FluidThemeAnimationEasing),
        label = "FullPlayerBackgroundTop",
    )
    val animatedMidColor by animateColorAsState(
        targetValue = targetStops.midColor,
        animationSpec = tween(durationMillis = FLUID_THEME_DURATION_MS, easing = FluidThemeAnimationEasing),
        label = "FullPlayerBackgroundMid",
    )
    val animatedBottomColor by animateColorAsState(
        targetValue = targetStops.bottomColor,
        animationSpec = tween(durationMillis = FLUID_THEME_DURATION_MS, easing = FluidThemeAnimationEasing),
        label = "FullPlayerBackgroundBottom",
    )

    return remember(animatedTopColor, animatedMidColor, animatedBottomColor) {
        Brush.verticalGradient(
            0.0f to animatedTopColor,
            0.45f to animatedMidColor,
            1.0f to animatedBottomColor,
        )
    }
}

/**
 * Vinyl record and album artwork presentation composable with soft shadow and rounded corners.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun PlayerVinylArtwork(
    currentTrack: AudioTrack?,
    sessionState: PlayerSessionState,
    onArtworkClick: () -> Unit,
    modifier: Modifier = Modifier,
    artworkColors: ArtworkColors? = null,
) {
    val isDark = isSystemInDarkTheme()
    val activeColors =
        artworkColors
            ?: rememberArtworkColors(
                thumbnailPath = currentTrack?.coverThumbnailPath,
                defaultSurfaceColor = MaterialTheme.colorScheme.surfaceContainer,
                defaultBackgroundColor = MaterialTheme.colorScheme.surface,
                isDarkTheme = isDark,
            ).value

    val shadowSourceColor =
        if (activeColors.isMonochrome) {
            activeColors.mutedColor
        } else {
            activeColors.vibrantColor
        }

    val spotAlpha = if (isDark) 0.35f else 0.22f
    val ambientAlpha = if (isDark) 0.25f else 0.15f

    val animatedSpotColor by animateColorAsState(
        targetValue = shadowSourceColor.copy(alpha = spotAlpha),
        animationSpec = tween(durationMillis = FLUID_THEME_DURATION_MS, easing = FluidThemeAnimationEasing),
        label = "SquircleCardSpotColor",
    )
    val animatedAmbientColor by animateColorAsState(
        targetValue = shadowSourceColor.copy(alpha = ambientAlpha),
        animationSpec = tween(durationMillis = FLUID_THEME_DURATION_MS, easing = FluidThemeAnimationEasing),
        label = "SquircleCardAmbientColor",
    )

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        // Large Album Artwork Card with 28dp Squircle & Ambient Glow
        Box(
            modifier =
                Modifier
                    .fillMaxWidth(0.85f)
                    .sizeIn(minWidth = 190.dp, minHeight = 190.dp, maxWidth = 280.dp, maxHeight = 280.dp)
                    .aspectRatio(1f)
                    .shadow(
                        elevation = 24.dp,
                        shape = FullPlayerDefaults.ArtworkSquircleShape,
                        spotColor = animatedSpotColor,
                        ambientColor = animatedAmbientColor,
                    )
                    .clip(FullPlayerDefaults.ArtworkSquircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable { onArtworkClick() },
            contentAlignment = Alignment.Center,
        ) {
            CoverThumbnailImage(
                thumbnailPath = currentTrack?.coverThumbnailPath,
                contentDescription = "专辑封面",
                modifier = Modifier.fillMaxSize(),
            ) {
                Icon(
                    imageVector = AppIcons.Audiotrack,
                    contentDescription = "专辑封面",
                    modifier = Modifier.size(100.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Track Title with basicMarquee for long titles
        Text(
            text = currentTrack?.title ?: "无正在播放曲目",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .basicMarquee(),
        )

        Spacer(modifier = Modifier.height(6.dp))

        // Artist Name or Weak Network Alert Subtitle
        val subtitleText =
            when {
                sessionState.isSuspendedRetry -> PlayerSessionState.RETRY_BANNER_MESSAGE
                sessionState.isWeakNetwork -> PlayerSessionState.WEAK_NETWORK_MESSAGE
                else -> currentTrack?.artist?.trim()?.takeIf { it.isNotBlank() } ?: "未知艺术家"
            }
        Text(
            text = subtitleText,
            style = MaterialTheme.typography.bodyLarge,
            color = if (sessionState.isWeakNetwork) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )

        // Audiophile Specification Capsule
        val specs = currentTrack?.audiophileSpecsModel
        if (specs != null && specs.formatted.isNotBlank()) {
            Spacer(modifier = Modifier.height(8.dp))
            AudiophileSpecsBadge(specs = specs)
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun PlayerVinylArtworkPreview() {
    MaterialTheme {
        PlayerVinylArtwork(
            currentTrack =
                AudioTrack(
                    id = "1:/Music/demo.flac",
                    serverId = 1L,
                    remotePath = "/Music/demo.flac",
                    title = "Sample Hi-Res Song",
                    artist = "Master Artist",
                    album = "Masterpiece Album",
                    durationMs = 240000L,
                    size = 30000000L,
                    format = AudioFormat.FLAC,
                ),
            sessionState = PlayerSessionState(),
            onArtworkClick = {},
        )
    }
}

