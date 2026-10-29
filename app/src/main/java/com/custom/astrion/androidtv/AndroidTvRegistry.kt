package com.custom.astrion.androidtv

import android.content.Context
import android.util.Log
import com.custom.astrion.config.AndroidTvConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.web.ConfigServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

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

    private class Device(val spec: AndroidTvSpec, val connection: AndroidTvConnection, val helper: AndroidTvHelperClient?) {
        @Volatile var helperState = AndroidTvHelperState()

        @Volatile var artwork: ByteArray? = null
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()
    private val hosts = ConcurrentHashMap<String, String>() // localId -> current host
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "AndroidTv-send").apply { isDaemon = true } }
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
            device = Device(spec, connection, helper)
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
            it.helper?.stop()
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
        action.keys.forEachIndexed { i, key ->
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
