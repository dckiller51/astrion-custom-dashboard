package com.custom.astrion.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyWakeTest {
    private fun device(id: String, name: String) = SpotifyDevice(id, name, "TV", active = false, volume = null, supportsVolume = true)

    private val appleTv = SpotifyWake.Player("media_player.apple_tv_salon", "Apple TV Salon", "off", "Spotify")
    private val shield = SpotifyWake.Player("media_player.shield", "Shield", "on", "Spotify")

    @Test
    fun `Spotify is found in a source list`() {
        assertEquals("Spotify", SpotifyWake.spotifySource(listOf("Netflix", "Spotify", "YouTube")))
        assertEquals("Spotify - Music", SpotifyWake.spotifySource(listOf("Netflix", "Spotify - Music")))
        assertNull(SpotifyWake.spotifySource(listOf("Netflix", "HDMI 1")))
    }

    @Test
    fun `names of one device`() {
        assertTrue(SpotifyWake.same("Apple TV Salon", "Salon Apple TV"))
        assertTrue(SpotifyWake.same("Apple TV", "Apple TV Salon"))
        assertTrue(SpotifyWake.same("Shield", "SHIELD"))
        // A lone word isn't enough: the living room's speaker isn't its TV.
        assertFalse(SpotifyWake.same("Salon", "Apple TV Salon"))
        assertFalse(SpotifyWake.same("Shield", "Apple TV"))
    }

    @Test
    fun `a player already listed by Spotify isn't offered again`() {
        val devices = listOf(device("d1", "Salon Apple TV"), device("d2", "Kitchen speaker"))
        assertEquals(listOf("Shield"), SpotifyWake.wakeable(listOf(appleTv, shield), devices, emptyMap()).map { it.name })
        // Woken once as "Living room": Spotify's own name for it.
        val renamed = listOf(device("d3", "Living room"))
        assertEquals(listOf("Apple TV Salon"), SpotifyWake.wakeable(listOf(appleTv), renamed, emptyMap()).map { it.name })
        assertTrue(SpotifyWake.wakeable(listOf(appleTv), renamed, mapOf(appleTv.entityId to "d3")).isEmpty())
    }

    @Test
    fun `the device a player became`() {
        val before = setOf("d1")
        // Named like it.
        val named = listOf(device("d1", "Kitchen speaker"), device("d2", "Phone"), device("d3", "Apple TV Salon"))
        assertEquals("d3", SpotifyWake.found(appleTv, before, named)?.id)
        // Named otherwise, but the only new one.
        val other = listOf(device("d1", "Kitchen speaker"), device("d4", "Living room"))
        assertEquals("d4", SpotifyWake.found(appleTv, before, other)?.id)
        // Two new ones, neither named like it: none.
        val two = other + device("d5", "Phone")
        assertNull(SpotifyWake.found(appleTv, before, two))
        assertNull(SpotifyWake.found(appleTv, before, listOf(device("d1", "Kitchen speaker"))))
    }

    @Test
    fun `asleep players are turned on first`() {
        assertTrue(SpotifyWake.asleep("off"))
        assertTrue(SpotifyWake.asleep("standby"))
        assertFalse(SpotifyWake.asleep("on"))
        assertFalse(SpotifyWake.asleep("idle"))
    }

    @Test
    fun `players are offered after the devices`() {
        val (_, a) = SpotifyProtocol.entity("Spotify", null, listOf(device("d1", "Kitchen speaker")), "now", listOf("Apple TV Salon"))
        assertEquals(listOf("Kitchen speaker", "Apple TV Salon"), a["source_list"])
    }
}
