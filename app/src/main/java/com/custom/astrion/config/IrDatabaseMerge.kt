package com.custom.astrion.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

/**
 * Merges an incoming ir-database category file into the one already on
 * the remote: every model in [incoming] is added, or replaces the model
 * with the same brand + model name (case-insensitive); every other model
 * already there is kept. Used for the sniffer's "Send to my remote", which
 * only ever exports the models checked in that browser session — e.g. a
 * single device just imported from the Harmony archive — and used to
 * overwrite the whole file, silently dropping every other device of that
 * category already on the remote.
 *
 * Returns the merged file as JSON text. [existing] null/blank/unreadable
 * → [incoming] as-is. Throws on an unreadable [incoming] (the caller has
 * already validated it).
 */
object IrDatabaseMerge {
    fun merge(existing: String?, incoming: String): String {
        val inc = Json.parseToJsonElement(incoming).jsonObject
        val old = existing?.takeIf { it.isNotBlank() }?.let { runCatching { Json.parseToJsonElement(it).jsonObject }.getOrNull() }
            ?: return inc.toString()

        val brands = LinkedHashMap<String, Pair<JsonObject, LinkedHashMap<String, JsonElement>>>()
        fun absorb(file: JsonObject) {
            (file["brands"] as? JsonArray).orEmpty().forEach { brandEl ->
                val brand = brandEl as? JsonObject ?: return@forEach
                val name = (brand["brand_name"] as? JsonPrimitive)?.content ?: return@forEach
                val slot = brands.getOrPut(name.lowercase()) { brand to LinkedHashMap() }
                (brand["models"] as? JsonArray).orEmpty().forEach { modelEl ->
                    val model = (modelEl as? JsonObject)?.get("model_name") as? JsonPrimitive ?: return@forEach
                    slot.second[model.content.lowercase()] = modelEl
                }
            }
        }
        absorb(old)
        absorb(inc)

        val merged =
            brands.values.map { (brand, models) ->
                JsonObject(brand + ("models" to JsonArray(models.values.toList())))
            }
        return JsonObject(old + inc + ("brands" to JsonArray(merged))).toString()
    }
}
