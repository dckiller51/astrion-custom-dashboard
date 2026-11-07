package com.custom.astrion.sky

import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to one Sky box (1.5.3): its remote-key port, and a Sky Q's REST and UPnP services, plus
 * Sky's public guide. Blocking: called from the registry's own threads, never the UI's.
 */
class SkyClient(private val host: () -> String, private val skyQ: Boolean) {
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    /** The SkyPlay control path, found once among the box's UPnP descriptions. */
    @Volatile private var controlPath: String? = null

    @Volatile private var controlTriedAt = 0L

    /** The guide by channel and day, so it's read once a day per channel, not at every look. */
    private val guide = object : LinkedHashMap<String, List<SkyProtocol.Programme>>(GUIDE_CACHE, LOAD, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<SkyProtocol.Programme>>?) = size > GUIDE_CACHE
    }

    // ---- keys -----------------------------------------------------------------------------------

    /** Presses Sky key [name] (see [SkyProtocol.KEYS]); false when the box didn't take it. */
    fun sendKey(name: String): Boolean {
        val code = SkyProtocol.KEYS[name] ?: return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host(), if (skyQ) SkyProtocol.SKY_Q_PORT else SkyProtocol.SKY_HD_PORT), CONNECT_MS)
                socket.soTimeout = KEY_TIMEOUT_MS
                handshakeThenKey(socket, code)
            }
        }.getOrDefault(false)
    }

    /**
     * The box speaks first ("SKY 000.001…"); what it says is sent back (12 bytes, then 1) until
     * it says 24 bytes or more, then the key, pressed and released.
     */
    private fun handshakeThenKey(socket: Socket, code: Int): Boolean {
        val input = socket.getInputStream()
        val output = socket.getOutputStream()
        val buf = ByteArray(BUF)
        var echo = FIRST_ECHO
        repeat(MAX_ROUNDS) {
            val n = input.read(buf)
            if (n <= 0) return false
            if (n < HANDSHAKE_DONE) {
                output.write(buf, 0, minOf(echo, n))
                output.flush()
                echo = 1
            } else {
                val press = SkyProtocol.keyBytes(code)
                output.write(press)
                press[1] = 0
                output.write(press)
                output.flush()
                return true
            }
        }
        return false
    }

    // ---- a Sky Q's state (REST, UPnP) --------------------------------------------------------------

    fun rest(path: String): JsonObject? = text("http://${host()}:${SkyProtocol.REST_PORT}/as/$path")?.let(SkyProtocol::parse)

    /** `system/information`'s standby, or null when the box doesn't answer. */
    fun standby(): Boolean? = rest(SkyProtocol.SYSTEM_INFO)?.let(SkyProtocol::standby)

    fun deviceInfo(): SkyProtocol.DeviceInfo? = rest(SkyProtocol.DEVICE_INFO)?.let(SkyProtocol::deviceInfo)

    fun channels(info: SkyProtocol.DeviceInfo): List<SkyProtocol.Channel> {
        val b = info.bouquet ?: return emptyList()
        return SkyProtocol.channels(rest(SkyProtocol.channelsPath(b, info.subBouquet ?: "1")))
    }

    fun favourites(): List<String> = SkyProtocol.favourites(rest(SkyProtocol.FAVOURITES))

    /** `CurrentTransportState` (PLAYING…), or null. */
    fun transport(): String? = soap("GetTransportInfo")?.let { SkyProtocol.soapValue(it, "CurrentTransportState") }

    fun media(): SkyProtocol.Media = SkyProtocol.mediaOf(soap("GetMediaInfo")?.let { SkyProtocol.soapValue(it, "CurrentURI") })

    private fun soap(method: String): String? {
        val path = control() ?: return null
        val request =
            Request
                .Builder()
                .url("http://${host()}:${SkyProtocol.UPNP_PORT}$path")
                .header("User-Agent", SkyProtocol.USER_AGENT)
                .header("SOAPACTION", SkyProtocol.soapAction(method))
                .post(SkyProtocol.soapBody(method).toRequestBody("text/xml; charset=utf-8".toMediaType()))
                .build()
        val answer = call(request)
        if (answer == null) controlPath = null // the box restarted: its path may have changed
        return answer
    }

    /** The SkyPlay service's control path; looked for again at most every [CONTROL_RETRY_MS]. */
    private fun control(): String? {
        controlPath?.let { return it }
        val now = System.currentTimeMillis()
        if (now - controlTriedAt < CONTROL_RETRY_MS) return null
        controlTriedAt = now
        for (i in 0 until SkyProtocol.DESCRIPTIONS) {
            val xml = text(SkyProtocol.descriptionUrl(host(), i), SkyProtocol.USER_AGENT) ?: continue
            SkyProtocol.controlPath(xml)?.let {
                controlPath = it
                return it
            }
        }
        return null
    }

    // ---- Sky's guide ------------------------------------------------------------------------------

    /** What channel [sid] shows at [nowS], from Sky's guide (read once a day per channel). */
    fun onNow(sid: String, country: String?, nowS: Long): SkyProtocol.Programme? {
        val day = SkyProtocol.today(nowS)
        val key = "$sid/$day"
        val list = synchronized(guide) { guide[key] } ?: schedule(sid, country, day).also { synchronized(guide) { guide[key] = it } }
        return SkyProtocol.onNow(list, nowS)
    }

    private fun schedule(sid: String, country: String?, day: LocalDate): List<SkyProtocol.Programme> {
        val (territory, _) = SkyProtocol.territoryOf(country)
        val request =
            Request
                .Builder()
                .url(SkyProtocol.scheduleUrl(sid, day))
                .header("x-skyott-territory", territory)
                .header("x-skyott-provider", "SKY")
                .header("x-skyott-proposition", "SKYQ")
                .build()
        return SkyProtocol.schedule(call(request)?.let(SkyProtocol::parse))
    }

    // ---- plumbing ----------------------------------------------------------------------------------

    private fun text(url: String, userAgent: String? = null): String? {
        val b = Request.Builder().url(url)
        userAgent?.let { b.header("User-Agent", it) }
        return call(b.build())
    }

    private fun call(request: Request): String? = runCatching {
        http.newCall(request).execute().use { resp -> if (resp.isSuccessful) resp.body?.string() else null }
    }.getOrNull()

    companion object {
        private const val TIMEOUT_S = 4L
        private const val CONNECT_MS = 3_000
        private const val KEY_TIMEOUT_MS = 3_000
        private const val BUF = 64
        private const val FIRST_ECHO = 12
        private const val HANDSHAKE_DONE = 24
        private const val MAX_ROUNDS = 10
        private const val CONTROL_RETRY_MS = 60_000L
        private const val GUIDE_CACHE = 64
        private const val LOAD = 0.75f
        private const val BANNER_MS = 2_000

        /**
         * What answers at [host]: "skyq" (its key port 49160 greets with "SKY"), "skyhd" (5900
         * does), or null. A Sky+ HD's port 5900 is its own, not a VNC server's: those greet "RFB".
         */
        fun check(host: String): String? = when {
            greets(host, SkyProtocol.SKY_Q_PORT) -> "skyq"
            greets(host, SkyProtocol.SKY_HD_PORT) -> "skyhd"
            else -> null
        }

        private fun greets(host: String, port: Int): Boolean = runCatching {
            Socket().use { s ->
                s.connect(InetSocketAddress(host, port), BANNER_MS)
                s.soTimeout = BANNER_MS
                readBanner(s.getInputStream()).startsWith("SKY")
            }
        }.getOrDefault(false)

        private fun readBanner(input: InputStream): String {
            val buf = ByteArray(BUF)
            val n = input.read(buf)
            return if (n > 0) String(buf, 0, n, Charsets.US_ASCII) else ""
        }
    }
}
