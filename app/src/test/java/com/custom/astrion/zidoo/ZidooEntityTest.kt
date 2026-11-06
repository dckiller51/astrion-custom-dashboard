package com.custom.astrion.zidoo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `browse opens the library of a player that answers`() {
        fun browses(s: ZidooState) = (ZidooEntityMapper.media(spec, s).attributes["supported_features"] as Int) and 131072 != 0
        assertTrue(browses(idle))
        assertTrue(browses(film))
        assertTrue(!browses(ZidooState()))
        assertTrue(browses(idle.copy(model = "DMP-A6")))
    }

    @Test
    fun `an Apple Music track whose cover is cached on the Eversolo gets the player's own cover call`() {
        val text = javaClass.getResource("/zidoo/eversolo-apple-music-state.json")!!.readText(Charsets.UTF_8)
        val p = ZidooParse.musicV2(ZidooParse.parse(text))!!
        assertEquals("Fight Fire with Fire (Remastered)", p.title)
        assertEquals("Ride the Lightning (Remastered)", p.album)
        assertEquals("/SystemSettings/getItemSettingIcon?iconName=1440845421&type=1", p.artUrl)
        assertTrue(p.playing)
    }

    private fun appleMusicState() = javaClass.getResource("/zidoo/eversolo-apple-music-state.json")!!.readText(Charsets.UTF_8)

    @Test
    fun `an Eversolo's input and what plays, as an audiophile reads it`() {
        val o = ZidooParse.parse(appleMusicState())
        assertEquals(EversoloInput.INTERNAL, ZidooParse.input(o)) // "XMOS-XMOS"
        assertEquals("44.1 kHz", ZidooParse.musicV2(o)!!.quality) // Apple Music: rate only
        // Its own player says more (same answer, no app hosted).
        val own = ZidooParse.parse(appleMusicState().replace("\"isOpenApp\":true", "\"isOpenApp\":false"))
        assertEquals("FLAC · 44.1 kHz · 16 bit · 913 kbps", ZidooParse.musicV2(own)!!.quality)
        assertEquals(EversoloInput.OPTICAL, EversoloInput.ofTag("SPDIF-Optical"))
        assertEquals(EversoloInput.BLUETOOTH, EversoloInput.ofTag("BT-xx"))
        assertNull(EversoloInput.ofTag(""))
    }

    @Test
    fun `an Eversolo's inputs are sources, and selecting one sends its key`() {
        val evs = idle.copy(model = "DMP-A6", input = EversoloInput.OPTICAL)
        val attrs = ZidooEntityMapper.media(spec, evs).attributes

        @Suppress("UNCHECKED_CAST")
        val sources = attrs["source_list"] as List<String>
        assertEquals(listOf("Internal player", "Bluetooth In", "USB-C In", "Coaxial In", "Optical In"), sources.take(5))
        assertEquals("Optical In", attrs["source"])
        assertEquals(listOf(ZidooStep.Key("Key.DAC.SPDIF")), ZidooServices.media("select_source", mapOf("source" to "Optical In"), evs))
        // A Zidoo has no inputs.
        @Suppress("UNCHECKED_CAST")
        val zidooSources = ZidooEntityMapper.media(spec, idle).attributes["source_list"] as List<String>?
        assertTrue(zidooSources.orEmpty().none { it == "Optical In" })
    }

    @Test
    fun `an Eversolo turns off and mutes with its own calls`() {
        val evs = idle.copy(model = "DMP-A6", muted = false)
        assertEquals(listOf(ZidooStep.Key("Key.Poweroff")), ZidooServices.media("turn_off", emptyMap(), evs))
        assertEquals(listOf(ZidooStep.Key("Key.PowerOn.Poweroff")), ZidooServices.media("turn_off", emptyMap(), idle))
        assertEquals(
            listOf(ZidooStep.Api("ZidooMusicControl/v2/setMuteVolume?isMute=1")),
            ZidooServices.media("volume_mute", mapOf("is_volume_muted" to true), evs)
        )
        // Without the asked state, the opposite of what it says.
        assertEquals(
            listOf(ZidooStep.Api("ZidooMusicControl/v2/setMuteVolume?isMute=0")),
            ZidooServices.media("volume_mute", emptyMap(), evs.copy(muted = true))
        )
        assertEquals(listOf(ZidooStep.Key("Key.Mute")), ZidooServices.media("volume_mute", emptyMap(), idle))
        assertEquals(false, ZidooEntityMapper.media(spec, evs).attributes["is_volume_muted"])
        assertEquals(false, ZidooParse.muted(ZidooParse.parse(appleMusicState())))
        assertEquals(EversoloInput.COAXIAL, EversoloInput.ofTag("RCA"))
    }

    @Test
    fun `an AirPlay stream shows what is streamed, not the player's last track`() {
        val text = javaClass.getResource("/zidoo/eversolo-airplay-state.json")!!.readText(Charsets.UTF_8)
        val p = ZidooParse.musicV2(ZidooParse.parse(text))!!
        assertEquals("Murders In the Rue Morgue", p.title)
        assertEquals("Iron Maiden", p.artist)
        assertEquals("Killers", p.album)
        assertEquals("/SystemSettings/getItemSettingIcon?iconName=538faf3eca45505b5084a2c01b08eb52.png&type=1", p.artUrl)
        assertEquals("44.1 kHz · 16 bit", p.quality)
        assertFalse(p.canSeek) // isCanSeek false: no bar to drag
        assertTrue(p.playing)
    }

    @Test
    fun `a web radio is live`() {
        val radio = idle.copy(source = ZidooSource.MUSIC, playing = true, title = "FIP", durationMs = 0)
        assertEquals(true, ZidooEntityMapper.media(spec, radio).attributes["astrion_live"])
        assertNull(ZidooEntityMapper.media(spec, radio.copy(durationMs = 200_000)).attributes["astrion_live"])
    }

    @Test
    fun `streaming app covers are made readable`() {
        assertEquals(
            "https://is1-ssl.mzstatic.com/image/thumb/Music/x/600x600bb.jpg",
            ZidooParse.coverUrl("https://is1-ssl.mzstatic.com/image/thumb/Music/x/{w}x{h}{c}.{f}")
        )
        assertEquals("https://a.b/c.jpg", ZidooParse.coverUrl("https://a.b/c.jpg"))
        assertNull(ZidooParse.coverUrl("https://a.b/motion/cover.m3u8?x=1"))
        // What an Eversolo said for Ride the Lightning in the official Apple Music: a file of its own, then its own call.
        assertNull(ZidooParse.coverUrl("/storage/emulated/0/.zcontrol/cache/phonecontrol/1440845421"))
        assertEquals(
            "/SystemSettings/getItemSettingIcon?iconName=1440845421&type=1",
            ZidooParse.coverUrl("/SystemSettings/getItemSettingIcon?iconName=1440845421&type=1")
        )
        assertNull(ZidooParse.coverUrl(null))
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
        assertEquals(
            listOf(ZidooStep.Api("ZidooMusicControl/seekTo?time=60000")),
            ZidooServices.media("media_seek", mapOf("seek_position" to 60.0), song)
        )
        assertEquals(
            listOf(ZidooStep.Api("MusicControl/v2/seekTo?time=60000")),
            ZidooServices.media("media_seek", mapOf("seek_position" to 60.0), song.copy(model = "DMP-A6"))
        )
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
        assertEquals(listOf(44L), movie!!.artIds())
        val episode =
            ZidooParse.movie(
                ZidooParse.parse(
                    """{"episode":{"id":412,"parentId":348,"aggregation":{"episodeNumber":7,"name":"Le traître"}},""" +
                        """"season":{"id":348,"parentId":189,"aggregation":{"seasonNumber":4,"tvName":"Kaamelott"}}}"""
                )
            )
        assertEquals(listOf(189L, 348L, 412L), episode!!.artIds())
        val film = ZidooParse.movie(ZidooParse.parse("""{"movie":{"id":13057,"name":"Alien"}}"""))
        assertEquals(listOf(13057L), film!!.artIds())
    }

    @Test
    fun `the official Apple Music on an Eversolo is read from everSoloPlayInfo`() {
        fun read(state: Int) = ZidooParse.musicV2(
            ZidooParse.parse(
                """{"state":$state,"position":97902,"duration":228933,"playingMusic":{"id":-32434293,"type":3,""" +
                    """"title":"2 Minutes To Midnight","artist":"Iron Maiden","albumArt":"https://a/old.jpg"},""" +
                    """"everSoloPlayInfo":{"currentPosition":97902,"duration":228933,"isOpenApp":true,"playStatus":2,""" +
                    """"everSoloPlayAudioInfo":{"albumName":"Blue Moves","albumUrl":"https://a/new.jpg",""" +
                    """"artistName":"Elton John","songName":"Sorry Seems to Be the Hardest Word"}}}"""
            )
        )!!
        assertEquals(false, read(4).playing) // paused: state 4 (its playStatus says 2)
        val p = read(3) // playing: state 3 (its playStatus says 1)
        assertEquals("Sorry Seems to Be the Hardest Word", p.title)
        assertEquals("Elton John", p.artist)
        assertEquals("Blue Moves", p.album)
        assertEquals("https://a/new.jpg", p.artUrl)
        assertEquals(true, p.playing)
        assertEquals(97902L, p.positionMs)
        assertEquals(228933L, p.durationMs)
    }

    @Test
    fun `an Eversolo is named by its model`() {
        assertEquals("Eversolo", ZidooKeys.brandOf("DMP-A6"))
        assertEquals("Zidoo", ZidooKeys.brandOf("Z9X"))
        assertEquals("Zidoo", ZidooKeys.brandOf(null))
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
