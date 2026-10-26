package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * Native Plex browser — the lightweight answer to the plex-meets-homeassistant
 * web card. Talks straight to the Plex server's HTTP API (no HA round-trip):
 * shows On Deck, Recently Added Movies and Recently Added TV Shows as
 * swipeable poster rows.
 *
 * Input is two-tier, matching the rest of the app's long-press-for-detail
 * convention (LightCard, CoverCard, MediaPlayerCard) — but with tap and
 * long-press swapped from that convention on purpose: this row scrolls under
 * a finger, and an accidental tap mid-scroll is common (touch slop doesn't
 * always save it), so tap is bound to the harmless, easily-dismissed action
 * and the side-effecting one needs a deliberate hold.
 *   - Tap a poster    → [PlexDetailDialog]: synopsis, genres, and — for an
 *                       episode — the rest of its season to browse. Its own
 *                       Play button is what actually attempts real
 *                       playback (`media_player.play_media` on
 *                       `play_entity`, deep-linking to that exact item).
 *   - Long-press it    → [PlexLibraryDialog]: every item in that poster's
 *                       own Plex library, since Plex has no deep-link for
 *                       "open this whole library" — Astrion reimplements
 *                       that browse view itself. Falls back to opening
 *                       Plex on the playback entity directly
 *                       (`media_player.select_source`) if the server
 *                       didn't report a library section for the item.
 *
 * Kept deliberately light for the MT6580: posters are requested pre-scaled by
 * the server's photo transcoder (~140x210), rows are LazyRow (only visible
 * posters decode), and network/caching is shared with the detail dialog via
 * [PlexApi] rather than duplicated.
 *
 * Movies/TV are split automatically: [PlexApi] fetches `/library/sections`
 * once, keeps whichever sections report `type=="movie"` / `type=="show"`, and
 * calls each section's own `/recentlyAdded` — no need to type a library name.
 *
 * Config shape:
 *   { "type": "plex", "options": {
 *       "host": "http://plex-server:32400",
 *       "token": "<X-Plex-Token>",
 *       "media_entity": "media_player.tv",
 *       "play_entity": "media_player.plex_tv_client",   // optional
 *       "play_content_type": "video",                     // optional, defaults to "video" —
 *                                                          // use "url" if play_entity is HA's
 *                                                          // native Apple TV integration entity
 *       "source": "Plex",                                // optional, defaults to "Plex"
 *       "show_on_deck": true,                             // optional, defaults to true
 *       "show_recently_added_movies": true,               // optional, defaults to true
 *       "show_recently_added_shows": true,                // optional, defaults to true
 *       "items_per_row": 12,                               // optional, defaults to 12
 *       "show_search": true                                // optional (1.3.8), the 🔍 next to "Plex"
 *   } }
 */
class PlexCard : CardRenderer {
    override val type = "plex"

    private data class PlexConfig(
        val host: String,
        val token: String,
        val mediaEntity: String,
        val source: String,
        val playEntity: String?,
        val playContentType: String,
        val showOnDeck: Boolean,
        val showMovies: Boolean,
        val showShows: Boolean,
        val itemsPerRow: Int,
        val showSearch: Boolean
    )

    private class PlexUiState {
        var rows by mutableStateOf<List<PlexApi.PlexRow>?>(null)
        var machineId by mutableStateOf<String?>(null)
        var error by mutableStateOf<String?>(null)
        var detailItem by mutableStateOf<PlexApi.PlexItem?>(null)
        var detail by mutableStateOf<PlexApi.PlexDetail?>(null)
        var librarySectionKey by mutableStateOf<String?>(null)
        var librarySectionTitle by mutableStateOf<String?>(null)
        var searchOpen by mutableStateOf(false)
    }

    private fun readConfig(config: CardConfig): PlexConfig? {
        val host = config.string("host")?.trimEnd('/') ?: return null
        val token = config.string("token") ?: return null
        // Android-TV media_player entity. Long-pressing launches Plex on it via
        // select_source — deep-link item playback isn't reliable on Plex for
        // Android TV (opens the app but won't navigate to the item).
        val mediaEntity = config.string("media_entity") ?: return null
        return PlexConfig(
            host = host,
            token = token,
            mediaEntity = mediaEntity,
            source = config.string("source") ?: "Plex",
            // Optional: a real Plex-client entity (HA Plex integration, requires the
            // TV's Plex app registered as a companion client) → true item playback.
            playEntity = config.string("play_entity"),
            // "video" is right for a Plex-integration client entity; HA's native Apple TV
            // integration instead needs "url" to route through its deep-link/launch_app path
            // (see home-assistant.io/integrations/apple_tv/#launching-apps) — anything else it
            // treats as a real media stream and rejects with "Streaming ... is not supported".
            playContentType = config.string("play_content_type") ?: "video",
            showOnDeck = config.bool("show_on_deck", default = true),
            showMovies = config.bool("show_recently_added_movies", default = true),
            showShows = config.bool("show_recently_added_shows", default = true),
            itemsPerRow = config.int("items_per_row", default = 12).coerceIn(1, 30),
            showSearch = config.bool("show_search", default = true)
        )
    }

    @Composable
    private fun rememberPlexState(cfg: PlexConfig): PlexUiState {
        val context = LocalContext.current
        val state = remember { PlexUiState() }
        LaunchedEffect(cfg.host, cfg.token, cfg.showOnDeck, cfg.showMovies, cfg.showShows, cfg.itemsPerRow) {
            try {
                state.machineId = PlexApi.fetchIdentity(cfg.host, cfg.token)
                val out = loadRows(cfg.host, cfg.token, cfg.showOnDeck, cfg.showMovies, cfg.showShows, cfg.itemsPerRow)
                state.rows = out
                if (out.isEmpty()) state.error = context.getString(R.string.plex_error_no_items)
            } catch (ex: Exception) {
                state.error = ex.message ?: context.getString(R.string.plex_error_generic)
                state.rows = emptyList()
            }
        }
        LaunchedEffect(state.detailItem) {
            state.detail = null
            val cur = state.detailItem ?: return@LaunchedEffect
            state.detail = PlexApi.fetchDetail(cfg.host, cfg.token, cur.key)
        }
        return state
    }

    private fun openSource(client: HaClient, cfg: PlexConfig) {
        // Reliable fallback: open Plex on the TV so you can pick it.
        client.callService(ServiceCall.of("media_player", "select_source", cfg.mediaEntity, "source" to cfg.source))
    }

    private fun play(client: HaClient, cfg: PlexConfig, machineId: String?, ratingKey: String) {
        // machineId comes from the same /identity call rows already depend on; if it's still
        // null here the Plex server didn't answer it (or answered oddly) — rather than silently
        // doing nothing, fall back to just opening the app like the no-play_entity case does below.
        if (cfg.playEntity != null && machineId != null) {
            // Real playback via the HA Plex integration's client entity, or HA's native
            // Apple TV integration when play_content_type is "url". Note the Plex path only
            // works if that client is already connected (e.g. the TV's Plex app is open) —
            // HA's Plex integration can't wake/launch a client that isn't already active.
            client.callService(
                ServiceCall.of(
                    "media_player",
                    "play_media",
                    cfg.playEntity,
                    "media_content_type" to cfg.playContentType,
                    "media_content_id" to "plex://preplay/?metadataKey=$ratingKey&server=$machineId"
                )
            )
        } else {
            openSource(client, cfg)
        }
    }

    private fun openLibrary(client: HaClient, cfg: PlexConfig, state: PlexUiState, item: PlexApi.PlexItem) {
        val key = item.sectionKey
        if (key != null) {
            state.librarySectionKey = key
            state.librarySectionTitle = item.sectionTitle
        } else {
            // No section info from the server for this item — fall back to just
            // opening the app rather than doing nothing.
            openSource(client, cfg)
        }
    }

    @Composable
    private fun PlexDialogs(cfg: PlexConfig, state: PlexUiState, theme: ThemeColors, onPlay: (String) -> Unit) {
        // Search first, so the details of a result open over it (closing them comes back to the results).
        if (state.searchOpen) {
            PlexSearchDialog(
                host = cfg.host,
                token = cfg.token,
                theme = theme,
                onSelectItem = { item -> state.detailItem = item },
                onClose = { state.searchOpen = false }
            )
        }
        if (state.detailItem != null) {
            PlexDetailDialog(
                host = cfg.host,
                token = cfg.token,
                detail = state.detail,
                theme = theme,
                onSelectEpisode = { ep -> state.detailItem = PlexApi.PlexItem(ep.key, ep.title, "", ep.thumb, 0L) },
                onPlay = { key ->
                    onPlay(key)
                    state.detailItem = null
                    state.searchOpen = false
                },
                onClose = { state.detailItem = null }
            )
        }
        val sectionKey = state.librarySectionKey
        if (sectionKey != null) {
            PlexLibraryDialog(
                host = cfg.host,
                token = cfg.token,
                sectionKey = sectionKey,
                sectionTitle = state.librarySectionTitle ?: stringResource(R.string.plex_library),
                theme = theme,
                onSelectItem = { item ->
                    state.librarySectionKey = null
                    state.detailItem = item
                },
                onClose = { state.librarySectionKey = null }
            )
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val cfg = readConfig(config) ?: return
        val state = rememberPlexState(cfg)

        PlexRowsList(
            theme = ctx.theme,
            host = cfg.host,
            token = cfg.token,
            rows = state.rows,
            error = state.error,
            actions =
            PlexRowActions(
                onSearch = if (cfg.showSearch) ({ state.searchOpen = true }) else null,
                onTap = { item -> state.detailItem = item },
                onLongPress = { item -> openLibrary(ctx.client, cfg, state, item) }
            )
        )

        PlexDialogs(cfg, state, ctx.theme) { key -> play(ctx.client, cfg, state.machineId, key) }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PlexRowsList(
        theme: ThemeColors,
        host: String,
        token: String,
        rows: List<PlexApi.PlexRow>?,
        error: String?,
        actions: PlexRowActions
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(theme.cardSurface)
                .padding(vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            PlexHeader(theme, actions.onSearch)
            when {
                rows == null ->
                    Text(
                        stringResource(R.string.media_loading),
                        color = theme.mutedText,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(horizontal = 14.dp)
                    )
                error != null ->
                    Text(error, color = theme.danger, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
                else ->
                    rows.forEach { row ->
                        Text(row.title, color = theme.mutedText, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp)
                        ) {
                            items(row.items) { item ->
                                PosterTile(
                                    host = host,
                                    token = token,
                                    item = item,
                                    theme = theme,
                                    onTap = { actions.onTap(item) },
                                    onLongPress = { actions.onLongPress(item) }
                                )
                            }
                        }
                    }
            }
        }
    }

    /** What the card does on the search button (null: hidden), a poster tap and a poster long press. */
    private class PlexRowActions(
        val onSearch: (() -> Unit)?,
        val onTap: (PlexApi.PlexItem) -> Unit,
        val onLongPress: (PlexApi.PlexItem) -> Unit
    )

    /** "Plex", and the search button (1.3.8) when the card shows it. */
    @Composable
    private fun PlexHeader(theme: ThemeColors, onSearch: (() -> Unit)?) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Plex", color = theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (onSearch != null) {
                Row(
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(theme.controlBackground)
                        .tapClickable(onClick = onSearch)
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(Icons.Filled.Search, contentDescription = null, tint = theme.accent, modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.plex_search), color = theme.primaryText, fontSize = 13.sp)
                }
            }
        }
    }

    private suspend fun loadRows(
        host: String,
        token: String,
        showOnDeck: Boolean,
        showMovies: Boolean,
        showShows: Boolean,
        itemsPerRow: Int
    ): List<PlexApi.PlexRow> {
        val sections = if (showMovies || showShows) PlexApi.fetchSections(host, token) else emptyList()
        val out = mutableListOf<PlexApi.PlexRow>()
        if (showOnDeck) {
            PlexApi.fetchItems(host, token, "/library/onDeck", itemsPerRow)
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += PlexApi.PlexRow("On Deck", it) }
        }
        if (showMovies) {
            PlexApi.fetchRecentlyAddedForType(host, token, sections, "movie", itemsPerRow)
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += PlexApi.PlexRow("Recently Added Movies", it) }
        }
        if (showShows) {
            PlexApi.fetchRecentlyAddedForType(host, token, sections, "show", itemsPerRow)
                ?.takeIf { it.isNotEmpty() }
                ?.let { out += PlexApi.PlexRow("Recently Added TV", it) }
        }
        return out
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun PosterTile(
        host: String,
        token: String,
        item: PlexApi.PlexItem,
        theme: ThemeColors,
        onTap: () -> Unit,
        onLongPress: () -> Unit
    ) {
        // combinedClickable alone never fires onLongClick from a held hardware D-pad key
        // (only from a touch gesture's press duration) — rememberLongPressKeyModifier covers
        // that case separately, same fix as LightCard/CoverCard/MediaPlayerCard.
        val gestureModifier =
            rememberLongPressKeyModifier(item.key) { onLongPress() }
                .combinedClickable(onClick = onTap, onLongClick = onLongPress)
        Column(
            modifier = Modifier.width(104.dp).then(gestureModifier)
        ) {
            val posterUrl = remember(item.thumb) { PlexApi.posterUrl(host, token, item.thumb) }
            var bmp by remember(posterUrl) { mutableStateOf<ImageBitmap?>(null) }
            LaunchedEffect(posterUrl) { bmp = posterUrl?.let { PlexApi.loadBitmap(it) } }
            val posterMod = Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(10.dp))
            if (bmp != null) {
                Image(bmp!!, contentDescription = item.title, modifier = posterMod, contentScale = ContentScale.Crop)
            } else {
                Box(posterMod.background(theme.insetSurface))
            }
            Spacer(Modifier.height(4.dp))
            Text(item.title, color = theme.primaryText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.subtitle, color = theme.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
