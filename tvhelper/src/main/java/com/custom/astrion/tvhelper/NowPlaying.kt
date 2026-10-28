package com.custom.astrion.tvhelper

/**
 * What's playing on the TV, as the app in front tells Android in its media session. Every field
 * but [state] may be missing: apps share what they choose to (Plex: title, position, cover;
 * myCANAL: only playing/paused).
 */
data class NowPlaying(
    /** Package of the app owning the session, e.g. `com.plexapp.android`. */
    val packageName: String? = null,
    /** Its name as the TV shows it ("Plex"). */
    val appName: String? = null,
    /** `playing`, `paused`, `buffering`, `idle`, or `none` when no app has a media session. */
    val state: String = STATE_NONE,
    val title: String? = null,
    val subtitle: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val durationMs: Long? = null,
    /** Position at [positionUpdatedAt] (epoch ms); it moves on at [speed] while playing. */
    val positionMs: Long? = null,
    val positionUpdatedAt: Long? = null,
    val speed: Float = 1f,
    /** A cover the app only gives as a web address (no image in the session itself). */
    val artUrl: String? = null
) {
    /** The `/now-playing` answer: this, plus the [version] to wait from and whether `/art` has an image. */
    fun toJson(version: Long, artVersion: Long?): String = Json.obj(
        "version" to version,
        "package" to packageName,
        "app" to appName,
        "state" to state,
        "title" to title,
        "subtitle" to subtitle,
        "artist" to artist,
        "album" to album,
        "durationMs" to durationMs,
        "positionMs" to positionMs,
        "positionUpdatedAt" to positionUpdatedAt,
        "speed" to speed.toDouble(),
        "artVersion" to artVersion,
        "artUrl" to artUrl
    )

    companion object {
        const val STATE_PLAYING = "playing"
        const val STATE_PAUSED = "paused"
        const val STATE_BUFFERING = "buffering"
        const val STATE_IDLE = "idle"
        const val STATE_NONE = "none"
    }
}

/** Just enough JSON writing for the helper's answers (no library on purpose: the APK stays tiny). */
object Json {
    fun obj(vararg fields: Pair<String, Any?>): String = fields.joinToString(",", "{", "}") { (k, v) -> "${str(k)}:${value(v)}" }

    fun value(v: Any?): String = when (v) {
        null -> "null"
        is Boolean -> v.toString()
        is Int, is Long -> v.toString()
        is Double -> if (v.isFinite()) v.toString() else "null"
        is Float -> value(v.toDouble())
        else -> str(v.toString())
    }

    fun str(s: String): String {
        val out = StringBuilder(s.length + 2).append('"')
        for (c in s) {
            when {
                c == '"' -> out.append("\\\"")
                c == '\\' -> out.append("\\\\")
                c == '\n' -> out.append("\\n")
                c == '\r' -> out.append("\\r")
                c == '\t' -> out.append("\\t")
                c < ' ' -> out.append("\\u%04x".format(c.code))
                else -> out.append(c)
            }
        }
        return out.append('"').toString()
    }
}
