package com.custom.astrion.cards

import com.custom.astrion.config.JsonPlain
import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** `visibility` rules, read from JSON the way DashboardLoader hands options to the cards. */
class VisibilityTest {
    private val entities =
        listOf(
            "lock.front_door" to "locked",
            "sensor.outdoor_temp" to "25.5",
            "sensor.broken" to "unknown",
            "input_number.threshold" to "20"
        )
            .associate { (id, state) -> id to EntityState(id, state, JsonObject(emptyMap())) }

    private fun visible(json: String) = Visibility.isVisible(JsonPlain.toPlain(Json.parseToJsonElement(json)), entities)

    @Test
    fun `state and state_not, one value or a list`() {
        assertTrue(visible("""[{ "condition": "state", "entity": "lock.front_door", "state": "locked" }]"""))
        assertFalse(visible("""[{ "condition": "state", "entity": "lock.front_door", "state_not": "locked" }]"""))
        assertTrue(visible("""[{ "condition": "state", "entity": "lock.front_door", "state": ["unlocked", "locked"] }]"""))
        assertFalse(visible("""[{ "condition": "state", "entity": "lock.front_door", "state_not": ["jammed", "locked"] }]"""))
    }

    @Test
    fun `numeric_state bounds are exclusive and a non-numeric state fails`() {
        assertTrue(visible("""[{ "condition": "numeric_state", "entity": "sensor.outdoor_temp", "above": 20, "below": 35 }]"""))
        assertFalse(visible("""[{ "condition": "numeric_state", "entity": "sensor.outdoor_temp", "above": 25.5 }]"""))
        assertFalse(visible("""[{ "condition": "numeric_state", "entity": "sensor.outdoor_temp", "below": 25 }]"""))
        assertFalse(visible("""[{ "condition": "numeric_state", "entity": "sensor.broken", "above": 0 }]"""))
        assertTrue(visible("""[{ "condition": "numeric_state", "entity": "sensor.outdoor_temp", "above": "input_number.threshold" }]"""))
        assertFalse(visible("""[{ "condition": "numeric_state", "entity": "sensor.outdoor_temp", "below": "20" }]"""))
    }

    @Test
    fun `a condition without a condition key is a state check`() {
        assertFalse(visible("""[{ "entity": "lock.front_door", "state_not": "locked" }]"""))
    }

    @Test
    fun `an entity Home Assistant doesn't report is unavailable`() {
        assertTrue(visible("""[{ "condition": "state", "entity": "lock.garage", "state": "unavailable" }]"""))
        assertFalse(visible("""[{ "condition": "state", "entity": "lock.garage", "state_not": "unavailable" }]"""))
        assertFalse(visible("""[{ "condition": "numeric_state", "entity": "sensor.gone", "below": 100 }]"""))
    }

    @Test
    fun `every top-level condition must hold, and and or nest`() {
        val locked = """{ "condition": "state", "entity": "lock.front_door", "state": "locked" }"""
        val cold = """{ "condition": "numeric_state", "entity": "sensor.outdoor_temp", "below": 10 }"""
        assertFalse(visible("[$locked, $cold]"))
        assertTrue(visible("""[{ "condition": "or", "conditions": [$cold, $locked] }]"""))
        assertFalse(visible("""[{ "condition": "and", "conditions": [$locked, $cold] }]"""))
        assertTrue(visible("""[{ "condition": "or", "conditions": [$cold, { "condition": "and", "conditions": [$locked] }] }]"""))
    }

    @Test
    fun `no rules, an empty list or a rule it can't read never hides anything`() {
        assertTrue(Visibility.isVisible(null, entities))
        assertTrue(visible("[]"))
        assertTrue(visible("""{ "condition": "state", "entity": "lock.front_door", "state": "unlocked" }"""))
        assertTrue(visible("""[{ "condition": "stat", "entity": "lock.front_door", "state": "unlocked" }]"""))
        assertTrue(visible("""[{ "condition": "state", "state": "unlocked" }]"""))
        assertTrue(visible("""[{ "condition": "numeric_state", "entity": "sensor.gone" }]"""))
        assertTrue(visible("""["oops", { "condition": "or", "conditions": [] }]"""))
    }
}
