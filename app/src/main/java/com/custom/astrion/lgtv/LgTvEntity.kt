package com.custom.astrion.lgtv

import kotlinx.serialization.json.JsonObject

/** One LG TV added on the web page (1.5.2), as the registry drives it. */
data class LgTvSpec(
    val localId: String,
    /** `media_player.lgtv_…`; the remote entity is the same object id under `remote.`. */
    val entityId: String,
    val name: String,
    /** The TV's MAC address (wired first), for Wake-on-LAN. Blank when unknown. */
    val mac: String = ""
) {
    val remoteEntityId: String get() = "remote." + entityId.substringAfter('.')
}

/** What the TV last said. [reachable] false: no connection (off, or away from the network). */
data class LgTvState(
    val reachable: Boolean = false,
    val on: Boolean = false,
    val screenOff: Boolean = false,
    val model: String? = null,
    /** The app in front; an input is an app too (`com.webos.app.hdmi1`). */
    val app: String? = null,
    val volume: Int? = null,
    val muted: Boolean? = null,
    val apps: List<LgTvProtocol.Item> = emptyList(),
    val inputs: List<LgTvProtocol.Input> = emptyList(),
    /** Last play / pause sent: webOS doesn't say, so play-pause alternates. */
    val paused: Boolean = false
) {
    /** The input in front, when the app in front is one. */
    val input: LgTvProtocol.Input? get() = app?.let { a -> inputs.firstOrNull { it.appId == a } }

    /** The icon the TV gives for an app or an input, by its id (an input's app id too). */
    fun iconOf(id: String): String? = apps.firstOrNull { it.id == id }?.icon ?: inputs.firstOrNull { it.id == id || it.appId == id }?.icon

    /** The name of what is in front: the input's ("HDMI 1") or the app's ("Netflix"). */
    val sourceLabel: String? get() = input?.label ?: app?.let { a -> apps.firstOrNull { it.id == a }?.label ?: a }

    val liveTv: Boolean get() = app == LIVE_TV

    companion object {
        const val LIVE_TV = "com.webos.app.livetv"
    }
}

/** One entity as published to the dashboard: id, state and attributes. */
data class LgTvEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * Maps what an LG TV says to a `media_player` and a `remote` entity shaped like Home Assistant's
 * (its LG webOS TV integration publishes a media player too), so the media player, source and
 * TV remote cards, buttons, hotkeys and Activities drive it with or without Home Assistant.
 */
object LgTvEntityMapper {
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

    /**
     * An app's or input's icon as the remote serves it (1.5.2): the TV's own, fetched over the TV's
     * connection (its certificate pinned) and kept in memory — see LgTvRegistry.iconFor.
     */
    fun iconUrl(spec: LgTvSpec, id: String): String =
        "http://127.0.0.1:8080/lgtv-icon/${spec.localId}?id=" + java.net.URLEncoder.encode(id, "UTF-8")

    fun stateOf(s: LgTvState): String = if (s.reachable && s.on) "on" else "off"

    fun media(spec: LgTvSpec, s: LgTvState): LgTvEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "tv", "supported_features" to FEATURES)
        a["manufacturer"] = "LG"
        s.model?.let { a["model"] = it }
        // Its inputs first in the Sources pop-up (connected ones first), then its apps.
        val inputs = s.inputs.sortedBy { !it.connected }.map { it.label }
        val apps = s.apps.map { it.label }.sortedWith(String.CASE_INSENSITIVE_ORDER)
        (inputs + apps).distinct().takeIf { it.isNotEmpty() }?.let { a["source_list"] = it }
        addIcons(a, spec, s)
        if (s.reachable && s.on) {
            s.sourceLabel?.let { a["source"] = it }
            s.app?.let { a["app_id"] = it }
            // What's in front, as its icon: webOS gives no cover for what an app plays.
            s.app?.let { id -> s.iconOf(id)?.let { a["entity_picture"] = iconUrl(spec, id) } }
            s.volume?.let { a["volume_level"] = it / PERCENT }
            s.muted?.let { a["is_volume_muted"] = it }
            if (s.liveTv) a["media_content_type"] = "channel"
        }
        return LgTvEntityData(spec.entityId, stateOf(s), a)
    }

    /** Its apps with their icons (the TV remote card's app buttons), and each source's icon for the Sources pop-up. */
    private fun addIcons(a: MutableMap<String, Any?>, spec: LgTvSpec, s: LgTvState) {
        s.apps.takeIf { it.isNotEmpty() }?.let { list ->
            a["astrion_apps"] =
                list.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label }).map { app ->
                    mapOf("name" to app.label, "app" to app.id, "icon" to app.icon?.let { iconUrl(spec, app.id) })
                }
        }
        // Canal+, Plex… as the TV draws them; HDMI inputs have theirs too.
        val inputIcons = s.inputs.filter { it.icon != null }.map { it.label to iconUrl(spec, it.id) }
        val appIcons = s.apps.filter { it.icon != null }.map { it.label to iconUrl(spec, it.id) }
        (inputIcons + appIcons).toMap().takeIf { it.isNotEmpty() }?.let { a["astrion_source_icons"] = it }
    }

    fun remote(spec: LgTvSpec, s: LgTvState): LgTvEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name)
        if (s.reachable && s.on) s.sourceLabel?.let { a["current_activity"] = it }
        return LgTvEntityData(spec.remoteEntityId, stateOf(s), a)
    }
}

/** One thing to do on the TV, for a service call. */
sealed class LgTvStep {
    /** A remote button, on the pointer socket. */
    data class Button(val name: String) : LgTvStep()

    /** An `ssap://` request. */
    data class Request(val uri: String, val payload: JsonObject? = null) : LgTvStep()

    /** A Wake-on-LAN packet to the TV's MAC address. */
    object Wake : LgTvStep()
}

/**
 * What a Home Assistant service call on an LG TV's entities turns into. Pure, so the mapping is
 * unit-tested; the registry runs the steps.
 */
object LgTvServices {
    private const val MAX_REPEATS = 20
    private const val PERCENT = 100.0

    private fun req(uri: String, payload: JsonObject? = null) = listOf(LgTvStep.Request(uri, payload))

    /** Home Assistant `media_player.*`; null when the service isn't supported. */
    fun media(service: String, data: Map<String, Any?>, state: LgTvState): List<LgTvStep>? {
        power(service, state)?.let { return it }
        volume(service, data, state)?.let { return it }
        transport(service, state)?.let { return it }
        return when (service) {
            "select_source", "play_media" -> sourceOf(data["source"] ?: data["media_content_id"], state)
            else -> null
        }
    }

    private fun transport(service: String, state: LgTvState): List<LgTvStep>? = when (service) {
        "media_play" -> req(LgTvProtocol.PLAY)
        "media_pause" -> req(LgTvProtocol.PAUSE)
        "media_play_pause" -> req(if (state.paused) LgTvProtocol.PLAY else LgTvProtocol.PAUSE)
        "media_stop" -> req(LgTvProtocol.STOP)
        // Live TV: the channels; an app: rewind / fast-forward.
        "media_next_track" -> req(if (state.liveTv) LgTvProtocol.CHANNEL_UP else LgTvProtocol.FAST_FORWARD)
        "media_previous_track" -> req(if (state.liveTv) LgTvProtocol.CHANNEL_DOWN else LgTvProtocol.REWIND)
        else -> null
    }

    private fun power(service: String, state: LgTvState): List<LgTvStep>? = when (service) {
        "turn_on" -> turnOn(state)
        "turn_off" -> if (state.reachable) req(LgTvProtocol.POWER_OFF) else emptyList()
        "toggle" -> if (state.reachable && state.on) req(LgTvProtocol.POWER_OFF) else turnOn(state)
        else -> null
    }

    /** Wake-on-LAN for a TV that's off; a TV with only its screen off gets it back. */
    private fun turnOn(state: LgTvState): List<LgTvStep> = when {
        state.reachable && state.on && state.screenOff -> req(LgTvProtocol.SCREEN_ON)
        state.reachable && state.on -> emptyList()
        else -> listOf(LgTvStep.Wake)
    }

    private fun volume(service: String, data: Map<String, Any?>, state: LgTvState): List<LgTvStep>? = when (service) {
        "volume_up" -> req(LgTvProtocol.VOLUME_UP)
        "volume_down" -> req(LgTvProtocol.VOLUME_DOWN)
        "volume_set" ->
            (data["volume_level"] as? Number)?.let {
                req(LgTvProtocol.SET_VOLUME, LgTvProtocol.volumePayload(Math.round(it.toDouble() * PERCENT).toInt()))
            }
        "volume_mute" -> {
            val want = data["is_volume_muted"] as? Boolean ?: (state.muted != true)
            req(LgTvProtocol.SET_MUTE, LgTvProtocol.mutePayload(want))
        }
        else -> null
    }

    /** An input by its name or id ("HDMI 1", "HDMI_1"), else an app by its name or id ("Netflix", "netflix"). */
    fun sourceOf(value: Any?, state: LgTvState): List<LgTvStep>? {
        val v = (value as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        state.inputs.firstOrNull { it.label.equals(v, true) || it.id.equals(v, true) || it.appId.equals(v, true) }?.let {
            return req(LgTvProtocol.SWITCH_INPUT, LgTvProtocol.inputPayload(it.id))
        }
        val app = state.apps.firstOrNull { it.label.equals(v, true) || it.id.equals(v, true) }?.id ?: v
        return req(LgTvProtocol.LAUNCH, LgTvProtocol.launchPayload(app))
    }

    /**
     * Home Assistant `remote.*`: `send_command` with `command` (one name or a list: LG's button
     * names or Android TV names like "DPAD_UP"), optional `num_repeats`. POWER is a toggle,
     * MEDIA_PLAY_PAUSE alternates. Unknown names are skipped.
     */
    fun remote(service: String, data: Map<String, Any?>, state: LgTvState): List<LgTvStep>? = when (service) {
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

    private fun command(name: String, state: LgTvState): List<LgTvStep> = when (name.trim().uppercase().removePrefix("KEYCODE_")) {
        "POWER" -> media("toggle", emptyMap(), state).orEmpty()
        "WAKEUP" -> turnOn(state)
        "SLEEP" -> if (state.reachable) req(LgTvProtocol.POWER_OFF) else emptyList()
        "MEDIA_PLAY_PAUSE", "PLAY_PAUSE" -> media("media_play_pause", emptyMap(), state).orEmpty()
        else -> listOfNotNull(LgTvButtons.of(name)?.let { LgTvStep.Button(it) })
    }
}
