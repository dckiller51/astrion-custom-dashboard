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
    val musicType: Int? = null
)

/** The film or episode a video file is, from the player's poster wall (for title and backdrop). */
data class ZidooMovie(
    val id: Long,
    val name: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null
)

/**
 * Reads the answers of the Zidoo control API (the player's built-in HTTP server on port 9529, the
 * one Zidoo's own phone app uses, documented by Zidoo). Pure, so it is unit-tested.
 */
object ZidooParse {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(body: String): JsonObject? = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let {
        it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.int(key: String) =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull

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
        val season = o.obj("season")?.obj("aggregation")
        val id = movie?.long("id") ?: episode?.long("id") ?: o.obj("video")?.long("parentId") ?: return null
        if (id <= 0) return null
        return ZidooMovie(
            id = id,
            name = movie?.str("name") ?: episode?.obj("aggregation")?.str("name"),
            seriesName = season?.str("tvName"),
            season = season?.int("seasonNumber"),
            episode = episode?.obj("aggregation")?.int("episodeNumber")
        )
    }

    private const val HTTP_OK = 200
    private val MAC = Regex("^([0-9A-Fa-f]{2}[:-]){5}[0-9A-Fa-f]{2}$")
}

/** Plain values of a JSON element, for service data. */
internal fun plainOf(el: JsonElement?): Any? = when (el) {
    null, JsonNull -> null
    is JsonPrimitive -> if (el.isString) el.content else el.booleanOrNull ?: el.content.toDoubleOrNull() ?: el.content
    is JsonArray -> el.map { plainOf(it) }
    else -> el.toString()
}
