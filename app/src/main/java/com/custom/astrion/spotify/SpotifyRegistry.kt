package com.custom.astrion.spotify

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SpotifyConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.HaTime
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.hue.HueEntities
import com.custom.astrion.zidoo.plainOf
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Spotify accounts added on the web page (1.6.2), without Home Assistant: each one a
 * `media_player.spotify_<name>` entity in [HaClient]'s local-entity overlay, whose state is read
 * from Spotify's Web API (every 3 s while it plays, every 10 s otherwise, nothing while the
 * remote's screen is off), and whose service calls, search and browse go to Spotify (see
 * [SpotifyProtocol]). The remote controls Spotify Connect devices: the sound plays on them. A
 * player that has Spotify among its apps but isn't listed (asleep, its Spotify closed) is a source
 * too: picking it opens Spotify on it, then moves the music there (see [SpotifyWake]).
 */
class SpotifyRegistry(
    configs: List<SpotifyConfig>,
    private val saveRefresh: (localId: String, refresh: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val configs = configs.filter { it.refreshToken.isNotBlank() && it.clientId.isNotBlank() }

    private inner class Account(val cfg: SpotifyConfig, val entityId: String) {
        val api = SpotifyApi(cfg.clientId, cfg.refreshToken) { saveRefresh(cfg.localId, it) }
        val executor: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(
                r,
                "Spotify-${cfg.name}"
            ).apply { isDaemon = true }
        }

        @Volatile var player: JsonObject? = null

        @Volatile var devices: List<SpotifyDevice> = emptyList()

        @Volatile var devicesAt = 0L

        @Volatile var lastDevice: String? = null

        /** The Spotify device each player became once woken, by its entity. */
        val woken = ConcurrentHashMap<String, String>()

        @Volatile var reachable = false

        @Volatile var saidAway = false

        @Volatile var generation = 0

        /** Polls again at once (after a command). */
        @Volatile var poller: Thread? = null
    }

    private val accounts = ArrayList<Account>()
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null
    private var previousBrowse: ((String, String?, String?) -> JsonObject?)? = null
    private var previousRequest: ((String, JsonObject) -> JsonObject?)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        val taken = HashSet<String>()
        for (cfg in configs) {
            var id = SPOTIFY_ENTITY + HueEntities.slug(cfg.name, "spotify")
            var n = 2
            while (!taken.add(id)) id = SPOTIFY_ENTITY + HueEntities.slug(cfg.name, "spotify") + "_" + n++
            accounts += Account(cfg, id)
        }
        if (accounts.isEmpty()) return
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        previousBrowse = haClient.localBrowseMediaHandler
        haClient.localBrowseMediaHandler = { entityId, contentId, contentType ->
            browse(entityId, contentId) ?: previousBrowse?.invoke(entityId, contentId, contentType)
        }
        previousRequest = haClient.localRequestHandler
        haClient.localRequestHandler = { type, fields -> request(type, fields) ?: previousRequest?.invoke(type, fields) }
        accounts.forEach { open(it) }
    }

    /** Screen off a while: no polling; on wake, read at once. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        accounts.forEach { a ->
            if (quiet) a.generation++ else open(a)
        }
    }

    fun stop() {
        stopped = true
        accounts.forEach {
            it.generation++
            it.poller?.interrupt()
            it.executor.shutdownNow()
        }
        client?.let { c ->
            c.setLocalEntities(emptyList(), accounts.map { it.entityId })
            if (accounts.isNotEmpty()) {
                c.localServiceHandler = previousHandler
                c.localBrowseMediaHandler = previousBrowse
                c.localRequestHandler = previousRequest
            }
        }
        accounts.clear()
    }

    /** For the web page: the entity, and whether Spotify answers. */
    fun status(localId: String): Pair<String, Boolean>? =
        accounts.firstOrNull { it.cfg.localId == localId }?.let { it.entityId to it.reachable }

    private fun open(a: Account) {
        if (stopped || quiet) return
        val generation = ++a.generation
        a.poller?.interrupt()
        a.poller = Thread({ poll(a, generation) }, "SpotifyPoll-${a.cfg.name}").apply {
            isDaemon = true
            start()
        }
    }

    private fun current(a: Account, generation: Int) = !stopped && !quiet && a.generation == generation

    private fun poll(a: Account, generation: Int) {
        while (current(a, generation)) {
            nap(once(a))
        }
    }

    /** One read; how long until the next: sooner while it plays, later after a failure. */
    private fun once(a: Account): Long = try {
        read(a)
        if ((a.player?.get("is_playing") as? JsonPrimitive)?.content == "true") PLAYING_MS else IDLE_MS
    } catch (e: Exception) {
        away(a, e.message ?: e.javaClass.simpleName)
        RETRY_MS
    }

    private fun nap(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            // A new generation took over, or the app stops.
        }
    }

    /** What plays, and the devices every half minute; then the entity. */
    private fun read(a: Account) {
        if (System.currentTimeMillis() - a.devicesAt > DEVICES_MS) {
            a.devices = SpotifyProtocol.devices(a.api.call(SpotifyCall("GET", "/me/player/devices")))
            a.devicesAt = System.currentTimeMillis()
        }
        a.player = a.api.call(SpotifyCall("GET", "/me/player"))
        (a.player?.get("device") as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.content }?.let { a.lastDevice = it }
        if (!a.reachable || a.saidAway) log("[${a.cfg.name}] connected to Spotify (${a.devices.size} devices)")
        a.reachable = true
        a.saidAway = false
        publish(a)
    }

    private fun away(a: Account, reason: String) {
        if (!a.saidAway) log("[${a.cfg.name}] Spotify didn't answer: $reason")
        a.saidAway = true
        a.reachable = false
        publish(a)
    }

    private fun publish(a: Account) {
        val c = client ?: return
        if (stopped) return
        val wakeable = SpotifyWake.wakeable(players(), a.devices, a.woken).map { it.name }
        val now = HaTime.isoInstant(System.currentTimeMillis())
        val (state, attrs) = SpotifyProtocol.entity(a.cfg.name, a.player, a.devices, now, wakeable)
        val entity =
            EntityState(
                entityId = a.entityId,
                state = if (a.reachable) state else "unavailable",
                attributes = JsonObject(attrs.mapValues { toJson(it.value) }),
                lastChanged = null,
                lastUpdated = System.currentTimeMillis().toString()
            )
        c.setLocalEntities(listOf(entity))
    }

    private fun handle(call: ServiceCall): Boolean {
        val a = accounts.firstOrNull { it.entityId == call.entityId } ?: return false
        if (call.domain != "media_player" && call.domain != "homeassistant") return false
        val data = call.data.mapValues { plainOf(it.value) }
        runCatching { a.executor.execute { run(a, call.service, data) } }
        return true
    }

    private fun run(a: Account, service: String, data: Map<String, Any?>) {
        val playing = (a.player?.get("is_playing") as? JsonPrimitive)?.content == "true"
        val volume = ((a.player?.get("device") as? JsonObject)?.get("volume_percent") as? JsonPrimitive)?.content?.toIntOrNull()
        if (service == "select_source" || a.devices.isEmpty()) refreshDevices(a)
        if (service == "select_source" && wakeFor(a, data["source"] as? String)) return
        val calls = SpotifyProtocol.command(service, data, playing, volume, a.devices, a.lastDevice)
        if (calls == null) {
            log("[${a.cfg.name}] unsupported service media_player.$service")
            return
        }
        try {
            calls.forEach { sendWithDevice(a, it) }
        } catch (e: Exception) {
            log("[${a.cfg.name}] Spotify refused $service: ${e.message}")
        }
        // Spotify takes a moment to say what changed.
        if (pause(AFTER_COMMAND_MS)) runCatching { read(a) }
    }

    /** No device active: the same call aimed at the last one used, once. */
    private fun sendWithDevice(a: Account, call: SpotifyCall) {
        try {
            a.api.call(call)
        } catch (e: SpotifyApi.ApiError) {
            val device = a.lastDevice ?: a.devices.firstOrNull()?.id
            if (e.reason != "NO_ACTIVE_DEVICE" || device == null || "device_id=" in call.path) throw e
            a.api.call(call.copy(path = call.path + (if ('?' in call.path) "&" else "?") + "device_id=" + device))
        }
    }

    /** The players the remote knows that can open Spotify (not Spotify players themselves). */
    private fun players(): List<SpotifyWake.Player> = client?.entities?.value?.values.orEmpty().mapNotNull { e ->
        if (e.domain != "media_player" || e.entityId.startsWith(SPOTIFY_ENTITY) || e.state == "unavailable") return@mapNotNull null
        SpotifyWake.spotifySource(e.attrStringList("source_list"))?.let { SpotifyWake.Player(e.entityId, e.friendlyName, e.state, it) }
    }

    /** [source] names a player to wake, not a Spotify device: woken (true), or not one (false). */
    private fun wakeFor(a: Account, source: String?): Boolean {
        if (source == null || a.devices.any { it.name.equals(source, ignoreCase = true) }) return false
        val player = SpotifyWake.wakeable(players(), a.devices, a.woken).firstOrNull { it.name == source } ?: return false
        wake(a, player)
        return true
    }

    /** Opens Spotify on [p] (turned on first when off), waits for Spotify to list it, moves the music there. */
    private fun wake(a: Account, p: SpotifyWake.Player) {
        log("[${a.cfg.name}] opening Spotify on ${p.name}")
        val before = a.devices.map { it.id }.toSet()
        if (SpotifyWake.asleep(p.state)) {
            service(ServiceCall("media_player", "turn_on", p.entityId))
            if (!pause(TURN_ON_MS)) return
        }
        service(ServiceCall.of("media_player", "select_source", p.entityId, "source" to p.spotifySource))
        val deadline = System.currentTimeMillis() + WAKE_MS
        while (System.currentTimeMillis() < deadline) {
            if (!pause(WAKE_STEP_MS)) return
            refreshDevices(a)
            val device = SpotifyWake.found(p, before, a.devices) ?: continue
            a.woken[p.entityId] = device.id
            try {
                a.api.call(SpotifyProtocol.transfer(device.id))
                log("[${a.cfg.name}] ${p.name} is ${device.name} for Spotify: playing there")
            } catch (e: Exception) {
                log("[${a.cfg.name}] Spotify refused to play on ${device.name}: ${e.message}")
            }
            if (pause(AFTER_COMMAND_MS)) runCatching { read(a) }
            return
        }
        log("[${a.cfg.name}] ${p.name} didn't show up in Spotify: is its Spotify app signed in to this account?")
    }

    /** A player's service call, made on the main thread as the cards make them. */
    private fun service(call: ServiceCall) {
        val c = client ?: return
        Handler(Looper.getMainLooper()).post { c.callService(call) }
    }

    /** Sleeps; false when interrupted (the app stops). */
    private fun pause(ms: Long): Boolean = try {
        Thread.sleep(ms)
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun refreshDevices(a: Account) {
        runCatching {
            a.devices = SpotifyProtocol.devices(a.api.call(SpotifyCall("GET", "/me/player/devices")))
            a.devicesAt = System.currentTimeMillis()
        }
    }

    /** `media_player/browse_media` on an account's entity: its root, playlists, recently played. */
    private fun browse(entityId: String, contentId: String?): JsonObject? {
        val a = accounts.firstOrNull { it.entityId == entityId } ?: return null
        val call = SpotifyProtocol.browseCall(contentId) ?: return SpotifyProtocol.root()
        val title = if (contentId == SpotifyProtocol.PLAYLISTS) "Playlists" else "Recently played"
        return runCatching { SpotifyProtocol.browseResult(contentId!!, title, a.api.call(call)) }
            .getOrElse {
                log("[${a.cfg.name}] Spotify browse failed: ${it.message}")
                null
            }
    }

    /** `media_player/search_media` on an account's entity, answered as Home Assistant would. */
    private fun request(type: String, fields: JsonObject): JsonObject? {
        if (type != "media_player/search_media") return null
        val entityId = (fields["entity_id"] as? JsonPrimitive)?.content ?: return null
        val a = accounts.firstOrNull { it.entityId == entityId } ?: return null
        val query = (fields["search_query"] as? JsonPrimitive)?.content.orEmpty()
        val filter = ((fields["media_filter_classes"] as? JsonArray)?.firstOrNull() as? JsonPrimitive)?.content
        return try {
            val result = SpotifyProtocol.searchResult(a.api.call(SpotifyProtocol.searchCall(query, filter)))
            JsonObject(mapOf("success" to JsonPrimitive(true), "result" to result))
        } catch (e: Exception) {
            log("[${a.cfg.name}] Spotify search failed: ${e.message}")
            JsonObject(mapOf("success" to JsonPrimitive(false)))
        }
    }

    private companion object {
        const val TAG = "Spotify"
        const val PLAYING_MS = 3_000L
        const val IDLE_MS = 10_000L
        const val RETRY_MS = 30_000L
        const val DEVICES_MS = 30_000L
        const val AFTER_COMMAND_MS = 600L
        const val TURN_ON_MS = 6_000L
        const val WAKE_MS = 25_000L
        const val WAKE_STEP_MS = 1_500L
        const val SPOTIFY_ENTITY = "media_player.spotify_"
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

/** Builds, starts and wires a [SpotifyRegistry]. Never throws: Spotify is optional. */
fun installSpotify(context: Context, haClient: HaClient, log: (String) -> Unit = {}): SpotifyRegistry {
    val configs = runCatching { RemoteSettings.spotifies(context) }.getOrElse { emptyList() }
    val save: (String, String) -> Unit = { localId, refresh ->
        runCatching {
            RemoteSettings.saveSpotifies(
                context,
                RemoteSettings.spotifies(context).map {
                    if (it.localId == localId) it.copy(refreshToken = refresh) else it
                }
            )
        }
    }
    val registry = SpotifyRegistry(configs, save, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Spotify failed to start: ${e.message}")
    }
    return registry
}
