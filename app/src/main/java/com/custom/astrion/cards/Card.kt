package com.custom.astrion.cards

import androidx.compose.runtime.Composable
import com.custom.astrion.config.ActivityConfig
import com.custom.astrion.config.ActivityRuntime
import com.custom.astrion.config.IrDeviceConfig
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.ThemeColors

/**
 * THE EXTENSIBILITY CORE.
 *
 * A card in your dashboard config is defined as:
 * `{ type: "<your-type>", <arbitrary options...> }`.
 * `CardConfig.options` passes these options straight to your renderer.
 */

/** One card entry from your dashboard layout config. */
data class CardConfig(
    val type: String,
    /** Free-form per-card options. Your renderer decides how to read these. */
    val options: Map<String, Any?> = emptyMap()
) {
    fun string(key: String): String? = options[key] as? String

    fun stringList(key: String): List<String> = (options[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()

    fun bool(key: String, default: Boolean = false): Boolean = options[key] as? Boolean ?: default

    fun int(key: String, default: Int = 0): Int = (options[key] as? Number)?.toInt() ?: default
}

/**
 * The device-level toggles shown on the settings page, bundled into one
 * value instead of 8 separate constructor params on both [CardContext] and
 * `Dashboard()` — the settings page reads/writes these as a group anyway
 * (see SettingsMenu.kt's WakeOnMotionRow/WifiKeepAwakeRow/ConfigServerRow/
 * TapFeedbackRow), no card renderer needs any of them individually.
 */
data class DeviceSettingsState(
    /** Current state of the motion-wake feature, and a way to toggle it —
     * used by the settings page (mirrors HaRemote's "Wake on movement" switch). */
    val wakeOnMotionEnabled: Boolean = true,
    val setWakeOnMotionEnabled: (Boolean) -> Unit = {},
    /** Current state of the Wi-Fi-keep-awake feature, and a way to toggle it
     * — used by the settings page. Off by default: it disables the Wi-Fi
     * radio's own power-save the whole time it's held, a real continuous
     * battery cost on a device that's mostly idle/screen-off. Fixes Home
     * Assistant intermittently failing to reach this device (the
     * astrion.set_page/start_activity services, or the push-webhook
     * feature) while the screen's off — worth it only if you actually rely
     * on either while the screen would otherwise be off. */
    val wifiKeepAwakeEnabled: Boolean = false,
    val setWifiKeepAwakeEnabled: (Boolean) -> Unit = {},
    /** Current state of the local :8080 config/builder server, and a way to
     * toggle it — used by the settings page. Left running by default; once a
     * device is fully set up, turning it off closes an unauthenticated LAN
     * admin surface (connection settings, dashboard.json, icon uploads) that
     * has no further reason to stay open. */
    val configServerEnabled: Boolean = true,
    val setConfigServerEnabled: (Boolean) -> Unit = {},
    /** Current state of the tap-feedback feature, and a way to toggle it —
     * used by the settings page. When on, tappable elements emit the same
     * little "tap" notice the device's own Android UI menus do (via
     * [com.custom.astrion.ui.LocalTapFeedback] + Modifier.tapClickable);
     * when off they stay silent. See TapFeedback.kt for details. */
    val tapFeedbackEnabled: Boolean = true,
    val setTapFeedbackEnabled: (Boolean) -> Unit = {}
)

/**
 * Context handed to every card render.
 *
 * Gives the card read access to live entity states and service calls.
 * It also enables navigation between pages (e.g. tapping an item to open a page).
 */
// This is THE EXTENSIBILITY CORE (see the file's own top comment) — every
// card renderer reads its capabilities as flat `ctx.xxx` properties, and
// all but the first two already have safe no-op/empty defaults specifically
// so existing cards/tests keep compiling as new ones are added over time.
// Bundling the less-central ones into a sub-object (the usual fix for
// detekt's LongParameterList elsewhere in this codebase — see
// CardContextInputs/ActivityDispatcherInputs) would mean rewriting every
// `ctx.xxx` access across every CardRenderer for a purely internal lint
// threshold, so this is suppressed here instead. Note the explicit
// `constructor` keyword below: detekt's LongParameterList reports on the
// primary constructor itself, and in Kotlin `@Suppress` only attaches to
// it via that keyword — `@Suppress(...) class CardContext(` (annotating
// the class, no `constructor`) does NOT suppress it.
class CardContext
@Suppress("LongParameterList")
constructor(
    val entities: EntityMap,
    val client: HaClient,
    /** No-op default so existing cards/tests that don't pass this keep working. */
    val navigateToPage: (String) -> Unit = {},
    /** Opens `pageName` (matched case-insensitively against
     * `dashboard.json`'s `pages[].name`, same as [navigateToPage]) as a
     * floating popup over whatever page is currently on screen, instead of
     * navigating the pager itself. Uses that page's own
     * `popupWidthFraction`/`popupHeightFraction`/`popupPosition` — the same
     * floating overlay a `linkedPage` swipe-up or an `openWhenEntity` with
     * `openMode: "popup"` already use, just triggered here by a direct tap
     * on any tile instead. No-op if `pageName` isn't found, and a no-op
     * default so existing cards/tests that don't pass this keep working. */
    val openPagePopup: (String) -> Unit = {},
    /** Closes whichever popup is currently shown (however it was opened —
     * [openPagePopup], a `linkedPage` swipe-up, or `openWhenEntity`), if
     * any; no-op when none is open. Lets a tile INSIDE a popup (e.g. a
     * TV/Projector source picker) fire its own action and then dismiss the
     * popup it's showing in, in the same tap — see each card's own
     * `closePopup` option. No-op default, same reasoning as
     * [openPagePopup]. */
    val closePopup: () -> Unit = {},
    /** Starts a Harmony Activity directly on a hub (bypasses HA). `hub` is a
     * HarmonyHubConfig.localId; null/blank falls back to the first configured hub. */
    val startHarmonyActivity: (activityId: String, hub: String?) -> Unit = { _, _ -> },
    /** Sends an IR command to a device directly on a hub (bypasses HA). `hub` is a
     * HarmonyHubConfig.localId; null/blank falls back to the first configured hub. */
    val sendHarmonyCommand: (deviceId: String, command: String, hub: String?) -> Unit = { _, _, _ -> },
    /** The settings-page toggles (motion-wake, Wi-Fi-keep-awake, config
     * server, tap feedback) — see [DeviceSettingsState]. */
    val deviceSettings: DeviceSettingsState = DeviceSettingsState(),
    /** Live connection state of the direct Harmony hub link — for a status
     * indicator on the settings page (HA's own state is on ctx.client.connection). */
    val harmonyConnected: Boolean = false,
    /** Local IR devices (id -> config), resolved once from AppConfig.irDevices.
     * Used by scene_grid items with `irDevice`+`irCommand` fields, and by
     * composed Activities' `"ir"`-sourced devices, to send a raw IR command
     * directly through the device's own blaster — no hub, no HA, no cloud. */
    val irDevices: Map<String, IrDeviceConfig> = emptyMap(),
    /** Sends one command directly through the local IR blaster. */
    val sendIrCommand: (deviceId: String, command: String) -> Unit = { _, _ -> },
    /** Every composed Activity (id -> config), resolved once from
     * AppConfig.activities — see ActivityConfig doc for what "composed" means
     * (more than one device, Astrion itself orchestrates the switch). */
    val activities: Map<String, ActivityConfig> = emptyMap(),
    /** Starts a composed Activity by id: runs its device sequence (diffed
     * against whatever Activity is currently active in the same room, so a
     * device used by both is left alone — see ActivityRuntime.switchActivity),
     * then marks it active. No-op if `activityId` isn't found. */
    val startActivity: (activityId: String) -> Unit = {},
    /** Tracks which AV Activity is active in each room — see the NOTE atop
     * AppConfig.kt. Cards that render a trackable item (scene_grid with
     * `track: true`, or `activity`) call into this after firing their own
     * action; an "active activities" overlay reads from it directly. */
    val activityRuntime: ActivityRuntime? = null,
    /** Resolved theme colors for this dashboard. Cards read from here instead
     * of hardcoded Color literals. Defaults to the original palette so cards
     * render correctly even without a provider (e.g. in previews/tests). */
    val theme: ThemeColors = com.custom.astrion.ui.ThemeColors.Default,
    /** False while the device's screen is off. This is a HOME launcher
     * activity, so it's never stopped just because the screen turns off (see
     * MainActivity's motion-wake) — Compose keeps recomposing and running
     * LaunchedEffects regardless. Cards that do continuous background work
     * while composed (e.g. CameraCard's live MJPEG stream / snapshot
     * polling) should key off this and pause that work while it's false,
     * rather than silently burning CPU and radio all night on whatever page
     * was showing when the screen last timed out. Defaults to true so
     * existing cards/tests that don't pass this keep working as before. */
    val screenOn: Boolean = true
)

/**
 * Implement this to create a new native card type.
 *
 * `type` matches the key used in your config. Render uses Jetpack Compose,
 * giving you full control over layout, colors, animations, and sizing.
 */
interface CardRenderer {
    val type: String

    @Composable
    fun Render(config: CardConfig, ctx: CardContext)
}

/**
 * Global registry. Register once at startup.
 * Lookup by type happens when the dashboard is built.
 */
object CardRegistry {
    private val renderers = LinkedHashMap<String, CardRenderer>()

    fun register(renderer: CardRenderer) {
        renderers[renderer.type] = renderer
    }

    fun register(vararg rs: CardRenderer) = rs.forEach { register(it) }

    fun get(type: String): CardRenderer? = renderers[type]

    @Suppress("Unused")
    fun known(): Set<String> = renderers.keys
}
