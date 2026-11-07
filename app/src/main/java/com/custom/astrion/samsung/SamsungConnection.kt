package com.custom.astrion.samsung

import com.custom.astrion.lgtv.LgTvConnection
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener

/**
 * The remote WebSocket to one Samsung TV (1.5.3), see [SamsungProtocol]. Over `wss://` the TV's
 * certificate is its own, signed by no one: it is trusted the first time (its fingerprint kept as
 * [pin]) and must be the same after, as for LG TVs.
 *
 * Callbacks come on OkHttp's threads.
 */
class SamsungConnection(
    private val host: String,
    private val token: String?,
    private val pin: String?,
    private val tls: Boolean,
    private val listener: Listener
) {
    interface Listener {
        /** Allowed: [token] when the TV gave a new one, [pin] the certificate's fingerprint (null over `ws://`). */
        fun onConnected(token: String?, pin: String?)

        fun onApps(apps: List<SamsungProtocol.App>) {}

        /** Closed, refused or lost; [reason] for the log ("denied" when refused on the TV). */
        fun onClosed(reason: String)
    }

    @Volatile private var socket: WebSocket? = null

    @Volatile private var closed = false

    @Volatile var seenPin: String? = null
        private set

    /** The one icon being asked for: the TV answers them in order, without saying which. */
    private val iconLock = Any()

    @Volatile private var iconWait: Pair<CountDownLatch, AtomicReference<ByteArray?>>? = null

    private val trust =
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                val cert = chain?.firstOrNull() ?: throw CertificateException("no certificate")
                val print = LgTvConnection.fingerprint(cert.encoded)
                if (pin != null && pin != print) throw CertificateException("not the TV paired")
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
        socket = http.newWebSocket(Request.Builder().url(SamsungProtocol.remoteUrl(host, token, tls)).build(), socketListener)
    }

    private val socketListener =
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                val e = SamsungProtocol.event(text) ?: return
                when (e.name) {
                    SamsungProtocol.CONNECT -> listener.onConnected(SamsungProtocol.tokenOf(e), if (tls) seenPin else null)
                    SamsungProtocol.UNAUTHORIZED -> fail("denied")
                    SamsungProtocol.TIMEOUT -> fail("not allowed on the TV in time")
                    SamsungProtocol.APPS_EVENT -> SamsungProtocol.apps(e)?.let(listener::onApps)
                    SamsungProtocol.ICON_EVENT ->
                        iconWait?.let { (latch, value) ->
                            value.set(SamsungProtocol.icon(e))
                            latch.countDown()
                        }
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(NORMAL, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                fail("closed ($code)")
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                fail(t.message ?: t.javaClass.simpleName)
            }
        }

    private fun fail(reason: String) {
        if (closed) return
        closed = true
        socket?.cancel()
        iconWait?.first?.countDown()
        listener.onClosed(reason)
    }

    fun send(text: String): Boolean = socket?.send(text) == true

    fun key(code: String): Boolean = send(SamsungProtocol.key(code))

    /** A long press: pressed, held [ms], released. Blocks the caller's thread that long. */
    fun hold(code: String, ms: Long) {
        send(SamsungProtocol.key(code, "Press"))
        Thread.sleep(ms)
        send(SamsungProtocol.key(code, "Release"))
    }

    fun requestApps(): Boolean = send(SamsungProtocol.appsRequest())

    fun launch(app: SamsungProtocol.App): Boolean = send(SamsungProtocol.launch(app.id, app.type))

    /** An app's icon from the TV (its `icon` path); null after [ICON_S] seconds. */
    fun icon(path: String): ByteArray? = synchronized(iconLock) {
        val latch = CountDownLatch(1)
        val value = AtomicReference<ByteArray?>(null)
        iconWait = latch to value
        try {
            if (!send(SamsungProtocol.iconRequest(path))) return null
            latch.await(ICON_S, TimeUnit.SECONDS)
            value.get()
        } finally {
            iconWait = null
        }
    }

    fun close() {
        closed = true
        socket?.close(NORMAL, null)
        iconWait?.first?.countDown()
    }

    private companion object {
        const val CONNECT_S = 5L
        const val PING_S = 20L
        const val NORMAL = 1000
        const val ICON_S = 4L
    }
}
