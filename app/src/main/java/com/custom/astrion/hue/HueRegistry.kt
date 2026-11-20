package com.custom.astrion.hue

import android.content.Context
import android.util.Log
import com.custom.astrion.config.HueConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.zidoo.plainOf
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Philips Hue bridges added on the web page (1.6.1): each bridge's lights, plugs, rooms, zones and
 * scenes published as `light.hue_…` and `scene.hue_…` entities into [HaClient]'s local-entity
 * overlay (see [HueEntities]), and the service calls aimed at them sent to the bridge, so the light
 * card, buttons, scene tiles, hotkeys and Activities drive Hue lights without Home Assistant.
 *
 * Everything is read once, then the bridge pushes its changes on its event stream (a light
 * switched from the Hue app or a wall switch shows at once). With the remote's screen off a while
 * (quiet mode) the stream closes; on wake everything is read again and the stream reopened.
 */
class HueRegistry(
    configs: List<HueConfig>,
    private val saveIds: (localId: String, ids: Map<String, String>) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private inner class Bridge(val cfg: HueConfig) {
        val client = HueClient(cfg.host, cfg.appKey, cfg.pin.ifBlank { null }, cfg.localId)

        /** What the bridge has; only read and changed on [executor]. */
        var store = HueStore()

        @Volatile var summary = Summary(reachable = false, lights = 0, groups = 0, scenes = 0)
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r -> Thread(r, "Hue-${cfg.localId}").apply { isDaemon = true } }

        @Volatile var ids: Map<String, String> = cfg.entities

        /** Entity id → (resource type, resource id). */
        @Volatile var targets: Map<String, Pair<String, String>> = emptyMap()

        @Volatile var published: Set<String> = emptySet()

        @Volatile var reachable = false

        @Volatile var streamThread: Thread? = null

        /** Each wake (or retry) starts a new generation: an older stream thread stops. */
        @Volatile var generation = 0

        @Volatile var lastGroupPut = 0L

        @Volatile var saidAway = false

        /** (entity id, name) of each entity, once the bridge has been read; null before. */
        @Volatile var catalog: List<Pair<String, String>>? = null
    }

    private val bridges = ArrayList<Bridge>()
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val b = Bridge(cfg)
            bridges += b
            if (cfg.appKey.isBlank()) {
                log("[${cfg.name}] not paired yet: pair it again on the web page")
            } else {
                open(b)
            }
        }
    }

    /** Screen off a while: the streams close; on wake everything is read again. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        bridges.filter { it.cfg.appKey.isNotBlank() }.forEach { b ->
            if (quiet) {
                b.generation++
                b.client.close()
            } else {
                open(b)
            }
        }
    }

    /** What each bridge has, for the web page: lights, rooms and zones, scenes, and whether it answers. */
    fun summary(localId: String): Summary? {
        val b = bridges.firstOrNull { it.cfg.localId == localId } ?: return null
        return b.summary.copy(reachable = b.reachable)
    }

    /**
     * The builder's catalog for bridge [localId] (1.6.1): each light, room, zone and scene as
     * (entity id, name). Null while the bridge hasn't been read yet (nothing to add or remove).
     */
    fun catalog(localId: String): List<Pair<String, String>>? = bridges.firstOrNull { it.cfg.localId == localId }?.catalog

    data class Summary(val reachable: Boolean, val lights: Int, val groups: Int, val scenes: Int)

    fun stop() {
        stopped = true
        bridges.forEach {
            it.generation++
            it.client.close()
            it.executor.shutdownNow()
        }
        client?.let { c ->
            c.setLocalEntities(emptyList(), bridges.flatMap { it.published })
            c.localServiceHandler = previousHandler
        }
        bridges.clear()
    }

    /** Reads everything, then keeps the stream open (on its own thread) until the next generation. */
    private fun open(b: Bridge) {
        if (stopped) return
        val generation = ++b.generation
        b.client.close()
        val thread =
            Thread({ run(b, generation) }, "HueStream-${b.cfg.localId}").apply {
                isDaemon = true
            }
        b.streamThread = thread
        thread.start()
    }

    private fun current(b: Bridge, generation: Int) = !stopped && !quiet && b.generation == generation

    private fun run(b: Bridge, generation: Int) {
        while (current(b, generation)) {
            val loaded = runCatching { b.client.all() }
            val fresh = HueStore()
            if (loaded.getOrNull()?.let { fresh.load(it) } != true) {
                away(b, loaded.exceptionOrNull()?.message ?: "unexpected answer")
                if (!sleep(RETRY_MS)) return
                continue
            }
            if (!b.reachable || b.saidAway) log("[${b.cfg.name}] connected: ${fresh.ofType(HueEntities.LIGHT).size} lights")
            b.reachable = true
            b.saidAway = false
            // Swapped on the executor, before any event of the stream opened next.
            submit(b) {
                b.store = fresh
                publish(b)
            }
            val why =
                b.client.stream(onOpen = {}) { events ->
                    submit(b) { if (b.store.apply(events)) publish(b) }
                }
            if (!current(b, generation)) return
            // Ended by its idle timeout (a quiet home): opened again without a word in the log.
            if (!why.contains("timeout", ignoreCase = true)) log("[${b.cfg.name}] event stream ended ($why): reading everything again")
            if (!sleep(AGAIN_MS)) return
        }
    }

    private fun away(b: Bridge, reason: String) {
        if (!b.saidAway) log("[${b.cfg.name}] not answering at ${b.cfg.host}: $reason")
        b.saidAway = true
        if (b.reachable) {
            b.reachable = false
            submit(b) { publish(b) }
        }
    }

    private fun sleep(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun submit(b: Bridge, task: () -> Unit) {
        runCatching { b.executor.execute(task) }
    }

    /** All the bridge's entities, published at once; new names get an id, kept from then on. */
    private fun publish(b: Bridge) {
        val c = client ?: return
        if (stopped) return
        if (b.store.size > 0) {
            val taken = bridges.filter { it !== b }.flatMap { it.ids.values }.toSet()
            val ids = HueEntities.assignIds(b.store, b.ids, taken)
            if (ids != b.ids) {
                b.ids = ids
                saveIds(b.cfg.localId, ids)
            }
        }
        val entities =
            HueEntities.entities(b.store, b.ids).map { e ->
                if (b.reachable) e else e.copy(state = HueEntities.UNAVAILABLE)
            }
        b.summary =
            Summary(
                reachable = b.reachable,
                lights = b.store.ofType(HueEntities.LIGHT).size,
                groups = b.store.ofType("room").size + b.store.ofType("zone").size,
                scenes = b.store.ofType(HueEntities.SCENE).size
            )
        val typeOf = HashMap<String, Pair<String, String>>()
        b.ids.forEach { (rid, entityId) -> b.store[rid]?.str("type")?.let { typeOf[entityId] = it to rid } }
        b.targets = typeOf
        if (b.store.size > 0) b.catalog = entities.map { it.entityId to (it.attributes["friendly_name"] as? String ?: it.entityId) }
        val now = entities.map { it.entityId }.toSet()
        c.setLocalEntities(entities.map(::toEntityState), b.published - now)
        b.published = now
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val b = bridges.firstOrNull { entityId in it.targets } ?: return false
        val (type, rid) = b.targets.getValue(entityId)
        val data = call.data.mapValues { plainOf(it.value) }
        val service = call.service
        val known = call.domain == "homeassistant" || call.domain == entityId.substringBefore('.')
        // Built on the executor, where the light's state (for toggle and steps) is read.
        submit(b) {
            val body =
                when {
                    !known -> null
                    type == HueEntities.SCENE -> HueEntities.sceneCommand(service, data)
                    else -> HueEntities.lightCommand(service, data, b.store[rid], group = type == HueEntities.GROUP)
                }
            if (body == null) log("[${b.cfg.name}] unsupported service ${call.domain}.$service on $entityId") else send(b, type, rid, body)
        }
        // Consumed either way: Home Assistant doesn't know this entity.
        return true
    }

    private fun send(b: Bridge, type: String, rid: String, body: JsonObject) {
        // The bridge takes about one group command a second (ten a second for a light).
        if (type == HueEntities.GROUP) {
            val wait = b.lastGroupPut + GROUP_GAP_MS - System.currentTimeMillis()
            if (wait > 0 && !sleep(wait)) return
            b.lastGroupPut = System.currentTimeMillis()
        }
        try {
            b.client.put(type, rid, body)
            if (type != HueEntities.SCENE) {
                b.store.merge(rid, HueEntities.optimistic(body))
                publish(b)
            }
        } catch (e: Exception) {
            log("[${b.cfg.name}] $type $rid refused: ${e.message}")
        }
    }

    private fun toEntityState(e: HueEntity) = EntityState(
        entityId = e.entityId,
        state = e.state,
        attributes = JsonObject(e.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    private companion object {
        const val TAG = "HueRegistry"
        const val RETRY_MS = 30_000L
        const val AGAIN_MS = 2_000L
        const val GROUP_GAP_MS = 1_000L
    }
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) -> k.toString() to toJson(v) })
    else -> JsonPrimitive(value.toString())
}

/**
 * Builds, starts and wires a [HueRegistry], chained after the Denon one. Never throws: Hue
 * support is optional and must not stop the app starting.
 */
fun installHue(context: Context, haClient: HaClient, log: (String) -> Unit = {}): HueRegistry {
    val configs = runCatching { RemoteSettings.hues(context) }.getOrElse { emptyList() }
    val saveIds: (String, Map<String, String>) -> Unit = { localId, ids ->
        runCatching {
            RemoteSettings.saveHues(
                context,
                RemoteSettings.hues(context).map { if (it.localId == localId) it.copy(entities = ids) else it }
            )
        }
    }
    val registry = HueRegistry(configs, saveIds, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Hue support failed to start: ${e.message}")
    }
    return registry
}
