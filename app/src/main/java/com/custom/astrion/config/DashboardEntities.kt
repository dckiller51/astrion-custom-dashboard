package com.custom.astrion.config

import com.custom.astrion.cards.impl.ClockWeatherCard
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The Home Assistant entities a dashboard.json uses, so [com.custom.astrion.ha.HaClient] only
 * receives those (`subscribe_entities` with `entity_ids`) instead of every state change in the
 * house (1.5.1).
 *
 * Rather than knowing every card's options, it takes every string in the file that is a valid
 * entity id: cards, hotkeys, `openWhenEntity`, Activities, `screen_off_entities`, the dock… A
 * service name such as `light.toggle` comes along too, which costs nothing. Added on top: the
 * entities the app reads without the file naming them (see [implicit]).
 *
 * Null = every entity, as before: `power.ha_entities: "all"`, a file that names no entity at all,
 * or a dock clock that shows whichever `media_player` plays (no `media_entities`).
 */
object DashboardEntities {
    // Home Assistant's own valid_entity_id: one id it refuses makes the whole subscription fail.
    private val ENTITY_ID = Regex("^(?!.+__)(?!_)[\\da-z_]+(?<!_)\\.(?!_)[\\da-z_]+(?<!_)$")

    // Read by MainActivity for the dock's night brightness.
    private const val SUN = "sun.sun"

    // PictureElementsCard's `radar.targets` default.
    private const val RADAR_TARGETS = 3

    fun of(root: JsonElement, cfg: AppConfig): Set<String>? {
        if (cfg.power.allEntities) return null
        if (cfg.dock.mode == DockConfig.MODE_CLOCK && cfg.dock.mediaEntities.isEmpty()) return null
        val ids = HashSet<String>()
        collect(root, ids)
        if (ids.isEmpty()) return null
        ids += implicit(root, ids)
        return ids
    }

    private fun collect(element: JsonElement, into: MutableSet<String>) {
        when (element) {
            is JsonPrimitive -> element.contentOrNull?.takeIf { element.isString && ENTITY_ID.matches(it) }?.let { into += it }
            is JsonArray -> element.forEach { collect(it, into) }
            is JsonObject -> element.values.forEach { collect(it, into) }
        }
    }

    /**
     * Entities a card reads without the file naming them: the TV remote card's `media_player.<x>`
     * for its `remote.<x>` (no `media_entity`), the clock & weather card's default weather entity,
     * the picture elements radar's `<prefix>_<n>_x` / `_y`, and `sun.sun`.
     */
    private fun implicit(root: JsonElement, named: Set<String>): Set<String> {
        val ids = hashSetOf(SUN)
        named.filter { it.startsWith("remote.") }.forEach { ids += "media_player." + it.substringAfter('.') }
        forEachObject(root) { obj ->
            if ((obj["type"] as? JsonPrimitive)?.contentOrNull == "clock_weather") ids += ClockWeatherCard.DEFAULT_ENTITY
            val radar = obj["radar"] as? JsonObject ?: return@forEachObject
            val prefix = (radar["prefix"] as? JsonPrimitive)?.contentOrNull ?: return@forEachObject
            val targets = (radar["targets"] as? JsonPrimitive)?.intOrNull ?: RADAR_TARGETS
            for (i in 1..targets) {
                ids += "${prefix}_${i}_x"
                ids += "${prefix}_${i}_y"
            }
        }
        return ids.filterTo(HashSet()) { ENTITY_ID.matches(it) }
    }

    private fun forEachObject(element: JsonElement, block: (JsonObject) -> Unit) {
        when (element) {
            is JsonArray -> element.forEach { forEachObject(it, block) }
            is JsonObject -> {
                block(element)
                element.values.forEach { forEachObject(it, block) }
            }
            else -> Unit
        }
    }
}
