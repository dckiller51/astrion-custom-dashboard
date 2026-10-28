package com.custom.astrion.cards.impl

import com.custom.astrion.ha.HaClient
import kotlin.time.Duration.Companion.seconds
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Home Assistant's own media search (1.3.9), for the media player card's "Search music":
 * `media_player/search_media` asks the player itself, so it works with every integration that
 * offers it — Lyrion Music Server (Squeezebox), Music Assistant… — with nothing to set up.
 * A result plays with the standard `media_player.play_media`, now or at the end of the queue.
 */
internal object MediaSearch {
    /** One result, as Home Assistant's media browser describes it. */
    data class Item(
        val contentId: String,
        val contentType: String,
        val title: String,
        val mediaClass: String,
        val thumbnail: String?,
        val canPlay: Boolean,
        val canExpand: Boolean
    )

    /** The results of one kind (`media_class`: "artist", "album", "track", "playlist"…). */
    data class Group(val mediaClass: String, val items: List<Item>)

    /** The search's filters, in the dialog's order (none = every kind). */
    val FILTERS = listOf("artist", "album", "track", "playlist")

    /** The search answer: its groups, or why there are none. */
    sealed class Answer {
        data class Results(val groups: List<Group>) : Answer()

        /** The player can't search (Home Assistant's `not_supported`). */
        data object Unsupported : Answer()

        /** Home Assistant didn't answer in time, or the player failed. */
        data object Failed : Answer()
    }

    private val SEARCH_TIMEOUT = 25.seconds

    /** Searches [player] for [query]; [filter]: one kind only, null for all. */
    suspend fun search(client: HaClient, player: String, query: String, filter: String?): Answer {
        val fields =
            buildJsonObject {
                put("entity_id", player)
                put("search_query", query)
                filter?.let { putJsonArray("media_filter_classes") { add(JsonPrimitive(it)) } }
            }
        val reply = client.request("media_player/search_media", fields, SEARCH_TIMEOUT) ?: return Answer.Failed
        if ((reply["success"] as? JsonPrimitive)?.content != "true") {
            val code = ((reply["error"] as? JsonObject)?.get("code") as? JsonPrimitive)?.content
            return if (code == "not_supported") Answer.Unsupported else Answer.Failed
        }
        return Answer.Results(parse(reply["result"] as? JsonObject ?: JsonObject(emptyMap())))
    }

    /** `{"result": [items]}` → groups of one kind each: [FILTERS] order first, then the others as they come. */
    fun parse(result: JsonObject): List<Group> {
        val items = (result["result"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonObject)?.toItem() }
        val byClass = items.groupBy { it.mediaClass }
        val order = FILTERS.filter { it in byClass } + byClass.keys.filter { it !in FILTERS }
        return order.map { Group(it, byClass.getValue(it)) }
    }

    private fun JsonObject.toItem(): Item? {
        val id = str("media_content_id") ?: return null
        return Item(
            contentId = id,
            contentType = str("media_content_type").orEmpty(),
            title = str("title") ?: id,
            mediaClass = str("media_class") ?: "music",
            thumbnail = str("thumbnail"),
            canPlay = (this["can_play"] as? JsonPrimitive)?.content == "true",
            canExpand = (this["can_expand"] as? JsonPrimitive)?.content == "true"
        )
    }

    /** Plays [item] on [player]: [enqueue] "play" (now, keeping the queue) or "add" (end of the queue). */
    fun play(client: HaClient, player: String, item: Item, enqueue: String) {
        client.playMedia(player, item.contentId, item.contentType, enqueue)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() && it != "null" }
}
