package com.custom.astrion.androidtv

import java.io.BufferedReader
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/** What the Astrion TV Helper on the TV says is playing (1.3.6); see tvhelper's HelperRoutes. */
data class AndroidTvHelperInfo(
    val version: Long,
    val packageName: String?,
    val state: String,
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    val positionMs: Long? = null,
    val positionUpdatedAt: Long? = null,
    /** Set when the helper has a cover image (`/art`); changes with it. */
    val artVersion: Long? = null,
    /** A cover only available as a web address. */
    val artUrl: String? = null
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Parses `/now-playing`; null when it isn't the helper's answer. */
        fun parse(text: String): AndroidTvHelperInfo? = runCatching {
            val o = json.parseToJsonElement(text).jsonObject
            fun str(k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull?.takeIf { it.isNotBlank() }
            fun long(k: String) = (o[k] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }
            AndroidTvHelperInfo(
                version = long("version") ?: return null,
                packageName = str("package"),
                state = str("state") ?: "none",
                title = str("title"),
                subtitle = str("subtitle"),
                artist = str("artist"),
                album = str("album"),
                durationMs = long("durationMs"),
                positionMs = long("positionMs"),
                positionUpdatedAt = long("positionUpdatedAt"),
                artVersion = long("artVersion"),
                artUrl = str("artUrl")
            )
        }.getOrNull()

        /** True when `/status` comes from the helper. */
        fun isHelperStatus(text: String): Boolean = runCatching {
            (json.parseToJsonElement(text) as? JsonObject)?.get("app")?.let { (it as? JsonPrimitive)?.contentOrNull } == "astrion-tv-helper"
        }.getOrDefault(false)
    }
}

/** How the link to the helper stands, shown on the web page (`astrion_helper` attribute). */
enum class HelperLink(val attribute: String) {
    NONE("none"),
    CONNECTED("connected"),
    UNREACHABLE("unreachable"),
    WRONG_CODE("wrong_code")
}

/**
 * Talks to the Astrion TV Helper on one TV (port 6470): follows what's playing with a long poll
 * (the helper answers as soon as something changes, or every [WAIT_S] s), fetches the cover when
 * it changes, and opens apps / sends media actions on request. Its own daemon thread, like
 * [AndroidTvConnection]; never throws.
 */
class AndroidTvHelperClient(
    private val host: () -> String,
    private val code: String,
    private val onUpdate: (HelperLink, AndroidTvHelperInfo?, ByteArray?) -> Unit,
    private val log: (String) -> Unit,
    private val port: Int = PORT
) {
    @Volatile private var running = false
    private var thread: Thread? = null

    // A stopped thread may still sit in a long poll (a socket read isn't interrupted); a quick
    // restart then starts a new one, and the old one sees a newer generation and exits (1.4.0).
    @Volatile private var generation = 0

    @Volatile var link: HelperLink = HelperLink.NONE
        private set

    fun start() {
        if (running || code.isBlank()) return
        running = true
        val gen = ++generation
        thread = Thread({ loop(gen) }, "AndroidTv-helper").apply { isDaemon = true }.also { it.start() }
    }

    /** Stops polling (the remote resting, too); the wake channel stays open for the next Power. */
    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    /** Stops for good (the TV removed, settings reloaded): the wake channel too. */
    fun shutdown() {
        stop()
        synchronized(channelLock) { closeChannel() }
    }

    // ---- wake channel (1.5.7, helper 1.1.2, port 6471) ------------------------------------------
    // A connection opened while the TV is awake and kept through its sleep: a box that Wake-on-LAN
    // only brings up for a second (Mi Box S) can't take a new connection in that time, but reads a
    // line already sent on an open one (TCP resends it) at once.

    private class Channel(val socket: Socket, val reader: BufferedReader)

    private val channelLock = Any()
    private var channel: Channel? = null
    private var channelTriedAt = 0L

    /** Opens the wake channel if there's none (at most every [CHANNEL_RETRY_MS]); called while the TV answers. */
    private fun openWakeChannel() = synchronized(channelLock) {
        if (channel != null || System.currentTimeMillis() - channelTriedAt < CHANNEL_RETRY_MS) return@synchronized
        channelTriedAt = System.currentTimeMillis()
        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host(), WAKE_PORT), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            socket.soTimeout = CHANNEL_HELLO_MS
            val reader = socket.getInputStream().bufferedReader()
            socket.getOutputStream().apply {
                write("$code\n".toByteArray())
                flush()
            }
            val hello = reader.readLine()
            if (hello != "OK") throw IOException("refused ($hello)")
            socket.soTimeout = 0
            channel = Channel(socket, reader)
            log("helper wake channel open")
        } catch (e: IOException) {
            runCatching { socket.close() }
            log("helper wake channel unavailable (helper 1.1.2 needed): ${e.message}")
        }
    }

    /**
     * Asks for the screen over the wake channel: true / false = the screen's state afterwards, null
     * when there's no channel or it broke. Waits up to [waitMs], the time Wake-on-LAN takes to
     * bring the box up for TCP's resend to get through.
     */
    fun wakeViaChannel(waitMs: Int): Boolean? = synchronized(channelLock) {
        val c = channel ?: return@synchronized null
        try {
            c.socket.soTimeout = waitMs
            c.socket.getOutputStream().apply {
                write("WAKE\n".toByteArray())
                flush()
            }
            when (val answer = c.reader.readLine()) {
                "ON" -> true
                "OFF" -> false
                else -> throw IOException("answered $answer")
            }.also { c.socket.soTimeout = 0 }
        } catch (e: IOException) {
            log("helper wake channel: ${e.message}")
            closeChannel()
            null
        }
    }

    private fun closeChannel() {
        channel?.let { runCatching { it.socket.close() } }
        channel = null
    }

    // Poll state, only touched by the client's own thread.
    private var version = 0L
    private var artVersion: Long? = null
    private var art: ByteArray? = null

    private fun loop(gen: Int) {
        var failures = 0
        while (running && gen == generation) {
            try {
                poll()
                failures = 0
            } catch (e: WrongCodeException) {
                report(HelperLink.WRONG_CODE, null, null)
                log("helper at ${host()} refused the code: link it again on the web page (${e.message})")
                sleep(WRONG_CODE_RETRY_MS)
            } catch (e: IOException) {
                if (link == HelperLink.CONNECTED) log("helper at ${host()} lost: ${e.message}")
                report(HelperLink.UNREACHABLE, null, null)
                version = 0
                sleep(BACKOFF_MS[minOf(failures++, BACKOFF_MS.lastIndex)])
            }
        }
    }

    /** One long poll: returns once the helper reports a change (or after its wait), and passes it on. */
    private fun poll() {
        val text = request("GET", "/now-playing?since=$version&wait=$WAIT_S", READ_TIMEOUT_MS)
        val info = AndroidTvHelperInfo.parse(text) ?: throw IOException("not the helper's answer")
        if (info.artVersion != artVersion) {
            art = info.artVersion?.let { runCatching { requestBytes("/art") }.getOrNull() }
            artVersion = info.artVersion
        }
        if (link != HelperLink.CONNECTED) log("helper connected at ${host()}")
        version = info.version
        report(HelperLink.CONNECTED, info, art)
        openWakeChannel()
    }

    private fun report(newLink: HelperLink, info: AndroidTvHelperInfo?, art: ByteArray?) {
        link = newLink
        onUpdate(newLink, info, art)
    }

    private fun sleep(ms: Long) = try {
        Thread.sleep(ms)
    } catch (_: InterruptedException) {
        running = false
    }

    /** Opens [packageName] on the TV through the helper; false when it couldn't. */
    fun launch(packageName: String): Boolean = command("/launch?package=${enc(packageName)}")

    /** Sends a media [action] (play, pause, play_pause, stop, next, previous, seek); false when it couldn't. */
    fun media(action: String, positionMs: Long? = null): Boolean =
        command("/media?action=${enc(action)}" + (positionMs?.let { "&position=$it" } ?: ""))

    /**
     * Asks the helper to turn the screen on (1.5.7, helper 1.1.0 with "Stay reachable in standby").
     * Tried even while the link isn't up (polls stop while the remote rests); an older helper
     * answers 404, quietly ignored.
     */
    fun wake(): Boolean? = try {
        // Short tries: a box woken by Wake-on-LAN is only up for about a second each time.
        screenOnOf(request("POST", "/wake", WAKE_READ_MS, WAKE_CONNECT_MS)) ?: false
    } catch (e: WrongCodeException) {
        log("helper wake refused: ${e.message}")
        false
    } catch (e: IOException) {
        // An older helper: HTTP 404, an answer all the same. No answer at all: the box sleeps.
        if (e.message?.startsWith("HTTP ") == true) false else null
    }

    /** Whether the TV's screen is on, as the helper (1.1.0) says; null when it doesn't answer or doesn't say. */
    fun screenOn(): Boolean? = runCatching { screenOnOf(request("GET", "/status", STATUS_TIMEOUT_MS, WAKE_CONNECT_MS)) }.getOrNull()

    private fun command(path: String): Boolean = link == HelperLink.CONNECTED && try {
        request("POST", path, COMMAND_TIMEOUT_MS)
        true
    } catch (e: IOException) {
        log("helper command $path failed: ${e.message}")
        false
    }

    private fun open(method: String, path: String, timeoutMs: Int, connectMs: Int = CONNECT_TIMEOUT_MS): HttpURLConnection {
        val c = URL("http://${host()}:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = connectMs
        c.readTimeout = timeoutMs
        c.setRequestProperty(CODE_HEADER, code)
        c.useCaches = false
        if (method == "POST") {
            c.doOutput = true
            c.setFixedLengthStreamingMode(0)
        }
        when (val status = c.responseCode) {
            HttpURLConnection.HTTP_OK -> return c
            HttpURLConnection.HTTP_UNAUTHORIZED -> throw WrongCodeException("HTTP $status")
            else -> {
                val body = c.errorStream?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
                c.disconnect()
                throw IOException("HTTP $status $body")
            }
        }
    }

    private fun request(method: String, path: String, timeoutMs: Int, connectMs: Int = CONNECT_TIMEOUT_MS): String {
        val c = open(method, path, timeoutMs, connectMs)
        return try {
            c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
        } finally {
            c.disconnect()
        }
    }

    private fun requestBytes(path: String): ByteArray {
        val c = open("GET", path, COMMAND_TIMEOUT_MS)
        return try {
            c.inputStream.use { it.readBytes() }
        } finally {
            c.disconnect()
        }
    }

    private class WrongCodeException(message: String) : IOException(message)

    companion object {
        const val PORT = 6470
        const val CODE_HEADER = "X-Astrion-Code"
        private const val WAIT_S = 25
        private const val READ_TIMEOUT_MS = (WAIT_S + 10) * 1000
        private const val CONNECT_TIMEOUT_MS = 4_000
        private const val COMMAND_TIMEOUT_MS = 5_000
        private const val STATUS_TIMEOUT_MS = 1_500
        private const val WAKE_CONNECT_MS = 700
        private const val WAKE_PORT = 6471
        private const val CHANNEL_HELLO_MS = 3_000
        private const val CHANNEL_RETRY_MS = 60_000L
        private const val WAKE_READ_MS = 3_000
        private val screenOnJson = Json { ignoreUnknownKeys = true }

        /** The `screenOn` of a helper answer (`/status`, `/wake`); null when it hasn't one. */
        fun screenOnOf(text: String): Boolean? = runCatching {
            ((screenOnJson.parseToJsonElement(text) as? JsonObject)?.get("screenOn") as? JsonPrimitive)?.booleanOrNull
        }.getOrNull()
        private const val WRONG_CODE_RETRY_MS = 60_000L
        private val BACKOFF_MS = longArrayOf(5_000, 10_000, 30_000, 60_000)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /**
         * Checks [code] against the helper at [host] for the web page: null when it's right, else why
         * not (no helper there, wrong code). Blocking.
         */
        fun check(host: String, code: String, port: Int = PORT): String? {
            return try {
                val status = fetch("http://$host:$port/status", null)
                if (!AndroidTvHelperInfo.isHelperStatus(status)) return "no Astrion TV Helper at $host"
                fetch("http://$host:$port/now-playing", code)
                null
            } catch (_: WrongCodeException) {
                "wrong code"
            } catch (e: IOException) {
                "no Astrion TV Helper at $host (${e.message})"
            }
        }

        private fun fetch(url: String, code: String?): String {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = CONNECT_TIMEOUT_MS
            c.readTimeout = COMMAND_TIMEOUT_MS
            code?.let { c.setRequestProperty(CODE_HEADER, it) }
            try {
                if (c.responseCode == HttpURLConnection.HTTP_UNAUTHORIZED) throw WrongCodeException("HTTP 401")
                if (c.responseCode != HttpURLConnection.HTTP_OK) throw IOException("HTTP ${c.responseCode}")
                return c.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            } finally {
                c.disconnect()
            }
        }
    }
}
