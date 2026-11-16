package com.custom.astrion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState

/**
 * Parts of the screen that take the remote's physical Back key for themselves (1.5.8): the media
 * remote card's player view. MainActivity asks here before any hotkey, so Back leaves the view
 * instead of reaching the TV (where it stopped playback: Xavier's tests on Apple TV and Zidoo).
 * Kept apart from Android's OnBackPressedDispatcher, which the 1.5.7 attempt relied on and which
 * didn't see these handlers on the remote. Main thread only.
 */
object ScreenBack {
    private val handlers = mutableListOf<() -> Unit>()

    val active: Boolean get() = handlers.isNotEmpty()

    /** Runs the most recent handler; false when there's none. */
    fun take(): Boolean = handlers.lastOrNull()?.let {
        it()
        true
    } ?: false

    internal fun add(handler: () -> Unit) {
        handlers += handler
    }

    internal fun remove(handler: () -> Unit) {
        handlers -= handler
    }
}

/** While [enabled], the remote's Back key runs [onBack] (and nothing else). */
@Composable
fun ScreenBackHandler(enabled: Boolean, onBack: () -> Unit) {
    val current by rememberUpdatedState(onBack)
    DisposableEffect(enabled) {
        if (!enabled) return@DisposableEffect onDispose { }
        val handler = { current() }
        ScreenBack.add(handler)
        onDispose { ScreenBack.remove(handler) }
    }
}
