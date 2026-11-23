package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.PlayniteConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.playnite.PlayniteClient
import com.custom.astrion.playnite.PlayniteProtocol
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Playnite part (1.6.3), kept out of [ConfigServer]:
 *
 *  POST /playnite-check   host, code → {name, plugin, mac} when the Astrion Custom Dashboard extension answers
 *                         there and takes the code
 *
 * PCs are saved with the rest of the connection settings (POST /save-connection, `playnite_*[]`
 * rows, see [saveRows]) and listed in GET /devices-config (`playnites`, see [configJson]); the code
 * stays on the remote.
 */
class PlayniteRoutes(private val context: Context) {
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/playnite-check" -> if (session.method == Method.POST) {
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
        fun param(name: String) = session.parameters[name]?.firstOrNull()?.trim().orEmpty()
        val host = param("host")
        val code = param("code")
        if (!HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "invalid address")
        if (!CODE.matches(code)) return error(Response.Status.BAD_REQUEST, "the code is the 6 digits shown in Playnite")
        val client = PlayniteClient(host, PlayniteProtocol.DEFAULT_PORT, code)
        val info =
            runCatching { client.info() }.getOrNull()
                ?: return error(
                    Response.Status.SERVICE_UNAVAILABLE,
                    HostNames.unresolved(host)
                        ?: "Playnite doesn't answer at $host (extension installed, Playnite open, Windows firewall allowing it?)"
                )
        return try {
            client.running()
            json(
                Response.Status.OK,
                JSONObject().put("name", info.name).put("plugin", info.plugin).put("mac", info.macs.firstOrNull().orEmpty())
            )
        } catch (_: PlayniteClient.CodeRefused) {
            error(Response.Status.BAD_REQUEST, "Playnite refused this code: see Extensions › Astrion Custom Dashboard › Show the code")
        } catch (e: Exception) {
            error(Response.Status.SERVICE_UNAVAILABLE, e.message ?: "Playnite didn't answer")
        }
    }

    /** The PCs for GET /devices-config, without their code. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.playnites(context).forEach { p ->
            put(
                JSONObject()
                    .put("localId", p.localId)
                    .put("name", p.name)
                    .put("entityId", p.entityId)
                    .put("host", p.host)
                    .put("mac", p.mac)
                    .put("hasCode", p.code.isNotBlank())
            )
        }
    }

    /**
     * Saves the `playnite_*[]` rows of POST /save-connection, only with `playnite_present=1`. A
     * row's code is the one just checked, else the one saved before.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["playnite_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["playnite_$name[]"].orEmpty()
        val before = RemoteSettings.playnites(context).associateBy { it.localId }
        val localIds = col("localid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val codes = col("code")
        val macs = col("mac")
        val used = HashSet<String>()
        val pcs =
            localIds.indices.mapNotNull { i ->
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!HOST.matches(host)) return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "PC" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "pc" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.playnite_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.playnite_${slug}_${suffix++}"
                val localId = localIds[i].trim().takeIf { LOCAL_ID.matches(it) } ?: slug
                val old = before[localId]
                PlayniteConfig(
                    localId = localId,
                    name = name,
                    entityId = entityId,
                    host = host,
                    port = old?.port ?: PlayniteProtocol.DEFAULT_PORT,
                    code = codes.getOrNull(i).orEmpty().trim().takeIf { CODE.matches(it) } ?: old?.code.orEmpty(),
                    // Blank: removed on the page; else a MAC address, or the one saved before.
                    mac = macOf(macs.getOrNull(i)) ?: old?.mac.orEmpty()
                )
            }
        RemoteSettings.savePlaynites(context, pcs)
    }

    /** A row's MAC address: "" when blank, null when not one (then the one saved before stays). */
    private fun macOf(raw: String?): String? = raw?.trim()?.let { if (it.isEmpty()) "" else it.takeIf { m -> MAC.matches(m) } }

    /** A `haDevices` catalog entry per PC, so the builder's pickers list it. */
    fun reconcileHaDevices(rawJson: String): String {
        val pcs = RemoteSettings.playnites(context)
        if (pcs.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (p in pcs) {
            val id = "playnite_${p.localId}"
            val existing = byId[id]
            if (existing == null) {
                haDevices.put(JSONObject().put("id", id).put("domain", "media_player").put("entityId", p.entityId).put("name", p.name))
                changed = true
            } else if (existing.optString("entityId") != p.entityId || existing.optString("name") != p.name) {
                existing.put("entityId", p.entityId).put("name", p.name)
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
        private val CODE = Regex("^[0-9]{6}$")
        private val MAC = Regex("^[0-9a-fA-F]{2}([:-][0-9a-fA-F]{2}){5}$")
    }
}
