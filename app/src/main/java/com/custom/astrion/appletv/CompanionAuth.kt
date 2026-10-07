package com.custom.astrion.appletv

import java.util.UUID

private const val PAIRING_DATA = "_pd"

/** Extracts and validates the TLV8 blob of an authentication reply. */
private fun pairingData(reply: Map<*, *>): Map<Int, ByteArray> {
    val raw = reply[PAIRING_DATA] as? ByteArray ?: throw CompanionException("No pairing data in reply")
    val tlv = Tlv8.decode(raw)
    if (tlv.containsKey(Tlv8.ERROR)) throw CompanionException("Pairing rejected: ${Tlv8.describeError(tlv)}")
    return tlv
}

/** Reads a required TLV8 field, or fails with a message naming what's missing. */
private fun requireTlv(tlv: Map<Int, ByteArray>, tag: Int, whatsMissing: String): ByteArray =
    tlv[tag] ?: throw CompanionException(whatsMissing)

/**
 * HAP pair-verify: proves to the Apple TV (and it to us) that both sides
 * hold the long-term keys from an earlier pair-setup, and derives the
 * per-session transport keys.
 */
internal object CompanionPairVerify {
    private const val OUTPUT_INFO = "ClientEncrypt-main"
    private const val INPUT_INFO = "ServerEncrypt-main"

    /** Decrypts and TLV8-decodes the accessory's encrypted verify payload. */
    private fun decryptServerInfo(sessionKey: ByteArray, encrypted: ByteArray): Map<Int, ByteArray> = try {
        Tlv8.decode(HapCrypto.chachaDecrypt(sessionKey, HapCrypto.labelNonce("PV-Msg02"), encrypted))
    } catch (e: Exception) {
        throw CompanionException("Verify: cannot decrypt device response (were the credentials created for this Apple TV?)", e)
    }

    /** Confirms the accessory is the same one [credentials] were paired with, and that it really holds the matching key. */
    private fun verifyServerIdentity(
        credentials: AppleTvCredentials,
        identifier: ByteArray,
        serverPublic: ByteArray,
        signature: ByteArray,
        ourPublic: ByteArray
    ) {
        if (!identifier.contentEquals(credentials.atvId)) {
            throw CompanionException("Verify: this is a different Apple TV than the one paired")
        }
        if (!HapCrypto.ed25519Verify(credentials.ltpk, serverPublic + identifier + ourPublic, signature)) {
            throw CompanionException("Verify: device signature is invalid")
        }
    }

    /** Runs the two verify exchanges over [link] and arms transport encryption on it. */
    fun run(link: CompanionLink, credentials: AppleTvCredentials) {
        val keyPair = HapCrypto.newX25519()
        val first =
            link.exchangeAuth(
                FrameType.PvStart,
                mapOf(
                    PAIRING_DATA to Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to keyPair.publicBytes)),
                    "_auTy" to 4L
                )
            )
        val tlv = pairingData(first)
        val serverPublic = requireTlv(tlv, Tlv8.PUBLIC_KEY, "Verify: missing device public key")
        val encrypted = requireTlv(tlv, Tlv8.ENCRYPTED_DATA, "Verify: missing encrypted data")

        val shared = keyPair.agree(serverPublic)
        val sessionKey = HapCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val inner = decryptServerInfo(sessionKey, encrypted)
        val identifier = requireTlv(inner, Tlv8.IDENTIFIER, "Verify: missing device identifier")
        val signature = requireTlv(inner, Tlv8.SIGNATURE, "Verify: missing device signature")
        verifyServerIdentity(credentials, identifier, serverPublic, signature, keyPair.publicBytes)

        val ourSignature = HapCrypto.ed25519Sign(credentials.ltsk, keyPair.publicBytes + credentials.clientId + serverPublic)
        val reply =
            HapCrypto.chachaEncrypt(
                sessionKey,
                HapCrypto.labelNonce("PV-Msg03"),
                Tlv8.encode(listOf(Tlv8.IDENTIFIER to credentials.clientId, Tlv8.SIGNATURE to ourSignature))
            )
        link.armEncryption(
            HapCrypto.hkdfSha512(shared, "", OUTPUT_INFO),
            HapCrypto.hkdfSha512(shared, "", INPUT_INFO)
        )
        val last =
            link.exchangeAuth(
                FrameType.PvNext,
                mapOf(PAIRING_DATA to Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to reply)))
            )
        pairingData(last)
    }
}

/**
 * HAP pair-setup with the on-screen PIN. Usage: [begin] (the Apple TV now
 * shows a 4-digit code), then [finish] with what the user reads off the TV.
 */
class CompanionPairing(private val host: String, private val port: Int, private val clientName: String = "Astrion") : AutoCloseable {
    private val link =
        CompanionLink(
            host,
            port,
            object : CompanionLink.Listener {
                override fun onEvent(name: String, content: Map<*, *>) = Unit

                override fun onClosed(error: Throwable?) = Unit
            }
        )
    private val seed = HapCrypto.randomBytes(32)
    private val pairingId = UUID.randomUUID().toString().toByteArray(Charsets.US_ASCII)
    private var salt: ByteArray? = null
    private var serverPublic: ByteArray? = null

    /** Connects and starts pair-setup; the Apple TV displays the PIN. */
    fun begin() {
        link.connect()
        val reply =
            link.exchangeAuth(
                FrameType.PsStart,
                mapOf(
                    PAIRING_DATA to Tlv8.encode(listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1))),
                    "_pwTy" to 1L
                )
            )
        val tlv = pairingData(reply)
        salt = requireTlv(tlv, Tlv8.SALT, "Pairing: missing salt")
        serverPublic = requireTlv(tlv, Tlv8.PUBLIC_KEY, "Pairing: missing device public key")
    }

    /** Completes pairing with the PIN shown on screen and returns the credentials to store. */
    fun finish(pin: String): AppleTvCredentials {
        val srp = computeSrp(pin)
        // Throws with "wrong PIN" details if the device rejected our proof.
        pairingData(sendProof(srp))
        val sessionKey = HapCrypto.hkdfSha512(srp.sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val accessory = exchangeIdentities(srp, sessionKey)
        return AppleTvCredentials(
            ltpk = requireTlv(accessory, Tlv8.PUBLIC_KEY, "Pairing: missing device key"),
            ltsk = seed,
            atvId = requireTlv(accessory, Tlv8.IDENTIFIER, "Pairing: missing device identifier"),
            clientId = pairingId
        )
    }

    /** Combines the PIN with the salt/public value from [begin] into the SRP proof and session key. */
    private fun computeSrp(pin: String): SrpClient.Result {
        val saltBytes = salt
        val serverKey = serverPublic
        if (saltBytes == null || serverKey == null) throw CompanionException("Pairing was not started")
        return try {
            SrpClient(pin.trim().padStart(4, '0')).process(saltBytes, serverKey)
        } catch (e: IllegalArgumentException) {
            throw CompanionException("Pairing failed: ${e.message}", e)
        }
    }

    private fun sendProof(srp: SrpClient.Result): Map<*, *> = link.exchangeAuth(
        FrameType.PsNext,
        mapOf(
            PAIRING_DATA to
                Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof)),
            "_pwTy" to 1L
        )
    )

    /** Sends our long-term identity (M5) and returns the accessory's decrypted identity (M6). */
    private fun exchangeIdentities(srp: SrpClient.Result, sessionKey: ByteArray): Map<Int, ByteArray> {
        val signSeed = HapCrypto.hkdfSha512(srp.sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val publicKey = HapCrypto.ed25519PublicKey(seed)
        val signature = HapCrypto.ed25519Sign(seed, signSeed + pairingId + publicKey)
        val inner =
            Tlv8.encode(
                listOf(
                    Tlv8.IDENTIFIER to pairingId,
                    Tlv8.PUBLIC_KEY to publicKey,
                    Tlv8.SIGNATURE to signature,
                    Tlv8.NAME to Opack.pack(mapOf("name" to clientName))
                )
            )
        val sealed = HapCrypto.chachaEncrypt(sessionKey, HapCrypto.labelNonce("PS-Msg05"), inner)
        val m5 =
            link.exchangeAuth(
                FrameType.PsNext,
                mapOf(
                    PAIRING_DATA to Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to sealed)),
                    "_pwTy" to 1L
                )
            )
        val encrypted = requireTlv(pairingData(m5), Tlv8.ENCRYPTED_DATA, "Pairing: missing final data")
        return try {
            Tlv8.decode(HapCrypto.chachaDecrypt(sessionKey, HapCrypto.labelNonce("PS-Msg06"), encrypted))
        } catch (e: Exception) {
            throw CompanionException("Pairing: cannot decrypt the device's final message", e)
        }
    }

    override fun close() = link.close()
}
