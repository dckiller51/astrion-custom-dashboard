package com.custom.astrion.ui

import android.hardware.ConsumerIrManager
import android.util.Log
import com.custom.astrion.config.ActivityConfig
import com.custom.astrion.config.ActivityDeviceConfig
import com.custom.astrion.config.ActivityRuntime
import com.custom.astrion.config.IrDatabaseRuntime
import com.custom.astrion.config.IrDeviceConfig
import com.custom.astrion.config.IrTarget
import com.custom.astrion.extender.ExtenderBatchCommand
import com.custom.astrion.extender.ExtenderRegistry
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.harmony.HarmonyHubRegistry
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** The two [CoroutineScope]s [ActivityDispatcher] needs, bundled purely to
 * keep its constructor under detekt's `LongParameterList` threshold — see
 * that constructor's own doc comment on why they stay distinct internally
 * rather than getting merged into one. */
data class ActivityDispatcherScopes(
    val scope: CoroutineScope,
    val extenderScope: CoroutineScope
)

/**
 * "Send a command somewhere" and "switch/stop an Activity" — extracted out
 * of [Dashboard] itself, where this used to live as six nested functions
 * (`sendIrCommand`, `dispatchActivityCommand`, `dispatchActivityPower`,
 * `switchActivity`, `startActivity`, `stopActivity`). That pushed
 * `Dashboard()` over detekt's LongMethod/CyclomaticComplexity thresholds;
 * logic here is unchanged from the original except for the batching
 * described below. Constructed once per [Dashboard] composition via
 * `remember(...)`, same lifetime the closures had before.
 */
class ActivityDispatcher(
    private val client: HaClient,
    /** [DashboardRegistries] — the same harmonyRegistry/extenderRegistry
     * bundle [Dashboard] itself already builds, reused here purely to keep
     * this constructor's own parameter count under detekt's
     * `LongParameterList` threshold; unpacked into the two private vals
     * below so nothing else in this class has to change. */
    registries: DashboardRegistries,
    private val irManager: ConsumerIrManager?,
    private val irDevicesById: Map<String, IrDeviceConfig>,
    private val activitiesById: Map<String, ActivityConfig>,
    private val activityRuntime: ActivityRuntime,
    /** Called with [ActivityConfig.page] right after [switchActivity] marks
     * the Activity active — the single place "start this Activity" now
     * navigates from, replacing a scene_grid tile's own redundant `"page"`
     * field for this case (see [SceneGridCard]'s doc comment). Firing here,
     * after `markActiveById`, rather than synchronously in the tile's own
     * tap handler, is what actually matters: this is genuinely
     * asynchronous (every device command in the plan has to go out first),
     * so calling it any earlier would be racing work that hasn't happened
     * yet. */
    private val navigateToPage: (String) -> Unit,
    /** [ActivityDispatcherScopes] — same reasoning as [registries]: purely
     * a `LongParameterList`-driven bundle, not a behavior change. Kept as
     * two distinct scopes internally exactly as before (see each private
     * val's own doc, right below). */
    scopes: ActivityDispatcherScopes
) {
    /** Used by [startActivity]/[stopActivity] — matches the original
     * `scope.launch { switchActivity(activity) }`, the same CoroutineScope
     * [PageIndicator]'s dot-tap navigation and hardware-nav use elsewhere
     * in [Dashboard]. */
    private val scope: CoroutineScope = scopes.scope

    /** Used only by extender-bound sends — matches the original, separate
     * `coroutineScope.launch { client.send(...) }`. Kept distinct from
     * [scope] rather than merged, since that's exactly how the two were
     * used before this got extracted — no reason to introduce a behavior
     * change alongside a code-motion refactor. */
    private val extenderScope: CoroutineScope = scopes.extenderScope
    private val harmonyRegistry: HarmonyHubRegistry = registries.harmonyRegistry
    private val extenderRegistry: ExtenderRegistry = registries.extenderRegistry

    /** One dispatched action, in the order it should run. Built up-front
     * by [switchActivity]/[stopActivity] rather than fired immediately
     * device-by-device, specifically so [runSteps] can look at the whole
     * sequence and merge consecutive extender-bound steps into a single
     * batched request (see [DispatchStep.Extender]'s kdoc for why that
     * matters). */
    private sealed class DispatchStep {
        /** How long to wait after the *previous* step finished before
         * this one runs — the exact same value a device's own
         * `delayAfterMs` used to feed straight into a `delay()` call
         * between devices. For a batched [Extender] run, this becomes a
         * per-line delay prefix in the request body instead of a real
         * wait — see [runSteps]. */
        abstract val gapBeforeMs: Int

        /** A single Pronto code bound for [extenderId]. Consecutive
         * `Extender` steps targeting the *same* extender get merged by
         * [runSteps] into one `POST /pronto` batch instead of one
         * request per step — this is what actually fixes commands
         * getting silently dropped when several arrive close together
         * (e.g. an Activity's TV-off immediately followed by another
         * device's command, with no gap between them: a fast second
         * request used to be able to overwrite a first one the extender
         * hadn't transmitted yet). */
        data class Extender(val extenderId: String, val prontoCode: String, override val gapBeforeMs: Int) : DispatchStep()

        /** Anything that isn't a batchable extender send — local IR,
         * Harmony, or a Home Assistant service call. Always dispatched
         * on its own, with a real `delay()` for [gapBeforeMs] beforehand
         * if set, exactly like every step used to run before batching
         * existed. */
        data class Immediate(override val gapBeforeMs: Int, val run: () -> Unit) : DispatchStep()
    }

    /** Accumulates [DispatchStep]s for one Activity switch/stop in device
     * order, tracking the "gap before the next step" implied by a
     * device's own `delayAfterMs` so it lands on the right step
     * regardless of how many steps that device itself produces (a power
     * command, an input command, or both). */
    private inner class StepPlan {
        val steps = mutableListOf<DispatchStep>()
        private var pendingGapMs = 0

        fun setGapBeforeNext(ms: Int) {
            this.pendingGapMs = ms
        }

        fun addPower(d: ActivityDeviceConfig, on: Boolean) {
            if (d.source == "ha") {
                val gap = this.takeGap()
                val domain = d.deviceId.substringBefore('.')
                this.steps += DispatchStep.Immediate(gap) {
                    client.callService(ServiceCall(domain = domain, service = if (on) "turn_on" else "turn_off", entityId = d.deviceId))
                }
            } else {
                this.addCommand(d, if (on) d.powerOnCommand else d.powerOffCommand)
            }
        }

        fun addCommand(d: ActivityDeviceConfig, command: String?) {
            if (command == null) return
            val gap = this.takeGap()
            when (d.source) {
                "ir" -> this.addIrCommand(d, command, gap)
                "harmony" -> this.steps += DispatchStep.Immediate(gap) {
                    harmonyRegistry.client(d.hub)?.sendCommand(d.deviceId, command)
                        ?: Log.w("Dashboard", "activity device ${d.deviceId}: hub ${d.hub} not configured")
                }
                "ha" -> this.steps += DispatchStep.Immediate(gap) {
                    val domain = d.deviceId.substringBefore('.')
                    client.callService(ServiceCall.of(domain, "select_source", d.deviceId, "source" to command))
                }
            }
        }

        private fun addIrCommand(d: ActivityDeviceConfig, command: String, gap: Int) {
            val device = irDevicesById[d.deviceId]
            val resolved = device?.let { IrDatabaseRuntime.resolve(it, command) }
            when {
                device == null -> Log.w("Dashboard", "activity device ${d.deviceId}: unknown irDevice")
                resolved == null -> Log.w("Dashboard", "activity device ${d.deviceId}: command \"$command\" not found")
                else -> when (val target = device.target) {
                    // Unchanged from before this device gained a `target` field —
                    // every dashboard.json without one defaults here.
                    IrTarget.Local -> this.steps += DispatchStep.Immediate(gap) {
                        runCatching { irManager?.transmit(resolved.freq, resolved.pattern.toIntArray()) }
                            .onFailure { Log.e("Dashboard", "IR send failed: ${d.deviceId}/$command", it) }
                    }
                    is IrTarget.Extender -> {
                        val pronto = resolved.pronto
                        if (pronto == null) {
                            // Inline-sourced devices don't carry the original Pronto
                            // string in dashboard.json (only the already-decoded
                            // freq/pattern) -- only ir-database (SdCardRef) devices
                            // can target an extender for now. See IrStepConfig's kdoc.
                            Log.w(
                                "Dashboard",
                                "activity device ${d.deviceId}/$command targets an extender but has no raw " +
                                    "Pronto string (Inline-sourced IR devices can't target an extender yet)"
                            )
                        } else {
                            this.steps += DispatchStep.Extender(target.extenderId, pronto, gap)
                        }
                    }
                }
            }
        }

        private fun takeGap(): Int {
            val gap = this.pendingGapMs
            this.pendingGapMs = 0
            return gap
        }
    }

    /** Runs a [StepPlan]'s steps in order. Consecutive [DispatchStep.Extender]
     * entries targeting the same extender are merged into one
     * [com.custom.astrion.extender.ExtenderClient.sendBatch] call — each
     * step's [DispatchStep.gapBeforeMs] becomes that batch line's own
     * delay prefix, handled by the extender's firmware, instead of this
     * app sitting through a real `delay()` between separate requests.
     * Anything else still gets a real `delay()` beforehand (if it has a
     * gap) and runs on its own — there's no way to fold a Harmony or HA
     * call into an extender's request body. */
    private suspend fun runSteps(steps: List<DispatchStep>) {
        var i = 0
        while (i < steps.size) {
            when (val step = steps[i]) {
                is DispatchStep.Extender -> i = this.runExtenderRun(steps, i, step.extenderId)
                is DispatchStep.Immediate -> {
                    if (step.gapBeforeMs > 0) delay(step.gapBeforeMs.milliseconds)
                    step.run()
                    i++
                }
            }
        }
    }

    /** Collects every consecutive [DispatchStep.Extender] step starting at
     * [start] that targets [extenderId], sends them as one batch, and
     * returns the index of the first step (extender-bound or not) that
     * didn't belong to this run — [runSteps] resumes from there. Split
     * out purely to keep [runSteps] itself under detekt's
     * NestedBlockDepth threshold. */
    private fun runExtenderRun(steps: List<DispatchStep>, start: Int, extenderId: String): Int {
        val batch = mutableListOf<ExtenderBatchCommand>()
        var j = start
        while (j < steps.size) {
            val s = steps[j]
            if (s !is DispatchStep.Extender || s.extenderId != extenderId) break
            batch += ExtenderBatchCommand(s.gapBeforeMs, s.prontoCode)
            j++
        }
        extenderRegistry.client(extenderId)?.let { c -> extenderScope.launch { c.sendBatch(batch) } }
        return j
    }

    /** Single-command entry point, used by scene_grid tiles and hotkeys
     * outside of an Activity switch — kept as its own direct
     * implementation rather than going through [StepPlan]/[runSteps],
     * since a lone command never needs batching against anything else. */
    fun sendIrCommand(deviceId: String, command: String) {
        val device = irDevicesById[deviceId]
        val resolved = device?.let { IrDatabaseRuntime.resolve(it, command) }
        when {
            device == null -> Log.w("Dashboard", "sendIrCommand: unknown irDevice \"$deviceId\"")
            resolved == null -> Log.w(
                "Dashboard",
                "sendIrCommand: device \"$deviceId\" has no command \"$command\" " +
                    "(if it's an ir-database reference, check /sdcard/astrion/ir-database/ — see IrDatabaseRuntime logs above)"
            )
            // Unchanged from before this device gained a `target` field —
            // every dashboard.json without one defaults here.
            device.target == IrTarget.Local ->
                runCatching { irManager?.transmit(resolved.freq, resolved.pattern.toIntArray()) }
                    .onFailure { Log.e("Dashboard", "IR send failed: $deviceId/$command", it) }
            else -> this.sendIrCommandViaExtender(deviceId, command, resolved.pronto, device.target as IrTarget.Extender)
        }
    }

    /** The [IrTarget.Extender] branch of [sendIrCommand], split out purely
     * to keep that function under detekt's NestedBlockDepth threshold. */
    private fun sendIrCommandViaExtender(deviceId: String, command: String, pronto: String?, target: IrTarget.Extender) {
        if (pronto == null) {
            // Inline-sourced devices don't carry the original Pronto string in
            // dashboard.json (only the already-decoded freq/pattern) -- only
            // ir-database (SdCardRef) devices can target an extender for now.
            // See IrStepConfig's kdoc.
            Log.w(
                "Dashboard",
                "sendIrCommand: $deviceId/$command targets an extender but has no raw " +
                    "Pronto string (Inline-sourced IR devices can't target an extender yet)"
            )
            return
        }
        extenderRegistry.client(target.extenderId)?.let { c -> extenderScope.launch { c.send(pronto) } }
    }

    /**
     * The composed-Activity switch: diffs the outgoing Activity (whatever
     * was active in `activity.room` before, if anything — Harmony-backed or
     * composed, both work uniformly via TrackedActivity.devices, see below)
     * against `activity` itself. A device present in both is left alone —
     * no power cycle, and its input is only re-sent if this Activity gives
     * it one — a device only in the outgoing one gets powered off (unless
     * powerOffOnExit is false), a device only in the incoming one gets
     * powered on + its input (unless powerOnFirst is false). Devices are
     * *planned* in declared order, each carrying its own delayAfterMs as
     * the gap before the next step — [runSteps] then decides, per step,
     * whether that gap becomes a real wait or an embedded batch delay.
     *
     * "Already on" is read from TrackedActivity.devices, not from a
     * composed ActivityConfig's own device list — this matters a lot for a
     * shared device with only a toggle command (no discrete on/off, e.g.
     * many IR soundbars): if the outgoing Activity was Harmony-backed (no
     * ActivityConfig of its own at all), we'd otherwise have no idea a
     * shared device was already on and could send an unwanted toggle. See
     * HotkeyConfig.devices / the scene_grid "devices" hint for how a
     * Harmony-backed tracked tile declares which physical devices it
     * touches. Actual *stop* commands (powerOffCommand) still only fire for
     * a genuinely composed outgoing Activity — a Harmony-backed one has no
     * ActivityDeviceConfig of its own to run one from; its hub is left to
     * manage its own devices' power on its own terms.
     */
    suspend fun switchActivity(activity: ActivityConfig) {
        val outgoingTracked = activityRuntime.activeActivity(activity.room)
        val outgoingDeviceIds = outgoingTracked?.devices?.toSet().orEmpty()
        val incomingIds = activity.devices.map { it.deviceId }.toSet()
        val outgoingComposed = outgoingTracked?.let { activitiesById[it.id] }

        val plan = StepPlan()
        outgoingComposed?.devices?.forEach { d ->
            if (d.deviceId !in incomingIds && d.powerOffOnExit) plan.addPower(d, on = false)
        }

        activity.devices.forEachIndexed { index, d ->
            val alreadyOn = d.deviceId in outgoingDeviceIds
            if (!alreadyOn && d.powerOnFirst) plan.addPower(d, on = true)
            plan.addCommand(d, d.inputCommand)
            if (index < activity.devices.lastIndex) plan.setGapBeforeNext(d.delayAfterMs)
        }

        runSteps(plan.steps)
        activityRuntime.markActiveById(activity.id)
        activity.page?.let(navigateToPage)
    }

    fun startActivity(activityId: String) {
        activitiesById[activityId]?.let { activity ->
            scope.launch { switchActivity(activity) }
        } ?: Log.w("Dashboard", "startActivity: unknown activity \"$activityId\"")
    }

    /**
     * The missing counterpart to switchActivity/startActivity: stops
     * whichever Activity is currently active in `room`, without starting a
     * new one. Two real cases:
     *  - Composed Activity (AppConfig.activities): send each device's own
     *    powerOffCommand (same as switchActivity's outgoing-diff branch,
     *    just with an empty incoming set — same batching applies too, now
     *    that this also goes through [StepPlan]/[runSteps]), then clear
     *    the room.
     *  - Harmony-backed tracked Activity: Harmony has no "stop just this
     *    Activity" command — a hub always runs exactly one Activity at a
     *    time, so PowerOff on *that Activity's own hub* is the correct,
     *    narrowest possible stop (it never touches a different room's hub).
     *    ActivityRuntime.bind()'s own "-1" handling clears the room(s) that
     *    hub drives once the hub confirms it, so no explicit clear() here.
     * A plain HA-entity tracked tile has no dedicated "stop" of its own
     * (it's whatever a scene_grid tap already toggles) — just clear it.
     */
    fun stopActivity(room: String) {
        val tracked = activityRuntime.activeActivity(room) ?: return
        val composed = activitiesById[tracked.id]
        when {
            composed != null -> {
                val plan = StepPlan()
                composed.devices.forEach { d -> if (d.powerOffOnExit) plan.addPower(d, on = false) }
                scope.launch { runSteps(plan.steps) }
                activityRuntime.clear(room)
            }
            tracked.harmonyActivityId != null ->
                harmonyRegistry.client(tracked.harmonyHub)?.startActivity("-1")
                    ?: Log.w("Dashboard", "stopActivity($room): hub ${tracked.harmonyHub} not configured")
            else -> activityRuntime.clear(room)
        }
    }
}
