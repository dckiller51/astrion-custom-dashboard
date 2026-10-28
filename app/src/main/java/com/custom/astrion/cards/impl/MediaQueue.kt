package com.custom.astrion.cards.impl

import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * The media player card's queue (1.4.0). What a player shares depends on its integration:
 *  - **Lyrion Music Server** (Squeezebox): the whole queue from the current track on, through
 *    `squeezebox.call_query` `status` (its answer lands in the entity's `query_result`
 *    attribute); a track can be played (`playlist index`) or removed (`playlist delete`).
 *  - **Music Assistant**: the current and next track, and how many there are
 *    (`music_assistant.get_queue`); Home Assistant offers nothing to edit it.
 *  - any player with `CLEAR_PLAYLIST`: **Clear** (`media_player.clear_playlist`).
 */
internal object MediaQueue {
    /** Home Assistant's MediaPlayerEntityFeature.CLEAR_PLAYLIST. */
    const val CLEAR_PLAYLIST = 8192

    /** Tracks asked from Lyrion at once (the current one and the next ones). */
    const val LYRION_PAGE = 100

    enum class Kind { LYRION, MUSIC_ASSISTANT, OTHER }

    /** One track of the queue; [index] is its place in the whole queue (0 = first). */
    data class Track(
        val index: Int,
        val title: String,
        val artist: String?,
        val album: String?,
        val duration: Double?,
        val image: String?,
        val current: Boolean
    )

    /**
     * The queue as the player shares it: [tracks] (from the current one on), [total] tracks in
     * all, the current one's [currentIndex]; [editable]: tracks can be played or removed.
     */
    data class Queue(val tracks: List<Track>, val total: Int, val currentIndex: Int?, val editable: Boolean)

    sealed class Answer {
        data class Loaded(val queue: Queue) : Answer()

        /** The player shares nothing but maybe Clear. */
        data object Unsupported : Answer()

        data object Failed : Answer()
    }

    /** Lyrion's entities always carry `query_result`; Music Assistant's `mass_player_type`. */
    fun kind(e: EntityState?): Kind = when {
        e == null -> Kind.OTHER
        e.attributes.containsKey("query_result") -> Kind.LYRION
        e.attributes.containsKey("mass_player_type") -> Kind.MUSIC_ASSISTANT
        else -> Kind.OTHER
    }

    fun canClear(e: EntityState?): Boolean = e?.supports(CLEAR_PLAYLIST) == true

    /** The card shows a Queue button when the player shares its queue or can clear it. */
    fun available(e: EntityState?): Boolean = e != null && !e.isUnavailable && (kind(e) != Kind.OTHER || canClear(e))

    private val QUERY_TIMEOUT = 15.seconds
    private val ATTRIBUTE_WAIT = 3.seconds

    suspend fun load(client: HaClient, player: String): Answer = when (kind(client.entities.value[player])) {
        Kind.LYRION -> loadLyrion(client, player)
        Kind.MUSIC_ASSISTANT -> loadMusicAssistant(client, player)
        Kind.OTHER -> Answer.Unsupported
    }

    private suspend fun loadLyrion(client: HaClient, player: String): Answer {
        val before = client.entities.value[player]?.attr("query_result")
        val reply =
            client.request(
                "call_service",
                serviceFields("squeezebox", "call_query", player) {
                    put("command", "status")
                    putJsonArray("parameters") {
                        add(JsonPrimitive("-"))
                        add(JsonPrimitive(LYRION_PAGE.toString()))
                        add(JsonPrimitive("tags:adlK"))
                    }
                },
                QUERY_TIMEOUT
            )
        if (reply == null || !reply.succeeded()) return Answer.Failed
        // The answer comes as the entity's new `query_result`; an answer identical to the last one
        // changes nothing, so after a short wait the attribute as it stands is the answer.
        val changed =
            withTimeoutOrNull(ATTRIBUTE_WAIT) {
                client.entities.first { it[player]?.attr("query_result").let { r -> r != before && r is JsonObject } }
            }
        val result = (changed ?: client.entities.value)[player]?.attr("query_result") as? JsonObject ?: return Answer.Failed
        return Answer.Loaded(parseLyrion(result))
    }

    private suspend fun loadMusicAssistant(client: HaClient, player: String): Answer {
        val reply =
            client.request(
                "call_service",
                serviceFields("music_assistant", "get_queue", player, returnResponse = true) {},
                QUERY_TIMEOUT
            ) ?: return Answer.Failed
        if (!reply.succeeded()) return Answer.Unsupported
        val response = (reply["result"] as? JsonObject)?.get("response") as? JsonObject ?: return Answer.Failed
        val queue = response[player] as? JsonObject ?: return Answer.Failed
        return Answer.Loaded(parseMusicAssistant(queue))
    }

    /** Lyrion's `status` answer: `playlist_loop` (each with its `playlist index`), `playlist_tracks`, `playlist_cur_index`. */
    fun parseLyrion(result: JsonObject): Queue {
        val current = result.int("playlist_cur_index")
        val tracks =
            (result["playlist_loop"] as? JsonArray).orEmpty().mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val index = o.int("playlist index") ?: return@mapNotNull null
                Track(
                    index = index,
                    title = o.str("title") ?: o.str("remote_title") ?: "#${index + 1}",
                    artist = o.str("artist"),
                    album = o.str("album"),
                    duration = o.double("duration"),
                    image = o.str("artwork_url")?.takeIf { it.startsWith("http") },
                    current = index == current
                )
            }
        return Queue(tracks, result.int("playlist_tracks") ?: tracks.size, current, editable = true)
    }

    /** Music Assistant's `get_queue` answer for one player: `current_item`, `next_item`, `items` (how many), `current_index`. */
    fun parseMusicAssistant(queue: JsonObject): Queue {
        val current = queue.int("current_index")
        val tracks =
            listOfNotNull(
                (queue["current_item"] as? JsonObject)?.toMaTrack(current ?: 0, true),
                (queue["next_item"] as? JsonObject)?.toMaTrack((current ?: 0) + 1, false)
            )
        return Queue(tracks, queue.int("items") ?: tracks.size, current, editable = false)
    }

    private fun JsonObject.toMaTrack(index: Int, current: Boolean): Track {
        val media = this["media_item"] as? JsonObject
        val artists = (media?.get("artists") as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.str("name") }
        return Track(
            index = index,
            title = str("stream_title") ?: media?.str("name") ?: str("name") ?: "#${index + 1}",
            artist = artists.joinToString(", ").ifEmpty { null },
            album = (media?.get("album") as? JsonObject)?.str("name"),
            duration = double("duration"),
            image = media?.str("image"),
            current = current
        )
    }

    /** Plays the queue's track [index] (Lyrion). */
    fun playIndex(client: HaClient, player: String, index: Int) = lyrionMethod(client, player, "index", index)

    /** Removes the queue's track [index] (Lyrion). */
    fun remove(client: HaClient, player: String, index: Int) = lyrionMethod(client, player, "delete", index)

    fun clear(client: HaClient, player: String) {
        client.callService(ServiceCall("media_player", "clear_playlist", player))
    }

    fun next(client: HaClient, player: String) {
        client.callService(ServiceCall("media_player", "media_next_track", player))
    }

    private fun lyrionMethod(client: HaClient, player: String, action: String, index: Int) {
        val data: Map<String, JsonElement> =
            mapOf(
                "command" to JsonPrimitive("playlist"),
                "parameters" to JsonArray(listOf(JsonPrimitive(action), JsonPrimitive(index.toString())))
            )
        client.callService(ServiceCall("squeezebox", "call_method", player, data))
    }

    private fun serviceFields(
        domain: String,
        service: String,
        player: String,
        returnResponse: Boolean = false,
        data: JsonObjectBuilder.() -> Unit
    ): JsonObject = buildJsonObject {
        put("domain", domain)
        put("service", service)
        putJsonObject("service_data", data)
        putJsonObject("target") { put("entity_id", player) }
        if (returnResponse) put("return_response", true)
    }

    private fun JsonObject.succeeded() = (this["success"] as? JsonPrimitive)?.content == "true"

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }

    private fun JsonObject.double(key: String): Double? = str(key)?.toDoubleOrNull()

    private fun JsonObject.int(key: String): Int? = double(key)?.toInt()
}
