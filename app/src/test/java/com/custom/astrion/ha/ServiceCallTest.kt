package com.custom.astrion.ha

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** ServiceCall.of keeps nested data as JSON (#142: a script's variables). */
class ServiceCallTest {
    @Test
    fun `nested data stays JSON`() {
        val call =
            ServiceCall.of(
                "script",
                "turn_on",
                "script.text_input",
                "variables" to mapOf("text" to "a", "input_action" to "key", "count" to 2),
                "list" to listOf("x", true),
                "skipped" to null
            )
        assertEquals(
            JsonObject(mapOf("text" to JsonPrimitive("a"), "input_action" to JsonPrimitive("key"), "count" to JsonPrimitive(2))),
            call.data["variables"]
        )
        assertEquals(JsonArray(listOf(JsonPrimitive("x"), JsonPrimitive(true))), call.data["list"])
        assertEquals(setOf("variables", "list"), call.data.keys)
        assertEquals(JsonPrimitive(60), ServiceCall.of("light", "turn_on", "light.k", "brightness_pct" to 60).data["brightness_pct"])
    }
}
