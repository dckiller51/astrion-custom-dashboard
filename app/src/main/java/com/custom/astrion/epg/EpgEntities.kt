package com.custom.astrion.epg

import java.text.Normalizer
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * The guide's channels as entities (1.6.0): `sensor.epg_<channel>`, its state what's on now,
 * its attributes the times, the synopsis and what's next, for every card. Pure, so it is unit-tested.
 */
object EpgEntities {
    private val NOT_ID = Regex("[^a-z0-9]+")
    private val MARKS = Regex("\\p{M}+")
    private const val MAX_STATE = 255
    private const val PERCENT = 100

    /** "France2.fr" → "sensor.epg_france2_fr". */
    fun entityId(channelId: String): String {
        val slug =
            Normalizer
                .normalize(channelId, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase()
                .replace(NOT_ID, "_")
                .trim('_')
        return "sensor.epg_" + slug.ifEmpty { "channel" }
    }

    /** [ch]'s state and attributes at [t]. */
    fun of(ch: EpgChannel, guide: EpgGuide, t: Long): Pair<String, Map<String, Any?>> {
        val now = guide.now(ch.id, t)
        val next = guide.next(ch.id, t)
        val attrs =
            linkedMapOf<String, Any?>(
                "friendly_name" to ch.name,
                "icon" to "mdi:television-guide",
                "astrion_epg" to true,
                "channel_id" to ch.id,
                "channel_name" to ch.name
            )
        ch.icon?.let { attrs["entity_picture"] = it }
        if (now != null) {
            attrs["title"] = now.title
            now.sub?.let { attrs["sub_title"] = it }
            now.desc?.let { attrs["description"] = it }
            now.category?.let { attrs["category"] = it }
            now.icon?.let { attrs["picture"] = it }
            attrs["start"] = iso(now.start)
            attrs["end"] = iso(now.stop)
            val length = now.stop - now.start
            if (length > 0) attrs["progress"] = ((t - now.start) * PERCENT / length).coerceIn(0, PERCENT.toLong()).toInt()
        }
        if (next != null) {
            attrs["next_title"] = next.title
            attrs["next_start"] = iso(next.start)
            attrs["next_end"] = iso(next.stop)
            next.desc?.let { attrs["next_description"] = it }
        }
        return (now?.title?.take(MAX_STATE) ?: "unknown") to attrs
    }

    /** When the state changes next after [t]: the end of what's on, else the start of what's next. */
    fun nextChange(guide: EpgGuide, t: Long): Long? = guide.channels.mapNotNull { ch ->
        guide.now(ch.id, t)?.stop ?: guide.next(ch.id, t)?.start
    }.minOrNull()

    fun iso(ms: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.ROOT)
        .apply { timeZone = TimeZone.getDefault() }
        .format(Date(ms))
}
