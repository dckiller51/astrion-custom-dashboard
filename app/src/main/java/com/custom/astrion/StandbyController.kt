package com.custom.astrion

import android.app.Activity
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import com.custom.astrion.config.PowerConfig
import com.custom.astrion.ha.HaClient
import java.io.File
import java.util.concurrent.Executors

/**
 * What the remote does to save battery around the screen going off — kept out of MainActivity so
 * the whole standby policy reads in one place. Driven by the `power` block of dashboard.json
 * ([PowerConfig]):
 *
 *  - **Quiet Home Assistant.** [PowerConfig.screenOffFilterSeconds] after the screen goes off (and
 *    the remote isn't docked), the HA connection stops receiving every state change — only
 *    [PowerConfig.screenOffEntities] stay live. Screen on: every state is re-read, so the
 *    dashboard is current the moment it's seen.
 *  - **Motion-wake for a limited time.** The accelerometer listens for
 *    [PowerConfig.motionWakeMinutes] after the screen goes off, then stops until the screen comes
 *    back (it's only needed to catch a pick-up shortly after the remote was put down). The HA100's
 *    accelerometer isn't a wake-up sensor: it says nothing while the processor sleeps, so the
 *    processor is kept awake (screen off) for that time (1.4.1 — it used to stay awake only by
 *    chance, e.g. while the TV Helper was polled, and the pick-up went unseen once it slept).
 *  - **Screen timeout.** When [PowerConfig.screenTimeoutSeconds] is set and the app may write
 *    system settings (`adb shell appops set com.custom.astrion WRITE_SETTINGS allow`, once), the
 *    system screen timeout is held at that value — some setups leave it at "never", which keeps
 *    an undocked remote lit until its battery is flat.
 *  - **Network-aware reconnect.** HA reconnects as soon as Wi-Fi is back, and doesn't retry in
 *    a loop while there is none.
 *  - **Screen state file** (1.4.0): `/sdcard/astrion/.screen` holds `on` or `off`, for the
 *    optional "wake keys" remote tweak (device/astrion-tweaks.sh). It replays the key that woke
 *    the screen once this says `on`; the HA100's backlight level can't tell (it stays at 255).
 */
class StandbyController(
    private val activity: Activity,
    private val client: () -> HaClient?,
    private val isDocked: () -> Boolean,
    private val motion: MotionHooks,
    /** Quiet mode on/off for what else polls the network (the TV Helpers, 1.4.0). */
    private val onQuiet: (Boolean) -> Unit = {}
) {
    /** Turns the motion-wake sensor listener on/off; [enabled] says whether the user wants it at all. */
    class MotionHooks(val enabled: () -> Boolean, val register: () -> Unit, val unregister: () -> Unit)

    var config: PowerConfig = PowerConfig()
        set(value) {
            field = value
            applyScreenTimeout()
        }

    private val handler = Handler(Looper.getMainLooper())
    private var screenOn = true

    private val goQuiet =
        Runnable {
            if (!screenOn && !isDocked()) {
                client()?.setQuiet(true, config.screenOffEntities.toSet())
                onQuiet(true)
            }
        }
    private val stopMotion =
        Runnable {
            if (!screenOn) {
                Log.i(TAG, "motion-wake window over (${config.motionWakeMinutes} min) — sensor off until the screen comes on")
                motion.unregister()
            }
            releaseMotionLock()
        }

    private val motionLock: PowerManager.WakeLock? =
        (activity.getSystemService(Context.POWER_SERVICE) as? PowerManager)
            ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astrion:motionwake-window")
            ?.apply { setReferenceCounted(false) }

    /** Keeps the processor (not the screen) awake for [ms], so the accelerometer keeps reporting. */
    private fun holdMotionLock(ms: Long) {
        val lock = motionLock ?: return
        runCatching { lock.acquire(ms) }.onFailure { Log.w(TAG, "motion-wake lock refused", it) }
        Log.i(TAG, "motion wake: processor kept awake for ${ms / SECOND_MS} s so the sensor keeps reporting")
    }

    private fun releaseMotionLock() {
        val lock = motionLock ?: return
        if (lock.isHeld) runCatching { lock.release() }
    }

    private val connectivity = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
    private val networkCallback =
        object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { client()?.onNetworkAvailable() }
            }
        }

    fun start() {
        client()?.networkAvailable = ::hasNetwork
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "network callback unavailable", it) }
        applyScreenTimeout()
        writeScreenState(true)
    }

    fun stop() {
        handler.removeCallbacksAndMessages(null)
        releaseMotionLock()
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
    }

    /** A new HaClient was created (connection settings saved): hook it up again. */
    fun onClientReplaced() {
        client()?.networkAvailable = ::hasNetwork
    }

    fun onScreenOff() {
        screenOn = false
        writeScreenState(false)
        handler.removeCallbacks(goQuiet)
        handler.removeCallbacks(stopMotion)
        val filter = config.screenOffFilterSeconds
        if (filter >= 0) handler.postDelayed(goQuiet, filter * SECOND_MS)
        val listening = motion.enabled() && !isDocked()
        when (val minutes = config.motionWakeMinutes) {
            -1 -> if (listening) holdMotionLock(ALWAYS_LOCK_MS) // as long as the screen is off: "Always"
            0 -> motion.unregister()
            else -> {
                if (listening) holdMotionLock(minutes * MINUTE_MS)
                handler.postDelayed(stopMotion, minutes * MINUTE_MS)
            }
        }
    }

    fun onScreenOn() {
        screenOn = true
        writeScreenState(true)
        handler.removeCallbacks(goQuiet)
        handler.removeCallbacks(stopMotion)
        releaseMotionLock()
        client()?.setQuiet(false)
        onQuiet(false)
        if (motion.enabled()) motion.register()
        applyScreenTimeout()
    }

    private fun hasNetwork(): Boolean {
        val cm = connectivity ?: return true
        val caps = runCatching { cm.getNetworkCapabilities(cm.activeNetwork) }.getOrNull() ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /** Holds the system screen timeout at [PowerConfig.screenTimeoutSeconds], when set and allowed. */
    private fun applyScreenTimeout() {
        val seconds = config.screenTimeoutSeconds ?: return
        if (!Settings.System.canWrite(activity)) {
            Log.i(TAG, "screen_timeout_seconds set but WRITE_SETTINGS not granted — leaving the system timeout alone")
            return
        }
        val wanted = seconds * SECOND_MS
        val current = Settings.System.getLong(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1L)
        if (current == wanted) return
        runCatching { Settings.System.putLong(activity.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, wanted) }
            .onSuccess { Log.i(TAG, "screen timeout ${current}ms -> ${wanted}ms") }
            .onFailure { Log.w(TAG, "couldn't set the screen timeout", it) }
    }

    /** Off the main thread, one write at a time and in order (a quick off/on can't end up "off"). */
    private fun writeScreenState(on: Boolean) {
        fileWriter.execute {
            runCatching {
                val dir = File(Environment.getExternalStorageDirectory(), "astrion").apply { mkdirs() }
                File(dir, SCREEN_FILE).writeText(if (on) "on\n" else "off\n")
            }.onFailure { Log.w(TAG, "screen state file not written", it) }
        }
    }

    private val fileWriter = Executors.newSingleThreadExecutor()

    private companion object {
        const val TAG = "Standby"
        const val SCREEN_FILE = ".screen"
        const val SECOND_MS = 1000L
        const val MINUTE_MS = 60_000L

        /** "Always": re-held at every screen-off; a cap so a lost release can't keep it forever. */
        const val ALWAYS_LOCK_MS = 12 * 60 * MINUTE_MS
    }
}
