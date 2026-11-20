package com.custom.astrion.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BleWakeTest {
    @Test
    fun theReadyMadeFirmwaresButton() {
        assertEquals("http://192.168.2.156/button/Wake%20the%20TV/press", BleWake.url("192.168.2.156"))
    }

    @Test
    fun aNameAPortOrATypedPrefix() {
        assertEquals("http://xiaomi-ble-wake.local/button/Wake%20the%20TV/press", BleWake.url(" http://xiaomi-ble-wake.local/ "))
        assertEquals("http://10.0.0.5:8080/button/Wake%20the%20TV/press", BleWake.url("10.0.0.5:8080"))
    }

    @Test
    fun anotherButtonName() {
        assertEquals("http://esp/button/R%C3%A9veil%20TV/press", BleWake.url("esp", "Réveil TV"))
    }

    @Test
    fun hosts() {
        assertTrue(BleWake.isHost("192.168.2.156"))
        assertTrue(BleWake.isHost("xiaomi-ble-wake-6ad3c4.local"))
        assertFalse(BleWake.isHost("http://x/y"))
        assertFalse(BleWake.isHost(""))
    }
}
