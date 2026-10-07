package com.custom.astrion.extender

import android.util.Log
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One line of a batched /pronto POST — [delayBeforeMs] (0 = no delay) is
 * how long the extender's firmware should wait, after finishing whatever
 * came before it in the same request, before transmitting [prontoCode]. */
data class ExtenderBatchCommand(val delayBeforeMs: Int, val prontoCode: String)

/**
 * Talks to a single Astrion IR Extender (see the astrion-ir-extender
 * project) over plain HTTP on the LAN — no Home Assistant, no cloud, no
 * native/protobuf API client, matching the same "works standalone" goal
 * as [com.custom.astrion.harmony.HarmonyHubClient].
 *
 * The extender's firmware exposes a single custom endpoint, `POST
 * /pronto`, accepting either one raw Pronto code as the whole plain-text
 * body, or several newline-separated codes (each optionally prefixed
 * `<delay_ms>>`) batched into one request — not one of ESPHome's native
 * `web_server`-exposed entities, specifically so it isn't bound by their
 * 255-character schema limit (many real captured Pronto codes exceed
 * that).
 */
class ExtenderClient(
    private val host: String,
    private val onError: (message: String) -> Unit = {}
) {
    private companion object {
        const val TAG = "ExtenderClient"
    }

    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()

    private val textMediaType = "text/plain".toMediaType()

    /** Sends a single [prontoCode] to this extender. Returns true if the
     * extender accepted it (HTTP 200) — this only confirms the code was
     * received and queued, not that the physical IR transmission itself
     * succeeded (the extender transmits asynchronously after responding,
     * see astrion-ir-extender's AstrionHttpEndpoint). Logs and reports
     * failures via [onError] rather than throwing, matching every other
     * network call site in this app (Harmony, HA) — a single unreachable
     * extender should never crash the button that tried to use it. */
    suspend fun send(prontoCode: String): Boolean = sendBody(prontoCode)

    /** Sends several [commands] in one request, letting the extender's
     * own firmware handle the inter-command timing (see
     * [ExtenderBatchCommand.delayBeforeMs]) instead of this app doing N
     * separate HTTP round-trips. This is what actually fixes commands
     * getting silently dropped when several arrive close together (e.g.
     * an Activity switch touching multiple devices on the same
     * extender) — a fast second POST used to be able to overwrite a
     * first one the extender hadn't transmitted yet. */
    suspend fun sendBatch(commands: List<ExtenderBatchCommand>): Boolean {
        if (commands.isEmpty()) return true
        val body =
            commands.joinToString("\n") { cmd ->
                if (cmd.delayBeforeMs > 0) "${cmd.delayBeforeMs}>${cmd.prontoCode}" else cmd.prontoCode
            }
        return sendBody(body)
    }

    private suspend fun sendBody(body: String): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val request =
                Request
                    .Builder()
                    .url("http://$host/pronto")
                    .post(body.toRequestBody(textMediaType))
                    .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val msg = "HTTP ${response.code} from extender at $host"
                    Log.w(TAG, msg)
                    onError(msg)
                    return@withContext false
                }
                true
            }
        }.getOrElse { e ->
            val msg = "Couldn't reach extender at $host: ${e.message}"
            Log.w(TAG, msg, e)
            onError(msg)
            false
        }
    }
}
