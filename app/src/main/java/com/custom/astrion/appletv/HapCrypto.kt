package com.custom.astrion.appletv

import java.math.BigInteger
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.bouncycastle.crypto.agreement.X25519Agreement
import org.bouncycastle.crypto.modes.ChaCha20Poly1305
import org.bouncycastle.crypto.params.AEADParameters
import org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters
import org.bouncycastle.crypto.params.Ed25519PublicKeyParameters
import org.bouncycastle.crypto.params.KeyParameter
import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.bouncycastle.crypto.params.X25519PublicKeyParameters
import org.bouncycastle.crypto.signers.Ed25519Signer

/** Hex helpers (the Kotlin stdlib's HexFormat is not stable on every toolchain we build with). */
internal fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

internal fun String.hexToBytes(): ByteArray {
    require(length % 2 == 0) { "odd-length hex string" }
    return ByteArray(length / 2) { i -> substring(2 * i, 2 * i + 2).toInt(16).toByte() }
}

/** Symmetric/asymmetric primitives used by HAP pairing and the Companion transport. */
internal object HapCrypto {
    private val random = SecureRandom()

    fun randomBytes(n: Int): ByteArray = ByteArray(n).also { random.nextBytes(it) }

    /** RFC 5869 HKDF with HMAC-SHA512; an empty [salt] means "no salt" (a zero-filled block). */
    fun hkdfSha512(ikm: ByteArray, salt: String, info: String, length: Int = 32): ByteArray {
        val mac = Mac.getInstance("HmacSHA512")
        val saltBytes = if (salt.isEmpty()) ByteArray(64) else salt.toByteArray(Charsets.UTF_8)
        mac.init(SecretKeySpec(saltBytes, "HmacSHA512"))
        val prk = mac.doFinal(ikm)
        mac.init(SecretKeySpec(prk, "HmacSHA512"))
        val infoBytes = info.toByteArray(Charsets.UTF_8)
        var previous = ByteArray(0)
        val out = java.io.ByteArrayOutputStream()
        var counter = 1
        while (out.size() < length) {
            mac.update(previous)
            mac.update(infoBytes)
            mac.update(counter.toByte())
            previous = mac.doFinal()
            out.write(previous)
            counter++
        }
        return out.toByteArray().copyOf(length)
    }

    /** ChaCha20-Poly1305 seal: returns ciphertext followed by the 16-byte tag. */
    fun chachaEncrypt(key: ByteArray, nonce: ByteArray, plain: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(true, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(plain.size))
        var n = cipher.processBytes(plain, 0, plain.size, out, 0)
        n += cipher.doFinal(out, n)
        return out.copyOf(n)
    }

    /** ChaCha20-Poly1305 open; throws [org.bouncycastle.crypto.InvalidCipherTextException] on a bad tag. */
    fun chachaDecrypt(key: ByteArray, nonce: ByteArray, sealed: ByteArray, aad: ByteArray? = null): ByteArray {
        val cipher = ChaCha20Poly1305()
        cipher.init(false, AEADParameters(KeyParameter(key), 128, nonce, aad))
        val out = ByteArray(cipher.getOutputSize(sealed.size))
        var n = cipher.processBytes(sealed, 0, sealed.size, out, 0)
        n += cipher.doFinal(out, n)
        return out.copyOf(n)
    }

    /** HAP message nonces: 4 zero bytes followed by an 8-byte ASCII label such as "PV-Msg02". */
    fun labelNonce(label: String): ByteArray {
        val bytes = label.toByteArray(Charsets.US_ASCII)
        require(bytes.size == 8) { "HAP nonce label must be 8 bytes" }
        return ByteArray(4) + bytes
    }

    fun ed25519PublicKey(seed: ByteArray): ByteArray = Ed25519PrivateKeyParameters(seed, 0).generatePublicKey().encoded

    fun ed25519Sign(seed: ByteArray, message: ByteArray): ByteArray {
        val signer = Ed25519Signer()
        signer.init(true, Ed25519PrivateKeyParameters(seed, 0))
        signer.update(message, 0, message.size)
        return signer.generateSignature()
    }

    fun ed25519Verify(publicKey: ByteArray, message: ByteArray, signature: ByteArray): Boolean {
        val signer = Ed25519Signer()
        signer.init(false, Ed25519PublicKeyParameters(publicKey, 0))
        signer.update(message, 0, message.size)
        return signer.verifySignature(signature)
    }

    class X25519KeyPair(val privateKey: X25519PrivateKeyParameters) {
        val publicBytes: ByteArray = privateKey.generatePublicKey().encoded

        fun agree(peerPublic: ByteArray): ByteArray {
            val agreement = X25519Agreement()
            agreement.init(privateKey)
            val out = ByteArray(agreement.agreementSize)
            agreement.calculateAgreement(X25519PublicKeyParameters(peerPublic, 0), out, 0)
            return out
        }
    }

    fun newX25519(): X25519KeyPair = X25519KeyPair(X25519PrivateKeyParameters(random))
}

/**
 * Client side of SRP-6a (3072-bit group, SHA-512) exactly as Apple's
 * Pair-Setup uses it. Apple's implementation follows the byte-encoding
 * conventions of the widely used `srptools` library (integers hashed as
 * minimal big-endian bytes, except where the group's PAD() is applied),
 * so those conventions are reproduced verbatim — a "cleaner" encoding
 * would compute a different proof and the Apple TV would reject it.
 */
internal class SrpClient(private val pin: String) {
    companion object {
        private const val USERNAME = "Pair-Setup"
        private const val PRIME_HEX =
            "FFFFFFFFFFFFFFFFC90FDAA22168C234C4C6628B80DC1CD129024E088A67CC74020BBEA63B139B22514A08798E3404DD" +
                "EF9519B3CD3A431B302B0A6DF25F14374FE1356D6D51C245E485B576625E7EC6F44C42E9A637ED6B0BFF5CB6F406B7ED" +
                "EE386BFB5A899FA5AE9F24117C4B1FE649286651ECE45B3DC2007CB8A163BF0598DA48361C55D39A69163FA8FD24CF5F" +
                "83655D23DCA3AD961C62F356208552BB9ED529077096966D670C354E4ABC9804F1746C08CA18217C32905E462E36CE3B" +
                "E39E772C180E86039B2783A2EC07A28FB5C55DF06F4C52C9DE2BCBF6955817183995497CEA956AE515D2261898FA0510" +
                "15728E5A8AAAC42DAD33170D04507A33A85521ABDF1CBA64ECFB850458DBEF0A8AEA71575D060C7DB3970F85A6E1E4C7" +
                "ABF5AE8CDB0933D71E8C94E04A25619DCEE3D2261AD2EE6BF12FFA06D98A0864D87602733EC86A64521F2B18177B200C" +
                "BBE117577A615D6C770988C0BAD946E208E24FA074E5AB3143DB5BFCE0FD108E4B82D120A93AD2CAFFFFFFFFFFFFFFFF"
        val N = BigInteger(PRIME_HEX, 16)
        val G: BigInteger = BigInteger.valueOf(5)
        private val PAD_LEN = toMinimalBytes(N).size

        fun toMinimalBytes(v: BigInteger): ByteArray {
            var hex = v.toString(16)
            if (hex.length % 2 == 1) hex = "0$hex"
            return hex.hexToBytes()
        }

        private fun pad(v: BigInteger): ByteArray {
            val raw = toMinimalBytes(v)
            return if (raw.size >= PAD_LEN) raw else ByteArray(PAD_LEN - raw.size) + raw
        }

        private fun sha512(vararg parts: ByteArray): ByteArray {
            val md = MessageDigest.getInstance("SHA-512")
            parts.forEach { md.update(it) }
            return md.digest()
        }

        private fun asInt(digest: ByteArray) = BigInteger(1, digest)
    }

    private val a = BigInteger(1, HapCrypto.randomBytes(32))
    private val bigA = G.modPow(a, N)

    /** A, encoded the way the Apple TV expects it (minimal big-endian bytes). */
    val publicKey: ByteArray = toMinimalBytes(bigA)

    class Result(val publicKey: ByteArray, val proof: ByteArray, val sessionKey: ByteArray, val expectedServerProof: ByteArray)

    /** Combines the accessory's salt and public value B with the PIN into the client proof M1 and session key K. */
    fun process(salt: ByteArray, serverPublic: ByteArray): Result {
        val b = BigInteger(1, serverPublic)
        require(b.mod(N).signum() != 0) { "invalid SRP public value from device" }

        val k = asInt(sha512(toMinimalBytes(N), pad(G)))
        val u = asInt(sha512(pad(bigA), pad(b)))
        val inner = sha512("$USERNAME:$pin".toByteArray(Charsets.UTF_8))
        val x = asInt(sha512(salt, inner))
        val v = G.modPow(x, N)
        val base = b.subtract(k.multiply(v)).mod(N)
        val s = base.modPow(a.add(u.multiply(x)), N)
        val sessionKey = sha512(toMinimalBytes(s))

        val hn = asInt(sha512(toMinimalBytes(N)))
        val hg = asInt(sha512(toMinimalBytes(G)))
        val hi = asInt(sha512(USERNAME.toByteArray(Charsets.UTF_8)))
        val aBytes = toMinimalBytes(bigA)
        val proof =
            sha512(
                toMinimalBytes(hn.xor(hg)),
                toMinimalBytes(hi),
                salt,
                aBytes,
                toMinimalBytes(b),
                sessionKey
            )
        val serverProof = sha512(aBytes, proof, sessionKey)
        return Result(aBytes, proof, sessionKey, serverProof)
    }
}
