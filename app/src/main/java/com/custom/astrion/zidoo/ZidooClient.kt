package com.custom.astrion.zidoo

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonObject
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Talks to one Zidoo over its control API — plain HTTP GETs on port 9529, no pairing. Blocking:
 * called from the registry's own threads, never the UI's.
 */
class ZidooClient(private val host: () -> String) {
    private val cookies = HashMap<String, List<Cookie>>()

    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            // The player sets a session cookie on its answers; its own app sends it back.
            .cookieJar(
                object : CookieJar {
                    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
                        synchronized(this@ZidooClient.cookies) { this@ZidooClient.cookies[url.host] = cookies }
                    }

                    override fun loadForRequest(url: HttpUrl): List<Cookie> =
                        synchronized(this@ZidooClient.cookies) { this@ZidooClient.cookies[url.host].orEmpty() }
                }
            ).build()

    fun base(): String = "http://${host()}:$PORT/"

    /** GET [path] (with its query) → the JSON answer, or null when the player doesn't answer. */
    fun get(path: String): JsonObject? = runCatching {
        val request = Request.Builder().url(base() + path.trimStart('/')).header("Cache-Control", "no-cache").build()
        http.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@use null
            // Bytes read as UTF-8 (1.4.8): the player says another charset, and a path with an
            // accent ("Séries") came back garbled, so its episodes were never found on the poster wall.
            ZidooParse.parse(resp.body?.bytes()?.toString(Charsets.UTF_8).orEmpty())
        }
    }.getOrNull()

    fun model(): ZidooModel? = ZidooParse.model(get("ZidooControlCenter/getModel"))

    fun sendKey(key: String): Boolean = ZidooParse.ok(get("ZidooControlCenter/RemoteControl/sendkey?key=${enc(key)}"))

    fun video(): ZidooPlayback? = ZidooParse.video(get("ZidooVideoPlay/getPlayStatus"))

    fun music(): ZidooPlayback? = ZidooParse.music(get("ZidooMusicControl/getPlayStatus"))

    fun musicV2(): ZidooPlayback? = ZidooParse.musicV2(get("ZidooMusicControl/v2/getState"))

    /** What plays: the video player, else the music player (v2 first, 1.4.8: an Eversolo says there
     * what a streaming app plays, with its cover), else nothing. */
    fun playback(): ZidooPlayback? = video() ?: musicV2() ?: music()

    fun apps(): Map<String, String> = ZidooParse.apps(get("ZidooControlCenter/Apps/getApps"))

    fun openApp(packageName: String): Boolean = ZidooParse.ok(get("ZidooControlCenter/Apps/openApp?packageName=${enc(packageName)}"))

    fun movieOf(path: String): ZidooMovie? = ZidooParse.movie(get("ZidooPoster/v2/getAggregationOfFile?path=${enc(path)}"))

    fun backdropUrl(movieId: Long): String = base() + "ZidooPoster/v2/getBackdrop?id=$movieId&w=$ART_W&h=$ART_H"

    fun posterUrl(movieId: Long): String = base() + "ZidooPoster/v2/getPoster?id=$movieId&w=$POSTER_W&h=$POSTER_H"

    /**
     * Whether [url] answers with an image (1.4.9): an episode often has no backdrop of its own, so
     * the registry tries the series', then the season's, and keeps the first one that is there.
     */
    fun isImage(url: String): Boolean = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val bytes = resp.body?.bytes() ?: return@use false
            // A missing image is a JSON error with HTTP 200 ({"status":803,…}); the image may come
            // with no image/* type, so its first byte decides.
            val type = resp.header("Content-Type").orEmpty()
            resp.isSuccessful && bytes.isNotEmpty() && (type.startsWith("image/") || bytes[0] != '{'.code.toByte())
        }
    }.getOrDefault(false)

    fun musicArtUrl(musicId: Long, musicType: Int): String =
        base() + "ZidooMusicControl/v2/getImage?id=$musicId&music_type=$musicType&type=4&target=16"

    private fun enc(value: String) = ZidooParse.query(value)

    companion object {
        const val PORT = 9529
        private const val TIMEOUT_S = 3L
        private const val ART_W = 1080
        private const val ART_H = 720
        private const val POSTER_W = 480
        private const val POSTER_H = 720
        private const val WOL_PORT = 9
        private const val MAC_BYTES = 6
        private const val WOL_REPEATS = 16
        private const val HEX = 16

        /** Sends a Wake-on-LAN magic packet for [mac] to the local network's broadcast address. */
        fun wake(mac: String): Boolean = runCatching {
            val bytes = mac.split(':', '-').map { it.toInt(HEX).toByte() }
            require(bytes.size == MAC_BYTES)
            val packet = ByteArray(MAC_BYTES) { 0xFF.toByte() } + List(WOL_REPEATS) { bytes }.flatten()
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.send(DatagramPacket(packet, packet.size, InetAddress.getByName("255.255.255.255"), WOL_PORT))
            }
            true
        }.getOrDefault(false)
    }
}
