package com.custom.astrion.appletv.airplay

import com.custom.astrion.appletv.AppleTvCredentials
import com.custom.astrion.appletv.HapCrypto
import com.custom.astrion.appletv.HapPinPairing
import com.custom.astrion.appletv.Opack
import com.custom.astrion.appletv.SrpClient
import com.custom.astrion.appletv.Tlv8
import java.util.UUID

/**
 * HAP pairing/verify over AirPlay's control connection — the same SRP-6a/Ed25519/X25519 state
 * machine `CompanionPairing`/`MrpPairing` already run, just with a third transport: the TLV8 blob
 * travels as the RTSP body itself (`Content-Type: application/octet-stream`), with no OPACK or
 * protobuf envelope around it. The crypto and TLV8 encoding are shared verbatim via [HapCrypto]/
 * [Tlv8]/[SrpClient]; only the framing (RTSP POST to `/pair-setup` / `/pair-verify`) differs.
 */
// Confirmed against pyatv's protocols/airplay/auth/hap.py: pairing/verify use this exact header
// set (including a *different* User-Agent than the RTSP session proper uses post-pairing) on
// every request, body included or not.
private const val USER_AGENT = "AirPlay/320.20"
private const val OCTET_STREAM = "application/octet-stream"
private val PAIRING_HEADERS = mapOf("Connection" to "keep-alive", "X-Apple-HKP" to "3")

private fun pairingData(response: RtspHttp.Response): Map<Int, ByteArray> {
    val tlv = Tlv8.decode(response.body)
    if (tlv.containsKey(Tlv8.ERROR)) throw AirPlayException("Pairing rejected: ${Tlv8.describeError(tlv)}")
    return tlv
}

private fun requireTlv(tlv: Map<Int, ByteArray>, tag: Int, whatsMissing: String): ByteArray =
    tlv[tag] ?: throw AirPlayException(whatsMissing)

/**
 * Pair-verify on the AirPlay control connection: proves both sides hold the long-term keys from
 * an earlier [AirPlayPairing], derives a shared secret, and arms the control connection's own
 * transport encryption. [encryptionKeys] then lets the *same* shared secret derive the event and
 * data channels' keys too (different salt/info strings per channel — see [AirPlayMrpClient]).
 */
internal class AirPlayPairVerify(private val conn: AirPlayConnection, private val credentials: AppleTvCredentials) {
    private lateinit var sharedSecret: ByteArray

    private fun decryptServerInfo(sessionKey: ByteArray, encrypted: ByteArray): Map<Int, ByteArray> = try {
        Tlv8.decode(HapCrypto.chachaDecrypt(sessionKey, HapCrypto.labelNonce("PV-Msg02"), encrypted))
    } catch (e: Exception) {
        throw AirPlayException("Verify: cannot decrypt device response (were the credentials created for this Apple TV?)", e)
    }

    private fun verifyServerIdentity(identifier: ByteArray, serverPublic: ByteArray, signature: ByteArray, ourPublic: ByteArray) {
        if (!identifier.contentEquals(credentials.atvId)) throw AirPlayException("Verify: this is a different Apple TV than the one paired")
        if (!HapCrypto.ed25519Verify(credentials.ltpk, serverPublic + identifier + ourPublic, signature)) {
            throw AirPlayException("Verify: device signature is invalid")
        }
    }

    /** Runs the two-exchange verify handshake; call [encryptionKeys] afterwards for any channel's keys. */
    fun run() {
        val keyPair = HapCrypto.newX25519()
        val first =
            conn.exchange(
                "POST",
                "/pair-verify",
                USER_AGENT,
                extraHeaders = PAIRING_HEADERS,
                contentType = OCTET_STREAM,
                body = Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(1), Tlv8.PUBLIC_KEY to keyPair.publicBytes))
            )
        val tlv = pairingData(first)
        val serverPublic = requireTlv(tlv, Tlv8.PUBLIC_KEY, "Verify: missing device public key")
        val encrypted = requireTlv(tlv, Tlv8.ENCRYPTED_DATA, "Verify: missing encrypted data")

        val shared = keyPair.agree(serverPublic)
        sharedSecret = shared
        val sessionKey = HapCrypto.hkdfSha512(shared, "Pair-Verify-Encrypt-Salt", "Pair-Verify-Encrypt-Info")
        val inner = decryptServerInfo(sessionKey, encrypted)
        val identifier = requireTlv(inner, Tlv8.IDENTIFIER, "Verify: missing device identifier")
        val signature = requireTlv(inner, Tlv8.SIGNATURE, "Verify: missing device signature")
        verifyServerIdentity(identifier, serverPublic, signature, keyPair.publicBytes)

        val ourSignature = HapCrypto.ed25519Sign(credentials.ltsk, keyPair.publicBytes + credentials.clientId + serverPublic)
        val reply =
            HapCrypto.chachaEncrypt(
                sessionKey,
                HapCrypto.labelNonce("PV-Msg03"),
                Tlv8.encode(listOf(Tlv8.IDENTIFIER to credentials.clientId, Tlv8.SIGNATURE to ourSignature))
            )
        val last =
            conn.exchange(
                "POST",
                "/pair-verify",
                USER_AGENT,
                extraHeaders = PAIRING_HEADERS,
                contentType = OCTET_STREAM,
                body = Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.ENCRYPTED_DATA to reply))
            )
        pairingData(last)
    }

    /** Derives one channel's write/read key pair from this verify's shared secret. */
    fun encryptionKeys(salt: String, outputInfo: String, inputInfo: String): Pair<ByteArray, ByteArray> =
        HapCrypto.hkdfSha512(sharedSecret, salt, outputInfo) to HapCrypto.hkdfSha512(sharedSecret, salt, inputInfo)
}

/**
 * HAP pair-setup for AirPlay — a separate pairing from both Companion's and classic MRP's (the
 * Apple TV shows yet another PIN). AirPlay additionally requires a `/pair-pin-start` call before
 * `/pair-setup` even begins (it's what actually makes the TV display the code at all; skipping it
 * is the single most common reason a hand-rolled AirPlay pairing client silently never gets a PIN
 * shown, confirmed against pyatv's own pairing flow).
 */
internal class AirPlayPairing(host: String, port: Int, private val clientName: String = "Astrion") : HapPinPairing {
    private val conn = AirPlayConnection(host, port)
    private val seed = HapCrypto.randomBytes(32)
    private val pairingId = UUID.randomUUID().toString()
    private var salt: ByteArray? = null
    private var serverPublic: ByteArray? = null

    private fun exchange(tlv: ByteArray): RtspHttp.Response =
        conn.exchange("POST", "/pair-setup", USER_AGENT, extraHeaders = PAIRING_HEADERS, contentType = OCTET_STREAM, body = tlv)

    /** Connects, triggers the PIN prompt, and starts pair-setup. */
    override fun begin() {
        conn.connect(6000)
        conn.exchange("POST", "/pair-pin-start", USER_AGENT, extraHeaders = PAIRING_HEADERS, contentType = OCTET_STREAM)
        val reply = exchange(Tlv8.encode(listOf(Tlv8.METHOD to byteArrayOf(0), Tlv8.SEQ_NO to byteArrayOf(1))))
        val tlv = pairingData(reply)
        salt = requireTlv(tlv, Tlv8.SALT, "Pairing: missing salt")
        serverPublic = requireTlv(tlv, Tlv8.PUBLIC_KEY, "Pairing: missing device public key")
    }

    /** Completes pairing with the PIN shown on screen and returns credentials to store (same format as Companion's/MRP's). */
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
        if (saltBytes == null || serverKey == null) throw AirPlayException("Pairing was not started")
        return try {
            SrpClient(pin.trim().padStart(4, '0')).process(saltBytes, serverKey)
        } catch (e: IllegalArgumentException) {
            throw AirPlayException("Pairing failed: ${e.message}", e)
        }
    }

    private fun sendProof(srp: SrpClient.Result): RtspHttp.Response =
        exchange(Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(3), Tlv8.PUBLIC_KEY to srp.publicKey, Tlv8.PROOF to srp.proof)))

    private fun exchangeIdentities(srp: SrpClient.Result, sessionKey: ByteArray): Map<Int, ByteArray> {
        val signSeed = HapCrypto.hkdfSha512(srp.sessionKey, "Pair-Setup-Controller-Sign-Salt", "Pair-Setup-Controller-Sign-Info")
        val publicKey = HapCrypto.ed25519PublicKey(seed)
        val pairingIdBytes = pairingId.toByteArray(Charsets.US_ASCII)
        val signature = HapCrypto.ed25519Sign(seed, signSeed + pairingIdBytes + publicKey)
        // Without this, the pairing still succeeds, but the Apple TV lists the paired remote as
        // an unnamed "Unknown device" under Settings > Remotes and Devices instead of showing
        // [clientName] — same mechanism as CompanionPairing's own Tlv8.NAME, confirmed against
        // pyatv's hap_srp.py (SRPAuthHandler.step3, which conditionally sets this exact TLV).
        val inner =
            Tlv8.encode(
                listOf(
                    Tlv8.IDENTIFIER to pairingIdBytes,
                    Tlv8.PUBLIC_KEY to publicKey,
                    Tlv8.SIGNATURE to signature,
                    Tlv8.NAME to Opack.pack(mapOf("name" to clientName))
                )
            )
        val sealed = HapCrypto.chachaEncrypt(sessionKey, HapCrypto.labelNonce("PS-Msg05"), inner)
        val m5 = exchange(Tlv8.encode(listOf(Tlv8.SEQ_NO to byteArrayOf(5), Tlv8.ENCRYPTED_DATA to sealed)))
        val encrypted = requireTlv(pairingData(m5), Tlv8.ENCRYPTED_DATA, "Pairing: missing final data")
        return try {
            Tlv8.decode(HapCrypto.chachaDecrypt(sessionKey, HapCrypto.labelNonce("PS-Msg06"), encrypted))
        } catch (e: Exception) {
            throw AirPlayException("Pairing: cannot decrypt the device's final message", e)
        }
    }

    override fun close() = conn.close()
}
