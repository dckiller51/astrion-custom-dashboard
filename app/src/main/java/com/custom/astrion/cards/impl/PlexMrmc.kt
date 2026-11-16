package com.custom.astrion.cards.impl

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Playing a Plex film with MrMC on an Apple TV (1.5.9, dckiller51's idea): the new Plex app for
 * Apple TV opens on its home screen whatever its links say, and no longer lets a server drive it.
 * MrMC (Kodi for tvOS) plays the film's file straight from the Plex server when asked through its
 * JSON-RPC (*Allow remote control via HTTP*). Pure, so it is unit-tested; MrmcPlayer does the calls.
 */
internal object PlexMrmc {
    private val json = Json { ignoreUnknownKeys = true }

    /** The first file of the item in a `/library/metadata/<id>` answer, or null (a series, a season: nothing to play). */
    fun partKey(body: String): String? {
        val item = (container(body)?.get("Metadata") as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val media = (item["Media"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        val part = (media["Part"] as? JsonArray)?.firstOrNull() as? JsonObject ?: return null
        return part.str("key")?.takeIf { it.startsWith("/") }
    }

    /** A token for MrMC alone, from `/security/token?type=delegation`: it expires, unlike the account's. */
    fun delegatedToken(body: String): String? = container(body)?.str("token")

    /** The address MrMC reads the film from. */
    fun fileUrl(host: String, partKey: String, token: String): String =
        host.trimEnd('/') + partKey + "?X-Plex-Token=" + URLEncoder.encode(token, "UTF-8")

    /** The item's own path, from what the card's detail gives ("/library/metadata/12", "12", ".../children"). */
    fun itemPath(ratingKey: String): String =
        ratingKey.removeSuffix("/children").let { if (it.startsWith("/")) it else "/library/metadata/$it" }

    val PING: String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "JSONRPC.Ping")
        put("id", 1)
    }.toString()

    fun open(file: String): String = buildJsonObject {
        put("jsonrpc", "2.0")
        put("method", "Player.Open")
        putJsonObject("params") { putJsonObject("item") { put("file", file) } }
        put("id", 1)
    }.toString()

    fun isPong(body: String): Boolean = result(body) == "pong"

    fun isOk(body: String): Boolean = result(body) == "OK"

    private fun result(body: String): String? =
        (runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("result") as? JsonPrimitive)?.contentOrNull

    private fun container(body: String): JsonObject? =
        runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()?.get("MediaContainer") as? JsonObject

    private fun JsonObject.str(key: String) = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
}

/**
 * Playing a Plex item with Rivulet on an Apple TV (1.5.9): a Plex client for tvOS whose own
 * `rivulet://` links start an item where it was left (`play`) or open its page (`detail`). The
 * path form, as the Apple TV's Companion channel drops a link's query. A series or a season
 * (`…/children`) opens its page: there is no one file to play.
 */
internal object PlexRivulet {
    private val ID = Regex("^[A-Za-z0-9]+$")

    fun link(ratingKey: String): String? {
        val action = if (ratingKey.endsWith("/children")) "detail" else "play"
        val id = PlexMrmc.itemPath(ratingKey).substringAfterLast('/')
        return if (ID.matches(id)) "rivulet://$action/$id" else null
    }
}
