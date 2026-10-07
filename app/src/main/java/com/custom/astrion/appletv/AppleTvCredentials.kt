package com.custom.astrion.appletv

import java.security.MessageDigest

/**
 * Long-term pairing credentials for one Apple TV, produced by
 * [CompanionPairing] and required for every later connection.
 *
 * [serialize] uses the `ltpk:ltsk:atv_id:client_id` (all hex) layout that
 * pyatv and Home Assistant's Apple TV integration use, so credentials
 * exported from either of them can be pasted in as-is.
 */
class AppleTvCredentials(
    /** Accessory's long-term Ed25519 public key. */
    val ltpk: ByteArray,
    /** Our long-term Ed25519 private seed. */
    val ltsk: ByteArray,
    /** Accessory identifier. */
    val atvId: ByteArray,
    /** Our pairing identifier. */
    val clientId: ByteArray
) {
    fun serialize(): String = listOf(ltpk, ltsk, atvId, clientId).joinToString(":") { it.toHex() }

    /** Stable id for this physical Apple TV (derived from its accessory identifier), so re-pairing the same box yields the same id. */
    fun deviceKey(): String = "atv_" + MessageDigest.getInstance("SHA-1").digest(atvId).toHex().take(10)

    companion object {
        /** Returns null for anything that isn't a valid four-part hex credentials string. */
        fun parse(text: String?): AppleTvCredentials? {
            val parts = text?.trim()?.split(":") ?: return null
            if (parts.size != 4 || parts.any { it.isEmpty() }) return null
            return try {
                val bytes = parts.map { it.hexToBytes() }
                AppleTvCredentials(ltpk = bytes[0], ltsk = bytes[1], atvId = bytes[2], clientId = bytes[3])
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}
