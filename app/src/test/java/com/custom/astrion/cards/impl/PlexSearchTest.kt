package com.custom.astrion.cards.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** Plex search answers (1.3.8): which hubs are kept and how each result reads. */
class PlexSearchTest {
    private val answer =
        Json.parseToJsonElement(
            """
            {"MediaContainer": {"Hub": [
              {"type": "movie", "title": "Movies", "Metadata": [
                {"key": "/library/metadata/10", "type": "movie", "title": "Dune", "year": 2021, "thumb": "/t/10"}]},
              {"type": "show", "title": "Shows", "Metadata": [
                {"key": "/library/metadata/20/children", "type": "show", "title": "Dark", "year": 2017}]},
              {"type": "episode", "title": "Episodes", "Metadata": [
                {"key": "/library/metadata/31", "type": "episode", "title": "Secrets", "grandparentTitle": "Dark",
                 "parentIndex": 1, "index": 1, "grandparentThumb": "/t/20"}]},
              {"type": "actor", "title": "Actors", "Metadata": [{"key": "/a/1", "title": "Someone"}]},
              {"type": "album", "title": "Albums", "Metadata": [{"key": "/library/metadata/40", "title": "Disc"}]},
              {"type": "season", "title": "Seasons", "Metadata": [
                {"key": "/library/metadata/21/children", "type": "season", "title": "Season 1", "parentTitle": "Dark"}]},
              {"type": "clip", "title": "Music videos", "Metadata": []}
            ]}}
            """
        ) as JsonObject

    @Test
    fun `only watchable, non-empty hubs are kept, in order`() {
        val groups = PlexApi.parseSearch(answer)
        assertEquals(listOf("movie", "show", "episode", "season"), groups.map { it.type })
    }

    @Test
    fun `each result reads like on the rows`() {
        val groups = PlexApi.parseSearch(answer).associateBy { it.type }
        val film = groups.getValue("movie").items.single()
        assertEquals("Dune" to "2021", film.title to film.subtitle)
        val show = groups.getValue("show").items.single()
        assertEquals("/library/metadata/20/children", show.key)
        assertEquals("show", show.type)
        val episode = groups.getValue("episode").items.single()
        assertEquals("Dark" to "S1E1 · Secrets", episode.title to episode.subtitle)
        assertEquals("/t/20", episode.thumb)
        val season = groups.getValue("season").items.single()
        assertEquals("Season 1" to "Dark", season.title to season.subtitle)
    }

    @Test
    fun `no hub at all is an empty answer`() {
        assertEquals(
            emptyList<PlexApi.PlexSearchGroup>(),
            PlexApi.parseSearch(Json.parseToJsonElement("""{"MediaContainer": {}}""") as JsonObject)
        )
    }
}
