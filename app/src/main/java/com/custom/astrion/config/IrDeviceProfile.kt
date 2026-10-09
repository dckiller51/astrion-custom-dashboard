package com.custom.astrion.config

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * How a device has to be driven so an Activity works reliably — the same
 * per-device knowledge a Logitech Harmony hub used, read from the optional
 * `"profile"` block of an ir-database model entry. The sniffer's
 * "Import from Harmony" fills it from the Logitech Harmony IR archive;
 * it can also be written by hand for a device captured another way.
 *
 * ```json
 * "profile": {
 *   "power":  { "type": "discrete", "on": ["PowerOn"], "off": ["PowerOff"] },
 *   "timing": { "power_on_delay_ms": 5000, "inter_key_delay_ms": 100,
 *               "inter_device_delay_ms": 500, "input_delay_ms": 0, "repeats": 3 },
 *   "inputs": [ { "name": "HDMI 1", "steps": ["InputHdmi1", {"delay_ms": 2500}, "Exit"] } ]
 * }
 * ```
 *
 * A device without a profile keeps Astrion's original behavior exactly.
 */
data class IrDeviceProfile(
    val power: PowerProfile = PowerProfile(),
    val timing: TimingProfile = TimingProfile(),
    /** Input name ("HDMI 1") → the key sequence that selects it. */
    val inputs: Map<String, List<IrMacroStep>> = emptyMap()
) {
    /** The input whose name matches [name] (case/space-insensitive), or null. */
    fun input(name: String): List<IrMacroStep>? = inputs[name] ?: inputs.entries.firstOrNull { normalize(it.key) == normalize(name) }?.value
        // "InputHdmi2" (a command id typed as the input) ↔ input "HDMI 2"
        ?: inputs.entries.firstOrNull { normalize(it.key) == normalize(name).removePrefix("input") }?.value

    companion object {
        private fun normalize(s: String) = s.lowercase().replace(Regex("[^a-z0-9]"), "")

        /** Lenient parse — unknown/malformed parts fall back to defaults
         * rather than throwing, so one odd field never loses a device's
         * commands. */
        fun parse(el: JsonElement?): IrDeviceProfile? {
            val obj = el as? JsonObject ?: return null
            return IrDeviceProfile(
                power = PowerProfile.parse(obj["power"] as? JsonObject),
                timing = TimingProfile.parse(obj["timing"] as? JsonObject),
                inputs = (obj["inputs"] as? JsonArray).orEmpty().mapNotNull { inputEl ->
                    val input = inputEl as? JsonObject ?: return@mapNotNull null
                    val name = (input["name"] as? JsonPrimitive)?.content?.trim().orEmpty()
                    val steps = IrMacroStep.parseList(input["steps"])
                    if (name.isEmpty() || steps.isEmpty()) null else name to steps
                }.toMap()
            )
        }
    }
}

enum class PowerType { DISCRETE, TOGGLE, NONE, UNKNOWN }

data class PowerProfile(
    val type: PowerType = PowerType.UNKNOWN,
    val on: List<IrMacroStep> = emptyList(),
    val off: List<IrMacroStep> = emptyList(),
    val toggle: List<IrMacroStep> = emptyList()
) {
    companion object {
        fun parse(obj: JsonObject?): PowerProfile {
            if (obj == null) return PowerProfile()
            val type =
                when ((obj["type"] as? JsonPrimitive)?.content) {
                    "discrete" -> PowerType.DISCRETE
                    "toggle" -> PowerType.TOGGLE
                    "none" -> PowerType.NONE
                    else -> PowerType.UNKNOWN
                }
            return PowerProfile(
                type = type,
                on = IrMacroStep.parseList(obj["on"]),
                off = IrMacroStep.parseList(obj["off"]),
                toggle = IrMacroStep.parseList(obj["toggle"])
            )
        }
    }
}

data class TimingProfile(
    /** How long the device needs after power-on before it reacts to anything else. */
    val powerOnDelayMs: Int = 0,
    /** Gap between two keys sent to this same device. */
    val interKeyDelayMs: Int = 0,
    /** Gap before the next device's command, after one sent to this device. */
    val interDeviceDelayMs: Int = 0,
    /** Settling time after an input change. */
    val inputDelayMs: Int = 0,
    /** How many times each IR frame is sent per key press (Harmony's pressMinRepeats). */
    val repeats: Int = 1
) {
    companion object {
        private const val MAX_DELAY_MS = 60_000
        private const val MAX_REPEATS = 10

        fun parse(obj: JsonObject?): TimingProfile {
            if (obj == null) return TimingProfile()
            fun ms(key: String) = ((obj[key] as? JsonPrimitive)?.intOrNull ?: 0).coerceIn(0, MAX_DELAY_MS)
            return TimingProfile(
                powerOnDelayMs = ms("power_on_delay_ms"),
                interKeyDelayMs = ms("inter_key_delay_ms"),
                interDeviceDelayMs = ms("inter_device_delay_ms"),
                inputDelayMs = ms("input_delay_ms"),
                repeats = ((obj["repeats"] as? JsonPrimitive)?.intOrNull ?: 1).coerceIn(1, MAX_REPEATS)
            )
        }
    }
}

/** One step of a power/input key sequence. */
sealed class IrMacroStep {
    data class Send(val command: String) : IrMacroStep()

    data class Delay(val ms: Int) : IrMacroStep()

    /** Keep sending [command] for [durationMs] (0 = a single press). */
    data class Hold(val command: String, val durationMs: Int) : IrMacroStep()

    companion object {
        fun parseList(el: JsonElement?): List<IrMacroStep> = (el as? JsonArray).orEmpty().mapNotNull(::parse)

        private fun parse(el: JsonElement): IrMacroStep? = when (el) {
            is JsonPrimitive -> el.content.trim().takeIf { it.isNotEmpty() }?.let(::Send)
            is JsonObject -> {
                val delay = (el["delay_ms"] as? JsonPrimitive)?.intOrNull
                val hold = (el["hold"] as? JsonPrimitive)?.content?.trim()
                when {
                    delay != null -> Delay(delay.coerceAtLeast(0))
                    !hold.isNullOrEmpty() -> Hold(hold, (el["duration_ms"]?.jsonPrimitive?.intOrNull ?: 0).coerceAtLeast(0))
                    else -> null
                }
            }
            else -> null
        }
    }
}

/**
 * Builds one Pronto (type 0000) whose "once" section holds the first frame
 * followed by `repeats - 1` repeat frames, so a single
 * `ConsumerIrManager.transmit()` (or one extender line) sends a press
 * exactly the way the device's own remote does.
 *
 * The repeat frame is, in order of preference: [prontoRepeat] (a separate
 * repeat burst, e.g. NEC's short repeat code — sending the full frame again
 * would count as a *second press*, which matters a lot for a power
 * toggle), the code's own repeat section, then the full frame itself.
 * A [prontoRepeat] that is malformed, truncated or on a different carrier
 * is ignored. Never exceeds [maxDurationUs] in total (Android's IR HAL
 * rejects very long patterns); fewer repeats are used instead.
 *
 * Returns [pronto] unchanged when [repeats] <= 1 or it can't be parsed.
 */
fun buildRepeatedPronto(pronto: String, prontoRepeat: String?, repeats: Int, maxDurationUs: Long = MAX_IR_PATTERN_US): String {
    val main = ProntoWords.parse(pronto)
    val base = main?.let { it.once.ifEmpty { it.repeat } }.orEmpty()
    if (repeats <= 1 || main == null || base.isEmpty()) return pronto

    val separate = prontoRepeat?.let(ProntoWords::parse)?.takeIf { it.freqCode == main.freqCode }
    val frame = separate?.let { it.once.ifEmpty { it.repeat } }?.takeIf { it.isNotEmpty() }
        ?: main.repeat.ifEmpty { base }
    val periodUs = 1_000_000.0 / (4145146.0 / main.freqCode)
    fun durationUs(words: List<Int>) = words.sumOf { it * periodUs }.toLong()
    val frameUs = durationUs(frame).coerceAtLeast(1)
    val extra = minOf(repeats - 1, ((maxDurationUs - durationUs(base)) / frameUs).toInt().coerceAtLeast(0))

    val words = base + List(extra) { frame }.flatten()
    val header = listOf(0x0000, main.freqCode, words.size / 2, 0)
    return if (extra == 0) pronto else (header + words).joinToString(" ") { "%04X".format(it) }
}

/**
 * Undoes the IR sniffer's old capture bug (fixed in sniffer 0.4.1): it
 * split a code at its own `0000` repeat-length field, storing the
 * header-less rest as `pronto` (`0000 015A 00AE …`, which reads as a
 * ~12 kHz carrier with a wrong length) and the cut-off header as
 * `pronto_repeat` (`0000 006D 0022`). When [pronto] isn't a complete,
 * plausible code but `"$prontoRepeat $pronto"` is, returns that rejoined
 * code with no repeat; otherwise returns both unchanged. Lets files already
 * copied onto a remote work without regenerating them.
 */
fun repairSplitPronto(pronto: String, prontoRepeat: String?): Pair<String, String?> {
    val joined = prontoRepeat?.let { "$it $pronto" }
    val broken = !ProntoWords.isPlausible(pronto)
    return if (broken && joined != null && ProntoWords.isPlausible(joined)) joined to null else pronto to prontoRepeat
}

/** Android's IR HAL rejects patterns much past ~2 s of total duration. */
const val MAX_IR_PATTERN_US = 1_900_000L

/** A parsed, length-checked learned (0000) Pronto code. */
private class ProntoWords(val freqCode: Int, val once: List<Int>, val repeat: List<Int>) {
    companion object {
        private const val MIN_CARRIER_HZ = 20_000
        private const val MAX_CARRIER_HZ = 60_000

        /** Complete code (exact declared length) on a real IR carrier. */
        fun isPlausible(code: String): Boolean {
            val parsed = parse(code) ?: return false
            val exact = code.trim().split(Regex("\\s+")).size == 4 + parsed.once.size + parsed.repeat.size
            return exact && (4145146.0 / parsed.freqCode).toInt() in MIN_CARRIER_HZ..MAX_CARRIER_HZ
        }

        fun parse(code: String): ProntoWords? {
            val words = code.trim().split(Regex("\\s+")).mapNotNull { it.toIntOrNull(16) }
            val header = words.size >= 4 && words[0] == 0x0000 && words[1] != 0
            val onceLen = if (header) words[2] * 2 else 0
            val repeatLen = if (header) words[3] * 2 else 0
            val complete = header && words.size >= 4 + onceLen + repeatLen // false when truncated
            val rest = words.drop(4)
            return if (complete) ProntoWords(words[1], rest.take(onceLen), rest.drop(onceLen).take(repeatLen)) else null
        }
    }
}
