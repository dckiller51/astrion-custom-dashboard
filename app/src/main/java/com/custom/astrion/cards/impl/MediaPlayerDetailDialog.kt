package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.ForwardHotkeys
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.icons.MdiIcons
import com.custom.astrion.ui.tapClickable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * Long-press detail popup for [MediaPlayerCard]'s compact tile (mirrors
 * [LightDetailDialog] / [CoverDetailDialog]'s long-press-for-detail pattern):
 * large album art, a live progress bar, the full transport row, a volume
 * slider with mute, and — only when the entity reports the feature — a
 * power toggle.
 *
 * Everything fires standard `media_player.*` services; buttons are the same
 * feature-gated set [MediaPlayerCard] uses for its own rows, just always
 * requesting every key so nothing is hidden here by a compact-card config.
 *
 * [library]: the card's `music_search` player (1.3.9, a "Search music" button next to Browse) and
 * the player whose queue a "Queue" button opens (1.4.0).
 *
 * [MediaLibraryPlayers.showVolume] false (1.5.0: the TV remote card's `show_volume: false`, a volume set elsewhere such
 * as an amplifier): no volume row. Power is only the round button at the bottom, not also first in
 * the transport row (it showed twice).
 */
/**
 * Under the title (1.5.0, players added in Astrion): a web radio's "● Live" (no length, so no bar)
 * and what plays as an audiophile reads it, "FLAC · 44.1 kHz · 16 bit · 913 kbps". Nothing when
 * the player says neither.
 */
@Composable
internal fun MediaQualityLine(e: EntityState?, theme: ThemeColors) {
    val live = e?.attrString("astrion_live") == "true" && e.attrDouble("media_duration") == null
    val quality = e?.attrString("astrion_quality")
    if (!live && quality == null) return
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (live) {
            Text("● " + stringResource(R.string.media_live), color = theme.accent, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        }
        quality?.let { Text(it, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
fun MediaPlayerDetailDialog(
    entityId: String,
    name: String,
    e: EntityState?,
    client: HaClient,
    theme: ThemeColors = ThemeColors.Default,
    library: MediaLibraryPlayers = MediaLibraryPlayers(),
    onClose: () -> Unit
) {
    val mediaButtons = remember(e) { computeMediaButtons(e, MediaPlayerCard.MEDIA_CONTROL_KEYS - "on_off") }
    val volumeButtons = remember(e) { computeVolumeButtons(e, listOf("mute")) }
    val hasVolumeSet = e?.supports(Feature.VOLUME_SET) == true
    val hasVolumeStep = e?.supports(Feature.VOLUME_STEP) == true
    val hasVolumeControl = hasVolumeSet || hasVolumeStep || volumeButtons.isNotEmpty()
    val volumeRow = library.showVolume && hasVolumeControl

    val title = e?.attrString("media_title") ?: name
    val subtitle =
        e?.attrString("media_artist")
            ?: e?.attrString("media_series_title")
            ?: e?.attrString("app_name")

    val artPath = e?.attrString("entity_picture")
    var art by remember(artPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artPath) { art = artPath?.let { client.fetchBitmap(it) } }

    fun mp(service: String, data: Array<out Pair<String, Any?>> = emptyArray()) {
        client.callService(ServiceCall.of("media_player", service, entityId, *data))
    }

    Dialog(onDismissRequest = onClose) {
        ForwardHotkeys() // the remote's buttons keep working here (1.4.9)
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .padding(20.dp)
                .width(300.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val artMod = Modifier.fillMaxWidth().aspectRatio(1.3f).clip(RoundedCornerShape(18.dp))
            if (art != null) {
                Image(art!!, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                val isOff = e == null || e.state == "off" || e.isUnavailable
                Box(artMod.background(theme.controlBackground), contentAlignment = Alignment.Center) {
                    Icon(
                        if (isOff) MdiIcons.CastOff else MdiIcons.Cast,
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(48.dp)
                    )
                }
            }

            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    title,
                    color = theme.primaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                Text(
                    subtitle ?: mediaStateLabel(e?.state),
                    color = theme.mutedText,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
                MediaQualityLine(e, theme)
            }

            DetailLibraryButtons(entityId, e, client, theme, library)

            if (e != null && e.attrDouble("media_duration") != null) {
                // A player that can seek (1.5.0): tap or drag the bar, as on the full card. It was
                // display-only here, so the TV remote card's now-playing pop-up couldn't seek.
                if (e.supports(Feature.SEEK)) {
                    AtvProgress(e, theme, thick = true) { seconds ->
                        client.callService(
                            ServiceCall.of("media_player", "media_seek", entityId, "seek_position" to seconds.coerceAtLeast(0.0))
                        )
                    }
                } else {
                    DialogProgressBar(e, theme)
                }
            }

            if (mediaButtons.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    mediaButtons.forEach { b ->
                        val big = b.action == "media_play" || b.action == "media_pause"
                        Circle(b.icon, if (big) 60.dp else 46.dp, theme, accent = big || b.active) {
                            mp(b.action, b.data.toTypedArray())
                        }
                    }
                }
            }

            if (volumeRow) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    volumeButtons.forEach { b -> Circle(b.icon, 40.dp, theme) { mp(b.action, b.data.toTypedArray()) } }
                    if (hasVolumeSet) {
                        DialogVolumeSlider(entityId, e, Modifier.weight(1f), theme) { level ->
                            mp("volume_set", arrayOf("volume_level" to level))
                        }
                    } else if (hasVolumeStep) {
                        Circle(MdiIcons.VolumeOff, 40.dp, theme) { mp("volume_down") }
                        Circle(MdiIcons.VolumeHigh, 40.dp, theme) { mp("volume_up") }
                    }
                }
            }

            if (e?.supports(Feature.TURN_ON) == true || e?.supports(Feature.TURN_OFF) == true) {
                // media_player has no generic "toggle" service (unlike light/switch) —
                // fire turn_on/turn_off explicitly based on the live state instead.
                val on = e.state != "off"
                Box(
                    modifier =
                    Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(if (on) theme.accentSecondary else theme.controlBackground)
                        .tapClickable(focusShape = CircleShape) { mp(if (on) "turn_off" else "turn_on") },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(MdiIcons.Power, contentDescription = stringResource(R.string.media_power_toggle), tint = Color.White)
                }
            }
        }
    }
}

/** The music players behind the detail dialog's library buttons: `music_search` (1.3.9) and the queue (1.4.0). */
data class MediaLibraryPlayers(
    val musicSearch: String? = null,
    val queue: String? = null,
    /** Another entity of the same device (1.5.0: the TV remote card's remote): its Zidoo library opens on Browse. */
    val browseAlso: String? = null,
    /** The volume row (1.5.0): false from a TV remote card with `show_volume: false`. */
    val showVolume: Boolean = true
)

/** Browse, Search music and Queue, each opening its own window. */
@Composable
private fun DetailLibraryButtons(entityId: String, e: EntityState?, client: HaClient, theme: ThemeColors, library: MediaLibraryPlayers) {
    var showBrowser by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    MediaLibraryButtons(
        theme,
        onBrowse = if (canBrowse(entityId, e, library.browseAlso)) ({ showBrowser = true }) else null,
        onSearch = library.musicSearch?.let { { showSearch = true } },
        onQueue = library.queue?.let { { showQueue = true } }
    )
    if (showBrowser) {
        BrowseDialog(entityId = entityId, client = client, theme = theme, also = library.browseAlso) { showBrowser = false }
    }
    library.musicSearch?.takeIf { showSearch }?.let { MusicSearchDialog(client, it, theme) { showSearch = false } }
    library.queue?.takeIf { showQueue }?.let { MediaQueueDialog(client, it, theme) { showQueue = false } }
}

@Composable
private fun DialogProgressBar(e: EntityState, theme: ThemeColors) {
    val duration = e.attrDouble("media_duration") ?: 0.0
    // See MediaPlayerCard.MediaProgressBar's identical comment — Kodi's
    // media_content_id is a nested JsonObject, not a plain string, so this
    // keys on the raw JsonElement's string form instead of attrString().
    val contentId = e.attr("media_content_id")?.toString()
    val positionBaseline = e.attrDouble("media_position")
    val positionUpdatedAt = e.attrString("media_position_updated_at")

    var elapsed by remember(e.entityId, contentId) {
        mutableDoubleStateOf(currentMediaPosition(e))
    }
    // Restarts on entity/track/state changes AND whenever the server
    // reports a fresh position baseline — without positionBaseline/
    // positionUpdatedAt in the key, a seek mid-track wouldn't restart this
    // coroutine (state and content_id are unchanged by a seek), so it kept
    // ticking from its stale captured baseline instead of tracking it.
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
                .background(theme.insetSurface)
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
private fun DialogVolumeSlider(entityId: String, e: EntityState?, modifier: Modifier, theme: ThemeColors, onCommit: (Float) -> Unit) {
    val level = (e?.attrDouble("volume_level") ?: 0.0).toFloat().coerceIn(0f, 1f)
    var dragLevel by remember(entityId) { mutableStateOf<Float?>(null) }
    val shown = dragLevel ?: level
    Box(
        modifier =
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.insetSurface)
            .pointerInput(entityId) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        dragLevel?.let(onCommit)
                        dragLevel = null
                    },
                    onDragCancel = { dragLevel = null }
                ) { change, _ -> dragLevel = (change.position.x / size.width).coerceIn(0f, 1f) }
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
                .clip(RoundedCornerShape(12.dp))
                .background(theme.accentSecondary)
        )
    }
}

@Composable
private fun Circle(icon: ImageVector, size: androidx.compose.ui.unit.Dp, theme: ThemeColors, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (accent) theme.accentSecondary else theme.controlBackground)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}
