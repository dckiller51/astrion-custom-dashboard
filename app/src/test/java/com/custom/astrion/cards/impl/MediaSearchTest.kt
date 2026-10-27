package com.custom.astrion.cards.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home Assistant's `media_player/search_media` answers (1.3.9), as Lyrion or Music Assistant give them. */
class MediaSearchTest {
    private val answer =
        Json.parseToJsonElement(
            """
            {"result": [
              {"title": "One More Time", "media_class": "track", "media_content_type": "track",
               "media_content_id": "library://track/9", "can_play": true, "can_expand": false, "thumbnail": "/api/x"},
              {"title": "Daft Punk", "media_class": "artist", "media_content_type": "artist",
               "media_content_id": "artist_id:12", "can_play": true, "can_expand": true, "thumbnail": null},
              {"title": "Discovery", "media_class": "album", "media_content_type": "album",
               "media_content_id": "album_id:7", "can_play": true, "can_expand": true},
              {"title": "Electronic", "media_class": "genre", "media_content_type": "genre",
               "media_content_id": "genre_id:3", "can_play": false, "can_expand": true},
              {"title": "no id", "media_class": "track"}
            ]}
            """
        ) as JsonObject

    @Test
    fun `groups follow artist, album, track, playlist, then the others`() {
        val groups = MediaSearch.parse(answer)
        assertEquals(listOf("artist", "album", "track", "genre"), groups.map { it.mediaClass })
        assertEquals(1, groups.first { it.mediaClass == "track" }.items.size)
    }

    @Test
    fun `each result keeps what to play and what it can do`() {
        val groups = MediaSearch.parse(answer).associateBy { it.mediaClass }
        val track = groups.getValue("track").items.single()
        assertEquals("library://track/9" to "track", track.contentId to track.contentType)
        assertEquals("/api/x", track.thumbnail)
        assertTrue(track.canPlay)
        assertFalse(track.canExpand)
        val genre = groups.getValue("genre").items.single()
        assertFalse(genre.canPlay)
        assertTrue(genre.canExpand)
        assertEquals(null, groups.getValue("artist").items.single().thumbnail)
    }

    @Test
    fun `no result at all`() {
        assertEquals(emptyList<MediaSearch.Group>(), MediaSearch.parse(JsonObject(emptyMap())))
    }
}
