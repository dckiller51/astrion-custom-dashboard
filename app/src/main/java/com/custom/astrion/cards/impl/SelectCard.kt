package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.toColorInt
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.tapClickable

/**
 * Generic picker for any `input_select.*` or `select.*` entity — an
 * adaptation of Home Assistant's Mushroom select card, the same way
 * [CoverCard] adapts Mushroom's cover card: icon in a colored shape, a
 * name/state column, and a dedicated control area below (or beside, or
 * stacked, per `layout`) rather than the whole tile being one clickable
 * dropdown trigger. Mushroom's own select card reads `stateObj.state` as
 * the current choice and `stateObj.attributes.options` as the list, then
 * calls `<domain>.select_option` on tap — same three facts, ported here.
 *
 * Deliberately a separate renderer from [SourceSelectCard] rather than a
 * shared/branching one: that card is specific to media_player's
 * `source_list`/`source` attributes and `media_player.select_source`
 * service, none of which exist on an input_select/select entity, so it
 * can't simply be repointed at one — this card exists to cover exactly
 * what it doesn't, the same way Mushroom ships select-card and
 * source-select-card as two distinct cards rather than one.
 *
 * Config shape:
 *   { "type": "select", "options": {
 *       "entity_id": "input_select.video_output_living_room",
 *       "name": "Living room output",
 *       "icon_color": "#6EA8FE",
 *       "layout": "default"
 *       //   "default"    — icon + name/state row, control full-width below
 *       //                  (Mushroom's own default look)
 *       //   "horizontal" — icon + name/state on the left, control inline
 *       //                  on the right, single row
 *       //   "vertical"   — icon, name, state and control all centered and
 *       //                  stacked in one column
 *   } }
 */
class SelectCard : CardRenderer {
    override val type = "select"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val domain = entityId.substringBefore('.')
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val options = e?.attrStringList("options") ?: emptyList()
        // The option list lives in an attribute, but the *current* choice
        // is the entity's own state — unlike a cover or media_player,
        // input_select/select have no separate "unavailable option"
        // shape to account for: state is always one of `options`, or
        // "unavailable"/"unknown" if the entity itself is down.
        val current = e?.state
        val stateLabel =
            current ?: if (options.isEmpty()) {
                stringResource(R.string.select_no_options)
            } else {
                stringResource(R.string.select_choose)
            }

        val iconColor = config.string("icon_color")?.let(::parseHexColor)

        val controlSlot: @Composable (fillWidth: Boolean) -> Unit = { fillWidth ->
            SelectMenuControl(
                current = current,
                options = options,
                fillWidth = fillWidth,
                onSelect = { option ->
                    if (option != current) {
                        ctx.client.callService(
                            ServiceCall.of(domain, "select_option", entityId, "option" to option)
                        )
                    }
                }
            )
        }

        when (config.string("layout")) {
            "horizontal" -> HorizontalLayout(name, stateLabel, iconColor, controlSlot)
            "vertical" -> VerticalLayout(name, stateLabel, iconColor, controlSlot)
            else -> DefaultLayout(name, stateLabel, iconColor, controlSlot)
        }
    }

    private fun parseHexColor(s: String): Color? = runCatching {
        val hex = if (s.startsWith("#")) s else "#$s"
        Color(hex.toColorInt())
    }.getOrNull()
}

// ---- shared pieces ---------------------------------------------------------

@Composable
private fun SelectIcon(color: Color?, size: Dp = 42.dp) {
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(color?.copy(alpha = 0.2f) ?: Color(0xFF2A4954)),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = color ?: Color(0xFFB6C9CE))
    }
}

@Composable
private fun NameState(
    name: String,
    stateLabel: String,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    textAlign: TextAlign = TextAlign.Start
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            name,
            color = Color(0xFFE6F0F1),
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
        Text(
            stateLabel,
            color = Color(0xFF93AFB6),
            fontSize = 13.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
    }
}

/**
 * The actual select control — a full-width (or fixed-width, in the
 * horizontal layout) rounded pill showing the current option, tapping it
 * opens a dropdown of every option. Mirrors [CoverCard]'s `PercentSlider`
 * in visual weight (a filled pill acting as the "control" for this card
 * type), standing in for Mushroom's own `ha-control-select-menu`.
 */
@Composable
private fun SelectMenuControl(current: String?, options: List<String>, fillWidth: Boolean, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(140.dp)) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF152B33))
                .tapClickable(enabled = options.isNotEmpty()) { expanded = true }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                current ?: "—",
                color = Color(0xFFE6F0F1),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = if (options.isEmpty()) Color(0xFF5A7783) else Color(0xFFCBDCE0)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(Color(0xFF1E3841)).widthIn(max = 400.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            color = if (option == current) Color(0xFF6EA8FE) else Color(0xFFE6F0F1),
                            fontSize = 14.sp,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    },
                    onClick = {
                        expanded = false
                        onSelect(option)
                    }
                )
            }
        }
    }
}

// ---- "default": icon + name/state row, control full-width below -----------

@Composable
private fun DefaultLayout(name: String, stateLabel: String, iconColor: Color?, control: @Composable (fillWidth: Boolean) -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1E3841))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SelectIcon(iconColor)
            Spacer(Modifier.width(12.dp))
            NameState(name, stateLabel)
        }
        control(true)
    }
}

// ---- "horizontal": icon + name/state on the left, control on the right ----

@Composable
private fun HorizontalLayout(name: String, stateLabel: String, iconColor: Color?, control: @Composable (fillWidth: Boolean) -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1E3841))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectIcon(iconColor)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { NameState(name, stateLabel) }
        Spacer(Modifier.width(12.dp))
        control(false)
    }
}

// ---- "vertical": icon, name, state, control — all centered and stacked ----

@Composable
private fun VerticalLayout(name: String, stateLabel: String, iconColor: Color?, control: @Composable (fillWidth: Boolean) -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(Color(0xFF1E3841))
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SelectIcon(iconColor, size = 48.dp)
        Spacer(Modifier.height(8.dp))
        NameState(name, stateLabel, horizontalAlignment = Alignment.CenterHorizontally, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        control(true)
    }
}
