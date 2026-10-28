package com.custom.astrion.androidtv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import javax.net.ssl.SSLSocket

/** What pairing learns about a TV. */
data class PairedAndroidTv(
    /** The name the TV gives itself in its certificate ("SHIELD Android TV"…), may be blank. */
    val name: String,
    /** Its MAC address, from the same certificate — a stable identity across IP changes. */
    val mac: String,
    /** SHA-256 of its certificate: only this TV is accepted afterwards. */
    val certSha256: String
)

class AndroidTvPairingException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Pairing with an Android TV (port 6467), in two steps like the Google TV app: [begin] makes
 * the TV show a 6-character code, [finish] sends the secret derived from that code. Blocking:
 * call off the main thread. Single use.
 */
class AndroidTvPairing(
    private val host: String,
    private val identity: AndroidTvIdentity,
    private val clientName: String = "Astrion Custom",
    private val port: Int = PAIRING_PORT,
    private val timeoutMs: Int = TIMEOUT_MS
) : AutoCloseable {
    private var socket: SSLSocket? = null
    private var serverCertificate: X509Certificate? = null

    /** Connects and asks the TV to show a code. Returns once the code is on screen. */
    fun begin() {
        try {
            val s = identity.connect(host, port, timeoutMs, onServerCertificate = { serverCertificate = it })
            socket = s
            send(PoloMessages.pairingRequest(clientName))
            while (true) {
                val reply = receive()
                when {
                    reply.has(PoloMessages.FIELD_PAIRING_REQUEST_ACK) -> send(PoloMessages.options())
                    reply.has(PoloMessages.FIELD_OPTIONS) -> send(PoloMessages.configuration())
                    reply.has(PoloMessages.FIELD_CONFIGURATION_ACK) -> return
                    else -> fail("unexpected pairing step from the TV: $reply")
                }
            }
        } catch (e: AndroidTvPairingException) {
            close()
            throw e
        } catch (e: Exception) {
            close()
            throw AndroidTvPairingException("Couldn't start pairing with $host: ${e.message}", e)
        }
    }

    /** Sends the secret for [code] (as shown on the TV). Throws when the code is wrong. */
    fun finish(code: String): PairedAndroidTv {
        val s = socket ?: fail("Pairing session closed: start again")
        val server = serverCertificate ?: fail("No certificate from the TV")
        try {
            val secret = secret(identity.certificate, server, code) ?: fail("Wrong code")
            send(PoloMessages.secret(secret))
            val reply = receive()
            if (!reply.has(PoloMessages.FIELD_SECRET_ACK)) fail("The TV refused the code")
            val (name, mac) = nameAndMac(server)
            return PairedAndroidTv(name, mac, AndroidTvIdentity.sha256(server.encoded))
        } catch (e: AndroidTvPairingException) {
            throw e
        } catch (e: Exception) {
            throw AndroidTvPairingException("Pairing failed: ${e.message}", e)
        } finally {
            runCatching { s.close() }
            socket = null
        }
    }

    private fun fail(message: String): Nothing = throw AndroidTvPairingException(message)

    override fun close() {
        runCatching { socket?.close() }
        socket = null
    }

    private fun send(message: ByteArray) = ProtoWriter.writeDelimited(socket!!.outputStream, message)

    private fun receive(): ProtoMessage {
        val message = ProtoMessage.parse(ProtoMessage.readDelimited(socket!!.inputStream))
        val status = message.int(PoloMessages.FIELD_STATUS)
        if (status != PoloMessages.STATUS_OK) {
            // 402 = bad secret, i.e. a wrong code; anything else is a refusal of the step.
            throw AndroidTvPairingException(if (status == STATUS_BAD_SECRET) "Wrong code" else "The TV refused pairing (status $status)")
        }
        return message
    }

    companion object {
        const val PAIRING_PORT = 6467
        private const val TIMEOUT_MS = 10_000
        private const val STATUS_BAD_SECRET = 402
        private const val CODE_LENGTH = 6
        private const val HEX = 16

        /**
         * The pairing secret: SHA-256 over both RSA keys (modulus, then exponent, as hex digits) and
         * the last 4 digits of the code; the code's first 2 digits must equal its first byte —
         * which is how a mistyped code is caught before sending. Null when [code] doesn't match.
         */
        fun secret(client: X509Certificate, server: X509Certificate, code: String): ByteArray? {
            val c = code.trim().uppercase()
            if (c.length != CODE_LENGTH || c.any { Character.digit(it, HEX) < 0 }) return null
            val clientKey = client.publicKey as RSAPublicKey
            val serverKey = server.publicKey as RSAPublicKey
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(hexBytes(clientKey.modulus.hex()))
            digest.update(hexBytes("0" + clientKey.publicExponent.hex()))
            digest.update(hexBytes(serverKey.modulus.hex()))
            digest.update(hexBytes("0" + serverKey.publicExponent.hex()))
            digest.update(hexBytes(c.substring(2)))
            val hash = digest.digest()
            return hash.takeIf { (it[0].toInt() and 0xFF) == c.substring(0, 2).toInt(HEX) }
        }

        private fun BigInteger.hex() = toString(HEX).uppercase()

        private fun hexBytes(hex: String): ByteArray {
            // Same as Python's bytes.fromhex(): an odd number of digits is padded on the left.
            val h = if (hex.length % 2 == 1) "0$hex" else hex
            return ByteArray(h.length / 2) { i -> h.substring(i * 2, i * 2 + 2).toInt(HEX).toByte() }
        }

        /**
         * The TV's name and MAC from its certificate subject, e.g.
         * `CN=atvremote/darcy/darcy/SHIELD Android TV/XX:XX:XX:XX:XX:XX` (NVIDIA SHIELD) or
         * `dnQualifier=fugu/fugu/Nexus Player, CN=atvremote/XX:XX:XX:XX:XX:XX`.
         */
        fun nameAndMac(cert: X509Certificate): Pair<String, String> {
            val subject = cert.subjectX500Principal.getName(javax.security.auth.x500.X500Principal.RFC2253)
            val cn = Regex("(?:^|,)CN=([^,]*)").find(subject)?.groupValues?.get(1).orEmpty()
            val dnq = Regex("(?:^|,)(?:dnQualifier|2\\.5\\.4\\.46)=([^,]*)").find(subject)?.groupValues?.get(1).orEmpty()
            val cnParts = cn.split('/')
            val name =
                when {
                    dnq.isNotEmpty() -> dnq.split('/').last()
                    cnParts.size > 1 -> cnParts[cnParts.size - 2]
                    else -> cn
                }
            val mac = cnParts.last().takeIf { Regex("([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}").matches(it) }.orEmpty()
            return name to mac
        }
    }
}
