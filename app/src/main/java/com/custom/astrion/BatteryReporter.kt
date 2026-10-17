package com.custom.astrion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.util.Log
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.HaClient
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Pushes this remote's battery to Home Assistant through the push webhook — `{"type": "battery",
 * "level": 57, "charging": false}` (plus the remote's identity, added by [HaClient.pushWebhook]) —
 * so the integration no longer has to poll `/battery` every few seconds (each poll wakes the
 * remote's Wi-Fi). Sent when the level crosses a [STEP_PERCENT] step, when charging starts or
 * stops, and once at start. Nothing is sent without a webhook id configured.
 */
class BatteryReporter(private val context: Context, private val client: () -> HaClient?) {
    private var lastLevel = -1
    private var lastCharging: Boolean? = null

    private val receiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent?.let(::onBattery)
            }
        }

    fun start() {
        // Sticky broadcast: registering also delivers the current state right away.
        runCatching { context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }
            .onFailure { Log.w(TAG, "battery receiver unavailable", it) }
    }

    fun stop() {
        runCatching { context.unregisterReceiver(receiver) }
    }

    /** Send the current state again on the next battery broadcast (e.g. a new webhook id was saved). */
    fun resend() {
        lastLevel = -1
        lastCharging = null
    }

    private fun onBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return
        val percent = level * PERCENT / scale
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        val stepChanged = lastLevel < 0 || percent / STEP_PERCENT != lastLevel / STEP_PERCENT
        if (!stepChanged && charging == lastCharging) return
        val webhookId = RemoteSettings.haWebhookId(context)
        val ha = client()
        if (webhookId.isBlank() || ha == null) return
        lastLevel = percent
        lastCharging = charging
        ha.pushWebhook(
            webhookId,
            buildJsonObject {
                put("type", "battery")
                put("level", percent)
                put("charging", charging)
            }
        )
    }

    private companion object {
        const val TAG = "BatteryReporter"
        const val PERCENT = 100
        const val STEP_PERCENT = 5
    }
}
