package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.HaLabels
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * Fan card. Two layouts, auto-picked from what the entity actually reports
 * (or forced via the `style` option):
 *
 * - **simple** (default when the entity has neither `preset_modes` nor
 *   `oscillating`): the original compact single-row tile — tap to toggle,
 *   chevrons step `percentage` up/down. Unchanged behavior for plain
 *   percentage-only fans.
 * - **full** (auto-picked when the entity reports `preset_modes` and/or an
 *   `oscillating` attribute — e.g. many Xiaomi/Smart-Fan integrations, which
 *   drive speed via named presets like "Level 1"..."Level 4" rather than a
 *   0-100 percentage): a bigger card with a dedicated power button, preset
 *   chips (or a percentage stepper if there are no presets), and an
 *   oscillate on/off toggle.
 * - **step**: a minimal single row — left-justified "-", center percentage
 *   display, right-justified "+". Each press steps `percentage` by `step`
 *   (e.g. `step: 13` → 13→26→39…). No name, no power button; just the
 *   stepper. Tap-to-toggle isn't exposed in this layout.
 *
 * Uses `fan.toggle`, `fan.set_percentage`, `fan.set_preset_mode`, and `fan.oscillate`.
 *
 * Config shape:
 * ```json
 * {
 *   "type": "fan",
 *   "options": {
 *     "entity_id": "fan.mi_smart_standing_fan_2",
 *     "name": "Standing fan",
 *     "style": "auto",
 *     "preset_modes": ["Level 1", "Level 2", "Level 3", "Level 4"],
 *     "step": 20
 *   }
 * }
 * ```
 * `style` — `"auto"` (default), `"simple"`, `"step"`, or `"full"`. `preset_modes` is an
 * optional override (order respected), same pattern as `ClimateCard`'s
 * `fan_modes`/`swing_modes`: normally read straight from the entity so it
 * always matches what the device actually supports. `"off"` is always
 * excluded from the preset chips — the card's own power button covers it.
 */
class FanCard : CardRenderer {
    override val type = "fan"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val on = e?.isOn == true
        val percentage = e?.attrInt("percentage")
        val step =
            (config.options["step"] as? Number)?.toInt()
                ?: e?.attrInt("percentage_step")
                ?: 20
        val presetModes =
            config
                .stringList("preset_modes")
                .ifEmpty { e?.attrStringList("preset_modes").orEmpty() }
                .filter { !it.equals("off", ignoreCase = true) }
        val presetMode = e?.attrString("preset_mode")
        // Presence of the attribute (not its value) is what indicates support —
        // a fan that can't oscillate simply doesn't report this attribute at all.
        val oscillateSupported = e?.attr("oscillating") != null
        val oscillating = e?.attrBoolean("oscillating") == true

        val styleOpt = config.string("style")
        val useStep = styleOpt == "step"
        val useFull = !useStep && (styleOpt == "full" || (styleOpt != "simple" && (presetModes.isNotEmpty() || oscillateSupported)))
        val showCaptions = config.options["show_captions"] as? Boolean ?: true

        fun setPercentage(p: Int) = ctx.client.callService(
            ServiceCall.of("fan", "set_percentage", entityId, "percentage" to p.coerceIn(0, 100))
        )

        fun setPreset(m: String) = ctx.client.callService(
            ServiceCall.of("fan", "set_preset_mode", entityId, "preset_mode" to m)
        )

        fun setOscillate(v: Boolean) = ctx.client.callService(
            ServiceCall.of("fan", "oscillate", entityId, "oscillating" to v)
        )

        if (useStep) {
            val pct = percentage ?: 0
            val explicitName = config.string("name")

            fun changeSpeed(direction: String) = ctx.client.callService(
                ServiceCall.of("fan", direction, entityId)
            )
            Column(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(ctx.theme.controlBackground)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                if (explicitName != null) {
                    Text(
                        explicitName,
                        color = ctx.theme.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    StepBtn("-", ctx.theme) { changeSpeed("decrease_speed") }
                    Text(
                        if (on) "$pct%" else stringResource(R.string.astrion_state_off),
                        color = ctx.theme.primaryText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    StepBtn("+", ctx.theme) { changeSpeed("increase_speed") }
                }
            }
            return
        }

        if (!useFull) {
            val pct = percentage ?: 0
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (on) ctx.theme.success.copy(alpha = 0.25f) else ctx.theme.controlBackground)
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(
                    modifier =
                    Modifier
                        .weight(1f)
                        .tapClickable { ctx.client.toggle(entityId) }
                ) {
                    Text(name, color = ctx.theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (on) "$pct%" else stringResource(R.string.astrion_state_off),
                        color = ctx.theme.mutedText,
                        fontSize = 13.sp
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircleBtn(Icons.Filled.KeyboardArrowDown, ctx.theme) { setPercentage(pct - step) }
                    CircleBtn(Icons.Filled.KeyboardArrowUp, ctx.theme) { setPercentage(pct + step) }
                }
            }
            return
        }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: name + a dedicated power button (mirrors ClimateCard).
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, color = ctx.theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    modifier =
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(if (on) ctx.theme.success.copy(alpha = 0.25f) else ctx.theme.danger.copy(alpha = 0.25f))
                        .tapClickable(focusShape = CircleShape) { ctx.client.toggle(entityId) },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = "Power",
                        tint = if (on) ctx.theme.success else ctx.theme.danger,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Speed: preset chips if the entity has named presets, else a
            // plain percentage stepper (same control as simple mode).
            if (presetModes.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showCaptions) {
                        Text(
                            stringResource(R.string.fan_preset_caption),
                            color = ctx.theme.mutedText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        balancedChunks(presetModes, maxPerRow = 4).forEach { row ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                row.forEach { m ->
                                    FanChip(
                                        label = m,
                                        selected = on && presetMode?.equals(m, ignoreCase = true) == true,
                                        theme = ctx.theme,
                                        modifier = Modifier.weight(1f)
                                    ) { setPreset(m) }
                                }
                            }
                        }
                    }
                }
            } else if (percentage != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircleBtn(Icons.Filled.KeyboardArrowDown, ctx.theme) { setPercentage(percentage - step) }
                    Text(
                        "$percentage%",
                        color = ctx.theme.primaryText,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    CircleBtn(Icons.Filled.KeyboardArrowUp, ctx.theme) { setPercentage(percentage + step) }
                }
            }

            // Oscillate toggle.
            if (oscillateSupported) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (showCaptions) {
                        Text(
                            stringResource(R.string.fan_oscillate_caption),
                            color = ctx.theme.mutedText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    FanChip(
                        // Reuses ClimateCard's swing_mode "on"/"off" translation —
                        // same concept (oscillation on/off), no need for a second key.
                        label = HaLabels.swingMode(if (oscillating) "on" else "off"),
                        selected = oscillating,
                        theme = ctx.theme,
                        modifier = Modifier.fillMaxWidth()
                    ) { setOscillate(!oscillating) }
                }
            }
        }
    }
}

/** Small text-only chip, used by [FanCard]'s preset and oscillate rows. */
@Composable
private fun FanChip(label: String, selected: Boolean, modifier: Modifier, theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        modifier
            .height(34.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(if (selected) theme.accentSecondary else theme.controlBackground)
            .tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color.White else theme.mutedText,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium
        )
    }
}

/**
 * Split [items] into rows of at most [maxPerRow], balanced (e.g. 5 items @
 * max 4 -> 3+2, not a lopsided 4+1). Used by [FanCard]; [ClimateCard] has its
 * own private copy of the same logic.
 */
@Suppress("SameParameterValue")
private fun <T> balancedChunks(items: List<T>, maxPerRow: Int): List<List<T>> {
    if (items.isEmpty()) return emptyList()
    val rows = (items.size + maxPerRow - 1) / maxPerRow
    val perRow = (items.size + rows - 1) / rows
    return items.chunked(perRow)
}

/** Shared small circular icon button used by [FanCard]'s simple layout. */
@Composable
private fun CircleBtn(icon: ImageVector, theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(theme.controlBackground)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = theme.iconTint)
    }
}

/** Text-based "-" / "+" button used by [FanCard]'s step layout. */
@Composable
private fun StepBtn(label: String, theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(theme.insetSurface)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = theme.iconTint,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold
        )
    }
}
