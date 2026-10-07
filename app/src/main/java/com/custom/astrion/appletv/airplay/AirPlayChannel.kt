package com.custom.astrion.appletv.airplay

import java.net.InetSocketAddress
import java.net.Socket

/**
 * A single outbound TCP connection for the AirPlay remote-control session's event or data
 * channel: connect, arm HAP record-layer encryption with this channel's own derived keys, then
 * send/receive whole encrypted frames. Unlike the control connection (RTSP request/response),
 * these channels are fire-and-forget/push: [send] writes one frame, [receiveFrame] blocks for the
 * next one — there's no status-line framing here at all, just raw HAP frames end to end.
 */
internal class AirPlayChannel(private val host: String, private val port: Int, private val label: String = "?") : AutoCloseable {
    private var socket: Socket? = null
    private lateinit var recordLayer: HapRecordLayer
    private val writeLock = Any()

    fun connect(timeoutMs: Int, writeKey: ByteArray, readKey: ByteArray) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), timeoutMs)
        s.soTimeout = 0 // this channel is read by a dedicated blocking reader thread, not request/response
        socket = s
        recordLayer = HapRecordLayer(writeKey, readKey, label)
    }

    /**
     * Synchronized because, on the data channel, this is now called both from whatever thread
     * sends outgoing MRP requests and from the dedicated reader thread (to send the bare "rply"
     * transport acks — see [AirPlayMrpClient]'s data-channel handling): without this lock, two
     * concurrent frames could interleave their bytes on the wire, or race on the record layer's
     * own write counter and so its nonce, corrupting encryption for everything sent afterwards.
     */
    fun send(plain: ByteArray) {
        synchronized(writeLock) {
            val s = socket ?: throw AirPlayException("channel not connected")
            recordLayer.encryptTo(s.outputStream, plain)
            s.outputStream.flush()
        }
    }

    /** Blocks for exactly one decrypted frame; throws once the socket closes. */
    fun receiveFrame(): ByteArray {
        val s = socket ?: throw AirPlayException("channel not connected")
        return recordLayer.decryptOneFrame(s.inputStream)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }
}
