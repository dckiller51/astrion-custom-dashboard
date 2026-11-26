package com.custom.astrion.sonos

import android.content.Context
import android.util.Log
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.SonosConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.HaTime
import com.custom.astrion.ha.ServiceCall
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
 * Sonos speakers added on the web page (1.6.3), driven directly on the network: each one published
 * as a `media_player` into [HaClient]'s local-entity overlay (its volume, what its group plays, the
 * TV, the line-in and the Sonos favourites as sources, `group_members`), and the service calls aimed
 * at it (transport, volume, sources, join / unjoin for the speaker group card) answered here.
 *
 * Polled while the screen is on: every 2 s while playing, 5 s otherwise; a speaker that stops
 * answering (a Roam asleep, one unplugged) shows "unavailable" and is tried every 30 s. The groups
 * are read every 15 s, the favourites every 5 minutes. A speaker's address is followed when it
 * changes (DHCP) and saved by the caller ([onHost]).
 */
class SonosRegistry(
    configs: List<SonosConfig>,
    private val onHost: (uuid: String, host: String) -> Unit = { _, _ -> },
    private val log: (String) -> Unit = { Log.d(TAG, it) }
) {
    private val configs = configs.filter { it.uuid.isNotBlank() && it.host.isNotBlank() }

    private class Speaker(val spec: SonosSpec, val executor: ScheduledExecutorService) {
        @Volatile var host = spec.host

        @Volatile var reachable = true

        @Volatile var misses = 0

        @Volatile var volume: Int? = null

        @Volatile var muted: Boolean? = null

        /** What this speaker plays, when it is its group's coordinator. */
        @Volatile var playback: SonosPlayback? = null

        @Volatile var coordinator = spec.uuid

        @Volatile var poll: ScheduledFuture<*>? = null
    }

    private val speakers = LinkedHashMap<String, Speaker>() // by entity id
    private val byUuid = HashMap<String, Speaker>()

    /** Every speaker of the household by UUID (also those not added): its address, from the zone group state. */
    private val hosts = ConcurrentHashMap<String, String>()

    /** What a coordinator that wasn't added plays, read by its members. */
    private val foreign = ConcurrentHashMap<String, SonosPlayback>()

    @Volatile private var favorites: List<SonosFavorite> = emptyList()

    @Volatile private var favoritesReadAt = 0L

    @Volatile private var topologyReadAt = 0L

    private val household = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Sonos").apply { isDaemon = true } }
    private var householdPoll: ScheduledFuture<*>? = null

    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null
    private var previousBrowse: ((String, String?, String?) -> JsonObject?)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        if (configs.isEmpty()) return
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        previousBrowse = haClient.localBrowseMediaHandler
        haClient.localBrowseMediaHandler = { entityId, contentId, contentType ->
            browse(entityId) ?: previousBrowse?.invoke(entityId, contentId, contentType)
        }
        for (cfg in configs) {
            val spec = SonosSpec(cfg.entityId, cfg.name, cfg.uuid, cfg.host, cfg.lineIn, cfg.tv)
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Sonos-${cfg.name}").apply { isDaemon = true } }
            val speaker = Speaker(spec, executor)
            speakers[spec.entityId] = speaker
            byUuid[spec.uuid] = speaker
            hosts[spec.uuid] = spec.host
            publish(speaker)
        }
        scheduleHousehold(0L)
        speakers.values.forEach { schedule(it, 0L) }
    }

    /** Screen off a while (quiet mode): not polled; at once on wake. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        if (quiet) {
            householdPoll?.cancel(false)
            speakers.values.forEach { it.poll?.cancel(false) }
        } else {
            scheduleHousehold(0L)
            speakers.values.forEach { schedule(it, 0L) }
        }
    }

    fun stop() {
        stopped = true
        household.shutdownNow()
        speakers.values.forEach { it.executor.shutdownNow() }
        client?.let { c ->
            if (speakers.isNotEmpty()) {
                speakers.values.forEach { c.removeLocalEntity(it.spec.entityId) }
                c.localServiceHandler = previousHandler
                c.localBrowseMediaHandler = previousBrowse
            }
        }
        speakers.clear()
        byUuid.clear()
    }

    // ---- polling --------------------------------------------------------------------

    private fun schedule(speaker: Speaker, delayMs: Long) {
        if (stopped || (quiet && delayMs > 0)) return
        speaker.poll?.cancel(false)
        speaker.poll = runCatching { speaker.executor.schedule({ pollOnce(speaker) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun scheduleHousehold(delayMs: Long) {
        if (stopped || (quiet && delayMs > 0)) return
        householdPoll?.cancel(false)
        householdPoll = runCatching { household.schedule({ readHousehold() }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun pollOnce(speaker: Speaker) {
        val wasReachable = speaker.reachable
        val ok = runCatching { read(speaker) }.isSuccess
        if (!ok && wasReachable) {
            if (quiet) return
            // A miss or two is often the remote's own Wi-Fi waking up: unavailable after a few in a row.
            if (++speaker.misses < MISSES_BEFORE_DOWN) {
                schedule(speaker, RETRY_MS)
                return
            }
            speaker.reachable = false
            speaker.playback = null
            log("[${speaker.spec.name}] not answering (asleep, unplugged?)")
            publishGroup(speaker)
        } else if (ok) {
            speaker.misses = 0
            if (!wasReachable) {
                speaker.reachable = true
                log("[${speaker.spec.name}] back")
                scheduleHousehold(0L)
            }
            publishGroup(speaker)
        }
        if (quiet) return
        val playing = SonosProtocol.stateOf(speaker.reachable, playbackOf(speaker)) == "playing"
        schedule(speaker, if (!speaker.reachable) DOWN_POLL_MS else if (playing) PLAYING_POLL_MS else IDLE_POLL_MS)
    }

    /** The speaker's own volume and mute; what it plays when it leads its group (or its leader wasn't added). */
    private fun read(speaker: Speaker) {
        val host = speaker.host
        val me = speaker.spec.uuid
        speaker.volume = SonosProtocol.volumeOf(SonosClient.call(host, SonosProtocol.getVolume(me)))
        speaker.muted = SonosProtocol.muteOf(SonosClient.call(host, SonosProtocol.getMute(me)))
        val co = speaker.coordinator
        when {
            co == me -> speaker.playback = readPlayback(host)
            byUuid[co] == null -> hosts[co]?.let { h -> runCatching { readPlayback(h) }.getOrNull()?.let { foreign[co] = it } }
        }
    }

    private fun readPlayback(host: String): SonosPlayback {
        fun ask(action: String) = SonosClient.call(host, SonosProtocol.av("", action))
        return SonosProtocol.playback(
            ask("GetTransportInfo"),
            ask("GetPositionInfo"),
            ask("GetMediaInfo"),
            ask("GetTransportSettings"),
            host
        )
    }

    /** The groups (and the speakers' addresses), then the favourites when due, from a speaker that answers. */
    private fun readHousehold() {
        val now = System.currentTimeMillis()
        val source = speakers.values.firstOrNull { it.reachable } ?: speakers.values.firstOrNull()
        if (source != null) {
            runCatching { SonosClient.speakers(source.host) }
                .onSuccess { topology(it) }
                .onFailure { if (now - topologyReadAt > TOPOLOGY_MS * 2) log("groups not read: ${it.message}") }
            if (favorites.isEmpty() || now - favoritesReadAt > FAVORITES_MS) {
                runCatching { SonosClient.call(source.host, SonosProtocol.favorites("")) }.onSuccess {
                    val list = SonosProtocol.favoritesOf(it, source.host)
                    favoritesReadAt = now
                    if (list != favorites) {
                        favorites = list
                        speakers.values.forEach { s -> publish(s) }
                    }
                }
            }
        }
        scheduleHousehold(TOPOLOGY_MS)
    }

    private fun topology(list: List<SonosSpeaker>) {
        topologyReadAt = System.currentTimeMillis()
        list.forEach { hosts[it.uuid] = it.host }
        var changed = false
        for (s in list) {
            val speaker = byUuid[s.uuid] ?: continue
            if (s.host != speaker.host) {
                log("[${speaker.spec.name}] now at ${s.host} (was ${speaker.host})")
                speaker.host = s.host
                onHost(s.uuid, s.host)
            }
            if (s.coordinator != speaker.coordinator) {
                speaker.coordinator = s.coordinator
                if (s.coordinator != s.uuid) speaker.playback = null
                changed = true
            }
        }
        // A speaker the household no longer lists (a Roam asleep) is alone again when it's back.
        speakers.values.filter { sp -> list.none { it.uuid == sp.spec.uuid } && sp.coordinator != sp.spec.uuid }.forEach {
            it.coordinator = it.spec.uuid
            changed = true
        }
        if (changed) speakers.values.forEach { publish(it) }
    }

    // ---- publishing -------------------------------------------------------------------

    private fun playbackOf(speaker: Speaker): SonosPlayback? {
        val co = speaker.coordinator
        if (co == speaker.spec.uuid) return speaker.playback
        return byUuid[co]?.playback ?: foreign[co]
    }

    /** The speaker's group, its coordinator first, as entity ids (the speakers added only). */
    private fun groupMembers(speaker: Speaker): List<String> {
        val co = speaker.coordinator
        return speakers.values
            .filter { it.reachable && it.coordinator == co }
            .sortedBy { if (it.spec.uuid == co) 0 else 1 }
            .map { it.spec.entityId }
    }

    /** The speaker, and the members it leads (they show what it plays). */
    private fun publishGroup(speaker: Speaker) {
        publish(speaker)
        speakers.values.filter { it !== speaker && it.coordinator == speaker.spec.uuid }.forEach { publish(it) }
    }

    private fun publish(speaker: Speaker) {
        val c = client ?: return
        if (stopped) return
        val playback = if (speaker.reachable) playbackOf(speaker) else null
        val attrs =
            SonosProtocol.media(
                speaker.spec,
                speaker.volume,
                speaker.muted,
                playback,
                if (speaker.reachable) groupMembers(speaker) else emptyList(),
                favorites,
                HaTime.isoInstant(System.currentTimeMillis())
            )
        c.setLocalEntity(
            EntityState(
                entityId = speaker.spec.entityId,
                state = SonosProtocol.stateOf(speaker.reachable, playback),
                attributes = JsonObject(attrs.mapValues { toJson(it.value) }),
                lastChanged = null,
                lastUpdated = System.currentTimeMillis().toString()
            )
        )
    }

    // ---- service calls -----------------------------------------------------------------

    private fun handle(call: ServiceCall): Boolean {
        if (call.domain != "media_player") return false
        val speaker = speakers[call.entityId ?: return false] ?: return false
        val context =
            SonosContext(
                self = speaker.spec.copy(host = speaker.host),
                coordinator = speaker.coordinator,
                playback = playbackOf(speaker),
                volume = speaker.volume,
                favorites = favorites,
                uuidOf = { id -> speakers[id]?.spec?.uuid }
            )
        val calls = SonosProtocol.service(call.service, call.data, context)
        if (calls == null) {
            log("[${speaker.spec.name}] nothing to do for media_player.${call.service}")
        } else {
            runCatching { speaker.executor.execute { perform(speaker, call.service, calls) } }
        }
        // Consumed either way: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(speaker: Speaker, service: String, calls: List<SonosCall>) {
        try {
            for (c in calls) {
                val host = hosts[c.target] ?: error("no address for ${c.target}")
                SonosClient.call(host, c)
            }
        } catch (e: Exception) {
            log("[${speaker.spec.name}] $service failed: ${e.message}")
        }
        // Read back soon: the speaker, the group's leader, and for a join the whole household.
        if (service == "join" || service == "unjoin") scheduleHousehold(AFTER_COMMAND_MS)
        calls.mapNotNull { byUuid[it.target] }.distinct().forEach { schedule(it, AFTER_COMMAND_MS) }
        byUuid[speaker.coordinator]?.let { schedule(it, AFTER_COMMAND_MS) }
        schedule(speaker, AFTER_COMMAND_MS)
    }

    private fun browse(entityId: String): JsonObject? {
        if (speakers[entityId] == null) return null
        return SonosProtocol.browseRoot(favorites)
    }

    companion object {
        private const val TAG = "SonosRegistry"
        private const val PLAYING_POLL_MS = 2_000L
        private const val IDLE_POLL_MS = 5_000L
        private const val DOWN_POLL_MS = 30_000L
        private const val RETRY_MS = 2_000L
        private const val MISSES_BEFORE_DOWN = 3
        private const val AFTER_COMMAND_MS = 400L
        private const val TOPOLOGY_MS = 15_000L
        private const val FAVORITES_MS = 5 * 60_000L
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
 * Builds, starts and wires a [SonosRegistry], chained to the handlers before it. Never throws:
 * Sonos support is optional and must not stop the app starting.
 */
fun installSonos(context: Context, haClient: HaClient, log: (String) -> Unit = {}): SonosRegistry {
    val configs = runCatching { RemoteSettings.sonos(context) }.getOrElse { emptyList() }
    val registry =
        SonosRegistry(
            configs,
            onHost = { uuid, host ->
                runCatching {
                    RemoteSettings.saveSonos(
                        context,
                        RemoteSettings.sonos(context).map { if (it.uuid == uuid) it.copy(host = host) else it }
                    )
                }
            },
            log = log
        )
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Sonos support failed to start: ${e.message}")
    }
    return registry
}
