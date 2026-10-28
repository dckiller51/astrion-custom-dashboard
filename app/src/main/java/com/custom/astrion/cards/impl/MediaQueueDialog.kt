package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay

/**
 * The queue (1.4.0), opened from the media player card's **Queue** button. Lyrion Music Server:
 * the current track and the next ones; tap one to play it, ✕ to remove it. Music Assistant: the
 * current and next track (tap the next one to skip to it). Every player that can: **Clear**,
 * tapped twice. The list follows the player: it reloads when the track changes.
 */
@Composable
internal fun MediaQueueDialog(client: HaClient, player: String, theme: ThemeColors, onClose: () -> Unit) {
    val entities by client.entities.collectAsState()
    val e = entities[player]
    val trackKey = "${e?.attrString("media_content_id")}|${e?.attrString("media_title")}"
    val canClear = MediaQueue.canClear(e)
    var answer by remember { mutableStateOf<MediaQueue.Answer?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var confirmClear by remember { mutableStateOf(false) }

    LaunchedEffect(trackKey, reload) {
        if (reload > 0) delay(AFTER_ACTION_MS.milliseconds)
        answer = MediaQueue.load(client, player)
    }
    LaunchedEffect(confirmClear) {
        if (confirmClear) {
            delay(CONFIRM_WINDOW)
            confirmClear = false
        }
    }
    val actions =
        QueueActions(
            play = { index ->
                MediaQueue.playIndex(client, player, index)
                reload++
            },
            remove = { index ->
                MediaQueue.remove(client, player, index)
                reload++
            },
            skip = {
                MediaQueue.next(client, player)
                reload++
            }
        )

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .width(340.dp)
                .heightIn(max = 560.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            QueueHeader(answer, theme, onRefresh = { reload++ }, onClose = onClose)
            QueueBody(client, answer, theme, actions, Modifier.weight(1f, fill = false))
            if (canClear) {
                ClearButton(confirmClear, theme) {
                    if (confirmClear) {
                        MediaQueue.clear(client, player)
                        confirmClear = false
                        reload++
                    } else {
                        confirmClear = true
                    }
                }
            }
        }
    }
}

/** What the rows do: [play] a Lyrion track, [remove] it, [skip] to Music Assistant's next one. */
private class QueueActions(val play: (Int) -> Unit, val remove: (Int) -> Unit, val skip: () -> Unit)

@Composable
private fun QueueHeader(answer: MediaQueue.Answer?, theme: ThemeColors, onRefresh: () -> Unit, onClose: () -> Unit) {
    val queue = (answer as? MediaQueue.Answer.Loaded)?.queue
    val subtitle =
        when {
            queue == null || queue.total == 0 -> null
            queue.currentIndex != null -> stringResource(R.string.music_queue_position, queue.currentIndex + 1, queue.total)
            else -> stringResource(R.string.music_queue_count, queue.total)
        }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.music_queue), color = theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            subtitle?.let { Text(it, color = theme.mutedText, fontSize = 12.sp) }
        }
        HeaderIcon(Icons.Filled.Refresh, stringResource(R.string.music_queue_refresh), theme, onRefresh)
        HeaderIcon(Icons.Filled.Close, stringResource(R.string.close), theme, onClose)
    }
}

@Composable
private fun HeaderIcon(icon: ImageVector, description: String, theme: ThemeColors, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = theme.mutedText,
        modifier = Modifier.size(32.dp).clip(CircleShape).tapClickable(onClick = onClick).padding(5.dp)
    )
}

@Composable
private fun QueueBody(client: HaClient, answer: MediaQueue.Answer?, theme: ThemeColors, actions: QueueActions, modifier: Modifier) {
    val note: String? =
        when (answer) {
            null -> stringResource(R.string.media_loading)
            MediaQueue.Answer.Unsupported -> stringResource(R.string.music_queue_unsupported)
            MediaQueue.Answer.Failed -> stringResource(R.string.music_search_failed)
            is MediaQueue.Answer.Loaded -> if (answer.queue.tracks.isEmpty()) stringResource(R.string.music_queue_empty) else null
        }
    if (note != null || answer !is MediaQueue.Answer.Loaded) {
        Text(note.orEmpty(), color = theme.mutedText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    val queue = answer.queue
    val after = queue.total - ((queue.tracks.maxOfOrNull { it.index } ?: -1) + 1)
    LazyColumn(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        items(queue.tracks, key = { it.index }) { track ->
            TrackRow(client, track, queue.editable, theme, actions)
        }
        if (after > 0) {
            item(key = "more") {
                val text =
                    if (queue.editable) {
                        stringResource(R.string.music_queue_more, after)
                    } else {
                        stringResource(R.string.music_queue_more_ma, after)
                    }
                Text(text, color = theme.mutedText, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
            }
        }
    }
}

/**
 * The current track is highlighted. Lyrion: tap another to play it, ✕ to remove it.
 * Music Assistant: tap the next one to skip to it.
 */
@Composable
private fun TrackRow(client: HaClient, track: MediaQueue.Track, editable: Boolean, theme: ThemeColors, actions: QueueActions) {
    val onTap: (() -> Unit)? =
        when {
            track.current -> null
            editable -> ({ actions.play(track.index) })
            else -> actions.skip
        }
    var rowMod = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
    if (track.current) rowMod = rowMod.background(theme.accent.copy(alpha = 0.16f))
    if (onTap != null) rowMod = rowMod.tapClickable(onClick = onTap)
    Row(
        modifier = rowMod.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        TrackCover(client, track, theme)
        Column(Modifier.weight(1f)) {
            Text(
                track.title,
                color = if (track.current) theme.accent else theme.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val details = listOfNotNull(track.artist, track.duration?.takeIf { it > 0 }?.let { formatMediaTime(it) })
            if (details.isNotEmpty()) {
                Text(details.joinToString(" · "), color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (editable && !track.current) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.music_queue_remove),
                tint = theme.mutedText,
                modifier = Modifier.size(34.dp).clip(CircleShape).tapClickable { actions.remove(track.index) }.padding(7.dp)
            )
        }
    }
}

/** The track's cover when the player gives one, else a note (an equalizer for the current track). */
@Composable
private fun TrackCover(client: HaClient, track: MediaQueue.Track, theme: ThemeColors) {
    var bmp by remember(track.image) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(track.image) { bmp = track.image?.let { runCatching { client.fetchBitmap(it) }.getOrNull() } }
    val mod = Modifier.size(COVER_DP.dp).clip(RoundedCornerShape(8.dp))
    val image = bmp
    if (image != null) {
        Image(image, contentDescription = null, modifier = mod, contentScale = ContentScale.Crop)
    } else {
        Box(mod.background(theme.insetSurface), contentAlignment = Alignment.Center) {
            Icon(
                if (track.current) Icons.Filled.Equalizer else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (track.current) theme.accent else theme.mutedText,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/** **Clear**: a first tap asks to tap again (for a few seconds), the second one clears. */
@Composable
private fun ClearButton(confirm: Boolean, theme: ThemeColors, onClick: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (confirm) theme.accent else theme.controlBackground)
            .tapClickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (confirm) theme.background else theme.primaryText
        Icon(Icons.Filled.DeleteSweep, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(
            stringResource(if (confirm) R.string.music_queue_clear_confirm else R.string.music_queue_clear),
            color = color,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 8.dp)
        )
    }
}

/** The Queue button's icon, for [MediaLibraryButtons]. */
internal val QueueIcon: ImageVector get() = Icons.AutoMirrored.Filled.QueueMusic

private const val COVER_DP = 40
private const val AFTER_ACTION_MS = 700
private val CONFIRM_WINDOW = 4.seconds
