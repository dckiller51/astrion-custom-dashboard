package com.custom.astrion.samsung

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Samsung TVs (1.5.3): what the TV says, keys, entities and services. */
class SamsungProtocolTest {
    @Test
    fun `the TV's info is read`() {
        val info =
            SamsungProtocol.info(
                SamsungProtocol.parse(
                    """{"device":{"PowerState":"standby","name":"[TV] Samsung Q80 &amp; Co","modelName":"QE55Q80TATXXC",""" +
                        """"wifiMac":"A4:30:7A:01:02:03","TokenAuthSupport":"true","FrameTVSupport":"false"},"type":"Samsung SmartTV"}"""
                )
            )!!
        assertEquals("[TV] Samsung Q80 & Co", info.name)
        assertEquals("QE55Q80TATXXC", info.model)
        assertEquals("a4:30:7a:01:02:03", info.mac)
        assertEquals(false, info.powerOn)
        assertTrue(info.tokens)
        assertFalse(info.frame)
        assertNull(SamsungProtocol.info(SamsungProtocol.parse("""{"device":{"modelName":"UE40"}}"""))!!.powerOn) // 2016–2017
        assertTrue(SamsungProtocol.appVisible(SamsungProtocol.parse("""{"id":"3201907018807","running":true,"visible":true}""")))
    }

    @Test
    fun `the remote channel and its messages`() {
        assertEquals(
            "wss://192.168.1.50:8002/api/v2/channels/samsung.remote.control?name=QXN0cmlvbg==&token=12345678",
            SamsungProtocol.remoteUrl("192.168.1.50", "12345678")
        )
        assertEquals(
            "ws://tv:8001/api/v2/channels/samsung.remote.control?name=QXN0cmlvbg==",
            SamsungProtocol.remoteUrl("tv", null, tls = false)
        )
        val connect = SamsungProtocol.event("""{"data":{"clients":[],"id":"x","token":"87654321"},"event":"ms.channel.connect"}""")!!
        assertEquals("87654321", SamsungProtocol.tokenOf(connect))
        assertEquals(
            """{"method":"ms.remote.control","params":{"Cmd":"Click","DataOfCmd":"KEY_UP",""" +
                """"Option":"false","TypeOfRemote":"SendRemoteKey"}}""",
            SamsungProtocol.key("KEY_UP")
        )
        assertTrue(SamsungProtocol.launch("11101200001", 4).contains(""""action_type":"NATIVE_LAUNCH""""))
        val apps =
            SamsungProtocol.apps(
                SamsungProtocol.event(
                    """{"data":{"data":[{"appId":"3201907018807","app_type":2,"name":"Netflix","icon":"/opt/netflix.png"},""" +
                        """{"appId":"111299001912","app_type":2,"name":"YouTube"}]},"event":"ed.installedApp.get"}"""
                )!!
            )!!
        assertEquals(listOf("Netflix", "YouTube"), apps.map { it.name })
        assertEquals("/opt/netflix.png", apps[0].iconPath)
        val icon = SamsungProtocol.icon(
            SamsungProtocol.event("""{"data":{"imageBase64":"data:image/png;base64,iVBORw=="},"event":"ed.apps.icon"}""")!!
        )!!
        assertEquals(0x89.toByte(), icon[0])
    }

    @Test
    fun `keys and volume`() {
        assertEquals("KEY_UP", SamsungKeys.of("DPAD_UP"))
        assertEquals("KEY_RETURN", SamsungKeys.of("BACK"))
        assertEquals("KEY_CYAN", SamsungKeys.of("PROG_BLUE"))
        assertEquals("KEY_7", SamsungKeys.of("7"))
        assertEquals("KEY_AMBIENT", SamsungKeys.of("key_ambient"))
        assertNull(SamsungKeys.of("NOT_A_KEY"))
        val answer =
            """<s:Envelope><s:Body><u:GetVolumeResponse><CurrentVolume>17</CurrentVolume>""" +
                """</u:GetVolumeResponse></s:Body></s:Envelope>"""
        assertEquals("17", SamsungProtocol.soapValue(answer, "CurrentVolume"))
        assertTrue(SamsungProtocol.setVolumeBody(150).contains("<DesiredVolume>100</DesiredVolume>"))
    }

    private val spec = SamsungSpec("s", "media_player.samsung_salon", "Salon", mac = "a4:30:7a:01:02:03")
    private val netflix = SamsungProtocol.App("3201907018807", "Netflix", 2, "/opt/netflix.png")
    private val on = SamsungState(reachable = true, powerOn = true, connected = true, volume = 17, muted = false, apps = listOf(netflix))

    @Test
    fun `the media player`() {
        val m = SamsungEntityMapper.media(spec, on.copy(lastApp = netflix.id, appVisible = true))
        assertEquals("on", m.state)
        assertEquals(listOf("TV", "HDMI", "Netflix"), m.attributes["source_list"])
        assertEquals("Netflix", m.attributes["source"])
        assertEquals(0.17, m.attributes["volume_level"])
        assertTrue((m.attributes["entity_picture"] as String).contains("/samsung-icon/s?id=3201907018807"))
        assertEquals("off", SamsungEntityMapper.media(spec, on.copy(powerOn = false)).state) // standby
        assertEquals("on", SamsungEntityMapper.media(spec, on.copy(powerOn = null)).state) // too old to say
        assertNull(SamsungEntityMapper.media(spec, on).attributes["source"])
    }

    @Test
    fun `services become steps`() {
        assertEquals(listOf(SamsungStep.Wake), SamsungServices.media("turn_on", emptyMap(), SamsungState()))
        assertEquals(listOf(SamsungStep.Key("KEY_POWER")), SamsungServices.media("turn_on", emptyMap(), on.copy(powerOn = false)))
        assertEquals(emptyList<SamsungStep>(), SamsungServices.media("turn_on", emptyMap(), on))
        assertEquals(listOf(SamsungStep.Key("KEY_POWER")), SamsungServices.media("turn_off", emptyMap(), on))
        assertEquals(
            listOf(SamsungStep.Hold("KEY_POWER", SamsungServices.FRAME_OFF_MS)),
            SamsungServices.media("turn_off", emptyMap(), on.copy(frame = true))
        )
        assertEquals(listOf(SamsungStep.Volume(40)), SamsungServices.media("volume_set", mapOf("volume_level" to 0.4), on))
        assertEquals(listOf(SamsungStep.Mute(true)), SamsungServices.media("volume_mute", mapOf("is_volume_muted" to true), on))
        assertEquals(listOf(SamsungStep.Key("KEY_HDMI")), SamsungServices.media("select_source", mapOf("source" to "hdmi"), on))
        assertEquals(listOf(SamsungStep.Launch(netflix)), SamsungServices.media("select_source", mapOf("source" to "Netflix"), on))
        assertEquals(listOf(SamsungStep.Key("KEY_CHUP")), SamsungServices.media("media_next_track", emptyMap(), on))
        // An Activity's "app" can be a key: HOME brings the home screen up.
        assertEquals(listOf(SamsungStep.Key("KEY_HOME")), SamsungServices.media("select_source", mapOf("source" to "HOME"), on))
        assertEquals(listOf(SamsungStep.Key("KEY_SOURCE")), SamsungServices.media("select_source", mapOf("source" to "KEY_SOURCE"), on))
        // Not listed by the TV: a known app by its name, any other by its id, both over REST.
        assertEquals(
            listOf(SamsungStep.Launch(SamsungProtocol.App("111299001912", "YouTube", SamsungProtocol.REST_APP, null))),
            SamsungServices.media("select_source", mapOf("source" to "youtube"), on)
        )
        assertEquals(
            listOf(SamsungStep.Launch(SamsungProtocol.App("3201606009684", "3201606009684", SamsungProtocol.REST_APP, null))),
            SamsungServices.media("play_media", mapOf("media_content_id" to "3201606009684"), on)
        )
        val rest = SamsungProtocol.restApp(
            SamsungProtocol.parse("""{"id":"3201907018807","name":"Netflix","running":false,"visible":false}"""),
            "3201907018807",
            "x"
        )
        assertEquals("Netflix", rest!!.name)
        assertNull(SamsungProtocol.restApp(SamsungProtocol.parse("""{"code":404,"message":"Not found"}"""), "1", "x"))
        assertEquals(
            listOf(SamsungStep.Key("KEY_UP"), SamsungStep.Key("KEY_ENTER"), SamsungStep.Key("KEY_UP"), SamsungStep.Key("KEY_ENTER")),
            SamsungServices.remote("send_command", mapOf("command" to listOf("DPAD_UP", "OK", "NOPE"), "num_repeats" to 2), on)
        )
    }
}
