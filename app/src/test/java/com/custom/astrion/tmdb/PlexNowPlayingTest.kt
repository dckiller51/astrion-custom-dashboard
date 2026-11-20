package com.custom.astrion.tmdb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlexNowPlayingTest {
    private val body =
        """
        {"MediaContainer":{"size":3,"Metadata":[
          {"type":"movie","title":"Le Fabuleux Destin d'Amélie Poulain","originalTitle":"Amélie","year":2001,
           "summary":"Amélie, une jeune serveuse…","audienceRating":8.3,"thumb":"/library/metadata/12/thumb/1"},
          {"type":"episode","title":"Pilote","grandparentTitle":"Immeuble partagé","parentYear":2024,
           "summary":"Premier épisode.","thumb":"/library/metadata/40/thumb/2","grandparentThumb":"/library/metadata/30/thumb/3"},
          {"type":"track","title":"Clair de lune","grandparentTitle":"Debussy"}
        ]}}
        """.trimIndent()

    private val sessions = PlexNowPlaying.parse(body)

    @Test
    fun parseKeepsFilmsAndEpisodes() {
        assertEquals(2, sessions.size)
        val ep = sessions[1]
        assertEquals("Immeuble partagé", ep.series)
        assertEquals(2024, ep.year)
        assertEquals("/library/metadata/30/thumb/3", ep.thumb)
    }

    @Test
    fun matchesTitleWithoutCaseOrAccents() {
        val q = TmdbQuery("le fabuleux destin d amelie poulain", null, false)
        assertEquals("Amélie, une jeune serveuse…", PlexNowPlaying.match(sessions, q, false)?.summary)
        // Its original title too.
        assertEquals(2001, PlexNowPlaying.match(sessions, TmdbQuery("Amelie", null, false), false)?.year)
    }

    @Test
    fun matchesAnEpisodeOnItsSeries() {
        // "Immeuble partagé - Saison 2" from the Apple TV, cleaned by TmdbMatch.
        val q = TmdbMatch.clean("Immeuble partagé - Saison 2")!!
        val info = PlexNowPlaying.infoOf(PlexNowPlaying.match(sessions, q, false)!!) { "art:$it" }
        assertEquals("Immeuble partagé", info.title)
        assertEquals("art:/library/metadata/30/thumb/3", info.posterUrl)
        assertEquals(TmdbInfo.PLEX, info.source)
    }

    @Test
    fun noMatchNoGuessUnlessThePlayerIsPlexWithOneSession() {
        val q = TmdbQuery("Journal 13h00", null, false)
        assertNull(PlexNowPlaying.match(sessions, q, true))
        val one = sessions.take(1)
        assertNull(PlexNowPlaying.match(one, q, false))
        assertEquals(one[0], PlexNowPlaying.match(one, q, true))
    }

    @Test
    fun badAnswersGiveNothing() {
        assertEquals(emptyList<PlexSession>(), PlexNowPlaying.parse("<html>"))
        assertEquals(emptyList<PlexSession>(), PlexNowPlaying.parse("{\"MediaContainer\":{\"size\":0}}"))
    }

    @Test
    fun sourceDefaultsToAuto() {
        assertEquals(MediaInfoSource.AUTO, MediaInfoSource.of(null))
        assertEquals(MediaInfoSource.PLEX, MediaInfoSource.of("plex"))
        assertEquals(MediaInfoSource.AUTO, MediaInfoSource.of("other"))
    }
}
