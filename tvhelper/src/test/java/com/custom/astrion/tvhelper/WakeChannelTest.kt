package com.custom.astrion.tvhelper

import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Test

class WakeChannelTest {
    private fun talk(lines: String, screen: Boolean = true): Pair<String, Int> {
        var woken = 0
        val out = ByteArrayOutputStream()
        WakeChannel.converse(lines.byteInputStream().bufferedReader(), out, "ABC234", {
            woken++
            screen
        })
        return out.toString() to woken
    }

    @Test
    fun `wake after the code, the screen's state back`() {
        assertEquals("OK\nON\n" to 1, talk("ABC234\nWAKE\n"))
        assertEquals("OK\nOFF\n" to 1, talk("ABC234\nWAKE\n", screen = false))
        assertEquals("OK\nPONG\nON\n" to 1, talk("ABC234\nPING\nWAKE\n"))
    }

    @Test
    fun `a wrong code is refused and wakes nothing`() {
        assertEquals("DENIED\n" to 0, talk("NOPE00\nWAKE\n"))
        assertEquals("DENIED\n" to 0, talk(""))
    }
}
