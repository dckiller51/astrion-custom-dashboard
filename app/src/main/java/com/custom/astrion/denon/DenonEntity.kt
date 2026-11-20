package com.custom.astrion.denon

/** One Denon / Marantz receiver added on the web page (1.5.4). */
data class DenonSpec(
    val localId: String,
    /** `media_player.denon_…`; the remote entity is the same object id under `remote.`. */
    val entityId: String,
    val name: String,
    /** The inputs' names given in Astrion, by code; see [DenonProtocol.customInputs]. */
    val inputNames: Map<String, String> = emptyMap(),
    val hiddenInputs: Set<String> = emptySet(),
    /** 1.5.7: 1 the main zone only, 2 with zone 2, 3 with zones 2 and 3. */
    val zones: Int = 1
) {
    val remoteEntityId: String get() = "remote." + entityId.substringAfter('.')

    /** Zone 2 or 3's `media_player` (1.5.7): the receiver's id + `_zone2`. */
    fun zoneEntityId(zone: Int) = entityId + "_zone$zone"
}

/** What the receiver last said. [reachable] false: it doesn't answer (off the network). */
data class DenonState(
    val reachable: Boolean = false,
    val on: Boolean = false,
    val model: String? = null,
    val input: String? = null,
    val volumeDb: Double? = null,
    val muted: Boolean = false,
    val soundMode: String? = null,
    /** The inputs shown: [receiverInputs] as renamed / hidden in Astrion, see [DenonProtocol.customInputs]. */
    val inputs: List<DenonProtocol.Input> = emptyList(),
    /** The inputs as the receiver names them, for the web page's input editor. */
    val receiverInputs: List<DenonProtocol.Input> = emptyList(),
    /** Its HEOS streaming (recent models), when the CLI answers. */
    val heos: HeosState = HeosState(),
    /** Zones 2 and 3 as they last answered (1.5.7), when published. */
    val zones: Map<Int, DenonProtocol.ZoneStatus> = emptyMap()
) {
    val isOn: Boolean get() = reachable && on

    /** What HEOS plays shows (and its keys go to HEOS) while the receiver is on a HEOS input. */
    val heosShown: Boolean get() = isOn && heos.active && (input == null || input.uppercase() in HEOS_INPUTS)

    /** The HEOS favorites the input list doesn't already have. */
    val heosFavorites: List<HeosProtocol.Favorite>
        get() = heos.favorites.filter { f -> inputs.none { it.name.equals(f.name, true) } }

    companion object {
        /** The receiver's inputs HEOS plays on. */
        val HEOS_INPUTS =
            setOf(
                "NET", "SERVER", "IRP", "IRADIO", "FAVORITES", "SPOTIFY", "PANDORA", "SIRIUSXM", "LASTFM", "FLICKR", "USB/IPOD", "USB", "BT"
            )
    }

    val inputName: String?
        get() = input?.let { code -> (inputs + receiverInputs).firstOrNull { it.code.equals(code, true) }?.name ?: code }
}

/** One entity as published to the dashboard: id, state and attributes. */
data class DenonEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * A receiver as a `media_player` (power, volume, mute, input, sound mode) and a `remote` entity
 * shaped like Home Assistant's Denon AVR integration, so the media player, source select and TV
 * remote cards, buttons, hotkeys and Activities drive it with or without Home Assistant.
 */
object DenonEntityMapper {
    private const val VOLUME_SET = 4
    private const val VOLUME_MUTE = 8
    private const val TURN_ON = 128
    private const val TURN_OFF = 256
    private const val VOLUME_STEP = 1024
    private const val SELECT_SOURCE = 2048
    private const val SELECT_SOUND_MODE = 65536
    private const val PAUSE = 1
    private const val PREVIOUS_TRACK = 16
    private const val NEXT_TRACK = 32
    private const val STOP = 4096
    private const val PLAY = 16384
    private const val SHUFFLE_SET = 32768
    private const val REPEAT_SET = 262144
    const val FEATURES = VOLUME_SET or VOLUME_MUTE or TURN_ON or TURN_OFF or VOLUME_STEP or SELECT_SOURCE or SELECT_SOUND_MODE

    /** With HEOS: its transport, shuffle and repeat too. */
    const val HEOS_FEATURES = FEATURES or PAUSE or PREVIOUS_TRACK or NEXT_TRACK or STOP or PLAY or SHUFFLE_SET or REPEAT_SET

    private val REPEATS = mapOf("off" to "off", "on_all" to "all", "on_one" to "one")

    fun media(spec: DenonSpec, s: DenonState): DenonEntityData {
        val features = if (s.heos.connected) HEOS_FEATURES else FEATURES
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "receiver", "supported_features" to features)
        a["manufacturer"] = "Denon / Marantz"
        s.model?.let { a["model"] = it }
        // The HEOS favorites after the inputs: a tap plays one.
        val sources = s.inputs.map { it.name } + s.heosFavorites.map { it.name }
        if (sources.isNotEmpty()) a["source_list"] = sources
        s.heosFavorites.filter {
            it.image != null
        }.associate { it.name to it.image }.takeIf { it.isNotEmpty() }?.let { a["astrion_source_icons"] = it }
        a["sound_mode_list"] = DenonProtocol.SOUND_MODES
        // Code and the receiver's own name of every input: the web page renames / hides them.
        if (s.receiverInputs.isNotEmpty()) a["astrion_inputs"] = s.receiverInputs.map { mapOf("code" to it.code, "name" to it.name) }
        if (s.isOn) {
            s.inputName?.let { a["source"] = it }
            s.volumeDb?.let { a["volume_level"] = DenonProtocol.level(it) }
            a["is_volume_muted"] = s.muted
            s.soundMode?.let { a["sound_mode"] = it }
            if (s.heosShown) addHeos(a, s.heos)
        }
        return DenonEntityData(spec.entityId, stateOf(s), a)
    }

    /** off (standby or not answering), playing / paused (HEOS), else on. */
    fun stateOf(s: DenonState): String = when {
        !s.isOn -> "off"
        s.heosShown && s.heos.playState == "play" -> "playing"
        s.heosShown && s.heos.playState == "pause" -> "paused"
        else -> "on"
    }

    /** What HEOS plays: title (or station), artist, album, cover, position, shuffle and repeat. */
    private fun addHeos(a: MutableMap<String, Any?>, h: HeosState) {
        val np = h.nowPlaying ?: return
        a["media_content_type"] = "music"
        a["media_title"] = np.title ?: np.station
        np.artist?.let { a["media_artist"] = it }
        np.album?.let { a["media_album_name"] = it }
        if (np.title != null) np.station?.let { a["media_channel"] = it }
        np.image?.let { a["entity_picture"] = it }
        h.positionS?.let { a["media_position"] = it }
        h.durationS?.let { a["media_duration"] = it }
        h.positionAt?.let { a["media_position_updated_at"] = com.custom.astrion.ha.HaTime.isoInstant(it) }
        h.shuffle?.let { a["shuffle"] = it }
        h.repeat?.let { r -> REPEATS[r]?.let { a["repeat"] = it } }
    }

    /** Zone 2 or 3 (1.5.7): power, volume, mute and input of its own, the receiver's inputs to pick from. */
    fun zone(spec: DenonSpec, s: DenonState, zone: Int): DenonEntityData {
        val z = s.zones[zone]
        val a =
            linkedMapOf<String, Any?>(
                "friendly_name" to "${spec.name} Zone $zone",
                "device_class" to "receiver",
                "supported_features" to ZONE_FEATURES
            )
        a["manufacturer"] = "Denon / Marantz"
        s.model?.let { a["model"] = it }
        if (s.inputs.isNotEmpty()) a["source_list"] = s.inputs.map { it.name }
        val on = s.reachable && z?.on == true
        if (on) {
            z.input?.let { code -> a["source"] = s.inputs.firstOrNull { it.code.equals(code, true) }?.name ?: code }
            z.volumeDb?.let { a["volume_level"] = DenonProtocol.level(it) }
            a["is_volume_muted"] = z.muted
        }
        return DenonEntityData(spec.zoneEntityId(zone), if (on) "on" else "off", a)
    }

    private const val ZONE_FEATURES = VOLUME_SET or VOLUME_MUTE or TURN_ON or TURN_OFF or VOLUME_STEP or SELECT_SOURCE

    fun remote(spec: DenonSpec, s: DenonState): DenonEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name)
        if (s.isOn) s.inputName?.let { a["current_activity"] = it }
        return DenonEntityData(spec.remoteEntityId, if (s.isOn) "on" else "off", a)
    }
}

/**
 * What a service call on a receiver's entities becomes: the receiver's own commands. Pure, so
 * tested. Power and mute have their own on / off commands, so "turn on" is never a toggle; power is
 * the main zone's (`ZMON` / `ZMOFF`), the other zones are left as they are.
 */
object DenonServices {
    private const val MAX_REPEATS = 20

    fun media(service: String, data: Map<String, Any?>, s: DenonState): List<String>? =
        heos(service, data, s) ?: SIMPLE[service]?.let { listOf(it) } ?: when (service) {
            "toggle" -> listOf(if (s.isOn) "ZMOFF" else "ZMON")
            "volume_set" -> (data["volume_level"] as? Number)?.let { listOf(DenonProtocol.volumeCommand(it.toDouble())) }
            "volume_mute" -> mute(data, s)
            "select_source", "play_media" -> inputCommand(data["source"] ?: data["media_content_id"], s)
            "select_sound_mode" -> soundModeCommand(data["sound_mode"])
            else -> null
        }

    /** Zone 2 or 3's `media_player` services (1.5.7): `Z2ON`, `Z2UP`, `Z2MUON`, `Z250`, `Z2SAT/CBL`… */
    fun zone(service: String, data: Map<String, Any?>, s: DenonState, zone: Int): List<String>? {
        val z = "Z$zone"
        val on = s.zones[zone]?.on == true
        return when (service) {
            "turn_on" -> listOf("${z}ON")
            "turn_off" -> listOf("${z}OFF")
            "toggle" -> listOf(if (on) "${z}OFF" else "${z}ON")
            "volume_up" -> listOf("${z}UP")
            "volume_down" -> listOf("${z}DOWN")
            "volume_mute" -> listOf(zoneMute(data, s, zone))
            "volume_set" -> (data["volume_level"] as? Number)?.let { listOf(DenonProtocol.zoneVolumeCommand(zone, it.toDouble())) }
            "select_source", "play_media" -> inputCommand(data["source"] ?: data["media_content_id"], s)?.map { z + it.removePrefix("SI") }
            else -> null
        }
    }

    /** `Z2MUON` / `Z2MUOFF`: as asked, else the other way from now. */
    private fun zoneMute(data: Map<String, Any?>, s: DenonState, zone: Int): String {
        val mute = data["is_volume_muted"] as? Boolean ?: (s.zones[zone]?.muted != true)
        return "Z$zone" + if (mute) "MUON" else "MUOFF"
    }

    private fun mute(data: Map<String, Any?>, s: DenonState): List<String> =
        listOf(if (data["is_volume_muted"] as? Boolean ?: !s.muted) "MUON" else "MUOFF")

    /**
     * With HEOS connected: play / pause / stop / next / previous, shuffle and repeat, and a HEOS
     * favorite as a source, go to HEOS (`heos:…`, see [DenonRegistry]); null for the rest.
     */
    private fun heos(service: String, data: Map<String, Any?>, s: DenonState): List<String>? {
        val h = s.heos
        if (!h.connected) return null
        HEOS_KEYS[service]?.let { return listOf(it) }
        return when (service) {
            "media_play_pause" -> listOf(if (h.playState == "play") "heos:pause" else "heos:play")
            "shuffle_set" -> (data["shuffle"] as? Boolean)?.let { listOf(playMode(h.repeat ?: "off", it)) }
            "repeat_set" -> REPEAT_MODES[data["repeat"] as? String]?.let { listOf(playMode(it, h.shuffle == true)) }
            "select_source", "play_media" -> favorite(data["source"] ?: data["media_content_id"], s)
            else -> null
        }
    }

    private fun playMode(repeat: String, shuffle: Boolean) = "heos:mode:$repeat:" + if (shuffle) "on" else "off"

    private val HEOS_KEYS =
        mapOf(
            "media_play" to "heos:play",
            "media_pause" to "heos:pause",
            "media_stop" to "heos:stop",
            "media_next_track" to "heos:next",
            "media_previous_track" to "heos:previous"
        )

    private val REPEAT_MODES = mapOf("off" to "off", "all" to "on_all", "one" to "on_one")

    /** A HEOS favorite by name (an input of that name wins): `heos:preset:<its place, from 1>`. */
    private fun favorite(value: Any?, s: DenonState): List<String>? {
        val v = (value as? String)?.trim() ?: return null
        if (s.inputs.any { it.name.equals(v, true) || it.code.equals(v, true) }) return null
        val i = s.heos.favorites.indexOfFirst { it.name.equals(v, true) }
        return if (i < 0) null else listOf("heos:preset:${i + 1}")
    }

    /** `MS` + the mode, as the receiver takes it ("Pure Direct" → `MSPURE DIRECT`). */
    private fun soundModeCommand(value: Any?): List<String>? {
        val mode = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return listOf("MS" + mode.uppercase())
    }

    private val MEDIA_KEYS =
        mapOf(
            "MEDIA_PLAY" to "media_play",
            "MEDIA_PAUSE" to "media_pause",
            "MEDIA_STOP" to "media_stop",
            "MEDIA_NEXT" to "media_next_track",
            "MEDIA_PREVIOUS" to "media_previous_track"
        )

    /** Services that are always the same command. */
    private val SIMPLE =
        mapOf(
            // The main zone only (ZM): PWON / PWSTANDBY would switch every zone of the receiver.
            "turn_on" to "ZMON",
            "turn_off" to "ZMOFF",
            "volume_up" to "MVUP",
            "volume_down" to "MVDOWN",
            "media_play" to "NS9A",
            "media_pause" to "NS9B",
            "media_stop" to "NS9C",
            "media_next_track" to "NS9D",
            "media_previous_track" to "NS9E"
        )

    /** An input by the name the receiver shows ("Freebox") or its code ("SAT/CBL"): `SI` + its code. */
    fun inputCommand(value: Any?, s: DenonState): List<String>? {
        val v = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val code = s.inputs.firstOrNull { it.name.equals(v, true) || it.code.equals(v, true) }?.code ?: v.uppercase()
        return listOf("SI$code")
    }

    /**
     * `remote.send_command`: the receiver's own commands (`MSMOVIE`, `PSDYNEQ ON`…) or Android
     * names, `num_repeats`. POWER and MUTE toggle; unknown names are skipped.
     */
    fun remote(service: String, data: Map<String, Any?>, s: DenonState): List<String>? = when (service) {
        "send_command" -> {
            val names =
                when (val c = data["command"]) {
                    is String -> listOf(c)
                    is List<*> -> c.filterIsInstance<String>()
                    else -> emptyList()
                }
            val repeats = ((data["num_repeats"] as? Number)?.toInt() ?: 1).coerceIn(1, MAX_REPEATS)
            val once = names.flatMap { command(it, s) }
            List(repeats) { once }.flatten()
        }
        "turn_on", "turn_off", "toggle" -> media(service, data, s)
        else -> null
    }

    private fun command(name: String, s: DenonState): List<String> = when (name.trim().uppercase().removePrefix("KEYCODE_")) {
        "POWER" -> media("toggle", emptyMap(), s).orEmpty()
        "WAKEUP" -> listOf("ZMON")
        "SLEEP" -> listOf("ZMOFF")
        "MUTE", "VOLUME_MUTE" -> media("volume_mute", emptyMap(), s).orEmpty()
        "MEDIA_PLAY_PAUSE", "PLAY_PAUSE" -> media("media_play_pause", emptyMap(), s).orEmpty()
        "MEDIA_PLAY", "MEDIA_PAUSE", "MEDIA_STOP", "MEDIA_NEXT", "MEDIA_PREVIOUS" ->
            media(MEDIA_KEYS.getValue(name.trim().uppercase().removePrefix("KEYCODE_")), emptyMap(), s).orEmpty()
        else -> listOfNotNull(DenonProtocol.commandOf(name))
    }
}
