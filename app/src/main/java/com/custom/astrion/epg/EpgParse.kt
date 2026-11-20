package com.custom.astrion.epg

/** A channel of an XMLTV guide: its id ("France2.fr"), its name, its logo. */
data class EpgChannel(val id: String, val name: String, val icon: String?)

/** A programme: times in epoch milliseconds. */
data class EpgShow(
    val channel: String,
    val start: Long,
    val stop: Long,
    val title: String,
    val sub: String? = null,
    val desc: String? = null,
    val category: String? = null,
    val icon: String? = null
)

/**
 * A streaming XML reader, so a guide of any size is read without holding it: Android's
 * XmlPullParser on the remote, the JDK's StAX reader in the tests.
 */
interface XmlTokens {
    enum class Token { START, END, TEXT, DONE }

    fun next(): Token

    /** The element's name, on START and END. */
    val name: String

    /** One of the element's attributes, on START. */
    fun attr(name: String): String?

    /** The text, on TEXT. */
    val text: String
}

/**
 * XMLTV, the guide format of xmltvfr.fr, epgshare01 and most TV guides (1.6.0): `<channel>` then
 * `<programme start stop channel>` with `<title>`, `<sub-title>`, `<desc>`, `<category>`,
 * `<icon src>`, each maybe several times in several languages ([language] preferred, else the
 * first). Pure, so it is unit-tested.
 */
object EpgParse {
    /** "20261117103000 +0100" in epoch milliseconds; no offset: UTC. */
    fun time(s: String?): Long? {
        // By hand, not a Regex and a Calendar: a full guide has over 100 000 of them (1.6.0).
        val t = s?.trim() ?: return null
        if (t.length < MINUTES_END || (0 until MINUTES_END).any { !t[it].isDigit() }) return null
        fun num(from: Int, to: Int) = (from until to).fold(0) { acc, k -> acc * DEC + (t[k] - '0') }
        val seconds = if (t.length >= SECONDS_END && (MINUTES_END until SECONDS_END).all { t[it].isDigit() }) {
            num(
                MINUTES_END,
                SECONDS_END
            )
        } else {
            0
        }
        val days = daysFromCivil(num(0, 4), num(4, 6), num(6, 8))
        val local = ((days * HOURS_A_DAY + num(8, 10)) * MINUTES + num(10, 12)) * MINUTES + seconds
        return (local - offsetSeconds(t)) * MS
    }

    /** The "+0100" after the date, in seconds; none: UTC. */
    private fun offsetSeconds(t: String): Long {
        val k = t.indexOfAny(charArrayOf('+', '-'), MINUTES_END)
        if (k < 0 || k + OFFSET_LEN > t.length || (1 until OFFSET_LEN).any { !t[k + it].isDigit() }) return 0L
        val h = (t[k + 1] - '0') * DEC + (t[k + 2] - '0')
        val m = (t[k + 3] - '0') * DEC + (t[k + 4] - '0')
        val v = (h * MINUTES + m) * MINUTES.toLong()
        return if (t[k] == '-') -v else v
    }

    /** Days since 1970-01-01 of a proleptic Gregorian date (Howard Hinnant's algorithm). */
    private fun daysFromCivil(year: Int, month: Int, day: Int): Long {
        val y = (if (month <= 2) year - 1 else year).toLong()
        val era = (if (y >= 0) y else y - ERA_YEARS + 1) / ERA_YEARS
        val yoe = y - era * ERA_YEARS
        val mp = (month + (if (month > 2) -3 else 9)).toLong()
        val doy = (MONTH_DAYS_A * mp + 2) / MONTH_DAYS_B + day - 1
        val doe = yoe * YEAR_DAYS + yoe / 4 - yoe / CENTURY + doy
        return era * ERA_DAYS + doe - EPOCH_SHIFT
    }

    private const val DEC = 10
    private const val MINUTES_END = 12
    private const val SECONDS_END = 14
    private const val OFFSET_LEN = 5
    private const val HOURS_A_DAY = 24L
    private const val MINUTES = 60
    private const val MS = 1000L
    private const val ERA_YEARS = 400L
    private const val ERA_DAYS = 146_097L
    private const val YEAR_DAYS = 365L
    private const val CENTURY = 100L
    private const val MONTH_DAYS_A = 153L
    private const val MONTH_DAYS_B = 5L
    private const val EPOCH_SHIFT = 719_468L

    /**
     * Reads a guide: every channel to [channel], and to [show] the programmes of the channels
     * [keep] keeps that overlap [from]..[to].
     */
    fun read(
        x: XmlTokens,
        keep: (String) -> Boolean,
        from: Long,
        to: Long,
        language: String?,
        channel: (EpgChannel) -> Unit,
        show: (EpgShow) -> Unit
    ) {
        val r = Reader(x, language)
        while (true) {
            when (x.next()) {
                XmlTokens.Token.DONE -> return
                XmlTokens.Token.START -> when (x.name) {
                    "channel" -> r.channel()?.let(channel)
                    "programme" -> r.programme(keep, from, to)?.let(show)
                }
                else -> Unit
            }
        }
    }

    /** One element's texts by name, the preferred language's first. */
    private class Texts(private val language: String?) {
        private val values = HashMap<String, String>()
        private val preferred = HashSet<String>()

        fun put(name: String, lang: String?, value: String) {
            val v = value.trim()
            if (v.isEmpty()) return
            val isPreferred = language != null && lang != null && lang.startsWith(language, ignoreCase = true)
            if (name !in values || (isPreferred && name !in preferred)) {
                values[name] = v
                if (isPreferred) preferred += name
            }
        }

        operator fun get(name: String) = values[name]
    }

    private class Reader(private val x: XmlTokens, private val language: String?) {
        fun channel(): EpgChannel? {
            val id = x.attr("id")?.trim()?.takeIf { it.isNotEmpty() }
            val (texts, icon) = children("channel")
            return id?.let { EpgChannel(it, texts["display-name"] ?: it, icon) }
        }

        fun programme(keep: (String) -> Boolean, from: Long, to: Long): EpgShow? {
            // The channel first: most programmes of a big guide are skipped before reading a date.
            val ch = x.attr("channel")?.trim()
            if (ch == null || !keep(ch)) return skipped()
            val start = time(x.attr("start")) ?: return skipped()
            val stop = time(x.attr("stop")) ?: start
            if (start > to || stop < from) return skipped()
            val (t, icon) = children("programme")
            val title = t["title"] ?: return null
            return EpgShow(ch, start, stop, title, t["sub-title"], t["desc"], t["category"], icon)
        }

        private fun skipped(): EpgShow? {
            skip("programme")
            return null
        }

        /** The texts and the icon of the element [end] until it closes. */
        private fun children(end: String): Pair<Texts, String?> {
            val texts = Texts(language)
            var icon: String? = null
            var current: String? = null
            var lang: String? = null
            val buf = StringBuilder()
            while (true) {
                when (x.next()) {
                    XmlTokens.Token.START -> {
                        if (x.name == "icon" && icon == null) icon = x.attr("src")?.takeIf { it.isNotBlank() }
                        current = x.name
                        lang = x.attr("lang")
                        buf.setLength(0)
                    }
                    XmlTokens.Token.TEXT -> if (current != null) buf.append(x.text)
                    XmlTokens.Token.END -> {
                        if (x.name == end) return texts to icon
                        if (x.name == current) texts.put(current, lang, buf.toString())
                        current = null
                    }
                    XmlTokens.Token.DONE -> return texts to icon
                }
            }
        }

        private fun skip(end: String) {
            var depth = 0
            while (true) {
                when (x.next()) {
                    XmlTokens.Token.START -> depth++
                    XmlTokens.Token.END -> if (depth == 0 && x.name == end) return else depth--
                    XmlTokens.Token.DONE -> return
                    else -> Unit
                }
            }
        }
    }
}
