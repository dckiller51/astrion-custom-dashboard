package com.custom.astrion.hue

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HueEntitiesTest {
    /** A small home as `GET /clip/v2/resource` gives it: a colour bulb and a plug in a room, a zone, the home, two scenes. */
    private val bridge =
        """
        {"errors": [], "data": [
          {"id": "d-bulb", "type": "device", "metadata": {"name": "Lampe salon", "archetype": "sultan_bulb"},
           "services": [{"rid": "l-bulb", "rtype": "light"}, {"rid": "z-bulb", "rtype": "zigbee_connectivity"}]},
          {"id": "z-bulb", "type": "zigbee_connectivity", "status": "connected"},
          {"id": "l-bulb", "type": "light", "owner": {"rid": "d-bulb", "rtype": "device"},
           "metadata": {"name": "Lampe salon", "archetype": "sultan_bulb"},
           "on": {"on": true}, "dimming": {"brightness": 50.0, "min_dim_level": 0.2},
           "color_temperature": {"mirek": 366, "mirek_valid": true, "mirek_schema": {"mirek_minimum": 153, "mirek_maximum": 500}},
           "color": {"xy": {"x": 0.4573, "y": 0.41}, "gamut_type": "C"},
           "effects": {"status": "no_effect", "status_values": ["no_effect", "candle", "fire"], "effect_values": ["no_effect", "candle", "fire"]}},
          {"id": "d-plug", "type": "device", "metadata": {"name": "Prise Mi Box"},
           "services": [{"rid": "l-plug", "rtype": "light"}, {"rid": "z-plug", "rtype": "zigbee_connectivity"}]},
          {"id": "z-plug", "type": "zigbee_connectivity", "status": "connectivity_issue"},
          {"id": "l-plug", "type": "light", "owner": {"rid": "d-plug", "rtype": "device"},
           "metadata": {"name": "Prise Mi Box", "archetype": "plug"}, "on": {"on": false}},
          {"id": "r-salon", "type": "room", "metadata": {"name": "Salle à manger", "archetype": "living_room"},
           "children": [{"rid": "d-bulb", "rtype": "device"}, {"rid": "d-plug", "rtype": "device"}],
           "services": [{"rid": "g-salon", "rtype": "grouped_light"}]},
          {"id": "g-salon", "type": "grouped_light", "owner": {"rid": "r-salon", "rtype": "room"},
           "on": {"on": true}, "dimming": {"brightness": 50.0}},
          {"id": "zone-tv", "type": "zone", "metadata": {"name": "Coin TV"},
           "children": [{"rid": "l-bulb", "rtype": "light"}], "services": [{"rid": "g-tv", "rtype": "grouped_light"}]},
          {"id": "g-tv", "type": "grouped_light", "owner": {"rid": "zone-tv", "rtype": "zone"}, "on": {"on": true}},
          {"id": "home", "type": "bridge_home", "children": [], "services": [{"rid": "g-home", "rtype": "grouped_light"}]},
          {"id": "g-home", "type": "grouped_light", "owner": {"rid": "home", "rtype": "bridge_home"}, "on": {"on": true}},
          {"id": "s-relax", "type": "scene", "metadata": {"name": "Détente"}, "group": {"rid": "r-salon", "rtype": "room"},
           "status": {"active": "static"}},
          {"id": "s-film", "type": "scene", "metadata": {"name": "Film"}, "group": {"rid": "zone-tv", "rtype": "zone"},
           "status": {"active": "inactive"}}
        ]}
        """.trimIndent()

    private fun store() = HueStore().apply { assertTrue(load(bridge)) }

    private fun entities(s: HueStore = store()) = HueEntities.entities(s, HueEntities.assignIds(s, emptyMap())).associateBy { it.entityId }

    @Test
    fun `ids from names, accents and spaces made plain, kept once known`() {
        val s = store()
        val ids = HueEntities.assignIds(s, emptyMap())
        assertEquals("light.hue_lampe_salon", ids["l-bulb"])
        assertEquals("light.hue_prise_mi_box", ids["l-plug"])
        assertEquals("light.hue_salle_a_manger", ids["g-salon"])
        assertEquals("light.hue_coin_tv", ids["g-tv"])
        assertEquals("light.hue_all", ids["g-home"])
        assertEquals("scene.hue_salle_a_manger_detente", ids["s-relax"])
        // Renamed in the Hue app: the id stays; a clash gets a number.
        s.merge("l-bulb", obj("""{"metadata": {"name": "Coin TV"}}"""))
        val again = HueEntities.assignIds(s, mapOf("l-bulb" to "light.hue_lampe_salon"))
        assertEquals("light.hue_lampe_salon", again["l-bulb"])
        val fresh = HueEntities.assignIds(s, emptyMap())
        assertEquals(setOf("light.hue_coin_tv", "light.hue_coin_tv_2"), setOf(fresh["l-bulb"], fresh["g-tv"]))
        // Another bridge's ids are left alone.
        assertEquals("light.hue_all_2", HueEntities.assignIds(s, emptyMap(), setOf("light.hue_all"))["g-home"])
    }

    @Test
    fun `a colour bulb as the light card reads it`() {
        val e = entities().getValue("light.hue_lampe_salon")
        assertEquals("on", e.state)
        assertEquals(listOf("color_temp", "xy"), e.attributes["supported_color_modes"])
        assertEquals("color_temp", e.attributes["color_mode"])
        assertEquals(128, e.attributes["brightness"])
        assertEquals(2732, e.attributes["color_temp_kelvin"])
        assertEquals(2000, e.attributes["min_color_temp_kelvin"])
        assertEquals(6536, e.attributes["max_color_temp_kelvin"])
        assertEquals(listOf("off", "candle", "fire"), e.attributes["effect_list"])
        assertEquals("off", e.attributes["effect"])
        assertTrue((e.attributes["rgb_color"] as List<*>).size == 3)
    }

    @Test
    fun `a colour set gives xy mode and its rgb`() {
        val s = store()
        s.merge(
            "l-bulb",
            obj("""{"color": {"xy": {"x": 0.675, "y": 0.322}}, "color_temperature": {"mirek": null, "mirek_valid": false}}""")
        )
        val e = entities(s).getValue("light.hue_lampe_salon")
        assertEquals("xy", e.attributes["color_mode"])
        val (r, g, b) = (e.attributes["rgb_color"] as List<*>).map { it as Int }
        assertTrue("red: $r $g $b", r == 255 && g < 80 && b < 80)
    }

    @Test
    fun `a plug off its zigbee link is unavailable, on and off only`() {
        val e = entities().getValue("light.hue_prise_mi_box")
        assertEquals(HueEntities.UNAVAILABLE, e.state)
        assertEquals(listOf("onoff"), e.attributes["supported_color_modes"])
        assertNull(e.attributes["brightness"])
    }

    @Test
    fun `rooms, zones and the home as groups with their lights`() {
        val all = entities()
        val room = all.getValue("light.hue_salle_a_manger")
        assertEquals("on", room.state)
        assertEquals("Salle à manger", room.attributes["friendly_name"])
        assertEquals(listOf("light.hue_lampe_salon", "light.hue_prise_mi_box"), room.attributes["entity_id"])
        assertEquals(listOf("color_temp", "xy"), room.attributes["supported_color_modes"])
        assertEquals(true, room.attributes["is_hue_group"])
        assertEquals("room", room.attributes["hue_type"])
        assertEquals(listOf("light.hue_lampe_salon"), all.getValue("light.hue_coin_tv").attributes["entity_id"])
        assertEquals(HueEntities.ALL_LIGHTS, all.getValue("light.hue_all").attributes["friendly_name"])
    }

    @Test
    fun `scenes say whether they're on`() {
        val all = entities()
        val relax = all.getValue("scene.hue_salle_a_manger_detente")
        assertEquals("on", relax.state)
        assertEquals("Salle à manger Détente", relax.attributes["friendly_name"])
        assertEquals("off", all.getValue("scene.hue_coin_tv_film").state)
    }

    @Test
    fun `the event stream's changes are merged`() {
        val s = store()
        val changed =
            s.apply(
                """[{"creationtime": "2026-11-20T10:00:00Z", "id": "e1", "type": "update",
                    "data": [{"id": "l-bulb", "type": "light", "on": {"on": false}, "owner": {"rid": "d-bulb", "rtype": "device"}}]},
                   {"id": "e2", "type": "delete", "data": [{"id": "s-film", "type": "scene"}]}]"""
            )
        assertTrue(changed)
        val light = s["l-bulb"]!!
        assertFalse(light.obj("on")!!["on"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(50.0, light.obj("dimming")!!["brightness"]!!.jsonPrimitive.double, 0.0)
        assertNull(s["s-film"])
        assertFalse(s.apply("not json"))
    }

    @Test
    fun `light card calls become the bridge's bodies`() {
        val bulb = store()["l-bulb"]
        fun cmd(service: String, vararg data: Pair<String, Any?>) = HueEntities.lightCommand(service, mapOf(*data), bulb)!!
        assertEquals(30.0, cmd("turn_on", "brightness_pct" to 30)["dimming"]!!.jsonObject["brightness"]!!.jsonPrimitive.double, 0.0)
        assertEquals(false, cmd("turn_on", "brightness_pct" to 0)["on"]!!.jsonObject["on"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(370, cmd("turn_on", "color_temp_kelvin" to 2700)["color_temperature"]!!.jsonObject["mirek"]!!.jsonPrimitive.int)
        // Colder than the bulb can: its coolest.
        assertEquals(153, cmd("turn_on", "color_temp_kelvin" to 10000)["color_temperature"]!!.jsonObject["mirek"]!!.jsonPrimitive.int)
        val red = cmd("turn_on", "rgb_color" to listOf(255, 0, 0))["color"]!!.jsonObject["xy"]!!.jsonObject
        assertEquals(0.7006, red["x"]!!.jsonPrimitive.double, 0.01)
        assertEquals(0.2993, red["y"]!!.jsonPrimitive.double, 0.01)
        val wheel = cmd("turn_on", "hs_color" to listOf(240, 100))["color"]!!.jsonObject["xy"]!!.jsonObject
        assertTrue(wheel["x"]!!.jsonPrimitive.double < 0.2)
        assertEquals("candle", cmd("turn_on", "effect" to "candle")["effects"]!!.jsonObject["effect"]!!.jsonPrimitive.content)
        assertEquals("no_effect", cmd("turn_on", "effect" to "off")["effects"]!!.jsonObject["effect"]!!.jsonPrimitive.content)
        assertEquals(2000, cmd("turn_off", "transition" to 2)["dynamics"]!!.jsonObject["duration"]!!.jsonPrimitive.int)
        // Toggle: the bulb is on, so off.
        assertEquals(false, cmd("toggle")["on"]!!.jsonObject["on"]!!.jsonPrimitive.content.toBoolean())
        // A group takes no effect.
        assertNull(HueEntities.lightCommand("turn_on", mapOf("effect" to "candle"), null, group = true)!!["effects"])
        assertNull(HueEntities.lightCommand("set_value", emptyMap(), bulb))
    }

    @Test
    fun `scenes are recalled`() {
        val body = HueEntities.sceneCommand("turn_on", mapOf("transition" to 1.5))!!["recall"]!!.jsonObject
        assertEquals("active", body["action"]!!.jsonPrimitive.content)
        assertEquals(1500, body["duration"]!!.jsonPrimitive.int)
        assertEquals(
            "dynamic_palette",
            HueEntities.sceneCommand("turn_on", mapOf("dynamic" to true))!!["recall"]!!.jsonObject["action"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `what a command changes shows at once`() {
        val s = store()
        val body = HueEntities.lightCommand("turn_on", mapOf("rgb_color" to listOf(0, 0, 255)), s["l-bulb"])!!
        s.merge("l-bulb", HueEntities.optimistic(body))
        assertEquals("xy", entities(s).getValue("light.hue_lampe_salon").attributes["color_mode"])
    }

    @Test
    fun `colours round trip`() {
        val (x, y) = HueColor.rgbToXy(255, 255, 255)
        assertEquals(0.3227, x, 0.01)
        assertEquals(0.329, y, 0.01)
        assertEquals(Triple(255, 0, 0), HueColor.hsToRgb(0.0, 100.0))
        assertEquals(Triple(0, 0, 255), HueColor.hsToRgb(240.0, 100.0))
        assertEquals(240.0 to 100.0, HueColor.rgbToHs(0, 0, 255))
        assertEquals(370, HueColor.kelvinToMirek(2700))
        assertEquals(6536, HueColor.mirekToKelvin(153))
        assertEquals(Triple(255, 255, 255), HueColor.xyToRgb(0.0, 0.0))
    }

    private fun obj(text: String): JsonObject = kotlinx.serialization.json.Json.parseToJsonElement(text).jsonObject.also {
        require(it.isNotEmpty())
    }
}
