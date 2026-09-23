package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable

/**
 * Generic grid of action buttons. Each button independently fires any
 * combination of: an HA service call ("service", optionally "entity_id" +
 * "data"), a direct Harmony device command ("harmonyDevice"+"harmonyCommand",
 * optionally "hub" — bypasses HA), a direct Harmony Activity start
 * ("activityId", optionally "hub"), and/or a local IR command
 * ("irDevice"+"irCommand", sent straight through the device's own blaster,
 * no hub/HA needed) — same Harmony/IR fields and behavior as scene_grid's,
 * just without scene_grid's page/composed-Activity/tracking options, which
 * don't apply to a plain action button. Buttons can carry a PNG icon loaded
 * from a file path (e.g. /sdcard/astrion/icons/mos.png), a text label, or
 * both. Used for the TV-app row, Group/Ungroup, and the playlist buttons.
 *
 * A button can also change its background/border based on a Home Assistant
 * entity's live state — e.g. visually highlighting whichever source is
 * currently selected on an input_select, similar to how scene_grid
 * border-highlights the currently active Activity, but driven by an
 * arbitrary entity/state here instead:
 * - "state_entity": the entity to watch.
 * - "state_value": the state (or list of states) that counts as "active".
 * - "active_color": background color while active (hex, same format as
 *   scene_grid's "color"). Falls back to the ordinary tile background when
 *   absent/unparsable.
 * - "active_border": optional accent border while active — `true` draws
 *   the theme's accent color, or give your own hex color instead.
 *
 * "closePopup": true dismisses whichever popup is currently open, fired
 * after every other action on that button — e.g. a small TV/Projector
 * popup (opened via a scene_grid/title tile's own "pageMode": "popup")
 * where each source button here sends its IR/Harmony command and closes
 * the popup in the same tap.
 *
 * Config shape:
 *   { "type": "button_grid", "options": {
 *       "columns": 3,
 *       "iconPosition": "left",
 *       "buttons": [
 *         { "name": "Group",   "service": "script.group" },
 *         { "name": "Disco",   "icon": "/sdcard/astrion/icons/disco.png",
 *           "service": "script.playlist_disco" },
 *         { "name": "Netflix", "service": "media_player.play_media",
 *           "entity_id": "media_player.the_club_tvv",
 *           "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } },
 *         { "name": "HDMI 1", "irDevice": "samsung_hw_m550", "irCommand": "hdmi1", "closePopup": true },
 *         { "name": "Volume Up", "harmonyDevice": "62845789", "harmonyCommand": "VolumeUp", "hub": "salon_hub" },
 *         { "name": "Watch TV", "activityId": "39568252", "hub": "salon_hub" },
 *         { "name": "TV", "service": "script.select_source_tv",
 *           "state_entity": "input_select.living_room_source", "state_value": "TV",
 *           "active_color": "#FF2A4954", "active_border": true }
 *       ]
 *   } }
 */
class ButtonGridCard : CardRenderer {
    override val type = "button_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = config.int("columns", 3).coerceAtLeast(1)
        val buttons = (config.options["buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        // Card-wide — every button in the grid shares one layout, same idea as
        // "columns". Unrecognized/missing values fall back to "top", the
        // original (and only, before this) behavior.
        val iconPosition = IconPosition.from(config.string("iconPosition"))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            buttons.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    row.forEach { b ->
                        GridButton(b, Modifier.weight(1f), ctx.theme, iconPosition, ctx.entities) { fire(ctx, b) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    /** Fires every action this button carries, independently — "service"
     * (a plain HA service call), a direct Harmony device command
     * ("harmonyDevice"+"harmonyCommand", optionally "hub"), a direct
     * Harmony Activity start ("activityId", optionally "hub" — bypasses
     * HA, same as scene_grid's own Harmony-activity mode), and a local IR
     * command ("irDevice"+"irCommand", no hub needed). A button can carry
     * any combination — most will only set one, but e.g. a button could
     * both call an HA service AND send a direct IR command in one tap.
     * "closePopup": true dismisses whichever popup is currently open,
     * fired last so it runs after every action above — the exact
     * TV/Projector-source-picker-inside-a-popup case scene_grid's own
     * "closePopup" documents, just as likely (if not more) on a
     * button_grid button. No-op when no popup is open. See the class doc
     * for the full config shape. */
    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String
        if (service != null) {
            val domain = service.substringBefore('.')
            val svc = service.substringAfter('.')
            val entityId = b["entity_id"] as? String
            val data = (b["data"] as? Map<String, Any?>).orEmpty()
            ctx.client.callService(
                ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
            )
        }
        val hub = b["hub"] as? String
        val harmonyDevice = b["harmonyDevice"] as? String
        val harmonyCommand = b["harmonyCommand"] as? String
        if (harmonyDevice != null && harmonyCommand != null) {
            ctx.sendHarmonyCommand(harmonyDevice, harmonyCommand, hub)
        }
        (b["activityId"] as? String)?.let { ctx.startHarmonyActivity(it, hub) }
        val irDevice = b["irDevice"] as? String
        val irCommand = b["irCommand"] as? String
        if (irDevice != null && irCommand != null) {
            ctx.sendIrCommand(irDevice, irCommand)
        }
        if (b["closePopup"] == true) ctx.closePopup()
    }

    /** True when this button's optional `"state_entity"`/`"state_value"`
     * condition currently matches — see the class doc for the conditional-
     * styling shape. False (never "active") whenever either field is
     * missing, so an ordinary button without them renders exactly as
     * before. `state_value` accepts either a single state string or a list
     * of them, so one button can highlight for more than one matching
     * state. */
    private fun isConditionActive(b: Map<String, Any?>, entities: EntityMap): Boolean {
        val stateEntity = b["state_entity"] as? String ?: return false
        val current = entities[stateEntity]?.state ?: return false
        return when (val target = b["state_value"]) {
            is String -> current == target
            is List<*> -> target.any { it == current }
            else -> false
        }
    }

    /** The button's background while active — `"active_color"` when set
     * and parsable, otherwise the ordinary theme control background (same
     * as an inactive/non-conditional button). */
    private fun tileBackground(b: Map<String, Any?>, active: Boolean, theme: ThemeColors): Color =
        (if (active) parseHexColor(b["active_color"] as? String) else null) ?: theme.controlBackground

    /** The optional accent border while active — `"active_border"` in the
     * config: `true` draws `theme.accent`, a hex string draws that color
     * instead, and anything else (including absent/false, or the button
     * not being active right now) draws no border at all — a no-op
     * modifier, so callers can always chain it in without an extra branch. */
    private fun activeBorderModifier(b: Map<String, Any?>, active: Boolean, theme: ThemeColors): Modifier {
        if (!active) return Modifier
        val color = when (val raw = b["active_border"]) {
            is Boolean -> if (raw) theme.accent else null
            is String -> parseHexColor(raw)
            else -> null
        } ?: return Modifier
        return Modifier.border(2.dp, color, RoundedCornerShape(14.dp))
    }

    @Composable
    private fun GridButton(
        b: Map<String, Any?>,
        modifier: Modifier,
        theme: ThemeColors,
        iconPosition: IconPosition,
        entities: EntityMap,
        onClick: () -> Unit
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        val targetPx = with(LocalDensity.current) { 32.dp.toPx() }.toInt()
        val bitmap =
            remember(iconPath, targetPx) {
                iconPath?.let { decodeIconSampled(it, targetPx) }
            }
        val hasIcon = bitmap != null
        val hasName = !name.isNullOrBlank()
        val active = isConditionActive(b, entities)
        // left/right lay the icon and label side by side, so the tile doesn't
        // need the extra vertical room top/bottom do to fit both.
        val sideBySide = iconPosition.sideBySide

        val icon: @Composable () -> Unit = {
            if (bitmap != null) Image(bitmap = bitmap, contentDescription = name, modifier = Modifier.size(32.dp))
        }
        val label: @Composable () -> Unit = {
            if (hasName) {
                Text(
                    name!!,
                    color = theme.primaryText,
                    fontSize = if (hasIcon) 12.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
        }

        val boxModifier =
            modifier
                .height(if (hasIcon && !sideBySide) 68.dp else 48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(tileBackground(b, active, theme))
                .then(activeBorderModifier(b, active, theme))
                .tapClickable(focusShape = RoundedCornerShape(14.dp), onClick = onClick)
                .padding(6.dp)

        if (sideBySide) {
            GridButtonRow(boxModifier, iconPosition == IconPosition.LEFT, hasIcon && hasName, icon, label)
        } else {
            GridButtonColumn(boxModifier, iconPosition == IconPosition.BOTTOM, hasIcon && hasName, icon, label)
        }
    }

    /** left/right layout — icon and label side by side. Split out of
     * [GridButton] purely to keep that function's cyclomatic complexity
     * under detekt's threshold; no behavior difference from having it
     * inline. */
    @Composable
    private fun GridButtonRow(
        modifier: Modifier,
        iconFirst: Boolean,
        showSpacer: Boolean,
        icon: @Composable () -> Unit,
        label: @Composable () -> Unit
    ) {
        Row(modifier = modifier, horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            if (iconFirst) {
                icon()
                if (showSpacer) Spacer(Modifier.width(6.dp))
                label()
            } else {
                label()
                if (showSpacer) Spacer(Modifier.width(6.dp))
                icon()
            }
        }
    }

    /** top/bottom layout — icon above or below the label. See
     * [GridButtonRow]'s doc comment. */
    @Composable
    private fun GridButtonColumn(
        modifier: Modifier,
        iconLast: Boolean,
        showSpacer: Boolean,
        icon: @Composable () -> Unit,
        label: @Composable () -> Unit
    ) {
        Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            if (iconLast) {
                label()
                if (showSpacer) Spacer(Modifier.height(4.dp))
                icon()
            } else {
                icon()
                if (showSpacer) Spacer(Modifier.height(4.dp))
                label()
            }
        }
    }
}
