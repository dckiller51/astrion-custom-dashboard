package com.custom.astrion.web

import android.content.Context
import android.util.Base64
import android.util.Log
import com.custom.astrion.config.WebPassword
import fi.iki.elonen.NanoHTTPD
import fi.iki.elonen.NanoHTTPD.IHTTPSession
import fi.iki.elonen.NanoHTTPD.Response

/**
 * The web page's password check (1.4.6), in front of every page and endpoint of [ConfigServer].
 *
 * HTTP Basic: the browser asks once and sends it with every request; tools (the Home Assistant
 * integration, the Astrion Config Manager, curl) send an `Authorization` header. Any user name is
 * accepted, only the password counts. The remote's own requests (127.0.0.1) are never asked.
 *
 * A PBKDF2 check takes a while on the remote, so a header that already passed is remembered (a few
 * of them: a browser and Home Assistant at once) until the password changes.
 */
class WebAuth(private val context: Context) {
    private val accepted = LinkedHashSet<String>()
    private var acceptedGeneration = -1

    /** Null when the request may go on, else the 401 to send back. */
    fun check(session: IHTTPSession): Response? {
        if (!WebPassword.isSet(context) || isLoopback(session.remoteIpAddress)) return null
        val header = session.headers["authorization"].orEmpty()
        if (isAccepted(header)) return null
        val password = basicPassword(header)
        if (password != null && WebPassword.check(context, password)) {
            remember(header)
            return null
        }
        if (password != null) {
            Log.w(TAG, "wrong web page password from ${session.remoteIpAddress}")
            // Slows down guessing.
            Thread.sleep(FAILURE_DELAY_MS)
        }
        return NanoHTTPD.newFixedLengthResponse(
            Response.Status.UNAUTHORIZED,
            "text/plain; charset=utf-8",
            "This remote's web page has a password (remote › Settings › Web configuration).\n"
        ).apply { addHeader("WWW-Authenticate", "Basic realm=\"Astrion remote\", charset=\"UTF-8\"") }
    }

    @Synchronized
    private fun isAccepted(header: String): Boolean {
        if (acceptedGeneration != WebPassword.generation) {
            accepted.clear()
            acceptedGeneration = WebPassword.generation
        }
        return header.isNotEmpty() && header in accepted
    }

    @Synchronized
    private fun remember(header: String) {
        if (acceptedGeneration != WebPassword.generation) {
            accepted.clear()
            acceptedGeneration = WebPassword.generation
        }
        if (accepted.size >= MAX_REMEMBERED) accepted.remove(accepted.first())
        accepted += header
    }

    private companion object {
        const val TAG = "WebAuth"
        const val FAILURE_DELAY_MS = 600L
        const val MAX_REMEMBERED = 8

        fun isLoopback(ip: String?): Boolean = ip == "127.0.0.1" || ip == "::1" || ip == "0:0:0:0:0:0:0:1"

        /** The password of a `Basic` header (whatever comes after the first ':'), or null. */
        fun basicPassword(header: String): String? {
            if (!header.startsWith("Basic ", ignoreCase = true)) return null
            val decoded =
                runCatching { String(Base64.decode(header.substring(6).trim(), Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
                    ?: return null
            val colon = decoded.indexOf(':')
            return if (colon < 0) null else decoded.substring(colon + 1)
        }
    }
}
