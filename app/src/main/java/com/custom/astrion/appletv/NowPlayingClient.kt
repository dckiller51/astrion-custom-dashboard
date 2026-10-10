package com.custom.astrion.appletv

import com.custom.astrion.appletv.mrp.MrpPlaying

/**
 * What [AppleTvDevice] needs from a now-playing session, regardless of which transport it
 * actually runs over — classic MRP-over-TCP ([com.custom.astrion.appletv.mrp.MrpClient]) or MRP
 * tunnelled through AirPlay 2 ([com.custom.astrion.appletv.airplay.AirPlayMrpClient]). Both
 * classes already have exactly this shape; this interface just names it so
 * [AppleTvDevice.tryConnectMrp] can hold either without caring which.
 */
internal interface NowPlayingClient {
    val isConnected: Boolean
    val playing: MrpPlaying?

    fun connect(timeoutMs: Int = 5000)

    fun close()

    fun fetchArtwork(location: Int, width: Double = 512.0, height: Double = -1.0): ByteArray?

    /** Asks the active app to jump to [seconds]; true when the app reports it accepted the command. */
    fun seekTo(seconds: Double): Boolean

    /** Sends a bare MRP command (see the `Mrp.CMD_*` values); true when the app accepted it. */
    fun sendCommand(command: Int): Boolean
}
