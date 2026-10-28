package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.HaLabels
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.weather.LocalWeather
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Clock + weather header. Big local time (device clock), date, current
 * condition + temperature from a `weather.*` entity, and — below the current
 * info, full width — a multi-day forecast with a min→max temperature gradient
 * bar per day (styled like the HA clock-weather-card).
 *
 * Forecast data comes from the `weather.get_forecasts` service (modern HA
 * dropped the `forecast` attribute), falling back to the attribute if present.
 *
 * The weather condition label (e.g. "partly cloudy") is translated via
 * `HaLabels.weatherCondition()` — see assets/ha_labels/<lang>.json — instead
 * of prettifying the raw HA state string, so it follows the app's language.
 *
 * Config shape:
 *   { "type": "clock_weather", "options": {
 *       "entity_id": "weather.forecast_home",
 *       "time_format": 12,
 *       "forecast_rows": 4,
 *       "calendar_entity": "calendar.family"
 *   } }
 */
class ClockWeatherCard : CardRenderer {
    override val type = "clock_weather"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: "weather.forecast_home"
        val e = ctx.entities[entityId]
        val is24 = config.int("time_format", 12) == 24
        val forecastRows = config.int("forecast_rows", 4)
        val calendarEntity = config.string("calendar_entity")

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(10_000)
            }
        }
        val timeFmt =
            remember(is24) {
                SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
            }
        val dateFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }

        // Fetch the forecast via the service; refresh every 30 min. The built-in weather (1.3.2) is
        // re-read each time the remote fetches it, so the first forecast shows without waiting.
        var forecast by remember { mutableStateOf<List<Forecast>>(emptyList()) }
        val localUpdate = if (entityId == LocalWeather.ENTITY_ID) e?.lastUpdated else null
        LaunchedEffect(entityId, localUpdate) {
            while (true) {
                val arr = ctx.client.getForecast(entityId)
                val parsed = arr?.let { parseForecast(it) }.orEmpty()
                if (parsed.isNotEmpty()) forecast = parsed
                delay(30 * 60 * 1000L)
            }
        }

        val condition = e?.state ?: "unknown"
        val temp = e?.attrDouble("temperature")

        // Today's calendar event, if the entity's next/current event happens
        // to fall today — cheap to compute (no extra HTTP call), and kept to
        // one thin line so it barely adds height.
        val todayEvent = calendarEntity?.let { todaysEvent(ctx.entities[it], now, is24) }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ctx.theme.cardSurface)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Current info.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emojiFor(condition), fontSize = 34.sp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        timeFmt.format(Date(now)),
                        color = ctx.theme.primaryText,
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Light
                    )
                    Text(dateFmt.format(Date(now)), color = ctx.theme.mutedText, fontSize = 13.sp)
                    if (todayEvent != null) {
                        Text(
                            todayEvent,
                            color = ctx.theme.accent,
                            fontSize = 11.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        temp?.let { "${trim(it)}°" } ?: "—",
                        color = ctx.theme.primaryText,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        HaLabels.weatherCondition(condition),
                        color = ctx.theme.mutedText,
                        fontSize = 11.sp
                    )
                }
            }

            // Full-width forecast rows.
            val shown = forecast.take(forecastRows)
            if (shown.isNotEmpty()) {
                val temps = shown.flatMap { listOfNotNull(it.low, it.high) }
                val weekMin = temps.minOrNull() ?: 0.0
                val weekMax = temps.maxOrNull() ?: 1.0
                val span = (weekMax - weekMin).coerceAtLeast(1.0)

                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    shown.forEach { f -> ForecastRow(f, weekMin, span, ctx.theme) }
                }
            }
        }
    }

    @Composable
    private fun ForecastRow(f: Forecast, weekMin: Double, span: Double, theme: ThemeColors) {
        val lowFrac = (((f.low ?: weekMin) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        val highFrac = (((f.high ?: (weekMin + span)) - weekMin) / span).toFloat().coerceIn(0f, 1f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(f.day, color = theme.mutedText, fontSize = 13.sp, modifier = Modifier.width(40.dp))
            Text(
                emojiFor(f.condition),
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.width(30.dp)
            )
            Text(
                f.low?.let { "${trim(it)}°" } ?: "",
                color = theme.mutedText,
                fontSize = 13.sp,
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp)
            )
            Spacer(Modifier.width(8.dp))
            // Min→max gradient bar, positioned within the week's range via weights.
            Box(
                modifier =
                Modifier
                    .weight(1f)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(theme.controlBackground)
            ) {
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.weight(lowFrac.coerceAtLeast(0.001f)))
                    Box(
                        modifier =
                        Modifier
                            .weight((highFrac - lowFrac).coerceAtLeast(0.03f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(theme.success, theme.success.copy(alpha = 0.55f))
                                )
                            )
                    )
                    Spacer(Modifier.weight((1f - highFrac).coerceAtLeast(0.001f)))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                f.high?.let { "${trim(it)}°" } ?: "",
                color = theme.primaryText,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp)
            )
        }
    }

    private data class Forecast(val day: String, val condition: String, val low: Double?, val high: Double?)

    private fun parseForecast(arr: JsonArray): List<Forecast> {
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null

            fun str(k: String) = (o[k] as? JsonPrimitive)?.content

            fun dbl(k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val dt = str("datetime")
            val day = dt?.let { runCatching { dayFmt.format(parser.parse(it.take(16))!!) }.getOrNull() } ?: ""
            Forecast(day, str("condition") ?: "unknown", dbl("templow"), dbl("temperature"))
        }
    }

    /**
     * The calendar entity's `message`/`start_time`/`end_time` attributes
     * describe its next (or current) event — HA doesn't expose a full list
     * over the WebSocket state, only this one. Show it only if that event's
     * date is today, so a distant next event doesn't sit here for days.
     */
    private fun todaysEvent(e: com.custom.astrion.ha.EntityState?, nowMs: Long, is24: Boolean): String? {
        e ?: return null
        val message = e.attrString("message") ?: return null
        val startStr = e.attrString("start_time") ?: return null
        val allDay = (e.attr("all_day") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val start = runCatching { fmt.parse(startStr) }.getOrNull() ?: return null

        val dayFmt = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        if (dayFmt.format(start) != dayFmt.format(Date(nowMs))) return null

        if (allDay) return message
        // Same 12/24 h choice as the clock above (it used to be always 12 h).
        val timeFmt = SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
        val endStr = e.attrString("end_time")
        val end = endStr?.let { runCatching { fmt.parse(it) }.getOrNull() }
        val range = if (end != null) "${timeFmt.format(start)}–${timeFmt.format(end)}" else timeFmt.format(start)
        return "$message · $range"
    }

    private fun trim(d: Double): String = if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

    /** Map HA weather condition strings to a simple emoji — no icon assets needed. */
    private fun emojiFor(condition: String): String = when (condition) {
        "sunny", "clear" -> "☀️"
        "clear-night" -> "🌙"
        "partlycloudy" -> "⛅"
        "cloudy" -> "☁️"
        // Not 🌫️: Android 8.1's emoji font (the HA100's) draws it as a plain grey square.
        "fog" -> "☁️"
        "rainy", "pouring" -> "🌧️"
        "lightning", "lightning-rainy" -> "⛈️"
        "snowy", "snowy-rainy" -> "❄️"
        "windy", "windy-variant" -> "💨"
        "hail" -> "🌨️"
        else -> "🌡️"
    }
}
