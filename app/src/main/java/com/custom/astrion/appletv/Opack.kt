package com.custom.astrion.appletv

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Encoder/decoder for Apple's OPACK serialization format, the payload of
 * every Companion-link frame.
 *
 * Value mapping (Kotlin <-> OPACK):
 *  - `null`, `Boolean`, `Long`/`Int` (unsigned only), `Double`/`Float`
 *  - `String` (UTF-8), `ByteArray`, `UUID`
 *  - `List<*>` and `Map<*, *>` (insertion-ordered on decode)
 *
 * Decoding resolves OPACK's back-references ("UID" objects) the same way
 * Apple's own decoder does: every string/data/number that isn't a tiny
 * inline integer is appended to an object table the first time it's
 * seen, and later references index into it. Containers are never added to
 * that table.
 *
 * Encoding never emits back-references — the format treats them as an
 * optional size optimization, and always writing values in full keeps the
 * encoder trivially correct.
 */
object Opack {
    class OpackException(message: String) : IllegalArgumentException(message)

    fun pack(value: Any?): ByteArray {
        val out = ByteArrayOutputStream()
        write(out, value)
        return out.toByteArray()
    }

    /** Decodes exactly one value from the start of [data]. */
    fun unpack(data: ByteArray): Any? = Reader(data).read()

    // ---- encoding -----------------------------------------------------------

    private fun write(out: ByteArrayOutputStream, value: Any?) {
        when (value) {
            null -> out.write(0x04)
            is Boolean -> out.write(if (value) 0x01 else 0x02)
            is UUID -> writeUuid(out, value)
            is Int -> writeInt(out, value.toLong())
            is Long -> writeInt(out, value)
            is Float -> writeDouble(out, value.toDouble())
            is Double -> writeDouble(out, value)
            is String -> writeString(out, value)
            is ByteArray -> writeData(out, value)
            is List<*> -> writeList(out, value)
            is Map<*, *> -> writeMap(out, value)
            else -> throw OpackException("Unsupported OPACK type: ${value::class.java.name}")
        }
    }

    private fun writeUuid(out: ByteArrayOutputStream, value: UUID) {
        out.write(0x05)
        val buf = ByteBuffer.allocate(16).order(ByteOrder.BIG_ENDIAN)
        buf.putLong(value.mostSignificantBits).putLong(value.leastSignificantBits)
        out.write(buf.array())
    }

    private fun writeInt(out: ByteArrayOutputStream, value: Long) {
        if (value < 0) throw OpackException("Negative integers are not supported (send a Double instead): $value")
        when {
            value < 0x28 -> out.write((value + 8).toInt())
            value <= 0xFF -> {
                out.write(0x30)
                writeLe(out, value, 1)
            }
            value <= 0xFFFF -> {
                out.write(0x31)
                writeLe(out, value, 2)
            }
            value <= 0xFFFFFFFFL -> {
                out.write(0x32)
                writeLe(out, value, 4)
            }
            else -> {
                out.write(0x33)
                writeLe(out, value, 8)
            }
        }
    }

    private fun writeDouble(out: ByteArrayOutputStream, value: Double) {
        out.write(0x36)
        writeLe(out, java.lang.Double.doubleToRawLongBits(value), 8)
    }

    private fun writeString(out: ByteArrayOutputStream, value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val len = bytes.size
        when {
            len <= 0x20 -> out.write(0x40 + len)
            len <= 0xFF -> {
                out.write(0x61)
                writeLe(out, len.toLong(), 1)
            }
            len <= 0xFFFF -> {
                out.write(0x62)
                writeLe(out, len.toLong(), 2)
            }
            len <= 0xFFFFFF -> {
                out.write(0x63)
                writeLe(out, len.toLong(), 3)
            }
            else -> {
                out.write(0x64)
                writeLe(out, len.toLong(), 4)
            }
        }
        out.write(bytes)
    }

    private fun writeData(out: ByteArrayOutputStream, bytes: ByteArray) {
        val len = bytes.size
        when {
            len <= 0x20 -> out.write(0x70 + len)
            len <= 0xFF -> {
                out.write(0x91)
                writeLe(out, len.toLong(), 1)
            }
            len <= 0xFFFF -> {
                out.write(0x92)
                writeLe(out, len.toLong(), 2)
            }
            else -> {
                out.write(0x93)
                writeLe(out, len.toLong(), 4)
            }
        }
        out.write(bytes)
    }

    private fun writeList(out: ByteArrayOutputStream, list: List<*>) {
        out.write(0xD0 + minOf(list.size, 0xF))
        list.forEach { write(out, it) }
        if (list.size >= 0xF) out.write(0x03)
    }

    private fun writeMap(out: ByteArrayOutputStream, map: Map<*, *>) {
        out.write(0xE0 + minOf(map.size, 0xF))
        for ((k, v) in map) {
            write(out, k)
            write(out, v)
        }
        if (map.size >= 0xF) out.write(0x03)
    }

    private fun writeLe(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in 0 until size) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    // ---- decoding -----------------------------------------------------------

    private class Reader(private val data: ByteArray) {
        private var pos = 0
        private val objects = ArrayList<Any?>()

        fun read(): Any? = readValue()

        private fun need(n: Int) {
            if (n < 0 || pos + n > data.size) throw OpackException("Truncated OPACK data")
        }

        private fun u8(): Int {
            need(1)
            return data[pos++].toInt() and 0xFF
        }

        private fun leInt(size: Int): Long {
            need(size)
            var v = 0L
            for (i in 0 until size) v = v or ((data[pos + i].toLong() and 0xFF) shl (8 * i))
            pos += size
            return v
        }

        private fun bytes(n: Int): ByteArray {
            need(n)
            val out = data.copyOfRange(pos, pos + n)
            pos += n
            return out
        }

        private fun remember(value: Any?): Any? {
            if (objects.none { sameValue(it, value) }) objects.add(value)
            return value
        }

        @Suppress("CyclomaticComplexMethod", "ReturnCount")
        private fun readValue(): Any? {
            val b = u8()
            return when {
                b == 0x01 -> true
                b == 0x02 -> false
                b == 0x04 -> null
                b == 0x05 -> remember(readUuid())
                b == 0x06 -> remember(leInt(8)) // absolute time; kept as a raw integer
                b in 0x08..0x2F -> (b - 8).toLong()
                b == 0x35 -> remember(java.lang.Float.intBitsToFloat(leInt(4).toInt()).toDouble())
                b == 0x36 -> remember(java.lang.Double.longBitsToDouble(leInt(8)))
                b in 0x30..0x33 -> remember(leInt(1 shl (b and 0xF)))
                b in 0x40..0x60 -> remember(String(bytes(b - 0x40), Charsets.UTF_8))
                b in 0x61..0x64 -> remember(String(bytes(leInt(b and 0xF).toInt()), Charsets.UTF_8))
                b in 0x70..0x90 -> remember(bytes(b - 0x70))
                b in 0x91..0x94 -> remember(bytes(leInt(1 shl ((b and 0xF) - 1)).toInt()))
                (b and 0xF0) == 0xD0 -> readList(b and 0xF)
                (b and 0xE0) == 0xE0 -> readMap(b and 0xF)
                b in 0xA0..0xC0 -> reference(b - 0xA0)
                b in 0xC1..0xC4 -> reference(leInt(b - 0xC0).toInt())
                else -> throw OpackException("Unsupported OPACK tag 0x${b.toString(16)}")
            }
        }

        private fun readUuid(): UUID {
            val buf = ByteBuffer.wrap(bytes(16)).order(ByteOrder.BIG_ENDIAN)
            return UUID(buf.long, buf.long)
        }

        private fun reference(index: Int): Any? {
            if (index !in objects.indices) throw OpackException("Bad OPACK object reference $index")
            return objects[index]
        }

        private fun peek(): Int {
            need(1)
            return data[pos].toInt() and 0xFF
        }

        private fun readList(count: Int): List<Any?> {
            val out = ArrayList<Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) out.add(readValue())
                pos++
            } else {
                repeat(count) { out.add(readValue()) }
            }
            return out
        }

        private fun readMap(count: Int): Map<Any?, Any?> {
            val out = LinkedHashMap<Any?, Any?>()
            if (count == 0xF) {
                while (peek() != 0x03) {
                    val k = readValue()
                    out[k] = readValue()
                }
                pos++
            } else {
                repeat(count) {
                    val k = readValue()
                    out[k] = readValue()
                }
            }
            return out
        }

        private fun sameValue(a: Any?, b: Any?): Boolean = if (a is ByteArray && b is ByteArray) a.contentEquals(b) else a == b
    }
}
