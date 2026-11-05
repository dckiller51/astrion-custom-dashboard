package com.custom.astrion.input

import android.view.KeyEvent
import android.view.Window
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider

/**
 * The remote's own buttons while a pop-up is open (1.4.9). A Compose Dialog is a window of its
 * own: its keys never reach MainActivity, so a hotkey (volume, play, a TV's arrows…) did nothing
 * while the sources or keypad pop-up was open. MainActivity sets [forward]; a pop-up calls
 * [ForwardHotkeys] and gets its keys handed over. BACK stays the pop-up's (it closes it), and a
 * key with no hotkey stays too (it moves the pop-up's focus).
 */
object DialogKeys {
    /** Runs [KeyEvent] as a hotkey; false when it isn't one. Set by MainActivity. */
    @Volatile
    var forward: ((KeyEvent) -> Boolean)? = null

    fun handles(event: KeyEvent): Boolean = event.keyCode != KeyEvent.KEYCODE_BACK && forward?.invoke(event) == true
}

/** Inside a Dialog's content: its window's keys go to the hotkeys first (see [DialogKeys]). */
@Composable
fun ForwardHotkeys() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.parent as? DialogWindowProvider)?.window
        val original = window?.callback
        if (window != null && original != null) {
            window.callback =
                object : Window.Callback by original {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean = DialogKeys.handles(event) || original.dispatchKeyEvent(event)
                }
        }
        onDispose { if (window != null && original != null) window.callback = original }
    }
}
