package com.custom.astrion.denon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DenonZonesTest {
    private val inputs = listOf(DenonProtocol.Input("SAT/CBL", "Freebox"), DenonProtocol.Input("TUNER", "Tuner"))
    private val spec = DenonSpec("salon", "media_player.denon_salon", "Salon", zones = 3)

    private fun state(zone2: DenonProtocol.ZoneStatus?) = DenonState(
        reachable = true,
        on = true,
        inputs = inputs,
        zones = listOfNotNull(zone2?.let { 2 to it }).toMap()
    )

    @Test
    fun `a zone's Lite status page is read`() {
        val xml = "<item><Power><value>ON</value></Power><InputFuncSelect><value>TUNER</value></InputFuncSelect>" +
            "<MasterVolume><value>-40.0</value></MasterVolume><Mute><value>off</value></Mute></item>"
        assertEquals(DenonProtocol.ZoneStatus(true, "TUNER", -40.0, false), DenonProtocol.zoneStatus(xml))
        assertNull(DenonProtocol.zoneStatus("<item></item>"))
        assertEquals("/goform/formZone2_Zone2XmlStatusLite.xml", DenonProtocol.zoneStatusPath(2))
    }

    @Test
    fun `a zone is its own media player`() {
        val e = DenonEntityMapper.zone(spec, state(DenonProtocol.ZoneStatus(true, "TUNER", -40.0, true)), 2)
        assertEquals("media_player.denon_salon_zone2", e.entityId)
        assertEquals("on", e.state)
        assertEquals("Salon Zone 2", e.attributes["friendly_name"])
        assertEquals("Tuner", e.attributes["source"])
        assertEquals(true, e.attributes["is_volume_muted"])
        assertEquals(listOf("Freebox", "Tuner"), e.attributes["source_list"])
        assertEquals("off", DenonEntityMapper.zone(spec, state(null), 3).state)
    }

    @Test
    fun `a zone's services are its own commands`() {
        val s = state(DenonProtocol.ZoneStatus(true, "TUNER", -40.0, false))
        assertEquals(listOf("Z2ON"), DenonServices.zone("turn_on", emptyMap(), s, 2))
        assertEquals(listOf("Z3OFF"), DenonServices.zone("turn_off", emptyMap(), s, 3))
        assertEquals(listOf("Z2OFF"), DenonServices.zone("toggle", emptyMap(), s, 2))
        assertEquals(listOf("Z2UP"), DenonServices.zone("volume_up", emptyMap(), s, 2))
        assertEquals(listOf("Z2MUON"), DenonServices.zone("volume_mute", emptyMap(), s, 2))
        assertEquals(listOf("Z2MUOFF"), DenonServices.zone("volume_mute", mapOf("is_volume_muted" to false), s, 2))
        assertEquals(listOf("Z250"), DenonServices.zone("volume_set", mapOf("volume_level" to 0.5), s, 2))
        assertEquals(listOf("Z2SAT/CBL"), DenonServices.zone("select_source", mapOf("source" to "Freebox"), s, 2))
        assertNull(DenonServices.zone("media_play", emptyMap(), s, 2))
    }
}
