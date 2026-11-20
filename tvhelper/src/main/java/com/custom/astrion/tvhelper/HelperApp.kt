package com.custom.astrion.tvhelper

import android.app.Application
import android.util.Log
import java.io.IOException

/**
 * Starts the web server with the process: when the TV binds [MediaListener] at boot, or when the
 * helper is opened from the TV's app list. Nothing else runs in the background, unless "Stay
 * reachable in standby" is on ([KeepAwake]).
 */
class HelperApp : Application() {
    override fun onCreate() {
        super.onCreate()
        val routes = HelperRoutes(HelperCode.get(this), store, AndroidHelperDevice(this))
        try {
            HelperServer(routes).start()
        } catch (e: IOException) {
            Log.e("AstrionHelper", "port ${HelperRoutes.PORT} unavailable: ${e.message}")
        }
        try {
            WakeChannel(HelperCode.get(this), { KeepAwake.wakeScreen(this) }).start()
        } catch (e: IOException) {
            Log.e("AstrionHelper", "port ${WakeChannel.PORT} unavailable: ${e.message}")
        }
        // "Stay reachable in standby" (1.1.0): its service also keeps this process, so the server, alive.
        KeepAwake.start(this)
    }

    companion object {
        /** Shared by [MediaListener] (writes) and the web server (reads). */
        val store = NowPlayingStore()
    }
}
