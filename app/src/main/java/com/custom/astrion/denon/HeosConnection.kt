package com.custom.astrion.denon

import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * The HEOS CLI connection to one receiver (1.5.4), see [HeosProtocol]: opened on [start], kept
 * open (the receiver pushes what changes), opened again after a drop, closed on [stop]. Its reader
 * runs on its own thread; [onChange] is called there with each new state.
 */
class HeosConnection(
    private val host: String,
    private val onChange: (HeosState) -> Unit,
    private val log: (String) -> Unit = {},
    private val port: Int = HeosProtocol.PORT
) {
    @Volatile var state = HeosState()
        private set

    @Volatile private var socket: Socket? = null

    @Volatile private var out: OutputStream? = null

    @Volatile private var running = false

    private var thread: Thread? = null

    fun start() {
        if (running) return
        running = true
        thread = Thread({ loop() }, "HEOS-$host").apply {
            isDaemon = true
            start()
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        thread?.interrupt()
        thread = null
        update { HeosState(favorites = it.favorites) }
    }

    /** Sends a command; [cmd] may hold `{pid}`, the receiver's player. False when not connected. */
    fun send(cmd: String): Boolean {
        val pid = state.pid ?: return false
        val o = out ?: return false
        return runCatching {
            synchronized(o) {
                o.write(cmd.replace("{pid}", pid.toString()).toByteArray())
                o.flush()
            }
        }.isSuccess
    }

    private fun write(line: String) {
        val o = out ?: return
        runCatching {
            synchronized(o) {
                o.write(line.toByteArray())
                o.flush()
            }
        }
    }

    private fun loop() {
        var wait = RETRY_MS
        while (running) {
            runCatching { session() }.onFailure { if (running) log("HEOS $host: ${it.message ?: it.javaClass.simpleName}") }
            out = null
            update { HeosState(favorites = it.favorites) }
            if (!running) break
            runCatching { Thread.sleep(wait) }
            wait = (wait * 2).coerceAtMost(MAX_RETRY_MS)
        }
    }

    private fun session() {
        Socket().use { s ->
            s.connect(InetSocketAddress(host, port), CONNECT_MS)
            s.soTimeout = 0
            s.keepAlive = true
            socket = s
            out = s.getOutputStream()
            write(HeosProtocol.registerEvents())
            write(HeosProtocol.players())
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            while (running) {
                val line = reader.readLine() ?: break
                HeosProtocol.parse(line)?.let(::handle)
            }
        }
    }

    private fun handle(m: HeosProtocol.Message) {
        if (m.pending) return
        val pid = state.pid
        val forUs = m.params["pid"]?.toLongOrNull()?.let { it == pid } ?: true
        when (m.command) {
            "player/get_players" -> players(m)
            "browse/browse" -> if (m.success) update { it.copy(favorites = HeosProtocol.favorites(m)) }
            "event/sources_changed", "event/user_changed" -> write(HeosProtocol.favorites())
            "event/players_changed" -> write(HeosProtocol.players())
            else -> if (forUs) player(m, pid)
        }
    }

    private fun players(m: HeosProtocol.Message) {
        val player = HeosProtocol.playerFor(HeosProtocol.players(m), host) ?: return
        update { it.copy(connected = true, pid = player.pid) }
        refresh(player.pid)
    }

    /** What the receiver's own player says. */
    private fun player(m: HeosProtocol.Message, pid: Long?) {
        when (m.command) {
            "player/get_play_state", "event/player_state_changed" -> update { it.copy(playState = m.params["state"]) }
            "player/get_now_playing_media" -> update { it.copy(nowPlaying = HeosProtocol.nowPlaying(m)) }
            "event/player_now_playing_changed" -> pid?.let { write(HeosProtocol.nowPlaying(it)) }
            "event/player_now_playing_progress" -> progress(m)
            "player/get_play_mode", "event/repeat_mode_changed", "event/shuffle_mode_changed" -> playMode(m)
        }
    }

    private fun refresh(pid: Long) {
        write(HeosProtocol.playState(pid))
        write(HeosProtocol.nowPlaying(pid))
        write(HeosProtocol.playMode(pid))
        write(HeosProtocol.favorites())
    }

    private fun progress(m: HeosProtocol.Message) {
        val pos = m.params["cur_pos"]?.toLongOrNull() ?: return
        val dur = m.params["duration"]?.toLongOrNull()
        update {
            it.copy(
                positionS = pos / MS_PER_S,
                durationS = dur?.let { d -> d / MS_PER_S }?.takeIf { d -> d > 0 },
                positionAt = System.currentTimeMillis()
            )
        }
    }

    private fun playMode(m: HeosProtocol.Message) {
        update { s ->
            s.copy(repeat = m.params["repeat"] ?: s.repeat, shuffle = m.params["shuffle"]?.let { it == "on" } ?: s.shuffle)
        }
    }

    private fun update(change: (HeosState) -> HeosState) {
        val next = change(state)
        if (next == state) return
        state = next
        onChange(next)
    }

    private companion object {
        const val CONNECT_MS = 3_000
        const val RETRY_MS = 5_000L
        const val MAX_RETRY_MS = 60_000L
        const val MS_PER_S = 1000L
    }
}
