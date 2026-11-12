package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
/**
 * One remote for every player (1.5.6, `media_remote`, step 1: the main view; by Xavier with
 * Claude, tested on his remote): the same layout for an Apple TV, an Android TV, a Zidoo, an
 * Eversolo, a TV…
 *   - no scrolling: the card fills the screen and the D-pad is sized to what is left
 *     (trackpadFor / measureFit, as TvRemoteCard's "full" layout and the Apple TV card do);
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
        val remoteId = config.string("remote_entity") ?: "remote.${playerId.substringAfter('.')}"
        val popup = remember { mutableStateOf<Popup?>(null) }
        val deviceDialog = remember { mutableStateOf<ZidooDialog?>(null) }
        var playerOpen by remember { mutableStateOf(false) }
        val r = Parts(ctx, config, playerId, remoteId, popup, deviceDialog) { playerOpen = true }
        val player = ctx.entities[playerId]
        val active = player != null && player.state in ACTIVE_STATES
        // A tap on what plays opens the media player view; it closes once nothing plays.
        LaunchedEffect(active) { if (!active) playerOpen = false }
        val fit = remember(config) { AtvFit() }
        val density = LocalDensity.current

        // Heights (dp) of everything except the D-pad, so trackpadFor can size the D-pad to the screen.
        val showDpad = config.bool("show_dpad", true)
        val blocks = blockHeights(r, player, active, showDpad)
        val pad = if (showDpad) trackpadFor(blocks, min = MIN_PAD, max = MAX_PAD, fit = fit) else 0
        fit.usedPad = pad

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { measureFit(fit, it, density) }
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(GAP.dp)
        ) {
            r.Header()
            if (active) {
                r.NowPlaying(player!!)
                r.Transport(player)
            }
            // The library (and the player's own buttons) show even when nothing plays.
            r.DeviceButtons(player, active)
            r.NavRow()
            r.AppsRow()
            if (showDpad) r.Pad(pad)
        }
        r.Dialogs()
        if (playerOpen && active) PlayerView(ctx, config, player, remoteId) { playerOpen = false }
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

    /** The media player view of what plays, opened by a tap on its cover, title or bar. */
    @Composable
    private fun PlayerView(ctx: CardContext, config: CardConfig, player: EntityState, remoteId: String, onClose: () -> Unit) {
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
            (e != null && deviceChips(e).isNotEmpty()) || (config.bool("show_browse", true) && hasLibrary(playerId, remoteId))

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
            if (chips.isEmpty() && !browse) return
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (browse) Chip(stringResource(R.string.zidoo_library_title), null, Modifier.weight(1f)) { popup = Popup.BROWSE }
                chips.take(3).forEach { (label, value, dialog) ->
                    Chip(stringResource(label), value, Modifier.weight(1f)) {
                        deviceDialog = dialog
                        popup = Popup.DEVICE
                    }
                }
            }
            if (active && e != null && config.bool("show_quality", true)) MediaQualityLine(e, theme)
        }

        @Composable
        private fun Chip(label: String, value: String?, modifier: Modifier, onClick: () -> Unit) {
            Column(
                modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(12.dp)).background(theme.controlBackground)
                    .tapClickable(onClick = onClick).padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                // Two lines rather than one cut word ("File d'attente" in a narrow chip).
                Text(
                    label,
                    color = theme.primaryText,
                    fontSize = 13.sp,
                    lineHeight = 15.sp,
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

        /** At most this many rows of apps (`apps_rows`, 1.5.6; 0: all); the others stay in the Apps pop-up. */
        private fun appsRows() = config.int("apps_rows", 0).coerceAtLeast(0)

        /** Heights of the app rows, for the D-pad's size (icons: 5 a row; buttons: 2 a row). */
        fun appsBlocks(): List<Int> {
            if (!showApps()) return emptyList()
            val perRow = if (iconsStyle()) AppShortcuts.ICONS_PER_ROW else 2
            val rows = (apps.size + perRow - 1) / perRow
            val shown = if (appsRows() > 0) minOf(rows, appsRows()) else rows
            return List(shown) { if (iconsStyle()) BLOCK_APPS else KEY }
        }

        /** The apps, as the TV remote card shows them (`apps_style`, `show_apps`), `apps_rows` at most. */
        @Composable
        fun AppsRow() {
            if (showApps()) shortcuts.Rows(iconsStyle = iconsStyle(), cardPadding = PADDING, maxRows = appsRows())
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
        fun Pad(pad: Int) {
            // Round, and narrow enough to leave room for the side keys.
            val d = minOf(pad, cardInnerWidthDp(PADDING) - 2 * (SIDE_KEY + 8)).coerceAtLeast(120)
            val arrow = (d * 0.24f).coerceIn(36f, 56f).dp
            val ok = (d * 0.32f).dp
            val side = SIDE_KEY.dp
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                Popup.SEARCH -> MusicSearchDialog(ctx.client, playerId, theme, close)
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
            val shape = androidx.compose.foundation.shape.CircleShape
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
