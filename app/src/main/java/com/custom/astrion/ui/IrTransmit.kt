package com.custom.astrion.ui

import android.hardware.ConsumerIrManager
import android.util.Log
import com.custom.astrion.config.IrStepConfig

/**
 * The one place an IR command leaves this device's own blaster — shared by
 * Activities, tiles and hotkeys — and the one log tag to filter on when IR
 * "doesn't work": `adb logcat -s AstrionIR`.
 *
 * Every send is logged (it used to be silent unless it threw), and so is a
 * send that can't happen because Android reports no IR emitter — that case
 * used to be skipped without a trace (`irManager?.transmit`). A carrier
 * outside the usual IR range is flagged: it's the signature of a damaged
 * Pronto code (e.g. one split by the IR sniffer before 0.4.1).
 */
object IrTransmit {
    const val TAG = "AstrionIR"
    private const val MIN_CARRIER_HZ = 30_000
    private const val MAX_CARRIER_HZ = 60_000
    private const val US_PER_MS = 1000

    fun local(irManager: ConsumerIrManager?, deviceId: String, command: String, step: IrStepConfig) {
        if (irManager == null || !irManager.hasIrEmitter()) {
            Log.w(TAG, "NOT SENT $deviceId/$command: Android reports no IR emitter on this device")
            return
        }
        runCatching { irManager.transmit(step.freq, step.pattern.toIntArray()) }
            .onSuccess { Log.i(TAG, "sent $deviceId/$command — ${describe(step)}, built-in blaster") }
            .onFailure { Log.e(TAG, "send FAILED $deviceId/$command — ${describe(step)}", it) }
    }

    /** Logs a command handed to an IR Extender (it transmits on its own). */
    fun extender(deviceId: String, command: String, extenderId: String, pronto: String) {
        Log.i(TAG, "sent $deviceId/$command — ${pronto.trim().split(Regex("\\s+")).size} Pronto words, extender $extenderId")
    }

    private fun describe(step: IrStepConfig): String {
        val carrierOk = step.freq in MIN_CARRIER_HZ..MAX_CARRIER_HZ
        return "${step.freq} Hz${if (carrierOk) "" else " (UNUSUAL carrier — damaged code?)"}, " +
            "${step.pattern.size} timings, ${step.pattern.sum() / US_PER_MS} ms"
    }
}
