package com.webdav.player.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RadialGradientShader
import androidx.compose.ui.graphics.Shader
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.compositeOver
import androidx.palette.graphics.Palette
import com.webdav.player.ui.theme.BackgroundDark
import com.webdav.player.ui.theme.BackgroundLight
import com.webdav.player.ui.theme.ExpressiveDarkColorScheme
import com.webdav.player.ui.theme.ExpressiveLightColorScheme
import com.webdav.player.ui.theme.SurfaceContainerDark
import com.webdav.player.ui.theme.SurfaceContainerLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Multi-stop ambient glow colors blending vibrant, dominant, and muted tones into background.
 */
data class AmbientGlowStops(
    val topColor: Color,
    val midColor: Color,
    val bottomColor: Color,
)

/**
 * Atmospheric color scheme dynamically derived from track artwork or fallback colors.
 */
data class ArtworkColors(
    val dominantColor: Color,
    val backgroundTopColor: Color,
    val backgroundBottomColor: Color,
    val accentColor: Color,
    val isFallback: Boolean = false,
    val isMonochrome: Boolean = false,
    val vibrantColor: Color = dominantColor,
    val mutedColor: Color = dominantColor,
    val darkColorScheme: ColorScheme = ArtworkColorExtractor.createDarkColorScheme(dominantColor, isFallback, isMonochrome),
    val lightColorScheme: ColorScheme = ArtworkColorExtractor.createLightColorScheme(dominantColor, isFallback, isMonochrome),
) {
    fun colorScheme(isDark: Boolean): ColorScheme = if (isDark) darkColorScheme else lightColorScheme

    fun ambientGlowBrush(
        isDarkTheme: Boolean = true,
        surfaceColor: Color = if (isDarkTheme) backgroundTopColor else Color(0xFFE2E2E9),
        backgroundColor: Color = backgroundBottomColor,
    ): Brush {
        val stops =
            ArtworkColorExtractor.computeAmbientGlowStops(
                artworkColors = this,
                isDarkTheme = isDarkTheme,
                surfaceColor = surfaceColor,
                backgroundColor = backgroundColor,
            )
        return object : ShaderBrush() {
            override fun createShader(size: Size): Shader {
                val center = Offset(size.width * 0.5f, size.height * 0.26f)
                val radius = maxOf(size.width, size.height) * 0.85f
                return RadialGradientShader(
                    colors = listOf(stops.topColor, stops.midColor, stops.bottomColor),
                    colorStops = listOf(0.0f, 0.48f, 1.0f),
                    center = center,
                    radius = radius.coerceAtLeast(1f),
                    tileMode = TileMode.Clamp,
                )
            }
        }
    }
}

object ArtworkColorExtractor {
    private const val MAX_COLOR_CACHE_ENTRIES = 64
    private val colorCache =
        object : LinkedHashMap<String, ArtworkColors>(MAX_COLOR_CACHE_ENTRIES, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ArtworkColors>?): Boolean =
                size > MAX_COLOR_CACHE_ENTRIES
        }

    private fun buildCacheKey(
        thumbnailPath: String,
        defaultSurfaceColor: Color,
        defaultBackgroundColor: Color,
        isDarkTheme: Boolean,
    ): String = "$thumbnailPath:${defaultSurfaceColor.value}:${defaultBackgroundColor.value}:$isDarkTheme"

    fun getCachedArtworkColors(
        thumbnailPath: String?,
        defaultSurfaceColor: Color = SurfaceContainerDark,
        defaultBackgroundColor: Color = BackgroundDark,
        isDarkTheme: Boolean = true,
    ): ArtworkColors? {
        if (thumbnailPath.isNullOrBlank()) return null
        val key = buildCacheKey(thumbnailPath, defaultSurfaceColor, defaultBackgroundColor, isDarkTheme)
        return synchronized(colorCache) {
            colorCache[key]
        }
    }

    fun clearCache() {
        synchronized(colorCache) {
            colorCache.clear()
        }
    }

    /**
     * Converts a Compose [Color] into HSL components:
     * - Hue in [0f, 360f)
     * - Saturation in [0f, 1f]
     * - Lightness in [0f, 1f]
     */
    fun colorToHsl(color: Color): FloatArray {
        val r = color.red
        val g = color.green
        val b = color.blue

        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val delta = max - min

        var h = 0f
        var s = 0f
        val l = (max + min) / 2f

        if (delta > 1e-5f) {
            s = if (l > 0.5f) delta / (2f - max - min) else delta / (max + min)
            h =
                when (max) {
                    r -> ((g - b) / delta + (if (g < b) 6f else 0f)) * 60f
                    g -> ((b - r) / delta + 2f) * 60f
                    else -> ((r - g) / delta + 4f) * 60f
                }
        }
        return floatArrayOf((h % 360f + 360f) % 360f, s.coerceIn(0f, 1f), l.coerceIn(0f, 1f))
    }

    /**
     * Reconstructs a Compose [Color] from HSL components.
     */
    fun hslToColor(
        h: Float,
        s: Float,
        l: Float,
        alpha: Float = 1f,
    ): Color {
        val normalizedH = (h % 360f + 360f) % 360f
        val clampedS = s.coerceIn(0f, 1f)
        val clampedL = l.coerceIn(0f, 1f)

        val c = (1f - abs(2f * clampedL - 1f)) * clampedS
        val x = c * (1f - abs((normalizedH / 60f) % 2f - 1f))
        val m = clampedL - c / 2f

        val (rPrime, gPrime, bPrime) =
            when {
                normalizedH < 60f -> Triple(c, x, 0f)
                normalizedH < 120f -> Triple(x, c, 0f)
                normalizedH < 180f -> Triple(0f, c, x)
                normalizedH < 240f -> Triple(0f, x, c)
                normalizedH < 300f -> Triple(x, 0f, c)
                else -> Triple(c, 0f, x)
            }

        return Color(
            red = (rPrime + m).coerceIn(0f, 1f),
            green = (gPrime + m).coerceIn(0f, 1f),
            blue = (bPrime + m).coerceIn(0f, 1f),
            alpha = alpha,
        )
    }

    /**
     * Computes the perceptual Chroma (C*) in CIE L*a*b* color space for the given [Color].
     */
    fun perceptualChroma(color: Color): Float {
        fun pivot(n: Double): Double = if (n <= 0.04045) n / 12.92 else ((n + 0.055) / 1.055).pow(2.4)

        val rLin = pivot(color.red.toDouble())
        val gLin = pivot(color.green.toDouble())
        val bLin = pivot(color.blue.toDouble())

        val x = (0.4124 * rLin + 0.3576 * gLin + 0.1805 * bLin) / 0.95047
        val y = (0.2126 * rLin + 0.7152 * gLin + 0.0722 * bLin) / 1.00000
        val z = (0.0193 * rLin + 0.1192 * gLin + 0.9505 * bLin) / 1.08883

        fun f(t: Double): Double = if (t > 0.008856) t.pow(1.0 / 3.0) else (7.787 * t) + (16.0 / 116.0)

        val fx = f(x)
        val fy = f(y)
        val fz = f(z)

        val a = 500.0 * (fx - fy)
        val b = 200.0 * (fy - fz)
        return sqrt(a * a + b * b).toFloat()
    }

    /**
     * Checks if a [Color] is neutral monochrome (saturation < 0.12 or perceptual Chroma < 8).
     */
    fun isMonochrome(color: Color): Boolean {
        val hsl = colorToHsl(color)
        if (hsl[1] < 0.12f) return true
        return perceptualChroma(color) < 8f
    }

    /**
     * Clean neutral Material 3 monochrome dark color scheme (Tone 8 background, Tone 12 surface, Tone 85 primary).
     * Has zero hue bias (zero red tint).
     */
    val MonochromeDarkColorScheme: ColorScheme = ExpressiveDarkColorScheme

    /**
     * Clean neutral Material 3 monochrome light color scheme (Tone 98 background, Tone 94 surface, Tone 20 primary).
     * Has zero hue bias (zero red tint).
     */
    val MonochromeLightColorScheme: ColorScheme = ExpressiveLightColorScheme

    /**
     * Derives a complete dark [ColorScheme] from the dominant seed color.
     * Produces deep dark immersive background (Tone 6~8, Chroma <= 12), dark container (Tone 12~14),
     * and vibrant primary (Tone 80) with contrast >= 4.5:1.
     */
    fun deriveDarkColorScheme(
        seedColor: Color,
        dominantColor: Color = seedColor,
    ): ColorScheme {
        val seedHsl = colorToHsl(seedColor)
        val seedH = seedHsl[0]
        val seedS = seedHsl[1]

        val domHsl = colorToHsl(dominantColor)
        val domH = domHsl[0]
        val domS = domHsl[1]

        // Tone 6~8 deep dark immersive background & surface (Chroma <= 12, Saturation <= 0.12)
        val background = hslToColor(domH, minOf(0.12f, domS * 0.28f), 0.07f)
        val surface = background
        val surfaceContainerLowest = hslToColor(domH, minOf(0.10f, domS * 0.25f), 0.04f)
        val surfaceContainerLow = hslToColor(domH, minOf(0.12f, domS * 0.25f), 0.09f)
        val surfaceContainer = hslToColor(domH, minOf(0.12f, domS * 0.25f), 0.13f)
        val surfaceContainerHigh = hslToColor(domH, minOf(0.14f, domS * 0.25f), 0.17f)
        val surfaceContainerHighest = hslToColor(domH, minOf(0.14f, domS * 0.25f), 0.22f)
        val surfaceVariant = hslToColor(domH, minOf(0.15f, domS * 0.20f), 0.26f)

        // Tone 80 primary - ensure contrast with onPrimary >= 4.5:1
        var primaryL = 0.80f
        var primary = hslToColor(seedH, maxOf(0.40f, seedS * 0.85f), primaryL)
        var onPrimaryL = 0.20f
        var onPrimary = hslToColor(seedH, seedS * 0.90f, onPrimaryL)
        while (contrastRatio(onPrimary, primary) < 4.5 && (primaryL < 0.92f || onPrimaryL > 0.08f)) {
            if (primaryL < 0.92f) primaryL += 0.02f
            if (onPrimaryL > 0.08f) onPrimaryL -= 0.02f
            primary = hslToColor(seedH, maxOf(0.40f, seedS * 0.85f), primaryL)
            onPrimary = hslToColor(seedH, seedS * 0.90f, onPrimaryL)
        }

        // PrimaryContainer (Tone 30) and onPrimaryContainer (Tone 90), contrast >= 4.5:1
        var pContainerL = 0.30f
        var primaryContainer = hslToColor(seedH, seedS * 0.70f, pContainerL)
        var onPContainerL = 0.90f
        var onPrimaryContainer = hslToColor(seedH, seedS * 0.30f, onPContainerL)
        while (contrastRatio(onPrimaryContainer, primaryContainer) < 4.5 && (onPContainerL < 0.98f || pContainerL > 0.15f)) {
            if (onPContainerL < 0.98f) onPContainerL += 0.02f
            if (pContainerL > 0.15f) pContainerL -= 0.02f
            primaryContainer = hslToColor(seedH, seedS * 0.70f, pContainerL)
            onPrimaryContainer = hslToColor(seedH, seedS * 0.30f, onPContainerL)
        }

        val secondary = hslToColor(domH, domS * 0.50f, 0.80f)
        val onSecondary = hslToColor(domH, domS * 0.50f, 0.20f)
        val secondaryContainer = hslToColor(domH, domS * 0.40f, 0.30f)
        val onSecondaryContainer = hslToColor(domH, domS * 0.20f, 0.90f)

        val inverseSurface = hslToColor(domH, maxOf(0.08f, domS * 0.22f), 0.90f)
        val inverseOnSurface = Color(0xFF1A1B21)
        val inversePrimary = hslToColor(seedH, maxOf(0.50f, seedS * 0.95f), 0.40f)

        return ExpressiveDarkColorScheme.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            secondary = secondary,
            onSecondary = onSecondary,
            secondaryContainer = secondaryContainer,
            onSecondaryContainer = onSecondaryContainer,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            surfaceTint = primary,
        )
    }

    /**
     * Derives a complete light [ColorScheme] from the dominant seed color.
     * Produces soft ambient pastel background (Tone 97~98, Chroma <= 8), delicate container (Tone 93~95),
     * fixed dark slate charcoal text (#1E293B / #3A3546, contrast >= 8.5:1 WCAG AAA),
     * and high-contrast pills (>= 8.5:1 WCAG AAA).
     */
    fun deriveLightColorScheme(
        seedColor: Color,
        dominantColor: Color = seedColor,
    ): ColorScheme {
        val seedHsl = colorToHsl(seedColor)
        val seedH = seedHsl[0]
        val seedS = seedHsl[1]

        val domHsl = colorToHsl(dominantColor)
        val domH = domHsl[0]
        val domS = domHsl[1]

        // Tone 97~98 delicate soft pastel ambient background (Chroma <= 8, Saturation <= 0.08)
        val background = hslToColor(domH, minOf(0.08f, domS * 0.20f), 0.975f)
        val surface = background
        val surfaceContainerLowest = Color.White
        val surfaceContainerLow = hslToColor(domH, minOf(0.08f, domS * 0.18f), 0.95f)
        val surfaceContainer = hslToColor(domH, minOf(0.08f, domS * 0.16f), 0.93f)
        val surfaceContainerHigh = hslToColor(domH, minOf(0.08f, domS * 0.16f), 0.90f)
        val surfaceContainerHighest = hslToColor(domH, minOf(0.08f, domS * 0.16f), 0.86f)
        val surfaceVariant = hslToColor(domH, minOf(0.08f, domS * 0.14f), 0.90f)

        // Tone 40 primary - ensure contrast with onPrimary (White) >= 4.5:1
        var primaryL = 0.40f
        var primary = hslToColor(seedH, maxOf(0.50f, seedS * 0.95f), primaryL)
        while (contrastRatio(Color.White, primary) < 4.5 && primaryL > 0.15f) {
            primaryL -= 0.02f
            primary = hslToColor(seedH, maxOf(0.50f, seedS * 0.95f), primaryL)
        }
        val onPrimary = Color.White

        // Pills: primaryContainer (Tone 92) + onPrimaryContainer (Tone 8~10) reaching WCAG AAA (>= 8.5:1)
        val primaryContainer = hslToColor(seedH, minOf(0.35f, seedS * 0.45f), 0.92f)
        var textL = 0.08f
        var onPrimaryContainer = hslToColor(seedH, minOf(0.60f, seedS * 0.70f), textL)
        while (contrastRatio(onPrimaryContainer, primaryContainer) < 8.5 && textL > 0.02f) {
            textL -= 0.01f
            onPrimaryContainer = hslToColor(seedH, minOf(0.60f, seedS * 0.70f), textL)
        }

        val inverseSurface = hslToColor(domH, domS * 0.25f, 0.20f)
        val inverseOnSurface = Color(0xFFE4E1E8)
        val inversePrimary = hslToColor(seedH, maxOf(0.40f, seedS * 0.85f), 0.80f)

        return ExpressiveLightColorScheme.copy(
            primary = primary,
            onPrimary = onPrimary,
            primaryContainer = primaryContainer,
            onPrimaryContainer = onPrimaryContainer,
            inversePrimary = inversePrimary,
            background = background,
            surface = surface,
            surfaceVariant = surfaceVariant,
            // Main text is dark slate charcoal (#1E293B) reaching WCAG AAA (> 8.5:1)
            onBackground = Color(0xFF1E293B),
            onSurface = Color(0xFF1E293B),
            // Secondary text is dark slate charcoal (#3A3546) reaching WCAG AAA (> 8.5:1)
            onSurfaceVariant = Color(0xFF3A3546),
            surfaceContainerLowest = surfaceContainerLowest,
            surfaceContainerLow = surfaceContainerLow,
            surfaceContainer = surfaceContainer,
            surfaceContainerHigh = surfaceContainerHigh,
            surfaceContainerHighest = surfaceContainerHighest,
            inverseSurface = inverseSurface,
            inverseOnSurface = inverseOnSurface,
            surfaceTint = primary,
        )
    }

    private fun channelLuminance(channel: Float): Double =
        if (channel <= 0.03928) {
            channel / 12.92
        } else {
            ((channel + 0.055) / 1.055).toDouble().pow(2.4)
        }

    fun relativeLuminance(color: Color): Double {
        val r = channelLuminance(color.red)
        val g = channelLuminance(color.green)
        val b = channelLuminance(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }

    fun contrastRatio(
        colorA: Color,
        colorB: Color,
    ): Double {
        val l1 = relativeLuminance(colorA)
        val l2 = relativeLuminance(colorB)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    /**
     * Creates dark [ColorScheme] respecting fallback and monochrome flags.
     */
    fun createDarkColorScheme(
        dominantColor: Color,
        isFallback: Boolean = false,
        isMonochrome: Boolean = false,
    ): ColorScheme =
        when {
            isFallback || isMonochrome -> MonochromeDarkColorScheme
            else -> deriveDarkColorScheme(dominantColor)
        }

    /**
     * Creates light [ColorScheme] respecting fallback and monochrome flags.
     */
    fun createLightColorScheme(
        dominantColor: Color,
        isFallback: Boolean = false,
        isMonochrome: Boolean = false,
    ): ColorScheme =
        when {
            isFallback || isMonochrome -> MonochromeLightColorScheme
            else -> deriveLightColorScheme(dominantColor)
        }

    /**
     * Computes multi-stop ambient glow colors blending vibrantColor, dominantColor, and mutedColor.
     * Implements universal Perceptual Tonal Normalization:
     * - Top Stop: radiant luminous source normalized to Tone 60~72 with authentic hue.
     * - Mid Stop: ambient dispersion layer normalized to Tone 32~45.
     * - Bottom Stop: deep obsidian melt (Tone 8~9) tinted with the artwork's dominant hue.
     * - When isMonochrome is true: ensures clean neutral slate/graphite dark glow with zero red tint.
     */
    fun computeAmbientGlowStops(
        artworkColors: ArtworkColors,
        isDarkTheme: Boolean = true,
        surfaceColor: Color = if (isDarkTheme) artworkColors.backgroundTopColor else Color(0xFFE2E2E9),
        backgroundColor: Color = artworkColors.backgroundBottomColor,
    ): AmbientGlowStops {
        val topAlpha = if (isDarkTheme) 0.62f else 0.28f
        val midAlpha = if (isDarkTheme) 0.36f else 0.12f

        if (artworkColors.isMonochrome) {
            val neutralSurface = if (isDarkTheme) Color(0xFF1E1F25) else Color(0xFFE2E2E9)
            val topStop = artworkColors.mutedColor.copy(alpha = topAlpha).compositeOver(neutralSurface)
            val midStop = artworkColors.mutedColor.copy(alpha = midAlpha).compositeOver(neutralSurface)
            val bottomStop = if (isDarkTheme) Color(0xFF121318) else Color(0xFFFFFFFF)
            return AmbientGlowStops(
                topColor = topStop,
                midColor = midStop,
                bottomColor = bottomStop,
            )
        }

        // Universal Chromatic Perceptual Tonal Normalization:
        val vibrantHsl = colorToHsl(artworkColors.vibrantColor)
        val mutedHsl = colorToHsl(artworkColors.mutedColor)
        val domHsl = colorToHsl(artworkColors.dominantColor)

        // 1. Radiant luminous source (Top Stop): Hue preserved, Lightness normalized to Tone 60~72
        val radiantLightness = vibrantHsl[2].coerceIn(0.58f, 0.72f)
        val radiantSaturation = maxOf(0.50f, vibrantHsl[1])
        val radiantTopColor = hslToColor(vibrantHsl[0], radiantSaturation, radiantLightness)

        // 2. Mid ambient dispersion: Hue preserved, Lightness normalized to Tone 32~45
        val midLightness = mutedHsl[2].coerceIn(0.32f, 0.45f)
        val midSaturation = minOf(0.50f, mutedHsl[1])
        val midDiffuseColor = hslToColor(mutedHsl[0], midSaturation, midLightness)

        // 3. Deep tone surface base with matching hue tint (Tone 10~11)
        val baseSurfaceTone =
            if (isDarkTheme) {
                hslToColor(domHsl[0], minOf(0.30f, domHsl[1] * 0.40f), 0.11f)
            } else {
                surfaceColor
            }

        val topStop = radiantTopColor.copy(alpha = topAlpha).compositeOver(baseSurfaceTone)
        val midStop = midDiffuseColor.copy(alpha = midAlpha).compositeOver(baseSurfaceTone)
        val bottomStop =
            if (isDarkTheme) {
                hslToColor(domHsl[0], minOf(0.25f, domHsl[1] * 0.35f), 0.085f)
            } else {
                backgroundColor
            }

        return AmbientGlowStops(
            topColor = topStop,
            midColor = midStop,
            bottomColor = bottomStop,
        )
    }

    /**
     * Extracts dynamic artwork colors from a cached thumbnail file path.
     * Gracefully falls back to pure neutral MD3 monochrome if file is null, empty, or unreadable.
     */
    fun extractArtworkColors(
        thumbnailPath: String?,
        defaultSurfaceColor: Color = SurfaceContainerDark,
        defaultBackgroundColor: Color = BackgroundDark,
        isDarkTheme: Boolean = true,
        context: Context? = null,
    ): ArtworkColors {
        if (thumbnailPath.isNullOrBlank()) {
            return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
        }

        val cacheKey = buildCacheKey(thumbnailPath, defaultSurfaceColor, defaultBackgroundColor, isDarkTheme)
        if (!thumbnailPath.startsWith("content://")) {
            val cleanPath = if (thumbnailPath.startsWith("file://")) thumbnailPath.removePrefix("file://") else thumbnailPath
            val file = File(cleanPath)
            if (!file.exists() || file.length() == 0L) {
                synchronized(colorCache) { colorCache.remove(cacheKey) }
                return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
            }
        }
        synchronized(colorCache) {
            colorCache[cacheKey]?.let { return it }
        }

        return try {
            val bitmap =
                when {
                    thumbnailPath.startsWith("content://") && context != null -> {
                        context.contentResolver.openInputStream(android.net.Uri.parse(thumbnailPath))?.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        }
                    }

                    else -> {
                        val cleanPath =
                            when {
                                thumbnailPath.startsWith("file://") -> thumbnailPath.removePrefix("file://")
                                else -> thumbnailPath
                            }
                        val file = File(cleanPath)
                        if (!file.exists() || file.length() == 0L) {
                            return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
                        }

                        val options =
                            BitmapFactory.Options().apply {
                                inJustDecodeBounds = false
                                inPreferredConfig = Bitmap.Config.ARGB_8888
                            }
                        BitmapFactory.decodeFile(file.absolutePath, options)
                    }
                } ?: return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)

            val extracted =
                try {
                    extractArtworkColors(bitmap, defaultSurfaceColor, defaultBackgroundColor, isDarkTheme)
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            if (!extracted.isFallback) {
                synchronized(colorCache) {
                    colorCache[cacheKey] = extracted
                }
            }
            extracted
        } catch (_: Throwable) {
            createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
        }
    }

    private fun hasNonTransparentPixels(bitmap: Bitmap): Boolean {
        val stepX = maxOf(1, bitmap.width / 16)
        val stepY = maxOf(1, bitmap.height / 16)
        for (y in 0 until bitmap.height step stepY) {
            for (x in 0 until bitmap.width step stepX) {
                if (((bitmap.getPixel(x, y) ushr 24) and 0xFF) >= 32) {
                    return true
                }
            }
        }
        return false
    }

    /**
     * Extracts dynamic artwork colors from a Bitmap instance using androidx.palette.
     * Extracts Vibrant, Dominant, and Muted swatches.
     * Implements Zero-Tint Monochrome Safeguard: if saturation < 0.12 or chroma < 8,
     * outputs clean neutral monochrome slate/pearl tones without red hue bias.
     */
    fun extractArtworkColors(
        bitmap: Bitmap?,
        defaultSurfaceColor: Color = SurfaceContainerDark,
        defaultBackgroundColor: Color = BackgroundDark,
        isDarkTheme: Boolean = true,
    ): ArtworkColors {
        if (bitmap == null || bitmap.width <= 0 || bitmap.height <= 0 || !hasNonTransparentPixels(bitmap)) {
            return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
        }

        val palette =
            try {
                Palette
                    .from(bitmap)
                    .maximumColorCount(16)
                    .clearFilters()
                    .addFilter { rgb, _ -> ((rgb ushr 24) and 0xFF) >= 64 }
                    .generate()
            } catch (_: Throwable) {
                return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
            }

        if (palette.swatches.isEmpty()) {
            return createFallbackColors(defaultSurfaceColor, defaultBackgroundColor)
        }

        val dominantSwatch =
            palette.dominantSwatch
                ?: palette.swatches.maxByOrNull { it.population }
                ?: palette.swatches.first()

        val dominant = Color(dominantSwatch.rgb)

        val vibrantSwatch =
            when {
                palette.lightVibrantSwatch != null && (palette.vibrantSwatch == null || palette.vibrantSwatch!!.hsl[2] < 0.58f) -> {
                    palette.lightVibrantSwatch!!
                }

                palette.vibrantSwatch != null -> {
                    palette.vibrantSwatch!!
                }

                else -> {
                    palette.lightVibrantSwatch
                        ?: palette.dominantSwatch
                        ?: palette.mutedSwatch
                        ?: palette.lightMutedSwatch
                        ?: dominantSwatch
                }
            }

        val vibrant = Color(vibrantSwatch.rgb)

        val mutedSwatch =
            palette.mutedSwatch
                ?: palette.lightMutedSwatch
                ?: palette.darkMutedSwatch
                ?: palette.dominantSwatch
                ?: vibrantSwatch

        val muted = Color(mutedSwatch.rgb)

        // Zero-Tint Monochrome Safeguard:
        // If extracted swatches or sampled colors exhibit saturation < 0.12 or perceptual Chroma < 8
        val isMono =
            isMonochrome(dominant) &&
                (palette.vibrantSwatch == null || isMonochrome(Color(palette.vibrantSwatch!!.rgb))) &&
                palette.swatches.none { swatch ->
                    val c = Color(swatch.rgb)
                    val hsl = colorToHsl(c)
                    hsl[1] >= 0.12f && perceptualChroma(c) >= 8f && swatch.population > 5
                }

        if (isMono) {
            val bgTop = if (isDarkTheme) Color(0xFF1E1F25) else Color(0xFFE2E2E9)
            val bgBottom = if (isDarkTheme) Color(0xFF121318) else Color(0xFFFFFFFF)
            val accent = if (isDarkTheme) Color(0xFFE2E2E9) else Color(0xFF1E293B)
            return ArtworkColors(
                dominantColor = dominant,
                backgroundTopColor = bgTop,
                backgroundBottomColor = bgBottom,
                accentColor = accent,
                isFallback = false,
                isMonochrome = true,
                vibrantColor = if (isDarkTheme) Color(0xFFE2E2E9) else Color(0xFF1E293B),
                mutedColor = if (isDarkTheme) Color(0xFF8E9099) else Color(0xFF74777F),
                darkColorScheme = MonochromeDarkColorScheme,
                lightColorScheme = MonochromeLightColorScheme,
            )
        }

        // Chromatic artwork:
        val atmosphericTintAlpha = if (isDarkTheme) 0.50f else 0.28f
        val backgroundTop = dominant.copy(alpha = atmosphericTintAlpha).compositeOver(defaultSurfaceColor)
        val backgroundBottom = defaultBackgroundColor

        return ArtworkColors(
            dominantColor = dominant,
            backgroundTopColor = backgroundTop,
            backgroundBottomColor = backgroundBottom,
            accentColor = vibrant,
            isFallback = false,
            isMonochrome = false,
            vibrantColor = vibrant,
            mutedColor = muted,
            darkColorScheme = deriveDarkColorScheme(seedColor = vibrant, dominantColor = dominant),
            lightColorScheme = deriveLightColorScheme(seedColor = vibrant, dominantColor = dominant),
        )
    }

    /**
     * Extracts the most prominent/vibrant dominant color from a bitmap using androidx.palette.
     * Returns null if bitmap is invalid or has no non-transparent pixels.
     */
    fun extractDominantColor(bitmap: Bitmap): Color? {
        if (bitmap.width <= 0 || bitmap.height <= 0 || !hasNonTransparentPixels(bitmap)) return null
        return try {
            val palette =
                Palette
                    .from(bitmap)
                    .maximumColorCount(16)
                    .clearFilters()
                    .addFilter { rgb, _ -> ((rgb ushr 24) and 0xFF) >= 64 }
                    .generate()

            val swatch =
                palette.dominantSwatch
                    ?: palette.swatches.maxByOrNull { it.population }
                    ?: palette.vibrantSwatch
                    ?: palette.mutedSwatch
            swatch?.let { Color(it.rgb) }
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Fallback atmospheric colors when artwork thumbnail is missing or cannot be loaded.
     * Pure neutral Material 3 monochrome without hardcoded brand purple (#141022 or #5544B3).
     */
    fun createFallbackColors(
        defaultSurfaceColor: Color = SurfaceContainerDark,
        defaultBackgroundColor: Color = BackgroundDark,
    ): ArtworkColors =
        ArtworkColors(
            dominantColor = defaultSurfaceColor,
            backgroundTopColor = defaultSurfaceColor,
            backgroundBottomColor = defaultBackgroundColor,
            accentColor = Color(0xFFE2E2E9),
            isFallback = true,
            isMonochrome = true,
            vibrantColor = Color(0xFFE2E2E9),
            mutedColor = Color(0xFF8E9099),
            darkColorScheme = MonochromeDarkColorScheme,
            lightColorScheme = MonochromeLightColorScheme,
        )
}

/**
 * Remembers reactive dynamic artwork colors from the given cover thumbnail path.
 * Computes in background IO dispatcher when thumbnail path changes, falling back to MD3 surfaceContainer.
 */
@Composable
fun rememberArtworkColors(
    thumbnailPath: String?,
    defaultSurfaceColor: Color = Color.Unspecified,
    defaultBackgroundColor: Color = Color.Unspecified,
    isDarkTheme: Boolean = isSystemInDarkTheme(),
): State<ArtworkColors> {
    val resolvedSurface =
        if (defaultSurfaceColor != Color.Unspecified) {
            defaultSurfaceColor
        } else if (isDarkTheme) {
            SurfaceContainerDark
        } else {
            SurfaceContainerLight
        }

    val resolvedBackground =
        if (defaultBackgroundColor != Color.Unspecified) {
            defaultBackgroundColor
        } else if (isDarkTheme) {
            BackgroundDark
        } else {
            BackgroundLight
        }

    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext

    val initialFallback =
        ArtworkColorExtractor.createFallbackColors(
            resolvedSurface,
            resolvedBackground,
        )
    val initialCached =
        ArtworkColorExtractor.getCachedArtworkColors(
            thumbnailPath = thumbnailPath,
            defaultSurfaceColor = resolvedSurface,
            defaultBackgroundColor = resolvedBackground,
            isDarkTheme = isDarkTheme,
        )

    return produceState(
        initialValue = initialCached ?: initialFallback,
        thumbnailPath,
        resolvedSurface,
        resolvedBackground,
        isDarkTheme,
    ) {
        if (thumbnailPath.isNullOrBlank()) {
            value = initialFallback
            return@produceState
        }

        val cached =
            ArtworkColorExtractor.getCachedArtworkColors(
                thumbnailPath = thumbnailPath,
                defaultSurfaceColor = resolvedSurface,
                defaultBackgroundColor = resolvedBackground,
                isDarkTheme = isDarkTheme,
            )
        if (cached != null) {
            value = cached
            return@produceState
        }

        val extracted =
            withContext(Dispatchers.IO) {
                ArtworkColorExtractor.extractArtworkColors(
                    thumbnailPath = thumbnailPath,
                    defaultSurfaceColor = resolvedSurface,
                    defaultBackgroundColor = resolvedBackground,
                    isDarkTheme = isDarkTheme,
                    context = context,
                )
            }
        value = extracted
    }
}
