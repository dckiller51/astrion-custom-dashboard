package com.custom.astrion.appletv

import java.io.ByteArrayOutputStream

/**
 * TLV8 as used by HomeKit-style pairing: a flat sequence of
 * `tag(1) length(1) value(length)` records. A value longer than 255 bytes
 * is split into consecutive records with the same tag, which [decode]
 * reassembles.
 */
internal object Tlv8 {
    const val METHOD = 0x00
    const val IDENTIFIER = 0x01
    const val SALT = 0x02
    const val PUBLIC_KEY = 0x03
    const val PROOF = 0x04
    const val ENCRYPTED_DATA = 0x05
    const val SEQ_NO = 0x06
    const val ERROR = 0x07
    const val BACK_OFF = 0x08
    const val SIGNATURE = 0x0A
    const val NAME = 0x11

    fun encode(items: List<Pair<Int, ByteArray>>): ByteArray {
        val out = ByteArrayOutputStream()
        for ((tag, value) in items) {
            var pos = 0
            while (pos < value.size) {
                val size = minOf(255, value.size - pos)
                out.write(tag)
                out.write(size)
                out.write(value, pos, size)
                pos += size
            }
        }
        return out.toByteArray()
    }

    fun decode(data: ByteArray): Map<Int, ByteArray> {
        val out = LinkedHashMap<Int, ByteArray>()
        var pos = 0
        while (pos + 2 <= data.size) {
            val tag = data[pos].toInt() and 0xFF
            val len = data[pos + 1].toInt() and 0xFF
            val end = minOf(pos + 2 + len, data.size)
            val chunk = data.copyOfRange(pos + 2, end)
            out[tag] = out[tag]?.plus(chunk) ?: chunk
            pos = end
        }
        return out
    }

    /** Human-readable summary of an error TLV, for exception messages. */
    fun describeError(tlv: Map<Int, ByteArray>): String {
        val code = tlv[ERROR]?.firstOrNull()?.toInt()?.and(0xFF)
        val name =
            when (code) {
                0x01 -> "unknown"
                0x02 -> "authentication (wrong PIN?)"
                0x03 -> "back-off (too many attempts)"
                0x04 -> "max peers reached"
                0x05 -> "max tries exceeded"
                0x06 -> "unavailable"
                0x07 -> "busy"
                else -> "code $code"
            }
        val backOff = tlv[BACK_OFF]?.let { b -> b.foldIndexed(0L) { i, acc, v -> acc or ((v.toLong() and 0xFF) shl (8 * i)) } }
        return if (backOff != null) "$name, retry in ${backOff}s" else name
    }
}
