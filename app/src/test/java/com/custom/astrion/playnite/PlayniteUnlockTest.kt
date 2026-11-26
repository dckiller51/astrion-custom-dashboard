package com.custom.astrion.playnite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlayniteUnlockTest {
    @Test
    fun `the unlock ESP's answers are read`() {
        val info = PlayniteUnlock.info("""{"app":"astrion-unlock","version":"0.1.0","name":"Gaming PC","pin":true,"usb":"suspended"}""")
        assertEquals(UnlockInfo("Gaming PC", true, "suspended"), info)
        assertEquals(UnlockInfo("PC", false, "active"), PlayniteUnlock.info("""{"ok":true,"name":"PC","pin":false,"usb":"active"}"""))
        assertNull(PlayniteUnlock.info("""{"app":"playnite","name":"PC"}"""))
        assertNull(PlayniteUnlock.info("""{"error":"wrong code"}"""))
        assertNull(PlayniteUnlock.info("<html>"))
    }

    @Test
    fun `an unlock's status is a result`() {
        assertEquals(UnlockResult.DONE, PlayniteUnlock.result(200))
        assertEquals(UnlockResult.CODE_REFUSED, PlayniteUnlock.result(401))
        assertEquals(UnlockResult.PC_NOT_ON, PlayniteUnlock.result(409))
        assertEquals(UnlockResult.FAILED, PlayniteUnlock.result(429))
    }
}
