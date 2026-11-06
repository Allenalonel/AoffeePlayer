package com.webdav.player.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.TweenSpec
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.webdav.player.ui.player.ArtworkColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class WebDavPlayerThemeTest {

    private fun channelLuminance(channel: Float): Double {
        return if (channel <= 0.03928) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).toDouble().pow(2.4)
        }
    }

    private fun relativeLuminance(color: Color): Double {
        val r = channelLuminance(color.red)
        val g = channelLuminance(color.green)
        val b = channelLuminance(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    private fun contrastRatio(colorA: Color, colorB: Color): Double {
        val l1 = relativeLuminance(colorA)
        val l2 = relativeLuminance(colorB)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    @Test
    fun darkColorScheme_meetsHighContrastAccessibilityStandards() {
        val scheme = ExpressiveDarkColorScheme

        // onSurface vs surface
        val surfaceContrast = contrastRatio(scheme.onSurface, scheme.surface)
        assertTrue(
            "onSurface vs surface contrast must be >= 7:1 for high contrast dark theme, got $surfaceContrast",
            surfaceContrast >= 7.0
        )

        // onBackground vs background
        val backgroundContrast = contrastRatio(scheme.onBackground, scheme.background)
        assertTrue(
            "onBackground vs background contrast must be >= 7:1, got $backgroundContrast",
            backgroundContrast >= 7.0
        )

        // onPrimary vs primary
        val primaryContrast = contrastRatio(scheme.onPrimary, scheme.primary)
        assertTrue(
            "onPrimary vs primary contrast must be >= 4.5:1, got $primaryContrast",
            primaryContrast >= 4.5
        )

        // onError vs error
        val errorContrast = contrastRatio(scheme.onError, scheme.error)
        assertTrue(
            "onError vs error contrast must be >= 4.5:1, got $errorContrast",
            errorContrast >= 4.5
        )
    }

    @Test
    fun lightColorScheme_meetsHighContrastAccessibilityStandards() {
        val scheme = ExpressiveLightColorScheme

        // onSurface vs surface
        val surfaceContrast = contrastRatio(scheme.onSurface, scheme.surface)
        assertTrue(
            "onSurface vs surface contrast must be >= 7:1 for light theme, got $surfaceContrast",
            surfaceContrast >= 7.0
        )

        // onBackground vs background
        val backgroundContrast = contrastRatio(scheme.onBackground, scheme.background)
        assertTrue(
            "onBackground vs background contrast must be >= 7:1, got $backgroundContrast",
            backgroundContrast >= 7.0
        )

        // onPrimary vs primary
        val primaryContrast = contrastRatio(scheme.onPrimary, scheme.primary)
        assertTrue(
            "onPrimary vs primary contrast must be >= 4.5:1, got $primaryContrast",
            primaryContrast >= 4.5
        )

        // onError vs error
        val errorContrast = contrastRatio(scheme.onError, scheme.error)
        assertTrue(
            "onError vs error contrast must be >= 4.5:1, got $errorContrast",
            errorContrast >= 4.5
        )

        // onSurfaceVariant (secondary text) is dark slate charcoal (#3A3546)
        assertEquals(Color(0xFF3A3546), scheme.onSurfaceVariant)

        // onSurfaceVariant vs background reaches WCAG AAA (>= 8.5:1)
        val secTextBgContrast = contrastRatio(scheme.onSurfaceVariant, scheme.background)
        assertTrue(
            "onSurfaceVariant vs background contrast must be >= 8.5:1, got $secTextBgContrast",
            secTextBgContrast >= 8.5
        )

        // onSurfaceVariant vs surfaceContainer reaches WCAG AAA (>= 8.5:1)
        val secTextContainerContrast = contrastRatio(scheme.onSurfaceVariant, scheme.surfaceContainer)
        assertTrue(
            "onSurfaceVariant vs surfaceContainer contrast must be >= 8.5:1, got $secTextContainerContrast",
            secTextContainerContrast >= 8.5
        )

        // onPrimaryContainer vs primaryContainer (pills) reaches WCAG AAA (>= 8.5:1)
        val pillContrast = contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)
        assertTrue(
            "onPrimaryContainer vs primaryContainer contrast must be >= 8.5:1, got $pillContrast",
            pillContrast >= 8.5
        )
    }

    @Test
    fun defaultThemes_lockToObsidianDarkAndPureWhiteLight() {
        assertEquals(BackgroundDark, ExpressiveDarkColorScheme.background)
        assertEquals(SurfaceDark, ExpressiveDarkColorScheme.surface)
        assertEquals(Color(0xFF121318), ExpressiveDarkColorScheme.background)
        assertEquals(Color(0xFF121318), ExpressiveDarkColorScheme.surface)

        assertEquals(BackgroundLight, ExpressiveLightColorScheme.background)
        assertEquals(SurfaceLight, ExpressiveLightColorScheme.surface)
        assertEquals(Color(0xFFFFFFFF), ExpressiveLightColorScheme.background)
        assertEquals(Color(0xFFFFFFFF), ExpressiveLightColorScheme.surface)
    }

    @Test
    fun defaultThemes_adhereToTrackDrivenZeroPresetPrincipleWithoutPurpleBias() {
        val legacyPurpleAccent = Color(0xFF5544B3)
        val legacyPurpleDarkBg = Color(0xFF141022)

        // Neither scheme should contain hardcoded brand purple bias
        assertNotEquals("Dark primary must not be legacy purple", legacyPurpleAccent, ExpressiveDarkColorScheme.primary)
        assertNotEquals("Light primary must not be legacy purple", legacyPurpleAccent, ExpressiveLightColorScheme.primary)
        assertNotEquals("Dark background must not be legacy purple", legacyPurpleDarkBg, ExpressiveDarkColorScheme.background)
        assertNotEquals("Light background must not be legacy purple", legacyPurpleDarkBg, ExpressiveLightColorScheme.background)

        // Primary tokens in neutral fallback schemes must be slate/charcoal in light and pearl white in dark
        assertEquals(Color(0xFF2E3135), ExpressiveLightColorScheme.primary)
        assertEquals(Color(0xFFE2E2E6), ExpressiveDarkColorScheme.primary)

        // Dark pill contrast meets WCAG AAA standards
        val darkPillContrast = contrastRatio(ExpressiveDarkColorScheme.onPrimaryContainer, ExpressiveDarkColorScheme.primaryContainer)
        assertTrue(
            "Dark onPrimaryContainer vs primaryContainer must be >= 8.5:1, got $darkPillContrast",
            darkPillContrast >= 8.5
        )
    }

    @Test
    fun fluidThemeAnimation_configuredTo450msTweenWithCubicBezier() {
        assertEquals(450, FLUID_THEME_DURATION_MS)
        assertEquals(CubicBezierEasing(0.4f, 0.0f, 0.2f, 1.0f), FluidThemeAnimationEasing)

        val tweenSpec = FluidThemeAnimationSpec as? TweenSpec<Color>
        assertNotNull("FluidThemeAnimationSpec must be a TweenSpec", tweenSpec)
        assertEquals(450, tweenSpec?.durationMillis)
        assertEquals(FluidThemeAnimationEasing, tweenSpec?.easing)
    }

    @Test
    fun trackDrivenTheming_webDavPlayerTheme100PercentDrivenByArtworkColors() {
        val customDarkScheme = ExpressiveDarkColorScheme.copy(primary = Color(0xFF112233))
        val customLightScheme = ExpressiveLightColorScheme.copy(primary = Color(0xFF445566))

        val customArtwork =
            ArtworkColors(
                dominantColor = Color(0xFF112233),
                backgroundTopColor = Color(0xFF223344),
                backgroundBottomColor = Color(0xFF334455),
                accentColor = Color(0xFF445566),
                isFallback = false,
                darkColorScheme = customDarkScheme,
                lightColorScheme = customLightScheme,
            )

        // When artworkColors is supplied, theme is 100% driven by artworkColors
        val resolvedDark = resolveBaseColorScheme(darkTheme = true, artworkColors = customArtwork)
        assertEquals(customDarkScheme, resolvedDark)

        val resolvedLight = resolveBaseColorScheme(darkTheme = false, artworkColors = customArtwork)
        assertEquals(customLightScheme, resolvedLight)

        // When artworkColors has isFallback = true, it is still driven by artworkColors
        val fallbackArtwork = customArtwork.copy(isFallback = true)
        val resolvedFallbackDark = resolveBaseColorScheme(darkTheme = true, artworkColors = fallbackArtwork)
        assertEquals(customDarkScheme, resolvedFallbackDark)

        // When targetColorScheme is explicitly given, it overrides artwork
        val explicitOverride = ExpressiveDarkColorScheme.copy(primary = Color.Magenta)
        val resolvedOverride =
            resolveBaseColorScheme(
                darkTheme = true,
                artworkColors = customArtwork,
                targetColorScheme = explicitOverride,
            )
        assertEquals(explicitOverride, resolvedOverride)

        // When no artwork and no target is given, falls back to neutral default schemes
        val fallbackDark = resolveBaseColorScheme(darkTheme = true, artworkColors = null)
        assertEquals(ExpressiveDarkColorScheme, fallbackDark)

        val fallbackLight = resolveBaseColorScheme(darkTheme = false, artworkColors = null)
        assertEquals(ExpressiveLightColorScheme, fallbackLight)
    }

    @Test
    fun surfaceContainerHierarchy_isDifferentiatedInDarkScheme() {
        val scheme = ExpressiveDarkColorScheme

        // Dark theme: surfaceContainerLowest is darkest, highest is lightest
        val lumLowest = relativeLuminance(scheme.surfaceContainerLowest)
        val lumLow = relativeLuminance(scheme.surfaceContainerLow)
        val lumContainer = relativeLuminance(scheme.surfaceContainer)
        val lumHigh = relativeLuminance(scheme.surfaceContainerHigh)
        val lumHighest = relativeLuminance(scheme.surfaceContainerHighest)

        assertTrue("Lowest <= Low", lumLowest <= lumLow)
        assertTrue("Low <= Container", lumLow <= lumContainer)
        assertTrue("Container <= High", lumContainer <= lumHigh)
        assertTrue("High <= Highest", lumHigh <= lumHighest)
    }

    @Test
    fun surfaceContainerHierarchy_isDifferentiatedInLightScheme() {
        val scheme = ExpressiveLightColorScheme

        // Light theme: surfaceContainerLowest is purest white (highest luminance), highest is darker
        val lumLowest = relativeLuminance(scheme.surfaceContainerLowest)
        val lumLow = relativeLuminance(scheme.surfaceContainerLow)
        val lumContainer = relativeLuminance(scheme.surfaceContainer)
        val lumHigh = relativeLuminance(scheme.surfaceContainerHigh)
        val lumHighest = relativeLuminance(scheme.surfaceContainerHighest)

        assertTrue("Lowest >= Low in light mode", lumLowest >= lumLow)
        assertTrue("Low >= Container in light mode", lumLow >= lumContainer)
        assertTrue("Container >= High in light mode", lumContainer >= lumHigh)
        assertTrue("High >= Highest in light mode", lumHigh >= lumHighest)
    }

    @Test
    fun expressiveTypography_hasProperHierarchyAndFontWeights() {
        val typography = ExpressiveTypography

        // Display > Headline > Title > Body > Label
        assertTrue(
            "displayLarge > headlineLarge",
            typography.displayLarge.fontSize > typography.headlineLarge.fontSize
        )
        assertTrue(
            "headlineLarge > titleLarge",
            typography.headlineLarge.fontSize > typography.titleLarge.fontSize
        )
        assertTrue(
            "titleLarge > bodyLarge",
            typography.titleLarge.fontSize > typography.bodyLarge.fontSize
        )
        assertTrue(
            "bodyLarge > labelSmall",
            typography.bodyLarge.fontSize > typography.labelSmall.fontSize
        )

        // Font weights check
        assertEquals(FontWeight.Bold, typography.displayLarge.fontWeight)
        assertEquals(FontWeight.Bold, typography.headlineLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, typography.titleMedium.fontWeight)
        assertEquals(FontWeight.Normal, typography.bodyLarge.fontWeight)
        assertEquals(FontWeight.SemiBold, typography.labelSmall.fontWeight)
    }

    @Test
    fun expressiveShapes_adheresToStrictCornerRadiusScale() {
        val shapes = ExpressiveShapes
        val density = Density(1f)
        val testSize = Size(200f, 200f)

        val xsRadius = shapes.extraSmall.topStart.toPx(testSize, density)
        val sRadius = shapes.small.topStart.toPx(testSize, density)
        val mRadius = shapes.medium.topStart.toPx(testSize, density)
        val lRadius = shapes.large.topStart.toPx(testSize, density)
        val xlRadius = shapes.extraLarge.topStart.toPx(testSize, density)

        assertTrue("extraSmall < small", xsRadius < sRadius)
        assertTrue("small < medium", sRadius < mRadius)
        assertTrue("medium < large", mRadius < lRadius)
        assertTrue("large < extraLarge", lRadius < xlRadius)

        assertEquals(6f, xsRadius, 0.01f)
        assertEquals(10f, sRadius, 0.01f)
        assertEquals(16f, mRadius, 0.01f)
        assertEquals(24f, lRadius, 0.01f)
        assertEquals(32f, xlRadius, 0.01f)
    }
}
