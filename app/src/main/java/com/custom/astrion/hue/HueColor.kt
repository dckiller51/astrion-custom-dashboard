package com.custom.astrion.hue

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Colours as the light card speaks them (RGB, hue / saturation, kelvin) and as a Hue bridge does
 * (CIE xy, mirek), both ways (1.6.1). The xy ↔ RGB matrices are the wide-gamut ones Philips gives
 * for its lamps; a colour outside a lamp's gamut is brought to its nearest by the bridge itself.
 * Pure, so it is unit-tested.
 */
object HueColor {
    /** D65, the white point: what black or an empty colour falls back to. */
    val WHITE_XY = 0.3127 to 0.3290

    /** sRGB 0–255 → CIE xy. */
    fun rgbToXy(r: Int, g: Int, b: Int): Pair<Double, Double> {
        val lr = linear(r / MAX)
        val lg = linear(g / MAX)
        val lb = linear(b / MAX)
        val x = lr * 0.664511 + lg * 0.154324 + lb * 0.162028
        val y = lr * 0.283881 + lg * 0.668433 + lb * 0.047685
        val z = lr * 0.000088 + lg * 0.072310 + lb * 0.986039
        val sum = x + y + z
        if (sum <= 0.0) return WHITE_XY
        return round4(x / sum) to round4(y / sum)
    }

    /** CIE xy → sRGB 0–255, at full brightness (the colour itself, for a card's tint). */
    fun xyToRgb(x: Double, y: Double): Triple<Int, Int, Int> {
        if (y <= 0.0) return Triple(MAX.toInt(), MAX.toInt(), MAX.toInt())
        val bigY = 1.0
        val bigX = bigY / y * x
        val bigZ = bigY / y * (1.0 - x - y)
        var r = bigX * 1.656492 - bigY * 0.354851 - bigZ * 0.255038
        var g = -bigX * 0.707196 + bigY * 1.655397 + bigZ * 0.036152
        var b = bigX * 0.051713 - bigY * 0.121364 + bigZ * 1.011530
        r = max(r, 0.0)
        g = max(g, 0.0)
        b = max(b, 0.0)
        val top = max(r, max(g, b))
        if (top > 1.0) {
            r /= top
            g /= top
            b /= top
        }
        return Triple(channel(gamma(r)), channel(gamma(g)), channel(gamma(b)))
    }

    /** Hue 0–360 and saturation 0–100 (Home Assistant's `hs_color`) → sRGB at full value. */
    fun hsToRgb(hue: Double, sat: Double): Triple<Int, Int, Int> {
        val h = ((hue % DEGREES) + DEGREES) % DEGREES / SECTOR
        val s = (sat / PERCENT).coerceIn(0.0, 1.0)
        val c = s
        val xx = c * (1 - abs(h % 2 - 1))
        val (r, g, b) =
            when (h.toInt()) {
                0 -> Triple(c, xx, 0.0)
                1 -> Triple(xx, c, 0.0)
                2 -> Triple(0.0, c, xx)
                3 -> Triple(0.0, xx, c)
                4 -> Triple(xx, 0.0, c)
                else -> Triple(c, 0.0, xx)
            }
        val m = 1 - c
        return Triple(channel(r + m), channel(g + m), channel(b + m))
    }

    /** sRGB → hue 0–360 and saturation 0–100, rounded to a tenth. */
    fun rgbToHs(r: Int, g: Int, b: Int): Pair<Double, Double> {
        val rf = r / MAX
        val gf = g / MAX
        val bf = b / MAX
        val top = max(rf, max(gf, bf))
        val low = min(rf, min(gf, bf))
        val d = top - low
        val hue =
            when {
                d == 0.0 -> 0.0
                top == rf -> SECTOR * (((gf - bf) / d) % 6)
                top == gf -> SECTOR * ((bf - rf) / d + 2)
                else -> SECTOR * ((rf - gf) / d + 4)
            }.let { if (it < 0) it + DEGREES else it }
        val sat = if (top == 0.0) 0.0 else d / top * PERCENT
        return round1(hue) to round1(sat)
    }

    fun kelvinToMirek(kelvin: Int): Int = (MILLION / kelvin.coerceAtLeast(1)).roundToInt()

    fun mirekToKelvin(mirek: Int): Int = (MILLION / mirek.coerceAtLeast(1)).roundToInt()

    /** A white's colour at [kelvin], for a card's tint (Tanner Helland's fit). */
    fun kelvinToRgb(kelvin: Int): Triple<Int, Int, Int> {
        val t = kelvin.coerceIn(1000, 40000) / 100.0
        val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
        val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
        val b =
            when {
                t >= 66 -> 255.0
                t <= 19 -> 0.0
                else -> 138.5177312231 * ln(t - 10) - 305.0447927307
            }
        return Triple(r.roundToInt().coerceIn(0, 255), g.roundToInt().coerceIn(0, 255), b.roundToInt().coerceIn(0, 255))
    }

    private fun linear(v: Double) = if (v > 0.04045) ((v + 0.055) / 1.055).pow(2.4) else v / 12.92

    private fun gamma(v: Double) = if (v <= 0.0031308) 12.92 * v else 1.055 * v.pow(1 / 2.4) - 0.055

    private fun channel(v: Double) = (v * MAX).roundToInt().coerceIn(0, MAX.toInt())

    private fun round4(v: Double) = (v * 10_000).roundToInt() / 10_000.0

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    private const val MAX = 255.0
    private const val DEGREES = 360.0
    private const val SECTOR = 60.0
    private const val PERCENT = 100.0
    private const val MILLION = 1_000_000.0
}
