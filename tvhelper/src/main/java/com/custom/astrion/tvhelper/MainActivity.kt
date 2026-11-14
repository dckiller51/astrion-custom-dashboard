package com.custom.astrion.tvhelper

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * The helper's only screen, opened from the TV's app list: the code to type on the remote's web
 * page, whether the two permissions are granted, and the ADB commands that grant them (Android
 * TV hides the settings screens for both on most devices).
 */
class MainActivity : Activity() {
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var commands: TextView
    private lateinit var keepAwake: Button

    private val refresher =
        object : Runnable {
            override fun run() {
                refresh()
                handler.postDelayed(this, REFRESH_MS)
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pad = dp(PADDING_DP)
        val column =
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, pad, pad, pad)
                setBackgroundColor(BACKGROUND)
            }
        column.addView(text(getString(R.string.app_name), TITLE_SP, Color.WHITE, bold = true))
        column.addView(text(getString(R.string.code_label), BODY_SP, MUTED))
        column.addView(text(HelperCode.get(this), CODE_SP, ACCENT, bold = true, mono = true))
        column.addView(text(getString(R.string.code_hint), BODY_SP, MUTED))
        status = text("", BODY_SP, Color.WHITE)
        column.addView(status)
        commands = text("", SMALL_SP, MUTED, mono = true)
        column.addView(commands)
        column.addView(
            Button(this).apply {
                text = getString(R.string.open_settings)
                setOnClickListener { openNotificationAccess() }
            }
        )
        // 1.1.0: for boxes the remote can't wake from their deep sleep (Xiaomi Mi Box S…).
        keepAwake =
            Button(this).apply {
                setOnClickListener {
                    KeepAwake.set(this@MainActivity, !KeepAwake.enabled(this@MainActivity))
                    refresh()
                }
            }
        column.addView(keepAwake)
        column.addView(text(getString(R.string.keep_awake_hint), SMALL_SP, MUTED))
        setContentView(ScrollView(this).apply { addView(column) })
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val media = MediaListener.connected
        val apps = Settings.canDrawOverlays(this)
        val lines = mutableListOf<String>()
        lines += getString(if (media) R.string.media_ok else R.string.media_missing)
        lines += getString(if (apps) R.string.apps_ok else R.string.apps_missing)
        lines += getString(R.string.address, addresses().joinToString(", ").ifEmpty { "?" }, HelperRoutes.PORT)
        val awake = KeepAwake.enabled(this)
        val doze = awake && !KeepAwake.unrestricted(this)
        lines += getString(if (awake) R.string.keep_awake_on else R.string.keep_awake_off)
        status.text = lines.joinToString("\n\n")
        keepAwake.text = getString(if (awake) R.string.keep_awake_disable else R.string.keep_awake_enable)
        commands.text =
            if (media && apps && !doze) {
                ""
            } else {
                getString(R.string.adb_title) + "\n" + listOfNotNull(
                    "adb shell cmd notification allow_listener $packageName/${MediaListener::class.java.name}".takeIf { !media },
                    "adb shell appops set $packageName SYSTEM_ALERT_WINDOW allow".takeIf { !apps },
                    // Battery savings (Doze) would cut the Wi-Fi despite the helper's locks.
                    "adb shell dumpsys deviceidle whitelist +$packageName".takeIf { doze }
                ).joinToString("\n")
            }
    }

    private fun openNotificationAccess() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, getString(R.string.settings_unavailable) + " (${e.message})", Toast.LENGTH_LONG).show()
        }
    }

    private fun addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { it.inetAddresses.toList() }
            .filterIsInstance<Inet4Address>()
            .map { it.hostAddress.orEmpty() }
    }.getOrDefault(emptyList())

    private fun text(value: String, sp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        setTextColor(color)
        gravity = Gravity.START
        typeface = Typeface.create(if (mono) Typeface.MONOSPACE else Typeface.DEFAULT, if (bold) Typeface.BOLD else Typeface.NORMAL)
        setPadding(0, dp(GAP_DP), 0, 0)
        setTextIsSelectable(mono)
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private companion object {
        const val REFRESH_MS = 2_000L
        const val PADDING_DP = 32
        const val GAP_DP = 8
        const val TITLE_SP = 28f
        const val CODE_SP = 56f
        const val BODY_SP = 18f
        const val SMALL_SP = 14f
        val BACKGROUND = Color.rgb(14, 18, 23)
        val ACCENT = Color.rgb(0, 229, 255)
        val MUTED = Color.rgb(154, 168, 173)
    }
}
