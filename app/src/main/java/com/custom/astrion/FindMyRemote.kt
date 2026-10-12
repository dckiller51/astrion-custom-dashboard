package com.custom.astrion

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Shared state of "Find my remote" (the config server's `/ring`): whether it is ringing right
 * now, and how to stop it. The web server owns the sound; the activity watches [ringing] to wake
 * the screen and show the "Found me!" overlay, and calls [stop] on the first touch or button
 * press — so whoever finds the remote can silence it on the spot.
 */
object FindMyRemote {
    private val ringingState = MutableStateFlow(false)
    val ringing: StateFlow<Boolean> = ringingState.asStateFlow()

    @Volatile private var stopper: (() -> Unit)? = null

    /** Called by the ring's owner once the sound actually started; [stop] will call [onStop]. */
    fun started(onStop: () -> Unit) {
        stopper = onStop
        ringingState.value = true
    }

    /** Called by the ring's owner whenever the sound ends (timer, `/ring/stop`, or [stop]). */
    fun stopped() {
        stopper = null
        ringingState.value = false
    }

    /** Silences the ring from the remote itself. Safe to call when nothing is ringing. */
    fun stop() {
        val s = stopper
        stopped()
        runCatching { s?.invoke() }
    }
}
