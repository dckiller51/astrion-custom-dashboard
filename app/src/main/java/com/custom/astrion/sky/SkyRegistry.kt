package com.custom.astrion.sky

import android.content.Context
import android.util.Log
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SkyConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
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
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Sky boxes added on the web page (1.5.3): each one published as a `media_player` and a `remote`
 * entity into [HaClient]'s local-entity overlay, and the service calls aimed at them answered here
 * with Sky remote keys. A Sky Q is polled (standby, channel, what's on); a Sky+ HD says nothing over
 * the network, so it's shown on (an assumed state, as in Home Assistant's `sky_remote`). Like the
 * Zidoo and LG ones, so cards, hotkeys and Activities drive it the same way with or without Home
 * Assistant.
 */
class SkyRegistry(configs: List<SkyConfig>, private val log: (String) -> Unit = { Log.d(TAG, it) }) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private class Device(val spec: SkySpec, val client: SkyClient, val executor: ScheduledExecutorService) {
        @Volatile var state = SkyState()

        @Volatile var poll: ScheduledFuture<*>? = null

        @Volatile var channelsReadAt = 0L
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()

    /** Key presses waiting: each opens a connection to the box, so a held key mustn't pile up. */
    private val pending = AtomicInteger()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "Sky-send").apply { isDaemon = true } }
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val spec = SkySpec(cfg.localId, cfg.entityId, cfg.name, cfg.skyQ)
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Sky-${cfg.localId}").apply { isDaemon = true } }
            val device = Device(spec, SkyClient({ cfg.host }, cfg.skyQ), executor)
            devices[spec.entityId] = device
            byRemoteEntity[spec.remoteEntityId] = device
            if (!cfg.skyQ) device.state = SkyState(reachable = true)
            publish(device)
            schedule(device, 0L)
        }
    }

    /** Screen off a while (quiet mode): not polled at all; at once on wake. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        if (quiet) devices.values.forEach { it.poll?.cancel(false) } else devices.values.forEach { schedule(it, 0L) }
    }

    fun stop() {
        stopped = true
        devices.values.forEach { it.executor.shutdownNow() }
        sender.shutdownNow()
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

    /** Each Sky Q's channel logos (its Sources: favourites, else the first channels), label → URL, for the icon import. */
    fun channelLogos(): List<Pair<String, Map<String, String>>> = devices.values.filter { it.spec.skyQ }.map { d ->
        d.spec.name to d.state.sourceChannels.associate { it.label to SkyProtocol.channelLogoUrl(it.sid, it.name, d.state.country) }
    }

    private fun schedule(device: Device, delayMs: Long) {
        val asleep = quiet && delayMs > 0
        if (stopped || !device.spec.skyQ || asleep) return
        device.poll?.cancel(false)
        device.poll = runCatching { device.executor.schedule({ pollOnce(device) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun pollOnce(device: Device) {
        val before = device.state
        val next = runCatching { read(device, before) }.getOrElse { before.copy(reachable = false) }
        if (next.reachable != before.reachable || next.standby != before.standby) {
            log("[${device.spec.name}] ${if (!next.reachable) "not answering" else if (next.standby) "in standby" else "on"}")
        }
        device.state = next
        publish(device)
        if (!quiet) schedule(device, if (next.on) ON_POLL_MS else OFF_POLL_MS)
    }

    private fun read(device: Device, before: SkyState): SkyState {
        val c = device.client
        val standby = c.standby() ?: return before.copy(reachable = false).idle()
        val now = System.currentTimeMillis()
        var s = before.copy(reachable = true, standby = standby)
        if (s.channels.isEmpty() || now - device.channelsReadAt > CHANNELS_REFRESH_MS) {
            c.deviceInfo()?.let { info ->
                val channels = c.channels(info)
                if (channels.isNotEmpty()) device.channelsReadAt = now
                val favourites = c.favourites()
                s = s.copy(model = info.model, country = info.country, channels = channels.ifEmpty { s.channels }, favourites = favourites)
            }
        }
        if (standby) return s.idle()
        val media = c.media()
        val sid = (media as? SkyProtocol.Media.Live)?.sid
        val (programme, next) = sid?.let { runCatching { c.onNowAndNext(it, s.country, now / MS_PER_S) }.getOrNull() } ?: (null to null)
        return s.copy(transport = c.transport(), media = media, programme = programme, next = next)
    }

    private fun publish(device: Device) {
        val c = client ?: return
        if (stopped) return
        c.setLocalEntity(toEntityState(SkyEntityMapper.media(device.spec, device.state)))
        c.setLocalEntity(toEntityState(SkyEntityMapper.remote(device.spec, device.state)))
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val data = call.data.mapValues { plain(it.value) }
        val (device, keys) =
            when (call.domain) {
                "media_player" -> devices[entityId]?.let { it to SkyServices.media(it.spec, call.service, data, it.state) }
                "remote" -> byRemoteEntity[entityId]?.let { it to SkyServices.remote(it.spec, call.service, data, it.state) }
                else -> null
            } ?: return false
        if (keys == null) {
            log("[${device.spec.name}] unsupported service ${call.domain}.${call.service}")
        } else if (keys.isNotEmpty() && pending.get() < MAX_PENDING) {
            pending.incrementAndGet()
            runCatching { sender.execute { perform(device, keys) } }.onFailure { pending.decrementAndGet() }
        }
        // Consumed even when unsupported: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(device: Device, keys: List<String>) {
        try {
            keys.forEachIndexed { i, key ->
                if (i > 0) Thread.sleep(KEY_GAP_MS)
                if (!device.client.sendKey(key)) log("[${device.spec.name}] key $key not taken")
            }
        } finally {
            pending.decrementAndGet()
        }
        // See the result soon (a channel changed, standby) instead of at the next poll.
        schedule(device, AFTER_COMMAND_MS)
    }

    private fun toEntityState(data: SkyEntityData) = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    companion object {
        private const val TAG = "SkyRegistry"
        private const val ON_POLL_MS = 5_000L
        private const val OFF_POLL_MS = 30_000L
        private const val AFTER_COMMAND_MS = 1_200L
        private const val CHANNELS_REFRESH_MS = 6 * 60 * 60_000L
        private const val KEY_GAP_MS = 150L
        private const val MAX_PENDING = 3
        private const val MS_PER_S = 1000L
    }
}

private fun plain(el: JsonElement?): Any? = when (el) {
    null, JsonNull -> null
    is JsonPrimitive -> if (el.isString) el.content else el.booleanOrNull ?: el.longOrNull ?: el.doubleOrNull ?: el.contentOrNull
    is JsonArray -> el.map { plain(it) }
    is JsonObject -> el.mapValues { plain(it.value) }
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
 * Builds, starts and wires a [SkyRegistry] — after the LG one, whose service handler it chains to.
 * Never throws: Sky support is optional and must not stop the app starting.
 */
fun installSky(context: Context, haClient: HaClient, log: (String) -> Unit = {}): SkyRegistry {
    val configs = runCatching { RemoteSettings.skys(context) }.getOrElse { emptyList() }
    val registry = SkyRegistry(configs, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Sky support failed to start: ${e.message}")
    }
    return registry
}
