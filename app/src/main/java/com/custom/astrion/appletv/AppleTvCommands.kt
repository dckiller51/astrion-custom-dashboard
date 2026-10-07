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
            "skipbackward" to { c -> c.mediaControl(MediaCommand.PreviousTrack) }
        )

    private fun normalize(rawName: String): String =
        rawName.lowercase().replace("_", "").replace("-", "").replace(" ", "").removePrefix("direction")

    /** Resolves a remote command name (any accepted spelling) to the action that sends it, or null if unrecognised. */
    fun forCommand(rawName: String): ((CompanionClient) -> Unit)? {
        val key = normalize(rawName)
        return HID_COMMANDS[key]?.let { hid -> { c: CompanionClient -> c.press(hid) } } ?: OTHER_COMMANDS[key]
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
