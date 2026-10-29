package com.custom.astrion.androidtv

import java.io.IOException
import java.io.OutputStream
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket

/** What the TV reports, as last heard. Null fields: not known (yet). */
data class AndroidTvState(
    val connected: Boolean = false,
    /** True when the TV refuses this remote: it must be paired again. */
    val needsPairing: Boolean = false,
    val isOn: Boolean? = null,
    /** Package of the app in the foreground ("com.netflix.ninja"…). */
    val currentApp: String? = null,
    val volumeLevel: Int? = null,
    val volumeMax: Int? = null,
    val muted: Boolean? = null,
    val model: String? = null,
    val vendor: String? = null
)

/**
 * The control connection to one paired Android TV (port 6466), kept open on its own thread and
 * reopened when it drops — the TV pings every few seconds, so silence means the link is gone.
 * Sending is non-blocking for the caller and silently dropped while disconnected (logged).
 */
class AndroidTvConnection(
    private val host: () -> String,
    private val identity: AndroidTvIdentity,
    private val certSha256: String?,
    private val onState: (AndroidTvState) -> Unit,
    private val log: (String) -> Unit = {},
    private val port: Int = CONTROL_PORT,
    /** Called on the connection thread after each failed attempt, with the count so far
     * (lets the owner look the TV up again by mDNS when its IP may have changed). */
    private val onConnectFailed: (Int) -> Unit = {}
) {
    @Volatile private var running = false

    // Quiet (1.4.1): a TV that doesn't answer (off, asleep) is tried every 10 minutes instead of
    // every 30 s, which woke the remote all night; back on screen, it's tried again at once.
    @Volatile private var quiet = false

    @Volatile private var socket: SSLSocket? = null
    private var thread: Thread? = null
    private val writeLock = Any()

    /** What we use: what we want among what the TV said it supports (its configure). */
    @Volatile private var features = RemoteMessages.WANTED_FEATURES

    /** What the TV offered in its last configure (0 until then). */
    @Volatile private var tvFeatures = 0

    @Volatile var state = AndroidTvState()
        private set

    fun start() {
        if (running) return
        running = true
        thread = Thread(::runLoop, "AndroidTv-${host()}").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
    }

    fun setQuiet(quiet: Boolean) {
        this.quiet = quiet
        if (!quiet && !state.connected) thread?.interrupt() // ends the wait: try again now
    }

    /** Presses a key ([direction]: short press by default, or start/end of a long press). */
    fun sendKey(keyCode: Int, direction: Int = RemoteMessages.DIRECTION_SHORT) = send(RemoteMessages.keyInject(keyCode, direction))

    /** Opens an app (`com.netflix.ninja`) or a link (`https://…`, `market://…`). */
    fun launch(appOrLink: String) {
        val link = AndroidTvApps.linkFor(appOrLink)
        // A TV that didn't offer app links answers one with an error and drops the connection.
        if (tvFeatures != 0 && (tvFeatures and RemoteMessages.FEATURE_APP_LINK) == 0) {
            log("${host()} doesn't accept app launches, $link not sent. $APP_LINK_ADVICE")
            return
        }
        log("opening $link on ${host()}")
        send(RemoteMessages.appLinkLaunch(link))
    }

    private fun send(message: ByteArray): Boolean {
        val out: OutputStream = socket?.takeIf { state.connected }?.outputStream ?: run {
            log("not connected to ${host()}, command dropped")
            return false
        }
        return try {
            synchronized(writeLock) { ProtoWriter.writeDelimited(out, message) }
            true
        } catch (e: IOException) {
            log("send to ${host()} failed: ${e.message}")
            runCatching { socket?.close() }
            false
        }
    }

    private fun update(change: (AndroidTvState) -> AndroidTvState) {
        val next = change(state)
        if (next != state) {
            state = next
            onState(next)
        }
    }

    private fun runLoop() {
        var failures = 0
        while (running) {
            try {
                val s = identity.connect(host(), port, CONNECT_TIMEOUT_MS, certSha256)
                s.soTimeout = SILENCE_TIMEOUT_MS // the TV pings every ~5 s: silence means the link is gone
                socket = s
                failures = 0
                update { it.copy(connected = true, needsPairing = false) }
                log("connected to ${host()}")
                readLoop(s)
            } catch (e: SSLHandshakeException) {
                // The TV refuses our certificate (it was reset, or pairing was removed on the TV),
                // or its own certificate isn't the paired one any more.
                if (running) log("${host()} refused this remote: ${e.message}")
                update { it.copy(connected = false, needsPairing = true) }
                failures++
            } catch (e: Exception) {
                if (running) log("connection to ${host()} lost: ${e.message}")
                update { it.copy(connected = false) }
                failures++
            } finally {
                runCatching { socket?.close() }
                socket = null
            }
            if (running && failures > 0) runCatching { onConnectFailed(failures) }
            if (running) sleepQuietly(if (quiet) QUIET_RETRY_MS else backoffMs(failures))
        }
        update { it.copy(connected = false) }
    }

    private fun readLoop(s: SSLSocket) {
        val input = s.inputStream
        while (running) {
            val message = ProtoMessage.parse(ProtoMessage.readDelimited(input))
            handle(message, s.outputStream)
        }
    }

    /** The TV's error names the message it refused (its field number) and carries it back: log both. */
    private fun logError(error: ProtoMessage) {
        val refused = error.bytes(2)
        val field = runCatching { ProtoMessage.parse(refused).toString() }.getOrDefault("?")
        log("${host()} refused message $field: ${refused.joinToString("") { "%02x".format(it) }}")
        val link = runCatching { ProtoMessage.parse(refused).message(APP_LINK_FIELD).string(1) }.getOrDefault("")
        if (link.startsWith("market://")) {
            log(
                "${host()} won't open $link: since a Play Store update (August 2026) many TVs refuse this way of " +
                    "opening an app. Give the app a deep link instead (its https:// or app:// address) in the card or hotkey."
            )
        }
    }

    private fun handle(m: ProtoMessage, out: OutputStream) {
        fun reply(bytes: ByteArray) = synchronized(writeLock) { ProtoWriter.writeDelimited(out, bytes) }
        when {
            m.has(RemoteMessages.CONFIGURE) -> {
                val cfg = m.message(RemoteMessages.CONFIGURE)
                tvFeatures = cfg.int(1)
                features = tvFeatures and RemoteMessages.WANTED_FEATURES
                log("${host()} offers features $tvFeatures, using $features")
                if ((tvFeatures and RemoteMessages.FEATURE_APP_LINK) == 0) log("${host()} doesn't offer app launches. $APP_LINK_ADVICE")
                val info = cfg.message(RemoteMessages.DEVICE_INFO)
                update {
                    it.copy(
                        model = info.string(RemoteMessages.DEVICE_MODEL).ifBlank { null },
                        vendor = info.string(RemoteMessages.DEVICE_VENDOR).ifBlank { null }
                    )
                }
                reply(RemoteMessages.configure(features))
            }
            m.has(RemoteMessages.SET_ACTIVE) -> reply(RemoteMessages.setActive(features))
            m.has(RemoteMessages.PING_REQUEST) -> reply(RemoteMessages.pingResponse(m.message(RemoteMessages.PING_REQUEST).int(1)))
            m.has(RemoteMessages.START) -> update { it.copy(isOn = m.message(RemoteMessages.START).bool(1)) }
            m.has(RemoteMessages.IME_KEY_INJECT) -> {
                val app = m.message(RemoteMessages.IME_KEY_INJECT).message(RemoteMessages.APP_INFO).string(RemoteMessages.APP_PACKAGE)
                if (app.isNotBlank()) update { it.copy(currentApp = app) }
            }
            m.has(RemoteMessages.SET_VOLUME_LEVEL) -> {
                val v = m.message(RemoteMessages.SET_VOLUME_LEVEL)
                update {
                    it.copy(
                        volumeLevel = v.int(RemoteMessages.VOLUME_LEVEL),
                        volumeMax = v.int(RemoteMessages.VOLUME_MAX).takeIf { max -> max > 0 },
                        muted = v.bool(RemoteMessages.VOLUME_MUTED)
                    )
                }
            }
            m.has(RemoteMessages.ERROR) -> logError(m.message(RemoteMessages.ERROR))
        }
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // stop() wakes the thread up to end it.
        }
    }

    companion object {
        const val CONTROL_PORT = 6466
        private const val APP_LINK_FIELD = 90

        /** Home Assistant's library gives the same advice for a TV that stopped offering a feature. */
        private const val APP_LINK_ADVICE =
            "On the TV: Settings > Apps > See all apps > Show system apps > Android TV Remote Service > " +
                "Clear data, then pair it again on the remote's web page."
        private const val CONNECT_TIMEOUT_MS = 5_000
        private const val SILENCE_TIMEOUT_MS = 20_000
        private val BACKOFF_MS = longArrayOf(1_000, 2_000, 5_000, 10_000, 30_000)
        private const val QUIET_RETRY_MS = 10 * 60_000L

        private fun backoffMs(failures: Int): Long = BACKOFF_MS[(failures - 1).coerceIn(0, BACKOFF_MS.size - 1)]
    }
}
