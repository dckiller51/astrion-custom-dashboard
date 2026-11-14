package com.custom.astrion.samsung

/** One Samsung TV added on the web page (1.5.3), as the registry drives it. */
data class SamsungSpec(
    val localId: String,
    /** `media_player.samsung_…`; the remote entity is the same object id under `remote.`. */
    val entityId: String,
    val name: String,
    /** The TV's MAC address, for Wake-on-LAN. Blank when unknown. */
    val mac: String = ""
) {
    val remoteEntityId: String get() = "remote." + entityId.substringAfter('.')
}

/** What the TV last said. [reachable] false: it doesn't answer (off, or away from the network). */
data class SamsungState(
    val reachable: Boolean = false,
    /** null: a TV too old to say (2016–2017): on when it answers. false: standby, network still on. */
    val powerOn: Boolean? = null,
    val connected: Boolean = false,
    val model: String? = null,
    val frame: Boolean = false,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val apps: List<SamsungProtocol.App> = emptyList(),
    /** The app last opened from Astrion, and whether it's still the one on screen (the TV says only that). */
    val lastApp: String? = null,
    val appVisible: Boolean = false,
    /** Last play / pause sent: the TV doesn't say, so play-pause alternates. */
    val paused: Boolean = false
) {
    val on: Boolean get() = reachable && powerOn != false

    /** The app on screen, when it's the one last opened from here. */
    val app: SamsungProtocol.App? get() = lastApp?.takeIf { appVisible }?.let { id -> apps.firstOrNull { it.id == id } }

    /** Off: what it has (model, apps) stays known. */
    fun off(): SamsungState = SamsungState(model = model, frame = frame, apps = apps)
}

/** One entity as published to the dashboard: id, state and attributes. */
data class SamsungEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * Maps what a Samsung TV says to a `media_player` and a `remote` entity shaped like Home
 * Assistant's (its Samsung TV integration publishes a media player too), so the media player,
 * source and TV remote cards, buttons, hotkeys and Activities drive it with or without Home
 * Assistant.
 */
object SamsungEntityMapper {
    // Home Assistant MediaPlayerEntityFeature bits.
    private const val PAUSE = 1
    private const val VOLUME_SET = 4
    private const val VOLUME_MUTE = 8
    private const val PREVIOUS_TRACK = 16
    private const val NEXT_TRACK = 32
    private const val TURN_ON = 128
    private const val TURN_OFF = 256
    private const val PLAY_MEDIA = 512
    private const val VOLUME_STEP = 1024
    private const val SELECT_SOURCE = 2048
    private const val STOP = 4096
    private const val PLAY = 16384
    const val FEATURES =
        PAUSE or VOLUME_SET or VOLUME_MUTE or PREVIOUS_TRACK or NEXT_TRACK or TURN_ON or TURN_OFF or
            PLAY_MEDIA or VOLUME_STEP or SELECT_SOURCE or STOP or PLAY

    private const val PERCENT = 100.0

    /** The TV's own sources, as Home Assistant's integration has them: live TV, and the HDMI key (next input). */
    val SOURCES = linkedMapOf("TV" to "KEY_TV", "HDMI" to "KEY_HDMI")

    /** An app's icon as the remote serves it: fetched from the TV once, kept in memory (see SamsungRegistry.iconFor). */
    fun iconUrl(spec: SamsungSpec, id: String): String =
        "http://127.0.0.1:8080/samsung-icon/${spec.localId}?id=" + java.net.URLEncoder.encode(id, "UTF-8")

    fun stateOf(s: SamsungState): String = if (s.on) "on" else "off"

    fun media(spec: SamsungSpec, s: SamsungState): SamsungEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "tv", "supported_features" to FEATURES)
        a["manufacturer"] = "Samsung"
        s.model?.let { a["model"] = it }
        val apps = s.apps.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        a["source_list"] = SOURCES.keys.toList() + apps.map { it.name }
        if (apps.isNotEmpty()) {
            a["astrion_apps"] =
                apps.map { app -> mapOf("name" to app.name, "app" to app.id, "icon" to app.iconPath?.let { iconUrl(spec, app.id) }) }
            apps.filter {
                it.iconPath != null
            }.associate { it.name to iconUrl(spec, it.id) }.takeIf { it.isNotEmpty() }?.let { a["astrion_source_icons"] = it }
        }
        if (s.on) {
            s.app?.let { app ->
                a["source"] = app.name
                a["app_id"] = app.id
                a["app_name"] = app.name
                if (app.iconPath != null) a["entity_picture"] = iconUrl(spec, app.id)
            }
            s.volume?.let { a["volume_level"] = it / PERCENT }
            s.muted?.let { a["is_volume_muted"] = it }
        }
        return SamsungEntityData(spec.entityId, stateOf(s), a)
    }

    fun remote(spec: SamsungSpec, s: SamsungState): SamsungEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name)
        if (s.on) s.app?.let { a["current_activity"] = it.name }
        return SamsungEntityData(spec.remoteEntityId, stateOf(s), a)
    }
}

/** One thing to do on the TV, for a service call. */
sealed class SamsungStep {
    /** A key, clicked. */
    data class Key(val code: String) : SamsungStep()

    /** A key held [ms] (a Frame TV's power key: off, not Art Mode). */
    data class Hold(val code: String, val ms: Long) : SamsungStep()

    data class Launch(val app: SamsungProtocol.App) : SamsungStep()

    /** The volume, 0–100, over UPnP. */
    data class Volume(val level: Int) : SamsungStep()

    data class Mute(val muted: Boolean) : SamsungStep()

    /** A Wake-on-LAN packet to the TV's MAC address. */
    object Wake : SamsungStep()
}

/**
 * What a Home Assistant service call on a Samsung TV's entities turns into. Pure, so the mapping is
 * unit-tested; the registry runs the steps.
 */
object SamsungServices {
    private const val MAX_REPEATS = 20
    private const val PERCENT = 100.0
    const val FRAME_OFF_MS = 3_000L

    private fun key(code: String) = listOf(SamsungStep.Key(code))

    /** Home Assistant `media_player.*`; null when the service isn't supported. */
    fun media(service: String, data: Map<String, Any?>, state: SamsungState): List<SamsungStep>? {
        power(service, state)?.let { return it }
        volume(service, data, state)?.let { return it }
        transport(service, state)?.let { return it }
        return when (service) {
            "select_source", "play_media" -> sourceOf(data["source"] ?: data["media_content_id"], state)
            else -> null
        }
    }

    private fun transport(service: String, state: SamsungState): List<SamsungStep>? = when (service) {
        "media_play" -> key("KEY_PLAY")
        "media_pause" -> key("KEY_PAUSE")
        "media_play_pause" -> key(if (state.paused) "KEY_PLAY" else "KEY_PAUSE")
        "media_stop" -> key("KEY_STOP")
        // An app on screen: fast-forward / rewind; else the channels.
        "media_next_track" -> key(if (state.app != null) "KEY_FF" else "KEY_CHUP")
        "media_previous_track" -> key(if (state.app != null) "KEY_REWIND" else "KEY_CHDOWN")
        else -> null
    }

    private fun power(service: String, state: SamsungState): List<SamsungStep>? = when (service) {
        "turn_on" -> turnOn(state)
        "turn_off" -> turnOff(state)
        "toggle" -> if (state.on) turnOff(state) else turnOn(state)
        else -> null
    }

    /** Standby with its network on: its power key; off the network: Wake-on-LAN. */
    private fun turnOn(state: SamsungState): List<SamsungStep> = when {
        state.on -> emptyList()
        state.reachable -> key("KEY_POWER")
        else -> listOf(SamsungStep.Wake)
    }

    /** The power key; held on a Frame TV, whose short press only switches to Art Mode. */
    private fun turnOff(state: SamsungState): List<SamsungStep> = when {
        !state.on -> emptyList()
        state.frame -> listOf(SamsungStep.Hold("KEY_POWER", FRAME_OFF_MS))
        else -> key("KEY_POWER")
    }

    private fun volume(service: String, data: Map<String, Any?>, state: SamsungState): List<SamsungStep>? = when (service) {
        "volume_up" -> key("KEY_VOLUP")
        "volume_down" -> key("KEY_VOLDOWN")
        "volume_set" -> (data["volume_level"] as? Number)?.let { listOf(SamsungStep.Volume(Math.round(it.toDouble() * PERCENT).toInt())) }
        "volume_mute" -> {
            val want = data["is_volume_muted"] as? Boolean ?: (state.muted != true)
            // Over UPnP when the TV answers it (it says the mute), else the mute key, a toggle.
            // Unknown mute (no UPnP): the key, a toggle.
            if (state.muted != null) listOf(SamsungStep.Mute(want)) else key("KEY_MUTE")
        }
        else -> null
    }

    /**
     * "TV" or "HDMI", else an app by its name or id ("Netflix", "3201907018807"), else a key
     * ("HOME", "KEY_HOME": an Activity's input can bring the TV's home screen up).
     */
    fun sourceOf(value: Any?, state: SamsungState): List<SamsungStep>? {
        val v = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        SamsungEntityMapper.SOURCES.entries.firstOrNull { it.key.equals(v, true) }?.let { return key(it.value) }
        val app =
            state.apps.firstOrNull { it.name.equals(v, true) || it.id == v }
                ?: SamsungProtocol.KNOWN_APPS.firstOrNull { it.first.equals(v, true) }?.let { (name, ids) ->
                    SamsungProtocol.App(ids.first(), name, SamsungProtocol.REST_APP, null)
                }
                ?: SamsungKeys.of(v)?.let { return key(it) }
                ?: SamsungProtocol.App(v, v, SamsungProtocol.REST_APP, null)
        return listOf(SamsungStep.Launch(app))
    }

    /**
     * Home Assistant `remote.*`: `send_command` with `command` (one name or a list: Samsung's
     * `KEY_…` codes or Android names like "DPAD_UP"), optional `num_repeats`. POWER is a toggle,
     * MEDIA_PLAY_PAUSE alternates. Unknown names are skipped.
     */
    fun remote(service: String, data: Map<String, Any?>, state: SamsungState): List<SamsungStep>? = when (service) {
        "send_command" -> {
            val names =
                when (val c = data["command"]) {
                    is String -> listOf(c)
                    is List<*> -> c.filterIsInstance<String>()
                    else -> emptyList()
                }
            val repeats = ((data["num_repeats"] as? Number)?.toInt() ?: 1).coerceIn(1, MAX_REPEATS)
            val once = names.flatMap { command(it, state) }
            List(repeats) { once }.flatten()
        }
        "turn_on", "turn_off", "toggle" -> media(service, data, state)
        else -> null
    }

    private fun command(name: String, state: SamsungState): List<SamsungStep> = when (name.trim().uppercase().removePrefix("KEYCODE_")) {
        "POWER", "KEY_POWER" -> media("toggle", emptyMap(), state).orEmpty()
        "WAKEUP", "KEY_POWERON" -> turnOn(state)
        "SLEEP", "KEY_POWEROFF" -> turnOff(state)
        "MEDIA_PLAY_PAUSE", "PLAY_PAUSE" -> media("media_play_pause", emptyMap(), state).orEmpty()
        else -> listOfNotNull(SamsungKeys.of(name)?.let { SamsungStep.Key(it) })
    }
}
