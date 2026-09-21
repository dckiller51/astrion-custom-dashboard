package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Full app configuration: swipeable pages of cards plus hardware-button
 * bindings. Loaded from /sdcard/astrion/dashboard.json by DashboardLoader,
 * with `DashboardConfig.default` as the compiled-in fallback.
 */
@Suppress("Unused")
data class AppConfig(
    /** Left-to-right page order; swipe between them. */
    val pages: List<PageConfig>,
    /** Index of the page shown at launch (the "home" page). */
    val startPage: Int = 0,
    /** Short-press button bindings. */
    val hotkeys: List<HotkeyConfig> = emptyList(),
    /** Long-press (~500ms hold) button bindings — same shape as hotkeys. */
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /** Local IR devices — a registry of named commands per physical device,
     * sent directly through the device's own IR blaster (ConsumerIrManager).
     * No hub, no Home Assistant, no cloud — the resilience baseline: works
     * even if every cloud service involved (Harmony's included) disappears.
     * Referenced by id from a scene_grid item's `irDevice`+`irCommand`
     * fields, or as one of an ActivityConfig's `devices`. */
    val irDevices: List<IrDeviceConfig> = emptyList(),
    /** Composed AV Activities ("Watch Apple TV", "Listen to Music"...) that
     * orchestrate more than one device — the multi-device case Harmony's own
     * Activity engine handles internally, reimplemented here so it also
     * works for IR-only and mixed-source setups (see ActivityConfig doc).
     * Single-device/single-action Activities don't need an entry here at
     * all — see the NOTE further down for that lighter-weight path. */
    val activities: List<ActivityConfig> = emptyList(),
    /** Global color theme. Every UI color that was once a hardcoded literal
     * reads from here (via ThemeColors / LocalTheme in the Compose layer).
     * Missing fields fall back to the built-in defaults, so an empty `theme`
     * block renders identically to the original look. */
    val theme: ThemeConfig = ThemeConfig()
)

/**
 * One swipeable page: a name (used by hotkey `page` navigation), its cards,
 * and optional page-scoped hotkeys that override the global ones while this
 * page is on screen (e.g. the D-pad targets the Apple TV only on its page,
 * while VOLUME_UP/DOWN keep pointing at the soundbar everywhere).
 */
@Suppress("Unused")
data class PageConfig(
    val name: String,
    val cards: List<CardConfig>,
    val hotkeys: List<HotkeyConfig> = emptyList(),
    val longHotkeys: List<HotkeyConfig> = emptyList(),
    /** Optional parent page name — makes this page a child in a navigation
     * tree (e.g. "Apple TV" with parent "Vidéo"), rather than a flat
     * top-level page. Entering a child is unchanged: any card's own
     * `navigateToPage`, exactly like today. Leaving is new: the physical
     * button named by [parentKey] (previously a no-op unless a page-specific
     * hotkey bound it — see MainActivity's `dispatchKeyEvent`) now jumps to
     * this page's parent when nothing else claims that key first, so an AV
     * page that binds its own hotkey on the same key is never affected.
     * null (default) = today's flat top-level page, behavior unchanged. */
    val parent: String? = null,
    /** Which physical button triggers the jump to [parent]. A HardwareKey
     * name (case-insensitive) — same vocabulary as [HotkeyConfig.key], e.g.
     * "HOME" or "PAGE_DOWN" instead of the hardware BACK button. Defaults to
     * "BACK", matching the original, non-configurable behavior. Only
     * consulted when [parent] is set; an unrecognized name is treated as if
     * this were left at the default. Note this only changes what triggers
     * the *parent-navigation fallback* — if [parentKey] is set to something
     * other than "BACK", the hardware BACK button itself goes back to doing
     * nothing on this page (today's behavior for a page with no parent at
     * all), since it's no longer the configured "leave" button. */
    val parentKey: String = "BACK",
    /** Optional name of another page in [AppConfig.pages] that this page
     * links to "below" it — swiping UP on this page's [PageIndicator]
     * jumps straight there (same instant `scrollToPage` as any other
     * hardware/tap navigation), the vertical counterpart of [parent]'s
     * horizontal relationship. Typically used for a per-card "more options"
     * page (e.g. an Apple TV page's extra controls).
     *
     * Several different pages can all set this to the *same* linked page —
     * a shared "TV Remote" page with HDMI/amp-source controls, say, linked
     * from both an "Apple TV" page and an "Xbox" page. Leave [parent] unset
     * on a page used this way: [PageIndicator] then falls back to
     * whichever page the most recent swipe-up actually came from, rather
     * than the single fixed page a static [parent] could only ever name
     * one of — so the back chevron always returns to the right place
     * regardless of which of the several linking pages you arrived from.
     * (If the shared page DOES set [parent], that still wins outright —
     * this fallback only fills in when it hasn't.)
     *
     * Case-insensitive; an unresolved name is simply ignored (swipe-up does
     * nothing). null (default) = no linked page, and on a page with no
     * [linkedPage] swiping up does nothing (see DashboardContent's
     * PageIndicator wiring). */
    val linkedPage: String? = null,
    /** How [linkedPage] is reached on swipe-up. `"page"` (default) —
     * unchanged, an instant `scrollToPage` jump exactly like today.
     * `"popup"` — the linked page's own cards render inside a floating
     * overlay on top of the current page instead, sized/placed by
     * [popupWidthFraction]/[popupHeightFraction]/[popupPosition], and the
     * current page (and pager position) is left completely untouched —
     * dismissed by tapping outside it, BACK, or swiping it down. Meant for
     * a quick secondary control (e.g. "TV" / "Projector" on a Video page)
     * that doesn't warrant leaving the page you're on. Unrecognized value
     * falls back to `"page"`. */
    val linkedPageMode: String = "page",
    /** Popup width as a fraction of the screen width (0–1). Only consulted
     * when [linkedPageMode] is `"popup"`. Defaults to a compact 0.7 rather
     * than full-width — a popup that fills the screen edge to edge reads as
     * a full page, undermining the point of using popup mode at all. */
    val popupWidthFraction: Float = 0.7f,
    /** Popup height as a fraction of the screen height (0–1). See
     * [popupWidthFraction]. Defaults to 0.5. */
    val popupHeightFraction: Float = 0.5f,
    /** Where the popup is anchored on screen: "center" (default), "top",
     * "bottom", "left", or "right". Only consulted when [linkedPageMode] is
     * `"popup"`. Unrecognized value falls back to "center". */
    val popupPosition: String = "center",
    /** Optional Activity id — an [ActivityRuntime.TrackedActivity.id], which
     * covers BOTH a composed [ActivityConfig.id] (same id space as a
     * scene_grid item's `"activity"` field) AND any `"track": true`
     * scene_grid tile or hotkey, Harmony-backed ones included (see
     * [ActivityRuntime.scan] for exactly how each kind's id is derived) —
     * NOT a room name. While set, this page's dot is left out of
     * [PageIndicator] until [ActivityRuntime.activeByRoom] reports that
     * specific Activity as the active one somewhere, then the dot appears
     * (and disappears again once a different Activity — or none — takes
     * over that room), decluttering the indicator for a page tied to a
     * device that isn't always in use. null (default) = always shown,
     * today's behavior.
     *
     * Deliberately dot-only, not a real filter on the pager itself
     * (`AppConfig.pages`/`pagerState` always cover every page, regardless of
     * this field) — a page tied to an Activity is exactly the page a
     * scene_grid tile's own `"page"` field jumps to right after firing
     * `"activity"` (see SceneGridCard's `onTap`), and that jump is a
     * synchronous call while the Activity dispatch is still mid-flight
     * (ActivityDispatcher.switchActivity only calls `markActiveById` — what
     * actually updates `activeByRoom` — *after* every device command in the
     * plan has been sent). Gating the pager itself on live Activity state
     * made that combination silently fail to navigate the first version of
     * this feature shipped with, since the target page wasn't "visible" yet
     * at the moment of the jump; keeping the pager itself ungated sidesteps
     * that whole race by construction. */
    val hiddenUnlessActivity: String? = null,
    /** Optional Home Assistant entity id to watch: when its state matches
     * [openWhenState] (default `"on"`), automatically navigates to this
     * page — e.g. a doorbell `switch` turning on pops open a "Doorbell"
     * page — remembering wherever you were before via the same dynamic
     * back-target [linkedPage] uses ([resolveBackTargetName] in
     * Dashboard.kt), so BACK/the chevron return there.
     *
     * When the entity's state stops matching WHILE this page is the one on
     * screen, automatically navigates back too — same target. Only fires
     * once per "on" streak: leaving this page manually while the entity is
     * still matching does not immediately re-open it (see
     * `DashboardEntityPageEffect`'s own `autoOpenedFor` tracking) — it can
     * trigger again on the next transition into [openWhenState].
     *
     * For BACK to close this page from a hardware button (not just an
     * on-screen tap), set [parent] explicitly too: the hardware BACK key is
     * wired in MainActivity against `AppConfig.pages` directly, and only
     * knows about a page's static [parent] — it has no visibility into the
     * dynamic, Compose-only back-target this field alone would leave you
     * with. Leaving [parent] unset still gives on-screen closing (chevron
     * tap) and the automatic close-on-state-change above. */
    val openWhenEntity: String? = null,
    val openWhenState: String = "on",
    val closeWhenState: String? = null,
    /** How [openWhenEntity] reaching [openWhenState] opens this page.
     * `"page"` (default) — unchanged, the same `scrollToPage` navigation
     * described on [openWhenEntity]. `"popup"` — this page's own cards
     * render inside the same floating overlay [linkedPageMode] uses
     * instead, sized/placed by the same [popupWidthFraction] /
     * [popupHeightFraction] / [popupPosition] fields (a page's popup shape
     * is one property of the page, regardless of which of the two
     * mechanisms opens it as one) — the pager is left completely
     * untouched, ideal for something like an intercom/doorbell call you
     * want to see without losing whatever page you were on. Auto-closes
     * the same way `"page"` mode does — the entity's state leaving
     * [openWhenState] (or reaching [closeWhenState] when set) — but by
     * dismissing the popup instead of navigating back, and only when it
     * was this same entity that opened it (a popup opened by hand via
     * [linkedPage] swipe-up is never auto-closed by an unrelated entity's
     * state). Unrecognized value falls back to `"page"`. */
    val openMode: String = "page"
)

/**
 * One physical-button binding. `key` is a HardwareKey name — the HA100 has:
 * UP DOWN LEFT RIGHT CENTER, PAGE_UP PAGE_DOWN, VOLUME_UP VOLUME_DOWN MUTE,
 * BACK HOME POWER VOICE, LIGHT CURTAIN SCENE AC, CUSTOM_1...CUSTOM_4.
 *
 * Exactly one action per binding:
 *  - `page`: navigate to the page with that name (case-insensitive), or
 *  - `service` ("domain.service") + optional `entityId` + flat `data` map
 *    (routed through Home Assistant), or
 *  - `harmonyDevice` + `harmonyCommand`: IR command sent DIRECTLY to the
 *    Harmony hub (HarmonyHubClient), bypass HA — same ids as checklist_codes_ir.csv, or
 *  - `harmonyActivity`: starts a Harmony Activity by its id (from
 *    harmony_config.json), PowerOff = "-1".
 */
@Suppress("Unused")
data class HotkeyConfig(
    val key: String,
    val page: String? = null,
    val service: String? = null,
    val entityId: String? = null,
    val data: Map<String, Any?> = emptyMap(),
    val harmonyDevice: String? = null,
    val harmonyCommand: String? = null,
    val harmonyActivity: String? = null,
    /** Which configured Harmony hub (HarmonyHubConfig.localId) this action
     * targets. Null/blank falls back to the first configured hub — see
     * HarmonyHubRegistry.client(). Only meaningful alongside
     * harmonyDevice/harmonyCommand or harmonyActivity. */
    val hub: String? = null,
    /** Local IR command sent directly through the device's own blaster —
     * `irDevice` is an [IrDeviceConfig].id, `irCommand` a key in its
     * `commands` map. Independent of Harmony/HA, checked after
     * harmonyDevice+harmonyCommand and before a plain `service` call. */
    val irDevice: String? = null,
    val irCommand: String? = null,
    /** Marks this binding as a trackable AV Activity — see AppConfig-level
     * doc on "Activities" below. When true, `room` is required: this is what
     * makes it show up in ActivityRuntime and in an "active activities" card,
     * and what makes starting it replace whatever Activity was previously
     * tracked as active in the same room. The actual action fired on press
     * is still whichever of `page`/`service`/`harmonyDevice+harmonyCommand`/
     * `harmonyActivity`/`irDevice+irCommand` above is set — `track` adds
     * bookkeeping, it doesn't change what gets executed. */
    val track: Boolean = false,
    val room: String? = null,
    /** Physical devices this binding's Activity is known to involve —
     * purely a bookkeeping hint for [ActivityRuntime]'s switchActivity diff,
     * never dispatched to directly. Matters most for a Harmony-backed
     * tracked Activity: the hub handles the actual devices internally, so
     * without this hint a *composed* Activity that later takes over the
     * same room has no way to know a device (e.g. a shared soundbar) was
     * already on, and may needlessly power-cycle it — a real problem for a
     * device with only a `PowerToggle` command and no discrete on/off.
     * Plain device-catalog ids (same ones used in an ActivityConfig's
     * `devices[].deviceId`), regardless of source. */
    val devices: List<String> = emptyList(),
    /** Opens a full-screen overlay instead of dispatching any device/page
     * action: `"settings"` (same overlay as swiping down from the top
     * status bar) or `"activities"` (same as swiping up from the page
     * indicator — the Active Activities picker). Case-insensitive; any
     * other value is treated as unset. Checked first in `runHotkey`'s
     * priority chain, before page navigation, so it always wins over the
     * rest of this binding if both happen to be set. */
    val openOverlay: String? = null,
    /** One-tap "return to the AV Activity that's actually running" — the
     * page-navigation equivalent of tapping an entry in the Active
     * Activities overlay, but for whichever [TrackedActivity] is currently
     * active in *this* room specifically, resolved live at press time via
     * `ActivityRuntime.activeActivity(room)?.page`. A no-op if this room has
     * no active Activity right now (e.g. everything's off) — deliberately
     * doesn't fall through to the rest of this binding's action chain in
     * that case, same as a parent-navigation press on a root page. Distinct
     * from [openOverlay]\="activities": this jumps straight to the one
     * Activity's page with no picker, so it only makes sense on a
     * remote/page that's already dedicated to a single room. Checked right
     * after [openOverlay], before page navigation. */
    val openCurrentActivityRoom: String? = null
) {
    /** Helper flags to quickly check hotkey action type. */
    val isPageNavigation: Boolean get() = !page.isNullOrBlank()
    val isServiceCall: Boolean get() = !service.isNullOrBlank()
    val isHarmonyCommand: Boolean get() = !harmonyDevice.isNullOrBlank() && !harmonyCommand.isNullOrBlank()
    val isHarmonyActivity: Boolean get() = !harmonyActivity.isNullOrBlank()
    val isOpenOverlay: Boolean
        get() = openOverlay.equals("settings", ignoreCase = true) || openOverlay.equals("activities", ignoreCase = true)
}

/**
 * A local IR device: a stable id/name plus a [source] telling the app
 * where its named commands come from. The offline, no-hub, no-cloud
 * equivalent of a Harmony device: works even if every cloud service
 * disappears overnight.
 */
@Suppress("Unused")
data class IrDeviceConfig(
    val id: String,
    val name: String = id,
    val source: IrDeviceSource,
    /** Where this device's commands actually get transmitted from. Lives
     * here (per-device), not on individual cards/hotkeys referencing this
     * device — a device is physically in one place, so every card/hotkey
     * that sends to it should automatically go the same way without
     * having to be configured (or risk being mis-configured) separately.
     * Defaults to [IrTarget.Local] so every existing dashboard.json with
     * no "target" field at all keeps working exactly as before. */
    val target: IrTarget = IrTarget.Local
)

/**
 * Where an [IrDeviceConfig]'s commands actually get fired from.
 *
 * - [Local]: this device's own built-in IR blaster (`ConsumerIrManager`)
 *   — the only option that has ever existed until now, still the default.
 * - [Extender]: a network-connected Astrion IR Extender (see the
 *   astrion-ir-extender project) reached over the LAN, for devices
 *   that live somewhere the local blaster's line of sight doesn't reach
 *   (a closed cabinet, a different room). [extenderId] matches a
 *   registered extender's stable id — same id-by-string-reference
 *   pattern already used for Harmony hubs (`HotkeyConfig.hub` against
 *   `HarmonyHubConfig.localId`), rather than embedding the extender's
 *   full config inline here. The registry that owns those ids (a
 *   "Devices" screen, name+IP+MAC-derived localId, mirroring the Harmony
 *   hub registry) doesn't exist yet — this is just the reference shape
 *   the model is ready for once it does.
 */
@Suppress("Unused")
sealed class IrTarget {
    data object Local : IrTarget()

    data class Extender(val extenderId: String) : IrTarget()
}

/**
 * Where an [IrDeviceConfig]'s commands come from.
 *
 * - [Inline]: freq+pattern already resolved from a hand-pasted Pronto Hex
 *   code, embedded directly in dashboard.json. For one-off buttons not
 *   (yet) in any curated database — e.g. straight out of the sniffer's
 *   Learning Mode.
 * - [SdCardRef]: a pointer into `/sdcard/astrion/ir-database/<category>.json`
 *   (see IrDatabaseRuntime.kt) — the curated files the ir-database picker
 *   (a separate static site, not bundled with this app) generates. Pronto
 *   is resolved here at runtime, on first use, and cached — dashboard.json
 *   itself only ever carries the *reference*, never the raw codes, so a
 *   community database update doesn't require re-touching every dashboard
 *   built against it.
 */
@Suppress("Unused")
sealed class IrDeviceSource {
    data class Inline(
        /** commandId (freeform, e.g. "power", "volume_up", "hdmi1") -> resolved IR frame. */
        val commands: Map<String, IrStepConfig>
    ) : IrDeviceSource()

    data class SdCardRef(
        /** Matches an ir-database category id, e.g. "tv", "ac" — also the filename stem. */
        val category: String,
        /** Matches a `brand_name` in that category's file, case-insensitively. */
        val brand: String,
        /** Matches a `model_name` under that brand, case-insensitively. */
        val model: String
    ) : IrDeviceSource()
}

/** One IR transmission: `freq` (Hz) + `pattern` (alternating on/off
 * durations in µs) map straight onto `ConsumerIrManager.transmit()`. */
@Suppress("Unused")
data class IrStepConfig(
    val freq: Int,
    val pattern: List<Int>,
    /** The original Pronto hex string this was decoded from, when known —
     * populated for [IrDeviceSource.SdCardRef] (the ir-database file
     * always has it), null for [IrDeviceSource.Inline] (dashboard.json
     * only ever persists the already-decoded freq/pattern for those, not
     * the original text). Needed to route a command to an
     * [IrTarget.Extender], which takes a raw Pronto string, not a decoded
     * pattern — recomputing one from [pattern] would need the exact
     * inverse of `prontoToPattern()`, an unnecessary source of subtle
     * rounding bugs when the original string can just be carried through
     * instead. */
    val pronto: String? = null
)

// NOTE: a *single-action* Activity (one HA script, one existing Harmony
// Activity — the hub already orchestrates everything for that one — or one
// direct command) doesn't need an ActivityConfig entry at all: just mark a
// scene_grid item or HotkeyConfig `track = true` with a `room`, same as
// before. Its action is whichever field was already there: `entityId`,
// `activityId`+`hub`, `harmonyDevice`+`harmonyCommand`, or `irDevice`+
// `irCommand`. `track` just makes it visible to ActivityRuntime and to the
// "active activities" overlay.
//
// A *composed* Activity — more than one device, where Astrion itself (not a
// hub) has to decide what to power on/off when switching — needs the real
// thing below.

/**
 * One user-facing AV Activity that orchestrates more than one device
 * ("Watch Apple TV" = TV on HDMI1 + receiver on HDMI2 + lights off). Astrion
 * itself runs the start sequence and, when switching to a *different*
 * Activity in the same `room`, diffs `devices` against the incoming
 * Activity's so a device used by both is left alone (no needless off/on
 * flicker, just a possible input change) while a device only in the
 * outgoing one gets powered off — see ActivityRuntime.switchActivity().
 *
 * Referenced by id from a scene_grid item's `activity` field. Always
 * implicitly tracked (no separate `track` flag needed) — the whole point of
 * defining one is room exclusivity.
 */
@Suppress("Unused")
data class ActivityConfig(
    val id: String,
    val name: String,
    val room: String,
    val icon: String? = null,
    /** Page to open when this Activity becomes active — optional; a
     * composed Activity can exist purely for room-exclusivity/orchestration
     * without navigating anywhere. */
    val page: String? = null,
    val devices: List<ActivityDeviceConfig>,
    /** Which of `devices` (by `deviceId`) VOLUME_UP/DOWN/MUTE hotkeys should
     * target while this Activity is active. Paired with the three commands
     * below — the builder writes them out as page-scoped hotkeys on `page`
     * when this Activity is saved (see docs/js/activities.js), not read
     * directly by the app at runtime; PageConfig.hotkeys already overrides
     * global bindings while its page is on screen, so no separate "which
     * room is the panel in right now" runtime logic is needed. */
    val volumeDeviceId: String? = null,
    val volumeUpCommand: String? = null,
    val volumeDownCommand: String? = null,
    val muteCommand: String? = null
)

/**
 * One device's role within an [ActivityConfig].
 *
 * `source` selects which registry `deviceId` resolves against:
 *  - `"ir"` — an [IrDeviceConfig].id; `powerOnCommand`/`powerOffCommand`/
 *    `inputCommand`, if set, are commandIds in that device's `commands` map.
 *  - `"harmony"` — a Harmony device id (via `hub`); same three fields, but
 *    Harmony command names (e.g. "PowerOn"/"PowerOff"/"InputHdmi1").
 *  - `"ha"` — a Home Assistant entity id. Power is `turn_on`/`turn_off`
 *    (gated by `powerOnFirst`/`powerOffOnExit`, `powerOnCommand`/
 *    `powerOffCommand` are ignored); `inputCommand`, if set, is passed as
 *    `media_player.select_source`'s `source`.
 *
 * On Activity start: if this device wasn't already on for the *previous*
 * Activity in the same room (or `powerOnFirst` is true regardless),
 * `powerOnCommand` fires, then — after `delayAfterMs` — `inputCommand`. On
 * losing the room to a *different* Activity: if this device isn't also used
 * by the incoming one (or `powerOffOnExit` is true regardless),
 * `powerOffCommand` fires; a device shared by both is left alone entirely
 * (no power cycle, no re-sent input) unless the incoming Activity gives it a
 * different `inputCommand`.
 */
@Suppress("Unused")
data class ActivityDeviceConfig(
    val deviceId: String,
    val source: String,
    val hub: String? = null,
    val powerOnCommand: String? = null,
    val powerOffCommand: String? = null,
    val inputCommand: String? = null,
    val powerOnFirst: Boolean = true,
    val powerOffOnExit: Boolean = true,
    /** Wait this long after this device's start commands before starting the
     * *next* device's — e.g. TV on, wait 2s, then the receiver. Ignored on
     * the last device and on stop (power-off runs with no delays between
     * devices — nothing downstream needs to wait for it). */
    val delayAfterMs: Int = 0
)

/**
 * Global color theme — 12 semantic tokens. Values are ARGB/RGB hex strings
 * (e.g. "#1B343D"). Each defaults to the app's original hardcoded color, so a
 * ThemeConfig() with no overrides reproduces the built-in look exactly.
 * Parsed from the `theme` block of dashboard.json by DashboardLoader.
 */
@Suppress("Unused")
data class ThemeConfig(
    val background: String = "#0E2229",
    val cardSurface: String = "#1B343D",
    val insetSurface: String = "#152B33",
    val controlBackground: String = "#2C4C58",
    val primaryText: String = "#E6F0F1",
    val mutedText: String = "#93AFB6",
    val iconTint: String = "#CBDCE0",
    val accent: String = "#6EA8FE",
    val accentSecondary: String = "#4C6EF5",
    val amber: String = "#FFC24B",
    val danger: String = "#E06767",
    val success: String = "#4CAF50"
)
