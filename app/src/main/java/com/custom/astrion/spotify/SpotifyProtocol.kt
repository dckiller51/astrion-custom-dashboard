package com.custom.astrion.spotify

import java.net.URLEncoder
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** A Spotify Connect device, as `GET /me/player/devices` lists it. */
data class SpotifyDevice(
    val id: String,
    val name: String,
    val type: String,
    val active: Boolean,
    val volume: Int?,
    val supportsVolume: Boolean
)

/** One Web API call: [method] on [path] (with its query), [body] as JSON or none. */
data class SpotifyCall(val method: String, val path: String, val body: JsonObject? = null)

/**
 * Spotify's Web API as Home Assistant's media player speaks it (1.6.2): `GET /me/player` as a
 * `media_player.spotify_…` entity's state and attributes, a service call as the Web API's call,
 * a search's answer and the user's playlists as Home Assistant's media browser items, so the
 * media player card, the media remote card, "Search music" and "Browse" work unchanged. Pure, so it
 * is unit-tested.
 */
object SpotifyProtocol {
    const val API = "https://api.spotify.com/v1"

    /** The browse root, and its folders. */
    const val ROOT = "spotify://root"
    const val PLAYLISTS = "spotify://playlists"
    const val RECENT = "spotify://recent"

    // Home Assistant's MediaPlayerEntityFeature.
    private const val PAUSE = 1
    private const val SEEK = 2
    private const val VOLUME_SET = 4
    private const val PREVIOUS = 16
    private const val NEXT = 32
    private const val PLAY_MEDIA = 512
    private const val VOLUME_STEP = 1024
    private const val SELECT_SOURCE = 2048
    private const val STOP = 4096
    private const val PLAY = 16384
    private const val SHUFFLE = 32768
    private const val BROWSE = 131072
    private const val REPEAT = 262144
    private const val SEARCH = 4194304

    private const val BASE_FEATURES =
        PAUSE or SEEK or PREVIOUS or NEXT or PLAY_MEDIA or SELECT_SOURCE or STOP or PLAY or SHUFFLE or BROWSE or REPEAT or SEARCH
    private const val VOLUME_FEATURES = VOLUME_SET or VOLUME_STEP
    private const val MS = 1000.0
    private const val PERCENT = 100.0
    private const val VOLUME_STEP_PCT = 10
    private const val ART_PX = 300

    // ---- state -----------------------------------------------------------------

    /** `GET /me/player/devices`' answer. */
    fun devices(body: JsonObject?): List<SpotifyDevice> = body?.arr("devices")?.mapNotNull { el ->
        val d = el as? JsonObject ?: return@mapNotNull null
        val id = d.str("id") ?: return@mapNotNull null
        SpotifyDevice(
            id = id,
            name = d.str("name") ?: "Spotify",
            type = d.str("type").orEmpty(),
            active = d.bool("is_active") == true,
            volume = d.int("volume_percent"),
            supportsVolume = d.bool("supports_volume") != false
        )
    }.orEmpty()

    /**
     * The entity's state and attributes from `GET /me/player` ([player], null when nothing plays
     * anywhere: 204) and the devices. [nowIso]: when the position was read. [wakeable]: the
     * players that can open Spotify, offered as sources after the devices (see [SpotifyWake]).
     */
    fun entity(
        name: String,
        player: JsonObject?,
        devices: List<SpotifyDevice>,
        nowIso: String,
        wakeable: List<String> = emptyList()
    ): Pair<String, Map<String, Any?>> {
        val a = LinkedHashMap<String, Any?>()
        a["friendly_name"] = name
        a["app_name"] = "Spotify"
        a["astrion_source"] = "spotify"
        val device = player?.obj("device")
        val deviceName = device?.str("name")
        val known = (devices.map { it.name } + listOfNotNull(deviceName) + wakeable).distinct()
        a["source_list"] = known
        deviceName?.let { a["source"] = it }
        val supportsVolume = device?.bool("supports_volume") != false
        a["supported_features"] = BASE_FEATURES or if (supportsVolume) VOLUME_FEATURES else 0
        if (player == null) return "idle" to a
        device?.int("volume_percent")?.takeIf { supportsVolume }?.let { a["volume_level"] = it / PERCENT }
        modes(player, a)
        val item = player.obj("item")
        if (item != null) media(item, a)
        player.long("progress_ms")?.let {
            a["media_position"] = (it / MS).roundToInt()
            a["media_position_updated_at"] = nowIso
        }
        player.obj("context")?.str("uri")?.let { a["media_playlist"] = it }
        val state =
            when {
                player.bool("is_playing") == true -> "playing"
                item != null -> "paused"
                else -> "idle"
            }
        return state to a
    }

    /** Shuffle, and repeat as Home Assistant says it (track → one, context → all). */
    private fun modes(player: JsonObject, a: MutableMap<String, Any?>) {
        player.bool("shuffle_state")?.let { a["shuffle"] = it }
        a["repeat"] =
            when (player.str("repeat_state")) {
                "track" -> "one"
                "context" -> "all"
                else -> "off"
            }
    }

    private fun media(item: JsonObject, a: MutableMap<String, Any?>) {
        val episode = item.str("type") == "episode"
        a["media_content_id"] = item.str("uri")
        a["media_content_type"] = if (episode) "podcast" else "music"
        a["media_title"] = item.str("name")
        if (episode) {
            item.obj("show")?.str("name")?.let { a["media_artist"] = it }
        } else {
            item.arr("artists")?.mapNotNull {
                (it as? JsonObject)?.str("name")
            }?.takeIf { it.isNotEmpty() }?.let { a["media_artist"] = it.joinToString(", ") }
            item.obj("album")?.str("name")?.let { a["media_album_name"] = it }
        }
        item.long("duration_ms")?.let { a["media_duration"] = (it / MS).roundToInt() }
        imageOf(item.obj("album") ?: item.obj("show") ?: item)?.let { a["entity_picture"] = it }
    }

    /** The smallest picture at least [ART_PX] wide, else the largest. */
    fun imageOf(o: JsonObject): String? {
        val images = o.arr("images")?.mapNotNull { it as? JsonObject }.orEmpty().filter { it.str("url") != null }
        if (images.isEmpty()) return null
        val sized = images.sortedBy { it.int("width") ?: 0 }
        return (sized.firstOrNull { (it.int("width") ?: 0) >= ART_PX } ?: sized.last()).str("url")
    }

    // ---- commands --------------------------------------------------------------

    /**
     * A `media_player` service call as the Web API's call(s), or null when it isn't one.
     * [playing]: what plays now; [devices] for `select_source`; [fallbackDevice]: where to
     * start playing when no device is active (the last one used).
     */
    fun command(
        service: String,
        data: Map<String, Any?>,
        playing: Boolean,
        volume: Int?,
        devices: List<SpotifyDevice>,
        fallbackDevice: String?
    ): List<SpotifyCall>? {
        val noneActive = devices.none { it.active } && fallbackDevice != null
        val onDevice: (String) -> String = { path ->
            if (noneActive) "$path${if ('?' in path) '&' else '?'}device_id=${enc(fallbackDevice!!)}" else path
        }
        return transport(service, playing, onDevice)?.let { listOf(it) }
            ?: valued(service, data, volume)?.let { listOf(it) }
            ?: when (service) {
                "select_source" -> selectSource(data["source"] as? String, devices)
                "play_media" -> playMedia(data, onDevice)
                else -> null
            }
    }

    /** Play, pause, next, previous: no data. */
    private fun transport(service: String, playing: Boolean, onDevice: (String) -> String): SpotifyCall? = when (service) {
        "media_play", "turn_on" -> SpotifyCall("PUT", onDevice("/me/player/play"))
        "media_pause", "media_stop", "turn_off" -> SpotifyCall("PUT", "/me/player/pause")
        "media_play_pause" -> if (playing) SpotifyCall("PUT", "/me/player/pause") else SpotifyCall("PUT", onDevice("/me/player/play"))
        "media_next_track" -> SpotifyCall("POST", "/me/player/next")
        "media_previous_track" -> SpotifyCall("POST", "/me/player/previous")
        else -> null
    }

    /** Seek, volume, shuffle, repeat: a value each. */
    private fun valued(service: String, data: Map<String, Any?>, volume: Int?): SpotifyCall? = when (service) {
        "media_seek" -> num(data["seek_position"])?.let { SpotifyCall("PUT", "/me/player/seek?position_ms=${(it * MS).roundToInt()}") }
        "volume_set" -> num(data["volume_level"])?.let { volumeCall((it * PERCENT).roundToInt()) }
        "volume_up" -> volumeCall((volume ?: 0) + VOLUME_STEP_PCT)
        "volume_down" -> volumeCall((volume ?: 0) - VOLUME_STEP_PCT)
        "shuffle_set" -> SpotifyCall("PUT", "/me/player/shuffle?state=${data["shuffle"] == true || data["shuffle"] == "true"}")
        "repeat_set" -> SpotifyCall("PUT", "/me/player/repeat?state=${repeatOf(data["repeat"])}")
        else -> null
    }

    private fun volumeCall(pct: Int) = SpotifyCall("PUT", "/me/player/volume?volume_percent=${pct.coerceIn(0, 100)}")

    private fun repeatOf(v: Any?): String = when (v) {
        "one" -> "track"
        "all" -> "context"
        else -> "off"
    }

    /** Playback moved to the device named [source] (Spotify Connect), playing there. */
    private fun selectSource(source: String?, devices: List<SpotifyDevice>): List<SpotifyCall>? {
        val device =
            devices.firstOrNull { it.name == source }
                ?: devices.firstOrNull { it.name.equals(source, ignoreCase = true) }
                ?: return null
        return listOf(transfer(device.id))
    }

    /** Playback moved to the device [deviceId], playing there. */
    fun transfer(deviceId: String) = SpotifyCall(
        "PUT",
        "/me/player",
        buildJsonObject {
            putJsonArray("device_ids") { add(JsonPrimitive(deviceId)) }
            put("play", true)
        }
    )

    /** A Spotify URI (or link) played: a track or an episode alone, the rest as a context; `enqueue` add/next queues it. */
    private fun playMedia(data: Map<String, Any?>, onDevice: (String) -> String): List<SpotifyCall>? {
        val uri = uriOf(data["media_content_id"] as? String ?: return null) ?: return null
        val enqueue = data["enqueue"] as? String
        if (enqueue == "add" || enqueue == "next") return listOf(SpotifyCall("POST", onDevice("/me/player/queue?uri=${enc(uri)}")))
        val single = uri.startsWith("spotify:track:") || uri.startsWith("spotify:episode:")
        val body =
            buildJsonObject {
                if (single) putJsonArray("uris") { add(JsonPrimitive(uri)) } else put("context_uri", uri)
            }
        return listOf(SpotifyCall("PUT", onDevice("/me/player/play"), body))
    }

    /** `spotify:album:…`, or `https://open.spotify.com/album/…?si=…` turned into it. */
    fun uriOf(text: String): String? {
        val t = text.trim()
        if (t.startsWith("spotify:")) return t
        val m = LINK.find(t) ?: return null
        return "spotify:${m.groupValues[1]}:${m.groupValues[2]}"
    }

    // ---- search and browse ----------------------------------------------------

    /** The search's call: [filter] one kind ("artist", "album", "track", "playlist"), or all four. */
    fun searchCall(query: String, filter: String?): SpotifyCall {
        val types = filter?.takeIf { it in KINDS } ?: KINDS.joinToString(",")
        return SpotifyCall("GET", "/search?q=${enc(query)}&type=$types&limit=10")
    }

    /** `GET /search`'s answer as Home Assistant's `{"result": [items]}`. */
    fun searchResult(body: JsonObject?): JsonObject {
        val items =
            KINDS.flatMap { kind ->
                body?.obj(kind + "s")?.arr("items")?.mapNotNull { (it as? JsonObject)?.let { o -> item(o, kind) } }.orEmpty()
            }
        return JsonObject(mapOf("result" to JsonArray(items)))
    }

    /** One Spotify object (a track, album, artist or playlist) as a media browser item, played by its URI. */
    fun item(o: JsonObject, kind: String): JsonObject? {
        val uri = o.str("uri") ?: return null
        val subtitle =
            when (kind) {
                "track", "album" -> o.arr("artists")?.mapNotNull { (it as? JsonObject)?.str("name") }?.joinToString(", ")
                "playlist" -> o.obj("owner")?.str("display_name")
                else -> null
            }?.takeIf { it.isNotBlank() }
        return buildJsonObject {
            put("title", listOfNotNull(o.str("name") ?: uri, subtitle).joinToString(" · "))
            put("media_class", kind)
            put("media_content_id", uri)
            put("media_content_type", kind)
            imageOf(o)?.let { put("thumbnail", it) } ?: o.obj("album")?.let { album -> imageOf(album)?.let { put("thumbnail", it) } }
            put("can_play", true)
            put("can_expand", false)
        }
    }

    /** The browse root: the user's playlists and what played lately. */
    fun root(playlistsTitle: String = "Playlists", recentTitle: String = "Recently played"): JsonObject = folder(
        "Spotify",
        ROOT,
        listOf(folder(playlistsTitle, PLAYLISTS, emptyList()), folder(recentTitle, RECENT, emptyList()))
    )

    /** The call that fills folder [contentId], or null for the root (built here). */
    fun browseCall(contentId: String?): SpotifyCall? = when (contentId) {
        PLAYLISTS -> SpotifyCall("GET", "/me/playlists?limit=50")
        RECENT -> SpotifyCall("GET", "/me/player/recently-played?limit=30")
        else -> null
    }

    /** A folder's answer as Home Assistant's browse result. */
    fun browseResult(contentId: String, title: String, body: JsonObject?): JsonObject {
        val children =
            when (contentId) {
                PLAYLISTS -> body?.arr("items")?.mapNotNull { (it as? JsonObject)?.let { o -> item(o, "playlist") } }
                RECENT ->
                    body?.arr("items")?.mapNotNull { (it as? JsonObject)?.obj("track")?.let { t -> item(t, "track") } }
                        ?.distinctBy { (it["media_content_id"] as? JsonPrimitive)?.content }
                else -> null
            }.orEmpty()
        return folder(title, contentId, children)
    }

    private fun folder(title: String, id: String, children: List<JsonObject>) = buildJsonObject {
        put("title", title)
        put("media_class", "directory")
        put("media_content_id", id)
        put("media_content_type", "spotify")
        put("can_play", false)
        put("can_expand", true)
        put("children", JsonArray(children))
    }

    // ---- helpers ---------------------------------------------------------------

    private val KINDS = listOf("artist", "album", "track", "playlist")
    private val LINK = Regex("open\\.spotify\\.com/(?:intl-[a-z]+/)?(track|album|playlist|artist|episode|show)/([A-Za-z0-9]+)")

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private fun num(v: Any?): Double? = when (v) {
        is Number -> v.toDouble()
        is String -> v.trim().toDoubleOrNull()
        else -> null
    }

    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject

    private fun JsonObject.arr(k: String): JsonArray? = this[k] as? JsonArray

    private fun JsonObject.bool(k: String): Boolean? = (this[k] as? JsonPrimitive)?.booleanOrNull

    private fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull

    private fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
}
