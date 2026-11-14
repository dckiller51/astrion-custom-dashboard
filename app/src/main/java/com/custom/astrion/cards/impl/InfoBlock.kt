package com.custom.astrion.cards.impl

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** One block of an info card, as read from its `blocks` option; [template] is what Home Assistant (or the remote) renders. */
internal sealed class InfoBlock {
    abstract val template: String

    /** Whether the block has something to show once [rendered] (empty, missing values: hidden). */
    abstract fun visible(rendered: String?): Boolean

    data class Image(val url: String, val ratio: Float) : InfoBlock() {
        override val template get() = url

        override fun visible(rendered: String?): Boolean = imageUrl(rendered) != null
    }

    data class Text(
        val text: String,
        val size: String,
        val lines: Int,
        val marquee: Boolean,
        val align: String,
        /** Hidden when a value in it is missing (1.5.7): "Next at None: None" after the day's last programme. */
        val hideMissing: Boolean = true
    ) : InfoBlock() {
        override val template get() = text

        override fun visible(rendered: String?): Boolean {
            val t = rendered?.trim().orEmpty()
            return t.isNotEmpty() && !(hideMissing && hasMissing(t))
        }
    }

    /**
     * A row of pictures from a list (1.5.7): the cast of a film, with photo, name and role. [source]
     * renders to a list of objects (`{{ state_attr('sensor.kodi_movie_credits', 'cast') }}`);
     * [image], [title] and [subtitle] take an item's fields as `{field}`.
     */
    data class ListRow(
        val source: String,
        val image: String,
        val title: String,
        val subtitle: String,
        val limit: Int,
        val ratio: Float,
        val round: Boolean
    ) : InfoBlock() {
        override val template get() = source

        override fun visible(rendered: String?): Boolean = itemsOf(rendered).isNotEmpty()

        /** The items, each with [image], [title] and [subtitle] filled in. */
        fun items(rendered: String?): List<Item> = itemsOf(rendered).take(limit).map {
            Item(imageUrl(fill(image, it, keep = true)), fill(title, it).trim(), fill(subtitle, it).trim())
        }

        data class Item(val image: String?, val title: String, val subtitle: String)
    }

    companion object {
        private val NONE = Regex("""\bNone\b""")
        private val FIELD = Regex("""\{([A-Za-z0-9_]+)\}""")
        private val UNSET = setOf("unknown", "unavailable")
        private val FALSY = setOf("false", "off", "no", "0", "none", "unknown", "unavailable", "standby", "idle")
        private const val DEFAULT_LIMIT = 10

        /** The blocks of [raw] (the `blocks` option), the unknown or empty ones left out. */
        fun listOf(raw: Any?): List<InfoBlock> = (raw as? List<*>).orEmpty().mapNotNull { b ->
            val m = b as? Map<*, *> ?: return@mapNotNull null
            when (m["type"]) {
                "image" -> (m["url"] as? String)?.takeIf { it.isNotBlank() }?.let { Image(it.trim(), ratioOf(m["ratio"])) }
                "text" -> textOf(m)
                "list" -> listRowOf(m)
                else -> null
            }
        }

        private fun textOf(m: Map<*, *>): Text? = (m["text"] as? String)?.takeIf { it.isNotBlank() }?.let {
            Text(
                text = it,
                size = m["size"] as? String ?: "body",
                lines = (m["lines"] as? Number)?.toInt() ?: 0,
                marquee = m["marquee"] == true,
                align = m["align"] as? String ?: "start",
                hideMissing = m["hide_missing"] != false
            )
        }

        private fun listRowOf(m: Map<*, *>): ListRow? = (m["source"] as? String)?.takeIf { it.isNotBlank() }?.let {
            ListRow(
                source = it.trim(),
                image = m["image"] as? String ?: "",
                title = m["title"] as? String ?: "",
                subtitle = m["subtitle"] as? String ?: "",
                limit = ((m["limit"] as? Number)?.toInt() ?: DEFAULT_LIMIT).coerceAtLeast(1),
                ratio = if (m["ratio"] == "round") 1f else ratioOf(m["ratio"]),
                round = m["ratio"] == "round"
            )
        }

        fun ratioOf(v: Any?): Float = when (v) {
            "wide" -> 16f / 9f
            "square" -> 1f
            else -> 2f / 3f
        }

        /** An image's URL once rendered, or null for none yet (empty, ends in `None`, `null` or `/`, or a field left unfilled). */
        fun imageUrl(rendered: String?): String? = rendered?.trim()?.takeUnless {
            it.isEmpty() || it.endsWith("None") || it.endsWith("null") || it.endsWith("/") || "{" in it
        }

        /** A value printed as missing: `None` in it, or the whole text unknown / unavailable. */
        fun hasMissing(text: String): Boolean = NONE.containsMatchIn(text) || text.lowercase() in UNSET

        /** The objects of a rendered list (JSON, as Home Assistant renders a list attribute), their fields as text. */
        fun itemsOf(rendered: String?): List<Map<String, String>> {
            val text = rendered?.trim()?.takeIf { it.startsWith("[") } ?: return emptyList()
            val array = runCatching { Json.parseToJsonElement(text) as? JsonArray }.getOrNull() ?: return emptyList()
            return array.mapNotNull { e ->
                when (e) {
                    is JsonObject -> e.mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
                    is JsonPrimitive -> e.contentOrNull?.let { mapOf("value" to it) }
                    else -> null
                }
            }
        }

        /**
         * [pattern] with each `{field}` of [item] put in; one the item lacks is left out, or kept
         * with [keep] (an image then isn't shown).
         */
        fun fill(pattern: String, item: Map<String, String>, keep: Boolean = false): String =
            FIELD.replace(pattern) { m -> item[m.groupValues[1]] ?: if (keep) m.value else "" }

        /**
         * Whether the card shows (1.5.7, `visible_if`): hidden when it renders to nothing, false, off,
         * no, 0, None, unknown, unavailable, standby or idle.
         */
        fun truthy(rendered: String?): Boolean = rendered?.trim()?.lowercase()?.let { it.isNotEmpty() && it !in FALSY } ?: false
    }
}
