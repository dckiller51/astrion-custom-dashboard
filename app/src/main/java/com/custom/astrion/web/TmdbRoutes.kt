package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.tmdb.TmdbClient
import com.custom.astrion.tmdb.TmdbMatch
import com.custom.astrion.tmdb.TmdbRefused
import com.custom.astrion.tmdb.tmdbLanguageOf
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.IOException
import org.json.JSONObject

/**
 * The web page's TMDB part (1.5.9), kept out of [ConfigServer]:
 *
 *  GET  /tmdb        {configured, language, effectiveLanguage}: never the token itself
 *  POST /tmdb        token (empty: removes it; absent: keeps it), language; restarts TMDB
 *  POST /tmdb-test   title → what TMDB finds for it with the saved (or given) token
 */
class TmdbRoutes(private val context: Context, private val onChanged: () -> Unit) {
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/tmdb" -> when (session.method) {
            Method.GET -> status()
            Method.POST -> save(session)
            else -> error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        "/tmdb-test" -> if (session.method == Method.POST) {
            test(
                session
            )
        } else {
            error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        else -> null
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private fun status(): Response = json(
        Response.Status.OK,
        JSONObject()
            .put("configured", RemoteSettings.tmdbToken(context).isNotBlank())
            .put("language", RemoteSettings.tmdbLanguage(context))
            .put("effectiveLanguage", tmdbLanguageOf(context))
    )

    private fun params(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun save(session: IHTTPSession): Response {
        val p = params(session)
        val language = p["language"].orEmpty()
        if (language.isNotEmpty() && !LANGUAGE.matches(language)) return error(Response.Status.BAD_REQUEST, "invalid language")
        RemoteSettings.saveTmdb(context, p["token"], language)
        onChanged()
        return status()
    }

    private fun test(session: IHTTPSession): Response {
        val p = params(session)
        val token = p["token"].orEmpty().ifBlank { RemoteSettings.tmdbToken(context) }
        if (token.isBlank()) return error(Response.Status.BAD_REQUEST, "no token")
        val q = TmdbMatch.clean(p["title"].orEmpty()) ?: return error(Response.Status.BAD_REQUEST, "no title")
        return try {
            val info = TmdbClient(token).search(q, tmdbLanguageOf(context))
            val body = JSONObject().put("found", info != null)
            info?.let {
                body.put("title", it.title).put("year", it.year ?: JSONObject.NULL).put("rating", it.rating ?: JSONObject.NULL)
                    .put("overview", it.overview.orEmpty()).put("poster", it.posterUrl.orEmpty())
            }
            json(Response.Status.OK, body)
        } catch (e: TmdbRefused) {
            error(Response.Status.UNAUTHORIZED, "token refused (HTTP ${e.code})")
        } catch (e: IOException) {
            error(Response.Status.SERVICE_UNAVAILABLE, "TMDB didn't answer: ${e.message}")
        }
    }

    private companion object {
        val LANGUAGE = Regex("^[a-z]{2}(-[A-Z]{2})?$")
    }
}
