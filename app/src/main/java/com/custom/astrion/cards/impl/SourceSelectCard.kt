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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable

/**
 * Source picker for a media_player: a compact row showing the current source,
 * tapping it opens a dropdown of the entity's live `source_list`; picking one
 * fires `media_player.select_source` immediately.
 *
 * Handles players whose source_list vanishes while off (e.g. Android TV) by
 * showing a hint instead of an empty menu.
 *
 * Config shape:
 *   { "type": "source_select", "options": {
 *       "entity_id": "media_player.living_room_tv",
 *       "name": "TV source",
 *       "icon": "/sdcard/astrion/icons/tv.png"   // optional (1.2.9+)
 *   } }
 */
class SourceSelectCard : CardRenderer {
    override val type = "source_select"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val sources = e?.attrStringList("source_list") ?: emptyList()
        val current = e?.attrString("source")

        var expanded by remember { mutableStateOf(false) }
        val iconPath = config.string("icon")
        val iconPx = with(LocalDensity.current) { ICON_DP.dp.toPx() }.toInt()
        val icon = remember(iconPath, iconPx) { iconPath?.let { decodeIconSampled(it, iconPx) } }

        Box {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(ctx.theme.controlBackground)
                    .tapClickable(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Image(bitmap = icon, contentDescription = null, modifier = Modifier.size(ICON_DP.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(name, color = ctx.theme.mutedText, fontSize = 11.sp)
                    Text(
                        current ?: if (sources.isEmpty()) {
                            stringResource(R.string.source_no_sources)
                        } else {
                            stringResource(R.string.source_choose)
                        },
                        color = ctx.theme.primaryText,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) ctx.theme.mutedText else ctx.theme.iconTint
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier =
                Modifier
                    .background(ctx.theme.controlBackground)
                    .widthIn(max = 400.dp)
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) ctx.theme.accent else ctx.theme.primaryText,
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        },
                        onClick = {
                            expanded = false
                            ctx.client.callService(
                                ServiceCall.of("media_player", "select_source", entityId, "source" to s)
                            )
                        }
                    )
                }
            }
        }
    }

    private companion object {
        const val ICON_DP = 28
    }
}
