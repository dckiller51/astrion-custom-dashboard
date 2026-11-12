package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Dialpad
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
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
import com.custom.astrion.zidoo.EversoloInput

/**
 * The Zidoo / Eversolo card (1.5.1): one card for a player added in Astrion that shows what
 * matters for what plays. A film: its backdrop, ±10 s, and its subtitle, audio and chapter
 * buttons. Music: the cover, what plays as an audiophile reads it, repeat and the play queue. An
 * Eversolo: its input and output too. Everything comes from the player itself (its control API,
 * port 9529), no Home Assistant.
 *
 * ```
 * { "type": "zidoo", "options": {
 *     "entity": "media_player.zidoo_z9x",     // the player added in Astrion
 *     "name": "Living room",                   // optional, the player's name otherwise
 *     "show_sources": true,                    // header: every app / input in a pop-up
 *     "show_keypad": true,                     // header: 0–9 keypad (default: a Zidoo yes, an Eversolo no)
 *     "show_tracks": true,                     // film: Subtitles, Audio, Chapters
 *     "show_queue": true,                      // music: the play queue
 *     "show_io": true,                         // an Eversolo: Input, Output
 *     "show_skip": true,                       // film: −10 s / +10 s
 *     "show_repeat": true,                     // music: repeat
 *     "show_quality": true,                    // "FLAC · 44.1 kHz · 16 bit"
 *     "show_volume": false,                    // − mute + (a volume set elsewhere: leave off)
 *     "show_apps": true, "apps": [ { "name": "Plex", "app": "com.plexapp.android" } ]
 * } }
 * ```
 * Without `apps`, the player's own apps (its utilities left out) are shown.
 */
class ZidooCard : CardRenderer {
    override val type = "zidoo"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity") ?: return
        val e = ctx.entities[entityId]
        val theme = ctx.theme
        val view = remember(e) { ZidooView.of(e) }
        var dialog by remember { mutableStateOf<ZidooDialog?>(null) }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(theme.cardSurface)
                .padding(CARD_PADDING.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Header(ctx, config, entityId, e, view) { dialog = it }
            Body(ctx, config, entityId, e, view) { dialog = it }
        }
        dialog?.let { ZidooCardDialog(ctx, config, entityId, e, it) { dialog = null } }
    }

    /** What plays and its buttons, else "Nothing playing"; then the volume and the apps. */
    @Composable
    private fun Body(
        ctx: CardContext,
        config: CardConfig,
        entityId: String,
        e: EntityState?,
        view: ZidooView,
        open: (ZidooDialog) -> Unit
    ) {
        if (!view.on || e == null) return
        if (view.playing) {
            NowPlaying(ctx, entityId, e, view, config)
            Chips(ctx, config, e, view, open)
        } else {
            Text(stringResource(R.string.zidoo_card_nothing), color = ctx.theme.mutedText, fontSize = 13.sp)
            if (view.eversolo) Chips(ctx, config, e, view, open)
        }
        if (config.bool("show_volume", false)) VolumeRow(ctx, entityId)
        if (!config.bool("show_apps", true)) return
        val apps = AppShortcuts.appsOf(config.options) ?: playerApps(e)
        if (apps.isNotEmpty()) {
            Text(stringResource(R.string.zidoo_card_apps), color = ctx.theme.mutedText, fontSize = 12.sp)
            AppShortcuts(ctx, apps, entityId).Rows(iconsStyle = config.string("apps_style") == "icons", cardPadding = CARD_PADDING)
        }
    }

    @Composable
    private fun Header(
        ctx: CardContext,
        config: CardConfig,
        entityId: String,
        e: EntityState?,
        view: ZidooView,
        open: (ZidooDialog) -> Unit
    ) {
        val theme = ctx.theme
        val name = config.string("name") ?: e?.attrString("friendly_name") ?: entityId
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = theme.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                val state = mediaStateLabel(if (view.on && !view.playing) "on" else e?.state)
                val status = listOfNotNull(state, view.app.takeIf { view.playing }).joinToString(" · ")
                Text(
                    (if (view.playing && e?.state == "playing") "● " else "") + status,
                    color = if (view.playing) theme.accent else theme.mutedText,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            HeaderButtons(ctx, config, entityId, e, view, open)
        }
    }

    /** Browse, every app / input, the 0–9 keypad (a Zidoo's; an Eversolo has no number keys), power. */
    @Composable
    private fun HeaderButtons(
        ctx: CardContext,
        config: CardConfig,
        entityId: String,
        e: EntityState?,
        view: ZidooView,
        open: (ZidooDialog) -> Unit
    ) {
        val theme = ctx.theme
        if (view.on && canBrowse(entityId, e)) {
            ZidooRoundIcon(
                Icons.Filled.VideoLibrary,
                stringResource(R.string.media_browse),
                theme,
                size = HEAD
            ) { open(ZidooDialog.BROWSE) }
        }
        if (view.on && config.bool("show_sources", true) && QuickPickers.sourcesOf(ctx, entityId).isNotEmpty()) {
            ZidooRoundIcon(Icons.Filled.Apps, stringResource(R.string.picker_sources), theme, size = HEAD) { open(ZidooDialog.SOURCES) }
        }
        if (view.on && config.bool("show_keypad", !view.eversolo)) {
            ZidooRoundIcon(Icons.Filled.Dialpad, stringResource(R.string.picker_keypad), theme, size = HEAD) { open(ZidooDialog.KEYPAD) }
        }
        ZidooRoundIcon(Icons.Filled.PowerSettingsNew, stringResource(R.string.media_power_toggle), theme, accent = view.on, size = HEAD) {
            zidooMp(ctx, entityId, if (view.on) "turn_off" else "turn_on")
        }
    }

    @Composable
    private fun NowPlaying(ctx: CardContext, entityId: String, e: EntityState, view: ZidooView, config: CardConfig) {
        val theme = ctx.theme
        Artwork(ctx, e, view.video)
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                e.attrString("media_title") ?: "",
                color = theme.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
            subtitleOf(e)?.let {
                Text(
                    it,
                    color = theme.mutedText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }
            if (config.bool("show_quality", true)) MediaQualityLine(e, theme)
        }
        val seek: ((Double) -> Unit)? =
            if (e.supports(Feature.SEEK)) ({ s -> zidooMp(ctx, entityId, "media_seek", "seek_position" to s.coerceAtLeast(0.0)) }) else null
        AtvProgress(e, theme, thick = true, onSeek = seek)
        Transport(ctx, entityId, e, view, config)
    }

    @Composable
    private fun Artwork(ctx: CardContext, e: EntityState, video: Boolean) {
        val path = e.attrString("entity_picture")
        val width = cardInnerWidthDp(CARD_PADDING).dp
        val px = with(LocalDensity.current) { width.toPx() }.toInt()
        val art = rememberImage(ctx, path, px)
        // A film's backdrop across the card; a cover square, centred.
        val mod =
            if (video) {
                Modifier.fillMaxWidth().aspectRatio(BACKDROP_RATIO)
            } else {
                Modifier.size(minOf(width, COVER_MAX.dp))
            }
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Box(mod.clip(RoundedCornerShape(14.dp)).background(ctx.theme.controlBackground), contentAlignment = Alignment.Center) {
                if (art != null) {
                    Image(
                        art,
                        null,
                        modifier = Modifier.fillMaxWidth().aspectRatio(if (video) BACKDROP_RATIO else 1f),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        if (video) MdiIcons.Video else MdiIcons.Cast,
                        null,
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(40.dp)
                    )
                }
            }
        }
    }

    @Composable
    private fun Transport(ctx: CardContext, entityId: String, e: EntityState, view: ZidooView, config: CardConfig) {
        val theme = ctx.theme
        val playing = e.state == "playing"
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            if (view.video && config.bool("show_skip", true)) {
                ZidooRoundIcon(
                    Icons.Filled.Replay10,
                    stringResource(R.string.zidoo_card_back10),
                    theme,
                    size = SMALL
                ) { skip(ctx, entityId, e, -SKIP_S) }
            }
            if (!view.video && config.bool("show_repeat", true)) {
                ZidooRoundIcon(
                    Icons.Filled.Repeat,
                    stringResource(R.string.zidoo_card_repeat),
                    theme,
                    size = SMALL
                ) { zidooKey(ctx, entityId, "Key.Repeat") }
            }
            ZidooRoundIcon(MdiIcons.SkipPrevious, null, theme, size = SMALL) { zidooMp(ctx, entityId, "media_previous_track") }
            ZidooRoundIcon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, null, theme, accent = true, size = BIG) {
                zidooMp(ctx, entityId, "media_play_pause")
            }
            ZidooRoundIcon(MdiIcons.SkipNext, null, theme, size = SMALL) { zidooMp(ctx, entityId, "media_next_track") }
            if (view.video && config.bool("show_skip", true)) {
                ZidooRoundIcon(Icons.Filled.Forward10, stringResource(R.string.zidoo_card_forward10), theme, size = SMALL) {
                    skip(ctx, entityId, e, SKIP_S)
                }
            }
        }
    }

    /** The buttons that follow what plays: a film's tracks, music's queue, an Eversolo's input and output. */
    @Composable
    private fun Chips(ctx: CardContext, config: CardConfig, e: EntityState, view: ZidooView, open: (ZidooDialog) -> Unit) {
        val chips = buildList {
            if (view.playing && view.video && config.bool("show_tracks", true)) {
                add(Chip(R.string.zidoo_card_subtitles, e.attrString("astrion_subtitle"), ZidooDialog.SUBTITLES))
                add(Chip(R.string.zidoo_card_audio, e.attrString("astrion_audio"), ZidooDialog.AUDIO))
                add(Chip(R.string.zidoo_card_chapters, null, ZidooDialog.CHAPTERS))
            }
            if (view.playing && !view.video && config.bool("show_queue", true)) {
                add(
                    Chip(R.string.zidoo_card_queue, null, ZidooDialog.QUEUE)
                )
            }
            if (view.eversolo && config.bool("show_io", true)) {
                add(Chip(R.string.zidoo_card_input, null, ZidooDialog.IO, input = view.input))
                add(Chip(R.string.zidoo_card_output, null, ZidooDialog.IO))
            }
        }
        if (chips.isEmpty()) return
        chips.chunked(CHIPS_PER_ROW).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { chip -> ChipButton(chip, Modifier.weight(1f), ctx.theme) { open(chip.dialog) } }
            }
        }
    }

    @Composable
    private fun VolumeRow(ctx: CardContext, entityId: String) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ZidooRoundIcon(Icons.AutoMirrored.Filled.VolumeDown, null, ctx.theme, size = SMALL) { zidooMp(ctx, entityId, "volume_down") }
            ZidooRoundIcon(Icons.AutoMirrored.Filled.VolumeOff, null, ctx.theme, size = SMALL) {
                zidooMp(
                    ctx,
                    entityId,
                    "volume_mute",
                    "is_volume_muted" to (ctx.entities[entityId]?.attr("is_volume_muted")?.toString() != "true")
                )
            }
            ZidooRoundIcon(Icons.AutoMirrored.Filled.VolumeUp, null, ctx.theme, size = SMALL) { zidooMp(ctx, entityId, "volume_up") }
        }
    }

    private companion object {
        const val CARD_PADDING = 16
        const val CHIPS_PER_ROW = 3
        const val BACKDROP_RATIO = 16f / 9f
        const val COVER_MAX = 220
        const val SKIP_S = 10.0
        val SMALL = 44.dp
        val BIG = 60.dp

        /** The header's round buttons: up to five beside the name. */
        val HEAD = 36.dp
    }
}

/** What the card shows, from the media player entity. */
internal data class ZidooView(
    val on: Boolean,
    val playing: Boolean,
    val video: Boolean,
    val eversolo: Boolean,
    val app: String?,
    val input: EversoloInput?
) {
    companion object {
        fun of(e: EntityState?): ZidooView {
            val on = e != null && !e.isUnavailable && e.state != "off"
            val playing = on && (e!!.state == "playing" || e.state == "paused")
            val type = e?.attrString("media_content_type")
            val source = e?.attrString("source")
            return ZidooView(
                on = on,
                playing = playing,
                video = type == "movie" || type == "tvshow",
                eversolo = e?.attrString("manufacturer") == "Eversolo",
                app = e?.attrString("app_name") ?: source?.takeIf { EversoloInput.ofLabel(it) == null },
                input = EversoloInput.ofLabel(source) ?: EversoloInput.INTERNAL.takeIf { e?.attrString("manufacturer") == "Eversolo" }
            )
        }
    }
}

/** The card's pop-ups. */
internal enum class ZidooDialog { BROWSE, SOURCES, KEYPAD, SUBTITLES, AUDIO, CHAPTERS, QUEUE, IO }

private data class Chip(val label: Int, val value: String?, val dialog: ZidooDialog, val input: EversoloInput? = null)

@Composable
private fun ChipButton(chip: Chip, modifier: Modifier, theme: ThemeColors, onClick: () -> Unit) {
    Column(
        modifier
            .height(52.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.controlBackground)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(stringResource(chip.label), color = theme.primaryText, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        val value = chip.value ?: chip.input?.let { stringResource(inputLabelRes(it)) }
        value?.let { Text(it, color = theme.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun ZidooRoundIcon(
    icon: ImageVector,
    description: String?,
    theme: ThemeColors,
    accent: Boolean = false,
    size: Dp = 40.dp,
    onClick: () -> Unit
) {
    Box(
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (accent) theme.accentSecondary else theme.controlBackground)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = description, tint = Color.White, modifier = Modifier.width(size * ICON_SHARE))
    }
}

private const val ICON_SHARE = 0.5f

/** "Kaamelott · Season 5 · Episode 1" for an episode, "Metallica · Ride the Lightning" for a track. */
@Composable
private fun subtitleOf(e: EntityState): String? {
    val series = e.attrString("media_series_title")
    if (series != null) {
        val season = e.attrDouble("media_season")?.toInt()
        val episode = e.attrDouble("media_episode")?.toInt()
        val parts = listOfNotNull(
            series,
            season?.let { stringResource(R.string.zidoo_card_season, it) },
            episode?.let { stringResource(R.string.zidoo_card_episode, it) }
        )
        return parts.joinToString(" · ")
    }
    return listOfNotNull(e.attrString("media_artist"), e.attrString("media_album_name")).joinToString(" · ").ifEmpty { null }
}

/** The player's own apps (its utilities left out), as the TV remote card's `apps` take them. */
@Suppress("UNCHECKED_CAST")
internal fun playerApps(e: EntityState?): List<Map<String, Any?>> {
    val list = e?.attr("astrion_apps") as? kotlinx.serialization.json.JsonArray ?: return emptyList()
    return list.mapNotNull { el ->
        val o = el as? kotlinx.serialization.json.JsonObject ?: return@mapNotNull null
        val name = (o["name"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
        val app = (o["app"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return@mapNotNull null
        mapOf("name" to name, "app" to app)
    }
}

internal fun inputLabelRes(input: EversoloInput): Int = when (input) {
    EversoloInput.INTERNAL -> R.string.zidoo_in_internal
    EversoloInput.BLUETOOTH -> R.string.zidoo_in_bluetooth
    EversoloInput.USB -> R.string.zidoo_in_usb
    EversoloInput.COAXIAL -> R.string.zidoo_in_coaxial
    EversoloInput.OPTICAL -> R.string.zidoo_in_optical
}

internal fun zidooMp(ctx: CardContext, entityId: String, service: String, vararg data: Pair<String, Any?>) {
    ctx.client.callService(ServiceCall.of("media_player", service, entityId, *data))
}

/** A player key (`Key.Repeat`…) through its remote entity, which takes them as they are. */
internal fun zidooKey(ctx: CardContext, entityId: String, key: String) {
    ctx.client.callService(ServiceCall.of("remote", "send_command", "remote." + entityId.substringAfter('.'), "command" to key))
}

/** ±10 s: a seek when the player can, else its rewind / fast-forward key. */
private fun skip(ctx: CardContext, entityId: String, e: EntityState, seconds: Double) {
    if (e.supports(Feature.SEEK)) {
        zidooMp(ctx, entityId, "media_seek", "seek_position" to (currentMediaPosition(e) + seconds).coerceAtLeast(0.0))
    } else {
        zidooKey(ctx, entityId, if (seconds < 0) "Key.MediaBackward" else "Key.MediaForward")
    }
}
