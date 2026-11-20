package com.custom.astrion.epg

import com.custom.astrion.tmdb.PlexNowPlaying
import com.custom.astrion.tmdb.TmdbInfo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * What the remote keeps of a guide (1.6.0): the channels chosen, their programmes over a day and
 * a half, and every channel of the source (to choose from on the web page). Saved as JSON; pure,
 * so it is unit-tested.
 */
class EpgGuide(
    val channels: List<EpgChannel>,
    shows: List<EpgShow>,
    val available: List<EpgChannel>,
    val fetched: Long
) {
    private val byChannel: Map<String, List<EpgShow>> = shows.groupBy { it.channel }.mapValues { (_, v) -> v.sortedBy { it.start } }

    val showCount: Int get() = byChannel.values.sumOf { it.size }

    fun shows(channel: String): List<EpgShow> = byChannel[channel].orEmpty()

    /** What [channel] airs at [t]. */
    fun now(channel: String, t: Long): EpgShow? = shows(channel).lastOrNull { it.start <= t && t < it.stop }

    /** What [channel] airs next after [t]. */
    fun next(channel: String, t: Long): EpgShow? = shows(channel).firstOrNull { it.start > t }

    /**
     * A programme on air at [t] (give or take [MARGIN_MS]) called [title]: a player playing live
     * TV ("Journal 13h00") finds its synopsis here. [alsoTitle]: the cleaned title, tried as well.
     */
    fun airing(title: String, t: Long, alsoTitle: String? = null): Pair<EpgChannel?, EpgShow>? {
        val wanted = listOfNotNull(title, alsoTitle).map(PlexNowPlaying::norm).filter { it.isNotEmpty() }.toSet()
        if (wanted.isEmpty()) return null
        for ((ch, list) in byChannel) {
            val s =
                list.firstOrNull { s ->
                    s.start - MARGIN_MS <= t && t < s.stop + MARGIN_MS &&
                        (PlexNowPlaying.norm(s.title) in wanted || s.sub?.let { PlexNowPlaying.norm("${s.title} $it") in wanted } == true)
                } ?: continue
            return channels.firstOrNull { it.id == ch } to s
        }
        return null
    }

    /** [airing] as what the cards show, from the guide. */
    fun infoFor(title: String, t: Long, alsoTitle: String? = null): TmdbInfo? {
        val (ch, s) = airing(title, t, alsoTitle) ?: return null
        return TmdbInfo(
            title = s.title,
            overview = s.desc,
            year = null,
            rating = null,
            posterUrl = s.icon ?: ch?.icon,
            tv = true,
            source = TmdbInfo.EPG
        )
    }

    fun toJson(): String = buildJsonObject {
        put("fetched", fetched)
        put("channels", buildJsonArray { channels.forEach { add(channelJson(it)) } })
        put("available", buildJsonArray { available.forEach { add(channelJson(it)) } })
        put(
            "shows",
            buildJsonArray {
                byChannel.values.flatten().forEach { s ->
                    add(
                        buildJsonObject {
                            put("c", s.channel)
                            put("s", s.start)
                            put("e", s.stop)
                            put("t", s.title)
                            s.sub?.let { put("u", it) }
                            s.desc?.let { put("d", it) }
                            s.category?.let { put("k", it) }
                            s.icon?.let { put("i", it) }
                        }
                    )
                }
            }
        )
    }.toString()

    companion object {
        /** A programme a few minutes late or early still counts. */
        const val MARGIN_MS = 10 * 60_000L

        private val json = Json { ignoreUnknownKeys = true }

        private fun channelJson(c: EpgChannel) = buildJsonObject {
            put("id", c.id)
            put("n", c.name)
            c.icon?.let { put("i", it) }
        }

        fun fromJson(text: String): EpgGuide? {
            val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
            fun chans(key: String) = (o[key] as? JsonArray).orEmpty().mapNotNull { e ->
                val c = e as? JsonObject ?: return@mapNotNull null
                val id = c.str("id") ?: return@mapNotNull null
                EpgChannel(id, c.str("n") ?: id, c.str("i"))
            }
            val shows = (o["shows"] as? JsonArray).orEmpty().mapNotNull { e ->
                val s = e as? JsonObject ?: return@mapNotNull null
                EpgShow(
                    channel = s.str("c") ?: return@mapNotNull null,
                    start = s.num("s") ?: return@mapNotNull null,
                    stop = s.num("e") ?: return@mapNotNull null,
                    title = s.str("t") ?: return@mapNotNull null,
                    sub = s.str("u"),
                    desc = s.str("d"),
                    category = s.str("k"),
                    icon = s.str("i")
                )
            }
            return EpgGuide(chans("channels"), shows, chans("available"), o.num("fetched") ?: 0L)
        }

        private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull

        private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.longOrNull
    }
}
