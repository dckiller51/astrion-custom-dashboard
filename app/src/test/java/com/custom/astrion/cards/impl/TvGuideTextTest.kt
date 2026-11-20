package com.custom.astrion.cards.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TvGuideTextTest {
    @Test
    fun clockAndSpan() {
        assertEquals("13:00", TvGuideText.clock("2026-11-17T13:00:00+01:00"))
        assertNull(TvGuideText.clock("soon"))
        assertEquals("13:00 – 13:45", TvGuideText.span("2026-11-17T13:00:00+01:00", "2026-11-17T13:45:00+01:00"))
        assertEquals("13:00", TvGuideText.span("2026-11-17T13:00:00+01:00", null))
        assertNull(TvGuideText.span(null, "2026-11-17T13:45:00+01:00"))
    }

    @Test
    fun channelsConfiguredOrAllByName() {
        val entities = mapOf("sensor.epg_tf1_fr" to "TF1", "sensor.epg_france2_fr" to "France 2", "light.salon" to "Salon")
        assertEquals(listOf("sensor.epg_france2_fr", "sensor.epg_tf1_fr"), TvGuideText.channels(emptyList(), entities))
        assertEquals(listOf("sensor.epg_tf1_fr"), TvGuideText.channels(listOf("sensor.epg_tf1_fr"), entities))
    }

    @Test
    fun linkPlaysOnThePlayer() {
        val a = TvGuideText.linkAction("media_player.shield", " https://www.canalplus.com/live/?channel=313 ")!!
        assertEquals("media_player.play_media", a["service"])
        assertEquals("media_player.shield", a["entity_id"])
        assertEquals(mapOf("media_content_type" to "url", "media_content_id" to "https://www.canalplus.com/live/?channel=313"), a["data"])
        assertEquals("channel", (TvGuideText.linkAction("media_player.sky", "101")!!["data"] as Map<*, *>)["media_content_type"])
        assertNull(TvGuideText.linkAction(null, "101"))
        assertNull(TvGuideText.linkAction("media_player.sky", " "))
    }
}
