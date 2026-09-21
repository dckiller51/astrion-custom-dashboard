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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
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
import com.custom.astrion.ui.icons.MdiIcons
import com.custom.astrion.ui.tapClickable

/**
 * Climate / thermostat card renderer.
 *
 * Displays target setpoint with steppers, current temperature, and HVAC/fan/swing
 * mode chips — mirroring Home Assistant's own climate tile-card features
 * (`climate-hvac-modes`, `climate-fan-modes`, `climate-swing-modes`), including
 * their per-feature `style: icons` option.
 * Uses `climate.set_temperature`, `climate.set_hvac_mode`, `climate.set_fan_mode`,
 * and `climate.set_swing_mode`.
 *
 * Config shape:
 * ```json
 * {
 *   "type": "climate",
 *   "options": {
 *     "entity_id": "climate.lounge",
 *     "step": 0.5,
 *     "hvac_modes": ["heat_cool", "cool"],
 *     "hvac_mode_style": "icons",
 *     "fan_modes": ["low", "medium", "high", "auto"],
 *     "fan_mode_style": "icons",
 *     "swing_modes": ["stop", "swing"],
 *     "swing_mode_style": "icons"
 *   }
 * }
 * ```
 * `hvac_modes`/`fan_modes`/`swing_modes` are optional overrides — normally the
 * chips come from the entity's own `hvac_modes`/`fan_modes`/`swing_modes`
 * attributes, so they always match what the device actually supports and
 * follow whatever order the integration reports. Set one in the dashboard
 * config to reorder or restrict which chips show (in the order given), or as
 * a last-resort fallback if an integration doesn't report the attribute at
 * all. `"off"` is always excluded from the hvac chips regardless of source —
 * see below.
 *
 * `hvac_mode_style`/`fan_mode_style`/`swing_mode_style` (default `"icons"`
 * for hvac, `"label"` for fan/swing) switch that row's chips to icons, same
 * as HA's tile card. The icon glyphs come from [MdiIcons] (hand-built from
 * MDI path data — see that file for why). The `"off"` hvac mode is never
 * shown as a chip — the header's power button already covers it, so a
 * separate "off" chip would just be a duplicate control.
 */
@Suppress("SpellCheckingInspection")
class ClimateCard : CardRenderer {
    override val type = "climate"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        // Prefer the entity's real step; a 1° aircon ignores 0.5° changes and
        // the down button looks broken (25.5 rounds back to 26).
        val step =
            e?.attrDouble("target_temp_step")
                ?: (config.options["step"] as? Number)?.toDouble()
                ?: 1.0
        val minT = e?.attrDouble("min_temp")
        val maxT = e?.attrDouble("max_temp")

        val target = e?.attrDouble("temperature")
        val targetHigh = e?.attrDouble("target_temp_high")
        val targetLow = e?.attrDouble("target_temp_low")
        val current = e?.attrDouble("current_temperature")
        val mode = e?.state ?: "off"
        // heat_cool mode uses a temp range (target_temp_low..target_temp_high)
        // instead of a single setpoint — temperature is null in that mode.
        val isRange = target == null && targetHigh != null && targetLow != null
        // "off" is deliberately excluded — the header's dedicated power
        // button already turns the unit off, so a chip for it would just
        // duplicate that control.
        // Prefer the config override (order respected as given) → the
        // entity's real hvac_modes → nothing. "off" is deliberately excluded
        // in all cases — see comment above.
        val modes =
            config
                .stringList("hvac_modes")
                .ifEmpty { e?.attrStringList("hvac_modes").orEmpty() }
                .filter { it != "off" }
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val fanMode = e?.attrString("fan_mode")
        val swingMode = e?.attrString("swing_mode")
        // Prefer what the entity actually reports it supports (like hvac_modes
        // above); a config override lets you reorder/restrict; the hardcoded
        // list is only a last resort for integrations that don't report it.
        val fanModes =
            config
                .stringList("fan_modes")
                .ifEmpty { e?.attrStringList("fan_modes").orEmpty() }
                .ifEmpty { listOf("low", "medium", "high", "auto") }
        val swingModes =
            config
                .stringList("swing_modes")
                .ifEmpty { e?.attrStringList("swing_modes").orEmpty() }
        val hvacModeIcons = config.string("hvac_mode_style") != "label"
        val fanModeIcons = config.string("fan_mode_style") == "icons"
        val swingModeIcons = config.string("swing_mode_style") == "icons"
        val showCaptions = config.options["show_captions"] as? Boolean ?: true

        fun setTemp(t: Double) {
            val clamped = t.coerceIn(minT ?: t, maxT ?: t)
            ctx.client.callService(
                ServiceCall.of("climate", "set_temperature", entityId, "temperature" to clamped)
            )
        }

        // In heat_cool mode, shift both bounds together by `delta` — keeps the
        // deadband intact. HA's set_temperature service accepts
        // target_temp_high/target_temp_low for range mode.
        fun shiftRange(delta: Double) {
            val lo = (targetLow!! + delta).coerceIn(minT ?: Double.NEGATIVE_INFINITY, maxT ?: Double.POSITIVE_INFINITY)
            val hi = (targetHigh!! + delta).coerceIn(minT ?: Double.NEGATIVE_INFINITY, maxT ?: Double.POSITIVE_INFINITY)
            ctx.client.callService(
                ServiceCall.of(
                    "climate",
                    "set_temperature",
                    entityId,
                    "target_temp_low" to lo,
                    "target_temp_high" to hi
                )
            )
        }

        fun setMode(m: String) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_hvac_mode", entityId, "hvac_mode" to m)
            )
        }

        fun setFan(f: String) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_fan_mode", entityId, "fan_mode" to f)
            )
        }

        fun setSwing(s: String) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_swing_mode", entityId, "swing_mode" to s)
            )
        }

        fun turnOff() {
            ctx.client.callService(ServiceCall("climate", "turn_off", entityId))
        }

        val isOff = mode == "off"

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // Header: name + a dedicated off button.
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
                        .background(if (isOff) ctx.theme.danger.copy(alpha = 0.25f) else ctx.theme.controlBackground)
                        .tapClickable(focusShape = CircleShape) { turnOff() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = "Off",
                        tint = if (isOff) ctx.theme.danger else ctx.theme.iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Setpoint with steppers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Stepper(Icons.Filled.Remove, ctx.theme) {
                    if (isRange) shiftRange(-step) else target?.let { setTemp(it - step) }
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        when {
                            isRange -> "${trim(targetLow!!)}-${trim(targetHigh!!)}°"
                            target != null -> "${trim(target)}°"
                            else -> "—"
                        },
                        color = ctx.theme.primaryText,
                        fontSize = if (isRange) 28.sp else 36.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        current?.let { stringResource(R.string.climate_current_temp, trim(it)) } ?: "",
                        color = ctx.theme.mutedText,
                        fontSize = 12.sp
                    )
                }

                Stepper(Icons.Filled.Add, ctx.theme) {
                    if (isRange) shiftRange(step) else target?.let { setTemp(it + step) }
                }
            }

            // HVAC mode chips (icons by default, matching HA's tile card; "off" excluded, see above).
            if (modes.isNotEmpty()) {
                ModeSection(caption = if (showCaptions) stringResource(R.string.climate_hvac_caption) else null, theme = ctx.theme) {
                    balancedRows(modes, maxPerRow = if (hvacModeIcons) 5 else 3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { m ->
                                ModeChip(
                                    label = HaLabels.hvacMode(m),
                                    icon = if (hvacModeIcons) hvacModeIcon(m) else null,
                                    selected = m == mode,
                                    theme = ctx.theme,
                                    modifier = Modifier.weight(1f)
                                ) { setMode(m) }
                            }
                        }
                    }
                }
            }

            // Fan mode chips
            if (fanModes.isNotEmpty()) {
                ModeSection(caption = if (showCaptions) stringResource(R.string.climate_fan_caption) else null, theme = ctx.theme) {
                    balancedRows(fanModes, maxPerRow = if (fanModeIcons) 5 else 3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { f ->
                                ModeChip(
                                    label = HaLabels.fanMode(f),
                                    icon = if (fanModeIcons) fanModeIcon(f) else null,
                                    selected = fanMode?.equals(f, ignoreCase = true) == true,
                                    theme = ctx.theme,
                                    modifier = Modifier.weight(1f)
                                ) { setFan(f) }
                            }
                        }
                    }
                }
            }

            // Swing mode chips
            if (swingModes.isNotEmpty()) {
                ModeSection(caption = if (showCaptions) stringResource(R.string.climate_swing_caption) else null, theme = ctx.theme) {
                    balancedRows(swingModes, maxPerRow = if (swingModeIcons) 5 else 3).forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            row.forEach { s ->
                                ModeChip(
                                    label = HaLabels.swingMode(s),
                                    icon = if (swingModeIcons) swingModeIcon(s) else null,
                                    selected = swingMode?.equals(s, ignoreCase = true) == true,
                                    theme = ctx.theme,
                                    modifier = Modifier.weight(1f)
                                ) { setSwing(s) }
                            }
                        }
                    }
                }
            }
        }
    }

    private fun trim(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    /**
     * Split [items] into rows of at most [maxPerRow], but balanced: e.g. 5
     * items with maxPerRow=4 gives two rows of 3+2, not a lopsided 4+1 with
     * one lonely chip. Small screens (this targets 480×800 panels) especially
     * suffer from an orphaned last-row chip stretching to full width.
     */
    private fun <T> balancedRows(items: List<T>, maxPerRow: Int): List<List<T>> {
        if (items.isEmpty()) return emptyList()
        val rows = (items.size + maxPerRow - 1) / maxPerRow
        val perRow = (items.size + rows - 1) / rows
        return items.chunked(perRow)
    }

    /** HVAC mode → MDI glyph, matching what HA's own climate tile-card feature shows. */
    private fun hvacModeIcon(mode: String): ImageVector = when (mode) {
        "heat" -> MdiIcons.Fire
        "cool" -> MdiIcons.Snowflake
        "heat_cool", "auto" -> MdiIcons.HeatCool
        "dry" -> MdiIcons.WaterPercent
        "fan_only" -> MdiIcons.Fan
        else -> MdiIcons.Power
    }

    /**
     * Fan-speed → MDI glyph. Numeric speeds ("1"..."5") show their digit —
     * that's the actual MDI glyph for them, not a placeholder. "auto" and
     * "quiet"/"silent" have their own distinct glyphs too. Anything else
     * (e.g. "low"/"medium"/"high"/"turbo") falls back to the generic fan
     * glyph, since MDI has no dedicated icon for those.
     */
    private fun fanModeIcon(mode: String): ImageVector = when (mode.lowercase()) {
        "auto" -> MdiIcons.FanAuto
        "quiet", "silent" -> MdiIcons.FanQuiet
        "1" -> MdiIcons.Fan1
        "2" -> MdiIcons.Fan2
        "3" -> MdiIcons.Fan3
        "4" -> MdiIcons.Fan4
        "5" -> MdiIcons.Fan5
        else -> MdiIcons.Fan
    }

    /** Swing mode → glyph. Covers both HA's official values (off/on/both/vertical/horizontal)
     *  and the "stop"/"swing" values some integrations (e.g. this Daikin one) use instead. */
    private fun swingModeIcon(mode: String): ImageVector = when (mode.lowercase()) {
        "off", "stop" -> MdiIcons.SwingOff
        else -> MdiIcons.SwingOn
    }

    @Composable
    private fun Stepper(icon: ImageVector, theme: ThemeColors, onClick: () -> Unit) {
        Box(
            modifier =
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(theme.controlBackground)
                .tapClickable(focusShape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = theme.iconTint)
        }
    }

    @Composable
    private fun ModeSection(caption: String?, theme: ThemeColors, content: @Composable () -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (caption != null) {
                Text(
                    caption,
                    color = theme.mutedText,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                content()
            }
        }
    }

    @Composable
    private fun ModeChip(
        label: String,
        selected: Boolean,
        modifier: Modifier,
        theme: ThemeColors,
        icon: ImageVector? = null,
        onClick: () -> Unit
    ) {
        Box(
            modifier =
            modifier
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) theme.accentSecondary else theme.controlBackground)
                .tapClickable(focusShape = RoundedCornerShape(10.dp), onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val tint = if (selected) Color.White else theme.mutedText
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(18.dp))
            } else {
                Text(label, color = tint, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}
