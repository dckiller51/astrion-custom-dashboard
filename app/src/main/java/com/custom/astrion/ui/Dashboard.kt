package com.custom.astrion.ui

import android.content.Context
import android.hardware.ConsumerIrManager
import android.media.AudioManager
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.DeviceSettingsState
import com.custom.astrion.config.ActivityConfig
import com.custom.astrion.config.ActivityRuntime
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.IrDeviceConfig
import com.custom.astrion.config.PageConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.extender.ExtenderRegistry
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.HaLabels
import com.custom.astrion.harmony.HarmonyHubRegistry
import com.custom.astrion.ui.icons.MdiIcons
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Swipeable, paginated dashboard. Each config page is a horizontally-swipeable
 * screen; a row of dots at the bottom shows how many pages there are and which
 * one you're on. Swipe left/right to move between them, jump via a physical
 * shortcut button (see MainActivity hotkeys), OR tap a card that calls
 * ctx.navigateToPage("Page Name") — e.g. an Activities menu card.
 *
 * Swiping down from the very top edge opens the settings overlay — imitates
 * HaRemote's hidden gesture to reach settings, since the real status bar is
 * hidden (kiosk fullscreen).
 *
 * Sized for the HA100 panel (480x800, portrait). Each page scrolls vertically
 * on its own; the pager stays light for the 1GB / MT6580 hardware.
 */
/** [HaClient] plus the two [State] flows it drives — bundled together since
 * all three are part of "the live connection to Home Assistant", and
 * bundling is what got [Dashboard]'s parameter count under detekt's
 * LongParameterList threshold. */
data class DashboardConnection(
    val client: HaClient,
    val entitiesState: State<EntityMap>,
    val connectionState: State<ConnectionState>
)

/** The two hub-style registries [Dashboard] talks to directly, bypassing
 * Home Assistant. */
data class DashboardRegistries(
    val harmonyRegistry: HarmonyHubRegistry,
    val extenderRegistry: ExtenderRegistry
)

data class DashboardNavigation(
    /** Page index requested by a hardware button; consumed via onNavHandled. */
    val navTarget: Int? = null,
    val onNavHandled: () -> Unit = {},
    /** Overlay requested by a hardware button — "settings" or "activities"
     * (case-insensitive); anything else is ignored. Consumed the same way
     * as [navTarget]/[onNavHandled], via [onOverlayHandled]. */
    val overlayTarget: String? = null,
    val onOverlayHandled: () -> Unit = {},
    /** Called whenever the visible page changes (swipe, dot, hardware nav, or
     * a card's navigateToPage) — MainActivity uses this to rebind hardware
     * hotkeys to the newly-visible page's own bindings. */
    val onPageChanged: (Int) -> Unit = {},
    /** Set by MainActivity.runHotkey every time a VOLUME_UP/VOLUME_DOWN/MUTE
     * hotkey actually fires an HA service call against a real entity — used
     * to pop up a temporary volume readout (see [VolumePopupHost]). Unlike
     * [navTarget]/[overlayTarget] this is never "handled"/cleared by
     * Dashboard: each press is a fresh, distinct value (its own [nonce]),
     * so simply observing it again on every repeat press is enough to
     * restart the popup's own auto-dismiss timer — there's nothing for
     * MainActivity to reset in between. */
    val volumeHotkeyTrigger: VolumeHotkeyTrigger? = null
)

/** See [DashboardNavigation.volumeHotkeyTrigger]. [nonce] only exists so two
 * consecutive presses on the *same* entity still count as distinct values
 * to Compose — a plain `String` entityId wouldn't restart the popup's own
 * `LaunchedEffect` (and its auto-dismiss delay) on repeat presses, since
 * `LaunchedEffect(key)` only relaunches when [key] actually *changes*. */
data class VolumeHotkeyTrigger(val entityId: String, val nonce: Long)

data class DashboardUiState(
    val configNotice: String? = null,
    /** The settings-page toggles (motion-wake, Wi-Fi-keep-awake, config
     * server, tap feedback) — see [DeviceSettingsState]. */
    val deviceSettings: DeviceSettingsState = DeviceSettingsState(),
    /** Live screen-on/off state from MainActivity's ACTION_SCREEN_ON/OFF
     * receiver — threaded through to [CardContext.screenOn] so cards that do
     * continuous background work while composed (e.g. CameraCard's live
     * stream) can pause it while the screen is off. See MainActivity's
     * `screenStateReceiver` doc for why this can't just be "the Activity
     * stopped". */
    val screenOn: Boolean = true
)

data class DashboardActivityCallbacks(
    /** Fired once per [ActivityRuntime] instance (i.e. once per config
     * load) so MainActivity can hold a live reference for ConfigServer's
     * `/activities*` routes — ActivityRuntime is created here, inside
     * Compose, rather than in MainActivity, so it can react to a
     * dashboard.json reload the same way `remember(config)` already does. */
    val onActivityRuntimeReady: (ActivityRuntime) -> Unit = {},
    /** Same hoisting pattern for the start/stop actions themselves — these
     * close over `activitiesById`/`harmonyRegistry`/`client`, which only
     * exist in this Composable's scope, so ConfigServer gets a fresh
     * function reference instead of duplicating the dispatch logic. */
    val onStartActivityReady: ((String) -> Unit) -> Unit = {},
    val onStopActivityReady: ((String) -> Unit) -> Unit = {}
)

/** Settings/Activities overlay visibility. Activities is reachable by
 * swiping down from [TopStatusBar]; Settings by whichever hardware key the
 * person assigns `openOverlay: "settings"` to in the Hotkeys tab, or a
 * `navTarget`/`overlayTarget` push from elsewhere (e.g. ConfigServer).
 * Bundled with its two
 * setters, rather than exposing raw `MutableState`, so the composable that
 * owns them ([rememberDashboardOverlayState]) stays the single place that
 * mutates them. */
data class DashboardOverlayState(
    val showSettings: Boolean,
    val onShowSettingsChange: (Boolean) -> Unit,
    val showActivities: Boolean,
    val onShowActivitiesChange: (Boolean) -> Unit
)

/**
 * Owns Settings/Activities overlay visibility and the two hardware-driven
 * effects that can open one of them — extracted out of [Dashboard] for the
 * same LongMethod/CyclomaticComplexity reasons as
 * [DashboardActivityRuntimeEffects] above, logic unchanged:
 *  - [navTarget]: jump straight to that page (scrollToPage, not
 *    animateScrollToPage — a physical shortcut button should land
 *    directly, not visibly scroll through every page in between), then
 *    consume it via [onNavHandled].
 *  - [overlayTarget]: "settings" or "activities" (case-insensitive)
 *    opens the matching overlay, then consumed via [onOverlayHandled] —
 *    the hardware-button counterpart to the two swipe gestures.
 */
@Composable
private fun rememberDashboardOverlayState(
    navTarget: Int?,
    onNavHandled: () -> Unit,
    overlayTarget: String?,
    onOverlayHandled: () -> Unit,
    pagerState: PagerState,
    pageCount: Int
): DashboardOverlayState {
    var showSettings by remember { mutableStateOf(false) }
    var showActivities by remember { mutableStateOf(false) }
    BackHandler(enabled = showSettings) { showSettings = false }
    BackHandler(enabled = showActivities) { showActivities = false }

    LaunchedEffect(navTarget) {
        val target = navTarget ?: return@LaunchedEffect
        if (target in 0 until pageCount) pagerState.scrollToPage(target)
        onNavHandled()
    }

    LaunchedEffect(overlayTarget) {
        when (overlayTarget?.lowercase()) {
            "settings" -> showSettings = true
            "activities" -> showActivities = true
        }
        if (overlayTarget != null) onOverlayHandled()
    }

    return DashboardOverlayState(
        showSettings = showSettings,
        onShowSettingsChange = { showSettings = it },
        showActivities = showActivities,
        onShowActivitiesChange = { showActivities = it }
    )
}

/**
 * The three activityRuntime-related side effects that used to live inline
 * in [Dashboard] — extracted for the same LongMethod/CyclomaticComplexity
 * reasons as [ActivityDispatcher], logic unchanged: binding each configured
 * Harmony hub's live activity into [activityRuntime], handing the instance
 * back via [onActivityRuntimeReady], and pushing an activity-change webhook
 * to the companion HA integration (instead of it having to poll
 * ConfigServer's `GET /activities/active` on a timer) whenever
 * [activityRuntime]'s active-by-room state changes.
 */
@Composable
private fun DashboardActivityRuntimeEffects(
    activityRuntime: ActivityRuntime,
    harmonyRegistry: HarmonyHubRegistry,
    client: HaClient,
    webhookContext: Context,
    onActivityRuntimeReady: (ActivityRuntime) -> Unit
) {
    LaunchedEffect(activityRuntime) {
        harmonyRegistry.clientsByLocalId.forEach { (localId, hubClient) ->
            launch {
                hubClient.connected.first { it }
                hubClient.getCurrentActivity()
                activityRuntime.bind(hubClient, localId)
            }
        }
    }
    LaunchedEffect(activityRuntime) { onActivityRuntimeReady(activityRuntime) }

    LaunchedEffect(activityRuntime) {
        val webhookId = RemoteSettings.haWebhookId(webhookContext)
        if (webhookId.isBlank()) return@LaunchedEffect
        activityRuntime.activeByRoom.collect { byRoom ->
            val rooms =
                buildJsonObject {
                    byRoom.keys.forEach { room ->
                        val active = activityRuntime.activeActivity(room)
                        if (active == null) {
                            put(room, JsonNull)
                        } else {
                            put(
                                room,
                                buildJsonObject {
                                    put("id", active.id)
                                    put("name", active.name)
                                }
                            )
                        }
                    }
                }
            client.pushWebhook(
                webhookId,
                buildJsonObject {
                    put("type", "activity")
                    put("rooms", rooms)
                }
            )
        }
    }
}

/** Everything [rememberActivityDispatcher] needs to construct an
 * [ActivityDispatcher] — bundled into one parameter purely to keep that
 * function itself under detekt's LongParameterList threshold (a data
 * class's `equals()` still makes `remember(inputs)` invalidate correctly
 * whenever any field actually changes, same as the individual-parameter
 * form this replaced). */
private data class ActivityDispatcherInputs(
    val client: HaClient,
    val harmonyRegistry: HarmonyHubRegistry,
    val extenderRegistry: ExtenderRegistry,
    val irManager: ConsumerIrManager?,
    val irDevicesById: Map<String, IrDeviceConfig>,
    val activitiesById: Map<String, ActivityConfig>,
    val activityRuntime: ActivityRuntime,
    val navigateToPage: (String) -> Unit,
    val scope: CoroutineScope,
    val extenderScope: CoroutineScope
)

/** Constructs (and, via `remember`, reuses across recompositions until any
 * of its inputs actually change) the [ActivityDispatcher] instance
 * [Dashboard] hands off IR/Activity commands to. Pulled out purely to keep
 * [Dashboard]'s own body shorter — the verbose one-arg-per-line
 * construction call was a meaningful chunk of it on its own. */
@Composable
private fun rememberActivityDispatcher(inputs: ActivityDispatcherInputs): ActivityDispatcher = remember(inputs) {
    ActivityDispatcher(
        client = inputs.client,
        registries = DashboardRegistries(harmonyRegistry = inputs.harmonyRegistry, extenderRegistry = inputs.extenderRegistry),
        irManager = inputs.irManager,
        irDevicesById = inputs.irDevicesById,
        activitiesById = inputs.activitiesById,
        activityRuntime = inputs.activityRuntime,
        navigateToPage = inputs.navigateToPage,
        scopes = ActivityDispatcherScopes(scope = inputs.scope, extenderScope = inputs.extenderScope)
    )
}

/** Everything [buildCardContext] needs — same bundling-for-detekt reason
 * as [ActivityDispatcherInputs] above. */
private data class CardContextInputs(
    val entities: EntityMap,
    val client: HaClient,
    val navigateToPage: (String) -> Unit,
    /** See [CardContext.openPagePopup] — built in [Dashboard] from the same
     * `linkedPopupPage`/`popupOpenedByEntityId` slot `DashboardEntityPageEffect`
     * and the `linkedPage` swipe-up already share, so a tile-opened popup,
     * an entity-opened one, and a swipe-up one can never fight over the
     * screen at once. */
    val openPagePopup: (String) -> Unit,
    /** See [CardContext.closePopup]. */
    val closePopup: () -> Unit,
    val harmonyRegistry: HarmonyHubRegistry,
    val deviceSettings: DeviceSettingsState,
    val harmonyConnected: Boolean,
    val irDevicesById: Map<String, IrDeviceConfig>,
    val sendIrCommand: (String, String) -> Unit,
    val activitiesById: Map<String, ActivityConfig>,
    val startActivity: (String) -> Unit,
    val activityRuntime: ActivityRuntime,
    val theme: ThemeColors,
    val screenOn: Boolean
)

/** Builds the [CardContext] every card in the currently-visible page reads
 * from — a plain (non-`@Composable`) function, matching the original code,
 * which never memoized this via `remember` either (a fresh [CardContext]
 * on every recomposition, same as before this got pulled out). Pulled out
 * purely to keep [Dashboard]'s own body shorter. */
private fun buildCardContext(inputs: CardContextInputs): CardContext = CardContext(
    entities = inputs.entities,
    client = inputs.client,
    navigateToPage = inputs.navigateToPage,
    openPagePopup = inputs.openPagePopup,
    closePopup = inputs.closePopup,
    startHarmonyActivity = { activityId, hub ->
        inputs.harmonyRegistry.client(hub)?.startActivity(activityId)
            ?: Log.w("Dashboard", "startHarmonyActivity($activityId, hub=$hub) but that hub isn't configured")
    },
    sendHarmonyCommand = { deviceId, command, hub ->
        inputs.harmonyRegistry.client(hub)?.sendCommand(deviceId, command)
            ?: Log.w("Dashboard", "sendHarmonyCommand($deviceId, $command, hub=$hub) but that hub isn't configured")
    },
    deviceSettings = inputs.deviceSettings,
    harmonyConnected = inputs.harmonyConnected,
    irDevices = inputs.irDevicesById,
    sendIrCommand = inputs.sendIrCommand,
    activities = inputs.activitiesById,
    startActivity = inputs.startActivity,
    activityRuntime = inputs.activityRuntime,
    theme = inputs.theme,
    screenOn = inputs.screenOn
)

/** The tap-feedback lambda fired by every `Modifier.tapClickable` in the
 * app. Plays the system touch sound (Effect_Tick.ogg) via
 * AudioManager.playSoundEffect — the same sound native Android UI menus
 * play on touch. Compose's clickable doesn't call this by default, so we
 * fire it ourselves. Returns a no-op when [enabled] is false, so the
 * settings switch silences it app-wide without every call site needing its
 * own `if`. */
@Composable
private fun rememberTapFeedback(enabled: Boolean): () -> Unit {
    val feedbackContext = LocalContext.current
    return remember(feedbackContext, enabled) {
        if (enabled) {
            {
                val am = feedbackContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
                am?.playSoundEffect(AudioManager.FX_KEY_CLICK)
            }
        } else {
            {}
        }
    }
}

/** Everything [rememberDashboardRuntimeState] derives once per [config]
 * (or, for [harmonyConnected], once per Harmony connection change) —
 * bundled purely to keep [Dashboard]'s own body shorter, same reasoning as
 * every other extraction in this file. */
private data class DashboardRuntimeState(
    /** Reflects the first configured hub — good enough for a single glance
     * indicator; a per-hub breakdown isn't worth the UI space here. */
    val harmonyConnected: Boolean,
    val scope: CoroutineScope,
    val coroutineScope: CoroutineScope,
    val pagerState: PagerState,
    /** id of every [ActivityConfig]/[TrackedActivity] currently active in
     * *any* room (`ActivityRuntime.activeByRoom`'s non-null values, as a
     * set) — used only to decide which [PageIndicator] dots to draw for a
     * page with [PageConfig.hiddenUnlessActivity] set. Deliberately NOT
     * used to filter the pager/pageCount itself; see that field's own doc
     * comment on [PageConfig] for why gating real navigation on this would
     * race a scene_grid tile's own "activity"+"page" combo tap. */
    val activeActivityIds: Set<String>,
    /** Scans pages/hotkeys once per config load for every `"track": true`
     * item; re-scanned automatically whenever `config` itself changes
     * (dashboard.json reload). Bound to each hub's live state via
     * [DashboardActivityRuntimeEffects]. */
    val activityRuntime: ActivityRuntime,
    /** Card-driven navigation: any card can call this with a page name (as
     * it appears in dashboard.json's "pages[].name", case-insensitive) to
     * jump there — same mechanism physical hotkeys use, just triggered by
     * a tap. Uses scrollToPage (instant, no animation) rather than
     * animateScrollToPage: the animated variant visibly scrolls through
     * every intermediate page between the current one and the target,
     * which reads as "the wrong page flashes up" right before the real one
     * lands — especially noticeable on the HA100's weak CPU. A direct jump
     * should land directly. */
    val navigateToPage: (String) -> Unit,
    /** Local IR — the resilience baseline: works fully offline, no hub, no
     * HA, no cloud. Shared by scene_grid's own irDevice/irCommand fields
     * AND by composed Activities' "ir"-sourced devices, so there's exactly
     * one place that touches ConsumerIrManager. */
    val irManager: ConsumerIrManager?,
    val irDevicesById: Map<String, IrDeviceConfig>,
    val activitiesById: Map<String, ActivityConfig>
)

@Composable
private fun rememberDashboardRuntimeState(config: AppConfig, harmonyRegistry: HarmonyHubRegistry): DashboardRuntimeState {
    val harmonyConnected by (harmonyRegistry.client()?.connected ?: remember { MutableStateFlow(false) }).collectAsState()
    val scope = rememberCoroutineScope()
    val coroutineScope = rememberCoroutineScope()

    val activityRuntime = remember(config) { ActivityRuntime(config) }

    // Only feeds PageIndicator's dot visibility (PageConfig.hiddenUnlessActivity)
    // — the pager itself always covers every page in `config.pages`,
    // unfiltered; see that field's doc comment for why.
    val activeByRoom by activityRuntime.activeByRoom.collectAsState()
    val activeActivityIds = remember(activeByRoom) { activeByRoom.values.filterNotNull().toSet() }

    val pageCount = config.pages.size.coerceAtLeast(1)
    val pagerState =
        rememberPagerState(
            initialPage = config.startPage.coerceIn(0, pageCount - 1),
            pageCount = { pageCount }
        )

    val navigateToPage: (String) -> Unit = { pageName ->
        val idx = config.pages.indexOfFirst { it.name.equals(pageName, ignoreCase = true) }
        if (idx >= 0) {
            scope.launch { pagerState.scrollToPage(idx) }
        }
    }

    val androidContext = LocalContext.current
    val irManager =
        remember(androidContext) {
            androidContext.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        }
    val irDevicesById = remember(config.irDevices) { config.irDevices.associateBy { it.id } }
    val activitiesById = activityRuntime.activityConfigs

    return DashboardRuntimeState(
        harmonyConnected = harmonyConnected,
        scope = scope,
        coroutineScope = coroutineScope,
        pagerState = pagerState,
        activeActivityIds = activeActivityIds,
        activityRuntime = activityRuntime,
        navigateToPage = navigateToPage,
        irManager = irManager,
        irDevicesById = irDevicesById,
        activitiesById = activitiesById
    )
}

/**
 * The screen-composition root: wires Home Assistant/Harmony/Extender state,
 * the page pager, Activity dispatch, and hardware-navigation effects into
 * the actual rendered dashboard. Already reduced from a cyclomatic
 * complexity of 62 (→ ~10, via [ActivityDispatcher],
 * [DashboardActivityRuntimeEffects], [rememberDashboardOverlayState],
 * [rememberDashboardRuntimeState], [DashboardContent]) and 17 parameters
 * (→ 6, via the `Dashboard*` bundles above). Length is no longer capped at
 * detekt's default 80 for this project (see `config/detekt/detekt.yml` —
 * LongMethod's line-count heuristic doesn't map well onto Compose's
 * declarative style; CyclomaticComplexMethod/LongParameterList, which
 * measure real complexity/coupling rather than raw lines, are still
 * enforced at their normal thresholds and did catch genuine issues here).
 */
@Composable
fun Dashboard(
    connection: DashboardConnection,
    registries: DashboardRegistries,
    config: AppConfig,
    navigation: DashboardNavigation = DashboardNavigation(),
    uiState: DashboardUiState = DashboardUiState(),
    activityCallbacks: DashboardActivityCallbacks = DashboardActivityCallbacks()
) {
    // Destructured back into their original names immediately below, so
    // the rest of this function's body (unchanged since before these
    // bundles existed) doesn't need touching at all — only the signature
    // needed to shrink for detekt's LongParameterList threshold.
    val client = connection.client
    val entitiesState = connection.entitiesState
    val connectionState = connection.connectionState
    val harmonyRegistry = registries.harmonyRegistry
    val extenderRegistry = registries.extenderRegistry
    val configNotice = uiState.configNotice
    val deviceSettings = uiState.deviceSettings
    val screenOn = uiState.screenOn
    val navTarget = navigation.navTarget
    val onNavHandled = navigation.onNavHandled
    val overlayTarget = navigation.overlayTarget
    val onOverlayHandled = navigation.onOverlayHandled
    val onPageChanged = navigation.onPageChanged
    val volumeHotkeyTrigger = navigation.volumeHotkeyTrigger
    val onActivityRuntimeReady = activityCallbacks.onActivityRuntimeReady
    val onStartActivityReady = activityCallbacks.onStartActivityReady
    val onStopActivityReady = activityCallbacks.onStopActivityReady

    val entities by entitiesState
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        HaLabels.init(context)
    }
    val connection by connectionState
    val theme = remember(config.theme) { config.theme.toColors() }

    val tapFeedback = rememberTapFeedback(deviceSettings.tapFeedbackEnabled)
    ProvideTheme(theme) {
        CompositionLocalProvider(LocalTapFeedback provides tapFeedback) {
            val runtime = rememberDashboardRuntimeState(config, harmonyRegistry)
            val harmonyConnected = runtime.harmonyConnected
            val scope = runtime.scope
            val coroutineScope = runtime.coroutineScope
            val pagerState = runtime.pagerState
            val activeActivityIds = runtime.activeActivityIds
            val activityRuntime = runtime.activityRuntime
            val navigateToPage = runtime.navigateToPage
            val irManager = runtime.irManager
            val irDevicesById = runtime.irDevicesById
            val activitiesById = runtime.activitiesById

            val webhookContext = LocalContext.current
            DashboardActivityRuntimeEffects(
                activityRuntime = activityRuntime,
                harmonyRegistry = harmonyRegistry,
                client = client,
                webhookContext = webhookContext,
                onActivityRuntimeReady = onActivityRuntimeReady
            )

            // Extracted to ActivityDispatcher (below) — this used to be six
            // nested functions here (sendIrCommand, dispatchActivityCommand,
            // dispatchActivityPower, switchActivity, startActivity,
            // stopActivity), which pushed Dashboard() itself over detekt's
            // LongMethod/CyclomaticComplexity thresholds. Logic is unchanged,
            // only where it lives — every call site below keeps the exact
            // same shape it had before (`sendIrCommand`, `startActivity`,
            // `::stopActivity`), just delegating to `dispatcher` now.
            val dispatcher =
                rememberActivityDispatcher(
                    ActivityDispatcherInputs(
                        client = client,
                        harmonyRegistry = harmonyRegistry,
                        extenderRegistry = extenderRegistry,
                        irManager = irManager,
                        irDevicesById = irDevicesById,
                        activitiesById = activitiesById,
                        activityRuntime = activityRuntime,
                        navigateToPage = navigateToPage,
                        scope = scope,
                        extenderScope = coroutineScope
                    )
                )
            val sendIrCommand = dispatcher::sendIrCommand
            val startActivity: (String) -> Unit = dispatcher::startActivity
            val stopActivity = dispatcher::stopActivity
            LaunchedEffect(activityRuntime) {
                onStartActivityReady(startActivity)
                onStopActivityReady(stopActivity)
            }

            // Shared with DashboardContent below (its swipe-up-to-linked-page
            // handler also writes this) — lifted up here rather than kept
            // local to DashboardContent because DashboardEntityPageEffect
            // needs to read AND write it too, and it needs `entities`
            // (only available at this level).
            var linkedJump by remember { mutableStateOf<Pair<String, String>?>(null) }
            // The linked/auto-opened page currently shown as a popup, or
            // null when none is open — shared by three different triggers
            // now (linkedPage swipe-up, below in DashboardContent;
            // DashboardEntityPageEffect's openWhenEntity+openMode="popup"
            // path right here; and any tile's own `ctx.openPagePopup` right
            // below) since only one popup can sensibly be on screen at a
            // time. Lifted to this level, same reasoning as `linkedJump`
            // above: DashboardEntityPageEffect needs to write it and needs
            // `entities` (only available here) — and it has to exist before
            // `ctx` below, which also closes over it.
            var linkedPopupPage by remember { mutableStateOf<LinkedPagePopupState?>(null) }
            // Which entity (if any) is responsible for the CURRENTLY open
            // popup — lets DashboardEntityPageEffect's auto-close only ever
            // dismiss a popup it opened itself, never one opened by hand
            // via linkedPage swipe-up, a tile's openPagePopup (both null),
            // or by a *different* entity.
            var popupOpenedByEntityId by remember { mutableStateOf<String?>(null) }

            // Opens `pageName` as a popup on a direct tile tap — e.g. a
            // scene_grid item with `"page"` + `"pageMode": "popup"`, or a
            // title/subtitle with `"..._pageMode": "popup"` — reusing the
            // exact same `linkedPopupPage` slot as the swipe-up and
            // entity-driven paths above, and the target page's own
            // popupWidthFraction/popupHeightFraction/popupPosition (same
            // fields `onOpenPopup` below reads), so it looks identical
            // regardless of which of the three opened it. A tile popup
            // counts as "opened by hand" for auto-close purposes, same as
            // a swipe-up one: popupOpenedByEntityId stays null.
            val openPagePopup: (String) -> Unit = { pageName ->
                val target = config.pages.firstOrNull { it.name.equals(pageName, ignoreCase = true) }
                if (target != null) {
                    popupOpenedByEntityId = null
                    linkedPopupPage =
                        LinkedPagePopupState(
                            targetPage = target,
                            widthFraction = target.popupWidthFraction,
                            heightFraction = target.popupHeightFraction,
                            position = target.popupPosition
                        )
                }
            }
            // Closes whichever popup is currently open, from any of the
            // three triggers above — used by a tile's own `"closePopup":
            // true` option so it can fire its action (e.g. pick a TV/
            // Projector input) and dismiss the popup it's shown in, in one
            // tap. No-op when nothing is open.
            val closePopup: () -> Unit = {
                popupOpenedByEntityId = null
                linkedPopupPage = null
            }

            val ctx =
                buildCardContext(
                    CardContextInputs(
                        entities = entities,
                        client = client,
                        navigateToPage = navigateToPage,
                        openPagePopup = openPagePopup,
                        closePopup = closePopup,
                        harmonyRegistry = harmonyRegistry,
                        deviceSettings = deviceSettings,
                        harmonyConnected = harmonyConnected,
                        irDevicesById = irDevicesById,
                        sendIrCommand = sendIrCommand,
                        activitiesById = activitiesById,
                        startActivity = startActivity,
                        activityRuntime = activityRuntime,
                        theme = theme,
                        screenOn = screenOn
                    )
                )

            DashboardEntityPageEffect(
                pages = config.pages,
                entities = entities,
                pagerState = pagerState,
                params = EntityPageEffectParams(
                    linkedJump = linkedJump,
                    onLinkedJumpChange = { linkedJump = it },
                    popupOpenedByEntityId = popupOpenedByEntityId,
                    onOpenPopup = { page: PageConfig, entityId: String ->
                        popupOpenedByEntityId = entityId
                        linkedPopupPage =
                            LinkedPagePopupState(
                                targetPage = page,
                                widthFraction = page.popupWidthFraction,
                                heightFraction = page.popupHeightFraction,
                                position = page.popupPosition
                            )
                    },
                    onClosePopup = {
                        popupOpenedByEntityId = null
                        linkedPopupPage = null
                    }
                )
            )

            val overlayState =
                rememberDashboardOverlayState(
                    navTarget = navTarget,
                    onNavHandled = onNavHandled,
                    overlayTarget = overlayTarget,
                    onOverlayHandled = onOverlayHandled,
                    pagerState = pagerState,
                    pageCount = config.pages.size.coerceAtLeast(1)
                )

            DashboardContent(
                DashboardContentInputs(
                    config = config,
                    ctx = ctx,
                    pagerState = pagerState,
                    activeActivityIds = activeActivityIds,
                    scope = scope,
                    connection = connection,
                    configNotice = configNotice,
                    overlayState = overlayState,
                    activityRuntime = activityRuntime,
                    stopActivity = stopActivity,
                    onPageChanged = onPageChanged,
                    webhookContext = webhookContext,
                    client = client,
                    linkedJump = linkedJump,
                    onLinkedJumpChange = { linkedJump = it },
                    volumeHotkeyTrigger = volumeHotkeyTrigger,
                    linkedPopupPage = linkedPopupPage,
                    onLinkedPopupPageChange = { page, entityId ->
                        linkedPopupPage = page
                        popupOpenedByEntityId = entityId
                    }
                )
            )
        }
    }
}

/** Everything [DashboardContent] needs — same bundling-for-detekt reason
 * as [ActivityDispatcherInputs]/[CardContextInputs] above. */
private data class DashboardContentInputs(
    val config: AppConfig,
    val ctx: CardContext,
    val pagerState: PagerState,
    val activeActivityIds: Set<String>,
    val scope: CoroutineScope,
    val connection: ConnectionState,
    val configNotice: String?,
    val overlayState: DashboardOverlayState,
    val activityRuntime: ActivityRuntime,
    val stopActivity: (String) -> Unit,
    val onPageChanged: (Int) -> Unit,
    val webhookContext: Context,
    val client: HaClient,
    /** Shared with [DashboardEntityPageEffect] — see [Dashboard]'s own
     * `linkedJump` declaration for why it's lifted up rather than kept
     * local to this composable. */
    val linkedJump: Pair<String, String>?,
    val onLinkedJumpChange: (Pair<String, String>?) -> Unit,
    val volumeHotkeyTrigger: VolumeHotkeyTrigger?,
    val linkedPopupPage: LinkedPagePopupState?,
    /** Sets (or clears, when both args are null) the shared popup slot —
     * second arg is the entity responsible, null for a manual swipe-up
     * open/close. See [DashboardEntityPageEffect]'s doc comment. */
    val onLinkedPopupPageChange: (LinkedPagePopupState?, String?) -> Unit
)

/** The dynamic "‹ back" target for [PageIndicator]: a page's own static
 * [PageConfig.parent] always wins when set, otherwise whichever page the
 * most recent swipe-up (or [PageConfig.openWhenEntity] auto-open — see
 * [DashboardEntityPageEffect]) into [current] actually came from ([Dashboard]'s
 * own `linkedJump` state) — lets one shared linked page serve several
 * different parents correctly. Pulled out of [DashboardContent] purely to
 * keep that composable under detekt's `CyclomaticComplexity` threshold;
 * not a behavior change. */
private fun resolveBackTargetName(pages: List<PageConfig>, current: Int, linkedJump: Pair<String, String>?): String? {
    val currentPageConfig = pages.getOrNull(current)
    return currentPageConfig?.parent
        ?: linkedJump?.let { (target, source) -> source.takeIf { currentPageConfig?.name == target } }
}

/** Case-insensitive index of the page named [name] in [pages], or null if
 * unset/not found. Shared lookup behind [jumpToPageByName] and
 * [DashboardEntityPageEffect]. */
private fun pageIndexNamed(pages: List<PageConfig>, name: String?): Int? =
    name?.let { n -> pages.indexOfFirst { it.name.equals(n, ignoreCase = true) } }?.takeIf { it >= 0 }

/** Finds [name] in [pages] (case-insensitive) and jumps [pagerState] there,
 * no-op if it isn't found — the shared "scroll to a page by name" used by
 * [PageIndicator]'s chevron tap and swipe-up-to-linked-page. Pulled out of
 * [DashboardContent] for the same `CyclomaticComplexity` reason as
 * [resolveBackTargetName]. */
private fun jumpToPageByName(pages: List<PageConfig>, name: String?, scope: CoroutineScope, pagerState: PagerState) {
    val idx = pageIndexNamed(pages, name) ?: return
    scope.launch { pagerState.scrollToPage(idx) }
}

/** Whether [state] should trigger auto-opening [page] — see
 * [PageConfig.openWhenEntity]. */
private fun entityPageOpens(page: PageConfig, state: String?): Boolean = state == page.openWhenState

/** Whether [state] should trigger auto-closing [page] — [PageConfig.closeWhenState]
 * if set, else any state other than [PageConfig.openWhenState] (the simple
 * on/off default). See that field's own doc comment for why an explicit
 * value matters for anything with more than two meaningful states. */
private fun entityPageCloses(page: PageConfig, state: String?): Boolean =
    page.closeWhenState?.let { state == it } ?: (state != page.openWhenState)

/**
 * Watches every page's [PageConfig.openWhenEntity] against live entity
 * state and drives the pager accordingly — a switch turning "on" pops open
 * its page, turning back off (or reaching [PageConfig.closeWhenState] when
 * set) pops back to [resolveBackTargetName] (the same dynamic back-target
 * [linkedJump] powers for [PageConfig.linkedPage], so a page reached this
 * way closes the same way a linked one does: static [PageConfig.parent] if
 * set, else wherever the pager was before this fired).
 *
 * `autoOpenedFor` exists so this only acts on the *transition* into
 * [PageConfig.openWhenState], not on every recomposition while it holds:
 * without it, manually swiping away from an auto-opened page while its
 * entity is still matching would just get immediately reopened on the
 * next entity update, fighting the person right back to the page they
 * just left. It's cleared the moment [entityPageCloses] matches, so the
 * next open-transition can fire again.
 */
/** What [DashboardEntityPageEffect] should do for one page on this tick —
 * pulled out to a pure function so the composable itself stays a simple
 * `when` over the result, for the same `CyclomaticComplexity` reason as
 * everything else extracted out of [DashboardContent]. */
private enum class EntityPageAction { NONE, OPEN_POPUP, CLOSE_POPUP, OPEN_PAGE, CLOSE_PAGE }

private data class EntityPageEffectParams(
    val linkedJump: Pair<String, String>?,
    val onLinkedJumpChange: (Pair<String, String>?) -> Unit,
    val popupOpenedByEntityId: String?,
    val onOpenPopup: (PageConfig, String) -> Unit,
    val onClosePopup: () -> Unit
)

private fun resolveEntityPageAction(
    page: PageConfig,
    opens: Boolean,
    closes: Boolean,
    isCurrent: Boolean,
    alreadyAutoOpened: Boolean,
    popupOpenedByThisEntity: Boolean
): EntityPageAction {
    if (page.openMode == "popup") {
        return when {
            opens && !alreadyAutoOpened -> EntityPageAction.OPEN_POPUP
            closes && popupOpenedByThisEntity -> EntityPageAction.CLOSE_POPUP
            else -> EntityPageAction.NONE
        }
    } else {
        return when {
            opens && !isCurrent && !alreadyAutoOpened -> EntityPageAction.OPEN_PAGE
            closes && isCurrent -> EntityPageAction.CLOSE_PAGE
            else -> EntityPageAction.NONE
        }
    }
}

@Composable
private fun DashboardEntityPageEffect(
    pages: List<PageConfig>,
    entities: EntityMap,
    pagerState: PagerState,
    params: EntityPageEffectParams
) {
    var autoOpenedFor by remember { mutableStateOf<Set<String>>(emptySet()) }
    LaunchedEffect(entities, pagerState.currentPage) {
        val currentName = pages.getOrNull(pagerState.currentPage)?.name
        pages.forEachIndexed { index, page ->
            val entityId = page.openWhenEntity ?: return@forEachIndexed
            val state = entities[entityId]?.state
            val opens = entityPageOpens(page, state)
            val closes = entityPageCloses(page, state)
            val isCurrent = currentName == page.name
            if (closes) autoOpenedFor = autoOpenedFor - entityId

            when (
                resolveEntityPageAction(
                    page = page,
                    opens = opens,
                    closes = closes,
                    isCurrent = isCurrent,
                    alreadyAutoOpened = entityId in autoOpenedFor,
                    popupOpenedByThisEntity = params.popupOpenedByEntityId == entityId
                )
            ) {
                EntityPageAction.OPEN_POPUP -> {
                    autoOpenedFor = autoOpenedFor + entityId
                    params.onOpenPopup(page, entityId)
                }
                EntityPageAction.CLOSE_POPUP -> params.onClosePopup()
                EntityPageAction.OPEN_PAGE -> {
                    autoOpenedFor = autoOpenedFor + entityId
                    params.onLinkedJumpChange(page.name to (currentName ?: page.name))
                    pagerState.scrollToPage(index)
                }
                EntityPageAction.CLOSE_PAGE -> {
                    val backTarget = resolveBackTargetName(pages, pagerState.currentPage, params.linkedJump)
                    pageIndexNamed(pages, backTarget)?.let { pagerState.scrollToPage(it) }
                }
                EntityPageAction.NONE -> {}
            }
        }
    }
}

/**
 * The page-change effect (tells MainActivity which page is visible now, so
 * it can rebind hardware hotkeys to that page's own bindings — swipe, dot
 * tap, hardware nav, or a card's navigateToPage all funnel through
 * `pagerState.currentPage` — plus the same page-change webhook push
 * pattern [DashboardActivityRuntimeEffects] uses for activity changes) and
 * the actual page/overlay rendering tree — extracted out of [Dashboard]
 * for the same LongMethod/CyclomaticComplexity reasons as everything else
 * moved out of it, logic unchanged.
 */
@Composable
private fun DashboardContent(inputs: DashboardContentInputs) {
    val ctx = inputs.ctx
    val pagerState = inputs.pagerState
    val scope = inputs.scope
    val connection = inputs.connection
    val configNotice = inputs.configNotice
    val overlayState = inputs.overlayState
    val activityRuntime = inputs.activityRuntime
    val stopActivity = inputs.stopActivity
    val onPageChanged = inputs.onPageChanged
    val webhookContext = inputs.webhookContext
    val client = inputs.client
    val config = inputs.config
    val activeActivityIds = inputs.activeActivityIds
    val linkedJump = inputs.linkedJump
    val onLinkedJumpChange = inputs.onLinkedJumpChange
    val volumeHotkeyTrigger = inputs.volumeHotkeyTrigger
    val linkedPopupPage = inputs.linkedPopupPage
    val onLinkedPopupPageChange = inputs.onLinkedPopupPageChange

    LaunchedEffect(pagerState.currentPage) {
        onPageChanged(pagerState.currentPage)
        val webhookId = RemoteSettings.haWebhookId(webhookContext)
        if (webhookId.isNotBlank()) {
            val page = config.pages.getOrNull(pagerState.currentPage)
            client.pushWebhook(
                webhookId,
                buildJsonObject {
                    put("type", "page")
                    put("index", pagerState.currentPage)
                    put("name", page?.name ?: "")
                }
            )
        }
    }

    // The linked/auto-opened page currently shown as a popup — see
    // Dashboard()'s own doc comment on this state, lifted up there so
    // DashboardEntityPageEffect can share the same slot.

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .background(LocalTheme.current.background)
        ) {
            // Swipe DOWN from the top bar now opens "Activities" (moved
            // here from the bottom-indicator swipe-up below) — Settings is
            // now just a regular assignable hotkey (openOverlay: "settings"),
            // see the Hotkeys tab.
            TopStatusBar(onSwipeDown = { overlayState.onShowActivitiesChange(true) })
            ConnectionBanner(connection)
            if (configNotice != null) ConfigNoticeBanner(configNotice)

            HorizontalPager(
                state = pagerState,
                modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) { pageIndex ->
                PageContent(config.pages[pageIndex], ctx)
            }

            val backTargetName = resolveBackTargetName(config.pages, pagerState.currentPage, linkedJump)

            PageIndicator(
                pages = config.pages,
                current = pagerState.currentPage,
                activeActivityIds = activeActivityIds,
                backTargetName = backTargetName,
                // Same instant scrollToPage as navigateToPage/hardware nav —
                // a dot tap is a direct jump too, not a swipe gesture, so it
                // shouldn't visibly scroll through pages in between.
                onDotClick = { index -> scope.launch { pagerState.scrollToPage(index) } },
                onNavigateToParent = { jumpToPageByName(config.pages, backTargetName, scope, pagerState) },
                // Swipe UP now jumps to this page's own `linkedPage` (a
                // per-card "more options" page, e.g. Apple TV's extra
                // controls) instead of opening the Activities overlay —
                // that moved to the top bar's swipe-down above. A page
                // with no `linkedPage` set simply does nothing on swipe-up,
                // same as a page with no `parent`/remembered source does
                // nothing on BACK.
                onSwipeUpToLinkedPage = {
                    val current = config.pages.getOrNull(pagerState.currentPage)
                    if (current != null) {
                        val linkedName = current.linkedPage
                        val linkedPageConfig = linkedName?.let { n -> config.pages.firstOrNull { it.name.equals(n, ignoreCase = true) } }
                        if (current.linkedPageMode == "popup" && linkedPageConfig != null) {
                            onLinkedPopupPageChange(
                                LinkedPagePopupState(
                                    targetPage = linkedPageConfig,
                                    widthFraction = current.popupWidthFraction,
                                    heightFraction = current.popupHeightFraction,
                                    position = current.popupPosition
                                ),
                                null
                            )
                        } else {
                            if (linkedName != null) onLinkedJumpChange(linkedName to current.name)
                            jumpToPageByName(config.pages, linkedName, scope, pagerState)
                        }
                    }
                }
            )
        }

        if (overlayState.showSettings) {
            SettingsOverlay(ctx = ctx, onClose = { overlayState.onShowSettingsChange(false) })
        }
        if (overlayState.showActivities) {
            ActivitiesOverlay(
                activityRuntime = activityRuntime,
                ctx = ctx,
                onStop = stopActivity,
                onClose = { overlayState.onShowActivitiesChange(false) }
            )
        }
        linkedPopupPage?.let { popup ->
            LinkedPagePopup(state = popup, ctx = ctx, onClose = { onLinkedPopupPageChange(null, null) })
        }
        VolumePopupHost(trigger = volumeHotkeyTrigger, ctx = ctx)
    }
}

/**
 * Full-screen settings overlay, reached only by swiping down from the top
 * edge (see [TopStatusBar]) — deliberately NOT part of `config.pages`, so it
 * never shows up in the horizontal pager or the page-indicator dots.
 * Dismissed by an upward swipe from the bottom gesture strip, the system
 * back button, or the close row.
 *
 * The swipe-up-to-close gesture lives on a dedicated bottom strip that sits
 * above the scrollable content in z-order. This avoids the gesture conflict
 * between `detectVerticalDragGestures` and `verticalScroll` when both are on
 * the same node — the scroll consumer eats all vertical drags before the drag
 * detector ever fires.
 */
@Composable
private fun SettingsOverlay(ctx: CardContext, onClose: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().background(LocalTheme.current.background)) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "✕ " + stringResource(R.string.close),
                    color = LocalTheme.current.mutedText,
                    fontSize = 13.sp,
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .tapClickable { onClose() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            SettingsMenu(ctx)
        }
        // Bottom gesture strip: swipe up to close. Sits above the scrollable
        // content so the drag detector doesn't fight verticalScroll for events.
        // A visual handle bar cues the user where to swipe.
        Box(
            modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(50.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        if (dragAmount < -15f) onClose()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LocalTheme.current.controlBackground)
            )
        }
    }
}

/**
 * Full-screen overlay listing every currently-active AV Activity, grouped by
 * room — reached by swiping DOWN from the top edge (see [TopStatusBar]),
 * the same discoverable spot Settings used to live on (Settings is now
 * just a regular assignable hotkey — `openOverlay: "settings"` — see the
 * Hotkeys tab).
 * Dismissed by a downward swipe from the top gesture strip, the system back
 * button, or the close row (see its doc comment on [SettingsOverlay]'s
 * mirror-image bottom strip for why the gesture lives on its own node
 * rather than on the scrollable Column). Tapping an Activity jumps to its
 * page — the "CURRENT_ACTIVITY" one-tap-back behaviour from the original
 * design discussion.
 */
@Composable
private fun ActivitiesOverlay(
    activityRuntime: ActivityRuntime,
    ctx: CardContext,
    /** Stops the Activity active in a given room — see Dashboard()'s own
     * `stopActivity`. Separate from `onClose`: stopping doesn't dismiss the
     * overlay, so more than one room can be stopped in a row. */
    onStop: (room: String) -> Unit,
    onClose: () -> Unit
) {
    val activeByRoom by activityRuntime.activeByRoom.collectAsState()
    val active = remember(activeByRoom) { activityRuntime.activeActivities() }

    Box(modifier = Modifier.fillMaxSize().background(LocalTheme.current.background)) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                Text(
                    "✕ " + stringResource(R.string.close),
                    color = LocalTheme.current.mutedText,
                    fontSize = 13.sp,
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(10.dp))
                        .tapClickable { onClose() }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            Text(
                stringResource(R.string.active_activities),
                color = LocalTheme.current.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 10.dp)
            )
            if (active.isEmpty()) {
                Text(
                    stringResource(R.string.no_active_activities),
                    color = LocalTheme.current.mutedText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(10.dp)
                )
            }
            active.groupBy { it.room }.forEach { (room, activities) ->
                Text(
                    room,
                    color = LocalTheme.current.accent,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
                activities.forEach { activity ->
                    Row(
                        modifier =
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(LocalTheme.current.insetSurface)
                            .tapClickable(enabled = activity.page != null) {
                                activity.page?.let {
                                    ctx.navigateToPage(it)
                                    onClose()
                                }
                            }.padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(activity.name, color = LocalTheme.current.primaryText, fontSize = 15.sp)
                        // Dedicated per-room stop — the missing piece this
                        // overlay didn't have before: previously the only
                        // way to end a classic Harmony Activity was a
                        // generic PowerOff hotkey, which (when a hub drives
                        // more than one room) kills every room on that hub
                        // instead of just this one. stopActivity() targets
                        // only this Activity's own hub.
                        Text(
                            stringResource(R.string.stop_activity),
                            color = LocalTheme.current.danger,
                            fontSize = 13.sp,
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .tapClickable { onStop(room) }
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }
        }
        // Bottom gesture strip: swipe up to close — now mirrors
        // SettingsOverlay's own strip exactly, since this overlay moved
        // onto the same "swipe down from the top to open" gesture Settings
        // used to use (see TopStatusBar's wiring in DashboardContent);
        // closing pulls it back up the way it came in.
        Box(
            modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(50.dp)
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        if (dragAmount < -15f) onClose()
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier =
                Modifier
                    .width(40.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(LocalTheme.current.controlBackground)
            )
        }
    }
}

/**
 * [LinkedPagePopup]'s params, bundled because they come from two different
 * pages: [targetPage] is the linked page being *shown* (its cards/name are
 * the popup's content), while [widthFraction]/[heightFraction]/[position]
 * are the *source* page's own [PageConfig.popupWidthFraction] /
 * [PageConfig.popupHeightFraction] / [PageConfig.popupPosition] — the page
 * that actually declared `linkedPageMode: "popup"` in the first place, not
 * the one it points to. Getting this backwards (reading the geometry off
 * [targetPage] instead) was a real bug: every popup silently used the
 * default 0.7/0.5/"center" regardless of what the *source* page configured,
 * since the target page it was mistakenly read from generally has no popup
 * settings of its own.
 */
private data class LinkedPagePopupState(
    val targetPage: PageConfig,
    val widthFraction: Float,
    val heightFraction: Float,
    val position: String
)

/**
 * Floating popup counterpart of the full-page swipe-up-to-linked-page jump —
 * used when the current page's [PageConfig.linkedPageMode] is `"popup"`
 * instead of the default `"page"`. Renders [LinkedPagePopupState.targetPage]'s
 * own cards via [PageContent] inside a sized/positioned box floating over
 * whatever page is actually on screen — the pager itself is never touched,
 * so this is purely cosmetic on top of the existing page, not a real
 * navigation.
 *
 * Dismissed by tapping the dimmed backdrop or the close row; a tap inside
 * the popup box itself is swallowed so it doesn't fall through to the
 * backdrop's dismiss handler.
 */
@Composable
private fun LinkedPagePopup(state: LinkedPagePopupState, ctx: CardContext, onClose: () -> Unit) {
    val alignment =
        when (state.position) {
            "top" -> Alignment.TopCenter
            "bottom" -> Alignment.BottomCenter
            "left" -> Alignment.CenterStart
            "right" -> Alignment.CenterEnd
            else -> Alignment.Center
        }

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .tapClickable(onClick = onClose),
        contentAlignment = alignment
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth(state.widthFraction)
                .fillMaxHeight(state.heightFraction)
                .padding(16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(LocalTheme.current.cardSurface)
                // Swallows taps so they don't reach the backdrop behind it.
                .tapClickable(onClick = {})
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        state.targetPage.name,
                        color = LocalTheme.current.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        "✕",
                        color = LocalTheme.current.mutedText,
                        fontSize = 15.sp,
                        modifier =
                        Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .tapClickable { onClose() }
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
                PageContent(state.targetPage, ctx)
            }
        }
    }
}

/**
 * Temporary volume readout popped up by a VOLUME_UP/VOLUME_DOWN/MUTE hotkey
 * (see MainActivity.runHotkey / [DashboardNavigation.volumeHotkeyTrigger]) —
 * shows the target entity's current level for a couple of seconds, then
 * disappears on its own. Unlike [LinkedPagePopup] or the other overlays,
 * this doesn't dim the screen or intercept taps at all — it's meant to be
 * glanced at while you keep pressing the physical button, not interacted
 * with.
 *
 * Home Assistant's `media_player.volume_level` is always a normalized 0–1
 * float, never decibels — an AV receiver integration doesn't generally
 * expose its own dB scale as a separate queryable attribute — so this
 * shows a 0–100% bar rather than a dB readout; there's no generic HA
 * attribute this could read a real dB value from.
 */
@Composable
private fun VolumePopupHost(trigger: VolumeHotkeyTrigger?, ctx: CardContext) {
    var visible by remember { mutableStateOf<VolumeHotkeyTrigger?>(null) }
    LaunchedEffect(trigger) {
        if (trigger == null) return@LaunchedEffect
        visible = trigger
        delay(2200)
        // Nothing else can have changed `visible` out from under us: a
        // newer trigger would have cancelled/relaunched this very
        // coroutine (LaunchedEffect(trigger)) before this delay finished.
        visible = null
    }
    val shown = visible ?: return
    val e = ctx.entities[shown.entityId] ?: return
    val level = e.attrDouble("volume_level")?.toFloat()?.coerceIn(0f, 1f)
    val muted = e.attrBoolean("is_volume_muted") == true

    Box(
        modifier = Modifier.fillMaxSize().padding(bottom = 60.dp),
        contentAlignment = Alignment.BottomCenter
    ) {
        Row(
            modifier =
            Modifier
                .clip(RoundedCornerShape(30.dp))
                .background(ctx.theme.cardSurface.copy(alpha = 0.95f))
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                if (muted) MdiIcons.VolumeOff else MdiIcons.VolumeHigh,
                contentDescription = null,
                tint = ctx.theme.accent,
                modifier = Modifier.size(22.dp)
            )
            Spacer(Modifier.width(12.dp))
            Box(
                modifier =
                Modifier
                    .width(140.dp)
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(ctx.theme.controlBackground)
            ) {
                if (level != null) {
                    Box(
                        modifier =
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(level.coerceAtLeast(0.02f))
                            .clip(RoundedCornerShape(3.dp))
                            .background(if (muted) ctx.theme.mutedText else ctx.theme.accent)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Text(
                if (muted) stringResource(R.string.volume_muted) else level?.let { "${(it * 100).toInt()}%" } ?: "—",
                color = ctx.theme.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }
    }
}

@Composable
private fun PageContent(page: PageConfig, ctx: CardContext) {
    val pinned = page.cards.filter { it.options["pin"] == "bottom" }
    val scrolling = page.cards.filter { it.options["pin"] != "bottom" }

    Column(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier =
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(scrolling, key = { it.hashCode() }) { RenderCard(it, ctx) }
        }
        if (pinned.isNotEmpty()) {
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .background(LocalTheme.current.insetSurface)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                pinned.forEach { RenderCard(it, ctx) }
            }
        }
    }
}

/**
 * Wraps a single card's [CardRenderer.Render] in a Box that draws a
 * theme.accent outline around the *whole card* while the D-pad focus is
 * anywhere inside it — not just around whichever individual button/tile
 * has focus (that's [tapClickable]'s own job). With several cards stacked
 * on one page, this is what answers "which card am I on" while navigating.
 *
 * `hasFocus` (as opposed to `isFocused`) is true for this Box both when it
 * is itself focused and when any descendant is — Compose's focus system
 * bubbles that up through the layout tree automatically, so this doesn't
 * need each card's own composable to opt in or report anything.
 */
@Composable
private fun RenderCard(cardConfig: CardConfig, ctx: CardContext) {
    val renderer = CardRegistry.get(cardConfig.type)
    if (renderer == null) {
        UnknownCard(cardConfig.type)
        return
    }
    var hasFocus by remember { mutableStateOf(false) }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .onFocusChanged { hasFocus = it.hasFocus }
            .then(
                if (hasFocus) {
                    Modifier.border(2.dp, ctx.theme.accent, RoundedCornerShape(18.dp))
                } else {
                    Modifier
                }
            )
    ) {
        renderer.Render(cardConfig, ctx)
    }
}

/**
 * Row of page dots + current page name at the bottom of the screen —
 * doubles as the swipe-UP trigger for the current page's own
 * [PageConfig.linkedPage] (e.g. an Apple TV card's "more options" page),
 * the bottom-edge mirror of [TopStatusBar]'s swipe gesture (which now opens
 * the Active Activities overlay instead of Settings — see
 * [DashboardContent]'s TopStatusBar wiring). Same accumulated-drag-past-a-
 * threshold approach, just the opposite sign. A page with no `linkedPage`
 * simply does nothing on swipe-up.
 *
 * The dots represent the *current page's siblings* — every page sharing
 * the same [PageConfig.parent] (including root pages, which all share the
 * implicit `parent == null`) — not the whole flat page list. On a
 * dashboard with no hierarchy at all every page shares `parent == null`,
 * so every page is a sibling of every other one and this renders exactly
 * as it did before parent/child pages existed: one dot per page, no
 * chevron. Windowed to at most [MAX_VISIBLE_DOTS] around the current
 * position so a page with many siblings never grows this row's height.
 *
 * The chevron on the left only appears on a child page (one with a
 * non-null `parent`) and is the on-screen twin of the hardware BACK key's
 * new behavior (see PageConfig.parent's own doc comment): tap it, or press
 * BACK, to jump straight to this page's parent. Swiping itself is
 * unaffected by hierarchy — it's still one continuous pager over the full
 * flat `pages` list in file order, same as always; only the dots and the
 * chevron change to reflect where you are in the tree.
 */
private const val MAX_VISIBLE_DOTS = 5

/** Whether [page] (found at [index] in the full page list) should get a dot
 * in [PageIndicator] right now: always true for the page actually on
 * screen, otherwise gated by [PageConfig.hiddenUnlessActivity] against
 * [activeActivityIds] — see that field's own doc comment. Pulled out of
 * [PageIndicator] itself purely to keep that composable under detekt's
 * `CyclomaticComplexity` threshold; not a behavior change. */
private fun isDotVisible(index: Int, page: PageConfig, current: Int, activeActivityIds: Set<String>): Boolean =
    index == current || page.hiddenUnlessActivity == null || page.hiddenUnlessActivity in activeActivityIds

@Composable
private fun PageIndicator(
    pages: List<PageConfig>,
    current: Int,
    onDotClick: (Int) -> Unit,
    onNavigateToParent: () -> Unit,
    onSwipeUpToLinkedPage: () -> Unit,
    /** ids of every [ActivityConfig] currently active anywhere — a page
     * with [PageConfig.hiddenUnlessActivity] set gets no dot unless its id
     * is in here. See that field's doc comment for why this is dots-only:
     * the underlying [pages]/[current] indices are never filtered, so a
     * dot being hidden never affects [onDotClick]/[onNavigateToParent]/
     * [onSwipeUpToLinkedPage]'s own targets. */
    activeActivityIds: Set<String>,
    /** Name to show in the "‹ back" chevron, or null to hide it — the
     * caller resolves this (config `parent`, falling back to wherever the
     * most recent swipe-up came from; see its own computation for why),
     * NOT necessarily `pages[current].parent` verbatim. */
    backTargetName: String?
) {
    val density = LocalDensity.current
    val triggerPx = with(density) { 40.dp.toPx() }
    var dragAccumulated by remember { mutableFloatStateOf(0f) }
    // `.pointerInput(Unit)` below deliberately never re-keys (restarting it on
    // every recomposition would risk cutting off a drag mid-gesture), so its
    // suspend block only ever captures the FIRST `onSwipeUpToLinkedPage` it's
    // ever given — meaning a fresh dashboard.json reload (new linkedPage/
    // linkedPageMode/popup* on the current page) would otherwise silently do
    // nothing until the app was fully restarted, since PageIndicator itself
    // stays mounted (and thus never re-captures anything) across reloads.
    // rememberUpdatedState keeps the long-lived coroutine but always resolves
    // through to whatever the latest recomposition actually passed in.
    val currentOnSwipeUpToLinkedPage = rememberUpdatedState(onSwipeUpToLinkedPage)

    val currentPage = pages.getOrNull(current)
    // Broader than "does any page set a static parent": a page with no
    // parent of its own can still show a dynamic backTargetName (see the
    // caller) if it's ever reached as someone else's linkedPage, so the
    // spacer reservation below has to account for that too, or dots would
    // drift sideways the first time that dynamic chevron actually appears.
    val hasHierarchy = remember(pages) { pages.any { it.parent != null || it.linkedPage != null } }
    val siblings =
        remember(pages, currentPage?.parent, current, activeActivityIds) {
            pages.withIndex().filter { (index, p) ->
                p.parent == currentPage?.parent && isDotVisible(index, p, current, activeActivityIds)
            }
        }
    val currentSiblingPos = siblings.indexOfFirst { it.index == current }.coerceAtLeast(0)

    val windowStart: Int
    val windowEnd: Int
    if (siblings.size <= MAX_VISIBLE_DOTS) {
        windowStart = 0
        windowEnd = siblings.lastIndex
    } else {
        val half = MAX_VISIBLE_DOTS / 2
        val centeredStart = (currentSiblingPos - half).coerceAtLeast(0)
        windowStart = centeredStart.coerceAtMost(siblings.size - MAX_VISIBLE_DOTS)
        windowEnd = windowStart + MAX_VISIBLE_DOTS - 1
    }

    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp, horizontal = 10.dp)
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragStart = { dragAccumulated = 0f },
                    onDragEnd = { dragAccumulated = 0f },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulated += dragAmount
                        if (dragAccumulated < -triggerPx) {
                            currentOnSwipeUpToLinkedPage.value()
                            dragAccumulated = 0f
                        }
                    }
                )
            },
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left zone: only shown when there's somewhere to go back to
        // (config `parent`, or wherever a swipe-up here came from — see
        // backTargetName's own computation). A fixed-width spacer on other
        // pages keeps the dots visually centered instead of drifting
        // sideways as you move between a page with a back target and one
        // without.
        if (backTargetName != null) {
            Row(
                modifier =
                Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .tapClickable { onNavigateToParent() }
                    .defaultMinSize(minWidth = 44.dp, minHeight = 44.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("‹", color = LocalTheme.current.accent, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.width(4.dp))
                Text(
                    backTargetName,
                    color = LocalTheme.current.accent,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        } else if (hasHierarchy) {
            Spacer(Modifier.width(44.dp))
        }

        // Center zone: the windowed sibling dots.
        Row(
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (windowStart > 0) EdgeEllipsis()
            for (i in windowStart..windowEnd) {
                val sibling = siblings[i]
                val active = i == currentSiblingPos
                Box(
                    modifier =
                    Modifier
                        .padding(horizontal = 5.dp)
                        .size(if (active) 10.dp else 8.dp)
                        .clip(CircleShape)
                        .background(if (active) LocalTheme.current.accent else LocalTheme.current.controlBackground)
                        .tapClickable(focusShape = CircleShape) { onDotClick(sibling.index) }
                )
            }
            if (windowEnd < siblings.lastIndex) EdgeEllipsis()
        }

        // Right zone: current page name — unchanged from before hierarchy
        // existed, deliberately not repeating the parent name (the left
        // zone already owns that) to avoid saying it twice in one row.
        Text(
            text = currentPage?.name ?: "",
            color = LocalTheme.current.mutedText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** A small "there are more siblings this way" marker at a clipped edge of
 * the dot window — text rather than a dot so it can never be mistaken for
 * a page itself. Not clickable; swipe (or the chevron, for the parent) is
 * how you get past the visible window. */
@Composable
private fun EdgeEllipsis() {
    Text("…", color = LocalTheme.current.mutedText, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 2.dp))
}

@Composable
private fun ConnectionBanner(connection: ConnectionState) {
    if (connection == ConnectionState.CONNECTED) return
    val (label, color) =
        when (connection) {
            ConnectionState.CONNECTING,
            ConnectionState.AUTHENTICATING
            -> stringResource(R.string.connection_connecting) to LocalTheme.current.accentSecondary
            ConnectionState.AUTH_FAILED -> stringResource(R.string.connection_auth_failed) to LocalTheme.current.danger
            ConnectionState.ERROR -> stringResource(R.string.connection_error_retrying) to LocalTheme.current.danger
            else -> stringResource(R.string.disconnected) to LocalTheme.current.controlBackground
        }
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(color)
            .padding(12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(text = label, color = Color.White, fontSize = 14.sp)
    }
}

@Composable
private fun ConfigNoticeBanner(text: String) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(LocalTheme.current.amber.copy(alpha = 0.25f))
            .padding(10.dp)
    ) {
        Text(text = text, color = LocalTheme.current.amber, fontSize = 12.sp)
    }
}

@Composable
private fun UnknownCard(type: String) {
    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .background(LocalTheme.current.cardSurface)
            .padding(14.dp)
    ) {
        Text(text = stringResource(R.string.unknown_card_type, type), color = LocalTheme.current.danger, fontSize = 13.sp)
    }
}
