package com.custom.astrion.appletv

/**
 * What [AppleTvRegistry]'s MRP pairing session needs from whichever HAP pair-setup flow is
 * running — [com.custom.astrion.appletv.mrp.MrpPairing] (classic, pre-tvOS-15) or
 * [com.custom.astrion.appletv.airplay.AirPlayPairing] (the AirPlay-2-tunnel pairing every modern
 * Apple TV actually needs). [CompanionPairing] implements this too, though its own session map
 * doesn't need the common type since it only ever holds one kind.
 */
internal interface HapPinPairing : AutoCloseable {
    fun begin()

    fun finish(pin: String): AppleTvCredentials
}
