package com.custom.astrion.denon

import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Talks to one Denon / Marantz receiver's web API (1.5.4), see [DenonProtocol]. Blocking: called
 * from the registry's own threads, never the UI's.
 *
 * Which status it reads is found once: the full status page (most models); else AppCommand.xml
 * (recent ones, that answer 403 on that page); else a lighter status page.
 */
class DenonClient(private val host: String, @Volatile var port: Int) {
    private enum class Source { STATUS, APP_COMMAND, LITE }

    @Volatile private var source: Source? = null

    @Volatile private var litePath: String? = null

    /** The inputs read from Deviceinfo.xml and the renames, refreshed now and then. */
    @Volatile private var appInputs: List<DenonProtocol.Input> = emptyList()

    @Volatile private var appInputsReadAt = 0L

    @Volatile private var appInputsTriedAt = 0L

    /**
     * The main zone's status; null when nothing answers, or not a receiver's status. A status
     * without the inputs (the lighter pages) gets Deviceinfo.xml's.
     */
    fun status(): DenonProtocol.Status? = when (source) {
        Source.STATUS -> fullStatus()
        Source.APP_COMMAND -> appStatus()
        Source.LITE -> litePath?.let(::statusAt)
        null -> find()
    }?.let { s -> if (s.inputs.isNotEmpty()) s else s.copy(inputs = deviceInputs()) }

    private fun find(): DenonProtocol.Status? {
        fullStatus()?.let {
            source = Source.STATUS
            return it
        }
        appStatus()?.let {
            source = Source.APP_COMMAND
            return it
        }
        for (path in DenonProtocol.LITE_STATUS_PATHS) {
            statusAt(path)?.let {
                source = Source.LITE
                litePath = path
                return it
            }
        }
        return null
    }

    private fun fullStatus(): DenonProtocol.Status? = statusAt(DenonProtocol.STATUS)

    private fun statusAt(path: String): DenonProtocol.Status? =
        get(path)?.takeIf { "InputFuncSelect" in it || "<Power>" in it || "ZonePower" in it }?.let(DenonProtocol::status)

    private fun appStatus(): DenonProtocol.Status? = post(DenonProtocol.STATE_COMMANDS)?.let { DenonProtocol.appStatus(it, deviceInputs()) }

    /** Deviceinfo.xml's inputs, as renamed / hidden on the receiver: read again every 10 min, or each minute while it has none. */
    private fun deviceInputs(): List<DenonProtocol.Input> {
        val now = System.currentTimeMillis()
        val stale = if (appInputs.isEmpty()) now - appInputsTriedAt > RETRY_INPUTS_MS else now - appInputsReadAt > INPUTS_REFRESH_MS
        if (stale) {
            appInputsTriedAt = now
            get(DenonProtocol.DEVICE_INFO)?.let { info ->
                val inputs = DenonProtocol.appInputs(info, post(DenonProtocol.SOURCE_COMMANDS))
                if (inputs.isNotEmpty()) {
                    appInputs = inputs
                    appInputsReadAt = now
                }
            }
        }
        return appInputs
    }

    fun model(): String? = get(DenonProtocol.DEVICE_INFO)?.let(DenonProtocol::model)

    /** Sends one command; false when the receiver didn't take it. */
    fun send(command: String): Boolean = get(DenonProtocol.commandPath(command)) != null

    /** Zone 2 or 3's status (1.5.7); null when the receiver has no such zone or doesn't answer. */
    fun zoneStatus(zone: Int): DenonProtocol.ZoneStatus? = get(DenonProtocol.zoneStatusPath(zone))?.let(DenonProtocol::zoneStatus)

    private fun get(path: String): String? = call(Request.Builder().url(DenonProtocol.url(host, port, path)).build())

    private fun post(commands: List<String>): String? = call(
        Request
            .Builder()
            .url(DenonProtocol.url(host, port, DenonProtocol.APP_COMMAND))
            // No Content-Type, as denonavr sends it: some models turn down a typed body.
            .post(DenonProtocol.appCommandBody(commands).toRequestBody())
            .build()
    )?.takeIf { "<cmd" in it }

    private fun call(request: Request): String? = runCatching {
        http.newCall(request).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    }.getOrNull()

    companion object {
        private const val TIMEOUT_S = 4L
        private const val INPUTS_REFRESH_MS = 10 * 60_000L
        private const val RETRY_INPUTS_MS = 60_000L
        private val http: OkHttpClient =
            OkHttpClient
                .Builder()
                .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
                .build()

        /**
         * The port a receiver at [host] answers on (8080, else 80) and its model: its device info
         * or its status, whichever it has; null when neither answers on either port.
         */
        fun check(host: String): Pair<Int, String>? {
            for (port in DenonProtocol.PORTS) {
                val c = DenonClient(host, port)
                val model = c.model()
                val status = if (model == null) c.status() else null
                if (model == null && status == null) continue
                return port to (model ?: status?.name ?: "Denon / Marantz")
            }
            return null
        }
    }
}
