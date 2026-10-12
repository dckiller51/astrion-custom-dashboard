package com.custom.astrion

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.WindowManager
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.custom.astrion.config.DockConfig

/**
 * Detects whether [activity] is sitting on its charging dock — as opposed
 * to just being plugged into a computer over USB for development, which
 * reports charging identically (both read as generic "AC" via
 * [BatteryManager], confirmed on real hardware; the dock's two charging
 * pins carry power only, bypassing the USB data lines entirely). What
 * *does* differ: whether a USB *data* connection is present at all — a
 * real cable enumerates, the dock's power-only pins never do. So: charging
 * AND no USB data connection = genuinely docked.
 *
 * While docked, also owns the dock screen's idle timer: after the [DockConfig]'s idle time
 * with no touches the remote goes "idle" — the charging screen dims to near-zero, the clock
 * mode shows its clock at the configured day/night brightness, the dashboard mode just dims
 * (see
 * [onUserInteraction], meant to be called from the Activity's own
 * override of the same name — the standard Android hook for "the user is
 * still there" regardless of which view or Compose node actually
 * consumed the touch), back up immediately on the next one.
 *
 * Extracted out of MainActivity — which was starting to accumulate a lot
 * of unrelated concerns in one place — so this one feature's state,
 * receiver, and timer live somewhere they can be read start to finish.
 */
class ChargeDockMonitor(private val activity: Activity) {

    data class State(val isCharging: Boolean, val isDocked: Boolean)

    var state by mutableStateOf(State(isCharging = false, isDocked = false))
        private set

    /** True once the remote has sat untouched on the dock for the configured idle time. */
    var dimmed by mutableStateOf(false)
        private set

    /** The effective `dock` settings (dashboard.json, with the Settings page's mode override). */
    var dockConfig: DockConfig = DockConfig()
        set(value) {
            if (field == value) return
            field = value
            if (state.isDocked) armDimTimer()
        }

    /** How long it must be charging before it counts as docked (dashboard.json `power.dock_debounce_seconds`):
     * a remote seated badly flickers between charging and not, and would otherwise flip the dock
     * screen (and the screen-on hold) on and off. Lifting it off counts at once. */
    var dockDebounceMs: Long = DEFAULT_DOCK_DEBOUNCE_MS

    private val confirmDock = Runnable { refresh(confirmed = true) }

    /** Whether it's night per Home Assistant (`sun.sun`), or null when unknown — then 21:00–07:00. */
    var sunIsDown: () -> Boolean? = { null }

    /** Night decides between [DockConfig.brightness] and its night value, and the clock's colours. */
    fun isNight(): Boolean = runCatching { sunIsDown() }.getOrNull() ?: defaultIsNight()

    private val handler = Handler(Looper.getMainLooper())
    private val dimRunnable = Runnable { applyDimmed(true) }

    // While idle, re-applies the backlight now and then so dusk/dawn switches between the day
    // and night levels without needing a touch.
    private val nightCheckRunnable =
        object : Runnable {
            override fun run() {
                if (!dimmed) return
                setWindowBrightness(idleBrightness())
                handler.postDelayed(this, NIGHT_CHECK_MS)
            }
        }

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) = refresh()
        }

    /** Call from Activity.onCreate(). */
    fun start() {
        activity.registerReceiver(
            receiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
                addAction(ACTION_USB_STATE)
            }
        )
        refresh()
        Log.i(TAG, "start — initial state=$state")
    }

    /** Call from Activity.onDestroy(). */
    fun stop() {
        runCatching { activity.unregisterReceiver(receiver) }
        handler.removeCallbacks(dimRunnable)
        handler.removeCallbacks(nightCheckRunnable)
        handler.removeCallbacks(confirmDock)
    }

    /** Call from Activity.onUserInteraction(). */
    fun onUserInteraction() {
        if (state.isDocked) armDimTimer()
    }

    /** Reads the current charge/USB state fresh via sticky broadcasts —
     * called once up front and again on every power-connected/-disconnected
     * or USB-state transition, rather than tracked incrementally, so it
     * can't drift out of sync with reality. */
    private fun refresh(confirmed: Boolean = false) {
        val batteryStatus =
            activity.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
                ?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging =
            batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING ||
                batteryStatus == BatteryManager.BATTERY_STATUS_FULL

        val usbConnected =
            activity.registerReceiver(null, IntentFilter(ACTION_USB_STATE))
                ?.getBooleanExtra(EXTRA_USB_CONNECTED, false) ?: false

        val onDock = isCharging && !usbConnected
        val docked = onDock && (state.isDocked || confirmed || dockDebounceMs <= 0L)
        handler.removeCallbacks(confirmDock)
        if (onDock && !docked) handler.postDelayed(confirmDock, dockDebounceMs)
        val newState = State(isCharging, isDocked = docked)
        if (newState == state) return
        Log.i(TAG, "state -> $newState (usbConnected=$usbConnected)")
        state = newState

        if (newState.isDocked) armDimTimer() else clearDimTimer()
    }

    private fun armDimTimer() {
        handler.removeCallbacks(dimRunnable)
        applyDimmed(false)
        handler.postDelayed(dimRunnable, dockConfig.idleMs)
    }

    private fun clearDimTimer() {
        handler.removeCallbacks(dimRunnable)
        applyDimmed(false)
    }

    private fun applyDimmed(dim: Boolean) {
        if (dimmed == dim) return
        dimmed = dim
        setWindowBrightness(if (dim) idleBrightness() else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
        handler.removeCallbacks(nightCheckRunnable)
        if (dim) handler.postDelayed(nightCheckRunnable, NIGHT_CHECK_MS)
    }

    private fun setWindowBrightness(brightness: Float) {
        runCatching {
            activity.window.attributes = activity.window.attributes.apply { screenBrightness = brightness }
        }.onFailure { Log.i(TAG, "set brightness ($brightness) failed", it) }
    }

    /** Near-black for the charging screen (as always); the configured level otherwise. */
    private fun idleBrightness(): Float = when (dockConfig.mode) {
        DockConfig.MODE_CHARGING -> DIMMED_BRIGHTNESS
        else -> if (isNight()) dockConfig.nightBrightness else dockConfig.brightness
    }

    private fun defaultIsNight(): Boolean {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        return hour >= NIGHT_START_HOUR || hour < NIGHT_END_HOUR
    }

    private companion object {
        const val TAG = "ChargeDock"
        const val DIMMED_BRIGHTNESS = 0.01f
        const val NIGHT_START_HOUR = 21
        const val NIGHT_END_HOUR = 7
        const val NIGHT_CHECK_MS = 10 * 60_000L
        const val DEFAULT_DOCK_DEBOUNCE_MS = 5_000L

        // Real, sticky-broadcast constants (confirmed present in AOSP source
        // and at runtime) but *not* part of the public UsbManager SDK stub —
        // UsbManager.ACTION_USB_STATE/USB_CONNECTED don't resolve at compile
        // time despite existing on-device, hence the raw string literals:
        // IntentFilter.addAction(String) and Intent.getBooleanExtra(String,
        // Boolean) are both fully public APIs, this is just the value they're
        // given, not a reflection-based reach into a hidden class member.
        const val ACTION_USB_STATE = "android.hardware.usb.action.USB_STATE"
        const val EXTRA_USB_CONNECTED = "connected"
    }
}
