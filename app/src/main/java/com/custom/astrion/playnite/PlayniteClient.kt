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

    fun games(): List<PlayniteGame>? = PlayniteProtocol.games(get("/games"))

    fun start(gameId: String) {
        post("/start/$gameId")
    }

    /** "sleep", "shutdown" or "restart". */
    fun power(action: String) {
        post("/power/$action")
    }

    companion object {
        private const val UNAUTHORIZED = 401

        private val http: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(3, TimeUnit.SECONDS)
                .readTimeout(8, TimeUnit.SECONDS)
                .build()
        }
    }
}
