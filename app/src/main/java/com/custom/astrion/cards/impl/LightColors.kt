package com.custom.astrion.cards.impl

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** A light's colour as Home Assistant's `hs_color`: hue 0–360, saturation 0–100. */
data class HueSat(val hue: Float, val sat: Float)

/** Where the light popup's colour wheel goes, against the swatches (`color_wheel`). */
enum class WheelPlace {
    TOP,
    BOTTOM,
    HIDDEN;

    companion object {
        fun of(value: String?): WheelPlace = when (value) {
            "top" -> TOP
            "hidden" -> HIDDEN
            else -> BOTTOM
        }
    }
}

/** How a light card dresses its long-press popup: swatches, wheel place, power button side. */
data class LightPopupStyle(
    val swatches: List<Triple<Int, Int, Int>> = LightColors.DEFAULT_SWATCHES,
    val wheel: WheelPlace = WheelPlace.BOTTOM,
    val powerLeft: Boolean = false
)

/** A D-pad arrow, while the wheel is being adjusted. */
enum class WheelMove { LEFT, RIGHT, UP, DOWN }

/** Pure maths and parsing behind the light's colour wheel and swatches (unit-tested). */
object LightColors {
    /** The swatches shown when the card sets no `colors`. */
    val DEFAULT_SWATCHES: List<Triple<Int, Int, Int>> =
        listOf(
            Triple(244, 67, 54),
            Triple(255, 152, 0),
            Triple(255, 235, 59),
            Triple(76, 175, 80),
            Triple(0, 188, 212),
            Triple(33, 150, 243),
            Triple(156, 39, 176),
            Triple(233, 30, 99),
            Triple(255, 182, 193),
            Triple(255, 255, 255)
        )

    /** Most swatches a card keeps from its `colors`. */
    const val MAX_SWATCHES = 15

    private const val HUE_STEP = 15f
    private const val SAT_STEP = 10f

    /** `#RRGGBB` or `RRGGBB` (also `#RGB`) to r, g, b; null when it isn't one. */
    fun parseHex(text: String): Triple<Int, Int, Int>? {
        val h = text.trim().removePrefix("#")
        val full =
            when {
                h.length == 3 -> h.map { "$it$it" }.joinToString("")
                h.length == 6 -> h
                else -> return null
            }
        val v = full.toIntOrNull(16) ?: return null
        return Triple((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
    }

    /** The card's `colors` (hex strings), or the defaults when it has none that parse. */
    fun swatches(colors: List<String>): List<Triple<Int, Int, Int>> =
        colors.mapNotNull(::parseHex).take(MAX_SWATCHES).ifEmpty { DEFAULT_SWATCHES }

    /**
     * Where a touch at ([dx], [dy]) from the wheel's centre lands, on a wheel of [radius].
     * Hue goes clockwise from the right (red), as the wheel is drawn; saturation grows outwards.
     */
    fun pointToHueSat(dx: Float, dy: Float, radius: Float): HueSat {
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val hue = (angle + 360f) % 360f
        val sat = if (radius <= 0f) 0f else (hypot(dx, dy) / radius).coerceIn(0f, 1f) * 100f
        return HueSat(hue, sat)
    }

    /** The marker's place (x, y from the centre) for [hs] on a wheel of [radius]. */
    fun hueSatToPoint(hs: HueSat, radius: Float): Pair<Float, Float> {
        val rad = Math.toRadians(hs.hue.toDouble())
        val r = radius * (hs.sat / 100f).coerceIn(0f, 1f)
        return Pair((cos(rad) * r).toFloat(), (sin(rad) * r).toFloat())
    }

    /** One D-pad step: left/right turn the hue, up/down change the saturation. */
    fun step(hs: HueSat, move: WheelMove): HueSat = when (move) {
        WheelMove.RIGHT -> hs.copy(hue = (hs.hue + HUE_STEP) % 360f)
        WheelMove.LEFT -> hs.copy(hue = (hs.hue - HUE_STEP + 360f) % 360f)
        WheelMove.UP -> hs.copy(sat = (hs.sat + SAT_STEP).coerceAtMost(100f))
        WheelMove.DOWN -> hs.copy(sat = (hs.sat - SAT_STEP).coerceAtLeast(0f))
    }

    /** Home Assistant's `hs_color` attribute ([hue, sat]); null when absent. */
    fun parseHs(values: List<Double>?): HueSat? {
        if (values == null || values.size < 2) return null
        return HueSat(values[0].toFloat().mod(360f), values[1].toFloat().coerceIn(0f, 100f))
    }
}
