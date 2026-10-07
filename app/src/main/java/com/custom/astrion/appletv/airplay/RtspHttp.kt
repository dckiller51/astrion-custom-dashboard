package com.custom.astrion.appletv.airplay

import java.io.ByteArrayOutputStream
import java.io.InputStream

/**
 * AirPlay's control connection speaks RTSP, which (for this purpose) is just HTTP/1.1's request
 * and status-line/header syntax with a different first word (`OPTIONS`/`SETUP`/`RECORD`/...
 * instead of `GET`/`POST`) and a different version token (`RTSP/1.0`). Framing — header order,
 * the blank-line body separator, `Content-Length`-gated bodies — is identical, so this is a small
 * text-framing layer rather than a real RTSP implementation.
 */
internal object RtspHttp {
    data class Response(val status: Int, val reasonPhrase: String, val headers: Map<String, String>, val body: ByteArray)

    /**
     * Builds one request exactly as pyatv's own `support/http.py` does: `METHOD uri RTSP/1.0`,
     * then `User-Agent`, `Content-Type` (only if there's a body) and `Content-Length` in that
     * fixed order, then any extra headers, then a blank line and the body. Real AirPlay receivers
     * are strict about header order for these three, which is why they're hardcoded first rather
     * than folded into [extraHeaders].
     */
    fun formatRequest(
        method: String,
        uri: String,
        userAgent: String,
        extraHeaders: Map<String, String> = emptyMap(),
        contentType: String? = null,
        body: ByteArray = ByteArray(0)
    ): ByteArray {
        val lines = StringBuilder()
        lines.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n")
        lines.append("User-Agent: ").append(userAgent).append("\r\n")
        if (contentType != null) lines.append("Content-Type: ").append(contentType).append("\r\n")
        lines.append("Content-Length: ").append(body.size).append("\r\n")
        extraHeaders.forEach { (k, v) -> lines.append(k).append(": ").append(v).append("\r\n") }
        lines.append("\r\n")
        val head = lines.toString().toByteArray(Charsets.US_ASCII)
        return head + body
    }

    /** Reads and parses exactly one response (status line + headers + `Content-Length` body) from [input]. */
    fun readOneResponse(input: InputStream): Response {
        val statusLine = readLine(input) ?: throw AirPlayException("connection closed before a response arrived")
        val (status, reason) = parseStatusLine(statusLine)
        val headers = readHeaders(input)
        val body = readBody(input, headers)
        return Response(status, reason, headers, body)
    }

    /** Splits a status line into its numeric status and reason phrase, e.g. `RTSP/1.0 200 OK`. */
    private fun parseStatusLine(statusLine: String): Pair<Int, String> {
        val parts = statusLine.split(' ', limit = 3)
        require(parts.size >= 2 && parts[0].startsWith("RTSP/")) { "not an RTSP/HTTP response: $statusLine" }
        val status = parts[1].toIntOrNull() ?: throw AirPlayException("bad status line: $statusLine")
        return status to parts.getOrNull(2).orEmpty()
    }

    /** Reads `Name: value` header lines up to the blank line that ends the header block. */
    private fun readHeaders(input: InputStream): Map<String, String> {
        val headers = LinkedHashMap<String, String>()
        while (true) {
            val line = readLine(input) ?: throw AirPlayException("connection closed while reading headers")
            if (line.isEmpty()) break
            val idx = line.indexOf(':')
            if (idx > 0) headers[line.substring(0, idx).trim().lowercase()] = line.substring(idx + 1).trim()
        }
        return headers
    }

    /** Reads exactly `Content-Length` bytes (0 when the header is absent) as the response body. */
    private fun readBody(input: InputStream, headers: Map<String, String>): ByteArray {
        val length = headers["content-length"]?.toIntOrNull() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) throw AirPlayException("connection closed while reading the body ($read/$length bytes)")
            read += n
        }
        return body
    }

    /** One CRLF-terminated line, decoded as ASCII (RTSP/HTTP header text is always ASCII here); null at EOF with nothing read. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream()
        var sawAny = false
        while (true) {
            val b = input.read()
            if (b < 0) return if (sawAny) out.toString(Charsets.US_ASCII.name()) else null
            sawAny = true
            if (b == '\n'.code) {
                val bytes = out.toByteArray()
                val end = if (bytes.isNotEmpty() && bytes.last() == '\r'.code.toByte()) bytes.size - 1 else bytes.size
                return String(bytes, 0, end, Charsets.US_ASCII)
            }
            out.write(b)
        }
    }
}

internal class AirPlayException(message: String, cause: Throwable? = null) : Exception(message, cause)
