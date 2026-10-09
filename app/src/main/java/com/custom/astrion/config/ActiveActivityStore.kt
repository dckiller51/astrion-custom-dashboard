package com.custom.astrion.config

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/**
 * Remembers which Activity is active in each room (room → Activity id)
 * across an app restart, an APK update and a dashboard reload — like a
 * Harmony hub, which still knows "Watch TV" is running after a reboot.
 * [ActivityRuntime] is rebuilt on every config load and used to start
 * empty, so the Active Activities list, the Stop/Help buttons and the
 * active-tile highlight were lost each time.
 *
 * Only the *belief* is restored — nothing is sent. A restored id that no
 * longer exists in the room (Activity renamed or deleted) is dropped by
 * [ActivityRuntime].
 */
class ActiveActivityStore(initial: Map<String, String> = emptyMap(), private val persist: (String) -> Unit = {}) {
    @Volatile
    var active: Map<String, String> = initial
        private set

    fun save(byRoom: Map<String, String?>) {
        val next = byRoom.mapNotNull { (room, id) -> id?.let { room to it } }.toMap()
        if (next == active) return
        active = next
        persist(serialize(next))
    }

    companion object {
        /** Replaced once at startup by AstrionApp with a persisted store;
         * this in-memory default only serves previews and tests. */
        @Volatile
        var shared: ActiveActivityStore = ActiveActivityStore()

        fun serialize(byRoom: Map<String, String>): String = JsonObject(byRoom.mapValues { JsonPrimitive(it.value) }).toString()

        /** Lenient: anything unreadable is simply forgotten. */
        fun deserialize(text: String?): Map<String, String> = runCatching {
            Json.parseToJsonElement(text ?: return emptyMap()).jsonObject
                .mapNotNull { (room, el) -> (el as? JsonPrimitive)?.contentOrNull?.let { room to it } }
                .toMap()
        }.getOrDefault(emptyMap())
    }
}
