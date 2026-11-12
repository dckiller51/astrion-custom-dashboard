package com.custom.astrion.cards.impl

/** One block of an info card, as read from its `blocks` option. */
internal sealed class InfoBlock {
    data class Image(val url: String, val ratio: Float) : InfoBlock()

    data class Text(val text: String, val size: String, val lines: Int, val marquee: Boolean, val align: String) : InfoBlock()

    companion object {
        /** The blocks of [raw] (the `blocks` option), the unknown or empty ones left out. */
        fun listOf(raw: Any?): List<InfoBlock> = (raw as? List<*>).orEmpty().mapNotNull { b ->
            val m = b as? Map<*, *> ?: return@mapNotNull null
            when (m["type"]) {
                "image" -> (m["url"] as? String)?.takeIf { it.isNotBlank() }?.let { Image(it.trim(), ratioOf(m["ratio"])) }
                "text" -> (m["text"] as? String)?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        size = m["size"] as? String ?: "body",
                        lines = (m["lines"] as? Number)?.toInt() ?: 0,
                        marquee = m["marquee"] == true,
                        align = m["align"] as? String ?: "start"
                    )
                }
                else -> null
            }
        }

        fun ratioOf(v: Any?): Float = when (v) {
            "wide" -> 16f / 9f
            "square" -> 1f
            else -> 2f / 3f
        }
    }
}
