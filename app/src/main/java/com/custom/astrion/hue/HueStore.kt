package com.custom.astrion.hue

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * What a Hue bridge has (1.6.1): its CLIP v2 resources (lights, rooms, zones, grouped lights,
 * scenes, devices…) by id, read once, then kept up to date by the bridge's event stream, whose
 * updates carry only what changed (merged here). Pure, so it is unit-tested.
 */
class HueStore {
    private val byId = LinkedHashMap<String, JsonObject>()

    operator fun get(id: String?): JsonObject? = id?.let { byId[it] }

    fun ofType(type: String): List<JsonObject> = byId.values.filter { it.str("type") == type }

    val size: Int get() = byId.size

    /** `GET /clip/v2/resource`'s answer (`{"errors": […], "data": […]}`): all of it, replacing what was known. */
    fun load(body: String): Boolean {
        val data = runCatching { (json.parseToJsonElement(body) as? JsonObject)?.get("data") as? JsonArray }.getOrNull() ?: return false
        byId.clear()
        data.forEach { el -> (el as? JsonObject)?.let { o -> o.str("id")?.let { byId[it] = o } } }
        return true
    }

    /**
     * One message of the event stream: a list of containers, each `{"type": "update" | "add" |
     * "delete", "data": [resources]}`. True when something changed.
     */
    fun apply(events: String): Boolean {
        val containers = runCatching { json.parseToJsonElement(events) as? JsonArray }.getOrNull() ?: return false
        var changed = false
        containers.filterIsInstance<JsonObject>().forEach { container ->
            val kind = container.str("type")
            container.objs("data").forEach { r -> if (applyOne(kind, r)) changed = true }
        }
        return changed
    }

    private fun applyOne(kind: String?, r: JsonObject): Boolean {
        val id = r.str("id") ?: return false
        when (kind) {
            "add" -> byId[id] = r
            "delete" -> byId.remove(id)
            "update" -> merge(id, r)
            else -> return false
        }
        return true
    }

    /** [partial] merged into resource [id]: objects merged key by key, the rest replaced. Unknown id: ignored. */
    fun merge(id: String, partial: JsonObject) {
        val old = byId[id] ?: return
        byId[id] = deepMerge(old, partial)
    }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun deepMerge(base: JsonObject, change: JsonObject): JsonObject {
            val out = LinkedHashMap(base)
            for ((k, v) in change) {
                val old = out[k]
                out[k] = if (old is JsonObject && v is JsonObject) deepMerge(old, v) else v
            }
            return JsonObject(out)
        }
    }
}

/** A string field, or null. */
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

/** A nested object, or null. */
internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

/** A nested list of objects (empty when absent). */
internal fun JsonObject.objs(key: String): List<JsonObject> = (this[key] as? JsonArray)?.mapNotNull { it as? JsonObject }.orEmpty()

/** `{"rid": …, "rtype": …}` references of [key]. */
internal fun JsonObject.refs(key: String): List<Pair<String, String>> = objs(key).mapNotNull { r ->
    val rid = r.str("rid") ?: return@mapNotNull null
    rid to r.str("rtype").orEmpty()
}
