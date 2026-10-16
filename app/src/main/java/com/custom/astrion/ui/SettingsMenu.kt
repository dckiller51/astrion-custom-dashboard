package com.custom.astrion.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.BrightnessMedium
import androidx.compose.material.icons.filled.Dock
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.SettingsSuggest
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Vibration
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.AppLanguage
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

/** The groups of the Settings page — each opens as a popup over the list (see [SettingsSectionPopup]). */
enum class SettingsSection { DISPLAY, CONNECTION, WEB_CONFIG, SOUND, LANGUAGE, ABOUT }

/** Result of the update check run when Settings opens — shared by the list and the About popup. */
data class UpdateCheckState(
    val status: Status = Status.CHECKING,
    val info: UpdateChecker.UpdateInfo? = null,
    val isBeta: Boolean = BuildConfig.VERSION_NAME.contains("-beta")
) {
    enum class Status { CHECKING, UP_TO_DATE, AVAILABLE, FAILED }
}

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
fun rememberUpdateCheck(): UpdateCheckState {
    var state by remember { mutableStateOf(UpdateCheckState()) }
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        val isBeta = state.isBeta
        val result =
            withContext(Dispatchers.IO) {
                if (isBeta) UpdateChecker.checkBetaUpdate(UpdateChecker.installedAt(context)) else UpdateChecker.checkForUpdate()
            }
        state =
            when (result) {
                is UpdateChecker.CheckResult.Available -> state.copy(status = UpdateCheckState.Status.AVAILABLE, info = result.info)
                is UpdateChecker.CheckResult.Failed -> {
                    Log.w("SettingsMenu", "Update check failed (beta=$isBeta): ${result.reason}")
                    state.copy(status = UpdateCheckState.Status.FAILED)
                }
                UpdateChecker.CheckResult.UpToDate -> state.copy(status = UpdateCheckState.Status.UP_TO_DATE)
            }
    }
    return state
}

/**
 * The Settings page: a short list of groups (Display, Connections, Web configuration, Sound &
 * touch, About), each with a one-line summary; tapping one opens it as a popup
 * ([SettingsSectionPopup], drawn by Dashboard's SettingsOverlay above the list). An available
 * update and the Android system shortcut stay directly on the list.
 *
 * Lives in `ui/` rather than `cards/impl/` because it is no longer a
 * dashboard.json-driven card: Dashboard renders it directly from its
 * settings overlay, not through CardRegistry/CardConfig like the
 * swipeable-page cards are.
 */
@Composable
fun SettingsMenu(ctx: CardContext, update: UpdateCheckState, onOpen: (SettingsSection) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val theme = LocalTheme.current
    val settings = ctx.deviceSettings
    val haConnection by ctx.client.connection.collectAsState()
    val haConnected = haConnection == ConnectionState.CONNECTED

    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(theme.cardSurface)
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(stringResource(R.string.settings_title), color = theme.primaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)

        update.info?.let { info -> UpdateRow(context, scope, info, update.isBeta) }

        SectionItem(
            icon = Icons.Filled.BrightnessMedium,
            label = stringResource(R.string.settings_display),
            summary =
            stringResource(
                R.string.settings_display_summary,
                screenTimeoutLabel(settings.screenTimeoutSeconds),
                stringResource(dockModeLabel(settings.dockMode))
            )
        ) { onOpen(SettingsSection.DISPLAY) }
        SectionItem(
            icon = Icons.Filled.Wifi,
            label = stringResource(R.string.settings_connections),
            summary = "Home Assistant · " + stringResource(if (haConnected) R.string.connected else haStatusString(haConnection)),
            dot = if (haConnected) theme.success else theme.danger
        ) { onOpen(SettingsSection.CONNECTION) }
        SectionItem(
            icon = Icons.Filled.Language,
            label = stringResource(R.string.settings_web_config),
            summary =
            if (settings.configServerEnabled) {
                localIpAddress()?.let { "http://$it:8080" } ?: stringResource(R.string.state_on)
            } else {
                stringResource(R.string.state_off)
            }
        ) { onOpen(SettingsSection.WEB_CONFIG) }
        SectionItem(
            icon = Icons.Filled.TouchApp,
            label = stringResource(R.string.settings_sound_touch),
            summary = stringResource(R.string.tap_feedback) + " · " + onOff(settings.tapFeedbackEnabled)
        ) { onOpen(SettingsSection.SOUND) }
        SectionItem(
            icon = Icons.Filled.Translate,
            label = stringResource(R.string.settings_language),
            summary = AppLanguage.CHOICES[settings.appLanguage] ?: stringResource(R.string.language_system)
        ) { onOpen(SettingsSection.LANGUAGE) }
        SectionItem(
            icon = Icons.Filled.Info,
            label = stringResource(R.string.settings_about),
            summary = listOf(settings.remoteName, "v" + BuildConfig.VERSION_NAME).filter { it.isNotBlank() }.joinToString(" · ")
        ) { onOpen(SettingsSection.ABOUT) }

        SettingRow(icon = Icons.Filled.SettingsSuggest, label = stringResource(R.string.android_system)) {
            context.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}

/**
 * One group of the Settings page, as a popup card over a dimmed backdrop: tapping the backdrop,
 * "✕" or the system Back button closes it.
 */
@Composable
fun SettingsSectionPopup(ctx: CardContext, section: SettingsSection, update: UpdateCheckState, onDismiss: () -> Unit) {
    val theme = LocalTheme.current
    BackHandler { onDismiss() }
    val maxHeight = (LocalConfiguration.current.screenHeightDp * POPUP_MAX_HEIGHT).dp
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = SCRIM_ALPHA))
                .tapClickable(onClick = onDismiss)
        )
        Column(
            modifier =
            Modifier
                .padding(horizontal = 12.dp)
                .fillMaxWidth()
                .heightIn(max = maxHeight)
                .clip(RoundedCornerShape(18.dp))
                .background(theme.cardSurface)
                .verticalScroll(rememberScrollState())
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(sectionTitle(section)),
                    color = theme.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    "✕",
                    color = theme.mutedText,
                    fontSize = 18.sp,
                    modifier =
                    Modifier
                        .clip(CircleShape)
                        .tapClickable(onClick = onDismiss)
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
            when (section) {
                SettingsSection.DISPLAY -> DisplaySection(ctx)
                SettingsSection.CONNECTION -> ConnectionSection(ctx)
                SettingsSection.WEB_CONFIG -> WebConfigSection(ctx)
                SettingsSection.SOUND -> SoundSection(ctx)
                SettingsSection.LANGUAGE -> LanguageSection(ctx)
                SettingsSection.ABOUT -> AboutSection(ctx, update)
            }
        }
    }
}

private fun sectionTitle(section: SettingsSection): Int = when (section) {
    SettingsSection.DISPLAY -> R.string.settings_display
    SettingsSection.CONNECTION -> R.string.settings_connections
    SettingsSection.WEB_CONFIG -> R.string.settings_web_config
    SettingsSection.SOUND -> R.string.settings_sound_touch
    SettingsSection.LANGUAGE -> R.string.settings_language
    SettingsSection.ABOUT -> R.string.settings_about
}

@Composable
private fun SectionItem(icon: ImageVector, label: String, summary: String, dot: Color? = null, onClick: () -> Unit) {
    val theme = LocalTheme.current
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(theme.controlBackground)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(icon, contentDescription = null, tint = theme.mutedText)
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = theme.primaryText, fontSize = 14.sp)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                dot?.let { Box(Modifier.size(8.dp).clip(CircleShape).background(it)) }
                Text(summary, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = theme.mutedText)
    }
}

@Composable
private fun onOff(on: Boolean): String = stringResource(if (on) R.string.state_on else R.string.state_off)

// ---- Display ----------------------------------------------------------------------------------

@Composable
private fun DisplaySection(ctx: CardContext) {
    BrightnessSlider(LocalContext.current as? Activity)
    ScreenTimeoutChoice(ctx)
    WakeOnMotionRow(ctx)
    DockModeChoice(ctx)
}

private val SCREEN_TIMEOUT_CHOICES = listOf<Int?>(null, 15, 30, 60, 120, 300)

/** "15 s" / "2 min"; very long values (Android's "never") as "∞". */
private fun formatSeconds(seconds: Long): String = when {
    seconds >= NEVER_THRESHOLD_S -> "∞"
    seconds >= SECONDS_PER_MINUTE && seconds % SECONDS_PER_MINUTE == 0L -> "${seconds / SECONDS_PER_MINUTE} min"
    else -> "$seconds s"
}

@Composable
private fun screenTimeoutLabel(seconds: Int?): String {
    if (seconds != null) return formatSeconds(seconds.toLong())
    val context = LocalContext.current
    val systemMs =
        runCatching { Settings.System.getLong(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
    return stringResource(R.string.screen_timeout_system, systemMs?.let { formatSeconds(it / MS_PER_SECOND) } ?: "?")
}

/**
 * "Screen timeout": the system screen timeout this remote holds (dashboard.json
 * `power.screen_timeout_seconds`, applied by StandbyController), or "System" to leave Android's
 * own setting alone. Writing it needs "Modify system settings" — the same permission as the
 * brightness slider; when it's missing, choosing a value opens Android's page for it.
 */
@Composable
private fun ScreenTimeoutChoice(ctx: CardContext) {
    val context = LocalContext.current
    val current = ctx.deviceSettings.screenTimeoutSeconds
    val canWrite = Settings.System.canWrite(context)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingLabel(Icons.Filled.Timer, stringResource(R.string.screen_timeout))
        ChoiceChips(
            options = SCREEN_TIMEOUT_CHOICES.map { it to screenTimeoutLabel(it) },
            selected = current,
            onSelect = { seconds ->
                ctx.deviceSettings.setScreenTimeout(seconds)
                if (seconds != null && !Settings.System.canWrite(context)) openWriteSettingsPermission(context)
            }
        )
        if (current != null && !canWrite) {
            SettingRow(icon = null, label = stringResource(R.string.allow_write_settings)) { openWriteSettingsPermission(context) }
        }
        Hint(stringResource(R.string.screen_timeout_hint))
    }
}

/**
 * "Dock screen": what the remote shows on its charging dock. Written into this remote's
 * dashboard.json (`dock.mode`), the same value the builder's Dock screen section edits, so the
 * two never disagree.
 */
@Composable
private fun DockModeChoice(ctx: CardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SettingLabel(Icons.Filled.Dock, stringResource(R.string.dock_screen))
        ChoiceChips(
            options = DockConfig.MODES.map { it to stringResource(dockModeLabel(it)) },
            selected = ctx.deviceSettings.dockMode,
            onSelect = { ctx.deviceSettings.setDockMode(it) }
        )
        Hint(stringResource(R.string.dock_screen_hint))
    }
}

private fun dockModeLabel(mode: String): Int = when (mode) {
    DockConfig.MODE_CLOCK -> R.string.dock_mode_clock
    DockConfig.MODE_DASHBOARD -> R.string.dock_mode_dashboard
    else -> R.string.dock_mode_charging
}

/** Opens Android's "Modify system settings" page for this app (falls back to the generic list). */
private fun openWriteSettingsPermission(context: Context) {
    val forApp = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:${context.packageName}"))
    runCatching { context.startActivity(forApp.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .recoverCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        .onFailure { Log.w("SettingsMenu", "No 'Modify system settings' page on this device", it) }
}

/** A selectable value per chip, three to a row. */
@Composable
private fun <T> ChoiceChips(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    val theme = LocalTheme.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        options.chunked(CHIPS_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { (value, label) ->
                    val on = value == selected
                    Box(
                        modifier =
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (on) theme.accent else theme.controlBackground)
                            .tapClickable { onSelect(value) }
                            .padding(horizontal = 6.dp, vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (on) theme.background else theme.primaryText,
                            fontSize = 13.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                repeat(CHIPS_PER_ROW - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingLabel(icon: ImageVector, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Icon(icon, contentDescription = null, tint = LocalTheme.current.mutedText)
        Text(label, color = LocalTheme.current.primaryText, fontSize = 14.sp)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, color = LocalTheme.current.mutedText, fontSize = 11.sp)
}

// ---- Connections ------------------------------------------------------------------------------

@Composable
private fun ConnectionSection(ctx: CardContext) {
    val context = LocalContext.current
    val haConnection by ctx.client.connection.collectAsState()
    ConnectionStatusSection(ctx)
    val busy = haConnection in setOf(ConnectionState.CONNECTED, ConnectionState.CONNECTING, ConnectionState.AUTHENTICATING)
    if (!busy) {
        SettingRow(icon = Icons.Filled.Refresh, label = stringResource(R.string.reconnect_now)) { ctx.client.reconnectNow() }
    }
    SettingRow(icon = Icons.Filled.Wifi, label = stringResource(R.string.wifi_network)) {
        context.startActivity(Intent(Settings.ACTION_WIFI_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    InfoRow(stringResource(R.string.ip_address), localIpAddress() ?: "—")
    WifiKeepAwakeRow(ctx)
}

// ---- Web configuration ------------------------------------------------------------------------

@Composable
private fun WebConfigSection(ctx: CardContext) {
    ConfigServerRow(ctx)
    localIpAddress()?.let { ip ->
        Text(
            if (ctx.deviceSettings.configServerEnabled) {
                stringResource(R.string.settings_local_config, "http://$ip:8080")
            } else {
                stringResource(R.string.config_server_off_hint)
            },
            color = if (ctx.deviceSettings.configServerEnabled) LocalTheme.current.accent else LocalTheme.current.mutedText,
            fontSize = 13.sp
        )
    }
}

// ---- Sound & touch, Language --------------------------------------------------------------------

@Composable
private fun SoundSection(ctx: CardContext) {
    TapFeedbackRow(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SettingRow(icon = Icons.Filled.NotificationsActive, label = stringResource(R.string.test_ring)) { ctx.deviceSettings.testRing() }
        Hint(stringResource(R.string.test_ring_hint))
    }
}

@Composable
private fun LanguageSection(ctx: CardContext) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ChoiceChips(
            options = listOf("" to stringResource(R.string.language_system)) + AppLanguage.CHOICES.toList(),
            selected = ctx.deviceSettings.appLanguage,
            onSelect = { ctx.deviceSettings.setAppLanguage(it) }
        )
        Hint(stringResource(R.string.language_hint))
    }
}

// ---- About ------------------------------------------------------------------------------------

@Composable
private fun AboutSection(ctx: CardContext, update: UpdateCheckState) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val battery = remember<Int?>(context) { batteryPercent(context) }
    RemoteNameField(ctx)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        InfoRow(stringResource(R.string.about_remote_id), ctx.deviceSettings.remoteId)
        InfoRow(stringResource(R.string.about_version), "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
        val channel = if (update.isBeta) R.string.channel_beta else R.string.channel_stable
        InfoRow(stringResource(R.string.about_channel), stringResource(channel))
        InfoRow(
            stringResource(R.string.about_update),
            stringResource(
                when (update.status) {
                    UpdateCheckState.Status.CHECKING -> R.string.update_checking
                    UpdateCheckState.Status.UP_TO_DATE -> R.string.update_up_to_date
                    UpdateCheckState.Status.FAILED -> R.string.update_check_failed
                    UpdateCheckState.Status.AVAILABLE -> R.string.update_available_short
                }
            )
        )
        update.info?.let { info -> UpdateRow(context, scope, info, update.isBeta) }
        InfoRow(stringResource(R.string.about_device), "${Build.MANUFACTURER} ${Build.MODEL}")
        InfoRow("Android", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        battery?.let { InfoRow(stringResource(R.string.about_battery), "$it %") }
        InfoRow(stringResource(R.string.ip_address), localIpAddress() ?: "—")
        InfoRow(stringResource(R.string.about_package), context.packageName)
        InfoRow(stringResource(R.string.about_project), "github.com/$PROJECT_REPO")
        val ip = localIpAddress()
        InfoRow(
            stringResource(R.string.about_logs),
            if (ctx.deviceSettings.configServerEnabled && ip != null) "http://$ip:8080/logs" else stringResource(R.string.logs_server_off)
        )
    }
    SettingRow(icon = Icons.Filled.Refresh, label = stringResource(R.string.reload_dashboard)) { ctx.deviceSettings.reloadDashboard() }
    SettingRow(icon = Icons.Filled.RestartAlt, label = stringResource(R.string.restart_app)) { ctx.deviceSettings.restartApp() }
}

/**
 * "Name of this remote" — shown in Home Assistant and sent with every webhook push and event, so a
 * home with several remotes can tell them apart. Saved with the keyboard's Done key or "OK".
 */
@Composable
private fun RemoteNameField(ctx: CardContext) {
    val theme = LocalTheme.current
    val saved = ctx.deviceSettings.remoteName
    var text by remember(saved) { mutableStateOf(saved) }
    val focus = LocalFocusManager.current
    val save = {
        ctx.deviceSettings.setRemoteName(text)
        focus.clearFocus()
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.remote_name), color = theme.mutedText, fontSize = 13.sp)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.take(REMOTE_NAME_MAX) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                colors =
                OutlinedTextFieldDefaults.colors(
                    focusedTextColor = theme.primaryText,
                    unfocusedTextColor = theme.primaryText,
                    focusedBorderColor = theme.accent,
                    unfocusedBorderColor = theme.mutedText,
                    cursorColor = theme.accent
                )
            )
            if (text.trim() != saved) {
                Text(
                    stringResource(R.string.save_short),
                    color = theme.background,
                    fontSize = 14.sp,
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .background(theme.accent)
                        .tapClickable { save() }
                        .padding(horizontal = 14.dp, vertical = 10.dp)
                )
            }
        }
        Hint(stringResource(R.string.remote_name_hint))
    }
}

/** This remote's battery level in %, or null when Android doesn't report it. */
private fun batteryPercent(context: Context): Int? = (context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)
    ?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
    ?.takeIf { it >= 0 }

@Composable
private fun InfoRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, color = LocalTheme.current.mutedText, fontSize = 13.sp)
        Spacer(Modifier.weight(1f))
        Text(value, color = LocalTheme.current.primaryText, fontSize = 13.sp, textAlign = TextAlign.End)
    }
}

private const val POPUP_MAX_HEIGHT = 0.88f
private const val SCRIM_ALPHA = 0.6f
private const val CHIPS_PER_ROW = 3
private const val SECONDS_PER_MINUTE = 60L
private const val MS_PER_SECOND = 1000L
private const val NEVER_THRESHOLD_S = 24 * 60 * 60L
private const val REMOTE_NAME_MAX = 40
private const val PROJECT_REPO = "dckiller51/astrion-custom-dashboard"

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
    // Read on every recomposition (not remembered): the user grants it in Android's own page and
    // comes back, and the dashboard reload on resume recomposes this.
    val canWrite = Settings.System.canWrite(context)
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
            SettingRow(icon = null, label = stringResource(R.string.allow_brightness_write)) { openWriteSettingsPermission(context) }
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

internal fun localIpAddress(): String? = runCatching {
    NetworkInterface
        .getNetworkInterfaces()
        .asSequence()
        .flatMap { it.inetAddresses.asSequence() }
        .firstOrNull { !it.isLoopbackAddress && it is Inet4Address }
        ?.hostAddress
}.getOrNull()
