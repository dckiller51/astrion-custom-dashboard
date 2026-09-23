package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable

/**
 * Title card — a section header for grouping cards on a page, styled after
 * Home Assistant's Mushroom Title card: a title (larger) and an optional
 * subtitle (smaller), independently alignable. No entity binding of its
 * own — purely a label, unless a tap action is set.
 *
 * Unlike Mushroom's own `title_tap_action`/`subtitle_tap_action` (full HA
 * ActionConfig objects — call-service, navigate, more-info, url, assist...),
 * the title and subtitle here can each optionally be tapped using the same
 * action vocabulary a scene_grid tile already has, just prefixed with
 * `title_`/`subtitle_` — one fewer concept to learn, and it already covers
 * Harmony/IR/composed-Activity actions Mushroom's own vocabulary has no
 * concept of. A title/subtitle with none of these fields set just isn't
 * tappable — no ripple, no `clickable`, matches Mushroom's own behavior of
 * only rendering an affordance (there, a chevron) when a real action exists.
 *
 * `icon` and `divider` add a Bubble-Card-style separator look — a small icon
 * before the title, a horizontal line filling the rest of the row after it.
 * `icon` is a PNG file path (`/sdcard/astrion/icons/xxx.png`), loaded the
 * same way every other icon in the app is (`BitmapFactory.decodeFile`) —
 * deliberately not a bundled/named vector icon set: that would be a second,
 * inconsistent icon system alongside the one every other card already uses.
 * When either is set, the title row is always left-aligned regardless of
 * `alignment` (that's the whole shape of this layout — an icon-then-line
 * row doesn't have a sensible centered/right-aligned form); the subtitle
 * below is unaffected and still honors `alignment` on its own.
 *
 * `color` (ARGB hex, same format as scene_grid's) optionally overrides the
 * title's default color, and — when `divider` is also on — tints the line
 * at reduced alpha to match, for a Bubble-Card-style accented section
 * header. Doesn't touch the subtitle (stays its own dimmer default
 * regardless) or tint the icon (icons are never tinted anywhere else in the
 * app — a PNG is treated as already the color it should be).
 *
 * Config shape:
 * ```json
 * {
 *   "type": "title",
 *   "options": {
 *     "title": "Living Room",
 *     "subtitle": "3 lights on",
 *     "alignment": "start",   // "start" (default) | "center" | "end" | "justify"
 *     "icon": "/sdcard/astrion/icons/living-room.png",
 *     "divider": true,
 *     "color": "#7FB3C4",
 *
 *     // Optional — makes the title tappable. Same fields work with a
 *     // "subtitle_" prefix instead, for the subtitle. Exactly the same
 *     // action set as scene_grid, see SceneGridCard's doc comment:
 *     "title_entity_id": "scene.movie_night",
 *     "title_page": "Lights", "title_pageMode": "popup", // "popup" opens title_page as a popup instead of navigating
 *     "title_closePopup": true, // dismisses whichever popup is open, after every action above
 *     "title_activityId": "39568252", "title_hub": "salon_hub",
 *     "title_harmonyDevice": "...", "title_harmonyCommand": "...",
 *     "title_irDevice": "...", "title_irCommand": "...",
 *     "title_activity": "salon_appletv"
 *   }
 * }
 * ```
 */
class TitleCard : CardRenderer {
    override val type = "title"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = remember(config) { config.string("title") }
        val subtitle = remember(config) { config.string("subtitle") }
        if (title.isNullOrBlank() && subtitle.isNullOrBlank()) return

        val iconPath = remember(config) { config.string("icon") }
        val divider = remember(config) { config.bool("divider") }
        val titleColor =
            remember(config) {
                config.string("color")?.let { parseHexColor(it) } ?: ctx.theme.primaryText
            }
        val iconTargetPx = with(LocalDensity.current) { 22.dp.toPx() }.toInt()
        val iconBitmap =
            remember(iconPath, iconTargetPx) {
                iconPath?.let { decodeIconSampled(it, iconTargetPx) }
            }
        // An icon or a divider forces the Bubble-Card-style left-aligned
        // "icon, label, line" row — see the class doc comment for why.
        val forceStart = iconBitmap != null || divider

        val alignment =
            remember(config, forceStart) {
                if (forceStart) {
                    TextAlign.Start
                } else {
                    when (config.string("alignment")) {
                        "center" -> TextAlign.Center
                        "end" -> TextAlign.End
                        "justify" -> TextAlign.Justify
                        else -> TextAlign.Start
                    }
                }
            }

        fun onTap(prefix: String) {
            val o = config.options
            (o["${prefix}_entity_id"] as? String)?.let { entityId ->
                val domain = entityId.substringBefore('.')
                ctx.client.callService(ServiceCall(domain = domain, service = "turn_on", entityId = entityId))
            }
            val hub = o["${prefix}_hub"] as? String
            (o["${prefix}_activityId"] as? String)?.let { ctx.startHarmonyActivity(it, hub) }
            val harmonyDevice = o["${prefix}_harmonyDevice"] as? String
            val harmonyCommand = o["${prefix}_harmonyCommand"] as? String
            if (harmonyDevice != null && harmonyCommand != null) {
                ctx.sendHarmonyCommand(harmonyDevice, harmonyCommand, hub)
            }
            val irDevice = o["${prefix}_irDevice"] as? String
            val irCommand = o["${prefix}_irCommand"] as? String
            if (irDevice != null && irCommand != null) {
                ctx.sendIrCommand(irDevice, irCommand)
            }
            (o["${prefix}_activity"] as? String)?.let(ctx.startActivity)
            (o["${prefix}_page"] as? String)?.let { page ->
                // "${prefix}_pageMode": "popup" opens `page` as a floating
                // popup instead of navigating the pager — same as
                // scene_grid's own "pageMode" option, see SceneGridCard's
                // class doc for the full explanation.
                if (o["${prefix}_pageMode"] == "popup") ctx.openPagePopup(page) else ctx.navigateToPage(page)
            }
            // "${prefix}_closePopup": true dismisses whichever popup is
            // currently open, after every other action above — see
            // SceneGridCard's own "closePopup" doc for the full
            // explanation (same option, same behavior, just prefixed here).
            if (o["${prefix}_closePopup"] == true) ctx.closePopup()
        }

        fun hasAction(prefix: String): Boolean {
            val o = config.options
            return o["${prefix}_entity_id"] != null ||
                o["${prefix}_page"] != null ||
                o["${prefix}_activityId"] != null ||
                o["${prefix}_activity"] != null ||
                o["${prefix}_closePopup"] == true ||
                (o["${prefix}_harmonyDevice"] != null && o["${prefix}_harmonyCommand"] != null) ||
                (o["${prefix}_irDevice"] != null && o["${prefix}_irCommand"] != null)
        }

        val titleTappable = remember(config) { hasAction("title") }
        val subtitleTappable = remember(config) { hasAction("subtitle") }

        Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp)) {
            if (!title.isNullOrBlank() || iconBitmap != null || divider) {
                Row(
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .let { if (titleTappable) it.tapClickable { onTap("title") } else it },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (iconBitmap != null) {
                        Image(bitmap = iconBitmap, contentDescription = null, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    if (!title.isNullOrBlank()) {
                        Text(
                            text = title,
                            color = titleColor,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.SemiBold,
                            textAlign = alignment,
                            modifier = if (forceStart) Modifier else Modifier.weight(1f)
                        )
                    }
                    if (divider) {
                        if (!title.isNullOrBlank() || iconBitmap != null) Spacer(Modifier.width(10.dp))
                        Box(Modifier.weight(1f).height(1.dp).background(titleColor.copy(alpha = 0.3f)))
                    }
                }
            }
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    color = ctx.theme.mutedText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    textAlign =
                    remember(config) {
                        when (config.string("alignment")) {
                            "center" -> TextAlign.Center
                            "end" -> TextAlign.End
                            "justify" -> TextAlign.Justify
                            else -> TextAlign.Start
                        }
                    },
                    modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(top = if (!title.isNullOrBlank()) 2.dp else 0.dp)
                        .let { if (subtitleTappable) it.tapClickable { onTap("subtitle") } else it }
                )
            }
        }
    }

    private fun parseHexColor(s: String): Color? = runCatching {
        val hex = if (s.startsWith("#")) s else "#$s"
        Color(hex.toColorInt())
    }.getOrNull()
}
