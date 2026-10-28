package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.delay

/**
 * Plex search (1.3.8): type a title, results arrive as you type — films, shows, seasons,
 * episodes and clips, from every library of the server (`/hubs/search`). Tapping one hands it
 * back to [PlexCard], which opens it in [PlexDetailDialog] (Play, a show's seasons…) over
 * this dialog, so closing the details comes back to the same results.
 *
 * Idea from baes-cloud/astrion-dashboard's `plex_browse` search (MIT), rewritten on [PlexApi].
 */
@Composable
internal fun PlexSearchDialog(
    host: String,
    token: String,
    theme: ThemeColors,
    onSelectItem: (PlexApi.PlexItem) -> Unit,
    onClose: () -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<SearchState>(SearchState.Idle) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            results = SearchState.Idle
            return@LaunchedEffect
        }
        // Wait for a pause in the typing: one request per word, not per letter.
        delay(DEBOUNCE_MS.milliseconds)
        results = SearchState.Loading
        results = PlexApi.search(host, token, q)?.let { SearchState.Done(q, it) } ?: SearchState.Failed
    }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    stringResource(R.string.plex_search),
                    color = theme.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = theme.mutedText,
                    modifier = Modifier.size(28.dp).clip(RoundedCornerShape(14.dp)).tapClickable(onClick = onClose).padding(4.dp)
                )
            }
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                singleLine = true,
                placeholder = { Text(stringResource(R.string.plex_search_hint), color = theme.mutedText, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = theme.accent) },
                trailingIcon = {
                    if (query.isNotEmpty()) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = null,
                            tint = theme.mutedText,
                            modifier = Modifier.tapClickable { query = "" }
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
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
            SearchResults(host, token, results, theme) { item ->
                keyboard?.hide()
                onSelectItem(item)
            }
        }
    }
}

/** Where the search stands: nothing typed yet, waiting for Plex, the answer, or Plex unreachable. */
private sealed class SearchState {
    data object Idle : SearchState()

    data object Loading : SearchState()

    data object Failed : SearchState()

    data class Done(val query: String, val groups: List<PlexApi.PlexSearchGroup>) : SearchState()
}

@Composable
private fun SearchResults(host: String, token: String, state: SearchState, theme: ThemeColors, onSelect: (PlexApi.PlexItem) -> Unit) {
    val note: String? =
        when (state) {
            SearchState.Idle -> stringResource(R.string.plex_search_type)
            SearchState.Loading -> stringResource(R.string.media_loading)
            SearchState.Failed -> stringResource(R.string.plex_search_failed)
            is SearchState.Done -> if (state.groups.isEmpty()) stringResource(R.string.plex_search_none, state.query) else null
        }
    if (note != null || state !is SearchState.Done) {
        Text(note.orEmpty(), color = theme.mutedText, fontSize = 13.sp, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.groups.forEach { group ->
            item(key = "h-${group.type}-${group.hubTitle}") {
                Text(
                    groupTitle(group),
                    color = theme.mutedText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }
            items(group.items, key = { "${group.type}-${it.key}" }) { item ->
                ResultRow(host, token, item, theme) { onSelect(item) }
            }
        }
    }
}

/** The group's heading in the app's language; a clip hub (music videos…) keeps the name Plex gives it. */
@Composable
private fun groupTitle(group: PlexApi.PlexSearchGroup): String = when (group.type) {
    "movie" -> stringResource(R.string.plex_movies)
    "show" -> stringResource(R.string.plex_shows)
    "season" -> stringResource(R.string.plex_seasons)
    "episode" -> stringResource(R.string.plex_episodes)
    else -> group.hubTitle ?: group.type
}

@Composable
private fun ResultRow(host: String, token: String, item: PlexApi.PlexItem, theme: ThemeColors, onClick: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .tapClickable(onClick = onClick)
            .padding(4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val posterUrl = remember(item.thumb) { PlexApi.posterUrl(host, token, item.thumb, width = 80, height = 120) }
        var bmp by remember(posterUrl) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(posterUrl) { bmp = posterUrl?.let { PlexApi.loadBitmap(it) } }
        val posterMod = Modifier.width(POSTER_W_DP.dp).height((POSTER_W_DP * 3 / 2).dp).clip(RoundedCornerShape(6.dp))
        bmp?.let { Image(it, contentDescription = null, modifier = posterMod, contentScale = ContentScale.Crop) }
            ?: Box(posterMod.background(theme.insetSurface))
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = theme.primaryText,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (item.subtitle.isNotBlank()) {
                Text(item.subtitle, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

private const val MIN_QUERY = 2
private const val DEBOUNCE_MS = 450
private const val POSTER_W_DP = 40
