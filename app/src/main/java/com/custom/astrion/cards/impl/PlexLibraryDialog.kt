package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ScreenCapture
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlinx.coroutines.flow.collect

/**
 * Long-press "browse this library" dialog: Plex doesn't offer a deep-link
 * that jumps to a whole library section (`plex://` only addresses single
 * items), so this reimplements that browse view directly in Astrion — a
 * paginated poster grid over `/library/sections/{sectionKey}/all`, loading
 * more as the user scrolls near the bottom. Tapping a poster hands the item
 * back to [PlexCard], which opens it in [PlexDetailDialog] the same way a
 * tap on a row poster does.
 */
@Composable
internal fun PlexLibraryDialog(
    host: String,
    token: String,
    sectionKey: String,
    sectionTitle: String,
    theme: ThemeColors,
    onSelectItem: (PlexApi.PlexItem) -> Unit,
    onClose: () -> Unit
) {
    val pageSize = 60
    var items by remember(sectionKey) { mutableStateOf<List<PlexApi.PlexItem>>(emptyList()) }
    var totalSize by remember(sectionKey) { mutableStateOf<Int?>(null) }
    var loading by remember(sectionKey) { mutableStateOf(false) }
    val gridState = rememberLazyGridState()

    suspend fun loadMore() {
        if (loading) return
        val known = totalSize
        if (known != null && items.size >= known) return
        loading = true
        PlexApi.fetchLibraryPage(host, token, sectionKey, items.size, pageSize)?.let { page ->
            items = items + page.items
            totalSize = page.totalSize
        }
        loading = false
    }

    LaunchedEffect(sectionKey) { loadMore() }

    val shouldLoadMore by remember {
        derivedStateOf {
            val info = gridState.layoutInfo
            val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
            items.isNotEmpty() && lastVisible >= items.size - 9
        }
    }
    LaunchedEffect(shouldLoadMore) {
        snapshotFlow { shouldLoadMore }.collect { if (it) loadMore() }
    }

    Dialog(onDismissRequest = onClose) {
        ScreenCapture.TrackForScreenshots()
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .width(340.dp)
                .heightIn(max = 560.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            LibraryHeader(sectionTitle, totalSize, theme)
            if (items.isEmpty()) {
                val emptyLabel =
                    if (loading) {
                        stringResource(R.string.media_loading)
                    } else {
                        stringResource(R.string.media_no_items)
                    }
                Text(emptyLabel, color = theme.mutedText, fontSize = 13.sp)
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    state = gridState,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    items(items) { item ->
                        LibraryTile(host, token, item, theme) { onSelectItem(item) }
                    }
                }
                if (loading) Text(stringResource(R.string.media_loading_more), color = theme.mutedText, fontSize = 12.sp)
            }
        }
    }
}

@Composable
private fun LibraryHeader(sectionTitle: String, totalSize: Int?, theme: ThemeColors) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(sectionTitle, color = theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        if (totalSize != null) {
            Text("$totalSize items", color = theme.mutedText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun LibraryTile(host: String, token: String, item: PlexApi.PlexItem, theme: ThemeColors, onClick: () -> Unit) {
    Column(modifier = Modifier.tapClickable(onClick = onClick)) {
        val posterUrl = remember(item.thumb) { PlexApi.posterUrl(host, token, item.thumb, width = 160, height = 240) }
        var bmp by remember(posterUrl) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(posterUrl) { bmp = posterUrl?.let { PlexApi.loadBitmap(it) } }
        val posterMod = Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))
        if (bmp != null) {
            Image(bmp!!, contentDescription = item.title, modifier = posterMod, contentScale = ContentScale.Crop)
        } else {
            Box(posterMod.background(theme.insetSurface))
        }
        Text(item.title, color = theme.primaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
