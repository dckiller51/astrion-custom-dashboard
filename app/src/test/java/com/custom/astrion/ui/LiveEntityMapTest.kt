package com.custom.astrion.ui

import androidx.compose.runtime.snapshots.Snapshot
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What [LiveEntityMap] tells Compose: a read of one entity is invalidated by that entity's
 * changes only, a whole-map read by any. Plain JVM: Compose's snapshot system, no UI.
 */
class LiveEntityMapTest {
    private fun entity(id: String, state: String, brightness: Int = 0): EntityState {
        val attributes = JsonObject(mapOf("brightness" to JsonPrimitive(brightness)))
        return EntityState(id, state, attributes)
    }

    private val lamp = entity("light.lamp", "on", 120)
    private val fan = entity("fan.ceiling", "off")
    private val live = LiveEntityMap(MutableStateFlow(mapOf(lamp.entityId to lamp, fan.entityId to fan)))

    /** The state objects [read] observes. */
    private fun readsOf(read: () -> Unit): Set<Any> {
        val reads = mutableSetOf<Any>()
        val snapshot = Snapshot.takeSnapshot(readObserver = { reads += it })
        try {
            snapshot.enter(read)
        } finally {
            snapshot.dispose()
        }
        return reads
    }

    /** The state objects [LiveEntityMap.update] writes for [next]. */
    private fun writesOf(next: EntityMap): Set<Any> {
        val writes = mutableSetOf<Any>()
        val snapshot = Snapshot.takeMutableSnapshot(writeObserver = { writes += it })
        try {
            snapshot.enter { live.update(next) }
            snapshot.apply().check()
        } finally {
            snapshot.dispose()
        }
        return writes
    }

    @Test
    fun `a read of one entity follows that entity only`() {
        live[lamp.entityId] // its slot exists from the first read on
        val lampReads = readsOf { live[lamp.entityId] }

        val fanChanged = writesOf(mapOf(lamp.entityId to lamp, fan.entityId to fan.copy(state = "on")))
        assertTrue(fanChanged.intersect(lampReads).isEmpty())

        val lampChanged = writesOf(mapOf(lamp.entityId to entity("light.lamp", "on", 200), fan.entityId to fan))
        assertFalse(lampChanged.intersect(lampReads).isEmpty())
        assertEquals(200, live[lamp.entityId]?.attrInt("brightness"))
    }

    @Test
    fun `an equal entity in a new map is not rewritten`() {
        live[lamp.entityId]
        val lampReads = readsOf { live[lamp.entityId] }
        // HaClient publishes a fresh map every time; an equal entity in it must not count as a change.
        val writes = writesOf(mapOf(lamp.entityId to lamp.copy(), fan.entityId to fan))
        assertTrue(writes.intersect(lampReads).isEmpty())
        assertSame(lamp, live[lamp.entityId])
    }

    @Test
    fun `entities added and removed`() {
        assertNull(live["switch.kettle"])
        val kettleReads = readsOf { live["switch.kettle"] }
        val kettle = entity("switch.kettle", "on")

        assertFalse(writesOf(mapOf(lamp.entityId to lamp, kettle.entityId to kettle)).intersect(kettleReads).isEmpty())
        assertEquals(kettle, live["switch.kettle"])
        assertTrue("switch.kettle" in live)
        assertNull(live[fan.entityId])
        assertFalse(fan.entityId in live)
        assertEquals(setOf(lamp.entityId, kettle.entityId), live.keys)
    }

    @Test
    fun `a slot first read in a discarded snapshot stays usable`() {
        // As in a composition that's abandoned: the slot is created inside a snapshot never applied.
        val snapshot = Snapshot.takeMutableSnapshot()
        try {
            snapshot.enter { live["switch.kettle"] }
        } finally {
            snapshot.dispose()
        }
        assertNull(live["switch.kettle"])
        val kettle = entity("switch.kettle", "on")
        live.update(mapOf(kettle.entityId to kettle))
        assertEquals(kettle, live["switch.kettle"])
    }

    @Test
    fun `a whole-map read follows every change`() {
        val allReads = readsOf { live.values.toList() }
        val writes = writesOf(mapOf(lamp.entityId to lamp, fan.entityId to fan.copy(state = "on")))
        assertFalse(writes.intersect(allReads).isEmpty())
        assertEquals(2, live.size)
    }
}
