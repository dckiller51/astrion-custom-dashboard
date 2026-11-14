package com.custom.astrion.tvhelper

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log

/**
 * "Stay reachable in standby" (1.1.0): boxes like the Xiaomi Mi Box S fall into a deep sleep the
 * remote can't wake them from (Bluetooth only answers their own remote, no HDMI-CEC). With this on,
 * the screen still turns off but the processor and the Wi-Fi stay up, so the helper (and the
 * Android TV Remote protocol) still answer, and `/wake` turns the screen back on.
 */
object KeepAwake {
    private const val PREFS = "helper"
    private const val KEY = "keep_awake"

    fun enabled(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY, false)

    fun set(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY, on).apply()
        if (on) start(context) else context.stopService(Intent(context, KeepAwakeService::class.java))
    }

    /** Starts the service when the option is on; never throws (Android 12+ may refuse from the background). */
    fun start(context: Context) {
        if (!enabled(context)) return
        val intent = Intent(context, KeepAwakeService::class.java)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.startForegroundService(intent) else context.startService(intent)
        }.onFailure { Log.w(TAG, "keep-awake service not started: ${it.message}") }
    }

    /** Whether Android leaves the helper out of its battery savings (Doze), which would cut the Wi-Fi anyway. */
    fun unrestricted(context: Context): Boolean =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(context.packageName)

    /**
     * Turns the screen on, as the box's own remote would: a screen wake lock that wakes the device
     * (deprecated, but what an app has), then, if the screen is still off (a box just woken by
     * Wake-on-LAN, which goes back to sleep without a user), [WakeActivity], which asks for the
     * screen as it opens. Returns whether the screen is on afterwards.
     */
    fun wakeScreen(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager

        @Suppress("DEPRECATION")
        val flags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE
        runCatching { pm.newWakeLock(flags, "astrion:wake").acquire(WAKE_HOLD_MS) }
            .onFailure { Log.w(TAG, "wake lock failed: ${it.message}") }
        // At once, not after waiting on the wake lock: a box woken by Wake-on-LAN is only up for
        // about a second, and on the Mi Box S only the wake screen turns the display on.
        if (!pm.isInteractive) {
            Log.i(TAG, "screen still off: opening the wake screen")
            runCatching {
                context.startActivity(Intent(context, WakeActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "wake screen failed: ${it.message}") }
        }
        waitInteractive(pm)
        Log.i(TAG, "wake: screen ${if (pm.isInteractive) "on" else "still off"}")
        return pm.isInteractive
    }

    private fun waitInteractive(pm: PowerManager): Boolean {
        repeat(WAIT_STEPS) {
            if (pm.isInteractive) return true
            Thread.sleep(WAIT_STEP_MS)
        }
        return pm.isInteractive
    }

    const val TAG = "AstrionHelper"
    private const val WAKE_HOLD_MS = 3_000L
    private const val WAIT_STEPS = 10
    private const val WAIT_STEP_MS = 100L
}

/** Holds the processor and Wi-Fi locks while "Stay reachable in standby" is on; restarted by Android if killed. */
class KeepAwakeService : Service() {
    private var cpu: PowerManager.WakeLock? = null
    private var wifi: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIFICATION_ID, notification())
        cpu = (getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astrion:keep-awake")
            .apply {
                setReferenceCounted(false)
                acquireForever()
            }
        @Suppress("DEPRECATION")
        val mode = WifiManager.WIFI_MODE_FULL_HIGH_PERF
        wifi = (applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)
            ?.createWifiLock(mode, "astrion:keep-awake")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
        Log.i(KeepAwake.TAG, "staying reachable in standby")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        runCatching { cpu?.release() }
        runCatching { wifi?.release() }
        Log.i(KeepAwake.TAG, "no longer staying reachable in standby")
        super.onDestroy()
    }

    @SuppressLint("WakelockTimeout")
    private fun PowerManager.WakeLock.acquireForever() = acquire()

    private fun notification(): Notification {
        val title = getString(R.string.keep_awake_notification)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            @Suppress("DEPRECATION")
            return Notification.Builder(this).setContentTitle(title).setSmallIcon(R.drawable.icon).build()
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, title, NotificationManager.IMPORTANCE_MIN))
        return Notification.Builder(this, CHANNEL).setContentTitle(title).setSmallIcon(R.drawable.icon).build()
    }

    private companion object {
        const val NOTIFICATION_ID = 1
        const val CHANNEL = "keep_awake"
    }
}

/**
 * Invisible screen that turns the display on as it opens (1.1.0), as an alarm clock does; closes
 * itself after a moment, leaving the TV on what it showed.
 */
class WakeActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setTurnScreenOn(true)
            setShowWhenLocked(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                android.view.WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
        }
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.postDelayed({ finish() }, SHOW_MS)
    }

    private companion object {
        const val SHOW_MS = 1_500L
    }
}

/** Starts [KeepAwakeService] again when the box starts, if the option is on. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) KeepAwake.start(context)
    }
}
