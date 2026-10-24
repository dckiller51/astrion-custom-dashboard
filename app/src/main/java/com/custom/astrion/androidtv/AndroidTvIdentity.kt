package com.custom.astrion.androidtv

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.net.Socket
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManager
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManager
import javax.net.ssl.X509KeyManager
import javax.net.ssl.X509TrustManager

/**
 * This remote's identity towards Android TVs (1.3.6): an RSA key and a self-signed certificate,
 * made once and shared by every Android TV it pairs with. The TV remembers the certificate when
 * pairing, and only lets that same certificate control it afterwards.
 */
class AndroidTvIdentity(val privateKey: PrivateKey, val certificate: X509Certificate) {
    /** `key:cert`, both Base64 — how [com.custom.astrion.config.RemoteSettings] stores it. */
    fun serialize(): String = Base64.getEncoder().encodeToString(privateKey.encoded) + ":" +
        Base64.getEncoder().encodeToString(certificate.encoded)

    /**
     * A TLS socket to [host]:[port] presenting this identity. The TV's certificate is
     * self-signed, so it can't be checked against any authority: [pinnedSha256] (from pairing)
     * makes sure it is still the same TV; [onServerCertificate] receives it (pairing needs it).
     */
    fun connect(
        host: String,
        port: Int,
        timeoutMs: Int,
        pinnedSha256: String? = null,
        onServerCertificate: (X509Certificate) -> Unit = {}
    ): SSLSocket {
        val context = SSLContext.getInstance("TLS")
        context.init(
            arrayOf<KeyManager>(ClientKeyManager()),
            arrayOf<TrustManager>(PinningTrustManager(pinnedSha256, onServerCertificate)),
            null
        )
        val raw = Socket()
        try {
            raw.connect(java.net.InetSocketAddress(host, port), timeoutMs)
            raw.soTimeout = timeoutMs
            val socket = context.socketFactory.createSocket(raw, host, port, true) as SSLSocket
            socket.startHandshake()
            return socket
        } catch (e: Exception) {
            runCatching { raw.close() }
            throw e
        }
    }

    private inner class ClientKeyManager : X509KeyManager {
        override fun getClientAliases(keyType: String?, issuers: Array<out Principal>?) = arrayOf(ALIAS)

        override fun chooseClientAlias(keyType: Array<out String>?, issuers: Array<out Principal>?, socket: Socket?) = ALIAS

        override fun getServerAliases(keyType: String?, issuers: Array<out Principal>?): Array<String>? = null

        override fun chooseServerAlias(keyType: String?, issuers: Array<out Principal>?, socket: Socket?): String? = null

        override fun getCertificateChain(alias: String?) = arrayOf(certificate)

        override fun getPrivateKey(alias: String?) = privateKey
    }

    /** Trusts the TV's self-signed certificate on first contact (pairing), then only that one. */
    private class PinningTrustManager(
        private val pinnedSha256: String?,
        private val onServerCertificate: (X509Certificate) -> Unit
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            throw CertificateException("client certificates are not accepted here")

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val cert = chain?.firstOrNull() ?: throw CertificateException("the TV sent no certificate")
            if (pinnedSha256 != null && sha256(cert.encoded) != pinnedSha256) {
                throw CertificateException("not the paired Android TV (its certificate changed: pair it again)")
            }
            onServerCertificate(cert)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    companion object {
        private const val ALIAS = "astrion"
        private const val KEY_BITS = 2048
        private const val VALIDITY_YEARS = 10
        private const val YEAR_MS = 365L * 24 * 3600 * 1000

        fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }

        fun parse(stored: String?): AndroidTvIdentity? = runCatching {
            val (key, cert) = stored!!.split(':')
            val decoder = Base64.getDecoder()
            val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoder.decode(key)))
            val certificate =
                CertificateFactory.getInstance("X.509")
                    .generateCertificate(ByteArrayInputStream(decoder.decode(cert))) as X509Certificate
            AndroidTvIdentity(privateKey, certificate)
        }.getOrNull()

        /** A new RSA-2048 key and a self-signed certificate (CN=atvremote, 10 years). Takes a second or two. */
        fun generate(commonName: String = "atvremote", now: Date = Date()): AndroidTvIdentity {
            val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
            val notAfter = Date(now.time + VALIDITY_YEARS * YEAR_MS)
            val name = Der.seq(Der.set(Der.seq(Der.oid(OID_COMMON_NAME), Der.utf8(commonName))))
            val sha256WithRsa = Der.seq(Der.oid(OID_SHA256_WITH_RSA), Der.NULL)
            // TBSCertificate: version 3, serial, algorithm, issuer, validity, subject, public key (already DER).
            val tbs =
                Der.seq(
                    Der.explicit(0, Der.integer(BigInteger.valueOf(2))),
                    Der.integer(BigInteger.valueOf(now.time)),
                    sha256WithRsa,
                    name,
                    Der.seq(Der.utcTime(now), Der.utcTime(notAfter)),
                    name,
                    keyPair.public.encoded
                )
            val signature = Signature.getInstance("SHA256withRSA").apply {
                initSign(keyPair.private)
                update(tbs)
            }.sign()
            val der = Der.seq(tbs, sha256WithRsa, Der.bitString(signature))
            val certificate = CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return AndroidTvIdentity(keyPair.private, certificate)
        }

        private val OID_COMMON_NAME = intArrayOf(2, 5, 4, 3)
        private val OID_SHA256_WITH_RSA = intArrayOf(1, 2, 840, 113549, 1, 1, 11)
    }
}

/** The handful of DER encodings a certificate needs. */
internal object Der {
    val NULL = byteArrayOf(0x05, 0x00)

    private fun tlv(tag: Int, content: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(tag)
        val len = content.size
        when {
            len < 0x80 -> out.write(len)
            len < 0x100 -> {
                out.write(0x81)
                out.write(len)
            }
            len < 0x10000 -> {
                out.write(0x82)
                out.write(len shr 8)
                out.write(len and 0xFF)
            }
            else -> {
                out.write(0x83)
                out.write(len shr 16)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            }
        }
        out.write(content)
        return out.toByteArray()
    }

    fun seq(vararg parts: ByteArray) = tlv(0x30, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    fun set(vararg parts: ByteArray) = tlv(0x31, parts.fold(ByteArray(0)) { acc, p -> acc + p })

    fun integer(value: BigInteger) = tlv(0x02, value.toByteArray())

    fun utf8(value: String) = tlv(0x0C, value.toByteArray(Charsets.UTF_8))

    fun bitString(value: ByteArray) = tlv(0x03, byteArrayOf(0) + value)

    fun explicit(tag: Int, content: ByteArray) = tlv(0xA0 or tag, content)

    fun utcTime(date: Date): ByteArray {
        val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        return tlv(0x17, format.format(date).toByteArray(Charsets.US_ASCII))
    }

    fun oid(parts: IntArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(parts[0] * 40 + parts[1])
        for (i in 2 until parts.size) {
            var v = parts[i]
            val stack = ArrayList<Int>()
            stack.add(v and 0x7F)
            v = v shr 7
            while (v > 0) {
                stack.add((v and 0x7F) or 0x80)
                v = v shr 7
            }
            stack.asReversed().forEach(out::write)
        }
        return tlv(0x06, out.toByteArray())
    }
}
