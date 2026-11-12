package com.custom.astrion.web

import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.LocalTemplates
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import kotlinx.coroutines.runBlocking
import org.json.JSONObject

/**
 * The builder's preview of an info card (1.5.6): a Home Assistant template rendered once.
 *
 *  POST /ha-template   `template` → {result} or {error}: a simple one filled here ([LocalTemplates]), the
 *                      others by Home Assistant; 503 while it isn't connected
 *  GET  /ha-image      `path` (a Home Assistant `/api/…` path: a media player's picture) → the image,
 *                      fetched with the remote's token, which never reaches the page
 */
class TemplateRoutes(private val haClient: HaClient) {
    /** The response for a template route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/ha-template" -> if (session.method == Method.POST) render(session) else methodNotAllowed()
        "/ha-image" -> if (session.method == Method.GET) image(session) else methodNotAllowed()
        else -> null
    }

    private fun render(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val template = session.parameters["template"]?.firstOrNull().orEmpty()
        if (template.isBlank() || template.length > MAX_LENGTH) return error(Response.Status.BAD_REQUEST, "invalid template")
        LocalTemplates.render(template, haClient.allEntities())?.let {
            return json(Response.Status.OK, JSONObject().put("result", it))
        }
        val answer =
            runBlocking { haClient.templates.renderOnce(template) }
                ?: return error(Response.Status.SERVICE_UNAVAILABLE, "Home Assistant isn't connected")
        val body = JSONObject()
        answer.error?.let { body.put("error", it) } ?: body.put("result", answer.text.orEmpty())
        return json(Response.Status.OK, body)
    }

    private fun image(session: IHTTPSession): Response {
        val path = session.parameters["path"]?.firstOrNull().orEmpty()
        if (!path.startsWith("/api/") || ".." in path) return error(Response.Status.BAD_REQUEST, "invalid path")
        val bytes = runBlocking { haClient.fetchBytes(path) } ?: return error(Response.Status.NOT_FOUND, "no image")
        val png = bytes.size > 3 && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte()
        val response = NanoHTTPD.newFixedLengthResponse(
            Response.Status.OK,
            if (png) "image/png" else "image/jpeg",
            bytes.inputStream(),
            bytes.size.toLong()
        )
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun methodNotAllowed() = error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private companion object {
        const val MAX_LENGTH = 4000
    }
}
