package com.custom.astrion.sonos

import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Sonos speakers' local API (1.6.3): SOAP on port 1400, the device description, and SSDP to find
 * one. Blocking: callers run it off the main thread.
 */
object SonosClient {
    /** The speaker answered with a SOAP fault (an action it can't do now, e.g. Next on a radio). */
    class Fault(code: String) : IOException("UPnP error $code")

    private val XML = "text/xml; charset=\"utf-8\"".toMediaType()
    private val ERROR_CODE = Regex("<errorCode>(\\d+)</errorCode>")

    private val http: OkHttpClient by lazy {
        OkHttpClient
            .Builder()
            .connectTimeout(2, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
    }

    private fun url(host: String, path: String) = "http://$host:${SonosProtocol.PORT}$path"

    /** One SOAP call to the speaker at [host]; the response body. */
    fun call(host: String, call: SonosCall): String {
        val body = SonosProtocol.envelope(call.service, call.action, call.args).toRequestBody(XML)
        val request =
            Request
                .Builder()
                .url(url(host, call.service.path))
                .header("SOAPACTION", SonosProtocol.soapAction(call.service, call.action))
                .post(body)
                .build()
        return http.newCall(request).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                throw ERROR_CODE.find(text)?.let { Fault(it.groupValues[1]) } ?: IOException("HTTP ${resp.code}")
            }
            text
        }
    }

    fun description(host: String): SonosModel =
        http.newCall(Request.Builder().url(url(host, "/xml/device_description.xml")).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("HTTP ${resp.code}")
            SonosProtocol.model(resp.body?.string().orEmpty())
        }

    /** The household's speakers, asked to the one at [host]. */
    fun speakers(host: String): List<SonosSpeaker> = SonosProtocol.speakers(call(host, SonosProtocol.topology("")))

    /**
     * SSDP M-SEARCH for Sonos players: the addresses that answered within [timeoutMs]. The answers
     * are unicast to this socket, so no multicast lock is needed.
     */
    fun discover(timeoutMs: Int = DISCOVER_MS): List<String> {
        val search =
            "M-SEARCH * HTTP/1.1\r\nHOST: $SSDP_ADDRESS:$SSDP_PORT\r\nMAN: \"ssdp:discover\"\r\nMX: 1\r\n" +
                "ST: urn:schemas-upnp-org:device:ZonePlayer:1\r\n\r\n"
        val found = LinkedHashSet<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = RECEIVE_MS
            val group = InetAddress.getByName(SSDP_ADDRESS)
            val bytes = search.toByteArray()
            repeat(2) { socket.send(DatagramPacket(bytes, bytes.size, group, SSDP_PORT)) }
            val deadline = System.currentTimeMillis() + timeoutMs
            val buffer = ByteArray(BUFFER)
            while (System.currentTimeMillis() < deadline) receive(socket, buffer)?.let { found.add(it) }
        }
        return found.toList()
    }

    /** One SSDP answer's address when a Sonos player sent it; null on a timeout or another device. */
    private fun receive(socket: DatagramSocket, buffer: ByteArray): String? {
        val packet = DatagramPacket(buffer, buffer.size)
        try {
            socket.receive(packet)
        } catch (_: SocketTimeoutException) {
            return null
        }
        val text = String(packet.data, 0, packet.length)
        return if (text.contains("Sonos", ignoreCase = true)) packet.address?.hostAddress else null
    }

    private const val SSDP_ADDRESS = "239.255.255.250"
    private const val SSDP_PORT = 1900
    private const val DISCOVER_MS = 3_000
    private const val RECEIVE_MS = 500
    private const val BUFFER = 2048
}
