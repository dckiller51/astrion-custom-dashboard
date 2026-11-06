package com.custom.astrion.zidoo

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The player's identity, from `ZidooControlCenter/getModel`. */
data class ZidooModel(val model: String?, val firmware: String?, val mac: String?)

/** What the player's video or music player says is playing. */
data class ZidooPlayback(
    val source: ZidooSource,
    val playing: Boolean,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val path: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    /** Music: the track's id and type, for its cover. */
    val musicId: Long? = null,
    val musicType: Int? = null,
    /** A cover the player gives as a full URL (Eversolo, streaming apps such as Apple Music). */
    val artUrl: String? = null,
    /** What it plays, as an audiophile reads it (1.5.0): "FLAC · 44.1 kHz · 16 bit · 913 kbps". */
    val quality: String? = null,
    /** False when the player says it can't seek (an AirPlay stream, 1.5.0). */
    val canSeek: Boolean = true
)

/**
 * An Eversolo's inputs (1.5.0, from Eversolo's command list for the DMP-A6): the name shown in the
 * Sources pop-up, the key that selects it, and how its `getState` names it (`volumeData.intputTag`,
 * "XMOS-XMOS" for the internal player).
 */
enum class EversoloInput(val label: String, val key: String, private val tags: List<String>) {
    INTERNAL("Internal player", "Key.DAC.XMOS", listOf("XMOS")),
    BLUETOOTH("Bluetooth In", "Key.DAC.BT", listOf("BT", "BLUETOOTH")),
    USB("USB-C In", "Key.DAC.USB", listOf("USB")),

    // Its coaxial input is "RCA" in its own calls (setInputList?tag=RCA, Neoheresy's Eversolo panel).
    COAXIAL("Coaxial In", "Key.DAC.COA", listOf("COA", "RCA")),
    OPTICAL("Optical In", "Key.DAC.SPDIF", listOf("SPDIF", "OPT"))
    ;

    companion object {
        fun ofLabel(label: Any?): EversoloInput? = entries.firstOrNull { it.label == label }

        fun ofTag(tag: String?): EversoloInput? {
            val t = tag?.uppercase()?.takeIf { it.isNotBlank() } ?: return null
            return entries.firstOrNull { input -> input.tags.any { t.startsWith(it) } }
                ?: entries.firstOrNull { input -> input.tags.any { it in t } }
        }
    }
}

/** The film or episode a video file is, from the player's poster wall (for title and backdrop). */
data class ZidooMovie(
    val id: Long,
    val name: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    /** An episode's season and series on the poster wall (1.4.9): their artwork, the episode has none of its own. */
    val seasonId: Long? = null,
    val seriesId: Long? = null
) {
    val isEpisode: Boolean get() = episode != null || seasonId != null

    /** The poster-wall ids whose artwork to try, best first: the series, the season, the episode itself. */
    fun artIds(): List<Long> = if (isEpisode) listOfNotNull(seriesId, seasonId, id).filter { it > 0 }.distinct() else listOf(id)
}

/**
 * Reads the answers of the Zidoo control API (the player's built-in HTTP server on port 9529, the
 * one Zidoo's own phone app uses, documented by Zidoo). Pure, so it is unit-tested.
 */
object ZidooParse {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): JsonObject? = parseAny(body) as? JsonObject

    fun parseAny(body: String): JsonElement? = runCatching { json.parseToJsonElement(body) }.getOrNull()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let {
        it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.int(key: String) =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

    /**
     * [value] for a query string, the way the player reads it (1.4.8): spaces as `%20` and `/`
     * left as is. Java's form encoding (`+` for a space, `%2F`) made the poster wall miss every
     * file path with a space, so no film title nor backdrop.
     */
    fun query(value: String): String = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20").replace("%2F", "/")

    fun ok(o: JsonObject?): Boolean = o != null && o.int("status") == HTTP_OK

    fun model(o: JsonObject?): ZidooModel? {
        if (!ok(o)) return null
        o!!
        val mac = listOfNotNull(o.str("net_mac"), o.str("wif_mac")).firstOrNull { MAC.matches(it) }
        return ZidooModel(o.str("model"), o.str("firmware"), mac?.lowercase()?.replace('-', ':'))
    }

    /** `ZidooVideoPlay/getPlayStatus`: null when its video player plays nothing. */
    fun video(o: JsonObject?): ZidooPlayback? {
        if (!ok(o)) return null
        val v = o!!.obj("video") ?: return null
        return ZidooPlayback(
            source = ZidooSource.VIDEO,
            playing = v.int("status") == 1,
            title = v.str("title"),
            path = v.str("path"),
            durationMs = v.long("duration"),
            positionMs = v.long("currentPosition")
        )
    }

    /** `ZidooMusicControl/getPlayStatus`: null when its music player plays nothing. */
    fun music(o: JsonObject?): ZidooPlayback? {
        if (!ok(o)) return null
        val m = o!!.obj("music") ?: return null
        val state = o.obj("state")
        val playing = state?.bool("playing") ?: (o.bool("isPlay") == true)
        return ZidooPlayback(
            source = ZidooSource.MUSIC,
            playing = playing,
            title = m.str("title"),
            artist = m.str("artist"),
            album = m.str("album"),
            path = m.str("uri"),
            durationMs = state?.long("duration"),
            positionMs = state?.long("position"),
            musicId = m.long("id"),
            musicType = m.int("type")
        )
    }

    /**
     * `ZidooMusicControl/v2/getState` (Eversolo, 1.4.8): what plays, built-in player or a streaming
     * app (Apple Music, Qobuz…), with the cover as a URL. `state` 0: nothing; 3: playing; else paused.
     * Null when it plays nothing or the player doesn't have this call (a Zidoo).
     *
     * An app the Eversolo only hosts (1.4.9: the official Apple Music) is in `everSoloPlayInfo`:
     * there `playingMusic` keeps the last track of the Eversolo's own player and `state` is 4, so
     * the card showed an old title, an old cover and a frozen bar. Its `everSoloPlayAudioInfo` is
     * the track really playing; `state` still says playing (3) or paused (4).
     */
    fun musicV2(o: JsonObject?): ZidooPlayback? {
        val state = o?.int("state") ?: return null
        if (state == 0) return null
        hostedApp(o, state == V2_PLAYING)?.let { return it }
        val m = o.obj("playingMusic") ?: return null
        val art = coverUrl(m.str("albumArtBig") ?: m.str("albumArt"))
        val quality =
            qualityOf(
                m.str("extension")?.uppercase(),
                m.str("sampleRate"),
                m.str("bits")?.takeIf { it != "0" }?.let { "$it bit" },
                m.str("bitrate")?.let(::kbps)
            )
        return ZidooPlayback(
            source = ZidooSource.MUSIC,
            playing = state == V2_PLAYING,
            title = m.str("title"),
            artist = m.str("artist"),
            album = m.str("album"),
            path = m.str("uri"),
            durationMs = o.long("duration"),
            positionMs = o.long("position"),
            musicId = m.long("id"),
            musicType = m.int("type"),
            artUrl = art,
            quality = quality
        )
    }

    /** `getState`'s mute on an Eversolo (`volumeData.isMute`); null when not said. */
    fun muted(o: JsonObject?): Boolean? = o?.obj("volumeData")?.bool("isMute")

    /** `getState`'s input on an Eversolo (`volumeData.intputTag`, sic); null on a Zidoo or when not said. */
    fun input(o: JsonObject?): EversoloInput? = EversoloInput.ofTag(o?.obj("volumeData")?.str("intputTag"))

    private fun qualityOf(vararg parts: String?): String? = parts.filterNotNull().filter {
        it.isNotBlank()
    }.joinToString(" · ").ifEmpty { null }

    /** "912,99 Kbps" → "913 kbps". */
    private fun kbps(raw: String): String? {
        val n = raw.lowercase().substringBefore("kbps").trim().replace(',', '.').toDoubleOrNull() ?: return null
        return if (n > 0) "${Math.round(n)} kbps" else null
    }

    /** 44100 → "44.1 kHz". */
    private fun khz(hz: Long): String {
        val k = hz / HZ_PER_KHZ
        return (if (k == Math.floor(k)) "${k.toLong()}" else "%.1f".format(java.util.Locale.ROOT, k)) + " kHz"
    }

    /**
     * What an app the Eversolo hosts plays (see [musicV2]), or what is streamed to it — AirPlay,
     * Spotify Connect (1.5.0: `playType` 6 / 4, `isOpenApp` false there, and `playingMusic` still the
     * last track of its own player); null when its own player plays.
     */
    private fun hostedApp(o: JsonObject, playing: Boolean): ZidooPlayback? {
        val info = o.obj("everSoloPlayInfo") ?: return null
        val app = info.bool("isOpenApp") == true
        val streamed = (info.long("playType") ?: o.long("playType"))?.toInt() in STREAMED
        if (!app && !streamed) return null
        val track = info.obj("everSoloPlayAudioInfo") ?: return null
        val title = track.str("songName") ?: return null
        // A streamed track's cover is the player's `icon` call (an app's `icon` is the app's own).
        val art = coverUrl(track.str("albumUrl")) ?: coverUrl(info.str("onlineIcon")) ?: if (app) null else coverUrl(info.str("icon"))
        return ZidooPlayback(
            source = ZidooSource.MUSIC,
            playing = playing,
            title = title,
            artist = track.str("artistName"),
            album = track.str("albumName"),
            durationMs = info.long("duration") ?: o.long("duration"),
            positionMs = info.long("currentPosition") ?: o.long("position"),
            artUrl = art,
            quality =
            qualityOf(
                track.long("audioSampleRate")?.takeIf { it > 0 }?.let(::khz),
                track.long("audioBitsPerSample")?.takeIf { it > 0 }?.let { "$it bit" }
            ),
            canSeek = info.bool("isCanSeek") != false
        )
    }

    /**
     * A cover as the player gives it (1.5.0), made into one an image can be read from, or null:
     * - a full URL, an Apple-style template (`…/{w}x{h}bb.jpg`) filled with a size;
     * - one of the player's own calls (`/SystemSettings/getItemSettingIcon?iconName=…`, an app it
     *   hosts: the official Apple Music's cover of a track not cached as a URL) kept as is, relative:
     *   the registry puts the player's address in front;
     * - a file on the player (`/storage/emulated/0/…`) or a video (an animated cover): null.
     */
    fun coverUrl(url: String?): String? {
        val raw = url?.trim().orEmpty()
        val u = raw.takeIf { it.startsWith("http") || isPlayerCall(it) } ?: return null
        val path = u.substringBefore('?').lowercase()
        if (VIDEO_COVER.any { path.endsWith(it) }) return null
        return u
            .replace("{w}", COVER_PX)
            .replace("{h}", COVER_PX)
            .replace("{c}", "bb")
            .replace("{f}", "jpg")
    }

    private fun isPlayerCall(path: String) = path.startsWith("/") && '?' in path && LOCAL_FILE.none { path.startsWith(it) }

    /** `ZidooControlCenter/Apps/getApps`: label → package, the apps it can open. */
    fun apps(o: JsonObject?): Map<String, String> {
        if (!ok(o)) return emptyMap()
        val list = o!!["apps"] as? JsonArray ?: return emptyMap()
        val out = linkedMapOf<String, String>()
        list.forEach { el ->
            val app = el as? JsonObject ?: return@forEach
            if (app.bool("isCanOpen") == false) return@forEach
            val pkg = app.str("packageName") ?: return@forEach
            out.putIfAbsent(app.str("label") ?: pkg, pkg)
        }
        return out
    }

    /** `ZidooPoster/v2/getAggregationOfFile`: the film or episode, null when the file isn't on the poster wall. */
    fun movie(o: JsonObject?): ZidooMovie? {
        if (o == null) return null
        val movie = o.obj("movie")
        val episode = o.obj("episode")
        val seasonObj = o.obj("season")
        val season = seasonObj?.obj("aggregation")
        val id = movie?.long("id") ?: episode?.long("id") ?: o.obj("video")?.long("parentId") ?: return null
        if (id <= 0) return null
        return ZidooMovie(
            id = id,
            name = movie?.str("name") ?: episode?.obj("aggregation")?.str("name"),
            seriesName = season?.str("tvName"),
            season = season?.int("seasonNumber"),
            episode = episode?.obj("aggregation")?.int("episodeNumber"),
            seasonId = seasonObj?.long("id") ?: episode?.long("parentId"),
            seriesId = seasonObj?.long("parentId")
        )
    }

    private const val HTTP_OK = 200
    private const val COVER_PX = "600"
    private val VIDEO_COVER = listOf(".m3u8", ".mp4", ".mov", ".webm")
    private val LOCAL_FILE = listOf("/storage/", "/sdcard/", "/data/", "/mnt/")
    private const val V2_PLAYING = 3
    private const val HZ_PER_KHZ = 1000.0

    /** `playType` of what is streamed to an Eversolo: Spotify Connect, AirPlay. */
    private val STREAMED = setOf(4, 6)
    private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
}

/** Plain values of a JSON element, for service data. */
internal fun plainOf(el: JsonElement?): Any? = when (el) {
    null, JsonNull -> null
    is JsonPrimitive -> if (el.isString) el.content else el.booleanOrNull ?: el.content.toDoubleOrNull() ?: el.content
    is JsonArray -> el.map { plainOf(it) }
    else -> el.toString()
}
