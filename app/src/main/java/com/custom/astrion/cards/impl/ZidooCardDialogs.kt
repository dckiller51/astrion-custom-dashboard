package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import com.custom.astrion.zidoo.EversoloInput
import com.custom.astrion.zidoo.ZidooClient
import com.custom.astrion.zidoo.ZidooControls
import com.custom.astrion.zidoo.ZidooQueueItem
import com.custom.astrion.zidoo.ZidooRegistry
import com.custom.astrion.zidoo.ZidooTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Opens [dialog] for the Zidoo / Eversolo card (1.5.1); everything but Browse talks to the player itself. */
@Composable
internal fun ZidooCardDialog(
    ctx: CardContext,
    config: CardConfig,
    entityId: String,
    e: EntityState?,
    dialog: ZidooDialog,
    onClose: () -> Unit
) {
    when (dialog) {
        ZidooDialog.BROWSE -> BrowseDialog(entityId = entityId, client = ctx.client, theme = ctx.theme, onClose = onClose)
        ZidooDialog.SOURCES -> {
            val shortcuts = AppShortcuts(ctx, AppShortcuts.appsOf(config.options).orEmpty(), entityId)
            SourcePickerDialog(ctx, entityId, shortcuts.icons(), shortcuts.order(), onClose = onClose)
        }
        ZidooDialog.KEYPAD -> {
            // The digits go to the player's remote entity (Key.Number_0…9), unless the card says otherwise.
            val keypad = KeypadTarget.from(config, "remote." + entityId.substringAfter('.'), "DPAD_CENTER")
            if (keypad != null) KeypadDialog(ctx.theme, keypad.hasOk, onKey = { keypad.send(ctx, it) }, onClose = onClose)
        }
        else -> PlayerDialog(ctx, entityId, e, dialog, onClose)
    }
}

/** The pop-ups that ask the player itself: tracks, queue, input and output. */
@Composable
private fun PlayerDialog(ctx: CardContext, entityId: String, e: EntityState?, dialog: ZidooDialog, onClose: () -> Unit) {
    val client = ZidooRegistry.clientOf(entityId)
    if (client == null) {
        // Not a player added in Astrion (removed meanwhile): nothing to open.
        LaunchedEffect(Unit) { onClose() }
        return
    }
    when (dialog) {
        ZidooDialog.SUBTITLES, ZidooDialog.AUDIO, ZidooDialog.CHAPTERS -> TracksDialog(client, ctx.theme, dialog, onClose)
        ZidooDialog.QUEUE -> QueueDialog(ctx, client, e, onClose)
        ZidooDialog.IO -> InputOutputDialog(ctx, entityId, client, e, onClose)
        else -> Unit
    }
}

/** A film's subtitles, audio tracks and chapters, a tab each; a tap changes the track and the list stays open. */
@Composable
private fun TracksDialog(client: ZidooClient, theme: ThemeColors, first: ZidooDialog, onClose: () -> Unit) {
    var tab by remember { mutableStateOf(first) }
    var tracks by remember { mutableStateOf<List<ZidooTrack>?>(null) }
    var current by remember { mutableStateOf<Int?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(tab, reload) {
        if (tab == ZidooDialog.CHAPTERS) return@LaunchedEffect
        if (reload == 0) tracks = null
        val subtitles = tab == ZidooDialog.SUBTITLES
        val (list, now) =
            withContext(Dispatchers.IO) {
                val l = if (subtitles) client.subtitles() else client.audioTracks()
                val c = client.currentTracks()
                l to if (subtitles) c.first else c.second
            }
        tracks = list
        current = now
    }
    PickerFrame(theme, stringResource(R.string.zidoo_card_tracks), onClose) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(
                ZidooDialog.SUBTITLES to R.string.zidoo_card_subtitles,
                ZidooDialog.AUDIO to R.string.zidoo_card_audio,
                ZidooDialog.CHAPTERS to R.string.zidoo_card_chapters
            ).forEach { (t, label) ->
                Pill(stringResource(label), t == tab, theme, Modifier.weight(1f)) {
                    tab = t
                    reload = 0
                }
            }
        }
        if (tab == ZidooDialog.CHAPTERS) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ListRow(stringResource(R.string.zidoo_card_chapter_previous), null, false, theme, Modifier.weight(1f)) {
                    scope.launch(Dispatchers.IO) { client.chapter(next = false) }
                }
                ListRow(stringResource(R.string.zidoo_card_chapter_next), null, false, theme, Modifier.weight(1f)) {
                    scope.launch(Dispatchers.IO) { client.chapter(next = true) }
                }
            }
            return@PickerFrame
        }
        val list = tracks
        when {
            list == null -> Hint(stringResource(R.string.zidoo_card_loading), theme)
            list.isEmpty() -> Hint(stringResource(R.string.zidoo_card_no_tracks), theme)
            else ->
                LazyColumn(Modifier.heightIn(max = ZIDOO_LIST_MAX.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list, key = { _, t -> t.index }) { _, t ->
                        ListRow(t.title, null, t.index == current, theme, Modifier.fillMaxWidth()) {
                            current = t.index
                            val subtitles = tab == ZidooDialog.SUBTITLES
                            scope.launch(Dispatchers.IO) {
                                if (subtitles) client.setSubtitle(t.index) else client.setAudio(t.index)
                                reload++
                            }
                        }
                    }
                }
        }
    }
}

/**
 * The play queue: the Eversolo's own player and Eversolo's own Apple Music play the track tapped.
 * Apple's Apple Music doesn't obey that call: the track is reached by skipping to it (1.5.6), when
 * the one playing is found in the queue; else its queue is only shown.
 */
@Composable
private fun QueueDialog(ctx: CardContext, client: ZidooClient, e: EntityState?, onClose: () -> Unit) {
    val theme = ctx.theme
    var queue by remember { mutableStateOf<List<ZidooQueueItem>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(reload) { queue = withContext(Dispatchers.IO) { client.playQueue() } }
    val playing = ZidooControls.currentIndex(queue.orEmpty(), e?.attrString("media_title"), e?.attrString("media_artist"))
    val list = queue
    val title =
        if (list.isNullOrEmpty()) {
            stringResource(R.string.zidoo_card_queue)
        } else {
            stringResource(R.string.zidoo_card_queue) + " · " + stringResource(R.string.zidoo_card_queue_count, list.size)
        }
    PickerFrame(theme, title, onClose) {
        when {
            list == null -> Hint(stringResource(R.string.zidoo_card_loading), theme)
            list.isEmpty() -> Hint(stringResource(R.string.zidoo_card_queue_empty), theme)
            else -> {
                val skips = playing >= 0
                val readOnly = list.none { it.playable } && !skips
                LazyColumn(Modifier.heightIn(max = ZIDOO_LIST_MAX.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    itemsIndexed(list) { i, item ->
                        QueueRow(ctx, client, item, i, i == playing, readOnly || (!item.playable && i == playing)) {
                            scope.launch(Dispatchers.IO) {
                                if (item.playable) client.playQueueItem(item, i) else client.skipTracks(i - playing)
                                reload++
                            }
                        }
                    }
                }
                when {
                    readOnly -> Hint(stringResource(R.string.zidoo_card_queue_read_only), theme)
                    list.none { it.playable } -> Hint(stringResource(R.string.zidoo_card_queue_skips), theme)
                }
            }
        }
    }
}

@Composable
private fun QueueRow(
    ctx: CardContext,
    client: ZidooClient,
    item: ZidooQueueItem,
    index: Int,
    current: Boolean,
    readOnly: Boolean,
    onPlay: () -> Unit
) {
    val theme = ctx.theme
    val art: ImageBitmap? = rememberImage(ctx, client.queueArtUrl(item), THUMB_PX)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(if (current) theme.insetSurface else theme.controlBackground)
            .then(if (current) Modifier.border(1.dp, theme.accent, RoundedCornerShape(12.dp)) else Modifier)
            .then(if (readOnly) Modifier else Modifier.tapClickable(onClick = onPlay))
            .padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Box(Modifier.size(THUMB.dp).clip(RoundedCornerShape(6.dp)).background(theme.cardSurface), contentAlignment = Alignment.Center) {
            if (art != null) {
                Image(art, null, Modifier.size(THUMB.dp), contentScale = ContentScale.Crop)
            } else {
                Text(if (current) "▶" else "${index + 1}", color = if (current) theme.accent else theme.mutedText, fontSize = 12.sp)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = theme.primaryText,
                fontSize = 14.sp,
                fontWeight = if (current) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            item.artist?.let { Text(it, color = theme.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        item.durationMs?.let { Text(formatMediaTime(it / QUEUE_MS_PER_S), color = theme.mutedText, fontSize = 12.sp) }
    }
}

/**
 * An Eversolo's input (its own keys, as the Sources pop-up) and output: the outputs it lists, else
 * its output keys. Changing the output asks a second tap: a wrong output cuts the sound.
 */
@Composable
private fun InputOutputDialog(ctx: CardContext, entityId: String, client: ZidooClient, e: EntityState?, onClose: () -> Unit) {
    val theme = ctx.theme
    val scope = rememberCoroutineScope()
    var outputs by remember { mutableStateOf<com.custom.astrion.zidoo.ZidooOutputs?>(null) }
    var pending by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { outputs = withContext(Dispatchers.IO) { client.outputs() } }
    val input = EversoloInput.ofLabel(e?.attrString("source")) ?: EversoloInput.INTERNAL
    PickerFrame(theme, stringResource(R.string.zidoo_card_io), onClose) {
        Hint(stringResource(R.string.zidoo_card_input), theme)
        EversoloInput.entries.chunked(IO_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { i ->
                    ListRow(stringResource(inputLabelRes(i)), null, i == input, theme, Modifier.weight(1f)) {
                        zidooMp(ctx, entityId, "select_source", "source" to i.label)
                    }
                }
                repeat(IO_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        Hint(stringResource(R.string.zidoo_card_output), theme)
        val listed = outputs?.outputs.orEmpty()
        val choices: List<OutputChoice> =
            if (listed.isNotEmpty()) {
                listed.mapIndexed { i, o ->
                    OutputChoice(o.name, o.enabled, i == outputs?.current) {
                        scope.launch(Dispatchers.IO) { client.setOutput(o.tag) }
                    }
                }
            } else {
                OUTPUT_KEYS.map { (label, k) -> OutputChoice(label, true, false) { zidooKey(ctx, entityId, k) } }
            }
        choices.chunked(IO_COLUMNS).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                row.forEach { c ->
                    ListRow(c.name, null, c.current, theme, Modifier.weight(1f), enabled = c.enabled) {
                        if (pending == c.name) {
                            pending = null
                            c.select()
                        } else {
                            pending = c.name
                        }
                    }
                }
                repeat(IO_COLUMNS - row.size) { Box(Modifier.weight(1f)) }
            }
        }
        pending?.let { Hint(stringResource(R.string.zidoo_card_output_confirm, it), theme) }
    }
}

private class OutputChoice(val name: String, val enabled: Boolean, val current: Boolean, val select: () -> Unit)

/** An Eversolo's output keys (its command list), when it doesn't list its outputs. */
private val OUTPUT_KEYS =
    listOf(
        "XLR" to "Key.OUT.XLR",
        "RCA" to "Key.OUT.RCA",
        "XLR + RCA" to "Key.OUT.XLRRCA",
        "HDMI" to "Key.OUT.HDMI",
        "SPDIF" to "Key.OUT.SPDIF",
        "USB DAC" to "Key.OUT.USB"
    )

@Composable
private fun Pill(label: String, selected: Boolean, theme: ThemeColors, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(if (selected) theme.accent else theme.controlBackground)
            .tapClickable(onClick = onClick)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            label,
            color = if (selected) theme.cardSurface else theme.primaryText,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ListRow(
    label: String,
    detail: String?,
    selected: Boolean,
    theme: ThemeColors,
    modifier: Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Row(
        modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) theme.insetSurface else theme.controlBackground)
            .then(if (selected) Modifier.border(1.dp, theme.accent, RoundedCornerShape(12.dp)) else Modifier)
            .then(if (enabled) Modifier.tapClickable(onClick = onClick) else Modifier)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                color = if (enabled) theme.primaryText else theme.mutedText,
                fontSize = 14.sp,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            detail?.let { Text(it, color = theme.mutedText, fontSize = 11.sp) }
        }
        if (selected) Icon(Icons.Filled.Check, null, tint = theme.accent, modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun Hint(text: String, theme: ThemeColors) {
    Text(text, color = theme.mutedText, fontSize = 12.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp))
}

private const val ZIDOO_LIST_MAX = 420
private const val THUMB = 40
private const val THUMB_PX = 96
private const val IO_COLUMNS = 3
private const val QUEUE_MS_PER_S = 1000.0
