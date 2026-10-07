package com.custom.astrion.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.time.Duration.Companion.seconds

/**
 * Persistent status bar shown on every page — mirrors HaRemote's IndexTopView
 * (Wi-Fi left, time centered, battery + charging indicator right), decompiled
 * to understand the layout. Doubles as the swipe-down trigger for the
 * "Active Activities" overlay ("activité en cours") — same natural,
 * discoverable spot as pulling down a phone's real status bar. Settings
 * moved off this gesture onto POWER (short press) — see MainActivity's
 * bindDefaultPowerKey().
 */
@Composable
fun TopStatusBar(onSwipeDown: () -> Unit) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val triggerPx = with(density) { 40.dp.toPx() }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }

    val wifiConnected = rememberWifiConnected(context)
    val (batteryPct, charging) = rememberBatteryState(context)
    val time = rememberTickingTime(context)

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(30.dp)
            .padding(horizontal = 14.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulated = 0f },
                    onDragEnd = { dragAccumulated = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulated += dragAmount
                        if (dragAccumulated > triggerPx) {
                            onSwipeDown()
                            dragAccumulated = 0f
                        }
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Box overlay: clock centered to the full bar width, Wi-Fi and
        // battery absolutely positioned to the edges so their widths don't
        // shift the clock off true center.
        Box(
            modifier = Modifier.fillMaxWidth(),
            contentAlignment = Alignment.Center
        ) {
            Text(time, color = LocalTheme.current.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)

            Icon(
                imageVector = if (wifiConnected) Icons.Filled.Wifi else Icons.Filled.WifiOff,
                contentDescription = null,
                tint = LocalTheme.current.mutedText,
                modifier = Modifier.size(16.dp).align(Alignment.CenterStart)
            )

            Row(
                modifier = Modifier.align(Alignment.CenterEnd),
                verticalAlignment = Alignment.CenterVertically
            ) {
                batteryGlyph(percent = batteryPct, charging = charging)
                Spacer(Modifier.padding(end = 2.dp))
                Text("$batteryPct%", color = LocalTheme.current.mutedText, fontSize = 12.sp)
            }
        }
    }
}

/** Simple hand-drawn battery glyph (outline + fill + nub) — avoids depending
 * on the extended Material icon pack, which may not be on the classpath.
 * Also used, at a much larger size, by [ChargingScreen]. */
@Composable
fun batteryGlyph(percent: Int, charging: Boolean, modifier: Modifier = Modifier.size(width = 20.dp, height = 11.dp)) {
    val theme = LocalTheme.current
    val fillColor =
        when {
            charging -> theme.success
            percent <= 15 -> theme.danger
            else -> theme.mutedText
        }
    Canvas(modifier = modifier) {
        val bodyWidth = size.width * 0.85f
        val nubWidth = size.width - bodyWidth
        val strokeWidth = 1.2.dp.toPx()

        drawRoundRect(
            color = theme.mutedText,
            topLeft = Offset(0f, 0f),
            size = Size(bodyWidth, size.height),
            style = Stroke(width = strokeWidth)
        )
        drawRoundRect(
            color = theme.mutedText,
            topLeft = Offset(bodyWidth + 1.dp.toPx(), size.height * 0.25f),
            size = Size(nubWidth - 1.dp.toPx(), size.height * 0.5f)
        )
        val inset = strokeWidth + 1.dp.toPx()
        val fillWidth = ((bodyWidth - inset * 2) * (percent / 100f)).coerceAtLeast(0f)
        drawRoundRect(
            color = fillColor,
            topLeft = Offset(inset, inset),
            size = Size(fillWidth, size.height - inset * 2)
        )
    }
}

@Composable
private fun rememberTickingTime(context: Context): String {
    var time by remember {
        mutableStateOf(formatNow(context))
    }
    LaunchedEffect(Unit) {
        while (true) {
            time = formatNow(context)
            kotlinx.coroutines.delay(15.seconds)
        }
    }
    return time
}

private fun formatNow(context: Context): String {
    val is24 =
        android.text.format.DateFormat
            .is24HourFormat(context)
    val pattern = if (is24) "HH:mm" else "h:mm a"
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date())
}

/** Uses ConnectivityManager.NetworkCallback (not the deprecated
 * CONNECTIVITY_ACTION broadcast) to track Wi-Fi connectivity live. */
@Composable
private fun rememberWifiConnected(context: Context): Boolean {
    var connected by remember { mutableStateOf(true) }
    DisposableEffect(Unit) {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        fun hasWifi(network: Network?): Boolean {
            val caps = cm?.getNetworkCapabilities(network ?: cm.activeNetwork)
            return caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        }

        connected = hasWifi(null)

        val callback =
            object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    connected = hasWifi(network)
                }

                override fun onLost(network: Network) {
                    connected = hasWifi(null)
                }

                override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                    connected = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                }
            }
        val request =
            NetworkRequest
                .Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
        cm?.registerNetworkCallback(request, callback)
        onDispose { cm?.unregisterNetworkCallback(callback) }
    }
    return connected
}

/** Live (percent, isCharging) — shared by [TopStatusBar] and [ChargingScreen]. */
@Composable
fun rememberBatteryState(context: Context): Pair<Int, Boolean> {
    var percent by remember { mutableIntStateOf(100) }
    var charging by remember { mutableStateOf(false) }
    DisposableEffect(Unit) {
        val receiver =
            object : BroadcastReceiver() {
                override fun onReceive(ctx: Context?, intent: Intent?) {
                    if (intent == null) return
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    if (level >= 0 && scale > 0) percent = (level * 100 / scale)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                        status == BatteryManager.BATTERY_STATUS_FULL
                }
            }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { context.unregisterReceiver(receiver) }
    }
    return percent to charging
}
