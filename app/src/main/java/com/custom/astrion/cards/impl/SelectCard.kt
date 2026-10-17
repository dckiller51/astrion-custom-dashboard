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
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
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
 *       "layout": "default",
 *       //   "default"    — icon + name/state row, control full-width below
 *       //                  (Mushroom's own default look)
 *       //   "horizontal" — icon + name/state on the left, control inline
 *       //                  on the right, single row
 *       //   "vertical"   — icon, name, state and control all centered and
 *       //                  stacked in one column
 *       //   "dropdown"   — (1.2.9+) the control alone, full width: no icon,
 *       //                  no name — just the current option and the menu
 *       "font_size": 16         // (1.2.9+) optional, sp: the current option
 *                               // and the menu's options (default 13, 16 for
 *                               // "dropdown")
 *   } }
 *
 * Colors follow the dashboard theme (1.2.9+; they used to be hard-coded dark
 * tones, unreadable with a light theme).
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
        // is the entity's own state — input_select/select have no separate
        // "unavailable option" shape: state is always one of `options`, or
        // "unavailable"/"unknown" if the entity itself is down.
        val current = e?.state
        val stateLabel =
            current ?: if (options.isEmpty()) {
                stringResource(R.string.select_no_options)
            } else {
                stringResource(R.string.select_choose)
            }

        val iconColor = parseHexColor(config.string("icon_color"))
        val layout = config.string("layout")
        val dropdownOnly = layout == "dropdown"
        val fontSize = (config.options["font_size"] as? Number)?.toFloat()?.coerceIn(MIN_FONT, MAX_FONT)
            ?: if (dropdownOnly) DROPDOWN_FONT else DEFAULT_FONT
        val theme = ctx.theme

        val controlSlot: @Composable (fillWidth: Boolean) -> Unit = { fillWidth ->
            SelectMenuControl(
                current = current,
                options = options,
                fillWidth = fillWidth,
                fontSize = fontSize,
                tall = dropdownOnly,
                theme = theme,
                onSelect = { option ->
                    if (option != current) {
                        ctx.client.callService(
                            ServiceCall.of(domain, "select_option", entityId, "option" to option)
                        )
                    }
                }
            )
        }

        val parts = SelectParts(name, stateLabel, iconColor, theme)
        when (layout) {
            "horizontal" -> HorizontalLayout(parts, controlSlot)
            "vertical" -> VerticalLayout(parts, controlSlot)
            "dropdown" -> controlSlot(true)
            else -> DefaultLayout(parts, controlSlot)
        }
    }

    private companion object {
        const val DEFAULT_FONT = 13f
        const val DROPDOWN_FONT = 16f
        const val MIN_FONT = 10f
        const val MAX_FONT = 32f
    }
}

/** What every layout but "dropdown" draws around the control. */
private class SelectParts(val name: String, val stateLabel: String, val iconColor: Color?, val theme: ThemeColors)

// ---- shared pieces ---------------------------------------------------------

@Composable
private fun SelectIcon(p: SelectParts, size: Dp = 42.dp) {
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(RoundedCornerShape(size / 2))
            .background(p.iconColor?.copy(alpha = 0.2f) ?: p.theme.controlBackground),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = p.iconColor ?: p.theme.iconTint)
    }
}

@Composable
private fun NameState(p: SelectParts, horizontalAlignment: Alignment.Horizontal = Alignment.Start, textAlign: TextAlign = TextAlign.Start) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            p.name,
            color = p.theme.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = textAlign
        )
        Text(
            p.stateLabel,
            color = p.theme.mutedText,
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
 * type), standing in for Mushroom's own `ha-control-select-menu`. `tall` is
 * the "dropdown" layout: the pill is the whole card, so it gets a card's
 * height and surface.
 */
@Composable
private fun SelectMenuControl(
    current: String?,
    options: List<String>,
    fillWidth: Boolean,
    fontSize: Float,
    tall: Boolean,
    theme: ThemeColors,
    onSelect: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val height = if (tall) maxOf(52f, fontSize * 2.6f) else maxOf(36f, fontSize * 2.4f)

    Box(modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier.width(140.dp)) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(height.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(if (tall) theme.cardSurface else theme.insetSurface)
                .tapClickable(enabled = options.isNotEmpty()) { expanded = true }
                .padding(horizontal = if (tall) 16.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                current ?: "—",
                color = theme.primaryText,
                fontSize = fontSize.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f, fill = false)
            )
            Icon(
                Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = if (options.isEmpty()) theme.mutedText else theme.iconTint
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.background(theme.cardSurface).widthIn(max = 400.dp)
        ) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            option,
                            color = if (option == current) theme.accent else theme.primaryText,
                            fontSize = maxOf(14f, fontSize).sp,
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
private fun DefaultLayout(p: SelectParts, control: @Composable (fillWidth: Boolean) -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.theme.cardSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            SelectIcon(p)
            Spacer(Modifier.width(12.dp))
            NameState(p)
        }
        control(true)
    }
}

// ---- "horizontal": icon + name/state on the left, control on the right ----

@Composable
private fun HorizontalLayout(p: SelectParts, control: @Composable (fillWidth: Boolean) -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.theme.cardSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SelectIcon(p)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) { NameState(p) }
        Spacer(Modifier.width(12.dp))
        control(false)
    }
}

// ---- "vertical": icon, name, state, control — all centered and stacked ----

@Composable
private fun VerticalLayout(p: SelectParts, control: @Composable (fillWidth: Boolean) -> Unit) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(p.theme.cardSurface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        SelectIcon(p, size = 48.dp)
        Spacer(Modifier.height(8.dp))
        NameState(p, horizontalAlignment = Alignment.CenterHorizontally, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        control(true)
    }
}
