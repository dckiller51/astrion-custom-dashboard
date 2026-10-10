package com.custom.astrion.appletv.mrp

/** Coarse playback state, in the terms a Home-Assistant-style `media_player` uses. */
enum class MrpPlayState { Idle, Playing, Paused, Loading }

/** What is playing right now, as seen from the active app's active player. */
data class MrpPlaying(
    val state: MrpPlayState,
    val nowPlaying: MrpNowPlaying?,
    val appBundleId: String?,
    val appName: String?,
    val location: Int,
    val itemIdentifier: String?,
    /**
     * Whether the active app accepts an absolute seek (MRP `SeekToPlaybackPosition` among the
     * commands it declares, and enabled). Null when the app hasn't declared its commands at all.
     */
    val canSeek: Boolean? = null
)

/**
 * Tracks the Apple TV's media state the way MRP actually delivers it — a port of pyatv's
 * `PlayerStateManager`, kept deliberately close to it because the wire behaviour is subtle:
 *
 *  - The device can have several *clients* (apps), each with several *players*, all sending
 *    updates; which one counts as "now playing" is announced separately
 *    (`SetNowPlayingClient/Player`), not implied by whichever spoke last. Showing the latest
 *    message would put a background app's data on screen.
 *  - `SetStateMessage`s are *partial*: a playback-state change often arrives without the queue
 *    (and vice versa), so what's known about a player has to persist between messages.
 *  - Position/metadata refreshes arrive as `UpdateContentItemMessage`s that are merged into the
 *    item already known.
 *
 * Not thread-safe by itself: callers feed it from a single reader thread.
 */
class MrpPlayerStateManager(private val onChange: (MrpPlaying?) -> Unit) {
    private class Item(val identifier: String?, var metadata: ProtobufWire.ProtoMessage?)

    private class Player(val id: String) {
        var playbackState: Int? = null
        var location = 0
        var items: List<Item> = emptyList()

        /** Declared commands → enabled; null until a SetStateMessage carries them. */
        var supportedCommands: Map<Int, Boolean>? = null

        val current: Item? get() = items.getOrNull(location)
    }

    private class Client(val bundleId: String) {
        var displayName: String? = null
        val players = LinkedHashMap<String, Player>()
        var activePlayerId: String? = null

        fun player(id: String): Player = players.getOrPut(id) { Player(id) }

        /** The player announced as active, else the default player, else nothing (pyatv falls back to an empty player). */
        val activePlayer: Player? get() = activePlayerId?.let { players[it] } ?: players[Mrp.DEFAULT_PLAYER_ID]
    }

    private val clients = LinkedHashMap<String, Client>()
    private var activeClient: Client? = null
    private var lastReported: MrpPlaying? = null
    private var reportedOnce = false

    /** Feeds one incoming message in; returns true if it was a type this manager handles. */
    fun handle(message: MrpMessage): Boolean {
        // proto2 omits an empty submessage from the wire entirely (e.g. "nothing playing" is a
        // SetNowPlayingClient whose client is empty), and pyatv reads that as an empty message,
        // not as "no message" — so a missing payload is handled as an empty one.
        fun inner(extension: Int) = message.inner(extension) ?: ProtobufWire.EMPTY
        when (message.type) {
            Mrp.TYPE_SET_STATE -> setState(inner(Mrp.EXT_SET_STATE))
            Mrp.TYPE_UPDATE_CONTENT_ITEM -> updateContentItem(inner(Mrp.EXT_UPDATE_CONTENT_ITEM))
            Mrp.TYPE_SET_NOW_PLAYING_CLIENT -> setNowPlayingClient(inner(Mrp.EXT_SET_NOW_PLAYING_CLIENT))
            Mrp.TYPE_SET_NOW_PLAYING_PLAYER -> setNowPlayingPlayer(inner(Mrp.EXT_SET_NOW_PLAYING_PLAYER))
            Mrp.TYPE_UPDATE_CLIENT -> updateClient(inner(Mrp.EXT_UPDATE_CLIENT))
            Mrp.TYPE_REMOVE_CLIENT -> removeClient(inner(Mrp.EXT_REMOVE_CLIENT))
            Mrp.TYPE_REMOVE_PLAYER -> removePlayer(inner(Mrp.EXT_REMOVE_PLAYER))
            else -> return false
        }
        publish()
        return true
    }

    /** Forgets everything (e.g. after a reconnect, when the device will resend its state from scratch). */
    fun reset() {
        clients.clear()
        activeClient = null
        lastReported = null
        reportedOnce = false
        publish()
    }

    val current: MrpPlaying? get() = compute()

    // ---- message handlers ---------------------------------------------------------

    private fun client(msg: ProtobufWire.ProtoMessage?): Client {
        val bundle = msg?.string(Mrp.NPC_BUNDLE_IDENTIFIER).orEmpty()
        val client = clients.getOrPut(bundle) { Client(bundle) }
        msg?.string(Mrp.NPC_DISPLAY_NAME)?.takeIf { it.isNotEmpty() }?.let { client.displayName = it }
        return client
    }

    private fun playerOf(path: ProtobufWire.ProtoMessage?): Player {
        val client = client(path?.message(Mrp.PP_CLIENT))
        return client.player(path?.message(Mrp.PP_PLAYER)?.string(Mrp.NPP_IDENTIFIER).orEmpty())
    }

    private fun items(queue: ProtobufWire.ProtoMessage): List<Item> = queue.repeatedMessages(Mrp.PQ_CONTENT_ITEMS)
        .map { Item(it.string(Mrp.CI_IDENTIFIER), it.message(Mrp.CI_METADATA)) }

    private fun setState(setState: ProtobufWire.ProtoMessage) {
        val player = playerOf(setState.message(Mrp.SS_PLAYER_PATH))
        setState.enumValue(Mrp.SS_PLAYBACK_STATE)?.let { player.playbackState = it }
        setState.message(Mrp.SS_SUPPORTED_COMMANDS)?.let { supported ->
            // A command listed without an explicit `enabled` counts as enabled.
            player.supportedCommands =
                supported.repeatedMessages(Mrp.SUP_COMMANDS)
                    .mapNotNull { info -> info.enumValue(Mrp.CMDI_COMMAND)?.let { it to (info.bool(Mrp.CMDI_ENABLED) ?: true) } }
                    .toMap()
        }
        setState.message(Mrp.SS_PLAYBACK_QUEUE)?.let { queue ->
            val previous = player.items.filter { it.identifier != null }.associateBy { it.identifier }
            player.items = items(queue).onEach { keepKnownMetadata(it, previous[it.identifier]) }
            player.location = queue.int32(Mrp.PQ_LOCATION) ?: 0
        }
    }

    /**
     * A re-sent queue often carries the *same* item (same identifier) without its metadata —
     * typical of a live stream, where tvOS refreshes the queue every few seconds and only
     * re-adds the title/artwork a moment later via `UpdateContentItemMessage`. Taking the queue
     * as-is (as pyatv does) made the title and artwork flicker to empty each time. Same
     * identifier → keep what was known and merge the new fields over it; a genuinely new item
     * (other identifier) still starts empty, so a stale title never sticks to it.
     */
    private fun keepKnownMetadata(item: Item, previous: Item?) {
        val known = previous?.metadata ?: return
        item.metadata = item.metadata?.let { known.merge(it) } ?: known
    }

    private fun updateContentItem(update: ProtobufWire.ProtoMessage) {
        val player = playerOf(update.message(Mrp.UCI_PLAYER_PATH))
        for (updated in update.repeatedMessages(Mrp.UCI_CONTENT_ITEMS)) {
            val identifier = updated.string(Mrp.CI_IDENTIFIER)
            val newMetadata = updated.message(Mrp.CI_METADATA) ?: continue
            player.items.filter { it.identifier == identifier }.forEach { existing ->
                existing.metadata = existing.metadata?.merge(newMetadata) ?: newMetadata
            }
        }
    }

    private fun setNowPlayingClient(msg: ProtobufWire.ProtoMessage) {
        activeClient = client(msg.message(Mrp.CLIENT_MSG_CLIENT))
    }

    private fun setNowPlayingPlayer(msg: ProtobufWire.ProtoMessage) {
        val path = msg.message(Mrp.PLAYER_MSG_PLAYER_PATH)
        val client = client(path?.message(Mrp.PP_CLIENT))
        val id = path?.message(Mrp.PP_PLAYER)?.string(Mrp.NPP_IDENTIFIER).orEmpty()
        client.player(id)
        client.activePlayerId = id.ifEmpty { null }
    }

    private fun updateClient(msg: ProtobufWire.ProtoMessage) {
        client(msg.message(Mrp.CLIENT_MSG_CLIENT))
    }

    private fun removeClient(msg: ProtobufWire.ProtoMessage) {
        val bundle = msg.message(Mrp.CLIENT_MSG_CLIENT)?.string(Mrp.NPC_BUNDLE_IDENTIFIER).orEmpty()
        val removed = clients.remove(bundle)
        if (removed != null && removed === activeClient) activeClient = null
    }

    private fun removePlayer(msg: ProtobufWire.ProtoMessage) {
        val path = msg.message(Mrp.RP_PLAYER_PATH) ?: return
        val client = clients[path.message(Mrp.PP_CLIENT)?.string(Mrp.NPC_BUNDLE_IDENTIFIER).orEmpty()] ?: return
        val id = path.message(Mrp.PP_PLAYER)?.string(Mrp.NPP_IDENTIFIER).orEmpty()
        if (id.isEmpty()) return
        client.players.remove(id)
        if (client.activePlayerId == id) client.activePlayerId = null
    }

    // ---- derived state ---------------------------------------------------------------

    private fun compute(): MrpPlaying? {
        val client = activeClient ?: return null
        val player = client.activePlayer
        val item = player?.current
        val metadata = item?.metadata?.let { MrpParsers.contentItemMetadata(it) }
        return MrpPlaying(
            state = stateOf(player, metadata),
            nowPlaying = metadata,
            appBundleId = client.bundleId.ifEmpty { null },
            appName = client.displayName,
            location = player?.location ?: 0,
            itemIdentifier = item?.identifier,
            canSeek = player?.supportedCommands?.let { it[Mrp.CMD_SEEK_TO_PLAYBACK_POSITION] == true }
        )
    }

    /** pyatv's `PlayerState.playback_state` + `device_state()`, folded into [MrpPlayState]. */
    private fun stateOf(player: Player?, metadata: MrpNowPlaying?): MrpPlayState {
        val raw = player?.playbackState ?: return MrpPlayState.Idle
        return when (raw) {
            // Paused with nothing queued means "nothing playing", not "paused".
            Mrp.PLAYBACK_PAUSED -> if (metadata != null) MrpPlayState.Paused else MrpPlayState.Idle
            Mrp.PLAYBACK_PLAYING, Mrp.PLAYBACK_SEEKING -> MrpPlayState.Playing
            Mrp.PLAYBACK_INTERRUPTED -> MrpPlayState.Loading
            Mrp.PLAYBACK_STOPPED -> MrpPlayState.Idle
            else -> MrpPlayState.Paused
        }
    }

    /** Reports the derived state to [onChange] only when it actually changed (duplicate pushes are common). */
    private fun publish() {
        val now = compute()
        if (reportedOnce && now == lastReported) return
        reportedOnce = true
        lastReported = now
        onChange(now)
    }
}
