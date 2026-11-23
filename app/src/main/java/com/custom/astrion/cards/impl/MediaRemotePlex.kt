package com.custom.astrion.cards.impl

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ScreenCapture
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.config.RemoteSettings
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * The media remote card's Plex (1.6.2, Zavie's request): its Library and Search buttons can open
 * the remote's Plex server (Devices › Plex server) instead of the player's own library or Home
 * Assistant's music search, and a film or an episode then plays on this card's Plex player
 * (`plex_play_with`, `plex_play_entity`… as the Plex card's, see [PlexPlayback]).
 *
 * ```
 * "library": "plex",           // the Library button opens Plex (default "device": the player's own)
 * "search_in": "plex",         // the Search button (music_search: true) searches Plex (default "music")
 * "plex_play_with": "rivulet", // or "mrmc" (with plex_mrmc_host…), else the Plex app
 * "plex_play_entity": "media_player.plex_apple_tv", "plex_play_content_type": "video", "plex_source": "Plex"
 * ```
 */
internal class RemotePlex(val server: PlexServer, val target: PlexPlayTarget) {
    companion object {
        /** The card's Plex, when the remote has a Plex server set; plays on [playerId] by default. */
        fun of(config: CardConfig, context: Context, playerId: String): RemotePlex? {
            val host = RemoteSettings.plexHost(context).trim().trimEnd('/').takeIf { it.isNotBlank() } ?: return null
            val token = RemoteSettings.plexToken(context).trim().takeIf { it.isNotBlank() } ?: return null
            return RemotePlex(PlexServer(host, token), PlexPlayback.targetOf(config, playerId, prefix = "plex_"))
        }

        fun libraryIsPlex(config: CardConfig) = config.string("library") == "plex"

        fun searchIsPlex(config: CardConfig) = config.string("search_in") == "plex"
    }
}

/** What of the card's Plex is open. */
internal enum class RemotePlexView { LIBRARY, SEARCH }

/**
 * The card's Plex dialogs: the library (its film and show sections, then one of them) or the
 * search; a title's details over it, with Play.
 */
@Composable
internal fun RemotePlexDialogs(ctx: CardContext, plex: RemotePlex, view: RemotePlexView, onClose: () -> Unit) {
    val context = LocalContext.current
    val host = plex.server.host
    val token = plex.server.token
    var machineId by remember { mutableStateOf<String?>(null) }
    var sections by remember { mutableStateOf<List<PlexApi.PlexSection>?>(null) }
    var section by remember { mutableStateOf<PlexApi.PlexSection?>(null) }
    var detailItem by remember { mutableStateOf<PlexApi.PlexItem?>(null) }
    var detail by remember { mutableStateOf<PlexApi.PlexDetail?>(null) }

    LaunchedEffect(host, token) {
        machineId = runCatching { PlexApi.fetchIdentity(host, token) }.getOrNull()
        if (view == RemotePlexView.LIBRARY) {
            val found = runCatching { PlexApi.fetchSections(host, token) }.getOrNull().orEmpty().filter { it.type in VIDEO_SECTIONS }
            sections = found
            // A single library: straight into it.
            if (found.size == 1) section = found.first()
        }
    }
    LaunchedEffect(detailItem) {
        detail = null
        val item = detailItem ?: return@LaunchedEffect
        detail = runCatching { PlexApi.fetchDetail(host, token, item.key) }.getOrNull()
    }

    when (view) {
        RemotePlexView.SEARCH ->
            PlexSearchDialog(host = host, token = token, theme = ctx.theme, onSelectItem = { detailItem = it }, onClose = onClose)
        RemotePlexView.LIBRARY -> {
            val open = section
            if (open == null) {
                SectionsDialog(sections, ctx.theme, onPick = { section = it }, onClose = onClose)
            } else {
                PlexLibraryDialog(
                    host = host,
                    token = token,
                    sectionKey = open.key,
                    sectionTitle = open.title,
                    theme = ctx.theme,
                    onSelectItem = { detailItem = it },
                    // Back to the sections when there are several, else out.
                    onClose = { if ((sections?.size ?: 0) > 1) section = null else onClose() }
                )
            }
        }
    }
    if (detailItem != null) {
        PlexDetailDialog(
            host = host,
            token = token,
            detail = detail,
            theme = ctx.theme,
            onSelectEpisode = { ep -> detailItem = PlexApi.PlexItem(ep.key, ep.title, "", ep.thumb, 0L) },
            onPlay = { key ->
                PlexPlayback.play(context, ctx.client, plex.server, plex.target, machineId, key)
                detailItem = null
                onClose()
            },
            onClose = { detailItem = null }
        )
    }
}

/** The server's film and show libraries, to pick one. */
@Composable
private fun SectionsDialog(
    sections: List<PlexApi.PlexSection>?,
    theme: ThemeColors,
    onPick: (PlexApi.PlexSection) -> Unit,
    onClose: () -> Unit
) {
    Dialog(onDismissRequest = onClose) {
        ScreenCapture.TrackForScreenshots()
        Column(
            Modifier.clip(RoundedCornerShape(24.dp)).background(theme.cardSurface).width(320.dp).heightIn(max = 520.dp).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(stringResource(R.string.plex_library), color = theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
            when {
                sections == null -> Text(stringResource(R.string.media_loading), color = theme.mutedText, fontSize = 13.sp)
                sections.isEmpty() -> Text(stringResource(R.string.plex_search_failed), color = theme.mutedText, fontSize = 13.sp)
                else ->
                    sections.forEach { s ->
                        Text(
                            s.title,
                            color = theme.primaryText,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(theme.controlBackground)
                                .tapClickable { onPick(s) }
                                .padding(horizontal = 14.dp, vertical = 12.dp)
                        )
                    }
            }
        }
    }
}

private val VIDEO_SECTIONS = setOf("movie", "show")
