package com.webdav.player.ui.player

import android.graphics.Bitmap
import androidx.compose.ui.graphics.Color
import com.webdav.player.ui.theme.ExpressiveDarkColorScheme
import com.webdav.player.ui.theme.ExpressiveLightColorScheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.FileOutputStream

@RunWith(RobolectricTestRunner::class)
class ArtworkColorExtractorTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    private val defaultSurface = Color(0xFF1E1E2E)
    private val defaultBackground = Color(0xFF11111B)

    private fun relativeLuminance(color: Color): Double = ArtworkColorExtractor.relativeLuminance(color)

    private fun contrastRatio(
        colorA: Color,
        colorB: Color,
    ): Double = ArtworkColorExtractor.contrastRatio(colorA, colorB)

    @Test
    fun extractArtworkColors_withNullPath_returnsFallback() {
        val result =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = null,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertTrue(result.isFallback)
        assertTrue(result.isMonochrome)
        assertEquals(defaultSurface, result.dominantColor)
        assertEquals(defaultSurface, result.backgroundTopColor)
        assertEquals(defaultBackground, result.backgroundBottomColor)
        assertNotNull(result.ambientGlowBrush())
    }

    @Test
    fun extractArtworkColors_withBlankPath_returnsFallback() {
        val result =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = "   ",
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertTrue(result.isFallback)
        assertTrue(result.isMonochrome)
        assertEquals(defaultSurface, result.dominantColor)
        assertEquals(defaultSurface, result.backgroundTopColor)
    }

    @Test
    fun extractArtworkColors_withNonExistentFile_returnsFallback() {
        val nonExistentPath = File(tempFolder.root, "does_not_exist.jpg").absolutePath
        val result =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = nonExistentPath,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertTrue(result.isFallback)
        assertTrue(result.isMonochrome)
        assertEquals(defaultSurface, result.dominantColor)
    }

    @Test
    fun extractDominantColor_fromPureRedBitmap_identifiesRedDominantColor() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.RED)

        val dominant = ArtworkColorExtractor.extractDominantColor(bitmap)
        assertNotNull(dominant)

        // Red channel should be close to 1.0, green/blue close to 0
        assertEquals(1f, dominant!!.red, 0.05f)
        assertEquals(0f, dominant.green, 0.05f)
        assertEquals(0f, dominant.blue, 0.05f)
    }

    @Test
    fun extractDominantColor_fromPureBlueBitmap_identifiesBlueDominantColor() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)

        val dominant = ArtworkColorExtractor.extractDominantColor(bitmap)
        assertNotNull(dominant)

        assertEquals(0f, dominant!!.red, 0.05f)
        assertEquals(0f, dominant.green, 0.05f)
        assertEquals(1f, dominant.blue, 0.05f)
    }

    @Test
    fun extractDominantColor_transparentBitmap_returnsNull() {
        val bitmap = Bitmap.createBitmap(8, 8, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.TRANSPARENT)

        val dominant = ArtworkColorExtractor.extractDominantColor(bitmap)
        assertEquals(null, dominant)
    }

    @Test
    fun extractArtworkColors_fromBitmap_generatesAtmosphericGradientWithoutFallback() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.GREEN)

        val result =
            ArtworkColorExtractor.extractArtworkColors(
                bitmap = bitmap,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
                isDarkTheme = true,
            )

        assertFalse(result.isFallback)
        assertFalse(result.isMonochrome)
        // Dominant should be green
        assertEquals(0f, result.dominantColor.red, 0.05f)
        assertEquals(1f, result.dominantColor.green, 0.05f)
        assertEquals(0f, result.dominantColor.blue, 0.05f)

        // Bottom background matches requested background
        assertEquals(defaultBackground, result.backgroundBottomColor)
        assertNotNull(result.ambientGlowBrush())
    }

    @Test
    fun extractArtworkColors_fromValidDiskFile_extractsProperColors() {
        val imageFile = tempFolder.newFile("sample_cover.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.MAGENTA)
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val result =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = imageFile.absolutePath,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
                isDarkTheme = true,
            )

        assertFalse(result.isFallback)
        assertFalse(result.isMonochrome)
        // Magenta is Red + Blue
        assertTrue(result.dominantColor.red > 0.8f)
        assertTrue(result.dominantColor.blue > 0.8f)
        assertEquals(defaultBackground, result.backgroundBottomColor)
    }

    @Test
    fun extractArtworkColors_emptyZeroByteFile_returnsFallback() {
        val emptyFile = tempFolder.newFile("empty.png")
        val result =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = emptyFile.absolutePath,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertTrue(result.isFallback)
        assertTrue(result.isMonochrome)
        assertEquals(defaultSurface, result.dominantColor)
    }

    @Test
    fun extractArtworkColors_fallback_providesNeutralMonochromeColorSchemesWithoutBrandPurple() {
        val result =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = null,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertTrue(result.isFallback)
        assertTrue(result.isMonochrome)
        assertEquals(ArtworkColorExtractor.MonochromeDarkColorScheme, result.darkColorScheme)
        assertEquals(ArtworkColorExtractor.MonochromeLightColorScheme, result.lightColorScheme)
        assertEquals(ExpressiveDarkColorScheme, ArtworkColorExtractor.MonochromeDarkColorScheme)
        assertEquals(ExpressiveLightColorScheme, ArtworkColorExtractor.MonochromeLightColorScheme)
        assertEquals(result.darkColorScheme, result.colorScheme(isDark = true))
        assertEquals(result.lightColorScheme, result.colorScheme(isDark = false))

        // Must not contain hardcoded brand purple #5544B3 or #141022
        val brandPurpleLight = Color(0xFF5544B3)
        val brandPurpleDark = Color(0xFF141022)
        assertFalse(result.lightColorScheme.primary == brandPurpleLight)
        assertFalse(result.darkColorScheme.background == brandPurpleDark)
    }

    @Test
    fun extractArtworkColors_vividCyanBlueBitmap_preservesCyanBluePrimaryHueRange() {
        val cyanBlueBitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        cyanBlueBitmap.eraseColor(android.graphics.Color.rgb(0, 180, 255))

        val result =
            ArtworkColorExtractor.extractArtworkColors(
                bitmap = cyanBlueBitmap,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertFalse(result.isFallback)
        assertFalse(result.isMonochrome)

        val extractedAccentHue = ArtworkColorExtractor.colorToHsl(result.accentColor)[0]
        val darkPrimaryHue = ArtworkColorExtractor.colorToHsl(result.darkColorScheme.primary)[0]
        val lightPrimaryHue = ArtworkColorExtractor.colorToHsl(result.lightColorScheme.primary)[0]

        assertTrue(
            "Extracted accent hue must be in cyan/blue range (180..260), got $extractedAccentHue",
            extractedAccentHue in 180f..260f,
        )
        assertTrue(
            "Derived dark colorScheme.primary hue must be in cyan/blue range (180..260), got $darkPrimaryHue",
            darkPrimaryHue in 180f..260f,
        )
        assertTrue(
            "Derived light colorScheme.primary hue must be in cyan/blue range (180..260), got $lightPrimaryHue",
            lightPrimaryHue in 180f..260f,
        )
    }

    @Test
    fun extractArtworkColors_chromaticBitmaps_identifiesCorrectHueAndNotMonochrome() {
        val colors =
            listOf(
                android.graphics.Color.RED to "Red",
                android.graphics.Color.BLUE to "Blue",
                android.graphics.Color.GREEN to "Green",
                android.graphics.Color.MAGENTA to "Magenta",
            )

        for ((androidColor, name) in colors) {
            val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(androidColor)

            val result =
                ArtworkColorExtractor.extractArtworkColors(
                    bitmap = bitmap,
                    defaultSurfaceColor = defaultSurface,
                    defaultBackgroundColor = defaultBackground,
                )

            assertFalse("$name: isFallback should be false", result.isFallback)
            assertFalse("$name: isMonochrome should be false", result.isMonochrome)
            assertNotNull("$name: dominantColor should be present", result.dominantColor)
            assertNotNull("$name: vibrantColor should be present", result.vibrantColor)

            // WCAG checks on the derived schemes
            val darkScheme = result.darkColorScheme
            val lightScheme = result.lightColorScheme

            assertTrue(
                "$name: dark onPrimary vs primary >= 4.5",
                contrastRatio(darkScheme.onPrimary, darkScheme.primary) >= 4.5,
            )
            assertTrue(
                "$name: light onPrimary vs primary >= 4.5",
                contrastRatio(lightScheme.onPrimary, lightScheme.primary) >= 4.5,
            )
            assertTrue(
                "$name: light onSurfaceVariant vs background >= 8.5",
                contrastRatio(lightScheme.onSurfaceVariant, lightScheme.background) >= 8.5,
            )
            assertTrue(
                "$name: light onPrimaryContainer vs primaryContainer >= 8.5",
                contrastRatio(lightScheme.onPrimaryContainer, lightScheme.primaryContainer) >= 8.5,
            )
        }
    }

    @Test
    fun extractArtworkColors_syntheticGrayscaleBitmap_triggersZeroTintMonochromeSafeguard() {
        // Pure grayscale bitmap where R == G == B simulating album cover like AS I AM
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val gray = (x * 16).coerceIn(0, 255)
                bitmap.setPixel(x, y, android.graphics.Color.rgb(gray, gray, gray))
            }
        }

        val result =
            ArtworkColorExtractor.extractArtworkColors(
                bitmap = bitmap,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertFalse("isFallback must be false for valid grayscale image", result.isFallback)
        assertTrue("isMonochrome must be true for grayscale image", result.isMonochrome)

        val darkScheme = result.darkColorScheme
        val lightScheme = result.lightColorScheme

        // Check primary and background for zero red tint / low chroma
        val darkBgChroma = ArtworkColorExtractor.perceptualChroma(darkScheme.background)
        val darkPrimaryChroma = ArtworkColorExtractor.perceptualChroma(darkScheme.primary)
        assertTrue("Dark background perceptual Chroma must be < 8, got $darkBgChroma", darkBgChroma < 8f)
        assertTrue("Dark primary perceptual Chroma must be < 8, got $darkPrimaryChroma", darkPrimaryChroma < 8f)

        // Zero red tint: red channel should not dominate (the old bug was Hue 0° high saturation wine red #8F2D2D)
        val darkPrimaryHsl = ArtworkColorExtractor.colorToHsl(darkScheme.primary)
        val darkBgHsl = ArtworkColorExtractor.colorToHsl(darkScheme.background)
        assertFalse(
            "Primary must not have red hue with high saturation",
            darkPrimaryHsl[1] > 0.30f && (darkPrimaryHsl[0] < 25f || darkPrimaryHsl[0] > 340f),
        )
        assertFalse(
            "Background must not have red hue with high saturation",
            darkBgHsl[1] > 0.30f && (darkBgHsl[0] < 25f || darkBgHsl[0] > 340f),
        )

        // Dark theme: Background Tone 8 (#121318), Surface Tone 12 (#1E1F25), Primary Tone 85 (#E2E2E9), OnPrimary Tone 10 (#191C20)
        assertEquals(Color(0xFF121318), darkScheme.background)
        assertEquals(Color(0xFF1E1F25), darkScheme.surface)
        assertEquals(Color(0xFFE2E2E9), darkScheme.primary)
        assertEquals(Color(0xFF191C20), darkScheme.onPrimary)

        // Light theme: Background Tone 98 (#FFFFFF), Surface Tone 94 (#E2E2E9), Primary Tone 20 (#1E293B), OnPrimary Tone 100 (#FFFFFF)
        assertEquals(Color(0xFFFFFFFF), lightScheme.background)
        assertEquals(Color(0xFFE2E2E9), lightScheme.surface)
        assertEquals(Color(0xFF1E293B), lightScheme.primary)
        assertEquals(Color(0xFFFFFFFF), lightScheme.onPrimary)

        // WCAG assertions
        assertTrue("Dark onPrimary vs primary >= 4.5", contrastRatio(darkScheme.onPrimary, darkScheme.primary) >= 4.5)
        assertTrue("Light onPrimary vs primary >= 4.5", contrastRatio(lightScheme.onPrimary, lightScheme.primary) >= 4.5)
        assertTrue(
            "Light onSurfaceVariant vs background >= 8.5",
            contrastRatio(lightScheme.onSurfaceVariant, lightScheme.background) >= 8.5,
        )
        assertTrue(
            "Light onPrimaryContainer vs primaryContainer >= 8.5",
            contrastRatio(lightScheme.onPrimaryContainer, lightScheme.primaryContainer) >= 8.5,
        )
    }

    @Test
    fun wcagContrastRatios_satisfiedAcrossAllDerivedColorSchemes() {
        val testBitmaps =
            listOf(
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.RED) },
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.BLUE) },
                Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(android.graphics.Color.rgb(100, 100, 100))
                },
            )

        for (bitmap in testBitmaps) {
            val result =
                ArtworkColorExtractor.extractArtworkColors(
                    bitmap = bitmap,
                    defaultSurfaceColor = defaultSurface,
                    defaultBackgroundColor = defaultBackground,
                )

            for (scheme in listOf(result.darkColorScheme, result.lightColorScheme)) {
                val onPrimaryContrast = contrastRatio(scheme.onPrimary, scheme.primary)
                val onPrimaryContainerContrast = contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)

                assertTrue("onPrimary vs primary >= 4.5:1, got $onPrimaryContrast", onPrimaryContrast >= 4.5)
                assertTrue(
                    "onPrimaryContainer vs primaryContainer >= 4.5:1, got $onPrimaryContainerContrast",
                    onPrimaryContainerContrast >= 4.5,
                )
            }

            // Light scheme specific WCAG AAA requirements
            val lightScheme = result.lightColorScheme
            val onSurfaceVariantBgContrast = contrastRatio(lightScheme.onSurfaceVariant, lightScheme.background)
            val onSurfaceVariantContainerContrast = contrastRatio(lightScheme.onSurfaceVariant, lightScheme.surfaceContainer)
            assertTrue(
                "onSurfaceVariant vs background >= 8.5:1 (WCAG AAA), got $onSurfaceVariantBgContrast",
                onSurfaceVariantBgContrast >= 8.5,
            )
            assertTrue(
                "onSurfaceVariant vs surfaceContainer >= 8.5:1 (WCAG AAA), got $onSurfaceVariantContainerContrast",
                onSurfaceVariantContainerContrast >= 8.5,
            )
        }
    }

    @Test
    fun deriveDarkColorScheme_derivesCompleteTonalPaletteFromDominantColors() {
        val testSeeds =
            listOf(
                Color(0xFF8A2BE2), // Violet
                Color(0xFF1E90FF), // Dodger Blue
                Color(0xFF2E8B57), // Sea Green
                Color(0xFFDC143C), // Crimson
            )

        for (seed in testSeeds) {
            val scheme = ArtworkColorExtractor.deriveDarkColorScheme(seed)

            // Background & Surface should be dark tones (Tone 6~8)
            val bgLum = relativeLuminance(scheme.background)
            val surfaceLum = relativeLuminance(scheme.surface)
            assertTrue("Dark background luminance must be deep dark, got $bgLum", bgLum < 0.05)
            assertTrue("Dark surface luminance must be deep dark, got $surfaceLum", surfaceLum < 0.05)

            // Surface hierarchy in dark mode: lowest <= low <= container <= high <= highest
            val lumLowest = relativeLuminance(scheme.surfaceContainerLowest)
            val lumLow = relativeLuminance(scheme.surfaceContainerLow)
            val lumContainer = relativeLuminance(scheme.surfaceContainer)
            val lumHigh = relativeLuminance(scheme.surfaceContainerHigh)
            val lumHighest = relativeLuminance(scheme.surfaceContainerHighest)

            assertTrue("Lowest <= Low", lumLowest <= lumLow + 0.001)
            assertTrue("Low <= Container", lumLow <= lumContainer + 0.001)
            assertTrue("Container <= High", lumContainer <= lumHigh + 0.001)
            assertTrue("High <= Highest", lumHigh <= lumHighest + 0.001)

            // High contrast text & primary
            val bgContrast = contrastRatio(scheme.onBackground, scheme.background)
            val surfaceContrast = contrastRatio(scheme.onSurface, scheme.surface)
            val primaryContrast = contrastRatio(scheme.onPrimary, scheme.primary)

            assertTrue("onBackground vs background must be >= 7:1, got $bgContrast", bgContrast >= 7.0)
            assertTrue("onSurface vs surface must be >= 7:1, got $surfaceContrast", surfaceContrast >= 7.0)
            assertTrue("onPrimary vs primary must be >= 4.5:1, got $primaryContrast", primaryContrast >= 4.5)
        }
    }

    @Test
    fun deriveLightColorScheme_derivesCompleteTonalPaletteWithWCAGAAASpecifications() {
        val testSeeds =
            listOf(
                Color(0xFF8A2BE2), // Violet -> pastel #F7F3FB
                Color(0xFFFF8C00), // Orange -> sunset peach #FFF5F0
                Color(0xFF00BFFF), // Blue -> ice sea morning blue #F0F6FA
                Color(0xFF228B22), // Green -> pastoral grass green #F0F8F2
            )

        for (seed in testSeeds) {
            val scheme = ArtworkColorExtractor.deriveLightColorScheme(seed)

            // Background & Surface should be light soft pastel tones (Tone 96~98)
            val bgLum = relativeLuminance(scheme.background)
            val surfaceLum = relativeLuminance(scheme.surface)
            assertTrue("Light background luminance must be ambient pastel >= 0.85, got $bgLum", bgLum >= 0.85)
            assertTrue("Light surface luminance must be ambient pastel >= 0.85, got $surfaceLum", surfaceLum >= 0.85)

            // Surface hierarchy in light mode: lowest (pure white) >= low >= container >= high >= highest
            val lumLowest = relativeLuminance(scheme.surfaceContainerLowest)
            val lumLow = relativeLuminance(scheme.surfaceContainerLow)
            val lumContainer = relativeLuminance(scheme.surfaceContainer)
            val lumHigh = relativeLuminance(scheme.surfaceContainerHigh)
            val lumHighest = relativeLuminance(scheme.surfaceContainerHighest)

            assertEquals("SurfaceContainerLowest must be pure white", Color.White, scheme.surfaceContainerLowest)
            assertTrue("Lowest >= Low in light mode", lumLowest >= lumLow - 0.001)
            assertTrue("Low >= Container in light mode", lumLow >= lumContainer - 0.001)
            assertTrue("Container >= High in light mode", lumContainer >= lumHigh - 0.001)
            assertTrue("High >= Highest in light mode", lumHigh >= lumHighest - 0.001)

            // Secondary text MUST be dark slate charcoal #3A3546
            assertEquals(Color(0xFF3A3546), scheme.onSurfaceVariant)

            // Secondary text contrast against background & surfaceContainer must exceed 8.5:1 (WCAG AAA)
            val secTextBgContrast = contrastRatio(scheme.onSurfaceVariant, scheme.background)
            val secTextContainerContrast = contrastRatio(scheme.onSurfaceVariant, scheme.surfaceContainer)
            assertTrue(
                "Secondary text vs background contrast must reach WCAG AAA (>= 8.5:1), got $secTextBgContrast",
                secTextBgContrast >= 8.5,
            )
            assertTrue(
                "Secondary text vs surfaceContainer contrast must reach WCAG AAA (>= 8.5:1), got $secTextContainerContrast",
                secTextContainerContrast >= 8.5,
            )

            // Pills: onPrimaryContainer vs primaryContainer must exceed 8.5:1 (WCAG AAA)
            val pillContrast = contrastRatio(scheme.onPrimaryContainer, scheme.primaryContainer)
            assertTrue(
                "Pill contrast (onPrimaryContainer vs primaryContainer) must reach WCAG AAA (>= 8.5:1), got $pillContrast",
                pillContrast >= 8.5,
            )

            // Primary text contrast
            val bgContrast = contrastRatio(scheme.onBackground, scheme.background)
            val surfaceContrast = contrastRatio(scheme.onSurface, scheme.surface)
            val primaryContrast = contrastRatio(scheme.onPrimary, scheme.primary)

            assertTrue("onBackground vs background must be >= 7:1, got $bgContrast", bgContrast >= 7.0)
            assertTrue("onSurface vs surface must be >= 7:1, got $surfaceContrast", surfaceContrast >= 7.0)
            assertTrue("onPrimary vs primary must be >= 4.5:1, got $primaryContrast", primaryContrast >= 4.5)
        }
    }

    @Test
    fun extractArtworkColors_fromBitmap_providesDynamicDarkAndLightColorSchemes() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)

        val result =
            ArtworkColorExtractor.extractArtworkColors(
                bitmap = bitmap,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertFalse(result.isFallback)
        val darkScheme = result.darkColorScheme
        val lightScheme = result.lightColorScheme

        assertNotNull(darkScheme)
        assertNotNull(lightScheme)

        // Light scheme onSurfaceVariant must be dark slate charcoal #3A3546
        assertEquals(Color(0xFF3A3546), lightScheme.onSurfaceVariant)

        // Pill contrast reaches WCAG AAA (8.5:1)
        val pillContrast = contrastRatio(lightScheme.onPrimaryContainer, lightScheme.primaryContainer)
        assertTrue("Pill contrast >= 8.5:1, got $pillContrast", pillContrast >= 8.5)

        // Secondary text contrast reaches WCAG AAA (8.5:1)
        val secTextContrast = contrastRatio(lightScheme.onSurfaceVariant, lightScheme.background)
        assertTrue("Secondary text contrast >= 8.5:1, got $secTextContrast", secTextContrast >= 8.5)
    }

    @Test
    fun computeAmbientGlowStops_forChromaticArtwork_generatesMultiStopAtmosphericTones() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.BLUE)

        val artworkColors =
            ArtworkColorExtractor.extractArtworkColors(
                bitmap = bitmap,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertFalse(artworkColors.isMonochrome)

        // Dark theme multi-stop
        val darkStops = ArtworkColorExtractor.computeAmbientGlowStops(artworkColors, isDarkTheme = true)
        assertNotNull(darkStops.topColor)
        assertNotNull(darkStops.midColor)
        assertNotNull(darkStops.bottomColor)

        // Top ambient stop should have blue hue component
        val topHsl = ArtworkColorExtractor.colorToHsl(darkStops.topColor)
        assertTrue("Top stop hue should be in blue family (180..260), got ${topHsl[0]}", topHsl[0] in 180f..260f)
        assertTrue("Top stop should have non-zero saturation, got ${topHsl[1]}", topHsl[1] > 0.15f)

        // Bottom stop should melt into deep background tone
        val bottomLum = ArtworkColorExtractor.relativeLuminance(darkStops.bottomColor)
        assertTrue("Bottom stop luminance must be deep dark (< 0.08), got $bottomLum", bottomLum < 0.08)

        // Ambient brush can be created without crashing
        val darkBrush = artworkColors.ambientGlowBrush(isDarkTheme = true)
        assertNotNull(darkBrush)

        // Light theme multi-stop
        val lightStops = ArtworkColorExtractor.computeAmbientGlowStops(artworkColors, isDarkTheme = false)
        assertNotNull(lightStops.topColor)
        assertNotNull(lightStops.midColor)
        assertNotNull(lightStops.bottomColor)

        val lightBrush = artworkColors.ambientGlowBrush(isDarkTheme = false)
        assertNotNull(lightBrush)
    }

    @Test
    fun computeAmbientGlowStops_forMonochromeArtwork_producesZeroRedTintNeutralSmoke() {
        // Pure grayscale bitmap where R == G == B simulating album cover like AS I AM
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        for (y in 0 until 16) {
            for (x in 0 until 16) {
                val gray = (x * 16).coerceIn(0, 255)
                bitmap.setPixel(x, y, android.graphics.Color.rgb(gray, gray, gray))
            }
        }

        val artworkColors =
            ArtworkColorExtractor.extractArtworkColors(
                bitmap = bitmap,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
            )

        assertTrue(artworkColors.isMonochrome)

        // Dark theme neutral smoke tones
        val darkStops = ArtworkColorExtractor.computeAmbientGlowStops(artworkColors, isDarkTheme = true)
        val topDark = darkStops.topColor
        val midDark = darkStops.midColor
        val bottomDark = darkStops.bottomColor

        // Perceptual Chroma of all stops must be strictly neutral (< 8.0)
        val topChroma = ArtworkColorExtractor.perceptualChroma(topDark)
        val midChroma = ArtworkColorExtractor.perceptualChroma(midDark)
        val bottomChroma = ArtworkColorExtractor.perceptualChroma(bottomDark)

        assertTrue("Dark top stop Chroma must be < 8, got $topChroma", topChroma < 8f)
        assertTrue("Dark mid stop Chroma must be < 8, got $midChroma", midChroma < 8f)
        assertTrue("Dark bottom stop Chroma must be < 8, got $bottomChroma", bottomChroma < 8f)

        // Zero red hue bias check: red channel must not dominate and saturation must remain neutral
        val topHsl = ArtworkColorExtractor.colorToHsl(topDark)
        assertFalse(
            "Monochrome top stop must never have high saturation red tint",
            topHsl[1] > 0.25f && (topHsl[0] < 30f || topHsl[0] > 330f),
        )

        // Light theme neutral pearl/silver smoke tones
        val lightStops = ArtworkColorExtractor.computeAmbientGlowStops(artworkColors, isDarkTheme = false)
        val topLightChroma = ArtworkColorExtractor.perceptualChroma(lightStops.topColor)
        val midLightChroma = ArtworkColorExtractor.perceptualChroma(lightStops.midColor)
        val bottomLightChroma = ArtworkColorExtractor.perceptualChroma(lightStops.bottomColor)

        assertTrue("Light top stop Chroma must be < 8, got $topLightChroma", topLightChroma < 8f)
        assertTrue("Light mid stop Chroma must be < 8, got $midLightChroma", midLightChroma < 8f)
        assertTrue("Light bottom stop Chroma must be < 8, got $bottomLightChroma", bottomLightChroma < 8f)

        // Verify gradient brushes construct without crash
        assertNotNull(artworkColors.ambientGlowBrush(isDarkTheme = true))
        assertNotNull(artworkColors.ambientGlowBrush(isDarkTheme = false))
    }

    @Test
    fun computeAmbientGlowStops_forFallback_producesNeutralMonochromeSmoke() {
        val fallback = ArtworkColorExtractor.createFallbackColors(defaultSurface, defaultBackground)
        assertTrue(fallback.isFallback)
        assertTrue(fallback.isMonochrome)

        val stops = ArtworkColorExtractor.computeAmbientGlowStops(fallback, isDarkTheme = true)
        val topChroma = ArtworkColorExtractor.perceptualChroma(stops.topColor)
        val midChroma = ArtworkColorExtractor.perceptualChroma(stops.midColor)

        assertTrue("Fallback top Chroma must be < 8, got $topChroma", topChroma < 8f)
        assertTrue("Fallback mid Chroma must be < 8, got $midChroma", midChroma < 8f)
        assertNotNull(fallback.ambientGlowBrush(isDarkTheme = true))
    }

    @Test
    fun extractArtworkColors_cachesResultInMemory_andInvalidatesWhenFileDeleted() {
        ArtworkColorExtractor.clearCache()
        val imageFile = tempFolder.newFile("cached_cover.png")
        val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.CYAN)
        FileOutputStream(imageFile).use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }

        val first =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = imageFile.absolutePath,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
                isDarkTheme = true,
            )
        assertFalse(first.isFallback)

        val cached =
            ArtworkColorExtractor.getCachedArtworkColors(
                thumbnailPath = imageFile.absolutePath,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
                isDarkTheme = true,
            )
        assertNotNull("Extracted artwork colors must be stored in memory cache", cached)
        assertEquals(first, cached)

        // Delete physical file: subsequent extraction must evict stale entry and return fallback
        assertTrue(imageFile.delete())
        val afterDelete =
            ArtworkColorExtractor.extractArtworkColors(
                thumbnailPath = imageFile.absolutePath,
                defaultSurfaceColor = defaultSurface,
                defaultBackgroundColor = defaultBackground,
                isDarkTheme = true,
            )
        assertTrue(afterDelete.isFallback)
    }
}
