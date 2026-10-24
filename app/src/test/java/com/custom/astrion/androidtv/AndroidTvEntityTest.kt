package com.custom.astrion.androidtv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a directly paired Android TV looks to cards and Activities, and what their calls send. */
class AndroidTvEntityTest {
    private val spec = AndroidTvSpec("aabbccddeeff", "media_player.androidtv_shield", "SHIELD")
    private val on =
        AndroidTvState(connected = true, isOn = true, currentApp = "com.netflix.ninja", volumeLevel = 25, volumeMax = 100, muted = false)

    @Test
    fun `media player entity looks like Home Assistant's Android TV one`() {
        val e = AndroidTvEntityMapper.media(spec, on)
        assertEquals("media_player.androidtv_shield", e.entityId)
        assertEquals("on", e.state)
        assertEquals("Netflix", e.attributes["source"])
        assertEquals("com.netflix.ninja", e.attributes["app_id"])
        assertEquals(0.25, e.attributes["volume_level"])
        assertEquals(false, e.attributes["is_volume_muted"])
        assertTrue((e.attributes["source_list"] as List<*>).contains("Disney+"))
        assertEquals("remote.androidtv_shield", AndroidTvEntityMapper.remote(spec, on).entityId)
    }

    @Test
    fun `state follows the connection and what the TV says`() {
        assertEquals("unavailable", AndroidTvEntityMapper.stateOf(AndroidTvState()))
        assertEquals("off", AndroidTvEntityMapper.stateOf(AndroidTvState(connected = true, isOn = false)))
        assertEquals("unknown", AndroidTvEntityMapper.stateOf(AndroidTvState(connected = true)))
        assertEquals(true, AndroidTvEntityMapper.media(spec, AndroidTvState(needsPairing = true)).attributes["astrion_needs_pairing"])
    }

    @Test
    fun `power is only pressed when it changes something`() {
        val off = on.copy(isOn = false)
        assertEquals(listOf(26), AndroidTvServices.media("turn_on", emptyMap(), off)!!.keys)
        assertEquals(emptyList<Int>(), AndroidTvServices.media("turn_on", emptyMap(), on)!!.keys)
        assertEquals(listOf(26), AndroidTvServices.media("turn_off", emptyMap(), on)!!.keys)
        assertEquals(emptyList<Int>(), AndroidTvServices.media("turn_off", emptyMap(), off)!!.keys)
        // Unknown state: turning on sends WAKEUP (can't turn it off), turning off sends nothing (POWER could turn it on).
        val unknown = on.copy(isOn = null)
        assertEquals(listOf(224), AndroidTvServices.media("turn_on", emptyMap(), unknown)!!.keys)
        assertEquals(emptyList<Int>(), AndroidTvServices.media("turn_off", emptyMap(), unknown)!!.keys)
    }

    @Test
    fun `apps open by name or by package`() {
        assertEquals("com.disney.disneyplus", AndroidTvServices.media("select_source", mapOf("source" to "disney+"), on)!!.launch)
        assertEquals("au.com.stan.and", AndroidTvServices.media("select_source", mapOf("source" to "au.com.stan.and"), on)!!.launch)
        val tile = mapOf("media_content_type" to "app", "media_content_id" to "com.plexapp.android")
        assertEquals("com.plexapp.android", AndroidTvServices.media("play_media", tile, on)!!.launch)
        assertNull(AndroidTvServices.media("select_source", emptyMap(), on))
        assertNull(AndroidTvServices.media("media_seek", emptyMap(), on))
    }

    @Test
    fun `remote send_command takes one key or a list, with repeats and holds`() {
        assertEquals(listOf(19), AndroidTvServices.remote("send_command", mapOf("command" to "DPAD_UP"), on)!!.keys)
        assertEquals(listOf(3, 4), AndroidTvServices.remote("send_command", mapOf("command" to listOf("HOME", "BACK", "NOPE")), on)!!.keys)
        assertEquals(
            listOf(24, 24, 24),
            AndroidTvServices.remote("send_command", mapOf("command" to "VOLUME_UP", "num_repeats" to 3.0), on)!!.keys
        )
        assertTrue(AndroidTvServices.remote("send_command", mapOf("command" to "DPAD_CENTER", "hold_secs" to 1.0), on)!!.longPress)
        assertEquals(listOf(26), AndroidTvServices.remote("turn_off", emptyMap(), on)!!.keys)
    }

    @Test
    fun `mute only toggles when it differs`() {
        assertEquals(listOf(164), AndroidTvServices.media("volume_mute", mapOf("is_volume_muted" to true), on)!!.keys)
        assertEquals(emptyList<Int>(), AndroidTvServices.media("volume_mute", mapOf("is_volume_muted" to false), on)!!.keys)
    }

    @Test
    fun `apps open through their deep link when they have one`() {
        assertEquals("netflix://", AndroidTvApps.linkFor("com.netflix.ninja"))
        assertEquals("https://www.youtube.com", AndroidTvApps.linkFor("com.google.android.youtube.tv"))
        assertEquals("mycanaltvlauncher://com.canal.android.canal/startapp", AndroidTvApps.linkFor("com.canal.android.canal"))
        assertEquals("market://launch?id=org.videolan.vlc", AndroidTvApps.linkFor("org.videolan.vlc"))
        assertEquals("https://www.canalplus.com", AndroidTvApps.linkFor("https://www.canalplus.com"))
    }

    @Test
    fun `home screens and system screens get a name, not a package`() {
        assertEquals("Home", AndroidTvApps.nameOf("com.google.android.tvlauncher"))
        assertEquals("Home", AndroidTvApps.nameOf("com.google.android.apps.tv.launcherx"))
        assertEquals("Settings", AndroidTvApps.nameOf("com.android.tv.settings"))
        assertEquals("org.videolan.vlc", AndroidTvApps.nameOf("org.videolan.vlc"))
    }
}
