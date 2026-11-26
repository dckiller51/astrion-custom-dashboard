package com.custom.astrion.playnite

import com.custom.astrion.zidoo.ZidooClient

/**
 * Waking a PC, unlocking Windows, then starting a game (1.6.3), on the PC's own thread:
 * Wake-on-LAN, and the unlock ESP (Astrion PC Unlock) when there is one — it can also wake the PC
 * from sleep through USB, and types the PIN when Windows is locked. A PIN is typed three times at
 * most per wake, so a wrong one never runs into Windows' own limit.
 */
internal class PlayniteWakeFlow(private val log: (String) -> Unit, private val stopped: () -> Boolean) {
    /** One PC, as the flow needs it. */
    class Target(
        val name: String,
        val mac: String,
        val client: PlayniteClient,
        val unlock: PlayniteUnlockClient?,
        /** Playnite switched to fullscreen before a game starts, or once woken (1.6.3). */
        val fullscreen: Boolean = false
    )

    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        !stopped()
    } catch (_: InterruptedException) {
        false
    }

    /** Wake-on-LAN (twice: the first packet is sometimes lost), and a wake through USB by the unlock ESP. */
    fun wake(t: Target): Boolean {
        var sent = false
        if (t.mac.isNotBlank()) {
            log("[${t.name}] Wake-on-LAN to ${t.mac}")
            sent = ZidooClient.wake(t.mac)
            pause(WOL_AGAIN_MS)
            sent = ZidooClient.wake(t.mac) || sent
        }
        val usb = t.unlock?.wake() == true
        if (usb) log("[${t.name}] woken through USB by the unlock ESP")
        if (!sent && !usb) log("[${t.name}] can't wake it: no MAC address (set it on the web page, ⏻)")
        return sent || usb
    }

    private fun problem(result: UnlockResult): String? = when (result) {
        UnlockResult.DONE -> null
        UnlockResult.CODE_REFUSED -> "the unlock ESP refused its code (web page, 🔓)"
        UnlockResult.PC_NOT_ON -> "the unlock ESP says the PC isn't on"
        UnlockResult.FAILED -> "the unlock ESP didn't answer"
    }

    /** Types the PIN; true when Playnite says Windows is unlocked within a few seconds. */
    fun unlockNow(t: Target): Boolean {
        val u = t.unlock ?: return false
        log("[${t.name}] unlocking Windows")
        problem(u.unlock())?.let {
            log("[${t.name}] $it")
            return false
        }
        val deadline = System.currentTimeMillis() + UNLOCK_WAIT_MS
        while (System.currentTimeMillis() < deadline && pause(UNLOCK_POLL_MS)) {
            val text = runCatching { t.client.stateText() }.getOrNull()
            if (text != null && !PlayniteProtocol.locked(text)) {
                log("[${t.name}] Windows unlocked")
                return true
            }
        }
        log("[${t.name}] Windows is still locked: check the PIN and the keyboard layout on the ESP's page")
        return false
    }

    /**
     * Playnite in fullscreen, when the PC asks for it (1.6.3, extension 0.1.3+): switched if it's
     * in desktop mode, then waited for — Playnite and the extension restart in the other mode.
     * Nothing for an older extension (it doesn't say its mode).
     */
    fun ensureFullscreen(t: Target) {
        if (!t.fullscreen) return
        val mode = runCatching { PlayniteProtocol.mode(t.client.stateText()) }.getOrDefault("")
        if (mode != "desktop") return
        log("[${t.name}] Playnite to fullscreen")
        runCatching { t.client.switchMode("fullscreen") }.onFailure { log("[${t.name}] fullscreen failed: ${it.message}") }
        val deadline = System.currentTimeMillis() + MODE_WAIT_MS
        while (System.currentTimeMillis() < deadline && pause(MODE_POLL_MS)) {
            val text = runCatching { t.client.stateText() }.getOrNull() ?: continue
            if (PlayniteProtocol.mode(text) == "fullscreen") return
        }
        log("[${t.name}] Playnite didn't come back in fullscreen")
    }

    /** Starts [game] on a PC that answers; unlocks Windows first (an unlock ESP), and goes fullscreen (when set). */
    fun start(t: Target, game: PlayniteGame, locked: Boolean) {
        if (locked && t.unlock != null) unlockNow(t)
        ensureFullscreen(t)
        log("[${t.name}] starting ${game.name}")
        t.client.start(game.id)
    }

    /** One wake: when it started, how many times the PIN was typed, how many times Playnite answered. */
    private class Attempt(val startedAt: Long) {
        var typed = 0
        var answers = 0
    }

    /**
     * One look at the PC while it wakes: true when done (ready, then [game] started). Playnite not
     * answering yet may be Windows at its sign-in (started with no session open): the PIN is typed
     * there 40 s after the wake, then 80 s, 120 s; Playnite answering but Windows locked: unlocked.
     * With an unlock ESP, Playnite answering unlocked is trusted the second time only: right after
     * a wake from sleep, the lock screen can come up a moment after Playnite answers (Zavie's PC).
     */
    private fun step(t: Target, game: PlayniteGame?, a: Attempt): Boolean {
        val text = runCatching { t.client.stateText() }.getOrNull()
        val unlock = t.unlock
        val canType = unlock != null && a.typed < MAX_TYPED
        when {
            text == null -> {
                if (canType && System.currentTimeMillis() - a.startedAt >= COLD_UNLOCK_AFTER_MS * (a.typed + 1)) {
                    a.typed++
                    if (unlock?.unlock() == UnlockResult.DONE) log("[${t.name}] PIN typed at the sign-in screen")
                }
                return false
            }
            PlayniteProtocol.locked(text) && canType -> {
                a.typed++
                unlockNow(t)
                return false
            }
            unlock != null && ++a.answers < 2 -> return false
        }
        ensureFullscreen(t)
        if (game == null) {
            log("[${t.name}] awake")
        } else {
            log("[${t.name}] Playnite answers: starting ${game.name}")
            runCatching { t.client.start(game.id) }.onFailure { log("[${t.name}] starting ${game.name} failed: ${it.message}") }
        }
        return true
    }

    /**
     * Wakes the PC, unlocks Windows when it's locked (an unlock ESP), then starts [game] once
     * Playnite answers; [game] null: only woken (and unlocked).
     */
    fun wakeAndStart(t: Target, game: PlayniteGame?) {
        // Only woken, with nothing to do once it answers (no unlock ESP, no fullscreen): not waited for.
        val nothingAfter = game == null && t.unlock == null && !t.fullscreen
        if (!wake(t) || nothingAfter) return
        log("[${t.name}] waiting for Playnite" + (game?.let { " to start ${it.name}" } ?: ""))
        val attempt = Attempt(System.currentTimeMillis())
        val deadline = attempt.startedAt + WAKE_START_MS
        while (System.currentTimeMillis() < deadline && pause(WAKE_POLL_MS)) {
            if (step(t, game, attempt)) return
        }
        if (!stopped()) {
            val notStarted = game?.let { ": ${it.name} not started" }.orEmpty()
            log("[${t.name}] Playnite didn't answer within ${WAKE_START_MS / MS_PER_MIN} minutes$notStarted")
        }
    }

    companion object {
        private const val WOL_AGAIN_MS = 500L
        private const val WAKE_START_MS = 4 * 60_000L
        private const val WAKE_POLL_MS = 5_000L
        private const val MS_PER_MIN = 60_000L
        private const val UNLOCK_WAIT_MS = 20_000L
        private const val UNLOCK_POLL_MS = 1_000L
        private const val MODE_WAIT_MS = 45_000L
        private const val MODE_POLL_MS = 2_000L

        /** A cold start: the PIN typed at the sign-in screen 40 s after the wake, then 80 s, 120 s. */
        private const val COLD_UNLOCK_AFTER_MS = 40_000L
        private const val MAX_TYPED = 3
    }
}
