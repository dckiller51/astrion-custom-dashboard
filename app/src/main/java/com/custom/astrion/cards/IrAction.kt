package com.custom.astrion.cards

/**
 * The IR part of a button, tile or title action: `irDevice` with either one
 * `irCommand` or several `irCommands` sent in a row (1.3.7) — a list
 * (`["8", "0", "1"]`) or a comma-separated string (`"8,0,1"`), spaced by
 * `irDelayMs` (else the device's inter-key delay). [prefix] is the title
 * card's `left_` / `right_`.
 */
fun CardContext.sendIrAction(action: Map<String, Any?>, prefix: String = "") {
    val device = action["${prefix}irDevice"] as? String ?: return
    val commands = irCommandsOf(action["${prefix}irCommands"]).ifEmpty { irCommandsOf(action["${prefix}irCommand"]) }
    if (commands.isEmpty()) return
    val delayMs = (action["${prefix}irDelayMs"] as? Number)?.toInt()?.takeIf { it >= 0 }
    if (commands.size == 1) sendIrCommand(device, commands[0]) else sendIrSequence(device, commands, delayMs)
}

/** `["8", "0", "1"]`, `"8,0,1"` or `"power"` → the command list; numbers count too (`[8, 0, 1]`). */
fun irCommandsOf(raw: Any?): List<String> = when (raw) {
    is List<*> -> raw.mapNotNull { v -> (v as? Number)?.let { irNumber(it) } ?: (v as? String)?.trim() }
    is String -> raw.split(',')
    is Number -> listOf(irNumber(raw))
    else -> emptyList()
}.map { it.trim() }.filter { it.isNotEmpty() }

private fun irNumber(n: Number): String = if (n.toDouble() % 1.0 == 0.0) n.toLong().toString() else n.toString()
