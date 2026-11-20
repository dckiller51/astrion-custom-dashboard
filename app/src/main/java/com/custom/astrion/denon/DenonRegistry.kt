package com.custom.astrion.denon

import android.content.Context
import android.util.Log
import com.custom.astrion.config.DenonConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.zidoo.plainOf
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Denon / Marantz receivers added on the web page (1.5.4): each one polled over its web API and
 * published as a `media_player` and a `remote` entity into [HaClient]'s local-entity overlay; the
 * service calls aimed at them answered here with the receiver's own commands. Like the Sky and
 * Samsung ones, so cards, hotkeys and Activities drive it the same way with or without Home
 * Assistant.
 *
 * Polled every [ON_POLL_MS] when on, [OFF_POLL_MS] in standby, not at all with the remote's
 * screen off a while (quiet mode).
 */
class DenonRegistry(configs: List<DenonConfig>, private val log: (String) -> Unit = { Log.d(TAG, it) }) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private class Device(val spec: DenonSpec, val client: DenonClient, val executor: ScheduledExecutorService) {
        @Volatile var state = DenonState()

        @Volatile var poll: ScheduledFuture<*>? = null

        /** Its HEOS connection (recent models; one that has no HEOS just never connects). */
        var heos: HeosConnection? = null
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()

    /** Zone 2 / 3 entities (1.5.7): the receiver and the zone. */
    private val byZoneEntity = HashMap<String, Pair<Device, Int>>()

    private fun zonesOf(spec: DenonSpec) = (2..spec.zones).toList()

    /** Commands waiting: a held volume key mustn't pile up behind a slow answer. */
    private val pending = AtomicInteger()
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val spec = DenonSpec(cfg.localId, cfg.entityId, cfg.name, cfg.inputNames, cfg.hiddenInputs, cfg.zones)
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Denon-${cfg.localId}").apply { isDaemon = true } }
            val device = Device(spec, DenonClient(cfg.host, cfg.port), executor)
            devices[spec.entityId] = device
            byRemoteEntity[spec.remoteEntityId] = device
            zonesOf(spec).forEach { z -> byZoneEntity[spec.zoneEntityId(z)] = device to z }
            device.heos =
                HeosConnection(cfg.host, onChange = { h ->
                    runCatching {
                        executor.execute {
                            device.state = device.state.copy(heos = h)
                            publish(device)
                        }
                    }
                }, log = log)
            publish(device)
            schedule(device, 0L)
            device.heos?.start()
        }
    }

    /** Screen off a while (quiet mode): not polled at all; at once on wake. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        if (quiet) {
            devices.values.forEach {
                it.poll?.cancel(false)
                it.heos?.stop()
            }
        } else {
            devices.values.forEach {
                schedule(it, 0L)
                it.heos?.start()
            }
        }
    }

    fun stop() {
        stopped = true
        devices.values.forEach {
            it.heos?.stop()
            it.executor.shutdownNow()
        }
        client?.let { c ->
            devices.values.forEach {
                c.removeLocalEntity(it.spec.entityId)
                c.removeLocalEntity(it.spec.remoteEntityId)
            }
            c.localServiceHandler = previousHandler
        }
        devices.clear()
        byRemoteEntity.clear()
    }

    private fun schedule(device: Device, delayMs: Long) {
        val asleep = quiet && delayMs > 0
        if (stopped || asleep) return
        device.poll?.cancel(false)
        device.poll = runCatching { device.executor.schedule({ pollOnce(device) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun pollOnce(device: Device) {
        val before = device.state
        val status = device.client.status()
        val next =
            if (status == null) {
                before.copy(reachable = false, on = false)
            } else {
                val model = before.model ?: device.client.model() ?: status.name
                DenonState(
                    reachable = true,
                    on = status.on,
                    model = model,
                    input = status.input,
                    volumeDb = status.volumeDb,
                    muted = status.muted,
                    soundMode = status.soundMode,
                    inputs =
                    status.inputs.takeIf { it.isNotEmpty() }?.let {
                        DenonProtocol.customInputs(it, device.spec.inputNames, device.spec.hiddenInputs)
                    } ?: before.inputs,
                    receiverInputs = status.inputs.ifEmpty { before.receiverInputs },
                    heos = before.heos,
                    zones = zonesOf(device.spec).mapNotNull { z -> device.client.zoneStatus(z)?.let { z to it } }.toMap()
                )
            }
        if (next.reachable != before.reachable || next.on != before.on) {
            log("[${device.spec.name}] ${if (!next.reachable) "not answering" else if (next.on) "on" else "in standby"}")
        }
        device.state = next
        publish(device)
        val anyOn = next.isOn || next.zones.values.any { it.on }
        if (!quiet) schedule(device, if (anyOn) ON_POLL_MS else OFF_POLL_MS)
    }

    private fun publish(device: Device) {
        val c = client ?: return
        if (stopped) return
        c.setLocalEntity(toEntityState(DenonEntityMapper.media(device.spec, device.state)))
        c.setLocalEntity(toEntityState(DenonEntityMapper.remote(device.spec, device.state)))
        zonesOf(device.spec).forEach { z -> c.setLocalEntity(toEntityState(DenonEntityMapper.zone(device.spec, device.state, z))) }
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val data = call.data.mapValues { plainOf(it.value) }
        val (device, commands) =
            when (call.domain) {
                "media_player" ->
                    devices[entityId]?.let { it to DenonServices.media(call.service, data, it.state) }
                        ?: byZoneEntity[entityId]?.let { (d, z) -> d to DenonServices.zone(call.service, data, d.state, z) }
                "remote" -> byRemoteEntity[entityId]?.let { it to DenonServices.remote(call.service, data, it.state) }
                else -> null
            } ?: return false
        if (commands == null) {
            log("[${device.spec.name}] unsupported service ${call.domain}.${call.service}")
        } else if (commands.isNotEmpty() && pending.get() < MAX_PENDING) {
            pending.incrementAndGet()
            runCatching { device.executor.execute { perform(device, commands) } }.onFailure { pending.decrementAndGet() }
        }
        // Consumed even when unsupported: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(device: Device, commands: List<String>) {
        try {
            commands.forEachIndexed { i, command ->
                if (i > 0) Thread.sleep(COMMAND_GAP_MS)
                val sent = if (command.startsWith("heos:")) heos(device, command) else device.client.send(command)
                if (!sent) log("[${device.spec.name}] $command not taken")
            }
        } finally {
            pending.decrementAndGet()
        }
        // See the result soon (volume, input, power) instead of at the next poll.
        schedule(device, AFTER_COMMAND_MS)
    }

    /** `heos:play`, `heos:preset:3`, `heos:mode:on_all:off`… (see [DenonServices]) to its HEOS connection. */
    private fun heos(device: Device, command: String): Boolean {
        val conn = device.heos ?: return false
        val pid = conn.state.pid ?: return false
        val parts = command.removePrefix("heos:").split(':')
        val line =
            when (parts[0]) {
                "play", "pause", "stop" -> HeosProtocol.setPlayState(pid, parts[0])
                "next" -> HeosProtocol.next(pid)
                "previous" -> HeosProtocol.previous(pid)
                "preset" -> parts.getOrNull(1)?.toIntOrNull()?.let { HeosProtocol.playPreset(pid, it) }
                "mode" -> if (parts.size == 3) HeosProtocol.setPlayMode(pid, parts[1], parts[2] == "on") else null
                else -> null
            } ?: return false
        return conn.send(line)
    }

    private fun toEntityState(data: DenonEntityData) = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    private companion object {
        const val TAG = "DenonRegistry"
        const val ON_POLL_MS = 3_000L
        const val OFF_POLL_MS = 15_000L
        const val AFTER_COMMAND_MS = 600L
        const val COMMAND_GAP_MS = 120L
        const val MAX_PENDING = 3
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
 * Builds, starts and wires a [DenonRegistry] — after the Samsung one, whose service handler it
 * chains to. Never throws: receiver support is optional and must not stop the app starting.
 */
fun installDenon(context: Context, haClient: HaClient, log: (String) -> Unit = {}): DenonRegistry {
    val configs = runCatching { RemoteSettings.denons(context) }.getOrElse { emptyList() }
    val registry = DenonRegistry(configs, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Denon / Marantz support failed to start: ${e.message}")
    }
    return registry
}
