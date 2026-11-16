package com.custom.astrion.cards.impl

import com.custom.astrion.ha.EntityState

/**
 * What the PlayStation card (1.5.8) shows, read from Home Assistant's official **PlayStation
 * Network** integration: the account's sensors (online ID with its avatar, online status, trophy
 * level and progress, the four trophy counts), the console's media player (the game, its cover)
 * and PlayStation Plus. Pure, so tested.
 */
internal object PlayStationModel {
    /** The online status sensor's states (an enum in Home Assistant). */
    enum class Status { ONLINE, ON_PS_APP, AWAY, OFFLINE, UNKNOWN }

    data class Game(val title: String, val picture: String?)

    /** The four kinds of trophy, best first, with their option names. */
    enum class Trophy(
        val option: String
    ) {
        PLATINUM("platinum_entity"),
        GOLD("gold_entity"),
        SILVER("silver_entity"),
        BRONZE("bronze_entity")
    }

    private val MISSING = setOf("", "unknown", "unavailable", "none")

    fun status(e: EntityState?): Status = when (e?.state) {
        "availabletoplay" -> Status.ONLINE
        "availabletocommunicate" -> Status.ON_PS_APP
        "busy" -> Status.AWAY
        "offline" -> Status.OFFLINE
        else -> Status.UNKNOWN
    }

    /**
     * The game being played: from the console's media player (playing, with its title and cover),
     * or from the account's "now playing" sensor (its state, the cover from [picture]). Null when
     * nothing is played (an app or a disc counts as nothing: the integration only knows games).
     */
    fun game(e: EntityState?, picture: String?): Game? {
        e ?: return null
        if (e.domain == "media_player") {
            if (e.state != "playing") return null
            val title = e.attrString("media_title")?.takeIf { it.isNotBlank() } ?: return null
            return Game(title, picture ?: e.attrString("entity_picture"))
        }
        val title = e.state.takeUnless { it.lowercase() in MISSING } ?: return null
        return Game(title, picture ?: e.attrString("entity_picture"))
    }

    /** Whether the console is off (its media player says so); a sensor can't tell. */
    fun consoleOff(e: EntityState?): Boolean = e?.domain == "media_player" && e.state in setOf("off", "standby")

    /** A count (trophies, level) from a sensor's state; null when it has none. */
    fun count(e: EntityState?): Int? = e?.state?.toDoubleOrNull()?.toInt()

    /** The next level's progress (its sensor is in %), as 0..1; null when unknown. */
    fun progress(e: EntityState?): Float? = e?.state?.toFloatOrNull()?.let { (it / PERCENT).coerceIn(0f, 1f) }

    private const val PERCENT = 100f
}
