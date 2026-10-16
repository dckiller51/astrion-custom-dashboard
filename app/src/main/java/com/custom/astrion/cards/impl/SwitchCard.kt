package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable

/**
 * Switch tile: simple toggle. Works for switch.* (and anything toggleable).
 *
 * Config: CardConfig("switch", mapOf("entity_id" to "switch.porch", "name" to "Porch"))
 *
 * `icon` is either a keyword (`heater`/`heat`, `fan`, `bulb`/`light`, anything
 * else = power) or, since 1.2.9, a PNG path (`/sdcard/astrion/icons/xxx.png`)
 * like every other card's icon — the builder's icon picker writes a path.
 * `on_color` accepts `#RRGGBB` (opaque) or `#AARRGGBB`, or an ARGB number.
 */
class SwitchCard : CardRenderer {
    override val type = "switch"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val on = e?.isOn == true
        val iconOpt = config.string("icon")
        val targetPx = with(LocalDensity.current) { ICON_DP.dp.toPx() }.toInt()
        val bitmap =
            remember(iconOpt, targetPx) {
                iconOpt?.takeIf { it.contains('/') }?.let { decodeIconSampled(it, targetPx) }
            }
        // On-state background (e.g. a semi-transparent dark red for a heater).
        val onColor = parseOnColor(config.options["on_color"]) ?: DEFAULT_ON_COLOR

        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) onColor else ctx.theme.controlBackground)
                .tapClickable { ctx.client.toggle(entityId) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Leading icon box, matching the cover tiles below it.
            Box(
                modifier =
                Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(ctx.theme.controlBackground),
                contentAlignment = Alignment.Center
            ) {
                val bmp = bitmap
                if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(ICON_DP.dp))
                } else {
                    Icon(
                        switchIcon(iconOpt),
                        contentDescription = null,
                        tint = if (on) ctx.theme.primaryText else ctx.theme.iconTint
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = ctx.theme.primaryText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (on) stringResource(R.string.astrion_state_on) else stringResource(R.string.astrion_state_off),
                    color = ctx.theme.mutedText,
                    fontSize = 13.sp
                )
            }
        }
    }

    private companion object {
        const val ICON_DP = 28
        val DEFAULT_ON_COLOR = Color(0xFF2E5A46)
    }
}

/** Map a switch card's `icon` keyword to a Material icon. */
private fun switchIcon(name: String?): ImageVector = when (name) {
    "heater", "heat" -> Icons.Filled.Whatshot
    "fan" -> Icons.Filled.Air
    "bulb", "light" -> Icons.Filled.Lightbulb
    else -> Icons.Filled.PowerSettingsNew
}

/** Parse an `on_color` option: a hex string (`#RRGGBB` or `#AARRGGBB`) or an ARGB number. */
private fun parseOnColor(v: Any?): Color? = when (v) {
    is Number -> Color(v.toLong())
    is String -> parseHexColor(v)
    else -> null
}
