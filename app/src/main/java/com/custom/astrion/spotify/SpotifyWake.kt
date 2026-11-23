package com.custom.astrion.spotify

/**
 * Spotify only lists the Spotify Connect devices awake (1.6.2): a TV or an Apple TV asleep, or
 * whose Spotify app is closed, isn't among them. Every player the remote knows that has Spotify
 * among its apps (an Apple TV, an Android TV, a Samsung or LG TV added in Astrion, or one from
 * Home Assistant) is offered as a source too: picking it opens Spotify on it, then the music moves
 * there once Spotify lists it. Names differ ("Living room" for Spotify, "Apple TV Living room"
 * for the remote), so a device is told by its words. Pure, so it is unit-tested.
 */
object SpotifyWake {
    /** A player that can open Spotify: [spotifySource] is the app's name in its source list. */
    data class Player(val entityId: String, val name: String, val state: String, val spotifySource: String)

    /** "Spotify" in a player's source list (or a name starting with it, "Spotify - Music and Podcasts"). */
    fun spotifySource(sources: List<String>): String? = sources.firstOrNull { it.trim().equals(SPOTIFY, ignoreCase = true) }
        ?: sources.firstOrNull { it.trim().startsWith(SPOTIFY, ignoreCase = true) }

    /** Off or asleep: turned on before Spotify is opened. */
    fun asleep(state: String): Boolean = state == "off" || state == "standby"

    private fun words(name: String): Set<String> = name.lowercase().split(NOT_WORD).filter { it.isNotEmpty() }.toSet()

    /**
     * Two names for one device: the same words, or every word of the shorter one in the other
     * when it has at least two ("Apple TV" / "Apple TV Living room"), so a lone "Living room"
     * speaker isn't taken for the living room's TV.
     */
    fun same(a: String, b: String): Boolean {
        val x = words(a)
        val y = words(b)
        if (x.isEmpty() || y.isEmpty()) return false
        if (x == y) return true
        val (small, big) = if (x.size <= y.size) x to y else y to x
        return small.size >= 2 && big.containsAll(small)
    }

    /** The players to offer: not already a Spotify device (by name, or because one was woken as it), one per name. */
    fun wakeable(players: List<Player>, devices: List<SpotifyDevice>, woken: Map<String, String>): List<Player> = players
        .filter { p -> devices.none { d -> woken[p.entityId] == d.id || same(d.name, p.name) } }
        .distinctBy { it.name.lowercase() }
        .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })

    /**
     * The device [player] became, once Spotify opened on it: one named like it, else the only
     * device new since [before].
     */
    fun found(player: Player, before: Set<String>, devices: List<SpotifyDevice>): SpotifyDevice? {
        val fresh = devices.filter { it.id !in before }
        return fresh.firstOrNull { same(it.name, player.name) }
            ?: devices.firstOrNull { same(it.name, player.name) }
            ?: fresh.singleOrNull()
    }

    private const val SPOTIFY = "Spotify"
    private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
}
