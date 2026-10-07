package com.custom.astrion.appletv.mrp

import com.custom.astrion.appletv.CompanionException
import com.custom.astrion.appletv.HapCrypto
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

/**
 * One TCP connection to an Apple TV's MRP (Media Remote Protocol) service: a varint-encoded
 * length prefix followed by a serialized `ProtocolMessage`, optionally ChaCha20-Poly1305
 * encrypted once pair-verify has armed transport keys.
 *
 * The one real difference from [com.custom.astrion.appletv.CompanionLink]'s framing (besides the
 * length prefix shape) is the nonce: MRP zero-pads the *front* of the 12 bytes and puts the
 * little-endian counter in the last 8 (`Chacha20Cipher8byteNonce` in pyatv), the reverse of
 * Companion's plain 12-byte little-endian counter — confirmed against pyatv's own source rather
 * than assumed, since getting this backwards would fail silently as garbled decryption.
 */
class MrpConnection(private val host: String, private val port: Int, private val listener: Listener) {
    interface Listener {
        /** An MRP message arrived that wasn't the reply to something we sent (e.g. a pushed `SetStateMessage`). */
        fun onMessage(message: MrpMessage)

        fun onClosed(error: Throwable?)
    }

    private class Crypto(val outKey: ByteArray, val inKey: ByteArray) {
        var outCounter = 0L
        var inCounter = 0L
    }

    companion object {
        const val DEFAULT_TIMEOUT_MS = 5000L
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private val writeLock = Any()
    private val closed = AtomicBoolean(false)

    // Requests matched by a generated `identifier` string (most message types), or — during
    // pairing, which carries no identifier — by a synthetic "type_<n>" key, mirroring pyatv.
    private val pending = ConcurrentHashMap<String, CompletableFuture<MrpMessage>>()

    @Volatile private var crypto: Crypto? = null

    // Keys waiting to switch on: see [armEncryptionAfterNextPairingReply].
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
        Thread({ readLoop() }, "mrp-$host").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Switches ChaCha20-Poly1305 on, for both directions, right after the *next* crypto-pairing
     * reply arrives — i.e. the answer to pair-verify's last message, which itself is still in the
     * clear. Doing the switch on the reader thread, before that reply is handed to the caller,
     * closes the window where the caller could send its next message (which must be encrypted)
     * before the switch happened.
     */
    fun armEncryptionAfterNextPairingReply(outputKey: ByteArray, inputKey: ByteArray) {
        armed = Crypto(outputKey, inputKey)
    }

    fun close(error: Throwable? = null) = shutdown(error)

    /** Sends a pre-built `ProtocolMessage` and waits for the reply matched by its `identifier`. */
    fun sendAndReceive(payload: ByteArray, identifier: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MrpMessage =
        roundTrip(payload, identifier, timeoutMs)

    /** Same, but matched by message type instead of an identifier — used only during pairing
     * (see [MrpMessages.cryptoPairing], which carries no `identifier`). */
    fun sendAndReceiveByType(payload: ByteArray, type: Int, timeoutMs: Long = DEFAULT_TIMEOUT_MS): MrpMessage =
        roundTrip(payload, "type_$type", timeoutMs)

    /** Fire-and-forget send (heartbeats/events where no reply is expected). */
    fun send(payload: ByteArray) = write(payload)

    private fun roundTrip(payload: ByteArray, key: String, timeoutMs: Long): MrpMessage {
        val future = CompletableFuture<MrpMessage>()
        pending[key] = future
        try {
            write(payload)
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

    /** MRP's transport nonce: 4 zero bytes, then an 8-byte little-endian counter
     * (`Chacha20Cipher8byteNonce` in pyatv — the opposite byte order from Companion's). */
    private fun nonce(counter: Long): ByteArray = ByteArray(12) { i -> if (i < 4) 0 else ((counter ushr (8 * (i - 4))) and 0xFF).toByte() }

    private fun write(payload: ByteArray) {
        synchronized(writeLock) {
            val out = socket?.getOutputStream() ?: throw CompanionException("Not connected")
            val c = crypto
            val body = if (c != null) HapCrypto.chachaEncrypt(c.outKey, nonce(c.outCounter++), payload) else payload
            try {
                out.write(Varint.encode(body.size.toLong()))
                out.write(body)
                out.flush()
            } catch (e: IOException) {
                shutdown(e)
                throw CompanionException("Send failed: ${e.message}", e)
            }
        }
    }

    /** Switches transport encryption on for both directions once the pair-verify reply (a `CryptoPairingMessage`) comes in. */
    private fun activateArmedEncryption(type: Int) {
        val pending = armed ?: return
        if (type != Mrp.TYPE_CRYPTO_PAIRING) return
        crypto = pending
        armed = null
    }

    private fun readOneFrame(inp: DataInputStream) {
        val length = Varint.readFrom(inp)
        val body = ByteArray(length)
        inp.readFully(body)
        val c = crypto
        val plain = if (c != null) HapCrypto.chachaDecrypt(c.inKey, nonce(c.inCounter++), body) else body
        val message = MrpMessage.parse(plain)
        activateArmedEncryption(message.type)
        dispatch(message)
    }

    private fun readLoop() {
        try {
            val inp = input ?: return
            while (!closed.get()) {
                readOneFrame(inp)
            }
        } catch (e: Exception) {
            shutdown(e)
        }
    }

    private fun dispatch(message: MrpMessage) {
        val byIdentifier = message.identifier?.let { pending.remove(it) }
        if (byIdentifier != null) {
            byIdentifier.complete(message)
            return
        }
        val byType = pending.remove("type_${message.type}")
        if (byType != null) {
            byType.complete(message)
            return
        }
        runCatching { listener.onMessage(message) }
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

/** Protobuf's base-128 varint, used bare (no tag) as MRP's frame length prefix. */
object Varint {
    fun encode(value: Long): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        var v = value
        while (true) {
            val b = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(b)
                break
            }
            out.write(b or 0x80)
        }
        return out.toByteArray()
    }

    fun readFrom(input: DataInputStream): Int {
        var result = 0
        var shift = 0
        while (true) {
            val b = input.readUnsignedByte()
            result = result or ((b and 0x7F) shl shift)
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result
    }
}
