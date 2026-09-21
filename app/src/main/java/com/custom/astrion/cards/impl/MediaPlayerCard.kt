package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.scale
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.icons.MdiIcons
import com.custom.astrion.ui.tapClickable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Media player card — styled after Home Assistant's Mushroom media-player
 * card for its "compact" layout, with two variants:
 *
 *  - "compact" (default): one Mushroom-style tile — round album-art/icon
 *    avatar, name + state line, and a control row below that can show either
 *    the transport buttons (prev/play-pause/next/shuffle/repeat/...) or the
 *    volume controls (mute/-/+ or a slider), with a small swap button to
 *    switch between the two — exactly how Mushroom's own media-player card
 *    behaves. Tap the tile to toggle play/pause; long-press to open
 *    [MediaPlayerDetailDialog] for the full transport, volume slider, and
 *    (when supported) power control.
 *  - "full": big album art, title/artist, a live progress bar, then the
 *    transport and volume rows — meant for a dedicated media page. Optional
 *    `top_buttons` fire arbitrary services (e.g. Group / Ungroup a speaker).
 *
 * Config:
 *   { "type": "media_player", "options": {
 *       "entity_id": "media_player.club",
 *       "variant": "full",                  // omit for compact
 *       "name": "Club",                      // optional override
 *       "use_media_info": true,              // show media_title/app instead of friendly_name/state
 *       "show_volume_level": false,          // append " ⸱ N%" to the state line
 *       "media_controls": "previous,play_pause,next",   // comma list, see MEDIA_CONTROL_KEYS
 *       "volume_controls": "mute,buttons",               // comma list, see VOLUME_CONTROL_KEYS
 *       "top_buttons": [ { "name": "Group", "service": "...", "entity_id": "...", "data": {} } ]
 *   } }
 *
 * All controls are filtered live against the entity's `supported_features`
 * bitmask, so an unsupported button (e.g. volume buttons on a group-only
 * speaker target) never renders even if requested in config.
 */
class MediaPlayerCard : CardRenderer {
    override val type = "media_player"

    @Suppress("UNCHECKED_CAST")
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val full = config.string("variant") == "full"
        val playerConfig = resolveMediaPlayerConfig(config)

        val e = ctx.entities[entityId]
        val isOff = e == null || e.state == "off" || e.isUnavailable
        val text = resolveMediaText(e, config, entityId, isOff, playerConfig.useMediaInfo, playerConfig.showVolumeLevel)

        val artPath = e?.attrString("entity_picture")
        var art by remember(artPath) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(artPath) { art = artPath?.let { ctx.client.fetchBitmap(it) } }

        val actions = remember(entityId, ctx.client) { MediaActions(entityId, ctx.client) }

        if (full) {
            MediaFullVariant(ctx, e, entityId, text, art, actions, playerConfig)
        } else {
            MediaCompactVariant(MediaCompactData(entityId, e, text, art, actions, playerConfig, ctx.theme, ctx.client))
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun resolveMediaPlayerConfig(config: CardConfig): MediaPlayerConfig {
        val topButtons = (config.options["top_buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val mediaControls =
            (config.string("media_controls") ?: DEFAULT_MEDIA_CONTROLS)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        val volumeControls =
            (config.string("volume_controls") ?: DEFAULT_VOLUME_CONTROLS)
                .split(",")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
        return MediaPlayerConfig(
            topButtons = topButtons,
            useMediaInfo = config.bool("use_media_info", true),
            showVolumeLevel = config.bool("show_volume_level", false),
            mediaControls = mediaControls,
            volumeControls = volumeControls,
            artworkFit = if (config.string("artwork_fit") == "contain") ContentScale.Fit else ContentScale.Crop,
            artworkAspectRatio = if (config.string("artwork_ratio") == "portrait") 2f / 3f else 1.2f
        )
    }

    @Composable
    private fun MediaFullVariant(
        ctx: CardContext,
        e: EntityState?,
        entityId: String,
        text: MediaText,
        art: ImageBitmap?,
        actions: MediaActions,
        playerConfig: MediaPlayerConfig
    ) {
        val blurredBg =
            remember(art) {
                art?.let { img ->
                    val src = img.asAndroidBitmap()
                    if (src.width <= 0) return@let null
                    val w = 32
                    val h = (w * src.height / src.width).coerceAtLeast(1)
                    src.scale(w, h, filter = true).asImageBitmap()
                }
            }
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
        ) {
            blurredBg?.let { bg ->
                Image(bg, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                Box(modifier = Modifier.matchParentSize().background(ctx.theme.background.copy(alpha = 0.7f)))
            }
            FullContent(
                MediaFullData(
                    ctx = ctx,
                    e = e,
                    entityId = entityId,
                    title = text.title,
                    artist = text.subtitle ?: text.finalState,
                    art = art,
                    mp = actions::fire,
                    playerConfig = playerConfig
                )
            )
        }
    }

    @Composable
    private fun MediaCompactVariant(data: MediaCompactData) {
        var showDetail by remember { mutableStateOf(false) }
        CompactTile(
            entityId = data.entityId,
            e = data.e,
            title = data.text.title,
            state = data.text.finalState,
            art = data.art,
            mediaControls = data.playerConfig.mediaControls,
            volumeControls = data.playerConfig.volumeControls,
            theme = data.theme,
            onTap = { data.actions.fire("media_play_pause") },
            onLongPress = { showDetail = true },
            mp = data.actions::fire
        )
        if (showDetail) {
            MediaPlayerDetailDialog(
                entityId = data.entityId,
                name = data.text.title,
                e = data.e,
                client = data.client,
                theme = data.theme,
                onClose = { showDetail = false }
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fireService(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }

    // ---- compact: Mushroom-style tile ---------------------------------------

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun CompactTile(
        entityId: String,
        e: EntityState?,
        title: String,
        state: String,
        art: ImageBitmap?,
        mediaControls: List<String>,
        volumeControls: List<String>,
        theme: ThemeColors,
        onTap: () -> Unit,
        onLongPress: () -> Unit,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit
    ) {
        val mediaButtons = remember(e, mediaControls) { computeMediaButtons(e, mediaControls) }
        val volumeButtons = remember(e, volumeControls) { computeVolumeButtons(e, volumeControls) }
        val hasVolumeSlider = volumeControls.contains("set") && e?.supports(Feature.VOLUME_SET) == true
        val hasVolumeGroup = volumeButtons.isNotEmpty() || hasVolumeSlider
        val hasMediaGroup = mediaButtons.isNotEmpty()

        // Which group is showing right now — Mushroom lets you flip between
        // them with a small swap button when both are available.
        var showVolume by remember(entityId) { mutableStateOf(!hasMediaGroup && hasVolumeGroup) }
        val activeIsVolume = showVolume && hasVolumeGroup

        // combinedClickable (not a raw pointerInput/detectTapGestures) so the
        // whole tile stays a normal focusable/clickable target: a bare
        // pointerInput never enters Compose's focus system, so a physical
        // remote's D-pad had nothing to land on here at all, and its
        // long-press never fired via a held key regardless — see LightCard's
        // identical fix. rememberLongPressKeyModifier separately covers a
        // held hardware key, which combinedClickable's onLongClick never
        // does on its own.
        val gestureModifier =
            rememberLongPressKeyModifier(entityId) { onLongPress() }
                .combinedClickable(
                    onClick = onTap,
                    onLongClick = onLongPress
                )

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(theme.cardSurface)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            CompactTileHeader(art, e, title, state, theme, Modifier.fillMaxWidth().then(gestureModifier))
            if (hasMediaGroup || hasVolumeGroup) {
                CompactTileControlsRow(
                    CompactTileGroups(entityId, e, mediaButtons, volumeButtons, hasVolumeSlider, theme, mp),
                    activeIsVolume
                ) { showVolume = !showVolume }
            }
        }
    }

    @Composable
    private fun CompactTileHeader(
        art: ImageBitmap?,
        e: EntityState?,
        title: String,
        state: String,
        theme: ThemeColors,
        modifier: Modifier
    ) {
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            val avatarMod = Modifier.size(42.dp).clip(CircleShape)
            if (art != null) {
                Image(art, null, modifier = avatarMod, contentScale = ContentScale.Crop)
            } else {
                val isOff = e == null || e.state == "off" || e.isUnavailable
                Box(
                    avatarMod.background(if (isOff) theme.controlBackground else theme.accentSecondary),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        if (isOff) MdiIcons.CastOff else MdiIcons.Cast,
                        contentDescription = null,
                        tint = if (isOff) theme.iconTint else Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = theme.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    state,
                    color = theme.mutedText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }

    @Composable
    private fun CompactTileControlsRow(groups: CompactTileGroups, activeIsVolume: Boolean, onToggleGroup: () -> Unit) {
        val hasVolumeGroup = groups.volumeButtons.isNotEmpty() || groups.hasVolumeSlider
        val hasMediaGroup = groups.mediaButtons.isNotEmpty()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (activeIsVolume) {
                if (groups.hasVolumeSlider) {
                    VolumeSlider(groups.entityId, groups.e, Modifier.weight(1f), groups.theme) { level ->
                        groups.mp("volume_set", arrayOf("volume_level" to level))
                    }
                }
                groups.volumeButtons.forEach { b ->
                    TileButton(
                        b.icon,
                        theme = groups.theme
                    ) { groups.mp(b.action, b.data.toTypedArray()) }
                }
            } else {
                groups.mediaButtons.forEach { b ->
                    TileButton(b.icon, theme = groups.theme, accent = b.active || b.action == "media_play" || b.action == "media_pause") {
                        groups.mp(b.action, b.data.toTypedArray())
                    }
                }
            }
            if (hasMediaGroup && hasVolumeGroup) {
                // The slider already has weight(1f) and fills the row on its
                // own — only insert a spacer to push the swap button to the
                // end when there's no slider doing that already (Modifier.weight
                // requires a value > 0, so this can't just be a conditional
                // weight(0f) on an always-present Spacer).
                if (!(activeIsVolume && groups.hasVolumeSlider)) {
                    Spacer(Modifier.weight(1f))
                }
                TileButton(if (activeIsVolume) MdiIcons.Play else MdiIcons.VolumeHigh, theme = groups.theme, onClick = onToggleGroup)
            }
        }
    }

    @Composable
    private fun TileButton(icon: ImageVector, theme: ThemeColors, accent: Boolean = false, onClick: () -> Unit) {
        Box(
            modifier =
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (accent) theme.accentSecondary else theme.controlBackground)
                .tapClickable(focusShape = RoundedCornerShape(10.dp), onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = theme.primaryText, modifier = Modifier.size(18.dp))
        }
    }

    @Composable
    private fun VolumeSlider(entityId: String, e: EntityState?, modifier: Modifier, theme: ThemeColors, onCommit: (Float) -> Unit) {
        val level = (e?.attrDouble("volume_level") ?: 0.0).toFloat().coerceIn(0f, 1f)
        var dragLevel by remember(entityId) { mutableStateOf<Float?>(null) }
        val shown = dragLevel ?: level
        Box(
            modifier =
            modifier
                .height(36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(theme.insetSurface)
                .pointerInput(entityId) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            dragLevel?.let(onCommit)
                            dragLevel = null
                        },
                        onDragCancel = { dragLevel = null }
                    ) { change, _ ->
                        change.consume()
                        dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                }.pointerInput(entityId) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        dragLevel = f
                        onCommit(f)
                        dragLevel = null
                    }
                }
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown.coerceIn(0.02f, 1f))
                    .clip(RoundedCornerShape(10.dp))
                    .background(theme.accentSecondary)
            )
        }
    }

    // ---- full (media page) --------------------------------------------------

    /** Everything FullContent needs, bundled so the function stays under
     * detekt's parameter-count limit (same idea as CompactTileGroups below). */
    private data class MediaFullData(
        val ctx: CardContext,
        val e: EntityState?,
        val entityId: String,
        val title: String,
        val artist: String,
        val art: ImageBitmap?,
        val mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        val playerConfig: MediaPlayerConfig
    )

    @Composable
    private fun FullContent(data: MediaFullData) {
        val ctx = data.ctx
        val e = data.e
        val playerConfig = data.playerConfig
        val mediaButtons =
            remember(e, playerConfig.mediaControls) { computeMediaButtons(e, playerConfig.mediaControls) }
        val volumeButtons =
            remember(e, playerConfig.volumeControls) { computeVolumeButtons(e, playerConfig.volumeControls) }
        val hasVolumeSlider = playerConfig.volumeControls.contains("set") && e?.supports(Feature.VOLUME_SET) == true

        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            FullTopButtons(ctx, playerConfig.topButtons)
            FullArtwork(ctx, e, data.art, playerConfig)
            FullTitleArtist(ctx.theme, data.title, data.artist)
            if (e?.attrDouble("media_duration") != null) {
                MediaProgressBar(e, ctx.theme)
            }
            FullMediaButtonsRow(ctx.theme, mediaButtons, data.mp)
            FullVolumeRow(FullVolumeRowData(data.entityId, e, data.mp, volumeButtons, hasVolumeSlider, ctx.theme))
        }
    }

    /** The optional row of full-width service-call buttons above the artwork
     * (e.g. speaker grouping) — split out of [FullContent] purely to keep
     * that function's complexity/parameter-count under detekt's thresholds. */
    @Composable
    private fun FullTopButtons(ctx: CardContext, topButtons: List<Map<String, Any?>>) {
        if (topButtons.isEmpty()) return
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            topButtons.forEach { b ->
                Box(
                    modifier =
                    Modifier
                        .weight(1f)
                        .height(44.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(ctx.theme.controlBackground.copy(alpha = 0.4f))
                        .tapClickable { fireService(ctx, b) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        b["name"] as? String ?: "",
                        color = ctx.theme.primaryText,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }
    }

    /** The artwork tile itself, or the cast-icon placeholder when there's no
     * art. See [FullTopButtons]'s doc comment for why this is split out. */
    @Composable
    private fun FullArtwork(ctx: CardContext, e: EntityState?, art: ImageBitmap?, playerConfig: MediaPlayerConfig) {
        val artMod =
            Modifier.fillMaxWidth().aspectRatio(playerConfig.artworkAspectRatio).clip(RoundedCornerShape(16.dp))
        if (art != null) {
            Image(art, null, modifier = artMod, contentScale = playerConfig.artworkFit)
        } else {
            val isOff = e == null || e.state == "off" || e.isUnavailable
            Box(
                artMod.background(if (isOff) ctx.theme.controlBackground else ctx.theme.accentSecondary),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isOff) MdiIcons.CastOff else MdiIcons.Cast,
                    contentDescription = null,
                    tint = if (isOff) Color.White.copy(alpha = 0.6f) else Color.White,
                    modifier = Modifier.size(48.dp)
                )
            }
        }
    }

    /** Title + artist/subtitle block. See [FullTopButtons]'s doc comment. */
    @Composable
    private fun FullTitleArtist(theme: ThemeColors, title: String, artist: String) {
        Column(modifier = Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                title,
                color = theme.primaryText,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
            Text(
                artist,
                color = theme.mutedText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }

    /** Play/pause/skip row. See [FullTopButtons]'s doc comment. */
    @Composable
    private fun FullMediaButtonsRow(theme: ThemeColors, mediaButtons: List<MpButton>, mp: (String, Array<out Pair<String, Any?>>) -> Unit) {
        if (mediaButtons.isEmpty()) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically
        ) {
            mediaButtons.forEach { b ->
                val big = b.action == "media_play" || b.action == "media_pause"
                FullCircleControl(b.icon, if (big) 64.dp else 50.dp, theme, accent = big || b.active) {
                    mp(b.action, b.data.toTypedArray())
                }
            }
        }
    }

    /** Everything [FullVolumeRow] needs — see [FullTopButtons]'s doc comment. */
    private data class FullVolumeRowData(
        val entityId: String,
        val e: EntityState?,
        val mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        val volumeButtons: List<MpButton>,
        val hasVolumeSlider: Boolean,
        val theme: ThemeColors
    )

    /** Volume slider + mute/up/down row. See [FullTopButtons]'s doc comment. */
    @Composable
    private fun FullVolumeRow(data: FullVolumeRowData) {
        if (data.volumeButtons.isEmpty() && !data.hasVolumeSlider) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (data.hasVolumeSlider) {
                VolumeSlider(data.entityId, data.e, Modifier.weight(1f).height(44.dp), data.theme) { level ->
                    data.mp("volume_set", arrayOf("volume_level" to level))
                }
            }
            data.volumeButtons.forEach { b ->
                FullCircleControl(b.icon, 44.dp, data.theme) { data.mp(b.action, b.data.toTypedArray()) }
            }
        }
    }

    @Composable
    private fun MediaProgressBar(e: EntityState, theme: ThemeColors) {
        val duration = e.attrDouble("media_duration") ?: 0.0
        // Keyed on the raw JsonElement's string form, not attrString() —
        // Kodi reports media_content_id as a nested JsonObject
        // ({"imdb":"...", "tmdb":"..."}) rather than a plain string like
        // most other media players, so attrString() would always see null
        // for it and this key would never change between tracks. toString()
        // works for both shapes and still changes when the track does.
        val contentId = e.attr("media_content_id")?.toString()
        val positionBaseline = e.attrDouble("media_position")
        val positionUpdatedAt = e.attrString("media_position_updated_at")

        var elapsed by remember(e.entityId, contentId) {
            mutableDoubleStateOf(currentMediaPosition(e))
        }
        // Restarts on entity/track/state changes AND whenever the server
        // reports a fresh position baseline (positionBaseline/positionUpdatedAt)
        // — without those two in the key, a seek mid-track wouldn't restart
        // this coroutine (state and content_id are unchanged by a seek), so
        // it kept ticking from its stale captured baseline forever, drifting
        // away from the real position instead of tracking it.
        LaunchedEffect(e.entityId, e.state, contentId, positionBaseline, positionUpdatedAt) {
            elapsed = currentMediaPosition(e)
            while (e.state == "playing") {
                delay(1.seconds)
                elapsed = currentMediaPosition(e)
            }
        }
        val fraction = if (duration > 0) (elapsed / duration).toFloat().coerceIn(0f, 1f) else 0f
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.2f))
            ) {
                Box(
                    Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(fraction.coerceAtLeast(0.01f))
                        .clip(RoundedCornerShape(2.dp))
                        .background(theme.accentSecondary)
                )
            }
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatMediaTime(elapsed), color = theme.mutedText, fontSize = 11.sp)
                Text(formatMediaTime(duration), color = theme.mutedText, fontSize = 11.sp)
            }
        }
    }

    @Composable
    private fun FullCircleControl(icon: ImageVector, size: Dp, theme: ThemeColors, accent: Boolean = false, onClick: () -> Unit) {
        Box(
            modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (accent) theme.accentSecondary else theme.controlBackground.copy(alpha = 0.33f))
                .tapClickable(focusShape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
        }
    }

    companion object {
        const val DEFAULT_MEDIA_CONTROLS = "previous,play_pause,next"
        const val DEFAULT_VOLUME_CONTROLS = "mute,buttons"

        /** All keys accepted by the `media_controls` option, in Mushroom's own display order. */
        val MEDIA_CONTROL_KEYS = listOf("on_off", "shuffle", "previous", "play_pause", "next", "repeat")

        /** All keys accepted by the `volume_controls` option. */
        val VOLUME_CONTROL_KEYS = listOf("mute", "buttons", "set")
    }
}

// ---- pulled out of Render()/CompactTile() to keep them under detekt's length/complexity limits ----

private data class MediaPlayerConfig(
    val topButtons: List<Map<String, Any?>>,
    val useMediaInfo: Boolean,
    val showVolumeLevel: Boolean,
    val mediaControls: List<String>,
    val volumeControls: List<String>,
    /** "cover" (default, unchanged) crops to fill the tile — right for a
     * square album cover, but chops the top/bottom off a portrait movie
     * poster. "contain" fits the whole image instead, letterboxing rather
     * than cropping. See `artwork_ratio` for changing the tile's own shape
     * (e.g. to a portrait poster ratio) instead of just the fit mode. */
    val artworkFit: ContentScale = ContentScale.Crop,
    /** Aspect ratio (width / height) of the artwork area in [MediaFullVariant].
     * Defaults to 1.2 (the original near-square shape). `"portrait"` in the
     * config switches this to 2/3, a closer fit for movie/TV posters. */
    val artworkAspectRatio: Float = 1.2f
)

private data class MediaText(val title: String, val subtitle: String?, val finalState: String)

/** Everything MediaCompactVariant needs, bundled so the function stays under detekt's parameter-count limit. */
private data class MediaCompactData(
    val entityId: String,
    val e: EntityState?,
    val text: MediaText,
    val art: ImageBitmap?,
    val actions: MediaActions,
    val playerConfig: MediaPlayerConfig,
    val theme: ThemeColors,
    val client: HaClient
)

/** Fires media_player.* services — pulled out of Render() to keep it short. */
private class MediaActions(private val entityId: String, private val client: HaClient) {
    fun fire(service: String, vararg data: Pair<String, Any?>) {
        client.callService(ServiceCall.of("media_player", service, entityId, *data))
    }
}

/** Everything CompactTileControlsRow needs, bundled so the function stays under detekt's parameter-count limit. */
private data class CompactTileGroups(
    val entityId: String,
    val e: EntityState?,
    val mediaButtons: List<MpButton>,
    val volumeButtons: List<MpButton>,
    val hasVolumeSlider: Boolean,
    val theme: ThemeColors,
    val mp: (String, Array<out Pair<String, Any?>>) -> Unit
)

private fun resolveMediaTitle(e: EntityState?, config: CardConfig, entityId: String, useMediaInfo: Boolean, isOff: Boolean): String =
    if (useMediaInfo && !isOff) {
        e?.attrString("media_title") ?: config.string("name") ?: e?.friendlyName ?: entityId
    } else {
        config.string("name") ?: e?.friendlyName ?: entityId
    }

private fun resolveMediaSubtitle(e: EntityState?, useMediaInfo: Boolean, isOff: Boolean): String? = if (useMediaInfo && !isOff) {
    e?.attrString("media_artist") ?: e?.attrString("media_series_title") ?: e?.attrString("app_name")
} else {
    null
}

@Composable
private fun resolveMediaText(
    e: EntityState?,
    config: CardConfig,
    entityId: String,
    isOff: Boolean,
    useMediaInfo: Boolean,
    showVolumeLevel: Boolean
): MediaText {
    val title = resolveMediaTitle(e, config, entityId, useMediaInfo, isOff)
    val subtitle = resolveMediaSubtitle(e, useMediaInfo, isOff)
    val stateLabel = subtitle ?: mediaStateLabel(e?.state)
    val finalState =
        if (showVolumeLevel && e?.attrDouble("volume_level") != null) {
            val pct = ((e.attrDouble("volume_level") ?: 0.0) * 100).toInt()
            "$stateLabel ⸱ $pct%"
        } else {
            stateLabel
        }
    return MediaText(title = title, subtitle = subtitle, finalState = finalState)
}

// ---- shared feature/state helpers, also used by MediaPlayerDetailDialog ---

internal object Feature {
    const val PAUSE = 1
    const val SEEK = 2
    const val VOLUME_SET = 4
    const val VOLUME_MUTE = 8
    const val PREVIOUS_TRACK = 16
    const val NEXT_TRACK = 32
    const val TURN_ON = 128
    const val TURN_OFF = 256
    const val VOLUME_STEP = 1024
    const val STOP = 4096
    const val PLAY = 16384
    const val SHUFFLE_SET = 32768

    // Home Assistant's media_player.const.SUPPORT_BROWSE_MEDIA — gates the
    // "Browse" button in MediaPlayerDetailDialog that opens MediaBrowser.
    const val BROWSE_MEDIA = 131072
    const val REPEAT_SET = 262144
}

internal fun EntityState.supports(bit: Int): Boolean {
    val features = attrInt("supported_features") ?: 0
    return (features and bit) == bit
}

internal data class MpButton(
    val icon: ImageVector,
    val action: String,
    val data: List<Pair<String, Any?>> = emptyList(),
    /** True when this button reflects an already-active toggle (shuffle on,
     *  repeat != off) — [MediaPlayerCard] tints these buttons instead of
     *  swapping their icon, since only one glyph exists for each. */
    val active: Boolean = false
)

/** Live playback position in seconds, accounting for time elapsed since `media_position_updated_at`. */
internal fun currentMediaPosition(e: EntityState): Double {
    val pos = e.attrDouble("media_position") ?: return 0.0
    if (e.state != "playing") return pos
    val updatedAt = e.attrString("media_position_updated_at") ?: return pos
    return try {
        // Instant.parse() only accepts a literal "Z" for UTC (strict
        // ISO_INSTANT) — Home Assistant reports a numeric offset instead
        // ("...+00:00"), which Instant.parse() rejects outright. That
        // exception was silently swallowed by the catch below, so this
        // always fell back to the raw static `pos` and never advanced —
        // the "position only changes on pause" symptom. OffsetDateTime
        // accepts both "+00:00" and "Z".
        val then = java.time.OffsetDateTime.parse(updatedAt).toInstant()
        val elapsedSince =
            java.time.Duration
                .between(then, java.time.Instant.now())
                .toMillis() / 1000.0
        (pos + elapsedSince.coerceAtLeast(0.0))
    } catch (_: Exception) {
        pos
    }
}

internal fun formatMediaTime(seconds: Double): String {
    if (seconds.isNaN() || seconds.isInfinite() || seconds < 0) return "0:00"
    val total = seconds.toInt()
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/**
 * Translated state label (assets/ha_labels/<lang>.json convention used
 * elsewhere would be overkill for 6 fixed values — these go through
 * strings.xml/values-fr like [LightCard]'s state strings instead).
 */
@Composable
internal fun mediaStateLabel(state: String?): String = when (state) {
    "playing" -> stringResource(R.string.media_state_playing)
    "paused" -> stringResource(R.string.media_state_paused)
    "idle" -> stringResource(R.string.media_state_idle)
    "buffering" -> stringResource(R.string.media_state_buffering)
    "on" -> stringResource(R.string.media_state_on)
    "off", null -> stringResource(R.string.media_state_off)
    else -> state.replaceFirstChar { it.uppercase() }
}

internal fun computeMediaButtons(e: EntityState?, controls: List<String>): List<MpButton> {
    if (e == null) return emptyList()
    val state = e.state
    val out = mutableListOf<MpButton>()

    if (state == "off") {
        if ("on_off" in controls && e.supports(Feature.TURN_ON)) out += MpButton(MdiIcons.Power, "turn_on")
        return out
    }

    if ("on_off" in controls && e.supports(Feature.TURN_OFF)) out += MpButton(MdiIcons.Power, "turn_off")

    val isActiveState = state == "playing" || state == "paused" || state == "idle" || state == "on"

    if (isActiveState && "shuffle" in controls && e.supports(Feature.SHUFFLE_SET)) {
        val shuffleOn = e.attrBoolean("shuffle") == true
        out += MpButton(MdiIcons.Shuffle, "shuffle_set", listOf("shuffle" to !shuffleOn), active = shuffleOn)
    }

    if (isActiveState && "previous" in controls && e.supports(Feature.PREVIOUS_TRACK)) {
        out += MpButton(MdiIcons.SkipPrevious, "media_previous_track")
    }

    if ("play_pause" in controls) {
        when {
            state == "playing" && e.supports(Feature.PAUSE) -> out += MpButton(MdiIcons.Pause, "media_pause")
            state == "playing" && e.supports(Feature.STOP) -> out += MpButton(MdiIcons.Pause, "media_stop")
            (state == "paused" || state == "idle" || state == "on") && e.supports(Feature.PLAY) ->
                out += MpButton(MdiIcons.Play, "media_play")
        }
    }

    if (isActiveState && "next" in controls && e.supports(Feature.NEXT_TRACK)) {
        out += MpButton(MdiIcons.SkipNext, "media_next_track")
    }

    if (isActiveState && "repeat" in controls && e.supports(Feature.REPEAT_SET)) {
        val current = e.attrString("repeat") ?: "off"
        val next =
            when (current) {
                "off" -> "all"
                "all" -> "one"
                else -> "off"
            }
        out += MpButton(MdiIcons.Repeat, "repeat_set", listOf("repeat" to next), active = current != "off")
    }

    return out
}

internal fun computeVolumeButtons(e: EntityState?, controls: List<String>): List<MpButton> {
    if (e == null || e.isUnavailable || e.state == "off") return emptyList()
    val out = mutableListOf<MpButton>()
    if ("mute" in controls && e.supports(Feature.VOLUME_MUTE)) {
        val muted = e.attrBoolean("is_volume_muted") == true
        out += MpButton(if (muted) MdiIcons.VolumeOff else MdiIcons.VolumeHigh, "volume_mute", listOf("is_volume_muted" to !muted))
    }
    if ("buttons" in controls && e.supports(Feature.VOLUME_STEP)) {
        out += MpButton(MdiIcons.VolumeOff, "volume_down")
        out += MpButton(MdiIcons.VolumeHigh, "volume_up")
    }
    return out
}
