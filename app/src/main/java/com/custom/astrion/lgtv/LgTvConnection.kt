package com.custom.astrion.lgtv

import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * One WebSocket connection to an LG webOS TV (1.5.2), see [LgTvProtocol]: `ws://` on port 3000
 * first, else `wss://` on 3001 (recent firmwares). The TV's certificate is its own, not signed by
 * anyone: it is trusted the first time (its fingerprint kept as [pin]) and must be the same after,
 * as Apple and Android TVs are pinned to the one paired.
 *
 * Callbacks come on OkHttp's threads.
 */
class LgTvConnection(
    private val host: String,
    private val clientKey: String?,
    private val pin: String?,
    private val listener: Listener
) {
    interface Listener {
        /** The TV shows its prompt (a remote it doesn't know yet). */
        fun onPrompt() {}

        /** Registered: [clientKey] to keep, [pin] the certificate's fingerprint (null over `ws://`). */
        fun onRegistered(clientKey: String, pin: String?)

        /** Closed, refused or lost; [reason] for the log. */
        fun onClosed(reason: String)
    }

    private val ids = AtomicInteger()
    private val answers = ConcurrentHashMap<String, (LgTvProtocol.Incoming) -> Unit>()
    private val subscriptions = ConcurrentHashMap<String, (JsonObject) -> Unit>()

    @Volatile private var main: WebSocket? = null

    @Volatile private var pointer: WebSocket? = null

    @Volatile private var closed = false

    @Volatile private var tls = false

    @Volatile private var opened = false

    /** The fingerprint of the certificate the TV showed, once over `wss://`. */
    @Volatile var seenPin: String? = null
        private set

    private val trust =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw java.security.cert.CertificateException("no certificate")
                val print = fingerprint(cert.encoded)
                if (pin != null && pin != print) throw java.security.cert.CertificateException("not the TV paired")
                seenPin = print
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }

    private val http: OkHttpClient by lazy {
        val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        OkHttpClient
            .Builder()
            .connectTimeout(CONNECT_S, TimeUnit.SECONDS)
            .readTimeout(0, TimeUnit.SECONDS)
            .pingInterval(PING_S, TimeUnit.SECONDS)
            .sslSocketFactory(ssl.socketFactory, trust)
            // The TV's certificate names no host: it is checked by its fingerprint instead.
            .hostnameVerifier { _, _ -> true }
            .build()
    }

    fun connect() {
        open(tlsFirst = false)
    }

    private fun open(tlsFirst: Boolean) {
        tls = tlsFirst
        opened = false
        val url = if (tlsFirst) "wss://$host:${LgTvProtocol.WSS_PORT}" else "ws://$host:${LgTvProtocol.WS_PORT}"
        main = http.newWebSocket(Request.Builder().url(url).build(), mainListener)
    }

    private val mainListener =
        object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened = true
                webSocket.send(LgTvProtocol.register(clientKey))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val m = LgTvProtocol.parse(text) ?: return
                when {
                    LgTvProtocol.isPrompt(m) -> listener.onPrompt()
                    m.id == LgTvProtocol.REGISTER_ID && m.type == "registered" ->
                        LgTvProtocol.clientKeyOf(m)?.let { listener.onRegistered(it, if (tls) seenPin else null) }
                    m.id == LgTvProtocol.REGISTER_ID && m.type == "error" -> fail("refused: ${m.error ?: "?"}")
                    else -> dispatch(m)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail("closed ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                // Port 3000 refused or not a WebSocket any more (recent firmwares): 3001 over TLS.
                if (!opened && !tls && !closed) {
                    open(tlsFirst = true)
                } else {
                    fail(t.message ?: t.javaClass.simpleName)
                }
            }
        }

    private fun dispatch(m: LgTvProtocol.Incoming) {
        val id = m.id ?: return
        subscriptions[id]?.let { handler ->
            if (m.type != "error") handler(m.payload)
            return
        }
        answers.remove(id)?.invoke(m)
    }

    private fun fail(reason: String) {
        if (closed) return
        closed = true
        pointer?.cancel()
        main?.cancel()
        listener.onClosed(reason)
    }

    /** `ssap://[uri]`; [answer] gets the TV's answer (an `error` too). */
    fun request(uri: String, payload: JsonObject? = null, answer: ((LgTvProtocol.Incoming) -> Unit)? = null): Boolean {
        val id = "r" + ids.incrementAndGet()
        answer?.let { answers[id] = it }
        return main?.send(LgTvProtocol.message(id, uri, payload)) == true
    }

    /** `ssap://[uri]` now and at every change. */
    fun subscribe(uri: String, handler: (JsonObject) -> Unit): Boolean {
        val id = "s" + ids.incrementAndGet()
        subscriptions[id] = handler
        return main?.send(LgTvProtocol.message(id, uri, subscribe = true)) == true
    }

    /** Opens the button socket the TV hands out; buttons wait for it. */
    fun openPointer(onReady: () -> Unit = {}) {
        request(LgTvProtocol.INPUT_SOCKET) { m ->
            val path = LgTvProtocol.socketPath(m.payload) ?: return@request
            pointer =
                http.newWebSocket(
                    Request.Builder().url(path).build(),
                    object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) = onReady()

                        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                            pointer = null
                        }

                        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                            pointer = null
                        }
                    }
                )
        }
    }

    /** One remote button; false when the button socket isn't open (it is asked for again). */
    fun button(name: String): Boolean {
        val p = pointer
        if (p != null && p.send(LgTvProtocol.button(name))) return true
        openPointer { pointer?.send(LgTvProtocol.button(name)) }
        return false
    }

    /** An icon or a picture the TV serves (`http(s)://<tv>:3000|3001/resources/…`), over this TV's pinned client. */
    fun download(url: String): ByteArray? = runCatching {
        http.newCall(Request.Builder().url(url).build()).execute().use { r -> if (r.isSuccessful) r.body?.bytes() else null }
    }.getOrNull()

    fun close() {
        closed = true
        pointer?.close(NORMAL, null)
        main?.close(NORMAL, null)
    }

    companion object {
        private const val CONNECT_S = 5L
        private const val PING_S = 20L
        private const val NORMAL = 1000

        fun fingerprint(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }
    }
}
