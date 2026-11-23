package com.custom.astrion.playnite

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlayniteProtocolTest {
    private val spec = PlayniteSpec("pc", "media_player.playnite_salon", "Salon", "192.168.1.60", 8095)
    private val hadesId = "11111111-1111-1111-1111-111111111111"
    private val hades = PlayniteGame(hadesId, "Hades", cover = true, playtime = 3600, lastPlayed = 1793487600)
    private val celeste = PlayniteGame("22222222-2222-2222-2222-222222222222", "Celeste", cover = false)

    @Test
    fun `the extension's answers are read`() {
        val infoText = """{"app":"playnite","plugin":"0.1.0","name":"GAMING-PC","port":8095,"macs":["aa:bb:cc:dd:ee:ff"]}"""
        val info = PlayniteProtocol.info(infoText)
        assertEquals("GAMING-PC", info?.name)
        assertEquals(listOf("aa:bb:cc:dd:ee:ff"), info?.macs)
        assertNull(PlayniteProtocol.info("""{"app":"something else"}"""))
        assertNull(PlayniteProtocol.info("<html>"))

        val state = """{"running":{"id":"$hadesId","name":"Hades","cover":true,"playtime":3600,"lastPlayed":1793487600}}"""
        val running = PlayniteProtocol.running(state)
        assertEquals(hades, running)
        assertNull(PlayniteProtocol.running("""{"running":null}"""))

        val list = """{"games":[{"id":"$hadesId","name":"Hades \"II\"","cover":true},{"id":"${celeste.id}","name":"Celeste"}]}"""
        val games = PlayniteProtocol.games(list)
        assertEquals(listOf("Hades \"II\"", "Celeste"), games?.map { it.name })
        assertNull(PlayniteProtocol.games("{}"))
    }

    @Test
    fun `the PC as a media player`() {
        assertEquals("off", PlayniteProtocol.stateOf(PlayniteState()))
        val on = PlayniteState(reachable = true, games = listOf(hades, celeste))
        assertEquals("on", PlayniteProtocol.stateOf(on))
        assertEquals(listOf("Hades", "Celeste"), PlayniteProtocol.media(spec, on)["source_list"])

        val playing = on.copy(running = hades)
        assertEquals("playing", PlayniteProtocol.stateOf(playing))
        val a = PlayniteProtocol.media(spec, playing)
        assertEquals("Hades", a["media_title"])
        assertEquals("Hades", a["source"])
        assertEquals("game", a["media_content_type"])
        assertEquals("http://192.168.1.60:8095/cover/${hades.id}", a["entity_picture"])
        assertFalse(PlayniteProtocol.media(spec, on.copy(running = celeste)).containsKey("entity_picture"))
        // Off: the games last seen stay offered; none known, none offered.
        assertEquals(listOf("Hades", "Celeste"), PlayniteProtocol.media(spec, on.copy(reachable = false))["source_list"])
        assertFalse(PlayniteProtocol.media(spec, PlayniteState()).containsKey("source_list"))
    }

    @Test
    fun `one source per name, the most recent first`() {
        val epic = hades.copy(id = "33333333-3333-3333-3333-333333333333")
        val s = PlayniteState(reachable = true, games = listOf(hades, epic, celeste))
        assertEquals(listOf("Hades", "Celeste"), PlayniteProtocol.sources(s).map { it.name })
        assertEquals(hades.id, PlayniteProtocol.gameNamed(s, "hades")?.id)
        val many = PlayniteState(reachable = true, games = (1..150).map { PlayniteGame("id$it", "Game $it", false) })
        assertEquals(PlayniteProtocol.MAX_SOURCES, PlayniteProtocol.sources(many).size)
    }

    @Test
    fun `the games kept on the remote read back the same`() {
        val games = listOf(hades, celeste.copy(name = "Céleste \"2\""))
        assertEquals(games, PlayniteProtocol.games(PlayniteProtocol.gamesJson(games)))
    }

    @Test
    fun `services become actions`() {
        val off = PlayniteState()
        val on = PlayniteState(reachable = true, games = listOf(hades, celeste))
        assertEquals(PlayniteAction.Start(celeste), PlayniteProtocol.service("select_source", mapOf("source" to "Celeste"), on))
        assertNull(PlayniteProtocol.service("select_source", mapOf("source" to "Portal"), on))
        // Picked while the PC is off: wake it, then start the game.
        val offWithGames = on.copy(reachable = false)
        val picked = PlayniteProtocol.service("select_source", mapOf("source" to "Celeste"), offWithGames)
        assertEquals(PlayniteAction.WakeAndStart(celeste), picked)
        assertEquals(PlayniteAction.Wake, PlayniteProtocol.service("turn_on", emptyMap(), off))
        assertNull(PlayniteProtocol.service("turn_on", emptyMap(), on))
        assertEquals(PlayniteAction.Power("shutdown"), PlayniteProtocol.service("turn_off", emptyMap(), on))
        assertNull(PlayniteProtocol.service("turn_off", emptyMap(), off))
        assertTrue(PlayniteProtocol.service("media_play", emptyMap(), on) == null)
    }
}
