package com.custom.astrion.ui

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.custom.astrion.config.ThemeConfig

/**
 * Resolved Compose colors for the whole dashboard — every UI surface that used
 * to be a hardcoded `Color(0x...)` literal now reads from here.
 *
 * Obtained from a [ThemeConfig] (the JSON-driven `theme` block) via
 * [ThemeConfig.toColors]. A default [LocalTheme] is provided so previews and
 * any code path outside the dashboard still get the original palette.
 *
 * The 12 fields map 1:1 to the editor's semantic tokens (see docs/js/theme.js):
 * background, cardSurface, insetSurface, controlBackground, primaryText,
 * mutedText, iconTint, accent, accentSecondary, amber, danger, success.
 */
data class ThemeColors(
    val background: Color,
    val cardSurface: Color,
    val insetSurface: Color,
    val controlBackground: Color,
    val primaryText: Color,
    val mutedText: Color,
    val iconTint: Color,
    val accent: Color,
    val accentSecondary: Color,
    val amber: Color,
    val danger: Color,
    val success: Color,
    /** Icon badges drawn as circles rather than rounded squares (theme "iconShape": "circle", 1.6.3). */
    val roundIcons: Boolean = false
) {
    /** The shape of a card's icon badge (lights, switches, covers). */
    val iconShape: Shape get() = if (roundIcons) CircleShape else ICON_ROUNDED
    companion object {
        /** The app's original hardcoded palette — used when no theme is
         * configured (empty `theme` block) and as the CompositionLocal default. */
        val Default: ThemeColors = ThemeConfig().toColors()
    }
}

/** Parses a hex color string ("#RRGGBB" or "#AARRGGBB" or "RRGGBB") into a
 * Compose [Color]. Falls back to [fallback] on any parse failure so a bad
 * value in dashboard.json never crashes the UI. */
fun parseHexColor(hex: String?, fallback: Color): Color {
    if (hex.isNullOrBlank()) return fallback
    var s = hex.trim()
    if (s.startsWith("#")) s = s.substring(1)
    return try {
        when (s.length) {
            6 -> Color(("FF$s").toLong(16))
            8 -> Color(s.toLong(16))
            else -> fallback
        }
    } catch (e: Exception) {
        fallback
    }
}

/** Converts the JSON [ThemeConfig] into resolved Compose colors, falling back
 * to each token's own default when a field is missing/invalid. */
fun ThemeConfig.toColors(): ThemeColors {
    val d = ThemeConfig()
    return ThemeColors(
        background = parseHexColor(background, parseHexColor(d.background, Color.Black)),
        cardSurface = parseHexColor(cardSurface, parseHexColor(d.cardSurface, Color.Black)),
        insetSurface = parseHexColor(insetSurface, parseHexColor(d.insetSurface, Color.Black)),
        controlBackground = parseHexColor(controlBackground, parseHexColor(d.controlBackground, Color.Black)),
        primaryText = parseHexColor(primaryText, parseHexColor(d.primaryText, Color.White)),
        mutedText = parseHexColor(mutedText, parseHexColor(d.mutedText, Color.White)),
        iconTint = parseHexColor(iconTint, parseHexColor(d.iconTint, Color.White)),
        accent = parseHexColor(accent, parseHexColor(d.accent, Color.White)),
        accentSecondary = parseHexColor(accentSecondary, parseHexColor(d.accentSecondary, Color.White)),
        amber = parseHexColor(amber, parseHexColor(d.amber, Color.White)),
        danger = parseHexColor(danger, parseHexColor(d.danger, Color.White)),
        success = parseHexColor(success, parseHexColor(d.success, Color.White)),
        roundIcons = iconShape == "circle"
    )
}

/**
 * CompositionLocal carrying the active [ThemeColors]. Provided once by
 * [Dashboard] from the parsed config; read anywhere via
 * `LocalTheme.current.<token>`. Defaults to [ThemeColors.Default] so
 * composables render correctly even without a provider.
 */
val LocalTheme = staticCompositionLocalOf { ThemeColors.Default }

/** Provides [theme] to descendants via [LocalTheme]. */
@Composable
fun ProvideTheme(theme: ThemeColors, content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTheme provides theme) { content() }
}

private val ICON_ROUNDED = RoundedCornerShape(12.dp)
