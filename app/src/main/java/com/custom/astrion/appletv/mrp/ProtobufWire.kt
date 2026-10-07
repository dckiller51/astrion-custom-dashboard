package com.custom.astrion.appletv.mrp

import java.io.ByteArrayOutputStream

/**
 * A minimal protobuf (proto2) wire-format reader/writer, hand-rolled because this build has no
 * access to `protoc` or a Maven repository to pull a generated/runtime protobuf library from.
 *
 * Rather than generating a class per message (as `protoc` would), a decoded message is just a
 * [ProtoMessage]: a multimap from field number to the raw values seen for it, keyed by wire type.
 * This is enough for MRP's needs here — reading a handful of known fields out of a message and
 * ignoring the rest — and it degrades gracefully on fields this code doesn't know about, exactly
 * like a real protobuf parser does (unknown fields are preserved as opaque bytes, never rejected).
 *
 * proto2 "extend" fields (how MRP attaches e.g. a `DeviceInfoMessage` to the top-level
 * `ProtocolMessage`) are ordinary fields at a fixed field number as far as the wire format is
 * concerned, so no special handling is needed for them beyond knowing that number (see
 * `MrpExtensions` in MrpMessages.kt).
 */
object ProtobufWire {
    const val WIRE_VARINT = 0
    const val WIRE_FIXED64 = 1
    const val WIRE_LENGTH_DELIMITED = 2
    const val WIRE_FIXED32 = 5

    class ProtoMessage(private val fields: Map<Int, List<Field>> = emptyMap()) {
        class Field(val wireType: Int, val varint: Long = 0, val bytes: ByteArray = ByteArray(0))

        fun varint(number: Int): Long? = fields[number]?.lastOrNull { it.wireType == WIRE_VARINT }?.varint

        fun int32(number: Int): Int? = varint(number)?.toInt()

        fun bool(number: Int): Boolean? = varint(number)?.let { it != 0L }

        /** Reads a varint-encoded field as an enum's raw numeric value. */
        fun enumValue(number: Int): Int? = int32(number)

        fun fixed64AsDouble(number: Int): Double? = fields[number]?.lastOrNull { it.wireType == WIRE_FIXED64 }
            ?.let { java.lang.Double.longBitsToDouble(leLong(it.bytes)) }

        fun fixed32AsFloat(number: Int): Float? = fields[number]?.lastOrNull { it.wireType == WIRE_FIXED32 }
            ?.let { java.lang.Float.intBitsToFloat(leInt(it.bytes)) }

        fun string(number: Int): String? = bytesField(number)?.toString(Charsets.UTF_8)

        fun bytesField(number: Int): ByteArray? = fields[number]?.lastOrNull { it.wireType == WIRE_LENGTH_DELIMITED }?.bytes

        /** Reads a length-delimited field as a nested message. */
        fun message(number: Int): ProtoMessage? = bytesField(number)?.let { parse(it) }

        /** All length-delimited values for a `repeated` field, each parsed as a nested message. */
        fun repeatedMessages(number: Int): List<ProtoMessage> = (fields[number] ?: emptyList())
            .filter { it.wireType == WIRE_LENGTH_DELIMITED }
            .map { parse(it.bytes) }

        /**
         * Protobuf `MergeFrom` semantics for the scalar/message fields this client cares about:
         * every field present in [update] replaces the same field here; fields only present here
         * are kept. (Real `MergeFrom` also appends repeated fields; nothing merged here is repeated.)
         */
        fun merge(update: ProtoMessage): ProtoMessage = ProtoMessage(fields + update.fields)

        private fun leLong(b: ByteArray): Long {
            var v = 0L
            for (i in 0 until 8) v = v or ((b.getOrElse(i) { 0 }.toLong() and 0xFF) shl (8 * i))
            return v
        }

        private fun leInt(b: ByteArray): Int {
            var v = 0
            for (i in 0 until 4) v = v or ((b.getOrElse(i) { 0 }.toInt() and 0xFF) shl (8 * i))
            return v
        }
    }

    /** Builds one message field by field, in whatever order they're set (proto2 doesn't require ordering). */
    class Builder {
        private val out = ByteArrayOutputStream()

        fun varint(number: Int, value: Long): Builder = apply {
            tag(number, WIRE_VARINT)
            writeVarint(out, value)
        }

        fun int32(number: Int, value: Int): Builder = varint(number, value.toLong() and 0xFFFFFFFFL)

        fun bool(number: Int, value: Boolean): Builder = varint(number, if (value) 1L else 0L)

        fun enumValue(number: Int, value: Int): Builder = varint(number, value.toLong())

        fun double(number: Int, value: Double): Builder = apply {
            tag(number, WIRE_FIXED64)
            val bits = java.lang.Double.doubleToRawLongBits(value)
            for (i in 0 until 8) out.write(((bits ushr (8 * i)) and 0xFF).toInt())
        }

        fun string(number: Int, value: String): Builder = bytes(number, value.toByteArray(Charsets.UTF_8))

        fun bytes(number: Int, value: ByteArray): Builder = apply {
            tag(number, WIRE_LENGTH_DELIMITED)
            writeVarint(out, value.size.toLong())
            out.write(value)
        }

        fun message(number: Int, value: Builder): Builder = bytes(number, value.build())

        fun rawField(number: Int, wireType: Int, raw: ByteArray): Builder = apply {
            tag(number, wireType)
            out.write(raw)
        }

        fun build(): ByteArray = out.toByteArray()

        private fun tag(number: Int, wireType: Int) {
            writeVarint(out, ((number.toLong() shl 3) or wireType.toLong()))
        }
    }

    /** A message with no fields — what proto2 leaves out of the wire entirely for an empty submessage. */
    val EMPTY = ProtoMessage()

    fun parse(data: ByteArray): ProtoMessage {
        val fields = LinkedHashMap<Int, MutableList<ProtoMessage.Field>>()
        var pos = 0
        while (pos < data.size) {
            val (tag, afterTag) = readVarint(data, pos)
            pos = afterTag
            val number = (tag ushr 3).toInt()
            val wireType = (tag and 0x7).toInt()
            val field: ProtoMessage.Field
            when (wireType) {
                WIRE_VARINT -> {
                    val (v, next) = readVarint(data, pos)
                    field = ProtoMessage.Field(wireType, varint = v)
                    pos = next
                }
                WIRE_FIXED64 -> {
                    field = ProtoMessage.Field(wireType, bytes = data.copyOfRange(pos, pos + 8))
                    pos += 8
                }
                WIRE_FIXED32 -> {
                    field = ProtoMessage.Field(wireType, bytes = data.copyOfRange(pos, pos + 4))
                    pos += 4
                }
                WIRE_LENGTH_DELIMITED -> {
                    val (len, afterLen) = readVarint(data, pos)
                    val end = afterLen + len.toInt()
                    field = ProtoMessage.Field(wireType, bytes = data.copyOfRange(afterLen, end))
                    pos = end
                }
                else -> error("Unsupported protobuf wire type $wireType at field $number")
            }
            fields.getOrPut(number) { ArrayList() }.add(field)
        }
        return ProtoMessage(fields)
    }

    private fun readVarint(data: ByteArray, start: Int): Pair<Long, Int> {
        var result = 0L
        var shift = 0
        var pos = start
        while (true) {
            val b = data[pos].toInt() and 0xFF
            result = result or ((b.toLong() and 0x7F) shl shift)
            pos++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to pos
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Long) {
        var v = value
        while (true) {
            val byte = (v and 0x7F).toInt()
            v = v ushr 7
            if (v == 0L) {
                out.write(byte)
                break
            } else {
                out.write(byte or 0x80)
            }
        }
    }
}
