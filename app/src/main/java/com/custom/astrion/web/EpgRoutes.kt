package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.epg.EpgChannel
import com.custom.astrion.epg.EpgEntities
import com.custom.astrion.epg.EpgRegistry
import com.custom.astrion.epg.MAX_CHANNELS as EPG_MAX_CHANNELS
import com.custom.astrion.epg.clearEpg
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's TV guide part (1.6.0), kept out of [ConfigServer]:
 *
 *  GET  /epg           {url, channels, fetching, fetched, bytes, shows, error, kept, available}
 *  POST /epg           url (empty: no guide, the one kept removed), channels (XMLTV ids, one per line); starts it again
 *  POST /epg-refresh   downloads the guide again now
 */
class EpgRoutes(
    private val context: Context,
    private val registry: () -> EpgRegistry?,
    private val onChanged: () -> Unit
) {
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/epg" -> when (session.method) {
            Method.GET -> status()
            Method.POST -> save(session)
            else -> error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        "/epg-refresh" -> if (session.method == Method.POST) refresh() else error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        else -> null
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private fun channels(list: List<EpgChannel>, entities: Boolean = false) = JSONArray().apply {
        list.forEach { c ->
            put(
                JSONObject().put("id", c.id).put("name", c.name).apply {
                    if (entities) put("entity", EpgEntities.entityId(c.id))
                }
            )
        }
    }

    private fun status(): Response {
        val r = registry()
        val g = r?.guide
        return json(
            Response.Status.OK,
            JSONObject()
                .put("url", RemoteSettings.epgUrl(context))
                .put("channels", JSONArray(RemoteSettings.epgChannels(context)))
                .put("running", r != null)
                .put("fetching", r?.fetching == true)
                .put("fetched", g?.fetched ?: 0L)
                .put("bytes", r?.bytes ?: 0L)
                .put("shows", g?.showCount ?: 0)
                .put("error", r?.lastError ?: JSONObject.NULL)
                .put("kept", channels(g?.channels.orEmpty(), entities = true))
                .put("available", channels(g?.available.orEmpty()))
        )
    }

    private fun params(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun save(session: IHTTPSession): Response {
        val p = params(session)
        val url = p["url"].orEmpty().trim()
        if (url.isNotEmpty() && !URL.matches(url)) return error(Response.Status.BAD_REQUEST, "invalid address")
        val ids =
            p["channels"]
                .orEmpty()
                .split('\n', ',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(EPG_MAX_CHANNELS)
        RemoteSettings.saveEpg(context, url, ids)
        onChanged()
        // No source: the guide kept on the remote goes too, once the old one has stopped.
        if (url.isEmpty()) clearEpg(context)
        return status()
    }

    private fun refresh(): Response {
        val r = registry() ?: return error(Response.Status.CONFLICT, "no guide set")
        r.refresh()
        return status()
    }

    private companion object {
        val URL = Regex("^https?://\\S+$", RegexOption.IGNORE_CASE)
    }
}
