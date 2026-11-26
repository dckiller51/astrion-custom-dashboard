package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/** The sources pop-up's cover view (1.6.3): a player that says `astrion_source_view: covers` (a PC's games). */
internal object CoverPicker {
    const val VIEW = "covers"

    fun wanted(ctx: CardContext, entityId: String): Boolean = ctx.entities[entityId]?.attrString("astrion_source_view") == VIEW

    /** A tap opens the source's sheet ([SourceSheet]) rather than picking it at once. */
    fun sheets(ctx: CardContext, entityId: String): Boolean =
        (ctx.entities[entityId]?.attr("astrion_source_details") as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}

/**
 * [sources] as portrait covers, three per row: the player's order (the most recently played
 * first) or A–Z; [covers] by source name, a coloured tile with the name when there is none. The
 * current one is outlined; a tap picks it, or opens its sheet when the player gives sheets (a PC's games).
 */
@Composable
internal fun CoverGrid(
    ctx: CardContext,
    entityId: String,
    sources: List<String>,
    covers: Map<String, String?>,
    current: String?,
    onPick: (String) -> Unit
) {
    var byName by remember { mutableStateOf(false) }
    var opened by remember { mutableStateOf<String?>(null) }
    // Kept while a sheet is open, so Back returns to the same place in the grid.
    val gridState = rememberLazyGridState()
    val shown = remember(sources, byName) { if (byName) sources.sortedWith(String.CASE_INSENSITIVE_ORDER) else sources }
    opened?.let { source ->
        SourceSheet(ctx, entityId, source, covers[source], onBack = { opened = null }, onLaunch = { onPick(source) })
        return
    }
    val sheets = CoverPicker.sheets(ctx, entityId)
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SortChip(ctx.theme, stringResource(R.string.picker_sort_recent), !byName) { byName = false }
            SortChip(ctx.theme, stringResource(R.string.picker_sort_az), byName) { byName = true }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(COLUMNS),
            state = gridState,
            modifier = Modifier.heightIn(max = MAX_HEIGHT.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(shown, key = { _, s -> s }) { index, source ->
                CoverTile(ctx, source, covers[source], source == current, index) { if (sheets) opened = source else onPick(source) }
            }
        }
    }
}

@Composable
private fun CoverTile(ctx: CardContext, name: String, path: String?, selected: Boolean, index: Int, onClick: () -> Unit) {
    val theme = ctx.theme
    val px = with(LocalDensity.current) { TILE_WIDTH.dp.toPx() }.toInt()
    val image = rememberImage(ctx, path, px)
    val shape = RoundedCornerShape(10.dp)
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .clip(shape)
                .background(if (image != null) theme.insetSurface else COVER_COLORS[index % COVER_COLORS.size])
                .then(if (selected) Modifier.border(2.dp, theme.accent, shape) else Modifier)
                .tapClickable(focusShape = shape, onClickLabel = name, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            if (image != null) {
                Image(image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Text(
                    name,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
        Text(
            name,
            color = if (selected) theme.primaryText else theme.mutedText,
            fontSize = 11.sp,
            lineHeight = 13.sp,
            // Two lines: a game's name is often long ("Red Dead Redemption 2", "Cyberpunk 2077: Ultimate Edition").
            minLines = 2,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SortChip(theme: ThemeColors, label: String, on: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(50)
    Text(
        label,
        color = if (on) theme.primaryText else theme.mutedText,
        fontSize = 13.sp,
        fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
        modifier =
        Modifier
            .clip(shape)
            .background(if (on) theme.insetSurface else Color.Transparent)
            .then(if (on) Modifier.border(1.dp, theme.accent, shape) else Modifier)
            .tapClickable(focusShape = shape, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 6.dp)
    )
}

private const val COLUMNS = 3
private const val MAX_HEIGHT = 460
private const val TILE_WIDTH = 96
private const val COVER_RATIO = 2f / 3f

/** A cover-less game's tile, by its place in the grid. */
private val COVER_COLORS =
    listOf(Color(0xFF3949AB), Color(0xFF00897B), Color(0xFF8E24AA), Color(0xFFD81B60), Color(0xFF6D4C41), Color(0xFF546E7A))
