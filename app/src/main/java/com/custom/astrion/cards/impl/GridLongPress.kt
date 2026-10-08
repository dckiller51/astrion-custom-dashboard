package com.custom.astrion.cards.impl

import com.custom.astrion.cards.CardContext
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.ha.ServiceCall

/**
 * Optional `"long_press"` block on a button_grid button or a scene_grid
 * tile — a second, fully separate action fired when the tile is held
 * (touch long-press, or a held D-pad CENTER/Enter) instead of tapped. The
 * tile's own top-level fields keep driving the plain tap exactly as before,
 * so e.g. a "Lights" tile can navigate to its page on tap and switch every
 * light off on hold:
 *
 * ```json
 * { "name": "Lights", "page": "Lights",
 *   "long_press": { "service": "script.astrion_bed_lights_off" } }
 * ```
 *
 * Accepted fields inside the block (any combination, all fired
 * independently, same semantics as on the tile itself):
 * - "service" (+ optional "entity_id", "data"): a plain HA service call.
 * - "entity_id" alone (no "service"): `<domain>.turn_on` on it — scene_grid's
 *   own scene/script activation shortcut.
 * - "harmonyDevice"+"harmonyCommand", "activityId": direct Harmony actions,
 *   routed through "hub" (falls back to the tile's own "hub" when the block
 *   doesn't set one).
 * - "irDevice"+"irCommand": a local IR command.
 * - "activity": starts a composed Activity.
 * - "page" (+ optional "pageMode": "popup"): navigates / opens a popup.
 *   Ignored when "activity" is also set, same rule as scene_grid's tap.
 * - "closePopup": true — dismisses the open popup, fired last.
 *
 * A tile without "long_press" (or with an empty one) keeps no long-press
 * behavior at all — a hold is just treated as a normal tap, unchanged.
 */
@Suppress("UNCHECKED_CAST")
internal fun longPressActionOf(item: Map<String, Any?>): Map<String, Any?>? =
    (item["long_press"] as? Map<String, Any?>)?.takeIf { it.isNotEmpty() }

/** Fires one `"long_press"` block — see [longPressActionOf] for the fields.
 * [fallbackHub] is the tile's own "hub", used when the block has none. */
@Suppress("UNCHECKED_CAST")
internal fun fireGridLongPress(ctx: CardContext, action: Map<String, Any?>, fallbackHub: String?) {
    val service = action["service"] as? String
    val entityId = action["entity_id"] as? String
    if (service != null && service.contains('.')) {
        // Built directly (not via ServiceCall.of's vararg) — avoids detekt's
        // SpreadOperator, and JsonPlain.toJson keeps nested lists/objects in
        // "data" as real JSON instead of stringifying them. Null values are
        // dropped, same as ServiceCall.of does.
        val data =
            (action["data"] as? Map<String, Any?>).orEmpty()
                .filterValues { it != null }
                .mapValues { (_, v) -> JsonPlain.toJson(v) }
        ctx.client.callService(ServiceCall(service.substringBefore('.'), service.substringAfter('.'), entityId, data))
    } else if (entityId != null) {
        ctx.client.callService(ServiceCall(domain = entityId.substringBefore('.'), service = "turn_on", entityId = entityId))
    }
    fireDeviceActions(ctx, action, action["hub"] as? String ?: fallbackHub)
    val activity = action["activity"] as? String
    if (activity != null) {
        ctx.startActivity(activity)
    } else {
        (action["page"] as? String)?.let { page ->
            if (action["pageMode"] == "popup") ctx.openPagePopup(page) else ctx.navigateToPage(page)
        }
    }
    if (action["closePopup"] == true) ctx.closePopup()
}

/** Harmony + IR part of [fireGridLongPress], split out to keep that
 * function under detekt's complexity threshold. */
private fun fireDeviceActions(ctx: CardContext, action: Map<String, Any?>, hub: String?) {
    (action["activityId"] as? String)?.let { ctx.startHarmonyActivity(it, hub) }
    val harmonyDevice = action["harmonyDevice"] as? String
    val harmonyCommand = action["harmonyCommand"] as? String
    if (harmonyDevice != null && harmonyCommand != null) {
        ctx.sendHarmonyCommand(harmonyDevice, harmonyCommand, hub)
    }
    val irDevice = action["irDevice"] as? String
    val irCommand = action["irCommand"] as? String
    if (irDevice != null && irCommand != null) {
        ctx.sendIrCommand(irDevice, irCommand)
    }
}
