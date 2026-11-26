package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SonosConfig
import com.custom.astrion.sonos.SonosClient
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Sonos part (1.6.3), kept out of [ConfigServer]:
 *
 *  POST /sonos-discover   [host] → {speakers: [{uuid, name, host, model, lineIn, tv}]}: the household's
 *                         speakers, asked to the one at host, else to the first that answers SSDP
 *
 * Speakers are saved with the rest of the connection settings (POST /save-connection, `sonos_*[]`
 * rows, see [saveRows]) and listed in GET /devices-config (`sonos`, see [configJson]).
 */
class SonosRoutes(private val context: Context) {
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/sonos-discover" -> if (session.method == Method.POST) {
            discover(
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

    private fun discover(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val typed = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        if (typed.isNotEmpty() && !HOST.matches(typed)) return error(Response.Status.BAD_REQUEST, "invalid address")
        val candidates = if (typed.isNotEmpty()) listOf(typed) else runCatching { SonosClient.discover() }.getOrDefault(emptyList())
        if (candidates.isEmpty()) {
            return error(
                Response.Status.SERVICE_UNAVAILABLE,
                "no Sonos speaker answered on the network: type one's IP address (Sonos app › Settings › System › About)"
            )
        }
        val household =
            candidates.firstNotNullOfOrNull { h -> runCatching { SonosClient.speakers(h) }.getOrNull()?.takeIf { it.isNotEmpty() } }
                ?: return error(
                    Response.Status.SERVICE_UNAVAILABLE,
                    (if (typed.isNotEmpty()) HostNames.unresolved(typed) else null)
                        ?: "no Sonos speaker answers at ${candidates.joinToString()}"
                )
        val list = JSONArray()
        household.forEach { s ->
            val model = runCatching { SonosClient.description(s.host) }.getOrNull()
            list.put(
                JSONObject()
                    .put("uuid", s.uuid)
                    .put("name", s.name)
                    .put("host", s.host)
                    .put("model", model?.model.orEmpty())
                    .put("lineIn", model?.lineIn == true)
                    .put("tv", model?.tv == true)
            )
        }
        return json(Response.Status.OK, JSONObject().put("speakers", list))
    }

    /** The speakers for GET /devices-config. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.sonos(context).forEach { s ->
            put(
                JSONObject()
                    .put("uuid", s.uuid)
                    .put("name", s.name)
                    .put("entityId", s.entityId)
                    .put("host", s.host)
                    .put("model", s.model)
                    .put("lineIn", s.lineIn)
                    .put("tv", s.tv)
            )
        }
    }

    /** Saves the `sonos_*[]` rows of POST /save-connection, only with `sonos_present=1`. */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["sonos_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["sonos_$name[]"].orEmpty()
        val uuids = col("uuid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val models = col("model")
        val lineIns = col("linein")
        val tvs = col("tv")
        val usedIds = HashSet<String>()
        val usedUuids = HashSet<String>()
        val speakers =
            uuids.indices.mapNotNull { i ->
                val uuid = uuids[i].trim().takeIf { UUID.matches(it) && usedUuids.add(it) } ?: return@mapNotNull null
                val host = hosts.getOrNull(i).orEmpty().trim().takeIf { HOST.matches(it) } ?: return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Sonos" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "speaker" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.sonos_$slug"
                var suffix = 2
                while (!usedIds.add(entityId)) entityId = "media_player.sonos_${slug}_${suffix++}"
                SonosConfig(
                    uuid = uuid,
                    name = name,
                    entityId = entityId,
                    host = host,
                    model = models.getOrNull(i).orEmpty().trim().take(MAX_MODEL),
                    lineIn = lineIns.getOrNull(i) == "1",
                    tv = tvs.getOrNull(i) == "1"
                )
            }
        RemoteSettings.saveSonos(context, speakers)
    }

    /** A `haDevices` catalog entry per speaker, so the builder's pickers list it. */
    fun reconcileHaDevices(rawJson: String): String {
        val speakers = RemoteSettings.sonos(context)
        if (speakers.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (s in speakers) {
            val id = "sonos_${s.uuid}"
            val existing = byId[id]
            if (existing == null) {
                haDevices.put(JSONObject().put("id", id).put("domain", "media_player").put("entityId", s.entityId).put("name", s.name))
                changed = true
            } else if (existing.optString("entityId") != s.entityId || existing.optString("name") != s.name) {
                existing.put("entityId", s.entityId).put("name", s.name)
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
        private val UUID = Regex("^RINCON_[0-9A-Fa-f]{12,20}$")
        private const val MAX_MODEL = 40
    }
}
