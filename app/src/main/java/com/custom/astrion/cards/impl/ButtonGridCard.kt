package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable

/**
 * Generic grid of action buttons, each firing a HA service call. Buttons can
 * carry a PNG icon loaded from a file path (e.g. /sdcard/astrion/icons/mos.png),
 * a text label, or both. Used for the TV-app row, Group/Ungroup, and the
 * playlist buttons.
 *
 * Config shape:
 *   { "type": "button_grid", "options": {
 *       "columns": 3,
 *       "buttons": [
 *         { "name": "Group",   "service": "script.group" },
 *         { "name": "Disco",   "icon": "/sdcard/astrion/icons/disco.png",
 *           "service": "script.playlist_disco" },
 *         { "name": "Netflix", "service": "media_player.play_media",
 *           "entity_id": "media_player.the_club_tvv",
 *           "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
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
                        GridButton(b, Modifier.weight(1f), ctx.theme, iconPosition) { fire(ctx, b) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    /** Where a button's icon sits relative to its label. */
    private enum class IconPosition {
        TOP,
        BOTTOM,
        LEFT,
        RIGHT
        ;

        companion object {
            fun from(raw: String?): IconPosition = when (raw?.lowercase()) {
                "bottom" -> BOTTOM
                "left" -> LEFT
                "right" -> RIGHT
                else -> TOP
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }

    @Composable
    private fun GridButton(b: Map<String, Any?>, modifier: Modifier, theme: ThemeColors, iconPosition: IconPosition, onClick: () -> Unit) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        val targetPx = with(LocalDensity.current) { 32.dp.toPx() }.toInt()
        val bitmap =
            remember(iconPath, targetPx) {
                iconPath?.let { decodeIconSampled(it, targetPx) }
            }
        val hasIcon = bitmap != null
        val hasName = !name.isNullOrBlank()
        // left/right lay the icon and label side by side, so the tile doesn't
        // need the extra vertical room top/bottom do to fit both.
        val sideBySide = iconPosition == IconPosition.LEFT || iconPosition == IconPosition.RIGHT

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
                .background(theme.controlBackground)
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
