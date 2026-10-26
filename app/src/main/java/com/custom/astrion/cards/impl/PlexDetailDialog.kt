package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.icons.MdiIcons
import com.custom.astrion.ui.tapClickable

/**
 * Long-press detail popup for [PlexCard]'s poster tiles (mirrors
 * [LightDetailDialog]/[CoverDetailDialog]/[MediaPlayerDetailDialog]'s
 * long-press-for-detail pattern): backdrop art, synopsis, genres, and —
 * for an episode — the rest of its season to browse without leaving the
 * dialog. A single tap on a poster stays the quick action (open Plex);
 * this is the "tell me more, then let me choose Play" path.
 *
 * [detail] is nullable to represent "still loading" — [PlexCard] kicks off
 * the fetch and passes the result in once it resolves.
 */
@Composable
internal fun PlexDetailDialog(
    host: String,
    token: String,
    detail: PlexApi.PlexDetail?,
    theme: ThemeColors,
    onSelectEpisode: (PlexApi.PlexEpisodeSummary) -> Unit,
    onPlay: (String) -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .width(340.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (detail == null) {
                Text(stringResource(R.string.media_loading), color = theme.mutedText, fontSize = 13.sp)
            } else {
                DetailArt(host, token, detail, theme)
                DetailHeader(detail, theme)
                if (detail.genres.isNotEmpty()) DetailGenres(detail.genres, theme)
                if (detail.summary.isNotEmpty()) {
                    Text(detail.summary, color = theme.primaryText, fontSize = 13.sp, lineHeight = 18.sp)
                }
                detail.episodes?.let { episodes ->
                    EpisodeRow(
                        host,
                        token,
                        EpisodeList(stringResource(R.string.plex_this_season), episodes, detail.ratingKey),
                        theme,
                        onSelectEpisode
                    )
                }
                // A show (1.3.8): its seasons; one opens its next episode to watch.
                detail.seasons?.takeIf { it.isNotEmpty() }?.let { seasons ->
                    EpisodeRow(
                        host,
                        token,
                        EpisodeList(stringResource(R.string.plex_seasons), seasons, null, isSeasons = true),
                        theme,
                        onSelectEpisode
                    )
                }
                PlayButton(theme) { onPlay(detail.ratingKey) }
            }
        }
    }
}

@Composable
private fun DetailArt(host: String, token: String, detail: PlexApi.PlexDetail, theme: ThemeColors) {
    val artUrl =
        remember(detail.art, detail.thumb) { PlexApi.posterUrl(host, token, detail.art ?: detail.thumb, width = 600, height = 340) }
    var bmp by remember(artUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artUrl) { bmp = artUrl?.let { PlexApi.loadBitmap(it) } }
    val mod = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp))
    if (bmp != null) {
        Image(bmp!!, contentDescription = null, modifier = mod, contentScale = ContentScale.Crop)
    } else {
        Box(mod.background(theme.insetSurface))
    }
}

@Composable
private fun DetailHeader(detail: PlexApi.PlexDetail, theme: ThemeColors) {
    Column {
        Text(
            detail.headerTitle,
            color = theme.primaryText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        if (detail.subheading.isNotEmpty()) {
            Text(detail.subheading, color = theme.mutedText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (detail.metaLine.isNotEmpty()) {
            Text(detail.metaLine, color = theme.mutedText, fontSize = 12.sp)
        }
    }
}

@Composable
private fun DetailGenres(genres: List<String>, theme: ThemeColors) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        genres.take(3).forEach { g ->
            Text(
                g,
                color = theme.mutedText,
                fontSize = 11.sp,
                modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.controlBackground)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            )
        }
    }
}

/** A row of episodes (or of a show's seasons) under its heading; [selectedKey] is highlighted. */
private class EpisodeList(
    val heading: String,
    val items: List<PlexApi.PlexEpisodeSummary>,
    val selectedKey: String?,
    val isSeasons: Boolean = false
)

@Composable
private fun EpisodeRow(
    host: String,
    token: String,
    list: EpisodeList,
    theme: ThemeColors,
    onSelectEpisode: (PlexApi.PlexEpisodeSummary) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(list.heading, color = theme.mutedText, fontSize = 13.sp)
        LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 2.dp)) {
            items(list.items) { ep ->
                EpisodeTile(host, token, ep, EpisodeLook(ep.key == list.selectedKey, list.isSeasons), theme) { onSelectEpisode(ep) }
            }
        }
    }
}

/** How a tile shows: highlighted or not, and a season (poster, its own title) rather than an episode (still, "E3 · title"). */
private class EpisodeLook(val selected: Boolean, val season: Boolean)

@Composable
private fun EpisodeTile(
    host: String,
    token: String,
    ep: PlexApi.PlexEpisodeSummary,
    look: EpisodeLook,
    theme: ThemeColors,
    onClick: () -> Unit
) {
    val selected = look.selected
    val thumbUrl =
        remember(ep.thumb, look.season) {
            if (look.season) {
                PlexApi.posterUrl(
                    host,
                    token,
                    ep.thumb,
                    width = 120,
                    height = 180
                )
            } else {
                PlexApi.posterUrl(host, token, ep.thumb, width = 160, height = 90)
            }
        }
    var bmp by remember(thumbUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(thumbUrl) { bmp = thumbUrl?.let { PlexApi.loadBitmap(it) } }
    Column(modifier = Modifier.width(if (look.season) SEASON_TILE_DP.dp else 96.dp).tapClickable(onClick = onClick)) {
        val mod =
            Modifier
                .fillMaxWidth()
                .height(if (look.season) (SEASON_TILE_DP * 3 / 2).dp else 54.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (selected) theme.accent.copy(alpha = 0.25f) else theme.insetSurface)
        if (bmp != null) {
            Image(bmp!!, contentDescription = ep.title, modifier = mod, contentScale = ContentScale.Crop)
        } else {
            Box(mod)
        }
        Text(
            if (look.season) ep.title else stringResource(R.string.plex_episode, ep.index?.toString() ?: "?", ep.title),
            color = if (selected) theme.accent else theme.primaryText,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PlayButton(theme: ThemeColors, onClick: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(theme.accent)
            .tapClickable(onClick = onClick)
            .padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(MdiIcons.Play, contentDescription = null, tint = Color.White, modifier = Modifier.height(20.dp))
        Text(
            stringResource(R.string.plex_play),
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** Width of a season poster in a show's details (height 3:2). */
private const val SEASON_TILE_DP = 72
