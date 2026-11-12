package com.custom.astrion.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LocalTemplatesTest {
    private val sky =
        EntityState(
            "media_player.sky_salon",
            "playing",
            Json.parseToJsonElement(
                """{"media_title":"EastEnders","media_channel":"BBC One","programme_start":"20:00",""" +
                    """"media_duration":1800,"genres":["Drama"]}"""
            ).jsonObject
        )
    private val entities: EntityMap = mapOf(sky.entityId to sky)

    @Test
    fun `states and state_attr are filled from the remote's entities`() {
        assertEquals("playing", LocalTemplates.render("{{ states('media_player.sky_salon') }}", entities))
        assertEquals(
            "BBC One · 20:00 — EastEnders",
            LocalTemplates.render(
                "{{ state_attr('media_player.sky_salon', 'media_channel') }} · " +
                    "{{state_attr(\"media_player.sky_salon\",\"programme_start\")}} — " +
                    "{{ state_attr('media_player.sky_salon','media_title') }}",
                entities
            )
        )
        assertEquals("1800", LocalTemplates.render("{{ state_attr('media_player.sky_salon', 'media_duration') }}", entities))
        assertEquals("""["Drama"]""", LocalTemplates.render("{{ state_attr('media_player.sky_salon', 'genres') }}", entities))
    }

    @Test
    fun `a missing attribute is nothing alone, None inside text`() {
        assertEquals("", LocalTemplates.render("{{ state_attr('media_player.sky_salon', 'x') }}", entities))
        assertEquals("https://i/None", LocalTemplates.render("https://i/{{ state_attr('media_player.sky_salon', 'x') }}", entities))
    }

    @Test
    fun `no bare closing brace in the pattern (Android's ICU regex refuses it)`() {
        val bare = Regex("""(?<!\\)\}""").findAll(LocalTemplates.EXPR.pattern).toList()
        assertEquals(emptyList<MatchResult>(), bare)
    }

    @Test
    fun `anything else goes to Home Assistant`() {
        assertNull(LocalTemplates.render("{{ states('sensor.unknown') }}", entities))
        assertNull(LocalTemplates.render("{{ state_attr('media_player.sky_salon', 'genres') | join(', ') }}", entities))
        assertNull(LocalTemplates.render("{% if is_state('media_player.sky_salon', 'playing') %}On{% endif %}", entities))
        assertNull(LocalTemplates.render("{{ states('media_player.sky_salon', 'x') }}", entities))
        assertNull(LocalTemplates.render("{{ now() }}", entities))
    }
}
