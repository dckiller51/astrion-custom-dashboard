package com.custom.astrion.cards.impl

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.Toast
import com.custom.astrion.R
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** The Plex server a film is read from. */
internal data class PlexServer(val host: String, val token: String)

/** MrMC on an Apple TV: its address for JSON-RPC, the player that opens it, its name there. */
internal data class MrmcTarget(val host: String, val port: Int, val mediaEntity: String, val app: String)

/**
 * Plays a Plex film with MrMC (1.5.9, see [PlexMrmc]): MrMC opened on the Apple TV when it doesn't
 * answer yet, then asked to play the film's file from the Plex server, with a token for it alone.
 * Runs on its own scope: closing the card's pop-up doesn't stop a film on its way.
 */
internal object MrmcPlayer {
    private const val TAG = "MrmcPlayer"
    private const val WAIT_MS = 20_000L
    private const val STEP_MS = 1_000L
    private const val TIMEOUT_S = 4L
    private val JSON = "application/json".toMediaType()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_S, TimeUnit.SECONDS)
            .build()

    fun play(context: Context, client: HaClient, server: PlexServer, mrmc: MrmcTarget, ratingKey: String) {
        val app = context.applicationContext
        scope.launch {
            val part = get(server.host + PlexMrmc.itemPath(ratingKey), server.token)?.let(PlexMrmc::partKey)
            if (part == null) {
                Log.i(TAG, "nothing to play in $ratingKey")
                toast(app, app.getString(R.string.plex_mrmc_no_file))
                return@launch
            }
            if (!awake(client, mrmc)) {
                Log.i(TAG, "MrMC didn't answer on ${mrmc.host}:${mrmc.port}")
                toast(app, app.getString(R.string.plex_mrmc_no_answer, mrmc.host))
                return@launch
            }
            val token =
                get(server.host + "/security/token?type=delegation&scope=all", server.token)?.let(PlexMrmc::delegatedToken)
                    ?: server.token.also { Log.i(TAG, "no delegated token from the server: the account's goes to MrMC") }
            val ok = rpc(mrmc, PlexMrmc.open(PlexMrmc.fileUrl(server.host, part, token)))?.let(PlexMrmc::isOk) == true
            Log.i(TAG, "play $part with MrMC on ${mrmc.host}: ${if (ok) "OK" else "refused"}")
            if (!ok) toast(app, app.getString(R.string.plex_mrmc_refused))
        }
    }

    /** MrMC answering: at once when it's on screen, else opened on the Apple TV and waited for. */
    private suspend fun awake(client: HaClient, mrmc: MrmcTarget): Boolean {
        if (ping(mrmc)) return true
        main.post { client.callService(ServiceCall.of("media_player", "select_source", mrmc.mediaEntity, "source" to mrmc.app)) }
        var waited = 0L
        while (waited < WAIT_MS) {
            delay(STEP_MS)
            waited += STEP_MS
            if (ping(mrmc)) return true
        }
        return false
    }

    private fun ping(mrmc: MrmcTarget) = rpc(mrmc, PlexMrmc.PING)?.let(PlexMrmc::isPong) == true

    private fun rpc(mrmc: MrmcTarget, body: String): String? = call(
        Request
            .Builder()
            .url("http://${mrmc.host}:${mrmc.port}/jsonrpc")
            .post(body.toRequestBody(JSON))
            .build()
    )

    private fun get(url: String, token: String): String? = call(
        Request
            .Builder()
            .url(url)
            .header("Accept", "application/json")
            .header("X-Plex-Token", token)
            .build()
    )

    private fun call(req: Request): String? = try {
        http.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
    } catch (_: IOException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }

    private fun toast(context: Context, text: String) {
        main.post { Toast.makeText(context, text, Toast.LENGTH_LONG).show() }
    }
}
