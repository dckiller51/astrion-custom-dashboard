package com.custom.astrion.appletv.airplay

import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * One TCP connection speaking AirPlay's RTSP/HTTP framing, with transport encryption armed
 * partway through (right after pair-verify) by calling [armEncryption]. Used for the control
 * connection and, via [AirPlayChannel], for the event/data channels too — all three are "connect,
 * optionally arm encryption, exchange request/response pairs" with nothing else in common, so
 * this one small class covers all of them.
 */
internal class AirPlayConnection(private val host: String, private val port: Int) : AutoCloseable {
    private var socket: Socket? = null
    private var recordLayer: HapRecordLayer? = null

    fun connect(timeoutMs: Int) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = timeoutMs
        socket = s
    }

    /** Our own address as seen on this connection — pyatv's RTSP session folds this into the
     * `rtsp://<local_ip>/<session_id>` URI it uses for SETUP/RECORD, so callers that build that
     * URI (see [AirPlayRtspSession]) need it too. */
    val localAddress: String get() = (socket ?: throw AirPlayException("not connected")).localAddress.hostAddress.orEmpty()

    /** Arms ChaCha20-Poly1305 transport encryption for everything sent/received from here on. */
    fun armEncryption(writeKey: ByteArray, readKey: ByteArray) {
        recordLayer = HapRecordLayer(writeKey, readKey, "control")
    }

    val isEncrypted: Boolean get() = recordLayer != null

    /**
     * Sends one RTSP/HTTP request and returns its response. No header is added automatically —
     * unlike Companion/MRP's single always-the-same envelope, AirPlay's control connection speaks
     * in two distinct header "dialects" depending on phase (pairing vs. the RTSP session proper),
     * so each caller ([AirPlayPairing]/[AirPlayPairVerify] vs. [AirPlayRtspSession]) supplies
     * exactly the headers that phase needs.
     */
    fun exchange(
        method: String,
        uri: String,
        userAgent: String,
        extraHeaders: Map<String, String> = emptyMap(),
        contentType: String? = null,
        body: ByteArray = ByteArray(0)
    ): RtspHttp.Response {
        val s = socket ?: throw AirPlayException("not connected")
        val request = RtspHttp.formatRequest(method, uri, userAgent, extraHeaders, contentType, body)
        val layer = recordLayer
        try {
            if (layer == null) {
                s.outputStream.write(request)
                s.outputStream.flush()
                return RtspHttp.readOneResponse(s.inputStream)
            }
            layer.encryptTo(s.outputStream, request)
            s.outputStream.flush()
            return readEncryptedResponse(layer)
        } catch (e: SocketTimeoutException) {
            throw AirPlayException("timed out waiting for a response to $method $uri", e)
        }
    }

    /** Reassembles one or more HAP frames (a response can span frames once it's past ~1KB) back
     * into the plain RTSP response they encode, then parses it. */
    private fun readEncryptedResponse(layer: HapRecordLayer): RtspHttp.Response {
        val s = socket ?: throw AirPlayException("not connected")
        val buffer = java.io.ByteArrayOutputStream()
        buffer.write(layer.decryptOneFrame(s.inputStream))
        // A response's Content-Length isn't knowable until its headers are fully buffered, so
        // frames are pulled in and reassembled until the whole thing parses cleanly.
        while (true) {
            val attempt = runCatching { RtspHttp.readOneResponse(java.io.ByteArrayInputStream(buffer.toByteArray())) }
            val response = attempt.getOrNull()
            if (response != null) return response
            buffer.write(layer.decryptOneFrame(s.inputStream))
        }
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
