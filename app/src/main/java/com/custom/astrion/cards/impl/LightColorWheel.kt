package com.custom.astrion.cards.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val WHEEL_HUES =
    listOf(Color.Red, Color.Yellow, Color.Green, Color.Cyan, Color.Blue, Color.Magenta, Color.Red)

private fun moveOf(key: Key): WheelMove? = when (key) {
    Key.DirectionLeft -> WheelMove.LEFT
    Key.DirectionRight -> WheelMove.RIGHT
    Key.DirectionUp -> WheelMove.UP
    Key.DirectionDown -> WheelMove.DOWN
    else -> null
}

private fun isOk(key: Key) = key == Key.DirectionCenter || key == Key.Enter || key == Key.NumPadEnter

/**
 * The wheel's D-pad: OK toggles adjusting; while adjusting, an arrow's press moves the colour
 * ([onMove]) and its release sends it. Returns whether the key was used.
 */
private fun wheelKey(
    event: KeyEvent,
    editing: Boolean,
    setEditing: (Boolean) -> Unit,
    current: HueSat,
    onCommit: (HueSat) -> Unit,
    onMove: (HueSat) -> Unit
): Boolean {
    if (isOk(event.key)) {
        if (event.type == KeyEventType.KeyUp) setEditing(!editing)
        return true
    }
    val move = moveOf(event.key)
    if (!editing || move == null) return false
    when (event.type) {
        KeyEventType.KeyDown -> onMove(LightColors.step(current, move))
        KeyEventType.KeyUp -> onCommit(current)
        else -> Unit
    }
    return true
}

/**
 * Hue/saturation wheel: drag or tap to pick, sent to [onCommit] when the finger lifts.
 * With the remote's D-pad: OK on the focused wheel starts adjusting it (left/right turn the
 * hue, up/down the saturation, each step sent when the key is released), OK again stops,
 * and the arrows move between the popup's controls again.
 */
@Composable
fun LightColorWheel(
    key: Any?,
    current: HueSat?,
    description: String,
    modifier: Modifier = Modifier.size(180.dp),
    onCommit: (HueSat) -> Unit
) {
    // Remembered per light only (the gesture handlers keep these same states); the light's
    // own colour, when Home Assistant reports a new one, moves the marker.
    var hue by remember(key) { mutableFloatStateOf(current?.hue ?: 0f) }
    var sat by remember(key) { mutableFloatStateOf(current?.sat ?: 0f) }
    LaunchedEffect(current) {
        if (current != null) {
            hue = current.hue
            sat = current.sat
        }
    }
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    var editing by remember(key) { mutableStateOf(false) }
    LaunchedEffect(focused) { if (!focused) editing = false }

    fun pick(offset: Offset, width: Float) {
        val r = width / 2f
        val hs = LightColors.pointToHueSat(offset.x - r, offset.y - r, r)
        hue = hs.hue
        sat = hs.sat
    }

    Canvas(
        modifier =
        modifier
            .semantics { contentDescription = description }
            .then(
                when {
                    editing -> Modifier.border(4.dp, Color.White, CircleShape)
                    focused -> Modifier.border(2.dp, Color.White.copy(alpha = 0.7f), CircleShape)
                    else -> Modifier
                }
            ).onPreviewKeyEvent { event ->
                wheelKey(event, editing, { editing = it }, HueSat(hue, sat), onCommit) { next ->
                    hue = next.hue
                    sat = next.sat
                }
            }.focusable(interactionSource = interaction)
            .pointerInput(key) {
                detectDragGestures(onDragEnd = { onCommit(HueSat(hue, sat)) }) { change, _ ->
                    pick(change.position, this.size.width.toFloat())
                }
            }.pointerInput(key) {
                detectTapGestures { offset ->
                    pick(offset, this.size.width.toFloat())
                    onCommit(HueSat(hue, sat))
                }
            }
    ) {
        val r = this.size.minDimension / 2f
        drawCircle(Brush.sweepGradient(WHEEL_HUES, center), r)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.White.copy(alpha = 0f)), center, r), r)
        val (px, py) = LightColors.hueSatToPoint(HueSat(hue, sat), r)
        val at = center + Offset(px, py)
        drawCircle(Color.hsv(hue, sat / 100f, 1f), 11.dp.toPx(), at)
        drawCircle(Color.White, 11.dp.toPx(), at, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.35f), 12.5.dp.toPx(), at, style = Stroke(1.dp.toPx()))
    }
}
