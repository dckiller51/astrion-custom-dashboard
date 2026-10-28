package com.custom.astrion.cards.impl

import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The media player card's queue (1.4.0), as Lyrion and Music Assistant share it. */
class MediaQueueTest {
    private fun json(text: String) = Json.parseToJsonElement(text) as JsonObject

    private fun player(attrs: String) = EntityState("media_player.x", "playing", json(attrs))

    @Test
    fun `the player's integration is told by its attributes`() {
        assertEquals(MediaQueue.Kind.LYRION, MediaQueue.kind(player("""{"query_result": {}}""")))
        assertEquals(MediaQueue.Kind.MUSIC_ASSISTANT, MediaQueue.kind(player("""{"mass_player_type": "player"}""")))
        assertEquals(MediaQueue.Kind.OTHER, MediaQueue.kind(player("""{"supported_features": 8192}""")))
        assertEquals(MediaQueue.Kind.OTHER, MediaQueue.kind(null))
    }

    @Test
    fun `the queue button needs a shared queue or clear`() {
        assertTrue(MediaQueue.available(player("""{"query_result": {}}""")))
        assertTrue(MediaQueue.available(player("""{"supported_features": 8193}""")))
        assertFalse(MediaQueue.available(player("""{"supported_features": 4096}""")))
        assertFalse(MediaQueue.available(EntityState("media_player.x", "unavailable", json("""{"query_result": {}}"""))))
    }

    @Test
    fun `lyrion status gives the queue from the current track`() {
        val q =
            MediaQueue.parseLyrion(
                json(
                    """
                    {"playlist_cur_index": "4", "playlist_tracks": 12, "playlist_loop": [
                      {"playlist index": 4, "title": "Aerodynamic", "artist": "Daft Punk", "album": "Discovery",
                       "duration": 212.5, "artwork_url": "https://cdn/x.jpg"},
                      {"playlist index": "5", "title": "Digital Love", "duration": "301", "artwork_url": "/imageproxy/y"},
                      {"title": "no index"}
                    ]}
                    """
                )
            )
        assertEquals(12, q.total)
        assertEquals(4, q.currentIndex)
        assertTrue(q.editable)
        assertEquals(listOf(4, 5), q.tracks.map { it.index })
        assertTrue(q.tracks[0].current)
        assertFalse(q.tracks[1].current)
        assertEquals("Daft Punk", q.tracks[0].artist)
        assertEquals(212.5, q.tracks[0].duration!!, 0.0)
        assertEquals("https://cdn/x.jpg", q.tracks[0].image)
        assertNull("a cover only Lyrion can serve is left out", q.tracks[1].image)
    }

    @Test
    fun `an empty lyrion queue`() {
        val q = MediaQueue.parseLyrion(json("""{"playlist_tracks": 0}"""))
        assertEquals(0, q.total)
        assertTrue(q.tracks.isEmpty())
    }

    @Test
    fun `music assistant gives the current and next track`() {
        val q =
            MediaQueue.parseMusicAssistant(
                json(
                    """
                    {"queue_id": "q", "active": true, "name": "Club", "items": 30, "shuffle_enabled": false,
                     "repeat_mode": "off", "current_index": 7, "elapsed_time": 12,
                     "current_item": {"queue_item_id": "a", "name": "Daft Punk - One More Time", "duration": 320,
                       "media_item": {"name": "One More Time", "image": "http://ma:8095/img",
                         "artists": [{"name": "Daft Punk"}, {"name": "Romanthony"}], "album": {"name": "Discovery"}}},
                     "next_item": {"queue_item_id": "b", "name": "Radio stream", "duration": null, "media_item": null}}
                    """
                )
            )
        assertEquals(30, q.total)
        assertFalse(q.editable)
        assertEquals(listOf(7, 8), q.tracks.map { it.index })
        assertEquals("One More Time", q.tracks[0].title)
        assertEquals("Daft Punk, Romanthony", q.tracks[0].artist)
        assertEquals("Discovery", q.tracks[0].album)
        assertTrue(q.tracks[0].current)
        assertEquals("Radio stream", q.tracks[1].title)
        assertNull(q.tracks[1].duration)
    }
}
