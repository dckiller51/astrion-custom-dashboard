package com.custom.astrion.ha

import com.custom.astrion.config.JsonPlain
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

/**
 * A single Home Assistant entity's live state.
 *
 * Mirrors the object HA returns from `get_states` and inside `state_changed`
 * events. We keep attributes as a raw JsonObject so individual cards can pull
 * out whatever domain-specific fields they need (brightness, hvac_mode,
 * current_temperature, media_title, etc.) without us modeling every domain.
 */
@Suppress("Unused")
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    val lastChanged: String? = null,
    val lastUpdated: String? = null
) {
    val domain: String get() = entityId.substringBefore('.')

    /** Convenience: friendly_name attribute, falling back to the entity id. */
    val friendlyName: String
        get() = attrString("friendly_name") ?: entityId

    fun attr(key: String): JsonElement? = attributes[key]

    /** Attribute as String, or null if absent/not a primitive. Deliberately
     * casts (`as?`) rather than using the `.jsonPrimitive` extension, which
     * *throws* — not returns null — on a JsonObject/JsonArray value (e.g.
     * Kodi's `media_content_id`, which HA reports as a nested object instead
     * of a plain string like most other media players). A non-primitive
     * attribute should degrade to "we don't have this", not crash the card
     * rendering it. */
    fun attrString(key: String): String? = (attributes[key] as? JsonPrimitive)?.contentOrNull

    /** Attribute as Double, or null. Handles ints and numeric strings too. */
    fun attrDouble(key: String): Double? = (attributes[key] as? JsonPrimitive)?.let { prim ->
        prim.doubleOrNull ?: prim.contentOrNull?.toDoubleOrNull()
    }

    /** Attribute as Int, or null. */
    fun attrInt(key: String): Int? = attrDouble(key)?.toInt()

    /** Attribute as Boolean, or null. Handles boolean primitives and boolean strings ("true"/"false"). */
    fun attrBoolean(key: String): Boolean? = (attributes[key] as? JsonPrimitive)?.let { prim ->
        prim.booleanOrNull ?: prim.contentOrNull?.toBooleanStrictOrNull()
    }

    /** Attribute as a list of strings (e.g. media_player source lists, hvac_modes). */
    fun attrStringList(key: String): List<String> = try {
        attributes[key]?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
    } catch (_: Exception) {
        emptyList()
    }

    val isOn: Boolean get() = state == "on"
    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
}

/** Snapshot of the whole entity registry, keyed by entity_id. */
typealias EntityMap = Map<String, EntityState>

/**
 * Connection lifecycle as surfaced to the UI. Kept intentionally small.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    AUTH_FAILED,
    ERROR
}

/**
 * A service call request. Matches HA's `call_service` websocket command shape:
 * { type: call_service, domain, service, service_data, target: { entity_id } }
 */
data class ServiceCall(
    val domain: String,
    val service: String,
    val entityId: String? = null,
    val data: Map<String, JsonElement> = emptyMap()
) {
    companion object {
        /**
         * Build a ServiceCall from plain values (String / Number / Boolean, and lists and maps of
         * them), as JSON. Keeps card code readable:
         *   ServiceCall.of("light", "turn_on", "light.kitchen", "brightness_pct" to 60)
         * A nested object or list stays one (1.5.6, #142): a button's `"variables": {…}` for
         * `script.turn_on` used to be sent as its text ("{text=a, …}"), which Home Assistant refused.
         */
        fun of(domain: String, service: String, entityId: String? = null, vararg data: Pair<String, Any?>): ServiceCall {
            val mapped =
                buildMap {
                    for ((k, v) in data) {
                        if (v != null) put(k, JsonPlain.toJson(v))
                    }
                }
            return ServiceCall(domain, service, entityId, mapped)
        }

        /** Overload convenience when no entityId is specified directly. */
        fun of(domain: String, service: String, vararg data: Pair<String, Any?>): ServiceCall =
            of(domain, service, entityId = null, data = data)
    }
}
