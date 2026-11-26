package com.custom.astrion.ui

/**
 * How far a swipe may go between pages (1.6.0, MR RIZK): never past the first or the last page
 * that isn't fully hidden. Pages never shown (popup pages) at the end of the list used to be
 * swiped onto, drawn as the last page and then left: the last page seemed to repeat, and a quick
 * swipe could slip through to a hidden one. Pure, so it is unit-tested.
 */
internal object PageReach {
    /** The pages a swipe can land on, from [hidden] (one flag per page), or null when none. */
    fun of(hidden: List<Boolean>): IntRange? {
        val first = hidden.indexOfFirst { !it }
        if (first < 0) return null
        val last = hidden.indexOfLast { !it }
        // 1.6.3: no hidden page at either end, nothing to limit — the pager's own ends do it. Held
        // anyway, it also stopped a scrollable row on the first page from scrolling back.
        if (first == 0 && last == hidden.lastIndex) return null
        return first..last
    }

    /**
     * How much of a scroll of [forward] px (> 0 towards the next pages) to hold back, the pager
     * being at [position] (page + offset fraction), [span] px per page: what would go past [reach].
     */
    fun held(position: Float, forward: Float, span: Float, reach: IntRange): Float {
        if (span <= 0f || forward == 0f) return 0f
        return if (forward > 0f) {
            val room = ((reach.last - position) * span).coerceAtLeast(0f)
            (forward - room).coerceAtLeast(0f)
        } else {
            val room = ((position - reach.first) * span).coerceAtLeast(0f)
            (forward + room).coerceAtMost(0f)
        }
    }

    /** Whether a fling at [velocity] (> 0 towards the next pages) from [position] would only go past [reach]. */
    fun blocksFling(position: Float, velocity: Float, reach: IntRange): Boolean = when {
        velocity > 0f -> position >= reach.last - EDGE
        velocity < 0f -> position <= reach.first + EDGE
        else -> false
    }

    private const val EDGE = 0.001f
}
