package com.custom.astrion.samsung

import android.content.Context
import android.util.Log
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SamsungConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.zidoo.ZidooClient
import com.custom.astrion.zidoo.plainOf
import java.util.concurrent.ConcurrentHashMap
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
 * Samsung TVs added on the web page (1.5.3): each one's state read over its REST and UPnP services,
 * its keys and apps sent over its remote WebSocket (see [SamsungConnection]), published as a
 * `media_player` and a `remote` entity into [HaClient]'s local-entity overlay — like the LG TVs,
 * so cards, hotkeys and Activities drive a Samsung TV the same way with or without Home Assistant.
 *
 * The TV pushes nothing: it is polled every [ON_POLL_MS] when on, [OFF_POLL_MS] when off, and not at
 * all with the remote's screen off a while (quiet mode), when the WebSocket is closed too.
 */
class SamsungRegistry(
    configs: List<SamsungConfig>,
    private val saveKeys: (localId: String, token: String?, pin: String?) -> Unit = { _, _, _ -> },
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private class Device(val cfg: SamsungConfig, val spec: SamsungSpec, val executor: ScheduledExecutorService) {
        val rest = SamsungRest(cfg.host)

        @Volatile var state = SamsungState()

        @Volatile var conn: SamsungConnection? = null

        @Volatile var poll: ScheduledFuture<*>? = null

        @Volatile var token: String = cfg.token

        @Volatile var pin: String = cfg.pin

        /** Which connection is the current one: a late callback of an older one is ignored. */
        @Volatile var generation = 0

        /** Refused on the TV: not tried again until it's paired again. */
        @Volatile var denied = false
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()
    private val icons = ConcurrentHashMap<String, ByteArray>()
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val spec = SamsungSpec(cfg.localId, cfg.entityId, cfg.name, cfg.mac)
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Samsung-${cfg.localId}").apply { isDaemon = true } }
            val device = Device(cfg, spec, executor)
            devices[spec.entityId] = device
            byRemoteEntity[spec.remoteEntityId] = device
            publish(device)
            schedule(device, 0L)
        }
    }

    /** Screen off a while (quiet mode): not polled, the WebSocket closed; at once on wake. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        devices.values.forEach { d ->
            if (quiet) {
                d.poll?.cancel(false)
                runCatching { d.executor.execute { leave(d) } }
            } else {
                schedule(d, 0L)
            }
        }
    }

    /** An app's icon of TV [localId] (GET /samsung-icon/<localId>?id=…), asked to the TV once. */
    fun iconFor(localId: String, appId: String): ByteArray? {
        val key = "$localId|$appId"
        icons[key]?.let { return it }
        val device = devices.values.firstOrNull { it.cfg.localId == localId } ?: return null
        val path = device.state.apps.firstOrNull { it.id == appId }?.iconPath ?: return null
        val bytes = device.conn?.icon(path) ?: return null
        icons[key] = bytes
        return bytes
    }

    /** Each TV's name and its apps' icons (name → bytes), for the web page's icon import. */
    fun allIcons(): List<Pair<String, Map<String, ByteArray>>> = devices.values.map { d ->
        d.cfg.name to d.state.apps.mapNotNull { app -> iconFor(d.cfg.localId, app.id)?.let { app.name to it } }.toMap()
    }

    fun stop() {
        stopped = true
        devices.values.forEach {
            it.poll?.cancel(false)
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
        val asleep = quiet && delayMs > 0
        if (stopped || asleep) return
        device.poll?.cancel(false)
        device.poll = runCatching { device.executor.schedule({ pollOnce(device) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun pollOnce(device: Device) {
        val info = device.rest.info()
        val before = device.state
        if (info == null) {
            if (before.reachable) log("[${device.spec.name}] not answering (off or away)")
            device.conn?.close()
            device.conn = null
            update(device) { it.off() }
        } else {
            if (!before.reachable) log("[${device.spec.name}] answering")
            update(device) { it.copy(reachable = true, powerOn = info.powerOn, model = info.model ?: it.model, frame = info.frame) }
            if (device.conn == null && !device.denied) connect(device)
            if (device.state.on) readOn(device)
        }
        if (!quiet) schedule(device, if (device.state.on) ON_POLL_MS else OFF_POLL_MS)
    }

    /** The volume (UPnP), and whether the app last opened is still on screen. */
    private fun readOn(device: Device) {
        val volume = device.rest.volume()
        val muted = if (volume != null) device.rest.muted() else null
        val visible = device.state.lastApp?.let { device.rest.appVisible(it) } == true
        update(device) { it.copy(volume = volume, muted = muted, appVisible = visible) }
    }

    private fun connect(device: Device) {
        val generation = ++device.generation
        val conn =
            SamsungConnection(
                device.cfg.host,
                device.token.ifBlank {
                    null
                },
                device.pin.ifBlank { null },
                device.cfg.tls,
                listenerOf(device, generation)
            )
        device.conn = conn
        conn.connect()
    }

    private fun listenerOf(device: Device, generation: Int) = object : SamsungConnection.Listener {
        override fun onConnected(token: String?, pin: String?) {
            runCatching { device.executor.execute { if (generation == device.generation) connected(device, token, pin) } }
        }

        override fun onApps(apps: List<SamsungProtocol.App>) {
            if (apps.isNotEmpty()) runCatching { device.executor.execute { update(device) { it.copy(apps = apps) } } }
        }

        override fun onClosed(reason: String) {
            runCatching { device.executor.execute { if (generation == device.generation) closed(device, reason) } }
        }
    }

    private fun connected(device: Device, token: String?, pin: String?) {
        val newToken = token?.takeIf { it != device.token }
        val newPin = pin?.takeIf { device.pin.isBlank() }
        newToken?.let { device.token = it }
        newPin?.let { device.pin = it }
        if (newToken != null || newPin != null) saveKeys(device.cfg.localId, newToken, newPin)
        log("[${device.spec.name}] connected")
        update(device) { it.copy(connected = true) }
        if (device.state.apps.isEmpty()) {
            device.conn?.requestApps()
            // Recent TVs (2022 onwards) don't answer: the known apps are then looked for one by one.
            runCatching {
                device.executor.schedule(
                    { if (device.state.apps.isEmpty()) findApps(device) },
                    APPS_WAIT_MS,
                    TimeUnit.MILLISECONDS
                )
            }
        }
    }

    /** The known apps the TV has (see [SamsungProtocol.KNOWN_APPS]), asked over REST. */
    private fun findApps(device: Device) {
        val found =
            SamsungProtocol.KNOWN_APPS.mapNotNull { (name, ids) ->
                ids.firstNotNullOfOrNull { id -> device.rest.app(id, name) }
            }
        log("[${device.spec.name}] the TV gives no list of its apps: ${found.size} known apps found")
        if (found.isNotEmpty() && device.state.apps.isEmpty()) update(device) { it.copy(apps = found) }
    }

    private fun closed(device: Device, reason: String) {
        device.conn = null
        if (reason == "denied") {
            device.denied = true
            log("[${device.spec.name}] the remote was refused on the TV: add it again on the web page and allow it")
        } else if (device.state.connected) {
            log("[${device.spec.name}] disconnected: $reason")
        }
        update(device) { it.copy(connected = false) }
    }

    /** Closed on purpose (quiet mode): no "disconnected" in the log. */
    private fun leave(device: Device) {
        device.generation++
        device.conn?.close()
        device.conn = null
        update(device) { it.off() }
    }

    private fun update(device: Device, change: (SamsungState) -> SamsungState) {
        device.state = change(device.state)
        publish(device)
    }

    private fun publish(device: Device) {
        val c = client ?: return
        if (stopped) return
        c.setLocalEntity(toEntityState(SamsungEntityMapper.media(device.spec, device.state)))
        c.setLocalEntity(toEntityState(SamsungEntityMapper.remote(device.spec, device.state)))
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val data = call.data.mapValues { plainOf(it.value) }
        val (device, steps) =
            when (call.domain) {
                "media_player" -> devices[entityId]?.let { it to SamsungServices.media(call.service, data, it.state) }
                "remote" -> byRemoteEntity[entityId]?.let { it to SamsungServices.remote(call.service, data, it.state) }
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

    private fun perform(device: Device, steps: List<SamsungStep>) {
        steps.forEachIndexed { i, step ->
            if (i > 0) Thread.sleep(KEY_GAP_MS)
            run(device, step)
        }
        // See the result soon (on, off, volume, app) instead of at the next poll.
        schedule(device, AFTER_COMMAND_MS)
    }

    private fun run(device: Device, step: SamsungStep) {
        val c = device.conn
        when (step) {
            is SamsungStep.Key -> {
                if (c?.key(step.code) != true) log("[${device.spec.name}] not connected: ${step.code} not sent")
                if (step.code == "KEY_PLAY" || step.code == "KEY_PAUSE") update(device) { it.copy(paused = step.code == "KEY_PAUSE") }
            }
            is SamsungStep.Hold -> c?.hold(step.code, step.ms)
            is SamsungStep.Launch -> launch(device, step.app)
            is SamsungStep.Volume -> if (device.rest.setVolume(step.level)) update(device) { it.copy(volume = step.level) }
            is SamsungStep.Mute -> if (device.rest.setMute(step.muted)) update(device) { it.copy(muted = step.muted) }
            SamsungStep.Wake -> wake(device)
        }
    }

    /** An app the TV listed opens over its WebSocket; one found over REST, or any id, over REST first. */
    private fun launch(device: Device, app: SamsungProtocol.App) {
        val c = device.conn
        val sent = app.type != SamsungProtocol.REST_APP && c?.launch(app) == true
        if (!sent && !device.rest.launch(app.id)) c?.launch(app)
        update(device) { it.copy(lastApp = app.id, appVisible = true) }
    }

    /** Wake-on-LAN, then looks every few seconds while it starts. */
    private fun wake(device: Device) {
        val mac = device.spec.mac
        if (mac.isBlank()) {
            log("[${device.spec.name}] can't turn it on: no MAC address (add it again with the TV on)")
            return
        }
        repeat(WAKE_PACKETS) { ZidooClient.wake(mac) }
        schedule(device, AFTER_WAKE_MS)
    }

    private fun toEntityState(data: SamsungEntityData) = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    private companion object {
        const val TAG = "SamsungRegistry"
        const val ON_POLL_MS = 5_000L
        const val OFF_POLL_MS = 15_000L
        const val AFTER_COMMAND_MS = 1_500L
        const val AFTER_WAKE_MS = 4_000L
        const val KEY_GAP_MS = 80L
        const val WAKE_PACKETS = 3
        const val APPS_WAIT_MS = 5_000L
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
 * Builds, starts and wires a [SamsungRegistry] — after the Sky one, whose service handler it chains
 * to. Never throws: Samsung support is optional and must not stop the app starting.
 */
fun installSamsung(context: Context, haClient: HaClient, log: (String) -> Unit = {}): SamsungRegistry {
    val configs = runCatching { RemoteSettings.samsungs(context) }.getOrElse { emptyList() }
    val saveKeys: (String, String?, String?) -> Unit = { localId, token, pin ->
        runCatching {
            RemoteSettings.saveSamsungs(
                context,
                RemoteSettings.samsungs(context).map {
                    if (it.localId == localId) it.copy(token = token ?: it.token, pin = pin ?: it.pin) else it
                }
            )
        }
    }
    val registry = SamsungRegistry(configs, saveKeys, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Samsung TV support failed to start: ${e.message}")
    }
    return registry
}
