package com.webdav.player.ui.theme

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.webdav.player.ui.player.ArtworkColors

val ExpressiveDarkColorScheme =
    darkColorScheme(
        primary = PrimaryDark,
        onPrimary = OnPrimaryDark,
        primaryContainer = PrimaryContainerDark,
        onPrimaryContainer = OnPrimaryContainerDark,
        secondary = SecondaryDark,
        onSecondary = OnSecondaryDark,
        secondaryContainer = SecondaryContainerDark,
        onSecondaryContainer = OnSecondaryContainerDark,
        tertiary = TertiaryDark,
        onTertiary = OnTertiaryDark,
        tertiaryContainer = TertiaryContainerDark,
        onTertiaryContainer = OnTertiaryContainerDark,
        background = BackgroundDark,
        onBackground = OnBackgroundDark,
        surface = SurfaceDark,
        onSurface = OnSurfaceDark,
        surfaceVariant = SurfaceVariantDark,
        onSurfaceVariant = OnSurfaceVariantDark,
        surfaceContainerLowest = SurfaceContainerLowestDark,
        surfaceContainerLow = SurfaceContainerLowDark,
        surfaceContainer = SurfaceContainerDark,
        surfaceContainerHigh = SurfaceContainerHighDark,
        surfaceContainerHighest = SurfaceContainerHighestDark,
        outline = OutlineDark,
        outlineVariant = OutlineVariantDark,
        error = ErrorDark,
        onError = OnErrorDark,
        errorContainer = ErrorContainerDark,
        onErrorContainer = OnErrorContainerDark,
    )

val ExpressiveLightColorScheme =
    lightColorScheme(
        primary = PrimaryLight,
        onPrimary = OnPrimaryLight,
        primaryContainer = PrimaryContainerLight,
        onPrimaryContainer = OnPrimaryContainerLight,
        secondary = SecondaryLight,
        onSecondary = OnSecondaryLight,
        secondaryContainer = SecondaryContainerLight,
        onSecondaryContainer = OnSecondaryContainerLight,
        tertiary = TertiaryLight,
        onTertiary = OnTertiaryLight,
        tertiaryContainer = TertiaryContainerLight,
        onTertiaryContainer = OnTertiaryContainerLight,
        background = BackgroundLight,
        onBackground = OnBackgroundLight,
        surface = SurfaceLight,
        onSurface = OnSurfaceLight,
        surfaceVariant = SurfaceVariantLight,
        onSurfaceVariant = OnSurfaceVariantLight,
        surfaceContainerLowest = SurfaceContainerLowestLight,
        surfaceContainerLow = SurfaceContainerLowLight,
        surfaceContainer = SurfaceContainerLight,
        surfaceContainerHigh = SurfaceContainerHighLight,
        surfaceContainerHighest = SurfaceContainerHighestLight,
        outline = OutlineLight,
        outlineVariant = OutlineVariantLight,
        error = ErrorLight,
        onError = OnErrorLight,
        errorContainer = ErrorContainerLight,
        onErrorContainer = OnErrorContainerLight,
    )

/**
 * Standard 450ms fluid physics morphing curve for track-driven color transitions.
 * Unified across background, surface containers, and primary interactive tokens.
 */
val FluidThemeAnimationEasing = CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f)
const val FLUID_THEME_DURATION_MS = 450

val FluidThemeAnimationSpec: AnimationSpec<Color> =
    tween(
        durationMillis = FLUID_THEME_DURATION_MS,
        easing = FluidThemeAnimationEasing,
    )

/**
 * Smoothly animates key background, container, and primary color tiers over 450ms.
 * Unified 450ms fluid physics morphing curve guarantees smooth color morphing
 * across track transitions without full 17-color tree recomposition lag.
 */
@Composable
fun animatedColorScheme(
    target: ColorScheme,
    animationSpec: AnimationSpec<Color> = FluidThemeAnimationSpec,
): ColorScheme {
    val background by animateColorAsState(target.background, animationSpec, label = "color_background")
    val surface by animateColorAsState(target.surface, animationSpec, label = "color_surface")
    val surfaceContainer by animateColorAsState(target.surfaceContainer, animationSpec, label = "color_surfaceContainer")
    val surfaceContainerHigh by animateColorAsState(target.surfaceContainerHigh, animationSpec, label = "color_surfaceContainerHigh")
    val surfaceContainerLow by animateColorAsState(target.surfaceContainerLow, animationSpec, label = "color_surfaceContainerLow")
    val primary by animateColorAsState(target.primary, animationSpec, label = "color_primary")
    val primaryContainer by animateColorAsState(target.primaryContainer, animationSpec, label = "color_primaryContainer")
    val onPrimary by animateColorAsState(target.onPrimary, animationSpec, label = "color_onPrimary")
    val onPrimaryContainer by animateColorAsState(target.onPrimaryContainer, animationSpec, label = "color_onPrimaryContainer")

    return target.copy(
        background = background,
        surface = surface,
        surfaceContainer = surfaceContainer,
        surfaceContainerHigh = surfaceContainerHigh,
        surfaceContainerLow = surfaceContainerLow,
        primary = primary,
        primaryContainer = primaryContainer,
        onPrimary = onPrimary,
        onPrimaryContainer = onPrimaryContainer,
    )
}

/**
 * Resolves the active [ColorScheme] following the Track-Driven Zero-Preset Principle.
 * When [artworkColors] is provided, the theme is 100% driven by the active track's
 * extracted colors (or neutral fallback when no artwork exists).
 */
fun resolveBaseColorScheme(
    darkTheme: Boolean,
    dynamicColor: Boolean = false,
    artworkColors: ArtworkColors? = null,
    targetColorScheme: ColorScheme? = null,
    dynamicDarkScheme: ColorScheme? = null,
    dynamicLightScheme: ColorScheme? = null,
): ColorScheme =
    when {
        targetColorScheme != null -> {
            targetColorScheme
        }

        artworkColors != null -> {
            artworkColors.colorScheme(darkTheme)
        }

        dynamicColor && (if (darkTheme) dynamicDarkScheme != null else dynamicLightScheme != null) -> {
            if (darkTheme) dynamicDarkScheme!! else dynamicLightScheme!!
        }

        darkTheme -> {
            ExpressiveDarkColorScheme
        }

        else -> {
            ExpressiveLightColorScheme
        }
    }

@Composable
fun WebDavPlayerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    artworkColors: ArtworkColors? = null,
    targetColorScheme: ColorScheme? = null,
    animateTransitions: Boolean = true,
    content: @Composable () -> Unit,
) {
    val dynamicDark =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicDarkColorScheme(LocalContext.current)
        } else {
            null
        }
    val dynamicLight =
        if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dynamicLightColorScheme(LocalContext.current)
        } else {
            null
        }

    val baseScheme =
        resolveBaseColorScheme(
            darkTheme = darkTheme,
            dynamicColor = dynamicColor,
            artworkColors = artworkColors,
            targetColorScheme = targetColorScheme,
            dynamicDarkScheme = dynamicDark,
            dynamicLightScheme = dynamicLight,
        )

    val finalColorScheme =
        if (animateTransitions) {
            animatedColorScheme(baseScheme)
        } else {
            baseScheme
        }

    MaterialTheme(
        colorScheme = finalColorScheme,
        typography = ExpressiveTypography,
        shapes = ExpressiveShapes,
        content = content,
    )
}
