package com.custom.astrion.config

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which Home Assistant entities a dashboard.json makes the remote subscribe to: the ones it
 * names anywhere, the ones cards read without naming them, and the cases that keep every entity.
 * Pure JVM: no Android, no network.
 */
class DashboardEntitiesTest {
    private fun ids(json: String, cfg: AppConfig = AppConfig(pages = emptyList())) =
        DashboardEntities.of(Json.parseToJsonElement(json), cfg)

    @Test
    fun `every string that is an entity id, wherever it is`() {
        val got =
            ids(
                """
                { "pages": [ { "name": "Home", "openWhenEntity": "binary_sensor.doorbell",
                    "cards": [ { "type": "light", "options": { "entity_id": "light.kitchen" } },
                               { "type": "scene_grid", "options": { "items": [ { "state_entity": "input_select.source" } ] } } ] } ],
                  "hotkeys": [ { "key": "VOLUME_UP", "service": "media_player.volume_up", "entity_id": "media_player.receiver" } ],
                  "power": { "screen_off_entities": [ "lock.front_door" ] } }
                """
            )!!
        listOf("binary_sensor.doorbell", "light.kitchen", "input_select.source", "media_player.receiver", "lock.front_door").forEach {
            assertTrue(it, it in got)
        }
        // A service name comes along: harmless, Home Assistant just has no such entity.
        assertTrue("media_player.volume_up" in got)
    }

    @Test
    fun `strings Home Assistant would refuse as entity ids are left out`() {
        val got =
            ids(
                """
                { "pages": [ { "name": "Home", "cards": [ { "type": "title", "options": {
                    "a": "Light.Kitchen", "b": "light._x", "c": "http://ha.local:8123", "d": "light.kitchen" } } ] } ] }
                """
            )!!
        assertFalse("Light.Kitchen" in got)
        assertFalse("light._x" in got)
        assertFalse("http://ha.local:8123" in got)
    }

    @Test
    fun `entities cards read without naming them`() {
        val got =
            ids(
                """
                { "pages": [ { "name": "Home", "cards": [
                    { "type": "tv_remote", "options": { "remote_entity": "remote.living_room" } },
                    { "type": "clock_weather", "options": {} },
                    { "type": "picture_elements", "options": { "radar": { "prefix": "sensor.radar_target", "targets": 2 } } } ] } ] }
                """
            )!!
        listOf(
            "media_player.living_room",
            "weather.forecast_home",
            "sensor.radar_target_1_x",
            "sensor.radar_target_2_y",
            "sun.sun"
        ).forEach { assertTrue(it, it in got) }
        assertFalse("sensor.radar_target_3_x" in got)
    }

    @Test
    fun `every entity when the file names none, when asked, or for a dock clock showing any player`() {
        val empty = """{ "pages": [ { "name": "Home", "cards": [ { "type": "title", "options": { "title": "Astrion" } } ] } ] }"""
        assertNull(ids(empty))
        val named = """{ "pages": [ { "name": "Home", "cards": [ { "type": "light", "options": { "entity_id": "light.kitchen" } } ] } ] }"""
        assertNull(ids(named, AppConfig(pages = emptyList(), power = PowerConfig(allEntities = true))))
        assertNull(ids(named, AppConfig(pages = emptyList(), dock = DockConfig(mode = DockConfig.MODE_CLOCK))))
        val clockWithPlayers = DockConfig(mode = DockConfig.MODE_CLOCK, mediaEntities = listOf("media_player.kitchen"))
        assertEquals(setOf("light.kitchen", "sun.sun"), ids(named, AppConfig(pages = emptyList(), dock = clockWithPlayers)))
    }
}
