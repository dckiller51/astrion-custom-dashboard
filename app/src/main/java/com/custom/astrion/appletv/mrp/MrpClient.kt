package com.custom.astrion.appletv.mrp

import com.custom.astrion.appletv.AppleTvCredentials
import com.custom.astrion.appletv.CompanionException
import com.custom.astrion.appletv.NowPlayingClient
import java.util.concurrent.atomic.AtomicBoolean

/**
 * An authenticated, encrypted MRP session with one Apple TV, used for what Companion can't tell
 * us: the title, artist, album, position, playback state and foreground app of whatever is
 * playing, plus its artwork. Remote-control keys stay on the Companion link.
 *
 * Handshake, in the order pyatv performs it (the Apple TV ignores anything sent out of order):
 * `DeviceInfoMessage` → pair-verify → `SetConnectionState(Connected)` → `ClientUpdatesConfig` →
 * `GetKeyboardSession`. After that the device pushes its media state on its own, and a heartbeat
 * every 30 s keeps the connection from being dropped. All calls block; keep them off the UI thread.
 */
class MrpClient(
    private val host: String,
    private val port: Int,
    private val credentials: AppleTvCredentials,
    private val clientName: String,
    private val listener: Listener,
    private val heartbeatIntervalMs: Long = HEARTBEAT_INTERVAL_MS
) : NowPlayingClient {
    interface Listener {
        /** The active app/player's state changed; null when nothing is active. Called on the reader thread. */
        fun onPlaying(playing: MrpPlaying?)

        /** The connection closed (after a successful [connect]) or failed. */
        fun onDisconnected(error: Throwable?)
    }

    companion object {
        private const val HEARTBEAT_INTERVAL_MS = 30_000L
    }

    private val stateManager = MrpPlayerStateManager { listener.onPlaying(it) }
    private val closing = AtomicBoolean(false)
    private val conn =
        MrpConnection(
            host,
            port,
            object : MrpConnection.Listener {
                override fun onMessage(message: MrpMessage) {
                    stateManager.handle(message)
                }

                override fun onClosed(error: Throwable?) {
                    closing.set(true)
                    heartbeat?.interrupt()
                    listener.onDisconnected(error)
                }
            }
        )

    @Volatile private var heartbeat: Thread? = null

    override val isConnected: Boolean get() = conn.isOpen

    /** What's playing according to everything received so far. */
    override val playing: MrpPlaying? get() = stateManager.current

    override fun connect(timeoutMs: Int) {
        conn.connect(timeoutMs)
        try {
            conn.exchangeDeviceInfo(clientName, credentials.clientId.toString(Charsets.US_ASCII))
            MrpPairVerify.run(conn, credentials)
            conn.send(MrpMessages.setConnectionStateConnected())
            request { MrpMessages.clientUpdatesConfig(it) }
            request { MrpMessages.getKeyboardSession(it) }
        } catch (e: Exception) {
            conn.close()
            throw e
        }
        startHeartbeat()
    }

    override fun close() {
        closing.set(true)
        heartbeat?.interrupt()
        conn.close()
    }

    private inline fun request(build: (String) -> ByteArray): MrpMessage {
        val id = MrpMessages.newIdentifier()
        return conn.sendAndReceive(build(id), id)
    }

    /**
     * Fetches the current item's artwork straight from the Apple TV (`PlaybackQueueRequest`), or
     * null if the device has none to give. [width]/[height] are hints; -1 means "any".
     */
    override fun fetchArtwork(location: Int, width: Double, height: Double): ByteArray? {
        val reply = request { MrpMessages.playbackQueueRequest(location, width, height, it) }
        val queue = reply.inner(Mrp.EXT_SET_STATE)?.message(Mrp.SS_PLAYBACK_QUEUE) ?: return null
        val item = queue.repeatedMessages(Mrp.PQ_CONTENT_ITEMS).getOrNull(queue.int32(Mrp.PQ_LOCATION) ?: 0) ?: return null
        return item.bytesField(Mrp.CI_ARTWORK_DATA)?.takeIf { it.isNotEmpty() }
    }

    override fun seekTo(seconds: Double): Boolean = MrpMessages.commandSucceeded(request { MrpMessages.seekToPosition(seconds, it) })

    override fun sendCommand(command: Int): Boolean = MrpMessages.commandSucceeded(request { MrpMessages.command(command, it) })

    private fun startHeartbeat() {
        heartbeat =
            Thread({
                while (!closing.get()) {
                    try {
                        Thread.sleep(heartbeatIntervalMs)
                        request { MrpMessages.heartbeat(it) }
                    } catch (_: InterruptedException) {
                        return@Thread
                    } catch (e: CompanionException) {
                        // A missed heartbeat means the link is dead; pass the real reason through so
                        // the owner's onDisconnected (and its logs) reflect why, not just that it happened.
                        conn.close(e)
                        return@Thread
                    }
                }
            }, "mrp-heartbeat-$host").apply {
                isDaemon = true
                start()
            }
    }
}
