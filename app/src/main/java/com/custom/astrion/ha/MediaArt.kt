package com.custom.astrion.ha

/**
 * A Home Assistant player's picture (`/api/media_player_proxy/…`) is asked again a few seconds
 * after it changes (1.6.0, Zavie): the player says it plays something new before its device (an
 * Apple TV) has the new picture, so the first answer may still be the last item's, and the
 * address doesn't change again. Pure, so it is unit-tested.
 */
object MediaArt {
    /** After the first load: once 3 s later, once more 7 s after that. */
    val AGAIN_MS = longArrayOf(3_000L, 7_000L)

    private const val PROXY = "/api/media_player_proxy/"

    /** [path] is a Home Assistant player's picture, which may settle late. */
    fun settles(path: String?): Boolean = path != null && PROXY in path
}
