package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.DenonConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.denon.DenonClient
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Denon / Marantz part (1.5.4), kept out of [ConfigServer]:
 *
 *  POST /denon-check   host → {model, port} when a receiver's web API answers there
 *
 * Receivers are saved with the rest of the connection settings (POST /save-connection,
 * `denon_*[]` rows, see [saveRows]) and listed in GET /devices-config (`denons`, see [configJson]).
 */
class DenonRoutes(private val context: Context) {
    /** The response for a Denon route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/denon-check" -> if (session.method == Method.POST) {
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
        val (port, model) =
            DenonClient.check(host) ?: return error(Response.Status.SERVICE_UNAVAILABLE, "no Denon / Marantz receiver answers at $host")
        return json(Response.Status.OK, JSONObject().put("port", port).put("model", model))
    }

    /** The receivers for GET /devices-config. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.denons(context).forEach { b ->
            put(
                JSONObject()
                    .put("localId", b.localId)
                    .put("name", b.name)
                    .put("entityId", b.entityId)
                    .put("host", b.host)
                    .put("port", b.port)
                    .put("inputNames", JSONObject(b.inputNames))
                    .put("hiddenInputs", JSONArray(b.hiddenInputs.toList()))
            )
        }
    }

    /**
     * Saves the `denon_*[]` rows of POST /save-connection — only when the form says it carries them
     * (`denon_present=1`), so an older page can't wipe the receivers. Entity ids are made unique.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["denon_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["denon_$name[]"].orEmpty()
        val localIds = col("localid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val ports = col("port")
        val inputs = col("inputs")
        val used = HashSet<String>()
        val receivers =
            localIds.indices.mapNotNull { i ->
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!HOST.matches(host)) return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Receiver" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "receiver" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.denon_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.denon_${slug}_${suffix++}"
                DenonConfig(
                    localId = localIds[i].trim().takeIf { LOCAL_ID.matches(it) } ?: slug,
                    name = name,
                    entityId = entityId,
                    host = host,
                    port = ports.getOrNull(i)?.toIntOrNull() ?: DEFAULT_PORT,
                    inputNames = inputNames(inputs.getOrNull(i)),
                    hiddenInputs = hiddenInputs(inputs.getOrNull(i))
                )
            }
        RemoteSettings.saveDenons(context, receivers)
    }

    /** A row's `denon_inputs[]`: `{"names": {code: name}, "hidden": [code]}`, as the page writes it. */
    private fun inputsJson(raw: String?): JSONObject? = raw?.takeIf { it.isNotBlank() }?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun inputNames(raw: String?): Map<String, String> {
        val names = inputsJson(raw)?.optJSONObject("names") ?: return emptyMap()
        return names.keys().asSequence().mapNotNull { k ->
            names.optString(k).trim().take(MAX_NAME).takeIf { it.isNotEmpty() }?.let { k to it }
        }.toMap()
    }

    private fun hiddenInputs(raw: String?): Set<String> {
        val hidden = inputsJson(raw)?.optJSONArray("hidden") ?: return emptySet()
        return (0 until hidden.length()).mapNotNull { hidden.optString(it).takeIf { c -> c.isNotEmpty() } }.toSet()
    }

    /**
     * Adds (or updates) a `haDevices` catalog entry per receiver in [rawJson], as for the other direct
     * devices, so the builder's pickers list it. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val receivers = RemoteSettings.denons(context)
        if (receivers.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (b in receivers) {
            val id = "denon_${b.localId}"
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
        private const val DEFAULT_PORT = 8080
        private const val MAX_NAME = 40
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val ENTITY = Regex("^media_player\\.[a-z0-9_]+$")
        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
