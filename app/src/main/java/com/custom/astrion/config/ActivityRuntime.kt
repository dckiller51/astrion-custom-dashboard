package com.custom.astrion.config

import com.custom.astrion.harmony.HarmonyHubClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One trackable Activity, discovered by scanning the loaded [AppConfig] for
 * every scene_grid item or hotkey with `"track": true` — see the NOTE atop
 * AppConfig.kt. Not a config type itself, just a read-only view over one.
 */
data class TrackedActivity(
    val id: String,
    val name: String,
    val room: String,
    val icon: String? = null,
    /** Which page to jump to on "return to current Activity" — the page this
     * item lives on, or its own `page` target for a hotkey/scene_grid tap. */
    val page: String?,
    /** Set when this Activity is backed by an existing Harmony Activity
     * (`activityId`+`hub`), so ActivityRuntime can prefer the hub's own live
     * state over the "last tapped" fallback — see [ActivityRuntime.bind]. */
    val harmonyActivityId: String? = null,
    val harmonyHub: String? = null,
    /** Physical devices this Activity is known to involve, plain
     * device-catalog ids regardless of source — for a composed Activity,
     * `ActivityConfig.devices.map { it.deviceId }`; for a lightweight
     * `track: true` tile/hotkey, an explicit `"devices"` hint list (mainly
     * useful for a Harmony-backed one, since the hub's own devices are
     * otherwise invisible to Astrion). Used purely for [Dashboard.kt]'s
     * switchActivity diff — never dispatched to directly. */
    val devices: List<String> = emptyList()
)

/**
 * Tracks, per room, which [TrackedActivity] is currently active. At most one
 * Activity is active per room at a time; different rooms are fully
 * independent (see the room-exclusivity discussion this was designed around).
 *
 * Two ways an entry gets updated:
 *  1. Manually — [markActive] is called right after Astrion itself fires the
 *     tap/hotkey action for a tracked item (works for any source: HA entity,
 *     direct Harmony command, IR, or a Harmony Activity with no live binding).
 *  2. Live, for Harmony-backed activities only — [bind] wires a room's state
 *     to a [HarmonyHubClient.currentActivityId]/[HarmonyHubClient.activityState],
 *     so an Activity started from elsewhere (another remote, an HA automation,
 *     the physical Harmony remote) is reflected here too, not just the ones
 *     Astrion itself triggered.
 */
class ActivityRuntime(config: AppConfig) {
    /** Every trackable Activity found in the config, in declaration order. */
    val all: List<TrackedActivity> = scan(config)

    private val byRoom: Map<String, List<TrackedActivity>> = all.groupBy { it.room }

    private val _activeByRoom = MutableStateFlow<Map<String, String?>>(emptyMap())

    /** room -> id of the currently active TrackedActivity in that room (or null). */
    val activeByRoom: StateFlow<Map<String, String?>> = _activeByRoom.asStateFlow()

    fun activitiesIn(room: String): List<TrackedActivity> = byRoom[room].orEmpty()

    fun activeActivity(room: String): TrackedActivity? {
        val id = _activeByRoom.value[room] ?: return null
        return byRoom[room]?.firstOrNull { it.id == id }
    }

    /** Every room that has at least one active Activity — backs an "active
     * activities" card without it needing to know about rooms with none. */
    fun activeActivities(): List<TrackedActivity> = _activeByRoom.value.entries.mapNotNull { (room, id) ->
        id?.let { activeId -> byRoom[room]?.firstOrNull { it.id == activeId } }
    }

    /** Call right after firing a tracked item's own action (HA/Harmony/IR). */
    fun markActive(activity: TrackedActivity) {
        _activeByRoom.value = _activeByRoom.value + (activity.room to activity.id)
    }

    /** Marks [room] as having no active Activity — the counterpart of
     * [markActive]. Called once a room's Activity has actually been stopped
     * (composed Activity's own poweroff devices sent, or a Harmony hub
     * confirmed PowerOff via [bind]'s own "-1" handling); never call this
     * speculatively before the stop action itself has been dispatched. */
    fun clear(room: String) {
        _activeByRoom.value = _activeByRoom.value + (room to null)
    }

    /** Same as [markActive], by id — for callers (like a composed Activity's
     * executor in Dashboard.kt) that only have the id on hand. No-op if the
     * id isn't a known trackable Activity. */
    fun markActiveById(id: String) {
        all.firstOrNull { it.id == id }?.let(::markActive)
    }

    /** The composed [ActivityConfig] behind a [TrackedActivity] that came
     * from `AppConfig.activities` (as opposed to a lightweight `track: true`
     * tile) — null for the latter. Used by the executor in Dashboard.kt to
     * get at `devices`/`volumeDeviceId` when starting one. */
    val activityConfigs: Map<String, ActivityConfig> = config.activities.associateBy { it.id }

    /**
     * Convenience for card renderers: given the exact same scene_grid item
     * map that was just tapped, finds the matching pre-scanned
     * [TrackedActivity] (by identity, i.e. reference-independent — same
     * derivation as [scan]) and marks it active. No-op if the item isn't
     * `track: true` or has no `room` — safe to call unconditionally after
     * every tap, cards don't need to pre-check.
     */
    fun trackTap(scene: Map<String, Any?>, fallbackPage: String? = null) {
        activityFrom(scene, fallbackPage)?.let { candidate ->
            // Re-resolve against `all` (not the freshly-built `candidate`) so
            // markActive uses the canonical instance already in byRoom.
            all
                .firstOrNull { it.id == candidate.id && it.room == candidate.room }
                ?.let(::markActive)
        }
    }

    /**
     * Mirrors a Harmony hub's live Activity state into this room, so
     * Activities started from outside Astrion still show up. Call once per
     * configured hub, after connecting — cheap to leave running for the
     * hub's lifetime, it's just a StateFlow collector.
     *
     * `isDefaultHub` should be true for exactly one call (the hub
     * HarmonyHubRegistry.client(null) would resolve to) so that tracked
     * items with no explicit `"hub"` in their JSON — the common case for a
     * single-hub setup — still match, the same way onTap()'s
     * startHarmonyActivity(id, hub = null) already falls back to that hub.
     *
     * Confirmed against a real hub capture (2026-08-14) — see
     * HarmonyHubClient.currentActivityId.
     */
    suspend fun bind(hub: HarmonyHubClient, hubLocalId: String, isDefaultHub: Boolean = false) {
        hub.currentActivityId.collect { activityId ->
            if (activityId == null) return@collect

            fun targetsThisHub(a: TrackedActivity) = a.harmonyHub == hubLocalId || (a.harmonyHub.isNullOrBlank() && isDefaultHub)
            val matches = all.filter { targetsThisHub(it) && it.harmonyActivityId == activityId }
            matches.forEach { markActive(it) }
            if (activityId == "-1") {
                // PowerOff: clear every room this hub could have been driving.
                val roomsForHub = all.filter(::targetsThisHub).map { it.room }.toSet()
                _activeByRoom.value = _activeByRoom.value.filterKeys { it !in roomsForHub }
            }
        }
    }

    /**
     * True if [scene] — the exact same scene_grid item map [trackTap] would
     * receive — represents the Activity currently active in its room, for a
     * composed Activity (`"activity"` field) or a `track: true` tile alike.
     * Takes the active-by-room snapshot as a parameter rather than reading
     * [activeByRoom] itself, so a caller collecting it via `collectAsState()`
     * in Compose (e.g. SceneGridCard, to highlight the active tile) stays
     * reactive — this is a pure read, the caller owns observing it.
     */
    fun isActiveTile(scene: Map<String, Any?>, activeByRoom: Map<String, String?>): Boolean {
        val activityId = scene["activity"] as? String
        if (activityId != null) {
            val room = activityConfigs[activityId]?.room ?: return false
            return activeByRoom[room] == activityId
        }
        val candidate = activityFrom(scene, fallbackPage = null) ?: return false
        return activeByRoom[candidate.room] == candidate.id
    }

    /** Shared id/name derivation for a scene_grid item — used by both [scan]
     * (at config-load time) and [trackTap] (at tap time), so the two never
     * disagree on what a given item's id is. Returns null if not trackable. */
    private fun activityFrom(scene: Map<String, Any?>, fallbackPage: String?): TrackedActivity? {
        if (scene["track"] != true) return null
        val room = (scene["room"] as? String)?.takeIf { it.isNotBlank() } ?: return null
        val name = (scene["name"] as? String) ?: (scene["entity_id"] as? String) ?: "Activity"
        val entityId = scene["entity_id"] as? String
        val harmonyActivityId = scene["activityId"] as? String
        val id = entityId ?: harmonyActivityId ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "_")

        @Suppress("UNCHECKED_CAST")
        val devices = (scene["devices"] as? List<*>)?.mapNotNull { it as? String } ?: emptyList()
        return TrackedActivity(
            id = id,
            name = name,
            room = room,
            icon = scene["icon"] as? String,
            page = (scene["page"] as? String) ?: fallbackPage,
            harmonyActivityId = harmonyActivityId,
            harmonyHub = scene["hub"] as? String,
            devices = devices
        )
    }

    private fun scan(config: AppConfig): List<TrackedActivity> {
        val found = mutableListOf<TrackedActivity>()

        fun fromHotkey(hk: HotkeyConfig, fallbackPage: String?) {
            if (!hk.track) return
            val room = hk.room?.takeIf { it.isNotBlank() } ?: return
            val name = hk.harmonyActivity ?: hk.page ?: hk.key
            val id = hk.entityId ?: hk.harmonyActivity ?: name.lowercase().replace(Regex("[^a-z0-9]+"), "_")
            found +=
                TrackedActivity(
                    id = id,
                    name = name,
                    room = room,
                    page = hk.page ?: fallbackPage,
                    harmonyActivityId = hk.harmonyActivity,
                    harmonyHub = hk.hub,
                    devices = hk.devices
                )
        }

        config.hotkeys.forEach { fromHotkey(it, fallbackPage = null) }
        config.longHotkeys.forEach { fromHotkey(it, fallbackPage = null) }
        config.pages.forEach { page ->
            page.cards
                .filter { it.type == "scene_grid" }
                .forEach { card ->
                    @Suppress("UNCHECKED_CAST")
                    val scenes = card.options["scenes"] as? List<Map<String, Any?>> ?: emptyList()
                    scenes.mapNotNull { activityFrom(it, fallbackPage = page.name) }.forEach { found += it }
                }
            page.hotkeys.forEach { fromHotkey(it, fallbackPage = page.name) }
            page.longHotkeys.forEach { fromHotkey(it, fallbackPage = page.name) }
        }
        // Composed Activities (AppConfig.activities) carry everything they
        // need themselves — no tile/hotkey to derive them from, no implicit
        // tracking flag: defining one is inherently tracked.
        config.activities.forEach { act ->
            found +=
                TrackedActivity(
                    id = act.id,
                    name = act.name,
                    room = act.room,
                    icon = act.icon,
                    page = act.page,
                    devices = act.devices.map { it.deviceId }
                )
        }
        return found
    }
}
