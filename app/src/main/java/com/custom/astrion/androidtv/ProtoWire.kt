package com.custom.astrion.androidtv

import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/**
 * The few protobuf wire-format pieces the Android TV Remote v2 protocol needs (1.3.6) — varints,
 * length-delimited fields and the varint length prefix in front of every message — so no
 * protobuf library or generated code is needed. Field numbers come from the protocol's
 * `polo.proto` and `remotemessage.proto` (see [PoloMessages] and [RemoteMessages]).
 */
class ProtoWriter {
    private val out = ByteArrayOutputStream()

    private fun tag(field: Int, wireType: Int) = rawVarint(((field shl 3) or wireType).toLong())

    private fun rawVarint(value: Long) {
        var v = value
        while (v and VARINT_MORE_MASK.inv() != 0L) {
            out.write(((v and VARINT_PAYLOAD) or VARINT_CONTINUE).toInt())
            v = v ushr VARINT_BITS
        }
        out.write(v.toInt())
    }

    /** A varint field; 0 is left out, like proto3 does. */
    fun uint(field: Int, value: Long): ProtoWriter = apply {
        if (value != 0L) {
            tag(field, WIRE_VARINT)
            rawVarint(value)
        }
    }

    fun uint(field: Int, value: Int): ProtoWriter = uint(field, value.toLong())

    fun bool(field: Int, value: Boolean): ProtoWriter = uint(field, if (value) 1L else 0L)

    fun bytes(field: Int, value: ByteArray): ProtoWriter = apply {
        tag(field, WIRE_LENGTH)
        rawVarint(value.size.toLong())
        out.write(value)
    }

    /** A string field; empty is left out, like proto3 does. */
    fun string(field: Int, value: String): ProtoWriter = apply { if (value.isNotEmpty()) bytes(field, value.toByteArray(Charsets.UTF_8)) }

    /** An embedded message — always written, even empty: its presence is what the protocol reads. */
    fun message(field: Int, build: ProtoWriter.() -> Unit): ProtoWriter = bytes(field, ProtoWriter().apply(build).toByteArray())

    fun toByteArray(): ByteArray = out.toByteArray()

    companion object {
        internal const val WIRE_VARINT = 0
        internal const val WIRE_FIXED64 = 1
        internal const val WIRE_LENGTH = 2
        internal const val WIRE_FIXED32 = 5
        private const val VARINT_BITS = 7
        private const val VARINT_PAYLOAD = 0x7FL
        private const val VARINT_CONTINUE = 0x80L
        private const val VARINT_MORE_MASK = 0x7FL

        /** Writes [message] with its varint length prefix, as every message on the wire is sent. */
        fun writeDelimited(stream: OutputStream, message: ByteArray) {
            val prefix = ProtoWriter().apply { rawVarint(message.size.toLong()) }.toByteArray()
            stream.write(prefix + message)
            stream.flush()
        }
    }
}

/** A decoded message: every field number to its values in order (varints as Long, the rest as bytes). */
class ProtoMessage(private val fields: Map<Int, List<Any>>) {
    fun has(field: Int): Boolean = fields.containsKey(field)

    fun long(field: Int): Long = fields[field]?.lastOrNull() as? Long ?: 0L

    fun int(field: Int): Int = long(field).toInt()

    fun bool(field: Int): Boolean = long(field) != 0L

    fun bytes(field: Int): ByteArray = fields[field]?.lastOrNull() as? ByteArray ?: ByteArray(0)

    fun string(field: Int): String = bytes(field).toString(Charsets.UTF_8)

    /** The embedded message in [field] (empty when absent). */
    fun message(field: Int): ProtoMessage = parse(bytes(field))

    override fun toString(): String = fields.keys.sorted().joinToString(prefix = "{", postfix = "}")

    companion object {
        private const val MAX_MESSAGE = 1 shl 20
        private const val FIXED32 = 4
        private const val FIXED64 = 8

        fun parse(data: ByteArray): ProtoMessage {
            val fields = LinkedHashMap<Int, MutableList<Any>>()
            val cursor = Cursor(data)
            while (cursor.hasMore()) {
                val key = cursor.varint()
                val field = (key ushr 3).toInt()
                val value: Any =
                    when ((key and 0x7).toInt()) {
                        ProtoWriter.WIRE_VARINT -> cursor.varint()
                        ProtoWriter.WIRE_LENGTH -> cursor.take(cursor.varint().toInt())
                        ProtoWriter.WIRE_FIXED32 -> cursor.take(FIXED32)
                        ProtoWriter.WIRE_FIXED64 -> cursor.take(FIXED64)
                        else -> throw ProtoException("unsupported wire type in field $field")
                    }
                fields.getOrPut(field) { mutableListOf() }.add(value)
            }
            return ProtoMessage(fields)
        }

        /** Reads one length-prefixed message; throws [EOFException] when the stream ends. */
        fun readDelimited(stream: InputStream): ByteArray {
            var len = 0L
            var shift = 0
            while (true) {
                val b = nextByte(stream)
                len = len or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) break
                shift += 7
                check(shift <= MAX_PREFIX_SHIFT) { "bad length prefix" }
            }
            check(len <= MAX_MESSAGE) { "message too large: $len" }
            val buf = ByteArray(len.toInt())
            var read = 0
            while (read < buf.size) {
                val n = stream.read(buf, read, buf.size - read)
                if (n < 0) throw EOFException("connection closed")
                read += n
            }
            return buf
        }

        private fun nextByte(stream: InputStream): Int = stream.read().also { if (it < 0) throw EOFException("connection closed") }

        private const val MAX_PREFIX_SHIFT = 35
    }

    /** Reads [data] front to back. */
    private class Cursor(private val data: ByteArray) {
        private var pos = 0

        fun hasMore() = pos < data.size

        fun varint(): Long {
            var result = 0L
            var shift = 0
            while (true) {
                if (pos >= data.size) throw ProtoException("truncated varint")
                val b = data[pos++].toInt() and 0xFF
                result = result or ((b and 0x7F).toLong() shl shift)
                if (b and 0x80 == 0) return result
                shift += 7
                if (shift > MAX_VARINT_SHIFT) throw ProtoException("varint too long")
            }
        }

        fun take(len: Int): ByteArray {
            if (len < 0 || pos + len > data.size) throw ProtoException("truncated field")
            return data.copyOfRange(pos, pos + len).also { pos += len }
        }

        private companion object {
            const val MAX_VARINT_SHIFT = 63
        }
    }
}

class ProtoException(message: String) : Exception(message)
