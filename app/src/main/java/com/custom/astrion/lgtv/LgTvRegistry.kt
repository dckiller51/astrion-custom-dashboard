package com.custom.astrion.lgtv

import android.content.Context
import android.util.Log
import com.custom.astrion.config.LgTvConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.zidoo.ZidooClient
import com.custom.astrion.zidoo.plainOf
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * LG TVs added on the web page (1.5.2): each one kept connected over webOS's WebSocket (see
 * [LgTvConnection]), what it says published as a `media_player` and a `remote` entity into
 * [HaClient]'s local-entity overlay, and the service calls aimed at those entities answered here —
 * like the Android TVs and Zidoos, so cards, hotkeys and Activities drive an LG TV the same way
 * with or without Home Assistant.
 *
 * The TV pushes its changes (power, app in front, volume, apps, inputs): nothing is polled. Off, it
 * doesn't answer; it is tried again every [RETRY_MS]. With the remote's screen off a while (quiet
 * mode) the connection is closed, and opened again the moment the screen comes on.
 */
class LgTvRegistry(
    configs: List<LgTvConfig>,
    private val savePin: (localId: String, pin: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private class Device(val cfg: LgTvConfig, val spec: LgTvSpec, val executor: ScheduledExecutorService) {
        @Volatile var state = LgTvState()

        @Volatile var conn: LgTvConnection? = null

        @Volatile var retry: ScheduledFuture<*>? = null

        @Volatile var pin: String = cfg.pin

        /** Which connection is the current one: a late callback of an older one is ignored. */
        @Volatile var generation = 0

        /** Not answering was said in the log, once until it answers again. */
        @Volatile var saidAway = false
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val spec = LgTvSpec(cfg.localId, cfg.entityId, cfg.name, cfg.mac)
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "LgTv-${cfg.localId}").apply { isDaemon = true } }
            val device = Device(cfg, spec, executor)
            devices[spec.entityId] = device
            byRemoteEntity[spec.remoteEntityId] = device
            publish(device)
            if (cfg.clientKey.isBlank()) {
                log("[${cfg.name}] not paired yet: pair it again on the web page")
            } else {
                schedule(device, 0L)
            }
        }
    }

    /** Screen off a while (quiet mode): the connections close; back at once on wake. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        devices.values.forEach { d ->
            if (quiet) {
                runCatching { d.executor.execute { leave(d) } }
            } else if (d.cfg.clientKey.isNotBlank()) {
                schedule(d, 0L)
            }
        }
    }

    /** Icons already fetched, by TV and id: small PNGs, kept while the app runs. */
    private val icons = java.util.concurrent.ConcurrentHashMap<String, ByteArray>()

    /**
     * The icon of an app or input of the TV [localId] (GET /lgtv-icon/<localId>?id=…), fetched once
     * from the TV itself — over its connection, so a TV with its own TLS certificate works too.
     */
    fun iconFor(localId: String, id: String): ByteArray? {
        val key = "$localId|$id"
        icons[key]?.let { return it }
        val device = devices.values.firstOrNull { it.cfg.localId == localId } ?: return null
        val url = device.state.iconOf(id) ?: return null
        val bytes = device.conn?.download(url) ?: return null
        icons[key] = bytes
        return bytes
    }

    /** Each TV's name and its apps' and inputs' icons (name → bytes), for the web page's icon import. */
    fun allIcons(): List<Pair<String, Map<String, ByteArray>>> = devices.values.map { d ->
        val items = d.state.apps.map { it.id to it.label } + d.state.inputs.map { it.id to it.label }
        d.cfg.name to items.mapNotNull { (id, label) -> iconFor(d.cfg.localId, id)?.let { label to it } }.toMap()
    }

    fun stop() {
        stopped = true
        devices.values.forEach {
            it.retry?.cancel(false)
            it.conn?.close()
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
        if (stopped) return
        device.retry?.cancel(false)
        device.retry = runCatching { device.executor.schedule({ connect(device) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun connect(device: Device) {
        if (stopped || device.state.reachable) return
        device.conn?.close()
        val generation = ++device.generation
        val conn = LgTvConnection(device.cfg.host, device.cfg.clientKey, device.pin.ifBlank { null }, listenerOf(device, generation))
        device.conn = conn
        conn.connect()
    }

    private fun listenerOf(device: Device, generation: Int) = object : LgTvConnection.Listener {
        override fun onPrompt() {
            // A key the TV forgot (reset, or the remote removed from its list): it asks again.
            log("[${device.spec.name}] the TV asks to accept the remote: accept it on the TV")
        }

        override fun onRegistered(clientKey: String, pin: String?) {
            runCatching { device.executor.execute { if (generation == device.generation) registered(device, pin) } }
        }

        override fun onClosed(reason: String) {
            runCatching { device.executor.execute { if (generation == device.generation) closed(device, reason) } }
        }
    }

    private fun registered(device: Device, pin: String?) {
        if (pin != null && device.pin.isBlank()) {
            device.pin = pin
            savePin(device.cfg.localId, pin)
        }
        if (device.saidAway || !device.state.reachable) log("[${device.spec.name}] connected")
        device.saidAway = false
        update(device) { it.copy(reachable = true, on = true) }
        val c = device.conn ?: return
        c.request(
            LgTvProtocol.SYSTEM_INFO
        ) { m -> LgTvProtocol.model(m.payload)?.let { model -> update(device) { it.copy(model = model) } } }
        c.subscribe(LgTvProtocol.POWER_STATE) { p ->
            val on = LgTvProtocol.isOn(p) ?: return@subscribe
            update(device) { it.copy(on = on, screenOff = LgTvProtocol.screenOff(p)) }
        }
        c.subscribe(LgTvProtocol.FOREGROUND_APP) { p -> update(device) { it.copy(app = LgTvProtocol.foregroundApp(p)) } }
        c.subscribe(LgTvProtocol.AUDIO_STATUS) { p ->
            update(device) { it.copy(volume = LgTvProtocol.volume(p) ?: it.volume, muted = LgTvProtocol.muted(p) ?: it.muted) }
        }
        c.subscribe(LgTvProtocol.VOLUME) { p ->
            update(device) { it.copy(volume = LgTvProtocol.volume(p) ?: it.volume, muted = LgTvProtocol.muted(p) ?: it.muted) }
        }
        c.subscribe(LgTvProtocol.APPS) { p -> update(device) { s -> appsAfter(s, p) } }
        c.subscribe(LgTvProtocol.INPUTS) { p -> LgTvProtocol.inputs(p)?.let { inputs -> update(device) { it.copy(inputs = inputs) } } }
        c.openPointer()
    }

    private fun appsAfter(s: LgTvState, p: JsonObject): LgTvState {
        LgTvProtocol.apps(p)?.let { return s.copy(apps = it) }
        val (id, item) = LgTvProtocol.appChange(p) ?: return s
        val others = s.apps.filter { it.id != id }
        return s.copy(apps = if (item == null) others else others + item)
    }

    /** Closed on purpose (quiet mode): no "disconnected" in the log, and no retry until the screen comes on. */
    private fun leave(device: Device) {
        device.retry?.cancel(false)
        device.generation++
        device.conn?.close()
        device.conn = null
        update(device) { LgTvState(model = it.model, apps = it.apps, inputs = it.inputs) }
    }

    private fun closed(device: Device, reason: String) {
        device.conn = null
        if (device.state.reachable) log("[${device.spec.name}] disconnected: $reason")
        if (!device.state.reachable && !device.saidAway) {
            log("[${device.spec.name}] not answering (off or away): $reason")
            device.saidAway = true
        }
        // What it has (apps, inputs, model) stays known while it's off.
        update(device) { LgTvState(model = it.model, apps = it.apps, inputs = it.inputs) }
        // Quiet (screen off a while): left closed, connected again the moment the screen comes on.
        if (!quiet) schedule(device, RETRY_MS)
    }

    private fun update(device: Device, change: (LgTvState) -> LgTvState) {
        device.state = change(device.state)
        publish(device)
    }

    private fun publish(device: Device) {
        val c = client ?: return
        if (stopped) return
        c.setLocalEntity(toEntityState(LgTvEntityMapper.media(device.spec, device.state)))
        c.setLocalEntity(toEntityState(LgTvEntityMapper.remote(device.spec, device.state)))
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val data = call.data.mapValues { plainOf(it.value) }
        val (device, steps) =
            when (call.domain) {
                "media_player" -> devices[entityId]?.let { it to LgTvServices.media(call.service, data, it.state) }
                "remote" -> byRemoteEntity[entityId]?.let { it to LgTvServices.remote(call.service, data, it.state) }
                else -> null
            } ?: return false
        if (steps == null) {
            log("[${device.spec.name}] unsupported service ${call.domain}.${call.service}")
        } else if (steps.isNotEmpty()) {
            runCatching { device.executor.execute { perform(device, steps) } }
        }
        // Consumed even when unsupported: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(device: Device, steps: List<LgTvStep>) {
        val c = device.conn
        steps.forEachIndexed { i, step ->
            if (i > 0) Thread.sleep(KEY_GAP_MS)
            when (step) {
                is LgTvStep.Button -> c?.button(step.name)
                is LgTvStep.Request -> {
                    c?.request(step.uri, step.payload)
                    if (step.uri == LgTvProtocol.PLAY || step.uri == LgTvProtocol.PAUSE) {
                        update(device) { it.copy(paused = step.uri == LgTvProtocol.PAUSE) }
                    }
                }
                LgTvStep.Wake -> wake(device)
            }
        }
    }

    /** Wake-on-LAN, then tries to connect every few seconds while it starts. */
    private fun wake(device: Device) {
        val mac = device.spec.mac
        if (mac.isBlank()) {
            log("[${device.spec.name}] can't turn it on: no MAC address (pair it again with the TV on)")
            return
        }
        repeat(WAKE_PACKETS) { ZidooClient.wake(mac) }
        schedule(device, AFTER_WAKE_MS)
    }

    private fun toEntityState(data: LgTvEntityData) = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    private companion object {
        const val TAG = "LgTvRegistry"
        const val RETRY_MS = 10_000L
        const val AFTER_WAKE_MS = 4_000L
        const val KEY_GAP_MS = 60L
        const val WAKE_PACKETS = 3
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
 * Builds, starts and wires an [LgTvRegistry] — after the Zidoo one, whose service handler it
 * chains to. Never throws: LG support is optional and must not stop the app starting.
 */
fun installLgTv(context: Context, haClient: HaClient, log: (String) -> Unit = {}): LgTvRegistry {
    val configs = runCatching { RemoteSettings.lgTvs(context) }.getOrElse { emptyList() }
    val savePin: (String, String) -> Unit = { localId, pin ->
        runCatching {
            RemoteSettings.saveLgTvs(context, RemoteSettings.lgTvs(context).map { if (it.localId == localId) it.copy(pin = pin) else it })
        }
    }
    val registry = LgTvRegistry(configs, savePin, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("LG TV support failed to start: ${e.message}")
    }
    return registry
}
