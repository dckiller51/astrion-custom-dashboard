package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Compiled-in FALLBACK layout. At runtime DashboardLoader reads the live layout
 * from /sdcard/astrion/dashboard.json (and writes this out as the initial file
 * when none exists), and falls back to it when that file is unreadable.
 *
 * Since 1.2.9 this is a deliberately minimal starter: one page that says
 * where to build the real dashboard, and a long press on MAIN (the "Menu"
 * key) opening Settings — a fresh install used to get the original author's
 * own four-page layout, full of entities that don't exist anywhere else and
 * with no way to reach Settings (swiping down opens Activities). The
 * remote's address shows in the connection banner while Home Assistant
 * isn't set up yet (see ConnectionBanner), so the welcome text doesn't need
 * it.
 */
object DashboardConfig {
    private val homePage =
        PageConfig(
            name = "Home",
            cards =
            listOf(
                CardConfig(
                    type = "title",
                    options =
                    mapOf(
                        "title" to "Astrion",
                        "subtitle" to "Build your dashboard from a browser: open the address shown at the top " +
                            "(http://<remote-ip>:8080). Hold the Menu key for Settings."
                    )
                )
            )
        )

    /** Long press MAIN → Settings: the one action a new remote can't do without. */
    private val longHotkeys = listOf(HotkeyConfig(key = "MAIN", openOverlay = "settings"))

    /** True while the remote still runs this starter (untouched): the connection banner then
     * invites to set the remote up. A remote used without Home Assistant has its own dashboard,
     * so it never gets a permanent "not connected" banner. */
    fun isStarter(cfg: AppConfig): Boolean = cfg.pages.size == 1 &&
        cfg.pages[0].name == homePage.name &&
        cfg.pages[0].cards == homePage.cards

    val default: AppConfig
        get() =
            AppConfig(
                pages = listOf(homePage),
                startPage = 0,
                longHotkeys = longHotkeys
            )
}
