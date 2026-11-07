package com.custom.astrion.sky

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Sky boxes (1.5.3): keys, what a Sky Q says, entities and services. */
class SkyProtocolTest {
    @Test
    fun `keys are Sky's codes, Android names included`() {
        assertEquals(listOf<Byte>(4, 1, 0, 0, 0, 0, (224 + 1).toByte(), 0), SkyProtocol.keyBytes(16).toList()) // up
        assertEquals(listOf<Byte>(4, 1, 0, 0, 0, 0, (224 + 15).toByte(), 1), SkyProtocol.keyBytes(241).toList()) // sky
        assertEquals("up", SkyProtocol.keyOf("DPAD_UP"))
        assertEquals("select", SkyProtocol.keyOf("DPAD_CENTER"))
        assertEquals("backup", SkyProtocol.keyOf("BACK"))
        assertEquals("tvguide", SkyProtocol.keyOf("tvguide"))
        assertEquals("5", SkyProtocol.keyOf("5"))
        assertNull(SkyProtocol.keyOf("VOLUME_UP")) // a Sky box has no volume over the network
    }

    @Test
    fun `what a Sky Q says is read`() {
        assertTrue(SkyProtocol.standby(SkyProtocol.parse("""{"activeStandby":true,"deviceType":"GATEWAYSTB"}""")!!))
        val info = SkyProtocol.deviceInfo(
            SkyProtocol.parse("""{"hardwareName":"Falcon","modelNumber":"Q200","countryCode":"GBR","bouquet":"4101","subbouquet":"1"}""")!!
        )
        assertEquals("Falcon", info.model)
        assertEquals("services/4101/1", SkyProtocol.channelsPath(info.bouquet!!, info.subBouquet!!))
        val channels =
            SkyProtocol.channels(
                SkyProtocol.parse(
                    """{"services":[{"c":"101","t":"BBC One","sid":"2076","sf":"hd"},{"c":"401","t":"Sky Sports","sid":"4002"}]}"""
                )
            )
        assertEquals(listOf("101 BBC One", "401 Sky Sports"), channels.map { it.label })
        assertEquals(listOf("4002"), SkyProtocol.favourites(SkyProtocol.parse("""{"favourites":[{"lcn":1,"sid":"4002"}]}""")))
    }

    @Test
    fun `UPnP answers are read`() {
        val description =
            """<root><device><deviceType>urn:schemas-nds-com:device:SkyControl:2</deviceType><serviceList>""" +
                """<service><serviceId>urn:nds-com:serviceId:SkyBook</serviceId><controlURL>/book</controlURL></service>""" +
                """<service><serviceId>urn:nds-com:serviceId:SkyPlay</serviceId>""" +
                """<controlURL>/444D5276-3247-536B-7951-0019fbb6e5a4SkyPlay</controlURL></service>""" +
                """</serviceList></device></root>"""
        assertEquals("/444D5276-3247-536B-7951-0019fbb6e5a4SkyPlay", SkyProtocol.controlPath(description))
        assertNull(SkyProtocol.controlPath(description.replace("SkyControl", "SkyServe")))
        val transport =
            """<s:Envelope><s:Body><u:GetTransportInfoResponse><CurrentTransportState>PLAYING</CurrentTransportState>""" +
                """</u:GetTransportInfoResponse></s:Body></s:Envelope>"""
        assertEquals("PLAYING", SkyProtocol.soapValue(transport, "CurrentTransportState"))
        assertEquals(SkyProtocol.Media.Live("2076"), SkyProtocol.mediaOf("xsi://81C"))
        assertEquals(SkyProtocol.Media.Recording("P12345"), SkyProtocol.mediaOf("file://pvr/12345"))
        assertEquals(SkyProtocol.Media.None, SkyProtocol.mediaOf(null))
    }

    @Test
    fun `the guide gives what's on`() {
        val schedule =
            SkyProtocol.schedule(
                SkyProtocol.parse(
                    """{"schedule":[{"sid":"2076","events":[{"t":"News","st":1000,"d":1800,"sy":"The news."},""" +
                        """{"t":"EastEnders","st":2800,"d":1800,"seasonnumber":40,"episodenumber":12,"programmeuuid":"abc-1"}]}]}"""
                )
            )
        assertEquals("EastEnders", SkyProtocol.onNow(schedule, 3000)!!.title)
        assertNull(SkyProtocol.onNow(schedule, 9000))
        assertEquals("http://atlantis.epgsky.com/as/schedule/19700101/2076", SkyProtocol.scheduleUrl("2076", SkyProtocol.today(3000)))
        assertEquals(
            "https://imageservice.sky.com/logo/skychb_2076bbcone/600/600?territory=GB&provider=SKY&proposition=SKYQ",
            SkyProtocol.channelLogoUrl("2076", "BBC One", "GBR")
        )
        assertTrue(SkyProtocol.programmeImageUrl("abc-1", "DEU").startsWith("https://de.imageservice.sky.com/pd-image/abc-1/"))
    }

    private val channels = listOf(SkyProtocol.Channel("101", "BBC One", "2076"), SkyProtocol.Channel("401", "Sky Sports", "4002"))
    private val q = SkySpec("q", "media_player.sky_salon", "Salon")
    private val playing =
        SkyState(
            reachable = true,
            transport = "PLAYING",
            media = SkyProtocol.Media.Live("2076"),
            channels = channels,
            favourites = listOf("4002"),
            programme = SkyProtocol.Programme("EastEnders", 0, 1800, uuid = "abc-1"),
            country = "GBR"
        )

    @Test
    fun `the media player shows the channel and its programme`() {
        val m = SkyEntityMapper.media(q, playing)
        assertEquals("playing", m.state)
        assertEquals("101 BBC One", m.attributes["source"])
        assertEquals("EastEnders", m.attributes["media_title"])
        assertEquals(listOf("401 Sky Sports"), m.attributes["source_list"]) // the favourites
        assertTrue((m.attributes["entity_picture"] as String).contains("pd-image/abc-1"))
        assertEquals("off", SkyEntityMapper.media(q, playing.copy(standby = true)).state)
        assertEquals("on", SkyEntityMapper.media(q.copy(skyQ = false), SkyState(reachable = true)).state)
    }

    @Test
    fun `services become keys, power only when needed`() {
        assertEquals(emptyList<String>(), SkyServices.media(q, "turn_on", emptyMap(), playing))
        assertEquals(listOf("power"), SkyServices.media(q, "turn_on", emptyMap(), playing.copy(standby = true)))
        assertEquals(listOf("power"), SkyServices.media(q.copy(skyQ = false), "turn_on", emptyMap(), SkyState()))
        assertEquals(listOf("4", "0", "1"), SkyServices.media(q, "select_source", mapOf("source" to "401 Sky Sports"), playing))
        assertEquals(listOf("1", "0", "1"), SkyServices.media(q, "play_media", mapOf("media_content_id" to "BBC One"), playing))
        assertEquals(listOf("pause"), SkyServices.media(q, "media_play_pause", emptyMap(), playing))
        assertEquals(listOf("channelup"), SkyServices.media(q, "media_next_track", emptyMap(), playing))
        assertEquals(
            listOf("up", "select", "backup", "up", "select", "backup"),
            SkyServices.remote(
                q,
                "send_command",
                mapOf("command" to listOf("DPAD_UP", "OK", "BACK", "VOLUME_UP"), "num_repeats" to 2),
                playing
            )
        )
    }
}
