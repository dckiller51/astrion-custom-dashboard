package com.custom.astrion.epg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A small XML reader as [XmlTokens], as Android's XmlPullParser is on the remote: the unit tests'
 * Android classpath has neither XmlPullParser's implementation nor the JDK's StAX. Enough for
 * these samples: declarations, comments and doctypes skipped, attributes in either quotes,
 * empty elements, the usual entities.
 */
private class TestTokens(private val xml: String) : XmlTokens {
    private var i = 0
    private var pendingEnd: String? = null
    private var attrs: Map<String, String> = emptyMap()
    override var name: String = ""
        private set
    override var text: String = ""
        private set

    override fun attr(name: String): String? = attrs[name]

    override fun next(): XmlTokens.Token {
        pendingEnd?.let {
            pendingEnd = null
            name = it
            return XmlTokens.Token.END
        }
        while (i < xml.length) {
            when {
                xml[i] != '<' -> return textRun()
                xml.startsWith("<!--", i) -> i = xml.indexOf("-->", i) + "-->".length
                xml.startsWith("<?", i) || xml.startsWith("<!", i) -> i = xml.indexOf('>', i) + 1
                xml.startsWith("</", i) -> return endTag()
                else -> return startTag()
            }
        }
        return XmlTokens.Token.DONE
    }

    private fun textRun(): XmlTokens.Token {
        val stop = xml.indexOf('<', i).let { if (it < 0) xml.length else it }
        text = unescape(xml.substring(i, stop))
        i = stop
        return XmlTokens.Token.TEXT
    }

    private fun endTag(): XmlTokens.Token {
        val stop = xml.indexOf('>', i)
        name = xml.substring(i + 2, stop).trim()
        i = stop + 1
        return XmlTokens.Token.END
    }

    private fun startTag(): XmlTokens.Token {
        val stop = xml.indexOf('>', i)
        var body = xml.substring(i + 1, stop)
        i = stop + 1
        val empty = body.endsWith("/")
        if (empty) body = body.dropLast(1)
        name = body.trim().substringBefore(' ').substringBefore('\n')
        attrs = ATTR.findAll(body).associate { m -> m.groupValues[1] to unescape(m.groupValues[2].ifEmpty { m.groupValues[3] }) }
        if (empty) pendingEnd = name
        return XmlTokens.Token.START
    }

    private fun unescape(s: String): String = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.drop(2).toInt(HEX).toChar().toString()
            e.startsWith("#") -> e.drop(1).toInt().toChar().toString()
            else -> NAMED[e] ?: m.value
        }
    }

    private companion object {
        const val HEX = 16
        val ATTR = Regex("([\\w:-]+)\\s*=\\s*(?:\"([^\"]*)\"|'([^']*)')")
        val ENTITY = Regex("&(#x[0-9a-fA-F]+|#\\d+|\\w+);")
        val NAMED = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'")
    }
}

class EpgParseTest {
    private val xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv generator-info-name="XML TV Fr">
          <channel id="France2.fr"><display-name>France 2</display-name><icon src="https://logo/f2.png"/></channel>
          <channel id="TF1.fr"><display-name>TF1</display-name></channel>
          <channel id="Arte.fr"><display-name lang="de">Arte DE</display-name><display-name lang="fr">Arte</display-name></channel>
          <programme start="20261117120000 +0100" stop="20261117130000 +0100" channel="France2.fr">
            <title lang="fr">Tout le monde veut prendre sa place</title>
            <desc lang="fr">Jeu.</desc>
          </programme>
          <programme start="20261117130000 +0100" stop="20261117134500 +0100" channel="France2.fr">
            <title lang="fr">Journal 13h00</title>
            <sub-title lang="fr">Émission du 17 novembre</sub-title>
            <desc lang="fr">Le journal &amp; la météo.</desc>
            <category lang="fr">Journal</category>
            <icon src="https://img/journal.jpg"/>
            <rating system="CSA"><value>Tout public</value></rating>
          </programme>
          <programme start="20261117130000 +0100" stop="20261117140000 +0100" channel="TF1.fr">
            <title>Le 13H</title>
          </programme>
          <programme start="20261120130000 +0100" stop="20261120140000 +0100" channel="France2.fr">
            <title>Trop tard</title>
          </programme>
        </tv>
        """.trimIndent()

    private val t1300 = EpgParse.time("20261117130000 +0100")!!

    private fun guide(keep: (String) -> Boolean = { it != "TF1.fr" }): EpgGuide {
        val channels = mutableListOf<EpgChannel>()
        val shows = mutableListOf<EpgShow>()
        EpgParse.read(TestTokens(xml), keep, t1300 - 3 * 3_600_000L, t1300 + 36 * 3_600_000L, "fr", channels::add, shows::add)
        return EpgGuide(channels.filter { keep(it.id) }, shows, channels, t1300)
    }

    @Test
    fun timesCarryTheirOffset() {
        assertEquals(EpgParse.time("20261117120000 +0000"), t1300)
        assertEquals(EpgParse.time("20261117120000"), t1300)
        assertNull(EpgParse.time("demain"))
        // As a calendar says, across months, leap days and offsets either way.
        listOf(
            "20240229235959 +0100",
            "20261231230000 -0500",
            "19991231120000 +0530",
            "20261117103000",
            "202611171030 +0200"
        ).forEach { s ->
            val c = java.util.Calendar.getInstance(
                java.util.TimeZone.getTimeZone(
                    "GMT" + (
                        s.substringAfter(' ', "+0000").let {
                            it.substring(0, 3) + ":" + it.substring(3)
                        }
                        )
                )
            )
            c.clear()
            c.set(
                s.substring(
                    0,
                    4
                ).toInt(),
                s.substring(4, 6).toInt() - 1,
                s.substring(
                    6,
                    8
                ).toInt(),
                s.substring(8, 10).toInt(),
                s.substring(10, 12).toInt(),
                s.substring(12).substringBefore(' ').ifEmpty {
                    "0"
                }.toInt()
            )
            assertEquals(s, c.timeInMillis, EpgParse.time(s))
        }
    }

    @Test
    fun readsChannelsAndKeptShows() {
        val g = guide()
        assertEquals(listOf("France2.fr", "TF1.fr", "Arte.fr"), g.available.map { it.id })
        assertEquals("Arte", g.available[2].name)
        assertEquals(2, g.showCount)
        val now = g.now("France2.fr", t1300 + 60_000)!!
        assertEquals("Journal 13h00", now.title)
        assertEquals("Le journal & la météo.", now.desc)
        assertEquals("Journal", now.category)
        assertEquals("https://img/journal.jpg", now.icon)
        assertNull(g.now("TF1.fr", t1300))
    }

    @Test
    fun airingMatchesTitleAndTime() {
        val g = guide()
        val info = g.infoFor("Journal 13h00", t1300 + 5 * 60_000)
        assertNotNull(info)
        assertEquals("Le journal & la météo.", info!!.overview)
        assertEquals("epg", info.source)
        // The day after: not on air.
        assertNull(g.infoFor("Journal 13h00", t1300 + 24 * 3_600_000L))
        // The player's title as the Apple TV says it, accents and case aside.
        assertNotNull(g.infoFor("TOUT LE MONDE VEUT PRENDRE SA PLACE", t1300 - 30 * 60_000))
    }

    @Test
    fun entitiesSayWhatsOnAndNext() {
        val g = guide()
        assertEquals("sensor.epg_france2_fr", EpgEntities.entityId("France2.fr"))
        val (state, attrs) = EpgEntities.of(g.channels[0], g, t1300 - 30 * 60_000)
        assertEquals("Tout le monde veut prendre sa place", state)
        assertEquals("Journal 13h00", attrs["next_title"])
        assertEquals(50, attrs["progress"])
        assertEquals("https://logo/f2.png", attrs["entity_picture"])
        assertEquals(t1300, EpgEntities.nextChange(g, t1300 - 30 * 60_000))
    }

    @Test
    fun savedAndReadBack() {
        val g = guide()
        val back = EpgGuide.fromJson(g.toJson())!!
        assertEquals(g.showCount, back.showCount)
        assertEquals(g.available, back.available)
        assertEquals(g.now("France2.fr", t1300), back.now("France2.fr", t1300))
        assertTrue(EpgGuide.fromJson("nope") == null)
    }

    @Test
    fun aWebPageIsNoGuide() {
        val page = "<!DOCTYPE html><html><head><title>XML TV Fr</title></head><body>Guide</body></html>"
        val e = runCatching { EpgParse.read(TestTokens(page), { true }, 0L, Long.MAX_VALUE, null, {}, {}) }.exceptionOrNull()
        assertTrue(e is NotXmltv && e.root == "html")
    }
}
