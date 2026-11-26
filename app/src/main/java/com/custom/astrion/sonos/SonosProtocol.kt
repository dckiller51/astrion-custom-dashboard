package com.custom.astrion.sonos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put

/** One of the household's speakers, as the zone group state names it. */
data class SonosSpeaker(val uuid: String, val name: String, val host: String, val coordinator: String)

/** What a speaker is, read once from its device description. */
data class SonosModel(val name: String, val model: String, val lineIn: Boolean, val tv: Boolean)

/** A track (or a radio's current song) from DIDL-Lite metadata. */
data class SonosTrack(
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val art: String? = null,
    /** A radio's "now playing" line, when it gives one. */
    val stream: String? = null
)

/** What a group's coordinator plays. */
data class SonosPlayback(
    val transport: String = "STOPPED",
    val playMode: String = "NORMAL",
    val uri: String? = null,
    /** The radio station or the favourite's name (GetMediaInfo). */
    val mediaTitle: String? = null,
    val track: SonosTrack? = null,
    val duration: Long? = null,
    val position: Long? = null
)

data class SonosFavorite(val id: String, val title: String, val uri: String, val meta: String, val art: String?)

/** One SOAP call, to the speaker [target] (its UUID). */
data class SonosCall(val target: String, val service: SonosService, val action: String, val args: List<Pair<String, String>> = emptyList())

enum class SonosService(val path: String, val urn: String) {
    AV_TRANSPORT("/MediaRenderer/AVTransport/Control", "urn:schemas-upnp-org:service:AVTransport:1"),
    RENDERING("/MediaRenderer/RenderingControl/Control", "urn:schemas-upnp-org:service:RenderingControl:1"),
    TOPOLOGY("/ZoneGroupTopology/Control", "urn:schemas-upnp-org:service:ZoneGroupTopology:1"),
    CONTENT("/MediaServer/ContentDirectory/Control", "urn:schemas-upnp-org:service:ContentDirectory:1")
}

/** A speaker as the remote publishes it. */
data class SonosSpec(val entityId: String, val name: String, val uuid: String, val host: String, val lineIn: Boolean, val tv: Boolean)

/** What a service call needs to know about the speaker and its group. */
data class SonosContext(
    val self: SonosSpec,
    /** The UUID of the speaker's group coordinator (itself when alone). */
    val coordinator: String,
    val playback: SonosPlayback?,
    val volume: Int?,
    val favorites: List<SonosFavorite>,
    /** Another speaker's UUID by its entity id (for join). */
    val uuidOf: (String) -> String?
)

/**
 * Sonos speakers over their local UPnP API (1.6.3, port 1400): the SOAP calls, their answers read,
 * a speaker as a `media_player` shaped like Home Assistant's (transport, volume, the group's
 * coordinator for what plays, favourites and inputs as sources, `group_members`), and the service
 * calls turned into SOAP calls. Pure, so it is unit-tested.
 */
object SonosProtocol {
    const val PORT = 1400
    const val TV = "TV"
    const val LINE_IN = "Line-in"
    const val FAVORITES_ID = "FV:2"
    const val VOLUME_STEP = 3

    private const val PAUSE = 1
    private const val SEEK = 2
    private const val VOLUME_SET = 4
    private const val VOLUME_MUTE = 8
    private const val PREVIOUS = 16
    private const val NEXT = 32
    private const val PLAY_MEDIA = 512
    private const val VOLUME_STEP_FEATURE = 1024
    private const val SELECT_SOURCE = 2048
    private const val STOP = 4096
    private const val PLAY = 16384
    private const val SHUFFLE_SET = 32768
    private const val BROWSE_MEDIA = 131072
    private const val REPEAT_SET = 262144
    private const val GROUPING = 524288
    const val FEATURES =
        PAUSE or SEEK or VOLUME_SET or VOLUME_MUTE or PREVIOUS or NEXT or PLAY_MEDIA or VOLUME_STEP_FEATURE or SELECT_SOURCE or
            STOP or PLAY or SHUFFLE_SET or BROWSE_MEDIA or REPEAT_SET or GROUPING

    private const val SECONDS_PER_MINUTE = 60
    private const val SECONDS_PER_HOUR = 3600
    private const val MAX_VOLUME = 100

    // ---- SOAP -------------------------------------------------------------------

    fun envelope(service: SonosService, action: String, args: List<Pair<String, String>>): String = buildString {
        append("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        append(
            "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\" " +
                "s:encodingStyle=\"http://schemas.xmlsoap.org/soap/encoding/\">"
        )
        append("<s:Body><u:").append(action).append(" xmlns:u=\"").append(service.urn).append("\">")
        args.forEach { (k, v) -> append('<').append(k).append('>').append(SonosXml.escape(v)).append("</").append(k).append('>') }
        append("</u:").append(action).append("></s:Body></s:Envelope>")
    }

    fun soapAction(service: SonosService, action: String) = "\"${service.urn}#$action\""

    private val INSTANCE = "InstanceID" to "0"
    private val MASTER = "Channel" to "Master"

    fun av(target: String, action: String, vararg args: Pair<String, String>) =
        SonosCall(target, SonosService.AV_TRANSPORT, action, listOf(INSTANCE) + args)

    fun play(target: String) = av(target, "Play", "Speed" to "1")

    fun setUri(target: String, uri: String, meta: String = "") = av(
        target,
        "SetAVTransportURI",
        "CurrentURI" to uri,
        "CurrentURIMetaData" to meta
    )

    fun getVolume(target: String) = SonosCall(target, SonosService.RENDERING, "GetVolume", listOf(INSTANCE, MASTER))

    fun getMute(target: String) = SonosCall(target, SonosService.RENDERING, "GetMute", listOf(INSTANCE, MASTER))

    fun setVolume(target: String, volume: Int) = SonosCall(
        target,
        SonosService.RENDERING,
        "SetVolume",
        listOf(INSTANCE, MASTER, "DesiredVolume" to volume.coerceIn(0, MAX_VOLUME).toString())
    )

    fun setMute(target: String, mute: Boolean) =
        SonosCall(target, SonosService.RENDERING, "SetMute", listOf(INSTANCE, MASTER, "DesiredMute" to if (mute) "1" else "0"))

    fun topology(target: String) = SonosCall(target, SonosService.TOPOLOGY, "GetZoneGroupState")

    fun favorites(target: String) = SonosCall(
        target,
        SonosService.CONTENT,
        "Browse",
        listOf(
            "ObjectID" to FAVORITES_ID,
            "BrowseFlag" to "BrowseDirectChildren",
            "Filter" to "*",
            "StartingIndex" to "0",
            "RequestedCount" to "100",
            "SortCriteria" to ""
        )
    )

    // ---- answers ----------------------------------------------------------------

    /** GetZoneGroupState: the visible speakers (not a stereo pair's second one, a Sub, surrounds), by group. */
    fun speakers(response: String): List<SonosSpeaker> {
        val state = SonosXml.text(response, "ZoneGroupState") ?: response
        return SonosXml.elements(state, "ZoneGroup").flatMap { group ->
            val coordinator = group.attrs["Coordinator"].orEmpty()
            SonosXml.elements(group.inner, "ZoneGroupMember").mapNotNull { m ->
                val a = m.attrs
                if (a["Invisible"] == "1") return@mapNotNull null
                val uuid = a["UUID"]?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val host = hostOf(a["Location"]) ?: return@mapNotNull null
                SonosSpeaker(uuid, a["ZoneName"].orEmpty().ifBlank { uuid }, host, coordinator.ifBlank { uuid })
            }
        }.distinctBy { it.uuid }
    }

    private val HOST = Regex("^https?://([^/:]+)")

    fun hostOf(location: String?): String? = location?.let { HOST.find(it)?.groupValues?.get(1) }

    /** device_description.xml: the room, the model, and whether it has a line-in or a TV input. */
    fun model(xml: String): SonosModel = SonosModel(
        name = SonosXml.text(xml, "roomName").orEmpty(),
        model = SonosXml.text(xml, "displayName") ?: SonosXml.text(xml, "modelName").orEmpty(),
        lineIn = xml.contains("urn:schemas-upnp-org:service:AudioIn:1"),
        tv = xml.contains("urn:schemas-upnp-org:service:HTControl:1")
    )

    /** DIDL-Lite of a track or a stream; [host] makes a relative album art address whole. */
    fun track(didl: String?, host: String): SonosTrack? {
        if (didl.isNullOrBlank() || !didl.contains("<")) return null
        return SonosTrack(
            title = SonosXml.text(didl, "title")?.takeIf { it.isNotBlank() },
            artist = SonosXml.text(didl, "creator")?.takeIf { it.isNotBlank() },
            album = SonosXml.text(didl, "album")?.takeIf { it.isNotBlank() },
            art = artUrl(SonosXml.text(didl, "albumArtURI"), host),
            stream = SonosXml.text(didl, "streamContent")?.takeIf { it.isNotBlank() && !it.startsWith("ZPSTR_") }
        )
    }

    fun artUrl(art: String?, host: String): String? = art?.trim()?.takeIf { it.isNotEmpty() }?.let {
        if (it.startsWith("/")) "http://$host:$PORT$it" else it
    }

    /** "0:03:45" in seconds; null for a stream ("NOT_IMPLEMENTED", ""). */
    fun seconds(hms: String?): Long? {
        val parts = hms?.trim()?.split(':') ?: return null
        if (parts.size != 3) return null
        val (h, m, s) = parts.map { it.substringBefore('.').toLongOrNull() ?: return null }
        return h * SECONDS_PER_HOUR + m * SECONDS_PER_MINUTE + s
    }

    fun hms(seconds: Long): String {
        val s = seconds.coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / SECONDS_PER_HOUR, (s % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE, s % SECONDS_PER_MINUTE)
    }

    /** The four AVTransport answers that make what a coordinator plays. */
    fun playback(transportInfo: String, positionInfo: String, mediaInfo: String, settings: String, host: String): SonosPlayback {
        val uri = SonosXml.text(mediaInfo, "CurrentURI")?.takeIf { it.isNotBlank() }
        return SonosPlayback(
            transport = SonosXml.text(transportInfo, "CurrentTransportState") ?: "STOPPED",
            playMode = SonosXml.text(settings, "PlayMode") ?: "NORMAL",
            uri = uri,
            mediaTitle = SonosXml.text(mediaInfo, "CurrentURIMetaData")?.let { SonosXml.text(it, "title") }?.takeIf { it.isNotBlank() },
            track = track(SonosXml.text(positionInfo, "TrackMetaData"), host),
            duration = seconds(SonosXml.text(positionInfo, "TrackDuration")),
            position = seconds(SonosXml.text(positionInfo, "RelTime"))
        )
    }

    fun volumeOf(response: String): Int? = SonosXml.text(response, "CurrentVolume")?.trim()?.toIntOrNull()

    fun muteOf(response: String): Boolean? = SonosXml.text(response, "CurrentMute")?.trim()?.let { it == "1" }

    /** Browse FV:2: the favourites (a radio, a playlist, an album…), with what plays them. */
    fun favoritesOf(response: String, host: String): List<SonosFavorite> {
        val didl = SonosXml.text(response, "Result") ?: return emptyList()
        return SonosXml.elements(didl, "item").mapNotNull { item ->
            val id = item.attrs["id"] ?: return@mapNotNull null
            val title = SonosXml.text(item.inner, "title") ?: return@mapNotNull null
            val uri = SonosXml.text(item.inner, "res") ?: return@mapNotNull null
            SonosFavorite(
                id,
                title,
                uri,
                SonosXml.text(item.inner, "resMD").orEmpty(),
                artUrl(SonosXml.text(item.inner, "albumArtURI"), host)
            )
        }
    }

    // ---- the entity -------------------------------------------------------------

    fun stateOf(reachable: Boolean, playback: SonosPlayback?): String = when {
        !reachable -> "unavailable"
        playback == null -> "idle"
        playback.transport == "PLAYING" || playback.transport == "TRANSITIONING" -> "playing"
        playback.transport == "PAUSED_PLAYBACK" -> "paused"
        else -> "idle"
    }

    /** NORMAL, REPEAT_ALL, REPEAT_ONE, SHUFFLE_NOREPEAT, SHUFFLE (and repeat all), SHUFFLE_REPEAT_ONE. */
    fun shuffleOf(playMode: String) = playMode.startsWith("SHUFFLE")

    fun repeatOf(playMode: String) = when (playMode) {
        "REPEAT_ALL", "SHUFFLE" -> "all"
        "REPEAT_ONE", "SHUFFLE_REPEAT_ONE" -> "one"
        else -> "off"
    }

    fun playModeOf(shuffle: Boolean, repeat: String) = when {
        shuffle && repeat == "all" -> "SHUFFLE"
        shuffle && repeat == "one" -> "SHUFFLE_REPEAT_ONE"
        shuffle -> "SHUFFLE_NOREPEAT"
        repeat == "all" -> "REPEAT_ALL"
        repeat == "one" -> "REPEAT_ONE"
        else -> "NORMAL"
    }

    /** The source a URI is: the TV, the line-in, or a favourite by its name. */
    fun sourceOf(uri: String?, favorites: List<SonosFavorite>): String? = when {
        uri == null -> null
        uri.startsWith("x-sonos-htastream:") -> TV
        uri.startsWith("x-rincon-stream:") -> LINE_IN
        else -> favorites.firstOrNull { it.uri == uri }?.title
    }

    fun sources(spec: SonosSpec, favorites: List<SonosFavorite>): List<String> =
        listOfNotNull(TV.takeIf { spec.tv }, LINE_IN.takeIf { spec.lineIn }) + favorites.map { it.title }

    /** The speaker's `media_player` attributes: its own volume, and what its group plays. */
    fun media(
        spec: SonosSpec,
        volume: Int?,
        muted: Boolean?,
        playback: SonosPlayback?,
        groupMembers: List<String>,
        favorites: List<SonosFavorite>,
        nowIso: String
    ): Map<String, Any?> {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "supported_features" to FEATURES, "device_class" to "speaker")
        volume?.let { a["volume_level"] = it / MAX_VOLUME.toDouble() }
        muted?.let { a["is_volume_muted"] = it }
        a["source_list"] = sources(spec, favorites)
        a["group_members"] = groupMembers
        val p = playback ?: return a
        a["shuffle"] = shuffleOf(p.playMode)
        a["repeat"] = repeatOf(p.playMode)
        sourceOf(p.uri, favorites)?.let { a["source"] = it }
        nowPlaying(p, a)
        p.duration?.takeIf { it > 0 }?.let { a["media_duration"] = it }
        p.position?.let {
            a["media_position"] = it
            a["media_position_updated_at"] = nowIso
        }
        a["app_name"] = "Sonos"
        return a
    }

    /** The title, artist, album and cover; none for the TV (it says nothing of what it plays). */
    private fun nowPlaying(p: SonosPlayback, a: MutableMap<String, Any?>) {
        val t = p.track
        val title = t?.stream ?: t?.title ?: p.mediaTitle ?: return
        if (p.uri?.startsWith("x-sonos-htastream:") == true) return
        a["media_title"] = title
        a["media_content_type"] = "music"
        // A radio: its station as the artist; a track: its artist.
        val artist = if (t?.stream != null) p.mediaTitle?.takeIf { it != title } else t?.artist ?: p.mediaTitle?.takeIf { it != title }
        artist?.let { a["media_artist"] = it }
        t?.album?.let { a["media_album_name"] = it }
        t?.art?.let { a["entity_picture"] = it }
    }

    // ---- services ---------------------------------------------------------------

    private fun JsonElement?.str() = (this as? JsonPrimitive)?.contentOrNull

    private fun JsonElement?.num() = (this as? JsonPrimitive)?.doubleOrNull

    private fun JsonElement?.bool() = (this as? JsonPrimitive)?.let { it.booleanOrNull ?: it.contentOrNull?.toBooleanStrictOrNull() }

    /** A `media_player` service as the SOAP calls that do it, in order; null when not supported. */
    fun service(service: String, data: Map<String, JsonElement>, c: SonosContext): List<SonosCall>? {
        val me = c.self.uuid
        val co = c.coordinator
        transport(service, c)?.let { return listOf(it) }
        return when (service) {
            "media_seek" -> data["seek_position"].num()?.let { listOf(av(co, "Seek", "Unit" to "REL_TIME", "Target" to hms(it.toLong()))) }
            "volume_set", "volume_up", "volume_down", "volume_mute" -> volume(service, data, c)?.let { listOf(it) }
            "shuffle_set", "repeat_set" -> playMode(service, data, c)
            "select_source" -> data["source"].str()?.let { selectSource(it, c) }
            "play_media" -> data["media_content_id"].str()?.let { playMedia(it, c) }
            "join" -> join(data["group_members"], c)
            "unjoin" -> listOf(av(me, "BecomeCoordinatorOfStandaloneGroup"))
            else -> null
        }
    }

    /** Play, pause, stop, next, previous: one call to the group's coordinator. */
    private fun transport(service: String, c: SonosContext): SonosCall? {
        val co = c.coordinator
        return when (service) {
            "media_play" -> play(co)
            "media_pause" -> av(co, "Pause")
            "media_play_pause" -> if (stateOf(true, c.playback) == "playing") av(co, "Pause") else play(co)
            "media_stop" -> av(co, "Stop")
            "media_next_track" -> av(co, "Next")
            "media_previous_track" -> av(co, "Previous")
            else -> null
        }
    }

    /** The volume and mute: this speaker's own, as the Sonos app's per-room slider. */
    private fun volume(service: String, data: Map<String, JsonElement>, c: SonosContext): SonosCall? {
        val me = c.self.uuid
        return when (service) {
            "volume_set" -> data["volume_level"].num()?.let { setVolume(me, Math.round(it * MAX_VOLUME).toInt()) }
            "volume_up" -> setVolume(me, (c.volume ?: 0) + VOLUME_STEP)
            "volume_down" -> setVolume(me, (c.volume ?: 0) - VOLUME_STEP)
            "volume_mute" -> data["is_volume_muted"].bool()?.let { setMute(me, it) }
            else -> null
        }
    }

    private fun playMode(service: String, data: Map<String, JsonElement>, c: SonosContext): List<SonosCall>? {
        val mode = c.playback?.playMode ?: "NORMAL"
        val shuffle = if (service == "shuffle_set") data["shuffle"].bool() ?: return null else shuffleOf(mode)
        val repeat = if (service == "repeat_set") data["repeat"].str() ?: return null else repeatOf(mode)
        return listOf(av(c.coordinator, "SetPlayMode", "NewPlayMode" to playModeOf(shuffle, repeat)))
    }

    /** The TV and the line-in play on this speaker alone; a favourite plays on its group. */
    private fun selectSource(source: String, c: SonosContext): List<SonosCall>? {
        val me = c.self.uuid
        return when {
            source == TV && c.self.tv -> listOf(setUri(me, "x-sonos-htastream:$me:spdif"), play(me))
            source == LINE_IN && c.self.lineIn -> listOf(setUri(me, "x-rincon-stream:$me"), play(me))
            else -> c.favorites.firstOrNull { it.title == source }?.let { playFavorite(it, c.coordinator) }
        }
    }

    /** A favourite's id (FV:2/…), or any address Sonos plays. */
    private fun playMedia(id: String, c: SonosContext): List<SonosCall> =
        c.favorites.firstOrNull { it.id == id }?.let { playFavorite(it, c.coordinator) }
            ?: listOf(setUri(c.coordinator, id), play(c.coordinator))

    /** A playlist or an album goes through the queue; a radio or a track is played as is. */
    fun playFavorite(f: SonosFavorite, target: String): List<SonosCall> {
        val container = f.uri.startsWith("x-rincon-cpcontainer:") || SonosXml.text(f.meta, "class")?.contains("container") == true
        if (!container) return listOf(setUri(target, f.uri, f.meta), play(target))
        return listOf(
            av(target, "RemoveAllTracksFromQueue"),
            av(
                target,
                "AddURIToQueue",
                "EnqueuedURI" to f.uri,
                "EnqueuedURIMetaData" to f.meta,
                "DesiredFirstTrackNumberEnqueued" to "0",
                "EnqueueAsNext" to "0"
            ),
            setUri(target, "x-rincon-queue:$target#0"),
            play(target)
        )
    }

    /** join: each of `group_members` follows this speaker's group coordinator. */
    private fun join(members: JsonElement?, c: SonosContext): List<SonosCall>? {
        val ids =
            when (members) {
                is JsonArray -> members.mapNotNull { it.str() }
                is JsonPrimitive -> listOfNotNull(members.contentOrNull)
                else -> null
            } ?: return null
        return ids.mapNotNull { c.uuidOf(it) }.filter { it != c.coordinator }.map { setUri(it, "x-rincon:${c.coordinator}") }
    }

    // ---- browse -------------------------------------------------------------------

    /** The browse root: the household's Sonos favourites, each played on the speaker's group. */
    fun browseRoot(favorites: List<SonosFavorite>, title: String = "Sonos favorites"): JsonObject = buildJsonObject {
        put("title", title)
        put("media_class", "directory")
        put("media_content_id", FAVORITES_ID)
        put("media_content_type", "favorites_folder")
        put("can_play", false)
        put("can_expand", true)
        put(
            "children",
            JsonArray(
                favorites.map { f ->
                    buildJsonObject {
                        put("title", f.title)
                        put("media_class", "music")
                        put("media_content_id", f.id)
                        put("media_content_type", "favorite_item_id")
                        f.art?.let { put("thumbnail", it) }
                        put("can_play", true)
                        put("can_expand", false)
                    }
                }
            )
        )
    }
}
