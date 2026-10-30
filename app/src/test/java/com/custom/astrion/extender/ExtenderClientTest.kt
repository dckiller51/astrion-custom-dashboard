package com.custom.astrion.extender

import org.junit.Assert.assertEquals
import org.junit.Test

/** The extender's `/pronto` URL, with the IR output of a multi-output extender (1.4.3). */
class ExtenderClientTest {
    @Test
    fun `no output keeps the plain URL`() {
        assertEquals("http://192.168.1.80/pronto", prontoUrl("192.168.1.80", 0))
    }

    @Test
    fun `an output is sent as out`() {
        assertEquals("http://192.168.1.80/pronto?out=2", prontoUrl("192.168.1.80", 2))
        assertEquals("http://agv3.local/pronto?out=3", prontoUrl("agv3.local", 3))
    }
}
