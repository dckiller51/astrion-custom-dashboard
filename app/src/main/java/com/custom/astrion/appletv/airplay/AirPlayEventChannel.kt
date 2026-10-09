package com.custom.astrion.appletv.airplay

import java.io.ByteArrayOutputStream

/**
 * The AirPlay 2 remote-control session's *event* channel, receiver side.
 *
 * tvOS sends RTSP-style requests on it (e.g. `POST /command RTSP/1.0`) and
 * expects a `200 OK` for each one. Left unanswered — this channel used to be
 * connected and never read, on the mistaken belief that pyatv doesn't read
 * it either — tvOS tears the whole session down after about 30 seconds,
 * which showed up as "MRP connection lost: connection closed mid-frame"
 * every ~34 s, then a reconnect. pyatv's `EventChannel.handle_received`
 * answers every request with an empty `200 OK` echoing `CSeq` and `Server`,
 * plus `Content-Length: 0` and `Audio-Latency: 0`; this does the same.
 *
 * Pure byte handling (no sockets) so it can be unit-tested: feed decrypted
 * frames in, get complete requests out — a request may span frames, or one
 * frame may hold several.
 */
internal class AirPlayEventChannel {
    /** One parsed request: just what the response needs. */
    data class Request(val method: String, val path: String, val protocol: String, val headers: Map<String, String>)

    private val buffer = ByteArrayOutputStream()

    /** Adds decrypted bytes; returns every request now complete (bodies skipped). */
    fun feed(bytes: ByteArray): List<Request> {
        buffer.write(bytes)
        val requests = mutableListOf<Request>()
        var data = buffer.toByteArray()
        var next = nextMessage(data)
        while (next != null) {
            next.request?.let(requests::add)
            data = data.copyOfRange(next.consumed, data.size)
            next = nextMessage(data)
        }
        buffer.reset()
        buffer.write(data)
        return requests
    }

    /** One complete message at the start of [data], or null while it's still incomplete. */
    private class Parsed(val request: Request?, val consumed: Int)

    private fun nextMessage(data: ByteArray): Parsed? {
        val headerEnd = indexOf(data, HEADER_END)
        if (headerEnd < 0) return null
        val head = String(data, 0, headerEnd, Charsets.ISO_8859_1).split("\r\n")
        val headers = parseHeaders(head.drop(1))
        val total = headerEnd + HEADER_END.size + (headers["Content-Length"]?.trim()?.toIntOrNull() ?: 0)
        return if (data.size < total) null else Parsed(parseRequestLine(head.first(), headers), total)
    }

    private fun parseRequestLine(line: String, headers: Map<String, String>): Request? {
        val parts = line.trim().split(' ')
        return if (parts.size == REQUEST_LINE_PARTS) Request(parts[0], parts[1], parts[2], headers) else null
    }

    private fun parseHeaders(lines: List<String>): Map<String, String> = lines.mapNotNull { line ->
        val colon = line.indexOf(':')
        if (colon > 0) canonical(line.substring(0, colon).trim()) to line.substring(colon + 1).trim() else null
    }.toMap()

    companion object {
        private val HEADER_END = "\r\n\r\n".toByteArray(Charsets.ISO_8859_1)
        private const val REQUEST_LINE_PARTS = 3

        /** Header names are case-insensitive; keep the few we use in one spelling. */
        private fun canonical(name: String): String = when (name.lowercase()) {
            "cseq" -> "CSeq"
            "content-length" -> "Content-Length"
            "server" -> "Server"
            else -> name
        }

        /** The empty `200 OK` pyatv sends back for every event-channel request. */
        fun okResponse(request: Request): ByteArray {
            val lines = mutableListOf("${request.protocol} 200 OK", "Content-Length: 0", "Audio-Latency: 0")
            request.headers["Server"]?.let { lines += "Server: $it" }
            request.headers["CSeq"]?.let { lines += "CSeq: $it" }
            return (lines.joinToString("\r\n") + "\r\n\r\n").toByteArray(Charsets.ISO_8859_1)
        }

        private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
            for (i in 0..data.size - pattern.size) {
                if (pattern.indices.all { data[i + it] == pattern[it] }) return i
            }
            return -1
        }
    }
}
