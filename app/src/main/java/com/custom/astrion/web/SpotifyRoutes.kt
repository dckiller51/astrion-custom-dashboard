package com.custom.astrion.web

import android.content.Context
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SpotifyConfig
import com.custom.astrion.spotify.SpotifyApi
import com.custom.astrion.spotify.SpotifyAuth
import com.custom.astrion.spotify.SpotifyCall
import com.custom.astrion.spotify.SpotifyRegistry
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Method
import fi.iki.elonen.NanoHTTPD.Response
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonPrimitive
import org.json.JSONArray
import org.json.JSONObject

/**
 * The web page's Spotify part (1.6.2), kept out of [ConfigServer]:
 *
 *  POST /spotify-start   clientId → {url}: the Spotify sign-in page, with a PKCE challenge
 *  POST /spotify-finish  url (the address the browser landed on) → {localId, account}
 *
 * The account is then saved with the other connection settings (POST /save-connection,
 * `spotify_*[]` rows, see [saveRows]) and listed in GET /devices-config (`spotifies`, see
 * [configJson]). The refresh token never goes to the page: it waits here until the account is
 * saved, then stays on the remote.
 */
class SpotifyRoutes(private val context: Context, private val registry: () -> SpotifyRegistry? = { null }) {
    private class Started(val clientId: String, val verifier: String, val at: Long)

    private class Signed(val clientId: String, val refresh: String, val account: String)

    /** Sign-ins begun, by their state. */
    private val started = ConcurrentHashMap<String, Started>()

    /** Accounts signed in, not saved yet, by Spotify user id. */
    private val signed = ConcurrentHashMap<String, Signed>()

    fun serve(uri: String, session: IHTTPSession): Response? = when (uri) {
        "/spotify-start" -> if (session.method == Method.POST) {
            start(
                session
            )
        } else {
            error(Response.Status.METHOD_NOT_ALLOWED, "method not allowed")
        }
        "/spotify-finish" -> if (session.method == Method.POST) {
            finish(
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

    private fun param(session: IHTTPSession, name: String) = session.parameters[name]?.firstOrNull()?.trim().orEmpty()

    private fun start(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val clientId = param(session, "clientId")
        if (!SpotifyAuth.isClientId(clientId)) return error(Response.Status.BAD_REQUEST, "a Client ID is 32 letters and digits (0-9, a-f)")
        val now = System.currentTimeMillis()
        started.entries.removeIf { now - it.value.at > STARTED_TTL_MS }
        val verifier = SpotifyAuth.newVerifier()
        val state = SpotifyAuth.newState()
        started[state] = Started(clientId, verifier, now)
        return json(Response.Status.OK, JSONObject().put("url", SpotifyAuth.authorizeUrl(clientId, SpotifyAuth.challenge(verifier), state)))
    }

    private fun finish(session: IHTTPSession): Response {
        session.parseBody(HashMap())
        val landing = SpotifyAuth.landing(param(session, "url"))
        if (landing is SpotifyAuth.Landing.Error) return error(Response.Status.BAD_REQUEST, "Spotify: ${landing.reason}")
        val code = landing as SpotifyAuth.Landing.Code
        // Its state names the sign-in; an address without one takes the last sign-in begun.
        val begun =
            code.state?.let { started.remove(it) }
                ?: started.entries.maxByOrNull { it.value.at }?.let { started.remove(it.key) }
                ?: return error(Response.Status.BAD_REQUEST, "this sign-in has expired: click Sign in to Spotify again")
        return try {
            val tokens = SpotifyApi.exchange(begun.clientId, code.code, begun.verifier)
            val refresh = tokens.refresh ?: return error(Response.Status.SERVICE_UNAVAILABLE, "Spotify gave no refresh token")
            val me = SpotifyApi(begun.clientId, refresh).call(SpotifyCall("GET", "/me"))
            val id = (me?.get("id") as? JsonPrimitive)?.content
                ?: return error(Response.Status.SERVICE_UNAVAILABLE, "Spotify didn't say who you are")
            val account = (me["display_name"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() } ?: id
            signed[id] = Signed(begun.clientId, refresh, account)
            json(Response.Status.OK, JSONObject().put("localId", id).put("account", account))
        } catch (e: Exception) {
            error(Response.Status.SERVICE_UNAVAILABLE, e.message ?: "Spotify didn't answer")
        }
    }

    /** The accounts for GET /devices-config: no token, only whether it's signed in, and its entity. */
    fun configJson(): JSONArray = JSONArray().apply {
        RemoteSettings.spotifies(context).forEach { a ->
            val status = registry()?.status(a.localId)
            put(
                JSONObject()
                    .put("localId", a.localId)
                    .put("name", a.name)
                    .put("account", a.account)
                    .put("paired", a.refreshToken.isNotBlank())
                    .put("reachable", status?.second == true)
                    .put("entityId", status?.first.orEmpty())
            )
        }
    }

    /** Saves the `spotify_*[]` rows (only with `spotify_present=1`); an account keeps its token, or gets its sign-in's. */
    fun saveRows(params: Map<String, List<String>>) {
        if (params["spotify_present"]?.firstOrNull() != "1") return
        fun col(name: String) = params["spotify_$name[]"].orEmpty()
        val before = RemoteSettings.spotifies(context).associateBy { it.localId }
        val ids = col("localid")
        val names = col("name")
        val seen = HashSet<String>()
        val accounts =
            ids.indices.mapNotNull { i ->
                val localId = ids[i].trim().takeIf { LOCAL_ID.matches(it) && seen.add(it) } ?: return@mapNotNull null
                val s = signed.remove(localId)
                val old = before[localId]
                SpotifyConfig(
                    localId = localId,
                    name = names.getOrNull(i).orEmpty().trim().ifBlank { old?.name ?: "Spotify" },
                    clientId = s?.clientId ?: old?.clientId.orEmpty(),
                    refreshToken = s?.refresh ?: old?.refreshToken.orEmpty(),
                    account = s?.account ?: old?.account.orEmpty()
                ).takeIf { it.clientId.isNotBlank() }
            }
        RemoteSettings.saveSpotifies(context, accounts)
    }

    /**
     * The builder's pickers read the `haDevices` catalog: each account's media player gets an
     * entry (domain `media_player`, marked `spotify`), taken out with its account.
     */
    fun reconcileHaDevices(rawJson: String): String {
        val reg = registry() ?: return rawJson
        val saved = RemoteSettings.spotifies(context)
        val wanted = saved.mapNotNull { a ->
            reg.status(
                a.localId
            )?.first?.let { entry(a, it) }
        }.associateBy { it.getString("id") }.toMutableMap()
        val accounts = saved.map { it.localId }.toSet()
        val root = JSONObject(rawJson)
        val before = root.optJSONArray("haDevices") ?: JSONArray()
        val after = JSONArray()
        var changed = false
        for (i in 0 until before.length()) {
            val obj = before.optJSONObject(i) ?: continue
            val kept = kept(obj, accounts, wanted)
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

    /** Another device's entry as is; a Spotify one updated, or null when its account is gone. */
    private fun kept(obj: JSONObject, accounts: Set<String>, wanted: MutableMap<String, JSONObject>): JSONObject? {
        val account = obj.optString("spotify")
        return when {
            account.isEmpty() -> obj
            account !in accounts -> null
            else -> wanted.remove(obj.optString("id")) ?: obj
        }
    }

    private fun entry(a: SpotifyConfig, entityId: String) = JSONObject()
        .put("id", "spotify_" + entityId.substringAfter('.'))
        .put("domain", "media_player")
        .put("entityId", entityId)
        .put("name", a.name)
        .put("spotify", a.localId)

    private companion object {
        const val STARTED_TTL_MS = 15 * 60_000L
        val LOCAL_ID = Regex("^[A-Za-z0-9._-]{1,64}$")
    }
}
