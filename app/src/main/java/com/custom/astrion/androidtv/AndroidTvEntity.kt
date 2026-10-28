package com.custom.astrion.androidtv

/** One paired Android TV, as the registry drives it. */
data class AndroidTvSpec(
    val localId: String,
    /** `media_player.androidtv_…`; the remote entity is the same object id under `remote.`. */
    val entityId: String,
    val name: String
) {
    val remoteEntityId: String get() = "remote." + entityId.substringAfter('.')
}

/** The Astrion TV Helper's side of a TV (1.3.6): how the link stands, and what it says is playing. */
data class AndroidTvHelperState(val link: HelperLink = HelperLink.NONE, val info: AndroidTvHelperInfo? = null)

/** A Home-Assistant-shaped entity: state string plus attributes. */
data class AndroidTvEntityData(val entityId: String, val state: String, val attributes: Map<String, Any?>)

/**
 * Apps by package, for `source`/`source_list`: Android TV Remote v2 tells which app is in the
 * foreground (by package) but can't list the installed ones, so the common streaming apps are
 * known here. Any other package still works with `select_source` / `play_media`.
 */
object AndroidTvApps {
    val known: Map<String, String> =
        linkedMapOf(
            "com.netflix.ninja" to "Netflix",
            "com.google.android.youtube.tv" to "YouTube",
            "com.amazon.amazonvideo.livingroom" to "Prime Video",
            "com.disney.disneyplus" to "Disney+",
            "com.wbd.stream" to "Max",
            "com.apple.atve.androidtv.appletv" to "Apple TV",
            "com.cbs.ott" to "Paramount+",
            "com.plexapp.android" to "Plex",
            "org.xbmc.kodi" to "Kodi",
            "com.spotify.tv.android" to "Spotify",
            "com.google.android.youtube.tvmusic" to "YouTube Music",
            "tv.twitch.android.app" to "Twitch",
            "com.canal.android.canal" to "myCANAL",
            "tv.molotov.app" to "Molotov",
            "fr.francetv.pluzz" to "france.tv",
            "au.com.stan.and" to "Stan",
            "com.dazn" to "DAZN",
            "com.crunchyroll.crunchyroid" to "Crunchyroll",
            "com.nvidia.geforcenow" to "GeForce NOW",
            "com.google.android.youtube.tvkids" to "YouTube Kids"
        )

    /**
     * Deep links that open an app directly. Since a Google Play Store update (August 2026), many
     * Android TVs refuse `market://launch?id=<package>`, the protocol's generic way of opening an
     * app, but still open links an app claims. Links from Home Assistant's Android TV Remote docs, and the apps' own intent filters.
     */
    private val deepLinks: Map<String, String> =
        mapOf(
            "com.google.android.youtube.tv" to "https://www.youtube.com",
            "com.netflix.ninja" to "netflix://",
            "com.amazon.amazonvideo.livingroom" to "https://app.primevideo.com",
            "com.disney.disneyplus" to "https://www.disneyplus.com",
            "com.plexapp.android" to "plex://",
            "tv.twitch.android.app" to "twitch://home",
            // From the app's own intent filters (TvMainActivity), checked on an NVIDIA SHIELD.
            "com.canal.android.canal" to "mycanaltvlauncher://com.canal.android.canal/startapp"
        )

    /** The home screens of Android TV / Google TV (not apps to open, so not in [known]). */
    val launchers: Set<String> =
        setOf(
            "com.google.android.tvlauncher",
            "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher",
            "com.amazon.tv.launcher"
        )

    /** Other system screens the TV may report as the app in front. */
    private val system: Map<String, String> =
        mapOf(
            "com.android.tv.settings" to "Settings",
            "com.android.vending" to "Play Store",
            "com.google.android.katniss" to "Google Assistant"
        )

    fun nameOf(packageName: String): String = when (packageName) {
        in launchers -> "Home"
        else -> known[packageName] ?: system[packageName] ?: packageName
    }

    /**
     * What to send to open [appOrLink]: a link as is, an app's deep link when known, otherwise
     * the generic `market://launch?id=<package>`.
     */
    fun linkFor(appOrLink: String): String = when {
        appOrLink.contains("://") -> appOrLink
        else -> deepLinks[appOrLink] ?: "market://launch?id=$appOrLink"
    }

    /** "Netflix" or "com.netflix.ninja" → the package; anything else (a package, a link) as is. */
    fun packageOf(source: String): String =
        known.entries.firstOrNull { it.value.equals(source.trim(), ignoreCase = true) }?.key ?: source.trim()
}

/**
 * Maps what a TV reports to the `media_player` and `remote` entities Home Assistant's own Android
 * TV Remote integration publishes, so every card, hotkey and Activity that works with those works
 * with a directly paired TV — the TV remote card included (`remote.send_command`).
 */
object AndroidTvEntityMapper {
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
        PAUSE or VOLUME_MUTE or PREVIOUS_TRACK or NEXT_TRACK or TURN_ON or TURN_OFF or PLAY_MEDIA or
            VOLUME_STEP or SELECT_SOURCE or STOP or PLAY

    /** "unavailable" when not connected, then "on"/"off" as the TV says, "unknown" before it says. */
    fun stateOf(s: AndroidTvState): String = when {
        !s.connected -> "unavailable"
        s.isOn == true -> "on"
        s.isOn == false -> "off"
        else -> "unknown"
    }

    fun media(spec: AndroidTvSpec, s: AndroidTvState, helper: AndroidTvHelperState = AndroidTvHelperState()): AndroidTvEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "device_class" to "tv")
        val helperUp = helper.link == HelperLink.CONNECTED
        a["supported_features"] = if (helperUp) FEATURES or SEEK else FEATURES
        addTvAttributes(a, s)
        if (helper.link != HelperLink.NONE) a["astrion_helper"] = helper.link.attribute
        var state = stateOf(s)
        // The helper's session only counts for the app in front: a paused Plex left behind
        // while on the home screen or in another app isn't "what's playing".
        val np = helper.info?.takeIf { helperUp && it.state != "none" && (s.currentApp == null || it.packageName == s.currentApp) }
        if (np != null && state == "on") {
            state = playerState(np.state)
            addNowPlaying(a, spec, np)
        }
        return AndroidTvEntityData(spec.entityId, state, a)
    }

    /** What the TV itself reports: the app in front, volume, model. */
    private fun addTvAttributes(a: MutableMap<String, Any?>, s: AndroidTvState) {
        a["source_list"] = AndroidTvApps.known.values.sortedWith(String.CASE_INSENSITIVE_ORDER)
        s.currentApp?.let { app ->
            a["app_id"] = app
            a["app_name"] = AndroidTvApps.nameOf(app)
            a["source"] = AndroidTvApps.nameOf(app)
        }
        val level = s.volumeLevel
        val max = s.volumeMax
        if (level != null && max != null && max > 0) a["volume_level"] = level.toDouble() / max
        s.muted?.let { a["is_volume_muted"] = it }
        s.model?.let { a["model"] = it }
        s.vendor?.let { a["manufacturer"] = it }
        if (s.needsPairing) a["astrion_needs_pairing"] = true
    }

    /** The helper's playback state as Home Assistant's; "idle" stays "on" (Activities read it as on). */
    private fun playerState(helperState: String) = when (helperState) {
        "playing", "paused", "buffering" -> helperState
        else -> "on"
    }

    private fun addNowPlaying(a: MutableMap<String, Any?>, spec: AndroidTvSpec, np: AndroidTvHelperInfo) {
        a["media_content_type"] = "video"
        np.title?.let { a["media_title"] = it }
        (np.artist ?: np.subtitle)?.let { a["media_artist"] = it }
        np.album?.let { a["media_album_name"] = it }
        np.durationMs?.let { a["media_duration"] = (it / MS_PER_S).toInt() }
        np.positionMs?.let { a["media_position"] = (it / MS_PER_S).toInt() }
        np.positionUpdatedAt?.let { a["media_position_updated_at"] = isoInstant(it) }
        // Same pattern as the Apple TV: the cover is served by this remote's own web server, which
        // fetched it from the helper (cards load it without Home Assistant).
        val picture = np.artVersion?.let { "http://127.0.0.1:8080/androidtv-artwork/${spec.localId}?v=$it" } ?: np.artUrl
        picture?.let { a["entity_picture"] = it }
    }

    private fun isoInstant(unixMs: Long): String {
        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", java.util.Locale.US)
        formatter.timeZone = java.util.TimeZone.getTimeZone("UTC")
        return formatter.format(java.util.Date(unixMs))
    }

    private const val MS_PER_S = 1000L

    fun remote(spec: AndroidTvSpec, s: AndroidTvState): AndroidTvEntityData {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name)
        s.currentApp?.let { a["current_activity"] = AndroidTvApps.nameOf(it) }
        if (s.needsPairing) a["astrion_needs_pairing"] = true
        return AndroidTvEntityData(spec.remoteEntityId, stateOf(s), a)
    }
}

/**
 * What a Home Assistant service call on a TV's entities turns into: keys to press and/or an app
 * to open. Pure, so the mapping is unit-tested; the registry sends the result.
 */
object AndroidTvServices {
    /** [seekMs]: a position to jump to, through the Astrion TV Helper (the protocol has no seek). */
    data class Action(
        val keys: List<Int> = emptyList(),
        val launch: String? = null,
        val longPress: Boolean = false,
        val seekMs: Long? = null
    )

    private fun key(name: String) = listOfNotNull(AndroidTvKeys.code(name))

    /** Services that are just a key. */
    private val keyServices =
        mapOf(
            "toggle" to "POWER",
            "volume_up" to "VOLUME_UP",
            "volume_down" to "VOLUME_DOWN",
            "media_play_pause" to "MEDIA_PLAY_PAUSE",
            "media_play" to "MEDIA_PLAY",
            "media_pause" to "MEDIA_PAUSE",
            "media_stop" to "MEDIA_STOP",
            "media_next_track" to "MEDIA_NEXT",
            "media_previous_track" to "MEDIA_PREVIOUS"
        )

    /** Home Assistant `media_player.*`; null when the service isn't supported. */
    fun media(service: String, data: Map<String, Any?>, state: AndroidTvState): Action? {
        keyServices[service]?.let { return Action(key(it)) }
        return when (service) {
            "turn_on" -> Action(turnOnKeys(state))
            // Like Home Assistant's integration: POWER is a toggle, only pressed when it changes something.
            "turn_off" -> Action(if (state.isOn == true) key("POWER") else emptyList())
            "volume_mute" -> {
                val mute = data["is_volume_muted"] as? Boolean
                Action(if (mute == null || mute != state.muted) key("VOLUME_MUTE") else emptyList())
            }
            "select_source" -> launchOf(data["source"])
            "media_seek" -> (data["seek_position"] as? Number)?.let { Action(seekMs = (it.toDouble() * MS_PER_SECOND).toLong()) }
            "play_media" -> launchOf(data["media_content_id"])
            else -> null
        }
    }

    /** POWER only when the TV is known off; state not known yet: WAKEUP, which can only turn it on, never off. */
    private fun turnOnKeys(state: AndroidTvState): List<Int> = when (state.isOn) {
        false -> key("POWER")
        null -> key("WAKEUP")
        true -> emptyList()
    }

    private fun launchOf(value: Any?): Action? = (value as? String)?.takeIf {
        it.isNotBlank()
    }?.let { Action(launch = AndroidTvApps.packageOf(it)) }

    /**
     * Home Assistant `remote.*`: `send_command` with `command` (one name or a list, Android key
     * names like "DPAD_UP"), optional `num_repeats` and `hold_secs` (> 0 = long press);
     * `turn_on`/`turn_off`/`toggle` like the media player. Unknown key names are skipped.
     */
    fun remote(service: String, data: Map<String, Any?>, state: AndroidTvState): Action? = when (service) {
        "send_command" -> {
            val names =
                when (val c = data["command"]) {
                    is String -> listOf(c)
                    is List<*> -> c.filterIsInstance<String>()
                    else -> emptyList()
                }
            val repeats = ((data["num_repeats"] as? Number)?.toInt() ?: 1).coerceIn(1, MAX_REPEATS)
            val hold = ((data["hold_secs"] as? Number)?.toDouble() ?: 0.0) > 0.0
            val keys = names.mapNotNull(AndroidTvKeys::code)
            Action(keys = List(repeats) { keys }.flatten(), longPress = hold)
        }
        "turn_on", "turn_off", "toggle" -> media(service, data, state)
        else -> null
    }

    private const val MAX_REPEATS = 20
    private const val MS_PER_SECOND = 1000
}
