package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.androidtv.AndroidTvApps
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * TV / Android-TV remote card.
 *
 * Reuses your existing Home Assistant `remote.*` command map — the same
 * mechanism your working aiks-tv-card uses. Each button fires:
 *   remote.send_command  with { entity_id, command }
 * against your Android TV remote entity, e.g. `remote.android_tv_10_0_1_248`.
 *
 * Unlike the stock card, the layout is fully yours: a D-pad cluster, a transport
 * row, and volume — all native Compose, resized for the 480x800 panel.
 *
 * Config shape:
 *   { "type": "tv_remote", "options": {
 *       "name": "Living room TV",
 *       "remote_entity": "remote.living_room_tv",
 *       // optional: where the volume / mute keys go when it isn't the TV
 *       // itself (e.g. a soundbar's own remote.* entity)
 *       "mute_entity": "remote.soundbar",
 *       // optional: the TV's media_player, for the app buttons. Defaults to
 *       // media_player.<same object id as remote_entity> — what the Android
 *       // TV Remote integration creates (1.2.9; it used to default to one
 *       // hard-coded entity of the original author's setup).
 *       "media_entity": "media_player.living_room_tv",
 *       "show_volume": true,     // optional: the vol− / mute / vol+ row (1.2.9)
 *       "apps": [ { "name": "Netflix", "app": "com.netflix.ninja",
 *                   "icon": "/sdcard/astrion/icons/netflix.png", "color": "#B3261E" },   // icon/color: 1.3.6
 *                 { "name": "Night mode", "service": "script.night", "entity_id": null, "data": {} } ],
 *       "apps_style": "icons",          // optional (1.3.6): "buttons" (default) or "icons"
 *       "show_now_playing": true,       // optional (1.3.6): the app in front / title, cover, progress
 *       "now_playing_entity": "media_player.shield_cast",   // optional (1.3.6): default media_entity
 *       "show_transport": false,        // optional (1.3.6): rewind / play-pause / fast forward
 *       "layout": "full",               // optional (1.3.6): large cover, draggable progress, ±10 s, app icons
 *       "show_skip": true,              // optional (1.3.6, full layout): the −10 s / Play-Pause / +10 s row
 *       "commands": { "power": "POWER", "volume_up": "VOLUME_UP" }   // optional renames
 *   } }
 *
 * Command values below match the Android TV Remote integration's
 * (DPAD_UP/DOWN/LEFT/RIGHT/CENTER, BACK, HOME, MENU, POWER, VOLUME_UP/DOWN,
 * MUTE); any key can be renamed through `commands`.
 */
class TvRemoteCard : CardRenderer {
    override val type = "tv_remote"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val remoteEntity = config.string("remote_entity") ?: return
        val commands = (config.options["commands"] as? Map<String, Any?>).orEmpty()
        val mediaEntity = config.string("media_entity") ?: "media_player.${remoteEntity.substringAfter('.')}"
        val r =
            TvRender(
                ctx = ctx,
                remoteEntity = remoteEntity,
                muteEntity = config.string("mute_entity") ?: remoteEntity,
                mediaEntity = mediaEntity,
                commands = commands,
                apps = (config.options["apps"] as? List<Map<String, Any?>>) ?: DEFAULT_APPS
            )
        val nowPlaying = nowPlayingOf(config, ctx, mediaEntity)
        val full = config.string("layout") == "full"
        val fit = remember(config) { AtvFit() }
        val density = LocalDensity.current

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                // Full: measures where the card ends once, to size the D-pad to the screen (as the Apple TV card does).
                .then(if (full) Modifier.onGloballyPositioned { measureFit(fit, it, density) } else Modifier)
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(if (full) FULL_PADDING.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (full) FULL_GAP.dp else 14.dp)
        ) {
            // Header row: name + power. A long name is cut, never pushed under the power button.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    config.string("name") ?: "TV",
                    color = ctx.theme.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                RoundIconButton(Icons.Filled.PowerSettingsNew, ctx.theme, tint = ctx.theme.danger) {
                    r.send(r.c("power", "POWER"))
                }
            }

            if (full) {
                FullLayout(r, config, nowPlaying, fit)
            } else {
                if (nowPlaying != null) NowPlayingStrip(r, nowPlaying)
                RemoteControls(r, config)
                if (config.string("apps_style") == "icons") AppIcons(r) else AppButtons(r)
            }
        }
    }

    /**
     * The `full` layout, like the Apple TV card's: what's playing on top (cover, draggable progress,
     * ±10 s), the apps, then a round D-pad between back / home / menu and the volume buttons. The
     * D-pad takes the height left on screen, so the whole card fits without scrolling.
     */
    @Composable
    private fun FullLayout(r: TvRender, config: CardConfig, nowPlaying: EntityState?, fit: AtvFit) {
        // −10 s / Play-Pause / +10 s can go: the progress bar still seeks, and it leaves the D-pad more room.
        val showSkip = config.bool("show_skip", true)
        if (nowPlaying != null) FullNowPlaying(r, nowPlaying, showSkip)
        if (r.apps.isNotEmpty()) {
            AtvAppsRow(
                r.ctx,
                r.apps.map(r::tileOf).take(ICONS_PER_ROW),
                44.dp,
                labels = true,
                rowWidth = cardInnerWidthDp(FULL_PADDING).dp
            ) { tile ->
                r.apps.firstOrNull { r.tileOf(it) == tile }?.let(r::launch)
            }
        }
        val blocks =
            listOfNotNull(
                HEADER_H,
                FULL_NOW_PLAYING_H.takeIf { nowPlaying != null },
                FULL_PROGRESS_H.takeIf { nowPlaying != null },
                FULL_TRANSPORT_H.takeIf { nowPlaying != null && showSkip },
                FULL_APPS_H.takeIf { r.apps.isNotEmpty() }
            )
        val widthCap = cardInnerWidthDp(FULL_PADDING) - (SIDE_BUTTON + SIDE_GAP) * 2
        val pad = minOf(trackpadFor(blocks, min = MIN_PAD, max = MAX_PAD, fit = fit), widthCap)
        fit.usedPad = pad
        val theme = r.ctx.theme
        // Three buttons per side column: never taller than the D-pad beside them.
        val side = ((pad - SIDE_GAP * 2) / 3).coerceIn(MIN_SIDE_BUTTON, SIDE_BUTTON).dp
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            SideColumn {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, theme, size = side) { r.send(r.c("back", "BACK")) }
                RoundIconButton(Icons.Filled.Home, theme, size = side) { r.send(r.c("home", "HOME")) }
                RoundIconButton(Icons.Filled.Menu, theme, size = side) { r.send(r.c("menu", "MENU")) }
            }
            DPad(
                r,
                modifier = Modifier.size(pad.dp),
                buttonSize = (pad * DPAD_BUTTON_RATIO).toInt().coerceIn(MIN_DPAD_BUTTON, 52).dp,
                centerSize = (pad * DPAD_CENTER_RATIO).toInt().coerceIn(MIN_DPAD_CENTER, 64).dp
            )
            SideColumn {
                if (config.bool("show_volume", true)) {
                    RoundIconButton(
                        Icons.AutoMirrored.Filled.VolumeUp,
                        theme,
                        size = side
                    ) { r.send(r.c("volume_up", "VOLUME_UP"), r.muteEntity) }
                    RoundIconButton(Icons.AutoMirrored.Filled.VolumeOff, theme, size = side) { r.send(r.c("mute", "MUTE"), r.muteEntity) }
                    RoundIconButton(Icons.AutoMirrored.Filled.VolumeDown, theme, size = side) {
                        r.send(r.c("volume_down", "VOLUME_DOWN"), r.muteEntity)
                    }
                }
            }
        }
    }

    @Composable
    private fun SideColumn(content: @Composable () -> Unit) {
        Column(
            modifier = Modifier.width(SIDE_BUTTON.dp),
            verticalArrangement = Arrangement.spacedBy(SIDE_GAP.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) { content() }
    }

    /** The D-pad, back / home / menu, and the optional transport and volume rows. */
    @Composable
    private fun RemoteControls(r: TvRender, config: CardConfig) {
        val ctx = r.ctx
        // D-pad cluster
        DPad(r)

        // Navigation row: back / home / menu
        ButtonRow(
            ctx.theme,
            Icons.AutoMirrored.Filled.ArrowBack to { r.send(r.c("back", "BACK")) },
            Icons.Filled.Home to { r.send(r.c("home", "HOME")) },
            Icons.Filled.Menu to { r.send(r.c("menu", "MENU")) }
        )

        // Transport row (1.3.6, optional): rewind / play-pause / fast forward.
        if (config.bool("show_transport", false)) {
            ButtonRow(
                ctx.theme,
                Icons.Filled.FastRewind to { r.send(r.c("rewind", "MEDIA_REWIND")) },
                Icons.Filled.PlayArrow to { r.send(r.c("play_pause", "MEDIA_PLAY_PAUSE")) },
                Icons.Filled.FastForward to { r.send(r.c("fast_forward", "MEDIA_FAST_FORWARD")) }
            )
        }

        // Volume row: sent to mute_entity when set (a soundbar), else the TV.
        if (config.bool("show_volume", true)) {
            ButtonRow(
                ctx.theme,
                Icons.AutoMirrored.Filled.VolumeDown to { r.send(r.c("volume_down", "VOLUME_DOWN"), r.muteEntity) },
                Icons.AutoMirrored.Filled.VolumeOff to { r.send(r.c("mute", "MUTE"), r.muteEntity) },
                Icons.AutoMirrored.Filled.VolumeUp to { r.send(r.c("volume_up", "VOLUME_UP"), r.muteEntity) }
            )
        }
    }

    /** The player the now-playing part follows, when it's on and has something to show. */
    private fun nowPlayingOf(config: CardConfig, ctx: CardContext, mediaEntity: String): EntityState? {
        if (!config.bool("show_now_playing", true)) return null
        val id = config.string("now_playing_entity")?.takeIf { it.isNotBlank() } ?: mediaEntity
        return ctx.entities[id]?.takeIf { it.state !in NOT_PLAYING_STATES && hasSomethingToShow(it) }
    }

    /** An app or a title to show: without either the strip would only repeat the card's name. */
    private fun hasSomethingToShow(e: EntityState): Boolean = e.attrString("app_id")?.let { it in HOME_SCREENS } != true &&
        listOf("app_id", "app_name", "media_title").any { !e.attrString(it).isNullOrBlank() }

    /** What every part of the card needs to send its commands. */
    private class TvRender(
        val ctx: CardContext,
        val remoteEntity: String,
        val muteEntity: String,
        val mediaEntity: String,
        val commands: Map<String, Any?>,
        val apps: List<Map<String, Any?>>
    ) {
        /** Per-button command names, overridable from config; defaults match Android TV's `remote.send_command`. */
        fun c(key: String, default: String): String = (commands[key] as? String) ?: default

        fun send(command: String, entity: String = remoteEntity) {
            ctx.client.callService(ServiceCall.of(domain = "remote", service = "send_command", entityId = entity, "command" to command))
        }

        /**
         * An app button: `{ name, app }` is launched with media_player.play_media on `media_entity`,
         * `{ name, service: "domain.service", entity_id, data }` calls that service.
         */
        @Suppress("UNCHECKED_CAST")
        fun launch(app: Map<String, Any?>) {
            val service = app["service"] as? String
            if (service != null) {
                val data = (app["data"] as? Map<String, Any?>).orEmpty()
                ctx.client.callService(
                    ServiceCall.of(
                        service.substringBefore('.'),
                        service.substringAfter('.'),
                        app["entity_id"] as? String,
                        *data.entries.map { it.key to it.value }.toTypedArray()
                    )
                )
            } else {
                val appId = app["app"] as? String ?: return
                ctx.client.callService(
                    ServiceCall.of("media_player", "play_media", mediaEntity, "media_content_type" to "app", "media_content_id" to appId)
                )
            }
        }

        /** Jumps to [seconds] in what [entity] plays (needs SEEK: the Astrion TV Helper, Plex, Cast…). */
        fun seek(entity: EntityState, seconds: Double) {
            ctx.client.callService(
                ServiceCall.of("media_player", "media_seek", entity.entityId, "seek_position" to seconds.coerceAtLeast(0.0))
            )
        }

        /** −10 s / +10 s: a real jump when the player can seek, else the TV's rewind / fast-forward keys. */
        fun skip(entity: EntityState, deltaSeconds: Double) {
            if (entity.supports(Feature.SEEK)) {
                seek(entity, currentMediaPosition(entity) + deltaSeconds)
            } else {
                send(if (deltaSeconds < 0) c("rewind", "MEDIA_REWIND") else c("fast_forward", "MEDIA_FAST_FORWARD"))
            }
        }

        fun playPause() = send(c("play_pause", "MEDIA_PLAY_PAUSE"))

        /** [app] as an icon tile: its `icon` (file or URL), else its initials on `color`. */
        fun tileOf(app: Map<String, Any?>): AtvApp {
            val label = app["name"] as? String ?: "App"
            return AtvApp(
                source = (app["app"] as? String) ?: label,
                label = label,
                icon = (app["icon"] as? String)?.takeIf { it.isNotBlank() },
                color = parseHexColor(app["color"] as? String)
            )
        }

        /** The card's own entry for the app in front, matched on its package (`app_id`). */
        fun appInFront(entity: EntityState): Map<String, Any?>? {
            val id = entity.attrString("app_id")?.takeIf { it.isNotBlank() } ?: return null
            return apps.firstOrNull { it["app"] == id }
        }
    }

    /**
     * What's on: the cover and title when the entity has them (e.g. a Cast or Plex player from
     * Home Assistant), otherwise the app in front with the icon given to it in this card.
     */
    @Composable
    private fun NowPlayingStrip(r: TvRender, entity: EntityState) {
        val theme = r.ctx.theme
        val np = AtvNowPlaying.of(entity)
        val app = r.appInFront(entity)
        val hasCover = !(entity.attrString("entity_picture_local") ?: entity.attrString("entity_picture")).isNullOrBlank()
        val title = if (np.app == null && app != null) app["name"] as? String ?: np.title else np.title
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.insetSurface)
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (!hasCover && app != null) {
                AtvAppTile(r.ctx, r.tileOf(app), 0, 52.dp, labelWidth = null) { r.launch(app) }
            } else {
                AtvArtwork(r.ctx, entity, 52.dp, 10.dp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = theme.primaryText,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                (np.subtitle ?: np.app)?.let {
                    Text(it, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                AtvProgress(entity, theme, thick = false, onSeek = seekHandler(r, entity))
            }
            RoundIconButton(if (entity.state == "playing") Icons.Filled.Pause else Icons.Filled.PlayArrow, theme) { r.playPause() }
        }
    }

    /** Dragging the bar seeks, when the player can: otherwise it only shows the progress. */
    private fun seekHandler(r: TvRender, entity: EntityState): ((Double) -> Unit)? =
        if (entity.supports(Feature.SEEK)) ({ seconds -> r.seek(entity, seconds) }) else null

    /**
     * The `full` layout's now playing, like the Apple TV card's: a large cover, the state, title,
     * subtitle and app, a progress bar to drag, then −10 s / Play-Pause / +10 s.
     */
    @Composable
    private fun FullNowPlaying(r: TvRender, entity: EntityState, showSkip: Boolean) {
        val theme = r.ctx.theme
        val np = AtvNowPlaying.of(entity)
        val app = r.appInFront(entity)
        val hasCover = !(entity.attrString("entity_picture_local") ?: entity.attrString("entity_picture")).isNullOrBlank()
        val title = if (np.app == null && app != null) app["name"] as? String ?: np.title else np.title
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (!hasCover && app != null) {
                AtvAppTile(r.ctx, r.tileOf(app), 0, FULL_COVER_DP.dp, labelWidth = null) { r.launch(app) }
            } else {
                AtvArtwork(r.ctx, entity, FULL_COVER_DP.dp, 14.dp)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                // State and app on one line (not a chip of their own): keeps the block as tall as the cover.
                Text(
                    listOfNotNull(mediaStateLabel(entity.state), np.app).joinToString(" · "),
                    color = theme.accent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    title,
                    color = theme.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                np.subtitle?.let { Text(it, color = theme.mutedText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        AtvProgress(entity, theme, thick = true, onSeek = seekHandler(r, entity))
        if (!showSkip) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AtvRoundButton(Icons.Filled.Replay10, "−10 s", 48.dp, theme) { r.skip(entity, -SKIP_S) }
            AtvRoundButton(
                if (entity.state == "playing") Icons.Filled.Pause else Icons.Filled.PlayArrow,
                "Play/Pause",
                56.dp,
                theme,
                filled = true
            ) {
                r.playPause()
            }
            AtvRoundButton(Icons.Filled.Forward10, "+10 s", 48.dp, theme) { r.skip(entity, SKIP_S) }
        }
    }

    /** App shortcuts as text buttons, two per row (the original look). */
    @Composable
    private fun AppButtons(r: TvRender) {
        r.apps.chunked(2).forEach { pair ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                pair.forEach { app ->
                    AppButton(
                        label = app["name"] as? String ?: "App",
                        modifier = Modifier.weight(1f),
                        theme = r.ctx.theme
                    ) { r.launch(app) }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }

    /** App shortcuts as labelled icons (1.3.6), up to [ICONS_PER_ROW] per row. */
    @Composable
    private fun AppIcons(r: TvRender) {
        val width = cardInnerWidthDp(CARD_PADDING).dp
        r.apps.chunked(ICONS_PER_ROW).forEach { row ->
            AtvAppsRow(r.ctx, row.map(r::tileOf), 52.dp, labels = true, rowWidth = width) { tile ->
                row.firstOrNull { r.tileOf(it) == tile }?.let(r::launch)
            }
        }
    }

    @Composable
    private fun ButtonRow(theme: ThemeColors, vararg buttons: Pair<ImageVector, () -> Unit>) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            buttons.forEach { (icon, onClick) -> RoundIconButton(icon, theme, onClick = onClick) }
        }
    }

    @Composable
    private fun AppButton(label: String, modifier: Modifier, theme: ThemeColors, onClick: () -> Unit) {
        Box(
            modifier =
            modifier
                .height(48.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(theme.controlBackground)
                .tapClickable(onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = theme.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
        }
    }

    private companion object {
        const val CARD_PADDING = 16
        const val FULL_COVER_DP = 80

        // Full layout sizes (dp), for trackpadFor (AppleTvRemoteCard.kt): its padding and gap
        // (14 / 12) are the ones FULL_PADDING / FULL_GAP use here.
        const val FULL_PADDING = 14
        const val FULL_GAP = 12
        const val HEADER_H = 52
        const val FULL_NOW_PLAYING_H = 88
        const val FULL_PROGRESS_H = 28
        const val FULL_TRANSPORT_H = 56
        const val FULL_APPS_H = 62
        const val SIDE_BUTTON = 44
        const val MIN_SIDE_BUTTON = 32
        const val SIDE_GAP = 8
        const val MIN_PAD = 120
        const val MAX_PAD = 220
        const val MIN_DPAD_BUTTON = 36
        const val MIN_DPAD_CENTER = 48
        const val DPAD_BUTTON_RATIO = 0.26f
        const val DPAD_CENTER_RATIO = 0.32f
        const val SKIP_S = 10.0
        const val ICONS_PER_ROW = 5

        /** The TV's home screen in front: nothing is playing, so no strip. */
        val HOME_SCREENS = AndroidTvApps.launchers

        /** States in which there's nothing to show in the now-playing strip. */
        val NOT_PLAYING_STATES = setOf("off", "unavailable", "unknown", "standby")

        val DEFAULT_APPS =
            listOf(
                mapOf("name" to "Netflix", "app" to "com.netflix.ninja"),
                mapOf("name" to "YouTube", "app" to "com.google.android.youtube.tv"),
                mapOf("name" to "Prime Video", "app" to "com.amazon.amazonvideo.livingroom"),
                mapOf("name" to "Plex", "app" to "com.plexapp.android")
            )
    }

    @Composable
    private fun DPad(
        r: TvRender,
        // The classic layout's wide oval; the full layout passes a circle sized to the screen.
        modifier: Modifier = Modifier.fillMaxWidth().height(180.dp),
        buttonSize: Dp = 52.dp,
        centerSize: Dp = 64.dp
    ) {
        val theme = r.ctx.theme
        val onUp = { r.send(r.c("up", "DPAD_UP")) }
        val onDown = { r.send(r.c("down", "DPAD_DOWN")) }
        val onLeft = { r.send(r.c("left", "DPAD_LEFT")) }
        val onRight = { r.send(r.c("right", "DPAD_RIGHT")) }
        val onCenter = { r.send(r.c("center", "DPAD_CENTER")) }
        val edge = (buttonSize / 4).coerceAtMost(12.dp)
        Box(
            modifier =
            modifier
                .clip(RoundedCornerShape(50))
                .background(theme.controlBackground),
            contentAlignment = Alignment.Center
        ) {
            // Up
            Box(Modifier.align(Alignment.TopCenter).padding(top = edge)) {
                RoundIconButton(Icons.Filled.KeyboardArrowUp, theme, size = buttonSize, onClick = onUp)
            }
            // Down
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = edge)) {
                RoundIconButton(Icons.Filled.KeyboardArrowDown, theme, size = buttonSize, onClick = onDown)
            }
            // Left
            Box(Modifier.align(Alignment.CenterStart).padding(start = edge)) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowLeft, theme, size = buttonSize, onClick = onLeft)
            }
            // Right
            Box(Modifier.align(Alignment.CenterEnd).padding(end = edge)) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowRight, theme, size = buttonSize, onClick = onRight)
            }
            // Center / OK
            Box(
                modifier =
                Modifier
                    .size(centerSize)
                    .clip(CircleShape)
                    .background(theme.controlBackground)
                    .tapClickable(focusShape = CircleShape, onClick = onCenter),
                contentAlignment = Alignment.Center
            ) {
                Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun RoundIconButton(
        icon: ImageVector,
        theme: ThemeColors,
        tint: Color = theme.iconTint,
        size: Dp = 52.dp,
        onClick: () -> Unit
    ) {
        Box(
            modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(theme.controlBackground)
                .tapClickable(focusShape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
    }
}
