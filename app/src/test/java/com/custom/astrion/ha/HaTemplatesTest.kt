package com.custom.astrion.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HaTemplatesTest {
    private fun msg(s: String) = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `a value with Jinja is a template, plain text is not`() {
        assertTrue(HaTemplates.isTemplate("https://image.tmdb.org/t/p/w500{{ state_attr('sensor.x','poster_path') }}"))
        assertTrue(HaTemplates.isTemplate("{% if is_state('a.b','on') %}on{% endif %}"))
        assertFalse(HaTemplates.isTemplate("Now playing"))
    }

    @Test
    fun `the subscription's success result carries no text`() {
        assertNull(HaTemplates.answerOf(msg("""{"id":5,"type":"result","success":true,"result":null}""")))
    }

    @Test
    fun `a refused template is an error`() {
        val t = HaTemplates.answerOf(
            msg("""{"id":5,"type":"result","success":false,"error":{"code":"template_error","message":"unexpected '}'"}}""")
        )
        assertEquals(TemplateText(error = "unexpected '}'"), t)
    }

    @Test
    fun `an event's result is the text, native types as JSON`() {
        assertEquals("Buddy", HaTemplates.answerOf(msg("""{"id":5,"type":"event","event":{"result":"Buddy","listeners":{}}}"""))?.text)
        assertEquals("96", HaTemplates.answerOf(msg("""{"id":5,"type":"event","event":{"result":96}}"""))?.text)
        assertEquals(
            """["Horror","Komedie"]""",
            HaTemplates.answerOf(msg("""{"id":5,"type":"event","event":{"result":["Horror","Komedie"]}}"""))?.text
        )
        assertEquals("", HaTemplates.answerOf(msg("""{"id":5,"type":"event","event":{"result":null}}"""))?.text)
    }

    @Test
    fun `a warning is skipped, an error is kept`() {
        assertNull(HaTemplates.answerOf(msg("""{"id":5,"type":"event","event":{"error":"'x' is undefined","level":"WARNING"}}""")))
        assertEquals("boom", HaTemplates.answerOf(msg("""{"id":5,"type":"event","event":{"error":"boom","level":"ERROR"}}"""))?.error)
    }

    @Test
    fun `a template is subscribed once, sent again after a reconnect, dropped with its last card`() {
        val sent = mutableListOf<String>()
        var id = 1
        var connected = false
        val t = HaTemplates({ sent += it.toString() }, { id++ }, { connected })
        t.acquire("{{ 1 }}")
        t.acquire("{{ 1 }}")
        assertTrue(sent.isEmpty())
        connected = true
        t.resubscribe()
        assertEquals(1, sent.count { "render_template" in it })
        t.onMessage(1, msg("""{"id":1,"type":"event","event":{"result":1}}"""))
        assertEquals("1", t.acquire("{{ 1 }}").value.text)
        t.release("{{ 1 }}")
        t.release("{{ 1 }}")
        assertFalse(sent.any { "unsubscribe_events" in it })
        t.release("{{ 1 }}")
        assertTrue(sent.last().contains("unsubscribe_events"))
        assertFalse(t.onMessage(1, msg("""{"id":1,"type":"event","event":{"result":2}}""")))
    }
}
