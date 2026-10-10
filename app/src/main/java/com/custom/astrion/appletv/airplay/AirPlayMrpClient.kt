package com.custom.astrion.appletv.airplay

import com.custom.astrion.appletv.AppleTvCredentials
import com.custom.astrion.appletv.NowPlayingClient
import com.custom.astrion.appletv.airplay.BinaryPlist.Value
import com.custom.astrion.appletv.airplay.BinaryPlist.asInt
import com.custom.astrion.appletv.airplay.BinaryPlist.asMap
import com.custom.astrion.appletv.mrp.Mrp
import com.custom.astrion.appletv.mrp.MrpMessage
import com.custom.astrion.appletv.mrp.MrpMessages
import com.custom.astrion.appletv.mrp.MrpPlayerStateManager
import com.custom.astrion.appletv.mrp.MrpPlaying
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MRP (title/artist/artwork/position) tunnelled over an AirPlay 2 "remote control" session —
 * tvOS's actual now-playing transport since tvOS 15 removed the old standalone `_mediaremotetv`
 * service MRP used directly. Same end result as the classic [com.custom.astrion.appletv.mrp.MrpClient]
 * (same [Listener] shape, same [MrpPlaying] model, same [MrpPlayerStateManager]), reached through
 * three extra hops instead of one plain socket:
 *
 *  1. Control connection (RTSP, pair-verified, encrypted) — exists only to run SETUP/RECORD.
 *  2. Event channel — set up because tvOS requires it, never actually used afterwards (confirmed
 *     dead in pyatv's own client, which sets it up and then never reads from it either).
 *  3. Data channel — carries the actual MRP protobuf traffic, framed per [AirPlayDataChannel].
 *
 * Every header/body field and HKDF salt/info string below was cross-checked against pyatv's
 * installed source (`protocols/airplay/ap2_session.py`, `protocols/airplay/auth/__init__.py`) in
 * this same environment, not reconstructed from memory alone.
 */
internal class AirPlayMrpClient(
    private val host: String,
    private val controlPort: Int,
    private val credentials: AppleTvCredentials,
    private val clientName: String,
    private val listener: Listener
) : NowPlayingClient {
    interface Listener {
        fun onPlaying(playing: MrpPlaying?)

        fun onDisconnected(error: Throwable?)
    }

    companion object {
        private const val EVENTS_SALT = "Events-Salt"
        private const val EVENTS_WRITE_INFO = "Events-Write-Encryption-Key"
        private const val EVENTS_READ_INFO = "Events-Read-Encryption-Key"
        private const val DATASTREAM_SALT = "DataStream-Salt" // seed appended
        private const val DATASTREAM_OUTPUT_INFO = "DataStream-Output-Encryption-Key"
        private const val DATASTREAM_INPUT_INFO = "DataStream-Input-Encryption-Key"
        private const val CONTROL_SALT = "Control-Salt"
        private const val CONTROL_OUTPUT_INFO = "Control-Write-Encryption-Key"
        private const val CONTROL_INPUT_INFO = "Control-Read-Encryption-Key"
        private const val CLIENT_TYPE_UUID = "1910A70F-DBC0-4242-AF95-115DB30604E1"
        private const val REQUEST_TIMEOUT_MS = 7000L

        // pyatv's AP2Session.start_keep_alive: a POST /feedback on the RTSP *control* connection
        // every 2 seconds. Without it, tvOS tears the control connection (and the event/data
        // channels set up under it) down after its own idle timeout.
        private const val FEEDBACK_INTERVAL_MS = 2000L
        private const val FEEDBACK_MAX_RETRIES = 1

        // The *data* channel's own socket turns out to have an independent idle timeout that the
        // control-channel feedback above does nothing for: confirmed on real hardware by the
        // actual disconnect reason, now that it's logged — "connection closed mid-frame (0/2
        // bytes)" on the data channel's read, i.e. tvOS's own clean FIN, not a decode error —
        // happening consistently around 30 seconds of data-channel silence. pyatv's comment that
        // AirPlay-tunnelled MRP needs no heartbeat ("Already have heartbeat on control channel")
        // evidently assumes enough real traffic already flows on the data channel in practice;
        // sending this at a safely shorter interval than the ~30s timeout keeps it from ever
        // going quiet. Same request, same retry tolerance as the feedback loop above.
        private const val DATA_HEARTBEAT_INTERVAL_MS = 15_000L
        private const val DATA_HEARTBEAT_MAX_RETRIES = 1

        // Matches pyatv's own InfoSettings defaults — a combination already known to be accepted
        // by real tvOS, which matters more here than the exact values (the receiver doesn't care
        // who "Astrion" is, only that every field it expects is present and well-formed).
        private const val OS_NAME = "iPhone OS"
        private const val OS_VERSION = "14.7.1"
        private const val OS_BUILD = "18G82"
        private const val MODEL = "iPhone10,6"
        private const val MAC_ADDRESS = "02:70:79:61:74:76"
        private const val DEVICE_ID = "FF:70:79:61:74:76"
    }

    private val stateManager = MrpPlayerStateManager { listener.onPlaying(it) }
    private val closing = AtomicBoolean(false)
    private val pending = ConcurrentHashMap<String, java.util.concurrent.CompletableFuture<MrpMessage>>()

    private var controlConn: AirPlayConnection? = null
    private var rtspSession: AirPlayRtspSession? = null
    private var dataChannel: AirPlayChannel? = null
    private var eventChannel: AirPlayChannel? = null
    private var dataReaderThread: Thread? = null
    private var eventReaderThread: Thread? = null

    @Volatile private var sendSeqNo: Long = 0

    @Volatile private var feedbackLoop: Thread? = null

    @Volatile private var dataHeartbeat: Thread? = null

    override val isConnected: Boolean get() = controlConn?.isEncrypted == true && !closing.get()
    override val playing: MrpPlaying? get() = stateManager.current

    override fun connect(timeoutMs: Int) {
        val control = AirPlayConnection(host, controlPort)
        try {
            control.connect(timeoutMs)
            val verify = AirPlayPairVerify(control, credentials)
            verify.run()
            val (controlWrite, controlRead) = verify.encryptionKeys(CONTROL_SALT, CONTROL_OUTPUT_INFO, CONTROL_INPUT_INFO)
            control.armEncryption(controlWrite, controlRead)
            controlConn = control

            val rtsp = AirPlayRtspSession(control)
            rtspSession = rtsp
            setupEventChannel(rtsp, verify)
            rtsp.record()
            setupDataChannel(rtsp, verify)
            startDataReader()
            sendInitialDataChannelMessages()
            // Both loops turned out to be needed, confirmed from real-hardware logs: removing the
            // MRP-level heartbeat on pyatv's say-so ("Already have heartbeat on control channel")
            // only swapped one 30-second disconnect for another — the *data* channel's own socket
            // was then the one tvOS closed ("connection closed mid-frame (0/2 bytes)" on a plain
            // read, i.e. the peer's own clean FIN, not a decode error) once nothing was sent on it
            // for a while. The RTSP /feedback loop evidently only keeps the control connection (and
            // whatever it's directly responsible for) alive; the data channel's own idle timeout is
            // independent of it and needs its own periodic traffic, same as classic MrpClient.
            startDataHeartbeat()
            startFeedbackLoop()
        } catch (e: Exception) {
            runCatching { close() }
            throw e
        }
    }

    override fun close() {
        closing.set(true)
        feedbackLoop?.interrupt()
        dataHeartbeat?.interrupt()
        dataReaderThread?.interrupt()
        eventReaderThread?.interrupt()
        pending.values.forEach { it.cancel(false) }
        pending.clear()
        runCatching { eventChannel?.close() }
        runCatching { dataChannel?.close() }
        runCatching { controlConn?.close() }
    }

    private fun setupEventChannel(rtsp: AirPlayRtspSession, verify: AirPlayPairVerify) {
        val response =
            rtsp.setup(
                Value.dict(
                    mapOf(
                        "isRemoteControlOnly" to true,
                        "osName" to OS_NAME,
                        "sourceVersion" to "550.10",
                        "timingProtocol" to "None",
                        "model" to MODEL,
                        "deviceID" to DEVICE_ID,
                        "osVersion" to OS_VERSION,
                        "osBuildVersion" to OS_BUILD,
                        "macAddress" to MAC_ADDRESS,
                        "sessionUUID" to UUID.randomUUID().toString().uppercase(),
                        "name" to clientName
                    )
                )
            )
        val eventPort = response.asMap()["eventPort"]?.asInt() ?: throw AirPlayException("device did not return an eventPort")
        // Confirmed reversed in pyatv's ap2_session.py ("Read/Write info reversed here as
        // connection originates from receiver"): what we encrypt *with* for this channel is
        // derived from EVENTS_READ_INFO, not EVENTS_WRITE_INFO.
        val (write, read) = verify.encryptionKeys(EVENTS_SALT, EVENTS_READ_INFO, EVENTS_WRITE_INFO)
        val channel = AirPlayChannel(host, eventPort, "event")
        channel.connect(REQUEST_TIMEOUT_MS.toInt(), write, read)
        eventChannel = channel
        startEventReader(channel)
    }

    /**
     * Answers every request tvOS sends on the event channel with an empty `200 OK` — see
     * [AirPlayEventChannel]. This channel used to be left unread (on the belief that pyatv
     * doesn't read it either — it does, `EventChannel.handle_received`), and the unanswered
     * requests made tvOS drop the whole session about every 30 seconds.
     */
    private fun startEventReader(channel: AirPlayChannel) {
        val parser = AirPlayEventChannel()
        eventReaderThread =
            Thread({
                try {
                    while (!closing.get()) {
                        parser.feed(channel.receiveFrame()).forEach { request ->
                            channel.send(AirPlayEventChannel.okResponse(request))
                        }
                    }
                } catch (e: Exception) {
                    if (closing.compareAndSet(false, true)) listener.onDisconnected(e)
                }
            }, "atv-airplay-events-$host").apply {
                isDaemon = true
                start()
            }
    }

    private fun setupDataChannel(rtsp: AirPlayRtspSession, verify: AirPlayPairVerify) {
        val seed = (0..Long.MAX_VALUE).random()
        val response =
            rtsp.setup(
                Value.dict(
                    mapOf(
                        "streams" to
                            listOf(
                                mapOf(
                                    "controlType" to 2,
                                    "channelID" to UUID.randomUUID().toString().uppercase(),
                                    "seed" to seed,
                                    "clientUUID" to UUID.randomUUID().toString().uppercase(),
                                    "type" to 130,
                                    "wantsDedicatedSocket" to true,
                                    "clientTypeUUID" to CLIENT_TYPE_UUID
                                )
                            )
                    )
                )
            )
        val streams = response.asMap()["streams"]?.let { (it as? Value.Arr)?.value } ?: emptyList()
        val dataPort = streams.firstOrNull()?.asMap()?.get("dataPort")?.asInt()
            ?: throw AirPlayException("device did not return a dataPort")
        val (write, read) = verify.encryptionKeys(DATASTREAM_SALT + seed, DATASTREAM_OUTPUT_INFO, DATASTREAM_INPUT_INFO)
        val channel = AirPlayChannel(host, dataPort, "data")
        channel.connect(REQUEST_TIMEOUT_MS.toInt(), write, read)
        dataChannel = channel
        sendSeqNo = (0..Long.MAX_VALUE).random() // one fixed value for the whole connection, matching pyatv
    }

    private fun startDataReader() {
        val assembler = AirPlayDataChannel.FrameAssembler()
        dataReaderThread =
            Thread({
                try {
                    while (!closing.get()) {
                        val frame = dataChannel?.receiveFrame() ?: return@Thread
                        assembler.feed(frame).forEach(::handleDataChannelMessage)
                    }
                } catch (e: Exception) {
                    if (!closing.get()) {
                        closing.set(true)
                        listener.onDisconnected(e)
                    }
                }
            }, "atv-airplay-mrp-$host").apply {
                isDaemon = true
                start()
            }
    }

    /**
     * Every "sync"-type data-channel message — whether it's the actual MRP-level reply to one of
     * our own requests, or an unsolicited push like a `SetStateMessage` — requires a bare "rply"
     * transport-level acknowledgement echoing its own seqno, sent *before* (or at least
     * regardless of) whatever MRP-level processing follows: pyatv's own client does this
     * unconditionally in `BaseDataStreamChannel.handle_received`, and real tvOS hardware won't
     * send anything further on this channel until it gets that ack for what it last sent.
     */
    private fun handleDataChannelMessage(message: AirPlayDataChannel.Decoded) {
        if (message.messageType.startsWith("sync")) {
            runCatching { dataChannel?.send(AirPlayDataChannel.encodeReply(message.seqNo)) }
        }
        message.protobufMessages.forEach(::handleIncoming)
    }

    private fun handleIncoming(raw: ByteArray) {
        val message = MrpMessage.parse(raw)
        val identifier = message.identifier
        if (identifier != null) {
            pending.remove(identifier)?.complete(message)
        }
        stateManager.handle(message)
    }

    private fun send(protobuf: ByteArray) {
        val channel = dataChannel ?: throw AirPlayException("data channel not connected")
        channel.send(AirPlayDataChannel.encodeSend(sendSeqNo, listOf(protobuf)))
    }

    /** Sends [build]'s message and blocks for the reply matching its identifier. */
    private fun request(build: (String) -> ByteArray): MrpMessage {
        val id = MrpMessages.newIdentifier()
        val future = java.util.concurrent.CompletableFuture<MrpMessage>()
        pending[id] = future
        send(build(id))
        return try {
            future.get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: Exception) {
            pending.remove(id)
            throw AirPlayException("no reply to MRP request $id", e)
        }
    }

    private fun sendInitialDataChannelMessages() {
        request { MrpMessages.deviceInfo(clientName, credentials.clientId.toString(Charsets.US_ASCII), it) }
        // Without this, tvOS keeps the session alive but never pushes a full SetStateMessage
        // (title/artist/artwork) — only once it's told a remote is actually "connected" does it
        // start pushing now-playing updates, matching classic MrpClient.connect()'s own ordering.
        send(MrpMessages.setConnectionStateConnected())
        request { MrpMessages.clientUpdatesConfig(it) }
        request { MrpMessages.getKeyboardSession(it) }
    }

    /** Same absolute seek as classic MRP, over this tunnel. */
    override fun seekTo(seconds: Double): Boolean = MrpMessages.commandSucceeded(request { MrpMessages.seekToPosition(seconds, it) })

    override fun sendCommand(command: Int): Boolean = MrpMessages.commandSucceeded(request { MrpMessages.command(command, it) })

    /** Same artwork fetch as classic MRP — same request message, just sent over this tunnel. */
    override fun fetchArtwork(location: Int, width: Double, height: Double): ByteArray? {
        val reply = request { MrpMessages.playbackQueueRequest(location, width, height, it) }
        val queue = reply.inner(Mrp.EXT_SET_STATE)?.message(Mrp.SS_PLAYBACK_QUEUE) ?: return null
        val item = queue.repeatedMessages(Mrp.PQ_CONTENT_ITEMS).getOrNull(queue.int32(Mrp.PQ_LOCATION) ?: 0) ?: return null
        return item.bytesField(Mrp.CI_ARTWORK_DATA)?.takeIf { it.isNotEmpty() }
    }

    /** Keeps tvOS from tearing down the whole remote-control session — see [FEEDBACK_INTERVAL_MS]. */
    private fun startFeedbackLoop() {
        feedbackLoop = startKeepAliveLoop("feedback", FEEDBACK_INTERVAL_MS, FEEDBACK_MAX_RETRIES) { rtspSession?.feedback() }
    }

    /** Keeps the data channel's own socket from idling out — see [DATA_HEARTBEAT_INTERVAL_MS]. */
    private fun startDataHeartbeat() {
        dataHeartbeat =
            startKeepAliveLoop("data-heartbeat", DATA_HEARTBEAT_INTERVAL_MS, DATA_HEARTBEAT_MAX_RETRIES) {
                request { MrpMessages.heartbeat(it) }
            }
    }

    /**
     * Runs [action] every [intervalMs] for as long as this client is connected, tolerating up to
     * [maxRetries] consecutive failures before giving up — matching pyatv's own `heartbeater`
     * utility default (`HEARTBEAT_RETRIES = 1`): a failed attempt is retried immediately, with no
     * further sleep, so one transient failure alone never tears the session down.
     */
    private fun startKeepAliveLoop(name: String, intervalMs: Long, maxRetries: Int, action: () -> Unit): Thread = Thread({
        var attempts = 0
        while (!closing.get()) {
            try {
                if (attempts == 0) Thread.sleep(intervalMs)
                action()
                attempts = 0
            } catch (_: InterruptedException) {
                return@Thread
            } catch (e: Exception) {
                attempts++
                if (attempts <= maxRetries) continue
                if (!closing.compareAndSet(false, true)) return@Thread
                listener.onDisconnected(e)
                return@Thread
            }
        }
    }, "atv-airplay-mrp-$name-$host").apply {
        isDaemon = true
        start()
    }
}
