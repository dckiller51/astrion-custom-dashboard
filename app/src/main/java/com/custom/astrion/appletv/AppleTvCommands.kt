package com.custom.astrion.appletv

/**
 * Stateless mapping from the command/service names cards and Home Assistant
 * use to the [CompanionClient] calls that carry them out. Kept separate from
 * [AppleTvDevice] (which owns connection state) so this table — the part
 * most likely to grow as more commands are supported — stays independent
 * and easy to unit-test on its own.
 */
internal object AppleTvCommands {
    // Every accepted spelling of a remote-control button, mapped to the HID key it presses.
    // Covers both the Harmony-card names ("DirectionUp", "Select", ...) and plain ones
    // ("up", "play_pause", "volume_up", ...) once normalised by [normalize].
    private val HID_COMMANDS: Map<String, HidButton> =
        mapOf(
            "up" to HidButton.Up,
            "down" to HidButton.Down,
            "left" to HidButton.Left,
            "right" to HidButton.Right,
            "select" to HidButton.Select,
            "ok" to HidButton.Select,
            "enter" to HidButton.Select,
            "menu" to HidButton.Menu,
            "back" to HidButton.Menu,
            "home" to HidButton.Home,
            "tv" to HidButton.Home,
            "tvhome" to HidButton.Home,
            "playpause" to HidButton.PlayPause,
            "volumeup" to HidButton.VolumeUp,
            "volumedown" to HidButton.VolumeDown,
            "siri" to HidButton.Siri,
            "screensaver" to HidButton.Screensaver,
            "guide" to HidButton.Guide,
            "controlcenter" to HidButton.PageDown,
            "channelup" to HidButton.ChannelUp,
            "channeldown" to HidButton.ChannelDown
        )

    private val OTHER_COMMANDS: Map<String, (CompanionClient) -> Unit> =
        mapOf(
            "wake" to { c -> c.hid(HidButton.Wake, down = false) },
            "poweron" to { c -> c.hid(HidButton.Wake, down = false) },
            "sleep" to { c -> c.hid(HidButton.Sleep, down = false) },
            "poweroff" to { c -> c.hid(HidButton.Sleep, down = false) },
            "play" to { c -> playOrFallback(c, MediaCommand.Play) },
            "pause" to { c -> playOrFallback(c, MediaCommand.Pause) },
            "stop" to { c -> playOrFallback(c, MediaCommand.Pause) },
            "next" to { c -> c.mediaControl(MediaCommand.NextTrack) },
            "nexttrack" to { c -> c.mediaControl(MediaCommand.NextTrack) },
            "skipforward" to { c -> c.mediaControl(MediaCommand.NextTrack) },
            "previous" to { c -> c.mediaControl(MediaCommand.PreviousTrack) },
            "prev" to { c -> c.mediaControl(MediaCommand.PreviousTrack) },
            "previoustrack" to { c -> c.mediaControl(MediaCommand.PreviousTrack) },
            "skipback" to { c -> c.mediaControl(MediaCommand.PreviousTrack) },
            "skipbackward" to { c -> c.mediaControl(MediaCommand.PreviousTrack) },
            // A jump within the current item, like clicking the edge of the Siri Remote's clickpad.
            // Deliberately NOT "skipforward"/"skipbackward" above, which already mean next/previous
            // track and may be bound to existing hotkeys.
            "seekforward" to { c -> skipOrArrow(c, SEEK_STEP_SECONDS, HidButton.Right) },
            "seekbackward" to { c -> skipOrArrow(c, -SEEK_STEP_SECONDS, HidButton.Left) },
            "forward10" to { c -> skipOrArrow(c, SEEK_STEP_SECONDS, HidButton.Right) },
            "replay10" to { c -> skipOrArrow(c, -SEEK_STEP_SECONDS, HidButton.Left) }
        )

    const val SEEK_STEP_SECONDS = 10.0

    private fun normalize(rawName: String): String =
        rawName.lowercase().replace("_", "").replace("-", "").replace(" ", "").removePrefix("direction")

    /**
     * Resolves a remote command name (any accepted spelling) to the action that sends it, or null
     * if unrecognised.
     *
     * A button can be *held* instead of tapped, either by suffixing/prefixing its name
     * (`SelectHold`, `select_hold`, `LongSelect`, `long_select`) or by passing [holdMs] (what
     * `hold_secs` on `send_command` becomes). Holding only applies to the HID buttons; a held
     * name that isn't one (e.g. `PlayHold`) is unrecognised rather than silently tapped.
     */
    fun forCommand(rawName: String, holdMs: Long? = null): ((CompanionClient) -> Unit)? {
        val key = normalize(rawName)
        val namedHold = key.endsWith("hold") || key.startsWith("long")
        val base = if (namedHold) key.removeSuffix("hold").removePrefix("long") else key
        val hid = HID_COMMANDS[base]
        if (hid != null) {
            val duration = holdMs ?: if (namedHold) CompanionClient.DEFAULT_HOLD_MS else null
            return if (duration != null && duration > 0) {
                { c: CompanionClient -> c.hold(hid, duration) }
            } else {
                { c: CompanionClient -> c.press(hid) }
            }
        }
        return if (namedHold) null else OTHER_COMMANDS[key]
    }

    /** [CompanionClient.skipBy], falling back to the arrow key when the app refuses it (some apps scrub with Left/Right instead). */
    fun skipOrArrow(c: CompanionClient, seconds: Double, fallback: HidButton) {
        try {
            c.skipBy(seconds)
        } catch (_: CompanionException) {
            c.press(fallback)
        }
    }

    /**
     * `media_seek`: Companion has no absolute seek, so a target position becomes a relative jump
     * from where playback is now ([currentPosition], computed from MRP's last report). Null when
     * the call has no `seek_position` or nothing is known about the current position.
     */
    fun seekAction(data: Map<String, Any?>, currentPosition: () -> Double?): ((CompanionClient) -> Unit)? {
        val target = (data["seek_position"] as? Number)?.toDouble() ?: return null
        return { c ->
            val now = currentPosition()
            if (now != null) {
                val delta = target - now
                if (kotlin.math.abs(delta) >= 1.0) c.skipBy(delta)
            }
        }
    }

    /** Play/Pause via the media-control API, falling back to the HID toggle key when the Apple TV rejects it. */
    fun playOrFallback(c: CompanionClient, command: MediaCommand) {
        try {
            c.mediaControl(command)
        } catch (_: CompanionException) {
            c.press(HidButton.PlayPause)
        }
    }

    fun volumeSetAction(data: Map<String, Any?>): ((CompanionClient) -> Unit)? {
        val level = (data["volume_level"] as? Number)?.toDouble() ?: return null
        return { c -> c.setVolume(level) }
    }

    /** `select_source`: launches the app matching [data]'s `source` by display name, falling back to [knownApps] then a fresh fetch. */
    fun selectSourceAction(data: Map<String, Any?>, knownApps: () -> Map<String, String>): ((CompanionClient) -> Unit)? {
        val source = (data["source"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        return { c ->
            val apps = knownApps().ifEmpty { c.fetchApps() }
            val bundle =
                apps.entries.firstOrNull { it.value.equals(source, ignoreCase = true) }?.key
                    ?: apps.keys.firstOrNull { it == source }
                    ?: source
            c.launch(bundle)
        }
    }

    fun playMediaAction(data: Map<String, Any?>): ((CompanionClient) -> Unit)? {
        val type = (data["media_content_type"] as? String)?.lowercase()
        val id = (data["media_content_id"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        if (type != "app" && type != "url") return null
        return { c -> c.launch(id) }
    }
}
