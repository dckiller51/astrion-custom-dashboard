package com.custom.astrion.hue

import java.text.Normalizer
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** One entity a bridge's resource becomes: `light.hue_…` or `scene.hue_…`, Home Assistant's way. */
data class HueEntity(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * A Hue bridge's resources as Home Assistant entities (1.6.1), named and shaped like Home
 * Assistant's own Hue integration so the light card, buttons, scene tiles and hotkeys use them
 * unchanged: each light or plug `light.hue_<name>`, each room, zone and the whole home (their
 * grouped light) `light.hue_<name>`, each scene `scene.hue_<room>_<scene>`. And the other way:
 * a service call's data as the body of a `PUT` to the bridge. Pure, so it is unit-tested.
 */
object HueEntities {
    const val LIGHT = "light"
    const val GROUP = "grouped_light"
    const val SCENE = "scene"

    /** The whole home's grouped light, named in no room. */
    const val ALL_LIGHTS = "All Hue lights"

    // ---- ids -------------------------------------------------------------------

    /** "Salle à manger" → "salle_a_manger". */
    fun slug(name: String, fallback: String = "light"): String {
        val plain = Normalizer.normalize(name, Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return plain.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { fallback }
    }

    /**
     * Entity ids for the bridge's lights, grouped lights and scenes, by resource id: the ones
     * [known] from before kept (a light renamed in the Hue app keeps its cards), new ones from
     * their name, none of them in [taken] (another bridge's). Resources gone are left out.
     */
    fun assignIds(store: HueStore, known: Map<String, String>, taken: Set<String> = emptySet()): Map<String, String> {
        val out = LinkedHashMap<String, String>()
        val used = HashSet(taken)
        val wanted = targets(store)
        wanted.forEach { (rid, _) -> known[rid]?.takeIf { used.add(it) }?.let { out[rid] = it } }
        wanted.forEach { (rid, base) ->
            if (rid in out) return@forEach
            var id = base
            var n = 2
            while (!used.add(id)) id = "${base}_${n++}"
            out[rid] = id
        }
        return out
    }

    /** Every resource that becomes an entity, with the id its name gives (lights, then rooms and zones, then scenes). */
    private fun targets(store: HueStore): List<Pair<String, String>> {
        val lights = store.ofType(LIGHT).mapNotNull { l -> l.str("id")?.let { it to "light.hue_" + slug(lightName(store, l)) } }
        val groups =
            store.ofType(GROUP).mapNotNull { g ->
                val id = g.str("id") ?: return@mapNotNull null
                val owner = store[g.obj("owner")?.str("rid")]
                val home = owner == null || owner.str("type") == "bridge_home"
                id to if (home) "light.hue_all" else "light.hue_" + slug(groupName(store, g))
            }
        val scenes =
            store.ofType(SCENE).mapNotNull { s ->
                val id = s.str("id") ?: return@mapNotNull null
                val room = store[s.obj("group")?.str("rid")]?.let { metaName(it) }
                id to "scene.hue_" + listOfNotNull(room?.let { slug(it, "room") }, slug(metaName(s) ?: "scene", "scene")).joinToString("_")
            }
        return lights + groups + scenes
    }

    // ---- names and links -------------------------------------------------------

    private fun metaName(r: JsonObject): String? = r.obj("metadata")?.str("name")?.takeIf { it.isNotBlank() }

    fun lightName(store: HueStore, light: JsonObject): String =
        metaName(light) ?: store[light.obj("owner")?.str("rid")]?.let { metaName(it) } ?: "Hue light"

    /** A grouped light's room or zone name, or [ALL_LIGHTS] for the home's. */
    fun groupName(store: HueStore, group: JsonObject): String {
        val owner = store[group.obj("owner")?.str("rid")] ?: return ALL_LIGHTS
        return if (owner.str("type") == "bridge_home") ALL_LIGHTS else metaName(owner) ?: "Hue room"
    }

    /** The lights of a grouped light: its room's devices' lights, its zone's lights, or all for the home. */
    fun membersOf(store: HueStore, group: JsonObject): List<JsonObject> {
        val owner = store[group.obj("owner")?.str("rid")] ?: return emptyList()
        if (owner.str("type") == "bridge_home") return store.ofType(LIGHT)
        return owner.refs("children").flatMap { (rid, rtype) ->
            when (rtype) {
                LIGHT -> listOfNotNull(store[rid])
                "device" -> store[rid]?.refs("services").orEmpty().filter { it.second == LIGHT }.mapNotNull { store[it.first] }
                else -> emptyList()
            }
        }
    }

    /** False when the light's device says its Zigbee link is down; true when it says nothing. */
    fun reachable(store: HueStore, light: JsonObject): Boolean {
        val device = store[light.obj("owner")?.str("rid")] ?: return true
        val link = device.refs("services").firstOrNull { it.second == "zigbee_connectivity" }?.let { store[it.first] } ?: return true
        return link.str("status")?.let { it == "connected" } ?: true
    }

    // ---- entities --------------------------------------------------------------

    /** Every entity of the bridge, with the ids from [assignIds]. */
    fun entities(store: HueStore, ids: Map<String, String>): List<HueEntity> {
        val out = ArrayList<HueEntity>()
        store.ofType(LIGHT).forEach { l -> ids[l.str("id")]?.let { out += light(store, l, it) } }
        store.ofType(GROUP).forEach { g -> ids[g.str("id")]?.let { out += group(store, g, it, ids) } }
        store.ofType(SCENE).forEach { s -> ids[s.str("id")]?.let { out += scene(store, s, it) } }
        return out
    }

    private fun light(store: HueStore, l: JsonObject, entityId: String): HueEntity {
        val on = l.obj("on")?.bool("on") == true
        val a = LinkedHashMap<String, Any?>()
        a["friendly_name"] = lightName(store, l)
        val modes = colorModes(l)
        a["supported_color_modes"] = modes
        temperatureRange(l)?.let { (lo, hi) ->
            a["min_color_temp_kelvin"] = HueColor.mirekToKelvin(hi)
            a["max_color_temp_kelvin"] = HueColor.mirekToKelvin(lo)
            a["min_mireds"] = lo
            a["max_mireds"] = hi
        }
        val effects = effectsOf(l)
        if (effects.isNotEmpty()) {
            a["effect_list"] = listOf(EFFECT_OFF) + effects
            val status = l.obj("effects")?.str("status")
            a["effect"] = if (on && status != null && status != NO_EFFECT) status else EFFECT_OFF
        }
        if (on) {
            val mode = colorMode(l, modes)
            a["color_mode"] = mode
            l.obj("dimming")?.dbl("brightness")?.let { a["brightness"] = (it / PERCENT * BRI_MAX).roundToInt().coerceIn(1, BRI_MAX) }
            colorAttributes(l, mode, a)
        }
        a["supported_features"] = if (effects.isNotEmpty()) FEATURES_WITH_EFFECT else FEATURES
        l.obj("metadata")?.str("archetype")?.let { a["hue_archetype"] = it }
        a["astrion_source"] = "hue"
        val state = if (!reachable(store, l)) UNAVAILABLE else if (on) "on" else "off"
        return HueEntity(entityId, state, a)
    }

    private fun group(store: HueStore, g: JsonObject, entityId: String, ids: Map<String, String>): HueEntity {
        val members = membersOf(store, g)
        val on = g.obj("on")?.bool("on") == true
        val a = LinkedHashMap<String, Any?>()
        a["friendly_name"] = groupName(store, g)
        val memberModes = members.flatMap { colorModes(it) }.toSet()
        val modes =
            when {
                COLOR_XY in memberModes -> listOf(COLOR_TEMP, COLOR_XY)
                COLOR_TEMP in memberModes -> listOf(COLOR_TEMP)
                BRIGHTNESS in memberModes -> listOf(BRIGHTNESS)
                else -> listOf(ONOFF)
            }
        a["supported_color_modes"] = modes
        val ranges = members.mapNotNull { temperatureRange(it) }
        if (ranges.isNotEmpty()) {
            val lo = ranges.minOf { it.first }
            val hi = ranges.maxOf { it.second }
            a["min_color_temp_kelvin"] = HueColor.mirekToKelvin(hi)
            a["max_color_temp_kelvin"] = HueColor.mirekToKelvin(lo)
            a["min_mireds"] = lo
            a["max_mireds"] = hi
        }
        if (on) {
            // The group says no colour of its own: the first lit member's, as Home Assistant shows it.
            val lit = members.firstOrNull { it.obj("on")?.bool("on") == true }
            val mode = lit?.let { colorMode(it, colorModes(it)) }?.takeIf { it in modes } ?: modes.last()
            a["color_mode"] = mode
            g.obj("dimming")?.dbl("brightness")?.let { a["brightness"] = (it / PERCENT * BRI_MAX).roundToInt().coerceIn(1, BRI_MAX) }
            lit?.let { colorAttributes(it, mode, a) }
        }
        a["entity_id"] = members.mapNotNull { ids[it.str("id")] }
        a["is_hue_group"] = true
        a["hue_type"] = store[g.obj("owner")?.str("rid")]?.str("type") ?: "bridge_home"
        a["supported_features"] = FEATURES
        a["astrion_source"] = "hue"
        return HueEntity(entityId, if (on) "on" else "off", a)
    }

    private fun scene(store: HueStore, s: JsonObject, entityId: String): HueEntity {
        val group = store[s.obj("group")?.str("rid")]
        val name = metaName(s) ?: "Scene"
        val room = group?.let { metaName(it) }
        val active = s.obj("status")?.str("active") ?: "inactive"
        val a = LinkedHashMap<String, Any?>()
        a["friendly_name"] = listOfNotNull(room, name).joinToString(" ")
        a["name"] = name
        room?.let { a["group_name"] = it }
        group?.str("type")?.let { a["group_type"] = it }
        a["is_active"] = active != "inactive"
        a["is_dynamic"] = active == "dynamic_palette"
        a["astrion_source"] = "hue"
        return HueEntity(entityId, if (active != "inactive") "on" else "off", a)
    }

    private fun colorModes(l: JsonObject): List<String> {
        val color = l.obj("color") != null
        val temp = l.obj("color_temperature") != null
        return when {
            color && temp -> listOf(COLOR_TEMP, COLOR_XY)
            color -> listOf(COLOR_XY)
            temp -> listOf(COLOR_TEMP)
            l.obj("dimming") != null -> listOf(BRIGHTNESS)
            else -> listOf(ONOFF)
        }
    }

    /** Home Assistant's rule: white while the bridge says its mirek holds, else colour. */
    private fun colorMode(l: JsonObject, modes: List<String>): String {
        val ct = l.obj("color_temperature")
        if (ct != null && ct.bool("mirek_valid") == true && ct.int("mirek") != null) return COLOR_TEMP
        return when {
            COLOR_XY in modes -> COLOR_XY
            COLOR_TEMP in modes -> COLOR_TEMP
            else -> modes.first()
        }
    }

    /** Mirek range (coolest, warmest) of a white-capable light. */
    private fun temperatureRange(l: JsonObject): Pair<Int, Int>? {
        val ct = l.obj("color_temperature") ?: return null
        val schema = ct.obj("mirek_schema")
        return (schema?.int("mirek_minimum") ?: MIREK_COOLEST) to (schema?.int("mirek_maximum") ?: MIREK_WARMEST)
    }

    private fun effectsOf(l: JsonObject): List<String> =
        l.obj("effects")?.let { e -> (e["effect_values"] as? kotlinx.serialization.json.JsonArray) }
            ?.mapNotNull { (it as? JsonPrimitive)?.content }
            ?.filter { it != NO_EFFECT }
            .orEmpty()

    private fun colorAttributes(l: JsonObject, mode: String, a: MutableMap<String, Any?>) {
        when (mode) {
            COLOR_TEMP -> {
                val mirek = l.obj("color_temperature")?.int("mirek") ?: return
                val k = HueColor.mirekToKelvin(mirek)
                a["color_temp_kelvin"] = k
                a["color_temp"] = mirek
                val (r, g, b) = HueColor.kelvinToRgb(k)
                a["rgb_color"] = listOf(r, g, b)
                val (h, s) = HueColor.rgbToHs(r, g, b)
                a["hs_color"] = listOf(h, s)
            }
            COLOR_XY -> {
                val xy = l.obj("color")?.obj("xy") ?: return
                val x = xy.dbl("x") ?: return
                val y = xy.dbl("y") ?: return
                a["xy_color"] = listOf(x, y)
                val (r, g, b) = HueColor.xyToRgb(x, y)
                a["rgb_color"] = listOf(r, g, b)
                val (h, s) = HueColor.rgbToHs(r, g, b)
                a["hs_color"] = listOf(h, s)
            }
        }
    }

    // ---- commands --------------------------------------------------------------

    /**
     * The body of a `PUT /clip/v2/resource/<type>/<id>` for `light.turn_on` / `turn_off` /
     * `toggle` (or `homeassistant.…`) on a light or a grouped light ([group]), or null when the
     * service isn't one. [current] is the resource as known, for toggle and steps.
     */
    fun lightCommand(service: String, data: Map<String, Any?>, current: JsonObject?, group: Boolean = false): JsonObject? = when (service) {
        "turn_off" -> off(data)
        "toggle" -> if (current?.obj("on")?.bool("on") == true) off(data) else on(data, current, group)
        "turn_on" -> on(data, current, group)
        else -> null
    }

    /** `scene.turn_on`: recalled, with `transition` (seconds), `dynamic` and `brightness_pct` if given. */
    fun sceneCommand(service: String, data: Map<String, Any?>): JsonObject? {
        if (service != "turn_on") return null
        return buildJsonObject {
            putJsonObject("recall") {
                put("action", if (data["dynamic"] == true || data["dynamic"] == "true") "dynamic_palette" else "active")
                num(data["transition"])?.let { put("duration", (it * MS).roundToInt()) }
                num(data["brightness_pct"])?.let { putJsonObject("dimming") { put("brightness", it.coerceIn(0.0, PERCENT)) } }
            }
        }
    }

    private fun off(data: Map<String, Any?>): JsonObject = buildJsonObject {
        putJsonObject("on") { put("on", false) }
        num(data["transition"])?.let { putJsonObject("dynamics") { put("duration", (it * MS).roundToInt()) } }
    }

    private fun on(data: Map<String, Any?>, current: JsonObject?, group: Boolean): JsonObject {
        val pct = brightnessOf(data, current)
        if (pct != null && pct <= 0.0) return off(data)
        return buildJsonObject {
            putJsonObject("on") { put("on", true) }
            pct?.let { putJsonObject("dimming") { put("brightness", round2(it.coerceIn(MIN_BRIGHTNESS, PERCENT))) } }
            val xy = xyOf(data)
            val mirek = if (xy == null) mirekOf(data, current) else null
            xy?.let { (x, y) ->
                putJsonObject("color") {
                    putJsonObject("xy") {
                        put("x", x)
                        put("y", y)
                    }
                }
            }
            mirek?.let { putJsonObject("color_temperature") { put("mirek", it) } }
            if (!group) {
                (data["effect"] as? String)?.let { e ->
                    putJsonObject("effects") { put("effect", if (e.lowercase() in OFF_NAMES) NO_EFFECT else e) }
                }
            }
            if (data["flash"] is String) putJsonObject("alert") { put("action", "breathe") }
            num(data["transition"])?.let { putJsonObject("dynamics") { put("duration", (it * MS).roundToInt()) } }
        }
    }

    /** The brightness asked, as a percentage: `brightness_pct`, `brightness` (0–255) or a step from [current]. */
    private fun brightnessOf(data: Map<String, Any?>, current: JsonObject?): Double? {
        num(data["brightness_pct"])?.let { return it }
        num(data["brightness"])?.let { return it / BRI_MAX * PERCENT }
        val now = current?.obj("dimming")?.dbl("brightness") ?: 0.0
        val wasOn = current?.obj("on")?.bool("on") == true
        val base = if (wasOn) now else 0.0
        num(data["brightness_step_pct"])?.let { return base + it }
        num(data["brightness_step"])?.let { return base + it / BRI_MAX * PERCENT }
        return null
    }

    private fun xyOf(data: Map<String, Any?>): Pair<Double, Double>? {
        nums(data["xy_color"], 2)?.let { return it[0] to it[1] }
        nums(data["rgb_color"], 3)?.let { return HueColor.rgbToXy(it[0].roundToInt(), it[1].roundToInt(), it[2].roundToInt()) }
        nums(data["hs_color"], 2)?.let {
            val (r, g, b) = HueColor.hsToRgb(it[0], it[1])
            return HueColor.rgbToXy(r, g, b)
        }
        return null
    }

    /** The white asked, as mirek within the light's own range when known. */
    private fun mirekOf(data: Map<String, Any?>, current: JsonObject?): Int? {
        val mirek =
            num(data["color_temp_kelvin"])?.let { HueColor.kelvinToMirek(it.roundToInt()) }
                ?: num(data["kelvin"])?.let { HueColor.kelvinToMirek(it.roundToInt()) }
                ?: num(data["color_temp"])?.roundToInt()
                ?: return null
        val (lo, hi) = current?.let { temperatureRange(it) } ?: (MIREK_COOLEST to MIREK_WARMEST)
        return mirek.coerceIn(lo, hi)
    }

    /**
     * What a `PUT` that succeeded changes, merged into the resource at once so the card follows
     * the finger; the bridge's event stream confirms it within a second.
     */
    fun optimistic(body: JsonObject): JsonObject = buildJsonObject {
        body.obj("on")?.let { put("on", it) }
        body.obj("dimming")?.let { put("dimming", it) }
        body.obj("color")?.obj("xy")?.let { xy ->
            putJsonObject("color") { put("xy", xy) }
            putJsonObject("color_temperature") {
                put("mirek", JsonNull)
                put("mirek_valid", false)
            }
        }
        body.obj("color_temperature")?.int("mirek")?.let { m ->
            putJsonObject("color_temperature") {
                put("mirek", m)
                put("mirek_valid", true)
            }
        }
        body.obj("effects")?.str("effect")?.let { e -> putJsonObject("effects") { put("status", e) } }
    }

    // ---- helpers ---------------------------------------------------------------

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun nums(v: Any?, n: Int): List<Double>? = (v as? List<*>)?.mapNotNull { num(it) }?.takeIf { it.size >= n }

    private fun round2(v: Double) = (v * 100).roundToInt() / 100.0

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.doubleOrNull

    const val UNAVAILABLE = "unavailable"
    private const val EFFECT_OFF = "off"
    private const val NO_EFFECT = "no_effect"
    private val OFF_NAMES = setOf("off", "none", NO_EFFECT)
    private const val COLOR_TEMP = "color_temp"
    private const val COLOR_XY = "xy"
    private const val BRIGHTNESS = "brightness"
    private const val ONOFF = "onoff"
    private const val PERCENT = 100.0
    private const val BRI_MAX = 255
    private const val MS = 1000
    private const val MIN_BRIGHTNESS = 0.4
    private const val MIREK_COOLEST = 153
    private const val MIREK_WARMEST = 500

    // Home Assistant's LightEntityFeature: EFFECT 4, FLASH 8, TRANSITION 32.
    private const val FEATURES = 40
    private const val FEATURES_WITH_EFFECT = 44
}
