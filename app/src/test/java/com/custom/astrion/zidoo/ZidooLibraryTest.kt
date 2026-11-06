package com.custom.astrion.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The library pop-up reads a Zidoo's own answers (shortened copies of a Z9X's, November 2026). */
class ZidooLibraryTest {
    private fun answer(name: String) = ZidooParse.parse(javaClass.getResource("/zidoo/$name.json")!!.readText(Charsets.UTF_8))

    @Test
    fun `a list page reads films, sagas and where playback stopped`() {
        val page = ZidooLibrary.page(answer("library-page"))!!
        assertEquals(listOf(12218L, 4173L, 5017L, 5010L), page.map { it.id })
        val dollars = page[0]
        assertEquals(ZidooKind.MOVIE, dollars.kind)
        assertTrue(dollars.is4k)
        assertTrue(dollars.inProgress)
        assertEquals(2_657_781L, dollars.positionMs)
        val gold = page[1]
        assertTrue(gold.watched)
        assertNull(gold.positionMs) // -1: never resumed
        assertFalse(gold.inProgress)
        assertEquals(2012, gold.year)
        assertNull(page[2].progress) // never started: no position at all
        assertEquals(ZidooKind.COLLECTION, page[3].kind)
        assertTrue(page[3].kind.isFolder)
    }

    @Test
    fun `series lists hold series and seasons shown on their own`() {
        val page = ZidooLibrary.page(answer("library-series"))!!
        assertEquals(listOf(ZidooKind.SEASON, ZidooKind.SERIES), page.map { it.kind })
        assertTrue(page.all { it.kind.isFolder })
    }

    @Test
    fun `search finds the series and the films`() {
        val found = ZidooLibrary.search(answer("library-search"))!!
        assertEquals(listOf("Kaamelott", "Kaamelott : Premier volet", "Kaamelott - Deuxième volet (Partie 1)"), found.map { it.name })
        assertEquals(listOf(ZidooKind.SERIES, ZidooKind.MOVIE, ZidooKind.MOVIE), found.map { it.kind })
    }

    @Test
    fun `a film is played by its video file, not by its own id`() {
        val film = answer("library-film")
        assertEquals(21046L, ZidooLibrary.fileOf(film))
        // Its only child is that file: nothing to list.
        assertEquals(emptyList<ZidooLibraryItem>(), ZidooLibrary.collection(film)!!.items)
        assertNull(ZidooLibrary.fileOf(null))
    }

    @Test
    fun `a season lists its episodes in order, each with its file`() {
        val season = ZidooLibrary.season(answer("library-season"))!!
        assertEquals("Livre IV", season.title)
        assertNotNull(season.overview)
        assertEquals(listOf(7, 23, 54), season.items.map { it.episode })
        assertEquals(listOf(31962L, 32085L, 32316L), season.items.map { it.fileId })
        assertEquals(ZidooKind.EPISODE, season.items[0].kind)
        assertEquals("Le traître", season.items[0].name)
        assertTrue(season.items[2].watched)
    }

    @Test
    fun `when a film or a season was last watched`() {
        assertEquals(1_781_127_742_365L, ZidooLibrary.lastWatchedFilm(answer("library-film")))
        // Episode 54 watched, the other two never (-1).
        assertEquals(1_793_760_321_548L, ZidooLibrary.lastWatchedSeason(answer("library-season")))
        assertNull(ZidooLibrary.lastWatchedFilm(null))
    }

    @Test
    fun `missing answers read as nothing`() {
        assertNull(ZidooLibrary.page(null))
        assertNull(ZidooLibrary.search(ZidooParse.parse("""{"status":804,"msg":"Error!!!"}""")))
        assertNull(ZidooLibrary.season(ZidooParse.parse("""{"status":803}""")))
        assertEquals(emptyList<ZidooLibraryItem>(), ZidooLibrary.page(ZidooParse.parse("""{"start":0,"count":20,"total":0,"array":[]}""")))
    }
}
