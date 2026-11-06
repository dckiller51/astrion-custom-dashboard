package com.custom.astrion.ha

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Times as Home Assistant writes them in attributes (`media_position_updated_at`…). */
object HaTime {
    /** [unixMs] as an ISO 8601 instant in UTC, e.g. `2026-11-05T10:15:30.000Z`. */
    fun isoInstant(unixMs: Long): String {
        val formatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US)
        formatter.timeZone = TimeZone.getTimeZone("UTC")
        return formatter.format(Date(unixMs))
    }
}
