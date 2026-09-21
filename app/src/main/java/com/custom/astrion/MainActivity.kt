package com.custom.astrion

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.ConsumerIrManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.net.wifi.WifiManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.lifecycleScope
import com.custom.astrion.cards.DeviceSettingsState
import com.custom.astrion.config.ActivityRuntime
import com.custom.astrion.config.DashboardConfig
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.IrDatabaseRuntime
import com.custom.astrion.config.IrStepConfig
import com.custom.astrion.config.IrTarget
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.extender.ExtenderRegistry
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.harmony.HarmonyHubRegistry
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.ui.ChargingScreen
import com.custom.astrion.ui.Dashboard
import com.custom.astrion.ui.DashboardActivityCallbacks
import com.custom.astrion.ui.DashboardConnection
import com.custom.astrion.ui.DashboardNavigation
import com.custom.astrion.ui.DashboardRegistries
import com.custom.astrion.ui.DashboardUiState
import com.custom.astrion.ui.ProvideTheme
import com.custom.astrion.ui.VolumeHotkeyTrigger
import com.custom.astrion.ui.toColors
import com.custom.astrion.web.ConfigServer
import kotlin.math.acos
import kotlin.math.sqrt
import kotlinx.coroutines.launch

/**
 * Single-activity host. Owns the HA client, the direct Harmony hub client,
 * wires physical buttons to the config's hotkeys, and renders the swipeable
 * dashboard.
 *
 * IMPORTANT — configure your connection in `secrets.properties` (see
 * secrets.properties.example) before building — HA_URL / HA_TOKEN /
 * HARMONY_HUB_IP / HARMONY_HUB_ID are injected as BuildConfig fields so
 * nothing sensitive lands in source.
 */
@Suppress("SpellCheckingInspection")
class MainActivity : ComponentActivity() {
    private companion object {
        const val DEBUG_KEYS = false
        const val KEY_TAG = "AstrionKeys"
        const val MOTION_TAG = "MotionWake"
        const val SCREEN_TAG = "ScreenTimeout"
        const val LONG_PRESS_MS = 1500L
        const val TILT_WAKE_DEG = 30f
        const val LIN_ACC_WAKE = 2.5f
        const val MOTION_CONSECUTIVE_N = 3
        const val SCREEN_OFF_WARMUP_MS = 1500L
        const val MAGNITUDE_SANITY_FLOOR = 5f
        const val HOLD_SCREEN_INTERVAL_MS = 1000L
        const val KEEP_SCREEN_HOLD_MS = 10000L
        const val AMBIENT_WINDOW_MS = 5000L
        const val WAKE_COOLDOWN_MS = 2000L

        // Keys that pop up a temporary volume readout when their hotkey
        // fires an HA service call — see runHotkey()'s bottom branch.
        val VOLUME_POPUP_KEYS = setOf("VOLUME_UP", "VOLUME_DOWN", "MUTE")
    }

    private val keyHandler = Handler(Looper.getMainLooper())
    private var pendingLong: Runnable? = null
    private var activeLongKey = -1
    private var longFired = false

    private var sensorManager: SensorManager? = null
    private var motionSensor: Sensor? = null
    private var lastWakeMs = 0L
    private var ambientSamples = 0
    private var ambientMin = Float.MAX_VALUE
    private var ambientMax = 0f
    private var ambientSum = 0f
    private var ambientMaxLin = 0f
    private var ambientOverThreshold = 0
    private var ambientWindowStart = 0L
    private var lastScreenOffMs = 0L
    private var warmupDrops = 0
    private var subfloorDrops = 0
    private var lastHoldScreenMs = 0L
    private var gravX = 0f
    private var gravY = 0f
    private var gravZ = 0f
    private var lastSampleMs = 0L
    private var overCount = 0
    private val motionListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                val (x, y, z) = event.values
                val mag = sqrt(x * x + y * y + z * z)
                val now = System.currentTimeMillis()
                if (lastScreenOffMs != 0L && now - lastScreenOffMs < SCREEN_OFF_WARMUP_MS) {
                    warmupDrops++
                    return
                }
                if (warmupDrops > 0) {
                    Log.i(MOTION_TAG, "warmup: dropped $warmupDrops samples in ${SCREEN_OFF_WARMUP_MS}ms after screen-off")
                    warmupDrops = 0
                }
                if (mag < MAGNITUDE_SANITY_FLOOR) {
                    subfloorDrops++
                    Log.i(
                        MOTION_TAG,
                        "subfloor drop: x=${f2(x)} y=${f2(y)} z=${f2(z)} mag=${f2(mag)} " +
                            "(glitch sample, |a|<${MAGNITUDE_SANITY_FLOOR}, total=$subfloorDrops)"
                    )
                    return
                }
                trackAmbientWindow(mag, now)
                if (lastSampleMs == 0L || now - lastSampleMs > 1000) {
                    gravX = x
                    gravY = y
                    gravZ = z
                    overCount = 0
                }
                lastSampleMs = now
                val gmag = sqrt(gravX * gravX + gravY * gravY + gravZ * gravZ)
                val ax = x - gravX
                val ay = y - gravY
                val az = z - gravZ
                val lin = sqrt(ax * ax + ay * ay + az * az)
                if (lin > ambientMaxLin) ambientMaxLin = lin
                val cosT = (x * gravX + y * gravY + z * gravZ) / (mag * gmag)
                val tilt = Math.toDegrees(acos(cosT.coerceIn(-1f, 1f)).toDouble()).toFloat()
                val moving = tilt > TILT_WAKE_DEG || lin > LIN_ACC_WAKE
                if (moving) {
                    ambientOverThreshold++
                    overCount++
                    if (overCount >= MOTION_CONSECUTIVE_N) {
                        overCount = 0
                        gravX = x
                        gravY = y
                        gravZ = z
                        Log.i(
                            MOTION_TAG,
                            "MOTION x=${f2(x)} y=${f2(y)} z=${f2(z)} tilt=${f1(tilt)} lin=${f2(lin)} " +
                                "mag=${f2(mag)} -> wakeScreen()"
                        )
                        wakeScreen(tilt, lin, mag)
                    } else {
                        Log.i(
                            MOTION_TAG,
                            "move $overCount/$MOTION_CONSECUTIVE_N x=${f2(x)} y=${f2(y)} z=${f2(z)} " +
                                "tilt=${f1(tilt)} lin=${f2(lin)}"
                        )
                    }
                } else {
                    overCount = 0
                    gravX = 0.9f * gravX + 0.1f * x
                    gravY = 0.9f * gravY + 0.1f * y
                    gravZ = 0.9f * gravZ + 0.1f * z
                    if (tilt > TILT_WAKE_DEG / 2f || lin > LIN_ACC_WAKE / 2f) holdScreenWhileInHand()
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }

    private fun f2(v: Float): String = "%.2f".format(v)
    private fun f1(v: Float): String = "%.1f".format(v)

    private val keepScreenOnClear = Runnable {
        runCatching { window.decorView.keepScreenOn = false }
            .onFailure { Log.i(MOTION_TAG, "clear keepScreenOn failed", it) }
    }

    /** The 15s system screen timeout does not reset on accelerometer
     * activity, so the screen goes dark while the device is still in hand
     * (observed: screen turned off mid-shake 15.5s after the motion wake).
     * While the device is actually being moved (delta past the near threshold),
     * hold FLAG_KEEP_SCREEN_ON and re-arm the clear timer on every sample. */
    private fun holdScreenWhileInHand() {
        if (!screenOn) return
        val now = System.currentTimeMillis()
        if (now - lastHoldScreenMs < HOLD_SCREEN_INTERVAL_MS) return
        lastHoldScreenMs = now
        runCatching {
            val dv = window.decorView
            dv.keepScreenOn = true
            keyHandler.removeCallbacks(keepScreenOnClear)
            keyHandler.postDelayed(keepScreenOnClear, KEEP_SCREEN_HOLD_MS)
        }.onFailure { Log.i(MOTION_TAG, "set keepScreenOn failed", it) }
    }

    private fun trackAmbientWindow(mag: Float, now: Long) {
        if (ambientWindowStart == 0L) ambientWindowStart = now
        ambientSamples++
        if (mag < ambientMin) ambientMin = mag
        if (mag > ambientMax) ambientMax = mag
        ambientSum += mag
        if (now - ambientWindowStart >= AMBIENT_WINDOW_MS) {
            val rate = "%.1f".format(ambientSamples * 1000f / (now - ambientWindowStart))
            val summary =
                "ambient ${AMBIENT_WINDOW_MS}ms: n=$ambientSamples rate=$rate/s " +
                    "min=${f2(ambientMin)} max=${f2(ambientMax)} " +
                    "mean=${f2(ambientSum / ambientSamples)} " +
                    "maxLin=${f2(ambientMaxLin)} over=$ambientOverThreshold"
            Log.i(MOTION_TAG, summary)
            ambientSamples = 0
            ambientMin = Float.MAX_VALUE
            ambientMax = 0f
            ambientSum = 0f
            ambientMaxLin = 0f
            ambientOverThreshold = 0
            ambientWindowStart = now
        }
    }

    /** Live screen-on/off state, fed to Compose (see [composeContent]) so
     * cards that do continuous background work while composed — e.g.
     * CameraCard's live MJPEG stream / snapshot polling — can pause it while
     * the screen is off instead of quietly burning CPU + radio all night on
     * whatever page was showing when the screen last timed out. This is a
     * HOME launcher activity, so the Activity itself is never stopped just
     * because the screen turns off (that's the whole point of motion-wake),
     * meaning Compose keeps recomposing/running LaunchedEffects unless
     * something explicit like this tells cards to stand down. */
    private var screenOn by mutableStateOf(true)
    private var screenOnSinceMs = 0L
    private var screenOffSinceMs = 0L
    private val screenStateReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                when (intent?.action) {
                    Intent.ACTION_SCREEN_OFF -> {
                        screenOn = false
                        screenOffSinceMs = now
                        lastScreenOffMs = now
                        warmupDrops = 0
                        val onFor = if (screenOnSinceMs > 0) now - screenOnSinceMs else -1L
                        val timeoutMs =
                            runCatching {
                                Settings.System.getString(
                                    contentResolver,
                                    Settings.System.SCREEN_OFF_TIMEOUT
                                )?.toLongOrNull()
                            }.getOrNull()
                        Log.i(SCREEN_TAG, "SCREEN_OFF — was on for ${onFor}ms, system timeout=${timeoutMs}ms")
                    }
                    Intent.ACTION_SCREEN_ON -> {
                        screenOn = true
                        screenOnSinceMs = now
                        val offFor = if (screenOffSinceMs > 0) now - screenOffSinceMs else -1L
                        Log.i(SCREEN_TAG, "SCREEN_ON — was off for ${offFor}ms")
                    }
                }
            }
        }

    /** See ChargeDockMonitor.kt for what this actually does — kept as a
     * single delegate here rather than inline so this class doesn't
     * accumulate yet another self-contained feature's receiver/state/timer
     * on top of everything else already here. */
    private val chargeDockMonitor = ChargeDockMonitor(this)

    /** Any touch/key event anywhere in the Activity, regardless of which
     * view (or Compose node) actually consumed it. */
    override fun onUserInteraction() {
        super.onUserInteraction()
        chargeDockMonitor.onUserInteraction()
    }

    private lateinit var client: HaClient

    /** Owns one HarmonyHubClient per configured Harmony hub. */
    private lateinit var harmonyRegistry: HarmonyHubRegistry
    private lateinit var extenderRegistry: ExtenderRegistry

    /** Local IR blaster — used by hotkeys with irDevice+irCommand (see
     * runHotkey()) and shared with the composed-Activity switch executor in
     * Dashboard.kt's own Compose-scoped instance; this one is MainActivity's
     * own since hotkey dispatch happens outside Compose. Null on hardware
     * with no IR blaster. */
    private val irManager: ConsumerIrManager? by lazy {
        getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
    }

    private lateinit var configServer: ConfigServer
    private lateinit var configServerSupervisor: ConfigServerSupervisor

    /**
     * Off by default: this is a battery-powered handheld remote that spends
     * most of its time idle/screen-off, and WIFI_MODE_FULL_HIGH_PERF disables
     * the Wi-Fi radio's own power-save the whole time it's held — a real,
     * continuous battery cost, not a one-off. When on, it fixes Home
     * Assistant intermittently failing to reach this device while the
     * screen's off (ConfigServer's listener itself never stops — see
     * screenOn above — the radio going into power-save was the actual
     * culprit). Worth it if you rely on the astrion.set_page/start_activity
     * services or the push-webhook feature; not worth it if you don't call
     * either from Home Assistant while the screen would otherwise be off.
     * Persisted, toggled live via setWifiKeepAwake() — see
     * WifiKeepAwakeRow in SettingsMenu.kt.
     */
    private var wifiKeepAwakeEnabled by mutableStateOf(false)
    private var wifiLock: WifiManager.WifiLock? = null

    /** Latest [ActivityRuntime] handed up from Dashboard.kt's own
     * `remember(config) { ActivityRuntime(config) }` (see its
     * onActivityRuntimeReady doc) — ConfigServer reads this lazily via a
     * lambda so it always sees the current instance, including across a
     * dashboard.json reload. Null only for the brief window before the
     * first composition runs. */
    private var activityRuntime: ActivityRuntime? = null

    /** Live start/stop-activity functions handed up from Dashboard.kt the
     * same way — they close over Compose-scoped state (activitiesById,
     * harmonyRegistry, client) that MainActivity itself doesn't have direct
     * access to. */
    private var startActivityFn: ((String) -> Unit)? = null
    private var stopActivityFn: ((String) -> Unit)? = null
    private val keyRouter = HardwareKeyRouter()
    private var dashboard by mutableStateOf(DashboardLoader.Result(DashboardConfig.default, null))
    private var navTarget by mutableStateOf<Int?>(null)
    private var overlayTarget by mutableStateOf<String?>(null)

    /** See DashboardNavigation.volumeHotkeyTrigger — set fresh (never
     * cleared back to null) every time a VOLUME_UP/VOLUME_DOWN/MUTE hotkey
     * fires against a real HA entity, in runHotkey() below. */
    private var volumeHotkeyTrigger by mutableStateOf<VolumeHotkeyTrigger?>(null)

    /** Which page is currently visible — used to know which page-scoped
     * hotkeys should currently be layered on top of the global ones. */
    private var currentPageIndex = 0

    private val prefs by lazy { getSharedPreferences("astrion_settings", MODE_PRIVATE) }

    /** Backing state for the settings page's "Wake on movement" switch —
     * persisted, and toggled live via setWakeOnMotion() without a restart. */
    private var wakeOnMotionEnabled by mutableStateOf(true)

    /** Backing state for the settings page's "Local config server" switch —
     * persisted, and toggled live via setConfigServerEnabled() without a
     * restart. Defaults to true so a fresh install can still be configured;
     * meant to be turned off once setup is done, closing the unauthenticated
     * :8080 admin surface. */
    private var configServerEnabled by mutableStateOf(true)

    /** Backing state for the settings page's "Tap feedback" switch —
     * persisted, and toggled live via setTapFeedbackEnabled() without a
     * restart. Defaults to true so the remote gives the same little "tap"
     * notice its own Android UI menus do when you touch a scene, button,
     * dot, etc. Plays the system touch sound (AudioManager.FX_KEY_CLICK). */
    private var tapFeedbackEnabled by mutableStateOf(true)

    private val storagePermission =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { reloadDashboard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i("MainActivity", "onCreate — activity instance ${this.hashCode()}")

        // Enable full-screen immersive sticky mode for dedicated wall/remote tablet mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                )
            )
        }

        wakeOnMotionEnabled = prefs.getBoolean("wake_on_motion_enabled", true)
        setupMotionWake()
        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            }
        )
        val pmInteractive = (getSystemService(POWER_SERVICE) as? PowerManager)?.isInteractive
        if (pmInteractive == true) {
            screenOnSinceMs = System.currentTimeMillis()
        } else {
            val nowMs = System.currentTimeMillis()
            screenOffSinceMs = nowMs
            lastScreenOffMs = nowMs
        }
        Log.i(SCREEN_TAG, "onCreate — initial screenOn=$screenOn pm.isInteractive=$pmInteractive")

        chargeDockMonitor.start()

        initClientsAndServer()
        configServerSupervisor =
            ConfigServerSupervisor(
                scope = lifecycleScope,
                serverProvider = { configServer },
                isEnabled = { configServerEnabled && !isDestroyed }
            )
        configServerEnabled = prefs.getBoolean("config_server_enabled", true)
        tapFeedbackEnabled = prefs.getBoolean("tap_feedback_enabled", true)
        wifiKeepAwakeEnabled = prefs.getBoolean("wifi_keep_awake_enabled", false)
        if (wifiKeepAwakeEnabled) acquireWifiLock()
        if (configServerEnabled) configServerSupervisor.start()
        lifecycleScope.launch { harmonyRegistry.connectAll() }

        currentPageIndex = dashboard.config.startPage
        rebindHotkeysForCurrentPage()
        client.connect()

        composeContent()
    }

    /** Creates (or re-creates) the HA client, Harmony registry, and ConfigServer
     *  from current RemoteSettings. Called once from onCreate and again whenever
     *  connection settings are saved — avoids Activity.recreate(), which is
     *  unreliable on Android 8.1 HOME launcher activities. */
    private fun initClientsAndServer() {
        client = HaClient(baseUrl = RemoteSettings.haUrl(this), token = RemoteSettings.haToken(this))
        harmonyRegistry =
            HarmonyHubRegistry(
                hubs = RemoteSettings.harmonyHubs(this),
                onError = { hubName, msg -> Log.e("HarmonyHubClient", "[$hubName] $msg") },
                onHubIdDiscovered = { updatedHubs -> RemoteSettings.saveHarmonyHubs(this, updatedHubs) }
            )
        extenderRegistry =
            ExtenderRegistry(
                extenders = RemoteSettings.extenders(this),
                onError = { extName, msg -> Log.e("ExtenderClient", "[$extName] $msg") }
            )
        configServer =
            ConfigServer(
                context = this,
                harmonyRegistry = harmonyRegistry,
                haClient = client,
                onConnectionSaved = { runOnUiThread { reconnectWithNewSettings() } },
                onDashboardUpdated = { runOnUiThread { reloadDashboard() } },
                getPageNames = { dashboard.config.pages.map { it.name } },
                getCurrentPageIndex = { currentPageIndex },
                // Reuses the exact mechanism hardware shortcut buttons already use
                // (see runHotkey's `hk.page` branch): set navTarget, Dashboard's
                // LaunchedEffect(navTarget) does the scrollToPage + clears it.
                onSetPage = { index -> runOnUiThread { navTarget = index } },
                getActivityRuntime = { activityRuntime },
                onStartActivity = { id -> runOnUiThread { startActivityFn?.invoke(id) } },
                onStopActivity = { room -> runOnUiThread { stopActivityFn?.invoke(room) } }
            )
    }

    /** Called when the user saves new HA/Harmony connection settings via the
     *  web configurator. Disconnects the old clients, re-creates them with the
     *  updated settings, restarts the ConfigServer, and re-composes the UI —
     *  all within the same Activity instance, no recreate() needed. */
    private fun reconnectWithNewSettings() {
        Log.i("MainActivity", "reconnectWithNewSettings")
        configServerSupervisor.stop()
        client.disconnect()
        harmonyRegistry.disconnectAll()

        initClientsAndServer()
        if (configServerEnabled) configServerSupervisor.start()
        client.connect()
        lifecycleScope.launch { harmonyRegistry.connectAll() }

        composeContent()
    }

    /**
     * Docked or not, [Dashboard] stays composed — earlier versions replaced
     * it outright with [ChargingScreen] via `return@setContent`, which also
     * tore down everything that only exists *inside* Dashboard's own
     * composition: the `LaunchedEffect(navTarget)` that actually acts on
     * page-navigation requests (from hardware hotkeys as well as
     * ConfigServer's `/set-page`), and the Activity start/stop callbacks
     * `onActivityRuntimeReady`/`onStartActivityReady`/`onStopActivityReady`
     * wire up. So while docked, HA's page `select` and Activity control
     * would flip back after ~5s: MainActivity updated `navTarget`/called the
     * runnable, but nothing was composed to consume either one. Overlaying
     * [ChargingScreen] in a [Box] instead keeps the exact same visual result
     * (it fills the screen) while leaving page nav, Activities, and webhooks
     * live in the background. Heavy cards (camera/vacuum/...) still pause
     * while docked via `screenOn = screenOn && !isDocked` below, same as
     * they already do whenever the physical screen is off.
     */
    private fun composeContent() {
        setContent {
            val entities = client.entities.collectAsState()
            val connection = client.connection.collectAsState()
            val isDocked = chargeDockMonitor.state.isDocked
            Box {
                Dashboard(
                    connection = DashboardConnection(client = client, entitiesState = entities, connectionState = connection),
                    registries = DashboardRegistries(harmonyRegistry = harmonyRegistry, extenderRegistry = extenderRegistry),
                    config = dashboard.config,
                    navigation =
                    DashboardNavigation(
                        navTarget = navTarget,
                        onNavHandled = { navTarget = null },
                        overlayTarget = overlayTarget,
                        onOverlayHandled = { overlayTarget = null },
                        onPageChanged = { pageIndex ->
                            currentPageIndex = pageIndex
                            rebindHotkeysForCurrentPage()
                        },
                        volumeHotkeyTrigger = volumeHotkeyTrigger
                    ),
                    uiState =
                    DashboardUiState(
                        configNotice = dashboard.notice,
                        deviceSettings =
                        DeviceSettingsState(
                            wakeOnMotionEnabled = wakeOnMotionEnabled,
                            setWakeOnMotionEnabled = { enabled -> setWakeOnMotion(enabled) },
                            wifiKeepAwakeEnabled = wifiKeepAwakeEnabled,
                            setWifiKeepAwakeEnabled = { enabled -> setWifiKeepAwake(enabled) },
                            configServerEnabled = configServerEnabled,
                            setConfigServerEnabled = { enabled -> updateConfigServerEnabled(enabled) },
                            tapFeedbackEnabled = tapFeedbackEnabled,
                            setTapFeedbackEnabled = { enabled -> setTapFeedback(enabled) }
                        ),
                        screenOn = screenOn && !isDocked
                    ),
                    activityCallbacks =
                    DashboardActivityCallbacks(
                        onActivityRuntimeReady = { activityRuntime = it },
                        onStartActivityReady = { fn -> startActivityFn = fn },
                        onStopActivityReady = { fn -> stopActivityFn = fn }
                    )
                )
                if (isDocked) {
                    val theme = remember(dashboard.config.theme) { dashboard.config.theme.toColors() }
                    ProvideTheme(theme) { ChargingScreen(dimmed = chargeDockMonitor.dimmed) }
                }
            }
        }
    }

    /**
     * Jump to the current page's [PageConfig.parent], if any — the same
     * navTarget mechanism `runHotkey`'s own page-navigation branch uses.
     * Returns false (no-op) on a root page, or if the parent name doesn't
     * resolve to an actual page.
     */
    private fun goToParent(): Boolean {
        val parentName =
            dashboard.config.pages
                .getOrNull(currentPageIndex)
                ?.parent ?: return false
        val idx = dashboard.config.pages.indexOfFirst { it.name.equals(parentName, ignoreCase = true) }
        if (idx < 0) return false
        navTarget = idx
        return true
    }

    /**
     * Safety net for the default case only. The configurable parent-jump
     * itself is bound as a regular fallback handler in `keyRouter` by
     * `rebindHotkeysForCurrentPage()`, for whichever key [PageConfig.parentKey]
     * names — that's what fires for a custom (non-BACK) key, via
     * `dispatchKeyEvent`. This override exists only to preserve the exact
     * original behavior when `parentKey` is left at its default ("BACK"):
     * if it were set to something else, the hardware BACK button goes back
     * to doing nothing here, same as a page with no parent at all — it's
     * simply no longer this page's configured "leave" button. Either way,
     * BACK is never allowed to dismiss the launcher itself (kiosk mode).
     */
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    @SuppressLint("MissingSuperCall") // intentional: BACK is fully intercepted on root pages
    // to keep this a kiosk-mode launcher — see class doc above.
    override fun onBackPressed() {
        val page = dashboard.config.pages.getOrNull(currentPageIndex) ?: return
        if (page.parent != null && page.parentKey.equals("BACK", ignoreCase = true)) {
            goToParent()
        }
    }

    override fun onResume() {
        super.onResume()
        Log.i("MainActivity", "onResume — instance ${this.hashCode()} screenOn=$screenOn")
        reloadDashboard()
    }

    override fun onPause() {
        super.onPause()
        Log.i("MainActivity", "onPause — instance ${this.hashCode()} screenOn=$screenOn")
    }

    override fun onStop() {
        super.onStop()
        Log.i("MainActivity", "onStop — instance ${this.hashCode()} screenOn=$screenOn")
    }

    private fun reloadDashboard() {
        val result = DashboardLoader.load()
        dashboard = result
        currentPageIndex = currentPageIndex.coerceIn(0, result.config.pages.size - 1)
        rebindHotkeysForCurrentPage()
    }

    /** Merge the global hotkeys with the current page's own hotkeys — a
     * page-scoped binding for a given key wins over the global one for that
     * same key while that page is visible; keys the page doesn't touch keep
     * their global behavior (e.g. volume always targets the soundbar). Then,
     * if the page has a [PageConfig.parent], layer in the parent-navigation
     * fallback on [PageConfig.parentKey] — but only if that key isn't
     * already claimed by one of the hotkeys just bound above, so an AV page
     * that binds its own hotkey on the same key (e.g. a custom HOME action)
     * is never overridden by the fallback. */
    private fun rebindHotkeysForCurrentPage() {
        val page = dashboard.config.pages.getOrNull(currentPageIndex)
        val mergedShort = mergeHotkeys(dashboard.config.hotkeys, page?.hotkeys.orEmpty())
        val mergedLong = mergeHotkeys(dashboard.config.longHotkeys, page?.longHotkeys.orEmpty())
        bindHotkeys(mergedShort, mergedLong)

        if (page?.parent != null) {
            val key = runCatching { HardwareKey.valueOf(page.parentKey.uppercase()) }.getOrNull()
            if (key != null && !keyRouter.isShortBound(key)) {
                keyRouter.on(key) { goToParent() }
            }
        }
    }

    private fun mergeHotkeys(global: List<HotkeyConfig>, pageSpecific: List<HotkeyConfig>): List<HotkeyConfig> {
        val byKey = LinkedHashMap<String, HotkeyConfig>()
        global.forEach { byKey[it.key.uppercase()] = it }
        pageSpecific.forEach { byKey[it.key.uppercase()] = it } // Page overrides global for the same key
        return byKey.values.toList()
    }

    // ---- Hotkeys ------------------------------------------------------------

    private fun bindHotkeys(short: List<HotkeyConfig>, long: List<HotkeyConfig>) {
        keyRouter.clear()
        short.forEach { hk ->
            val key =
                runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                    ?: return@forEach
            keyRouter.on(key) { runHotkey(hk) }
        }
        long.forEach { hk ->
            val key =
                runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                    ?: return@forEach
            keyRouter.onLong(key) { runHotkey(hk) }
        }
    }

    /** `hk.openOverlay`'s handling, split out of [runHotkey] purely to keep
     * that function's cyclomatic complexity down — behavior unchanged. */
    private fun openOverlayHotkey(target: String): Boolean {
        if (!target.equals("settings", ignoreCase = true) && !target.equals("activities", ignoreCase = true)) return false
        overlayTarget = target.lowercase()
        return true
    }

    /** `hk.openCurrentActivityRoom`'s handling, split out of [runHotkey]
     * purely to keep that function's cyclomatic complexity down — behavior
     * unchanged. Deliberately does NOT fall through to the rest of a
     * binding's action chain when the room has nothing active: a hotkey
     * configured for this is meant to be a dedicated "back to what's
     * playing" button, not a page-nav/service call in disguise for the
     * idle case. */
    private fun openCurrentActivityHotkey(room: String): Boolean {
        val pageName = activityRuntime?.activeActivity(room)?.page ?: return false
        val idx = dashboard.config.pages.indexOfFirst { it.name.equals(pageName, ignoreCase = true) }
        if (idx < 0) return false
        navTarget = idx
        return true
    }

    /**
     * Execute one hotkey, in priority order:
     *  1. Open overlay (settings / active activities)
     *  2. Open current Activity's page for a room
     *  3. Page navigation
     *  4. Harmony Activity by id
     *  5. Direct Harmony hub command (harmonyDevice + harmonyCommand) — no HA involved
     *  6. Local IR command (irDevice + irCommand) — no hub, no HA, fully offline
     *  7. Home Assistant service call
     */
    /** Resolves and routes a hotkey's IR command to wherever `device.target`
     * points — extracted out of [runHotkey] itself, which was pushed over
     * detekt's cyclomatic-complexity/nesting-depth thresholds by this
     * branch when it lived inline. */
    private fun sendHotkeyIrCommand(irDevice: String, irCommand: String) {
        val device = dashboard.config.irDevices.firstOrNull { it.id == irDevice }
        if (device == null) {
            Log.w("MainActivity", "hotkey irDevice=$irDevice not found in AppConfig.irDevices")
            return
        }
        val irStep = IrDatabaseRuntime.resolve(device, irCommand)
        if (irStep == null) {
            Log.w("MainActivity", "hotkey irDevice=$irDevice irCommand=$irCommand not found")
            return
        }
        when (val target = device.target) {
            // Unchanged from before this device gained a `target` field —
            // every dashboard.json without one defaults here.
            IrTarget.Local ->
                runCatching { irManager?.transmit(irStep.freq, irStep.pattern.toIntArray()) }
                    .onFailure { Log.e("MainActivity", "hotkey IR send failed: $irDevice/$irCommand", it) }
            is IrTarget.Extender -> sendHotkeyIrViaExtender(irDevice, irCommand, irStep, target)
        }
    }

    private fun sendHotkeyIrViaExtender(irDevice: String, irCommand: String, irStep: IrStepConfig, target: IrTarget.Extender) {
        val prontoCode = irStep.pronto
        if (prontoCode == null) {
            // Inline-sourced devices don't carry the original Pronto string in
            // dashboard.json today (only the already-decoded freq/pattern) --
            // only ir-database (SdCardRef) devices can target an extender for
            // now. See IrStepConfig's kdoc.
            Log.w(
                "MainActivity",
                "hotkey irDevice=$irDevice irCommand=$irCommand targets an extender but has no raw Pronto " +
                    "string (Inline-sourced IR devices can't target an extender yet)"
            )
            return
        }
        extenderRegistry.client(target.extenderId)?.let { client ->
            lifecycleScope.launch { client.send(prontoCode) }
        }
    }

    private fun runHotkey(hk: HotkeyConfig): Boolean {
        if (hk.openOverlay != null) return openOverlayHotkey(hk.openOverlay)
        if (hk.openCurrentActivityRoom != null) return openCurrentActivityHotkey(hk.openCurrentActivityRoom)

        hk.page?.let { pageName ->
            val idx = dashboard.config.pages.indexOfFirst { it.name.equals(pageName, ignoreCase = true) }
            if (idx < 0) return false
            navTarget = idx
            return true
        }

        hk.harmonyActivity?.let { activityId ->
            harmonyRegistry.client(hk.hub)?.startActivity(activityId)
                ?: Log.w("MainActivity", "hotkey harmonyActivity=$activityId (hub=${hk.hub}) but that hub isn't configured")
            return true
        }

        val device = hk.harmonyDevice
        val command = hk.harmonyCommand
        if (device != null && command != null) {
            harmonyRegistry.client(hk.hub)?.sendCommand(device, command)
                ?: Log.w("MainActivity", "hotkey harmonyCommand (hub=${hk.hub}) but that hub isn't configured")
            return true
        }

        val irDevice = hk.irDevice
        val irCommand = hk.irCommand
        if (irDevice != null && irCommand != null) {
            sendHotkeyIrCommand(irDevice, irCommand)
            return true
        }

        val service = hk.service ?: return false
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val data = hk.data.mapValues { JsonPlain.toJson(it.value) }
        client.callService(ServiceCall(domain, svc, hk.entityId, data))
        maybeTriggerVolumePopup(hk)
        return true
    }

    /**
     * Pops up a temporary volume readout for a VOLUME_UP/VOLUME_DOWN/MUTE
     * hotkey that just fired an HA service call — split out of [runHotkey]
     * purely to keep that function's cyclomatic complexity under detekt's
     * threshold, no behavior difference from having it inline. No
     * queryable numeric level exists for the harmonyCommand/irCommand
     * branches earlier in [runHotkey] (no HA entity there at all), so this
     * is only ever called from the HA-service-call path.
     */
    private fun maybeTriggerVolumePopup(hk: HotkeyConfig) {
        val entityId = hk.entityId ?: return
        if (hk.key.uppercase() in VOLUME_POPUP_KEYS) {
            volumeHotkeyTrigger = VolumeHotkeyTrigger(entityId, System.nanoTime())
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        logKeyEventDown(event)
        val shortH = keyRouter.shortHandler(code)
        val longH = keyRouter.longHandler(code)

        if (shortH == null && longH == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(KEY_TAG, "keyCode=$code (${KeyEvent.keyCodeToString(code)})")
                if (DEBUG_KEYS) Toast.makeText(this, "Unmapped key: $code", Toast.LENGTH_SHORT).show()
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (longH != null) {
                    if (event.repeatCount == 0) {
                        cancelPendingLong()
                        longFired = false
                        activeLongKey = code
                        val r =
                            Runnable {
                                longFired = true
                                fireButtonTap()
                                longH.invoke()
                            }
                        pendingLong = r
                        keyHandler.postDelayed(r, LONG_PRESS_MS)
                    }
                } else {
                    fireButtonTap()
                    shortH?.invoke()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (longH != null && code == activeLongKey) {
                    cancelPendingLong()
                    activeLongKey = -1
                    if (!longFired) {
                        fireButtonTap()
                        shortH?.invoke()
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun cancelPendingLong() {
        pendingLong?.let { keyHandler.removeCallbacks(it) }
        pendingLong = null
    }

    private fun logKeyEventDown(event: KeyEvent) {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            val code = event.keyCode
            Log.i(
                KEY_TAG,
                "DOWN keyCode=$code (${KeyEvent.keyCodeToString(code)}) key=${HardwareKey.fromKeyCode(code)}"
            )
        }
    }

    // ---- Motion Wake --------------------------------------------------------

    private fun setupMotionWake() {
        val sm = getSystemService(SENSOR_SERVICE) as? SensorManager ?: return
        sensorManager = sm

        motionSensor = sm
            .getSensorList(Sensor.TYPE_ACCELEROMETER)
            .firstOrNull { it.isWakeUpSensor }
            ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val s = motionSensor
        if (s == null) {
            Log.e(MOTION_TAG, "setup: no accelerometer found, motion wake disabled")
        } else {
            Log.i(
                MOTION_TAG,
                "setup: sensor='${s.name}' vendor='${s.vendor}' " +
                    "wakeUp=${s.isWakeUpSensor} minDelay=${s.minDelay}us wakeOnMotion=$wakeOnMotionEnabled " +
                    "tiltWake=${TILT_WAKE_DEG}deg linWake=${LIN_ACC_WAKE} consecutive=$MOTION_CONSECUTIVE_N " +
                    "warmup=${SCREEN_OFF_WARMUP_MS}ms floor=$MAGNITUDE_SANITY_FLOOR"
            )
        }

        if (wakeOnMotionEnabled) registerMotionListener()
    }

    private fun registerMotionListener() {
        val sm = sensorManager ?: return
        motionSensor?.let { sensor ->
            sm.registerListener(motionListener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            Log.i(MOTION_TAG, "listener registered on '${sensor.name}' (SENSOR_DELAY_NORMAL)")
        }
    }

    /** Called from the settings page switch — persists the choice and
     * registers/unregisters the sensor listener immediately, no restart needed. */
    private fun setWakeOnMotion(enabled: Boolean) {
        Log.i(MOTION_TAG, "setWakeOnMotion($enabled)")
        wakeOnMotionEnabled = enabled
        prefs.edit { putBoolean("wake_on_motion_enabled", enabled) }
        if (enabled) {
            registerMotionListener()
        } else {
            keyHandler.removeCallbacks(keepScreenOnClear)
            runCatching { window.decorView.keepScreenOn = false }
            sensorManager?.unregisterListener(motionListener)
        }
    }

    /** Called from the settings page switch — see [wifiKeepAwakeEnabled]'s doc
     * for the battery-vs-reachability trade-off this toggles. */
    private fun setWifiKeepAwake(enabled: Boolean) {
        wifiKeepAwakeEnabled = enabled
        prefs.edit { putBoolean("wifi_keep_awake_enabled", enabled) }
        if (enabled) acquireWifiLock() else releaseWifiLock()
    }

    /**
     * Grabs a high-perf Wi-Fi lock so the radio doesn't get suspended while
     * the screen is off — see [wifiKeepAwakeEnabled]'s doc for the trade-off.
     * Best-effort: a failure here (e.g. a device without the standard Wi-Fi
     * stack) just means Home Assistant may see occasional connection drops
     * while the screen's off, not a crash.
     */
    private fun acquireWifiLock() {
        if (wifiLock?.isHeld == true) return
        runCatching {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock =
                wifiManager
                    .createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "astrion:configserver")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
        }.onFailure { Log.w("MainActivity", "Failed to acquire Wi-Fi lock", it) }
    }

    private fun releaseWifiLock() {
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wifiLock = null
    }

    /** Called from the settings page switch — persists the choice and
     * starts/stops the :8080 server immediately, no restart needed. Turning
     * it off also closes /builder/, icon uploads, and dashboard.json
     * upload/download until it's switched back on from here. */
    private fun updateConfigServerEnabled(enabled: Boolean) {
        configServerEnabled = enabled
        prefs.edit { putBoolean("config_server_enabled", enabled) }
        if (enabled) configServerSupervisor.start() else configServerSupervisor.stop()
    }

    /** Called from the settings page switch — persists the choice, no
     * restart needed. Dashboard reads [tapFeedbackEnabled] and rebuilds the
     * feedback lambda it provides via LocalTapFeedback, so taps go silent
     * (or come back) on the next recomposition. */
    private fun setTapFeedback(enabled: Boolean) {
        tapFeedbackEnabled = enabled
        prefs.edit { putBoolean("tap_feedback_enabled", enabled) }
    }

    /** Fires the tap sound for a hardware-button press — the button-press
     * counterpart of the screen-tap sound Dashboard provides via
     * LocalTapFeedback. Plays the system touch sound (AudioManager.FX_KEY_CLICK,
     * the same one native Android UI menus play on touch). Gated by
     * [tapFeedbackEnabled]. */
    private fun fireButtonTap() {
        if (!tapFeedbackEnabled) return
        val am = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        am.playSoundEffect(AudioManager.FX_KEY_CLICK)
    }

    @Suppress("DEPRECATION")
    private fun wakeScreen(tiltDeg: Float, linAcc: Float, mag: Float) {
        val pm = getSystemService(POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) {
            Log.i(MOTION_TAG, "wakeScreen skipped: already interactive (tilt=${f1(tiltDeg)} lin=${f2(linAcc)})")
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastWakeMs < WAKE_COOLDOWN_MS) {
            val left = WAKE_COOLDOWN_MS - (now - lastWakeMs)
            Log.i(MOTION_TAG, "wakeScreen skipped: cooldown ${left}ms left (tilt=${f1(tiltDeg)} lin=${f2(linAcc)})")
            return
        }
        lastWakeMs = now

        Log.i(MOTION_TAG, "WAKE: tilt=${f1(tiltDeg)} lin=${f2(linAcc)} mag=${f2(mag)} — acquiring wakelock 5000ms")

        // Suppressed deprecation for older Android 8.1 (HA100 remote) compatibility
        val flags =
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                PowerManager.ACQUIRE_CAUSES_WAKEUP or
                PowerManager.ON_AFTER_RELEASE

        val wl = pm.newWakeLock(flags, "astrion:motionwake")
        wl.acquire(5000)
        keyHandler.postDelayed({
            if (wl.isHeld) {
                Log.i(MOTION_TAG, "wakelock released at 4500ms (early release)")
                wl.release()
            }
        }, 4500)
    }

    override fun onDestroy() {
        Log.i("MainActivity", "onDestroy — activity instance ${this.hashCode()}")
        sensorManager?.unregisterListener(motionListener)
        Log.i(MOTION_TAG, "listener unregistered")
        runCatching { unregisterReceiver(screenStateReceiver) }
        chargeDockMonitor.stop()
        releaseWifiLock()
        if (::configServerSupervisor.isInitialized) configServerSupervisor.stop()
        client.disconnect()
        harmonyRegistry.disconnectAll()
        super.onDestroy()
    }
}
