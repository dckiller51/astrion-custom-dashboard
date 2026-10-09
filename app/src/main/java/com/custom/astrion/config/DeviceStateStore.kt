package com.custom.astrion.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * What Astrion believes about one physical device — the same thing a
 * Harmony hub remembers: is it on, and which input is it on. `null` means
 * "unknown" (never driven by an Activity yet, or reset): the engine then
 * falls back to its original, pre-1.3 guesses.
 */
data class DeviceState(val on: Boolean? = null, val input: String? = null)

/**
 * Per-device [DeviceState], keyed by the Activity device id (an IR device
 * id, a Harmony device id, or an HA entity id). Persisted through [persist]
 * so it survives an app restart — the single most important difference
 * from the old "which Activity is active in this room" memory, which
 * restarted empty and made a toggle-only device switch *off* on the next
 * Activity start. Observable ([states]) for the Activity "Help" screen.
 */
class DeviceStateStore(initial: Map<String, DeviceState> = emptyMap(), private val persist: (String) -> Unit = {}) {
    private val _states = MutableStateFlow(initial)
    val states: StateFlow<Map<String, DeviceState>> = _states.asStateFlow()

    operator fun get(deviceId: String): DeviceState = _states.value[deviceId] ?: DeviceState()

    fun setPower(deviceId: String, on: Boolean) = edit(deviceId) { it.copy(on = on) }

    fun setInput(deviceId: String, input: String?) = edit(deviceId) { it.copy(input = input) }

    /** Forget everything known about one device ("Help": I don't know). */
    fun reset(deviceId: String) {
        _states.update { it - deviceId }
        persist(serialize(_states.value))
    }

    private fun edit(deviceId: String, change: (DeviceState) -> DeviceState) {
        _states.update { it + (deviceId to change(it[deviceId] ?: DeviceState())) }
        persist(serialize(_states.value))
    }

    companion object {
        /** The app-wide store — replaced once at startup by AstrionApp with
         * a persisted one; this in-memory default only ever serves previews
         * and tests. */
        @Volatile
        var shared: DeviceStateStore = DeviceStateStore()

        fun serialize(states: Map<String, DeviceState>): String = JsonObject(
            states.mapValues { (_, s) ->
                JsonObject(
                    mapOf(
                        "on" to (s.on?.let(::JsonPrimitive) ?: JsonNull),
                        "input" to (s.input?.let(::JsonPrimitive) ?: JsonNull)
                    )
                )
            }
        ).toString()

        /** Lenient: anything unreadable is simply forgotten. */
        fun deserialize(text: String?): Map<String, DeviceState> = runCatching {
            Json.parseToJsonElement(text ?: return emptyMap()).jsonObject.mapValues { (_, el) ->
                val o = el.jsonObject
                DeviceState(
                    on = (o["on"] as? JsonPrimitive)?.booleanOrNull,
                    input = (o["input"] as? JsonPrimitive)?.contentOrNull
                )
            }
        }.getOrDefault(emptyMap())
    }
}
