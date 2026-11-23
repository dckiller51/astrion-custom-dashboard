package com.custom.astrion.playnite

import android.content.Context
import android.util.Log
import com.custom.astrion.config.PlayniteConfig
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.zidoo.ZidooClient
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PCs running Playnite with the Astrion Custom Dashboard extension, added on the web page (1.6.3): each one
 * published as a `media_player` into [HaClient]'s local-entity overlay (the game running, with its
 * cover; the games as sources), and the service calls aimed at it answered here: a source starts
 * the game, turn_on wakes the PC (Wake-on-LAN), turn_off shuts it down. Polled while the screen is
 * on, like the other direct devices.
 */
class PlayniteRegistry(
    configs: List<PlayniteConfig>,
    /** Where each PC's games are kept, to offer them while it's off (1.6.2); null: not kept. */
    private val cacheDir: File? = null,
    /** The PC gave another MAC address (a better card for Wake-on-LAN): saved by the caller. */
    private val onMac: (localId: String, mac: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private class Device(val spec: PlayniteSpec, mac: String, val client: PlayniteClient, val executor: ScheduledExecutorService) {
        @Volatile var mac = mac

        @Volatile var state = PlayniteState()

        @Volatile var poll: ScheduledFuture<*>? = null

        @Volatile var gamesReadAt = 0L

        @Volatile var misses = 0
    }

    private val devices = LinkedHashMap<String, Device>() // by entity id
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val spec = PlayniteSpec(cfg.localId, cfg.entityId, cfg.name, cfg.host, cfg.port)
            val executor =
                Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Playnite-${cfg.localId}").apply { isDaemon = true } }
            val device = Device(spec, cfg.mac, PlayniteClient(cfg.host, cfg.port, cfg.code), executor)
            device.state = PlayniteState(games = loadGames(spec))
            devices[spec.entityId] = device
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
        client?.let { c ->
            devices.values.forEach { c.removeLocalEntity(it.spec.entityId) }
            c.localServiceHandler = previousHandler
        }
        devices.clear()
    }

    private fun schedule(device: Device, delayMs: Long) {
        if (stopped || (quiet && delayMs > 0)) return
        device.poll?.cancel(false)
        device.poll = runCatching { device.executor.schedule({ pollOnce(device) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun pollOnce(device: Device) {
        val before = device.state
        val next = read(device, before)
        // A miss or two is often the remote's own Wi-Fi waking up: "off" after a few in a row.
        if (!next.reachable && before.reachable) {
            if (quiet) return
            device.misses++
            if (device.misses < MISSES_BEFORE_DOWN) {
                schedule(device, RETRY_MS)
                return
            }
        }
        if (next.reachable) device.misses = 0
        changed(device, before, next)
        device.state = next
        publish(device)
        if (!quiet) schedule(device, if (next.reachable) ON_POLL_MS else OFF_POLL_MS)
    }

    /** What follows a change: the MAC refreshed when the PC comes on, the games kept, the log. */
    private fun changed(device: Device, before: PlayniteState, next: PlayniteState) {
        if (next.reachable && !before.reachable) refreshMac(device)
        if (next.games !== before.games && next.games.isNotEmpty()) saveGames(device.spec, next.games)
        if (next.reachable != before.reachable || next.codeRefused != before.codeRefused) {
            val what = if (!next.reachable) "not answering" else if (next.codeRefused) "answers, but refuses the code" else "on"
            log("[${device.spec.name}] $what")
        }
        if (next.running?.id != before.running?.id) log("[${device.spec.name}] playing: ${next.running?.name ?: "nothing"}")
    }

    private fun read(device: Device, before: PlayniteState): PlayniteState {
        val c = device.client
        return try {
            val running = c.running()
            val now = System.currentTimeMillis()
            // The library: at first contact, every few minutes, and when a game ends (its play time changed).
            val gameEnded = before.running != null && running == null
            val stale = before.games.isEmpty() || !before.reachable || now - device.gamesReadAt > GAMES_REFRESH_MS || gameEnded
            val games =
                if (stale) {
                    c.games()?.also { device.gamesReadAt = now } ?: before.games
                } else {
                    before.games
                }
            PlayniteState(reachable = true, running = running, games = games)
        } catch (_: PlayniteClient.CodeRefused) {
            PlayniteState(reachable = true, codeRefused = true)
        } catch (_: Exception) {
            before.copy(reachable = false, running = null, codeRefused = false)
        }
    }

    private fun cacheFile(spec: PlayniteSpec) = cacheDir?.let { File(it, "playnite_${spec.localId}.json") }

    private fun loadGames(spec: PlayniteSpec): List<PlayniteGame> =
        runCatching { cacheFile(spec)?.takeIf { it.isFile }?.readText()?.let { PlayniteProtocol.games(it) } }.getOrNull().orEmpty()

    private fun saveGames(spec: PlayniteSpec, games: List<PlayniteGame>) {
        runCatching { cacheFile(spec)?.writeText(PlayniteProtocol.gamesJson(games)) }
    }

    /** The PC's best card for Wake-on-LAN, as the extension (0.1.1+) ranks them; saved when it changed. */
    private fun refreshMac(device: Device) {
        val mac = runCatching { device.client.info()?.macs?.firstOrNull() }.getOrNull() ?: return
        if (mac.equals(device.mac, ignoreCase = true)) return
        log("[${device.spec.name}] Wake-on-LAN address: $mac (was ${device.mac.ifBlank { "none" }})")
        device.mac = mac
        onMac(device.spec.localId, mac)
    }

    private fun publish(device: Device) {
        val c = client ?: return
        if (stopped) return
        val attrs = PlayniteProtocol.media(device.spec, device.state)
        c.setLocalEntity(
            EntityState(
                entityId = device.spec.entityId,
                state = PlayniteProtocol.stateOf(device.state),
                attributes = JsonObject(attrs.mapValues { toJson(it.value) }),
                lastChanged = null,
                lastUpdated = System.currentTimeMillis().toString()
            )
        )
    }

    private fun handle(call: ServiceCall): Boolean {
        if (call.domain != "media_player") return false
        val device = devices[call.entityId ?: return false] ?: return false
        val data = call.data.mapValues { (it.value as? JsonPrimitive)?.contentOrNull }
        val action = PlayniteProtocol.service(call.service, data, device.state)
        if (action == null) {
            log("[${device.spec.name}] nothing to do for media_player.${call.service}")
        } else {
            runCatching { device.executor.execute { perform(device, action) } }
        }
        // Consumed either way: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(device: Device, action: PlayniteAction) {
        val name = device.spec.name
        try {
            when (action) {
                is PlayniteAction.Start -> {
                    log("[$name] starting ${action.game.name}")
                    device.client.start(action.game.id)
                }
                is PlayniteAction.Wake -> wake(device)
                is PlayniteAction.WakeAndStart -> wakeAndStart(device, action.game)
                is PlayniteAction.Power -> {
                    log("[$name] ${action.action}")
                    device.client.power(action.action)
                }
            }
        } catch (e: Exception) {
            log("[$name] ${action::class.simpleName} failed: ${e.message}")
        }
        schedule(device, AFTER_COMMAND_MS)
    }

    private fun wake(device: Device): Boolean {
        if (device.mac.isBlank()) {
            log("[${device.spec.name}] can't wake it: no MAC address (set it on the web page, ✎)")
            return false
        }
        log("[${device.spec.name}] Wake-on-LAN to ${device.mac}")
        // Twice: the first packet is sometimes lost while the network card wakes.
        val sent = ZidooClient.wake(device.mac)
        runCatching { Thread.sleep(WOL_AGAIN_MS) }
        return ZidooClient.wake(device.mac) || sent
    }

    /** Wakes the PC, waits for Playnite to answer (Windows starting, then Playnite), then starts [game]. */
    private fun wakeAndStart(device: Device, game: PlayniteGame) {
        val name = device.spec.name
        if (!wake(device)) return
        log("[$name] waiting for Playnite to start ${game.name}")
        val deadline = System.currentTimeMillis() + WAKE_START_MS
        while (System.currentTimeMillis() < deadline && !stopped) {
            try {
                Thread.sleep(WAKE_POLL_MS)
            } catch (_: InterruptedException) {
                return
            }
            val answered = runCatching { device.client.running() }.isSuccess
            if (answered) {
                log("[$name] Playnite answers: starting ${game.name}")
                runCatching { device.client.start(game.id) }.onFailure { log("[$name] starting ${game.name} failed: ${it.message}") }
                return
            }
        }
        log("[$name] Playnite didn't answer within ${WAKE_START_MS / MS_PER_MIN} minutes: ${game.name} not started")
    }

    companion object {
        private const val TAG = "PlayniteRegistry"
        private const val WOL_AGAIN_MS = 500L
        private const val WAKE_START_MS = 4 * 60_000L
        private const val WAKE_POLL_MS = 5_000L
        private const val MS_PER_MIN = 60_000L
        private const val ON_POLL_MS = 5_000L
        private const val OFF_POLL_MS = 30_000L
        private const val RETRY_MS = 3_000L
        private const val MISSES_BEFORE_DOWN = 3
        private const val AFTER_COMMAND_MS = 2_000L
        private const val GAMES_REFRESH_MS = 10 * 60_000L
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

/**
 * Builds, starts and wires a [PlayniteRegistry], chained to the service handlers before it.
 * Never throws: Playnite support is optional and must not stop the app starting.
 */
fun installPlaynite(context: Context, haClient: HaClient, log: (String) -> Unit = {}): PlayniteRegistry {
    val configs = runCatching { RemoteSettings.playnites(context) }.getOrElse { emptyList() }
    val registry =
        PlayniteRegistry(
            configs,
            cacheDir = context.filesDir,
            onMac = { localId, mac ->
                runCatching {
                    RemoteSettings.savePlaynites(
                        context,
                        RemoteSettings.playnites(context).map { if (it.localId == localId) it.copy(mac = mac) else it }
                    )
                }
            },
            log = log
        )
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Playnite support failed to start: ${e.message}")
    }
    return registry
}
