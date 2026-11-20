package com.custom.astrion.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue

/**
 * Where the page's cards may go down to, in window pixels (1.5.8): the bottom of the page's
 * scrolling area, inside its padding — above the page dots and any card pinned at the bottom, on
 * the screen or in a popup. A card that fills the page (the media remote, the Apple TV remote)
 * ends there, instead of at a guess of the dots' height (cards stopping a little past the page,
 * cut at the bottom: Zavie's screenshots).
 */
class PageBounds {
    /** 0 until the page is laid out. */
    var bottomPx by mutableFloatStateOf(0f)
}

/** The page the card is drawn on; null outside a page. */
val LocalPageBounds = compositionLocalOf<PageBounds?> { null }
