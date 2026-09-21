package com.custom.astrion.cards.impl

import androidx.compose.ui.graphics.Color
import androidx.core.graphics.toColorInt

/**
 * Where a tile's icon sits relative to its label — one grid-wide setting
 * (the `"iconPosition"` card option), shared between [ButtonGridCard] and
 * [SceneGridCard] so both offer the same four positions.
 */
internal enum class IconPosition {
    TOP,
    BOTTOM,
    LEFT,
    RIGHT
    ;

    companion object {
        fun from(raw: String?): IconPosition = when (raw?.lowercase()) {
            "bottom" -> BOTTOM
            "left" -> LEFT
            "right" -> RIGHT
            else -> TOP
        }
    }
}

/** left/right lay the icon and label side by side, so a tile in either of
 * those positions doesn't need the extra vertical room top/bottom do to fit
 * both. */
internal val IconPosition.sideBySide: Boolean
    get() = this == IconPosition.LEFT || this == IconPosition.RIGHT

/**
 * Parses a hex color string (with or without a leading `#`, ARGB or RGB) as
 * used throughout `button_grid`/`scene_grid` configs (a scene's `"color"`,
 * a button's `"active_color"`/`"active_border"`...). Returns null on
 * anything missing or unparsable rather than throwing, so a typo in
 * dashboard.json degrades to "no override" instead of crashing the card.
 */
internal fun parseHexColor(s: String?): Color? {
    if (s.isNullOrBlank()) return null
    return runCatching {
        val hex = if (s.startsWith("#")) s else "#$s"
        Color(hex.toColorInt())
    }.getOrNull()
}
