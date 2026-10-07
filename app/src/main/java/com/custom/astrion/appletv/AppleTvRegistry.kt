package com.custom.astrion.appletv

import android.content.Context
import android.util.Log
import com.custom.astrion.R
import com.custom.astrion.appletv.airplay.AirPlayPairing
import com.custom.astrion.appletv.mrp.MrpPairing
import com.custom.astrion.config.AppleTvConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.web.ConfigServer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * Owns one [AppleTvDevice] per configured Apple TV, publishes each as a
 * `media_player` entity into [HaClient]'s local-entity overlay, routes
 * service calls aimed at those entities back to the right device, and runs
 * the PIN-pairing sessions behind the web configurator.
 *
 * Service calls understood, all through the ordinary [HaClient.callService]:
 *  - `media_player.*` targeting an Apple TV's entity id (turn_on/off,
 *    media_play_pause, media_next_track, volume_up/down/set, select_source, ...)
 *  - `astrion_appletv.send_command` with `entity_id` and `command`
 *    (Up/Down/Left/Right/Select/Menu/Home/Play/Pause/...), used by the
 *    Apple TV remote card.
 */
class AppleTvRegistry(context: Context, configs: List<AppleTvConfig>, private val log: (String) -> Unit = { Log.d(TAG, it) }) {
    companion object {
        private const val TAG = "AppleTvRegistry"
        const val REMOTE_DOMAIN = "astrion_appletv"
        private const val PAIRING_TTL_MS = 3 * 60_000L
    }

    private val appContext = context.applicationContext
    private val discovery = AppleTvDiscovery(context)
    private val configs = configs.filter { AppleTvCredentials.parse(it.credentials) != null }
    private val devices = LinkedHashMap<String, AppleTvDevice>() // by entityId
    private var client: HaClient? = null

    private class PairingSession(val pairing: CompanionPairing, val createdAt: Long = System.currentTimeMillis())
    private class MrpPairingSession(val pairing: HapPinPairing, val createdAt: Long = System.currentTimeMillis())

    private val sessions = ConcurrentHashMap<String, PairingSession>()
    private val mrpSessions = ConcurrentHashMap<String, MrpPairingSession>()

    /** Starts every configured device, publishing into [haClient] and taking over its Apple TV service calls. */
    fun start(haClient: HaClient) {
        client = haClient
        haClient.localServiceHandler = { call -> handle(call) }
        // contentType is always "app" for anything this registry itself ever hands back (see
        // [browse]'s doc comment), so it's not needed to decide what to serve.
        haClient.localBrowseMediaHandler = { entityId, contentId, _ -> browse(entityId, contentId) }
        for (cfg in configs) {
            val credentials = AppleTvCredentials.parse(cfg.credentials) ?: continue
            val mrpCredentials = AppleTvCredentials.parse(cfg.mrpCredentials)
            val mrpTransport = if (cfg.mrpTransport == "classic") MrpTransport.Classic else MrpTransport.AirPlayTunnel
            val mrp = mrpCredentials?.let { MrpSpec(cfg.mrpHost, cfg.mrpPort, cfg.mrpServiceName, it, mrpTransport) }
            val spec = AppleTvSpec(cfg.localId, cfg.entityId, cfg.name, cfg.host, cfg.port, cfg.serviceName, credentials, mrp)
            val device =
                AppleTvDevice(
                    spec = spec,
                    resolver = discovery,
                    onEntity = { data -> haClient.setLocalEntity(toEntityState(data)) },
                    onEndpointChanged = { endpoint -> persistEndpoint(cfg, endpoint) },
                    onMrpEndpointChanged = { endpoint -> persistMrpEndpoint(cfg, endpoint) },
                    log = log
                )
            devices[cfg.entityId] = device
            device.start()
        }
    }

    /** An Apple TV's Companion port/IP changed (they're dynamic) — remember the new address as the fallback for next time. */
    private fun persistEndpoint(cfg: AppleTvConfig, endpoint: AppleTvEndpoint) {
        val updated =
            RemoteSettings.appleTvs(appContext).map {
                if (it.localId == cfg.localId) it.copy(host = endpoint.host, port = endpoint.port) else it
            }
        RemoteSettings.saveAppleTvs(appContext, updated)
    }

    /** Same, for the separate MRP endpoint. */
    private fun persistMrpEndpoint(cfg: AppleTvConfig, endpoint: AppleTvEndpoint) {
        val updated =
            RemoteSettings.appleTvs(appContext).map {
                if (it.localId == cfg.localId) it.copy(mrpHost = endpoint.host, mrpPort = endpoint.port) else it
            }
        RemoteSettings.saveAppleTvs(appContext, updated)
    }

    fun stop() {
        devices.values.forEach { it.stop() }
        client?.let { c ->
            devices.keys.forEach { c.removeLocalEntity(it) }
            c.localServiceHandler = null
            c.localBrowseMediaHandler = null
        }
        devices.clear()
        sessions.values.forEach { runCatching { it.pairing.close() } }
        sessions.clear()
        mrpSessions.values.forEach { runCatching { it.pairing.close() } }
        mrpSessions.clear()
    }

    val isEmpty: Boolean get() = devices.isEmpty()

    /** Lookup by entity id, local id or (case-insensitive) name. */
    private fun device(ref: String?): AppleTvDevice? {
        if (ref.isNullOrBlank()) return devices.values.firstOrNull()
        devices[ref]?.let { return it }
        val cfg = configs.firstOrNull { it.localId == ref || it.name.equals(ref, ignoreCase = true) } ?: return null
        return devices[cfg.entityId]
    }

    /** Sends a named remote command (see [AppleTvDevice.sendCommand]) to the Apple TV identified by [ref]. */
    fun sendCommand(ref: String?, command: String): Boolean = device(ref)?.sendCommand(command) ?: false

    /** The current artwork JPEG for the Apple TV identified by [ref] (its `entity_picture`
     * attribute points here), or null if MRP hasn't fetched one. */
    fun artworkFor(ref: String?): ByteArray? = device(ref)?.snapshotArtwork

    private fun handle(call: ServiceCall): Boolean {
        if (call.domain == REMOTE_DOMAIN) {
            if (call.service == "send_command") {
                val command = plainValue(call.data["command"]) as? String
                val target = device(call.entityId ?: plainValue(call.data["device"]) as? String)
                if (command != null) target?.sendCommand(command)
            }
            return true
        }
        val entityId = call.entityId ?: return false
        val target = devices[entityId] ?: return false
        if (call.domain == "media_player") {
            val plain = call.data.mapValues { plainValue(it.value) }
            if (!target.handleService(call.service, plain)) log("[$entityId] unsupported service ${call.service}")
        }
        // Consumed even when unsupported: forwarding an Apple TV entity id to Home Assistant could only fail.
        return true
    }

    /**
     * [HaClient.localBrowseMediaHandler]: serves a directly-paired Apple TV's installed-apps list
     * (its `source_list`, really — see [AppleTvDevice.snapshotApps]) in the same shape
     * `media_player/browse_media` would, so [com.custom.astrion.cards.impl.MediaBrowser] can
     * browse and launch them with no Home Assistant involved at all. The Companion protocol has
     * no deeper hierarchy under "installed apps" — `FetchLaunchableApplicationsEvent` is a flat
     * bundle-id -> name map — so any [contentId] other than the root (meaning: a card tried to
     * drill into one of these "app" items, which are always marked non-expandable so that
     * shouldn't happen in practice) comes back with no children rather than erroring.
     */
    private fun browse(entityId: String, contentId: String?): JsonObject? {
        val target = devices[entityId] ?: return null
        if (contentId != null) return JsonObject(mapOf("children" to JsonArray(emptyList())))
        val children =
            target.snapshotApps.entries
                .sortedBy { it.value.lowercase() }
                .map { (bundleId, name) ->
                    JsonObject(
                        mapOf(
                            "title" to JsonPrimitive(name),
                            "media_content_id" to JsonPrimitive(bundleId),
                            "media_content_type" to JsonPrimitive("app"),
                            "can_expand" to JsonPrimitive(false),
                            "can_play" to JsonPrimitive(false)
                        )
                    )
                }
        return JsonObject(
            mapOf(
                "title" to JsonPrimitive(appContext.getString(R.string.media_apps_title)),
                "children" to JsonArray(children)
            )
        )
    }

    // ---- discovery / pairing (used by the web configurator) -------------------

    fun scan(): List<DiscoveredAppleTv> = discovery.scan(AppleTvServiceKind.Companion)

    /**
     * Step 2 (optional): browses for whatever now-playing-info service this Apple TV actually
     * offers, once Companion is already paired — see [beginMrpPairing]. Tries the modern AirPlay
     * tunnel first (what every tvOS >= 15 device needs) and falls back to the classic standalone
     * MRP service for anything older that still advertises it; both kinds are returned (tagged via
     * [DiscoveredAppleTv.kind]) since there's no way to know which one a given TV supports before
     * scanning for both.
     */
    fun scanMrp(): List<DiscoveredAppleTv> = discovery.scan(AppleTvServiceKind.AirPlay) + discovery.scan(AppleTvServiceKind.Mrp)

    /** Starts pair-setup with an Apple TV (it shows a PIN on screen) and returns a session id for [finishPairing]. */
    fun beginPairing(host: String, port: Int): String {
        expireSessions()
        val pairing = CompanionPairing(host, port)
        try {
            pairing.begin()
        } catch (e: Exception) {
            runCatching { pairing.close() }
            throw e
        }
        val id = UUID.randomUUID().toString()
        sessions[id] = PairingSession(pairing)
        return id
    }

    /** Completes pairing with the PIN read off the TV; the session is single-use. */
    fun finishPairing(sessionId: String, pin: String): AppleTvCredentials {
        val session = sessions.remove(sessionId) ?: throw CompanionException("Pairing session expired — start again")
        try {
            return session.pairing.finish(pin)
        } finally {
            runCatching { session.pairing.close() }
        }
    }

    /**
     * Now-playing-info PIN pairing — the Apple TV shows a second, separate code for this. Same
     * session/TTL pattern as Companion's. [transport] picks which handshake to actually run
     * ("airplay", the modern default, or "classic" for a pre-tvOS-15 device) — it must match
     * whichever [DiscoveredAppleTv.kind] the person picked from [scanMrp]'s results, since the two
     * speak entirely different protocols to entirely different ports.
     */
    fun beginMrpPairing(host: String, port: Int, transport: String): String {
        expireSessions()
        val pairing: HapPinPairing = if (transport == "classic") MrpPairing(host, port) else AirPlayPairing(host, port)
        try {
            pairing.begin()
        } catch (e: Exception) {
            runCatching { pairing.close() }
            throw e
        }
        val id = UUID.randomUUID().toString()
        mrpSessions[id] = MrpPairingSession(pairing)
        return id
    }

    fun finishMrpPairing(sessionId: String, pin: String): AppleTvCredentials {
        val session = mrpSessions.remove(sessionId) ?: throw CompanionException("Pairing session expired — start again")
        try {
            return session.pairing.finish(pin)
        } finally {
            runCatching { session.pairing.close() }
        }
    }

    private fun expireSessions() {
        val now = System.currentTimeMillis()
        sessions.entries.removeIf { (_, s) ->
            (now - s.createdAt > PAIRING_TTL_MS).also { if (it) runCatching { s.pairing.close() } }
        }
        mrpSessions.entries.removeIf { (_, s) ->
            (now - s.createdAt > PAIRING_TTL_MS).also { if (it) runCatching { s.pairing.close() } }
        }
    }

    private fun toEntityState(data: AppleTvEntityData): EntityState = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { appleTvAttributeToJson(it.value) }),
        lastChanged = null,
        lastUpdated = null
    )
}

// ---- JSON conversion (stateless, kept outside the class so it doesn't count against
// AppleTvRegistry's own member count) -----------------------------------------------

/**
 * Builds, starts and wires an [AppleTvRegistry] into [haClient] and [configServer] in one call —
 * kept as a free function (rather than inline in `MainActivity`) so wiring in a new kind of directly
 * driven device stays a one-line change at the call site.
 *
 * Never throws: Apple TV support is optional, and a problem with it (missing system service on some
 * device, corrupt saved config, ...) must never be able to stop the rest of the app from starting.
 * Anything that goes wrong here is logged and leaves Apple TV support inert rather than crashing.
 */
fun installAppleTv(context: Context, haClient: HaClient, configServer: ConfigServer, log: (String) -> Unit = {}): AppleTvRegistry {
    val configs =
        try {
            RemoteSettings.appleTvs(context)
        } catch (e: Exception) {
            log("Could not read saved Apple TVs: ${e.message}")
            emptyList()
        }
    val registry =
        try {
            AppleTvRegistry(context, configs, log)
        } catch (e: Exception) {
            log("Apple TV support unavailable on this device: ${e.message}")
            AppleTvRegistry(context, emptyList(), log)
        }
    try {
        registry.start(haClient)
        configServer.appleTvRegistry = registry
    } catch (e: Exception) {
        log("Apple TV support failed to start: ${e.message}")
    }
    return registry
}

private fun appleTvAttributeToJson(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is Boolean -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is String -> JsonPrimitive(value)
    is Iterable<*> -> JsonArray(value.map { appleTvAttributeToJson(it) })
    else -> JsonPrimitive(value.toString())
}

private fun plainValue(el: JsonElement?): Any? = when (el) {
    null, JsonNull -> null
    is JsonPrimitive -> if (el.isString) el.content else el.booleanOrNull ?: el.doubleOrNull ?: el.content
    else -> el.toString()
}
