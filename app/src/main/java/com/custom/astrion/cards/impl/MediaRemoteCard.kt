package com.custom.astrion.cards.impl

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableFloatState
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.icons.MdiIcons
import com.custom.astrion.ui.tapClickable
import kotlin.math.abs
/**
 * One remote for every player (1.5.6, `media_remote`, step 1: the main view; by Xavier with
 * Claude, tested on his remote): the same layout for an Apple TV, an Android TV, a Zidoo, an
 * Eversolo, a TV…
 *   - no scrolling: the card fills the screen and the D-pad is sized to what is left
 *     (measured, 1.5.7: FillState; the card ends at the page dots, room to spare around the D-pad);
 *   - each player keeps its own channel: key names go through [KeyMap] per player kind
 *     (Apple TV's names, Android-style names for Android TV / Samsung / Zidoo…), and volume
 *     goes to `volume_entity` like the other cards' `muteEntity`;
 *   - channel keys appear only when the player has them.
 *
 * ONE-HAND PRINCIPLE: above the now-playing preview there is only Power; everything you press is
 * below it, in the lower half of the screen.
 *
 * Top to bottom: Power · now playing + progress · transport (±10 s optional) · player-specific
 * buttons (library; Subtitles/Audio/Chapters on a Zidoo film; Queue/Input/Output on an Eversolo)
 * · [apps | HOME | keypad] · up to 5 favourite apps · D-pad with back/menu (left) and
 * volume (right) — channels (far right) only if the player has them.
 *
 * ```
 * { "type": "media_remote", "options": {
 *     "player_entity": "media_player.zidoo_zidoo",
 *     "remote_entity": "remote.zidoo_zidoo",
 *     "volume_entity": "remote.denon_ampli",             // optional (default: remote_entity)
 *     "kind": "auto",                                    // "auto" | "android" | "apple_tv"  (key names)
 *     "apps": [ { "name": "Plex", "app": "com.plexapp.android" } ],
 *     "apps_style": "icons", "show_apps": true,         // as the TV remote card: "buttons" (default) or "icons"; false hides the shortcuts
 *     "show_skip": true, "show_browse": true, "show_sources": true, "show_keypad": true,
 *     "show_dpad": true, "show_volume": true, "show_channels": true,
 *     "show_tracks": true, "show_queue": true, "show_io": true, "show_quality": true
 * } }
 * ```
 *
 * Builder form: js/cards-media-remote.js.
 */
class MediaRemoteCard : CardRenderer {
    override val type = "media_remote"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val playerId = config.string("player_entity") ?: return
        val remoteId = remoteOf(config, playerId)
        val popup = remember { mutableStateOf<Popup?>(null) }
        val deviceDialog = remember { mutableStateOf<ZidooDialog?>(null) }
        // 1.5.7: the player view (big cover) in the card itself, instead of the remote.
        var playerView by remember { mutableStateOf(false) }
        var detailsOpen by remember { mutableStateOf(false) }
        val r = Parts(ctx, config, playerId, remoteId, popup, deviceDialog) { playerView = true }
        val player = ctx.entities[playerId]
        val active = player != null && player.state in ACTIVE_STATES
        // `auto_player`: to the player view when something starts, back to the remote when it stops.
        val auto = config.bool("auto_player", false)
        LaunchedEffect(active) { playerView = active && (auto || playerView) }
        // The remote's own Back key leaves the player view.
        BackHandler(enabled = playerView && active) { playerView = false }
        val density = LocalDensity.current
        val share = sizeShare(config.string("size"))

        val showDpad = config.bool("show_dpad", true)
        val inPlayer = playerView && active
        val pad = basePad(r, player, inPlayer, active, showDpad, share)

        // 1.5.7: the card ends at the page dots (or at its share of the screen), measured rather
        // than guessed: room to spare goes around the D-pad (or into the cover); too little room
        // shrinks the cover, then puts app rows away in the Apps pop-up (Xavier's screenshots:
        // cards stopping short, or cut off below the D-pad).
        val room = remember(config) { mutableFloatStateOf(0f) }
        val fill = remember(config, inPlayer, active) { FillState(room) }
        r.appsDrop = fill.appsDrop
        val limits = fillLimits(config, inPlayer, pad, cardInnerWidthDp(PADDING))
        val target = fill.room * share

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (target > 0f) Modifier.heightIn(min = target.dp) else Modifier)
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(PADDING.dp)
        ) {
            Column(
                Modifier.fillMaxWidth().onGloballyPositioned { fill.measure(it, density, share, limits, r.appsRowCount(), inPlayer) },
                verticalArrangement = Arrangement.spacedBy(GAP.dp)
            ) {
                r.Header()
                if (inPlayer) {
                    r.PlayerView(player!!, pad + fill.extra.toInt(), onRemote = { playerView = false }, onDetails = { detailsOpen = true })
                } else {
                    r.RemoteBlocks(player, active, showDpad, pad, fill.extra.toInt())
                }
            }
            Spacer(Modifier.weight(1f))
        }
        r.Dialogs()
        if (detailsOpen && active) Details(ctx, config, player!!, remoteId) { detailsOpen = false }
    }

    /** A first guess at the D-pad's (or the cover's) size, from the other blocks' heights; FillState corrects it. */
    @Composable
    private fun basePad(r: Parts, player: EntityState?, inPlayer: Boolean, active: Boolean, showDpad: Boolean, share: Float): Int {
        if (!inPlayer && !showDpad) return 0
        val blocks = if (inPlayer && player != null) r.playerBlocks(player) else blockHeights(r, player, active, showDpad)
        return trackpadFor(blocks, MIN_PAD, if (inPlayer) MAX_COVER else MAX_PAD, null, share)
    }

    /** How far FillState may move: the cover between its minimum and the card's width; the D-pad's gap from 0 up. */
    private fun fillLimits(config: CardConfig, inPlayer: Boolean, pad: Int, innerWidth: Int): ClosedFloatingPointRange<Float> {
        if (!inPlayer) return 0f..MAX_EXTRA
        val ratio = if (config.string("cover_ratio") == "wide") WIDE else 1f
        return (MIN_COVER - pad).toFloat()..(innerWidth / ratio - pad).coerceAtLeast((MIN_COVER - pad).toFloat())
    }

    private fun remoteOf(config: CardConfig, playerId: String) = config.string("remote_entity") ?: "remote.${playerId.substringAfter('.')}"

    /**
     * How the card fits the page (1.5.7), from its content as laid out: [room] from the card's top
     * to the page dots; [extra] dp around the D-pad, or added to (taken from) the cover; app rows
     * put away ([appsDrop]) when even that isn't enough. Each layout corrects the last, so it
     * settles in a frame or two; nothing changes once it fits.
     */
    private class FillState(private val roomState: MutableFloatState) {
        var room by roomState
        var extra by mutableFloatStateOf(0f)
        var appsDrop by mutableIntStateOf(0)

        fun measure(
            content: LayoutCoordinates,
            density: Density,
            share: Float,
            limits: ClosedFloatingPointRange<Float>,
            appRows: Int,
            inPlayer: Boolean
        ) {
            val px = density.density
            val top = content.positionInWindow().y / px - PADDING
            val limit = content.findRootCoordinates().size.height / px - PAGE_BOTTOM_CHROME
            (limit - top).takeIf { abs(it - room) > 1f }?.let { room = it }
            val target = room * share
            if (target <= 0f) return
            val diff = target - (content.size.height / px + 2 * PADDING)
            if (abs(diff) <= 1f) return
            val wanted = (extra + diff).coerceIn(limits)
            when {
                abs(wanted - extra) > 0.5f -> extra = wanted
                diff < 0 && !inPlayer && appsDrop < appRows -> appsDrop++
            }
        }
    }

    /** Heights (dp) of every block but the D-pad, in the order they are drawn. */
    private fun blockHeights(r: Parts, player: EntityState?, active: Boolean, showDpad: Boolean): List<Int> = buildList {
        add(BLOCK_POWER)
        if (active) {
            add(BLOCK_NOW_PLAYING + BLOCK_PROGRESS)
            add(BLOCK_TRANSPORT)
        }
        if (r.hasDeviceButtons(player)) add(BLOCK_DEVICE)
        add(BLOCK_NAV)
        addAll(r.appsBlocks())
        if (showDpad && r.hasLowerRow()) add(BLOCK_LOWER)
    }

    /** All the player's details (volume slider, casting…), from the player view's ⋯ key. */
    @Composable
    private fun Details(ctx: CardContext, config: CardConfig, player: EntityState, remoteId: String, onClose: () -> Unit) {
        MediaPlayerDetailDialog(
            entityId = player.entityId,
            name = player.attrString("friendly_name") ?: player.entityId,
            e = player,
            client = ctx.client,
            theme = ctx.theme,
            library = MediaLibraryPlayers(browseAlso = remoteId, showVolume = config.bool("show_volume", true)),
            onClose = onClose
        )
    }

    private companion object {
        const val PADDING = 14
        const val GAP = 12
        const val KEY = 48
        const val SKIP_S = 10.0

        // Block heights for trackpadFor (to tune on the device).
        const val BLOCK_POWER = 48
        const val BLOCK_NOW_PLAYING = 88
        const val BLOCK_PROGRESS = 28
        const val BLOCK_TRANSPORT = 56
        const val BLOCK_DEVICE = 56
        const val BLOCK_NAV = 56
        const val BLOCK_APPS = 72
        const val BLOCK_LOWER = 48 // the mute key, centred under the D-pad
        const val SIDE_KEY = 44

        // Back over volume + / −: three side keys, their 8 dp gap, and 24 dp between Back and volume +.
        const val SIDE_COLUMN = 3 * SIDE_KEY + 8 + 24
        const val MIN_PAD = 140
        const val MAX_PAD = 260
        val ACTIVE_STATES = setOf("playing", "paused", "buffering")
        const val MAX_COVER = 420
        const val MIN_COVER = 100
        const val SMALL_CHIPS = 4
        const val MAX_EXTRA = 600f
        const val PAGE_BOTTOM_CHROME = 42 // the page dots under the card
        const val WIDE = 16f / 9f
        const val COVER_FILL = 0.2f
        const val RECT_CORNER = 0.28f

        // Player view heights (to tune on the device): title lines, progress, transport, the row of keys.
        const val BLOCK_TITLE = 64
        const val BLOCK_KEYS = 56

        /** The blocks in `order` (1.5.7); any left out follow in the default order, unknown names ignored. */
        val DEFAULT_ORDER = listOf("playing", "device", "nav", "apps", "pad")

        fun orderOf(config: CardConfig): List<String> {
            val asked = (config.options["order"] as? List<*>).orEmpty().mapNotNull { it as? String }
                .filter { it in DEFAULT_ORDER }.distinct()
            return asked + DEFAULT_ORDER.filter { it !in asked }
        }

        /** `size` (1.5.7): the part of the screen the card takes. */
        fun sizeShare(size: String?): Float = when (size) {
            "two_thirds" -> 2f / 3f
            "half" -> 0.5f
            else -> 1f
        }
    }

    private enum class Popup { SOURCES, KEYPAD, BROWSE, SEARCH, DEVICE }

    /**
     * The key names a player's own channel understands. Android TV, Samsung, Zidoo/Eversolo take
     * Android-style names; the Apple TV (companion protocol) takes its own. A key a player does not
     * have returns null and its button is not drawn.
     */
    private class KeyMap(val appleTv: Boolean) {
        fun of(key: String): String? = if (appleTv) APPLE[key] else ANDROID[key]

        companion object {
            val ANDROID = mapOf(
                "up" to "DPAD_UP", "down" to "DPAD_DOWN", "left" to "DPAD_LEFT", "right" to "DPAD_RIGHT",
                "center" to "DPAD_CENTER", "back" to "BACK", "home" to "HOME", "menu" to "MENU", "power" to "POWER",
                "volume_up" to "VOLUME_UP", "volume_down" to "VOLUME_DOWN", "mute" to "MUTE",
                "channel_up" to "CHANNEL_UP", "channel_down" to "CHANNEL_DOWN",
                "rewind" to "MEDIA_REWIND", "fast_forward" to "MEDIA_FAST_FORWARD"
            )
            val APPLE = mapOf(
                "up" to "Up", "down" to "Down", "left" to "Left", "right" to "Right", "center" to "Select",
                "back" to "Menu", "home" to "Home",
                "volume_up" to "VolumeUp", "volume_down" to "VolumeDown",
                "channel_up" to "ChannelUp", "channel_down" to "ChannelDown",
                "rewind" to "SeekBackward", "fast_forward" to "SeekForward"
            ) // no menu / mute / power key on the Apple TV remote channel
        }
    }

    private class Parts(
        val ctx: CardContext,
        val config: CardConfig,
        val playerId: String,
        val remoteId: String,
        val popupState: MutableState<Popup?>,
        val deviceState: MutableState<ZidooDialog?>,
        val openPlayer: () -> Unit
    ) {
        val theme: ThemeColors = ctx.theme
        val volumeId = config.string("volume_entity") ?: remoteId
        val keys = KeyMap(
            when (config.string("kind")) {
                "apple_tv" -> true
                "android" -> false
                else -> remoteId.contains("appletv", ignoreCase = true)
            }
        )

        // The card's apps, else the player's own (a Zidoo's, an Eversolo's), as the Zidoo card does.
        val apps = AppShortcuts.appsOf(config.options) ?: playerApps(ctx.entities[playerId])
        private val rectKeys = config.string("key_shape") == "rect"
        val shortcuts = AppShortcuts(ctx, apps, playerId)
        private var popup by popupState
        private var deviceDialog by deviceState

        /** Sends a logical key through the player's own channel; false when the player has no such key. */
        fun press(key: String, entity: String = remoteId): Boolean {
            val command = keys.of(key) ?: return false
            ctx.client.callService(ServiceCall.of("remote", "send_command", entity, "command" to command))
            return true
        }

        fun mp(service: String, vararg data: Pair<String, Any?>) {
            ctx.client.callService(ServiceCall.of("media_player", service, playerId, *data))
        }

        /** ±10 s: a seek when the player can; else its rewind / fast-forward key (never the previous / next track). */
        fun skip(e: EntityState, delta: Double) {
            when {
                e.supports(Feature.SEEK) -> mp("media_seek", "seek_position" to (currentMediaPosition(e) + delta).coerceAtLeast(0.0))
                isZidoo() -> zidooKey(ctx, playerId, if (delta < 0) "Key.MediaBackward" else "Key.MediaForward")
                else -> press(if (delta < 0) "rewind" else "fast_forward")
            }
        }

        /** A Zidoo or an Eversolo added in Astrion: its own buttons (tracks, queue, input / output). */
        fun isZidoo(): Boolean = playerId.startsWith("media_player.zidoo_")

        /** On: [mp] turn_off; off: turn_on (a direct player wakes over the network). Without them, the power key. */
        fun power(e: EntityState?) {
            val on = e != null && !e.isUnavailable && e.state != "off" && e.state != "standby"
            when {
                on && e?.supports(Feature.TURN_OFF) == true -> mp("turn_off")
                !on && (e == null || e.supports(Feature.TURN_ON)) -> mp("turn_on")
                !press("power") -> mp(if (on) "turn_off" else "turn_on")
            }
        }

        // ---- blocks -------------------------------------------------------------------------

        @Composable
        fun Header() {
            val showPower = config.bool("show_power", true)
            val e = ctx.entities[playerId]
            // The card's name (1.5.6): its own, else the player's, as the other cards show theirs.
            val title = config.string("name") ?: e?.attrString("friendly_name") ?: playerId.substringAfter('.')
            val named = config.bool("show_name", true)
            if (!showPower && !named) return
            val on = e != null && !e.isUnavailable && e.state != "off" && e.state != "standby"
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (named) title else "",
                    color = theme.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                // Accent while on, red while off: a tap turns it the other way.
                if (showPower) Key(Icons.Filled.PowerSettingsNew, "Power", tint = if (on) theme.accent else theme.danger) { power(e) }
            }
        }

        /** The remote's blocks, in `order` (1.5.7). */
        @Composable
        fun RemoteBlocks(player: EntityState?, active: Boolean, showDpad: Boolean, pad: Int, extra: Int = 0) {
            orderOf(config).forEach { block ->
                when (block) {
                    "playing" -> if (active && player != null) {
                        NowPlaying(player)
                        Transport(player)
                    }
                    // The library (and the player's own buttons) show even when nothing plays.
                    "device" -> DeviceButtons(player, active)
                    "nav" -> NavRow()
                    "apps" -> AppsRow()
                    "pad" -> if (showDpad) Pad(pad, extra)
                }
            }
        }

        @Composable
        fun NowPlaying(e: EntityState) {
            val np = AtvNowPlaying.of(e)
            val seek: ((Double) -> Unit)? =
                if (e.supports(Feature.SEEK)) { s -> mp("media_seek", "seek_position" to s) } else null
            Row(
                Modifier.fillMaxWidth().tapClickable(onClick = openPlayer),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                AtvArtwork(ctx, e, 72.dp, 12.dp)
                Column(Modifier.weight(1f)) {
                    Text(
                        listOfNotNull(mediaStateLabel(e.state), np.app).joinToString(" · "),
                        color = theme.accent,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        np.title,
                        color = theme.primaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    np.subtitle?.let { Text(it, color = theme.mutedText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
            // A bar that can seek keeps its own taps and drags; one that can't opens the player view.
            Box(if (seek == null) Modifier.fillMaxWidth().tapClickable(onClick = openPlayer) else Modifier.fillMaxWidth()) {
                AtvProgress(e, theme, thick = true, onSeek = seek)
            }
        }

        /** Heights (dp) of the player view's blocks but the cover, for trackpadFor to size the cover. */
        fun playerBlocks(e: EntityState): List<Int> = buildList {
            add(BLOCK_POWER)
            add(BLOCK_TITLE)
            add(BLOCK_PROGRESS)
            add(BLOCK_TRANSPORT)
            add(BLOCK_KEYS)
            if (hasDeviceButtons(e)) add(BLOCK_DEVICE)
        }

        /**
         * The player view (1.5.7): a big cover (`cover_ratio`: square, or "wide" for 16:9 film art),
         * the title, the bar, the transport, volume, and the way back to the remote (its key, or the
         * remote's own Back key); ⋯ opens all the player's details.
         */
        @Composable
        fun PlayerView(e: EntityState, coverHeight: Int, onRemote: () -> Unit, onDetails: () -> Unit) {
            val ratio = if (config.string("cover_ratio") == "wide") WIDE else 1f
            val h = minOf(coverHeight.toFloat(), cardInnerWidthDp(PADDING) / ratio)
            Box(Modifier.fillMaxWidth().tapClickable(onClick = onDetails), contentAlignment = Alignment.Center) {
                Cover(e, (h * ratio).dp, h.dp, ratio)
            }
            val np = AtvNowPlaying.of(e)
            Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    np.title,
                    color = theme.primaryText,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                np.subtitle?.let {
                    Text(
                        it,
                        color = theme.mutedText,
                        fontSize = 14.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
            val seek: ((Double) -> Unit)? = if (e.supports(Feature.SEEK)) { s -> mp("media_seek", "seek_position" to s) } else null
            AtvProgress(e, theme, thick = true, onSeek = seek)
            Transport(e)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Key(Icons.Filled.SettingsRemote, "Remote") { onRemote() }
                if (hasVolume()) {
                    Key(Icons.AutoMirrored.Filled.VolumeDown, "Vol −") { press("volume_down", volumeId) }
                    if (keys.of("mute") != null) Key(Icons.AutoMirrored.Filled.VolumeOff, "Mute") { press("mute", volumeId) }
                    Key(Icons.AutoMirrored.Filled.VolumeUp, "Vol +") { press("volume_up", volumeId) }
                }
                Key(Icons.Filled.MoreHoriz, "Details") { onDetails() }
            }
            DeviceButtons(e, active = true)
        }

        /** The cover in a [w]×[h] frame: filling it when its shape is close, else whole (a square album in a 16:9 frame). */
        @Composable
        private fun Cover(e: EntityState, w: Dp, h: Dp, ratio: Float) {
            val path = e.attrString("entity_picture_local") ?: e.attrString("entity_picture")
            val px = with(LocalDensity.current) { w.toPx() }.toInt()
            val art = rememberImage(ctx, path, px)
            Box(Modifier.size(w, h).clip(RoundedCornerShape(16.dp)).background(theme.insetSurface), contentAlignment = Alignment.Center) {
                if (art != null) {
                    val own = art.width.toFloat() / art.height.coerceAtLeast(1)
                    val fills = kotlin.math.abs(own - ratio) / ratio < COVER_FILL
                    Image(art, null, contentScale = if (fills) ContentScale.Crop else ContentScale.Fit, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Icons.Filled.MusicNote, null, tint = theme.mutedText, modifier = Modifier.size(h * 0.3f))
                }
            }
        }

        @Composable
        fun Transport(e: EntityState) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                if (config.bool("show_skip", true)) Key(Icons.Filled.Replay10, "−10 s") { skip(e, -SKIP_S) }
                Key(MdiIcons.SkipPrevious, "Previous") { mp("media_previous_track") }
                Key(
                    if (e.state == "playing") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    "Play/Pause",
                    big = true
                ) { mp("media_play_pause") }
                Key(MdiIcons.SkipNext, "Next") { mp("media_next_track") }
                if (config.bool("show_skip", true)) Key(Icons.Filled.Forward10, "+10 s") { skip(e, SKIP_S) }
            }
        }

        fun hasDeviceButtons(e: EntityState?): Boolean =
            (e != null && deviceChips(e).isNotEmpty()) || (config.bool("show_browse", true) && hasLibrary(playerId, remoteId)) || searches()

        /** `music_search` (1.5.7, as the media player card): Home Assistant's media search, on `search_player`. */
        private fun searches() = config.bool("music_search", false)
        private val searchPlayer get() = config.string("search_player") ?: playerId

        private fun deviceChips(e: EntityState): List<Triple<Int, String?, ZidooDialog>> {
            // Only a Zidoo / Eversolo answers these pop-ups (they ask the player itself).
            if (!isZidoo()) return emptyList()
            val v = ZidooView.of(e)
            return buildList {
                if (v.playing && v.video && config.bool("show_tracks", true)) {
                    add(Triple(R.string.zidoo_card_subtitles, e.attrString("astrion_subtitle"), ZidooDialog.SUBTITLES))
                    add(Triple(R.string.zidoo_card_audio, e.attrString("astrion_audio"), ZidooDialog.AUDIO))
                    add(Triple(R.string.zidoo_card_chapters, null, ZidooDialog.CHAPTERS))
                }
                if (v.playing && !v.video && config.bool("show_queue", true)) {
                    add(
                        Triple(R.string.zidoo_card_queue, null, ZidooDialog.QUEUE)
                    )
                }
                if (v.eversolo && config.bool("show_io", true)) {
                    add(Triple(R.string.zidoo_card_input, null, ZidooDialog.IO))
                    add(Triple(R.string.zidoo_card_output, null, ZidooDialog.IO))
                }
            }
        }

        /** Library button + the buttons only this player has (same detection as ZidooCard), one row. */
        @Composable
        fun DeviceButtons(e: EntityState?, active: Boolean) {
            val chips = if (e != null) deviceChips(e) else emptyList()
            val browse = config.bool("show_browse", true) && hasLibrary(playerId, remoteId)
            if (chips.isEmpty() && !browse && !searches()) return
            val shown = chips.take(if (searches()) 2 else 3)
            // Four chips (a Zidoo: library, subtitles, audio, chapters): smaller text, so no word is cut in two (1.5.7).
            val small = listOf(browse, searches()).count { it } + shown.size >= SMALL_CHIPS
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (small) 6.dp else 8.dp)) {
                if (browse) Chip(stringResource(R.string.zidoo_library_title), null, Modifier.weight(1f), small) { popup = Popup.BROWSE }
                if (searches()) {
                    Chip(
                        stringResource(R.string.zidoo_library_search),
                        null,
                        Modifier.weight(1f),
                        small
                    ) { popup = Popup.SEARCH }
                }
                shown.forEach { (label, value, dialog) ->
                    Chip(stringResource(label), value, Modifier.weight(1f), small) {
                        deviceDialog = dialog
                        popup = Popup.DEVICE
                    }
                }
            }
            if (active && e != null && config.bool("show_quality", true)) MediaQualityLine(e, theme)
        }

        @Composable
        private fun Chip(label: String, value: String?, modifier: Modifier, small: Boolean = false, onClick: () -> Unit) {
            Column(
                modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(theme.controlBackground)
                    .tapClickable(onClick = onClick).padding(horizontal = if (small) 3.dp else 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Two lines rather than one cut word ("File d'attente" in a narrow chip).
                Text(
                    label,
                    color = theme.primaryText,
                    fontSize = if (small) 11.sp else 13.sp,
                    lineHeight = if (small) 13.sp else 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                value?.let { Text(it, color = theme.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }

        /**
         * Apps (left) · HOME (centre) · menu (right), in line with the pad's side keys (Back and
         * volume under Apps, keypad and channels under the menu) and the D-pad under HOME. A
         * Zidoo / Eversolo's menu key does nothing: there it opens the player's settings (1.5.6).
         */
        @Composable
        fun NavRow() {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SideSlot { if (config.bool("show_sources", true)) Key(Icons.Filled.Apps, "Apps") { popup = Popup.SOURCES } }
                Key(Icons.Filled.Home, "Home") { press("home") }
                val settings = if (isZidoo()) ctx.entities[playerId]?.attrString("astrion_settings_app") else null
                SideSlot {
                    when {
                        settings != null -> Key(Icons.Filled.Settings, "Settings") { mp("select_source", "source" to settings) }
                        keys.of("menu") != null -> Key(Icons.Filled.Menu, "Menu") { press("menu") }
                    }
                }
            }
        }

        /** A slot as wide as the pad's side keys, so what's in it is centred above them. */
        @Composable
        private fun SideSlot(content: @Composable () -> Unit) {
            Box(Modifier.width(SIDE_KEY.dp).wrapContentWidth(unbounded = true), contentAlignment = Alignment.Center) { content() }
        }

        private fun showApps() = apps.isNotEmpty() && config.bool("show_apps", true)
        private fun iconsStyle() = config.string("apps_style") == "icons"

        /** App rows put away because the card didn't fit (1.5.7, see FillState). */
        var appsDrop = 0

        /**
         * At most this many rows of apps (`apps_rows`, 1.5.6; 0: all); the others stay in the Apps
         * pop-up. A card on half or two thirds of the screen (`size`, 1.5.7) keeps one; any card
         * fewer when the page is too short for them ([appsDrop]).
         */
        private fun appsRows(): Int {
            val asked = config.int("apps_rows", 0).coerceAtLeast(0)
            val sized = if (sizeShare(config.string("size")) < 1f) (if (asked == 0) 1 else minOf(asked, 1)) else asked
            val shown = if (sized > 0) minOf(sized, allAppRows()) else allAppRows()
            return (shown - appsDrop).coerceAtLeast(0)
        }

        private fun allAppRows(): Int {
            val perRow = if (iconsStyle()) AppShortcuts.ICONS_PER_ROW else 2
            return (apps.size + perRow - 1) / perRow
        }

        /** How many app rows show before any is put away, for FillState. */
        fun appsRowCount(): Int = if (showApps()) appsRows() + appsDrop else 0

        /** Heights of the app rows, for the D-pad's size (icons: 5 a row; buttons: 2 a row). */
        fun appsBlocks(): List<Int> {
            if (!showApps()) return emptyList()
            return List(appsRows()) { if (iconsStyle()) BLOCK_APPS else KEY }
        }

        /** The apps, as the TV remote card shows them (`apps_style`, `show_apps`), `apps_rows` at most. */
        @Composable
        fun AppsRow() {
            val rows = appsRows()
            if (showApps() && rows > 0) shortcuts.Rows(iconsStyle = iconsStyle(), cardPadding = PADDING, maxRows = rows)
        }

        private fun hasVolume() = config.bool("show_volume", true)
        private fun hasChannels() = config.bool("show_channels", true) && keys.of("channel_up") != null && !isMusicPlayer()

        /** Whether the mute key row under the D-pad is drawn. */
        fun hasLowerRow() = hasVolume() && keys.of("mute") != null

        /**
         * Like the physical remote: a round D-pad in the middle; Back at its top left and the 0–9
         * keypad at its top right; volume (+ over −) at its bottom left and channels (+ over −) at its bottom
         * right; mute centred under it.
         */
        @Composable
        fun Pad(pad: Int, extra: Int = 0) {
            // Round, and narrow enough to leave room for the side keys.
            val d = minOf(pad, cardInnerWidthDp(PADDING) - 2 * (SIDE_KEY + 8)).coerceAtLeast(120)
            val arrow = (d * 0.24f).coerceIn(36f, 56f).dp
            val ok = (d * 0.32f).dp
            val side = SIDE_KEY.dp
            // The room the page has to spare (1.5.7, FillState), half above the D-pad, half below.
            Column(Modifier.fillMaxWidth().padding(vertical = (extra / 2).dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Tall enough to keep Back apart from volume +, and the keypad from channel + (1.5.6).
                Box(Modifier.fillMaxWidth().height(maxOf(d, SIDE_COLUMN).dp)) {
                    Box(
                        Modifier.align(Alignment.Center).size(d.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape).background(theme.controlBackground)
                    ) {
                        Box(
                            Modifier.align(Alignment.TopCenter).padding(top = 6.dp)
                        ) { SmallKey(Icons.Filled.KeyboardArrowUp, "Up", arrow) { press("up") } }
                        Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp)) {
                            SmallKey(Icons.Filled.KeyboardArrowDown, "Down", arrow) { press("down") }
                        }
                        Box(Modifier.align(Alignment.CenterStart).padding(start = 6.dp)) {
                            SmallKey(Icons.AutoMirrored.Filled.ArrowLeft, "Left", arrow) { press("left") }
                        }
                        Box(Modifier.align(Alignment.CenterEnd).padding(end = 6.dp)) {
                            SmallKey(Icons.AutoMirrored.Filled.ArrowRight, "Right", arrow) { press("right") }
                        }
                        Box(Modifier.align(Alignment.Center)) { SmallKey(null, "OK", ok) { press("center") } }
                    }
                    Box(
                        Modifier.align(Alignment.TopStart)
                    ) { SmallKey(Icons.AutoMirrored.Filled.ArrowBack, "Back", side) { press("back") } }
                    if (config.bool("show_keypad", true)) {
                        Box(Modifier.align(Alignment.TopEnd)) { SmallKey(Icons.Filled.Dialpad, "Keypad", side) { popup = Popup.KEYPAD } }
                    }
                    if (hasVolume()) {
                        Column(Modifier.align(Alignment.BottomStart), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallKey(Icons.AutoMirrored.Filled.VolumeUp, "Vol +", side) { press("volume_up", volumeId) }
                            SmallKey(Icons.AutoMirrored.Filled.VolumeDown, "Vol −", side) { press("volume_down", volumeId) }
                        }
                    }
                    if (hasChannels()) {
                        Column(Modifier.align(Alignment.BottomEnd), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SmallKey(Icons.Filled.KeyboardArrowUp, "Ch +", side) { press("channel_up") }
                            SmallKey(Icons.Filled.KeyboardArrowDown, "Ch −", side) { press("channel_down") }
                        }
                    }
                }
                if (hasLowerRow()) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                        SmallKey(Icons.AutoMirrored.Filled.VolumeOff, "Mute", side) { press("mute", volumeId) }
                    }
                }
            }
        }

        /** An Eversolo (music) has no channels. */
        private fun isMusicPlayer(): Boolean = ctx.entities[playerId]?.let { ZidooView.of(it).eversolo } == true

        @Composable
        fun Dialogs() {
            val close = { popup = null }
            when (popup) {
                Popup.SOURCES -> SourcePickerDialog(
                    ctx,
                    playerId,
                    preferred = shortcuts.order(),
                    icons = shortcuts.icons(),
                    onClose = close
                )
                Popup.BROWSE -> BrowseDialog(playerId, ctx.client, theme, also = remoteId, onClose = close)
                Popup.SEARCH -> MusicSearchDialog(ctx.client, searchPlayer, theme, close)
                Popup.DEVICE -> deviceDialog?.let { ZidooCardDialog(ctx, config, playerId, ctx.entities[playerId], it, close) }
                Popup.KEYPAD -> KeypadTarget.from(config, remoteId, keys.of("center") ?: "DPAD_CENTER")?.let { target ->
                    KeypadDialog(theme, target.hasOk, onKey = { target.send(ctx, it) }, onClose = close)
                }
                null -> Unit
            }
        }

        @Composable
        private fun Key(
            icon: ImageVector?,
            label: String,
            tint: androidx.compose.ui.graphics.Color = theme.iconTint,
            big: Boolean = false,
            onClick: () -> Unit
        ) {
            SmallKey(icon, label, (if (big) KEY + 8 else KEY).dp, tint, onClick)
        }

        @Composable
        private fun SmallKey(
            icon: ImageVector?,
            label: String,
            size: Dp,
            tint: androidx.compose.ui.graphics.Color = theme.iconTint,
            onClick: () -> Unit
        ) {
            // `key_shape` (1.5.7): "rect" for rounded rectangles, as a physical remote's keys.
            val shape = if (rectKeys) RoundedCornerShape(size * RECT_CORNER) else androidx.compose.foundation.shape.CircleShape
            Box(
                Modifier.size(size).clip(shape).background(theme.controlBackground).tapClickable(focusShape = shape, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                if (icon != null) {
                    Icon(icon, label, tint = tint, modifier = Modifier.size(size * 0.5f))
                } else {
                    Text(label, color = theme.primaryText, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
    }
}
