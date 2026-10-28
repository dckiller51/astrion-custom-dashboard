package com.custom.astrion.cards.impl

import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The `xbox` card's reading of real entities from the Xbox integration and TrueAchievements. */
class XboxModelTest {
    private fun entity(id: String, state: String, vararg attrs: Pair<String, String>) =
        EntityState(id, state, JsonObject(attrs.associate { (k, v) -> k to JsonPrimitive(v) }))

    private val taGame =
        entity(
            "sensor.trueachievements_dckillerpp_jeu_en_cours",
            "Human Fall Flat",
            "name" to "Human Fall Flat",
            "platform" to "Xbox One",
            "publisher" to "Curve Games",
            "achievements" to "29 / 139",
            "gamerscore" to "570 G / 4360 G",
            "ta_score" to "1264 TA / 25753 TA",
            "hours_played" to "7 h",
            "game_completion" to "5%",
            "game_ratio" to "2.00"
        )

    private val xboxGame =
        entity(
            "sensor.dckillerpp_now_playing",
            "Human Fall Flat",
            "platform" to "Xbox One",
            "publisher" to "Curve Games",
            "achievements" to "29 / 0",
            "gamerscore" to "570 / 4360",
            "progress" to "13 %"
        )

    @Test
    fun `fractions are read whatever their unit`() {
        assertEquals(XboxModel.Fraction(570, 4360), XboxModel.Fraction.parse("570 G / 4360 G"))
        assertEquals(XboxModel.Fraction(1264, 25753), XboxModel.Fraction.parse("1264 TA / 25753 TA"))
        assertEquals(XboxModel.Fraction(29, 0), XboxModel.Fraction.parse("29 / 0"))
        assertNull(XboxModel.Fraction(29, 0).ratio)
        assertEquals(0.5f, XboxModel.Fraction(1, 2).ratio)
        assertNull(XboxModel.Fraction.parse("Human Fall Flat"))
        assertNull(XboxModel.Fraction.parse(null))
    }

    @Test
    fun `a TrueAchievements game gives three bars and three chips`() {
        val g = XboxModel.game(taGame)!!
        assertEquals("Human Fall Flat", g.title)
        assertEquals("Xbox One · Curve Games", g.subtitle)
        assertEquals(
            listOf(XboxModel.Bar.Kind.ACHIEVEMENTS, XboxModel.Bar.Kind.GAMERSCORE, XboxModel.Bar.Kind.TA_SCORE),
            g.bars.map { it.kind }
        )
        assertEquals(139L, g.bars[0].fraction.total)
        assertEquals(listOf("5%", "2.00", "7 h"), g.chips.map { it.value })
    }

    @Test
    fun `an Xbox integration game has no achievement total, and its progress`() {
        val g = XboxModel.game(xboxGame)!!
        assertNull(g.bars.first { it.kind == XboxModel.Bar.Kind.ACHIEVEMENTS }.fraction.ratio)
        assertEquals(570L, g.bars.first { it.kind == XboxModel.Bar.Kind.GAMERSCORE }.fraction.done)
        assertEquals(listOf(XboxModel.Chip(XboxModel.Chip.Kind.COMPLETION, "13%")), g.chips)
    }

    @Test
    fun `no game when the console is off`() {
        assertNull(XboxModel.game(entity("sensor.x", "offline_status")))
        assertNull(XboxModel.game(entity("sensor.x", "unknown")))
        assertNull(XboxModel.game(null))
        assertFalse(XboxModel.isPlaying(entity("sensor.x", "unavailable")))
        assertTrue(XboxModel.isPlaying(taGame))
    }

    @Test
    fun `counters are grouped by thousands, with short units only`() {
        assertEquals("833 634", XboxModel.formatNumber("833634"))
        assertEquals("96.4", XboxModel.formatNumber("96.4"))
        assertEquals("603", XboxModel.formatNumber("603"))
        val gs =
            entity("sensor.trueachievements_dckillerpp_gamerscore", "833634", "unit_of_measurement" to "G", "icon" to "mdi:alpha-g-circle")
        assertEquals("833 634 G", XboxModel.statValue(gs))
        val ta = entity("sensor.trueachievements_dckillerpp_score_ta", "1652917", "unit_of_measurement" to "TA")
        assertEquals("1 652 917", XboxModel.statValue(ta))
        assertEquals("96.4 %", XboxModel.statValue(entity("sensor.p", "96.4", "unit_of_measurement" to "%")))
        assertEquals(XboxModel.StatKind.GAMERSCORE, XboxModel.statKind(gs))
        assertEquals(XboxModel.StatKind.TA_SCORE, XboxModel.statKind(ta))
        assertEquals(XboxModel.StatKind.PEOPLE, XboxModel.statKind(entity("sensor.f", "65", "unit_of_measurement" to "people")))
    }

    @Test
    fun `counter labels drop the account part`() {
        val ta =
            entity(
                "sensor.trueachievements_dckillerpp_jeux_termines",
                "603",
                "friendly_name" to "TrueAchievements (dckillerpp) Jeux terminés"
            )
        assertEquals("Jeux terminés", XboxModel.statLabel(ta))
        val xbox = entity("sensor.dckillerpp_friends", "65", "friendly_name" to "dckillerpp Amis")
        assertEquals("Amis", XboxModel.statLabel(xbox))
    }

    @Test
    fun `a counter's icon is automatic, built-in, none or a file`() {
        val gs = entity("sensor.x_gamer_score", "1", "unit_of_measurement" to "G")
        assertEquals(XboxModel.StatIcon.BuiltIn(XboxModel.StatKind.GAMERSCORE), XboxModel.statIcon(null, gs))
        assertEquals(XboxModel.StatIcon.BuiltIn(XboxModel.StatKind.GAMERSCORE), XboxModel.statIcon("auto", gs))
        assertEquals(XboxModel.StatIcon.BuiltIn(XboxModel.StatKind.FINISHED), XboxModel.statIcon("trophy", gs))
        assertEquals(XboxModel.StatIcon.None, XboxModel.statIcon("none", gs))
        assertEquals(XboxModel.StatIcon.File("/sdcard/astrion/icons/a.png"), XboxModel.statIcon("/sdcard/astrion/icons/a.png", gs))
    }

    @Test
    fun `unknown release date and age are placeholders`() {
        assertTrue(XboxModel.isPlaceholder("release_date", "9998-12-30"))
        assertFalse(XboxModel.isPlaceholder("release_date", "2026-05-19"))
        assertTrue(XboxModel.isPlaceholder("min_age", "0"))
        assertFalse(XboxModel.isPlaceholder("min_age", "12"))
        assertFalse(XboxModel.isPlaceholder("platform", "0"))
    }
}
