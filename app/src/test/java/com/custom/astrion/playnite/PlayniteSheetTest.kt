package com.custom.astrion.playnite

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class PlayniteSheetTest {
    private val gowId = "33333333-3333-3333-3333-333333333333"
    private val gow = PlayniteGame(gowId, "God of War", cover = true, playtime = 7200, lastPlayed = 1793487600)

    @Test
    fun `a sheet is read and kept as is`() {
        val text = """{"id":"$gowId","name":"God of War","description":"Kratos & Atreus.\n\nGo north.","genres":["Action","RPG"],""" +
            """"developers":["Santa Monica Studio"],"publishers":[],"platforms":["PC"],"year":2018,"critic":94,"community":0,""" +
            """"playtime":3600,"lastPlayed":0,"cover":true,"background":true}"""
        val d = PlayniteSheet.details(text)!!
        assertEquals(listOf("Action", "RPG"), d.genres)
        assertEquals(2018, d.year)
        assertEquals("Kratos & Atreus.\n\nGo north.", d.description)
        assertEquals(d, PlayniteSheet.details(PlayniteSheet.detailsJson(d)))
        assertNull(PlayniteSheet.details("""{"error":"no such game"}"""))
    }

    @Test
    fun `the answer falls back on the games list`() {
        val spec = PlayniteSpec("pc", "media_player.playnite_salon", "Salon", "192.168.1.60", 8095)
        val known =
            PlayniteDetails(gowId, "God of War", developers = emptyList(), publishers = listOf("Sony"), playtime = 3600, background = true)
        val a = PlayniteSheet.answer(gow, known, "/data/c.jpg", PlayniteSheet.backgroundUrl(spec, gowId), reachable = true, running = false)
        assertEquals(JsonPrimitive(true), a["success"])
        val r = a["result"] as JsonObject
        assertEquals(JsonArray(listOf(JsonPrimitive("Sony"))), r["studios"])
        assertEquals(JsonPrimitive(7200L), r["playtime"]) // the games list's, fresher
        assertEquals(JsonPrimitive("http://192.168.1.60:8095/background/$gowId"), r["background"])

        val off = PlayniteSheet.answer(gow, null, null, null, reachable = false, running = false)["result"] as JsonObject
        assertEquals(JsonPrimitive(""), off["description"])
        assertEquals(JsonPrimitive(false), off["reachable"])
        assertFalse(off.containsKey("background"))
    }
}
