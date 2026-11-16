package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.cards.impl.PlexApi
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.tmdb.MediaInfoSource
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * The web page's Plex server (1.5.9), kept out of [ConfigServer] and out of dashboard.json:
 *
 *  GET  /plex        {configured, host, mediaInfo}: never the token itself
 *  POST /plex        host, token (empty: removes it; absent: keeps it), mediaInfo ("auto", "plex", "tmdb")
 *  POST /plex-test   host, token (else the saved ones) → {ok, machineId} when the server answers
 *  GET  /plex-art    path=/library/… → that picture from the server, the token added here (what's
 *                    playing's poster, see tmdb/PlexNowPlaying: entity attributes never carry the token)
 *
 * A Plex card without its own `host` / `token` uses these. [onChanged]: what's-playing lookups start again.
 */
class PlexRoutes(private val context: Context, private val onChanged: () -> Unit = {}) {
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/plex" -> when (session.method) {
            Method.GET -> status()
            Method.POST -> save(session)
            else -> error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        "/plex-test" -> if (session.method == Method.POST) {
            test(
                session
            )
        } else {
            error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        "/plex-art" -> art(session)
        else -> null
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private fun status(): Response = json(
        Response.Status.OK,
        JSONObject()
            .put("configured", RemoteSettings.plexHost(context).isNotBlank() && RemoteSettings.plexToken(context).isNotBlank())
            .put("host", RemoteSettings.plexHost(context))
            .put("mediaInfo", RemoteSettings.mediaInfoSource(context))
    )

    private fun params(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun save(session: IHTTPSession): Response {
        val p = params(session)
        val host = p["host"].orEmpty().trim()
        if (host.isNotEmpty() && !HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "invalid address")
        RemoteSettings.savePlex(context, host, p["token"])
        p["mediaInfo"]?.let { RemoteSettings.saveMediaInfoSource(context, MediaInfoSource.of(it).id) }
        onChanged()
        return status()
    }

    private fun test(session: IHTTPSession): Response {
        val p = params(session)
        val host = p["host"].orEmpty().trim().ifBlank { RemoteSettings.plexHost(context) }.trimEnd('/')
        val token = p["token"].orEmpty().trim().ifBlank { RemoteSettings.plexToken(context) }
        if (!HOST.matches(host) || token.isBlank()) return error(Response.Status.BAD_REQUEST, "address and token needed")
        val id = runCatching { runBlocking { PlexApi.fetchIdentity(host, token) } }.getOrNull()
            ?: return error(Response.Status.SERVICE_UNAVAILABLE, "no Plex server answers at $host with this token")
        return json(Response.Status.OK, JSONObject().put("ok", true).put("machineId", id))
    }

    /** A picture of the Plex server's library, fetched with the saved token. */
    private fun art(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull().orEmpty()
        val host = RemoteSettings.plexHost(context).trimEnd('/')
        val token = RemoteSettings.plexToken(context)
        val ours = ART_PATH.matches(path) && ".." !in path
        if (!ours || host.isBlank() || token.isBlank()) return error(Response.Status.NOT_FOUND, "no such picture")
        val req = Request.Builder().url(host + path).header("X-Plex-Token", token).build()
        return runCatching {
            artHttp.newCall(req).execute().use { resp ->
                val bytes = resp.body?.bytes()
                if (!resp.isSuccessful || bytes == null) return error(Response.Status.NOT_FOUND, "HTTP ${resp.code}")
                val type = resp.header("Content-Type") ?: "image/jpeg"
                NanoHTTPD.newFixedLengthResponse(Response.Status.OK, type, ByteArrayInputStream(bytes), bytes.size.toLong())
            }
        }.getOrElse { error(Response.Status.SERVICE_UNAVAILABLE, "Plex didn't answer") }
    }

    private val artHttp by lazy { OkHttpClient.Builder().callTimeout(ART_TIMEOUT_S, TimeUnit.SECONDS).build() }

    private companion object {
        const val ART_TIMEOUT_S = 10L
        val ART_PATH = Regex("^/(library|photo)/[\\w/.:%=&?-]+$")
        val HOST = Regex("^https?://[^\\s/]+(:\\d+)?/?$")
    }
}
