package com.custom.astrion.zidoo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

data class MusicAlbum(val id: Long, val name: String, val artist: String? = null, val year: String? = null)

data class MusicArtist(val id: Long, val name: String)

data class MusicPlaylist(val id: Long, val name: String)

data class MusicTrack(
    val id: Long,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    val number: Int? = null,
    val disc: Int? = null,
    val durationMs: Long? = null,
    /** The player's track type, for its cover (`getImage`'s `music_type`). */
    val type: Int = 0
)

/** What a list of tracks plays from: an album, an artist or a playlist (the player's `playMusic` types). */
enum class MusicSource(val code: Int) {
    ARTIST(3),
    ALBUM(4),
    PLAYLIST(5)
}

/**
 * The music library of an Eversolo (1.5.0), as its own app reads it: albums, artists and
 * playlists (`MusicControl/v2/getAlbums`, `getArtists`, `getSongLists`), their tracks
 * (`getAlbumMusics`, `getArtistMusics`, `getSongListMusics`), a search (`searchAlbum`,
 * `searchArtist`) and `playMusic`, which plays an album, an artist or a playlist from one of its
 * tracks. Pure, so it is unit-tested; tried on a DMP-A6 by a tester in November 2026.
 */
object ZidooMusicLibrary {
    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() }

    private fun JsonObject.objects(key: String) = (this[key] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    private fun id(o: JsonObject) = o.long("id")?.takeIf { it > 0 }

    /** `getAlbums`, `searchAlbum`: null when the player didn't answer with a list. */
    fun albums(o: JsonObject?): List<MusicAlbum>? {
        if (o?.get("array") !is JsonArray) return null
        return o.objects("array").mapNotNull { a ->
            val id = id(a) ?: return@mapNotNull null
            MusicAlbum(id, a.str("name") ?: return@mapNotNull null, a.str("artist"), a.str("pubDate"))
        }
    }

    /** `getArtists`, `searchArtist`. */
    fun artists(o: JsonObject?): List<MusicArtist>? {
        if (o?.get("array") !is JsonArray) return null
        return o.objects("array").mapNotNull { a -> MusicArtist(id(a) ?: return@mapNotNull null, a.str("name") ?: return@mapNotNull null) }
    }

    /** `getSongLists`: a bare list of `{count, obj: {id, name…}}`. */
    fun playlists(answer: JsonElement?): List<MusicPlaylist>? {
        val list = answer as? JsonArray ?: (answer as? JsonObject)?.get("array") as? JsonArray ?: return null
        return list.mapNotNull { el ->
            val p = (el as? JsonObject)?.let { it["obj"] as? JsonObject ?: it } ?: return@mapNotNull null
            MusicPlaylist(id(p) ?: return@mapNotNull null, p.str("name") ?: return@mapNotNull null)
        }
    }

    /** `getAlbumMusics`, `getArtistMusics`, `getSongListMusics`: the tracks, an album's in disc and track order. */
    fun tracks(o: JsonObject?, source: MusicSource): List<MusicTrack>? {
        if (o?.get("array") !is JsonArray) return null
        val tracks =
            o.objects("array").mapNotNull { t ->
                MusicTrack(
                    id = id(t) ?: return@mapNotNull null,
                    title = t.str("title") ?: t.str("fileName") ?: return@mapNotNull null,
                    artist = t.str("artist"),
                    album = t.str("album"),
                    number = t.long("number")?.toInt()?.takeIf { it > 0 },
                    disc = t.long("diskNumber")?.toInt()?.takeIf { it > 0 },
                    durationMs = t.long("duration")?.takeIf { it > 0 },
                    type = t.long("type")?.toInt() ?: 0
                )
            }
        return if (source == MusicSource.ALBUM) tracks.sortedWith(compareBy({ it.disc ?: 1 }, { it.number ?: Int.MAX_VALUE })) else tracks
    }

    /** The `playMusic` query that plays [source] [id] from [track], the [index]-th (0-based) of its list. */
    fun playQuery(source: MusicSource, id: Long, track: MusicTrack, index: Int): String =
        "type=${source.code}&id=$id&musicId=${track.id}&music_type=0&trackIndex=${index + 1}&sort=0"
}
