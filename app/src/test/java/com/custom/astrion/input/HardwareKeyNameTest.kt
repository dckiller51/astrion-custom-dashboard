package com.custom.astrion.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HardwareKeyNameTest {
    @Test
    fun `a hotkey finds its button by its own name or an Android name`() {
        assertEquals(HardwareKey.MAIN, HardwareKey.fromName("MAIN"))
        assertEquals(HardwareKey.MAIN, HardwareKey.fromName("MENU"))
        assertEquals(HardwareKey.MAIN, HardwareKey.fromName("KEYCODE_MENU"))
        assertEquals(HardwareKey.HOME, HardwareKey.fromName("home"))
        assertEquals(HardwareKey.FASTFORWARD, HardwareKey.fromName("FAST_FORWARD"))
        assertEquals(HardwareKey.RED_BUTTON, HardwareKey.fromName("PROG_RED"))
    }

    @Test
    fun `a button this remote doesn't have is none`() {
        assertNull(HardwareKey.fromName("F4"))
        assertNull(HardwareKey.fromName("NUM_0"))
        assertNull(HardwareKey.fromName("UNKNOWN"))
    }
}
