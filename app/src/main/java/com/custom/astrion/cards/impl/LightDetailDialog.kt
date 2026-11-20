package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ScreenCapture
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Long-press detail popup for a light (bubble-card style): a big vertical
 * brightness pill, a power toggle, and — only when the light's
 * `supported_color_modes` allow — color swatches and color-temperature
 * presets. Everything fires standard light.turn_on/turn_off service data
 * (brightness_pct / rgb_color / color_temp_kelvin).
 *
 * 1.5.8: one panel at a time under the pill — **Colour** (the card's swatches, `colors`, and
 * a hue/saturation wheel, `hs_color`, under or above them or left out, `color_wheel`), **White** (a continuous slider within the light's
 * min/max kelvin, then the presets) and **Effects** (the light's `effect_list`).
 */
@Composable
fun LightDetailDialog(
    entityId: String,
    name: String,
    e: EntityState?,
    client: HaClient,
    theme: ThemeColors = ThemeColors.Default,
    style: LightPopupStyle = LightPopupStyle(),
    onClose: () -> Unit
) {
    val on = e?.isOn == true
    val actions = remember(entityId, client) { DialogActions(entityId, client) }
    val effects = e?.attrStringList("effect_list") ?: emptyList()
    val panels = lightPanels(e, effects)
    var panel by remember(entityId) {
        mutableStateOf(
            if (e?.attrString("color_mode") == "color_temp" && LightPanel.WHITE in panels) LightPanel.WHITE else panels.firstOrNull()
        )
    }

    Dialog(onDismissRequest = onClose) {
        ScreenCapture.TrackForScreenshots()
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            BrightnessAndPower(entityId, name, e, client, theme, PillLayout(compact = panels.isNotEmpty(), powerLeft = style.powerLeft))

            if (panels.size > 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    panels.forEach { p -> LightChip(stringResource(p.label), p == panel, theme) { panel = p } }
                }
            }

            when (panel) {
                LightPanel.COLOR -> ColorPanel(entityId, e, style, actions)
                LightPanel.WHITE -> WhitePanel(entityId, e, on, theme, actions)
                LightPanel.EFFECT -> EffectPanel(effects, e?.attrString("effect"), theme, actions)
                null -> Unit
            }
        }
    }
}

/** The live percentage, the light's name, the brightness pill and the power button. */
@Composable
private fun BrightnessAndPower(entityId: String, name: String, e: EntityState?, client: HaClient, theme: ThemeColors, layout: PillLayout) {
    val on = e?.isOn == true
    val level = lightLevel(e)
    var dragLevel by remember(level) { mutableFloatStateOf(level) }
    val fillColor = lightFillColor(e, theme.amber)

    fun commit(fraction: Float) {
        val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        if (pct <= 0) {
            client.callService(ServiceCall("light", "turn_off", entityId))
        } else {
            client.callService(ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct))
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
        // The percentage centred, the power button on the side the card chose (popup_power).
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                "${(dragLevel * 100).roundToInt()}%",
                color = theme.primaryText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Box(
                modifier =
                Modifier
                    .align(if (layout.powerLeft) Alignment.CenterStart else Alignment.CenterEnd)
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (on) theme.amber else theme.controlBackground)
                    .tapClickable(focusShape = CircleShape) { client.toggle(entityId) },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = "Toggle",
                    tint = if (on) Color(0xFF241A00) else theme.iconTint
                )
            }
        }
        Text(name, color = theme.mutedText, fontSize = 13.sp)

        // Vertical brightness pill: drag or tap to set; fill rises from the bottom.
        Box(
            modifier =
            Modifier
                .width(if (layout.compact) 90.dp else 120.dp)
                .height(if (layout.compact) 170.dp else 230.dp)
                .clip(RoundedCornerShape(30.dp))
                .background(theme.insetSurface)
                .pointerInput(entityId) {
                    detectVerticalDragGestures(
                        onDragEnd = { commit(dragLevel) }
                    ) { change, _ ->
                        dragLevel = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    }
                }.pointerInput(entityId) {
                    detectTapGestures { offset ->
                        val f = (1f - offset.y / size.height).coerceIn(0f, 1f)
                        dragLevel = f
                        commit(f)
                    }
                }
        ) {
            Box(
                modifier =
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(dragLevel.coerceIn(0.02f, 1f))
                    .background(fillColor.copy(alpha = if (on) 0.9f else 0.35f))
            )
        }
    }
}

/** The pill's size (smaller when panels follow) and the power button's side. */
private data class PillLayout(val compact: Boolean, val powerLeft: Boolean)

/** The light's brightness as 0–1 (0 while off, full when it has no brightness). */
private fun lightLevel(e: EntityState?): Float {
    if (e?.isOn != true) return 0f
    val brightness = e.attrInt("brightness") ?: return 1f
    return (brightness / 255f).coerceIn(0f, 1f)
}

/** The pill's fill: the light's live rgb_color where available. */
private fun lightFillColor(e: EntityState?, fallback: Color): Color {
    val rgb = e?.attr("rgb_color") as? JsonArray
    if (rgb == null || rgb.size < 3) return fallback
    fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255) ?: 255
    return Color(ch(0), ch(1), ch(2))
}

/** Which panels the light can use: colour, white, effects. */
private fun lightPanels(e: EntityState?, effects: List<String>): List<LightPanel> {
    val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()

    @Suppress("SpellCheckingInspection")
    val hasColor = colorModes.any { it in listOf("hs", "rgb", "rgbw", "rgbww", "xy") }
    val hasTemp = colorModes.contains("color_temp") || hasColor
    return buildList {
        if (hasColor) add(LightPanel.COLOR)
        if (hasTemp) add(LightPanel.WHITE)
        if (effects.isNotEmpty()) add(LightPanel.EFFECT)
    }
}

private enum class LightPanel(val label: Int) {
    COLOR(R.string.light_panel_color),
    WHITE(R.string.light_panel_white),
    EFFECT(R.string.light_panel_effect)
}

/** The service calls the popup's panels send. */
private class DialogActions(private val entityId: String, private val client: HaClient) {
    fun rgb(r: Int, g: Int, b: Int) {
        client.callService(
            ServiceCall(
                "light",
                "turn_on",
                entityId,
                mapOf("rgb_color" to JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b))))
            )
        )
    }

    fun hs(hs: HueSat) {
        client.callService(
            ServiceCall(
                "light",
                "turn_on",
                entityId,
                mapOf("hs_color" to JsonArray(listOf(JsonPrimitive(hs.hue.roundToInt()), JsonPrimitive(hs.sat.roundToInt()))))
            )
        )
    }

    fun kelvin(k: Int) {
        client.callService(ServiceCall.of("light", "turn_on", entityId, "color_temp_kelvin" to k))
    }

    fun effect(name: String) {
        client.callService(ServiceCall.of("light", "turn_on", entityId, "effect" to name))
    }
}

/** The card's swatches and the colour wheel, above, under or left out (`color_wheel`). */
@Composable
private fun ColorPanel(entityId: String, e: EntityState?, style: LightPopupStyle, actions: DialogActions) {
    val hsList = (e?.attr("hs_color") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content?.toDoubleOrNull() }
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val wheel: @Composable () -> Unit = {
            LightColorWheel(
                key = entityId,
                current = LightColors.parseHs(hsList),
                description = stringResource(R.string.light_color_wheel),
                modifier = Modifier.widthIn(max = 300.dp).fillMaxWidth(WHEEL_WIDTH).aspectRatio(1f),
                onCommit = actions::hs
            )
        }
        if (style.wheel == WheelPlace.TOP) wheel()
        style.swatches.chunked(SWATCHES_PER_ROW).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (r, g, b) ->
                    Box(
                        modifier =
                        Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(r, g, b))
                            .tapClickable(focusShape = CircleShape) { actions.rgb(r, g, b) }
                    )
                }
            }
        }
        if (style.wheel == WheelPlace.BOTTOM) wheel()
    }
}

/** A continuous warm-to-cool slider within the light's own range, then the presets. */
@Composable
private fun WhitePanel(entityId: String, e: EntityState?, on: Boolean, theme: ThemeColors, actions: DialogActions) {
    val minK = e?.attrInt("min_color_temp_kelvin") ?: DEFAULT_MIN_KELVIN
    val maxK = e?.attrInt("max_color_temp_kelvin") ?: DEFAULT_MAX_KELVIN
    val currentK = e?.attrInt("color_temp_kelvin") ?: ((minK + maxK) / 2)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ColorTempSlider(entityId, on, minK, maxK, currentK, actions::kelvin)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WHITE_PRESETS.forEach { (label, k) ->
                Box(Modifier.weight(1f)) {
                    LightChip(stringResource(label), false, theme, Modifier.fillMaxWidth()) { actions.kelvin(k.coerceIn(minK, maxK)) }
                }
            }
        }
    }
}

/** The light's effects (WLED, LED strips…), the running one highlighted. */
@Composable
private fun EffectPanel(effects: List<String>, current: String?, theme: ThemeColors, actions: DialogActions) {
    Column(
        modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        effects.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { name ->
                    Box(Modifier.weight(1f)) { LightChip(name, name == current, theme, Modifier.fillMaxWidth()) { actions.effect(name) } }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun LightChip(label: String, selected: Boolean, theme: ThemeColors, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier =
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) theme.amber else theme.controlBackground)
            .tapClickable(focusShape = RoundedCornerShape(12.dp), onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) Color(0xFF241A00) else theme.iconTint,
            fontSize = 12.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private const val SWATCHES_PER_ROW = 5
private const val WHEEL_WIDTH = 0.8f
private const val DEFAULT_MIN_KELVIN = 2000
private const val DEFAULT_MAX_KELVIN = 6535

private val WHITE_PRESETS =
    listOf(
        R.string.light_preset_candle to 2200,
        R.string.light_preset_warm to 2700,
        R.string.light_preset_neutral to 4000,
        R.string.light_preset_cool to 5500
    )
