package com.custom.astrion.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    private fun player(vararg attrs: Pair<String, String>) =
        EntityState("media_player.apple_tv", "playing", JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

    @Test
    fun `the Plex server's poster comes before the player's own picture`() {
        val own = "/api/media_player_proxy/media_player.apple_tv?cache=old"
        val plex = "http://127.0.0.1:8080/plex-art?path=%2Flibrary%2Fmetadata%2F7%2Fthumb"
        assertEquals(plex, player("entity_picture" to own, "astrion_poster" to plex, "astrion_meta_source" to "plex").mediaPicture)
        // TMDB's poster may be another film's: the player's own first.
        val tmdb = "https://image.tmdb.org/x.jpg"
        assertEquals(own, player("entity_picture" to own, "astrion_poster" to tmdb, "astrion_meta_source" to "tmdb").mediaPicture)
        assertEquals(tmdb, player("astrion_poster" to tmdb).mediaPicture)
    }
}
