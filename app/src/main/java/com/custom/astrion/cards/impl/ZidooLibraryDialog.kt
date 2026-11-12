package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.custom.astrion.R
import com.custom.astrion.ScreenCapture
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import com.custom.astrion.zidoo.MusicAlbum
import com.custom.astrion.zidoo.MusicArtist
import com.custom.astrion.zidoo.MusicPlaylist
import com.custom.astrion.zidoo.MusicSource
import com.custom.astrion.zidoo.MusicTrack
import com.custom.astrion.zidoo.ZidooClient
import com.custom.astrion.zidoo.ZidooKind
import com.custom.astrion.zidoo.ZidooLibrary
import com.custom.astrion.zidoo.ZidooLibraryItem
import com.custom.astrion.zidoo.ZidooLibraryList
import kotlin.time.Duration.Companion.milliseconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** The lists the library opens on: the player's own filters, plus a search. */
private enum class LibraryTab(val list: Int?, val label: Int) {
    ALL(ZidooLibrary.ALL, R.string.zidoo_library_all),
    IN_PROGRESS(ZidooLibrary.HISTORY, R.string.zidoo_library_in_progress),
    SERIES(ZidooLibrary.SERIES, R.string.zidoo_library_series),
    RECENT(ZidooLibrary.RECENT, R.string.zidoo_library_recent),
    FAVORITES(ZidooLibrary.FAVORITES, R.string.zidoo_library_favorites),
    SEARCH(null, R.string.zidoo_library_search)
}

private const val PAGE = 40

/** How many dates of "In progress" are asked of the player at once. */
private const val SORT_PARALLEL = 4

/** History is one list of everything started or watched: read at once, then only what isn't finished kept. */
private const val HISTORY_COUNT = 500
private const val MIN_QUERY = 2
private const val DEBOUNCE_MS = 500L
private const val POSTER_W = 160
private const val POSTER_H = 240
private const val NEAR_END = 9
private const val MS_PER_MIN = 60_000L
private const val MIN_PER_H = 60

/**
 * The Zidoo's own library (1.5.0), opened by Browse on a Zidoo added in Astrion: its poster wall
 * as the player's phone app shows it — every film and saga, what is in progress, series, recently
 * added, favourites, a search — then a saga's films, a series' seasons, a season's episodes and a
 * film's page. Play starts it on the player (on the TV), and the pop-up closes.
 *
 * Talks to the player itself ([ZidooClient]), no Home Assistant. Posters are asked small (the
 * player scales them) and kept in memory only, like the Plex card's. The remote's arrows move
 * between posters and OK opens one; Back goes up a level, then closes.
 */
@Composable
internal fun ZidooLibraryDialog(zidoo: ZidooClient, theme: ThemeColors, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(LibraryTab.ALL) }
    val stack = remember { mutableStateListOf<ZidooLibraryItem>() }
    // Kept while a saga, a series or a film is open: Back finds the list where it was.
    val pagers = remember { HashMap<LibraryTab, LibraryPager>() }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val starting = stringResource(R.string.zidoo_library_starting)
    val failed = stringResource(R.string.zidoo_library_play_failed)
    val back: () -> Unit = {
        if (stack.isNotEmpty()) stack.removeAt(stack.lastIndex) else onClose()
    }
    val play: (ZidooLibraryItem) -> Unit = { item ->
        status = starting
        scope.launch {
            val ok =
                withContext(Dispatchers.IO) {
                    val file = item.fileId ?: ZidooLibrary.fileOf(zidoo.collection(item.id))
                    file != null && zidoo.playVideo(file)
                }
            if (ok) {
                onClose()
            } else {
                status = failed
            }
        }
    }
    val open: (ZidooLibraryItem) -> Unit = { item ->
        status = null
        if (item.kind.isFolder || item.kind == ZidooKind.MOVIE) stack.add(item) else play(item)
    }

    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        ScreenCapture.TrackForScreenshots()
        Column(
            modifier =
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val top = stack.lastOrNull()
            LibraryHeader(top?.name ?: stringResource(R.string.zidoo_library_title), top != null, theme, back, onClose)
            if (top == null) LibraryTabs(tab, theme) { tab = it }
            status?.let { Text(it, color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    top != null -> OpenedPane(zidoo, top, theme, open, play)
                    tab == LibraryTab.SEARCH -> SearchPane(zidoo, theme, open)
                    else -> TabGrid(pagers.getOrPut(tab) { LibraryPager(zidoo, tab) }, zidoo, theme, open)
                }
            }
        }
    }
}

/** What was opened: a film's page, a season's episodes, or a saga's films / a series' seasons. */
@Composable
private fun OpenedPane(
    zidoo: ZidooClient,
    item: ZidooLibraryItem,
    theme: ThemeColors,
    open: (ZidooLibraryItem) -> Unit,
    play: (ZidooLibraryItem) -> Unit
) {
    when (item.kind) {
        ZidooKind.MOVIE -> FilmPane(zidoo, item, theme) { play(item) }
        ZidooKind.SEASON -> SeasonPane(zidoo, item, theme, play)
        else -> FolderGrid(zidoo, item, theme, open)
    }
}

/** Where a list stands: still reading it, the player didn't answer, or read. */
private enum class Load { LOADING, FAILED, READY }

private fun loadOf(loading: Boolean, failed: Boolean): Load = when {
    loading -> Load.LOADING
    failed -> Load.FAILED
    else -> Load.READY
}

@Composable
private fun LibraryHeader(title: String, nested: Boolean, theme: ThemeColors, onBack: () -> Unit, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (nested) {
            RoundIcon(Icons.AutoMirrored.Filled.ArrowBack, theme, onBack)
            Spacer(Modifier.width(6.dp))
        }
        Text(
            title,
            color = theme.primaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        RoundIcon(Icons.Filled.Close, theme, onClose)
    }
}

@Composable
private fun RoundIcon(icon: ImageVector, theme: ThemeColors, onClick: () -> Unit) {
    Icon(
        icon,
        contentDescription = null,
        tint = theme.iconTint,
        modifier = Modifier.size(34.dp).clip(CircleShape).tapClickable(focusShape = CircleShape, onClick = onClick).padding(6.dp)
    )
}

@Composable
private fun LibraryTabs(selected: LibraryTab, theme: ThemeColors, onSelect: (LibraryTab) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        LibraryTab.entries.forEach { t ->
            val on = t == selected
            val shape = RoundedCornerShape(14.dp)
            Text(
                stringResource(t.label),
                color = if (on) theme.background else theme.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                Modifier
                    .clip(shape)
                    .background(if (on) theme.accent else theme.controlBackground)
                    .tapClickable(focusShape = shape) { onSelect(t) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

/** One of the player's lists, read a page at a time as the grid scrolls. */
private class LibraryPager(private val zidoo: ZidooClient, private val tab: LibraryTab) {
    var items by mutableStateOf<List<ZidooLibraryItem>>(emptyList())
    val grid = LazyGridState()
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)

    /** "In progress" being put in order of last viewing. */
    var sorting by mutableStateOf(false)
    private var offset = 0
    private var done = false

    init {
        if (tab == LibraryTab.IN_PROGRESS) zidoo.forgetLastWatched()
    }

    suspend fun loadMore() {
        val list = tab.list ?: return
        if (loading || done) return
        loading = true
        val count = if (tab == LibraryTab.IN_PROGRESS) HISTORY_COUNT else PAGE
        val page = withContext(Dispatchers.IO) { zidoo.libraryPage(list, offset, count) }
        if (page == null) {
            failed = items.isEmpty()
            done = true
        } else {
            offset += page.size
            // The player's `total` isn't the number of entries: a short page is the end.
            done = page.size < count || tab == LibraryTab.IN_PROGRESS
            val kept = if (tab == LibraryTab.IN_PROGRESS) page.filter { it.inProgress } else page
            val known = items.mapTo(HashSet()) { it.id }
            items = items + kept.filter { it.id !in known }
        }
        loading = false
        // The player lists its history alphabetically, with no date: shown at once, then put
        // latest-watched first (1.5.0) as its own app shows it, once each item's date is read.
        if (tab == LibraryTab.IN_PROGRESS && items.size > 1) {
            sorting = true
            try {
                // Films and seasons first (one small call each): the list is in order within
                // seconds. Then sagas and series, which take a few calls each, and the final order.
                val (quick, folders) = items.partition { !it.kind.isFolder || it.kind == ZidooKind.SEASON }
                readDates(quick)
                items = byDate(items)
                grid.scrollToItem(0)
                readDates(folders)
                items = byDate(items)
                grid.scrollToItem(0)
            } finally {
                sorting = false
            }
        }
    }

    /** When each was last watched, four asked at once. */
    private val dates = HashMap<Long, Long>()

    private suspend fun readDates(list: List<ZidooLibraryItem>) = coroutineScope {
        val gate = Semaphore(SORT_PARALLEL)
        val times = list.map { item -> async(Dispatchers.IO) { gate.withPermit { item.id to zidoo.lastWatched(item) } } }.awaitAll()
        times.forEach { (id, time) -> dates[id] = time }
    }

    /** Latest watched first; one not read yet keeps its place after those (the list stays alphabetical there). */
    private fun byDate(list: List<ZidooLibraryItem>): List<ZidooLibraryItem> = list.sortedByDescending { dates[it.id] ?: -1L }
}

@Composable
private fun TabGrid(pager: LibraryPager, zidoo: ZidooClient, theme: ThemeColors, open: (ZidooLibraryItem) -> Unit) {
    LaunchedEffect(pager) { if (pager.items.isEmpty()) pager.loadMore() }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (pager.sorting) {
            Text(stringResource(R.string.zidoo_library_sorting), color = theme.mutedText, fontSize = 12.sp)
        }
        PosterGrid(pager.items, loadOf(pager.loading, pager.failed), zidoo, theme, open, pager.grid) { pager.loadMore() }
    }
}

/** A saga's films or a series' seasons. */
@Composable
private fun FolderGrid(zidoo: ZidooClient, folder: ZidooLibraryItem, theme: ThemeColors, open: (ZidooLibraryItem) -> Unit) {
    var list by remember(folder.id) { mutableStateOf<ZidooLibraryList?>(null) }
    var done by remember(folder.id) { mutableStateOf(false) }
    LaunchedEffect(folder.id) {
        list = withContext(Dispatchers.IO) { ZidooLibrary.collection(zidoo.collection(folder.id)) }
        done = true
    }
    PosterGrid(list?.items.orEmpty(), loadOf(!done, list == null), zidoo, theme, open)
}

@Composable
private fun SearchPane(zidoo: ZidooClient, theme: ThemeColors, open: (ZidooLibraryItem) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<ZidooLibraryItem>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS.milliseconds)
        loading = true
        val found = withContext(Dispatchers.IO) { zidoo.librarySearch(q, PAGE) }
        failed = found == null
        results = found.orEmpty()
        loading = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.zidoo_library_search_hint), color = theme.mutedText, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = theme.accent) },
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
            modifier = Modifier.fillMaxWidth()
        )
        if (query.trim().length >= MIN_QUERY) {
            PosterGrid(results, loadOf(loading, failed), zidoo, theme, open)
        }
    }
}

@Composable
private fun Centered(text: String, color: Color) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, color = color, fontSize = 14.sp, textAlign = TextAlign.Center)
    }
}

@Composable
private fun PosterGrid(
    items: List<ZidooLibraryItem>,
    load: Load,
    zidoo: ZidooClient,
    theme: ThemeColors,
    open: (ZidooLibraryItem) -> Unit,
    grid: LazyGridState = rememberLazyGridState(),
    /** Asked for more when the grid nears its end (a list read a page at a time). */
    onNearEnd: (suspend () -> Unit) = {}
) {
    when {
        items.isEmpty() && load == Load.LOADING -> Centered(stringResource(R.string.media_loading), theme.mutedText)
        items.isEmpty() && load == Load.FAILED -> Centered(stringResource(R.string.zidoo_library_unreachable), theme.danger)
        items.isEmpty() -> Centered(stringResource(R.string.media_no_items), theme.mutedText)
        else -> {
            LaunchedEffect(grid, items.size) {
                snapshotFlow { grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                    .collect { if (it >= items.size - NEAR_END) onNearEnd() }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(100.dp),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(items, key = { it.id }) { item -> PosterTile(item, zidoo, theme) { open(item) } }
            }
        }
    }
}

/** A picture of the player's, kept in memory with the Plex card's posters. */
@Composable
private fun rememberPicture(url: String): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) { bmp = PlexApi.loadBitmap(url) }
    return bmp
}

@Composable
private fun PosterTile(item: ZidooLibraryItem, zidoo: ZidooClient, theme: ThemeColors, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    Column(Modifier.tapClickable(focusShape = shape, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(shape).background(theme.insetSurface)) {
            val poster = rememberPicture(zidoo.posterUrl(item.id, POSTER_W, POSTER_H))
            if (poster != null) {
                Image(poster, contentDescription = item.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(
                    item.name,
                    color = theme.mutedText,
                    fontSize = 11.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.align(Alignment.Center).padding(6.dp)
                )
            }
            Row(Modifier.align(Alignment.TopEnd).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                if (item.is4k) Badge("4K")
                if (item.isHdr) Badge("HDR")
            }
            if (item.watched) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = stringResource(R.string.zidoo_library_watched),
                    tint = Color.White,
                    modifier = Modifier.align(
                        Alignment.TopStart
                    ).padding(4.dp).size(18.dp).clip(CircleShape).background(theme.accent).padding(2.dp)
                )
            }
            item.progress?.let { ProgressBar(it, theme, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(item.name, color = theme.primaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        item.year?.let { Text(it.toString(), color = theme.mutedText, fontSize = 10.sp) }
    }
}

@Composable
private fun Badge(text: String) {
    Text(
        text,
        color = Color.White,
        fontSize = 9.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.clip(
            RoundedCornerShape(4.dp)
        ).background(Color.Black.copy(alpha = 0.65f)).padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
private fun ProgressBar(fraction: Float, theme: ThemeColors, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).background(Color.Black.copy(alpha = 0.55f))) {
        Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(theme.accent))
    }
}

/** "1 h 05" or "42 min". */
private fun durationText(ms: Long): String {
    val minutes = ms / MS_PER_MIN
    return if (minutes >= MIN_PER_H) "%d h %02d".format(minutes / MIN_PER_H, minutes % MIN_PER_H) else "$minutes min"
}

/** Year · rating · 4K · HDR. */
private fun metaLine(item: ZidooLibraryItem): String = listOfNotNull(
    item.year?.toString(),
    item.rating?.let { "★ %.1f".format(it) },
    item.durationMs?.let(::durationText),
    "4K".takeIf { item.is4k },
    "HDR".takeIf { item.isHdr }
).joinToString(" · ")

/** A film: its backdrop, what it is, how far it was watched, and Play. */
@Composable
private fun FilmPane(zidoo: ZidooClient, film: ZidooLibraryItem, theme: ThemeColors, onPlay: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(12.dp)).background(theme.insetSurface)) {
            val backdrop = rememberPicture(zidoo.backdropUrl(film.id)) ?: rememberPicture(zidoo.posterUrl(film.id, POSTER_W, POSTER_H))
            backdrop?.let { Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            film.progress?.let { ProgressBar(it, theme, Modifier.align(Alignment.BottomCenter)) }
        }
        Text(film.name, color = theme.primaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(metaLine(film), color = theme.mutedText, fontSize = 13.sp)
        val position = film.positionMs
        val duration = film.durationMs
        when {
            film.inProgress && position != null && duration != null ->
                Text(
                    stringResource(R.string.zidoo_library_stopped_at, durationText(position), durationText(duration)),
                    color = theme.accent,
                    fontSize = 13.sp
                )
            film.watched -> Text(stringResource(R.string.zidoo_library_watched), color = theme.mutedText, fontSize = 13.sp)
        }
        PlayButton(theme, onPlay)
    }
}

@Composable
private fun PlayButton(theme: ThemeColors, onPlay: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier =
        Modifier
            .clip(shape)
            .background(theme.accent)
            .tapClickable(focusShape = shape, onClick = onPlay)
            .padding(horizontal = 22.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = theme.background, modifier = Modifier.size(20.dp))
        Text(
            stringResource(R.string.zidoo_library_play),
            color = theme.background,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 6.dp)
        )
    }
}

/** A season: its synopsis and its episodes in order; one tap plays an episode. */
@Composable
private fun SeasonPane(zidoo: ZidooClient, season: ZidooLibraryItem, theme: ThemeColors, play: (ZidooLibraryItem) -> Unit) {
    var list by remember(season.id) { mutableStateOf<ZidooLibraryList?>(null) }
    var done by remember(season.id) { mutableStateOf(false) }
    LaunchedEffect(season.id) {
        list = withContext(Dispatchers.IO) { zidoo.season(season.id) }
        done = true
    }
    val episodes = list?.items.orEmpty()
    when {
        !done -> Centered(stringResource(R.string.media_loading), theme.mutedText)
        list == null -> Centered(stringResource(R.string.zidoo_library_unreachable), theme.danger)
        episodes.isEmpty() -> Centered(stringResource(R.string.media_no_items), theme.mutedText)
        else ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                list?.overview?.let { text ->
                    item { Text(text, color = theme.mutedText, fontSize = 12.sp, maxLines = 4, overflow = TextOverflow.Ellipsis) }
                }
                items(episodes, key = { it.id }) { ep -> EpisodeRow(ep, theme) { play(ep) } }
            }
    }
}

@Composable
private fun EpisodeRow(ep: ZidooLibraryItem, theme: ThemeColors, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(theme.insetSurface).tapClickable(focusShape = shape, onClick = onClick)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ep.episode?.let {
                    "E$it"
                } ?: "·",
                color = theme.accent,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(40.dp)
            )
            Text(
                ep.name,
                color = theme.primaryText,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            ep.durationMs?.let {
                Text(
                    durationText(it),
                    color = theme.mutedText,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 6.dp)
                )
            }
            Icon(
                if (ep.watched) Icons.Filled.Check else Icons.Filled.PlayArrow,
                contentDescription = if (ep.watched) stringResource(R.string.zidoo_library_watched) else null,
                tint = if (ep.watched) theme.mutedText else theme.accent,
                modifier = Modifier.size(20.dp)
            )
        }
        ep.progress?.let { ProgressBar(it, theme) }
    }
}

// ---- An Eversolo's music library (1.5.0) ----

private enum class MusicTab(val label: Int) {
    ALBUMS(R.string.zidoo_music_albums),
    ARTISTS(R.string.zidoo_music_artists),
    PLAYLISTS(R.string.zidoo_music_playlists),
    SEARCH(R.string.zidoo_library_search)
}

/** An album, an artist or a playlist opened on its tracks. */
private data class MusicOpen(val source: MusicSource, val id: Long, val title: String, val subtitle: String? = null)

private const val MUSIC_PAGE = 60

/** A list of the player's read a page at a time ([load] gets a start and a count). */
private class MusicPager<T>(private val load: (Int, Int) -> List<T>?) {
    var items by mutableStateOf<List<T>>(emptyList())
    var loading by mutableStateOf(false)
    var failed by mutableStateOf(false)
    val grid = LazyGridState()
    val list = LazyListState()
    var started by mutableStateOf(false)
    private var done = false

    suspend fun loadMore() {
        if (loading || done) return
        started = true
        loading = true
        val page = withContext(Dispatchers.IO) { load(items.size, MUSIC_PAGE) }
        if (page == null) {
            failed = items.isEmpty()
            done = true
        } else {
            done = page.size < MUSIC_PAGE
            items = items + page
        }
        loading = false
    }
}

/**
 * An Eversolo's music library (1.5.0), opened by Browse: albums, artists, playlists and a search,
 * as its own app lists them, then an album's, an artist's or a playlist's tracks. A track plays
 * its album (artist, playlist) from there on the Eversolo, and the pop-up closes.
 */
@Composable
internal fun ZidooMusicDialog(zidoo: ZidooClient, theme: ThemeColors, onClose: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(MusicTab.ALBUMS) }
    var opened by remember { mutableStateOf<MusicOpen?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val albums = remember { MusicPager(zidoo::musicAlbums) }
    val artists = remember { MusicPager(zidoo::musicArtists) }
    val starting = stringResource(R.string.zidoo_library_starting)
    val failed = stringResource(R.string.zidoo_library_play_failed)
    val back: () -> Unit = {
        if (opened != null) {
            opened = null
        } else {
            onClose()
        }
    }
    val play: (MusicOpen, List<MusicTrack>, Int) -> Unit = { from, tracks, index ->
        status = starting
        scope.launch {
            val ok = withContext(Dispatchers.IO) { zidoo.playMusic(from.source, from.id, tracks[index], index) }
            if (ok) {
                onClose()
            } else {
                status = failed
            }
        }
    }
    val open: (MusicOpen) -> Unit = {
        status = null
        opened = it
    }

    Dialog(onDismissRequest = back, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = false)) {
        ScreenCapture.TrackForScreenshots()
        Column(
            modifier =
            Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.9f)
                .clip(RoundedCornerShape(20.dp))
                .background(theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            val top = opened
            LibraryHeader(top?.title ?: stringResource(R.string.zidoo_music_title), top != null, theme, back, onClose)
            if (top == null) TabChips(MusicTab.entries.map { stringResource(it.label) }, tab.ordinal, theme) { tab = MusicTab.entries[it] }
            status?.let { Text(it, color = theme.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold) }
            Box(Modifier.fillMaxWidth().weight(1f)) {
                when {
                    top != null -> TracksPane(zidoo, top, theme) { tracks, i -> play(top, tracks, i) }
                    tab == MusicTab.ALBUMS -> AlbumGrid(albums, zidoo, theme, open)
                    tab == MusicTab.ARTISTS -> ArtistList(artists, theme, open)
                    tab == MusicTab.PLAYLISTS -> PlaylistList(zidoo, theme, open)
                    else -> MusicSearchPane(zidoo, theme, open)
                }
            }
        }
    }
}

@Composable
private fun TabChips(labels: List<String>, selected: Int, theme: ThemeColors, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            val shape = RoundedCornerShape(14.dp)
            Text(
                label,
                color = if (on) theme.background else theme.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                Modifier
                    .clip(shape)
                    .background(if (on) theme.accent else theme.controlBackground)
                    .tapClickable(focusShape = shape) { onSelect(i) }
                    .padding(horizontal = 12.dp, vertical = 7.dp)
            )
        }
    }
}

@Composable
private fun <T> PagedState(pager: MusicPager<T>, theme: ThemeColors, content: @Composable () -> Unit) {
    LaunchedEffect(pager) { if (pager.items.isEmpty()) pager.loadMore() }
    when {
        pager.items.isNotEmpty() -> content()
        pager.failed -> Centered(stringResource(R.string.zidoo_library_unreachable), theme.danger)
        pager.loading || !pager.started -> Centered(stringResource(R.string.media_loading), theme.mutedText)
        else -> Centered(stringResource(R.string.media_no_items), theme.mutedText)
    }
}

/** The album grid: a square tile per album, its first track's cover (its initial until then), name and artist under. */
@Composable
private fun AlbumGrid(pager: MusicPager<MusicAlbum>, zidoo: ZidooClient, theme: ThemeColors, open: (MusicOpen) -> Unit) {
    PagedState(pager, theme) {
        val items = pager.items
        LaunchedEffect(pager.grid, items.size) {
            snapshotFlow { pager.grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { if (it >= items.size - NEAR_END) pager.loadMore() }
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(100.dp),
            state = pager.grid,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(items, key = { it.id }) { album ->
                AlbumTile(album, zidoo, theme) { open(MusicOpen(MusicSource.ALBUM, album.id, album.name, album.artist)) }
            }
        }
    }
}

@Composable
private fun AlbumTile(album: MusicAlbum, zidoo: ZidooClient, theme: ThemeColors, onClick: () -> Unit) {
    val shape = RoundedCornerShape(8.dp)
    // Asked as the tile shows: one small call per album, then kept (1.5.0).
    var coverUrl by remember(album.id) { mutableStateOf<String?>(null) }
    LaunchedEffect(album.id) { coverUrl = withContext(Dispatchers.IO) { zidoo.albumCoverUrl(album.id) } }
    Column(Modifier.tapClickable(focusShape = shape, onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(shape).background(theme.insetSurface), contentAlignment = Alignment.Center) {
            val cover = coverUrl?.let { rememberPicture(it) }
            if (cover != null) {
                Image(cover, contentDescription = album.name, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                Text(album.name.take(1).uppercase(), color = theme.accent, fontSize = 30.sp, fontWeight = FontWeight.Bold)
            }
        }
        Text(album.name, color = theme.primaryText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.artist?.let { Text(it, color = theme.mutedText, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

/** One line of a list: a name, what's under it, a tap. */
@Composable
private fun MusicRow(title: String, subtitle: String?, theme: ThemeColors, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Column(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.insetSurface)
            .tapClickable(focusShape = shape, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(title, color = theme.primaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        subtitle?.let { Text(it, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun ArtistList(pager: MusicPager<MusicArtist>, theme: ThemeColors, open: (MusicOpen) -> Unit) {
    PagedState(pager, theme) {
        val items = pager.items
        LaunchedEffect(pager.list, items.size) {
            snapshotFlow { pager.list.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0 }
                .collect { if (it >= items.size - NEAR_END) pager.loadMore() }
        }
        LazyColumn(state = pager.list, verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
            items(items, key = { it.id }) { a -> MusicRow(a.name, null, theme) { open(MusicOpen(MusicSource.ARTIST, a.id, a.name)) } }
        }
    }
}

@Composable
private fun PlaylistList(zidoo: ZidooClient, theme: ThemeColors, open: (MusicOpen) -> Unit) {
    var lists by remember { mutableStateOf<List<MusicPlaylist>?>(null) }
    var done by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        lists = withContext(Dispatchers.IO) { zidoo.musicPlaylists() }
        done = true
    }
    val items = lists.orEmpty()
    when {
        !done -> Centered(stringResource(R.string.media_loading), theme.mutedText)
        lists == null -> Centered(stringResource(R.string.zidoo_library_unreachable), theme.danger)
        items.isEmpty() -> Centered(stringResource(R.string.media_no_items), theme.mutedText)
        else ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                items(items, key = { it.id }) { p -> MusicRow(p.name, null, theme) { open(MusicOpen(MusicSource.PLAYLIST, p.id, p.name)) } }
            }
    }
}

@Composable
private fun MusicSearchPane(zidoo: ZidooClient, theme: ThemeColors, open: (MusicOpen) -> Unit) {
    var query by rememberSaveable { mutableStateOf("") }
    var albums by remember { mutableStateOf<List<MusicAlbum>>(emptyList()) }
    var artists by remember { mutableStateOf<List<MusicArtist>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < MIN_QUERY) {
            albums = emptyList()
            artists = emptyList()
            return@LaunchedEffect
        }
        delay(DEBOUNCE_MS.milliseconds)
        loading = true
        albums = withContext(Dispatchers.IO) { zidoo.musicSearchAlbums(q, PAGE) }.orEmpty()
        artists = withContext(Dispatchers.IO) { zidoo.musicSearchArtists(q, PAGE) }.orEmpty()
        loading = false
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            singleLine = true,
            placeholder = { Text(stringResource(R.string.zidoo_music_search_hint), color = theme.mutedText, fontSize = 14.sp) },
            leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, tint = theme.accent) },
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
            modifier = Modifier.fillMaxWidth()
        )
        when {
            query.trim().length < MIN_QUERY -> Unit
            loading && albums.isEmpty() && artists.isEmpty() -> Centered(stringResource(R.string.media_loading), theme.mutedText)
            albums.isEmpty() && artists.isEmpty() -> Centered(stringResource(R.string.media_no_items), theme.mutedText)
            else ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                    items(albums, key = { "al${it.id}" }) { a ->
                        MusicRow(a.name, a.artist, theme) { open(MusicOpen(MusicSource.ALBUM, a.id, a.name, a.artist)) }
                    }
                    items(artists, key = { "ar${it.id}" }) { a ->
                        MusicRow(
                            a.name,
                            stringResource(R.string.zidoo_music_artist),
                            theme
                        ) { open(MusicOpen(MusicSource.ARTIST, a.id, a.name)) }
                    }
                }
        }
    }
}

/** An album's, an artist's or a playlist's tracks: the first one's cover, Play, then each track. */
@Composable
private fun TracksPane(zidoo: ZidooClient, from: MusicOpen, theme: ThemeColors, play: (List<MusicTrack>, Int) -> Unit) {
    var tracks by remember(from) { mutableStateOf<List<MusicTrack>?>(null) }
    var done by remember(from) { mutableStateOf(false) }
    LaunchedEffect(from) {
        tracks = withContext(Dispatchers.IO) { zidoo.musicTracks(from.source, from.id) }
        done = true
    }
    val list = tracks.orEmpty()
    when {
        !done -> Centered(stringResource(R.string.media_loading), theme.mutedText)
        tracks == null -> Centered(stringResource(R.string.zidoo_library_unreachable), theme.danger)
        list.isEmpty() -> Centered(stringResource(R.string.media_no_items), theme.mutedText)
        else ->
            LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxSize()) {
                item { TracksHeader(zidoo, from, list, theme) { play(list, 0) } }
                items(list.size) { i -> TrackRow(list[i], from.source != MusicSource.ALBUM, theme) { play(list, i) } }
            }
    }
}

@Composable
private fun TracksHeader(zidoo: ZidooClient, from: MusicOpen, tracks: List<MusicTrack>, theme: ThemeColors, onPlay: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)).background(theme.insetSurface)) {
            rememberPicture(zidoo.trackArtUrl(tracks.first()))?.let {
                Image(it, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            from.subtitle?.let { Text(it, color = theme.mutedText, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            PlayButton(theme, onPlay)
        }
    }
}

@Composable
private fun TrackRow(track: MusicTrack, withArtist: Boolean, theme: ThemeColors, onClick: () -> Unit) {
    val shape = RoundedCornerShape(10.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(theme.insetSurface)
            .tapClickable(focusShape = shape, onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            track.number?.toString() ?: "·",
            color = theme.accent,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.width(32.dp)
        )
        Column(Modifier.weight(1f)) {
            Text(track.title, color = theme.primaryText, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val under = if (withArtist) listOfNotNull(track.artist, track.album).joinToString(" · ") else null
            under?.takeIf {
                it.isNotEmpty()
            }?.let { Text(it, color = theme.mutedText, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        track.durationMs?.let { Text(trackTime(it), color = theme.mutedText, fontSize = 12.sp, modifier = Modifier.padding(start = 6.dp)) }
    }
}

/** "4:47". */
private fun trackTime(ms: Long): String {
    val s = ms / MS_PER_S
    return "%d:%02d".format(s / SEC_PER_MIN, s % SEC_PER_MIN)
}

private const val MS_PER_S = 1000L
private const val SEC_PER_MIN = 60
