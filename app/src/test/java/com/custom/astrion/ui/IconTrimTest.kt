package com.custom.astrion.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The transparent margins `icon_trim` cuts off a logo (1.5.0). */
class IconTrimTest {
    private val clear = 0x00FFFFFF
    private val ink = 0xFF00FF00.toInt()

    @Test
    fun `the drawing's box, margins left out`() {
        // 5 × 4, ink at (1,1)..(3,2)
        val w = 5
        val h = 4
        val px = IntArray(w * h) { clear }
        for (y in 1..2) for (x in 1..3) px[y * w + x] = ink
        assertArrayEquals(intArrayOf(1, 1, 3, 2), opaqueBounds(px, w, h))
    }

    @Test
    fun `a fully transparent picture has no box`() {
        assertNull(opaqueBounds(IntArray(6) { clear }, 3, 2))
    }

    @Test
    fun `a picture without margins keeps its whole size`() {
        assertArrayEquals(intArrayOf(0, 0, 2, 1), opaqueBounds(IntArray(6) { ink }, 3, 2))
    }
}
