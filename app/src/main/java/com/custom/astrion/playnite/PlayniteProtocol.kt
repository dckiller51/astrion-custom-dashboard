package com.custom.astrion.playnite

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** One PC added on the web page (1.6.3): `media_player.playnite_…`, reached at [host]:[port]. */
data class PlayniteSpec(
    val localId: String,
    val entityId: String,
    val name: String,
    val host: String,
    val port: Int,
    /** What "turn off" does (1.6.3): "sleep" or "shutdown". */
    val off: String = "sleep",
    /** Playnite switched to fullscreen when the remote starts it or a game (1.6.3, extension 0.1.3+). */
    val fullscreen: Boolean = false
)

/** One game of the library, as the Astrion Custom Dashboard extension for Playnite gives it. */
data class PlayniteGame(val id: String, val name: String, val cover: Boolean, val playtime: Long = 0, val lastPlayed: Long = 0)

/**
 * What the PC last said. [reachable] false: Playnite doesn't answer (the PC is off, asleep, or
 * Playnite isn't running). [codeRefused]: it answers, but not to this code.
 */
data class PlayniteState(
    val reachable: Boolean = false,
    val codeRefused: Boolean = false,
    val running: PlayniteGame? = null,
    val games: List<PlayniteGame> = emptyList(),
    /** Windows is locked, the sign-in screen showing (extension 0.1.2+). */
    val locked: Boolean = false,
    /** Playnite's window: "desktop", "fullscreen", or "" when the extension doesn't say (before 0.1.3). */
    val mode: String = ""
)

/** What a service call asks of the PC. */
sealed class PlayniteAction {
    data class Start(val game: PlayniteGame) : PlayniteAction()

    object Wake : PlayniteAction()

    /** The PC is on but locked: its unlock ESP types the PIN (1.6.3, Astrion PC Unlock). */
    object Unlock : PlayniteAction()

    /** The PC is on and unlocked: Playnite switched to fullscreen, its console-like view (1.6.3). */
    object Fullscreen : PlayniteAction()

    /** A game picked while the PC is off: wake it, wait for Playnite, then start the game. */
    data class WakeAndStart(val game: PlayniteGame) : PlayniteAction()

    data class Power(val action: String) : PlayniteAction()
}

/**
 * The Astrion Custom Dashboard extension for Playnite (1.6.3): its answers read, the PC as a `media_player` shaped
 * like Home Assistant's (the game running as what plays, with its cover; the games as sources),
 * and the service calls turned into what to ask it. Pure, so it is unit-tested.
 */
object PlayniteProtocol {
    const val DEFAULT_PORT = 8095
    const val CODE_HEADER = "X-Astrion-Code"

    /** `astrion_source_view`: the sources pop-up shows the sources as covers (1.6.3). */
    const val COVERS_VIEW = "covers"

    /** The most played-recently games offered as sources; the rest are in the library. */
    const val MAX_SOURCES = 100

    private const val SELECT_SOURCE = 2048
    private const val TURN_ON = 128
    private const val TURN_OFF = 256
    private const val FEATURES = SELECT_SOURCE or TURN_ON or TURN_OFF

    private val json = Json { ignoreUnknownKeys = true }

    data class Info(val name: String, val plugin: String, val macs: List<String>)

    private fun obj(text: String): JsonObject? = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull()

    private fun JsonObject.str(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.num(k: String) = (this[k] as? JsonPrimitive)?.longOrNull ?: 0L

    /** GET /info: null when it isn't the Astrion Custom Dashboard extension answering. */
    fun info(text: String): Info? {
        val o = obj(text) ?: return null
        if (o.str("app") != "playnite") return null
        val macs = (o["macs"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
        return Info(o.str("name").orEmpty(), o.str("plugin").orEmpty(), macs)
    }

    fun game(o: JsonObject?): PlayniteGame? {
        if (o == null) return null
        val id = o.str("id")?.takeIf { it.isNotBlank() } ?: return null
        return PlayniteGame(
            id = id,
            name = o.str("name").orEmpty().ifBlank { id },
            cover = (o["cover"] as? JsonPrimitive)?.booleanOrNull == true,
            playtime = o.num("playtime"),
            lastPlayed = o.num("lastPlayed")
        )
    }

    /** GET /state: the game running, or null (none, or not readable). */
    fun running(text: String): PlayniteGame? = game(obj(text)?.get("running") as? JsonObject)

    /** GET /state or /info: Playnite's mode, "desktop" or "fullscreen" (extension 0.1.3+; "" before). */
    fun mode(text: String): String =
        (obj(text)?.get("mode") as? JsonPrimitive)?.contentOrNull?.takeIf { it == "desktop" || it == "fullscreen" }.orEmpty()

    /** GET /state: Windows is locked (extension 0.1.2+; false before). */
    fun locked(text: String): Boolean = (obj(text)?.get("locked") as? JsonPrimitive)?.booleanOrNull == true

    /** GET /games: installed and not hidden, played most recently first. */
    fun games(text: String): List<PlayniteGame>? = (obj(text)?.get("games") as? JsonArray)?.mapNotNull { game(it as? JsonObject) }

    /** The games kept on the remote (as GET /games gives them), for when the PC is off. */
    fun gamesJson(games: List<PlayniteGame>): String = JsonObject(
        mapOf(
            "games" to JsonArray(
                games.map { g ->
                    JsonObject(
                        mapOf(
                            "id" to JsonPrimitive(g.id),
                            "name" to JsonPrimitive(g.name),
                            "cover" to JsonPrimitive(g.cover),
                            "playtime" to JsonPrimitive(g.playtime),
                            "lastPlayed" to JsonPrimitive(g.lastPlayed)
                        )
                    )
                }
            )
        )
    ).toString()

    fun coverUrl(spec: PlayniteSpec, game: PlayniteGame): String? =
        if (game.cover) "http://${spec.host}:${spec.port}/cover/${game.id}" else null

    fun stateOf(s: PlayniteState): String = when {
        !s.reachable -> "off"
        s.running != null -> "playing"
        else -> "on"
    }

    /** The PC's `media_player` attributes. */
    fun media(spec: PlayniteSpec, s: PlayniteState, covers: Map<String, String> = emptyMap()): Map<String, Any?> {
        val a = linkedMapOf<String, Any?>("friendly_name" to spec.name, "supported_features" to FEATURES, "app_name" to "Playnite")
        // Off, the games last seen stay offered: picking one wakes the PC and starts it.
        if (s.games.isNotEmpty()) {
            val shown = sources(s)
            a["source_list"] = shown.map { it.name }
            // The sources pop-up shows them as covers (1.6.3), kept on the remote ([covers], by game id).
            a["astrion_source_view"] = COVERS_VIEW
            // A tap on a cover opens the game's sheet (PlayniteSheet.REQUEST), with its Launch button.
            a["astrion_source_details"] = true
            val icons = shown.mapNotNull { g -> covers[g.id]?.let { g.name to it } }.toMap()
            if (icons.isNotEmpty()) a["astrion_source_icons"] = icons
        }
        s.running?.let { g ->
            a["source"] = g.name
            a["media_title"] = g.name
            a["media_content_type"] = "game"
            coverUrl(spec, g)?.let { a["entity_picture"] = it }
        }
        if (s.codeRefused) a["astrion_code_refused"] = true
        if (s.reachable && s.locked) a["astrion_locked"] = true
        return a
    }

    /** The games offered as sources: one per name (the most recently played), [MAX_SOURCES] at most. */
    fun sources(s: PlayniteState): List<PlayniteGame> = s.games.distinctBy { it.name.lowercase() }.take(MAX_SOURCES)

    /** A source picked by name: the exact name first, else ignoring case. */
    fun gameNamed(s: PlayniteState, name: String): PlayniteGame? {
        val wanted = name.trim()
        return s.games.firstOrNull { it.name == wanted } ?: s.games.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    /**
     * A `media_player` service: select_source starts a game; turn_on wakes the PC (Wake-on-LAN),
     * or unlocks it when it's on but locked and [canUnlock] (an unlock ESP), or switches Playnite
     * to fullscreen when it's on, unlocked, in desktop mode and [fullscreen]; turn_off puts it to
     * sleep or shuts it down ([off]). Null when not supported, or nothing to do.
     */
    fun service(
        service: String,
        data: Map<String, Any?>,
        s: PlayniteState,
        off: String = "sleep",
        canUnlock: Boolean = false,
        fullscreen: Boolean = false
    ): PlayniteAction? = when (service) {
        "select_source" ->
            (data["source"] as? String)?.let { gameNamed(s, it) }?.let {
                if (s.reachable) PlayniteAction.Start(it) else PlayniteAction.WakeAndStart(it)
            }
        "turn_on" -> turnOn(s, canUnlock, fullscreen)
        // Sleep by default (1.6.3): an Activity's "Off" puts the PC to sleep; "shutdown" when chosen on the web page.
        "turn_off" -> if (s.reachable) PlayniteAction.Power(if (off == "shutdown") "shutdown" else "sleep") else null
        else -> null
    }

    private fun turnOn(s: PlayniteState, canUnlock: Boolean, fullscreen: Boolean): PlayniteAction? = when {
        !s.reachable -> PlayniteAction.Wake
        s.locked && canUnlock -> PlayniteAction.Unlock
        !s.locked && fullscreen && s.mode == "desktop" -> PlayniteAction.Fullscreen
        else -> null
    }
}
