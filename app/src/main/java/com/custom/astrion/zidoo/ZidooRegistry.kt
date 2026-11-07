package com.custom.astrion.zidoo

import android.content.Context
import android.util.Log
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.config.ZidooConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
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
 * Zidoo players added on the web page (1.4.6): each one polled over its control API and published
 * as a `media_player` and a `remote` entity into [HaClient]'s local-entity overlay, and the service
 * calls aimed at those entities answered here — like the Android TVs paired directly, so cards,
 * hotkeys and Activities drive a Zidoo the same way with or without Home Assistant.
 */
class ZidooRegistry(configs: List<ZidooConfig>, private val log: (String) -> Unit = { Log.d(TAG, it) }) {
    private val configs = configs.filter { it.host.isNotBlank() }

    private class Device(val spec: ZidooSpec, val client: ZidooClient, val executor: ScheduledExecutorService) {
        @Volatile var state = ZidooState()

        @Volatile var mac: String = spec.mac

        @Volatile var poll: ScheduledFuture<*>? = null

        @Volatile var appsReadAt = 0L

        @Volatile var appsTriedAt = 0L

        /** The app list didn't come once: said in the log, once. */
        @Volatile var appsWarned = false

        /** The video file whose film was last looked up, and what was found. */
        var moviePath: String? = null
        var movie: ZidooMovie? = null
        var moviePicture: String? = null
    }

    private val devices = LinkedHashMap<String, Device>() // by media entity id
    private val byRemoteEntity = HashMap<String, Device>()
    private val sender = Executors.newSingleThreadExecutor { r -> Thread(r, "Zidoo-send").apply { isDaemon = true } }
    private var client: HaClient? = null
    private var previousHandler: ((ServiceCall) -> Boolean)? = null

    @Volatile private var quiet = false

    @Volatile private var stopped = false

    fun start(haClient: HaClient) {
        client = haClient
        previousHandler = haClient.localServiceHandler
        haClient.localServiceHandler = { call -> handle(call) || previousHandler?.invoke(call) == true }
        for (cfg in configs) {
            val spec = ZidooSpec(cfg.localId, cfg.entityId, cfg.name, cfg.mac)
            val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "Zidoo-${cfg.localId}").apply { isDaemon = true } }
            val device = Device(spec, ZidooClient { cfg.host }, executor)
            devices[spec.entityId] = device
            libraries[spec.entityId] = Library(device.client) { device.state.model }
            byRemoteEntity[spec.remoteEntityId] = device
            publish(device)
            schedule(device, 0L)
        }
    }

    /** Screen off a while (1.4.0's quiet mode): polled every minute instead of every few seconds; at once on wake. */
    fun setQuiet(quiet: Boolean) {
        if (this.quiet == quiet) return
        this.quiet = quiet
        if (!quiet) devices.values.forEach { schedule(it, 0L) }
    }

    fun stop() {
        stopped = true
        devices.values.forEach { it.executor.shutdownNow() }
        devices.keys.forEach { libraries.remove(it) }
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

    private fun schedule(device: Device, delayMs: Long) {
        if (stopped) return
        device.poll?.cancel(false)
        device.poll = runCatching { device.executor.schedule({ pollOnce(device) }, delayMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun pollOnce(device: Device) {
        val before = device.state
        val next = runCatching { read(device, before) }.getOrElse { ZidooState() }
        if (next.reachable != before.reachable) {
            log(
                "[${device.spec.name}] ${if (next.reachable) "reachable" else "not answering (off or standby)"}"
            )
        }
        device.state = next
        publish(device)
        val delay =
            when {
                quiet -> QUIET_POLL_MS
                !next.reachable -> OFF_POLL_MS
                else -> ON_POLL_MS
            }
        schedule(device, delay)
    }

    /** One look at the player: what its video or music player plays, else whether it answers at all. */
    private fun read(device: Device, before: ZidooState): ZidooState {
        val c = device.client
        // Off or in standby, it doesn't answer: one call (and one timeout) to find out, not three.
        val first = if (before.reachable) null else c.model() ?: return ZidooState(apps = before.apps)
        val playback = c.playback()
        val model = first ?: if (playback == null || before.model == null) c.model() else null
        if (playback == null && model == null) return ZidooState(apps = before.apps)
        model?.mac?.let { if (device.mac.isBlank()) device.mac = it }
        val now = System.currentTimeMillis()
        val apps = appsOf(device, before, now)
        val base =
            ZidooState(
                reachable = true,
                model = model?.model ?: before.model,
                firmware = model?.firmware ?: before.firmware,
                apps = apps,
                input = c.lastInput,
                muted = c.lastMuted
            )
        return if (playback == null) base else withPlayback(device, base, playback, now)
    }

    /** The installed apps: read once it answers, then every [APPS_REFRESH_MS]. */
    private fun appsOf(device: Device, before: ZidooState, now: Long): Map<String, String> {
        if (before.apps.isNotEmpty() && now - device.appsReadAt <= APPS_REFRESH_MS) return before.apps
        // A list that didn't come is asked again a minute later, not at every read: the call may
        // take a while, and the player's state would wait for it.
        if (now - device.appsTriedAt < APPS_RETRY_MS) return before.apps
        device.appsTriedAt = now
        val apps = device.client.apps()
        if (apps.isEmpty()) {
            if (!device.appsWarned) log("[${device.spec.name}] its app list didn't come (ZidooControlCenter/Apps/getApps)")
            device.appsWarned = true
            return before.apps
        }
        device.appsReadAt = now
        return apps
    }

    private fun withPlayback(device: Device, base: ZidooState, p: ZidooPlayback, now: Long): ZidooState {
        val c = device.client
        var movie: ZidooMovie? = null
        var picture: String? = null
        if (p.source == ZidooSource.VIDEO) {
            val path = p.path
            if (path != null && path != device.moviePath) {
                device.moviePath = path
                device.movie = c.movieOf(path)
                device.moviePicture = device.movie?.let { pictureOf(c, it) }
            }
            movie = device.movie
            picture = device.moviePicture
        } else {
            val id = p.musicId
            // A cover the player serves itself comes as one of its calls (1.5.0): its address in front.
            val art = p.artUrl?.let { if (it.startsWith("/")) c.base() + it.trimStart('/') else it }
            picture = art ?: if (id != null && id > 0) c.musicArtUrl(id, p.musicType ?: 0) else null
        }
        return base.copy(
            source = p.source,
            playing = p.playing,
            title = movie?.name ?: p.title,
            artist = p.artist,
            album = p.album,
            seriesName = movie?.seriesName,
            season = movie?.season,
            episode = movie?.episode,
            durationMs = p.durationMs,
            positionMs = p.positionMs,
            positionAt = now,
            pictureUrl = picture,
            quality = p.quality,
            canSeek = p.canSeek,
            subtitle = p.subtitle,
            audio = p.audio,
            appName = p.appName
        )
    }

    /**
     * A film's backdrop; for an episode (1.4.9) the first artwork the player has among the
     * backdrops of the series, the season and the episode, then their posters — an episode
     * rarely has a backdrop, so before this it showed none.
     */
    private fun pictureOf(c: ZidooClient, movie: ZidooMovie): String? {
        if (!movie.isEpisode) return c.backdropUrl(movie.id)
        val ids = movie.artIds()
        val candidates = ids.map(c::backdropUrl) + ids.map(c::posterUrl)
        return candidates.firstOrNull(c::isImage) ?: c.backdropUrl(movie.id)
    }

    private fun publish(device: Device) {
        val c = client ?: return
        if (stopped) return
        c.setLocalEntity(toEntityState(ZidooEntityMapper.media(device.spec, device.state)))
        c.setLocalEntity(toEntityState(ZidooEntityMapper.remote(device.spec, device.state)))
    }

    private fun handle(call: ServiceCall): Boolean {
        val entityId = call.entityId ?: return false
        val data = call.data.mapValues { plainOf(it.value) }
        val (device, steps) =
            when (call.domain) {
                "media_player" -> devices[entityId]?.let { it to ZidooServices.media(call.service, data, it.state) }
                "remote" -> byRemoteEntity[entityId]?.let { it to ZidooServices.remote(call.service, data, it.state) }
                else -> null
            } ?: return false
        if (steps == null) {
            log("[${device.spec.name}] unsupported service ${call.domain}.${call.service}")
        } else if (steps.isNotEmpty()) {
            runCatching { sender.execute { perform(device, steps) } }
        }
        // Consumed even when unsupported: Home Assistant doesn't know this entity.
        return true
    }

    private fun perform(device: Device, steps: List<ZidooStep>) {
        val c = device.client
        steps.forEachIndexed { i, step ->
            if (i > 0) Thread.sleep(KEY_GAP_MS)
            val ok =
                when (step) {
                    is ZidooStep.Key -> c.sendKey(step.key)
                    is ZidooStep.Api -> ZidooParse.ok(c.get(step.path))
                    is ZidooStep.Launch -> c.openApp(packageOf(device, step.app))
                    ZidooStep.Wake -> device.mac.isNotBlank() && ZidooClient.wake(device.mac)
                }
            if (!ok && step !is ZidooStep.Wake && device.state.reachable) log("[${device.spec.name}] no answer to $step")
        }
        // See the result soon (a key pressed after a turn_on, a pause) instead of at the next poll.
        schedule(device, AFTER_COMMAND_MS)
    }

    /** "Netflix" (a label from `source_list`) → its package; anything else as is (a package). */
    private fun packageOf(device: Device, app: String): String =
        device.state.apps.entries.firstOrNull { it.key.equals(app, ignoreCase = true) }?.value ?: app

    private fun toEntityState(data: ZidooEntityData) = EntityState(
        entityId = data.entityId,
        state = data.state,
        attributes = JsonObject(data.attributes.mapValues { toJson(it.value) }),
        lastChanged = null,
        lastUpdated = System.currentTimeMillis().toString()
    )

    companion object {
        /** A player added in Astrion, for the library pop-up (1.5.0), which talks to the player itself. */
        private class Library(val client: ZidooClient, val model: () -> String?) {
            val isZidoo: Boolean get() = ZidooKeys.brandOf(model()) == "Zidoo"
        }

        private val libraries = java.util.concurrent.ConcurrentHashMap<String, Library>()

        /**
         * The Zidoo whose library Browse opens for [entityId]: the one published as that entity or,
         * given [also] (the TV remote card's `remote.zidoo_…` beside Home Assistant's own media
         * player of the Zidoo), as that one's media player; else, for an entity of Home
         * Assistant's Zidoo integration (`media_player.…zidoo…`), the only Zidoo added in
         * Astrion. Null for an Eversolo (no film poster wall) or any other player.
         */
        fun libraryOf(entityId: String, also: String? = null): ZidooClient? {
            libraries[entityId]?.let { return if (it.isZidoo) it.client else null }
            also?.let { libraries["media_player." + it.substringAfter('.')] }?.let { return if (it.isZidoo) it.client else null }
            if (!entityId.startsWith("media_player.") || !entityId.contains("zidoo", ignoreCase = true)) return null
            return libraries.values.filter { it.isZidoo }.singleOrNull()?.client
        }

        /** The Eversolo whose music library Browse opens for [entityId] (or [also]'s media player), 1.5.0. */
        fun musicLibraryOf(entityId: String, also: String? = null): ZidooClient? {
            val lib = libraries[entityId] ?: also?.let { libraries["media_player." + it.substringAfter('.')] } ?: return null
            return if (lib.isZidoo) null else lib.client
        }

        /** The player added in Astrion published as [entityId], Zidoo or Eversolo: the Zidoo / Eversolo card's own calls (1.5.1). */
        fun clientOf(entityId: String): ZidooClient? = libraries[entityId]?.client

        private const val TAG = "ZidooRegistry"
        private const val ON_POLL_MS = 3_000L
        private const val OFF_POLL_MS = 15_000L
        private const val QUIET_POLL_MS = 60_000L
        private const val AFTER_COMMAND_MS = 700L
        private const val APPS_REFRESH_MS = 10 * 60_000L
        private const val APPS_RETRY_MS = 60_000L
        private const val KEY_GAP_MS = 60L
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
 * Builds, starts and wires a [ZidooRegistry] — after the Android TV one, whose service handler it
 * chains to. Never throws: Zidoo support is optional and must not stop the app starting.
 */
fun installZidoo(context: Context, haClient: HaClient, log: (String) -> Unit = {}): ZidooRegistry {
    val configs = runCatching { RemoteSettings.zidoos(context) }.getOrElse { emptyList() }
    val registry = ZidooRegistry(configs, log)
    try {
        registry.start(haClient)
    } catch (e: Exception) {
        log("Zidoo support failed to start: ${e.message}")
    }
    return registry
}
