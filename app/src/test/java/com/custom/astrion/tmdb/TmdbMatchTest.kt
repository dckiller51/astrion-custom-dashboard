package com.custom.astrion.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmdbMatchTest {
    private fun query(vararg attrs: Pair<String, String>): TmdbQuery? {
        val m = attrs.toMap()
        return TmdbMatch.queryOf { m[it] }
    }

    @Test
    fun `a film's title from a streaming app, studio taken off`() {
        val q = query("media_title" to "Marvel Studios' Avengers: Endgame", "app_name" to "Disney+")!!
        assertEquals("Avengers: Endgame", q.title)
        assertFalse(q.tv)
        assertNull(q.year)
    }

    @Test
    fun `a series by its series title, HBO Max by its artist`() {
        val q = query("media_title" to "Winter Is Coming", "media_series_title" to "Game of Thrones")!!
        assertEquals("Game of Thrones", q.title)
        assertTrue(q.tv)
        val hbo = query("media_title" to "The Long Night", "media_artist" to "The Last of Us", "app_name" to "HBO Max")!!
        assertEquals("The Last of Us", hbo.title)
        assertTrue(hbo.tv)
    }

    @Test
    fun `an Apple TV episode's artist isn't taken for the title`() {
        val q = query("media_title" to "Star Wars : Le Réveil de la Force", "media_artist" to "Saison 3, ép. 12", "app_name" to "TV")!!
        assertEquals("Star Wars : Le Réveil de la Force", q.title)
    }

    @Test
    fun `music and empty players are left alone`() {
        assertNull(query("media_title" to "Fight Fire with Fire", "app_name" to "Apple Music"))
        assertNull(query("media_title" to "Song", "media_content_type" to "music", "media_album_name" to "Album"))
        // An Apple TV added in Astrion calls everything "music": a film without an album is looked up.
        assertEquals(
            "Le seigneur des anneaux : la communauté de l'anneau",
            query(
                "media_title" to "Le seigneur des anneaux : la communauté de l'anneau",
                "media_content_type" to "music",
                "app_name" to "Prime Video"
            )!!.title
        )
        assertNull(query("app_name" to "Netflix"))
    }

    @Test
    fun `a file's name gives its title and year`() {
        val q = TmdbMatch.clean("Gemini.Man.2019.2160p.UHD.BluRay.x265.mkv")!!
        assertEquals("Gemini Man", q.title)
        assertEquals(2019, q.year)
        val spaced = TmdbMatch.clean("Gemini Man (2019) 2160p AC3.mkv")!!
        assertEquals("Gemini Man", spaced.title)
        assertEquals(2019, spaced.year)
        assertEquals("1917", TmdbMatch.clean("1917.mkv")!!.title)
    }

    @Test
    fun `the search asks TMDB the right way`() {
        assertEquals(
            "search/multi?query=Gemini%20Man&language=fr-FR&include_adult=false",
            TmdbMatch.searchPath(TmdbQuery("Gemini Man", null, false), "fr-FR")
        )
        assertTrue(TmdbMatch.searchPath(TmdbQuery("Gemini Man", 2019, false), "fr-FR").startsWith("search/movie?"))
        assertTrue(TmdbMatch.searchPath(TmdbQuery("Lost", null, true), "en-US").startsWith("search/tv?"))
    }

    @Test
    fun `TMDB's answer, its year's result first`() {
        val body =
            """{"results":[{"media_type":"person","name":"Will Smith"},""" +
                """{"media_type":"movie","title":"Gemini Man","release_date":"1980-01-01","vote_average":5},""" +
                """{"media_type":"movie","title":"Gemini Man","overview":"Henry Brogan…","release_date":"2019-10-02",""" +
                """"vote_average":6.3,"poster_path":"/h3VRTOm9YmSnyF0aPZwWpimdN23.jpg"}]}"""
        val info = TmdbMatch.best(body, TmdbQuery("Gemini Man", 2019, false))!!
        assertEquals("Henry Brogan…", info.overview)
        assertEquals(2019, info.year)
        assertEquals(6.3, info.rating!!, 0.001)
        assertEquals("https://image.tmdb.org/t/p/w500/h3VRTOm9YmSnyF0aPZwWpimdN23.jpg", info.posterUrl)
        val tv = TmdbMatch.best("""{"results":[{"name":"Lost","first_air_date":"2004-09-22"}]}""", TmdbQuery("Lost", null, true))!!
        assertTrue(tv.tv)
        assertEquals(2004, tv.year)
        assertNull(TmdbMatch.best("""{"results":[]}""", TmdbQuery("x y", null, false)))
    }

    @Test
    fun `a season said after the series is taken off, and it's a series`() {
        val q = query("media_title" to "Ticaret - Saison 1", "app_name" to "CANAL+")!!
        assertEquals("Ticaret", q.title)
        assertTrue(q.tv)
        assertEquals("Lost", TmdbMatch.clean("Lost: Season 2")!!.title)
        assertEquals("Lost", TmdbMatch.clean("Lost S01E03")!!.title)
        assertEquals("Enquête exclusive", TmdbMatch.clean("Enquête exclusive")!!.title)
    }
}
