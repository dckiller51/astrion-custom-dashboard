package com.custom.astrion.input

/**
 * Physical button map for the Astrion HA100 hardware.
 *
 * These keycodes were extracted directly from the stock app's
 * assets/device_key_code.json (the "HA100" block). When a physical button is
 * pressed, the OS delivers a standard Android KeyEvent with these codes to the
 * focused Activity, which is why a standalone app can handle them via
 * dispatchKeyEvent / onKeyDown — no vendor SDK required for the buttons.
 *
 * The dedicated shortcut buttons (light / curtain / scene / ac / custom_1..4)
 * are the real prize: you can bind each of them to any action you want, instead
 * of HaRemote's fixed behavior.
 */
@Suppress("SpellCheckingInspection")
enum class HardwareKey {
    BACK,
    HOME,
    POWER,
    VOLUME_UP,
    VOLUME_DOWN,
    PAGE_UP,
    PAGE_DOWN,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    CENTER,
    MUTE,
    VOICE,
    MAIN,
    REWIND,
    PLAY,
    STOP,
    FASTFORWARD,
    RED_BUTTON,
    GREEN_BUTTON,
    BLUE_BUTTON,
    YELLOW_BUTTON,
    UNKNOWN
    ;

    companion object {
        // Android keycode -> logical button, straight from device_key_code.json (HA100).
        private val MAP: Map<Int, HardwareKey> =
            mapOf(
                4 to BACK,
                131 to HOME,
                132 to POWER,
                24 to VOLUME_UP,
                25 to VOLUME_DOWN,
                92 to PAGE_UP,
                93 to PAGE_DOWN,
                19 to UP,
                20 to DOWN,
                21 to LEFT,
                22 to RIGHT,
                23 to CENTER,
                82 to MAIN,
                164 to MUTE,
                133 to VOICE,
                134 to REWIND,
                135 to PLAY,
                136 to STOP,
                137 to FASTFORWARD,
                138 to RED_BUTTON,
                139 to GREEN_BUTTON,
                140 to BLUE_BUTTON,
                141 to YELLOW_BUTTON
            )

        fun fromKeyCode(code: Int): HardwareKey = MAP[code] ?: UNKNOWN
    }
}

/**
 * Bind hardware buttons to actions. Register short- and long-press handlers at
 * startup; MainActivity does the tap-vs-hold timing and calls back here.
 */
class HardwareKeyRouter {
    private val shortHandlers = mutableMapOf<HardwareKey, () -> Boolean>()
    private val longHandlers = mutableMapOf<HardwareKey, () -> Boolean>()

    fun on(key: HardwareKey, handler: () -> Boolean) {
        shortHandlers[key] = handler
    }

    fun onLong(key: HardwareKey, handler: () -> Boolean) {
        longHandlers[key] = handler
    }

    /** True if [key] already has a short-press handler bound — used to let a
     * fallback binding (e.g. parent-page navigation) yield to any explicit
     * hotkey already claiming that key, instead of overriding it. */
    fun isShortBound(key: HardwareKey): Boolean = shortHandlers.containsKey(key)

    /** Same idea as [isShortBound], for long-press — lets a built-in default
     * (e.g. POWER long-press → restart, see MainActivity.rebindHotkeysForCurrentPage)
     * yield to an explicit `longHotkeys` entry on the same key. */
    fun isLongBound(key: HardwareKey): Boolean = longHandlers.containsKey(key)

    /** Drop all bindings — used before rebinding from a reloaded config. */
    fun clear() {
        shortHandlers.clear()
        longHandlers.clear()
    }

    fun shortHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else shortHandlers[key]
    }

    fun longHandler(code: Int): (() -> Boolean)? {
        val key = HardwareKey.fromKeyCode(code)
        return if (key == HardwareKey.UNKNOWN) null else longHandlers[key]
    }
}
