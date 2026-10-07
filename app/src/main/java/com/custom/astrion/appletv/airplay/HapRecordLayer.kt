package com.custom.astrion.appletv.airplay

import com.custom.astrion.appletv.HapCrypto
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream

/**
 * The standard HomeKit Accessory Protocol transport-encryption framing — a 2-byte little-endian
 * length prefix followed by a ChaCha20-Poly1305-sealed frame, at most [MAX_FRAME] bytes of
 * plaintext per frame, a 64-bit little-endian counter nonce incrementing once per frame in each
 * direction. Companion uses exactly this framing (see `CompanionLink`); AirPlay's control
 * connection and its event/data channels reuse it verbatim once each has its own pair-verify
 * shared secret and HKDF-derived read/write keys — only the salts/info-strings used to derive
 * those keys differ per channel, not the framing itself.
 */
internal class HapRecordLayer(
    private val writeKey: ByteArray,
    private val readKey: ByteArray,
    // Purely diagnostic: folded into this instance's own exception messages (see [readExact])
    // so a disconnect log says *which* socket actually closed — control, event or data — instead
    // of the same bare "connection closed mid-frame" every one of them produces on its own.
    private val label: String = "?"
) {
    companion object {
        const val MAX_FRAME = 1024
    }

    private var writeCounter = 0L
    private var readCounter = 0L
    private val pending = ByteArrayOutputStream()

    // 4 zero bytes PREPENDED, then the 8-byte little-endian counter in the *last* 8 bytes —
    // confirmed against the real HAP record-layer nonce (pyatv's generic `Chacha20Cipher`, which
    // pads an 8-byte counter to 12 by prepending zeros). NOT the same layout classic MRP's
    // `MrpConnection.nonce()` and `CompanionLink`'s own nonce already use correctly elsewhere —
    // this function had the counter and the zero-padding on the wrong sides, which is invisible
    // for counter 0 (both layouts are all-zero) but corrupts every nonce from counter 1 onward.
    private fun nonceFor(counter: Long): ByteArray =
        ByteArray(12) { i -> if (i < 4) 0 else ((counter ushr (8 * (i - 4))) and 0xFF).toByte() }

    /** Encrypts [plain] as one or more frames (split at [MAX_FRAME]) and writes them to [out]; an
     * empty [plain] still produces exactly one (empty) frame, matching HAP's own framing. */
    fun encryptTo(out: OutputStream, plain: ByteArray) {
        var offset = 0
        do {
            val chunkSize = minOf(MAX_FRAME, plain.size - offset)
            val chunk = plain.copyOfRange(offset, offset + chunkSize)
            val lengthAad = byteArrayOf((chunkSize and 0xFF).toByte(), ((chunkSize ushr 8) and 0xFF).toByte())
            val sealed = HapCrypto.chachaEncrypt(writeKey, nonceFor(writeCounter), chunk, lengthAad)
            writeCounter++
            out.write(lengthAad)
            out.write(sealed)
            offset += chunkSize
        } while (offset < plain.size)
    }

    /** Decrypts exactly one frame read from [input]; throws on a short read or a bad auth tag. */
    fun decryptOneFrame(input: InputStream): ByteArray {
        val lengthAad = readExact(input, 2)
        val length = (lengthAad[0].toInt() and 0xFF) or ((lengthAad[1].toInt() and 0xFF) shl 8)
        val sealed = readExact(input, length + 16)
        val plain = HapCrypto.chachaDecrypt(readKey, nonceFor(readCounter), sealed, lengthAad)
        readCounter++
        return plain
    }

    /**
     * Feeds newly-arrived ciphertext bytes in (however they happened to arrive off the socket)
     * and returns every full plaintext frame now decodable; partial trailing data is buffered for
     * the next call. For callers that already have a full buffer rather than a stream.
     */
    fun decryptAvailable(newBytes: ByteArray): List<ByteArray> {
        pending.write(newBytes)
        val buffered = pending.toByteArray()
        val frames = ArrayList<ByteArray>()
        var pos = 0
        while (true) {
            val frame = tryReadFrameAt(buffered, pos) ?: break
            frames.add(frame.first)
            pos = frame.second
        }
        pending.reset()
        pending.write(buffered, pos, buffered.size - pos)
        return frames
    }

    /**
     * Attempts to decrypt one frame starting at [pos] in [buffered]; returns the decrypted
     * plaintext together with the offset just past the frame, or null when [buffered] doesn't yet
     * hold a full frame (either the 2-byte length prefix or the sealed body is still incomplete).
     */
    private fun tryReadFrameAt(buffered: ByteArray, pos: Int): Pair<ByteArray, Int>? {
        if (buffered.size - pos < 2) return null
        val length = (buffered[pos].toInt() and 0xFF) or ((buffered[pos + 1].toInt() and 0xFF) shl 8)
        val frameEnd = pos + 2 + length + 16
        if (buffered.size < frameEnd) return null
        val lengthAad = buffered.copyOfRange(pos, pos + 2)
        val sealed = buffered.copyOfRange(pos + 2, frameEnd)
        val plain = HapCrypto.chachaDecrypt(readKey, nonceFor(readCounter), sealed, lengthAad)
        readCounter++
        return plain to frameEnd
    }

    private fun readExact(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var read = 0
        while (read < n) {
            val got = input.read(out, read, n - read)
            if (got < 0) throw AirPlayException("[$label] connection closed mid-frame ($read/$n bytes)")
            read += got
        }
        return out
    }
}
