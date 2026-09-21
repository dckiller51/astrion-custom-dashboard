package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
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
 */
@Composable
fun LightDetailDialog(
    entityId: String,
    name: String,
    e: EntityState?,
    client: HaClient,
    theme: ThemeColors = ThemeColors.Default,
    onClose: () -> Unit
) {
    val on = e?.isOn == true
    val brightness = e?.attrInt("brightness")
    val level: Float =
        when {
            !on -> 0f
            brightness != null -> (brightness / 255f).coerceIn(0f, 1f)
            else -> 1f
        }
    var dragLevel by remember(level) { mutableFloatStateOf(level) }

    val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()

    @Suppress("SpellCheckingInspection")
    val hasColor = colorModes.any { it in listOf("hs", "rgb", "rgbw", "rgbww", "xy") }
    val hasTemp = colorModes.contains("color_temp") || hasColor

    // Fill color follows the light's live rgb_color where available.
    val rgb = e?.attr("rgb_color") as? JsonArray
    val fillColor =
        if (rgb != null && rgb.size >= 3) {
            fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255) ?: 255
            Color(ch(0), ch(1), ch(2))
        } else {
            theme.amber
        }

    fun commit(fraction: Float) {
        val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        if (pct <= 0) {
            client.callService(ServiceCall("light", "turn_off", entityId))
        } else {
            client.callService(ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct))
        }
    }

    fun setRgb(r: Int, g: Int, b: Int) {
        client.callService(
            ServiceCall(
                "light",
                "turn_on",
                entityId,
                mapOf("rgb_color" to JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b))))
            )
        )
    }

    fun setKelvin(k: Int) {
        client.callService(ServiceCall.of("light", "turn_on", entityId, "color_temp_kelvin" to k))
    }

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "${(dragLevel * 100).roundToInt()}%",
                color = theme.primaryText,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )
            Text(name, color = theme.mutedText, fontSize = 13.sp)

            // Vertical brightness pill: drag or tap to set; fill rises from the bottom.
            Box(
                modifier =
                Modifier
                    .width(120.dp)
                    .height(230.dp)
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

            // Power toggle.
            Box(
                modifier =
                Modifier
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

            // Color swatches (only for color-capable lights).
            if (hasColor) {
                val swatches =
                    listOf(
                        Triple(244, 67, 54),
                        Triple(255, 152, 0),
                        Triple(255, 235, 59),
                        Triple(76, 175, 80),
                        Triple(0, 188, 212),
                        Triple(33, 150, 243),
                        Triple(156, 39, 176),
                        Triple(233, 30, 99),
                        Triple(255, 182, 193),
                        Triple(255, 255, 255)
                    )
                swatches.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { (r, g, b) ->
                            Box(
                                modifier =
                                Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(r, g, b))
                                    .tapClickable(focusShape = CircleShape) { setRgb(r, g, b) }
                            )
                        }
                    }
                }
            }

            // Color-temperature presets (warm → cool).
            if (hasTemp) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("Candle" to 2200, "Warm" to 2700, "Neutral" to 4000, "Cool" to 5500).forEach { (label, k) ->
                        Box(
                            modifier =
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(theme.controlBackground)
                                .tapClickable(focusShape = RoundedCornerShape(12.dp)) { setKelvin(k) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Text(label, color = theme.iconTint, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
