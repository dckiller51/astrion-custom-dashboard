package com.custom.astrion.cards.impl

import com.custom.astrion.ha.EntityState

/**
 * What the `xbox` card shows, read from Home Assistant entities — pure, so it's unit-tested.
 * Two sources with their own entities and attributes:
 *  - the official **Xbox** integration: `sensor.<gamertag>_now_playing` (genres, developer,
 *    `achievements: "29 / 0"`, `gamerscore: "570 / 4360"`, `progress: "13 %"`…), the profile
 *    `binary_sensor.<gamertag>` (gamerpic, online), counters (gamerscore, friends, followers…);
 *  - **TrueAchievements** (custom component, TA Pro account): `sensor.trueachievements_<tag>_*`,
 *    the game sensor with `achievements: "29 / 139"`, `gamerscore: "570 G / 4360 G"`,
 *    `ta_score`, `game_completion`, `game_ratio`, `hours_played`, links… and a sync problem
 *    `binary_sensor` with `last_update`.
 */
internal object XboxModel {
    /** "570 G / 4360 G", "29 / 139", "1264 TA / 25753 TA" → done / total (total 0 = unknown). */
    data class Fraction(val done: Long, val total: Long) {
        /** 0..1, or null when the total isn't known (the Xbox integration reports "29 / 0"). */
        val ratio: Float? get() = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null

        companion object {
            private val PATTERN = Regex("""^\s*([\d\s.,]+?)\s*[A-Za-z]*\s*/\s*([\d\s.,]+?)\s*[A-Za-z]*\s*$""")

            fun parse(text: String?): Fraction? {
                val m = PATTERN.find(text ?: return null) ?: return null
                val done = digits(m.groupValues[1]) ?: return null
                return Fraction(done, digits(m.groupValues[2]) ?: 0)
            }

            private fun digits(s: String) = s.filter { it.isDigit() }.takeIf { it.isNotEmpty() }?.toLongOrNull()
        }
    }

    /** One progress bar of the game block. [kind] picks its label. */
    data class Bar(val kind: Kind, val fraction: Fraction, val text: String) {
        enum class Kind { ACHIEVEMENTS, GAMERSCORE, TA_SCORE }
    }

    /** A short value under the bars: completion, ratio, hours played. */
    data class Chip(val kind: Kind, val value: String) {
        enum class Kind { COMPLETION, RATIO, HOURS }
    }

    data class Game(
        val playing: Boolean,
        val title: String,
        val subtitle: String?,
        val bars: List<Bar>,
        val chips: List<Chip>
    )

    private val OFF_STATES = setOf("", "unknown", "unavailable", "offline_status", "none", "offline", "off")

    fun isPlaying(e: EntityState?): Boolean = e != null && e.state.trim().lowercase() !in OFF_STATES

    fun game(e: EntityState?): Game? {
        if (e == null || !isPlaying(e)) return null
        val title = e.attrString("name")?.takeIf { it.isNotBlank() } ?: e.state
        val subtitle = listOfNotNull(e.attrString("platform"), e.attrString("publisher")).filter { it.isNotBlank() }.joinToString(" · ")
        val bars =
            listOfNotNull(
                bar(Bar.Kind.ACHIEVEMENTS, e.attrString("achievements")),
                bar(Bar.Kind.GAMERSCORE, e.attrString("gamerscore")),
                bar(Bar.Kind.TA_SCORE, e.attrString("ta_score"))
            )
        val chips =
            listOfNotNull(
                (e.attrString("game_completion") ?: e.attrString("progress"))?.let { Chip(Chip.Kind.COMPLETION, it.replace(" %", "%")) },
                e.attrString("game_ratio")?.let { Chip(Chip.Kind.RATIO, it) },
                e.attrString("hours_played")?.let { Chip(Chip.Kind.HOURS, it) }
            )
        return Game(true, title, subtitle.ifBlank { null }, bars, chips)
    }

    private fun bar(kind: Bar.Kind, text: String?): Bar? {
        val f = Fraction.parse(text) ?: return null
        // "29 / 0" from the Xbox integration: no total, so no bar; the text alone is still worth showing.
        return Bar(kind, f, text!!.trim())
    }

    /** 833634 → "833 634"; 96.4 → "96.4"; text as is. Narrow no-break spaces, as French typography wants. */
    fun formatNumber(value: String): String {
        val n = value.toDoubleOrNull() ?: return value
        if (n % 1.0 != 0.0) return value
        return n.toLong().toString().reversed().chunked(GROUP).joinToString(" ").reversed()
    }

    /** A counter tile's value: the number, plus its unit when it's a symbol (G, %). */
    fun statValue(e: EntityState): String {
        val unit = e.attrString("unit_of_measurement").orEmpty()
        val value = formatNumber(e.state)
        return if (unit in SHORT_UNITS) "$value $unit" else value
    }

    /** A counter tile's label: its name without the account part ("TrueAchievements (dckillerpp) Gamerscore" → "Gamerscore"). */
    fun statLabel(e: EntityState, gamertag: String? = null): String {
        var name = e.friendlyName
        name = name.replace(Regex("""^TrueAchievements\s*\([^)]*\)\s*"""), "")
        val tag = gamertag ?: e.entityId.substringAfter('.').substringBefore('_')
        if (tag.isNotBlank() && name.startsWith("$tag ", ignoreCase = true)) name = name.substring(tag.length + 1)
        return name.ifBlank { e.friendlyName }
    }

    /** What kind of counter a tile shows, for its icon: from the entity's MDI icon or unit. */
    enum class StatKind { GAMERSCORE, TA_SCORE, PERCENT, FINISHED, GAMES, ACHIEVEMENTS, PEOPLE, OTHER }

    /** A counter's icon (1.3.7): a built-in one, none, or an uploaded icon file. */
    sealed class StatIcon {
        data class BuiltIn(val kind: StatKind) : StatIcon()
        data object None : StatIcon()
        data class File(val path: String) : StatIcon()
    }

    /** The built-in icons by the name a counter's `icon` option uses. */
    val ICON_NAMES =
        mapOf(
            "gamerscore" to StatKind.GAMERSCORE,
            "star" to StatKind.TA_SCORE,
            "percent" to StatKind.PERCENT,
            "trophy" to StatKind.FINISHED,
            "medal" to StatKind.ACHIEVEMENTS,
            "controller" to StatKind.GAMES,
            "people" to StatKind.PEOPLE,
            "chart" to StatKind.OTHER
        )

    /** `icon`: empty / `auto` → by the entity, `none`, a built-in name, or a file path / URL. */
    fun statIcon(option: String?, e: EntityState): StatIcon {
        val v = option?.trim().orEmpty()
        return when {
            v.isEmpty() || v == "auto" -> StatIcon.BuiltIn(statKind(e))
            v == "none" -> StatIcon.None
            v in ICON_NAMES -> StatIcon.BuiltIn(ICON_NAMES.getValue(v))
            else -> StatIcon.File(v)
        }
    }

    fun statKind(e: EntityState): StatKind {
        val icon = e.attrString("icon").orEmpty()
        val unit = e.attrString("unit_of_measurement").orEmpty()
        return when {
            icon == "mdi:alpha-g-circle" || unit == "G" || unit == "points" -> StatKind.GAMERSCORE
            icon == "mdi:star-circle" || unit == "TA" -> StatKind.TA_SCORE
            icon == "mdi:percent" || unit == "%" -> StatKind.PERCENT
            icon == "mdi:trophy" -> StatKind.FINISHED
            icon == "mdi:trophy-variant" -> StatKind.ACHIEVEMENTS
            icon == "mdi:controller" || unit == "games" -> StatKind.GAMES
            unit == "people" -> StatKind.PEOPLE
            else -> StatKind.OTHER
        }
    }

    /** Attributes the details sheet leaves out: technical, or already shown as the picture/title. */
    val HIDDEN_ATTRIBUTES =
        setOf(
            "entity_picture", "friendly_name", "icon", "unit_of_measurement", "state_class", "device_class",
            "access_token", "supported_features", "options", "attribution"
        )

    private val SHORT_UNITS = setOf("G", "%")
    private const val GROUP = 3
}
