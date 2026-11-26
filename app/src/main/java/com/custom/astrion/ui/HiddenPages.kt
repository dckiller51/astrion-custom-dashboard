package com.custom.astrion.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.Velocity
import com.custom.astrion.config.PageConfig

/**
 * `"hiddenUnlessActivity": "__never__"` (1.6.0, *Never shown*): a page with no dot that a swipe
 * never lands on, only opened by a tile, a key or a linked page (a popup page, MR RIZK). No
 * Activity has that id, so it's always hidden, and always completely.
 */
internal const val PAGE_NEVER_SHOWN = "__never__"

/** A page set to `"hideMode": "full"` whose Activity isn't running right now, or a page never shown. */
internal fun isFullyHidden(page: PageConfig?, activeActivityIds: Set<String>): Boolean {
    val activity = page?.hiddenUnlessActivity ?: return false
    if (activity == PAGE_NEVER_SHOWN) return true
    return page.hideMode == "full" && activity !in activeActivityIds
}

/**
 * What [HiddenPageSkipEffect] tells the pager: which page's content to draw in a slot. A fully
 * hidden page reached by a swipe is drawn as the page the swipe will end up on, so the hidden
 * page never shows — not even during the swipe — and the jump that follows is invisible.
 */
internal fun interface HiddenPageContent {
    /** The page whose content is drawn in pager slot [index] (usually [index] itself). */
    fun contentIndex(index: Int): Int
}

private val Identity = HiddenPageContent { it }

/**
 * `"hideMode": "full"` on a page with `hiddenUnlessActivity`:
 *
 *  - A **swipe** towards such a page while its Activity is off shows the next visible page in
 *    the same direction in its place (or the page it came from if there is none), and once the
 *    swipe settles the pager moves to that page with no visible change. The hidden page never
 *    appears. Jumps from a tile, hotkey, dot or Home Assistant are left alone — that's how
 *    "start the Activity + open its page" works, since the Activity only counts as running once
 *    all its commands are sent, a moment *after* the jump.
 *  - If the Activity **stops** while its page is on screen, the remote leaves the page: to its
 *    `parent` if it has one, else the nearest visible page before it.
 *
 * The pager itself still holds every page (see PageConfig.hiddenUnlessActivity for why).
 */
@Composable
internal fun HiddenPageSkipEffect(pages: List<PageConfig>, pagerState: PagerState, activeActivityIds: Set<String>): HiddenPageContent {
    if (pages.none { it.hiddenUnlessActivity != null && (it.hideMode == "full" || it.hiddenUnlessActivity == PAGE_NEVER_SHOWN) }) {
        return Identity
    }
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    var draggedSinceSettle by remember { mutableStateOf(false) }
    var lastSettled by remember { mutableIntStateOf(pagerState.settledPage) }
    LaunchedEffect(dragged) { if (dragged) draggedSinceSettle = true }

    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        if (draggedSinceSettle && isFullyHidden(pages.getOrNull(page), activeActivityIds)) {
            // Its slot already shows the target's content: this move is invisible.
            pagerState.scrollToPage(swipeTarget(pages, page, lastSettled, activeActivityIds))
            return@LaunchedEffect // runs again for the page we just moved to
        }
        draggedSinceSettle = false
        lastSettled = page
    }

    HiddenPageExitEffect(pages, pagerState, activeActivityIds)

    // Only while a swipe is under way (or settling): a jump onto the page must show the real thing.
    return HiddenPageContent { index ->
        val swiping = dragged || draggedSinceSettle
        if (swiping && isFullyHidden(pages.getOrNull(index), activeActivityIds)) {
            swipeTarget(pages, index, lastSettled, activeActivityIds)
        } else {
            index
        }
    }
}

/** Leaves a `"hideMode": "full"` page when its Activity stops while it's on screen. */
@Composable
private fun HiddenPageExitEffect(pages: List<PageConfig>, pagerState: PagerState, activeActivityIds: Set<String>) {
    var previousActive by remember { mutableStateOf(activeActivityIds) }
    LaunchedEffect(activeActivityIds) {
        val page = pages.getOrNull(pagerState.currentPage)
        val activity = page?.hiddenUnlessActivity
        val justStopped = activity != null && activity in previousActive && activity !in activeActivityIds
        previousActive = activeActivityIds
        if (justStopped && page?.hideMode == "full") {
            pagerState.scrollToPage(exitTarget(pages, pagerState.currentPage, activeActivityIds))
        }
    }
}

/** Where a swipe onto hidden page [index], coming from [from], ends up: the next visible page in its direction. */
private fun swipeTarget(pages: List<PageConfig>, index: Int, from: Int, active: Set<String>): Int {
    val step = if (index >= from) 1 else -1
    return nextVisible(pages, index, step, active) ?: from
}

/** The first page from [from] (exclusive) going [step] that isn't fully hidden, or null. */
private fun nextVisible(pages: List<PageConfig>, from: Int, step: Int, active: Set<String>): Int? {
    var i = from + step
    while (i in pages.indices) {
        if (!isFullyHidden(pages[i], active)) return i
        i += step
    }
    return null
}

/** Where to go when the page at [index] gets hidden under us: its parent, else the nearest visible page. */
private fun exitTarget(pages: List<PageConfig>, index: Int, active: Set<String>): Int {
    val parent = pages[index].parent?.let { name -> pages.indexOfFirst { it.name.equals(name, ignoreCase = true) } }
    if (parent != null && parent >= 0 && !isFullyHidden(pages[parent], active)) return parent
    return nextVisible(pages, index, -1, active) ?: nextVisible(pages, index, 1, active) ?: 0
}

/**
 * For the pager's modifier: a swipe stops at the first and the last page that isn't fully hidden
 * (see [PageReach]) instead of going on to hidden pages at either end. Pages opened by a tile or
 * a key aren't concerned: those are jumps, not scrolls.
 */
@Composable
internal fun rememberPageReachLimit(pages: List<PageConfig>, pagerState: PagerState, activeActivityIds: Set<String>): Modifier {
    val reach = remember(pages, activeActivityIds) { PageReach.of(pages.map { isFullyHidden(it, activeActivityIds) }) }
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val currentReach by rememberUpdatedState(reach)
    // 1.6.3: only the pager's own swipe is held. A scrollable row inside a page (scene_grid
    // "layout": "row"…) sends its scroll through here too, and must keep all of it.
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    val pagerDragged by rememberUpdatedState(dragged)
    val connection =
        remember(pagerState, rtl) {
            // A finger moving left (x < 0) goes to the next pages, the other way round in RTL.
            fun forward(x: Float) = if (rtl) x else -x
            fun position() = pagerState.currentPage + pagerState.currentPageOffsetFraction
            object : NestedScrollConnection {
                /** The gesture under way is the pager's own swipe (its fling follows its drag). */
                private var pagerGesture = false

                override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                    pagerGesture = pagerDragged
                    val r = currentReach ?: return Offset.Zero
                    if (!pagerGesture) return Offset.Zero
                    val span = (pagerState.layoutInfo.pageSize + pagerState.layoutInfo.pageSpacing).toFloat()
                    val held = PageReach.held(position(), forward(available.x), span, r)
                    return Offset(if (rtl) held else -held, 0f)
                }

                override suspend fun onPreFling(available: Velocity): Velocity {
                    val r = currentReach ?: return Velocity.Zero
                    if (!pagerGesture) return Velocity.Zero
                    pagerGesture = false
                    return if (PageReach.blocksFling(position(), forward(available.x), r)) available else Velocity.Zero
                }
            }
        }
    return if (reach == null) Modifier else Modifier.nestedScroll(connection)
}
