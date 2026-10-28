package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Music search (1.3.9), opened from the media player card: type a title, an artist, an album…
 * the player answers as you type (Home Assistant's media search: Lyrion Music Server, Music
 * Assistant…); filters narrow it to one kind. Tap a result to play it now on [player], + to add
 * it to the queue, › to open it (an artist's albums, an album's tracks…).
 */
@Composable
internal fun MusicSearchDialog(client: HaClient, player: String, theme: ThemeColors, onClose: () -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<SearchResults>(SearchResults.Idle) }
    var browseAt by remember { mutableStateOf<MediaSearch.Item?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(query, filter) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            results = SearchResults.Idle
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS.milliseconds)
        results = SearchResults.Loading
        results =
            when (val answer = MediaSearch.search(client, player, q, filter)) {
                is MediaSearch.Answer.Results -> SearchResults.Done(q, answer.groups)
                MediaSearch.Answer.Unsupported -> SearchResults.Unsupported
                MediaSearch.Answer.Failed -> SearchResults.Failed
            }
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    val playing = stringResource(R.string.music_playing)
    val queued = stringResource(R.string.music_queued)
    val actions =
        ResultActions(
            pick = { item, add ->
                keyboard?.hide()
                MediaSearch.play(client, player, item, if (add) "add" else "play")
                status = (if (add) queued else playing).format(item.title)
            },
            open = { item ->
                keyboard?.hide()
                browseAt = item
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
            Header(theme, onClose)
            SearchField(query, { query = it }, theme, focus) { keyboard?.hide() }
            FilterChips(filter, theme) { filter = it }
            status?.let { Text(it, color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Results(client, results, theme, actions)
        }
    }
    browseAt?.let { item ->
        MediaBrowser(entityId = player, client = client, theme = theme, start = item.contentId to item.contentType) { browseAt = null }
    }
}

/** What a result does: [pick] plays it (now, or added to the queue), [open] browses into it. */
private class ResultActions(val pick: (MediaSearch.Item, Boolean) -> Unit, val open: (MediaSearch.Item) -> Unit)

/**
 * "Browse" (when the player supports it), "Search music" (when the card has `music_search`) and
 * "Queue" (1.4.0, when the player shares its queue or can clear it), side by side, wrapping to a
 * second line when they don't fit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MediaLibraryButtons(theme: ThemeColors, onBrowse: (() -> Unit)?, onSearch: (() -> Unit)?, onQueue: (() -> Unit)? = null) {
    if (onBrowse == null && onSearch == null && onQueue == null) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        onBrowse?.let { LibraryPill(theme, Icons.AutoMirrored.Filled.List, stringResource(R.string.media_browse), it) }
        onSearch?.let { LibraryPill(theme, Icons.Filled.Search, stringResource(R.string.music_search_button), it) }
        onQueue?.let { LibraryPill(theme, QueueIcon, stringResource(R.string.music_queue), it) }
    }
}

@Composable
private fun LibraryPill(theme: ThemeColors, icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier =
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(theme.controlBackground)
            .tapClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = theme.primaryText, modifier = Modifier.size(16.dp))
        Text(label, color = theme.primaryText, fontSize = 13.sp, modifier = Modifier.padding(start = 6.dp))
    }
}

private sealed class SearchResults {
    data object Idle : SearchResults()

    data object Loading : SearchResults()

    data object Unsupported : SearchResults()

    data object Failed : SearchResults()

    data class Done(val query: String, val groups: List<MediaSearch.Group>) : SearchResults()
}

@Composable
private fun Header(theme: ThemeColors, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            stringResource(R.string.music_search),
            color = theme.primaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Filled.Close,
            contentDescription = stringResource(R.string.close),
            tint = theme.mutedText,
            modifier = Modifier.size(28.dp).clip(CircleShape).tapClickable(onClick = onClose).padding(4.dp)
        )
    }
}

@Composable
private fun SearchField(query: String, onChange: (String) -> Unit, theme: ThemeColors, focus: FocusRequester, onDone: () -> Unit) {
    OutlinedTextField(
        value = query,
        onValueChange = onChange,
        singleLine = true,
        placeholder = { Text(stringResource(R.string.music_search_hint), color = theme.mutedText, fontSize = 14.sp) },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = theme.accent) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = null,
                    tint = theme.mutedText,
                    modifier = Modifier.tapClickable { onChange("") }
                )
            }
        },
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onDone() }),
        colors =
        OutlinedTextFieldDefaults.colors(
            focusedTextColor = theme.primaryText,
            unfocusedTextColor = theme.primaryText,
            focusedBorderColor = theme.accent,
            unfocusedBorderColor = theme.mutedText,
            cursorColor = theme.accent
        ),
        modifier = Modifier.fillMaxWidth().focusRequester(focus)
    )
}

/** "All" then one chip per kind; the selected one is filled. */
@Composable
private fun FilterChips(selected: String?, theme: ThemeColors, onSelect: (String?) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (listOf<String?>(null) + MediaSearch.FILTERS).forEach { type ->
            val on = type == selected
            Text(
                typeLabel(type),
                color = if (on) theme.background else theme.primaryText,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (on) theme.accent else theme.controlBackground)
                    .tapClickable { onSelect(type) }
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }
    }
}

/** A kind's name; one without its own label (genre, podcast…) is shown as the player names it, made readable. */
@Composable
private fun typeLabel(type: String?): String = when (type) {
    null -> stringResource(R.string.music_all)
    "artist" -> stringResource(R.string.music_artists)
    "album" -> stringResource(R.string.music_albums)
    "track" -> stringResource(R.string.music_tracks)
    "playlist" -> stringResource(R.string.music_playlists)
    else -> type.replace('_', ' ').replaceFirstChar { it.uppercase() }
}

@Composable
private fun Results(client: HaClient, results: SearchResults, theme: ThemeColors, actions: ResultActions) {
    val note: String? =
        when (results) {
            SearchResults.Idle -> stringResource(R.string.music_search_type)
            SearchResults.Loading -> stringResource(R.string.media_loading)
            SearchResults.Unsupported -> stringResource(R.string.music_search_unsupported)
            SearchResults.Failed -> stringResource(R.string.music_search_failed)
            is SearchResults.Done -> if (results.groups.isEmpty()) stringResource(R.string.music_search_none, results.query) else null
        }
    if (note != null || results !is SearchResults.Done) {
        Note(note.orEmpty(), theme)
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        results.groups.forEach { group ->
            item(key = "h-${group.mediaClass}") {
                Text(
                    typeLabel(group.mediaClass),
                    color = theme.mutedText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(group.items, key = { "${group.mediaClass}-${it.contentId}" }) { item ->
                ResultRow(client, item, theme, actions)
            }
        }
    }
}

/** Tap: play it (or open it, when it can't be played). +: add to the queue. ›: open it. */
@Composable
private fun ResultRow(client: HaClient, item: MediaSearch.Item, theme: ThemeColors, actions: ResultActions) {
    val onTap = if (item.canPlay) ({ actions.pick(item, false) }) else ({ actions.open(item) })
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tapClickable(onClick = onTap).padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Cover(client, item.thumbnail, if (item.mediaClass == "artist") CircleShape else RoundedCornerShape(8.dp), theme)
        Text(
            item.title,
            color = theme.primaryText,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (item.canPlay) RowIcon(Icons.Filled.Add, stringResource(R.string.music_add_to_queue), theme) { actions.pick(item, true) }
        if (item.canExpand) {
            RowIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, stringResource(R.string.media_browse), theme) {
                actions.open(item)
            }
        }
    }
}

@Composable
private fun RowIcon(icon: ImageVector, description: String, theme: ThemeColors, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = description,
        tint = theme.accent,
        modifier = Modifier.size(36.dp).clip(CircleShape).tapClickable(onClick = onClick).padding(6.dp)
    )
}

@Composable
private fun Cover(client: HaClient, url: String?, shape: Shape, theme: ThemeColors) {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = url?.let { runCatching { client.fetchBitmap(it) }.getOrNull() } }
    val mod = Modifier.size(COVER_DP.dp).clip(shape)
    bmp?.let { Image(it, contentDescription = null, modifier = mod, contentScale = ContentScale.Crop) }
        ?: Box(mod.background(theme.insetSurface))
}

@Composable
private fun Note(text: String, theme: ThemeColors) {
    Text(text, color = theme.mutedText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
}

private const val MIN_QUERY = 2
private const val DEBOUNCE_MS = 500
private const val COVER_DP = 44
