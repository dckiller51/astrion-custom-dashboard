package com.custom.astrion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.impl.rememberLongPressKeyModifier

/**
 * Tap feedback — the tiny "tick" sound the device's own Android UI menus
 * play when you touch them. Jetpack Compose's plain
 * [androidx.compose.foundation.clickable] does NOT play this sound by
 * default (it only shows a ripple), so every tappable element in the
 * dashboard is silent unless we opt in.
 *
 * The sound is fired by a lambda provided through [LocalTapFeedback] so a
 * single setting in MainActivity (persisted, exposed on the settings page)
 * can gate it app-wide without every card having to read the preference.
 * The default value is a no-op so cards and previews that render without a
 * provider stay silent and side-effect-free.
 *
 * See [android.media.AudioManager.playSoundEffect] (specifically
 * [AudioManager.FX_KEY_CLICK]) for the underlying API.
 */
val LocalTapFeedback = staticCompositionLocalOf<() -> Unit> { {} }

/**
 * Whether D-pad focus outlines (the accent border [tapClickable] draws
 * around a focused tile, and Dashboard's whole-card outline) are allowed to
 * show right now. MainActivity flips [visible] to false the moment a
 * directional D-pad key is consumed by a hotkey (that key never reaches
 * Compose, so a visible outline would be a cursor that can't move), and
 * back to true as soon as a directional key is left to Compose's own focus
 * navigation again. Purely visual — it doesn't move or clear focus itself
 * (see MainActivity.dispatchUnmappedKey for why clearing focus alone wasn't
 * enough). Snapshot state, so only the outline readers recompose when it
 * flips; a plain object rather than a CompositionLocal because it's set
 * from outside Compose (key dispatch) and is app-wide anyway.
 */
object FocusHighlight {
    var visible by mutableStateOf(true)
}

/** Minimum gap between two long-press firings on the same element — a
 * held D-pad CENTER can be seen both by [rememberLongPressKeyModifier] and
 * (depending on the Compose Foundation version) by combinedClickable's own
 * key long-click support; this keeps one physical hold = one action. */
private const val LONG_PRESS_DEDUPE_MS = 800L

/**
 * Drop-in replacement for [Modifier.clickable] that also plays the tap
 * feedback sound through [LocalTapFeedback], and draws a clearly visible
 * accent border around itself while it holds keyboard/D-pad focus (physical
 * remote UP/DOWN/LEFT/RIGHT moving between tiles — not a touch tap, which
 * doesn't set Compose's notion of "focused" at all). Use this anywhere the
 * dashboard handles a tap (scene buttons, remote keys, dots, dialogs, close
 * affordances) so the device gives the same "tap" notice its own Android
 * menus do, and so a physical-remote user can actually tell which tile
 * they're on.
 *
 * Compose's own default focus indication is the stock ripple's "focused"
 * state — a few-percent darkening — which is the specific problem this
 * replaces: on the HA100's screen, at a glance, it barely reads as
 * different from an unfocused tile. [focusShape] should match whatever
 * shape the caller already clips itself to (e.g. the same
 * `RoundedCornerShape` passed to its own `.clip(...)`) so the border hugs
 * the tile's actual corners instead of sitting square over a rounded one;
 * left at the [RectangleShape] default it's still far more visible than
 * the stock ripple even when the corners don't quite line up.
 *
 * The feedback fires before [onClick] so it lands with the touch, not after
 * the (possibly slow) action — matching how platform views behave.
 */
fun Modifier.tapClickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Role? = null,
    focusShape: Shape = RectangleShape,
    onClick: () -> Unit
): Modifier = composed {
    val feedback = LocalTapFeedback.current
    val theme = LocalTheme.current
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    this
        .then(if (isFocused && FocusHighlight.visible) Modifier.border(2.5.dp, theme.accent, focusShape) else Modifier)
        .clickable(
            enabled = enabled,
            onClickLabel = onClickLabel,
            role = role,
            interactionSource = interactionSource,
            indication = LocalIndication.current
        ) {
            feedback()
            onClick()
        }
}

/**
 * [tapClickable] plus an optional long-press (button_grid / scene_grid
 * "long_press"): touch hold via combinedClickable, held D-pad CENTER/Enter
 * via [rememberLongPressKeyModifier] (combinedClickable alone doesn't
 * reliably cover a hardware key — see that function's doc). Same tap
 * sound and focus outline as [tapClickable]; with a null [onLongClick] it
 * simply is [tapClickable], so a tile without "long_press" is unchanged.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.tapCombinedClickable(focusShape: Shape, onLongClick: (() -> Unit)?, onClick: () -> Unit): Modifier {
    if (onLongClick == null) return tapClickable(focusShape = focusShape, onClick = onClick)
    return composed {
        val feedback = LocalTapFeedback.current
        val theme = LocalTheme.current
        val interactionSource = remember { MutableInteractionSource() }
        val isFocused by interactionSource.collectIsFocusedAsState()
        val lastLongFire = remember { mutableLongStateOf(0L) }
        val fireLong = {
            val now = System.currentTimeMillis()
            if (now - lastLongFire.longValue > LONG_PRESS_DEDUPE_MS) {
                lastLongFire.longValue = now
                feedback()
                onLongClick()
            }
        }
        this
            .then(if (isFocused && FocusHighlight.visible) Modifier.border(2.5.dp, theme.accent, focusShape) else Modifier)
            .then(rememberLongPressKeyModifier(Unit, fireLong))
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                onLongClick = fireLong
            ) {
                feedback()
                onClick()
            }
    }
}
