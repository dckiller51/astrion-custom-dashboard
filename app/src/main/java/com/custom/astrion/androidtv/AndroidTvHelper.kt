package com.custom.astrion.androidtv

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    @Volatile var link: HelperLink = HelperLink.NONE
        private set

    fun start() {
        if (running || code.isBlank()) return
        running = true
        thread = Thread({ loop() }, "AndroidTv-helper").apply { isDaemon = true }.also { it.start() }
    }

    fun stop() {
        running = false
        thread?.interrupt()
        thread = null
    }

    // Poll state, only touched by the client's own thread.
    private var version = 0L
    private var artVersion: Long? = null
    private var art: ByteArray? = null

    private fun loop() {
        var failures = 0
        while (running) {
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

    private fun command(path: String): Boolean = link == HelperLink.CONNECTED && try {
        request("POST", path, COMMAND_TIMEOUT_MS)
        true
    } catch (e: IOException) {
        log("helper command $path failed: ${e.message}")
        false
    }

    private fun open(method: String, path: String, timeoutMs: Int): HttpURLConnection {
        val c = URL("http://${host()}:$port$path").openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = CONNECT_TIMEOUT_MS
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

    private fun request(method: String, path: String, timeoutMs: Int): String {
        val c = open(method, path, timeoutMs)
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
