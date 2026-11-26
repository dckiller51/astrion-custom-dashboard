package com.custom.astrion.playnite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A game's sheet, as the extension (0.1.2+) gives it from GET /game/<id>. */
data class PlayniteDetails(
    val id: String,
    val name: String,
    val description: String = "",
    val genres: List<String> = emptyList(),
    val developers: List<String> = emptyList(),
    val publishers: List<String> = emptyList(),
    val platforms: List<String> = emptyList(),
    val year: Int = 0,
    val critic: Int = 0,
    val community: Int = 0,
    val playtime: Long = 0,
    val lastPlayed: Long = 0,
    val background: Boolean = false
)

/**
 * A game's sheet for the cover view (1.6.3): read from the extension, kept on the remote, and
 * answered to the cards' `astrion/source_details` request (entity_id, source) — the sheet opened
 * by a tap on a cover, with its Launch button. Pure, so it is unit-tested.
 */
object PlayniteSheet {
    /** The request a card sends for a source's sheet: `entity_id`, `source` (its name). */
    const val REQUEST = "astrion/source_details"

    private val json = Json { ignoreUnknownKeys = true }

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    private fun JsonObject.list(k: String) =
        (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.takeIf { s -> s.isNotBlank() } }.orEmpty()

    /** GET /game/<id> (or the copy kept on the remote); null when it isn't one. */
    fun details(text: String): PlayniteDetails? {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return PlayniteDetails(
            id = id,
            name = o.str("name").orEmpty(),
            description = o.str("description").orEmpty(),
            genres = o.list("genres"),
            developers = o.list("developers"),
            publishers = o.list("publishers"),
            platforms = o.list("platforms"),
            year = o.num("year").toInt(),
            critic = o.num("critic").toInt(),
            community = o.num("community").toInt(),
            playtime = o.num("playtime"),
            lastPlayed = o.num("lastPlayed"),
            background = (o["background"] as? JsonPrimitive)?.booleanOrNull == true
        )
    }

    private fun strings(list: List<String>) = JsonArray(list.map { JsonPrimitive(it) })

    /** What is kept on the remote: the same shape the extension gives. */
    fun detailsJson(d: PlayniteDetails): String = JsonObject(
        mapOf(
            "id" to JsonPrimitive(d.id),
            "name" to JsonPrimitive(d.name),
            "description" to JsonPrimitive(d.description),
            "genres" to strings(d.genres),
            "developers" to strings(d.developers),
            "publishers" to strings(d.publishers),
            "platforms" to strings(d.platforms),
            "year" to JsonPrimitive(d.year),
            "critic" to JsonPrimitive(d.critic),
            "community" to JsonPrimitive(d.community),
            "playtime" to JsonPrimitive(d.playtime),
            "lastPlayed" to JsonPrimitive(d.lastPlayed),
            "background" to JsonPrimitive(d.background)
        )
    ).toString()

    fun backgroundUrl(spec: PlayniteSpec, id: String) = "http://${spec.host}:${spec.port}/background/$id"

    /**
     * The request's answer, shaped like Home Assistant's (`success`, `result`): the sheet, from
     * [details] when known, else what the games list says ([game]). [cover] and [background] are
     * pictures the card loads; [reachable] false: the button wakes the PC first.
     */
    fun answer(
        game: PlayniteGame,
        details: PlayniteDetails?,
        cover: String?,
        background: String?,
        reachable: Boolean,
        running: Boolean
    ): JsonObject {
        val d = details ?: PlayniteDetails(game.id, game.name, playtime = game.playtime, lastPlayed = game.lastPlayed)
        val result =
            linkedMapOf<String, JsonElement>(
                "title" to JsonPrimitive(game.name),
                "description" to JsonPrimitive(d.description),
                "genres" to strings(d.genres),
                // The studio: its developers, else its publishers.
                "studios" to strings(d.developers.ifEmpty { d.publishers }),
                "platforms" to strings(d.platforms),
                "year" to JsonPrimitive(d.year),
                "critic" to JsonPrimitive(d.critic),
                "community" to JsonPrimitive(d.community),
                // The games list is read more often: its play time is the fresher.
                "playtime" to JsonPrimitive(maxOf(game.playtime, d.playtime)),
                "last_played" to JsonPrimitive(maxOf(game.lastPlayed, d.lastPlayed)),
                "reachable" to JsonPrimitive(reachable),
                "running" to JsonPrimitive(running)
            )
        cover?.let { result["cover"] = JsonPrimitive(it) }
        background?.let { result["background"] = JsonPrimitive(it) }
        return JsonObject(mapOf("success" to JsonPrimitive(true), "result" to JsonObject(result)))
    }

    fun failure(): JsonObject = JsonObject(mapOf("success" to JsonPrimitive(false)))
}
