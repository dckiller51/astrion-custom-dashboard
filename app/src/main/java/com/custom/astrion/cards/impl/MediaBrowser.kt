package com.custom.astrion.cards.impl

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.LocalTapFeedback
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One row in the media browser. */
private data class MediaItem(
    val title: String,
    val contentId: String,
    val contentType: String,
    val canExpand: Boolean,
    val canPlay: Boolean
)

private class BrowserUiState {
    var title by mutableStateOf<String?>(null)
    var items by mutableStateOf<List<MediaItem>?>(null)
    var error by mutableStateOf<String?>(null)
}

private suspend fun loadFolder(context: Context, client: HaClient, entityId: String, cid: String?, ctype: String?, state: BrowserUiState) {
    state.items = null
    state.error = null
    try {
        val result = client.browseMedia(entityId, cid, ctype)
        if (result == null) {
            state.error = context.getString(R.string.media_load_timeout)
            state.items = emptyList()
        } else {
            state.title = (result["title"] as? JsonPrimitive)?.content
            state.items = (result["children"] as? JsonArray)?.mapNotNull { parseItem(it as? JsonObject) } ?: emptyList()
        }
    } catch (ex: Exception) {
        // Some media_player integrations return an unexpected shape for a given
        // folder (e.g. a non-object "result") — surface it instead of letting
        // it crash the whole app.
        state.error = ex.message ?: context.getString(R.string.media_load_failed)
        state.items = emptyList()
    }
}

/**
 * Modal media browser over `media_player/browse_media`. Drill into expandable
 * folders (with a back button), tap a playable item to play it and close.
 * Kept to a plain list — no thumbnails — to stay light on the MT6580.
 */
/**
 * True for any content_type Home Assistant's Squeezebox/Lyrion integration
 * uses for its "Apps"/"Radios" category tree: the two roots themselves
 * (`"radios"`, `"apps"`) and every `"app-<cmd>"` node under either — which
 * stays true at any depth, since a node's content_type carries down
 * unchanged to all of its descendants (see browse_media.py). Both the
 * empty-folder-falls-back-to-play fix and the tap-tries-to-browse-anyway
 * fix below are deliberately scoped to just this, rather than any item
 * anywhere: real HA debug logs from this investigation confirmed this
 * specific integration's data is unreliable in *both* directions for these
 * two categories — leaf stations sometimes marked can_expand=true with
 * nothing underneath, and genuine sub-folders sometimes marked
 * can_expand=false despite having real children — so neither flag can be
 * fully trusted here. Elsewhere in the library (local tracks, DLNA, an
 * empty Favorites list) both flags are reliable and untouched.
 */
private fun isAppsOrRadiosContentType(ctype: String?): Boolean = ctype == "radios" || ctype == "apps" || ctype?.startsWith("app-") == true

@Composable
fun MediaBrowser(entityId: String, client: HaClient, theme: ThemeColors = ThemeColors.Default, onClose: () -> Unit) {
    // Navigation stack of (contentId, contentType); root is (null, null).
    val stack = remember { mutableStateListOf<Pair<String?, String?>>(null to null) }
    val state = remember { BrowserUiState() }

    // Reload whenever the depth changes (push/pop).
    val context = LocalContext.current
    androidx.compose.runtime.LaunchedEffect(stack.size) {
        val (cid, ctype) = stack.last()
        loadFolder(context, client, entityId, cid, ctype, state)
        // Some integrations (confirmed for Home Assistant's own Squeezebox/
        // Lyrion "Apps"/"Radios" listing) mark a leaf, directly-playable
        // entry as can_expand=true regardless of whether it actually has
        // anything underneath. A short tap on one of these correctly tries
        // to browse in first — an album/playlist is often both expandable
        // AND playable, so canExpand has to win the tap over canPlay — but
        // that browse then comes back genuinely empty. Rather than leave
        // the user looking at "Nothing here" for what's actually a leaf,
        // play it directly instead: this exactly matches how Home
        // Assistant's own frontend resolves-and-plays these outright
        // rather than insisting on can_expand (confirmed via its own debug
        // logs during this investigation). The root level (stack.size == 1,
        // cid/ctype both null) is excluded — there's no "item" to fall back
        // to playing there, an empty root is just an empty library.
        // Split from the final check below purely to keep each condition's
        // term count under detekt's ComplexCondition threshold.
        val isFromAppsOrRadios = isAppsOrRadiosContentType(ctype)
        val emptyNonRootFolder = stack.size > 1 && state.error == null && state.items?.isEmpty() == true
        val shouldFallbackToPlay = emptyNonRootFolder && isFromAppsOrRadios
        if (shouldFallbackToPlay && cid != null) {
            // ctype can't actually be null here even though its static type
            // is String? — every isFromAppsOrRadios branch above only
            // matches a non-null ctype, so shouldFallbackToPlay being true
            // already proves it (the compiler agrees: an explicit
            // `ctype != null` check was flagged as redundant/always-true).
            // The elvis is just to satisfy the compiler without `!!`; it
            // never actually returns early in practice.
            val safeCtype = ctype ?: return@LaunchedEffect
            client.playMedia(entityId, cid, safeCtype)
            onClose()
        }
    }

    Dialog(onDismissRequest = onClose) {
        MediaBrowserBody(entityId, client, theme, stack, state, onClose)
    }
}

@Composable
private fun MediaBrowserBody(
    entityId: String,
    client: HaClient,
    theme: ThemeColors,
    stack: MutableList<Pair<String?, String?>>,
    state: BrowserUiState,
    onClose: () -> Unit
) {
    // The item a long-press is currently offering actions for (folder, album,
    // playlist — anything with can_play, expandable or not), or null when no
    // popup is showing.
    var longPressItem by remember { mutableStateOf<MediaItem?>(null) }

    Box(
        modifier =
        Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(18.dp))
                .background(theme.cardSurface)
                .padding(12.dp)
        ) {
            // Header: back (when nested), title, close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) {
                    IconBtn(Icons.AutoMirrored.Filled.ArrowBack, theme) { if (stack.size > 1) stack.removeAt(stack.size - 1) }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    state.title ?: stringResource(R.string.media_default_title),
                    color = theme.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconBtn(Icons.Filled.Close, theme, onClick = onClose)
            }
            Spacer(Modifier.height(8.dp))

            val items = state.items
            val error = state.error
            when {
                items == null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = theme.accent)
                    }
                error != null ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(error, color = theme.danger, fontSize = 14.sp)
                    }
                items.isEmpty() ->
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(stringResource(R.string.media_nothing_here), color = theme.mutedText, fontSize = 14.sp)
                    }
                else ->
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        items(items) { item ->
                            MediaRow(
                                item = item,
                                theme = theme,
                                onClick = {
                                    when {
                                        item.canExpand -> stack.add(item.contentId to item.contentType)
                                        item.canPlay -> {
                                            client.playMedia(entityId, item.contentId, item.contentType)
                                            onClose()
                                        }
                                        // Apps/Radios data from this integration is unreliable in
                                        // both directions (see isAppsOrRadiosContentType's doc) —
                                        // here specifically, a genuine sub-folder came back marked
                                        // can_expand=false despite having real children underneath.
                                        // Try browsing into it anyway; if that turns out to have
                                        // been correct after all (a true dead end), the empty-
                                        // folder fallback in MediaBrowser's reload effect catches
                                        // it and plays the item instead.
                                        isAppsOrRadiosContentType(item.contentType) ->
                                            stack.add(item.contentId to item.contentType)
                                    }
                                },
                                // A playable item gets the popup outright (an album/playlist
                                // is often both can_play and can_expand). A can_expand-only
                                // item ALSO gets it now: some integrations (confirmed for Home
                                // Assistant's own Squeezebox/Lyrion "Apps"/"Radios" browsing —
                                // see the HA debug logs from this investigation) mark a leaf,
                                // directly-playable entry as can_expand=true, can_play=false
                                // regardless — a short tap just dead-ends into an empty
                                // folder there. Home Assistant's own frontend works around
                                // this by resolving-and-playing such items outright instead of
                                // insisting on can_expand; long-press here is that same escape
                                // hatch, without changing what a short tap does for an actual,
                                // non-empty folder.
                                onLongPress = if (item.canPlay || item.canExpand) {
                                    { longPressItem = item }
                                } else {
                                    null
                                }
                            )
                        }
                    }
            }
        }

        val popupItem = longPressItem
        if (popupItem != null) {
            MediaItemActionsPopup(
                item = popupItem,
                theme = theme,
                onDismiss = { longPressItem = null },
                onPlay = {
                    longPressItem = null
                    client.playMedia(entityId, popupItem.contentId, popupItem.contentType)
                    onClose()
                },
                onAddToQueue = {
                    longPressItem = null
                    client.playMedia(entityId, popupItem.contentId, popupItem.contentType, enqueue = "add")
                }
            )
        }
    }
}

/**
 * Small centered actions popup shown on a long-press of a playable Media
 * Browser row. Deliberately doesn't close the browser on "Add to queue" —
 * unlike "Play", queuing is meant to be repeated while continuing to browse.
 */
@Composable
private fun MediaItemActionsPopup(
    item: MediaItem,
    theme: ThemeColors,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onAddToQueue: () -> Unit
) {
    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.45f))
            .tapClickable(onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier =
            Modifier
                // Swallow taps on the popup itself so they don't fall through
                // to the dismiss backdrop behind it.
                .tapClickable(onClick = {})
                .clip(RoundedCornerShape(14.dp))
                .background(theme.cardSurface)
                .padding(vertical = 8.dp, horizontal = 4.dp)
                .width(220.dp)
        ) {
            Text(
                item.title,
                color = theme.mutedText,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
            )
            MediaActionRow(Icons.Filled.PlayArrow, stringResource(R.string.media_browser_play), theme, onPlay)
            MediaActionRow(Icons.Filled.Add, stringResource(R.string.media_browser_add_to_queue), theme, onAddToQueue)
        }
    }
}

@Composable
private fun MediaActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, theme: ThemeColors, onClick: () -> Unit) {
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .tapClickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = theme.accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(label, color = theme.primaryText, fontSize = 15.sp)
    }
}

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun MediaRow(item: MediaItem, theme: ThemeColors, onClick: () -> Unit, onLongPress: (() -> Unit)? = null) {
    val feedback = LocalTapFeedback.current
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClick = {
                    feedback()
                    onClick()
                },
                onLongClick = onLongPress?.let {
                    {
                        feedback()
                        it()
                    }
                }
            )
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.title,
            color = theme.primaryText,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        if (item.canExpand) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = theme.mutedText)
        } else if (item.canPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = theme.accent)
        }
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(40.dp)
            .tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = theme.iconTint)
    }
}

private fun parseItem(o: JsonObject?): MediaItem? {
    o ?: return null

    fun str(k: String) = (o[k] as? JsonPrimitive)?.content

    fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    val contentId = str("media_content_id") ?: return null
    val contentType = str("media_content_type") ?: return null
    val title = str("title") ?: contentId
    return MediaItem(title, contentId, contentType, bool("can_expand"), bool("can_play"))
}
