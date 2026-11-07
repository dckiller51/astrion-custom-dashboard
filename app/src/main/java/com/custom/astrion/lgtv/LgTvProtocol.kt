package com.custom.astrion.lgtv

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/**
 * LG webOS TVs' control protocol (1.5.2), the one LG's own phone app speaks: JSON messages over a
 * WebSocket on port 3000 (`ws://`) or, on recent firmwares, 3001 (`wss://`, LG's own certificate).
 * The first connection asks the TV to accept the remote (a prompt on screen) and the TV answers
 * with a `client-key`, sent back on every later connection. Then `request` / `subscribe`
 * messages call `ssap://` services; remote buttons go over a second socket the TV hands out
 * (`getPointerInputSocket`), as `type:button` lines.
 *
 * The endpoint names and the registration manifest follow what LG's webOS TVs answer, as
 * documented by the aiowebostv library (Apache-2.0) Home Assistant uses. Pure, so tested.
 */
object LgTvProtocol {
    const val WS_PORT = 3000
    const val WSS_PORT = 3001

    // ssap:// services.
    const val SYSTEM_INFO = "system/getSystemInfo"
    const val CONNECTION_INFO = "com.webos.service.connectionmanager/getinfo"
    const val POWER_STATE = "com.webos.service.tvpower/power/getPowerState"
    const val POWER_OFF = "system/turnOff"
    const val SCREEN_OFF = "com.webos.service.tvpower/power/turnOffScreen"
    const val SCREEN_ON = "com.webos.service.tvpower/power/turnOnScreen"
    const val FOREGROUND_APP = "com.webos.applicationManager/getForegroundAppInfo"
    const val APPS = "com.webos.applicationManager/listLaunchPoints"
    const val LAUNCH = "system.launcher/launch"
    const val INPUTS = "tv/getExternalInputList"
    const val SWITCH_INPUT = "tv/switchInput"
    const val AUDIO_STATUS = "audio/getStatus"
    const val VOLUME = "audio/getVolume"
    const val SET_VOLUME = "audio/setVolume"
    const val VOLUME_UP = "audio/volumeUp"
    const val VOLUME_DOWN = "audio/volumeDown"
    const val SET_MUTE = "audio/setMute"
    const val PLAY = "media.controls/play"
    const val PAUSE = "media.controls/pause"
    const val STOP = "media.controls/stop"
    const val REWIND = "media.controls/rewind"
    const val FAST_FORWARD = "media.controls/fastForward"
    const val CHANNEL_UP = "tv/channelUp"
    const val CHANNEL_DOWN = "tv/channelDown"
    const val INPUT_SOCKET = "com.webos.service.networkinput/getPointerInputSocket"
    const val TOAST = "system.notifications/createToast"

    /** What the TV is asked to let the remote do (the prompt lists none: accept or refuse). */
    private val PERMISSIONS =
        listOf(
            "APP_TO_APP", "CLOSE", "CONTROL_AUDIO", "CONTROL_DISPLAY", "CONTROL_INPUT_JOYSTICK",
            "CONTROL_INPUT_MEDIA_PLAYBACK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_TEXT",
            "CONTROL_INPUT_TV", "CONTROL_MOUSE_AND_KEYBOARD", "CONTROL_POWER", "CONTROL_TV_SCREEN",
            "LAUNCH", "LAUNCH_WEBAPP", "READ_APP_STATUS", "READ_COUNTRY_INFO", "READ_CURRENT_CHANNEL",
            "READ_INPUT_DEVICE_LIST", "READ_INSTALLED_APPS", "READ_LGE_SDX", "READ_LGE_TV_INPUT_EVENTS",
            "READ_NETWORK_STATE", "READ_NOTIFICATIONS", "READ_POWER_STATE", "READ_RUNNING_APPS",
            "READ_SETTINGS", "READ_TV_CHANNEL_LIST", "READ_TV_CURRENT_TIME", "READ_UPDATE_INFO",
            "SEARCH", "TEST_OPEN", "TEST_PROTECTED", "TEST_SECURE", "UPDATE_FROM_REMOTE_APP",
            "WRITE_NOTIFICATION_ALERT", "WRITE_NOTIFICATION_TOAST", "WRITE_SETTINGS"
        )

    private val json = Json { ignoreUnknownKeys = true }

    /** The registration: with [clientKey] (a TV that accepted the remote before), no prompt. */
    fun register(clientKey: String?): String = buildJsonObject {
        put("type", "register")
        put("id", REGISTER_ID)
        put(
            "payload",
            buildJsonObject {
                put("forcePairing", false)
                put("pairingType", "PROMPT")
                if (!clientKey.isNullOrBlank()) put("client-key", clientKey)
                put(
                    "manifest",
                    buildJsonObject {
                        put("appVersion", "1.1")
                        put("manifestVersion", 1)
                        put("permissions", buildJsonArray { PERMISSIONS.forEach { add(JsonPrimitive(it)) } })
                    }
                )
            }
        )
    }.toString()

    /** A `request` or `subscribe` to `ssap://[uri]`. */
    fun message(id: String, uri: String, payload: JsonObject? = null, subscribe: Boolean = false): String = buildJsonObject {
        put("type", if (subscribe) "subscribe" else "request")
        put("id", id)
        put("uri", "ssap://$uri")
        payload?.let { put("payload", it) }
    }.toString()

    /** One remote button on the pointer socket. */
    fun button(name: String): String = "type:button\nname:$name\n\n"

    const val REGISTER_ID = "register_0"

    /** A message from the TV: its type (`registered`, `response`, `error`…), id and payload. */
    data class Incoming(val type: String, val id: String?, val payload: JsonObject, val error: String? = null)

    fun parse(text: String): Incoming? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        return Incoming(
            type = o.str("type") ?: return null,
            id = o.str("id"),
            payload = o["payload"] as? JsonObject ?: JsonObject(emptyMap()),
            error = o.str("error")
        )
    }

    /** The client key of a `registered` answer. */
    fun clientKeyOf(m: Incoming): String? = if (m.type == "registered") m.payload.str("client-key") else null

    /** The first answer to a new remote: the TV shows its prompt. */
    fun isPrompt(m: Incoming): Boolean = m.type == "response" && m.id == REGISTER_ID && m.payload.str("pairingType") == "PROMPT"

    /** The answer said it worked (`returnValue`, or `subscribed` for a subscription). */
    fun ok(m: Incoming): Boolean = m.type != "error" && (m.payload.bool("returnValue") != false)

    // ---- what the TV says ---------------------------------------------------------------

    /** `getSystemInfo`: its model, e.g. "OLED55C24LA". */
    fun model(p: JsonObject): String? = p.str("modelName")

    /** `connectionmanager/getinfo`: its MAC address, wired first (Wake-on-LAN needs the wired one). */
    fun mac(p: JsonObject): String? {
        val wired = (p["wiredInfo"] as? JsonObject)?.str("macAddress")
        val wifi = (p["wifiInfo"] as? JsonObject)?.str("macAddress")
        return listOfNotNull(wired, wifi).map { it.lowercase().replace('-', ':') }.firstOrNull { MAC.matches(it) }
    }

    /** `getPowerState`: on unless off, suspended or in standby; "Screen Off" is on, screen dark. */
    fun isOn(p: JsonObject): Boolean? = p.str("state")?.let { it !in OFF_STATES }

    fun screenOff(p: JsonObject): Boolean = p.str("state") == "Screen Off"

    /** `getForegroundAppInfo`: the app in front (an input is an app too: `com.webos.app.hdmi1`). */
    fun foregroundApp(p: JsonObject): String? = p.str("appId")

    /** `audio/getStatus` or `getVolume`: the volume (0–100) and mute; null when not said. */
    fun volume(p: JsonObject): Int? = ((p["volumeStatus"] as? JsonObject) ?: p).int("volume")?.takeIf { it >= 0 }

    fun muted(p: JsonObject): Boolean? = p.bool("mute") ?: p.bool("muted") ?: (p["volumeStatus"] as? JsonObject)?.bool("muteStatus")

    /** An app or an input: its id (what launches it), its name, its icon. */
    data class Item(val id: String, val label: String, val icon: String? = null)

    /** `listLaunchPoints`: the installed apps, as the TV's home shows them. */
    fun apps(p: JsonObject): List<Item>? = (p["launchPoints"] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        Item(id, o.str("title") ?: id, o.str("icon"))
    }

    /** A later `listLaunchPoints` message: one app added, changed or removed. */
    fun appChange(p: JsonObject): Pair<String, Item?>? {
        val change = p.str("change") ?: return null
        val id = p.str("id") ?: return null
        return id to if (change == "removed") null else Item(id, p.str("title") ?: id, p.str("icon"))
    }

    /** `getExternalInputList`: HDMI 1, AV… — [Item.id] is the input's id (`HDMI_1`), what `switchInput` takes. */
    data class Input(val id: String, val label: String, val appId: String?, val connected: Boolean, val icon: String? = null)

    fun inputs(p: JsonObject): List<Input>? = (p["devices"] as? JsonArray)?.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val id = o.str("id") ?: return@mapNotNull null
        Input(id, o.str("label") ?: id, o.str("appId"), o.bool("connected") != false, o.str("icon"))
    }

    /** `getPointerInputSocket`: where to send buttons. */
    fun socketPath(p: JsonObject): String? = p.str("socketPath")

    fun volumePayload(level: Int) = buildJsonObject { put("volume", level.coerceIn(0, MAX_VOLUME)) }

    fun mutePayload(mute: Boolean) = buildJsonObject { put("mute", mute) }

    fun launchPayload(appId: String) = buildJsonObject { put("id", appId) }

    fun inputPayload(inputId: String) = buildJsonObject { put("inputId", inputId) }

    fun toastPayload(text: String) = buildJsonObject { put("message", text) }

    const val MAX_VOLUME = 100
    private val OFF_STATES = setOf("Power Off", "Suspend", "Active Standby")
    private val MAC = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String) =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    fun parseObject(text: String): JsonElement? = runCatching { json.parseToJsonElement(text) }.getOrNull()
}

/**
 * LG's remote buttons (`type:button` names) and the Android TV names the TV remote card, the
 * hotkeys and the keypad send, mapped to them — so a TV remote card pointed at `remote.lgtv_…`
 * works unchanged.
 */
object LgTvButtons {
    /** LG's own names, sent as they are. */
    val NATIVE =
        setOf(
            "LEFT", "RIGHT", "UP", "DOWN", "ENTER", "BACK", "EXIT", "HOME", "MENU", "QMENU", "INFO",
            "RED", "GREEN", "YELLOW", "BLUE", "CHANNELUP", "CHANNELDOWN", "VOLUMEUP", "VOLUMEDOWN", "MUTE",
            "PLAY", "PAUSE", "STOP", "REWIND", "FASTFORWARD", "RECORD", "ASTERISK", "DASH", "GUIDE",
            "LIST", "PROGRAM", "MYAPPS", "RECENT", "INPUT_HUB", "SCREEN_REMOTE", "CC", "SAP", "AD",
            "ASPECT_RATIO", "TELETEXT", "TEXTOPTION", "3D_MODE", "LIVE_ZOOM", "MAGNIFIER_ZOOM",
            "NETFLIX", "AMAZON", "POWER"
        ) + (0..9).map { "$it" }

    private val androidNames =
        mapOf(
            "DPAD_UP" to "UP",
            "DPAD_DOWN" to "DOWN",
            "DPAD_LEFT" to "LEFT",
            "DPAD_RIGHT" to "RIGHT",
            "DPAD_CENTER" to "ENTER",
            "OK" to "ENTER",
            "SELECT" to "ENTER",
            "CENTER" to "ENTER",
            "ESCAPE" to "BACK",
            "SETTINGS" to "MENU",
            "VOLUME_UP" to "VOLUMEUP",
            "VOLUME_DOWN" to "VOLUMEDOWN",
            "VOLUME_MUTE" to "MUTE",
            "CHANNEL_UP" to "CHANNELUP",
            "CHANNEL_DOWN" to "CHANNELDOWN",
            "MEDIA_PLAY" to "PLAY",
            "MEDIA_PAUSE" to "PAUSE",
            "MEDIA_STOP" to "STOP",
            "MEDIA_REWIND" to "REWIND",
            "MEDIA_FAST_FORWARD" to "FASTFORWARD",
            "FAST_FORWARD" to "FASTFORWARD",
            "MEDIA_RECORD" to "RECORD",
            "CAPTIONS" to "CC",
            "MEDIA_AUDIO_TRACK" to "SAP",
            "TV_INPUT" to "INPUT_HUB",
            "APP_SWITCH" to "RECENT",
            "GUIDE" to "GUIDE",
            "STAR" to "ASTERISK",
            "PROG_RED" to "RED",
            "PROG_GREEN" to "GREEN",
            "PROG_YELLOW" to "YELLOW",
            "PROG_BLUE" to "BLUE"
        )

    /** The LG button for [name] (LG's own, or an Android name); null when it has none. */
    fun of(name: String): String? {
        val n = name.trim().uppercase().removePrefix("KEYCODE_")
        return androidNames[n] ?: n.takeIf { it in NATIVE }
    }
}
