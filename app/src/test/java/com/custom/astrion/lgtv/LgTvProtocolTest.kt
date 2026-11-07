package com.custom.astrion.lgtv

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** An LG webOS TV's protocol (1.5.2): messages, what the TV says, entities and services. */
class LgTvProtocolTest {
    private fun obj(text: String) = LgTvProtocol.parseObject(text) as JsonObject

    @Test
    fun `registration asks for a prompt, then sends the key back`() {
        val first = obj(LgTvProtocol.register(null))
        assertEquals("register", first["type"]!!.jsonPrimitive.content)
        val payload = first["payload"]!!.jsonObject
        assertEquals("PROMPT", payload["pairingType"]!!.jsonPrimitive.content)
        assertNull(payload["client-key"])
        val again = obj(LgTvProtocol.register("abc123"))["payload"]!!.jsonObject
        assertEquals("abc123", again["client-key"]!!.jsonPrimitive.content)

        val prompt = LgTvProtocol.parse("""{"type":"response","id":"register_0","payload":{"pairingType":"PROMPT","returnValue":true}}""")!!
        assertTrue(LgTvProtocol.isPrompt(prompt))
        val registered = LgTvProtocol.parse("""{"type":"registered","id":"register_0","payload":{"client-key":"f00d"}}""")!!
        assertEquals("f00d", LgTvProtocol.clientKeyOf(registered))
        val refused = LgTvProtocol.parse("""{"type":"error","id":"register_0","error":"403 User denied access","payload":{}}""")!!
        assertFalse(LgTvProtocol.ok(refused))
        assertEquals("403 User denied access", refused.error)
    }

    @Test
    fun `requests, subscriptions and buttons are written as the TV reads them`() {
        val m = obj(LgTvProtocol.message("7", LgTvProtocol.SET_VOLUME, LgTvProtocol.volumePayload(140)))
        assertEquals("request", m["type"]!!.jsonPrimitive.content)
        assertEquals("ssap://audio/setVolume", m["uri"]!!.jsonPrimitive.content)
        assertEquals(100, m["payload"]!!.jsonObject["volume"]!!.jsonPrimitive.content.toInt())
        assertEquals(
            "subscribe",
            obj(LgTvProtocol.message("8", LgTvProtocol.POWER_STATE, subscribe = true))["type"]!!.jsonPrimitive.content
        )
        assertEquals("type:button\nname:UP\n\n", LgTvProtocol.button("UP"))
    }

    @Test
    fun `what the TV says is read`() {
        assertEquals("OLED55C24LA", LgTvProtocol.model(obj("""{"modelName":"OLED55C24LA","returnValue":true}""")))
        assertEquals(
            "a8:23:fe:01:02:03",
            LgTvProtocol.mac(obj("""{"wiredInfo":{"macAddress":"A8:23:FE:01:02:03"},"wifiInfo":{"macAddress":"a8:23:fe:0a:0b:0c"}}"""))
        )
        assertEquals(true, LgTvProtocol.isOn(obj("""{"state":"Active"}""")))
        assertEquals(true, LgTvProtocol.isOn(obj("""{"state":"Screen Off"}""")))
        assertTrue(LgTvProtocol.screenOff(obj("""{"state":"Screen Off"}""")))
        assertEquals(false, LgTvProtocol.isOn(obj("""{"state":"Active Standby"}""")))
        assertEquals(12, LgTvProtocol.volume(obj("""{"volumeStatus":{"volume":12,"muteStatus":true}}""")))
        assertEquals(true, LgTvProtocol.muted(obj("""{"volumeStatus":{"volume":12,"muteStatus":true}}""")))
        assertEquals(30, LgTvProtocol.volume(obj("""{"volume":30,"mute":false}""")))
        assertEquals(false, LgTvProtocol.muted(obj("""{"volume":30,"mute":false}""")))
        val apps = LgTvProtocol.apps(
            obj(
                """{"launchPoints":[{"id":"netflix","title":"Netflix","icon":"http://x/n.png"},""" +
                    """{"id":"youtube.leanback.v4","title":"YouTube"}]}"""
            )
        )!!
        assertEquals(listOf("Netflix", "YouTube"), apps.map { it.label })
        assertEquals("netflix" to null, LgTvProtocol.appChange(obj("""{"change":"removed","id":"netflix"}""")))
        val inputs = LgTvProtocol.inputs(
            obj(
                """{"devices":[{"id":"HDMI_1","label":"HDMI 1","appId":"com.webos.app.hdmi1","connected":true},""" +
                    """{"id":"HDMI_2","label":"PS5","appId":"com.webos.app.hdmi2","connected":false}]}"""
            )
        )!!
        assertEquals(listOf("HDMI_1", "HDMI_2"), inputs.map { it.id })
        assertFalse(inputs[1].connected)
    }

    private val state =
        LgTvState(
            reachable = true,
            on = true,
            model = "OLED55C24LA",
            app = "com.webos.app.hdmi1",
            volume = 12,
            muted = false,
            apps = listOf(LgTvProtocol.Item("netflix", "Netflix"), LgTvProtocol.Item("amazon", "Prime Video")),
            inputs =
            listOf(
                LgTvProtocol.Input("HDMI_1", "HDMI 1", "com.webos.app.hdmi1", true),
                LgTvProtocol.Input("HDMI_2", "PS5", "com.webos.app.hdmi2", false)
            )
        )
    private val spec = LgTvSpec("a823fe010203", "media_player.lgtv_salon", "Salon", "a8:23:fe:01:02:03")

    @Test
    fun `the media player says what is in front, inputs first`() {
        val m = LgTvEntityMapper.media(spec, state)
        assertEquals("on", m.state)
        assertEquals("HDMI 1", m.attributes["source"])
        assertEquals(listOf("HDMI 1", "PS5", "Netflix", "Prime Video"), m.attributes["source_list"])
        assertEquals(0.12, m.attributes["volume_level"])
        assertEquals("LG", m.attributes["manufacturer"])
        assertEquals("Netflix", LgTvEntityMapper.media(spec, state.copy(app = "netflix")).attributes["source"])
        assertEquals("off", LgTvEntityMapper.media(spec, state.copy(on = false)).state)
        assertEquals("remote.lgtv_salon", LgTvEntityMapper.remote(spec, state).entityId)
    }

    @Test
    fun `the TV's own icons show its apps and what's in front`() {
        val withIcons =
            state.copy(
                app = "netflix",
                apps = listOf(
                    LgTvProtocol.Item("netflix", "Netflix", "https://192.168.1.40:3001/resources/abc/icon.png"),
                    LgTvProtocol.Item("amazon", "Prime Video")
                )
            )
        val m = LgTvEntityMapper.media(spec, withIcons)
        assertEquals("http://127.0.0.1:8080/lgtv-icon/a823fe010203?id=netflix", m.attributes["entity_picture"])
        assertEquals(mapOf("Netflix" to "http://127.0.0.1:8080/lgtv-icon/a823fe010203?id=netflix"), m.attributes["astrion_source_icons"])
        assertNull(LgTvEntityMapper.media(spec, withIcons.copy(app = "amazon")).attributes["entity_picture"])
        assertEquals("https://192.168.1.40:3001/resources/abc/icon.png", withIcons.iconOf("netflix"))
        assertEquals(
            "http://127.0.0.1:8080/lgtv-icon/x?id=com.webos.app.hdmi1",
            LgTvEntityMapper.iconUrl(spec.copy(localId = "x"), "com.webos.app.hdmi1")
        )
    }

    @Test
    fun `services become requests, buttons or a wake-up`() {
        assertEquals(listOf(LgTvStep.Wake), LgTvServices.media("turn_on", emptyMap(), state.copy(reachable = false, on = false)))
        assertEquals(
            listOf(LgTvStep.Request(LgTvProtocol.SCREEN_ON)),
            LgTvServices.media("turn_on", emptyMap(), state.copy(screenOff = true))
        )
        assertEquals(listOf(LgTvStep.Request(LgTvProtocol.POWER_OFF)), LgTvServices.media("turn_off", emptyMap(), state))
        val vol = LgTvServices.media("volume_set", mapOf("volume_level" to 0.25), state)!!.single() as LgTvStep.Request
        assertEquals(JsonPrimitive(25), vol.payload!!["volume"])
        val mute = LgTvServices.media("volume_mute", emptyMap(), state)!!.single() as LgTvStep.Request
        assertEquals(JsonPrimitive(true), mute.payload!!["mute"])
        val input = LgTvServices.media("select_source", mapOf("source" to "PS5"), state)!!.single() as LgTvStep.Request
        assertEquals(LgTvProtocol.SWITCH_INPUT, input.uri)
        assertEquals(JsonPrimitive("HDMI_2"), input.payload!!["inputId"])
        val app = LgTvServices.media("play_media", mapOf("media_content_id" to "Netflix"), state)!!.single() as LgTvStep.Request
        assertEquals(LgTvProtocol.LAUNCH, app.uri)
        assertEquals(JsonPrimitive("netflix"), app.payload!!["id"])
        assertEquals(
            listOf(LgTvStep.Request(LgTvProtocol.CHANNEL_UP)),
            LgTvServices.media("media_next_track", emptyMap(), state.copy(app = LgTvState.LIVE_TV))
        )
        assertNull(LgTvServices.media("shuffle_set", emptyMap(), state))
    }

    @Test
    fun `the TV remote card's Android names become LG buttons`() {
        val steps = LgTvServices.remote(
            "send_command",
            mapOf("command" to listOf("DPAD_UP", "DPAD_CENTER", "BACK", "HOME", "5", "Nope"), "num_repeats" to 2),
            state
        )!!
        assertEquals(
            List(2) { listOf("UP", "ENTER", "BACK", "HOME", "5") }.flatten().map { LgTvStep.Button(it) },
            steps
        )
        assertEquals(
            listOf(LgTvStep.Request(LgTvProtocol.POWER_OFF)),
            LgTvServices.remote("send_command", mapOf("command" to "POWER"), state)
        )
        assertEquals("VOLUMEUP", LgTvButtons.of("volume_up"))
        assertEquals("CHANNELDOWN", LgTvButtons.of("CHANNELDOWN"))
        assertNull(LgTvButtons.of("F4"))
    }
}
