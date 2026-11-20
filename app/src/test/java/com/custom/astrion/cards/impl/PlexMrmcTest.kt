package com.custom.astrion.cards.impl

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlexMrmcTest {
    @Test
    fun partKeyIsTheFilmsFirstFile() {
        val body =
            """{"MediaContainer":{"Metadata":[{"type":"movie","Media":[{"Part":[{"key":"/library/parts/5189/1792789581/file.mkv"}]}]}]}}"""
        assertEquals("/library/parts/5189/1792789581/file.mkv", PlexMrmc.partKey(body))
    }

    @Test
    fun aSeriesHasNoFile() {
        assertNull(PlexMrmc.partKey("""{"MediaContainer":{"Metadata":[{"type":"show"}]}}"""))
        assertNull(PlexMrmc.partKey("<html>"))
    }

    @Test
    fun fileUrlCarriesTheToken() {
        assertEquals(
            "http://192.168.2.10:32400/library/parts/1/2/file.mkv?X-Plex-Token=a%2Bb",
            PlexMrmc.fileUrl("http://192.168.2.10:32400/", "/library/parts/1/2/file.mkv", "a+b")
        )
    }

    @Test
    fun itemPathFromTheCardsKeys() {
        assertEquals("/library/metadata/2264", PlexMrmc.itemPath("/library/metadata/2264"))
        assertEquals("/library/metadata/2264", PlexMrmc.itemPath("2264"))
        assertEquals("/library/metadata/30", PlexMrmc.itemPath("/library/metadata/30/children"))
    }

    @Test
    fun jsonRpcAnswers() {
        assertTrue(PlexMrmc.isPong("""{"id":1,"jsonrpc":"2.0","result":"pong"}"""))
        assertTrue(PlexMrmc.isOk("""{"id":1,"jsonrpc":"2.0","result":"OK"}"""))
        assertFalse(PlexMrmc.isOk("""{"id":1,"jsonrpc":"2.0","error":{"code":-32602}}"""))
        assertEquals("transient-1", PlexMrmc.delegatedToken("""{"MediaContainer":{"size":0,"token":"transient-1"}}"""))
    }

    @Test
    fun openAsksForTheFile() {
        assertEquals(
            """{"jsonrpc":"2.0","method":"Player.Open","params":{"item":{"file":"http://s/f.mkv?X-Plex-Token=t"}},"id":1}""",
            PlexMrmc.open("http://s/f.mkv?X-Plex-Token=t")
        )
    }

    @Test
    fun rivuletLinks() {
        assertEquals("rivulet://play/2264", PlexRivulet.link("/library/metadata/2264"))
        assertEquals("rivulet://play/2264", PlexRivulet.link("2264"))
        assertEquals("rivulet://detail/30", PlexRivulet.link("/library/metadata/30/children"))
        assertNull(PlexRivulet.link("/library/metadata/a b"))
    }
}
