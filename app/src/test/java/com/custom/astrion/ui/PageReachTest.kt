package com.custom.astrion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PageReachTest {
    private val span = 1000f

    @Test
    fun reachSkipsHiddenPagesAtBothEnds() {
        assertEquals(1..3, PageReach.of(listOf(true, false, true, false, true, true)))
        assertEquals(0..2, PageReach.of(listOf(false, false, false)))
        assertNull(PageReach.of(listOf(true, true)))
    }

    @Test
    fun aSwipeStopsOnTheLastVisiblePage() {
        // MR RIZK: popup pages never shown after the last page.
        val reach = 0..3
        assertEquals(40f, PageReach.held(3f, 40f, span, reach), 0.01f)
        assertEquals(0f, PageReach.held(3f, -40f, span, reach), 0.01f)
        // Half a page short: the rest of a long swipe is held back.
        assertEquals(200f, PageReach.held(2.5f, 700f, span, reach), 0.01f)
        assertEquals(0f, PageReach.held(2.5f, 300f, span, reach), 0.01f)
    }

    @Test
    fun aSwipeStopsOnTheFirstVisiblePage() {
        val reach = 2..5
        assertEquals(-40f, PageReach.held(2f, -40f, span, reach), 0.01f)
        assertEquals(0f, PageReach.held(2f, 40f, span, reach), 0.01f)
    }

    @Test
    fun onAHiddenPageOpenedByATileOnlyTheWayBackSwipes() {
        // On page 5, never shown, past the last visible page 3.
        val reach = 0..3
        assertEquals(50f, PageReach.held(5f, 50f, span, reach), 0.01f)
        assertEquals(0f, PageReach.held(5f, -50f, span, reach), 0.01f)
    }

    @Test
    fun flingsPastTheEdgeAreHeld() {
        val reach = 0..3
        assertTrue(PageReach.blocksFling(3f, 900f, reach))
        assertFalse(PageReach.blocksFling(3f, -900f, reach))
        assertFalse(PageReach.blocksFling(2.6f, 900f, reach))
        assertTrue(PageReach.blocksFling(0f, -900f, reach))
    }
}
