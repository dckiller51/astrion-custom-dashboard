package com.custom.astrion.appletv.airplay

import com.custom.astrion.appletv.airplay.BinaryPlist.Value
import com.custom.astrion.appletv.airplay.BinaryPlist.asBytes
import com.custom.astrion.appletv.airplay.BinaryPlist.asMap
import java.io.ByteArrayOutputStream

/**
 * The AirPlay 2 "data channel"'s own envelope, wrapping the MRP protobuf messages that travel
 * over it once the remote-control tunnel is up. Every message on this channel (after HAP record-
 * layer decryption) is:
 *
 *  - a fixed 32-byte big-endian header (`defpacket`-style): 4-byte total size (header + payload,
 *    *not* counting these 4 size bytes themselves), a 12-byte message-type tag (ASCII, NUL/space
 *    padded), a 4-byte command code, an 8-byte sequence number, 4 reserved bytes;
 *  - then a bplist payload shaped `{"params": {"data": <bytes>}}`, where `data` is one or more
 *    MRP protobuf messages, each prefixed with a *bare* varint length (same LEB128 encoding
 *    protobuf itself uses for field headers, just not attached to any field here).
 *
 * `send_seqno` is a single value picked once per connection and reused for every outgoing
 * message — not incremented — matching pyatv's own client exactly (confirmed from its source);
 * an incrementing counter here would still be accepted, but there's no reason to diverge from
 * what a real client does and risk tripping something that does check it.
 */
internal object AirPlayDataChannel {
    private const val HEADER_SIZE = 32
    private const val TYPE_FIELD_SIZE = 12

    // pyatv's DataStreamChannel.send_protobuf: message_type = b"sync" + 8 zero bytes, command =
    // b"comm" — a plain "sync" request carrying a protobuf payload under "comm". NUL-padded to 12.
    private const val MESSAGE_TYPE = "sync\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
    private const val COMMAND = "comm"

    private fun beUint(out: ByteArrayOutputStream, value: Long, size: Int) {
        for (i in size - 1 downTo 0) out.write(((value ushr (8 * i)) and 0xFF).toInt())
    }

    private fun beUint(bytes: ByteArray, pos: Int, size: Int): Long {
        var v = 0L
        for (i in 0 until size) v = (v shl 8) or (bytes[pos + i].toLong() and 0xFF)
        return v
    }

    private fun writeVarint(out: ByteArrayOutputStream, value: Int) {
        var v = value
        while (true) {
            val b = v and 0x7F
            v = v ushr 7
            if (v == 0) {
                out.write(b)
                return
            }
            out.write(b or 0x80)
        }
    }

    /** Reads one bare varint starting at [pos]; returns the value and the position right after it. */
    private fun readVarint(data: ByteArray, pos: Int): Pair<Int, Int> {
        var result = 0
        var shift = 0
        var p = pos
        while (true) {
            val b = data[p].toInt() and 0xFF
            result = result or ((b and 0x7F) shl shift)
            p++
            if (b and 0x80 == 0) break
            shift += 7
        }
        return result to p
    }

    /** Builds one data-channel frame carrying [protobufMessages], each varint-length-prefixed. */
    fun encodeSend(sendSeqNo: Long, protobufMessages: List<ByteArray>): ByteArray {
        val dataBytes = ByteArrayOutputStream()
        protobufMessages.forEach { msg ->
            writeVarint(dataBytes, msg.size)
            dataBytes.write(msg)
        }
        val payload = BinaryPlist.encode(Value.dict(mapOf("params" to mapOf("data" to dataBytes.toByteArray()))))
        return wrap(sendSeqNo, payload)
    }

    private fun wrap(seqNo: Long, payload: ByteArray): ByteArray = wrapFrame(MESSAGE_TYPE, COMMAND, seqNo, payload)

    private fun wrapFrame(messageType: String, command: String, seqNo: Long, payload: ByteArray): ByteArray {
        val header = ByteArrayOutputStream()
        val typeBytes = messageType.toByteArray(Charsets.US_ASCII).copyOf(TYPE_FIELD_SIZE)
        val commandBytes = command.toByteArray(Charsets.US_ASCII).copyOf(4)
        // The size field counts the *whole* frame, including its own 4 bytes (pyatv's
        // `DataHeader.length` — 32 — already includes the size field in the struct it measures).
        val totalSize = HEADER_SIZE + payload.size
        beUint(header, totalSize.toLong(), 4)
        header.write(typeBytes)
        header.write(commandBytes)
        beUint(header, seqNo, 8)
        beUint(header, 0, 4) // reserved
        header.write(payload)
        return header.toByteArray()
    }

    // pyatv's BaseDataStreamChannel.encode_reply: a bare transport-level acknowledgement, empty
    // payload, message_type "rply" NUL-padded, echoing the *same* seqno that was received.
    private const val REPLY_TYPE = "rply\u0000\u0000\u0000\u0000\u0000\u0000\u0000\u0000"
    private const val REPLY_COMMAND = "\u0000\u0000\u0000\u0000"

    /**
     * Builds the bare transport-level "rply" acknowledgement every received "sync" frame requires
     * (confirmed in pyatv's `BaseDataStreamChannel.handle_received`: "If this was a request, send
     * a reply to satisfy other end"). Without echoing this back with the sender's own [seqNo], the
     * real device stops sending anything further on the data channel after its first message —
     * which is exactly the "no reply to MRP request" timeout seen on real hardware: tvOS's own
     * next message (our request's actual MRP-level reply, itself framed as another "sync" message)
     * never arrives because the device is still waiting for this low-level ack of its previous one.
     */
    fun encodeReply(seqNo: Long): ByteArray = wrapFrame(REPLY_TYPE, REPLY_COMMAND, seqNo, ByteArray(0))

    /** One fully-decoded data-channel message: its type/seqno (for replying) and the MRP protobufs. */
    data class Decoded(val messageType: String, val seqNo: Long, val protobufMessages: List<ByteArray>)

    /**
     * Splits [frame] (one already HAP-decrypted plaintext blob — frames are not re-split here,
     * the record layer already dealt with HAP's own 1024-byte limit) into its header and payload,
     * and the payload's `params.data` into individual varint-prefixed protobuf messages.
     */
    fun decodeMessages(frame: ByteArray): Decoded {
        require(frame.size >= HEADER_SIZE) { "data-channel frame shorter than its own header (${frame.size} bytes)" }
        val messageType = String(frame, 4, TYPE_FIELD_SIZE, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        // seqno sits right after message_type (12 bytes) and command (4 bytes), i.e. at offset 20.
        val seqNo = beUint(frame, 4 + TYPE_FIELD_SIZE + 4, 8)
        val payload = frame.copyOfRange(HEADER_SIZE, frame.size)
        val protobufs = decodePayloadData(payload)
        return Decoded(messageType, seqNo, protobufs)
    }

    /** Parses the bplist payload and splits its `params.data` blob into individual protobuf messages. */
    fun decodePayloadData(payload: ByteArray): List<ByteArray> {
        if (payload.isEmpty()) return emptyList()
        val root = BinaryPlist.decode(payload)
        val data = root.asMap()["params"]?.asMap()?.get("data")?.asBytes() ?: return emptyList()
        return decodeProtobufs(data)
    }

    /**
     * Splits a `data` blob into its individual varint-length-prefixed protobuf messages — except
     * for the one real-world case pyatv documents where the very first message on a brand-new
     * data channel (a `ConfigureConnectionMessage`) arrives with *no* varint prefix at all: this
     * is detected heuristically (a prefix byte matching protobuf's own field-1-varint tag, `0x08`,
     * immediately followed by bytes that don't parse as a sane length) and the whole blob is then
     * treated as that one unprefixed message instead of trying to split it.
     */
    fun decodeProtobufs(data: ByteArray): List<ByteArray> {
        if (data.isEmpty()) return emptyList()
        if (looksUnprefixed(data)) return listOf(data)
        val out = ArrayList<ByteArray>()
        var pos = 0
        while (pos < data.size) {
            val (length, after) = readVarint(data, pos)
            if (length < 0 || after + length > data.size) {
                // A malformed/unexpected split: safer to hand back what's left as one message than
                // to throw and lose a connection over a single odd frame.
                out.add(data.copyOfRange(pos, data.size))
                break
            }
            out.add(data.copyOfRange(after, after + length))
            pos = after + length
        }
        return out
    }

    /**
     * Reassembles data-channel messages out of individual HAP record-layer frames: a message
     * under ~1000 bytes fits in one HAP frame, but a larger one (e.g. a `PlaybackQueue` reply
     * carrying artwork) spans several, each capped at [HapRecordLayer.MAX_FRAME] plaintext bytes
     * — [AirPlayChannel.receiveFrame] only ever returns one such frame at a time, so the pieces
     * have to be stitched back together here using the message's own `size` header field before
     * [decodeMessages] can parse it.
     */
    class FrameAssembler {
        private val buffer = ByteArrayOutputStream()

        /** Feeds one newly-received HAP frame; returns every data-channel message now complete. */
        fun feed(hapFrame: ByteArray): List<Decoded> {
            buffer.write(hapFrame)
            val out = ArrayList<Decoded>()
            while (true) {
                val message = tryTakeOneMessage() ?: break
                out.add(message)
            }
            return out
        }

        /** Extracts and decodes one complete message from [buffer], or null if it isn't fully there yet. */
        private fun tryTakeOneMessage(): Decoded? {
            val bytes = buffer.toByteArray()
            if (bytes.size < 4) return null
            val totalSize = beUint(bytes, 0, 4).toInt()
            if (bytes.size < totalSize) return null
            buffer.reset()
            buffer.write(bytes, totalSize, bytes.size - totalSize)
            return decodeMessages(bytes.copyOfRange(0, totalSize))
        }
    }

    private fun looksUnprefixed(data: ByteArray): Boolean {
        // A length-prefixed stream's first byte is a varint (any value); an unprefixed
        // ConfigureConnectionMessage's first byte is its own field-1 tag, 0x08 (varint field 1).
        // The two are genuinely ambiguous on the first byte alone, but a length of 0x08 would mean
        // "an 8-byte first message", which every real MRP message here is far larger than — so a
        // declared length that small (and not matching the rest of the buffer as a clean split) is
        // the signal actually used, same as pyatv's own client special-cases this exact message.
        if (data[0].toInt() and 0xFF != 0x08) return false
        val (declaredLength, after) = readVarint(data, 0)
        return after + declaredLength != data.size
    }
}
