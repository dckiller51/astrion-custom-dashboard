package com.custom.astrion.cards.impl

/**
 * The TV guide card's pure part (1.6.0): which channels it lists, and a programme's times as the
 * card shows them. Unit-tested.
 */
internal object TvGuideText {
    private val CLOCK = Regex("T(\\d{2}):(\\d{2})")

    /** "2026-11-17T13:00:00+01:00" → "13:00" (the guide's times are already in the remote's zone). */
    fun clock(iso: String?): String? = iso?.let { CLOCK.find(it) }?.let { "${it.groupValues[1]}:${it.groupValues[2]}" }

    /** "13:00 – 13:45", or the start alone. */
    fun span(start: String?, end: String?): String? {
        val a = clock(start) ?: return null
        return clock(end)?.let { "$a – $it" } ?: a
    }

    /**
     * The channels to list: [configured] (entity ids, in their order) when there are some, else
     * every guide channel among [entities] (ids starting with `sensor.epg_`), by name.
     */
    fun channels(configured: List<String>, entities: Map<String, String>): List<String> {
        if (configured.isNotEmpty()) return configured
        return entities.filterKeys { it.startsWith(PREFIX) }.entries.sortedBy { it.value.lowercase() }.map { it.key }
    }

    /**
     * A channel's link played on the card's [player] (1.6.0): an address (an app's own link, e.g.
     * `https://www.canalplus.com/live/?channel=313` opens M6 in CANAL+ on Android TV) as a `url`, a
     * number as a `channel`; null without a player or a link. In the button grid's keys.
     */
    fun linkAction(player: String?, link: String?): Map<String, Any?>? {
        val p = player?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val l = link?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val type = if (l.all { it.isDigit() }) "channel" else "url"
        return mapOf(
            "service" to "media_player.play_media",
            "entity_id" to p,
            "data" to mapOf("media_content_type" to type, "media_content_id" to l)
        )
    }

    const val PREFIX = "sensor.epg_"
}
