package com.custom.astrion.zidoo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/** What a poster-wall entry is, from its `type`. */
enum class ZidooKind(val code: Int) {
    FILE(0),
    MOVIE(1),
    COLLECTION(2),
    SERIES(3),
    SEASON(4),
    EPISODE(5),
    OTHER(-1)
    ;

    /** Opens on a list of its own (a saga's films, a series' seasons, a season's episodes). */
    val isFolder: Boolean get() = this == COLLECTION || this == SERIES || this == SEASON

    companion object {
        fun of(code: Int?): ZidooKind = entries.firstOrNull { it.code == code } ?: OTHER
    }
}

/** One poster of the Zidoo's own library (its poster wall): a film, a saga, a series, a season or an episode. */
data class ZidooLibraryItem(
    val id: Long,
    val kind: ZidooKind,
    val name: String,
    val year: Int? = null,
    val rating: Double? = null,
    val is4k: Boolean = false,
    val isHdr: Boolean = false,
    val favorite: Boolean = false,
    val watched: Boolean = false,
    /** Where playback stopped, in ms; null when never started (the player says -1). */
    val positionMs: Long? = null,
    val durationMs: Long? = null,
    /** An episode's number in its season. */
    val episode: Int? = null,
    val overview: String? = null,
    /** The video file to play (a film's or an episode's), when the answer carries it. */
    val fileId: Long? = null
) {
    /** Started but not finished. */
    val inProgress: Boolean get() = !watched && (positionMs ?: 0) > 0

    /** How far it was watched, 0..1, for the poster's bar; null when never started. */
    val progress: Float?
        get() {
            val p = positionMs ?: return null
            val d = durationMs ?: return null
            return if (p > 0 && d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else null
        }
}

/** A list of the library and the title it goes with (a season's name over its episodes). */
data class ZidooLibraryList(val title: String?, val items: List<ZidooLibraryItem>, val overview: String? = null)

/**
 * The Zidoo's library, as its own phone app reads it (1.5.0): the lists of `getAggregations`
 * (everything, favourites, history, series, recently added), `searchAggregation`, a saga's or a
 * series' content (`getCollection`), a season's episodes (`getDetail`) and `PlayVideo`, which
 * plays the video file of a film or an episode. Pure, so it is unit-tested; tried on a Z9X by
 * a tester in November 2026.
 */
object ZidooLibrary {
    /** `getAggregations` lists (the player's own filters). */
    const val ALL = 0
    const val FAVORITES = 1
    const val HISTORY = 2
    const val SERIES = 4
    const val RECENT = 9

    private const val NOT_STARTED = 0L

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.double(key: String) = (this[key] as? JsonPrimitive)?.doubleOrNull

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull == true

    private fun JsonObject.obj(key: String) = this[key] as? JsonObject

    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    /** One entry of any of these answers; null for one without an id or a name. */
    fun item(o: JsonObject): ZidooLibraryItem? {
        val id = o.long("id")?.takeIf { it > 0 } ?: return null
        val name = o.str("name") ?: return null
        val kind = ZidooKind.of(o.long("type")?.toInt())
        val inner = o.obj("aggregation")
        // An episode's file is its only child (type 0); a film's comes from getCollection.
        val file = o.objects("aggregations").firstOrNull { ZidooKind.of(it.long("type")?.toInt()) == ZidooKind.FILE }
        return ZidooLibraryItem(
            id = id,
            kind = kind,
            name = name,
            year = o.long("year")?.toInt()?.takeIf { it > 0 },
            rating = o.double("voteAverage")?.takeIf { it > 0 },
            is4k = o.bool("is4k"),
            isHdr = o.bool("isHdr"),
            favorite = o.bool("favor"),
            watched = o.bool("watched"),
            positionMs = o.long("position")?.takeIf { it > NOT_STARTED },
            durationMs = o.long("duration")?.takeIf { it > 0 },
            episode = inner?.long("episodeNumber")?.toInt()?.takeIf { it > 0 },
            overview = inner?.str("overview"),
            fileId = file?.long("aggregationId")?.takeIf { it > 0 }
        )
    }

    /** `getAggregations`: one page of a list; null when the player didn't answer with one. */
    fun page(o: JsonObject?): List<ZidooLibraryItem>? = (o?.get("array") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.let(::item) }

    /** `searchAggregation`: films, sagas and series found, in the player's order. */
    fun search(o: JsonObject?): List<ZidooLibraryItem>? =
        (o?.get("all") as? JsonArray)?.mapNotNull { (it as? JsonObject)?.obj("aggregation")?.let(::item) }

    /** `getCollection`: a saga's films or a series' seasons (the video files of a film left out). */
    fun collection(o: JsonObject?): ZidooLibraryList? {
        val data = o?.get("data") as? JsonArray ?: return null
        val items = data.mapNotNull { (it as? JsonObject)?.let(::item) }.filter { it.kind != ZidooKind.FILE }
        return ZidooLibraryList(o.str("name"), items)
    }

    /** `getCollection` of a film: the video file to play (its first one). */
    fun fileOf(o: JsonObject?): Long? = (o?.get("data") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.firstOrNull { ZidooKind.of(it.long("type")?.toInt()) == ZidooKind.FILE }
        ?.long("aggregationId")
        ?.takeIf { it > 0 }

    /**
     * When a film was last watched (ms), from its `getCollection`: its video file's
     * `videoinfo.lastWatchTime`. Null when never. The lists come in alphabetical order with no
     * date, so "In progress" is put in order with this (1.5.0), as the Zidoo's own app shows it.
     */
    fun lastWatchedFilm(o: JsonObject?): Long? = (o?.get("data") as? JsonArray)
        ?.mapNotNull { it as? JsonObject }
        ?.filter { ZidooKind.of(it.long("type")?.toInt()) == ZidooKind.FILE }
        ?.mapNotNull { it.obj("videoinfo")?.long("lastWatchTime")?.takeIf { t -> t > 0 } }
        ?.maxOrNull()

    /** The same for a season, from its `getDetail`: its latest-watched episode's file. */
    fun lastWatchedSeason(o: JsonObject?): Long? = o?.obj("aggregation")
        ?.objects("aggregations")
        ?.flatMap { ep -> ep.objects("aggregations") }
        ?.mapNotNull { file -> file.obj("aggregation")?.long("lastWatchTime")?.takeIf { it > 0 } }
        ?.maxOrNull()

    /** `getDetail` of a season: its episodes in order, with the season's name and synopsis. */
    fun season(o: JsonObject?): ZidooLibraryList? {
        val season = o?.obj("aggregation") ?: return null
        val episodes = season.objects("aggregations").mapNotNull(::item).sortedBy { it.episode ?: Int.MAX_VALUE }
        return ZidooLibraryList(season.str("name"), episodes, season.obj("aggregation")?.str("overview"))
    }
}
