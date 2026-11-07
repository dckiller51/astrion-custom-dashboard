package com.custom.astrion.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Zidoo / Eversolo card's own calls (1.5.1). Play queues: shortened copies of an Eversolo
 * DMP-A6's (November 2026), with its own player, Eversolo's Apple Music and Apple's.
 */
class ZidooControlsTest {
    private fun text(name: String) = javaClass.getResource("/zidoo/$name.json")!!.readText(Charsets.UTF_8)

    @Test
    fun `the queue of the Eversolo's own player plays the track tapped`() {
        val queue = ZidooControls.queue(ZidooParse.parseAny(text("queue-local")))
        assertEquals(listOf("Blackened", "…And Justice for All", "Eye of the Beholder", "One"), queue.map { it.title })
        val third = queue[2]
        assertEquals(3684L, third.id)
        assertEquals(1, third.type)
        assertEquals(385_911L, third.durationMs)
        assertTrue(third.local)
        assertTrue(third.playable)
        assertNull(third.artUrl) // asked by id, like the library's
        // The call the tester ran: it played "Eye of the Beholder".
        assertEquals(
            "ZidooMusicControl/v2/playMusic?type=0&id=-1&musicId=3684&musicType=1&trackIndex=2&seekTime=-1&sort=0&isSkipPlay=0&playMode=-1",
            ZidooControls.playQueuePath(third, 2)
        )
    }

    @Test
    fun `Eversolo's Apple Music plays from its queue, with its covers`() {
        val queue = ZidooControls.queue(ZidooParse.parseAny(text("queue-apple-music-eversolo")))
        assertEquals(4, queue.size)
        val creeping = queue[3]
        assertEquals("Creeping Death (Remastered)", creeping.title)
        assertEquals(3, creeping.type)
        assertTrue(creeping.playable)
        assertFalse(creeping.local)
        assertTrue(creeping.artUrl!!.startsWith("https://is1-ssl.mzstatic.com/"))
        assertEquals(
            "ZidooMusicControl/v2/playMusic?type=0&id=-1&musicId=1197640007&musicType=3" +
                "&trackIndex=6&seekTime=-1&sort=0&isSkipPlay=0&playMode=-1",
            ZidooControls.playQueuePath(creeping, 6)
        )
    }

    @Test
    fun `Apple's Apple Music queue is only shown, without lengths nor unreadable covers`() {
        val queue = ZidooControls.queue(ZidooParse.parseAny(text("queue-apple-music-official")))
        assertEquals(listOf("Intro", "Black Eyes", "Hometown Glory"), queue.map { it.title })
        assertTrue(queue.none { it.playable })
        assertTrue(queue.all { it.durationMs == null }) // it says 0
        assertNull(queue[0].artUrl) // content://… the remote can't read
        assertTrue(queue[2].artUrl!!.startsWith("https://"))
    }

    @Test
    fun `the track playing is found by its title and artist`() {
        val queue = ZidooControls.queue(ZidooParse.parseAny(text("queue-local")))
        assertEquals(3, ZidooControls.currentIndex(queue, "One", "Metallica"))
        assertEquals(0, ZidooControls.currentIndex(queue, "blackened", null))
        assertEquals(-1, ZidooControls.currentIndex(queue, "Fade to Black", "Metallica"))
        assertEquals(-1, ZidooControls.currentIndex(queue, null, null))
    }

    @Test
    fun `subtitle and audio tracks are read, whatever list name the firmware uses`() {
        val subs =
            ZidooControls.tracks(
                ZidooParse.parse("""{"status":200,"subtitles":[{"index":0,"title":"Off"},{"index":1,"title":"Français"},{"index":2}]}""")
            )
        assertEquals(listOf(ZidooTrack(0, "Off"), ZidooTrack(1, "Français"), ZidooTrack(2, "#2")), subs)
        val audio = ZidooControls.tracks(ZidooParse.parse("""{"status":200,"audios":[{"index":3,"title":"VF DTS-HD MA 5.1"}]}"""))
        assertEquals(listOf(ZidooTrack(3, "VF DTS-HD MA 5.1")), audio)
        assertEquals(emptyList<ZidooTrack>(), ZidooControls.tracks(ZidooParse.parse("""{"status":804}""")))
        val status = ZidooParse.parse("""{"status":200,"subtitle":{"index":1,"information":"Français"},"audio":{"index":3}}""")
        assertEquals(1 to "Français", ZidooControls.currentTrack(status, "subtitle"))
        assertEquals(3 to null, ZidooControls.currentTrack(status, "audio"))
        assertEquals("ZidooVideoPlay/setSubtitle?index=1", ZidooControls.setSubtitlePath(1))
        assertEquals("ZidooVideoPlay/changevideo?type=1&tag=1", ZidooControls.chapterPath(next = true))
        assertEquals("ZidooVideoPlay/changevideo?type=1&tag=0", ZidooControls.chapterPath(next = false))
    }

    @Test
    fun `a film's subtitle and audio track come with what plays`() {
        val p =
            ZidooParse.video(
                ZidooParse.parse(
                    """{"status":200,"subtitle":{"index":1,"information":"Français"},"audio":{"index":0,"information":"VF · DTS-HD"},""" +
                        """"video":{"status":1,"title":"Film","path":"/a.mkv","duration":1000,"currentPosition":10}}"""
                )
            )!!
        assertEquals("Français", p.subtitle)
        assertEquals("VF · DTS-HD", p.audio)
    }

    @Test
    fun `an Eversolo's outputs are read, the one in use known`() {
        val o =
            ZidooControls.outputs(
                ZidooParse.parse(
                    """{"status":200,"outputIndex":1,"outputData":[{"name":"XLR","tag":"XLR","enable":true},""" +
                        """{"name":"RCA","tag":"RCA","enable":true},{"name":"USB DAC","tag":"USB","enable":false}]}"""
                )
            )
        assertEquals(listOf("XLR", "RCA", "USB DAC"), o.outputs.map { it.name })
        assertEquals(1, o.current)
        assertFalse(o.outputs[2].enabled)
        assertEquals(ZidooOutputs(emptyList()), ZidooControls.outputs(null))
        assertEquals("ZidooMusicControl/v2/setOutInputList?tag=XLR%20RCA", ZidooControls.setOutputPath("XLR RCA"))
    }

    @Test
    fun `an Eversolo says which app it hosts or which stream it gets`() {
        val airplay = ZidooParse.musicV2(ZidooParse.parse(text("eversolo-airplay-state")))!!
        assertEquals("AirPlay", airplay.appName)
        val apple = ZidooParse.musicV2(ZidooParse.parse(text("eversolo-apple-music-state")))!!
        assertEquals("Apple Music", apple.appName)
    }
}
