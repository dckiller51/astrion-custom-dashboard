package com.custom.astrion.cards.impl

import org.junit.Assert.assertEquals
import org.junit.Test

class InfoBlockTest {
    @Test
    fun `blocks are read with their defaults, unknown and empty ones left out`() {
        val blocks = InfoBlock.listOf(
            listOf(
                mapOf("type" to "image", "url" to " https://x/{{ 1 }} ", "ratio" to "wide"),
                mapOf("type" to "text", "text" to "{{ 2 }}"),
                mapOf("type" to "text", "text" to "Hi", "size" to "title", "lines" to 2, "marquee" to true, "align" to "center"),
                mapOf("type" to "text", "text" to " "),
                mapOf("type" to "video", "url" to "x"),
                "junk"
            )
        )
        assertEquals(
            listOf(
                InfoBlock.Image("https://x/{{ 1 }}", 16f / 9f),
                InfoBlock.Text("{{ 2 }}", "body", 0, false, "start", true),
                InfoBlock.Text("Hi", "title", 2, true, "center", true)
            ),
            blocks
        )
    }

    @Test
    fun `a poster unless said otherwise`() {
        assertEquals(2f / 3f, InfoBlock.ratioOf(null), 0f)
        assertEquals(1f, InfoBlock.ratioOf("square"), 0f)
    }

    @Test
    fun `no blocks when the option is missing`() {
        assertEquals(emptyList<InfoBlock>(), InfoBlock.listOf(null))
    }

    @Test
    fun `a text with a missing value is hidden, unless asked`() {
        val t = InfoBlock.Text("x", "body", 0, false, "start")
        assertEquals(false, t.visible("Next at None: None"))
        assertEquals(false, t.visible("unavailable"))
        assertEquals(true, t.visible("Next at 21:00: News"))
        assertEquals(true, t.copy(hideMissing = false).visible("Next at None: None"))
        assertEquals(false, t.visible("  "))
    }

    @Test
    fun `a list row reads the cast and fills its fields`() {
        val row = InfoBlock.listOf(
            listOf(
                mapOf(
                    "type" to "list",
                    "source" to "{{ x }}",
                    "image" to "https://i/w185{profile_path}",
                    "title" to "{name}",
                    "subtitle" to "{character}",
                    "limit" to 2,
                    "ratio" to "round"
                )
            )
        ).single() as InfoBlock.ListRow
        val cast = """[{"name":"Michael Caine","character":"Harry Palmer","profile_path":"/a.jpg","order":0},
            {"name":"Nigel Green","character":"Dalby","profile_path":null},{"name":"Third"}]"""
        assertEquals(
            listOf(
                InfoBlock.ListRow.Item("https://i/w185/a.jpg", "Michael Caine", "Harry Palmer"),
                InfoBlock.ListRow.Item(null, "Nigel Green", "Dalby")
            ),
            row.items(cast)
        )
        assertEquals(true, row.round)
        assertEquals(false, row.visible("[]"))
        assertEquals(false, row.visible("not a list"))
    }

    @Test
    fun `visible_if hides the card when nothing plays`() {
        listOf("off", "idle", "standby", "False", "None", "unavailable", "", "0").forEach { assertEquals(it, false, InfoBlock.truthy(it)) }
        listOf("playing", "on", "True", "Buddy").forEach { assertEquals(it, true, InfoBlock.truthy(it)) }
    }
}
