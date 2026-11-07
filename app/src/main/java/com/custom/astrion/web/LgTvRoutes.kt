package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.LgTvConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.lgtv.LgTvConnection
import com.custom.astrion.lgtv.LgTvProtocol
import com.custom.astrion.lgtv.LgTvRegistry
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's LG TV part (1.5.2), kept out of [ConfigServer]:
 *
 *  POST /lgtv-pair   host → the TV shows its prompt; once accepted, {model, mac}
 *  GET  /lgtv-icon/<localId>?id=…   an app's or input's icon, fetched from the TV
 *
 * TVs are saved with the rest of the connection settings (POST /save-connection, `lgtv_*[]`
 * rows, see [saveRows]) and listed in GET /devices-config (`lgtvs`, see [configJson]). The key the
 * TV gives (and its certificate's fingerprint) never go to the page: they wait here, by address,
 * until the TV is saved, and stay on the remote.
 */
class LgTvRoutes(private val context: Context, private val registry: () -> LgTvRegistry? = { null }) {
    private class Paired(val clientKey: String, val pin: String, val mac: String)

    /** Keys of TVs paired but not saved yet, by address. */
    private val pending = ConcurrentHashMap<String, Paired>()

    /** The response for an LG route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when {
        uri.startsWith("/lgtv-icon/") -> icon(uri, session)
        else -> serveExact(uri, session)
    }

    /** An app's or input's icon (GET /lgtv-icon/<localId>?id=…), as the TV draws it. */
    private fun icon(uri: String, session: IHTTPSession): Response {
        val localId = uri.removePrefix("/lgtv-icon/").substringBefore('?')
        val id = session.parameters["id"]?.firstOrNull().orEmpty()
        val bytes = registry()?.iconFor(localId, id) ?: return error(Response.Status.NOT_FOUND, "no icon")
        val type = if (bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()) "image/jpeg" else "image/png"
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, type, java.io.ByteArrayInputStream(bytes), bytes.size.toLong())
    }

    private fun serveExact(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/lgtv-pair" -> if (session.method == Method.POST) {
            pair(
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

    private class Outcome {
        val done = CountDownLatch(1)

        @Volatile var key: String? = null

        @Volatile var pin: String? = null

        @Volatile var failure: String? = null

        @Volatile var prompted = false
    }

    /** Connects without a key: the TV asks on screen; waits for the answer (up to [PROMPT_S] seconds). */
    private fun pair(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val host = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        if (!HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "invalid address")
        val outcome = Outcome()
        val conn =
            LgTvConnection(
                host,
                clientKey = null,
                pin = null,
                listener =
                object : LgTvConnection.Listener {
                    override fun onPrompt() {
                        outcome.prompted = true
                    }

                    override fun onRegistered(clientKey: String, pin: String?) {
                        outcome.key = clientKey
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
        val key = outcome.key
        if (key == null) {
            conn.close()
            val why =
                when {
                    outcome.failure?.contains("refused") == true -> "refused on the TV"
                    outcome.prompted -> "not accepted on the TV in time"
                    else -> "no LG TV answers at $host (${outcome.failure ?: "no answer"})"
                }
            return error(Response.Status.SERVICE_UNAVAILABLE, why)
        }
        val model = ask(conn, LgTvProtocol.SYSTEM_INFO) { LgTvProtocol.model(it) }
        val mac = ask(conn, LgTvProtocol.CONNECTION_INFO) { LgTvProtocol.mac(it) }
        conn.close()
        pending[host] = Paired(key, outcome.pin.orEmpty(), mac.orEmpty())
        return json(Response.Status.OK, JSONObject().put("model", model.orEmpty()).put("mac", mac.orEmpty()))
    }

    /** One request's answer, read by [pick]; null after [ASK_S] seconds. */
    private fun <T> ask(conn: LgTvConnection, uri: String, pick: (JsonObject) -> T?): T? {
        val latch = CountDownLatch(1)
        val value = AtomicReference<T?>(null)
        conn.request(uri) { m ->
            value.set(pick(m.payload))
            latch.countDown()
        }
        latch.await(ASK_S, TimeUnit.SECONDS)
        return value.get()
    }

    /** The TVs for GET /devices-config: no key, only whether it's paired. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.lgTvs(context).forEach { t ->
            put(
                JSONObject()
                    .put("localId", t.localId)
                    .put("name", t.name)
                    .put("entityId", t.entityId)
                    .put("host", t.host)
                    .put("mac", t.mac)
                    .put("paired", t.clientKey.isNotBlank())
            )
        }
    }

    /**
     * Saves the `lgtv_*[]` rows of POST /save-connection — only when the form says it carries them
     * (`lgtv_present=1`), so an older page can't wipe the TVs. A TV keeps its key (or gets the one
     * of its pairing); entity ids are made unique.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["lgtv_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["lgtv_$name[]"].orEmpty()
        val before = RemoteSettings.lgTvs(context).associateBy { it.localId }
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
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "LG TV" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "tv" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.lgtv_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.lgtv_${slug}_${suffix++}"
                val localId = localIds[i].trim().takeIf { LOCAL_ID.matches(it) } ?: slug
                val old = before[localId]
                val paired = pending.remove(host)
                val mac = macs.getOrNull(i).orEmpty().trim().lowercase().takeIf { MAC.matches(it) } ?: paired?.mac ?: old?.mac.orEmpty()
                LgTvConfig(
                    localId = localId,
                    name = name,
                    entityId = entityId,
                    host = host,
                    mac = mac,
                    clientKey = paired?.clientKey ?: old?.clientKey.orEmpty(),
                    pin = paired?.pin ?: old?.pin.orEmpty()
                )
            }
        RemoteSettings.saveLgTvs(context, tvs)
    }

    /**
     * Adds (or updates) a `haDevices` catalog entry per LG TV in [rawJson], as is done for the other
     * direct devices, so the builder's pickers list it. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val tvs = RemoteSettings.lgTvs(context)
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
            val id = "lgtv_${t.localId}"
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
        private const val PROMPT_S = 60L
        private const val ASK_S = 4L
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val ENTITY = Regex("^media_player\\.[a-z0-9_]+$")
        private val MAC = Regex("^([0-9a-f]{2}:){5}[0-9a-f]{2}$")
        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
