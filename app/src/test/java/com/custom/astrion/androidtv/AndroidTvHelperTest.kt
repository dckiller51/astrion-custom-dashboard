package com.custom.astrion.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** What the Astrion TV Helper adds to a TV's entity: title, cover, position, playing/paused. */
class AndroidTvHelperTest {
    private val spec = AndroidTvSpec("aabbccddeeff", "media_player.androidtv_shield", "SHIELD")
    private val plexInFront = AndroidTvState(connected = true, isOn = true, currentApp = "com.plexapp.android")

    private val plexAnswer =
        """{"version":7,"package":"com.plexapp.android","app":"Plex","state":"playing","title":"Les Bad Guys 2",""" +
            """"subtitle":null,"artist":null,"album":null,"durationMs":6240000,"positionMs":1104491,""" +
            """"positionUpdatedAt":1792850000000,"speed":1.0,"artVersion":7,"artUrl":null,"future":"ignored"}"""

    private fun connected(info: AndroidTvHelperInfo?) = AndroidTvHelperState(HelperLink.CONNECTED, info)

    @Test
    fun `the helper's answer is read, unknown fields ignored`() {
        val info = AndroidTvHelperInfo.parse(plexAnswer)!!
        assertEquals(7L, info.version)
        assertEquals("com.plexapp.android", info.packageName)
        assertEquals("playing", info.state)
        assertEquals("Les Bad Guys 2", info.title)
        assertNull(info.subtitle)
        assertEquals(1_104_491L, info.positionMs)
        assertEquals(7L, info.artVersion)
        assertNull(AndroidTvHelperInfo.parse("<html>not the helper</html>"))
        assertNull(AndroidTvHelperInfo.parse("""{"state":"playing"}"""))
        assertTrue(AndroidTvHelperInfo.isHelperStatus("""{"app":"astrion-tv-helper","api":1}"""))
        assertFalse(AndroidTvHelperInfo.isHelperStatus("""{"app":"something-else"}"""))
    }

    @Test
    fun `what's playing in the app in front fills the media player`() {
        val e = AndroidTvEntityMapper.media(spec, plexInFront, connected(AndroidTvHelperInfo.parse(plexAnswer)))
        assertEquals("playing", e.state)
        assertEquals("Les Bad Guys 2", e.attributes["media_title"])
        assertEquals(6240, e.attributes["media_duration"])
        assertEquals(1104, e.attributes["media_position"])
        assertTrue((e.attributes["media_position_updated_at"] as String).startsWith("2026-10-"))
        assertEquals("http://127.0.0.1:8080/androidtv-artwork/aabbccddeeff?v=7", e.attributes["entity_picture"])
        assertEquals("connected", e.attributes["astrion_helper"])
        assertTrue((e.attributes["supported_features"] as Int) and 2 != 0) // SEEK
    }

    @Test
    fun `a session left behind by another app is ignored`() {
        val onHome = plexInFront.copy(currentApp = "com.google.android.tvlauncher")
        val e = AndroidTvEntityMapper.media(spec, onHome, connected(AndroidTvHelperInfo.parse(plexAnswer)))
        assertEquals("on", e.state)
        assertNull(e.attributes["media_title"])
        assertNull(e.attributes["entity_picture"])
    }

    @Test
    fun `without the helper, or with a TV off, nothing changes`() {
        val plain = AndroidTvEntityMapper.media(spec, plexInFront)
        assertEquals("on", plain.state)
        assertNull(plain.attributes["astrion_helper"])
        assertEquals(0, (plain.attributes["supported_features"] as Int) and 2)
        val off = AndroidTvEntityMapper.media(spec, plexInFront.copy(isOn = false), connected(AndroidTvHelperInfo.parse(plexAnswer)))
        assertEquals("off", off.state)
        val lost = AndroidTvEntityMapper.media(spec, plexInFront, AndroidTvHelperState(HelperLink.UNREACHABLE, null))
        assertEquals("on", lost.state)
        assertEquals("unreachable", lost.attributes["astrion_helper"])
    }

    @Test
    fun `myCANAL-like sessions give paused without a title`() {
        val canal = AndroidTvState(connected = true, isOn = true, currentApp = "com.canal.android.canal")
        val info = AndroidTvHelperInfo.parse("""{"version":3,"package":"com.canal.android.canal","state":"paused","positionMs":null}""")
        val e = AndroidTvEntityMapper.media(spec, canal, connected(info))
        assertEquals("paused", e.state)
        assertNull(e.attributes["media_title"])
        assertEquals("myCANAL", e.attributes["app_name"])
    }

    @Test
    fun `seeking goes through the helper, in milliseconds`() {
        assertEquals(90_500L, AndroidTvServices.media("media_seek", mapOf("seek_position" to 90.5), plexInFront)!!.seekMs)
        assertNull(AndroidTvServices.media("media_seek", emptyMap(), plexInFront))
    }
}
