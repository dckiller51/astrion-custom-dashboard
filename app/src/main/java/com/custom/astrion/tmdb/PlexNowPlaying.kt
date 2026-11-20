package com.custom.astrion.tmdb

import java.net.URLEncoder
import java.text.Normalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/** Where what's playing is looked up (1.6.0): Plex first then TMDB, or only one of them. */
enum class MediaInfoSource(val id: String) {
    AUTO("auto"),
    PLEX("plex"),
    TMDB("tmdb");

    companion object {
        fun of(id: String?): MediaInfoSource = entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/** One thing the Plex server is playing right now, from `/status/sessions`. */
data class PlexSession(
    val title: String,
    val series: String?,
    val originalTitle: String?,
    val summary: String?,
    val year: Int?,
    val rating: Double?,
    val thumb: String?,
    val tv: Boolean
)

/**
 * What the Plex server says of what plays (1.6.0): exact, in the library's own language, with
 * nothing to guess, where TMDB has to search a cleaned title. Pure, so it is unit-tested.
 */
object PlexNowPlaying {
    private val json = Json { ignoreUnknownKeys = true }
    private val NOT_LETTER = Regex("[^\\p{L}\\p{Nd}]+")
    private val MARKS = Regex("\\p{M}+")

    /** The films and episodes in a `/status/sessions` answer (music and photos left out). */
    fun parse(body: String): List<PlexSession> {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return emptyList()
        val items = ((root["MediaContainer"] as? JsonObject)?.get("Metadata") as? JsonArray).orEmpty()
        return items.mapNotNull { it as? JsonObject }.mapNotNull { m ->
            val type = m.str("type")
            if (type != "movie" && type != "episode") return@mapNotNull null
            val tv = type == "episode"
            PlexSession(
                title = m.str("title") ?: return@mapNotNull null,
                series = m.str("grandparentTitle").takeIf { tv },
                originalTitle = m.str("originalTitle"),
                summary = m.str("summary"),
                year = (m["year"] as? JsonPrimitive)?.intOrNull ?: (m["parentYear"] as? JsonPrimitive)?.intOrNull,
                rating = m.num("audienceRating") ?: m.num("rating"),
                // A series' poster rather than its episode's still: what the cover frame expects.
                thumb = (if (tv) m.str("grandparentThumb") else null) ?: m.str("thumb"),
                tv = tv
            )
        }
    }

    /**
     * The session playing [q], matched on its title, its series' or its original title. [plexPlayer]:
     * the player says it plays Plex, so when the server plays one thing only, that's it.
     */
    fun match(sessions: List<PlexSession>, q: TmdbQuery, plexPlayer: Boolean): PlexSession? {
        val want = norm(q.title)
        if (want.isNotEmpty()) {
            sessions.firstOrNull { s -> listOfNotNull(s.series, s.title, s.originalTitle).any { norm(it) == want } }?.let { return it }
        }
        return sessions.singleOrNull()?.takeIf { plexPlayer }
    }

    /** [s] as the cards show it; [posterOf] turns its thumb into a picture's address. */
    fun infoOf(s: PlexSession, posterOf: (String) -> String): TmdbInfo = TmdbInfo(
        title = s.series ?: s.title,
        overview = s.summary,
        year = s.year,
        rating = s.rating?.takeIf { it > 0.0 },
        posterUrl = s.thumb?.let(posterOf),
        tv = s.tv,
        source = TmdbInfo.PLEX
    )

    /** "Amélie (2001)" and "amelie 2001" alike: no case, accent, space nor punctuation. */
    fun norm(s: String): String = Normalizer
        .normalize(s, Normalizer.Form.NFD)
        .replace(MARKS, "")
        .lowercase()
        .replace(NOT_LETTER, "")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.num(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull
}

/**
 * A Plex picture through the remote's own web server (`/plex-art`, see PlexRoutes), which adds the
 * token: the entity's attributes never carry it.
 */
fun plexArtUrl(thumb: String): String = "http://127.0.0.1:8080/plex-art?path=" + URLEncoder.encode(thumb, "UTF-8")
