package com.custom.astrion.sonos

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SonosProtocolTest {
    private val salon = SonosSpec("media_player.sonos_salon", "Salon", "RINCON_A", "192.168.1.40", lineIn = false, tv = true)
    private val radio =
        SonosFavorite(
            "FV:2/1",
            "FIP",
            "x-sonosapi-stream:s15200?sid=254",
            "<DIDL-Lite><item><upnp:class>object.item.audioItem.audioBroadcast</upnp:class></item></DIDL-Lite>",
            null
        )
    private val playlist =
        SonosFavorite(
            "FV:2/2",
            "Chill",
            "x-rincon-cpcontainer:1006206cplaylist",
            "<DIDL-Lite><item><upnp:class>object.container.playlistContainer</upnp:class></item></DIDL-Lite>",
            null
        )

    private fun ctx(playback: SonosPlayback? = null, coordinator: String = "RINCON_A") = SonosContext(
        self = salon,
        coordinator = coordinator,
        playback = playback,
        volume = 20,
        favorites = listOf(radio, playlist),
        uuidOf = { mapOf("media_player.sonos_cuisine" to "RINCON_B", "media_player.sonos_salon" to "RINCON_A")[it] }
    )

    @Test
    fun `xml helpers`() {
        assertEquals("a < b & \"c\" é", SonosXml.unescape("a &lt; b &amp; &quot;c&quot; &#233;"))
        assertEquals("Rock &amp; Roll", SonosXml.escape("Rock & Roll"))
        assertEquals("x", SonosXml.text("<a><dc:title id=\"1\">x</dc:title></a>", "title"))
        val els = SonosXml.elements("<g><m UUID=\"1\"/><m UUID=\"2\">in</m></g>", "m")
        assertEquals(listOf("1", "2"), els.map { it.attrs["UUID"] })
        assertEquals("in", els[1].inner)
    }

    @Test
    fun `the zone group state lists visible speakers`() {
        val state =
            "<ZoneGroups><ZoneGroup Coordinator=\"RINCON_A\" ID=\"x\">" +
                "<ZoneGroupMember UUID=\"RINCON_A\" Location=\"http://192.168.1.40:1400/xml/device_description.xml\" ZoneName=\"Salon\"/>" +
                "<ZoneGroupMember UUID=\"RINCON_B\" Location=\"http://192.168.1.41:1400/xml/dd.xml\" ZoneName=\"Cuisine\"/>" +
                "<ZoneGroupMember UUID=\"RINCON_S\" Location=\"http://192.168.1.42:1400/xml/dd.xml\" ZoneName=\"Salon\" Invisible=\"1\"/>" +
                "</ZoneGroup><ZoneGroup Coordinator=\"RINCON_C\" ID=\"y\">" +
                "<ZoneGroupMember UUID=\"RINCON_C\" Location=\"http://192.168.1.43:1400/xml/device_description.xml\" ZoneName=\"Roam\">" +
                "<Satellite UUID=\"z\"/></ZoneGroupMember></ZoneGroup></ZoneGroups>"
        val response = "<s:Envelope><s:Body><u:GetZoneGroupStateResponse><ZoneGroupState>${SonosXml.escape(
            state
        )}</ZoneGroupState></u:GetZoneGroupStateResponse></s:Body></s:Envelope>"
        val speakers = SonosProtocol.speakers(response)
        assertEquals(listOf("Salon", "Cuisine", "Roam"), speakers.map { it.name })
        assertEquals("192.168.1.41", speakers[1].host)
        assertEquals("RINCON_A", speakers[1].coordinator)
        assertEquals("RINCON_C", speakers[2].coordinator)
    }

    @Test
    fun `device description and metadata`() {
        val desc = "<root><device><modelName>Sonos Era 100</modelName><displayName>Era 100</displayName><roomName>Salon</roomName>" +
            "<serviceType>urn:schemas-upnp-org:service:AudioIn:1</serviceType></device></root>"
        assertEquals(SonosModel("Salon", "Era 100", lineIn = true, tv = false), SonosProtocol.model(desc))

        val didl = "<DIDL-Lite><item><dc:title>Song</dc:title><dc:creator>Artist</dc:creator><upnp:album>Album</upnp:album>" +
            "<upnp:albumArtURI>/getaa?s=1&amp;u=x</upnp:albumArtURI></item></DIDL-Lite>"
        val t = SonosProtocol.track(didl, "192.168.1.40")
        assertEquals("Song", t?.title)
        assertEquals("http://192.168.1.40:1400/getaa?s=1&u=x", t?.art)
        assertNull(SonosProtocol.track("NOT_IMPLEMENTED", "h"))
        assertEquals(225L, SonosProtocol.seconds("0:03:45"))
        assertNull(SonosProtocol.seconds("NOT_IMPLEMENTED"))
        assertEquals("1:02:03", SonosProtocol.hms(3723))
    }

    @Test
    fun `what a coordinator plays is read`() {
        val track = "<DIDL-Lite><item><dc:title>So What</dc:title><dc:creator>Miles Davis</dc:creator></item></DIDL-Lite>"
        val position = "<u:GetPositionInfoResponse><TrackDuration>0:09:22</TrackDuration>" +
            "<TrackMetaData>${SonosXml.escape(track)}</TrackMetaData>" +
            "<RelTime>0:01:05</RelTime></u:GetPositionInfoResponse>"
        val media = "<u:GetMediaInfoResponse><CurrentURI>x-rincon-queue:RINCON_A#0</CurrentURI>" +
            "<CurrentURIMetaData></CurrentURIMetaData></u:GetMediaInfoResponse>"
        val p = SonosProtocol.playback(
            "<CurrentTransportState>PLAYING</CurrentTransportState>",
            position,
            media,
            "<PlayMode>REPEAT_ALL</PlayMode>",
            "h"
        )
        assertEquals("PLAYING", p.transport)
        assertEquals("So What", p.track?.title)
        assertEquals(562L, p.duration)
        assertEquals(65L, p.position)
        assertNull(p.mediaTitle)
        assertEquals(17, SonosProtocol.volumeOf("<CurrentVolume>17</CurrentVolume>"))
        assertEquals(true, SonosProtocol.muteOf("<CurrentMute>1</CurrentMute>"))
    }

    @Test
    fun `favorites are read`() {
        val didl = "<DIDL-Lite><item id=\"FV:2/1\"><dc:title>FIP</dc:title><upnp:albumArtURI>http://x/a.png</upnp:albumArtURI>" +
            "<res protocolInfo=\"x\">x-sonosapi-stream:s15200?sid=254&amp;flags=1</res>" +
            "<r:resMD>&lt;DIDL-Lite/&gt;</r:resMD></item></DIDL-Lite>"
        val response = "<u:BrowseResponse><Result>${SonosXml.escape(didl)}</Result></u:BrowseResponse>"
        val f = SonosProtocol.favoritesOf(response, "h").single()
        assertEquals("FIP", f.title)
        assertEquals("x-sonosapi-stream:s15200?sid=254&flags=1", f.uri)
        assertEquals("<DIDL-Lite/>", f.meta)
        assertEquals("http://x/a.png", f.art)
    }

    @Test
    fun `the speaker as a media player`() {
        assertEquals("unavailable", SonosProtocol.stateOf(false, null))
        assertEquals("playing", SonosProtocol.stateOf(true, SonosPlayback(transport = "PLAYING")))
        assertEquals("paused", SonosProtocol.stateOf(true, SonosPlayback(transport = "PAUSED_PLAYBACK")))
        assertEquals("idle", SonosProtocol.stateOf(true, SonosPlayback()))

        val playback = SonosPlayback(
            transport = "PLAYING",
            playMode = "SHUFFLE",
            uri = radio.uri,
            mediaTitle = "FIP",
            track = SonosTrack(title = "x", stream = "Miles Davis - So What"),
            duration = 0,
            position = 12
        )
        val a = SonosProtocol.media(salon, 25, false, playback, listOf("media_player.sonos_salon"), listOf(radio), "now")
        assertEquals(0.25, a["volume_level"])
        assertEquals("Miles Davis - So What", a["media_title"])
        assertEquals("FIP", a["media_artist"])
        assertEquals("FIP", a["source"])
        assertEquals(true, a["shuffle"])
        assertEquals("all", a["repeat"])
        assertEquals(listOf("TV", "FIP"), a["source_list"])
        assertFalse(a.containsKey("media_duration"))

        val tv = SonosProtocol.media(
            salon,
            25,
            false,
            SonosPlayback(transport = "PLAYING", uri = "x-sonos-htastream:RINCON_A:spdif"),
            emptyList(),
            emptyList(),
            "now"
        )
        assertEquals("TV", tv["source"])
        assertFalse(tv.containsKey("media_title"))
    }

    @Test
    fun `play modes round trip`() {
        listOf("NORMAL", "REPEAT_ALL", "REPEAT_ONE", "SHUFFLE_NOREPEAT", "SHUFFLE", "SHUFFLE_REPEAT_ONE").forEach {
            assertEquals(it, SonosProtocol.playModeOf(SonosProtocol.shuffleOf(it), SonosProtocol.repeatOf(it)))
        }
    }

    @Test
    fun `services become soap calls`() {
        val grouped = ctx(SonosPlayback(transport = "PLAYING"), coordinator = "RINCON_B")
        // Transport goes to the group's coordinator, volume to the speaker itself.
        assertEquals("RINCON_B", SonosProtocol.service("media_play_pause", emptyMap(), grouped)!!.single().target)
        assertEquals("Pause", SonosProtocol.service("media_play_pause", emptyMap(), grouped)!!.single().action)
        val vol = SonosProtocol.service("volume_set", mapOf("volume_level" to JsonPrimitive(0.42)), grouped)!!.single()
        assertEquals("RINCON_A", vol.target)
        assertTrue(vol.args.contains("DesiredVolume" to "42"))
        assertTrue(SonosProtocol.service("volume_up", emptyMap(), grouped)!!.single().args.contains("DesiredVolume" to "23"))
        val seek = SonosProtocol.service("media_seek", mapOf("seek_position" to JsonPrimitive(90)), grouped)!!.single()
        assertTrue(seek.args.contains("Target" to "0:01:30"))
        val repeat = SonosProtocol.service(
            "repeat_set",
            mapOf("repeat" to JsonPrimitive("one")),
            ctx(SonosPlayback(playMode = "SHUFFLE_NOREPEAT"))
        )!!
        assertTrue(repeat.single().args.contains("NewPlayMode" to "SHUFFLE_REPEAT_ONE"))

        val tv = SonosProtocol.service("select_source", mapOf("source" to JsonPrimitive("TV")), ctx())!!
        assertEquals("x-sonos-htastream:RINCON_A:spdif", tv[0].args.toMap()["CurrentURI"])
        assertNull(SonosProtocol.service("select_source", mapOf("source" to JsonPrimitive("Line-in")), ctx()))
        assertEquals(
            listOf(
                "SetAVTransportURI",
                "Play"
            ),
            SonosProtocol.service("select_source", mapOf("source" to JsonPrimitive("FIP")), ctx())!!.map {
                it.action
            }
        )
        val queued = SonosProtocol.service("play_media", mapOf("media_content_id" to JsonPrimitive("FV:2/2")), ctx())!!
        assertEquals(listOf("RemoveAllTracksFromQueue", "AddURIToQueue", "SetAVTransportURI", "Play"), queued.map { it.action })
        assertEquals("x-rincon-queue:RINCON_A#0", queued[2].args.toMap()["CurrentURI"])

        val join = SonosProtocol.service(
            "join",
            mapOf("group_members" to JsonArray(listOf(JsonPrimitive("media_player.sonos_cuisine")))),
            ctx()
        )!!
        assertEquals("RINCON_B", join.single().target)
        assertEquals("x-rincon:RINCON_A", join.single().args.toMap()["CurrentURI"])
        assertEquals("BecomeCoordinatorOfStandaloneGroup", SonosProtocol.service("unjoin", emptyMap(), ctx())!!.single().action)
        assertNull(SonosProtocol.service("turn_on", emptyMap(), ctx()))
    }

    @Test
    fun `the soap envelope escapes its arguments`() {
        val body = SonosProtocol.envelope(SonosService.AV_TRANSPORT, "SetAVTransportURI", listOf("CurrentURI" to "a&b"))
        assertTrue(
            body.contains("<u:SetAVTransportURI xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\"><CurrentURI>a&amp;b</CurrentURI>")
        )
        assertEquals("\"urn:schemas-upnp-org:service:AVTransport:1#Play\"", SonosProtocol.soapAction(SonosService.AV_TRANSPORT, "Play"))
    }
}
