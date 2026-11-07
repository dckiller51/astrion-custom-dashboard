package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SkyConfig
import com.custom.astrion.sky.SkyClient
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Sky part (1.5.3), kept out of [ConfigServer]:
 *
 *  POST /sky-check   host → {kind: "skyq" | "skyhd", model} when a Sky box answers there
 *
 * Boxes are saved with the rest of the connection settings (POST /save-connection, `sky_*[]`
 * rows, see [saveRows]) and listed in GET /devices-config (`skys`, see [configJson]).
 */
class SkyRoutes(private val context: Context) {
    /** The response for a Sky route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/sky-check" -> if (session.method == Method.POST) {
            check(
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

    private fun check(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val host = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        if (!HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "invalid address")
        val kind = SkyClient.check(host) ?: return error(Response.Status.SERVICE_UNAVAILABLE, "no Sky box answers at $host")
        val model = if (kind == "skyq") SkyClient({ host }, true).deviceInfo()?.model.orEmpty() else ""
        return json(Response.Status.OK, JSONObject().put("kind", kind).put("model", model))
    }

    /** The boxes for GET /devices-config. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.skys(context).forEach { b ->
            put(
                JSONObject()
                    .put("localId", b.localId)
                    .put("name", b.name)
                    .put("entityId", b.entityId)
                    .put("host", b.host)
                    .put("kind", if (b.skyQ) "skyq" else "skyhd")
            )
        }
    }

    /**
     * Saves the `sky_*[]` rows of POST /save-connection — only when the form says it carries them
     * (`sky_present=1`), so an older page can't wipe the boxes. Entity ids are made unique.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["sky_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["sky_$name[]"].orEmpty()
        val localIds = col("localid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val kinds = col("kind")
        val used = HashSet<String>()
        val boxes =
            localIds.indices.mapNotNull { i ->
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!HOST.matches(host)) return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Sky" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "box" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.sky_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.sky_${slug}_${suffix++}"
                SkyConfig(
                    localId = localIds[i].trim().takeIf { LOCAL_ID.matches(it) } ?: slug,
                    name = name,
                    entityId = entityId,
                    host = host,
                    skyQ = kinds.getOrNull(i) != "skyhd"
                )
            }
        RemoteSettings.saveSkys(context, boxes)
    }

    /**
     * Adds (or updates) a `haDevices` catalog entry per box in [rawJson], as for the other direct
     * devices, so the builder's pickers list it. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val boxes = RemoteSettings.skys(context)
        if (boxes.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (b in boxes) {
            val id = "sky_${b.localId}"
            val existing = byId[id]
            if (existing == null) {
                haDevices.put(JSONObject().put("id", id).put("domain", "media_player").put("entityId", b.entityId).put("name", b.name))
                changed = true
            } else if (existing.optString("entityId") != b.entityId || existing.optString("name") != b.name) {
                existing.put("entityId", b.entityId).put("name", b.name)
                changed = true
            }
        }
        if (!changed) return rawJson
        root.put("haDevices", haDevices)
        val result = root.toString()
        runCatching { DashboardLoader.configFile.writeText(result) }
        return result
    }

    companion object {
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val ENTITY = Regex("^media_player\\.[a-z0-9_]+$")
        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
