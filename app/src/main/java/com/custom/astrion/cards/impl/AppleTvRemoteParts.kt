package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.decodeIconSampled
import com.custom.astrion.ui.tapClickable
import com.custom.astrion.ui.tapCombinedClickable
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/*
 * Building blocks shared by AppleTvRemoteCard's three layouts (classic, compact, full). Kept in
 * their own file so the card itself stays a readable description of each layout.
 */

/** One app shortcut from the card's `apps` option. [source] is what `select_source` receives. */
internal data class AtvApp(val source: String, val label: String, val icon: String?, val color: Color?) {
    companion object {
        /**
         * Parses `apps: [{ "source": "Netflix", "label": "...", "icon": "...", "color": "#..." }]`;
         * entries without a source are skipped.
         */
        fun listFrom(raw: Any?): List<AtvApp> = (raw as? List<*>).orEmpty().mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val source = (m["source"] as? String)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            AtvApp(
                source = source,
                label = (m["label"] as? String)?.takeIf { it.isNotBlank() } ?: source,
                icon = (m["icon"] as? String)?.takeIf { it.isNotBlank() },
                color = parseHexColor(m["color"] as? String)
            )
        }
    }
}

// Fallback tile colors for an app without an icon or its own color: dark enough for white initials.
private val APP_FALLBACK_COLORS =
    listOf(
        Color(0xFFB3261E),
        Color(0xFF1F5FBF),
        Color(0xFF3A3A40),
        Color(0xFFB86E00),
        Color(0xFF1E7A4A),
        Color(0xFF5B3FA8)
    )

private fun initialsOf(label: String): String {
    val words = label.split(' ', '+', '-', '.').filter { it.isNotBlank() }
    return when {
        words.size >= 2 -> "${words[0].first()}${words[1].first()}"
        label.length >= 2 -> label.take(2)
        else -> label
    }.uppercase()
}

/**
 * Loads an icon/artwork path: `http…` or a Home Assistant `/api/…` path through
 * [CardContext.client], anything else as a local file.
 */
@Composable
private fun rememberImage(ctx: CardContext, path: String?, targetPx: Int): ImageBitmap? {
    val image by produceState<ImageBitmap?>(initialValue = null, path, targetPx) {
        value =
            when {
                path == null -> null
                path.startsWith("http") || path.startsWith("/api/") -> runCatching { ctx.client.fetchBitmap(path) }.getOrNull()
                else -> withContext(Dispatchers.IO) { decodeIconSampled(path, targetPx) }
            }
    }
    return image
}

/** The current item's cover art, or a TV glyph on an inset tile while there is none. */
@Composable
internal fun AtvArtwork(ctx: CardContext, entity: EntityState?, size: Dp, corner: Dp) {
    val path = entity?.attrString("entity_picture_local") ?: entity?.attrString("entity_picture")
    val px = with(LocalDensity.current) { size.toPx() }.toInt()
    val art = rememberImage(ctx, path, px)
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(RoundedCornerShape(corner))
            .background(ctx.theme.insetSurface),
        contentAlignment = Alignment.Center
    ) {
        if (art != null) {
            Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = ctx.theme.mutedText, modifier = Modifier.size(size * 0.4f))
        }
    }
}

/** Title / subtitle / app name pulled from a `media_player` entity, the way the card shows them. */
internal data class AtvNowPlaying(val title: String, val subtitle: String?, val app: String?) {
    companion object {
        fun of(e: EntityState): AtvNowPlaying {
            val app = e.attrString("app_name")
            val mediaTitle = e.attrString("media_title")?.takeIf { it.isNotBlank() }
            val series = e.attrString("media_series_title")?.takeIf { it.isNotBlank() }
            val season = e.attrInt("media_season")
            val episode = e.attrInt("media_episode")
            val episodeTag = if (season != null && episode != null) "S$season · E$episode" else null
            val subtitle =
                when {
                    series != null -> listOfNotNull(series, episodeTag).joinToString(" · ")
                    else -> e.attrString("media_artist")?.takeIf { it.isNotBlank() }
                }
            val title = mediaTitle ?: app ?: e.friendlyName.ifBlank { e.entityId }
            return AtvNowPlaying(title, if (mediaTitle != null) subtitle else null, if (mediaTitle != null) app else null)
        }
    }
}

/**
 * Playback progress of [e]. With [onSeek] set the bar can be tapped or dragged, and calls it
 * with the target position in seconds on release. Renders nothing while the item has no length.
 */
@Composable
internal fun AtvProgress(e: EntityState, theme: ThemeColors, thick: Boolean, onSeek: ((Double) -> Unit)?) {
    val duration = e.attrDouble("media_duration") ?: 0.0
    if (duration <= 0.0) return
    val baseline = e.attrDouble("media_position")
    val baselineAt = e.attrString("media_position_updated_at")
    var elapsed by remember(e.entityId) { mutableDoubleStateOf(currentMediaPosition(e)) }
    LaunchedEffect(e.entityId, e.state, baseline, baselineAt) {
        elapsed = currentMediaPosition(e)
        while (e.state == "playing") {
            delay(1.seconds)
            elapsed = currentMediaPosition(e)
        }
    }
    // While dragging, the bar follows the finger; once released it holds the chosen spot until the
    // Apple TV reports a fresh position (or a few seconds pass), instead of snapping back.
    var dragFraction by remember { mutableFloatStateOf(-1f) }
    var pendingFraction by remember { mutableFloatStateOf(-1f) }
    LaunchedEffect(baseline, baselineAt) { pendingFraction = -1f }
    LaunchedEffect(pendingFraction) {
        if (pendingFraction >= 0f) {
            delay(SEEK_PENDING_MS)
            pendingFraction = -1f
        }
    }
    val live = (elapsed / duration).toFloat().coerceIn(0f, 1f)
    val fraction =
        when {
            dragFraction >= 0f -> dragFraction
            pendingFraction >= 0f -> pendingFraction
            else -> live
        }
    val commit: (Float) -> Unit = { f ->
        pendingFraction = f
        onSeek?.invoke(duration * f)
    }
    val gestures =
        if (onSeek == null) {
            Modifier
        } else {
            Modifier
                .pointerInput(duration) {
                    detectTapGestures { offset -> commit((offset.x / size.width).coerceIn(0f, 1f)) }
                }.pointerInput(duration) {
                    detectHorizontalDragGestures(
                        onDragStart = { offset -> dragFraction = (offset.x / size.width).coerceIn(0f, 1f) },
                        onDragEnd = {
                            if (dragFraction >= 0f) commit(dragFraction)
                            dragFraction = -1f
                        },
                        onDragCancel = { dragFraction = -1f },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    )
                }
        }
    if (!thick) {
        ProgressTrack(fraction, theme, thick = false, gestures = gestures, modifier = Modifier.fillMaxWidth())
        return
    }
    // Times on either side of the bar, on one line, to keep the full layout short.
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(formatMediaTime(duration * fraction), color = theme.mutedText, fontSize = 12.sp)
        ProgressTrack(fraction, theme, thick = true, gestures = gestures, modifier = Modifier.weight(1f))
        Text("-" + formatMediaTime(duration * (1f - fraction)), color = theme.mutedText, fontSize = 12.sp)
    }
}

private const val SEEK_PENDING_MS = 4000L

@Composable
private fun ProgressTrack(fraction: Float, theme: ThemeColors, thick: Boolean, gestures: Modifier, modifier: Modifier) {
    val barHeight = if (thick) 6.dp else 3.dp
    val track = theme.controlBackground
    val fill = theme.accent
    val knob = theme.primaryText
    // Drawn in one pass (no child layouts): the track, the elapsed part, and on a thick (seekable)
    // bar a round thumb. A thick bar also gets a taller invisible hit area — 6 dp is too thin to grab.
    Box(
        modifier =
        modifier
            .height(if (thick) 28.dp else barHeight)
            .then(gestures)
            .drawBehind {
                val bar = barHeight.toPx()
                val top = (size.height - bar) / 2f
                val corner = CornerRadius(bar / 2f)
                drawRoundRect(track, topLeft = Offset(0f, top), size = Size(size.width, bar), cornerRadius = corner)
                val filled = size.width * fraction.coerceIn(0.01f, 1f)
                drawRoundRect(fill, topLeft = Offset(0f, top), size = Size(filled, bar), cornerRadius = corner)
                if (thick) {
                    val radius = THUMB_RADIUS.dp.toPx()
                    val x = radius + (size.width - 2 * radius) * fraction
                    drawCircle(knob, radius = radius, center = Offset(x, size.height / 2f))
                }
            }
    )
}

private const val THUMB_RADIUS = 8

/** A round icon (or short text) button, optionally with a caption underneath. */
@Composable
internal fun AtvRoundButton(
    icon: ImageVector?,
    label: String,
    size: Dp,
    theme: ThemeColors,
    showLabel: Boolean = false,
    filled: Boolean = false,
    onClick: () -> Unit
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (filled) theme.primaryText else theme.controlBackground)
                .tapClickable(focusShape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            val tint = if (filled) theme.cardSurface else theme.primaryText
            if (icon != null) {
                Icon(icon, contentDescription = label, tint = tint, modifier = Modifier.size(size * 0.48f))
            } else {
                Text(label.take(3), color = tint, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        if (showLabel) {
            Text(label, color = theme.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Long-press actions on the trackpad; any left null keeps that zone tap-only. */
internal class TrackpadHolds(
    val onSelect: (() -> Unit)? = null,
    val onLeft: (() -> Unit)? = null,
    val onRight: (() -> Unit)? = null
)

/** Circular trackpad: four edge arrows plus the centre Select, each optionally long-pressable. */
@Composable
internal fun AtvTrackpad(size: Dp, theme: ThemeColors, send: (String) -> Unit, holds: TrackpadHolds) {
    Box(
        modifier =
        Modifier
            .size(size)
            .clip(CircleShape)
            .background(theme.controlBackground)
            .tapCombinedClickable(CircleShape, onLongClick = holds.onSelect) { send("Select") },
        contentAlignment = Alignment.Center
    ) {
        EdgeIcon(Icons.Filled.KeyboardArrowUp, Alignment.TopCenter, theme, null) { send("DirectionUp") }
        EdgeIcon(Icons.Filled.KeyboardArrowDown, Alignment.BottomCenter, theme, null) { send("DirectionDown") }
        EdgeIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Alignment.CenterStart, theme, holds.onLeft) { send("DirectionLeft") }
        EdgeIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, Alignment.CenterEnd, theme, holds.onRight) { send("DirectionRight") }
        Box(
            modifier =
            Modifier
                .size(size * 0.3f)
                .clip(CircleShape)
                .background(theme.controlBackground)
        )
    }
}

@Composable
private fun EdgeIcon(icon: ImageVector, align: Alignment, theme: ThemeColors, onLongClick: (() -> Unit)?, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .padding(14.dp),
        contentAlignment = align
    ) {
        Box(
            modifier =
            Modifier
                .size(40.dp)
                .clip(CircleShape)
                .tapCombinedClickable(CircleShape, onLongClick = onLongClick, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = theme.mutedText)
        }
    }
}

/** One app shortcut: its icon (file path or URL), or its initials on a colored tile. */
@Composable
internal fun AtvAppTile(ctx: CardContext, app: AtvApp, index: Int, size: Dp, labelWidth: Dp?, onClick: () -> Unit) {
    val showLabel = labelWidth != null
    val px = with(LocalDensity.current) { size.toPx() }.toInt()
    val icon = rememberImage(ctx, app.icon, px)
    val shape = RoundedCornerShape(size * 0.26f)
    Column(
        modifier = if (labelWidth != null) Modifier.width(labelWidth) else Modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Box(
            modifier =
            Modifier
                .size(size)
                .clip(shape)
                .background(app.color ?: if (icon != null) Color.Transparent else APP_FALLBACK_COLORS[index % APP_FALLBACK_COLORS.size])
                .tapClickable(focusShape = shape, onClickLabel = app.label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (icon != null) {
                Image(icon, contentDescription = app.label, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(initialsOf(app.label), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            }
        }
        if (showLabel) {
            Text(
                app.label,
                color = ctx.theme.mutedText,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}

/**
 * A centred row of app shortcuts, never scrolled (a sideways scroll fights the page swipe). Each
 * tile is [tile] wide at most, and shrinks so every app fits [rowWidth] — the card's inner width,
 * see [cardInnerWidthDp].
 */
@Composable
internal fun AtvAppsRow(ctx: CardContext, apps: List<AtvApp>, tile: Dp, labels: Boolean, rowWidth: Dp, launch: (AtvApp) -> Unit) {
    val gap = if (labels) 4.dp else 10.dp
    val count = apps.size.coerceAtLeast(1)
    val slot = (rowWidth - gap * (count - 1)) / count
    val size = minOf(tile, if (labels) slot - 6.dp else slot)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(gap, Alignment.CenterHorizontally)
    ) {
        apps.forEachIndexed { i, app ->
            AtvAppTile(ctx, app, i, size, labelWidth = if (labels) minOf(slot, size + 18.dp) else null) { launch(app) }
        }
    }
}

/** Width (dp) inside a full-width card with [cardPadding] dp of padding: screen minus page and card padding. */
@Composable
internal fun cardInnerWidthDp(cardPadding: Int): Int = LocalConfiguration.current.screenWidthDp - 2 * 10 - 2 * cardPadding

/** Remembers a local play/pause guess for a target that reports no state of its own (Harmony). */
@Composable
internal fun rememberLocalPlaying(key: Any?) = remember(key) { mutableStateOf(true) }
