package com.custom.astrion.tvhelper

import java.security.MessageDigest

/** What the routes need from Android, behind an interface so they're tested on a plain JVM. */
interface HelperDevice {
    /** Whether Android lets the helper read media sessions (notification access granted). */
    val mediaAccess: Boolean

    /** Whether the helper may open apps while not on screen (the "display over other apps" permission). */
    val canOpenApps: Boolean

    /** Opens [packageName]'s TV (or phone) launcher entry; false when it isn't installed. */
    fun launch(packageName: String): Boolean

    /** Sends [action] to the app playing; false when no app has a media session. */
    fun media(action: String, positionMs: Long?): Boolean
}

/**
 * The helper's web API (port [PORT]), used by the Astrion remote:
 *
 *  GET  /status                        no code needed: tells the remote a helper is there
 *  GET  /now-playing?since=V&wait=S    what's playing; with `since`, waits up to S s for a change
 *  GET  /art                           the cover (JPEG), 404 when none
 *  POST /launch?package=P              opens an app
 *  POST /media?action=A[&position=MS]  play, pause, play_pause, stop, next, previous, seek
 *
 * Everything but /status needs the helper's code (shown on its screen on the TV), in an
 * `X-Astrion-Code` header or a `code` parameter, so only a remote it was linked to drives the TV.
 */
class HelperRoutes(private val code: String, private val store: NowPlayingStore, private val device: HelperDevice) {
    fun handle(req: HttpRequest): HttpResponse {
        if (req.path == "/status") return status()
        if (!authorized(req)) return HttpResponse.error(UNAUTHORIZED, "wrong or missing code")
        return when (req.path) {
            "/now-playing" -> get(req) { nowPlaying(req) }
            "/art" -> get(req) { art() }
            "/launch" -> post(req) { launch(req) }
            "/media" -> post(req) { media(req) }
            else -> HttpResponse.error(NOT_FOUND, "unknown path")
        }
    }

    private fun get(req: HttpRequest, answer: () -> HttpResponse) =
        if (req.method == "GET") answer() else HttpResponse.error(METHOD_NOT_ALLOWED, "use GET")

    private fun post(req: HttpRequest, answer: () -> HttpResponse) =
        if (req.method == "POST") answer() else HttpResponse.error(METHOD_NOT_ALLOWED, "use POST")

    private fun authorized(req: HttpRequest): Boolean {
        val given = (req.header("X-Astrion-Code") ?: req.query["code"]).orEmpty().trim().uppercase()
        // Constant-time comparison: the code can't be guessed character by character from timings.
        return MessageDigest.isEqual(given.toByteArray(), code.toByteArray())
    }

    private fun status() = HttpResponse.json(
        Json.obj(
            "app" to APP_ID,
            "api" to API_VERSION,
            "version" to BuildInfo.VERSION,
            "mediaAccess" to device.mediaAccess,
            "canOpenApps" to device.canOpenApps
        )
    )

    private fun nowPlaying(req: HttpRequest): HttpResponse {
        val since = req.query["since"]?.toLongOrNull()
        val waitMs = ((req.query["wait"]?.toLongOrNull() ?: 0L).coerceIn(0L, MAX_WAIT_S)) * MS_PER_S
        if (since != null && waitMs > 0) store.await(since, waitMs)
        return HttpResponse.json(store.json())
    }

    private fun art(): HttpResponse {
        val jpeg = store.artJpeg ?: return HttpResponse.error(NOT_FOUND, "no cover")
        return HttpResponse(OK, "image/jpeg", jpeg)
    }

    private fun launch(req: HttpRequest): HttpResponse {
        val pkg = req.query["package"].orEmpty().trim()
        if (!PACKAGE.matches(pkg)) return HttpResponse.error(BAD_REQUEST, "missing or invalid package")
        return if (device.launch(pkg)) ok() else HttpResponse.error(NOT_FOUND, "$pkg isn't installed")
    }

    private fun media(req: HttpRequest): HttpResponse {
        val action = req.query["action"].orEmpty()
        if (action !in MEDIA_ACTIONS) return HttpResponse.error(BAD_REQUEST, "unknown action")
        val position = req.query["position"]?.toLongOrNull()
        if (action == "seek" && position == null) return HttpResponse.error(BAD_REQUEST, "seek needs position (ms)")
        return if (device.media(action, position)) ok() else HttpResponse.error(CONFLICT, "nothing is playing")
    }

    private fun ok() = HttpResponse.json(Json.obj("ok" to true))

    companion object {
        const val PORT = 6470
        const val APP_ID = "astrion-tv-helper"
        const val API_VERSION = 1
        private const val MAX_WAIT_S = 30L
        private const val MS_PER_S = 1000L
        private const val OK = 200
        private const val BAD_REQUEST = 400
        private const val UNAUTHORIZED = 401
        private const val NOT_FOUND = 404
        private const val METHOD_NOT_ALLOWED = 405
        private const val CONFLICT = 409
        val MEDIA_ACTIONS = setOf("play", "pause", "play_pause", "stop", "next", "previous", "seek")
        private val PACKAGE = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
    }
}
