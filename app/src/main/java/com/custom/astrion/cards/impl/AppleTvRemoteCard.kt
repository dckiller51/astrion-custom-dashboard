package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.appletv.AppleTvRegistry
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * Apple TV remote styled like a Siri Remote.
 *
 * Commands bypass Home Assistant. Two ways to reach the Apple TV:
 *
 *  - **Direct** (`appleTv`): the Apple TV paired in the Devices page, driven over
 *    its own Companion link — no hub, no Home Assistant.
 *  - **Harmony** (`deviceId` [+ `hub`]): via `ctx.sendHarmonyCommand`.
 *
 * Config shapes:
 * ```json
 * { "type": "apple_tv_remote", "options": { "appleTv": "media_player.appletv_salon" } }
 * { "type": "apple_tv_remote", "options": { "deviceId": "62846050", "hub": "<localId>" } }
 * ```
 * `appleTv` is a paired Apple TV's entity id (or local id); when present it wins over
 * `deviceId`. `hub` is optional — a HarmonyHubConfig.localId; omit it to use the first
 * configured hub.
 *
 * `buttons` (optional) picks which buttons sit in the row under the trackpad, from
 * [ExtraButtons.CATALOG]'s ids — e.g. `["Menu", "SeekBackward", "SeekForward", "Home"]`.
 *
 * `layout` (optional):
 *  - absent / `"classic"`: the original card — trackpad, button row, Play/Pause.
 *  - `"compact"`: remote first — a one-line now-playing strip (small artwork, title, thin
 *    progress, Play/Pause) above the trackpad, the button row, then a row of app icons.
 *  - `"full"`: large artwork, title and a draggable progress bar, −10 s / Play-Pause / +10 s,
 *    a scrollable row of labelled apps, then the trackpad and button row.
 *
 * Now-playing info and app launching read/drive a `media_player`: the direct Apple TV itself,
 * or `entity` (e.g. Home Assistant's own Apple TV integration when the card goes through a
 * Harmony hub). Without one, those parts are simply left out.
 *
 *  - `showArtwork` / `showProgress` (default true): in compact, turning both off drops the
 *    now-playing strip and brings back the classic Play/Pause button.
 *  - `showSkip` (default true): the −10 s / +10 s buttons of the full layout, and holding the
 *    trackpad's left/right arrows to jump 10 s. Direct Apple TV only.
 *  - `apps`: `[{ "source": "Netflix", "label": "Netflix", "icon": "/sdcard/astrion/icons/n.png",
 *    "color": "#B3261E" }]` — `source` is the app name as in the entity's `source_list` and is
 *    launched with `media_player.select_source`; `icon` is a local PNG path or an image URL
 *    (initials on `color` otherwise).
 *
 * On a direct Apple TV, holding the trackpad's centre sends a held Select (`SelectHold`), which
 * opens tvOS's context menu — e.g. to remove an item from the Apple TV app's Up Next.
 */
object ExtraButtons {
    /**
     * One buildable extra button: [command] is what's actually sent (works for both a direct
     * Apple TV, which is tolerant of case/spacing, and a Harmony hub, which expects it verbatim).
     */
    data class Spec(val command: String, val label: String, val icon: ImageVector?)

    val CATALOG: List<Spec> =
        listOf(
            Spec("Menu", "Menu", Icons.Filled.Menu),
            Spec("Home", "Home", Icons.Filled.Home),
            Spec("SeekBackward", "−10 s", Icons.Filled.Replay10),
            Spec("SeekForward", "+10 s", Icons.Filled.Forward10),
            Spec("Rewind", "Rewind", Icons.Filled.FastRewind),
            Spec("FastForward", "Fast forward", Icons.Filled.FastForward),
            Spec("Previous", "Previous", Icons.Filled.SkipPrevious),
            Spec("Next", "Next", Icons.Filled.SkipNext),
            Spec("Siri", "Siri", Icons.Filled.Mic),
            Spec("Screensaver", "Screensaver", Icons.Filled.Wallpaper),
            Spec("Guide", "Guide", Icons.AutoMirrored.Filled.List),
            Spec("VolumeUp", "Vol +", Icons.AutoMirrored.Filled.VolumeUp),
            Spec("VolumeDown", "Vol −", Icons.AutoMirrored.Filled.VolumeDown),
            Spec("ControlCenter", "Control Center", Icons.Filled.Apps)
        )
    private val byCommand = CATALOG.associateBy { it.command.lowercase() }

    val DEFAULT_IDS = listOf("Menu", "Home")

    /** Default row for the compact layout, where ±10 s live in the button row. */
    val COMPACT_DEFAULT_IDS = listOf("Menu", "SeekBackward", "SeekForward", "Home")

    /** Falls back to just the command as its own label for a button id this catalog doesn't (yet) know. */
    fun resolve(id: String): Spec = byCommand[id.lowercase()] ?: Spec(id, id, null)
}

/** Where the card's commands go, and which `media_player` (if any) it reads now-playing info from. */
internal class AtvTarget(private val appleTv: String?, private val deviceId: String?, private val hub: String?, val mediaEntity: String?) {
    val isDirect: Boolean get() = appleTv != null

    fun send(ctx: CardContext, command: String) {
        if (appleTv != null) {
            ctx.client.callService(ServiceCall.of(AppleTvRegistry.REMOTE_DOMAIN, "send_command", appleTv, "command" to command))
        } else if (deviceId != null) {
            ctx.sendHarmonyCommand(deviceId, command, hub)
        }
    }

    fun mediaService(ctx: CardContext, service: String, vararg data: Pair<String, Any?>) {
        val entity = mediaEntity ?: return
        ctx.client.callService(ServiceCall.of("media_player", service, entity, *data))
    }

    companion object {
        fun from(config: CardConfig): AtvTarget? {
            val appleTv = config.string("appleTv")?.takeIf { it.isNotBlank() }
            val deviceId = config.string("deviceId")?.takeIf { it.isNotBlank() }
            if (appleTv == null && deviceId == null) return null
            val entity = config.string("entity")?.takeIf { it.isNotBlank() } ?: appleTv
            return AtvTarget(appleTv, deviceId, config.string("hub"), entity)
        }
    }
}

internal enum class AtvLayout { CLASSIC, COMPACT, FULL }

/**
 * On-device correction of the full layout's height estimate. The first time a given set of blocks
 * is laid out, the card measures where its bottom actually lands against the bottom of the screen
 * (minus the page dots) and remembers the difference, so the trackpad is resized to make the card
 * fit exactly — whatever the real height of the status bar, the page indicator or other cards
 * above it on the same page.
 */
internal class AtvFit {
    /** Correction in dp, per set of blocks (see AppleTvRemoteCard.fullBlocks). */
    val errors = mutableStateMapOf<List<Int>, Float>()

    // Written while composing, read by the next onGloballyPositioned: what the last layout used.
    var blocks: List<Int>? = null
    var rawEstimate = 0
    var usedPad = 0
}

/** Everything one render needs, resolved once from config + live state. */
internal class AtvRender(
    val ctx: CardContext,
    val config: CardConfig,
    val target: AtvTarget,
    val entity: EntityState?,
    val localPlaying: MutableState<Boolean>,
    val fit: AtvFit
) {
    val theme: ThemeColors get() = ctx.theme
    val apps: List<AtvApp> = if (target.mediaEntity != null) AtvApp.listFrom(config.options["apps"]) else emptyList()
    val showArtwork = config.bool("showArtwork", true)
    val showProgress = config.bool("showProgress", true)
    val showSkip = config.bool("showSkip", true) && target.isDirect

    fun send(command: String) = target.send(ctx, command)

    /** Real state when the target reports one; otherwise (Harmony) our own toggle guess. */
    val isPlaying: Boolean get() = entity?.state?.let { it == "playing" } ?: localPlaying.value

    fun playPause() {
        // Harmony has separate Play/Pause codes, so we track the state ourselves;
        // a direct Apple TV has a real play/pause toggle and needs no guess.
        if (target.isDirect) {
            send("PlayPause")
        } else {
            send(if (isPlaying) "Pause" else "Play")
            localPlaying.value = !localPlaying.value
        }
    }

    fun launch(app: AtvApp) = target.mediaService(ctx, "select_source", "source" to app.source)

    fun seekTo(seconds: Double) = target.mediaService(ctx, "media_seek", "seek_position" to seconds)

    val holds: TrackpadHolds
        get() =
            if (!target.isDirect) {
                TrackpadHolds()
            } else {
                TrackpadHolds(
                    onSelect = { send("SelectHold") },
                    onLeft = if (showSkip) ({ send("SeekBackward") }) else null,
                    onRight = if (showSkip) ({ send("SeekForward") }) else null
                )
            }

    fun buttons(defaults: List<String>): List<String> = config.stringList("buttons").ifEmpty { defaults }
}

class AppleTvRemoteCard : CardRenderer {
    override val type = "apple_tv_remote"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val target = AtvTarget.from(config) ?: return
        val entity = target.mediaEntity?.let { ctx.entities[it] }
        val layout =
            when (config.string("layout")?.lowercase()) {
                "compact" -> AtvLayout.COMPACT
                "full" -> AtvLayout.FULL
                else -> AtvLayout.CLASSIC
            }
        val fit = remember(config) { AtvFit() }
        val r = AtvRender(ctx, config, target, entity, rememberLocalPlaying(target.mediaEntity), fit)
        val density = LocalDensity.current
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .then(if (layout == AtvLayout.FULL) Modifier.onGloballyPositioned { measureFit(fit, it, density) } else Modifier)
                .clip(RoundedCornerShape(24.dp))
                .background(ctx.theme.cardSurface)
                .padding(if (layout == AtvLayout.CLASSIC) CLASSIC_PADDING.dp else CARD_PADDING.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(if (layout == AtvLayout.CLASSIC) 18.dp else GAP.dp)
        ) {
            when (layout) {
                AtvLayout.CLASSIC -> ClassicLayout(r)
                AtvLayout.COMPACT -> CompactLayout(r)
                AtvLayout.FULL -> FullLayout(r)
            }
        }
    }

    // ---- classic: the card as it always was (plus the long-presses) ----------------------

    @Composable
    private fun ClassicLayout(r: AtvRender) {
        AtvTrackpad(220.dp, r.theme, r::send, r.holds)
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            r.buttons(ExtraButtons.DEFAULT_IDS).forEach { id ->
                val spec = ExtraButtons.resolve(id)
                PillButton(spec.icon, spec.label, r.theme) { r.send(spec.command) }
            }
        }
        PlayPauseButton(r, 56)
        if (r.apps.isNotEmpty()) {
            val width = cardInnerWidthDp(CLASSIC_PADDING).dp
            AtvAppsRow(r.ctx, r.apps.take(WIDE_MAX_APPS), 52.dp, labels = false, rowWidth = width, launch = r::launch)
        }
    }

    // ---- compact: remote first, now playing as a one-line strip --------------------------

    @Composable
    private fun CompactLayout(r: AtvRender) {
        val stripEntity = r.entity?.takeIf { r.showArtwork || r.showProgress }
        val strip = stripEntity != null
        val blocks =
            listOfNotNull(
                STRIP_H.takeIf { strip },
                BUTTON_ROW_H,
                PLAY_ROW_H.takeIf { !strip },
                APPS_ICONS_H.takeIf { r.apps.isNotEmpty() }
            )
        val pad = trackpadFor(blocks, min = 150, max = 212)
        if (stripEntity != null) NowPlayingStrip(r, stripEntity)
        AtvTrackpad(pad.dp, r.theme, r::send, r.holds)
        RoundButtonRow(r, ExtraButtons.COMPACT_DEFAULT_IDS, size = 52, labels = false)
        if (!strip) PlayPauseButton(r, 56)
        if (r.apps.isNotEmpty()) {
            Box(Modifier.fillMaxWidth().padding(top = 4.dp)) {
                val width = cardInnerWidthDp(CARD_PADDING).dp
                AtvAppsRow(r.ctx, r.apps.take(COMPACT_MAX_APPS), 52.dp, labels = false, rowWidth = width, launch = r::launch)
            }
        }
    }

    @Composable
    private fun NowPlayingStrip(r: AtvRender, entity: EntityState) {
        val np = AtvNowPlaying.of(entity)
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(r.theme.insetSurface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (r.showArtwork) AtvArtwork(r.ctx, entity, 52.dp, 10.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        np.title,
                        color = r.theme.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )
                    np.app?.let { Text(it, color = r.theme.mutedText, fontSize = 12.sp, maxLines = 1) }
                }
                if (r.showProgress) AtvProgress(entity, r.theme, thick = false, onSeek = null)
            }
            AtvRoundButton(if (r.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", 44.dp, r.theme, filled = true) {
                r.playPause()
            }
        }
    }

    // ---- full: rich now playing + the whole remote ----------------------------------------

    @Composable
    private fun FullLayout(r: AtvRender) {
        val entity = r.entity
        val buttons = r.buttons(ExtraButtons.DEFAULT_IDS)
        // The first two buttons flank the transport controls (Menu · −10 · ⏯ · +10 · Home); any
        // others sit in two short columns either side of the trackpad (up to 3 each), so the card
        // gains no extra row.
        val sideButtons = buttons.drop(2).take(SIDE_MAX_BUTTONS * 2)
        val pad = trackpadFor(fullBlocks(r), min = 140, max = 220, fit = r.fit)
        if (entity != null) FullNowPlaying(r, entity)
        FullTransportRow(r, buttons.take(2))
        if (r.apps.isNotEmpty()) {
            val width = cardInnerWidthDp(CARD_PADDING).dp
            AtvAppsRow(r.ctx, r.apps.take(WIDE_MAX_APPS), 44.dp, labels = true, rowWidth = width, launch = r::launch)
        }
        if (sideButtons.isEmpty()) {
            AtvTrackpad(pad.dp, r.theme, r::send, r.holds)
        } else {
            TrackpadWithSides(r, pad, sideButtons)
        }
    }

    /** Trackpad between two columns of buttons: even-indexed [side] buttons on the left, odd ones on the right. */
    @Composable
    private fun TrackpadWithSides(r: AtvRender, pad: Int, side: List<String>) {
        // Leave room for both columns; the trackpad shrinks if the card is too narrow.
        val innerWidth = cardInnerWidthDp(CARD_PADDING)
        val widthCap = innerWidth - (SIDE_BUTTON + SIDE_GAP) * 2
        val size = minOf(pad, widthCap).coerceAtLeast(MIN_SIDE_PAD)
        r.fit.usedPad = size
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SideColumn(r, side.filterIndexed { i, _ -> i % 2 == 0 })
            AtvTrackpad(size.dp, r.theme, r::send, r.holds)
            SideColumn(r, side.filterIndexed { i, _ -> i % 2 == 1 })
        }
    }

    @Composable
    private fun SideColumn(r: AtvRender, ids: List<String>) {
        Column(
            modifier = Modifier.width(SIDE_BUTTON.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            ids.forEach { id ->
                val spec = ExtraButtons.resolve(id)
                AtvRoundButton(spec.icon, spec.label, SIDE_BUTTON.dp, r.theme) { r.send(spec.command) }
            }
        }
    }

    @Composable
    private fun FullNowPlaying(r: AtvRender, entity: EntityState) {
        FullHeader(r, entity)
        if (r.showProgress) {
            // Draggable only when the player advertises SEEK — a directly paired Apple TV drops it for
            // apps that declare no seek, so the bar stays read-only there instead of doing nothing.
            AtvProgress(entity, r.theme, thick = true, onSeek = if (entity.supports(Feature.SEEK)) r::seekTo else null)
        }
    }

    /** −10 s / Play-Pause / +10 s, flanked by up to two [sideButtons] (first left, second right). */
    @Composable
    private fun FullTransportRow(r: AtvRender, sideButtons: List<String>) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement =
            if (sideButtons.isNotEmpty()) {
                Arrangement.SpaceBetween
            } else {
                Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally)
            },
            verticalAlignment = Alignment.CenterVertically
        ) {
            sideButtons.getOrNull(0)?.let { ExtraRoundButton(r, it) }
            if (r.showSkip) AtvRoundButton(Icons.Filled.Replay10, "−10 s", 48.dp, r.theme) { r.send("SeekBackward") }
            AtvRoundButton(if (r.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, "Play/Pause", 56.dp, r.theme, filled = true) {
                r.playPause()
            }
            if (r.showSkip) AtvRoundButton(Icons.Filled.Forward10, "+10 s", 48.dp, r.theme) { r.send("SeekForward") }
            sideButtons.getOrNull(1)?.let { ExtraRoundButton(r, it) }
        }
    }

    @Composable
    private fun ExtraRoundButton(r: AtvRender, id: String) {
        val spec = ExtraButtons.resolve(id)
        AtvRoundButton(spec.icon, spec.label, 48.dp, r.theme) { r.send(spec.command) }
    }

    @Composable
    private fun FullHeader(r: AtvRender, entity: EntityState) {
        val np = AtvNowPlaying.of(entity)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (r.showArtwork) AtvArtwork(r.ctx, entity, FULL_HEADER_H.dp, 14.dp)
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(mediaStateLabel(entity.state), color = r.theme.accent, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    np.title,
                    color = r.theme.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                np.subtitle?.let { Text(it, color = r.theme.mutedText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                np.app?.let {
                    Text(
                        it,
                        color = r.theme.primaryText,
                        fontSize = 12.sp,
                        modifier =
                        Modifier
                            .clip(RoundedCornerShape(50))
                            .background(r.theme.controlBackground)
                            .padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }
    }

    // ---- shared ---------------------------------------------------------------------------

    @Composable
    private fun RoundButtonRow(r: AtvRender, defaults: List<String>, size: Int, labels: Boolean) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.Top) {
            r.buttons(defaults).forEach { id ->
                val spec = ExtraButtons.resolve(id)
                AtvRoundButton(spec.icon, spec.label, size.dp, r.theme, showLabel = labels) { r.send(spec.command) }
            }
        }
    }

    @Composable
    private fun PlayPauseButton(r: AtvRender, size: Int) {
        Box(
            modifier =
            Modifier
                .size(size.dp)
                .clip(CircleShape)
                .background(r.theme.controlBackground)
                .tapClickable(focusShape = CircleShape) { r.playPause() },
            contentAlignment = Alignment.Center
        ) {
            Icon(if (r.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = null, tint = r.theme.primaryText)
        }
    }

    @Composable
    private fun PillButton(icon: ImageVector? = null, label: String, theme: ThemeColors, onClick: () -> Unit) {
        Row(
            modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(theme.controlBackground)
                .tapClickable(focusShape = RoundedCornerShape(50), onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = theme.primaryText) }
            Text(label, color = theme.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}

// ---- sizing (file-level so the card class stays small) ----------------------------------

private const val COMPACT_MAX_APPS = 6

// Classic and full: more than 5 app icons don't fit the width (and scrolling them
// sideways fights the page swipe).
private const val WIDE_MAX_APPS = 5
private const val SIDE_MAX_BUTTONS = 3
private const val SIDE_BUTTON = 44
private const val SIDE_GAP = 8
private const val MIN_SIDE_PAD = 120

// Page dots + bottom page padding under the card, for the on-device fit (AtvFit).
private const val PAGE_BOTTOM_CHROME_H = 42

// Vertical budget, in dp. The HA100 dashboard keeps ~30 dp for the top status bar
// (Wi-Fi/time/battery), ~34 for the page indicator and 16 of page padding.
private const val DASHBOARD_CHROME_H = 84
private const val CARD_PADDING = 14
private const val CLASSIC_PADDING = 20
private const val GAP = 12
private const val STRIP_H = 68
private const val BUTTON_ROW_H = 52 // compact
private const val PLAY_ROW_H = 56
private const val APPS_ICONS_H = 56
private const val FULL_HEADER_H = 80
private const val PROGRESS_H = 28
private const val TRANSPORT_H = 56
private const val APPS_LABELLED_H = 62

/**
 * Trackpad diameter (dp) that lets the whole card fit on one page: the screen height minus the
 * dashboard's own chrome (top status bar, page indicator, page padding), the card's padding,
 * the other [blocks] and the gaps between them — clamped to [min]..[max]. On a page that also
 * holds other cards it simply scrolls, as before.
 */
@Composable
private fun trackpadFor(blocks: List<Int>, min: Int, max: Int, fit: AtvFit? = null): Int {
    val screen = LocalConfiguration.current.screenHeightDp
    val used = DASHBOARD_CHROME_H + CARD_PADDING * 2 + blocks.sum() + GAP * blocks.size
    val raw = screen - used
    val correction = fit?.errors?.get(blocks) ?: 0f
    val pad = (raw + correction).toInt().coerceIn(min, max)
    if (fit != null) {
        fit.blocks = blocks
        fit.rawEstimate = raw
        fit.usedPad = pad
    }
    return pad
}

/**
 * Once per set of blocks: how far the card's bottom lands past (or short of) the bottom of the
 * screen minus the page dots, turned into a correction for [trackpadFor].
 */
private fun measureFit(fit: AtvFit, coords: LayoutCoordinates, density: Density) {
    val blocks = fit.blocks ?: return
    if (fit.errors.containsKey(blocks)) return
    val rootHeight = coords.findRootCoordinates().size.height
    val bottom = coords.positionInWindow().y + coords.size.height
    val limit = rootHeight - with(density) { PAGE_BOTTOM_CHROME_H.dp.toPx() }
    val overflowDp = with(density) { (bottom - limit).toDp().value }
    fit.errors[blocks] = fit.usedPad - overflowDp - fit.rawEstimate
}

/** Heights (dp) of everything the full layout shows besides the trackpad — see [trackpadFor]. */
private fun fullBlocks(r: AtvRender): List<Int> {
    val hasEntity = r.entity != null
    return listOfNotNull(
        FULL_HEADER_H.takeIf { hasEntity },
        PROGRESS_H.takeIf { hasEntity && r.showProgress },
        TRANSPORT_H,
        APPS_LABELLED_H.takeIf { r.apps.isNotEmpty() }
    )
}
