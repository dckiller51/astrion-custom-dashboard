package com.custom.astrion.zidoo

/** One Zidoo player added on the web page (1.4.6), as the registry drives it. */
data class ZidooSpec(
    val localId: String,
    /** `media_player.zidoo_…`; the remote entity is the same object id under `remote.`. */
    val entityId: String,
    val name: String,
    /** The player's MAC address (wired first), for Wake-on-LAN. Blank when unknown. */
    val mac: String = ""
) {
    val remoteEntityId: String get() = "remote." + entityId.substringAfter('.')
}

/** Which of the player's own apps plays something: its video player, its music player, or none. */
enum class ZidooSource { NONE, VIDEO, MUSIC }

/** What the player last said. [reachable] false: it doesn't answer (off, standby or away). */
data class ZidooState(
    val reachable: Boolean = false,
    val model: String? = null,
    val firmware: String? = null,
    val source: ZidooSource = ZidooSource.NONE,
    val playing: Boolean = false,
    val title: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val seriesName: String? = null,
    val season: Int? = null,
    val episode: Int? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    /** When [positionMs] was read (unix ms), so cards move the progress bar between polls. */
    val positionAt: Long? = null,
    /** The cover or backdrop, served by the player itself (`http://<ip>:9529/…`). */
    val pictureUrl: String? = null,
    /** Installed apps that can be opened: label → package. */
    val apps: Map<String, String> = emptyMap()
)

/**
 * The player's remote keys (`Key.*`, the names its control API takes), and the Android TV key
 * names the TV remote card, the hotkeys and the Android TV integration use, mapped to them — so a
 * TV remote card pointed at `remote.zidoo_…` works unchanged.
 */
object ZidooKeys {
    private val androidNames: Map<String, String> =
        mapOf(
            "DPAD_UP" to "Key.Up",
            "DPAD_DOWN" to "Key.Down",
            "DPAD_LEFT" to "Key.Left",
            "DPAD_RIGHT" to "Key.Right",
            "DPAD_CENTER" to "Key.Ok",
            "ENTER" to "Key.Ok",
            "BACK" to "Key.Back",
            "ESCAPE" to "Key.Back",
            "HOME" to "Key.Home",
            "MENU" to "Key.Menu",
            "INFO" to "Key.Info",
            "VOLUME_UP" to "Key.VolumeUp",
            "VOLUME_DOWN" to "Key.VolumeDown",
            "VOLUME_MUTE" to "Key.Mute",
            "MUTE" to "Key.Mute",
            "MEDIA_PLAY" to "Key.MediaPlay",
            "MEDIA_PAUSE" to "Key.MediaPause",
            "MEDIA_STOP" to "Key.MediaStop",
            "MEDIA_NEXT" to "Key.MediaNext",
            "MEDIA_PREVIOUS" to "Key.MediaPrev",
            "MEDIA_REWIND" to "Key.MediaBackward",
            "MEDIA_FAST_FORWARD" to "Key.MediaForward",
            "MEDIA_RECORD" to "Key.Record",
            "MEDIA_TOP_MENU" to "Key.PopMenu",
            "MEDIA_AUDIO_TRACK" to "Key.Audio",
            "CAPTIONS" to "Key.Subtitle",
            "PAGE_UP" to "Key.PageUP",
            "PAGE_DOWN" to "Key.PageDown",
            "STAR" to "Key.Star",
            "POUND" to "Key.Pound",
            "APP_SWITCH" to "Key.APP.Switch",
            "PROG_RED" to "Key.UserDefine_A",
            "PROG_GREEN" to "Key.UserDefine_B",
            "PROG_YELLOW" to "Key.UserDefine_C",
            "PROG_BLUE" to "Key.UserDefine_D"
        ) + (0..9).associate { "$it" to "Key.Number_$it" }

    /** Short names some cards already use (as for Android TV). */
    private val aliases =
        mapOf(
            "UP" to "DPAD_UP",
            "DOWN" to "DPAD_DOWN",
            "LEFT" to "DPAD_LEFT",
            "RIGHT" to "DPAD_RIGHT",
            "OK" to "DPAD_CENTER",
            "SELECT" to "DPAD_CENTER",
            "CENTER" to "DPAD_CENTER",
            "PLAY" to "MEDIA_PLAY",
            "PAUSE" to "MEDIA_PAUSE",
            "STOP" to "MEDIA_STOP",
            "NEXT" to "MEDIA_NEXT",
            "PREVIOUS" to "MEDIA_PREVIOUS",
            "REWIND" to "MEDIA_REWIND",
            "FAST_FORWARD" to "MEDIA_FAST_FORWARD"
        )

    /** "Eversolo" for its streamers (DMP-A6, DMP-A8…: same control API, 1.4.9), else "Zidoo". */
    fun brandOf(model: String?): String {
        val m = model.orEmpty()
        val eversolo = m.startsWith("DMP", ignoreCase = true) || m.contains("eversolo", ignoreCase = true)
        return if (eversolo) "Eversolo" else "Zidoo"
    }

    private val KEY_NAME = Regex("^Key\\.[A-Za-z0-9_.]{1,40}$")

    /** The `Key.*` to send for [name]: one of the player's own (as is) or an Android name; null when unknown. */
    fun of(name: String): String? {
        val n = name.trim()
        if (KEY_NAME.matches(n)) return n
        val upper = n.uppercase().removePrefix("KEYCODE_")
        return androidNames[aliases[upper] ?: upper]
    }
}

/**
 * Maps what a Zidoo says to a `media_player` and a `remote` entity shaped like Home Assistant's
 * (the HACS Zidoo integration publishes the same two), so the media player, source and TV remote
 * cards, buttons, hotkeys and Activities drive it with or without Home Assistant.
 */
object ZidooEntityMapper {
    // Home Assistant MediaPlayerEntityFeature bits.
    private const val PAUSE = 1
    private const val SEEK = 2
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
        PAUSE or VOLUME_MUTE or PREVIOUS_TRACK or NEXT_TRACK or TURN_ON or TURN_OFF or
            PLAY_MEDIA or VOLUME_STEP or SELECT_SOURCE or STOP or PLAY

    private const val MS_PER_S = 1000L

    /** "off" when it doesn't answer (a Zidoo in standby stops answering), else on / playing / paused. */
    fun stateOf(s: ZidooState): String = when {
        !s.reachable -> "off"
        s.source == ZidooSource.NONE -> "on"
        s.playing -> "playing"
        else -> "paused"
    }

    fun media(spec: ZidooSpec, s: ZidooState): ZidooEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "receiver")
        // Seeking only while its own video or music player plays: otherwise the TV remote card's
        // ±10 s falls back to the rewind / fast-forward keys.
        val playing = s.reachable && s.source != ZidooSource.NONE
        a["supported_features"] = if (playing) FEATURES or SEEK else FEATURES
        a["manufacturer"] = ZidooKeys.brandOf(s.model)
        s.model?.let { a["model"] = it }
        s.firmware?.let { a["sw_version"] = it }
        if (s.apps.isNotEmpty()) a["source_list"] = s.apps.keys.sortedWith(String.CASE_INSENSITIVE_ORDER)
        mediaApps(s.apps).takeIf { it.isNotEmpty() }?.let { a["astrion_apps"] = it }
        if (playing) addNowPlaying(a, s)
        return ZidooEntityData(spec.entityId, stateOf(s), a)
    }

    private fun addNowPlaying(a: MutableMap<String, Any?>, s: ZidooState) {
        val video = s.source == ZidooSource.VIDEO
        a["source"] = if (video) "Video player" else "Music player"
        a["media_content_type"] = if (video) (if (s.episode != null) "tvshow" else "movie") else "music"
        s.title?.let { a["media_title"] = it }
        s.artist?.let { a["media_artist"] = it }
        s.album?.let { a["media_album_name"] = it }
        s.seriesName?.let { a["media_series_title"] = it }
        s.season?.let { a["media_season"] = it }
        s.episode?.let { a["media_episode"] = it }
        s.durationMs?.takeIf { it > 0 }?.let { a["media_duration"] = (it / MS_PER_S).toInt() }
        s.positionMs?.let { a["media_position"] = (it / MS_PER_S).toInt() }
        s.positionAt?.let { a["media_position_updated_at"] = isoInstant(it) }
        s.pictureUrl?.let { a["entity_picture"] = it }
    }

    /**
     * The apps worth a button (1.4.8): every openable one but the player's own utilities
     * (settings, updates, file manager…), as `{name, app}` — what the TV remote card's `apps`
     * take. The builder offers them, and the card uses them when it has no `apps` of its own.
     */
    fun mediaApps(apps: Map<String, String>): List<Map<String, String>> = apps.entries
        .filter { (_, pkg) -> pkg !in UTILITY_APPS }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.key })
        .map { (label, pkg) -> mapOf("name" to label, "app" to pkg) }

    private val UTILITY_APPS =
        setOf(
            "com.zidoo.custom.power",
            "com.zidoo.control.center",
            "com.zidoo.fileexplorer",
            "com.zidoo.usb.install",
            "com.zidoo.imageplayer",
            "com.zidoo.key.installation",
            "com.zidoo.ota.update",
            "com.zidoo.clear",
            "com.zidoo.rs232.control",
            "com.zidoo.shortcut.key",
            "com.zidoo.setupwizard",
            "com.zidoo.cnc",
            "com.android.quick.settings",
            "com.android.chrome"
        )

    fun remote(spec: ZidooSpec, s: ZidooState): ZidooEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name)
        if (s.reachable && s.source != ZidooSource.NONE) {
            a["current_activity"] = if (s.source == ZidooSource.VIDEO) "Video player" else "Music player"
        }
        return ZidooEntityData(spec.remoteEntityId, if (s.reachable) "on" else "off", a)
    }

    private fun isoInstant(unixMs: Long): String {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formatter.format(java.util.Date(unixMs))
    }
}

/** A Home-Assistant-shaped entity: state string plus attributes. */
data class ZidooEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/** One thing to do on the player, in order. */
sealed class ZidooStep {
    /** A remote key (`Key.*`). */
    data class Key(val key: String) : ZidooStep()

    /** A control API call: path and query, without the host. */
    data class Api(val path: String) : ZidooStep()

    /** A Wake-on-LAN packet to the player's MAC address. */
    object Wake : ZidooStep()

    /** Opens an installed app, by its label or package. */
    data class Launch(val app: String) : ZidooStep()
}

/**
 * What a Home Assistant service call on a Zidoo's entities turns into. Pure, so the mapping is
 * unit-tested; the registry runs the steps. Transport goes to the player's own video or music
 * player when one plays (exact play / pause / seek), else to the matching remote key.
 */
object ZidooServices {
    private const val MAX_REPEATS = 20
    private const val MS_PER_SECOND = 1000

    private const val POWER_ON = "Key.PowerOn"
    private const val POWER_OFF = "Key.PowerOn.Poweroff"

    private fun keys(vararg k: String) = k.map { ZidooStep.Key(it) }

    private fun api(path: String) = listOf(ZidooStep.Api(path))

    private val keyServices =
        mapOf(
            "volume_up" to "Key.VolumeUp",
            "volume_down" to "Key.VolumeDown",
            "volume_mute" to "Key.Mute",
            "media_stop" to "Key.MediaStop"
        )

    /** Home Assistant `media_player.*`; null when the service isn't supported. */
    fun media(service: String, data: Map<String, Any?>, state: ZidooState): List<ZidooStep>? {
        keyServices[service]?.let { return keys(it) }
        power(service, state)?.let { return it }
        transport(service, state)?.let { return it }
        return when (service) {
            "media_seek" -> (data["seek_position"] as? Number)?.let { seek(state, (it.toDouble() * MS_PER_SECOND).toLong()) }
            "select_source" -> launchOf(data["source"])
            "play_media" -> launchOf(data["media_content_id"])
            else -> null
        }
    }

    private fun power(service: String, state: ZidooState): List<ZidooStep>? = when (service) {
        "turn_on" -> turnOn(state)
        "turn_off" -> if (state.reachable) keys(POWER_OFF) else emptyList()
        "toggle" -> if (state.reachable) keys(POWER_OFF) else turnOn(state)
        else -> null
    }

    private fun transport(service: String, state: ZidooState): List<ZidooStep>? = when (service) {
        "media_play" -> play(state, true)
        "media_pause" -> play(state, false)
        "media_play_pause" -> play(state, !state.playing)
        "media_next_track" -> track(state, next = true)
        "media_previous_track" -> track(state, next = false)
        else -> null
    }

    /** Wake-on-LAN for a player that's fully off, then its power key for one in network standby. */
    private fun turnOn(state: ZidooState): List<ZidooStep> = if (state.reachable) emptyList() else listOf(ZidooStep.Wake) + keys(POWER_ON)

    private fun play(state: ZidooState, play: Boolean): List<ZidooStep> = when (state.source) {
        ZidooSource.VIDEO -> api("ZidooVideoPlay/changeStatus?status=${if (play) 1 else 0}")
        // The music player only toggles: sent only when it changes something.
        ZidooSource.MUSIC -> if (play != state.playing) api("MusicControl/v2/playOrPause") else emptyList()
        ZidooSource.NONE -> keys(if (play) "Key.MediaPlay" else "Key.MediaPause")
    }

    private fun track(state: ZidooState, next: Boolean): List<ZidooStep> = when (state.source) {
        ZidooSource.VIDEO -> api("ZidooVideoPlay/changevideo?type=0&tag=${if (next) 1 else 0}")
        ZidooSource.MUSIC -> api(if (next) "MusicControl/v2/playNext" else "MusicControl/v2/playLast")
        ZidooSource.NONE -> keys(if (next) "Key.MediaNext" else "Key.MediaPrev")
    }

    private fun seek(state: ZidooState, ms: Long): List<ZidooStep>? {
        val at = ms.coerceAtLeast(0L)
        return when (state.source) {
            // "positon" is how the player's API spells it.
            ZidooSource.VIDEO -> api("ZidooVideoPlay/seekTo?positon=$at")
            ZidooSource.MUSIC -> api("ZidooMusicControl/seekTo?time=$at")
            ZidooSource.NONE -> null
        }
    }

    private fun launchOf(value: Any?): List<ZidooStep>? = (value as? String)?.trim()?.takeIf {
        it.isNotEmpty()
    }?.let { listOf(ZidooStep.Launch(it)) }

    /**
     * Home Assistant `remote.*`: `send_command` with `command` (one name or a list: the player's
     * `Key.*` names or Android TV names like "DPAD_UP"), optional `num_repeats`. POWER is a toggle
     * here too, MEDIA_PLAY_PAUSE follows what plays. Unknown names are skipped.
     */
    fun remote(service: String, data: Map<String, Any?>, state: ZidooState): List<ZidooStep>? = when (service) {
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

    private fun command(name: String, state: ZidooState): List<ZidooStep> = when (name.trim().uppercase().removePrefix("KEYCODE_")) {
        "POWER" -> media("toggle", emptyMap(), state).orEmpty()
        "WAKEUP" -> turnOn(state)
        "SLEEP" -> if (state.reachable) keys(POWER_OFF) else emptyList()
        "MEDIA_PLAY_PAUSE", "PLAY_PAUSE" -> play(state, !state.playing)
        else -> listOfNotNull(ZidooKeys.of(name)?.let { ZidooStep.Key(it) })
    }
}
