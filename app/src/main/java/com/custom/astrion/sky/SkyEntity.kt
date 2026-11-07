package com.custom.astrion.sky

/** One Sky box added on the web page (1.5.3). [skyQ] false: a Sky+ HD, keys only. */
data class SkySpec(
    val localId: String,
    /** `media_player.sky_…`; the remote entity is the same object id under `remote.`. */
    val entityId: String,
    val name: String,
    val skyQ: Boolean = true
) {
    val remoteEntityId: String get() = "remote." + entityId.substringAfter('.')
}

/** What the box last said. [reachable] false: it doesn't answer (off, or away from the network). */
data class SkyState(
    val reachable: Boolean = false,
    val standby: Boolean = false,
    val model: String? = null,
    val country: String? = null,
    /** `PLAYING`, `PAUSED_PLAYBACK`, `STOPPED`… (Sky Q's UPnP). */
    val transport: String? = null,
    val media: SkyProtocol.Media = SkyProtocol.Media.None,
    val channels: List<SkyProtocol.Channel> = emptyList(),
    val favourites: List<String> = emptyList(),
    val programme: SkyProtocol.Programme? = null
) {
    val on: Boolean get() = reachable && !standby

    val channel: SkyProtocol.Channel?
        get() = (media as? SkyProtocol.Media.Live)?.let { live -> channels.firstOrNull { it.sid == live.sid } }

    /** Nothing playing (standby, or not answering). */
    fun idle(): SkyState = copy(transport = null, media = SkyProtocol.Media.None, programme = null)

    /** The channels the Sources pop-up lists: the favourites, else the first [MAX_SOURCES]. */
    val sourceChannels: List<SkyProtocol.Channel>
        get() {
            val bySid = channels.associateBy { it.sid }
            val favs = favourites.mapNotNull { bySid[it] }
            return favs.ifEmpty { channels.take(MAX_SOURCES) }
        }

    companion object {
        const val MAX_SOURCES = 60
    }
}

/** One entity as published to the dashboard: id, state and attributes. */
data class SkyEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * A Sky box as a `media_player` and a `remote` entity shaped like Home Assistant's (its Sky
 * integrations publish the same kinds), so the TV remote, media player and source cards,
 * hotkeys and Activities drive it with or without Home Assistant.
 */
object SkyEntityMapper {
    private const val PAUSE = 1
    private const val PREVIOUS_TRACK = 16
    private const val NEXT_TRACK = 32
    private const val TURN_ON = 128
    private const val TURN_OFF = 256
    private const val PLAY_MEDIA = 512
    private const val SELECT_SOURCE = 2048
    private const val STOP = 4096
    private const val PLAY = 16384
    const val FEATURES = PAUSE or PREVIOUS_TRACK or NEXT_TRACK or TURN_ON or TURN_OFF or PLAY_MEDIA or SELECT_SOURCE or STOP or PLAY

    /** off (not answering, or in standby), playing / paused, else on. A Sky+ HD says only on / off. */
    fun stateOf(spec: SkySpec, s: SkyState): String = when {
        !s.on -> "off"
        !spec.skyQ -> "on"
        s.transport == "PLAYING" -> "playing"
        s.transport == "PAUSED_PLAYBACK" -> "paused"
        else -> "on"
    }

    fun media(spec: SkySpec, s: SkyState): SkyEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "receiver", "supported_features" to FEATURES)
        a["manufacturer"] = "Sky"
        a["model"] = s.model ?: if (spec.skyQ) "Sky Q" else "Sky+ HD"
        if (!spec.skyQ) a["assumed_state"] = true
        s.sourceChannels.takeIf { it.isNotEmpty() }?.let { list ->
            a["source_list"] = list.map { it.label }
            a["astrion_source_icons"] = list.associate { it.label to SkyProtocol.channelLogoUrl(it.sid, it.name, s.country) }
        }
        if (s.on) addNowPlaying(a, s)
        return SkyEntityData(spec.entityId, stateOf(spec, s), a)
    }

    /** The channel and its programme (from Sky's guide), or a recording. */
    private fun addNowPlaying(a: MutableMap<String, Any?>, s: SkyState) {
        val channel = s.channel
        if (channel != null) {
            a["source"] = channel.label
            a["media_channel"] = channel.name
            a["media_content_type"] = "channel"
            a["media_content_id"] = channel.sid
            val p = s.programme
            a["media_title"] = p?.title ?: channel.name
            p?.let {
                a["media_series_title"] = channel.name
                it.season?.let { n -> a["media_season"] = n }
                it.episode?.let { n -> a["media_episode"] = n }
                a["media_position"] = (System.currentTimeMillis() / MS_PER_S - it.startS).coerceAtLeast(0)
                a["media_duration"] = it.durationS
            }
            a["entity_picture"] = p?.uuid?.let {
                SkyProtocol.programmeImageUrl(it, s.country)
            } ?: SkyProtocol.channelLogoUrl(channel.sid, channel.name, s.country)
        } else if (s.media is SkyProtocol.Media.Recording) {
            a["media_content_type"] = "tvshow"
            a["source"] = "Recordings"
        }
    }

    fun remote(spec: SkySpec, s: SkyState): SkyEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name)
        s.channel?.takeIf { s.on }?.let { a["current_activity"] = it.label }
        return SkyEntityData(spec.remoteEntityId, if (s.on) "on" else "off", a)
    }

    private const val MS_PER_S = 1000L
}

/**
 * What a service call on a Sky box's entities becomes: Sky keys to press. Pure, so tested.
 * Power is one key that toggles: it's only pressed when the box isn't already as asked (a Sky+ HD,
 * which doesn't say, gets it every time).
 */
object SkyServices {
    private const val MAX_REPEATS = 20

    fun media(spec: SkySpec, service: String, data: Map<String, Any?>, s: SkyState): List<String>? =
        power(spec, service, s) ?: when (service) {
            "media_play" -> listOf("play")
            "media_pause" -> listOf("pause")
            "media_play_pause" -> listOf(if (s.transport == "PAUSED_PLAYBACK") "play" else "pause")
            "media_stop" -> listOf("stop")
            "media_next_track" -> listOf("channelup")
            "media_previous_track" -> listOf("channeldown")
            "select_source", "play_media" -> channelKeys(data["source"] ?: data["media_content_id"], s)
            else -> null
        }

    private fun power(spec: SkySpec, service: String, s: SkyState): List<String>? = when (service) {
        "turn_on" -> if (spec.skyQ && s.on) emptyList() else listOf("power")
        "turn_off" -> if (spec.skyQ && !s.on) emptyList() else listOf("power")
        "toggle" -> listOf("power")
        else -> null
    }

    /** A channel by its label ("101 BBC One"), name or number: its number's digits. */
    fun channelKeys(value: Any?, s: SkyState): List<String>? {
        val v = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: (value as? Number)?.toString() ?: return null
        val number =
            s.channels.firstOrNull { it.label.equals(v, true) || it.name.equals(v, true) || it.sid == v }?.number
                ?: v.takeWhile { it.isDigit() }.takeIf { it.isNotEmpty() } ?: return null
        return number.map { it.toString() }
    }

    /** `remote.send_command`: Sky key names or Android ones, `num_repeats`; unknown names are skipped. */
    fun remote(spec: SkySpec, service: String, data: Map<String, Any?>, s: SkyState): List<String>? = when (service) {
        "send_command" -> {
            val names =
                when (val c = data["command"]) {
                    is String -> listOf(c)
                    is List<*> -> c.filterIsInstance<String>()
                    else -> emptyList()
                }
            val repeats = ((data["num_repeats"] as? Number)?.toInt() ?: 1).coerceIn(1, MAX_REPEATS)
            val once = names.flatMap { command(spec, it, s) }
            List(repeats) { once }.flatten()
        }
        "turn_on", "turn_off", "toggle" -> media(spec, service, data, s)
        else -> null
    }

    private fun command(spec: SkySpec, name: String, s: SkyState): List<String> = when (name.trim().lowercase().removePrefix("keycode_")) {
        "media_play_pause", "play_pause" -> media(spec, "media_play_pause", emptyMap(), s).orEmpty()
        "wakeup" -> media(spec, "turn_on", emptyMap(), s).orEmpty()
        "sleep" -> media(spec, "turn_off", emptyMap(), s).orEmpty()
        else -> listOfNotNull(SkyProtocol.keyOf(name))
    }
}
