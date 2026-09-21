package com.custom.astrion.config

import android.os.Environment
import android.util.Log
import com.custom.astrion.cards.CardConfig
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Loads the whole app layout (swipeable pages + hardware-button bindings) from
 * a JSON file on shared storage. If the file is missing or malformed, it falls
 * back to DashboardConfig.default. The app never crashes over a bad config; it
 * simply displays an onscreen notice.
 *
 * Path: [Environment.getExternalStorageDirectory]/astrion/dashboard.json. Shared
 * storage keeps the file editable over `adb push` or any file manager; MainActivity
 * requests the storage permission at runtime (Android 8.1 on the HA100).
 *
 * JSON shape:
 * {
 *   "startPage": 1,
 *   "pages": [
 *     { "name": "Lights", "cards": [ { "type": "...", "options": { ... } } ] },
 *     { "name": "Video",  "cards": [ ... ] },
 *     { "name": "Apple TV", "parent": "Video", "cards": [ ... ] },
 *     { "name": "Main",   "cards": [ ... ] },
 *     { "name": "TV",     "cards": [ ... ] }
 *   ],
 *   "hotkeys": [
 *     { "key": "UP", "service": "remote.send_command",
 *       "entityId": "remote.the_club_tvv", "data": { "command": "DPAD_UP" } },
 *     { "key": "LIGHT", "page": "Lights" },
 *     { "key": "VOLUME_UP", "harmonyDevice": "62845789", "harmonyCommand": "VolumeUp" },
 *     { "key": "SCENE", "harmonyActivity": "39568252", "hub": "living_hub",
 *       "track": true, "room": "Living Room" }
 *   ],
 *   "irDevices": [
 *     { "id": "salon_tv", "name": "TV Salon",
 *       "commands": {
 *         "power": { "freq": 38000, "pattern": [9000, 4500, 560, 560] },
 *         "hdmi1": { "freq": 38000, "pattern": [9000, 4500, 560, 1690] }
 *       } }
 *   ],
 *   "activities": [
 *     { "id": "salon_appletv", "name": "Apple TV", "room": "Salon",
 *       "volumeDeviceId": "salon_ampli",
 *       "devices": [
 *         { "deviceId": "salon_tv", "source": "ir",
 *           "powerOnCommand": "power", "inputCommand": "hdmi1" },
 *         { "deviceId": "salon_ampli", "source": "ir", "hub": null,
 *           "powerOnCommand": "power", "inputCommand": "hdmi2", "delayAfterMs": 500 }
 *       ] }
 *   ]
 * }
 *
 * A *single-action* Activity (one HA script, one existing Harmony Activity,
 * or one direct command) still doesn't need an "activities" entry — just
 * "track": true + "room" on a scene_grid item or hotkey, as above. The
 * "activities" section above is only for *composed*, multi-device
 * Activities — see the NOTE atop AppConfig.kt.
 *
 * A bare top-level array is also accepted for convenience — it becomes a single
 * page named "Main" with no hotkeys.
 */
object DashboardLoader {
    private const val TAG = "DashboardLoader"

    val configFile: File
        get() = File(Environment.getExternalStorageDirectory(), "astrion/dashboard.json")

    private val json =
        Json {
            prettyPrint = true
            ignoreUnknownKeys = true
        }

    data class Result(val config: AppConfig, val notice: String?)

    fun load(): Result {
        val file = configFile
        if (!file.exists()) {
            return if (writeDefaults()) {
                Result(DashboardConfig.default, "Wrote defaults to ${file.path} — edit it, then reopen the app")
            } else {
                Result(DashboardConfig.default, "Can't access ${file.path} (storage permission?) — using built-in defaults")
            }
        }
        return try {
            Result(parse(file.readText()), null)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load ${file.path}", e)
            Result(DashboardConfig.default, "dashboard.json invalid (${e.message?.take(80)}) — using built-in defaults")
        }
    }

    // ---- parse --------------------------------------------------------------

    private fun parse(text: String): AppConfig = when (val root = json.parseToJsonElement(text)) {
        is JsonArray ->
            AppConfig(
                pages = listOf(PageConfig("Main", root.map { parseCard(it.jsonObject) }))
            )
        is JsonObject -> {
            val pagesArr = root["pages"]?.jsonArray ?: error("missing \"pages\" array")
            val pages =
                pagesArr.map { p ->
                    val obj = p.jsonObject
                    val name = obj["name"]?.jsonPrimitive?.content ?: "Page"
                    val cards = obj["cards"]?.jsonArray?.map { parseCard(it.jsonObject) } ?: emptyList()
                    val pageHotkeys = obj["hotkeys"]?.jsonArray?.map { parseHotkey(it.jsonObject) } ?: emptyList()
                    val pageLongHotkeys = obj["longHotkeys"]?.jsonArray?.map { parseHotkey(it.jsonObject) } ?: emptyList()
                    val parent = obj["parent"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val parentKey = obj["parentKey"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "BACK"
                    val linkedPage = obj["linkedPage"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val linkedPageModeRaw = obj["linkedPageMode"]?.jsonPrimitive?.content
                    val linkedPageMode = if (linkedPageModeRaw == "popup") "popup" else "page"
                    val popupWidthFraction = obj["popupWidth"]?.jsonPrimitive?.floatOrNull?.coerceIn(0.1f, 1f) ?: 0.7f
                    val popupHeightFraction = obj["popupHeight"]?.jsonPrimitive?.floatOrNull?.coerceIn(0.1f, 1f) ?: 0.5f
                    val popupPositionRaw = obj["popupPosition"]?.jsonPrimitive?.content
                    val popupPosition = if (popupPositionRaw in setOf("top", "bottom", "left", "right")) popupPositionRaw!! else "center"
                    val hiddenUnlessActivity = obj["hiddenUnlessActivity"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val openWhenEntity = obj["openWhenEntity"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    val openWhenState = obj["openWhenState"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "on"
                    val closeWhenState = obj["closeWhenState"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                    PageConfig(
                        name = name,
                        cards = cards,
                        hotkeys = pageHotkeys,
                        longHotkeys = pageLongHotkeys,
                        parent = parent,
                        parentKey = parentKey,
                        linkedPage = linkedPage,
                        linkedPageMode = linkedPageMode,
                        popupWidthFraction = popupWidthFraction,
                        popupHeightFraction = popupHeightFraction,
                        popupPosition = popupPosition,
                        hiddenUnlessActivity = hiddenUnlessActivity,
                        openWhenEntity = openWhenEntity,
                        openWhenState = openWhenState,
                        closeWhenState = closeWhenState
                    )
                }
            if (pages.isEmpty()) error("\"pages\" is empty")
            val start = root["startPage"]?.jsonPrimitive?.intOrNull ?: 0
            val hotkeys = root["hotkeys"]?.jsonArray?.map { parseHotkey(it.jsonObject) } ?: emptyList()
            val longHotkeys = root["longHotkeys"]?.jsonArray?.map { parseHotkey(it.jsonObject) } ?: emptyList()
            val irDevices = root["irDevices"]?.jsonArray?.map { parseIrDevice(it.jsonObject) } ?: emptyList()
            val activities = root["activities"]?.jsonArray?.map { parseActivity(it.jsonObject) } ?: emptyList()
            val theme = root["theme"]?.jsonObject?.let { parseTheme(it) } ?: ThemeConfig()
            AppConfig(pages, start.coerceIn(0, pages.size - 1), hotkeys, longHotkeys, irDevices, activities, theme)
        }
        else -> error("top level must be an object or array")
    }

    private fun parseCard(obj: JsonObject): CardConfig {
        val type =
            obj["type"]?.jsonPrimitive?.takeIf { it.isString }?.content
                ?: error("card missing \"type\" string")
        val options =
            obj["options"]
                ?.jsonObject
                ?.entries
                ?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
                ?: emptyMap()
        return CardConfig(type, options)
    }

    private fun parseHotkey(obj: JsonObject): HotkeyConfig {
        val key = obj["key"]?.jsonPrimitive?.content ?: error("hotkey missing \"key\"")
        val page = obj["page"]?.jsonPrimitive?.content
        val service = obj["service"]?.jsonPrimitive?.content
        val entityId = obj["entityId"]?.jsonPrimitive?.content
        val data =
            obj["data"]
                ?.jsonObject
                ?.entries
                ?.associate { (k, v) -> k to JsonPlain.toPlain(v) }
                ?: emptyMap()
        val harmonyDevice = obj["harmonyDevice"]?.jsonPrimitive?.content
        val harmonyCommand = obj["harmonyCommand"]?.jsonPrimitive?.content
        val harmonyActivity = obj["harmonyActivity"]?.jsonPrimitive?.content
        val hub = obj["hub"]?.jsonPrimitive?.content
        val irDevice = obj["irDevice"]?.jsonPrimitive?.content
        val irCommand = obj["irCommand"]?.jsonPrimitive?.content
        val track = obj["track"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val room = obj["room"]?.jsonPrimitive?.content
        val devices = obj["devices"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()
        val openOverlay = obj["openOverlay"]?.jsonPrimitive?.content
        val openCurrentActivityRoom = obj["openCurrentActivityRoom"]?.jsonPrimitive?.content
        return HotkeyConfig(
            key,
            page,
            service,
            entityId,
            data,
            harmonyDevice,
            harmonyCommand,
            harmonyActivity,
            hub,
            irDevice,
            irCommand,
            track,
            room,
            devices,
            openOverlay,
            openCurrentActivityRoom
        )
    }

    private fun parseIrDevice(obj: JsonObject): IrDeviceConfig {
        val id = obj["id"]?.jsonPrimitive?.content ?: error("irDevice missing \"id\"")
        val name = obj["name"]?.jsonPrimitive?.content ?: id
        val source = when {
            obj.containsKey("commands") -> {
                val commandsObj = obj["commands"]!!.jsonObject
                if (commandsObj.isEmpty()) error("irDevice \"$id\" has an empty \"commands\" map")
                val commands = commandsObj.entries.associate { (cmdId, v) -> cmdId to parseIrStep(v.jsonObject) }
                IrDeviceSource.Inline(commands)
            }
            obj.containsKey("category") && obj.containsKey("brand") && obj.containsKey("model") -> {
                IrDeviceSource.SdCardRef(
                    category = obj["category"]!!.jsonPrimitive.content,
                    brand = obj["brand"]!!.jsonPrimitive.content,
                    model = obj["model"]!!.jsonPrimitive.content
                )
            }
            else -> error(
                "irDevice \"$id\" needs either a \"commands\" map (inline, hand-resolved) " +
                    "or \"category\"+\"brand\"+\"model\" (a reference into /sdcard/astrion/ir-database/)"
            )
        }
        val target = parseIrTarget(id, obj["target"])
        return IrDeviceConfig(id, name, source, target)
    }

    /**
     * Absent entirely -> [IrTarget.Local], same as every dashboard.json
     * written before this field existed. `"target": "local"` is the
     * explicit spelling of the same thing (accepted, never written by the
     * encoder below — no reason to clutter every device's JSON with the
     * default). `"target": {"extender": "<id>"}` -> [IrTarget.Extender].
     */
    private fun parseIrTarget(deviceId: String, value: JsonElement?): IrTarget = when {
        value == null -> IrTarget.Local
        value is JsonPrimitive && value.content == "local" -> IrTarget.Local
        value is JsonObject && value.containsKey("extender") -> {
            val extenderId = value["extender"]!!.jsonPrimitive.content
            if (extenderId.isBlank()) error("irDevice \"$deviceId\" has a blank \"target.extender\" id")
            IrTarget.Extender(extenderId)
        }
        else -> error(
            "irDevice \"$deviceId\" has an unrecognized \"target\" " +
                "(expected omitted, \"local\", or {\"extender\": \"<id>\"})"
        )
    }

    private fun parseIrStep(obj: JsonObject): IrStepConfig {
        val freq = obj["freq"]?.jsonPrimitive?.intOrNull ?: error("IR step missing \"freq\"")
        val pattern =
            obj["pattern"]?.jsonArray?.map { it.jsonPrimitive.int }
                ?: error("IR step missing \"pattern\"")
        if (pattern.isEmpty()) error("IR step has an empty \"pattern\"")
        return IrStepConfig(freq, pattern)
    }

    private fun parseActivity(obj: JsonObject): ActivityConfig {
        val id = obj["id"]?.jsonPrimitive?.content ?: error("activity missing \"id\"")
        val name = obj["name"]?.jsonPrimitive?.content ?: id
        val room =
            obj["room"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: error("activity \"$id\" missing \"room\"")
        val icon = obj["icon"]?.jsonPrimitive?.content
        val page = obj["page"]?.jsonPrimitive?.content
        val devicesArr = obj["devices"]?.jsonArray ?: error("activity \"$id\" missing \"devices\"")
        if (devicesArr.isEmpty()) error("activity \"$id\" has an empty \"devices\" list")
        val devices = devicesArr.map { parseActivityDevice(it.jsonObject, id) }
        val volumeDeviceId = obj["volumeDeviceId"]?.jsonPrimitive?.content
        val volumeUpCommand = obj["volumeUpCommand"]?.jsonPrimitive?.content
        val volumeDownCommand = obj["volumeDownCommand"]?.jsonPrimitive?.content
        val muteCommand = obj["muteCommand"]?.jsonPrimitive?.content
        return ActivityConfig(id, name, room, icon, page, devices, volumeDeviceId, volumeUpCommand, volumeDownCommand, muteCommand)
    }

    private fun parseActivityDevice(obj: JsonObject, activityId: String): ActivityDeviceConfig {
        val deviceId = obj["deviceId"]?.jsonPrimitive?.content ?: error("a device in activity \"$activityId\" is missing \"deviceId\"")
        val source =
            obj["source"]?.jsonPrimitive?.content ?: error("device \"$deviceId\" in activity \"$activityId\" is missing \"source\"")
        if (source !in setOf("ir", "harmony", "ha")) {
            error(
                "device \"$deviceId\" in activity \"$activityId\" has unknown source \"$source\""
            )
        }
        val hub = obj["hub"]?.jsonPrimitive?.content
        val powerOnCommand = obj["powerOnCommand"]?.jsonPrimitive?.content
        val powerOffCommand = obj["powerOffCommand"]?.jsonPrimitive?.content
        val inputCommand = obj["inputCommand"]?.jsonPrimitive?.content
        val powerOnFirst = obj["powerOnFirst"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val powerOffOnExit = obj["powerOffOnExit"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true
        val delayAfterMs = obj["delayAfterMs"]?.jsonPrimitive?.intOrNull ?: 0
        return ActivityDeviceConfig(
            deviceId,
            source,
            hub,
            powerOnCommand,
            powerOffCommand,
            inputCommand,
            powerOnFirst,
            powerOffOnExit,
            delayAfterMs
        )
    }

    private fun parseTheme(obj: JsonObject): ThemeConfig {
        fun s(key: String, default: String) = obj[key]
            ?.jsonPrimitive
            ?.takeIf { it.isString }
            ?.content
            ?.ifBlank { default } ?: default
        return ThemeConfig(
            background = s("background", ThemeConfig().background),
            cardSurface = s("cardSurface", ThemeConfig().cardSurface),
            insetSurface = s("insetSurface", ThemeConfig().insetSurface),
            controlBackground = s("controlBackground", ThemeConfig().controlBackground),
            primaryText = s("primaryText", ThemeConfig().primaryText),
            mutedText = s("mutedText", ThemeConfig().mutedText),
            iconTint = s("iconTint", ThemeConfig().iconTint),
            accent = s("accent", ThemeConfig().accent),
            accentSecondary = s("accentSecondary", ThemeConfig().accentSecondary),
            amber = s("amber", ThemeConfig().amber),
            danger = s("danger", ThemeConfig().danger),
            success = s("success", ThemeConfig().success)
        )
    }

    // ---- serialize defaults -------------------------------------------------

    private fun writeDefaults(): Boolean = try {
        val file = configFile
        file.parentFile?.mkdirs()
        file.writeText(json.encodeToString(JsonObject.serializer(), encode(DashboardConfig.default)))
        true
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't write default config", e)
        false
    }

    private fun encode(cfg: AppConfig): JsonObject = buildJsonObject {
        put("startPage", cfg.startPage)
        put(
            "pages",
            buildJsonArray {
                cfg.pages.forEach { page ->
                    add(
                        buildJsonObject {
                            put("name", page.name)
                            page.parent?.let {
                                put("parent", it)
                                put("parentKey", page.parentKey)
                            }
                            page.linkedPage?.let { put("linkedPage", it) }
                            if (page.linkedPageMode == "popup") {
                                put("linkedPageMode", "popup")
                                put("popupWidth", page.popupWidthFraction)
                                put("popupHeight", page.popupHeightFraction)
                                if (page.popupPosition != "center") put("popupPosition", page.popupPosition)
                            }
                            page.hiddenUnlessActivity?.let { put("hiddenUnlessActivity", it) }
                            page.openWhenEntity?.let {
                                put("openWhenEntity", it)
                                if (page.openWhenState != "on") put("openWhenState", page.openWhenState)
                                page.closeWhenState?.let { closeState -> put("closeWhenState", closeState) }
                            }
                            put(
                                "cards",
                                buildJsonArray {
                                    page.cards.forEach { card ->
                                        add(
                                            buildJsonObject {
                                                put("type", card.type)
                                                put("options", JsonPlain.toJson(card.options))
                                            }
                                        )
                                    }
                                }
                            )
                            if (page.hotkeys.isNotEmpty()) put("hotkeys", encodeHotkeys(page.hotkeys))
                            if (page.longHotkeys.isNotEmpty()) put("longHotkeys", encodeHotkeys(page.longHotkeys))
                        }
                    )
                }
            }
        )
        put("hotkeys", encodeHotkeys(cfg.hotkeys))
        put("longHotkeys", encodeHotkeys(cfg.longHotkeys))
        if (cfg.irDevices.isNotEmpty()) {
            put(
                "irDevices",
                buildJsonArray {
                    cfg.irDevices.forEach { device ->
                        add(
                            buildJsonObject {
                                put("id", device.id)
                                put("name", device.name)
                                when (val source = device.source) {
                                    is IrDeviceSource.Inline -> put(
                                        "commands",
                                        buildJsonObject {
                                            source.commands.forEach { (cmdId, step) ->
                                                put(
                                                    cmdId,
                                                    buildJsonObject {
                                                        put("freq", step.freq)
                                                        put("pattern", buildJsonArray { step.pattern.forEach { add(JsonPrimitive(it)) } })
                                                    }
                                                )
                                            }
                                        }
                                    )
                                    is IrDeviceSource.SdCardRef -> {
                                        put("category", source.category)
                                        put("brand", source.brand)
                                        put("model", source.model)
                                    }
                                }
                                when (val target = device.target) {
                                    IrTarget.Local -> {} // default, omitted rather than written explicitly
                                    is IrTarget.Extender -> put(
                                        "target",
                                        buildJsonObject { put("extender", target.extenderId) }
                                    )
                                }
                            }
                        )
                    }
                }
            )
        }
        if (cfg.activities.isNotEmpty()) {
            put(
                "activities",
                buildJsonArray {
                    cfg.activities.forEach { act ->
                        add(
                            buildJsonObject {
                                put("id", act.id)
                                put("name", act.name)
                                put("room", act.room)
                                act.icon?.let { put("icon", it) }
                                act.page?.let { put("page", it) }
                                act.volumeDeviceId?.let { put("volumeDeviceId", it) }
                                act.volumeUpCommand?.let { put("volumeUpCommand", it) }
                                act.volumeDownCommand?.let { put("volumeDownCommand", it) }
                                act.muteCommand?.let { put("muteCommand", it) }
                                put(
                                    "devices",
                                    buildJsonArray {
                                        act.devices.forEach { d ->
                                            add(
                                                buildJsonObject {
                                                    put("deviceId", d.deviceId)
                                                    put("source", d.source)
                                                    d.hub?.let { put("hub", it) }
                                                    d.powerOnCommand?.let { put("powerOnCommand", it) }
                                                    d.powerOffCommand?.let { put("powerOffCommand", it) }
                                                    d.inputCommand?.let { put("inputCommand", it) }
                                                    if (!d.powerOnFirst) put("powerOnFirst", false)
                                                    if (!d.powerOffOnExit) put("powerOffOnExit", false)
                                                    if (d.delayAfterMs != 0) put("delayAfterMs", d.delayAfterMs)
                                                }
                                            )
                                        }
                                    }
                                )
                            }
                        )
                    }
                }
            )
        }
    }

    private fun encodeHotkeys(hotkeys: List<HotkeyConfig>) = buildJsonArray {
        hotkeys.forEach { hk ->
            add(
                buildJsonObject {
                    put("key", hk.key)
                    hk.page?.let { put("page", it) }
                    hk.service?.let { put("service", it) }
                    hk.entityId?.let { put("entityId", it) }
                    if (hk.data.isNotEmpty()) put("data", JsonPlain.toJson(hk.data))
                    hk.harmonyDevice?.let { put("harmonyDevice", it) }
                    hk.harmonyCommand?.let { put("harmonyCommand", it) }
                    hk.harmonyActivity?.let { put("harmonyActivity", it) }
                    hk.hub?.let { put("hub", it) }
                    hk.irDevice?.let { put("irDevice", it) }
                    hk.irCommand?.let { put("irCommand", it) }
                    if (hk.track) put("track", true)
                    hk.room?.let { put("room", it) }
                    if (hk.devices.isNotEmpty()) put("devices", buildJsonArray { hk.devices.forEach { add(JsonPrimitive(it)) } })
                    hk.openOverlay?.let { put("openOverlay", it) }
                    hk.openCurrentActivityRoom?.let { put("openCurrentActivityRoom", it) }
                }
            )
        }
    }
}
