package com.custom.astrion.tmdb

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/** What to look up on TMDB: a cleaned title, its year when known, and whether it's a series. */
data class TmdbQuery(val title: String, val year: Int?, val tv: Boolean, val episode: String = "") {
    /**
     * One cache entry per query, and per episode for a series (1.6.2: the first episode's
     * synopsis, or its "nothing found", stayed on every other episode of the series).
     */
    val key: String get() = "${if (tv) "tv" else "any"}|${title.lowercase()}|${year ?: ""}" + if (episode.isEmpty()) "" else "|$episode"
}

/** What TMDB (or the Plex server, [source]) found: what the cards show. [posterUrl] is a full image address. */
data class TmdbInfo(
    val title: String,
    val overview: String?,
    val year: Int?,
    val rating: Double?,
    val posterUrl: String?,
    val tv: Boolean,
    val source: String = TMDB
) {
    companion object {
        const val TMDB = "tmdb"
        const val PLEX = "plex"
        const val EPG = "epg"
    }
}

/**
 * From a player's attributes to a TMDB search, and from TMDB's answer to [TmdbInfo] (1.6.0).
 * Pure, so it is unit-tested. The title clean-up follows Zavie's Home Assistant recipe: a
 * studio's name in front ("Marvel Studios' Avengers"), a file's name (dots, year, quality tags),
 * HBO Max sending the episode as the title and the series as the artist.
 */
object TmdbMatch {
    private val json = Json { ignoreUnknownKeys = true }

    private val FILE_EXT = Regex("\\.(mkv|mp4|avi|m2ts|iso|ts|mov|wmv|m4v)$", RegexOption.IGNORE_CASE)
    private val YEAR_PAREN = Regex("\\(((?:19|20)\\d{2})\\)")
    private val YEAR = Regex("(?<!\\d)((?:19|20)\\d{2})(?!\\d)")
    private val QUALITY =
        Regex(
            "\\b(2160p|1080p|720p|480p|4k|uhd|bluray|blu-ray|bdrip|remux|web-?dl|webrip|hdr10?|x264|x265|h\\.?264|h\\.?265|hevc|" +
                "aac|ac3|dts|truehd|atmos|multi|vostfr|vff|vfq|french|truefrench)\\b.*$",
            RegexOption.IGNORE_CASE
        )
    private val STUDIO = Regex("^(marvel studios|disney|pixar|lucasfilm|dreamworks)(['’]s?)?\\s+", RegexOption.IGNORE_CASE)
    private val SPACES = Regex("\\s+")

    /**
     * "Ticaret - Saison 1", "Lost: Season 2", "Lost S01E03", and Plex's own "Game of Thrones - S1 · E3 -
     * Lord Snow" (its Apple TV app, 1.6.1): the series, its season said after it.
     */
    private val SEASON =
        Regex(
            "(\\s*[-–:,]\\s*|\\s+)(saison|season|staffel|stagione|temporada)\\s*\\d+.*$|" +
                "(\\s*[-–:,]\\s*|\\s+)s\\d{1,2}\\s*[·•.x-]?\\s*e\\d{1,3}\\b.*$",
            RegexOption.IGNORE_CASE
        )

    /** Players playing music, where TMDB has nothing to say. */
    private val MUSIC_APPS =
        listOf("music", "musique", "musica", "música", "musik", "spotify", "deezer", "tidal", "qobuz", "podcast", "radio", "sonos", "heos")
    private val MUSIC_TYPES = setOf("music", "podcast", "playlist", "album", "track")

    /** "Saison 1" as an album: an episode (Rivulet on an Apple TV added in Astrion, 1.6.2), its series as the artist. */
    private val SEASON_ALBUM = Regex("^\\s*(?:saison|season|staffel|stagione|temporada|s)\\s*(\\d{1,3})\\s*$", RegexOption.IGNORE_CASE)

    /** Apps that play from a Plex server: Plex's own, and Rivulet (1.6.2). */
    private val PLEX_CLIENTS = listOf("plex", "rivulet")

    /**
     * Players of a local library (1.6.0, Zavie): what they play is known to their server, so
     * TMDB isn't asked (a search on a file's name may find another film). Plex set on the web
     * page still answers for Plex.
     */
    private val LOCAL_PLAYERS = listOf("plex", "rivulet", "jellyfin", "emby", "kodi", "coreelec", "movie theater", "zidoo", "eversolo")

    /** "Saison 2, ép. 5", "Season 2, Ep. 5": an Apple TV or CANAL+ episode, its series as the title (1.6.0, Zavie). */
    private val EPISODE_ARTIST =
        Regex(
            "^\\s*(?:saison|season|staffel|stagione|temporada)\\s*(\\d{1,3})\\s*[,·.-]?\\s*" +
                "(?:épisode|episode|episodio|folge|ép|ep|e)\\.?\\s*(\\d{1,4})\\s*$",
            RegexOption.IGNORE_CASE
        )

    private const val MIN_TITLE = 2

    /** An app playing from a Plex server, by its app or its entity id: Plex, Rivulet. */
    fun isPlexClient(app: String?, entityId: String): Boolean {
        val a = app?.lowercase().orEmpty()
        return PLEX_CLIENTS.any { it in a || it in entityId.lowercase() }
    }

    /** The season an album like "Saison 1" names, else null. */
    fun seasonOfAlbum(album: String?): Int? = album?.let(SEASON_ALBUM::find)?.groupValues?.get(1)?.toInt()

    /** A local library's player, by its app or its entity id: Plex, Kodi, a Zidoo… */
    fun isLocalPlayer(app: String?, entityId: String): Boolean {
        val a = app?.lowercase().orEmpty()
        val id = entityId.lowercase()
        return LOCAL_PLAYERS.any { it in a || it.replace(' ', '_') in id }
    }

    /** Season and episode from an artist like "Saison 2, ép. 5", else null. */
    fun episodeOf(artist: String?): Pair<Int, Int>? {
        val m = artist?.let(EPISODE_ARTIST::find) ?: return null
        return m.groupValues[1].toInt() to m.groupValues[2].toInt()
    }

    /**
     * The search for a player's attributes ([attr]: its string attributes), or null when there
     * is nothing to look up (no title, music, an empty name once cleaned).
     */
    /**
     * An album naming a season ("Saison 1"), or any album from a Plex player, which plays no music:
     * Kaamelott's seasons are "Livre I", "Livre II"… (1.6.2).
     */
    private fun albumIsSeason(album: String?, app: String): Boolean =
        album != null && (seasonOfAlbum(album) != null || PLEX_CLIENTS.any { it in app })

    /** The series' name: Home Assistant's, else the artist for HBO Max (the episode as the title) or under an album "Saison 1". */
    private fun seriesOf(attr: (String) -> String?, app: String, artist: String?, seasonAlbum: Boolean): String? =
        attr("media_series_title")?.takeIf { it.isNotBlank() }
            ?: artist?.takeIf { "hbo" in app || app == "max" || seasonAlbum }

    fun queryOf(attr: (String) -> String?): TmdbQuery? {
        val app = attr("app_name")?.lowercase().orEmpty()
        val type = attr("media_content_type")?.lowercase().orEmpty()
        // A song says its album: "music" alone isn't enough, an Apple TV added in Astrion says it
        // of everything it plays (a film on Prime Video included).
        val album = attr("media_album_name")?.takeIf { it.isNotBlank() }
        val seasonAlbum = albumIsSeason(album, app)
        val song = type in MUSIC_TYPES && album != null && !seasonAlbum
        if (song || MUSIC_APPS.any { it in app }) return null
        val artist = attr("media_artist")?.takeIf { it.isNotBlank() }
        val series = seriesOf(attr, app, artist, seasonAlbum)
        val raw = series ?: attr("media_title")?.takeIf { it.isNotBlank() } ?: return null
        val tv = series != null || type == "tvshow" || type == "episode" || attr("media_season") != null || episodeOf(artist) != null
        // A series: which episode, so each one is looked up (its own synopsis on Plex).
        return clean(raw, tv)?.let { q ->
            if (!q.tv) {
                q
            } else {
                q.copy(
                    episode = listOfNotNull(attr("media_title"), attr("media_season"), attr("media_episode")).joinToString("/").lowercase()
                )
            }
        }
    }

    /** [raw] as TMDB will find it: no file extension, separators, year, quality tags or studio. */
    fun clean(raw: String, tv: Boolean = false): TmdbQuery? {
        var t = raw.trim()
        val season = SEASON.find(t)?.takeIf { it.range.first > 0 }
        if (season != null) t = t.substring(0, season.range.first)
        val file = FILE_EXT.containsMatchIn(t) || (' ' !in t && ('.' in t || '_' in t))
        t = t.replace(FILE_EXT, "")
        if (file) t = t.replace('.', ' ').replace('_', ' ')
        var year = YEAR_PAREN.find(t)?.groupValues?.get(1)?.toInt()
        t = t.replace(YEAR_PAREN, " ")
        if (year == null && file) {
            YEAR.find(t)?.let { m ->
                if (m.range.first > 0) {
                    year = m.groupValues[1].toInt()
                    t = t.substring(0, m.range.first)
                }
            }
        }
        t = t.replace(QUALITY, "").replace(STUDIO, "").replace(SPACES, " ").trim(' ', '-', ':', '–', '—', '.')
        if (t.length < MIN_TITLE) return null
        return TmdbQuery(t, year, tv || season != null)
    }

    /** The search's path and query (after `https://api.themoviedb.org/3/`). */
    fun searchPath(q: TmdbQuery, language: String): String {
        val query = "query=${enc(q.title)}&language=${enc(language)}&include_adult=false"
        return when {
            q.tv -> "search/tv?$query" + (q.year?.let { "&first_air_date_year=$it" } ?: "")
            q.year != null -> "search/movie?$query&year=${q.year}"
            else -> "search/multi?$query"
        }
    }

    /** The best of TMDB's answer to [q]: one of its year when it gives one, else the first film or series. */
    fun best(body: String, q: TmdbQuery): TmdbInfo? {
        val o = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val results = (o["results"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }.filter { r ->
            val kind = r.str("media_type")
            kind == null || kind == "movie" || kind == "tv"
        }
        val pick = q.year?.let { y -> results.firstOrNull { yearOf(it) == y } } ?: results.firstOrNull() ?: return null
        val title = pick.str("title") ?: pick.str("name") ?: return null
        return TmdbInfo(
            title = title,
            overview = pick.str("overview"),
            year = yearOf(pick),
            rating = (pick["vote_average"] as? JsonPrimitive)?.doubleOrNull?.takeIf { it > 0.0 },
            posterUrl = pick.str("poster_path")?.let { POSTER + it },
            tv = pick.str("media_type") == "tv" || (pick.str("name") != null && pick.str("title") == null)
        )
    }

    private fun yearOf(r: JsonObject): Int? = (r.str("release_date") ?: r.str("first_air_date"))?.take(YEAR_DIGITS)?.toIntOrNull()

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8").replace("+", "%20")

    private const val POSTER = "https://image.tmdb.org/t/p/w500"
    private const val YEAR_DIGITS = 4
}
