package com.custom.astrion.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EversoloLoopTest {
    @Test
    fun `loopModel reads as repeat and shuffle`() {
        assertEquals("all", EversoloLoop.repeatOf(EversoloLoop.ALL))
        assertEquals("one", EversoloLoop.repeatOf(EversoloLoop.ONE))
        assertEquals("off", EversoloLoop.repeatOf(EversoloLoop.SHUFFLE))
        assertEquals("off", EversoloLoop.repeatOf(EversoloLoop.OFF))
        assertEquals(true, EversoloLoop.shuffleOf(EversoloLoop.SHUFFLE))
        assertEquals(false, EversoloLoop.shuffleOf(EversoloLoop.ALL))
    }

    @Test
    fun `repeat_set and shuffle_set aim at a loopModel`() {
        assertEquals(EversoloLoop.ONE, EversoloLoop.target("repeat_set", mapOf("repeat" to "one"), EversoloLoop.OFF))
        assertEquals(EversoloLoop.OFF, EversoloLoop.target("repeat_set", mapOf("repeat" to "off"), EversoloLoop.ALL))
        assertEquals(EversoloLoop.SHUFFLE, EversoloLoop.target("shuffle_set", mapOf("shuffle" to true), EversoloLoop.ALL))
        assertEquals(EversoloLoop.OFF, EversoloLoop.target("shuffle_set", mapOf("shuffle" to false), EversoloLoop.SHUFFLE))
        assertEquals(EversoloLoop.ONE, EversoloLoop.target("shuffle_set", mapOf("shuffle" to false), EversoloLoop.ONE))
        assertNull(EversoloLoop.target("repeat_set", mapOf("repeat" to "maybe"), EversoloLoop.ALL))
    }

    @Test
    fun `presses go round the four modes`() {
        assertEquals(1, EversoloLoop.presses(EversoloLoop.ALL, EversoloLoop.ONE))
        assertEquals(1, EversoloLoop.presses(EversoloLoop.OFF, EversoloLoop.ALL))
        assertEquals(3, EversoloLoop.presses(EversoloLoop.ONE, EversoloLoop.ALL))
        assertEquals(0, EversoloLoop.presses(EversoloLoop.SHUFFLE, EversoloLoop.SHUFFLE))
    }
}
