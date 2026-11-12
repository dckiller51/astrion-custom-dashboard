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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.ScreenCapture
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlin.math.roundToInt

/**
 * Long-press detail popup for a cover (bubble-card style, mirrors
 * LightDetailDialog): a big vertical position pill you can drag or tap to set
 * an exact 0-100% position, plus a row of quick presets (25 / 50 / 75%).
 *
 * Fires cover.set_cover_position for drag/tap/preset moves, and
 * cover.open_cover / cover.close_cover only for the pill's own 0%/100% ends
 * (so covers that don't report/accept an exact position still get a sensible
 * action there) — fully open/closed already has its own dedicated buttons on
 * the card itself, so the presets row only needs the in-between values.
 */
@Composable
fun CoverDetailDialog(
    entityId: String,
    name: String,
    e: EntityState?,
    client: HaClient,
    theme: ThemeColors = ThemeColors.Default,
    onClose: () -> Unit
) {
    val position = e?.attrInt("current_position") // 0..100
    val rawState = e?.state ?: "unknown"
    val level: Float =
        when {
            position != null -> (position / 100f).coerceIn(0f, 1f)
            rawState == "open" -> 1f
            else -> 0f
        }
    var dragLevel by remember(level) { mutableFloatStateOf(level) }

    fun commit(fraction: Float) {
        when (val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()) {
            0 -> client.callService(ServiceCall(domain = "cover", service = "close_cover", entityId = entityId))
            100 -> client.callService(ServiceCall(domain = "cover", service = "open_cover", entityId = entityId))
            else -> client.callService(ServiceCall.of("cover", "set_cover_position", entityId, "position" to pct))
        }
    }

    fun setPreset(pct: Int) {
        dragLevel = pct / 100f
        commit(dragLevel)
    }

    Dialog(onDismissRequest = onClose) {
        ScreenCapture.TrackForScreenshots()
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

            // Vertical position pill: drag or tap to set; fill rises from the bottom,
            // same interaction as LightDetailDialog's brightness pill.
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
                        .background(theme.accent.copy(alpha = 0.9f))
                )
            }

            // Quick presets — common intermediate positions, e.g. going from
            // 40% straight to 25/50/75% without fiddling with the drag pill.
            // No 0%/100% chip here: the card's own open/close buttons already
            // cover fully-open/fully-closed, and the pill goes all the way to
            // either end anyway (drag to the very bottom/top).
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(25, 50, 75).forEach { pct ->
                    Box(
                        modifier =
                        Modifier
                            .clip(RoundedCornerShape(12.dp))
                            .background(theme.controlBackground)
                            .tapClickable { setPreset(pct) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) {
                        Text("$pct%", color = theme.iconTint, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}
