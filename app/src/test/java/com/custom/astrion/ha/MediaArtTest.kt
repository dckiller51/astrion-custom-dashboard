package com.custom.astrion.ha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MediaArtTest {
    @Test
    fun `a Home Assistant player's picture is asked again`() {
        assertTrue(MediaArt.settles("/api/media_player_proxy/media_player.apple_tv?token=abc&cache=1f2e"))
        assertTrue(MediaArt.settles("http://ha.local:8123/api/media_player_proxy/media_player.x?cache=9"))
        assertEquals(2, MediaArt.AGAIN_MS.size)
    }

    @Test
    fun `other pictures aren't`() {
        assertFalse(MediaArt.settles(null))
        assertFalse(MediaArt.settles("http://127.0.0.1:8080/appletv-artwork/atv1?v=3"))
        assertFalse(MediaArt.settles("https://image.tmdb.org/t/p/w500/x.jpg"))
        assertFalse(MediaArt.settles("/api/camera_proxy/camera.door"))
    }
}
