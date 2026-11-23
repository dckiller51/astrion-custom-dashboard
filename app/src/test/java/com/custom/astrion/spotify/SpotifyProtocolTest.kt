package com.custom.astrion.spotify

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyProtocolTest {
    private fun obj(text: String) = Json.parseToJsonElement(text).jsonObject

    private val player =
        obj(
            """{"device": {"id": "d1", "name": "Salon", "type": "TV", "volume_percent": 40, "supports_volume": true},
               "shuffle_state": true, "repeat_state": "context", "progress_ms": 61500, "is_playing": true,
               "context": {"uri": "spotify:playlist:37i9"},
               "item": {"type": "track", "uri": "spotify:track:abc", "name": "Time After Time", "duration_ms": 241000,
                 "artists": [{"name": "Cyndi Lauper"}],
                 "album": {"name": "She's So Unusual",
                   "images": [{"url": "big", "width": 640}, {"url": "mid", "width": 300}, {"url": "small", "width": 64}]}}}"""
        )

    private val devices =
        SpotifyProtocol.devices(
            obj(
                """{"devices": [{"id": "d1", "name": "Salon", "type": "TV", "is_active": true, "volume_percent": 40, "supports_volume": true},
                   {"id": "d2", "name": "Denon AVR", "type": "AVR", "is_active": false, "volume_percent": 30}]}"""
            )
        )

    @Test
    fun `what plays, as Home Assistant's media player`() {
        val (state, a) = SpotifyProtocol.entity("Spotify", player, devices, "2026-11-22T10:00:00Z")
        assertEquals("playing", state)
        assertEquals("Time After Time", a["media_title"])
        assertEquals("Cyndi Lauper", a["media_artist"])
        assertEquals("She's So Unusual", a["media_album_name"])
        assertEquals("mid", a["entity_picture"])
        assertEquals(241, a["media_duration"])
        assertEquals(62, a["media_position"])
        assertEquals(0.4, a["volume_level"])
        assertEquals("all", a["repeat"])
        assertEquals(true, a["shuffle"])
        assertEquals("Salon", a["source"])
        assertEquals(listOf("Salon", "Denon AVR"), a["source_list"])
        assertEquals("Spotify", a["app_name"])
        val features = a["supported_features"] as Int
        assertTrue(features and 4 != 0 && features and 131072 != 0 && features and 4194304 != 0)
    }

    @Test
    fun `nothing anywhere is idle`() {
        val (state, a) = SpotifyProtocol.entity("Spotify", null, devices, "now")
        assertEquals("idle", state)
        assertNull(a["media_title"])
    }

    @Test
    fun `service calls become Web API calls`() {
        fun one(service: String, vararg data: Pair<String, Any?>) =
            SpotifyProtocol.command(service, mapOf(*data), playing = true, volume = 40, devices = devices, fallbackDevice = "d1")!!.single()
        assertEquals(SpotifyCall("PUT", "/me/player/pause"), one("media_play_pause"))
        assertEquals(SpotifyCall("POST", "/me/player/next"), one("media_next_track"))
        assertEquals(SpotifyCall("PUT", "/me/player/seek?position_ms=90000"), one("media_seek", "seek_position" to 90))
        assertEquals(SpotifyCall("PUT", "/me/player/volume?volume_percent=55"), one("volume_set", "volume_level" to 0.55))
        assertEquals(SpotifyCall("PUT", "/me/player/volume?volume_percent=50"), one("volume_up"))
        assertEquals(SpotifyCall("PUT", "/me/player/repeat?state=track"), one("repeat_set", "repeat" to "one"))
        assertEquals(SpotifyCall("PUT", "/me/player/shuffle?state=false"), one("shuffle_set", "shuffle" to false))
        val move = one("select_source", "source" to "Denon AVR")
        assertEquals("/me/player", move.path)
        assertEquals("d2", ((move.body!!["device_ids"] as JsonArray)[0] as JsonPrimitive).content)
        val album =
            one(
                "play_media",
                "media_content_id" to "https://open.spotify.com/album/4aawyAB9vmqN3uQ7FjRGTy?si=x",
                "media_content_type" to "album"
            )
        assertEquals("spotify:album:4aawyAB9vmqN3uQ7FjRGTy", (album.body!!["context_uri"] as JsonPrimitive).content)
        val track = one("play_media", "media_content_id" to "spotify:track:abc", "media_content_type" to "track")
        assertTrue(track.body!!.containsKey("uris"))
        assertEquals("POST", one("play_media", "media_content_id" to "spotify:track:abc", "enqueue" to "add").method)
        assertNull(SpotifyProtocol.command("set_value", emptyMap(), true, null, devices, null))
    }

    @Test
    fun `no device active, play goes to the last one`() {
        val idle = devices.map { it.copy(active = false) }
        val call = SpotifyProtocol.command(
            "media_play",
            emptyMap(),
            playing = false,
            volume = null,
            devices = idle,
            fallbackDevice = "d1"
        )!!.single()
        assertEquals("/me/player/play?device_id=d1", call.path)
    }

    @Test
    fun `search and browse as Home Assistant's media browser`() {
        assertEquals("/search?q=daft%20punk&type=artist,album,track,playlist&limit=10", SpotifyProtocol.searchCall("daft punk", null).path)
        assertEquals("/search?q=x&type=album&limit=10", SpotifyProtocol.searchCall("x", "album").path)
        val result =
            SpotifyProtocol.searchResult(
                obj(
                    """{"tracks": {"items": [{"uri": "spotify:track:1", "name": "One More Time", "artists": [{"name": "Daft Punk"}],
                       "album": {"images": [{"url": "cover", "width": 300}]}}]},
                       "artists": {"items": [{"uri": "spotify:artist:9", "name": "Daft Punk", "images": []}]}}"""
                )
            )
        val items = (result["result"] as JsonArray).map { it as JsonObject }
        assertEquals(listOf("artist", "track"), items.map { (it["media_class"] as JsonPrimitive).content })
        assertEquals("One More Time · Daft Punk", (items[1]["title"] as JsonPrimitive).content)
        assertEquals("cover", (items[1]["thumbnail"] as JsonPrimitive).content)
        val root = SpotifyProtocol.root()
        assertEquals(2, (root["children"] as JsonArray).size)
        assertEquals("/me/playlists?limit=50", SpotifyProtocol.browseCall(SpotifyProtocol.PLAYLISTS)!!.path)
        val lists = SpotifyProtocol.browseResult(
            SpotifyProtocol.PLAYLISTS,
            "Playlists",
            obj("""{"items": [{"uri": "spotify:playlist:p", "name": "Chill", "owner": {"display_name": "Xavier"}}]}""")
        )
        assertEquals("Chill · Xavier", ((lists["children"] as JsonArray)[0] as JsonObject)["title"].let { (it as JsonPrimitive).content })
    }

    @Test
    fun `sign-in with PKCE`() {
        // RFC 7636's example.
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", SpotifyAuth.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
        val url = SpotifyAuth.authorizeUrl("0123456789abcdef0123456789abcdef", "ch", "st")
        assertTrue(url.contains("redirect_uri=http%3A%2F%2F127.0.0.1%3A8888%2Fcallback"))
        assertTrue(url.contains("scope=user-read-playback-state%20"))
        assertEquals(86, SpotifyAuth.newVerifier().length)
        assertEquals(
            SpotifyAuth.Landing.Code("AQBx_long-code-1234567890", "st"),
            SpotifyAuth.landing("http://127.0.0.1:8888/callback?code=AQBx_long-code-1234567890&state=st")
        )
        assertEquals(
            SpotifyAuth.Landing.Error("access_denied"),
            SpotifyAuth.landing("http://127.0.0.1:8888/callback?error=access_denied&state=st")
        )
        assertTrue(SpotifyAuth.isClientId("0123456789abcdef0123456789abcdef"))
    }
}
