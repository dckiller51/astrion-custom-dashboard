package com.custom.astrion.playnite

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The Astrion Custom Dashboard extension for Playnite on one PC (1.6.3): a small HTTP API on
 * port 8095. Every call but /info carries the code shown in Playnite. Blocking: callers run it off the main thread.
 */
class PlayniteClient(private val host: String, private val port: Int, private val code: String) {
    /** Playnite answered, but not to this code. */
    class CodeRefused : IOException("code refused")

    private fun url(path: String) = "http://$host:$port$path"

    private fun call(request: Request): String = http.newCall(request).execute().use { resp ->
        val text = resp.body?.string().orEmpty()
        if (resp.code == UNAUTHORIZED) throw CodeRefused()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}: $text")
        text
    }

    private fun get(path: String, withCode: Boolean = true): String {
        val b = Request.Builder().url(url(path))
        if (withCode) b.header(PlayniteProtocol.CODE_HEADER, code)
        return call(b.build())
    }

    private fun post(path: String): String =
        call(Request.Builder().url(url(path)).header(PlayniteProtocol.CODE_HEADER, code).post(ByteArray(0).toRequestBody(null)).build())

    fun info(): PlayniteProtocol.Info? = PlayniteProtocol.info(get("/info", withCode = false))

    /** The game running (null: none). Throws when Playnite doesn't answer, [CodeRefused] when the code is wrong. */
    fun running(): PlayniteGame? = PlayniteProtocol.running(get("/state"))

    /** Switches Playnite to "fullscreen" or "desktop" (extension 0.1.3+): it restarts in that mode. */
    fun switchMode(mode: String) {
        post("/mode/$mode")
    }

    /** GET /state as it is: the game running and whether Windows is locked (see [PlayniteProtocol]). */
    fun stateText(): String = get("/state")

    fun games(): List<PlayniteGame>? = PlayniteProtocol.games(get("/games"))

    /** A game's cover as Playnite has it (no code needed); null when it has none or it's too big. */
    fun cover(gameId: String): ByteArray? = http.newCall(Request.Builder().url(url("/cover/$gameId")).build()).execute().use { resp ->
        val body = resp.body ?: return null
        if (!resp.isSuccessful || body.contentLength() > MAX_COVER_BYTES) return null
        body.bytes().takeIf { it.size <= MAX_COVER_BYTES }
    }

    /** A game's sheet (extension 0.1.2+); null when it doesn't know it, or the extension is older. */
    fun details(gameId: String): PlayniteDetails? = runCatching { PlayniteSheet.details(get("/game/$gameId")) }
        .onFailure { if (it is CodeRefused) throw it }
        .getOrNull()

    fun start(gameId: String) {
        post("/start/$gameId")
    }

    /** "sleep", "shutdown" or "restart". */
    fun power(action: String) {
        post("/power/$action")
    }

    companion object {
        private const val UNAUTHORIZED = 401
        private const val MAX_COVER_BYTES = 8L * 1024 * 1024

        private val http: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
