package com.custom.astrion.cards.impl

import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayStationModelTest {
    private fun e(id: String, state: String, vararg attrs: Pair<String, String>) =
        EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

    @Test
    fun `online status from the integration's states`() {
        assertEquals(PlayStationModel.Status.ONLINE, PlayStationModel.status(e("sensor.zavie_statut_en_ligne", "availabletoplay")))
        assertEquals(PlayStationModel.Status.ON_PS_APP, PlayStationModel.status(e("sensor.x", "availabletocommunicate")))
        assertEquals(PlayStationModel.Status.AWAY, PlayStationModel.status(e("sensor.x", "busy")))
        assertEquals(PlayStationModel.Status.OFFLINE, PlayStationModel.status(e("sensor.x", "offline")))
        assertEquals(PlayStationModel.Status.UNKNOWN, PlayStationModel.status(null))
    }

    @Test
    fun `game from the console's media player, playing only`() {
        val playing = e(
            "media_player.playstation_5",
            "playing",
            "media_title" to "Marvel's Spider-Man: Miles Morales",
            "entity_picture" to "https://image.api.playstation.com/cover.png"
        )
        assertEquals(
            PlayStationModel.Game("Marvel's Spider-Man: Miles Morales", "https://image.api.playstation.com/cover.png"),
            PlayStationModel.game(playing, null)
        )
        assertEquals(
            "/api/image_proxy/image.zavie_lecture_en_cours",
            PlayStationModel.game(playing, "/api/image_proxy/image.zavie_lecture_en_cours")?.picture
        )
        // Netflix or a Blu-ray: the console is on, without a game.
        assertNull(PlayStationModel.game(e("media_player.playstation_5", "on"), null))
        assertFalse(PlayStationModel.consoleOff(e("media_player.playstation_5", "on")))
        assertTrue(PlayStationModel.consoleOff(e("media_player.playstation_5", "off")))
    }

    @Test
    fun `game from the now playing sensor`() {
        assertEquals("Gran Turismo 7", PlayStationModel.game(e("sensor.zavie_lecture_en_cours", "Gran Turismo 7"), null)?.title)
        assertNull(PlayStationModel.game(e("sensor.zavie_lecture_en_cours", "unknown"), null))
        assertFalse(PlayStationModel.consoleOff(e("sensor.zavie_lecture_en_cours", "unknown")))
    }

    @Test
    fun `counts and the next level's progress`() {
        assertEquals(263, PlayStationModel.count(e("sensor.zavie_trophy_level", "263")))
        assertEquals(1814, PlayStationModel.count(e("sensor.zavie_bronze", "1814.0")))
        assertNull(PlayStationModel.count(e("sensor.x", "unavailable")))
        assertEquals(0.42f, PlayStationModel.progress(e("sensor.zavie_next_level", "42"))!!, 0.001f)
        assertEquals(1f, PlayStationModel.progress(e("sensor.x", "130"))!!, 0f)
        assertNull(PlayStationModel.progress(null))
    }
}
