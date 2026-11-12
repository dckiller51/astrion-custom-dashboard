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
                InfoBlock.Text("{{ 2 }}", "body", 0, false, "start"),
                InfoBlock.Text("Hi", "title", 2, true, "center")
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
}
