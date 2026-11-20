package com.custom.astrion.denon

import java.net.URLDecoder
import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * HEOS, the streaming built into recent Denon / Marantz receivers (1.5.4), over its CLI: a TCP
 * connection on port **1255**, one command per line (`heos://player/get_players`), JSON answers,
 * and change events pushed by the receiver once registered. What the HEOS app and Home Assistant's
 * HEOS integration use. Commands and answers as in Denon's "HEOS CLI Protocol Specification".
 * Pure, so tested.
 */
object HeosProtocol {
    const val PORT = 1255

    /** Favorites, the HEOS source id for the account's presets. */
    private const val FAVORITES_SID = 1028

    fun command(path: String, vararg params: Pair<String, Any>): String {
        val query = params.joinToString("&") { (k, v) -> "$k=" + URLEncoder.encode(v.toString(), "UTF-8").replace("+", "%20") }
        return "heos://$path" + (if (query.isEmpty()) "" else "?$query") + "\r\n"
    }

    fun registerEvents() = command("system/register_for_change_events", "enable" to "on")

    fun players() = command("player/get_players")

    fun playState(pid: Long) = command("player/get_play_state", "pid" to pid)

    fun nowPlaying(pid: Long) = command("player/get_now_playing_media", "pid" to pid)

    fun playMode(pid: Long) = command("player/get_play_mode", "pid" to pid)

    fun favorites() = command("browse/browse", "sid" to FAVORITES_SID)

    fun setPlayState(pid: Long, state: String) = command("player/set_play_state", "pid" to pid, "state" to state)

    fun next(pid: Long) = command("player/play_next", "pid" to pid)

    fun previous(pid: Long) = command("player/play_previous", "pid" to pid)

    /** [preset]: the favorite's place in the list, from 1. */
    fun playPreset(pid: Long, preset: Int) = command("browse/play_preset", "pid" to pid, "preset" to preset)

    fun setPlayMode(pid: Long, repeat: String, shuffle: Boolean) =
        command("player/set_play_mode", "pid" to pid, "repeat" to repeat, "shuffle" to if (shuffle) "on" else "off")

    // ---- answers and events -------------------------------------------------------------------------

    private val json = Json { ignoreUnknownKeys = true }

    /** One line from the receiver: its command (`player/get_play_state`, `event/player_state_changed`…). */
    data class Message(val command: String, val success: Boolean, val params: Map<String, String>, val payload: Any?) {
        /** "command under process": the real answer follows. */
        val pending: Boolean get() = params.containsKey("command under process")
    }

    fun parse(line: String): Message? {
        val o = runCatching { json.parseToJsonElement(line.trim()) as? JsonObject }.getOrNull() ?: return null
        val heos = o["heos"] as? JsonObject ?: return null
        val command = heos.str("command") ?: return null
        return Message(command, heos.str("result") != "fail", params(heos.str("message").orEmpty()), o["payload"])
    }

    /** `pid=1&state=play` → map (URL-decoded); a word without `=` maps to "". */
    fun params(message: String): Map<String, String> = message.split('&').filter { it.isNotBlank() }.associate { part ->
        val k = part.substringBefore('=')
        val v = if ('=' in part) part.substringAfter('=') else ""
        decode(k) to decode(v)
    }

    private fun decode(s: String) = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrDefault(s)

    /** A HEOS player (the receiver is one). */
    data class Player(val pid: Long, val name: String, val ip: String?, val model: String?)

    fun players(m: Message): List<Player> = (m.payload as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        Player(
            pid = (o["pid"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null,
            name = o.str("name") ?: "HEOS",
            ip = o.str("ip"),
            model = o.str("model")
        )
    }

    /** The receiver's player among [players]: the one at [host], else the only one. */
    fun playerFor(players: List<Player>, host: String): Player? = players.firstOrNull { it.ip == host } ?: players.singleOrNull()

    /** What plays. [station]: a radio's name, when it's one. */
    data class NowPlaying(
        val title: String?,
        val artist: String?,
        val album: String?,
        val image: String?,
        val station: String?,
        val type: String?
    )

    fun nowPlaying(m: Message): NowPlaying? {
        val o = m.payload as? JsonObject ?: return null
        return NowPlaying(
            title = o.str("song"),
            artist = o.str("artist"),
            album = o.str("album"),
            image = o.str("image_url"),
            station = o.str("station"),
            type = o.str("type")
        )
    }

    /** One HEOS favorite. */
    data class Favorite(val name: String, val image: String?)

    fun favorites(m: Message): List<Favorite> = (m.payload as? JsonArray).orEmpty().mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        Favorite(o.str("name") ?: return@mapNotNull null, o.str("image_url"))
    }

    private fun JsonObject.str(key: String): String? =
        (this[key] as? JsonPrimitive)?.let { it.contentOrNull ?: it.intOrNull?.toString() }?.takeIf { it.isNotBlank() }
}

/** What HEOS last said about the receiver's player. */
data class HeosState(
    val connected: Boolean = false,
    val pid: Long? = null,
    /** `play`, `pause`, `stop`. */
    val playState: String? = null,
    val nowPlaying: HeosProtocol.NowPlaying? = null,
    /** Seconds; when they were read (ms). */
    val positionS: Long? = null,
    val durationS: Long? = null,
    val positionAt: Long? = null,
    /** `off`, `on_all`, `on_one`. */
    val repeat: String? = null,
    val shuffle: Boolean? = null,
    val favorites: List<HeosProtocol.Favorite> = emptyList()
) {
    /** Something is playing or paused on HEOS. */
    val active: Boolean get() = connected && (playState == "play" || playState == "pause") && nowPlaying?.let {
        it.title != null || it.station != null
    } == true
}
