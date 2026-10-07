package com.custom.astrion.appletv.airplay

import java.io.ByteArrayOutputStream

/**
 * A minimal `bplist00` (Apple binary property list) codec. AirPlay's RTSP bodies are plists —
 * sometimes XML, always binary when this app writes one — and there is no `plutil`/Foundation
 * here to lean on, so this hand-rolls just enough of the format: null/bool, integers, real
 * (double), data, strings (ASCII and UTF-16BE), arrays and string-keyed dictionaries. That is
 * every shape AirPlay's SETUP/RECORD/pairing bodies actually use.
 *
 * Deliberately skips the dedup table a real writer (e.g. Python's `plistlib`) uses to shrink
 * output — every object gets its own slot. Dedup is a size optimisation, never a format
 * requirement: a plist with no deduped objects is exactly as valid as one with it, and decodes
 * to the same value either way (confirmed against `plistlib` both directions).
 */
internal object BinaryPlist {
    private val MAGIC = "bplist00".toByteArray(Charsets.US_ASCII)

    /** One parsed/to-be-encoded plist value. Dict keys are always strings — every AirPlay/MRP
     * plist body in this codebase uses only string-keyed dictionaries. */
    sealed class Value {
        object Null : Value()

        data class Bool(val value: Boolean) : Value()

        data class Int64(val value: Long) : Value()

        data class Real(val value: Double) : Value()

        data class Str(val value: String) : Value()

        data class Data(val value: ByteArray) : Value()

        data class Arr(val value: List<Value>) : Value()

        /**
         * Keys are [Str] nodes, not plain [String]s, so the *same object* can be referenced both
         * while flattening the tree for encoding and while writing it out — needed because
         * [Value] is a data class (structural equals/hashCode), so two keys with equal text would
         * otherwise be indistinguishable in an identity-keyed index and resolve to the wrong slot.
         * [of]/[dict] build these consistently; [asMap] converts back to a plain `Map<String, Value>`
         * for everyday reading.
         */
        data class Dict(val value: LinkedHashMap<Str, Value>) : Value()

        companion object {
            fun of(v: Any?): Value = when (v) {
                null -> Null
                is Value -> v
                is Boolean -> Bool(v)
                is Int -> Int64(v.toLong())
                is Long -> Int64(v)
                is Double -> Real(v)
                is Float -> Real(v.toDouble())
                is String -> Str(v)
                is ByteArray -> Data(v)
                is Map<*, *> -> dict(v.entries.associate { (k, value) -> k.toString() to value })
                is List<*> -> Arr(v.map { of(it) })
                else -> error("Unsupported plist value: $v (${v::class})")
            }

            fun dict(map: Map<String, Any?>): Dict {
                val out = LinkedHashMap<Str, Value>()
                map.forEach { (k, v) -> out[Str(k)] = of(v) }
                return Dict(out)
            }

            fun arr(items: List<Any?>): Arr = Arr(items.map { of(it) })
        }
    }

    // ---- public accessors (Value -> plain Kotlin) --------------------------------------

    fun Value.asMap(): Map<String, Value> = (this as? Value.Dict)?.value?.mapKeys { it.key.value } ?: emptyMap()

    fun Value.asList(): List<Value> = (this as? Value.Arr)?.value ?: emptyList()

    fun Value.asString(): String? = (this as? Value.Str)?.value

    fun Value.asLong(): Long? = (this as? Value.Int64)?.value

    fun Value.asInt(): Int? = asLong()?.toInt()

    fun Value.asBool(): Boolean? = (this as? Value.Bool)?.value

    fun Value.asBytes(): ByteArray? = (this as? Value.Data)?.value

    fun Value.asDouble(): Double? = when (this) {
        is Value.Real -> value
        is Value.Int64 -> value.toDouble()
        else -> null
    }

    // ---- encode -------------------------------------------------------------------------

    /** Serializes [root] to a `bplist00` byte array. */
    fun encode(root: Value): ByteArray {
        val objects = ArrayList<Value>()
        // Identity-keyed, deliberately: two structurally-equal Value.Dict/Arr instances (data
        // classes, so equals() compares contents) must still get *distinct* slots unless they are
        // literally the same object, or a reference meant for one would silently resolve to
        // whichever happened to be indexed last. A content-equality map would do exactly that.
        val indexOf = java.util.IdentityHashMap<Value, Int>()
        flatten(root, objects, indexOf)
        val refSize = refSizeFor(objects.size)

        val body = ByteArrayOutputStream()
        val offsets = IntArray(objects.size)
        for ((i, obj) in objects.withIndex()) {
            offsets[i] = body.size()
            writeObject(body, obj, indexOf, refSize)
        }

        val offsetTableOffset = 8 + body.size()
        // Must fit the largest individual object offset, i.e. up to (not including) the offset
        // table itself — every object lives in [8, offsetTableOffset).
        val offsetIntSize = intSizeFor(offsetTableOffset.toLong())
        val out = ByteArrayOutputStream()
        out.write(MAGIC)
        out.write(body.toByteArray())
        // offsets[] was recorded relative to the start of the body (0-based); every stored offset
        // must be relative to the start of the *file*, i.e. shifted past the 8-byte "bplist00" magic.
        for (off in offsets) writeBigEndianUint(out, (off + 8).toLong(), offsetIntSize)

        val trailer = ByteArrayOutputStream()
        repeat(5) { trailer.write(0) } // 5 unused bytes...
        trailer.write(0) // ...then sort version — 6 bytes total before the two size fields below
        trailer.write(offsetIntSize)
        trailer.write(refSize)
        writeBigEndianUint(trailer, objects.size.toLong(), 8)
        writeBigEndianUint(trailer, 0, 8) // root object is always index 0 (see flatten())
        writeBigEndianUint(trailer, offsetTableOffset.toLong(), 8)
        out.write(trailer.toByteArray())
        return out.toByteArray()
    }

    /**
     * Pre-order flatten: each node (root first, then its children left-to-right) is assigned the
     * next free index and appended to [objects], recorded by *identity* in [indexOf] so a later
     * reference to that exact node resolves to the right slot regardless of what it looks like
     * (see [Value.Dict]'s doc for why identity, not structural equality, matters here).
     */
    private fun flatten(value: Value, objects: MutableList<Value>, indexOf: java.util.IdentityHashMap<Value, Int>) {
        if (indexOf.containsKey(value)) return
        indexOf[value] = objects.size
        objects.add(value)
        when (value) {
            is Value.Arr -> value.value.forEach { flatten(it, objects, indexOf) }
            is Value.Dict ->
                value.value.entries.forEach { (k, v) ->
                    flatten(k, objects, indexOf)
                    flatten(v, objects, indexOf)
                }
            else -> Unit
        }
    }

    private fun refSizeFor(count: Int): Int = intSizeFor(count.toLong())

    private fun intSizeFor(max: Long): Int = when {
        max <= 0xFF -> 1
        max <= 0xFFFF -> 2
        else -> 4
    }

    private fun writeBigEndianUint(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    private fun writeCountMarker(out: ByteArrayOutputStream, highNibble: Int, count: Int) {
        if (count < 0xF) {
            out.write((highNibble shl 4) or count)
        } else {
            out.write((highNibble shl 4) or 0xF)
            writeIntObject(out, count.toLong())
        }
    }

    /** Writes an inline int *object* (marker 0x1_ + bytes) — used both for real integer values
     * and for the extended-length prefix containers use when they have 15+ elements. */
    private fun writeIntObject(out: ByteArrayOutputStream, value: Long) {
        when {
            value < 0 -> {
                out.write(0x13)
                writeBigEndianUint(out, value, 8)
            }
            value < (1L shl 8) -> {
                out.write(0x10)
                writeBigEndianUint(out, value, 1)
            }
            value < (1L shl 16) -> {
                out.write(0x11)
                writeBigEndianUint(out, value, 2)
            }
            value < (1L shl 32) -> {
                out.write(0x12)
                writeBigEndianUint(out, value, 4)
            }
            else -> {
                out.write(0x13)
                writeBigEndianUint(out, value, 8)
            }
        }
    }

    private fun writeRef(out: ByteArrayOutputStream, index: Int, refSize: Int) = writeBigEndianUint(out, index.toLong(), refSize)

    private fun writeObject(out: ByteArrayOutputStream, value: Value, indexOf: Map<Value, Int>, refSize: Int) {
        when (value) {
            Value.Null -> out.write(0x00)
            is Value.Bool -> out.write(if (value.value) 0x09 else 0x08)
            is Value.Int64 -> writeIntObject(out, value.value)
            is Value.Real -> {
                out.write(0x23)
                val bits = java.lang.Double.doubleToRawLongBits(value.value)
                writeBigEndianUint(out, bits, 8)
            }
            is Value.Data -> {
                writeCountMarker(out, 0x4, value.value.size)
                out.write(value.value)
            }
            is Value.Str -> writeString(out, value.value)
            is Value.Arr -> {
                writeCountMarker(out, 0xA, value.value.size)
                value.value.forEach { writeRef(out, indexOf.getValue(it), refSize) }
            }
            is Value.Dict -> {
                writeCountMarker(out, 0xD, value.value.size)
                value.value.keys.forEach { writeRef(out, indexOf.getValue(it), refSize) }
                value.value.values.forEach { writeRef(out, indexOf.getValue(it), refSize) }
            }
        }
    }

    private fun writeString(out: ByteArrayOutputStream, s: String) {
        val ascii = s.all { it.code < 128 }
        if (ascii) {
            writeCountMarker(out, 0x5, s.length)
            out.write(s.toByteArray(Charsets.US_ASCII))
        } else {
            writeCountMarker(out, 0x6, s.length)
            out.write(s.toByteArray(Charsets.UTF_16BE))
        }
    }

    // ---- decode -------------------------------------------------------------------------

    /** Everything [decode] needs to resolve an object-table index into its byte offset and back. */
    private class DecodeContext(val data: ByteArray, val offsets: IntArray, val objectRefSize: Int) {
        fun readRef(pos: Int): Int = beLong(data, pos, objectRefSize).toInt()

        fun readObjectAt(index: Int): Value {
            var pos = offsets[index]
            val marker = data[pos].toInt() and 0xFF
            val high = (marker ushr 4) and 0xF
            val low = marker and 0xF
            pos++
            return when (high) {
                0x0 -> readNullOrBool(marker)
                0x1 -> Value.Int64(readIntValue(data, pos, low))
                0x2 -> readReal(data, pos, low)
                0x3 -> Value.Real(java.lang.Double.longBitsToDouble(beLong(data, pos, 8))) // date: raw seconds
                0x4 -> readDataValue(data, pos, low)
                0x5 -> readAsciiStr(data, pos, low)
                0x6 -> readUtf16Str(data, pos, low)
                0xA -> readArray(pos, low)
                0xD -> readDict(pos, low)
                else -> Value.Null
            }
        }

        private fun readArray(pos: Int, low: Int): Value {
            val (count, after) = readCount(data, pos, low)
            val items = ArrayList<Value>(count)
            var p = after
            repeat(count) {
                items.add(readObjectAt(readRef(p)))
                p += objectRefSize
            }
            return Value.Arr(items)
        }

        private fun readDict(pos: Int, low: Int): Value {
            val (count, after) = readCount(data, pos, low)
            val keyRefs = IntArray(count)
            var p = after
            repeat(count) { i ->
                keyRefs[i] = readRef(p)
                p += objectRefSize
            }
            val map = LinkedHashMap<Value.Str, Value>()
            repeat(count) { i ->
                val key = (readObjectAt(keyRefs[i]) as? Value.Str) ?: Value.Str(keyRefs[i].toString())
                map[key] = readObjectAt(readRef(p))
                p += objectRefSize
            }
            return Value.Dict(map)
        }
    }

    private fun readNullOrBool(marker: Int): Value = when (marker) {
        0x00 -> Value.Null
        0x08 -> Value.Bool(false)
        0x09 -> Value.Bool(true)
        else -> Value.Null
    }

    private fun readReal(data: ByteArray, pos: Int, low: Int): Value {
        val size = 1 shl low
        val bits = beLong(data, pos, size)
        val real =
            if (size == 4) {
                java.lang.Float.intBitsToFloat(bits.toInt()).toDouble()
            } else {
                java.lang.Double.longBitsToDouble(bits)
            }
        return Value.Real(real)
    }

    private fun readDataValue(data: ByteArray, pos: Int, low: Int): Value {
        val (count, after) = readCount(data, pos, low)
        return Value.Data(data.copyOfRange(after, after + count))
    }

    private fun readAsciiStr(data: ByteArray, pos: Int, low: Int): Value {
        val (count, after) = readCount(data, pos, low)
        return Value.Str(String(data, after, count, Charsets.US_ASCII))
    }

    private fun readUtf16Str(data: ByteArray, pos: Int, low: Int): Value {
        val (count, after) = readCount(data, pos, low)
        return Value.Str(String(data, after, count * 2, Charsets.UTF_16BE))
    }

    /** Parses a `bplist00` byte array back into a [Value] tree. */
    fun decode(data: ByteArray): Value {
        require(data.size >= 40 && String(data, 0, 8, Charsets.US_ASCII) == "bplist00") { "not a bplist00" }
        val trailer = data.copyOfRange(data.size - 32, data.size)
        val offsetIntSize = trailer[6].toInt() and 0xFF
        val objectRefSize = trailer[7].toInt() and 0xFF
        val numObjects = beLong(trailer, 8, 8).toInt()
        val rootIndex = beLong(trailer, 16, 8).toInt()
        val offsetTableOffset = beLong(trailer, 24, 8).toInt()

        val offsets = IntArray(numObjects) { i -> beLong(data, offsetTableOffset + i * offsetIntSize, offsetIntSize).toInt() }
        return DecodeContext(data, offsets, objectRefSize).readObjectAt(rootIndex)
    }

    /** `0xF`-extended count: the next object (inline, not a ref) is an int giving the real count. */
    private fun readCount(data: ByteArray, pos: Int, low: Int): Pair<Int, Int> {
        if (low != 0xF) return low to pos
        val marker = data[pos].toInt() and 0xFF
        val sizeLog = marker and 0xF
        val count = readIntValue(data, pos + 1, sizeLog).toInt()
        return count to (pos + 1 + (1 shl sizeLog))
    }

    // Matches plistlib exactly: 1/2/4-byte widths are read unsigned (no sign bit to extend, since
    // only the low `size` bytes are used); an 8-byte width's bit pattern already *is* the correct
    // signed Long value once assembled, so no extra sign-extension step is needed for it either.
    private fun readIntValue(data: ByteArray, pos: Int, sizeLog: Int): Long = beLong(data, pos, 1 shl sizeLog)

    private fun beLong(data: ByteArray, pos: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (data[pos + i].toLong() and 0xFF)
        return v
    }
}
