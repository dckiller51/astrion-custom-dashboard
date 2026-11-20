package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HueConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.hue.HueClient
import com.custom.astrion.hue.HueEntities
import com.custom.astrion.hue.HueRegistry
import com.custom.astrion.hue.HueStore
import com.custom.astrion.hue.str
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Philips Hue part (1.6.1), kept out of [ConfigServer]:
 *
 *  POST /hue-discover   the bridges on this network, from Philips' discovery service
 *  POST /hue-pair       host → waits (up to 30 s) for the bridge's button; then {name, bridgeId, model, lights, groups, scenes}
 *
 * Bridges are saved with the rest of the connection settings (POST /save-connection, `hue_*[]`
 * rows, see [saveRows]) and listed in GET /devices-config (`hues`, see [configJson]). The app key
 * the bridge gives (and its certificate's fingerprint) never go to the page: they wait here, by
 * address, until the bridge is saved, and stay on the remote.
 */
class HueRoutes(private val context: Context, private val registry: () -> HueRegistry? = { null }) {
    private class Paired(val bridgeId: String, val appKey: String, val pin: String)

    /** Keys of bridges paired but not saved yet, by address. */
    private val pending = ConcurrentHashMap<String, Paired>()

    /** The response for a Hue route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/hue-discover" -> if (session.method == Method.POST) {
            discover()
        } else {
            error(
                Response.Status.METHOD_NOT_ALLOWED,
                "method not allowed"
            )
        }
        "/hue-pair" -> if (session.method == Method.POST) pair(session) else error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        else -> null
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    /**
     * Philips' discovery service lists the bridges that called home from this network (it needs
     * the internet, and answers about once every 15 minutes); each is then asked its name.
     */
    private fun discover(): Response {
        val list =
            runCatching {
                val http = OkHttpClient.Builder().callTimeout(DISCOVER_S, TimeUnit.SECONDS).build()
                http.newCall(Request.Builder().url(DISCOVERY_URL).build()).execute().use { r ->
                    if (!r.isSuccessful) throw java.io.IOException("HTTP ${r.code}")
                    JSONArray(r.body?.string().orEmpty())
                }
            }.getOrElse { return error(Response.Status.SERVICE_UNAVAILABLE, "Philips' discovery service didn't answer (${it.message})") }
        val found = JSONArray()
        val entries = (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        entries.forEach { o ->
            val host = o.optString("internalipaddress").takeIf { HOST.matches(it) } ?: return@forEach
            val config = runCatching { HueClient(host).config() }.getOrNull()
            found.put(
                JSONObject()
                    .put("host", host)
                    .put("bridgeId", config?.str("bridgeid") ?: o.optString("id"))
                    .put("name", config?.str("name").orEmpty())
                    .put("model", config?.str("modelid").orEmpty())
                    .put("answers", config != null)
            )
        }
        return json(Response.Status.OK, JSONObject().put("bridges", found))
    }

    /**
     * Asks the bridge at `host` for an app key every second for [BUTTON_S] seconds: it gives one
     * once its button was pressed. Then reads what it has, for the page to say.
     */
    private fun pair(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val host = session.parameters["host"]?.firstOrNull()?.trim().orEmpty()
        val remoteName = session.parameters["remote"]?.firstOrNull()?.trim().orEmpty()
        if (!HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "invalid address")
        val probe = HueClient(host)
        val config =
            runCatching { probe.config() }.getOrElse {
                val why = HostNames.unresolved(host) ?: "no Hue bridge answers at $host (${it.message})"
                return error(Response.Status.SERVICE_UNAVAILABLE, why)
            } ?: return error(Response.Status.BAD_REQUEST, "$host answers, but isn't a Hue bridge")
        val bridgeId = config.str("bridgeid").orEmpty().lowercase()
        val name = config.str("name").orEmpty().ifBlank { "Hue" }
        val model = config.str("modelid").orEmpty()
        if (model == V1_MODEL) {
            return error(
                Response.Status.BAD_REQUEST,
                "a first Hue bridge (round): only the square bridge and the Bridge Pro have the API Astrion uses"
            )
        }
        val deviceType = "astrion#" + remoteName.ifBlank { "remote" }.replace(Regex("[^A-Za-z0-9 _-]"), "").take(DEVICE_NAME_MAX)
        val deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(BUTTON_S)
        var key: String? = null
        while (key == null && System.currentTimeMillis() < deadline) {
            when (
                val r = runCatching {
                    probe.createKey(
                        deviceType
                    )
                }.getOrElse { HueClient.KeyResult.Refused(it.message ?: "no answer") }
            ) {
                is HueClient.KeyResult.Created -> key = r.appKey
                HueClient.KeyResult.PressButton -> Thread.sleep(ASK_EVERY_MS)
                is HueClient.KeyResult.Refused -> return error(Response.Status.SERVICE_UNAVAILABLE, "the bridge refused: ${r.reason}")
            }
        }
        if (key == null) return error(Response.Status.SERVICE_UNAVAILABLE, "the bridge's button wasn't pressed in time")
        val pin = probe.seenPin.orEmpty()
        pending[host] = Paired(bridgeId, key, pin)
        // What it has, read once with the new key, for the page to say.
        val store = HueStore()
        runCatching { store.load(HueClient(host, key, pin.ifBlank { null }, bridgeId).all()) }
        return json(
            Response.Status.OK,
            JSONObject()
                .put("name", name)
                .put("bridgeId", bridgeId)
                .put("model", model)
                .put("lights", store.ofType(HueEntities.LIGHT).size)
                .put("groups", store.ofType("room").size + store.ofType("zone").size)
                .put("scenes", store.ofType(HueEntities.SCENE).size)
        )
    }

    /** The bridges for GET /devices-config: no key, only whether it's paired, and what it has. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.hues(context).forEach { b ->
            val summary = registry()?.summary(b.localId)
            put(
                JSONObject()
                    .put("localId", b.localId)
                    .put("name", b.name)
                    .put("host", b.host)
                    .put("paired", b.appKey.isNotBlank())
                    .put("reachable", summary?.reachable == true)
                    .put("lights", summary?.lights ?: 0)
                    .put("groups", summary?.groups ?: 0)
                    .put("scenes", summary?.scenes ?: 0)
            )
        }
    }

    /**
     * Saves the `hue_*[]` rows of POST /save-connection — only when the form says it carries them
     * (`hue_present=1`), so an older page can't wipe the bridges. A bridge keeps its key and its
     * entity ids (or gets the key of its pairing).
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["hue_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["hue_$name[]"].orEmpty()
        val before = RemoteSettings.hues(context).associateBy { it.localId }
        val localIds = col("localid")
        val names = col("name")
        val hosts = col("host")
        val seen = HashSet<String>()
        val bridges =
            localIds.indices.mapNotNull { i ->
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!HOST.matches(host)) return@mapNotNull null
                val paired = pending.remove(host)
                val localId = (paired?.bridgeId ?: localIds[i].trim()).lowercase().takeIf { LOCAL_ID.matches(it) } ?: return@mapNotNull null
                if (!seen.add(localId)) return@mapNotNull null
                val old = before[localId]
                HueConfig(
                    localId = localId,
                    name = names.getOrNull(i).orEmpty().trim().ifBlank { old?.name ?: "Hue" },
                    host = host,
                    appKey = paired?.appKey ?: old?.appKey.orEmpty(),
                    pin = paired?.pin ?: old?.pin.orEmpty(),
                    entities = old?.entities.orEmpty()
                )
            }
        RemoteSettings.saveHues(context, bridges)
    }

    /**
     * The builder's pickers read the `haDevices` catalog (1.6.1): each bridge's lights, rooms,
     * zones (domain `light`) and scenes (domain `scene`) get an entry, marked with the bridge
     * (`hue`), as the other direct devices do. Entries of a bridge removed, or of a light gone from
     * a bridge read since, are taken out. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val reg = registry() ?: return rawJson
        val bridges = RemoteSettings.hues(context).map { it.localId }.toSet()
        val loaded = bridges.filter { reg.catalog(it) != null }.toSet()
        val wanted = LinkedHashMap<String, JSONObject>()
        loaded.forEach { localId ->
            reg.catalog(
                localId
            ).orEmpty().forEach { (entityId, name) -> wanted[catalogId(entityId)] = entry(localId, entityId, name) }
        }
        val root = JSONObject(rawJson)
        val before = root.optJSONArray("haDevices") ?: JSONArray()
        val after = JSONArray()
        var changed = false
        for (i in 0 until before.length()) {
            val obj = before.optJSONObject(i) ?: continue
            val kept = kept(obj, bridges, loaded, wanted)
            if (kept?.toString() != obj.toString()) changed = true
            kept?.let { after.put(it) }
        }
        wanted.values.forEach { after.put(it) }
        if (!changed && wanted.isEmpty()) return rawJson
        root.put("haDevices", after)
        val result = root.toString()
        runCatching { DashboardLoader.configFile.writeText(result) }
        return result
    }

    /** A catalog entry as it stays: another device's as is, a Hue one updated, or null when its bridge or light is gone. */
    private fun kept(obj: JSONObject, bridges: Set<String>, loaded: Set<String>, wanted: MutableMap<String, JSONObject>): JSONObject? {
        val bridge = obj.optString("hue")
        return when {
            bridge.isEmpty() -> obj
            bridge !in bridges -> null
            else -> wanted.remove(obj.optString("id")) ?: obj.takeIf { bridge !in loaded }
        }
    }

    private fun entry(localId: String, entityId: String, name: String) = JSONObject()
        .put("id", catalogId(entityId))
        .put("domain", entityId.substringBefore('.'))
        .put("entityId", entityId)
        .put("name", name)
        .put("hue", localId)

    private fun catalogId(entityId: String) = "hue_" + entityId.replace('.', '_')

    companion object {
        private const val DISCOVERY_URL = "https://discovery.meethue.com/"
        private const val DISCOVER_S = 8L
        private const val BUTTON_S = 30L
        private const val ASK_EVERY_MS = 1_000L
        private const val DEVICE_NAME_MAX = 19
        private const val V1_MODEL = "BSB001"
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val LOCAL_ID = Regex("^[A-Za-z0-9_-]{1,40}$")
    }
}
