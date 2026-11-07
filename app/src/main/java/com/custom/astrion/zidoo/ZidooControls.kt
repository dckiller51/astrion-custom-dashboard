package com.custom.astrion.zidoo

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** A subtitle or audio track of what a Zidoo's video player plays (1.5.1). */
data class ZidooTrack(val index: Int, val title: String)

/**
 * One track of the play queue (1.5.1, `MusicControl/v2/getPlayQueue`): the Eversolo's own player
 * (type 1, local files) or an app's (type 3: Eversolo's own Apple Music, `sourceType` 31, or
 * Apple's, `sourceType` 48).
 */
data class ZidooQueueItem(
    val id: Long,
    val type: Int,
    val title: String,
    val artist: String? = null,
    val album: String? = null,
    /** Milliseconds; null when the player doesn't say (Apple's Apple Music says 0). */
    val durationMs: Long? = null,
    /** A cover the player gives as a URL (an app's); null for a local file (asked by id) or one the remote can't read. */
    val artUrl: String? = null,
    val sourceType: Int? = null
) {
    /**
     * A tap plays it (tested on an Eversolo DMP-A6): its own player's queue and Eversolo's own
     * Apple Music. Apple's Apple Music answers but doesn't move, so its queue is only shown.
     */
    val playable: Boolean get() = sourceType != APPLE_OFFICIAL

    /** A local file's cover is asked by its id, like the library's. */
    val local: Boolean get() = type == LOCAL

    private companion object {
        const val APPLE_OFFICIAL = 48
        const val LOCAL = 1
    }
}

/**
 * An Eversolo output (1.5.1, `ZidooMusicControl/v2/getInputAndOutputList`): its name, the tag
 * that selects it, whether it can be chosen now.
 */
data class ZidooOutput(val name: String, val tag: String, val enabled: Boolean = true)

/** The outputs and which one is in use (its index in [outputs]; -1 when not said). */
data class ZidooOutputs(val outputs: List<ZidooOutput>, val current: Int = -1)

/**
 * The Zidoo / Eversolo card's own calls (1.5.1), beside what the media player entity says: the
 * video player's subtitle and audio tracks, chapters, the play queue and an Eversolo's outputs.
 * Paths from Zidoo's control API (`ZidooVideoPlay/…`, `ZidooMusicControl/v2/…`). Pure, so tested.
 */
object ZidooControls {
    const val SUBTITLES_PATH = "ZidooVideoPlay/getSubtitleList"

    fun setSubtitlePath(index: Int) = "ZidooVideoPlay/setSubtitle?index=$index"

    const val AUDIO_TRACKS_PATH = "ZidooVideoPlay/getAudioList"

    fun setAudioPath(index: Int) = "ZidooVideoPlay/setAudio?index=$index"

    /** Next or previous chapter of what the video player plays (`changevideo`, type 1: chapter). */
    fun chapterPath(next: Boolean) = "ZidooVideoPlay/changevideo?type=1&tag=${if (next) 1 else 0}"

    const val QUEUE_PATH = "MusicControl/v2/getPlayQueue"

    /** Plays the [index]-th track of the queue (0 first): the call Eversolo's own app makes. */
    fun playQueuePath(item: ZidooQueueItem, index: Int) =
        "ZidooMusicControl/v2/playMusic?type=0&id=-1&musicId=${item.id}&musicType=${item.type}" +
            "&trackIndex=$index&seekTime=-1&sort=0&isSkipPlay=0&playMode=-1"

    const val OUTPUTS_PATH = "ZidooMusicControl/v2/getInputAndOutputList"

    fun setOutputPath(tag: String) = "ZidooMusicControl/v2/setOutInputList?tag=${ZidooParse.query(tag)}"

    /**
     * `getSubtitleList` / `getAudioList`: `{index, title}` each. The audio list comes under
     * `subtitles` too on some firmwares, so every list of the answer is looked at.
     */
    fun tracks(o: JsonObject?): List<ZidooTrack> {
        if (o == null || !ZidooParse.ok(o)) return emptyList()
        val list = LIST_KEYS.firstNotNullOfOrNull { o[it] as? JsonArray } ?: return emptyList()
        return list.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val index = t.int("index") ?: return@mapNotNull null
            ZidooTrack(index, t.str("title") ?: t.str("name") ?: "#$index")
        }
    }

    /** `getPlayStatus`'s current subtitle or audio track (`subtitle` / `audio`: `{index, information}`). */
    fun currentTrack(o: JsonObject?, key: String): Pair<Int?, String?>? {
        val t = o?.get(key) as? JsonObject ?: return null
        return t.int("index") to t.str("information")
    }

    /** `getPlayQueue`: its `array` of tracks, in play order. */
    fun queue(o: JsonElement?): List<ZidooQueueItem> {
        val list = ((o as? JsonObject)?.get("array") ?: o) as? JsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val t = el as? JsonObject ?: return@mapNotNull null
            val id = t.long("id") ?: return@mapNotNull null
            ZidooQueueItem(
                id = id,
                type = t.int("type") ?: 0,
                title = t.str("title") ?: t.str("fileName") ?: return@mapNotNull null,
                artist = t.str("artist"),
                album = t.str("album"),
                durationMs = t.long("duration")?.takeIf { it > 0 },
                artUrl = t.str("albumArt")?.takeIf { it.startsWith("http://") || it.startsWith("https://") },
                sourceType = t.int("sourceType")
            )
        }
    }

    /** The queue's track that plays now: same title and artist as the player says (the queue doesn't mark it). */
    fun currentIndex(queue: List<ZidooQueueItem>, title: String?, artist: String?): Int {
        if (title.isNullOrBlank()) return -1
        val sameTitle = queue.indices.filter { queue[it].title.equals(title, ignoreCase = true) }
        return sameTitle.firstOrNull { artist == null || queue[it].artist.equals(artist, ignoreCase = true) }
            ?: sameTitle.firstOrNull() ?: -1
    }

    /** `getInputAndOutputList`: its `outputData` (`{name, tag, enable}`) and `outputIndex`. */
    fun outputs(o: JsonObject?): ZidooOutputs {
        if (o == null || !ZidooParse.ok(o)) return ZidooOutputs(emptyList())
        val list =
            (o["outputData"] as? JsonArray).orEmpty().mapNotNull { el ->
                val t = el as? JsonObject ?: return@mapNotNull null
                val tag = t.str("tag") ?: return@mapNotNull null
                ZidooOutput(t.str("name") ?: tag, tag, t.bool("enable") != false)
            }
        return ZidooOutputs(list, o.int("outputIndex")?.takeIf { it in list.indices } ?: -1)
    }

    private val LIST_KEYS = listOf("subtitles", "audios", "audio", "list", "data")

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.int(key: String) =
        (this[key] as? JsonPrimitive)?.let { it.intOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toInt() }

    private fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.let {
        it.longOrNull ?: it.contentOrNull?.toDoubleOrNull()?.toLong()
    }

    private fun JsonObject.bool(key: String) = (this[key] as? JsonPrimitive)?.booleanOrNull
}
