package com.custom.astrion.cards

import org.junit.Assert.assertEquals
import org.junit.Test

/** `irCommands` (1.3.7): every way a button can list its IR commands. */
class IrActionTest {
    @Test
    fun `a list, a comma-separated string or numbers give the same commands`() {
        val expected = listOf("8", "0", "1")
        assertEquals(expected, irCommandsOf(listOf("8", "0", "1")))
        assertEquals(expected, irCommandsOf("8, 0,1"))
        assertEquals(expected, irCommandsOf(listOf(8, 0, 1)))
        assertEquals(expected, irCommandsOf(listOf(8.0, 0L, " 1 ")))
    }

    @Test
    fun `one command stays one, blanks are dropped`() {
        assertEquals(listOf("power"), irCommandsOf("power"))
        assertEquals(listOf("hdmi1"), irCommandsOf(" hdmi1 ,, "))
        assertEquals(emptyList<String>(), irCommandsOf(null))
        assertEquals(emptyList<String>(), irCommandsOf(""))
    }
}
