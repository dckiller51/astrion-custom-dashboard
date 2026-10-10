package com.custom.astrion.appletv.mrp

import java.util.UUID

/**
 * Field numbers this client needs, taken from pyatv's `.proto` sources (proto2; every
 * "extend ProtocolMessage" block declares the number its message is attached to the envelope
 * under — these do NOT match the `ProtocolMessage.Type` enum values, they're a separate space).
 */
object Mrp {
    // ProtocolMessage's own (non-extension) fields.
    const val F_TYPE = 1
    const val F_IDENTIFIER = 2
    const val F_ERROR_CODE = 4
    const val F_UNIQUE_IDENTIFIER = 85

    // ProtocolMessage.Type enum values relevant here.
    const val TYPE_GET_KEYBOARD_SESSION = 24
    const val TYPE_CRYPTO_PAIRING = 34
    const val TYPE_DEVICE_INFO = 15
    const val TYPE_CLIENT_UPDATES_CONFIG = 16
    const val TYPE_SET_STATE = 4
    const val TYPE_SET_ARTWORK = 5
    const val TYPE_SET_CONNECTION_STATE = 38
    const val TYPE_GENERIC = 42
    const val TYPE_SET_NOW_PLAYING_CLIENT = 46
    const val TYPE_SET_NOW_PLAYING_PLAYER = 47
    const val TYPE_REMOVE_CLIENT = 53
    const val TYPE_REMOVE_PLAYER = 54
    const val TYPE_UPDATE_CLIENT = 55
    const val TYPE_UPDATE_CONTENT_ITEM = 56
    const val TYPE_PLAYBACK_QUEUE_REQUEST = 32
    const val TYPE_SEND_COMMAND = 1
    const val TYPE_SEND_COMMAND_RESULT = 2

    // Extension field numbers (ProtocolMessage field carrying each message type's payload).
    const val EXT_DEVICE_INFO = 20
    const val EXT_CRYPTO_PAIRING = 39
    const val EXT_CLIENT_UPDATES_CONFIG = 21
    const val EXT_SET_CONNECTION_STATE = 42
    const val EXT_SET_STATE = 9
    const val EXT_SET_ARTWORK = 10
    const val EXT_GENERIC = 46
    const val EXT_SET_NOW_PLAYING_CLIENT = 50
    const val EXT_SET_NOW_PLAYING_PLAYER = 51
    const val EXT_REMOVE_CLIENT = 57
    const val EXT_REMOVE_PLAYER = 58
    const val EXT_UPDATE_CLIENT = 59
    const val EXT_UPDATE_CONTENT_ITEM = 60
    const val EXT_PLAYBACK_QUEUE_REQUEST = 37
    const val EXT_SEND_COMMAND = 6
    const val EXT_SEND_COMMAND_RESULT = 7

    // SendCommandMessage / CommandOptions / SendCommandResultMessage fields (pyatv's protobuf
    // definitions). The result's two status fields are 0 when the app accepted the command.
    const val SC_COMMAND = 1
    const val SC_OPTIONS = 2
    const val CO_PLAYBACK_POSITION = 9
    const val SCR_SEND_ERROR = 1
    const val SCR_HANDLER_RETURN_STATUS = 2

    // Command enum values.
    const val CMD_SEEK_TO_PLAYBACK_POSITION = 45
    const val CMD_BEGIN_FAST_FORWARD = 9
    const val CMD_END_FAST_FORWARD = 10
    const val CMD_BEGIN_REWIND = 11
    const val CMD_END_REWIND = 12

    // SetStateMessage.supportedCommands → SupportedCommands.supportedCommands (repeated CommandInfo).
    const val SS_SUPPORTED_COMMANDS = 2
    const val SUP_COMMANDS = 1
    const val CMDI_COMMAND = 1
    const val CMDI_ENABLED = 2

    // DeviceInfoMessage fields.
    const val DI_UNIQUE_IDENTIFIER = 1
    const val DI_NAME = 2
    const val DI_LOCALIZED_MODEL_NAME = 3
    const val DI_SYSTEM_BUILD_VERSION = 4
    const val DI_APPLICATION_BUNDLE_IDENTIFIER = 5
    const val DI_APPLICATION_BUNDLE_VERSION = 6
    const val DI_PROTOCOL_VERSION = 7
    const val DI_LAST_SUPPORTED_MESSAGE_TYPE = 8
    const val DI_SUPPORTS_SYSTEM_PAIRING = 9
    const val DI_ALLOWS_PAIRING = 10
    const val DI_SYSTEM_MEDIA_APPLICATION = 12
    const val DI_SUPPORTS_ACL = 13
    const val DI_SUPPORTS_SHARED_QUEUE = 14
    const val DI_SHARED_QUEUE_VERSION = 17
    const val DI_DEVICE_CLASS = 21
    const val DI_LOGICAL_DEVICE_COUNT = 22
    const val DI_SUPPORTS_EXTENDED_MOTION = 15
    const val DEVICE_CLASS_IPHONE = 1

    // CryptoPairingMessage fields.
    const val CP_PAIRING_DATA = 1
    const val CP_STATUS = 2
    const val CP_IS_RETRYING = 3
    const val CP_IS_USING_SYSTEM_PAIRING = 4
    const val CP_STATE = 5

    // ClientUpdatesConfigMessage fields.
    const val CUC_ARTWORK = 1
    const val CUC_NOW_PLAYING = 2
    const val CUC_VOLUME = 3
    const val CUC_KEYBOARD = 4
    const val CUC_OUTPUT_DEVICE = 5

    // SetConnectionStateMessage fields.
    const val SCS_STATE = 1
    const val CONNECTION_STATE_CONNECTED = 2

    // SetStateMessage fields.
    const val SS_PLAYBACK_QUEUE = 3
    const val SS_DISPLAY_ID = 4
    const val SS_DISPLAY_NAME = 5
    const val SS_PLAYBACK_STATE = 6
    const val SS_PLAYER_PATH = 9

    // PlaybackQueue fields.
    const val PQ_LOCATION = 1
    const val PQ_CONTENT_ITEMS = 2

    // ContentItem fields.
    const val CI_IDENTIFIER = 1
    const val CI_METADATA = 2
    const val CI_ARTWORK_DATA = 3

    // SetNowPlayingClient/Player, RemoveClient/Player, UpdateClient, UpdateContentItem payloads.
    const val CLIENT_MSG_CLIENT = 1
    const val PLAYER_MSG_PLAYER_PATH = 1
    const val RP_PLAYER_PATH = 1
    const val UCI_CONTENT_ITEMS = 1
    const val UCI_PLAYER_PATH = 2

    // PlaybackQueueRequestMessage fields.
    const val PQR_LOCATION = 1
    const val PQR_LENGTH = 2
    const val PQR_ARTWORK_WIDTH = 4
    const val PQR_ARTWORK_HEIGHT = 5
    const val PQR_RETURN_ASSETS = 13

    // NowPlayingPlayer.identifier
    const val NPP_IDENTIFIER = 1
    const val DEFAULT_PLAYER_ID = "MediaRemote-DefaultPlayer"

    // ContentItemMetadata fields actually populated by modern tvOS (the older, flat
    // `SetStateMessage.nowPlayingInfo` field is not — pyatv's own real client reads
    // exclusively from here, confirmed against its source).
    const val CIM_TITLE = 1
    const val CIM_ALBUM_NAME = 6
    const val CIM_TRACK_ARTIST_NAME = 7
    const val CIM_DURATION = 14
    const val CIM_ARTWORK_AVAILABLE = 19
    const val CIM_ELAPSED_TIME = 35
    const val CIM_GENRE = 36
    const val CIM_PLAYBACK_RATE = 39
    const val CIM_ELAPSED_TIME_TIMESTAMP = 74
    const val CIM_ARTWORK_URL = 70
    const val CIM_ARTWORK_IDENTIFIER = 80
    const val CIM_ARTWORK_MIME_TYPE = 31

    /** Seconds between the Cocoa epoch (2001-01-01) `elapsedTimeTimestamp` is in and the Unix epoch. */
    const val COCOA_EPOCH_OFFSET_SECONDS = 978_307_200L

    // PlayerPath / NowPlayingClient / NowPlayingPlayer fields.
    const val PP_CLIENT = 2
    const val PP_PLAYER = 3
    const val NPC_BUNDLE_IDENTIFIER = 2
    const val NPC_DISPLAY_NAME = 7
    const val NPP_DISPLAY_NAME = 2

    // SetArtworkMessage fields.
    const val SA_JPEG_DATA = 1

    // Common.PlaybackState.Enum values.
    const val PLAYBACK_UNKNOWN = 0
    const val PLAYBACK_PLAYING = 1
    const val PLAYBACK_PAUSED = 2
    const val PLAYBACK_STOPPED = 3
    const val PLAYBACK_INTERRUPTED = 4
    const val PLAYBACK_SEEKING = 5
}

/** One parsed top-level `ProtocolMessage`. */
class MrpMessage(val type: Int, val identifier: String?, val uniqueIdentifier: String?, val raw: ProtobufWire.ProtoMessage) {
    fun inner(extensionField: Int): ProtobufWire.ProtoMessage? = raw.message(extensionField)

    companion object {
        fun parse(data: ByteArray): MrpMessage {
            val raw = ProtobufWire.parse(data)
            return MrpMessage(raw.int32(Mrp.F_TYPE) ?: 0, raw.string(Mrp.F_IDENTIFIER), raw.string(Mrp.F_UNIQUE_IDENTIFIER), raw)
        }
    }
}

/** Builds MRP `ProtocolMessage`s exactly as pyatv's `messages.py` does — same fields, same values. */
object MrpMessages {
    /** A fresh envelope of [type], with a random `uniqueIdentifier` (every real message carries one) and no `identifier`. */
    private fun envelope(type: Int): ProtobufWire.Builder = ProtobufWire.Builder()
        .int32(Mrp.F_TYPE, type)
        .varint(Mrp.F_ERROR_CODE, 0)
        .string(Mrp.F_UNIQUE_IDENTIFIER, UUID.randomUUID().toString().uppercase())

    /** A fresh request identifier; pass the same value to the builder and to
     * [MrpConnection.sendAndReceive] so the reply can be matched back. */
    fun newIdentifier(): String = UUID.randomUUID().toString().uppercase()

    private fun withIdentifier(builder: ProtobufWire.Builder, identifier: String): ProtobufWire.Builder =
        builder.string(Mrp.F_IDENTIFIER, identifier)

    fun deviceInfo(name: String, pairingId: String, identifier: String, systemBuildVersion: String = "20J5354c"): ByteArray {
        val info = ProtobufWire.Builder()
            .bool(Mrp.DI_ALLOWS_PAIRING, true)
            .string(Mrp.DI_APPLICATION_BUNDLE_IDENTIFIER, "com.apple.TVRemote")
            .string(Mrp.DI_APPLICATION_BUNDLE_VERSION, "344.28")
            .int32(Mrp.DI_LAST_SUPPORTED_MESSAGE_TYPE, 108)
            .string(Mrp.DI_LOCALIZED_MODEL_NAME, "iPhone")
            .string(Mrp.DI_NAME, name)
            .int32(Mrp.DI_PROTOCOL_VERSION, 1)
            .int32(Mrp.DI_SHARED_QUEUE_VERSION, 2)
            .bool(Mrp.DI_SUPPORTS_ACL, true)
            .bool(Mrp.DI_SUPPORTS_EXTENDED_MOTION, true)
            .bool(Mrp.DI_SUPPORTS_SHARED_QUEUE, true)
            .bool(Mrp.DI_SUPPORTS_SYSTEM_PAIRING, true)
            .string(Mrp.DI_SYSTEM_BUILD_VERSION, systemBuildVersion)
            .string(Mrp.DI_SYSTEM_MEDIA_APPLICATION, "com.apple.TVMusic")
            .string(Mrp.DI_UNIQUE_IDENTIFIER, pairingId)
            .enumValue(Mrp.DI_DEVICE_CLASS, Mrp.DEVICE_CLASS_IPHONE)
            .int32(Mrp.DI_LOGICAL_DEVICE_COUNT, 1)
        return withIdentifier(envelope(Mrp.TYPE_DEVICE_INFO).message(Mrp.EXT_DEVICE_INFO, info), identifier).build()
    }

    fun setConnectionStateConnected(): ByteArray {
        val inner = ProtobufWire.Builder().enumValue(Mrp.SCS_STATE, Mrp.CONNECTION_STATE_CONNECTED)
        return envelope(Mrp.TYPE_SET_CONNECTION_STATE).message(Mrp.EXT_SET_CONNECTION_STATE, inner).build()
    }

    /** Matches pyatv's own defaults exactly (`now_playing=false`) — SetStateMessage still arrives
     * regardless; this is what a real, working client sends. */
    fun clientUpdatesConfig(identifier: String): ByteArray {
        val inner = ProtobufWire.Builder()
            .bool(Mrp.CUC_ARTWORK, true)
            .bool(Mrp.CUC_NOW_PLAYING, false)
            .bool(Mrp.CUC_VOLUME, true)
            .bool(Mrp.CUC_KEYBOARD, true)
            .bool(Mrp.CUC_OUTPUT_DEVICE, true)
        return withIdentifier(envelope(Mrp.TYPE_CLIENT_UPDATES_CONFIG).message(Mrp.EXT_CLIENT_UPDATES_CONFIG, inner), identifier).build()
    }

    /** `GetKeyboardSessionMessage` is empty on the wire — no extension payload, matching pyatv's own `get_keyboard_session()`. */
    fun getKeyboardSession(identifier: String): ByteArray = withIdentifier(envelope(Mrp.TYPE_GET_KEYBOARD_SESSION), identifier).build()

    /** Heartbeat: an empty `GenericMessage`, sent with `send_and_receive` — matches pyatv's `heartbeat_loop`. */
    fun heartbeat(identifier: String): ByteArray = withIdentifier(envelope(Mrp.TYPE_GENERIC), identifier).build()

    /** Asks the Apple TV for the item at [location] *with* its artwork (its reply carries
     * `artworkData`); width/height are hints, -1 meaning "any". */
    fun playbackQueueRequest(location: Int, width: Double, height: Double, identifier: String): ByteArray {
        val inner = ProtobufWire.Builder()
            .int32(Mrp.PQR_LOCATION, location)
            .int32(Mrp.PQR_LENGTH, 1)
            .double(Mrp.PQR_ARTWORK_WIDTH, width)
            .double(Mrp.PQR_ARTWORK_HEIGHT, height)
            .bool(Mrp.PQR_RETURN_ASSETS, true)
        return withIdentifier(envelope(Mrp.TYPE_PLAYBACK_QUEUE_REQUEST).message(Mrp.EXT_PLAYBACK_QUEUE_REQUEST, inner), identifier).build()
    }

    /**
     * `SendCommandMessage(SeekToPlaybackPosition, playbackPosition = [seconds])` — what pyatv's
     * `set_position` and the iPhone's remote send. An absolute position, handled by the app's own
     * player, unlike Companion's relative `SkipBy`.
     */
    fun seekToPosition(seconds: Double, identifier: String): ByteArray {
        val options = ProtobufWire.Builder().double(Mrp.CO_PLAYBACK_POSITION, seconds)
        val inner =
            ProtobufWire.Builder()
                .enumValue(Mrp.SC_COMMAND, Mrp.CMD_SEEK_TO_PLAYBACK_POSITION)
                .message(Mrp.SC_OPTIONS, options)
        return withIdentifier(envelope(Mrp.TYPE_SEND_COMMAND).message(Mrp.EXT_SEND_COMMAND, inner), identifier).build()
    }

    /** A `SendCommandMessage` for a bare [command] with no options (fast-forward/rewind start/stop). */
    fun command(command: Int, identifier: String): ByteArray {
        val inner = ProtobufWire.Builder().enumValue(Mrp.SC_COMMAND, command)
        return withIdentifier(envelope(Mrp.TYPE_SEND_COMMAND).message(Mrp.EXT_SEND_COMMAND, inner), identifier).build()
    }

    /**
     * Whether a `SendCommandResultMessage` reply says the command was carried out: both its send
     * error and the app's handler status are absent or 0. Anything else (no such content, command
     * failed, skipping prohibited during an ad, ...) is a refusal.
     */
    fun commandSucceeded(reply: MrpMessage): Boolean {
        val result = reply.inner(Mrp.EXT_SEND_COMMAND_RESULT) ?: return reply.type == Mrp.TYPE_SEND_COMMAND_RESULT
        return (result.enumValue(Mrp.SCR_SEND_ERROR) ?: 0) == 0 && (result.enumValue(Mrp.SCR_HANDLER_RETURN_STATUS) ?: 0) == 0
    }

    /** Wraps a HAP pair-setup/pair-verify TLV8 blob for MRP's crypto-pairing envelope. [state]: 2
     * while pairing (pair-setup), 0 during pair-verify — matches pyatv. */
    fun cryptoPairing(tlv: ByteArray, isPairing: Boolean): ByteArray {
        val inner = ProtobufWire.Builder()
            .int32(Mrp.CP_STATUS, 0)
            .bytes(Mrp.CP_PAIRING_DATA, tlv)
            .bool(Mrp.CP_IS_RETRYING, false)
            .bool(Mrp.CP_IS_USING_SYSTEM_PAIRING, false)
            .int32(Mrp.CP_STATE, if (isPairing) 2 else 0)
        return envelope(Mrp.TYPE_CRYPTO_PAIRING).message(Mrp.EXT_CRYPTO_PAIRING, inner).build()
    }
}

/** Parsed `ContentItemMetadata` for the item actually playing: title/artist/album/duration/position. */
data class MrpNowPlaying(
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val durationSeconds: Double?,
    /** The item's elapsed time as of [positionUpdatedAtUnixMs] — not continuously updated;
     * combine both, the same way Home Assistant's own `media_position`/`media_position_updated_at`
     * pair works. */
    val elapsedTimeSeconds: Double?,
    val positionUpdatedAtUnixMs: Long?,
    val playbackRate: Float?,
    val artworkAvailable: Boolean?,
    /** Often an iTunes-style image URL template (`{w}`/`{h}`/`{c}`/`{f}` placeholders). */
    val artworkIdentifier: String?,
    val artworkUrl: String?,
    val artworkMimeType: String?
)

object MrpParsers {
    fun contentItemMetadata(metadata: ProtobufWire.ProtoMessage): MrpNowPlaying = MrpNowPlaying(
        title = metadata.string(Mrp.CIM_TITLE),
        artist = metadata.string(Mrp.CIM_TRACK_ARTIST_NAME),
        album = metadata.string(Mrp.CIM_ALBUM_NAME),
        genre = metadata.string(Mrp.CIM_GENRE),
        durationSeconds = metadata.fixed64AsDouble(Mrp.CIM_DURATION),
        elapsedTimeSeconds = metadata.fixed64AsDouble(Mrp.CIM_ELAPSED_TIME),
        positionUpdatedAtUnixMs = metadata.fixed64AsDouble(Mrp.CIM_ELAPSED_TIME_TIMESTAMP)
            ?.let { cocoa -> ((cocoa + Mrp.COCOA_EPOCH_OFFSET_SECONDS) * 1000).toLong() },
        playbackRate = metadata.fixed32AsFloat(Mrp.CIM_PLAYBACK_RATE),
        artworkAvailable = metadata.bool(Mrp.CIM_ARTWORK_AVAILABLE),
        artworkIdentifier = metadata.string(Mrp.CIM_ARTWORK_IDENTIFIER),
        artworkUrl = metadata.string(Mrp.CIM_ARTWORK_URL),
        artworkMimeType = metadata.string(Mrp.CIM_ARTWORK_MIME_TYPE)
    )
}
