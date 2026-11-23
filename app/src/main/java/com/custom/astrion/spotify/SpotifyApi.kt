package com.custom.astrion.spotify

import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Spotify's Web API for one account (1.6.2): an access token from the refresh token (renewed an
 * hour later, or when refused), then the calls of [SpotifyProtocol]. Spotify may hand a new
 * refresh token with each renewal: [onRefreshToken] keeps it. Blocking: callers run it off the
 * main thread.
 */
class SpotifyApi(
    private val clientId: String,
    @Volatile private var refreshToken: String,
    private val onRefreshToken: (String) -> Unit = {}
) {
    /** Spotify said no: [status] and its `reason` ("NO_ACTIVE_DEVICE", "PREMIUM_REQUIRED"…). */
    class ApiError(val status: Int, val reason: String?, message: String) : IOException(message)

    @Volatile private var access: String? = null

    @Volatile private var expiresAt = 0L

    /** [call] made; its JSON answer, or null when Spotify answers nothing (204, or an empty body). */
    fun call(call: SpotifyCall): JsonObject? = try {
        send(call, token())
    } catch (e: ApiError) {
        if (e.status != UNAUTHORIZED) throw e
        // The token ended early (revoked, clock): a new one, once.
        access = null
        send(call, token())
    }

    private fun send(call: SpotifyCall, token: String): JsonObject? {
        val body: RequestBody? = call.body?.toString()?.toRequestBody(JSON) ?: if (call.method == "GET") null else EMPTY
        val req =
            Request
                .Builder()
                .url(SpotifyProtocol.API + call.path)
                .header("Authorization", "Bearer $token")
                .method(call.method, body)
                .build()
        return http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw errorOf(resp.code, text, resp.header("Retry-After"))
            if (text.isBlank()) null else runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
        }
    }

    @Synchronized
    private fun token(): String {
        access?.takeIf { System.currentTimeMillis() < expiresAt }?.let { return it }
        val t =
            tokens(
                FormBody.Builder().add("grant_type", "refresh_token").add("refresh_token", refreshToken).add("client_id", clientId).build()
            )
        access = t.access
        expiresAt = System.currentTimeMillis() + (t.expiresInS - EARLY_S) * MS
        t.refresh?.takeIf { it != refreshToken }?.let {
            refreshToken = it
            onRefreshToken(it)
        }
        return t.access
    }

    data class Tokens(val access: String, val refresh: String?, val expiresInS: Long)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON = "application/json".toMediaType()
        private val EMPTY = ByteArray(0).toRequestBody(null)
        private const val TOKEN_URL = "https://accounts.spotify.com/api/token"
        private const val UNAUTHORIZED = 401
        private const val MS = 1000L
        private const val EARLY_S = 60L
        private const val DEFAULT_EXPIRY_S = 3600L

        private val http: OkHttpClient by lazy {
            OkHttpClient
                .Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(12, TimeUnit.SECONDS)
                .build()
        }

        /** The sign-in's code turned into tokens (the PKCE step, no secret). */
        fun exchange(clientId: String, code: String, verifier: String): Tokens = tokens(
            FormBody
                .Builder()
                .add("grant_type", "authorization_code")
                .add("code", code)
                .add("redirect_uri", SpotifyAuth.REDIRECT_URI)
                .add("client_id", clientId)
                .add("code_verifier", verifier)
                .build()
        )

        private fun tokens(form: FormBody): Tokens {
            val req = Request.Builder().url(TOKEN_URL).post(form).build()
            return http.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()
                fun str(k: String) = (o?.get(k) as? JsonPrimitive)?.contentOrNull
                val access = str("access_token")
                if (!resp.isSuccessful || access == null) {
                    throw ApiError(
                        resp.code,
                        str("error"),
                        "Spotify sign-in refused: ${str("error_description") ?: str("error") ?: "HTTP ${resp.code}"}"
                    )
                }
                Tokens(access, str("refresh_token"), (o?.get("expires_in") as? JsonPrimitive)?.longOrNull ?: DEFAULT_EXPIRY_S)
            }
        }

        /** `{"error": {"status": 404, "message": "…", "reason": "NO_ACTIVE_DEVICE"}}`. */
        private fun errorOf(code: Int, text: String, retryAfter: String?): ApiError {
            val e = runCatching { (json.parseToJsonElement(text) as? JsonObject)?.get("error") as? JsonObject }.getOrNull()
            val reason = (e?.get("reason") as? JsonPrimitive)?.contentOrNull
            val message = (e?.get("message") as? JsonPrimitive)?.contentOrNull ?: "HTTP $code"
            val wait = retryAfter?.let { " (retry after $it s)" }.orEmpty()
            return ApiError(code, reason, "$message$wait")
        }
    }
}
