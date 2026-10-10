package com.custom.astrion.appletv

import java.security.MessageDigest

/** HID button codes understood by the Companion `_hidC` request. */
enum class HidButton(val code: Int) {
    Up(1),
    Down(2),
    Left(3),
    Right(4),
    Menu(5),
    Select(6),
    Home(7),
    VolumeUp(8),
    VolumeDown(9),
    Siri(10),
    Screensaver(11),
    Sleep(12),
    Wake(13),
    PlayPause(14),
    ChannelUp(15),
    ChannelDown(16),
    Guide(17),
    PageUp(18),
    PageDown(19)
}

/** Playback commands for the Companion `_mcc` request. */
enum class MediaCommand(val code: Int) {
    Play(1),
    Pause(2),
    NextTrack(3),
    PreviousTrack(4),
    GetVolume(5),
    SetVolume(6),
    SkipBy(7)
}

/** Bit flags of the `_iMC` event: which playback controls the Apple TV currently offers. */
object MediaControlFlags {
    const val PLAY = 0x0001
    const val PAUSE = 0x0002
    const val NEXT_TRACK = 0x0004
    const val PREVIOUS_TRACK = 0x0008
    const val VOLUME = 0x0100
    const val SKIP_FORWARD = 0x0200
    const val SKIP_BACKWARD = 0x0400
}

/** Screen state as reported by Apple TV's attention/system-status. */
enum class AttentionState {
    Unknown,
    Asleep,
    Screensaver,
    Awake,
    Idle;

    val isOn: Boolean get() = this == Screensaver || this == Awake || this == Idle

    companion object {
        fun fromCode(code: Int?): AttentionState = when (code) {
            1 -> Asleep
            2 -> Screensaver
            3 -> Awake
            4 -> Idle
            else -> Unknown
        }
    }
}

/**
 * An authenticated, encrypted Companion session with one Apple TV:
 * remote-control keys, playback commands, app list / launch, power state
 * and change notifications. All calls block and must run off the UI thread.
 */
class CompanionClient(
    private val host: String,
    private val port: Int,
    private val credentials: AppleTvCredentials,
    private val listener: Listener
) {
    interface Listener {
        /** Media-control flags changed (see [MediaControlFlags]). */
        fun onMediaControlFlags(flags: Int)

        /** Power / attention state changed. */
        fun onAttention(state: AttentionState)

        /** Connection lost (after a successful [connect]) or closed. */
        fun onDisconnected(error: Throwable?)
    }

    private val link =
        CompanionLink(
            host,
            port,
            object : CompanionLink.Listener {
                override fun onEvent(name: String, content: Map<*, *>) = handleEvent(name, content)

                override fun onClosed(error: Throwable?) = listener.onDisconnected(error)
            }
        )

    val isConnected: Boolean get() = link.isOpen

    /** Connects, authenticates with the stored credentials and opens the remote-control session. */
    fun connect(timeoutMs: Int = 5000) {
        link.connect(timeoutMs)
        try {
            CompanionPairVerify.run(link, credentials)
            sendSystemInfo()
            runCatching { link.exchange("_touchStart", mapOf("_height" to 1000.0, "_tFl" to 0L, "_width" to 1000.0)) }
            startSession()
            runCatching { link.exchange("TVRCSessionStart", mapOf("ProtocolVersionKey" to "1.2")) }
            for (event in listOf("_iMC", "SystemStatus", "TVSystemStatus")) {
                link.sendEvent("_interest", mapOf("_regEvents" to listOf(event)))
            }
        } catch (e: Exception) {
            link.close()
            throw e
        }
    }

    fun close() = link.close()

    private fun sendSystemInfo() {
        val digest = MessageDigest.getInstance("MD5").digest(credentials.clientId).toHex()
        val stableId = digest.take(12)
        link.exchange(
            "_systemInfo",
            mapOf(
                "_bf" to 0L,
                "_cf" to 512L,
                "_clFl" to 128L,
                "_i" to stableId,
                "_idsID" to credentials.clientId,
                "_pubID" to stableId.chunked(2).joinToString(":"),
                "_sf" to 256L,
                "_sv" to "170.18",
                "model" to "iPhone10,6",
                "name" to "Astrion"
            )
        )
    }

    private fun startSession() {
        val localSid = (0L..0xFFFFFFFFL).random()
        link.exchange("_sessionStart", mapOf("_srvT" to "com.apple.tvremoteservices", "_sid" to localSid))
    }

    private fun handleEvent(name: String, content: Map<*, *>) {
        when (name) {
            "_iMC" -> (content["_mcF"] as? Long)?.let { listener.onMediaControlFlags(it.toInt()) }
            "SystemStatus", "TVSystemStatus" -> listener.onAttention(AttentionState.fromCode((content["state"] as? Long)?.toInt()))
        }
    }

    // ---- commands -----------------------------------------------------------

    /** Presses and releases a remote button. */
    fun press(button: HidButton) {
        hid(button, down = true)
        hid(button, down = false)
    }

    /**
     * Holds a remote button down for [durationMs] before releasing it — pyatv's `InputAction.Hold`.
     * A ~1 s hold on Select is what opens tvOS's context menu (e.g. "Remove from Up Next" in the
     * Apple TV app). Blocks the calling thread for the duration; [AppleTvDevice] runs every
     * command on its own serial thread, so that's harmless there.
     */
    fun hold(button: HidButton, durationMs: Long = DEFAULT_HOLD_MS) {
        hid(button, down = true)
        try {
            Thread.sleep(durationMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))
        } finally {
            hid(button, down = false)
        }
    }

    /**
     * Jumps the current item by [seconds] (negative = backwards) — what tapping the edge of a
     * real Siri Remote's clickpad does. Sent as a Double on purpose: OPACK has no negative
     * integer encoding, and the Apple TV accepts a float here.
     */
    fun skipBy(seconds: Double) {
        mediaControl(MediaCommand.SkipBy, mapOf("_skpS" to seconds))
    }

    companion object {
        const val DEFAULT_HOLD_MS = 1000L
        private const val MIN_HOLD_MS = 100L
        private const val MAX_HOLD_MS = 5000L
    }

    /** One half of a button press; Wake/Sleep are triggered by the release half alone. */
    fun hid(button: HidButton, down: Boolean) {
        link.exchange("_hidC", mapOf("_hBtS" to if (down) 1L else 2L, "_hidC" to button.code.toLong()))
    }

    fun mediaControl(command: MediaCommand, extra: Map<String, Any?> = emptyMap()): Map<*, *> = link.exchange(
        "_mcc",
        mapOf("_mcc" to command.code.toLong()) + extra
    )

    /** Current volume as 0.0–1.0, or null if the Apple TV doesn't expose one. */
    fun getVolume(): Double? = ((mediaControl(MediaCommand.GetVolume)["_c"] as? Map<*, *>)?.get("_vol") as? Double)

    fun setVolume(level: Double) {
        mediaControl(MediaCommand.SetVolume, mapOf("_vol" to level.coerceIn(0.0, 1.0)))
    }

    /** Launchable apps as bundle id -> display name. */
    fun fetchApps(): Map<String, String> {
        val content = link.exchange("FetchLaunchableApplicationsEvent", emptyMap())["_c"] as? Map<*, *> ?: return emptyMap()
        return content.entries.mapNotNull { (k, v) -> if (k is String && v is String) k to v else null }.toMap()
    }

    /** Launches an app by bundle id, or opens a URL / URL scheme. */
    fun launch(bundleIdOrUrl: String) {
        val key = if (bundleIdOrUrl.contains("://") ||
            (bundleIdOrUrl.endsWith(":") && !bundleIdOrUrl.contains('.'))
        ) {
            "_urlS"
        } else {
            "_bundleID"
        }
        link.exchange("_launchApp", mapOf(key to bundleIdOrUrl))
    }

    /** Best-effort power/attention snapshot; newer tvOS releases may refuse the query, in which case Unknown is returned. */
    fun fetchAttention(): AttentionState = try {
        val content = link.exchange("FetchAttentionState", emptyMap())["_c"] as? Map<*, *>
        AttentionState.fromCode((content?.get("state") as? Long)?.toInt())
    } catch (_: CompanionException) {
        AttentionState.Unknown
    }
}
