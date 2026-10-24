package com.custom.astrion.tvhelper

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.SecureRandom
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The helper's web API, without Android: requests in, answers out. */
class HelperRoutesTest {
    private class FakeDevice : HelperDevice {
        val launched = mutableListOf<String>()
        val actions = mutableListOf<String>()
        var playing = true
        override val mediaAccess = true
        override val canOpenApps = false

        override fun launch(packageName: String) = (packageName != "com.missing.app").also { if (it) launched += packageName }

        override fun media(action: String, positionMs: Long?) = playing.also { if (it) actions += "$action:$positionMs" }
    }

    private val store = NowPlayingStore()
    private val device = FakeDevice()
    private val routes = HelperRoutes("ABC234", store, device)

    private fun request(raw: String) = HttpRequest.read(ByteArrayInputStream(raw.toByteArray()))

    private fun call(method: String, target: String, code: String? = "ABC234"): Pair<Int, String> {
        val header = code?.let { "X-Astrion-Code: $it\r\n" }.orEmpty()
        val res = routes.handle(request("$method $target HTTP/1.1\r\nHost: tv\r\n$header\r\n"))
        return res.status to String(res.body)
    }

    @Test
    fun `requests are parsed, with their query decoded`() {
        val r = request("GET /launch?package=com.netflix.ninja&x=a%20b HTTP/1.1\r\nX-Astrion-Code: abc\r\n\r\n")
        assertEquals("GET", r.method)
        assertEquals("/launch", r.path)
        assertEquals("com.netflix.ninja", r.query["package"])
        assertEquals("a b", r.query["x"])
        assertEquals("abc", r.header("x-astrion-code"))
    }

    @Test(expected = IOException::class)
    fun `a request cut short is rejected`() {
        request("GET / HTTP/1.1\r\nHost: tv\r\n")
    }

    @Test
    fun `status is open, everything else needs the code`() {
        val (status, body) = call("GET", "/status", code = null)
        assertEquals(200, status)
        assertTrue(body.contains("\"app\":\"astrion-tv-helper\""))
        assertTrue(body.contains("\"mediaAccess\":true"))
        assertEquals(401, call("GET", "/now-playing", code = null).first)
        assertEquals(401, call("GET", "/now-playing", code = "ABC235").first)
        // Typed in lower case on the remote: still accepted.
        assertEquals(200, call("GET", "/now-playing?code=abc234", code = null).first)
    }

    @Test
    fun `now playing reports the session, and the cover once there is one`() {
        store.update(
            NowPlaying(
                packageName = "com.plexapp.android",
                appName = "Plex",
                state = NowPlaying.STATE_PLAYING,
                title = "Les Bad Guys 2",
                durationMs = 6_240_000,
                positionMs = 1_104_491,
                positionUpdatedAt = 1_700_000_000_000
            ),
            art = byteArrayOf(1, 2, 3),
            artChanged = true
        )
        val (_, body) = call("GET", "/now-playing")
        assertTrue(body, body.contains("\"title\":\"Les Bad Guys 2\""))
        assertTrue(body, body.contains("\"state\":\"playing\""))
        assertTrue(body, body.contains("\"positionMs\":1104491"))
        assertTrue(body, body.contains("\"artVersion\":${store.artVersion}"))
        val art = routes.handle(request("GET /art HTTP/1.1\r\nX-Astrion-Code: ABC234\r\n\r\n"))
        assertEquals("image/jpeg", art.contentType)
        assertEquals(3, art.body.size)
    }

    @Test
    fun `waiting returns at once when something changed, else after the wait`() {
        val v = store.version
        store.update(NowPlaying(state = NowPlaying.STATE_PAUSED))
        val start = System.currentTimeMillis()
        assertEquals(v + 1, store.await(v, 5_000))
        assertTrue(System.currentTimeMillis() - start < 1_000)
        // Same state again: no new version, so a waiting request isn't woken for nothing.
        store.update(NowPlaying(state = NowPlaying.STATE_PAUSED))
        assertEquals(v + 1, store.version)
        assertEquals(v + 1, store.await(v + 1, 50))
    }

    @Test
    fun `apps open by package, media actions reach the session`() {
        assertEquals(200, call("POST", "/launch?package=com.canal.android.canal").first)
        assertEquals(listOf("com.canal.android.canal"), device.launched)
        assertEquals(404, call("POST", "/launch?package=com.missing.app").first)
        assertEquals(400, call("POST", "/launch?package=not%20a%20package").first)
        assertEquals(405, call("GET", "/launch?package=com.canal.android.canal").first)
        assertEquals(200, call("POST", "/media?action=play_pause").first)
        assertEquals(200, call("POST", "/media?action=seek&position=90000").first)
        assertEquals(listOf("play_pause:null", "seek:90000"), device.actions)
        assertEquals(400, call("POST", "/media?action=seek").first)
        assertEquals(400, call("POST", "/media?action=explode").first)
        device.playing = false
        assertEquals(409, call("POST", "/media?action=pause").first)
    }

    @Test
    fun `json escapes what it must`() {
        assertEquals(
            "{\"a\":\"x\\\"y\\\\z\\n\",\"b\":null,\"c\":2,\"d\":true}",
            Json.obj("a" to "x\"y\\z\n", "b" to null, "c" to 2, "d" to true)
        )
    }

    @Test
    fun `responses carry their length`() {
        val out = ByteArrayOutputStream()
        HttpResponse.json("{}").write(out)
        val text = out.toString("UTF-8")
        assertTrue(text.startsWith("HTTP/1.1 200 OK\r\n"))
        assertTrue(text.contains("Content-Length: 2\r\n"))
        assertTrue(text.endsWith("\r\n\r\n{}"))
    }

    @Test
    fun `codes use only unambiguous characters`() {
        val random = SecureRandom()
        repeat(50) {
            val code = HelperCode.generate(random)
            assertEquals(6, code.length)
            assertTrue(code.all { it in HelperCode.ALPHABET })
            assertFalse(code.any { it in "O0I1" })
        }
    }
}
