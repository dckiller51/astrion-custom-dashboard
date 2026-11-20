package com.custom.astrion.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.referentialEqualityPolicy
import androidx.compose.runtime.remember
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import kotlinx.coroutines.flow.StateFlow

/**
 * [com.custom.astrion.ha.HaClient.entities] as the composition reads it: one map that stays the
 * same instance, with a snapshot state per entity. A card reading `ctx.entities["light.kitchen"]`
 * recomposes only when light.kitchen changes — not, as with a new map per update, the whole page
 * on every one. A whole-map read (iteration, `size`, `values`) still follows every change.
 *
 * Main thread only: [follow] runs in the composition's own scope, and cards read it from
 * composition, effects and click handlers.
 */
class LiveEntityMap(private val source: StateFlow<EntityMap>) : AbstractMap<String, EntityState>() {
    // The latest map, unobserved: what a new slot starts from.
    private var latest = source.value

    // The latest map again, observed: behind every whole-map read.
    private val whole = mutableStateOf(latest, referentialEqualityPolicy())

    // One per entity id read so far, present or not (a card waiting for its entity sees it arrive).
    private val slots = HashMap<String, MutableState<EntityState?>>()

    override val entries: Set<Map.Entry<String, EntityState>> get() = whole.value.entries

    override fun get(key: String): EntityState? = slots.getOrPut(key) { mutableStateOf(latest[key]) }.value

    override fun containsKey(key: String): Boolean = get(key) != null

    /** Applies [source]'s updates until cancelled. */
    suspend fun follow(): Nothing = source.collect { update(it) }

    /** Takes the next published map; a slot is written only when its entity changed. */
    internal fun update(next: EntityMap) {
        latest = next
        whole.value = next
        // The default policy skips an equal value, so an unchanged entity invalidates nothing.
        slots.forEach { (id, slot) -> slot.value = next[id] }
    }
}

/** This flow as a [LiveEntityMap], kept up to date for as long as the caller stays composed. */
@Composable
fun StateFlow<EntityMap>.collectAsLiveEntityMap(): EntityMap {
    val live = remember(this) { LiveEntityMap(this) }
    LaunchedEffect(live) { live.follow() }
    return live
}
