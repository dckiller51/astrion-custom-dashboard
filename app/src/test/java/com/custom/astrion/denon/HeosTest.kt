package com.custom.astrion.denon

import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** HEOS (1.5.4): the CLI's messages, the entity with what plays, and a connection to a fake receiver. */
class HeosTest {
    @Test
    fun `commands and answers`() {
        assertEquals("heos://player/set_play_state?pid=-1234&state=play\r\n", HeosProtocol.setPlayState(-1234, "play"))
        assertEquals("heos://browse/browse?sid=1028\r\n", HeosProtocol.favorites())
        val state = HeosProtocol.parse("""{"heos":{"command":"event/player_state_changed","message":"pid=-1234&state=pause"}}""")!!
        assertEquals("pause", state.params["state"])
        assertTrue(
            HeosProtocol.parse(
                """{"heos":{"command":"player/play_next","result":"success","message":"command under process&pid=1"}}"""
            )!!.pending
        )
        val players =
            HeosProtocol.players(
                HeosProtocol.parse(
                    """{"heos":{"command":"player/get_players","result":"success","message":""},"payload":[""" +
                        """{"name":"Kitchen","pid":11,"model":"HEOS 1","ip":"192.168.1.20"},""" +
                        """{"name":"Salon","pid":-1234,"model":"Denon AVC-X4800H","ip":"192.168.1.60"}]}"""
                )!!
            )
        assertEquals(-1234L, HeosProtocol.playerFor(players, "192.168.1.60")!!.pid)
        val np =
            HeosProtocol.nowPlaying(
                HeosProtocol.parse(
                    """{"heos":{"command":"player/get_now_playing_media","result":"success","message":"pid=-1234"},""" +
                        """"payload":{"type":"song","song":"Get Lucky","album":"Random Access Memories","artist":"Daft Punk",""" +
                        """"image_url":"http://img/x.jpg","mid":"1","qid":1,"sid":3}}"""
                )!!
            )!!
        assertEquals("Get Lucky", np.title)
        assertEquals("Daft Punk", np.artist)
        assertEquals("Mon artiste & co", HeosProtocol.params("artist=Mon%20artiste%20%26%20co")["artist"])
    }

    @Test
    fun `the media player shows HEOS while on a HEOS input`() {
        val spec = DenonSpec("d", "media_player.denon_salon", "Salon")
        val heos =
            HeosState(
                connected = true,
                pid = -1234,
                playState = "play",
                nowPlaying = HeosProtocol.NowPlaying("Get Lucky", "Daft Punk", "RAM", "http://img/x.jpg", null, "song"),
                repeat = "on_all",
                shuffle = false,
                favorites = listOf(HeosProtocol.Favorite("FIP", "http://img/fip.png"), HeosProtocol.Favorite("Jazz", null))
            )
        val on =
            DenonState(reachable = true, on = true, input = "NET", inputs = listOf(DenonProtocol.Input("NET", "HEOS Music")), heos = heos)
        val m = DenonEntityMapper.media(spec, on)
        assertEquals("playing", m.state)
        assertEquals("Get Lucky", m.attributes["media_title"])
        assertEquals("http://img/x.jpg", m.attributes["entity_picture"])
        assertEquals("all", m.attributes["repeat"])
        assertEquals(listOf("HEOS Music", "FIP", "Jazz"), m.attributes["source_list"])
        // On a game console's input: the receiver, not HEOS.
        assertEquals("on", DenonEntityMapper.media(spec, on.copy(input = "GAME")).state)
        assertEquals(listOf("heos:pause"), DenonServices.media("media_play_pause", emptyMap(), on))
        assertEquals(listOf("heos:preset:2"), DenonServices.media("select_source", mapOf("source" to "jazz"), on))
        assertEquals(listOf("SINET"), DenonServices.media("select_source", mapOf("source" to "HEOS Music"), on))
        assertEquals(listOf("heos:mode:on_all:on"), DenonServices.media("shuffle_set", mapOf("shuffle" to true), on))
        // Without HEOS: the receiver's own network player keys.
        assertEquals(listOf("NS9A"), DenonServices.media("media_play", emptyMap(), on.copy(heos = HeosState())))
    }

    @Test
    fun `a connection to a fake receiver`() {
        val server = ServerSocket(0)
        val got = mutableListOf<String>()
        val served = Thread {
            server.accept().use { s ->
                val r = s.getInputStream().bufferedReader()
                val w = s.getOutputStream()
                fun send(json: String) = w.write((json + "\r\n").toByteArray())

                fun answer(command: String, message: String, payload: String? = null) {
                    val head = """{"heos":{"command":"$command","result":"success","message":"$message"}"""
                    send(head + (payload?.let { ""","payload":$it""" } ?: "") + "}")
                }
                repeat(2) { got += r.readLine() } // register + get_players
                answer("system/register_for_change_events", "enable=on")
                answer("player/get_players", "", """[{"name":"Salon","pid":-7,"ip":"127.0.0.1"}]""")
                repeat(4) { got += r.readLine() } // play state, now playing, play mode, favorites
                answer("player/get_play_state", "pid=-7&state=play")
                answer("player/get_now_playing_media", "pid=-7", """{"type":"station","station":"FIP"}""")
                answer("browse/browse", "sid=1028", """[{"name":"FIP","image_url":"u"}]""")
                got += r.readLine() // the pause sent below
                Thread.sleep(300)
            }
        }
        served.start()
        val ready = CountDownLatch(1)
        lateinit var conn: HeosConnection
        conn =
            HeosConnection("127.0.0.1", onChange = { st ->
                if (st.favorites.isNotEmpty() && st.nowPlaying != null && st.playState == "play") ready.countDown()
            }, port = server.localPort)
        conn.start()
        assertTrue(ready.await(5, TimeUnit.SECONDS))
        assertEquals(-7L, conn.state.pid)
        assertEquals("FIP", conn.state.nowPlaying!!.station)
        assertTrue(conn.state.active)
        assertTrue(conn.send(HeosProtocol.setPlayState(-7, "pause")))
        served.join(3000)
        conn.stop()
        assertEquals("heos://system/register_for_change_events?enable=on", got[0])
        assertEquals("heos://player/get_now_playing_media?pid=-7", got[3])
        assertEquals("heos://player/set_play_state?pid=-7&state=pause", got.last())
    }
}
