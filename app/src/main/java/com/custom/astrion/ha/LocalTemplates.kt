package com.custom.astrion.ha

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull

/**
 * The simple templates, filled on the remote itself (1.5.6, info card): text with only
 * `{{ states('…') }}` and `{{ state_attr('…', '…') }}` in it, read from the entities the remote
 * knows — Home Assistant's, and the direct devices' (a Sky box, a Samsung TV…), which Home
 * Assistant doesn't know. So the info card works without Home Assistant too, and with no round
 * trip. Anything else (filters, `{% if %}`, an entity the remote doesn't have) goes to Home
 * Assistant ([HaTemplates]).
 */
object LocalTemplates {
    // Every brace escaped: Android's regex engine (ICU) refuses a bare "}", which the JVM accepts —
    // the info card crashed the app on its first template.
    internal val EXPR = Regex(
        """\{\{\s*(states|state_attr)\(\s*(['"])([a-z0-9_]+\.[a-z0-9_]+)\2\s*(?:,\s*(['"])([^'"]+)\4\s*)?\)\s*\}\}"""
    )

    /** [template] filled from [entities], or null when it isn't a simple one (Home Assistant renders it). */
    fun render(template: String, entities: EntityMap): String? {
        if ("{%" in template || "{#" in template) return null
        val matches = EXPR.findAll(template).toList()
        val alone = matches.size == 1 && matches[0].value == template.trim()
        val out = StringBuilder()
        var last = 0
        for (m in matches) {
            out.append(literal(template.substring(last, m.range.first)) ?: return null)
            out.append(value(m, entities, alone) ?: return null)
            last = m.range.last + 1
        }
        out.append(literal(template.substring(last)) ?: return null)
        return out.toString()
    }

    /** Text between two expressions; null when it still holds Jinja the regex didn't take. */
    private fun literal(s: String): String? = s.takeUnless { "{{" in it || "}}" in it }

    private fun value(m: MatchResult, entities: EntityMap, alone: Boolean): String? {
        val fn = m.groupValues[1]
        val id = m.groupValues[3]
        val attr = m.groupValues[5]
        val entity = entities[id] ?: return null
        return when {
            fn == "states" && attr.isEmpty() -> entity.state
            fn == "state_attr" && attr.isNotEmpty() -> entity.attributes[attr]?.takeUnless { it is JsonNull }?.let { textOf(it) }
                // Home Assistant prints a missing attribute as nothing on its own, as "None" inside text.
                ?: if (alone) "" else "None"
            else -> null
        }
    }

    private fun textOf(e: JsonElement): String = HaTemplates.textOf(e)
}
