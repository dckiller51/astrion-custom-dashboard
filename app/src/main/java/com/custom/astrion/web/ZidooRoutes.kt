package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.ZidooConfig
import com.custom.astrion.zidoo.ZidooClient
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Zidoo part (1.4.6), kept out of [ConfigServer]:
 *
 *  POST /zidoo-check   host → {model, firmware, mac} when a Zidoo answers there (port 9529)
 *
 * Players are saved with the rest of the connection settings (POST /save-connection,
 * `zidoo_*[]` rows, see [saveRows]) and listed in GET /devices-config (`zidoos`, see [configJson]).
 */
class ZidooRoutes(private val context: Context) {
    /** The response for a Zidoo route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/zidoo-check" -> if (session.method == Method.POST) {
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
        val model = ZidooClient {
            host
        }.model() ?: return error(Response.Status.SERVICE_UNAVAILABLE, "no Zidoo answers at $host:${ZidooClient.PORT}")
        return json(
            Response.Status.OK,
            JSONObject()
                .put("model", model.model.orEmpty())
                .put("firmware", model.firmware.orEmpty())
                .put("mac", model.mac.orEmpty())
        )
    }

    /** The players for GET /devices-config. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.zidoos(context).forEach { z ->
            put(
                JSONObject()
                    .put("localId", z.localId)
                    .put("name", z.name)
                    .put("entityId", z.entityId)
                    .put("host", z.host)
                    .put("mac", z.mac)
            )
        }
    }

    /**
     * Saves the `zidoo_*[]` rows of POST /save-connection — only when the form says it carries them
     * (`zidoo_present=1`), so an older page can't wipe the players. Entity ids are made unique.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["zidoo_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["zidoo_$name[]"].orEmpty()
        val localIds = col("localid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val macs = col("mac")
        val used = HashSet<String>()
        val players =
            localIds.indices.mapNotNull { i ->
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!HOST.matches(host)) return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Zidoo" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "player" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.zidoo_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.zidoo_${slug}_${suffix++}"
                val mac = macs.getOrNull(i).orEmpty().trim().lowercase().takeIf { MAC.matches(it) }.orEmpty()
                ZidooConfig(
                    localId = localIds[i].trim().takeIf { LOCAL_ID.matches(it) } ?: (mac.replace(":", "").ifBlank { slug }),
                    name = name,
                    entityId = entityId,
                    host = host,
                    mac = mac
                )
            }
        RemoteSettings.saveZidoos(context, players)
    }

    /**
     * Adds (or updates) a `haDevices` catalog entry per Zidoo in [rawJson], as is done for Apple
     * and Android TVs, so the builder's pickers list it. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val players = RemoteSettings.zidoos(context)
        if (players.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (z in players) {
            val id = "zidoo_${z.localId}"
            val existing = byId[id]
            if (existing == null) {
                haDevices.put(JSONObject().put("id", id).put("domain", "media_player").put("entityId", z.entityId).put("name", z.name))
                changed = true
            } else if (existing.optString("entityId") != z.entityId || existing.optString("name") != z.name) {
                existing.put("entityId", z.entityId).put("name", z.name)
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
        private val MAC = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
