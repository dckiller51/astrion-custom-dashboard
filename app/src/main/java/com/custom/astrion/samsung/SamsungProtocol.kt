package com.custom.astrion.samsung

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Samsung Tizen TVs' local control (1.5.3), the one Samsung's SmartThings phone app uses on the
 * home network (2016 onwards):
 *  - **REST** on port 8001: `GET /api/v2/` says the model, MAC and power state (2018+), and
 *    `/api/v2/applications/<id>` an app's state;
 *  - the **remote** WebSocket: `wss://<tv>:8002/api/v2/channels/samsung.remote.control` (its own
 *    certificate, pinned), `?name=` the remote's name in base64 and `&token=` the one the TV gave
 *    when the remote was allowed (the TV asks on screen the first time); `ws://…:8001` on TVs
 *    without tokens. Keys go as `ms.remote.control`, apps as `ms.channel.emit` events;
 *  - the **volume**: UPnP RenderingControl on port 9197 (SOAP `GetVolume`, `SetVolume`, `GetMute`).
 *
 * Messages as documented by the samsungtvws library (MIT) and Home Assistant's Samsung TV
 * integration (Apache-2.0). Pure, so tested.
 */
object SamsungProtocol {
    const val REST_PORT = 8001
    const val WSS_PORT = 8002
    const val UPNP_PORT = 9197

    /** The name the TV shows in its prompt and its list of allowed devices. */
    const val REMOTE_NAME = "Astrion"

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    // ---- REST ---------------------------------------------------------------------------------------

    fun infoUrl(host: String) = "http://$host:$REST_PORT/api/v2/"

    fun appUrl(host: String, appId: String) = "http://$host:$REST_PORT/api/v2/applications/$appId"

    /** What `GET /api/v2/` says. [powerOn] null: a TV too old to say (2016–2017): on when it answers. */
    data class Info(
        val name: String?,
        val model: String?,
        val mac: String?,
        val powerOn: Boolean?,
        val tokens: Boolean,
        val frame: Boolean
    )

    fun info(o: JsonObject?): Info? {
        val d = o?.get("device") as? JsonObject ?: return null
        val power = d.str("PowerState")?.lowercase()
        return Info(
            name = d.str("name")?.let(::htmlDecode),
            model = d.str("modelName") ?: d.str("model"),
            mac = d.str("wifiMac")?.lowercase()?.takeIf { MAC.matches(it) },
            powerOn = power?.let { it == "on" },
            tokens = d.str("TokenAuthSupport") == "true",
            frame = d.str("FrameTVSupport") == "true"
        )
    }

    /** `GET /api/v2/applications/<id>`: the app is the one on screen. */
    fun appVisible(o: JsonObject?): Boolean = o?.str("visible") == "true"

    /**
     * `GET /api/v2/applications/<id>` of an installed app (the TV answers 404 otherwise): the app,
     * named as the TV names it, else [fallbackName]. Opened over REST ([REST_APP]).
     */
    fun restApp(o: JsonObject?, id: String, fallbackName: String): App? {
        if (o == null || (o.str("id") == null && o.str("name") == null)) return null
        return App(id, o.str("name")?.let(::htmlDecode) ?: fallbackName, REST_APP, null)
    }

    /**
     * Apps known by their Samsung ids, looked for one by one when the TV doesn't list its apps
     * (`ed.installedApp.get` answers nothing on recent models, 2022 onwards): name → its ids (they
     * change with the year and the region; the first one the TV knows wins). Ids as collected by
     * the community (rs.ltd, Home Assistant's forum, samsung-tv-ws-api's list); 1.5.6: the ids of a
     * 2025 S90F (Canal+, Hue Sync, Netflix, Disney+, Plex), checked by Xavier, first. Any other app
     * opens by its id.
     */
    val KNOWN_APPS: List<Pair<String, List<String>>> =
        listOf(
            "Netflix" to listOf("org.tizen.netflix-app", "3201907018807", "11101200001"),
            "YouTube" to listOf("111299001912", "9Ur5IzDKqV.TizenYouTube"),
            "Prime Video" to listOf("3201910019365", "3201512006785"),
            "Disney+" to listOf("MCmYXNxgcu.DisneyPlus", "3201901017640", "3202009021709", "3202204027038"),
            "Apple TV" to listOf("3201807016597"),
            "Max" to listOf("3202301029760"),
            "Paramount+" to listOf("3201710014981", "3202110025305"),
            "Plex" to listOf("kIciSQlYEM.plex", "3201512006963", "hKfNDgSKZ3.tizenplex"),
            "Spotify" to listOf("3201606009684"),
            "Deezer" to listOf("3201608010191"),
            "Apple Music" to listOf("3201908019041"),
            "Amazon Music" to listOf("3201710014874"),
            "YouTube Music" to listOf("eDYF0FXdGw.YouTubeMusic"),
            "Tidal" to listOf("lbusDFJn4K.Tidal"),
            "Twitch" to listOf("3202203026841", "UhHSKyDHuf.Twitch"),
            "DAZN" to listOf("3201806016390"),
            "Crunchyroll" to listOf("3202302030097"),
            "Pluto TV" to listOf("3201808016802"),
            "Xbox" to listOf("3202203026799"),
            "GeForce NOW" to listOf("3202102023078"),
            "Steam Link" to listOf("zHBvQ6qEu8.steamlink"),
            "Canal+" to listOf("QmpwflxWdt.CanalPlusLauncher", "3201606009910"),
            "Hue Sync" to listOf("kupCeIdDkX.HueSync"),
            "france.tv" to listOf("3202103023232"),
            "ARTE" to listOf("3202011022316"),
            "Molotov" to listOf("3201611011210"),
            "OQEE by Free" to listOf("3202103023185"),
            "Internet" to listOf("org.tizen.browser", "3201907018784", "3202010022079")
        )

    // ---- the remote WebSocket ---------------------------------------------------------------------

    /** The remote channel's URL; [tls] false for a TV without tokens (`ws://…:8001`). */
    fun remoteUrl(host: String, token: String?, tls: Boolean = true): String {
        val name = Base64.getEncoder().encodeToString(REMOTE_NAME.toByteArray())
        val base = if (tls) "wss://$host:$WSS_PORT" else "ws://$host:$REST_PORT"
        val t = token?.takeIf { it.isNotBlank() }?.let { "&token=$it" }.orEmpty()
        return "$base/api/v2/channels/samsung.remote.control?name=$name$t"
    }

    /** One message the TV sends: its `event` and `data`. */
    data class Event(val name: String, val data: JsonElement?)

    fun event(text: String): Event? {
        val o = parse(text) ?: return null
        return Event(o.str("event") ?: return null, o["data"])
    }

    const val CONNECT = "ms.channel.connect"
    const val UNAUTHORIZED = "ms.channel.unauthorized"
    const val TIMEOUT = "ms.channel.timeOut"
    const val APPS_EVENT = "ed.installedApp.get"
    const val ICON_EVENT = "ed.apps.icon"

    /** `ms.channel.connect`: the token the TV gives a newly allowed remote (none when it already knows it). */
    fun tokenOf(e: Event): String? = (e.data as? JsonObject)?.str("token")

    /** A key: `Click`, or `Press` / `Release` for a long press. */
    fun key(code: String, cmd: String = "Click"): String = buildJsonObject {
        put("method", "ms.remote.control")
        putJsonObject("params") {
            put("Cmd", cmd)
            put("DataOfCmd", code)
            put("Option", "false")
            put("TypeOfRemote", "SendRemoteKey")
        }
    }.toString()

    private fun emit(event: String, data: JsonObject? = null): String = buildJsonObject {
        put("method", "ms.channel.emit")
        putJsonObject("params") {
            put("event", event)
            put("to", "host")
            data?.let { put("data", it) }
        }
    }.toString()

    fun appsRequest(): String = emit(APPS_EVENT)

    /** Opens an app: a web app (type 2) by deep link, a native one (type 4) by launch. */
    fun launch(appId: String, appType: Int = WEB_APP): String = emit(
        "ed.apps.launch",
        buildJsonObject {
            put("appId", appId)
            put("action_type", if (appType == WEB_APP) "DEEP_LINK" else "NATIVE_LAUNCH")
            put("metaTag", "")
        }
    )

    fun iconRequest(path: String): String = emit(ICON_EVENT, buildJsonObject { put("iconPath", path) })

    /** An installed app. */
    data class App(val id: String, val name: String, val type: Int, val iconPath: String?)

    /** `ed.installedApp.get`: the apps, or null when this isn't that answer. */
    fun apps(e: Event): List<App>? {
        if (e.name != APPS_EVENT) return null
        val list = ((e.data as? JsonObject)?.get("data") as? JsonArray) ?: return emptyList()
        return list.mapNotNull { el ->
            val a = el as? JsonObject ?: return@mapNotNull null
            App(
                id = a.str("appId") ?: return@mapNotNull null,
                name = a.str("name") ?: return@mapNotNull null,
                type = (a["app_type"] as? JsonPrimitive)?.intOrNull ?: WEB_APP,
                iconPath = a.str("icon")
            )
        }
    }

    /** `ed.apps.icon`: the icon asked for, as image bytes. */
    fun icon(e: Event): ByteArray? {
        if (e.name != ICON_EVENT) return null
        val b64 = (e.data as? JsonObject)?.str("imageBase64") ?: return null
        return runCatching { Base64.getDecoder().decode(b64.substringAfter("base64,")) }.getOrNull()
    }

    const val WEB_APP = 2

    /** An app found over REST (see [restApp]): opened with `POST /api/v2/applications/<id>`. */
    const val REST_APP = 0

    // ---- UPnP volume (port 9197) --------------------------------------------------------------------

    const val RENDERING_PATH = "/upnp/control/RenderingControl1"

    fun upnpUrl(host: String) = "http://$host:$UPNP_PORT$RENDERING_PATH"

    fun soapAction(method: String) = "\"urn:schemas-upnp-org:service:RenderingControl:1#$method\""

    fun soapBody(method: String, extra: String = "") =
        "<?xml version=\"1.0\" encoding=\"utf-8\"?><s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
            "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\"><s:Body>" +
            "<u:$method xmlns:u=\"urn:schemas-upnp-org:service:RenderingControl:1\">" +
            "<InstanceID>0</InstanceID><Channel>Master</Channel>$extra</u:$method></s:Body></s:Envelope>"

    fun setVolumeBody(level: Int) = soapBody("SetVolume", "<DesiredVolume>${level.coerceIn(0, MAX_VOLUME)}</DesiredVolume>")

    fun soapValue(xml: String?, tag: String): String? = xml?.let {
        Regex(
            "<$tag>\\s*(.*?)\\s*</$tag>",
            RegexOption.DOT_MATCHES_ALL
        ).find(it)?.groupValues?.get(1)?.takeIf { v -> v.isNotBlank() }
    }

    const val MAX_VOLUME = 100

    // ---- helpers ------------------------------------------------------------------------------------

    private val MAC = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")

    /** Names come HTML-escaped (`[TV] Samsung &amp; Co`). */
    private fun htmlDecode(s: String) =
        s.replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">").replace("&amp;", "&")

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * Samsung's key codes (`KEY_…`), and the Android names the TV remote card, hotkeys and keypad send.
 * Any `KEY_…` name is sent as it is: Samsung has many more (`KEY_PICTURE_SIZE`, `KEY_AMBIENT`…).
 */
object SamsungKeys {
    private val ANDROID =
        mapOf(
            "DPAD_UP" to "KEY_UP", "DPAD_DOWN" to "KEY_DOWN", "DPAD_LEFT" to "KEY_LEFT", "DPAD_RIGHT" to "KEY_RIGHT",
            "DPAD_CENTER" to "KEY_ENTER", "OK" to "KEY_ENTER", "ENTER" to "KEY_ENTER", "SELECT" to "KEY_ENTER", "CENTER" to "KEY_ENTER",
            "UP" to "KEY_UP", "DOWN" to "KEY_DOWN", "LEFT" to "KEY_LEFT", "RIGHT" to "KEY_RIGHT",
            "BACK" to "KEY_RETURN", "ESCAPE" to "KEY_EXIT", "EXIT" to "KEY_EXIT", "HOME" to "KEY_HOME",
            "MENU" to "KEY_MENU", "SETTINGS" to "KEY_MENU", "INFO" to "KEY_INFO", "GUIDE" to "KEY_GUIDE",
            "VOLUME_UP" to "KEY_VOLUP", "VOLUME_DOWN" to "KEY_VOLDOWN", "VOLUME_MUTE" to "KEY_MUTE", "MUTE" to "KEY_MUTE",
            "CHANNEL_UP" to "KEY_CHUP", "CHANNEL_DOWN" to "KEY_CHDOWN", "PAGE_UP" to "KEY_CHUP", "PAGE_DOWN" to "KEY_CHDOWN",
            "MEDIA_PLAY" to "KEY_PLAY", "MEDIA_PAUSE" to "KEY_PAUSE", "MEDIA_STOP" to "KEY_STOP",
            "MEDIA_REWIND" to "KEY_REWIND", "MEDIA_FAST_FORWARD" to "KEY_FF", "FAST_FORWARD" to "KEY_FF",
            "MEDIA_RECORD" to "KEY_REC", "MEDIA_PREVIOUS" to "KEY_REWIND", "MEDIA_NEXT" to "KEY_FF",
            "PROG_RED" to "KEY_RED", "PROG_GREEN" to "KEY_GREEN", "PROG_YELLOW" to "KEY_YELLOW", "PROG_BLUE" to "KEY_CYAN",
            "RED" to "KEY_RED", "GREEN" to "KEY_GREEN", "YELLOW" to "KEY_YELLOW", "BLUE" to "KEY_CYAN",
            "TV_INPUT" to "KEY_SOURCE", "CAPTIONS" to "KEY_CAPTION", "MEDIA_AUDIO_TRACK" to "KEY_AD",
            "TV" to "KEY_TV", "LAST_CHANNEL" to "KEY_PRECH", "CHANNEL_LIST" to "KEY_CH_LIST", "TOOLS" to "KEY_TOOLS"
        ) + (0..9).associate { "$it" to "KEY_$it" }

    private val NATIVE = Regex("^KEY_[A-Z0-9_]+$")

    /** Samsung's code for [name] (its own or an Android one); null when it has none. */
    fun of(name: String): String? {
        val n = name.trim().uppercase().removePrefix("KEYCODE_")
        return ANDROID[n] ?: n.takeIf { NATIVE.matches(it) }
    }
}
