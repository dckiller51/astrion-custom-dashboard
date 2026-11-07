package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SamsungConfig
import com.custom.astrion.samsung.SamsungConnection
import com.custom.astrion.samsung.SamsungRegistry
import com.custom.astrion.samsung.SamsungRest
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Samsung TV part (1.5.3), kept out of [ConfigServer]:
 *
 *  POST /samsung-pair   host → the TV asks to allow the remote; once allowed, {model, mac}
 *  GET  /samsung-icon/<localId>?id=…   an app's icon, fetched from the TV
 *
 * TVs are saved with the rest of the connection settings (POST /save-connection, `samsung_*[]`
 * rows, see [saveRows]) and listed in GET /devices-config (`samsungs`, see [configJson]). The token
 * the TV gives (and its certificate's fingerprint) never go to the page: they wait here, by
 * address, until the TV is saved, and stay on the remote.
 */
class SamsungRoutes(private val context: Context, private val registry: () -> SamsungRegistry? = { null }) {
    private class Paired(val token: String, val pin: String, val mac: String, val tls: Boolean)

    /** Tokens of TVs allowed but not saved yet, by address. */
    private val pending = ConcurrentHashMap<String, Paired>()

    /** The response for a Samsung route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when {
        uri.startsWith("/samsung-icon/") -> icon(uri, session)
        uri == "/samsung-pair" -> if (session.method == Method.POST) {
            pair(
                session
            )
        } else {
            error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        else -> null
    }

    private fun icon(uri: String, session: IHTTPSession): Response {
        val localId = uri.removePrefix("/samsung-icon/").substringBefore('?')
        val id = session.parameters["id"]?.firstOrNull().orEmpty()
        val bytes = registry()?.iconFor(localId, id) ?: return error(Response.Status.NOT_FOUND, "no icon")
        val type = if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, type, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private class Outcome {
        val done = CountDownLatch(1)

        @Volatile var connected = false

        @Volatile var token: String? = null

        @Volatile var pin: String? = null

        @Volatile var failure: String? = null
    }

    /**
     * Asks the TV what it is, then connects without a token: the TV asks on screen to allow the
     * remote; waits for the answer (up to [PROMPT_S] seconds).
     */
    private fun pair(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val host = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        if (!HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "invalid address")
        val info = SamsungRest(host).info() ?: return error(Response.Status.SERVICE_UNAVAILABLE, "no Samsung TV answers at $host")
        val outcome = Outcome()
        val conn =
            SamsungConnection(
                host,
                token = null,
                pin = null,
                tls = info.tokens,
                listener =
                object : SamsungConnection.Listener {
                    override fun onConnected(token: String?, pin: String?) {
                        outcome.connected = true
                        outcome.token = token
                        outcome.pin = pin
                        outcome.done.countDown()
                    }

                    override fun onClosed(reason: String) {
                        outcome.failure = reason
                        outcome.done.countDown()
                    }
                }
            )
        conn.connect()
        outcome.done.await(PROMPT_S, TimeUnit.SECONDS)
        conn.close()
        if (!outcome.connected) {
            val why =
                when (outcome.failure) {
                    "denied" -> "refused on the TV"
                    null -> "not allowed on the TV in time"
                    else -> outcome.failure!!
                }
            return error(Response.Status.SERVICE_UNAVAILABLE, why)
        }
        val mac = info.mac.orEmpty()
        pending[host] = Paired(outcome.token.orEmpty(), outcome.pin.orEmpty(), mac, info.tokens)
        return json(
            Response.Status.OK,
            JSONObject().put("model", info.model.orEmpty()).put("name", info.name.orEmpty()).put("mac", mac).put("frame", info.frame)
        )
    }

    /** The TVs for GET /devices-config: no token, only whether it's paired. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.samsungs(context).forEach { t ->
            put(
                JSONObject()
                    .put("localId", t.localId)
                    .put("name", t.name)
                    .put("entityId", t.entityId)
                    .put("host", t.host)
                    .put("mac", t.mac)
                    .put("paired", t.token.isNotBlank() || !t.tls)
            )
        }
    }

    /**
     * Saves the `samsung_*[]` rows of POST /save-connection — only when the form says it carries
     * them (`samsung_present=1`), so an older page can't wipe the TVs. A TV keeps its token (or gets
     * the one of its pairing); entity ids are made unique.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["samsung_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["samsung_$name[]"].orEmpty()
        val before = RemoteSettings.samsungs(context).associateBy { it.localId }
        val localIds = col("localid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val macs = col("mac")
        val used = HashSet<String>()
        val tvs =
            localIds.indices.mapNotNull { i ->
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!HOST.matches(host)) return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Samsung TV" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "tv" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.samsung_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.samsung_${slug}_${suffix++}"
                val localId = localIds[i].trim().takeIf { LOCAL_ID.matches(it) } ?: slug
                val old = before[localId]
                val paired = pending.remove(host)
                val mac = macs.getOrNull(i).orEmpty().trim().lowercase().takeIf { MAC.matches(it) } ?: paired?.mac ?: old?.mac.orEmpty()
                SamsungConfig(
                    localId = localId,
                    name = name,
                    entityId = entityId,
                    host = host,
                    mac = mac,
                    token = paired?.token ?: old?.token.orEmpty(),
                    // A new pairing may meet a new certificate (a reset TV): its own fingerprint.
                    pin = paired?.pin ?: old?.pin.orEmpty(),
                    tls = paired?.tls ?: old?.tls ?: true
                )
            }
        RemoteSettings.saveSamsungs(context, tvs)
    }

    /**
     * Adds (or updates) a `haDevices` catalog entry per Samsung TV in [rawJson], as for the other
     * direct devices, so the builder's pickers list it. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val tvs = RemoteSettings.samsungs(context)
        if (tvs.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (t in tvs) {
            val id = "samsung_${t.localId}"
            val existing = byId[id]
            if (existing == null) {
                haDevices.put(JSONObject().put("id", id).put("domain", "media_player").put("entityId", t.entityId).put("name", t.name))
                changed = true
            } else if (existing.optString("entityId") != t.entityId || existing.optString("name") != t.name) {
                existing.put("entityId", t.entityId).put("name", t.name)
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
        private const val PROMPT_S = 40L
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val ENTITY = Regex("^media_player\\.[a-z0-9_]+$")
        private val MAC = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
