package com.custom.astrion.cards

import com.custom.astrion.ha.EntityMap

/**
 * "Show only when" rules: a `visibility` list in Home Assistant's own Lovelace format, on a card's
 * `options` or on one `button_grid` button / `scene_grid` tile.
 *
 * ```json
 * "visibility": [
 *   { "condition": "state", "entity": "lock.front_door", "state_not": "locked" },
 *   { "condition": "numeric_state", "entity": "sensor.outdoor_temp", "above": 20, "below": 35 },
 *   { "condition": "or", "conditions": [ ... ] }
 * ]
 * ```
 *
 * Every condition must hold (`and` / `or` nest). `state` / `state_not` take one state or a list;
 * `above` / `below` are exclusive and, as in HA, may name an entity whose state is the bound. A
 * condition without `"condition"` is a state check (HA's older form). An entity Home Assistant
 * doesn't report counts as `unavailable`, as in HA. A rule this can't read (unknown condition, no
 * entity, no bound, an empty `or`) is ignored, so it never hides anything; no `visibility` (or an
 * empty one) always shows.
 */
object Visibility {
    private const val UNAVAILABLE = "unavailable"

    fun isVisible(raw: Any?, entities: EntityMap): Boolean = (raw as? List<*>).orEmpty().all { met(it, entities) }

    private fun met(raw: Any?, entities: EntityMap): Boolean {
        val c = raw as? Map<*, *> ?: return true
        return when (c["condition"]) {
            "state", null -> stateMet(c, entities)
            "numeric_state" -> numericMet(c, entities)
            "and" -> (c["conditions"] as? List<*>).orEmpty().all { met(it, entities) }
            "or" -> (c["conditions"] as? List<*>).orEmpty().let { subs -> subs.isEmpty() || subs.any { met(it, entities) } }
            else -> true
        }
    }

    private fun stateMet(c: Map<*, *>, entities: EntityMap): Boolean {
        val entity = c["entity"] as? String ?: return true
        val current = entities[entity]?.state ?: UNAVAILABLE
        val state = states(c["state"])
        val stateNot = states(c["state_not"])
        return (state == null || current in state) && (stateNot == null || current !in stateNot)
    }

    private fun numericMet(c: Map<*, *>, entities: EntityMap): Boolean {
        val entity = c["entity"] as? String ?: return true
        val above = bound(c["above"], entities)
        val below = bound(c["below"], entities)
        if (above == null && below == null) return true
        val value = entities[entity]?.state?.toDoubleOrNull() ?: return false
        return (above == null || value > above) && (below == null || value < below)
    }

    /** A number, a numeric string, or an entity id whose state is the bound. */
    private fun bound(raw: Any?, entities: EntityMap): Double? = when (raw) {
        is Number -> raw.toDouble()
        is String -> (entities[raw]?.state ?: raw).toDoubleOrNull()
        else -> null
    }

    private fun states(raw: Any?): List<String>? = when (raw) {
        is String -> listOf(raw)
        is List<*> -> raw.filterIsInstance<String>()
        else -> null
    }
}
