package com.custom.astrion.appletv

import java.io.DataInputStream
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Any protocol-level failure talking to an Apple TV (auth rejected, timeout, device-side error, ...). */
class CompanionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Companion-link frame types (first byte of every frame). */
internal enum class FrameType(val id: Int) {
    Unknown(0),
    NoOp(1),
    PsStart(3),
    PsNext(4),
    PvStart(5),
    PvNext(6),
    UOpack(7),
    EOpack(8),
    POpack(9);

    companion object {
        fun fromId(id: Int): FrameType = entries.firstOrNull { it.id == id } ?: Unknown
    }
}

/**
 * One TCP connection to an Apple TV's Companion service: 4-byte frame
 * headers (`type`, 3-byte big-endian length), OPACK payloads, and — once
 * pair-verify has produced keys — ChaCha20-Poly1305 encryption of every
 * non-empty payload with a per-direction little-endian frame counter as
 * the nonce and the frame header as AAD.
 *
 * Blocking I/O with a single reader thread; callers use [exchange] /
 * [exchangeAuth] which block the calling thread until the matching reply
 * arrives (or a timeout hits), so this must not be driven from a UI thread.
 */
internal class CompanionLink(private val host: String, private val port: Int, private val listener: Listener) {
    interface Listener {
        fun onEvent(name: String, content: Map<*, *>)

        fun onClosed(error: Throwable?)
    }

    private class Crypto(val outKey: ByteArray, val inKey: ByteArray) {
        var outCounter = 0L
        var inCounter = 0L
    }

    companion object {
        private const val AUTH_TAG = 16
        private const val HEADER = 4
        private const val MAX_FRAME = 0xFFFFFF
        const val DEFAULT_TIMEOUT_MS = 5000L
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<Any, CompletableFuture<Map<*, *>>>()
    private val xid = AtomicLong((0..0xFFFF).random().toLong())

    @Volatile private var crypto: Crypto? = null

    @Volatile private var armed: Crypto? = null

    val isOpen: Boolean get() = !closed.get() && socket?.isConnected == true

    fun connect(timeoutMs: Int = 5000) {
        val s = Socket()
        s.tcpNoDelay = true
        s.keepAlive = true
        try {
            s.connect(InetSocketAddress(host, port), timeoutMs)
        } catch (e: IOException) {
            runCatching { s.close() }
            throw CompanionException("Cannot connect to $host:$port: ${e.message}", e)
        }
        socket = s
        input = DataInputStream(s.getInputStream().buffered())
        Thread({ readLoop() }, "companion-$host").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Arms transport encryption: it switches on (for both directions) at the
     * moment the *next* pair-verify reply frame arrives — i.e. the answer to
     * the final verify message, which is still sent and received in clear.
     */
    fun armEncryption(outputKey: ByteArray, inputKey: ByteArray) {
        armed = Crypto(outputKey, inputKey)
    }

    fun close() {
        shutdown(null)
    }

    // ---- requests -------------------------------------------------------------

    /** Sends an authentication frame and waits for its reply (PS/PV frames carry no XID). */
    fun exchangeAuth(type: FrameType, data: Map<String, Any?>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Map<*, *> {
        val replyKey =
            when (type) {
                FrameType.PsStart -> FrameType.PsNext
                FrameType.PvStart -> FrameType.PvNext
                else -> type
            }
        val payload = LinkedHashMap(data)
        payload["_x"] = xid.getAndIncrement()
        return roundTrip(type, payload, replyKey, timeoutMs)
    }

    /** Sends a Companion request (`_t` = 2) and returns its response, throwing if the device reports an error. */
    fun exchange(identifier: String, content: Map<String, Any?>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Map<*, *> {
        val id = xid.getAndIncrement()
        val payload = linkedMapOf<String, Any?>("_i" to identifier, "_t" to 2L, "_c" to content, "_x" to id)
        val response = roundTrip(FrameType.EOpack, payload, id, timeoutMs)
        val error = response["_em"]
        if (error != null) throw CompanionException("$identifier failed: $error")
        return response
    }

    /** Sends a fire-and-forget event (`_t` = 1). */
    fun sendEvent(identifier: String, content: Map<String, Any?>) {
        val payload = linkedMapOf<String, Any?>("_i" to identifier, "_t" to 1L, "_c" to content, "_x" to xid.getAndIncrement())
        send(FrameType.EOpack, Opack.pack(payload))
    }

    private fun roundTrip(type: FrameType, payload: Map<String, Any?>, key: Any, timeoutMs: Long): Map<*, *> {
        val future = CompletableFuture<Map<*, *>>()
        pending[key] = future
        try {
            send(type, Opack.pack(payload))
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: TimeoutException) {
            throw CompanionException("Timed out waiting for reply from $host", e)
        } catch (e: ExecutionException) {
            throw (e.cause as? CompanionException) ?: CompanionException("Request failed: ${e.cause?.message}", e)
        } finally {
            pending.remove(key, future)
        }
    }

    // ---- framing --------------------------------------------------------------

    /** Builds one wire frame (header + optionally-encrypted body) for [data]; throws if it can't be framed. */
    private fun frameFor(type: FrameType, data: ByteArray): ByteArray {
        val c = crypto?.takeIf { data.isNotEmpty() }
        val length = data.size + if (c != null) AUTH_TAG else 0
        if (length > MAX_FRAME) throw CompanionException("Frame too large")
        val header = byteArrayOf(type.id.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte())
        val body = if (c != null) HapCrypto.chachaEncrypt(c.outKey, nonce(c.outCounter++), data, header) else data
        return header + body
    }

    private fun send(type: FrameType, data: ByteArray) {
        synchronized(writeLock) {
            val out = socket?.getOutputStream() ?: throw CompanionException("Not connected")
            val frame = frameFor(type, data)
            try {
                out.write(frame)
                out.flush()
            } catch (e: IOException) {
                shutdown(e)
                throw CompanionException("Send failed: ${e.message}", e)
            }
        }
    }

    private fun nonce(counter: Long): ByteArray = ByteArray(12) { i -> if (i < 8) ((counter ushr (8 * i)) and 0xFF).toByte() else 0 }

    /** Switches transport encryption on for both directions once the pair-verify reply ([FrameType.PvNext]) comes in. */
    private fun activateArmedEncryption(type: FrameType) {
        val pending = armed ?: return
        if (type != FrameType.PvNext) return
        crypto = pending
        armed = null
    }

    private fun readOneFrame(inp: DataInputStream, header: ByteArray) {
        inp.readFully(header)
        val length = ((header[1].toInt() and 0xFF) shl 16) or ((header[2].toInt() and 0xFF) shl 8) or (header[3].toInt() and 0xFF)
        val payload = ByteArray(length)
        inp.readFully(payload)
        val type = FrameType.fromId(header[0].toInt() and 0xFF)
        val plain = decrypt(header, payload)
        activateArmedEncryption(type)
        dispatch(type, plain)
    }

    private fun readLoop() {
        try {
            val inp = input ?: return
            val header = ByteArray(HEADER)
            while (!closed.get()) {
                readOneFrame(inp, header)
            }
        } catch (e: Exception) {
            shutdown(e)
        }
    }

    private fun decrypt(header: ByteArray, payload: ByteArray): ByteArray {
        val c = crypto
        if (c == null || payload.isEmpty()) return payload
        return HapCrypto.chachaDecrypt(c.inKey, nonce(c.inCounter++), payload, header.copyOf())
    }

    private fun dispatch(type: FrameType, data: ByteArray) {
        if (type !in
            listOf(
                FrameType.PsStart,
                FrameType.PsNext,
                FrameType.PvStart,
                FrameType.PvNext,
                FrameType.UOpack,
                FrameType.EOpack,
                FrameType.POpack
            )
        ) {
            return
        }
        if (data.isEmpty()) return
        val message = Opack.unpack(data) as? Map<*, *> ?: return
        when (type) {
            FrameType.PsNext, FrameType.PsStart, FrameType.PvNext, FrameType.PvStart -> {
                val key = if (type == FrameType.PsStart) {
                    FrameType.PsNext
                } else if (type == FrameType.PvStart) {
                    FrameType.PvNext
                } else {
                    type
                }
                pending.remove(key)?.complete(message)
            }
            else -> dispatchOpack(message)
        }
    }

    private fun dispatchOpack(message: Map<*, *>) {
        when ((message["_t"] as? Long)) {
            1L -> {
                val name = message["_i"] as? String ?: return
                val content = message["_c"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
                runCatching { listener.onEvent(name, content) }
            }
            3L -> {
                val id = message["_x"] as? Long ?: return
                pending.remove(id)?.complete(message)
            }
        }
    }

    private fun shutdown(error: Throwable?) {
        if (!closed.compareAndSet(false, true)) return
        runCatching { socket?.close() }
        val failure = CompanionException("Connection to $host closed" + (error?.message?.let { ": $it" } ?: ""), error)
        pending.values.forEach { it.completeExceptionally(failure) }
        pending.clear()
        runCatching { listener.onClosed(error) }
    }
}
