package com.custom.astrion.sonos

/**
 * Just enough XML for Sonos' answers (1.6.3): SOAP responses, the zone group state, DIDL-Lite
 * metadata. Their shape is simple and fixed, so a few patterns do it — no XML parser, which keeps
 * this pure and unit-tested on the JVM (Android's own XmlPullParser isn't there).
 */
object SonosXml {
    private val ENTITY = Regex("&(lt|gt|quot|apos|amp|#\\d+|#x[0-9a-fA-F]+);")
    private val ATTR = Regex("([\\w:.-]+)\\s*=\\s*\"([^\"]*)\"")

    fun unescape(s: String): String = ENTITY.replace(s) { m ->
        when (val e = m.groupValues[1]) {
            "lt" -> "<"
            "gt" -> ">"
            "quot" -> "\""
            "apos" -> "'"
            "amp" -> "&"
            else ->
                runCatching {
                    val code = if (e.startsWith("#x")) e.substring(2).toInt(HEX) else e.substring(1).toInt()
                    String(Character.toChars(code))
                }.getOrDefault(m.value)
        }
    }

    fun escape(s: String): String = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /** The text of the first element named [name] (any prefix), unescaped once; null when absent. */
    fun text(xml: String, name: String): String? =
        Regex("<(?:[\\w-]+:)?${Regex.escape(name)}(?:\\s[^>]*)?>(.*?)</(?:[\\w-]+:)?${Regex.escape(name)}>", RegexOption.DOT_MATCHES_ALL)
            .find(xml)
            ?.groupValues
            ?.get(1)
            ?.let { unescape(it) }

    /** An element: its attributes (unescaped) and its raw inner XML ("" when self-closing). */
    data class Element(val attrs: Map<String, String>, val inner: String)

    /** Every element named [name] (any prefix), in order; elements of the same name aren't nested in Sonos' XML. */
    fun elements(xml: String, name: String): List<Element> {
        val n = Regex.escape(name)
        val pattern = Regex("<(?:[\\w-]+:)?$n(\\s[^>]*?)?(/>|>(.*?)</(?:[\\w-]+:)?$n>)", RegexOption.DOT_MATCHES_ALL)
        return pattern.findAll(xml).map { m -> Element(attrs(m.groupValues[1]), m.groupValues[3]) }.toList()
    }

    fun attrs(s: String): Map<String, String> = ATTR.findAll(s).associate { it.groupValues[1] to unescape(it.groupValues[2]) }

    private const val HEX = 16
}
