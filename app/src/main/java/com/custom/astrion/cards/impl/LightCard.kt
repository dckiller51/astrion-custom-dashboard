package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.icons.MdiIcons
import com.custom.astrion.ui.tapClickable
import kotlin.math.roundToInt
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Light card — styled after Home Assistant's Mushroom light card, including
 * its 3 layout options AND its Mushroom-style configurable controls
 * (show_brightness_control / show_color_temp_control / show_color_control /
 * collapsible_controls), same as [CoverCard]'s buttons/position/tilt setup.
 *
 * Tap toggles the light; long-press opens LightDetailDialog for the full
 * brightness/color/color-temperature popup — unchanged, only how you get to
 * it (previously the bubble pill, now any of the 3 layouts here).
 * The icon itself swaps between MdiIcons.LightbulbOn (lit) and
 * MdiIcons.LightbulbOff (slashed) depending on state, Mushroom-style.
 *
 * Uses `light.toggle`, `light.turn_on`, and `light.turn_off`.
 *
 * Config shape:
 *   { "type": "light", "options": {
 *       "entity_id": "light.kitchen", "name": "Kitchen",
 *       "layout": "default"
 *       //   "default"    — icon + name/state row, controls area full-width
 *       //                  below it (Mushroom's own default look)
 *       //   "horizontal" — icon + name/state, controls inline on the right
 *       //   "vertical"   — icon, name, state, controls — all centered/stacked
 *       "use_light_color": false,
 *       //   When true and the light reports a rgb_color, the icon (and the
 *       //   brightness control's fill) tint with that color instead of the
 *       //   plain amber "on" look.
 *       "show_brightness": true,
 *       //   When true (default) the state line reads "N%" while the light
 *       //   is on and reports a brightness; set false to always show a
 *       //   plain "On" instead. (Only affects the text label.)
 *
 *       // Mushroom-style controls — same 3 flags as the Mushroom light
 *       // card, all independent. When none of the 3 are present at all,
 *       // the card falls back to its historical behaviour (brightness bar
 *       // only, "default" layout) so existing configs keep working
 *       // untouched. As soon as ANY of the 3 is set, only what's
 *       // explicitly enabled shows — exactly like Mushroom.
 *       "show_brightness_control": true,   // draggable brightness slider
 *       "show_color_temp_control": false,  // draggable warm<->cool slider
 *       "show_color_control": false,       // tappable color swatches
 *       "collapsible_controls": false,
 *       //   When true, the whole controls area hides while the light is
 *       //   off (Mushroom's own default). When false (default here, for
 *       //   backward compatibility) controls always show.
 *       //   When more than one control is enabled, only the first is shown
 *       //   at a time; a small chevron button cycles to the next one — same
 *       //   as Mushroom.
 *   } }
 */
class LightCard : CardRenderer {
    override val type = "light"

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val isOn = e?.isOn == true
        val useLightColor = config.bool("use_light_color", false)
        val showBrightness = config.bool("show_brightness", true)

        // brightness attribute is 0..255; convert to a 0..100 percent (shows 0 while off).
        val brightnessPct =
            if (isOn) e?.attrInt("brightness")?.let { (it / 255f * 100).roundToInt().coerceIn(0, 100) } else 0

        // The brightness slider holds its in-progress value after a commit until
        // the entity catches up, and some integrations report the *retained*
        // brightness when a light turns on — so the state label reads that same
        // pending value (when one is active) instead of the live entity value,
        // or it would disagree with the slider. The state is hoisted here so
        // both the label and the slider share one source of truth.
        val brightnessDrag = rememberDragFraction(entityId)
        val displayedBrightnessPct =
            if (brightnessDrag.value >= 0f) (brightnessDrag.value * 100).roundToInt() else brightnessPct
        val stateLabel = lightStateLabel(isOn, showBrightness, displayedBrightnessPct)

        val rgb = e?.attr("rgb_color") as? JsonArray
        val appearance = resolveLightAppearance(isOn, useLightColor, rgb, ctx.theme)
        val controlsConfig = resolveLightControls(config, e, isOn)

        // Long-press anywhere on the tile opens the detail dialog; a plain
        // tap toggles — same gestures BubbleLightCard used, minus the drag.
        // combinedClickable (not a raw pointerInput/detectTapGestures) so the
        // whole tile stays a normal focusable/clickable target: without it,
        // the tile never enters Compose's focus system and a physical
        // remote's D-pad skips over it entirely when navigating the page.
        // rememberLongPressKeyModifier separately covers a held hardware
        // key, which combinedClickable's onLongClick never does on its own
        // — see that function's doc for why.
        var showDetail by remember { mutableStateOf(false) }
        val tileGestureModifier =
            rememberLongPressKeyModifier(entityId) { showDetail = true }
                .combinedClickable(
                    onClick = { ctx.client.toggle(entityId) },
                    onLongClick = { showDetail = true }
                )

        val actions = remember(entityId, ctx.client) { LightActions(entityId, ctx.client) }
        val rowData = LightRowData(entityId, isOn, brightnessPct ?: 0, appearance, ctx.theme, controlsConfig, actions)

        // Hoisted here (not inside a layout function) so the active-control
        // state survives regardless of which layout renders it.
        var activeControl by remember(entityId) { mutableStateOf(controlsConfig.controls.firstOrNull()) }
        val resolvedActive = activeControl?.takeIf { it in controlsConfig.controls } ?: controlsConfig.controls.firstOrNull()

        // Controls live outside the tile's tap/long-press area so dragging
        // them doesn't also toggle the light or open the dialog.
        val controlsSlot: @Composable (fillWidth: Boolean) -> Unit = { fillWidth ->
            LightControlsRow(
                fillWidth = fillWidth,
                data = rowData,
                resolvedActive = resolvedActive,
                brightnessDrag = brightnessDrag,
                onCycle = {
                    val idx = controlsConfig.controls.indexOf(resolvedActive)
                    activeControl = controlsConfig.controls[(idx + 1) % controlsConfig.controls.size]
                }
            )
        }

        LightTileLayout(
            layout = config.string("layout"),
            name = name,
            stateLabel = stateLabel,
            appearance = appearance,
            controlsSlot = controlsSlot,
            theme = ctx.theme,
            modifier = tileGestureModifier
        )

        if (showDetail) {
            LightDetailDialog(
                entityId = entityId,
                name = name,
                e = e,
                client = ctx.client,
                theme = ctx.theme,
                onClose = { showDetail = false }
            )
        }
    }
}

private enum class LightControl { BRIGHTNESS, COLOR_TEMP, COLOR }

/** Icon/colors resolved once per recomposition — pulled out of Render() to keep it short. */
private data class LightAppearance(val icon: ImageVector, val iconBg: Color, val iconTint: Color, val barColor: Color)

/** Which Mushroom-style controls are enabled/visible, plus the color-temp range — see the class doc above. */
private data class LightControlsConfig(
    val controls: List<LightControl>,
    val controlsVisible: Boolean,
    val minKelvin: Int,
    val maxKelvin: Int,
    val currentKelvin: Int
)

/** Fires light.turn_on/turn_off for the tile's various controls — pulled out of Render() to keep it short. */
private class LightActions(private val entityId: String, private val client: HaClient) {
    fun commitBrightness(fraction: Float) {
        when (val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()) {
            0 -> client.callService(ServiceCall(domain = "light", service = "turn_off", entityId = entityId))
            else -> client.callService(ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct))
        }
    }

    fun commitKelvin(kelvin: Int) {
        client.callService(ServiceCall.of("light", "turn_on", entityId, "color_temp_kelvin" to kelvin))
    }

    fun commitRgb(r: Int, g: Int, b: Int) {
        client.callService(
            ServiceCall(
                "light",
                "turn_on",
                entityId,
                mapOf("rgb_color" to JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b))))
            )
        )
    }
}

// 0% reads as "Off" (translated), otherwise the raw percentage — the
// light's own on/off state still wins when there's no brightness
// attribute to read at all (plain toggle-only lights).
@Composable
private fun lightStateLabel(isOn: Boolean, showBrightness: Boolean, brightnessPct: Int?): String = when {
    !isOn -> stringResource(R.string.light_state_off)
    !showBrightness || brightnessPct == null -> stringResource(R.string.light_state_on)
    brightnessPct <= 0 -> stringResource(R.string.light_state_off)
    else -> stringResource(R.string.light_brightness_pct, brightnessPct)
}

// Reflects the light's real color when it reports one and the option is on;
// otherwise falls back to the plain amber "on" look.
private fun resolveLightAppearance(isOn: Boolean, useLightColor: Boolean, rgb: JsonArray?, theme: ThemeColors): LightAppearance {
    val lightColor: Color? = if (isOn && useLightColor) parseRgbColor(rgb) else null

    val icon: ImageVector = if (isOn) MdiIcons.LightbulbOn else MdiIcons.LightbulbOff
    val iconBg: Color
    val iconTint: Color
    when {
        !isOn -> {
            iconBg = theme.controlBackground
            iconTint = theme.iconTint
        }
        lightColor != null -> {
            iconBg = lightColor.copy(alpha = 0.22f)
            iconTint = lightColor
        }
        else -> {
            iconBg = Color(0xFFFFC24B)
            iconTint = Color(0xFF241A00)
        }
    }
    return LightAppearance(icon = icon, iconBg = iconBg, iconTint = iconTint, barColor = lightColor ?: Color(0xFFFFC24B))
}

// Guard clause instead of a single 4-operand && — same behavior, one
// condition instead of one detekt was flagging as too complex.
private fun parseRgbColor(rgb: JsonArray?): Color? {
    if (rgb == null || rgb.size < 3) return null
    fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255)
    val r = ch(0)
    val g = ch(1)
    val b = ch(2)
    return if (r != null && g != null && b != null) Color(r, g, b) else null
}

// Mirrors Mushroom's light card: 3 independent flags. If none of the 3 keys
// are present in the config at all, falls back to the historical default
// (brightness control only) so existing dashboards keep rendering exactly
// as before.
private fun resolveLightControls(config: CardConfig, e: EntityState?, isOn: Boolean): LightControlsConfig {
    val opts = config.options
    val hasExplicitControls =
        opts.containsKey("show_brightness_control") ||
            opts.containsKey("show_color_temp_control") ||
            opts.containsKey("show_color_control")
    val showBrightnessControl = config.bool("show_brightness_control", !hasExplicitControls)
    val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()

    @Suppress("SpellCheckingInspection")
    val supportsColor = colorModes.any { it in listOf("hs", "rgb", "rgbw", "rgbww", "xy") }
    val supportsColorTemp = colorModes.contains("color_temp")
    val showColorTempControl = config.bool("show_color_temp_control", false) && supportsColorTemp
    val showColorControl = config.bool("show_color_control", false) && supportsColor
    val collapsibleControls = config.bool("collapsible_controls", false)

    val controls =
        buildList {
            if (showBrightnessControl) add(LightControl.BRIGHTNESS)
            if (showColorTempControl) add(LightControl.COLOR_TEMP)
            if (showColorControl) add(LightControl.COLOR)
        }
    val controlsVisible = controls.isNotEmpty() && (!collapsibleControls || isOn)

    val minKelvin = e?.attrInt("min_color_temp_kelvin") ?: 2000
    val maxKelvin = e?.attrInt("max_color_temp_kelvin") ?: 6535
    val currentKelvin = e?.attrInt("color_temp_kelvin") ?: ((minKelvin + maxKelvin) / 2)

    return LightControlsConfig(
        controls = controls,
        controlsVisible = controlsVisible,
        minKelvin = minKelvin,
        maxKelvin = maxKelvin,
        currentKelvin = currentKelvin
    )
}

/** Everything LightControlsRow needs about the light itself, bundled so the function stays under detekt's parameter-count limit. */
private data class LightRowData(
    val entityId: String,
    val isOn: Boolean,
    val brightnessPct: Int,
    val appearance: LightAppearance,
    val theme: ThemeColors,
    val controlsConfig: LightControlsConfig,
    val actions: LightActions
)

/** The active control's slider/swatches, plus the cycle button when more than one control is enabled. */
@Composable
private fun LightControlsRow(
    fillWidth: Boolean,
    data: LightRowData,
    resolvedActive: LightControl?,
    brightnessDrag: MutableFloatState,
    onCycle: () -> Unit
) {
    if (!data.controlsConfig.controlsVisible) return
    Row(
        modifier = if (fillWidth) Modifier.fillMaxWidth() else Modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Box(Modifier.weight(1f, fill = fillWidth)) {
            when (resolvedActive) {
                LightControl.BRIGHTNESS ->
                    PercentSlider(
                        entityId = "${data.entityId}-brightness",
                        isOn = data.isOn,
                        value = data.brightnessPct,
                        drag = brightnessDrag,
                        color = data.appearance.barColor,
                        theme = data.theme,
                        onCommit = { pct -> data.actions.commitBrightness(pct / 100f) }
                    )
                LightControl.COLOR_TEMP ->
                    ColorTempSlider(
                        entityId = "${data.entityId}-ctemp",
                        isOn = data.isOn,
                        minKelvin = data.controlsConfig.minKelvin,
                        maxKelvin = data.controlsConfig.maxKelvin,
                        kelvin = data.controlsConfig.currentKelvin,
                        onCommit = data.actions::commitKelvin
                    )
                LightControl.COLOR -> ColorSwatchRow(onPick = data.actions::commitRgb)
                null -> {}
            }
        }
        if (data.controlsConfig.controls.size > 1) {
            CycleControlButton(theme = data.theme, onClick = onCycle)
        }
    }
}

/** Picks default/horizontal/vertical based on the config's "layout" string — unrecognized/missing values fall back to default. */
@Composable
private fun LightTileLayout(
    layout: String?,
    name: String,
    stateLabel: String,
    appearance: LightAppearance,
    controlsSlot: @Composable (fillWidth: Boolean) -> Unit,
    theme: ThemeColors,
    modifier: Modifier
) {
    val icon = appearance.icon
    val iconBg = appearance.iconBg
    val iconTint = appearance.iconTint
    when (layout) {
        "horizontal" -> HorizontalLayout(name, stateLabel, icon, iconBg, iconTint, controlsSlot, theme, modifier = modifier)
        "vertical" -> VerticalLayout(name, stateLabel, icon, iconBg, iconTint, controlsSlot, theme, modifier = modifier)
        else -> DefaultLayout(name, stateLabel, icon, iconBg, iconTint, controlsSlot, theme, modifier = modifier)
    }
}

// ---- shared pieces ---------------------------------------------------------

@Composable
private fun LightIcon(icon: ImageVector, bg: Color, tint: Color, size: Dp = 42.dp) {
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp))
            .background(bg),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = tint)
    }
}

@Composable
private fun NameState(
    name: String,
    stateLabel: String,
    theme: ThemeColors,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    textAlign: TextAlign = TextAlign.Start
) {
    Column(horizontalAlignment = horizontalAlignment) {
        Text(
            name,
            color = theme.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            textAlign = textAlign
        )
        Text(stateLabel, color = theme.mutedText, fontSize = 13.sp, textAlign = textAlign)
    }
}

/** Small round "next control" button — cycles through the enabled controls, Mushroom-style. */
@Composable
private fun CycleControlButton(theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(36.dp)
            .clip(CircleShape)
            .background(theme.controlBackground)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = theme.iconTint)
    }
}

/**
 * Brightness/position-style bar, doubling as a horizontal slider: drag or
 * tap anywhere on it to raise/lower a 0-100% value right from the card, no
 * dialog needed. The fraction shown always follows [value] except mid-drag,
 * where it tracks the finger for immediate feedback until release commits it.
 *
 * [drag] is hoisted from [LightCard]'s Render so the tile's state label can
 * mirror the in-progress value; its release timing is driven by
 * [TrackDragFraction].
 */
@Composable
private fun PercentSlider(
    entityId: String,
    isOn: Boolean,
    value: Int,
    color: Color,
    theme: ThemeColors,
    drag: MutableFloatState,
    onCommit: (Int) -> Unit
) {
    var dragFraction by drag
    val liveFraction = (value / 100f).coerceIn(0f, 1f)
    val shownFraction = if (dragFraction >= 0f) dragFraction else liveFraction

    TrackDragFraction(drag, liveFraction, isOn)

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(theme.insetSurface)
            .pointerInput(entityId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragFraction >= 0f) {
                            onCommit((dragFraction * 100).roundToInt())
                        }
                    },
                    onDragCancel = { dragFraction = DRAG_FRACTION_INACTIVE }
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }.pointerInput(entityId) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    dragFraction = f
                    onCommit((f * 100).roundToInt())
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier =
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .fillMaxWidth(shownFraction.coerceIn(0.02f, 1f))
                .clip(RoundedCornerShape(18.dp))
                .background(color)
        )
        Text(
            "${(shownFraction * 100).roundToInt()}%",
            color = theme.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Warm-to-cool gradient slider for color temperature — drag or tap to set
 * `color_temp_kelvin` between the entity's own min/max_color_temp_kelvin.
 */
@Composable
private fun ColorTempSlider(entityId: String, isOn: Boolean, minKelvin: Int, maxKelvin: Int, kelvin: Int, onCommit: (Int) -> Unit) {
    val range = (maxKelvin - minKelvin).coerceAtLeast(1)
    val drag = rememberDragFraction(entityId)
    var dragFraction by drag
    val liveFraction = ((kelvin - minKelvin).toFloat() / range).coerceIn(0f, 1f)
    val shownFraction = if (dragFraction >= 0f) dragFraction else liveFraction

    fun fractionToKelvin(f: Float) = (minKelvin + f * range).roundToInt()

    TrackDragFraction(drag, liveFraction, isOn)

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .height(36.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(
                Brush.horizontalGradient(
                    listOf(Color(0xFFFFB366), Color(0xFFFFF3E0), Color(0xFF9EC8FF))
                )
            ).pointerInput(entityId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragFraction >= 0f) onCommit(fractionToKelvin(dragFraction))
                    },
                    onDragCancel = { dragFraction = DRAG_FRACTION_INACTIVE }
                ) { change, _ ->
                    dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                }
            }.pointerInput(entityId) {
                detectTapGestures { offset ->
                    val f = (offset.x / size.width).coerceIn(0f, 1f)
                    dragFraction = f
                    onCommit(fractionToKelvin(f))
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Text(
            "${fractionToKelvin(shownFraction)}K",
            color = Color(0xFF241A00),
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/** Compact row of tappable color swatches — sets `rgb_color` directly. */
@Composable
private fun ColorSwatchRow(onPick: (Int, Int, Int) -> Unit) {
    val swatches =
        listOf(
            Triple(244, 67, 54),
            Triple(255, 152, 0),
            Triple(255, 235, 59),
            Triple(76, 175, 80),
            Triple(0, 188, 212),
            Triple(33, 150, 243),
            Triple(156, 39, 176),
            Triple(255, 255, 255)
        )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        swatches.forEach { (r, g, b) ->
            Box(
                modifier =
                Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(Color(r, g, b))
                    .tapClickable(focusShape = CircleShape) { onPick(r, g, b) }
            )
        }
    }
}

// ---- "default": icon + name/state row, controls area full-width below -----

@Composable
private fun DefaultLayout(
    name: String,
    stateLabel: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    controls: @Composable (fillWidth: Boolean) -> Unit,
    theme: ThemeColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(theme.cardSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().then(modifier)) {
            LightIcon(icon, iconBg, iconTint)
            Spacer(Modifier.width(12.dp))
            NameState(name, stateLabel, theme)
        }
        // Controls live outside modifier's tap/long-press area so dragging
        // them doesn't also toggle the light or open the dialog.
        controls(true)
    }
}

// ---- "horizontal": icon + name/state on the left, controls on the right ---

@Composable
private fun HorizontalLayout(
    name: String,
    stateLabel: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    controls: @Composable (fillWidth: Boolean) -> Unit,
    theme: ThemeColors,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(theme.cardSurface)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.then(modifier)) { LightIcon(icon, iconBg, iconTint) }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f).then(modifier)) { NameState(name, stateLabel, theme) }
        Box(Modifier.width(140.dp)) { controls(false) }
    }
}

// ---- "vertical": icon, name, state, controls — all centered and stacked ---

@Composable
private fun VerticalLayout(
    name: String,
    stateLabel: String,
    icon: ImageVector,
    iconBg: Color,
    iconTint: Color,
    controls: @Composable (fillWidth: Boolean) -> Unit,
    theme: ThemeColors,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(18.dp))
            .background(theme.cardSurface)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth().then(modifier)
        ) {
            LightIcon(icon, iconBg, iconTint, size = 48.dp)
            Spacer(Modifier.height(8.dp))
            NameState(name, stateLabel, theme, horizontalAlignment = Alignment.CenterHorizontally, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(10.dp))
        controls(true)
    }
}
