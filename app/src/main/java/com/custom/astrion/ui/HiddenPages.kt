package com.custom.astrion.ui

import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.custom.astrion.config.PageConfig

/** A page set to `"hideMode": "full"` whose Activity isn't running right now. */
internal fun isFullyHidden(page: PageConfig?, activeActivityIds: Set<String>): Boolean {
    val activity = page?.hiddenUnlessActivity ?: return false
    return page.hideMode == "full" && activity !in activeActivityIds
}

/**
 * `"hideMode": "full"` on a page with `hiddenUnlessActivity`:
 *
 *  - A **swipe** that lands on such a page while its Activity is off carries on to the next
 *    visible page in the same direction (or goes back where it came from if there is none).
 *    Jumps from a tile, hotkey, dot or Home Assistant are left alone — that's how "start the
 *    Activity + open its page" works, since the Activity only counts as running once all its
 *    commands are sent, a moment *after* the jump.
 *  - If the Activity **stops** while its page is on screen, the remote leaves the page: to its
 *    `parent` if it has one, else the nearest visible page before it.
 *
 * The pager itself still holds every page (see PageConfig.hiddenUnlessActivity for why).
 */
@Composable
internal fun HiddenPageSkipEffect(pages: List<PageConfig>, pagerState: PagerState, activeActivityIds: Set<String>) {
    if (pages.none { it.hideMode == "full" && it.hiddenUnlessActivity != null }) return
    val dragged by pagerState.interactionSource.collectIsDraggedAsState()
    var draggedSinceSettle by remember { mutableStateOf(false) }
    var lastSettled by remember { mutableIntStateOf(pagerState.settledPage) }
    LaunchedEffect(dragged) { if (dragged) draggedSinceSettle = true }

    LaunchedEffect(pagerState.settledPage) {
        val page = pagerState.settledPage
        if (draggedSinceSettle && isFullyHidden(pages.getOrNull(page), activeActivityIds)) {
            val step = if (page >= lastSettled) 1 else -1
            pagerState.scrollToPage(nextVisible(pages, page, step, activeActivityIds) ?: lastSettled)
            return@LaunchedEffect // runs again for the page we just moved to
        }
        draggedSinceSettle = false
        lastSettled = page
    }

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
