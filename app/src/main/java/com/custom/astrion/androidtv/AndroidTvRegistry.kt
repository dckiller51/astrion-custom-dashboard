package com.custom.astrion.androidtv

import android.content.Context
import android.util.Log
import com.custom.astrion.config.AndroidTvConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.web.ConfigServer
import com.custom.astrion.zidoo.ZidooClient
import java.io.IOException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Android TVs paired directly (1.3.6): one [AndroidTvConnection] per TV, each published as a
 * `media_player` and a `remote` entity into [HaClient]'s local-entity overlay, and the service
 * calls aimed at those entities answered here — so cards, hotkeys and Activities drive a TV the
 * same way with or without Home Assistant. Also runs the pairing sessions of the web page.
 */
class AndroidTvRegistry(context: Context, configs: List<AndroidTvConfig>, private val log: (String) -> Unit = { Log.d(TAG, it) }) {
    private val appContext = context.applicationContext
    private val discovery = AndroidTvDiscovery(context)
    private val configs = configs.filter { it.certSha256.isNotBlank() }

    private class Device(
        val spec: AndroidTvSpec,
        val connection: AndroidTvConnection,
        val helper: AndroidTvHelperClient?,
        /** For Wake-on-LAN (1.5.7), from the TV's certificate. */
        val mac: String = "",
        /** The ESP32 that wakes it over Bluetooth (1.6.0), blank without one. */
        val bleWake: String = ""
    ) {
        @Volatile var helperState = AndroidTvHelperState()

        @Volatile var artwork: ByteArray? = null
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()
    private val hosts = ConcurrentHashMap<String, String>() // localId -> current host
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "AndroidTv-send").apply { isDaemon = true } }
    private val http =
        OkHttpClient
            .Builder()
            .connectTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .readTimeout(HTTP_TIMEOUT_S, TimeUnit.SECONDS)
            .build()
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    private class PairingSession(val pairing: AndroidTvPairing, val createdAt: Long = System.currentTimeMillis())

    private val sessions = ConcurrentHashMap<String, PairingSession>()

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        val identity = AndroidTvIdentity.parse(RemoteSettings.androidTvIdentity(appContext))
        if (identity == null) {
            if (configs.isNotEmpty()) log("No Android TV identity stored: pair the TVs again")
            return
        }
        for (cfg in configs) {
            val spec = AndroidTvSpec(cfg.localId, cfg.entityId, cfg.name)
            hosts[cfg.localId] = cfg.host
            lateinit var device: Device
            val host = { hosts[cfg.localId] ?: cfg.host }
            val connection =
                AndroidTvConnection(
                    host = host,
                    identity = identity,
                    certSha256 = cfg.certSha256,
                    onState = { publish(device) },
                    log = { log("[${cfg.name}] $it") },
                    onConnectFailed = { failures -> if (failures % REFIND_EVERY == 0) refind(cfg) }
                )
            val helper =
                cfg.helperCode.takeIf { it.isNotBlank() }?.let { code ->
                    AndroidTvHelperClient(
                        host = host,
                        code = code,
                        onUpdate = { link, info, art ->
                            device.helperState = AndroidTvHelperState(link, info)
                            device.artwork = art
                            publish(device)
                        },
                        log = { log("[${cfg.name}] $it") }
                    )
                }
            device = Device(spec, connection, helper, cfg.mac, cfg.bleWake)
            devices[spec.entityId] = device
            byRemoteEntity[spec.remoteEntityId] = device
            publish(device)
            connection.start()
            helper?.start()
        }
    }

    /**
     * Quiet (screen off a while, like Home Assistant's quiet mode — 1.4.0): the TV Helpers' long
     * polls stop. During playback the helper answers every few seconds (the position moves), which
     * kept the remote's Wi-Fi and processor awake all night for nobody. Back on with the screen.
     * The TV connections themselves stay (they only talk when something happens).
     */
    fun setQuiet(quiet: Boolean) {
        // A TV that doesn't answer is tried every 10 minutes while quiet, at once on wake (1.4.1).
        devices.values.forEach { it.connection.setQuiet(quiet) }
        val helpers = devices.values.mapNotNull { it.helper }
        if (helpers.isEmpty()) return
        log(if (quiet) "quiet: TV Helper polling paused" else "awake: TV Helper polling resumed")
        helpers.forEach { if (quiet) it.stop() else it.start() }
    }

    fun stop() {
        devices.values.forEach {
            it.connection.stop()
            it.helper?.shutdown()
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
        sessions.values.forEach { runCatching { it.pairing.close() } }
        sessions.clear()
        sender.shutdownNow()
    }

    private fun publish(device: Device) {
        val c = client ?: return
        val state = device.connection.state
        c.setLocalEntity(toEntityState(AndroidTvEntityMapper.media(device.spec, state, device.helperState)))
        c.setLocalEntity(toEntityState(AndroidTvEntityMapper.remote(device.spec, state)))
    }

    /** The cover the helper sent for the TV [localId] (its `entity_picture` points here). */
    fun artworkFor(localId: String): ByteArray? = devices.values.firstOrNull { it.spec.localId == localId }?.artwork

    /** The TV's IP may have changed (DHCP): look it up again by its mDNS name and keep the new one. */
    private fun refind(cfg: AndroidTvConfig) {
        if (cfg.serviceName.isBlank()) return
        val host = discovery.resolve(cfg.serviceName) ?: return
        if (host == hosts[cfg.localId]) return
        log("[${cfg.name}] found again at $host")
        hosts[cfg.localId] = host
        val updated = RemoteSettings.androidTvs(appContext).map { if (it.localId == cfg.localId) it.copy(host = host) else it }
        RemoteSettings.saveAndroidTvs(appContext, updated)
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val data = call.data.mapValues { plainValue(it.value) }
        val (device, action) =
            when (call.domain) {
                "media_player" -> devices[entityId]?.let { it to AndroidTvServices.media(call.service, data, it.connection.state) }
                "remote" -> byRemoteEntity[entityId]?.let { it to AndroidTvServices.remote(call.service, data, it.connection.state) }
                else -> null
            } ?: return false
        if (action == null) {
            log("[${device.spec.name}] unsupported service ${call.domain}.${call.service}")
        } else {
            sender.execute { perform(device, action, data) }
        }
        // Consumed even when unsupported: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(device: Device, action: AndroidTvServices.Action, data: Map<String, Any?>) {
        val connection = device.connection
        // Woken (over Bluetooth or by the helper): POWER would turn it straight back off, WAKEUP is useless.
        val woken = wakeOverBluetooth(device, action.wake) || wakeScreen(device, action.wake)
        val keys = if (woken) action.keys.filterNot { it in POWER_KEYS } else action.keys
        keys.forEachIndexed { i, key ->
            if (i > 0) Thread.sleep(KEY_GAP_MS)
            if (action.longPress) {
                val holdMs = (((data["hold_secs"] as? Number)?.toDouble() ?: 0.0) * MS_PER_SECOND).toLong()
                connection.sendKey(key, RemoteMessages.DIRECTION_START_LONG)
                Thread.sleep(holdMs.coerceIn(MIN_HOLD_MS, MAX_HOLD_MS))
                connection.sendKey(key, RemoteMessages.DIRECTION_END_LONG)
            } else {
                connection.sendKey(key)
            }
        }
        action.launch?.let { app ->
            // The helper opens any installed app; the protocol only what Google still allows (see linkFor).
            val viaHelper = !app.contains("://") && device.helper?.launch(app) == true
            if (!viaHelper) connection.launch(app)
        }
        action.seekMs?.let { ms ->
            if (device.helper?.media("seek", ms) != true) log("[${device.spec.name}] seeking needs the Astrion TV Helper")
        }
    }

    /**
     * Wakes a TV whose Wi-Fi sleeps (Xiaomi TVs and boxes in deep standby, 1.6.0) through the ESP32
     * set for it: it sends the TV's remote's Bluetooth wake signal. Then waits for the TV to answer
     * on the network and say it's on. A TV already on is left alone (POWER turns it off). True when
     * the TV is on thanks to it.
     */
    private fun wakeOverBluetooth(device: Device, wake: AndroidTvServices.Wake): Boolean {
        val host = device.bleWake.takeIf { it.isNotBlank() } ?: return false
        if (wake == AndroidTvServices.Wake.NONE) return false
        val state = device.connection.state
        if (state.connected && state.isOn == true) return false
        if (wake == AndroidTvServices.Wake.TOGGLE && device.helper?.screenOn() == true) return false
        if (!pressBleWake(device, host)) return false
        log("[${device.spec.name}] Bluetooth wake sent by the ESP32 $host")
        val start = System.currentTimeMillis()
        while (System.currentTimeMillis() - start < BLE_WAKE_WINDOW_MS) {
            Thread.sleep(BLE_WAKE_POLL_MS)
            val s = device.connection.state
            if (s.connected && s.isOn == true) {
                log("[${device.spec.name}] on after ${System.currentTimeMillis() - start} ms")
                return true
            }
            device.connection.retryNow()
        }
        log("[${device.spec.name}] Bluetooth wake: no answer on the network after ${BLE_WAKE_WINDOW_MS / MS_PER_SECOND} s")
        return false
    }

    /** Presses the ESP32's "Wake the TV" button (ESPHome's web server); false when it didn't take it. */
    private fun pressBleWake(device: Device, host: String): Boolean {
        val url = BleWake.url(host)
        return try {
            val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody(null)).build()
            http.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) log("[${device.spec.name}] Bluetooth wake: $url answered ${resp.code}")
                resp.isSuccessful
            }
        } catch (e: IOException) {
            log("[${device.spec.name}] Bluetooth wake: ESP32 $host not reached (${e.message})")
            false
        }
    }

    /**
     * Turns the screen on through the Astrion TV Helper (1.5.7), before the keys. Some boxes (Xiaomi
     * Mi Box S) sleep so deeply that the protocol's POWER only lights their LED: their own remote is
     * a real Bluetooth key, ours isn't taken as one. Wake-on-LAN brings the box up for a few
     * seconds, the helper answers again, and turns the screen on as a user would. A POWER while the
     * screen is on is left alone (it turns the TV off). True when the screen is on thanks to it.
     */
    private fun wakeScreen(device: Device, wake: AndroidTvServices.Wake): Boolean {
        val helper = device.helper ?: return false
        if (wake == AndroidTvServices.Wake.NONE) return false
        if (alreadyAwake(device, helper, wake)) return false
        // One Wake-on-LAN packet keeps a Mi Box S up for about a second, too short for the helper:
        // packets every WOL_EVERY_MS keep it up until the helper has turned the screen on.
        val burst = device.mac.takeIf { it.isNotBlank() }?.let { WolBurst(it).apply { start() } }
        if (burst != null) {
            log("[${device.spec.name}] Wake-on-LAN to ${device.mac}, every $WOL_EVERY_MS ms")
        } else {
            log("[${device.spec.name}] no MAC for Wake-on-LAN (web page › Android TV › Wake-on-LAN)")
        }
        try {
            val start = System.currentTimeMillis()
            val deadline = start + WAKE_WINDOW_MS

            fun woke(how: String, on: Boolean): Boolean {
                val took = System.currentTimeMillis() - start
                log("[${device.spec.name}] helper wake$how: screen ${if (on) "on" else "still off"} after $took ms")
                return on
            }
            // The wake channel first (helper 1.1.2): the only way that fits a one-second wake.
            helper.wakeViaChannel(CHANNEL_WAIT_MS)?.let { return woke(" (channel)", it) }
            while (true) {
                helper.wake()?.let { return woke("", it) }
                if (System.currentTimeMillis() >= deadline) {
                    log("[${device.spec.name}] helper wake: no answer (asleep, Wake-on-LAN off, or no helper 1.1.0)")
                    return false
                }
                Thread.sleep(WAKE_RETRY_MS)
            }
        } finally {
            burst?.running = false
        }
    }

    /**
     * Whether the box is awake, so no waking (POWER then turns it off as usual): the protocol's
     * link is up and says on, or (for POWER) the helper says the screen is on. Asking the helper
     * alone wasn't enough: when it didn't answer in time, a box that was on got "woken" and
     * POWER was dropped (Xavier: Power did nothing).
     */
    private fun alreadyAwake(device: Device, helper: AndroidTvHelperClient, wake: AndroidTvServices.Wake): Boolean {
        val state = device.connection.state
        if (state.connected && state.isOn == true) return true
        return wake == AndroidTvServices.Wake.TOGGLE && helper.screenOn() == true
    }

    /** Wake-on-LAN packets for [mac] every [WOL_EVERY_MS] until [running] is cleared. */
    private class WolBurst(private val mac: String) : Thread("AndroidTv-wol") {
        @Volatile var running = true

        init {
            isDaemon = true
        }

        override fun run() {
            while (running) {
                ZidooClient.wake(mac)
                runCatching { Thread.sleep(WOL_EVERY_MS) }.onFailure { return }
            }
        }
    }

    // ---- web page: scan and pairing ------------------------------------------------------------

    fun scan(): List<DiscoveredAndroidTv> = discovery.scan()

    /** Makes the TV at [host] show a code; returns the session id for [finishPairing]. Slow the first time (key generation). */
    fun beginPairing(host: String): String {
        expireSessions()
        val pairing = AndroidTvPairing(host, identity())
        pairing.begin()
        val id = UUID.randomUUID().toString()
        sessions[id] = PairingSession(pairing)
        return id
    }

    fun finishPairing(sessionId: String, code: String): PairedAndroidTv {
        val session = sessions.remove(sessionId) ?: throw AndroidTvPairingException("Pairing session expired: start again")
        return session.pairing.finish(code)
    }

    /** This remote's identity, made and stored the first time a TV is paired. */
    @Synchronized
    private fun identity(): AndroidTvIdentity = AndroidTvIdentity.parse(RemoteSettings.androidTvIdentity(appContext))
        ?: AndroidTvIdentity.generate().also { RemoteSettings.saveAndroidTvIdentity(appContext, it.serialize()) }

    private fun expireSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, s) -> (now - s.createdAt > PAIRING_TTL_MS).also { expired -> if (expired) s.pairing.close() } }
    }

    private fun toEntityState(data: AndroidTvEntityData) = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    companion object {
        private const val TAG = "AndroidTvRegistry"
        private const val PAIRING_TTL_MS = 3 * 60_000L
        private const val REFIND_EVERY = 3
        private const val KEY_GAP_MS = 60L
        private const val WOL_EVERY_MS = 250L

        // 1.6.0: 25 s, not 12: after a long sleep the box's LED lights at the first packets but its
        // Wi-Fi takes longer to come back; with 12 s the burst stopped first and it fell asleep
        // again (Zavie: LED on then off; a second Power, the box already back, worked).
        private const val WAKE_WINDOW_MS = 25_000L
        private const val CHANNEL_WAIT_MS = 8_000
        private const val WAKE_RETRY_MS = 200L

        // 1.6.0: a Xiaomi TV woken over Bluetooth takes a few seconds to get its Wi-Fi back.
        private const val BLE_WAKE_WINDOW_MS = 20_000L
        private const val BLE_WAKE_POLL_MS = 1_000L
        private const val HTTP_TIMEOUT_S = 4L
        private val POWER_KEYS = listOfNotNull(AndroidTvKeys.code("POWER"), AndroidTvKeys.code("WAKEUP"))
        private const val MS_PER_SECOND = 1000
        private const val MIN_HOLD_MS = 300L
        private const val MAX_HOLD_MS = 10_000L
    }
}

private fun toJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map { toJson(it) })
    else -> JsonPrimitive(value.toString())
}

private fun plainValue(el: JsonElement?): Any? = when (el) {
    null, JsonNull -> null
    is JsonPrimitive -> if (el.isString) el.content else el.booleanOrNull ?: el.doubleOrNull ?: el.content
    is JsonArray -> el.map { plainValue(it) }
    else -> el.toString()
}

/**
 * Builds, starts and wires an [AndroidTvRegistry] — after the Apple TV one, whose service handler
 * it chains to. Never throws: Android TV support is optional and must not stop the app starting.
 */
fun installAndroidTv(context: Context, haClient: HaClient, configServer: ConfigServer, log: (String) -> Unit = {}): AndroidTvRegistry {
    val configs = runCatching { RemoteSettings.androidTvs(context) }.getOrElse { emptyList() }
    val registry = AndroidTvRegistry(context, configs, log)
    try {
        registry.start(haClient)
        configServer.androidTvRegistry = registry
    } catch (e: Exception) {
        log("Android TV support failed to start: ${e.message}")
    }
    return registry
}
