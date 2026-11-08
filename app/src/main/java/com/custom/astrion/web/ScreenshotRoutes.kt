package com.custom.astrion.web

import android.content.Context
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's screenshots of the remote (1.5.4), kept out of [ConfigServer]:
 *
 *  POST /screenshot          takes one now ([capture]: the app's screen as PNG) → {name, size}
 *  GET  /screenshots         the ones kept, newest first → {items: [{name, size, time}]}
 *  GET  /screenshot-file     one of them (`name`; `download=1` to save it)
 *  POST /screenshot-delete   deletes one (`name`) or all of them (`all=1`)
 *
 * Kept in the app's own storage, at most [MAX_KEPT] (the oldest go first); not in backups.
 */
class ScreenshotRoutes(private val context: Context, private val capture: () -> ByteArray?) {
    private val dir: File
        get() = File(context.filesDir, "screenshots").apply { mkdirs() }

    /** The response for a screenshot route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/screenshot" -> if (session.method == Method.POST) take() else methodNotAllowed()
        "/screenshots" -> if (session.method == Method.GET) list() else methodNotAllowed()
        "/screenshot-file" -> if (session.method == Method.GET) file(session) else methodNotAllowed()
        "/screenshot-delete" -> if (session.method == Method.POST) delete(session) else methodNotAllowed()
        else -> null
    }

    private fun take(): Response {
        val png = capture() ?: return error(Response.Status.SERVICE_UNAVAILABLE, "the screen couldn't be captured")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(Date())
        var f = File(dir, "astrion-$stamp.png")
        var n = 2
        while (f.exists()) f = File(dir, "astrion-$stamp-${n++}.png")
        f.writeBytes(png)
        shots().drop(MAX_KEPT).forEach { it.delete() }
        return json(Response.Status.OK, JSONObject().put("name", f.name).put("size", f.length()))
    }

    private fun shots(): List<File> = dir.listFiles { f -> NAME.matches(f.name) }.orEmpty().sortedByDescending { it.lastModified() }

    private fun list(): Response {
        val items = JSONArray()
        shots().forEach { items.put(JSONObject().put("name", it.name).put("size", it.length()).put("time", it.lastModified())) }
        return json(Response.Status.OK, JSONObject().put("items", items))
    }

    private fun named(session: IHTTPSession): File? =
        session.parameters["name"]?.firstOrNull()?.takeIf { NAME.matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }

    private fun file(session: IHTTPSession): Response {
        val f = named(session) ?: return error(Response.Status.NOT_FOUND, "no such screenshot")
        val response = NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/png", f.inputStream(), f.length())
        if (session.parameters["download"]?.firstOrNull() == "1") {
            response.addHeader("Content-Disposition", "attachment; filename=\"${f.name}\"")
        }
        response.addHeader("Cache-Control", "no-store")
        return response
    }

    private fun delete(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val deleted =
            if (session.parameters["all"]?.firstOrNull() == "1") {
                shots().count { it.delete() }
            } else {
                if (named(session)?.delete() == true) 1 else 0
            }
        return json(Response.Status.OK, JSONObject().put("deleted", deleted))
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private fun methodNotAllowed() = error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")

    private companion object {
        const val MAX_KEPT = 50
        val NAME = Regex("^astrion-[0-9-]{15,20}\\.png$")
    }
}
