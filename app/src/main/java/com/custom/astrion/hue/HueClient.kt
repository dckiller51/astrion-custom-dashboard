package com.custom.astrion.hue

import com.custom.astrion.lgtv.LgTvConnection
import java.io.IOException
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * HTTPS to one Hue bridge (1.6.1), its local CLIP v2 API: the app key made by the bridge's
 * button, its resources, a `PUT` per command, and its event stream. Blocking: callers run it off
 * the main thread.
 *
 * The bridge's certificate names its bridge id, signed by Signify for the bridge alone: it is
 * trusted when it is the one seen at pairing ([pin], its SHA-256) or names the bridge paired
 * ([bridgeId]), so a certificate the bridge renews after a firmware update keeps working.
 */
class HueClient(
    val host: String,
    private val appKey: String? = null,
    private val pin: String? = null,
    private val bridgeId: String? = null
) {
    /** The certificate's fingerprint, once a request has gone through. */
    @Volatile var seenPin: String? = null
        private set

    @Volatile private var streamCall: Call? = null

    private val trust =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                val print = LgTvConnection.fingerprint(cert.encoded)
                val named = bridgeId != null && commonName(cert).equals(bridgeId, ignoreCase = true)
                if (pin != null && pin != print && !named) throw CertificateException("not the bridge paired")
                seenPin = print
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    private val http: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
            .readTimeout(READ_S, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trust)
            // The certificate names the bridge id, not its address: it is checked above instead.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    private val streamHttp: OkHttpClient by lazy { http.newBuilder().readTimeout(STREAM_IDLE_S, TimeUnit.SECONDS).build() }

    private fun url(path: String) = "https://$host$path"

    private fun request(path: String) = Request.Builder().url(url(path)).apply { appKey?.let { header(KEY_HEADER, it) } }

    /** `GET /api/0/config`, open to all: the bridge's name, id, model and version. Null when it isn't a Hue bridge. */
    fun config(): JsonObject? {
        val body = call(request("/api/0/config").get().build())
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        return o.takeIf { it.str("bridgeid") != null }
    }

    sealed class KeyResult {
        data class Created(val appKey: String) : KeyResult()

        /** Error 101: the button on the bridge wasn't pressed in the last 30 seconds. */
        object PressButton : KeyResult()

        data class Refused(val reason: String) : KeyResult()
    }

    /** `POST /api` with [deviceType] ("astrion#salon"): a new app key once the bridge's button was pressed. */
    fun createKey(deviceType: String): KeyResult {
        val payload =
            buildJsonObject {
                put("devicetype", deviceType.take(DEVICE_TYPE_MAX))
                put("generateclientkey", true)
            }.toString()
        val body = call(Request.Builder().url(url("/api")).post(payload.toRequestBody(JSON)).build())
        val first = runCatching { (json.parseToJsonElement(body) as? JsonArray)?.firstOrNull() as? JsonObject }.getOrNull()
        first?.obj("success")?.str("username")?.let { return KeyResult.Created(it) }
        val error = first?.obj("error")
        if (error?.str("type") == LINK_BUTTON) return KeyResult.PressButton
        return KeyResult.Refused(error?.str("description") ?: "unexpected answer")
    }

    /** `GET /clip/v2/resource`: everything the bridge has, as [HueStore.load] reads it. */
    fun all(): String = call(request("/clip/v2/resource").get().build())

    /** `PUT /clip/v2/resource/<type>/<id>`; throws when the bridge refuses it. */
    fun put(type: String, id: String, body: JsonObject) {
        call(request("/clip/v2/resource/$type/$id").put(body.toString().toRequestBody(JSON)).build())
    }

    /**
     * Reads the event stream until it ends or [close]: each message's data (a JSON list of event
     * containers) goes to [onEvents]. Returns why it ended.
     */
    fun stream(onOpen: () -> Unit, onEvents: (String) -> Unit): String {
        val req = request("/eventstream/clip/v2").header("Accept", "text/event-stream").get().build()
        val call = streamHttp.newCall(req)
        streamCall = call
        return try {
            call.execute().use { resp ->
                if (!resp.isSuccessful) return "HTTP ${resp.code}"
                onOpen()
                val source = resp.body?.source() ?: return "no body"
                readEvents(source, onEvents)
                "closed by the bridge"
            }
        } catch (e: IOException) {
            if (call.isCanceled()) "stopped" else e.message ?: e.javaClass.simpleName
        } finally {
            if (streamCall === call) streamCall = null
        }
    }

    /** Server-sent events: `data:` lines gathered until a blank line, then handed over. */
    private fun readEvents(source: okio.BufferedSource, onEvents: (String) -> Unit) {
        val data = StringBuilder()
        var line = source.readUtf8Line()
        while (line != null) {
            if (line.isEmpty()) {
                if (data.isNotEmpty()) onEvents(data.toString())
                data.setLength(0)
            } else if (line.startsWith("data:")) {
                data.append(line.removePrefix("data:").trimStart())
            }
            // "id:", "event:" and ": hi" comments: nothing to keep.
            line = source.readUtf8Line()
        }
    }

    /** Ends [stream]. */
    fun close() {
        streamCall?.cancel()
    }

    private fun call(req: Request): String = http.newCall(req).execute().use { resp ->
        val body = resp.body?.string().orEmpty()
        if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}${errorOf(body)?.let { ": $it" } ?: ""}")
        body
    }

    /** The first error a CLIP v2 answer gives (`{"errors": [{"description": …}]}`). */
    private fun errorOf(body: String): String? =
        runCatching { ((json.parseToJsonElement(body) as? JsonObject)?.get("errors") as? JsonArray)?.firstOrNull() as? JsonObject }
            .getOrNull()
            ?.str("description")

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        private val JSON = "application/json".toMediaType()
        private const val KEY_HEADER = "hue-application-key"
        private const val LINK_BUTTON = "101"
        private const val DEVICE_TYPE_MAX = 40
        private const val CONNECT_S = 5L
        private const val READ_S = 10L

        // The bridge sends nothing while nothing changes: past this, the stream is opened again
        // (and everything read again), in case the connection died without a word.
        private const val STREAM_IDLE_S = 300L

        /** The certificate's CN: a Hue bridge's id ("001788fffe123456"). */
        fun commonName(cert: X509Certificate): String =
            cert.subjectX500Principal.name.split(',').map { it.trim() }.firstOrNull { it.startsWith("CN=", ignoreCase = true) }
                ?.substringAfter('=')
                .orEmpty()
    }
}
