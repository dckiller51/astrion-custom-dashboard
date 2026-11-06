package com.custom.astrion.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** An Eversolo's music library, read from its own answers (shortened copies of a DMP-A6's, November 2026). */
class ZidooMusicLibraryTest {
    private fun text(name: String) = javaClass.getResource("/zidoo/$name.json")!!.readText(Charsets.UTF_8)

    @Test
    fun `albums, artists and playlists are read`() {
        val albums = ZidooMusicLibrary.albums(ZidooParse.parse(text("music-albums")))!!
        assertEquals(listOf(1463L, 1213L, 869L), albums.map { it.id })
        assertEquals("Bal-Sagoth", albums[0].artist)
        assertEquals("1995", albums[0].year)
        val artists = ZidooMusicLibrary.artists(ZidooParse.parse(text("music-artists")))!!
        assertEquals("À la recherche de la Nouvelle Star", artists[0].name)
        // A bare list, not an object.
        val playlists = ZidooMusicLibrary.playlists(ZidooParse.parseAny(text("music-playlists")))!!
        assertEquals(listOf(MusicPlaylist(1, "favoris")), playlists)
    }

    @Test
    fun `an album's tracks come in order and play from the one tapped`() {
        val tracks = ZidooMusicLibrary.tracks(ZidooParse.parse(text("music-album-tracks")), MusicSource.ALBUM)!!
        assertEquals(listOf("Fight Fire With Fire", "Ride the Lightning", "For Whom the Bell Tolls"), tracks.map { it.title })
        assertEquals(287_116L, tracks[0].durationMs)
        // The call the tester ran: the album from its first track.
        assertEquals(
            "type=4&id=1060&musicId=3721&music_type=0&trackIndex=1&sort=0",
            ZidooMusicLibrary.playQuery(MusicSource.ALBUM, 1060, tracks[0], 0)
        )
    }

    @Test
    fun `missing answers read as nothing`() {
        assertNull(ZidooMusicLibrary.albums(ZidooParse.parse("""{"status":804,"msg":"Error!!!"}""")))
        assertNull(ZidooMusicLibrary.playlists(null))
        assertEquals(emptyList<MusicTrack>(), ZidooMusicLibrary.tracks(ZidooParse.parse("""{"array":[]}"""), MusicSource.ARTIST))
    }
}
