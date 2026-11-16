package com.custom.astrion.cards.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LightColorsTest {
    @Test
    fun `hex colours parse with or without the hash, short form too`() {
        assertEquals(Triple(255, 165, 0), LightColors.parseHex("#FFA500"))
        assertEquals(Triple(255, 165, 0), LightColors.parseHex(" ffa500 "))
        assertEquals(Triple(255, 0, 0), LightColors.parseHex("#f00"))
        assertNull(LightColors.parseHex("red"))
        assertNull(LightColors.parseHex("#12345"))
    }

    @Test
    fun `swatches fall back to the defaults and keep at most 15`() {
        assertEquals(LightColors.DEFAULT_SWATCHES, LightColors.swatches(emptyList()))
        assertEquals(LightColors.DEFAULT_SWATCHES, LightColors.swatches(listOf("nope")))
        assertEquals(listOf(Triple(0, 0, 255)), LightColors.swatches(listOf("zzz", "#0000FF")))
        assertEquals(LightColors.MAX_SWATCHES, LightColors.swatches(List(20) { "#FFFFFF" }).size)
    }

    @Test
    fun `wheel point to hue and saturation, clockwise from the right`() {
        val right = LightColors.pointToHueSat(100f, 0f, 100f)
        assertEquals(0f, right.hue, 0.01f)
        assertEquals(100f, right.sat, 0.01f)
        val below = LightColors.pointToHueSat(0f, 50f, 100f)
        assertEquals(90f, below.hue, 0.01f)
        assertEquals(50f, below.sat, 0.01f)
        assertEquals(270f, LightColors.pointToHueSat(0f, -10f, 100f).hue, 0.01f)
        assertEquals(100f, LightColors.pointToHueSat(300f, 0f, 100f).sat, 0.01f)
    }

    @Test
    fun `the marker goes back where the touch was`() {
        val hs = HueSat(135f, 60f)
        val (x, y) = LightColors.hueSatToPoint(hs, 100f)
        val back = LightColors.pointToHueSat(x, y, 100f)
        assertEquals(hs.hue, back.hue, 0.01f)
        assertEquals(hs.sat, back.sat, 0.01f)
    }

    @Test
    fun `d-pad steps wrap the hue and clamp the saturation`() {
        assertEquals(345f, LightColors.step(HueSat(0f, 50f), WheelMove.LEFT).hue, 0.01f)
        assertEquals(5f, LightColors.step(HueSat(350f, 50f), WheelMove.RIGHT).hue, 0.01f)
        assertEquals(100f, LightColors.step(HueSat(0f, 95f), WheelMove.UP).sat, 0.01f)
        assertEquals(0f, LightColors.step(HueSat(0f, 5f), WheelMove.DOWN).sat, 0.01f)
    }

    @Test
    fun `hs_color from home assistant`() {
        assertEquals(HueSat(30f, 100f), LightColors.parseHs(listOf(390.0, 120.0)))
        assertNull(LightColors.parseHs(listOf(10.0)))
        assertNull(LightColors.parseHs(null))
    }

    @Test
    fun `wheel place from the card, under the swatches by default`() {
        assertEquals(WheelPlace.TOP, WheelPlace.of("top"))
        assertEquals(WheelPlace.HIDDEN, WheelPlace.of("hidden"))
        assertEquals(WheelPlace.BOTTOM, WheelPlace.of(null))
        assertEquals(WheelPlace.BOTTOM, WheelPlace.of("sideways"))
    }
}
