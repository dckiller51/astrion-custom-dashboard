package com.custom.astrion.appletv

import com.custom.astrion.appletv.airplay.AirPlayMrpClient
import com.custom.astrion.appletv.mrp.Mrp
import com.custom.astrion.appletv.mrp.MrpClient
import com.custom.astrion.appletv.mrp.MrpPlayState
import com.custom.astrion.appletv.mrp.MrpPlaying
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** Finds an Apple TV's current address from its mDNS service name (Android's NsdManager in the app). */
fun interface AppleTvResolver {
    /** Blocks up to [timeoutMs]; returns null when the service isn't found. */
    fun resolve(serviceName: String, kind: AppleTvServiceKind, timeoutMs: Long): AppleTvEndpoint?
}

/**
 * Keeps one Apple TV connected and exposes it as a Home-Assistant-style
 * `media_player`: [onEntity] fires with a fresh [AppleTvEntityData] on
 * every change, and [handleService] / [sendCommand] execute service calls
 * and remote-control commands over the Companion link.
 *
 * Runs entirely on plain threads (one supervisor that connects and
 * reconnects with back-off, one serial executor for commands), so callers
 * — including the UI thread — never block.
 */
class AppleTvDevice(
    private val spec: AppleTvSpec,
    private val resolver: AppleTvResolver?,
    private val onEntity: (AppleTvEntityData) -> Unit,
    private val onEndpointChanged: (AppleTvEndpoint) -> Unit = {},
    private val onMrpEndpointChanged: (AppleTvEndpoint) -> Unit = {},
    private val log: (String) -> Unit = {}
) {
    companion object {
        private const val CONNECT_TIMEOUT_MS = 6000L
        private const val MIN_BACKOFF_MS = 3000L
        private const val MAX_BACKOFF_MS = 60_000L
        private const val RESOLVE_TIMEOUT_MS = 3000L
        private const val COMMAND_WAIT_MS = 7000L
        private const val SEEK_VERIFY_TIMEOUT_MS = 2500L
        private const val SEEK_VERIFY_POLL_MS = 150L
        private const val SEEK_TOLERANCE_SECONDS = 15.0
    }

    private val running = AtomicBoolean(false)
    private val commands: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread(r, "atv-cmd-${spec.localId}").apply {
            isDaemon =
                true
        }
    }
    private val retryNow = Semaphore(0)
    private val mrpRetryNow = Semaphore(0)
    private val connectedLock = Object()

    @Volatile private var client: CompanionClient? = null

    @Volatile private var snapshot = AppleTvSnapshot()

    @Volatile private var supervisor: Thread? = null

    @Volatile private var lastEndpoint = AppleTvEndpoint(spec.host, spec.port)

    // MRP (title/artist/artwork/position) is entirely optional and independent of Companion
    // above: its own client, its own supervisor thread with its own back-off, started only when
    // spec.mrp is set (i.e. the person paired it), and a failure here never touches Companion.
    @Volatile private var mrpClient: NowPlayingClient? = null

    @Volatile private var mrpSupervisor: Thread? = null

    @Volatile private var lastMrpEndpoint: AppleTvEndpoint? = spec.mrp?.let { AppleTvEndpoint(it.host, it.port) }

    // The item currently fetched into snapshot.artworkJpeg, so a repeated SetStateMessage for the
    // same item (position ticks) doesn't re-fetch its artwork every time.
    @Volatile private var artworkFetchedFor: String? = null

    val entityId: String get() = spec.entityId
    val current: AppleTvEntityData get() = AppleTvEntityMapper.map(spec, snapshot)

    /** The current item's artwork bytes, if MRP has fetched one — what a `/appletv-artwork/...` request serves. */
    val snapshotArtwork: ByteArray? get() = snapshot.artworkJpeg

    /** Launchable apps as bundle id -> display name — what [AppleTvRegistry]'s local
     * `media_player/browse_media` handler serves for a directly-paired device. */
    val snapshotApps: Map<String, String> get() = snapshot.apps
    val isConnected: Boolean get() = snapshot.connected

    fun start() {
        if (!running.compareAndSet(false, true)) return
        publish()
        supervisor = Thread({ superviseLoop() }, "atv-${spec.localId}").apply {
            isDaemon = true
            start()
        }
        if (spec.mrp != null) {
            mrpSupervisor = Thread({ mrpSuperviseLoop() }, "atv-mrp-${spec.localId}").apply {
                isDaemon = true
                start()
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        supervisor?.interrupt()
        mrpSupervisor?.interrupt()
        retryNow.release()
        mrpRetryNow.release()
        runCatching { client?.close() }
        runCatching { mrpClient?.close() }
        commands.shutdownNow()
    }

    // ---- connection supervision -----------------------------------------------

    /** Runs [block]; returns false (instead of throwing) if the thread was interrupted while it ran. */
    private fun awaitInterruptibly(block: () -> Unit): Boolean = try {
        block()
        true
    } catch (_: InterruptedException) {
        false
    }

    private fun superviseLoop() {
        var backoff = MIN_BACKOFF_MS
        while (running.get()) {
            val lost = Semaphore(0)
            if (tryConnect(lost)) {
                backoff = MIN_BACKOFF_MS
                if (!awaitInterruptibly({ lost.acquire() })) return
                log("[${spec.name}] connection lost")
                snapshot = AppleTvSnapshot()
                publish()
            }
            if (!running.get()) return
            if (!awaitInterruptibly({ retryNow.tryAcquire(backoff, TimeUnit.MILLISECONDS) })) return
            retryNow.drainPermits()
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    private fun candidateEndpoints(): List<AppleTvEndpoint> {
        val out = LinkedHashSet<AppleTvEndpoint>()
        if (spec.serviceName.isNotBlank()) {
            runCatching {
                resolver?.resolve(spec.serviceName, AppleTvServiceKind.Companion, RESOLVE_TIMEOUT_MS)
            }.getOrNull()?.let { out.add(it) }
        }
        out.add(lastEndpoint)
        out.add(AppleTvEndpoint(spec.host, spec.port))
        return out.filter { it.host.isNotBlank() && it.port > 0 }
    }

    private fun tryConnect(lost: Semaphore): Boolean {
        for (endpoint in candidateEndpoints()) {
            val c =
                CompanionClient(
                    endpoint.host,
                    endpoint.port,
                    spec.credentials,
                    object : CompanionClient.Listener {
                        override fun onMediaControlFlags(flags: Int) = handleFlags(flags)

                        override fun onAttention(state: AttentionState) = update { it.copy(attention = state) }

                        override fun onDisconnected(error: Throwable?) {
                            lost.release()
                        }
                    }
                )
            try {
                c.connect(CONNECT_TIMEOUT_MS.toInt())
                client = c
                initialize(c)
                if (endpoint != lastEndpoint) {
                    lastEndpoint = endpoint
                    onEndpointChanged(endpoint)
                }
                log("[${spec.name}] connected to ${endpoint.host}:${endpoint.port}")
                synchronized(connectedLock) { connectedLock.notifyAll() }
                return true
            } catch (e: Exception) {
                log("[${spec.name}] connect to ${endpoint.host}:${endpoint.port} failed: ${e.message}")
                runCatching { c.close() }
                client = null
            }
        }
        return false
    }

    // ---- MRP (now-playing metadata) supervision --------------------------------

    private fun mrpCandidateEndpoints(mrp: MrpSpec): List<AppleTvEndpoint> {
        val out = LinkedHashSet<AppleTvEndpoint>()
        val kind = if (mrp.transport == MrpTransport.AirPlayTunnel) AppleTvServiceKind.AirPlay else AppleTvServiceKind.Mrp
        if (mrp.serviceName.isNotBlank()) {
            runCatching { resolver?.resolve(mrp.serviceName, kind, RESOLVE_TIMEOUT_MS) }.getOrNull()?.let { out.add(it) }
        }
        lastMrpEndpoint?.let { out.add(it) }
        out.add(AppleTvEndpoint(mrp.host, mrp.port))
        return out.filter { it.host.isNotBlank() && it.port > 0 }
    }

    private fun mrpSuperviseLoop() {
        val mrp = spec.mrp ?: return
        var backoff = MIN_BACKOFF_MS
        while (running.get()) {
            val lost = Semaphore(0)
            // Set from the client's onDisconnected callback right before it releases [lost], so
            // the actual reason is available here instead of a bare, unexplained "connection
            // lost" — every disconnect was silently dropping its Throwable until now.
            val lostReason = AtomicReference<Throwable?>()
            if (tryConnectMrp(mrp, lost, lostReason)) {
                backoff = MIN_BACKOFF_MS
                if (!awaitInterruptibly({ lost.acquire() })) return
                log("[${spec.name}] MRP connection lost: ${lostReason.get()?.message ?: "no error given"}")
                // Keeping the last [playing] (title/artist/artwork) instead of nulling it here is
                // deliberate: this connection cycles every ~30s on real hardware regardless of any
                // keep-alive we send (tvOS's own doing, confirmed not fixable client-side — see the
                // CHANGELOG), and the reconnect itself only takes a few seconds. Clearing it on every
                // cycle produced a visible flicker in Home Assistant's media_player card between
                // "title + artwork" and "playing with no artwork", since AppleTvEntity.populate()
                // only fills those fields when `playing` is non-null. Showing the last known state
                // through that brief gap, until a fresh one actually arrives, reads as continuous
                // instead of flickering — and if the Apple TV changed what's playing during the gap,
                // the next push corrects it within a few seconds anyway.
                update { it.copy(mrpConnected = false) }
            }
            if (!running.get()) return
            if (!awaitInterruptibly({ mrpRetryNow.tryAcquire(backoff, TimeUnit.MILLISECONDS) })) return
            mrpRetryNow.drainPermits()
            backoff = minOf(backoff * 2, MAX_BACKOFF_MS)
        }
    }

    private fun newMrpClient(
        mrp: MrpSpec,
        endpoint: AppleTvEndpoint,
        lost: Semaphore,
        lostReason: AtomicReference<Throwable?>
    ): NowPlayingClient = if (mrp.transport == MrpTransport.AirPlayTunnel) {
        AirPlayMrpClient(
            endpoint.host,
            endpoint.port,
            mrp.credentials,
            "Astrion",
            object : AirPlayMrpClient.Listener {
                override fun onPlaying(playing: MrpPlaying?) = handleMrpPlaying(playing)

                override fun onDisconnected(error: Throwable?) {
                    lostReason.set(error)
                    lost.release()
                }
            }
        )
    } else {
        MrpClient(
            endpoint.host,
            endpoint.port,
            mrp.credentials,
            "Astrion",
            object : MrpClient.Listener {
                override fun onPlaying(playing: MrpPlaying?) = handleMrpPlaying(playing)

                override fun onDisconnected(error: Throwable?) {
                    lostReason.set(error)
                    lost.release()
                }
            }
        )
    }

    private fun tryConnectMrp(mrp: MrpSpec, lost: Semaphore, lostReason: AtomicReference<Throwable?>): Boolean {
        for (endpoint in mrpCandidateEndpoints(mrp)) {
            val c = newMrpClient(mrp, endpoint, lost, lostReason)
            try {
                c.connect(CONNECT_TIMEOUT_MS.toInt())
                mrpClient = c
                if (endpoint != lastMrpEndpoint) {
                    lastMrpEndpoint = endpoint
                    onMrpEndpointChanged(endpoint)
                }
                log("[${spec.name}] MRP connected to ${endpoint.host}:${endpoint.port}")
                update { it.copy(mrpConnected = true, playing = c.playing) }
                return true
            } catch (e: Exception) {
                log("[${spec.name}] MRP connect to ${endpoint.host}:${endpoint.port} failed: ${e.message}")
                runCatching { c.close() }
                mrpClient = null
            }
        }
        return false
    }

    private fun handleMrpPlaying(playing: MrpPlaying?) {
        log(
            "[${spec.name}] onPlaying: title=${playing?.nowPlaying?.title} artist=${playing?.nowPlaying?.artist} " +
                "artworkAvailable=${playing?.nowPlaying?.artworkAvailable}"
        )
        update { it.copy(playing = playing) }
        val identifier = playing?.itemIdentifier
        val artworkAvailable = playing?.nowPlaying?.artworkAvailable == true
        if (!artworkAvailable || identifier == null || identifier == artworkFetchedFor) return
        artworkFetchedFor = identifier
        val location = playing.location
        commands.execute {
            val jpeg = runCatching { mrpClient?.takeIf { it.isConnected }?.fetchArtwork(location) }.getOrNull()
            if (jpeg != null) {
                update { it.copy(artworkJpeg = jpeg, artworkVersion = it.artworkVersion + 1) }
            }
        }
    }

    private fun initialize(c: CompanionClient) {
        val apps = runCatching { c.fetchApps() }.getOrDefault(emptyMap())
        val attention = c.fetchAttention()
        // Publish "connected" only once the first snapshot is complete, so cards never flash an empty source list.
        snapshot =
            snapshot.copy(
                connected = true,
                attention = if (attention ==
                    AttentionState.Unknown
                ) {
                    AttentionState.Awake
                } else {
                    attention
                },
                apps = apps
            )
        publish()
    }

    private fun handleFlags(flags: Int) {
        update { it.copy(mediaFlags = flags, volume = if (flags and MediaControlFlags.VOLUME != 0) it.volume else null) }
        if (flags and MediaControlFlags.VOLUME != 0) {
            commands.execute {
                val volume = runCatching { client?.getVolume() }.getOrNull()
                if (volume != null) update { it.copy(volume = volume) }
            }
        }
    }

    private fun update(change: (AppleTvSnapshot) -> AppleTvSnapshot) {
        synchronized(this) { snapshot = change(snapshot) }
        publish()
    }

    private fun publish() {
        runCatching { onEntity(current) }
    }

    // ---- commands -----------------------------------------------------------

    /** Runs [action] on the serial command thread once connected (reconnecting first if needed). */
    private fun submit(description: String, action: (CompanionClient) -> Unit) {
        if (!running.get()) return
        commands.execute {
            val c = awaitClient()
            if (c == null) {
                log("[${spec.name}] $description: not connected")
                return@execute
            }
            try {
                action(c)
            } catch (e: Exception) {
                log("[${spec.name}] $description failed: ${e.message}")
            }
        }
    }

    private fun awaitClient(): CompanionClient? {
        client?.takeIf { it.isConnected }?.let { return it }
        retryNow.release()
        val deadline = System.currentTimeMillis() + COMMAND_WAIT_MS
        synchronized(connectedLock) {
            while (System.currentTimeMillis() < deadline) {
                client?.takeIf { it.isConnected }?.let { return it }
                connectedLock.wait(250)
            }
        }
        return client?.takeIf { it.isConnected }
    }

    /**
     * Sends a named remote-control command. Accepts the names used by the
     * Harmony-based remote card (`DirectionUp`, `Select`, `Menu`, `Home`, ...)
     * as well as plain ones (`up`, `play_pause`, `volume_up`, `wake`, ...).
     * Returns false for a name that isn't recognised.
     *
     * [holdMs] holds the button down that long instead of tapping it (e.g. Select held ~1 s opens
     * tvOS's context menu); a `...Hold` name (`SelectHold`) does the same with a default duration.
     */
    fun sendCommand(name: String, holdMs: Long? = null): Boolean {
        scanDirectionOf(name)?.let { direction ->
            submit("command $name") { c -> scan(c, direction) }
            return true
        }
        val action = AppleTvCommands.forCommand(name, holdMs) ?: return false
        submit("command $name") { action(it) }
        return true
    }

    // ---- fast-forward / rewind ---------------------------------------------------------

    /** +1 for a fast-forward command name, -1 for rewind, null for anything else. */
    private fun scanDirectionOf(name: String): Int? = when (name.lowercase().replace("_", "").replace("-", "").replace(" ", "")) {
        "fastforward", "ff", "forward", "scanforward" -> 1
        "rewind", "rew", "fastrewind", "scanbackward" -> -1
        else -> null
    }

    // What we last started, for when MRP doesn't report a playback rate to tell us.
    @Volatile private var scanning = 0

    /**
     * Classic fast-forward / rewind, like holding ⏩/⏪ on an iPhone remote: the first press
     * starts scanning in [direction] (MRP `BeginFastForward` / `BeginRewind`, which the app's
     * player speeds up on repeated presses); pressing the same one while already scanning that
     * way stops it and resumes normal playback. Without MRP (or if the app refuses), falls back
     * to a 10 s jump so the key still does something useful.
     */
    private fun scan(c: CompanionClient, direction: Int) {
        val mrp = mrpClient?.takeIf { it.isConnected }
        val current = currentScanDirection()
        val command = scanCommand(current, direction)
        val accepted = mrp != null && runCatching { mrp.sendCommand(command) }.getOrDefault(false)
        val what = if (direction > 0) "fast-forward" else "rewind"
        log("[${spec.name}] $what (MRP command $command): ${if (accepted) "accepted" else "not available, 10 s jump"}")
        if (accepted) {
            scanning = if (current == direction) 0 else direction
        } else {
            val step = if (direction > 0) AppleTvCommands.SEEK_STEP_SECONDS else -AppleTvCommands.SEEK_STEP_SECONDS
            AppleTvCommands.skipOrArrow(c, step, if (direction > 0) HidButton.Right else HidButton.Left)
        }
    }

    /** +1 / -1 / 0: from MRP's playback rate when it reports one, else what we last started. */
    private fun currentScanDirection(): Int {
        val rate = snapshot.playing?.nowPlaying?.playbackRate ?: return scanning
        return when {
            rate > 1f -> 1
            rate < 0f -> -1
            else -> 0
        }
    }

    /** Stop when already scanning that way, otherwise start scanning in [direction]. */
    private fun scanCommand(current: Int, direction: Int): Int = when {
        current == direction -> if (direction > 0) Mrp.CMD_END_FAST_FORWARD else Mrp.CMD_END_REWIND
        direction > 0 -> Mrp.CMD_BEGIN_FAST_FORWARD
        else -> Mrp.CMD_BEGIN_REWIND
    }

    /**
     * `media_seek`: first the app's own absolute seek over MRP (`SeekToPlaybackPosition`, what the
     * iPhone remote uses — Prime Video, Plex and other third-party players often ignore
     * Companion's relative jump but honour this), then Companion's `SkipBy` as a fallback when MRP
     * isn't paired/connected or the app refuses. Skipped straight to the fallback when the app has
     * declared it doesn't support seeking.
     */
    private fun seekAction(data: Map<String, Any?>): ((CompanionClient) -> Unit)? {
        val target = (data["seek_position"] as? Number)?.toDouble() ?: return null
        val fallback = AppleTvCommands.seekAction(data) { currentPositionSeconds() }
        return { c ->
            val app = snapshot.playing?.appBundleId
            when {
                app != null && app in relativeSeekApps -> fallback?.invoke(c)
                seekViaMrp(target) -> verifySeek(c, target, app)
                else -> fallback?.invoke(c)
            }
        }
    }

    // Apps seen accepting MRP's absolute seek but landing somewhere else (Canal+ restarts from
    // the beginning): from then on they get Companion's relative SkipBy straight away. In memory
    // only — relearnt with one misplaced seek after an app restart.
    private val relativeSeekApps: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /**
     * Some apps answer "accepted" to `SeekToPlaybackPosition` but ignore the position. Waits for
     * the next position report from MRP; if playback isn't near [target], corrects with a relative
     * SkipBy from where it actually is and remembers [app] as relative-only.
     */
    private fun verifySeek(c: CompanionClient, target: Double, app: String?) {
        val before = snapshot.playing?.nowPlaying?.positionUpdatedAtUnixMs
        val deadline = System.currentTimeMillis() + SEEK_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline && snapshot.playing?.nowPlaying?.positionUpdatedAtUnixMs == before) {
            Thread.sleep(SEEK_VERIFY_POLL_MS)
        }
        if (snapshot.playing?.nowPlaying?.positionUpdatedAtUnixMs == before) return // no fresh report: can't tell
        val now = currentPositionSeconds() ?: return
        if (kotlin.math.abs(now - target) <= SEEK_TOLERANCE_SECONDS) return
        log("[${spec.name}] $app landed at ${now.toInt()} s instead of ${target.toInt()} s: correcting with SkipBy")
        if (app != null) relativeSeekApps.add(app)
        c.skipBy(target - now)
    }

    private fun seekViaMrp(seconds: Double): Boolean {
        val mrp = mrpClient?.takeIf { it.isConnected } ?: return false
        val app = snapshot.playing?.appBundleId
        if (snapshot.playing?.canSeek == false) {
            log("[${spec.name}] seek: $app declares no absolute seek, using SkipBy")
            return false
        }
        val accepted =
            runCatching { mrp.seekTo(seconds) }
                .onFailure { log("[${spec.name}] MRP seek failed: ${it.message}") }
                .getOrDefault(false)
        log("[${spec.name}] seek to ${seconds.toInt()} s via MRP in $app: ${if (accepted) "accepted" else "refused, using SkipBy"}")
        return accepted
    }

    /**
     * Where playback is right now, in seconds: MRP's last reported elapsed time advanced by the
     * wall-clock time since that report (times the playback rate). Null without MRP data.
     */
    private fun currentPositionSeconds(): Double? {
        val np = snapshot.playing?.nowPlaying ?: return null
        val elapsed = np.elapsedTimeSeconds ?: return null
        val updatedAt = np.positionUpdatedAtUnixMs ?: return elapsed
        val rate = if (snapshot.playing?.state == MrpPlayState.Playing) (np.playbackRate ?: 1f).toDouble() else 0.0
        val since = (System.currentTimeMillis() - updatedAt).coerceAtLeast(0L) / 1000.0
        return elapsed + since * rate
    }

    // Services that need no data from the call, keyed by their HA media_player service name.
    private val plainServiceActions: Map<String, (CompanionClient) -> Unit> =
        mapOf(
            "turn_on" to { c -> c.hid(HidButton.Wake, down = false) },
            "turn_off" to { c -> c.hid(HidButton.Sleep, down = false) },
            "toggle" to { c -> if (snapshot.attention.isOn) c.hid(HidButton.Sleep, down = false) else c.hid(HidButton.Wake, down = false) },
            "media_play_pause" to { c -> c.press(HidButton.PlayPause) },
            "media_play" to { c -> AppleTvCommands.playOrFallback(c, MediaCommand.Play) },
            "media_pause" to { c -> AppleTvCommands.playOrFallback(c, MediaCommand.Pause) },
            "media_stop" to { c -> AppleTvCommands.playOrFallback(c, MediaCommand.Pause) },
            "media_next_track" to { c -> c.mediaControl(MediaCommand.NextTrack) },
            "media_previous_track" to { c -> c.mediaControl(MediaCommand.PreviousTrack) },
            "volume_up" to { c -> c.press(HidButton.VolumeUp) },
            "volume_down" to { c -> c.press(HidButton.VolumeDown) }
        )

    // Services that read something out of the call's data.
    private val dataServiceActions: Map<String, (Map<String, Any?>) -> ((CompanionClient) -> Unit)?> =
        mapOf(
            "volume_set" to AppleTvCommands::volumeSetAction,
            "select_source" to { data: Map<String, Any?> -> AppleTvCommands.selectSourceAction(data) { snapshot.apps } },
            "play_media" to AppleTvCommands::playMediaAction,
            "media_seek" to ::seekAction
        )

    /**
     * Executes a Home Assistant `media_player` service (e.g. `select_source`,
     * `media_play_pause`, `volume_set`) against this device. Returns false
     * for services an Apple TV over Companion can't do.
     */
    fun handleService(service: String, data: Map<String, Any?>): Boolean {
        val action = plainServiceActions[service] ?: dataServiceActions[service]?.invoke(data) ?: return false
        submit("service $service", action)
        return true
    }
}
