package com.custom.astrion.web

import android.content.Context
import android.util.Log
import com.custom.astrion.androidtv.AndroidTvHelperClient
import com.custom.astrion.androidtv.AndroidTvPairingException
import com.custom.astrion.androidtv.AndroidTvRegistry
import com.custom.astrion.config.AndroidTvConfig
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.RemoteSettings
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.io.ByteArrayInputStream
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Android TV part (1.3.6), kept out of [ConfigServer] (already large):
 *
 *  GET  /androidtv-scan          Android TVs found on the network (mDNS)
 *  POST /androidtv-pair-start    host[, serviceName] → the TV shows a code; returns {sessionId}
 *  POST /androidtv-pair-finish   sessionId, code → {name, mac, certSha256, localId}
 *  POST /androidtv-helper-check  host, code → {ok} when the Astrion TV Helper there accepts the code
 *  GET  /androidtv-artwork/<id>  the cover the helper sent (a TV's `entity_picture`)
 *
 * Paired TVs are saved with the rest of the connection settings (POST /save-connection,
 * `adtv_*[]` rows, see [saveRows]) and listed in GET /devices-config (`androidTvs`, see [configJson]).
 */
class AndroidTvRoutes(private val context: Context, private val registry: () -> AndroidTvRegistry?) {
    /** The response for an Android TV route, or null when [uri] isn't one. */
    fun serve(uri: String, session: IHTTPSession): Response? = when {
        uri.startsWith("/androidtv-artwork/") -> if (session.method == Method.GET) artwork(uri) else methodNotAllowed()
        else -> serveExact(uri, session)
    }

    private fun serveExact(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/androidtv-scan" -> if (session.method == Method.GET) scan() else methodNotAllowed()
        "/androidtv-helper-check" -> if (session.method == Method.POST) helperCheck(session) else methodNotAllowed()
        "/androidtv-pair-start" -> if (session.method == Method.POST) pairStart(session) else methodNotAllowed()
        "/androidtv-pair-finish" -> if (session.method == Method.POST) pairFinish(session) else methodNotAllowed()
        else -> null
    }

    private fun json(status: Response.Status, body: JSONObject): Response =
        NanoHTTPD.newFixedLengthResponse(status, "application/json", body.toString())

    private fun error(status: Response.Status, message: String) = json(status, JSONObject().put("error", message))

    private fun methodNotAllowed() = error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")

    private fun notReady() = error(Response.Status.SERVICE_UNAVAILABLE, "Android TV support is still starting")

    private fun params(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull()?.trim().orEmpty() }
    }

    /** The cover the Astrion TV Helper sent, for a TV's `entity_picture`. */
    private fun artwork(uri: String): Response {
        val localId = uri.removePrefix("/androidtv-artwork/").substringBefore('?')
        val jpeg = registry()?.artworkFor(localId) ?: return error(Response.Status.NOT_FOUND, "no artwork")
        return NanoHTTPD.newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(jpeg), jpeg.size.toLong())
    }

    /** Checks a helper code typed on the web page before it's saved: {ok:true} or {error}. */
    private fun helperCheck(session: IHTTPSession): Response {
        val p = params(session)
        val host = p["host"].orEmpty()
        val code = p["code"].orEmpty().uppercase()
        if (!HOST.matches(host) || !HELPER_CODE.matches(code)) return error(Response.Status.BAD_REQUEST, "missing or invalid host or code")
        val problem = AndroidTvHelperClient.check(host, code)
        return if (problem == null) json(Response.Status.OK, JSONObject().put("ok", true)) else error(Response.Status.BAD_REQUEST, problem)
    }

    private fun scan(): Response {
        val found = runCatching { registry()?.scan() }.getOrNull().orEmpty()
        val array = JSONArray()
        found.forEach { tv -> array.put(JSONObject().put("serviceName", tv.serviceName).put("name", tv.serviceName).put("host", tv.host)) }
        return json(Response.Status.OK, JSONObject().put("devices", array))
    }

    private fun pairStart(session: IHTTPSession): Response {
        val host = params(session)["host"].orEmpty()
        if (!HOST.matches(host)) return error(Response.Status.BAD_REQUEST, "missing or invalid host")
        val reg = registry() ?: return notReady()
        return try {
            json(Response.Status.OK, JSONObject().put("sessionId", reg.beginPairing(host)))
        } catch (e: AndroidTvPairingException) {
            Log.w(TAG, "pairing start failed", e)
            error(Response.Status.SERVICE_UNAVAILABLE, e.message ?: "pairing failed")
        }
    }

    private fun pairFinish(session: IHTTPSession): Response {
        val p = params(session)
        val sessionId = p["sessionId"].orEmpty()
        val code = p["code"].orEmpty()
        if (sessionId.isBlank() || code.isBlank()) return error(Response.Status.BAD_REQUEST, "missing sessionId or code")
        val reg = registry() ?: return notReady()
        return try {
            val tv = reg.finishPairing(sessionId, code)
            val localId = tv.mac.replace(":", "").lowercase().ifBlank { tv.certSha256.take(LOCAL_ID_LENGTH) }
            json(
                Response.Status.OK,
                JSONObject().put("name", tv.name).put("mac", tv.mac).put("certSha256", tv.certSha256).put("localId", localId)
            )
        } catch (e: AndroidTvPairingException) {
            error(Response.Status.BAD_REQUEST, e.message ?: "pairing failed")
        }
    }

    /** For GET /devices-config. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.androidTvs(context).forEach { tv ->
            put(
                JSONObject()
                    .put("localId", tv.localId)
                    .put("name", tv.name)
                    .put("entityId", tv.entityId)
                    .put("host", tv.host)
                    .put("serviceName", tv.serviceName)
                    .put("mac", tv.mac)
                    .put("certSha256", tv.certSha256)
                    .put("helperCode", tv.helperCode)
            )
        }
    }

    /**
     * Saves the `adtv_*[]` rows of POST /save-connection — only when the form says it carries them
     * (`adtv_present=1`), so an older page can't wipe the paired TVs. Entity ids are made unique.
     */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["adtv_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["adtv_$name[]"].orEmpty()
        val localIds = col("localid")
        val names = col("name")
        val entityIds = col("entityid")
        val hosts = col("host")
        val services = col("service")
        val macs = col("mac")
        val certs = col("cert")
        val helperCodes = col("helper")
        val used = HashSet<String>()
        val tvs =
            localIds.indices.mapNotNull { i ->
                val cert = certs.getOrNull(i).orEmpty().trim().lowercase()
                val host = hosts.getOrNull(i).orEmpty().trim()
                if (!SHA256.matches(cert) || !HOST.matches(host)) return@mapNotNull null
                val name = names.getOrNull(i).orEmpty().trim().ifBlank { "Android TV" }
                val slug = name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_').ifBlank { "tv" }
                var entityId = entityIds.getOrNull(i).orEmpty().trim().takeIf { ENTITY.matches(it) } ?: "media_player.androidtv_$slug"
                var suffix = 2
                while (!used.add(entityId)) entityId = "media_player.androidtv_${slug}_${suffix++}"
                AndroidTvConfig(
                    localId = localIds[i].trim().ifBlank { cert.take(LOCAL_ID_LENGTH) },
                    name = name,
                    entityId = entityId,
                    host = host,
                    serviceName = services.getOrNull(i).orEmpty().trim(),
                    mac = macs.getOrNull(i).orEmpty().trim(),
                    certSha256 = cert,
                    helperCode = helperCodes.getOrNull(i).orEmpty().trim().uppercase().takeIf { HELPER_CODE.matches(it) }.orEmpty()
                )
            }
        RemoteSettings.saveAndroidTvs(context, tvs)
    }

    /**
     * Adds (or updates) a `haDevices` catalog entry per paired Android TV in [rawJson], as is done
     * for Apple TVs, so the builder's pickers list it. Saves and returns the new text when it changed.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val tvs = RemoteSettings.androidTvs(context)
        if (tvs.isEmpty()) return rawJson
        val root = JSONObject(rawJson)
        val haDevices = root.optJSONArray("haDevices") ?: JSONArray()
        val byId = HashMap<String, JSONObject>()
        for (i in 0 until haDevices.length()) {
            val obj = haDevices.optJSONObject(i) ?: continue
            obj.optString("id").takeIf { it.isNotEmpty() }?.let { byId[it] = obj }
        }
        var changed = false
        for (tv in tvs) {
            val existing = byId["androidtv_${tv.localId}"]
            if (existing == null) {
                haDevices.put(
                    JSONObject().put(
                        "id",
                        "androidtv_${tv.localId}"
                    ).put("domain", "media_player").put("entityId", tv.entityId).put("name", tv.name)
                )
                changed = true
            } else if (existing.optString("entityId") != tv.entityId || existing.optString("name") != tv.name) {
                existing.put("entityId", tv.entityId).put("name", tv.name)
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
        private const val TAG = "AndroidTvRoutes"
        private const val LOCAL_ID_LENGTH = 12
        private val HOST = Regex("^[A-Za-z0-9.:-]{1,253}$")
        private val SHA256 = Regex("^[0-9a-f]{64}$")
        private val ENTITY = Regex("^media_player\\.[a-z0-9_]+$")
        private val HELPER_CODE = Regex("^[A-Z0-9]{6}$")
    }
}
