package com.custom.astrion.denon

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Denon / Marantz receivers (1.5.4): status, volume, sound modes, entities and services. */
class DenonProtocolTest {
    private val status =
        """<?xml version="1.0" encoding="utf-8" ?><item>
        <FriendlyName><value>Salon AVR</value></FriendlyName>
        <Power><value>ON</value></Power><ZonePower><value>ON</value></ZonePower>
        <InputFuncList><value>CD</value><value>SAT/CBL</value><value>GAME</value><value>TV AUDIO</value><value>Bluetooth</value></InputFuncList>
        <RenameSource><value><![CDATA[CD]]></value><value><![CDATA[Freebox]]></value><value><![CDATA[PS5]]></value><value>TV</value><value></value></RenameSource>
        <SourceDelete><value>DEL</value><value>USE</value><value>USE</value><value>USE</value><value>USE</value></SourceDelete>
        <InputFuncSelect><value>SAT/CBL</value></InputFuncSelect>
        <MasterVolume><value>-35.5</value></MasterVolume><Mute><value>off</value></Mute>
        <selectSurround><value>DOLBY ATMOS</value></selectSurround>
        </item>"""

    @Test
    fun `the status is read`() {
        val s = DenonProtocol.status(status)
        assertTrue(s.on)
        assertEquals("SAT/CBL", s.input)
        assertEquals(-35.5, s.volumeDb!!, 0.0)
        assertFalse(s.muted)
        assertEquals("DOLBY DIGITAL", s.soundMode)
        // CD is hidden on the receiver; renamed inputs keep their code, "TV AUDIO" / "Bluetooth" become TV / BT.
        assertEquals(
            listOf(
                DenonProtocol.Input("SAT/CBL", "Freebox"),
                DenonProtocol.Input("GAME", "PS5"),
                DenonProtocol.Input("TV", "TV"),
                DenonProtocol.Input("BT", "Bluetooth")
            ),
            s.inputs
        )
        assertEquals("AVR-X3800H", DenonProtocol.model("<Device_Info><ModelName>AVR-X3800H</ModelName></Device_Info>"))
        assertFalse(
            DenonProtocol.status("<item><Power><value>STANDBY</value></Power><MasterVolume><value>--</value></MasterVolume></item>").on
        )
    }

    @Test
    fun `volume and commands`() {
        assertEquals(0.445, DenonProtocol.level(-35.5), 1e-9)
        assertEquals("MV445", DenonProtocol.volumeCommand(0.445))
        assertEquals("MV40", DenonProtocol.volumeCommand(0.4))
        assertEquals("MV98", DenonProtocol.volumeCommand(1.0))
        assertEquals("/goform/formiPhoneAppDirect.xml?MSPURE%20DIRECT", DenonProtocol.commandPath("MSPURE DIRECT"))
        assertEquals("MNCUP", DenonProtocol.commandOf("DPAD_UP"))
        assertEquals("MSMOVIE", DenonProtocol.commandOf("msmovie"))
        assertEquals("PSDYNEQ ON", DenonProtocol.commandOf("PSDYNEQ ON"))
        assertNull(DenonProtocol.commandOf("CHANNEL_UP"))
        assertEquals("MOVIE", DenonProtocol.soundModeOf("PLII CINEMA"))
    }

    private val spec = DenonSpec("d", "media_player.denon_salon", "Salon")
    private val on =
        DenonState(reachable = true, on = true, input = "SAT/CBL", volumeDb = -35.5, inputs = DenonProtocol.status(status).inputs)

    @Test
    fun `entity and services`() {
        val m = DenonEntityMapper.media(spec, on)
        assertEquals("on", m.state)
        assertEquals("Freebox", m.attributes["source"])
        assertEquals(listOf("Freebox", "PS5", "TV", "Bluetooth"), m.attributes["source_list"])
        assertEquals(0.445, m.attributes["volume_level"] as Double, 1e-9)
        assertEquals("off", DenonEntityMapper.media(spec, on.copy(on = false)).state)
        // The main zone only: PWON would turn every zone on.
        assertEquals(listOf("ZMON"), DenonServices.media("turn_on", emptyMap(), on))
        assertEquals(listOf("ZMOFF"), DenonServices.media("turn_off", emptyMap(), on))
        assertEquals(listOf("ZMOFF"), DenonServices.remote("send_command", mapOf("command" to "POWER"), on))
        // Zone 2 on, main zone in standby: off.
        assertFalse(DenonProtocol.status("<item><Power><value>ON</value></Power><ZonePower><value>OFF</value></ZonePower></item>").on)
        assertEquals(listOf("SIGAME"), DenonServices.media("select_source", mapOf("source" to "ps5"), on))
        assertEquals(listOf("MUON"), DenonServices.media("volume_mute", mapOf("is_volume_muted" to true), on))
        assertEquals(listOf("MSPURE DIRECT"), DenonServices.media("select_sound_mode", mapOf("sound_mode" to "Pure Direct"), on))
        assertEquals(listOf("MV40"), DenonServices.media("volume_set", mapOf("volume_level" to 0.4), on))
        assertEquals(
            listOf("MVUP", "MVUP", "MUON"),
            DenonServices.remote("send_command", mapOf("command" to listOf("VOLUME_UP", "VOLUME_UP", "MUTE", "NOPE")), on)
        )
    }

    // AppCommand.xml answers of an AVC-X3700H (trimmed from denonavr's test data): recent models answer 403 on the status page.
    private val deviceInfo =
        """<Device_Info><ModelName>*AVC-X3700H</ModelName><DeviceZoneCapabilities><Zone><No>0</No></Zone>""" +
            """<ShortcutControl><EntryList><Shortcut><FuncName>Restorer</FuncName></Shortcut></EntryList></ShortcutControl>""" +
            """<InputSource><Control>1</Control><List>""" +
            """<Source><FuncName>CBL/SAT</FuncName><DefaultName>CBL/SAT</DefaultName></Source>""" +
            """<Source><FuncName>Blu-ray</FuncName><DefaultName>Blu-ray</DefaultName></Source>""" +
            """<Source><FuncName>GAME</FuncName><DefaultName>Game</DefaultName></Source>""" +
            """<Source><FuncName>Media Player</FuncName><DefaultName>Media Player</DefaultName></Source>""" +
            """</List></InputSource></DeviceZoneCapabilities>""" +
            """<DeviceZoneCapabilities><Zone><No>1</No></Zone><InputSource><List>""" +
            """<Source><FuncName>PHONO</FuncName><DefaultName>Phono</DefaultName></Source>""" +
            """</List></InputSource></DeviceZoneCapabilities></Device_Info>"""
    private val sources =
        """<?xml version="1.0" encoding="utf-8" ?><rx><cmd><functionrename>""" +
            """<list><name>CBL/SAT</name><rename>CBL/SAT         </rename></list>""" +
            """<list><name>GAME</name><rename>PS5             </rename></list>""" +
            """<list><name>Media Player</name><rename>Apple TV        </rename></list>""" +
            """</functionrename></cmd><cmd><functiondelete>""" +
            """<list><name>CBL/SAT</name><FuncName>CBL/SAT</FuncName><use>0</use></list>""" +
            """<list><name>GAME</name><FuncName>GAME</FuncName><use>1</use></list>""" +
            """</functiondelete></cmd></rx>"""
    private val state =
        """<?xml version="1.0" encoding="utf-8" ?><rx>""" +
            """<cmd><zone1>ON</zone1><zone2>OFF</zone2></cmd>""" +
            """<cmd><zone1><source>MPLAY</source></zone1><zone2><source>SOURCE</source></zone2></cmd>""" +
            """<cmd><zone1><volume>-42.5</volume><state>variable</state><limit>OFF</limit><disptype>ABSOLUTE</disptype></zone1></cmd>""" +
            """<cmd><zone1>off</zone1><zone2>off</zone2></cmd>""" +
            """<cmd><surround>Stereo </surround></cmd></rx>"""

    @Test
    fun `recent models over AppCommand_xml`() {
        assertEquals(
            """<?xml version="1.0" encoding="utf-8"?><tx><cmd id="1">GetRenameSource</cmd><cmd id="1">GetDeletedSource</cmd></tx>""",
            DenonProtocol.appCommandBody(DenonProtocol.SOURCE_COMMANDS)
        )
        assertEquals(2, DenonProtocol.appCommandBody(DenonProtocol.STATE_COMMANDS + "GetFriendlyName").split("<tx>").size - 1)
        val inputs = DenonProtocol.appInputs(deviceInfo, sources)
        // CBL/SAT hidden; zone 2's PHONO not listed; "Media Player" is MPLAY, "Blu-ray" BD.
        assertEquals(
            listOf(DenonProtocol.Input("BD", "Blu-ray"), DenonProtocol.Input("GAME", "PS5"), DenonProtocol.Input("MPLAY", "Apple TV")),
            inputs
        )
        val s = DenonProtocol.appStatus(state, inputs)!!
        assertTrue(s.on)
        assertEquals("MPLAY", s.input)
        assertEquals(-42.5, s.volumeDb!!, 0.0)
        assertFalse(s.muted)
        assertEquals("STEREO", s.soundMode)
        assertEquals("Apple TV", DenonState(reachable = true, on = true, input = s.input, inputs = inputs).inputName)
        assertNull(DenonProtocol.appStatus("<rx><cmd>x</cmd></rx>", inputs))
    }

    // An AVC-X4800H's Deviceinfo.xml (zone 0, trimmed): HEOS Music is NETWORK, GAME1 / GAME2 are kept.
    private val x4800h =
        """<Device_Info><ModelName>AVC-X4800H</ModelName><DeviceZones>3</DeviceZones>""" +
            """<DeviceZoneCapabilities><Zone><No>0</No></Zone><InputSource><Control>1</Control><List>""" +
            """<Source><IconId>4</IconId><FuncName>CBL/SAT</FuncName><DefaultName>CBL/SAT</DefaultName><RelatedFunc/></Source>""" +
            """<Source><IconId>8</IconId><FuncName>GAME1</FuncName><DefaultName>Game1</DefaultName><RelatedFunc/></Source>""" +
            """<Source><IconId>3</IconId><FuncName>TV AUDIO</FuncName><DefaultName>TV Audio</DefaultName><RelatedFunc/></Source>""" +
            """<Source><IconId>23</IconId><FuncName>NETWORK</FuncName><DefaultName>HEOS Music</DefaultName>""" +
            """<RelatedFunc>NetUsb</RelatedFunc></Source>""" +
            """</List></InputSource></DeviceZoneCapabilities>""" +
            """<DeviceZoneCapabilities><Zone><No>1</No></Zone><InputSource><List>""" +
            """<Source><FuncName>PHONO</FuncName><DefaultName>Phono</DefaultName></Source></List></InputSource>""" +
            """</DeviceZoneCapabilities></Device_Info>"""

    @Test
    fun `an AVC-X4800H`() {
        assertEquals(
            listOf(
                DenonProtocol.Input("SAT/CBL", "CBL/SAT"),
                DenonProtocol.Input("GAME1", "Game1"),
                DenonProtocol.Input("TV", "TV Audio"),
                DenonProtocol.Input("NET", "HEOS Music")
            ),
            DenonProtocol.appInputs(x4800h, null)
        )
        // Names given in Astrion (the X4800H doesn't say its own), hidden inputs left out.
        val shown = DenonProtocol.customInputs(
            DenonProtocol.appInputs(x4800h, null),
            mapOf("SAT/CBL" to " Zidoo ", "TV" to ""),
            setOf("GAME1")
        )
        assertEquals(
            listOf(
                DenonProtocol.Input("SAT/CBL", "Zidoo"),
                DenonProtocol.Input("TV", "TV Audio"),
                DenonProtocol.Input("NET", "HEOS Music")
            ),
            shown
        )
        val all = listOf(DenonProtocol.Input("CD", "CD"))
        assertEquals(all, DenonProtocol.customInputs(all, emptyMap(), setOf("CD")))
        // The page gets every input with the receiver's name; the state's input keeps its name when hidden.
        val st =
            DenonState(reachable = true, on = true, input = "GAME1", inputs = shown, receiverInputs = DenonProtocol.appInputs(x4800h, null))
        assertEquals("Game1", st.inputName)
        @Suppress("UNCHECKED_CAST")
        val listed = DenonEntityMapper.media(spec, st).attributes["astrion_inputs"] as List<Map<String, String>>
        assertEquals(mapOf("code" to "GAME1", "name" to "Game1"), listed[1])
        assertEquals(listOf("Zidoo", "TV Audio", "HEOS Music"), DenonEntityMapper.media(spec, st).attributes["source_list"])
        // Answers whose <cmd> carry an id are read too.
        assertEquals(listOf("ON", "x"), DenonProtocol.cmdBlocks("""<rx><cmd id="1">ON</cmd><cmd>x</cmd></rx>"""))
    }
}
