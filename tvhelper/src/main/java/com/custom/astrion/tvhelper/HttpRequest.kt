package com.custom.astrion.tvhelper

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.URLDecoder

/** One HTTP request, as much of it as the helper needs: method, path, query and headers (no body). */
data class HttpRequest(val method: String, val path: String, val query: Map<String, String>, val headers: Map<String, String>) {
    fun header(name: String): String? = headers[name.lowercase()]

    companion object {
        private const val MAX_HEAD = 8 * 1024

        /** Reads the request line and headers; throws [IOException] on anything malformed or too long. */
        fun read(input: InputStream): HttpRequest {
            val head = readHead(input)
            val lines = head.split("\r\n")
            val parts = lines.first().split(' ')
            if (parts.size < 3) throw IOException("bad request line")
            val target = parts[1]
            val headers =
                lines.drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                }.toMap()
            return HttpRequest(parts[0].uppercase(), target.substringBefore('?'), parseQuery(target.substringAfter('?', "")), headers)
        }

        private fun readHead(input: InputStream): String {
            val out = ByteArrayOutputStream()
            var match = 0
            val end = byteArrayOf('\r'.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte())
            while (match < end.size) {
                val b = input.read()
                if (b < 0) throw IOException("connection closed")
                out.write(b)
                if (out.size() > MAX_HEAD) throw IOException("request too large")
                match = if (b.toByte() == end[match]) match + 1 else if (b.toByte() == end[0]) 1 else 0
            }
            return out.toString("ISO-8859-1").removeSuffix("\r\n\r\n")
        }

        fun parseQuery(query: String): Map<String, String> = query.split('&').filter { it.isNotEmpty() }.associate { pair ->
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            URLDecoder.decode(k, "UTF-8") to URLDecoder.decode(v, "UTF-8")
        }
    }
}

/** An answer: status line, type and body. CORS is open so a browser page on the remote could call it too. */
class HttpResponse(val status: Int, val contentType: String, val body: ByteArray) {
    fun write(out: OutputStream) {
        val head =
            "HTTP/1.1 $status ${reason(status)}\r\n" +
                "Content-Type: $contentType\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Cache-Control: no-store\r\n" +
                "Access-Control-Allow-Origin: *\r\n" +
                "Connection: close\r\n\r\n"
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(body)
        out.flush()
    }

    companion object {
        fun json(body: String, status: Int = 200) =
            HttpResponse(status, "application/json; charset=utf-8", body.toByteArray(Charsets.UTF_8))

        fun error(status: Int, message: String) = json(Json.obj("error" to message), status)

        private fun reason(status: Int) = when (status) {
            200 -> "OK"
            400 -> "Bad Request"
            401 -> "Unauthorized"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            409 -> "Conflict"
            else -> "Error"
        }
    }
}
