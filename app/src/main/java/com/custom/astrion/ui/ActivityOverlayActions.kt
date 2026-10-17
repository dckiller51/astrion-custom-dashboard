package com.custom.astrion.ui

/** What the Active Activities overlay can do to a room's Activity —
 * bundled so adding "Help" didn't grow every signature it passes through. */
data class ActivityOverlayActions(
    /** Stops the Activity active in a room (see ActivityDispatcher.stopActivity). */
    val stop: (room: String) -> Unit,
    /** Replays it against the corrected device states (ActivityDispatcher.resyncActivity). */
    val resync: (room: String) -> Unit
)
