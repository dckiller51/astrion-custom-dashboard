package com.custom.astrion.config

/**
 * One thing to send while switching/stopping an Activity, in order.
 * [gapBeforeMs] is the wait after the previous action (it becomes a real
 * `delay()` or an extender batch-line delay — see ActivityDispatcher).
 */
sealed class PlannedAction {
    abstract val device: ActivityDeviceConfig
    abstract val gapBeforeMs: Int

    /** A named command: IR command id, or Harmony command name. */
    data class Command(override val device: ActivityDeviceConfig, val command: String, override val gapBeforeMs: Int) : PlannedAction()

    /** Home Assistant `turn_on`/`turn_off` on the device's entity. */
    data class HaPower(override val device: ActivityDeviceConfig, val on: Boolean, override val gapBeforeMs: Int) : PlannedAction()

    /** Home Assistant `media_player.select_source`. */
    data class HaSource(override val device: ActivityDeviceConfig, val source: String, override val gapBeforeMs: Int) : PlannedAction()
}

/** The plan plus the device states to record once it has been sent. */
data class ActivityPlan(
    val actions: List<PlannedAction>,
    val powerChanges: Map<String, Boolean>,
    val inputChanges: Map<String, String>
)

/**
 * Decides *what* to send to start or stop a composed Activity — the
 * Harmony way when the devices have an [IrDeviceProfile], the original
 * Astrion way when none of them do. Pure (no Android, no I/O) so it can be
 * unit-tested; ActivityDispatcher does the sending.
 *
 * Harmony-style start, used as soon as one device of the Activity has a
 * profile:
 *  1. Power off the room's devices that are on but not used by this
 *     Activity (known from [DeviceStateStore] — so this works after an app
 *     restart too, not only right after another Activity).
 *  2. Power on every device that is off, one after the other, spaced by
 *     each device's inter-device delay. A toggle-only device is only
 *     toggled when it's known (or assumed) to be off.
 *  3. Wait until each freshly powered device is ready (its power-on delay,
 *     counted from its own power-on — the delays overlap instead of adding
 *     up), then select inputs — as the profile's key sequence when the
 *     input is a named profile input ("HDMI 1"), as one command otherwise —
 *     and only when the device isn't already on that input.
 *
 * Without any profile, start keeps the exact original per-device order
 * (power on → input → delayAfterMs → next device); the only change is
 * that "already on" now comes from the persisted device state when known.
 */
class ActivityPlanner(
    private val profileOf: (ActivityDeviceConfig) -> IrDeviceProfile?,
    private val states: DeviceStateStore,
    /** Whether a device actually has a command — an explicit power command
     * it doesn't have falls back to the profile's own (e.g. an Apple TV has
     * no IR "PowerOn"; Harmony wakes it with "Home"). */
    private val hasCommand: (ActivityDeviceConfig, String) -> Boolean = { _, _ -> true }
) {
    private inner class Builder(val profileOf: (ActivityDeviceConfig) -> IrDeviceProfile?) {
        val actions = mutableListOf<PlannedAction>()
        val power = mutableMapOf<String, Boolean>()
        val inputs = mutableMapOf<String, String>()

        /** Elapsed plan time (sum of gaps) — used to honor power-on delays. */
        var clockMs = 0L
        private var pendingGap = 0

        fun gap(ms: Int) {
            pendingGap = maxOf(pendingGap, ms)
        }

        fun addDelay(ms: Int) {
            pendingGap += ms
        }

        /** Ensures at least [untilMs] of plan time has elapsed before the next action. */
        fun waitUntil(untilMs: Long) {
            val needed = (untilMs - (clockMs + pendingGap)).coerceAtLeast(0)
            pendingGap += needed.toInt()
        }

        private fun take(): Int = pendingGap.also {
            clockMs += it
            pendingGap = 0
        }

        /** Appends one action, built with the gap accumulated so far. */
        fun emit(action: (gapBeforeMs: Int) -> PlannedAction) {
            actions += action(take())
        }

        /** Sends a key sequence, spacing keys by the device's inter-key delay. */
        fun macro(d: ActivityDeviceConfig, steps: List<IrMacroStep>, interKeyMs: Int) {
            var first = true
            steps.forEach { step ->
                when (step) {
                    is IrMacroStep.Delay -> addDelay(step.ms)
                    is IrMacroStep.Send -> {
                        if (!first) gap(interKeyMs)
                        emit { gap -> PlannedAction.Command(d, step.command, gap) }
                        first = false
                    }
                    is IrMacroStep.Hold -> {
                        if (!first) gap(interKeyMs)
                        repeat(holdPresses(step.durationMs)) { emit { gap -> PlannedAction.Command(d, step.command, gap) } }
                        first = false
                    }
                }
            }
        }

        /** Power on/off one device. Returns true if anything was sent. */
        fun power(d: ActivityDeviceConfig, on: Boolean): Boolean {
            val sent =
                if (d.source == "ha") {
                    emit { gap -> PlannedAction.HaPower(d, on, gap) }
                    true
                } else {
                    irPower(d, on)
                }
            if (sent) power[d.deviceId] = on
            return sent
        }

        /** IR/Harmony part of [power]: the profile's sequence, or the explicit command. */
        private fun irPower(d: ActivityDeviceConfig, on: Boolean): Boolean {
            val profile = profileOf(d)
            // Ignored when the device doesn't have it but its profile knows
            // how to do the same — see [hasCommand].
            val explicit =
                (if (on) d.powerOnCommand else d.powerOffCommand)
                    ?.takeIf { profile == null || hasCommand(d, it) }
                    ?.let { listOf(IrMacroStep.Send(it)) }
            val steps: List<IrMacroStep> =
                when (profile?.power?.type) {
                    // An explicit command in the Activity always wins over
                    // the profile's discrete one — the person chose it.
                    PowerType.DISCRETE -> explicit ?: if (on) profile.power.on else profile.power.off
                    PowerType.TOGGLE -> profile.power.toggle.ifEmpty { explicit.orEmpty() }
                    PowerType.NONE -> emptyList()
                    else -> explicit.orEmpty()
                }
            if (steps.isNotEmpty()) macro(d, steps, profile?.timing?.interKeyDelayMs ?: 0)
            return steps.isNotEmpty()
        }

        /** Select an input: the profile's named sequence, or one command. */
        fun input(d: ActivityDeviceConfig, input: String) {
            when (d.source) {
                "ha" -> emit { gap -> PlannedAction.HaSource(d, input, gap) }
                else -> {
                    val profile = profileOf(d)
                    val steps = profile?.input(input) ?: listOf(IrMacroStep.Send(input))
                    macro(d, steps, profile?.timing?.interKeyDelayMs ?: 0)
                }
            }
            inputs[d.deviceId] = input
        }

        fun build() = ActivityPlan(actions.toList(), power.toMap(), inputs.toMap())
    }

    /**
     * Plan for starting [incoming].
     *
     * [roomDevices]: every device config of every composed Activity in the
     * same room — the candidates for "on but not needed anymore".
     * [outgoingDevices]: device ids of the Activity active in the room
     * right now (composed, or a Harmony-backed tile's "devices" hint) —
     * the pre-1.3 notion of "already on", still used when a device's
     * state is unknown.
     * [outgoingConfigs]: the outgoing composed Activity's own device
     * configs (their powerOffCommand/powerOffOnExit apply first).
     */
    fun planStart(
        incoming: ActivityConfig,
        roomDevices: List<ActivityDeviceConfig>,
        outgoingDevices: Set<String>,
        outgoingConfigs: List<ActivityDeviceConfig>
    ): ActivityPlan {
        val b = Builder(profileOf)
        val incomingIds = incoming.devices.map { it.deviceId }.toSet()

        // 1. Power off what this Activity doesn't use.
        powerOffCandidates(roomDevices, outgoingDevices, outgoingConfigs, incomingIds).forEach { d ->
            if (b.power(d, on = false)) b.gap(interDeviceDelay(d))
        }
        if (incoming.devices.none { profileOf(it) != null }) {
            planOriginalStart(b, incoming, outgoingDevices)
        } else {
            // 2. Power on, staggered. 3. Inputs, once each device is ready.
            val readyAt = planPowerOn(b, incoming, outgoingDevices)
            planInputs(b, incoming, outgoingDevices, readyAt)
        }
        return b.build()
    }

    private fun interDeviceDelay(d: ActivityDeviceConfig): Int = profileOf(d)?.timing?.interDeviceDelayMs ?: 0

    /** Known state first; unknown → "on" only if the outgoing Activity used it. */
    private fun isOn(d: ActivityDeviceConfig, outgoingDevices: Set<String>): Boolean =
        states[d.deviceId].on ?: (d.deviceId in outgoingDevices)

    /** Off → power on. Believed on → only a discrete "on" (harmless to
     * resend) when that belief didn't come from the outgoing Activity —
     * the device may have been switched off by hand since. */
    private fun needsPower(d: ActivityDeviceConfig, outgoingDevices: Set<String>): Boolean = d.powerOnFirst &&
        (!isOn(d, outgoingDevices) || (profileOf(d)?.power?.type == PowerType.DISCRETE && d.deviceId !in outgoingDevices))

    /** Select an input unless the device is on and already known to be on it. */
    private fun needsInput(d: ActivityDeviceConfig, input: String, poweredNow: Boolean, outgoingDevices: Set<String>): Boolean =
        poweredNow || states[d.deviceId].input?.equals(input, ignoreCase = true) != true || !isOn(d, outgoingDevices)

    /** No profiled device: the original order, unchanged — per device,
     * power → input → delayAfterMs. */
    private fun planOriginalStart(b: Builder, incoming: ActivityConfig, outgoingDevices: Set<String>) {
        incoming.devices.forEachIndexed { index, d ->
            if (needsPower(d, outgoingDevices)) b.power(d, on = true)
            d.inputCommand?.let { b.input(d, it) }
            if (index < incoming.devices.lastIndex) b.gap(d.delayAfterMs)
        }
    }

    /** Powers on, one device after the other; returns when each freshly
     * powered device will be ready (plan time, ms). */
    private fun planPowerOn(b: Builder, incoming: ActivityConfig, outgoingDevices: Set<String>): Map<String, Long> {
        val readyAt = mutableMapOf<String, Long>()
        incoming.devices.forEach { d ->
            val wasOn = isOn(d, outgoingDevices)
            if (needsPower(d, outgoingDevices) && b.power(d, on = true)) {
                val timing = profileOf(d)?.timing
                // A discrete "on" resent to a device believed on is only a
                // safety net: no power-on wait, no forced input for it.
                if (!wasOn) readyAt[d.deviceId] = b.clockMs + (timing?.powerOnDelayMs ?: d.delayAfterMs)
                b.gap(maxOf(timing?.interDeviceDelayMs ?: 0, d.delayAfterMs))
            }
        }
        return readyAt
    }

    /** Inputs, each once its device is ready, only when it changes. */
    private fun planInputs(b: Builder, incoming: ActivityConfig, outgoingDevices: Set<String>, readyAt: Map<String, Long>) {
        incoming.devices.forEach { d ->
            val input = d.inputCommand
            if (input != null && needsInput(d, input, d.deviceId in readyAt, outgoingDevices)) {
                readyAt[d.deviceId]?.let(b::waitUntil)
                b.input(d, input)
                val timing = profileOf(d)?.timing
                b.gap(maxOf(timing?.inputDelayMs ?: 0, timing?.interDeviceDelayMs ?: 0))
            }
        }
    }

    /** Plan for stopping [activity]: power off its devices that may be on. */
    fun planStop(activity: ActivityConfig): ActivityPlan {
        val b = Builder(profileOf)
        activity.devices.forEach { d ->
            if (d.powerOffOnExit && states[d.deviceId].on != false) {
                if (b.power(d, on = false)) b.gap(interDeviceDelay(d))
            }
        }
        return b.build()
    }

    /**
     * Devices to power off when [incomingIds] takes over the room: the
     * outgoing composed Activity's devices (original behavior), plus any
     * other room device the state store knows is on. One config per
     * device id — the outgoing Activity's own, when it has one.
     */
    private fun powerOffCandidates(
        roomDevices: List<ActivityDeviceConfig>,
        outgoingDevices: Set<String>,
        outgoingConfigs: List<ActivityDeviceConfig>,
        incomingIds: Set<String>
    ): List<ActivityDeviceConfig> {
        val byId = LinkedHashMap<String, ActivityDeviceConfig>()
        (outgoingConfigs + roomDevices).forEach { byId.putIfAbsent(it.deviceId, it) }
        return byId.values.filter { d ->
            val state = states[d.deviceId].on
            d.deviceId !in incomingIds && d.powerOffOnExit &&
                when (state) {
                    true -> true
                    false -> false
                    null -> d.deviceId in outgoingDevices && outgoingConfigs.any { it.deviceId == d.deviceId }
                }
        }
    }

    private companion object {
        /** Approximate length of one held-key frame (incl. its gap). */
        const val HOLD_FRAME_MS = 110
        const val MAX_HOLD_PRESSES = 50

        fun holdPresses(durationMs: Int) = (durationMs / HOLD_FRAME_MS).coerceIn(1, MAX_HOLD_PRESSES)
    }
}
