package com.custom.astrion.sky

import java.time.LocalDate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Sky boxes' local control (1.5.3), as Sky's own apps use it:
 *  - remote keys: TCP **49160** (Sky Q) or **5900** (Sky+ HD) — the box greets with "SKY…",
 *    is answered back, then takes an 8-byte key code (pressed, then released);
 *  - a Sky Q's state: REST on **9006** (`/as/system/information`, `/as/services/…`), and its
 *    UPnP "SkyPlay" service on **49153** (SOAP `GetTransportInfo`, `GetMediaInfo`);
 *  - what a channel shows: Sky's public guide (`atlantis.epgsky.com`) and image service.
 *
 * Ports, paths and key codes as documented by pyskyqremote and skyboxremote (both MIT), the
 * libraries Home Assistant's Sky integrations use. Pure, so tested.
 */
object SkyProtocol {
    const val SKY_Q_PORT = 49160
    const val SKY_HD_PORT = 5900
    const val REST_PORT = 9006
    const val UPNP_PORT = 49153
    const val USER_AGENT = "SKYPLUS_skyplus"
    const val DESCRIPTIONS = 50

    /** Sky's key names → codes. */
    val KEYS: Map<String, Int> =
        mapOf(
            "power" to 0, "select" to 1, "backup" to 2, "dismiss" to 2, "channelup" to 6, "channeldown" to 7,
            "interactive" to 8, "sidebar" to 8, "help" to 9, "services" to 10, "search" to 10, "tvguide" to 11,
            "home" to 11, "i" to 14, "text" to 15, "up" to 16, "down" to 17, "left" to 18, "right" to 19,
            "red" to 32, "green" to 33, "yellow" to 34, "blue" to 35, "play" to 64, "pause" to 65, "stop" to 66,
            "record" to 67, "fastforward" to 69, "rewind" to 71, "boxoffice" to 240, "sky" to 241
        ) + (0..9).associate { "$it" to 48 + it }

    /** The Android names the TV remote card, hotkeys and keypad send. */
    private val ANDROID =
        mapOf(
            "dpad_up" to "up", "dpad_down" to "down", "dpad_left" to "left", "dpad_right" to "right",
            "dpad_center" to "select", "ok" to "select", "enter" to "select", "center" to "select",
            "back" to "backup", "escape" to "backup", "menu" to "home", "settings" to "home", "guide" to "tvguide",
            "info" to "i", "channel_up" to "channelup", "channel_down" to "channeldown", "media_play" to "play",
            "media_pause" to "pause", "media_stop" to "stop", "media_record" to "record",
            "media_fast_forward" to "fastforward", "fast_forward" to "fastforward", "media_rewind" to "rewind",
            "prog_red" to "red", "prog_green" to "green", "prog_yellow" to "yellow", "prog_blue" to "blue",
            "captions" to "text", "page_up" to "channelup", "page_down" to "channeldown"
        )

    /** Sky's key for [name] (its own name or an Android one); null when it has none. */
    fun keyOf(name: String): String? {
        val n = name.trim().lowercase().removePrefix("keycode_")
        return ANDROID[n] ?: n.takeIf { it in KEYS }
    }

    /** The 8 bytes for key [code]; byte 1 is 1 (pressed), then sent again with 0 (released). */
    fun keyBytes(code: Int): ByteArray = byteArrayOf(4, 1, 0, 0, 0, 0, (224 + code / 16).toByte(), (code % 16).toByte())

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(text: String?): JsonObject? = text?.let { runCatching { json.parseToJsonElement(it) as? JsonObject }.getOrNull() }

    // ---- REST (port 9006) ---------------------------------------------------------------------

    const val SYSTEM_INFO = "system/information"
    const val DEVICE_INFO = "system/deviceinformation"
    const val FAVOURITES = "services/favourites"

    fun channelsPath(bouquet: String, subBouquet: String) = "services/$bouquet/$subBouquet"

    /** `system/information`: in active standby (on the network, screen dark). */
    fun standby(o: JsonObject): Boolean = o.bool("activeStandby") == true

    /** What `system/deviceinformation` says about the box. */
    data class DeviceInfo(
        val model: String?,
        val serial: String?,
        val country: String?,
        val bouquet: String?,
        val subBouquet: String?
    )

    fun deviceInfo(o: JsonObject): DeviceInfo = DeviceInfo(
        model = o.str("hardwareName") ?: o.str("modelNumber"),
        serial = o.str("serialNumber"),
        country = o.str("countryCode"),
        bouquet = o.str("bouquet"),
        subBouquet = o.str("subbouquet")
    )

    /** A channel: its number on the box, name and service id. */
    data class Channel(val number: String, val name: String, val sid: String) {
        val label: String get() = "$number $name"
    }

    /** `services/<bouquet>/<subbouquet>`: every channel, in number order. */
    fun channels(o: JsonObject?): List<Channel> = (o?.get("services") as? JsonArray).orEmpty().mapNotNull { el ->
        val c = el as? JsonObject ?: return@mapNotNull null
        Channel(c.str("c") ?: return@mapNotNull null, c.str("t") ?: return@mapNotNull null, c.str("sid") ?: return@mapNotNull null)
    }

    /** `services/favourites`: the favourite channels' service ids, in the box's order. */
    fun favourites(o: JsonObject?): List<String> =
        (o?.get("favourites") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("sid") }

    // ---- UPnP SkyPlay (port 49153) --------------------------------------------------------------

    fun descriptionUrl(host: String, index: Int) = "http://$host:$UPNP_PORT/description$index.xml"

    /** A device description: the SkyPlay service's control URL, when it is the SkyControl device's. */
    fun controlPath(xml: String): String? {
        if (!xml.contains("SkyControl")) return null
        val service = Regex("<service>(.*?)</service>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { it.groupValues[1] }
            .firstOrNull { it.contains("urn:nds-com:serviceId:SkyPlay") } ?: return null
        return Regex("<controlURL>\\s*(.*?)\\s*</controlURL>").find(service)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
    }

    fun soapAction(method: String) = "\"urn:schemas-nds-com:service:SkyPlay:2#$method\""

    fun soapBody(method: String) = "<s:Envelope xmlns:s='http://schemas.xmlsoap.org/soap/envelope/' " +
        "s:encodingStyle='http://schemas.xmlsoap.org/soap/encoding/'><s:Body>" +
        "<u:$method xmlns:u=\"urn:schemas-nds-com:service:SkyPlay:2\"><InstanceID>0</InstanceID></u:$method>" +
        "</s:Body></s:Envelope>"

    /** One value of a SOAP answer (`CurrentTransportState`, `CurrentURI`…). */
    fun soapValue(xml: String, tag: String): String? =
        Regex("<$tag>\\s*(.*?)\\s*</$tag>", RegexOption.DOT_MATCHES_ALL).find(xml)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }
            ?.replace("&amp;", "&")

    /** What plays: live TV (a channel's service id), a recording, or nothing. */
    sealed class Media {
        data class Live(val sid: String) : Media()

        data class Recording(val pvrId: String) : Media()

        object None : Media()
    }

    /** `CurrentURI`: `xsi://<sid in hex>` live, `file://pvr/<id>` a recording. */
    fun mediaOf(uri: String?): Media = when {
        uri == null -> Media.None
        uri.startsWith("xsi://") -> uri.removePrefix("xsi://").toLongOrNull(HEX)?.let { Media.Live(it.toString()) } ?: Media.None
        uri.contains("pvr") -> Media.Recording("P" + uri.substringAfterLast('/'))
        else -> Media.None
    }

    // ---- Sky's guide and images (internet) ------------------------------------------------------

    /** Sky's territory and image host prefix by the box's country (Ireland is on the UK's). */
    fun territoryOf(country: String?): Pair<String, String> = when (country?.uppercase()) {
        "DEU", "AUT" -> "DE" to "de."
        "ITA" -> "IT" to "it."
        else -> "GB" to ""
    }

    fun scheduleUrl(sid: String, date: LocalDate) = "http://atlantis.epgsky.com/as/schedule/${date.toString().replace("-", "")}/$sid"

    fun channelLogoUrl(sid: String, name: String, country: String?): String {
        val (territory, prefix) = territoryOf(country)
        val id = name.lowercase().filter { it.isLetterOrDigit() }
        return "https://${prefix}imageservice.sky.com/logo/skychb_$sid$id/600/600?territory=$territory&provider=SKY&proposition=SKYQ"
    }

    fun programmeImageUrl(uuid: String, country: String?): String {
        val (territory, prefix) = territoryOf(country)
        return "https://${prefix}imageservice.sky.com/pd-image/$uuid/16-9/1024?territory=$territory&provider=SKY&proposition=SKYQ"
    }

    /** A programme of the guide. */
    data class Programme(
        val title: String,
        val startS: Long,
        val durationS: Long,
        val synopsis: String? = null,
        val season: Int? = null,
        val episode: Int? = null,
        val uuid: String? = null
    ) {
        val endS: Long get() = startS + durationS
    }

    /** A schedule answer: the channel's programmes that day. */
    fun schedule(o: JsonObject?): List<Programme> {
        val day = (o?.get("schedule") as? JsonArray)?.firstOrNull() as? JsonObject ?: return emptyList()
        return (day["events"] as? JsonArray).orEmpty().mapNotNull { el ->
            val e = el as? JsonObject ?: return@mapNotNull null
            Programme(
                title = e.str("t") ?: return@mapNotNull null,
                startS = e.long("st") ?: return@mapNotNull null,
                durationS = e.long("d") ?: 0,
                synopsis = e.str("sy"),
                season = e.long("seasonnumber")?.toInt()?.takeIf { it > 0 },
                episode = e.long("episodenumber")?.toInt()?.takeIf { it > 0 },
                uuid = e.str("programmeuuid")
            )
        }
    }

    /** The programme on at [nowS] (UTC seconds). */
    fun onNow(programmes: List<Programme>, nowS: Long): Programme? = programmes.firstOrNull { nowS >= it.startS && nowS < it.endS }

    /** The guide's day (UTC) at [nowS]. */
    fun today(nowS: Long): LocalDate = LocalDate.ofEpochDay(nowS / SECONDS_PER_DAY)

    private const val HEX = 16
    private const val SECONDS_PER_DAY = 86_400L

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.let {
        it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}
