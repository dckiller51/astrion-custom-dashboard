package com.custom.astrion.appletv.mrp

import com.custom.astrion.appletv.AppleTvCredentials
import com.custom.astrion.appletv.CompanionException
import com.custom.astrion.appletv.HapCrypto
import com.custom.astrion.appletv.HapPinPairing
import com.custom.astrion.appletv.SrpClient
import com.custom.astrion.appletv.Tlv8
import java.util.UUID

private const val MRP_SALT = "MediaRemote-Salt"
private const val MRP_OUTPUT_INFO = "MediaRemote-Write-Encryption-Key"
private const val MRP_INPUT_INFO = "MediaRemote-Read-Encryption-Key"

/** Extracts and validates the TLV8 blob a `CryptoPairingMessage` carries. */
private fun pairingData(reply: MrpMessage): Map<Int, ByteArray> {
    val raw = reply.inner(Mrp.EXT_CRYPTO_PAIRING)?.bytesField(Mrp.CP_PAIRING_DATA) ?: throw CompanionException("No pairing data in reply")
    val tlv = Tlv8.decode(raw)
    if (tlv.containsKey(Tlv8.ERROR)) throw CompanionException("Pairing rejected: ${Tlv8.describeError(tlv)}")
    return tlv
}

private fun requireTlv(tlv: Map<Int, ByteArray>, tag: Int, whatsMissing: String): ByteArray =
    tlv[tag] ?: throw CompanionException(whatsMissing)

/**
 * The first message on any MRP connection must be a `DeviceInfoMessage`, or the Apple TV never
 * answers anything (pyatv documents this in its own protocol code). Its reply is the device's own
 * info, which this client doesn't need — only that it arrives.
 */
fun MrpConnection.exchangeDeviceInfo(name: String, pairingId: String) {
    val id = MrpMessages.newIdentifier()
    sendAndReceive(MrpMessages.deviceInfo(name, pairingId, id), id)
}

/** HAP pair-verify over MRP: same crypto as Companion's, different framing and transport-key salts. */
object MrpPairVerify {
    private fun decryptServerInfo(sessionKey: ByteArray, encrypted: ByteArray): Map<Int, ByteArray> = try {
        Tlv8.decode(HapCrypto.chachaDecrypt(sessionKey, HapCrypto.labelNonce("PV-Msg02"), encrypted))
    } catch (e: Exception) {
        throw CompanionException("Verify: cannot decrypt device response (were the credentials created for this Apple TV?)", e)
    }

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

    private fun exchange(conn: MrpConnection, tlv: ByteArray): MrpMessage =
        conn.sendAndReceiveByType(MrpMessages.cryptoPairing(tlv, isPairing = false), Mrp.TYPE_CRYPTO_PAIRING)

    /** Runs the two verify exchanges over [conn] and arms transport encryption on it. */
    fun run(conn: MrpConnection, credentials: AppleTvCredentials) {
        val keyPair = HapCrypto.newX25519()
        val first = exchange(conn, Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to keyPair.publicBytes)))
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
        conn.armEncryptionAfterNextPairingReply(
            HapCrypto.hkdfSha512(shared, MRP_SALT, MRP_OUTPUT_INFO),
            HapCrypto.hkdfSha512(shared, MRP_SALT, MRP_INPUT_INFO)
        )
        pairingData(exchange(conn, Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to reply))))
    }
}

/**
 * HAP pair-setup for the MRP service (a separate pairing from Companion's — the Apple TV asks for
 * a PIN again). Usage: [begin] (the TV shows a PIN), then [finish] with what the user reads off it.
 */
class MrpPairing(private val host: String, private val port: Int, private val clientName: String = "Astrion") : HapPinPairing {
    private val conn =
        MrpConnection(
            host,
            port,
            object : MrpConnection.Listener {
                override fun onMessage(message: MrpMessage) = Unit

                override fun onClosed(error: Throwable?) = Unit
            }
        )
    private val seed = HapCrypto.randomBytes(32)
    private val pairingId = UUID.randomUUID().toString()
    private var salt: ByteArray? = null
    private var serverPublic: ByteArray? = null

    private fun exchange(tlv: ByteArray, isPairing: Boolean = false): MrpMessage =
        conn.sendAndReceiveByType(MrpMessages.cryptoPairing(tlv, isPairing), Mrp.TYPE_CRYPTO_PAIRING)

    /** Connects, sends the mandatory opening `DeviceInfoMessage` and starts pair-setup; the Apple TV displays the PIN. */
    override fun begin() {
        conn.connect()
        conn.exchangeDeviceInfo(clientName, pairingId)
        val reply = exchange(Tlv8.encode(listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1))), isPairing = true)
        val tlv = pairingData(reply)
        salt = requireTlv(tlv, Tlv8.SALT, "Pairing: missing salt")
        serverPublic = requireTlv(tlv, Tlv8.PUBLIC_KEY, "Pairing: missing device public key")
    }

    /** Completes pairing with the PIN shown on screen and returns credentials to store (same format as Companion's). */
    override fun finish(pin: String): AppleTvCredentials {
        val srp = computeSrp(pin)
        pairingData(sendProof(srp)) // throws with "wrong PIN" details if the device rejected our proof
        val sessionKey = HapCrypto.hkdfSha512(srp.sessionKey, "Pair-Setup-Encrypt-Salt", "Pair-Setup-Encrypt-Info")
        val accessory = exchangeIdentities(srp, sessionKey)
        return AppleTvCredentials(
            ltpk = requireTlv(accessory, Tlv8.PUBLIC_KEY, "Pairing: missing device key"),
            ltsk = seed,
            atvId = requireTlv(accessory, Tlv8.IDENTIFIER, "Pairing: missing device identifier"),
            clientId = pairingId.toByteArray(Charsets.US_ASCII)
        )
    }

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

    private fun sendProof(srp: SrpClient.Result): MrpMessage =
        exchange(Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof)))

    /** Sends our long-term identity (M5) and returns the accessory's decrypted identity (M6). */
    private fun exchangeIdentities(srp: SrpClient.Result, sessionKey: ByteArray): Map<Int, ByteArray> {
        val signSeed = HapCrypto.hkdfSha512(srp.sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val publicKey = HapCrypto.ed25519PublicKey(seed)
        val pairingIdBytes = pairingId.toByteArray(Charsets.US_ASCII)
        val signature = HapCrypto.ed25519Sign(seed, signSeed + pairingIdBytes + publicKey)
        val inner =
            Tlv8.encode(
                listOf(
                    Tlv8.IDENTIFIER to pairingIdBytes,
                    Tlv8.PUBLIC_KEY to publicKey,
                    Tlv8.SIGNATURE to signature
                )
            )
        val sealed = HapCrypto.chachaEncrypt(sessionKey, HapCrypto.labelNonce("PS-Msg05"), inner)
        val m5 = exchange(Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to sealed)))
        val encrypted = requireTlv(pairingData(m5), Tlv8.ENCRYPTED_DATA, "Pairing: missing final data")
        return try {
            Tlv8.decode(HapCrypto.chachaDecrypt(sessionKey, HapCrypto.labelNonce("PS-Msg06"), encrypted))
        } catch (e: Exception) {
            throw CompanionException("Pairing: cannot decrypt the device's final message", e)
        }
    }

    override fun close() = conn.close()
}
