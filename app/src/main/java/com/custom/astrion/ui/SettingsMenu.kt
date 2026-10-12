package com.custom.astrion.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Dock
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.BuildConfig
import com.custom.astrion.R
import com.custom.astrion.cards.CardContext
import com.custom.astrion.config.DockConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.update.UpdateChecker
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Settings panel in the style of HaRemote (their SettingActivity /
 * SettingDisplayActivity, decompiled to understand the layout): live
 * brightness, Wi-Fi and Android system shortcuts, wake-on-motion, and
 * HA/Harmony connection status — without duplicating their whole menu
 * (account, language, lock screen, etc. not covered here, addable if
 * needed).
 *
 * Lives in `ui/` rather than `cards/impl/` because it is no longer a
 * dashboard.json-driven card: Dashboard renders it directly from its
 * swipe-down-from-top overlay, not through CardRegistry/CardConfig like
 * the swipeable-page cards are.
 */
/**
 * Checks for an app update once each time Settings is opened (not on a
 * background timer, to avoid polling GitHub while the app just sits on the
 * dashboard). A beta/debug build (versionNameSuffix = "-beta", see
 * build.gradle.kts) checks the rolling dev-latest pre-release instead of
 * /releases/latest — otherwise this row would never fire on a beta install,
 * since dev-latest is never "the newer official release". A Failed result
 * (network error, GitHub rate limit, misconfigured REPO constant...) is
 * logged rather than silently dropped, so a report of "the update
 * notification doesn't work" is diagnosable from logcat instead of
 * indistinguishable from "genuinely up to date".
 */
@Composable
private fun rememberUpdateCheck(): Pair<UpdateChecker.UpdateInfo?, Boolean> {
    var updateInfo by remember { mutableStateOf<UpdateChecker.UpdateInfo?>(null) }
    var updateIsBeta by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val isBeta = BuildConfig.VERSION_NAME.contains("-beta")
        val result =
            withContext(Dispatchers.IO) {
                if (isBeta) UpdateChecker.checkBetaUpdate() else UpdateChecker.checkForUpdate()
            }
        when (result) {
            is UpdateChecker.CheckResult.Available -> {
                updateInfo = result.info
                updateIsBeta = isBeta
            }
            is UpdateChecker.CheckResult.Failed ->
                Log.w("SettingsMenu", "Update check failed (beta=$isBeta): ${result.reason}")
            UpdateChecker.CheckResult.UpToDate -> Unit
        }
    }

    return updateInfo to updateIsBeta
}

@Composable
fun SettingsMenu(ctx: CardContext) {
    val context = LocalContext.current
    val activity = context as? Activity
    val scope = rememberCoroutineScope()
    val (updateInfo, updateIsBeta) = rememberUpdateCheck()

    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(LocalTheme.current.cardSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        Text(
            stringResource(R.string.settings_title),
            color = LocalTheme.current.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold
        )

        updateInfo?.let { info ->
            UpdateRow(context, scope, info, updateIsBeta)
        }

        localIpAddress()?.let { ip ->
            Text(
                if (ctx.deviceSettings.configServerEnabled) {
                    stringResource(R.string.settings_local_config, "http://$ip:8080")
                } else {
                    stringResource(R.string.config_server_off_hint)
                },
                color = if (ctx.deviceSettings.configServerEnabled) LocalTheme.current.accent else LocalTheme.current.mutedText,
                fontSize = 12.sp
            )
        }

        ConnectionStatusSection(ctx)

        BrightnessSlider(activity)

        SettingRow(icon = Icons.Filled.Wifi, label = stringResource(R.string.wifi_network)) {
            context.startActivity(
                Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        SettingRow(icon = Icons.Filled.SettingsSuggest, label = stringResource(R.string.android_system)) {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }

        WakeOnMotionRow(ctx)
        WifiKeepAwakeRow(ctx)
        ConfigServerRow(ctx)
        TapFeedbackRow(ctx)
        DockModeRow(ctx)
    }
}

/**
 * "Dock screen": what the remote shows on its charging dock — tapping cycles through following
 * dashboard.json, the charging screen, the clock and the plain dashboard. Stored on the remote,
 * so it overrides dashboard.json's `dock.mode` for this remote only.
 */
@Composable
private fun DockModeRow(ctx: CardContext) {
    val options =
        listOf(
            "" to R.string.dock_mode_default,
            DockConfig.MODE_CHARGING to R.string.dock_mode_charging,
            DockConfig.MODE_CLOCK to R.string.dock_mode_clock,
            DockConfig.MODE_DASHBOARD to R.string.dock_mode_dashboard
        )
    val current = options.indexOfFirst { it.first == ctx.deviceSettings.dockMode }.coerceAtLeast(0)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(LocalTheme.current.controlBackground)
                .tapClickable { ctx.deviceSettings.setDockMode(options[(current + 1) % options.size].first) }
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.Dock, contentDescription = null, tint = LocalTheme.current.mutedText)
            Text(stringResource(R.string.dock_screen), color = LocalTheme.current.primaryText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text(stringResource(options[current].second), color = LocalTheme.current.accent, fontSize = 14.sp)
        }
        Text(stringResource(R.string.dock_screen_hint), color = LocalTheme.current.mutedText, fontSize = 11.sp)
    }
}

@Composable
private fun UpdateRow(context: Context, scope: CoroutineScope, info: UpdateChecker.UpdateInfo, isBeta: Boolean) {
    val label =
        if (isBeta) {
            stringResource(R.string.settings_beta_update_available, info.version)
        } else {
            stringResource(R.string.settings_update_available, info.version)
        }
    SettingRow(icon = Icons.Filled.SystemUpdate, label = label) {
        scope.launch(Dispatchers.IO) {
            val file = UpdateChecker.download(context, info.apkUrl)
            if (file != null) {
                withContext(Dispatchers.Main) {
                    UpdateChecker.promptInstall(context, file)
                }
            }
        }
    }
}

@Composable
private fun ConnectionStatusSection(ctx: CardContext) {
    val haConnection by ctx.client.connection.collectAsState()
    val haConnected = haConnection == ConnectionState.CONNECTED

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ConnectionStatusRow(
            label = "Home Assistant",
            connected = haConnected,
            detail =
            if (haConnected) {
                stringResource(R.string.connected)
            } else {
                stringResource(haStatusString(haConnection))
            }
        )
        ConnectionStatusRow(
            label = "Harmony Hub",
            connected = ctx.harmonyConnected,
            detail = stringResource(if (ctx.harmonyConnected) R.string.connected else R.string.disconnected)
        )
    }
}

private fun haStatusString(state: ConnectionState): Int = when (state) {
    ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING -> R.string.connection_connecting
    ConnectionState.AUTH_FAILED -> R.string.connection_auth_failed
    ConnectionState.ERROR -> R.string.connection_error_retrying
    else -> R.string.disconnected
}

@Composable
private fun ConnectionStatusRow(label: String, connected: Boolean, detail: String) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalTheme.current.controlBackground)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(
            modifier =
            Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(if (connected) LocalTheme.current.success else LocalTheme.current.danger)
        )
        Text(label, color = LocalTheme.current.primaryText, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Text(detail, color = LocalTheme.current.mutedText, fontSize = 13.sp)
    }
}

@Composable
private fun BrightnessSlider(activity: Activity?) {
    val context = LocalContext.current
    var canWrite by remember { mutableStateOf(Settings.System.canWrite(context)) }
    var brightness by remember {
        mutableIntStateOf(
            runCatching {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS)
            }.getOrDefault(128)
        )
    }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.BrightnessMedium, contentDescription = null, tint = LocalTheme.current.mutedText)
            Text(stringResource(R.string.brightness), color = LocalTheme.current.primaryText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Text("${(brightness / 255f * 100).toInt()}%", color = LocalTheme.current.mutedText, fontSize = 13.sp)
        }

        if (!canWrite) {
            SettingRow(icon = null, label = stringResource(R.string.allow_brightness_write)) {
                context.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                canWrite = Settings.System.canWrite(context)
            }
        } else {
            Slider(
                value = brightness.toFloat(),
                valueRange = 10f..255f,
                onValueChange = { v ->
                    brightness = v.toInt()
                    activity?.let { act ->
                        val attrs = act.window.attributes
                        attrs.screenBrightness = brightness / 255f
                        act.window.attributes = attrs
                    }
                    runCatching {
                        Settings.System.putInt(
                            context.contentResolver,
                            Settings.System.SCREEN_BRIGHTNESS,
                            brightness
                        )
                    }
                },
                colors =
                SliderDefaults.colors(
                    thumbColor = LocalTheme.current.accent,
                    activeTrackColor = LocalTheme.current.accent,
                    inactiveTrackColor = LocalTheme.current.controlBackground
                )
            )
        }
    }
}

@Composable
private fun WakeOnMotionRow(ctx: CardContext) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(Icons.Filled.Vibration, contentDescription = null, tint = LocalTheme.current.mutedText)
        Text(stringResource(R.string.wake_on_motion), color = LocalTheme.current.primaryText, fontSize = 14.sp)
        Spacer(Modifier.weight(1f))
        Switch(
            checked = ctx.deviceSettings.wakeOnMotionEnabled,
            onCheckedChange = { ctx.deviceSettings.setWakeOnMotionEnabled(it) },
            colors = SwitchDefaults.colors(checkedTrackColor = LocalTheme.current.accent)
        )
    }
}

/**
 * "Keep Wi-Fi awake" switch — off by default. See [CardContext.wifiKeepAwakeEnabled]'s
 * doc for the battery-vs-reachability trade-off; this is purely about Home
 * Assistant reaching this device (services, push-webhook) while the screen's
 * off, not about the device's own connectivity for anything it initiates itself.
 */
@Composable
private fun WifiKeepAwakeRow(ctx: CardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = LocalTheme.current.mutedText)
            Text(stringResource(R.string.wifi_keep_awake), color = LocalTheme.current.primaryText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = ctx.deviceSettings.wifiKeepAwakeEnabled,
                onCheckedChange = { ctx.deviceSettings.setWifiKeepAwakeEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = LocalTheme.current.accent)
            )
        }
        Text(
            stringResource(R.string.wifi_keep_awake_hint),
            color = LocalTheme.current.mutedText,
            fontSize = 11.sp
        )
    }
}

/**
 * "Local config server" switch — the :8080 admin surface (connection
 * settings, dashboard.json, icon uploads, the on-device builder) has no
 * auth, so once a device is fully set up there's no reason to leave it
 * reachable on the LAN. Off by default has no upside (a fresh install
 * couldn't be configured at all), so this defaults to on and is meant to be
 * switched off here, on the device itself, once setup is done — turning it
 * back on later only requires this same switch, not the server itself.
 */
@Composable
private fun ConfigServerRow(ctx: CardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.Wifi, contentDescription = null, tint = LocalTheme.current.mutedText)
            Text(stringResource(R.string.config_server_toggle), color = LocalTheme.current.primaryText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = ctx.deviceSettings.configServerEnabled,
                onCheckedChange = { ctx.deviceSettings.setConfigServerEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = LocalTheme.current.accent)
            )
        }
        Text(
            stringResource(R.string.config_server_toggle_hint),
            color = LocalTheme.current.mutedText,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun TapFeedbackRow(ctx: CardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Icon(Icons.Filled.TouchApp, contentDescription = null, tint = LocalTheme.current.mutedText)
            Text(stringResource(R.string.tap_feedback), color = LocalTheme.current.primaryText, fontSize = 14.sp)
            Spacer(Modifier.weight(1f))
            Switch(
                checked = ctx.deviceSettings.tapFeedbackEnabled,
                onCheckedChange = { ctx.deviceSettings.setTapFeedbackEnabled(it) },
                colors = SwitchDefaults.colors(checkedTrackColor = LocalTheme.current.accent)
            )
        }
        Text(
            stringResource(R.string.tap_feedback_hint),
            color = LocalTheme.current.mutedText,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun SettingRow(icon: ImageVector?, label: String, onClick: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LocalTheme.current.controlBackground)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        icon?.let { Icon(it, contentDescription = null, tint = LocalTheme.current.mutedText) }
        Text(label, color = LocalTheme.current.primaryText, fontSize = 14.sp)
    }
}

private fun localIpAddress(): String? = runCatching {
    NetworkInterface
        .getNetworkInterfaces()
        .asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress
}.getOrNull()
