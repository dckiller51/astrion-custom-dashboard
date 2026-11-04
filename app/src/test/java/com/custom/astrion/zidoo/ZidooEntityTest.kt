package com.custom.astrion.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** How a Zidoo added in Astrion looks to cards and Activities, what their calls send, and how its answers read. */
class ZidooEntityTest {
    private val spec = ZidooSpec("80a036112233", "media_player.zidoo_z9x", "Z9X Pro", "80:a0:36:11:22:33")
    private val idle =
        ZidooState(reachable = true, model = "Z9X Pro", apps = mapOf("Netflix" to "com.netflix.mediaclient", "Kodi" to "org.xbmc.kodi"))
    private val film =
        idle.copy(source = ZidooSource.VIDEO, playing = true, title = "Dune", durationMs = 9_000_000, positionMs = 60_000, positionAt = 0)

    @Test
    fun `entities look like Home Assistant's media player and remote`() {
        assertEquals("off", ZidooEntityMapper.stateOf(ZidooState()))
        assertEquals("on", ZidooEntityMapper.stateOf(idle))
        assertEquals("playing", ZidooEntityMapper.stateOf(film))
        assertEquals("paused", ZidooEntityMapper.stateOf(film.copy(playing = false)))
        val e = ZidooEntityMapper.media(spec, film)
        assertEquals("Dune", e.attributes["media_title"])
        assertEquals(9000, e.attributes["media_duration"])
        assertEquals(60, e.attributes["media_position"])
        assertEquals(listOf("Kodi", "Netflix"), e.attributes["source_list"])
        assertEquals("remote.zidoo_z9x", ZidooEntityMapper.remote(spec, film).entityId)
        assertNull(ZidooEntityMapper.media(spec, idle).attributes["media_title"])
    }

    @Test
    fun `android key names of the TV remote card reach the player`() {
        assertEquals("Key.Up", ZidooKeys.of("DPAD_UP"))
        assertEquals("Key.Ok", ZidooKeys.of("DPAD_CENTER"))
        assertEquals("Key.Ok", ZidooKeys.of("ok"))
        assertEquals("Key.Mute", ZidooKeys.of("MUTE"))
        assertEquals("Key.Menu", ZidooKeys.of("MENU"))
        assertEquals("Key.Info", ZidooKeys.of("INFO"))
        assertEquals("Key.Menu", ZidooKeys.of("Key.Menu"))
        assertEquals("Key.Number_7", ZidooKeys.of("7"))
        assertEquals("Key.PopMenu", ZidooKeys.of("Key.PopMenu"))
        assertNull(ZidooKeys.of("NOT_A_KEY"))
        val steps = ZidooServices.remote("send_command", mapOf("command" to listOf("DPAD_UP", "BACK"), "num_repeats" to 2.0), idle)
        assertEquals(listOf("Key.Up", "Key.Back", "Key.Up", "Key.Back"), steps!!.map { (it as ZidooStep.Key).key })
    }

    @Test
    fun `power wakes an unreachable player and only turns off a reachable one`() {
        assertEquals(listOf(ZidooStep.Wake, ZidooStep.Key("Key.PowerOn")), ZidooServices.media("turn_on", emptyMap(), ZidooState()))
        assertEquals(emptyList<ZidooStep>(), ZidooServices.media("turn_on", emptyMap(), idle))
        assertEquals(listOf(ZidooStep.Key("Key.PowerOn.Poweroff")), ZidooServices.media("turn_off", emptyMap(), idle))
        assertEquals(emptyList<ZidooStep>(), ZidooServices.media("turn_off", emptyMap(), ZidooState()))
        assertEquals(listOf(ZidooStep.Key("Key.PowerOn.Poweroff")), ZidooServices.remote("send_command", mapOf("command" to "POWER"), idle))
    }

    @Test
    fun `transport goes to the player that plays`() {
        assertEquals(
            listOf(ZidooStep.Api("ZidooVideoPlay/changeStatus?status=0")),
            ZidooServices.media("media_play_pause", emptyMap(), film)
        )
        assertEquals(
            listOf(ZidooStep.Api("ZidooVideoPlay/seekTo?positon=90000")),
            ZidooServices.media("media_seek", mapOf("seek_position" to 90.0), film)
        )
        val song = idle.copy(source = ZidooSource.MUSIC, playing = true)
        assertEquals(listOf(ZidooStep.Api("MusicControl/v2/playOrPause")), ZidooServices.media("media_pause", emptyMap(), song))
        assertEquals(emptyList<ZidooStep>(), ZidooServices.media("media_play", emptyMap(), song))
        assertEquals(listOf(ZidooStep.Key("Key.MediaNext")), ZidooServices.media("media_next_track", emptyMap(), idle))
        assertNull(ZidooServices.media("media_seek", mapOf("seek_position" to 5.0), idle))
        assertEquals(listOf(ZidooStep.Launch("Netflix")), ZidooServices.media("select_source", mapOf("source" to "Netflix"), idle))
        assertNull(ZidooServices.media("shuffle_set", emptyMap(), idle))
    }

    @Test
    fun `control API answers are read`() {
        val model = ZidooParse.model(
            ZidooParse.parse("""{"status":200,"model":"Z9X Pro","firmware":"6.4.42","net_mac":"80-A0-36-11-22-33","wif_mac":""}""")
        )
        assertEquals("80:a0:36:11:22:33", model!!.mac)
        assertNull(ZidooParse.model(ZidooParse.parse("""{"status":804}""")))
        val video =
            ZidooParse.video(
                ZidooParse.parse(
                    """{"status":200,"video":{"status":1,"title":"Dune.mkv","path":"/mnt/a/Dune.mkv",""" +
                        """"duration":9000000,"currentPosition":60000}}"""
                )
            )
        assertTrue(video!!.playing)
        assertEquals(60_000L, video.positionMs)
        assertNull(ZidooParse.video(ZidooParse.parse("""{"status":806}""")))
        val music =
            ZidooParse.music(
                ZidooParse.parse(
                    """{"status":200,"isPlay":true,"music":{"id":12,"type":0,"title":"Song","artist":"Band"},""" +
                        """"state":{"playing":false,"position":1000,"duration":200000}}"""
                )
            )
        assertEquals(false, music!!.playing)
        assertEquals("Band", music.artist)
        val apps =
            ZidooParse.apps(
                ZidooParse.parse(
                    """{"status":200,"apps":[{"label":"Kodi","packageName":"org.xbmc.kodi","isCanOpen":true},""" +
                        """{"label":"Service","packageName":"x.y","isCanOpen":false}]}"""
                )
            )
        assertEquals(mapOf("Kodi" to "org.xbmc.kodi"), apps)
        val movie =
            ZidooParse.movie(
                ZidooParse.parse(
                    """{"episode":{"id":44,"aggregation":{"episodeNumber":3,"name":"Pilot"}},""" +
                        """"season":{"aggregation":{"seasonNumber":1,"tvName":"Show"}}}"""
                )
            )
        assertEquals(ZidooMovie(44, "Pilot", "Show", 1, 3), movie)
    }

    @Test
    fun `file paths are sent the way the player reads them`() {
        assertEquals(
            "/mnt/smb/192.168.1.66%23video/S%C3%A9ries/Kaamelott%20%282005%29/Season%204/Kaamelott%20-%20S04E86.mkv",
            ZidooParse.query("/mnt/smb/192.168.1.66#video/Séries/Kaamelott (2005)/Season 4/Kaamelott - S04E86.mkv")
        )
        assertEquals("Key.PowerOn.Poweroff", ZidooParse.query("Key.PowerOn.Poweroff"))
    }

    @Test
    fun `an Eversolo's streaming app is read with its cover`() {
        val p =
            ZidooParse.musicV2(
                ZidooParse.parse(
                    """{"state":3,"position":1872,"duration":417000,"playingMusic":{"id":-562332526,"type":3,""" +
                        """"title":"If Darkness Had a Son","artist":"Metallica","album":"72 Seasons",""" +
                        """"albumArt":"https://is1-ssl.mzstatic.com/a/800x800bb.jpg"}}"""
                )
            )
        assertTrue(p!!.playing)
        assertEquals("If Darkness Had a Son", p.title)
        assertEquals(417_000L, p.durationMs)
        assertEquals("https://is1-ssl.mzstatic.com/a/800x800bb.jpg", p.artUrl)
        assertNull(ZidooParse.musicV2(ZidooParse.parse("""{"state":0}""")))
        assertNull(ZidooParse.musicV2(ZidooParse.parse("""{"status":404}""")))
    }

    @Test
    fun `the device's apps worth a button leave its utilities out`() {
        val apps = mapOf("Alimentation" to "com.zidoo.custom.power", "ZDMC" to "com.zidoo.zdmc", "Home Theater 5.0" to "com.zidoo.poster")
        assertEquals(
            listOf(mapOf("name" to "Home Theater 5.0", "app" to "com.zidoo.poster"), mapOf("name" to "ZDMC", "app" to "com.zidoo.zdmc")),
            ZidooEntityMapper.media(spec, idle.copy(apps = apps)).attributes["astrion_apps"]
        )
    }
}
