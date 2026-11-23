package com.custom.astrion.spotify

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Spotify's sign-in for an app without a secret (1.6.2): Authorization Code with PKCE. Each user
 * makes their own app on Spotify's developer dashboard (its Client ID only), with the redirect URI
 * [REDIRECT_URI] — Spotify only takes HTTPS or the loopback address, so after signing in the
 * browser lands on a page that doesn't load, and the web page asks for that address, which holds
 * the code. Pure, so it is unit-tested.
 */
object SpotifyAuth {
    const val REDIRECT_URI = "http://127.0.0.1:8888/callback"

    /** What the remote reads and does: what plays, the devices, playback, the user's playlists. */
    val SCOPES =
        listOf(
            "user-read-playback-state",
            "user-modify-playback-state",
            "user-read-currently-playing",
            "user-read-recently-played",
            "playlist-read-private",
            "playlist-read-collaborative",
            "user-library-read"
        )

    private const val AUTHORIZE = "https://accounts.spotify.com/authorize"
    private const val VERIFIER_BYTES = 64
    private const val STATE_BYTES = 16

    /** A fresh code verifier (86 characters of base64url). */
    fun newVerifier(random: SecureRandom = SecureRandom()): String = randomUrl(VERIFIER_BYTES, random)

    fun newState(random: SecureRandom = SecureRandom()): String = randomUrl(STATE_BYTES, random)

    /** The challenge sent with the sign-in: base64url(SHA-256(verifier)), without padding. */
    fun challenge(verifier: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    )

    /** The page the user signs in on. */
    fun authorizeUrl(clientId: String, challenge: String, state: String): String = AUTHORIZE + "?" +
        listOf(
            "client_id" to clientId,
            "response_type" to "code",
            "redirect_uri" to REDIRECT_URI,
            "code_challenge_method" to "S256",
            "code_challenge" to challenge,
            "state" to state,
            "scope" to SCOPES.joinToString(" ")
        ).joinToString("&") { (k, v) -> k + "=" + URLEncoder.encode(v, "UTF-8").replace("+", "%20") }

    /** What the address the browser landed on says: the code and the state, or Spotify's error. */
    sealed class Landing {
        data class Code(val code: String, val state: String?) : Landing()

        data class Error(val reason: String) : Landing()
    }

    /** Reads `http://127.0.0.1:8888/callback?code=…&state=…` (or only its query, or the code alone). */
    fun landing(pasted: String): Landing {
        val text = pasted.trim()
        if (text.isEmpty()) return Landing.Error("empty")
        val query = runCatching { URI(text).rawQuery }.getOrNull() ?: text.substringAfter('?', text)
        val params =
            query.split('&').mapNotNull { part ->
                val k = part.substringBefore('=', "")
                if (k.isEmpty()) null else k to URLDecoder.decode(part.substringAfter('='), "UTF-8")
            }.toMap()
        params["error"]?.let { return Landing.Error(it) }
        params["code"]?.takeIf { it.isNotBlank() }?.let { return Landing.Code(it, params["state"]) }
        // Only the code, copied alone.
        return if (CODE.matches(text)) Landing.Code(text, null) else Landing.Error("no code in that address")
    }

    /** A Client ID as the dashboard shows it: 32 hexadecimal characters. */
    fun isClientId(text: String): Boolean = CLIENT_ID.matches(text.trim())

    private fun randomUrl(bytes: Int, random: SecureRandom): String {
        val b = ByteArray(bytes).also { random.nextBytes(it) }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    private val CODE = Regex("^[A-Za-z0-9_-]{20,}$")
    private val CLIENT_ID = Regex("^[0-9a-fA-F]{32}$")
}
