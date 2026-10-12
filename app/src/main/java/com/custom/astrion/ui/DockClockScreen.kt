package com.custom.astrion.ui

import android.text.format.DateFormat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.config.DockConfig
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.HaLabels
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The dock's "clock" face ([DockConfig.MODE_CLOCK]): shown over the dashboard once the remote
 * has sat untouched on its dock for a while. Black, quiet and readable from across the room —
 * a big thin clock, a line with the date, the weather and the battery, and, only while
 * something is playing, a small now-playing block at the bottom. At night every colour turns
 * a dim amber. The whole face drifts a few dp each minute so nothing stays lit in one place.
 *
 * Any touch is swallowed here (so it never also presses whatever card sits underneath) and
 * reported through [onTouch]; MainActivity's dock monitor then brings the dashboard back.
 */
@Composable
fun DockClockScreen(config: DockConfig, entities: EntityMap, client: HaClient, isNight: () -> Boolean, onTouch: () -> Unit) {
    val context = LocalContext.current
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Wake right after the next minute boundary, not every second: the clock shows minutes.
            delay(MINUTE_MS - now % MINUTE_MS + 50)
        }
    }
    // Re-read on every minute tick (`now` changes), so dusk and dawn switch colours by themselves.
    val palette = if (now > 0 && isNight()) ClockPalette.NIGHT else ClockPalette.DAY
    val use24h = config.timeFormat?.let { it == 24 } ?: DateFormat.is24HourFormat(context)
    // A slow drift, one step per minute, so no pixel stays lit at the same spot all night.
    val minute = (now / MINUTE_MS).toInt()
    val driftX = DRIFT_STEPS_X[minute % DRIFT_STEPS_X.size]
    val driftY = DRIFT_STEPS_Y[(minute / DRIFT_STEPS_X.size) % DRIFT_STEPS_Y.size]
    val (battery, _) = rememberBatteryState(context)

    Box(
        modifier =
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    onTouch()
                }
            }
    ) {
        Column(
            modifier =
            Modifier
                .fillMaxSize()
                .offset(x = driftX.dp, y = driftY.dp)
                .padding(horizontal = CLOCK_SIDE_PADDING.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.weight(1f))
            ClockText(now, use24h, palette)
            Spacer(Modifier.height(18.dp))
            InfoLine(now, config, entities, battery, palette)
            Spacer(Modifier.weight(1f))
            nowPlayingEntity(config, entities)?.let { NowPlaying(it, client, palette) }
        }
    }
}

/** Day: soft white; night: dim amber, easier on the eyes in a dark room. */
private class ClockPalette(val primary: Color, val secondary: Color, val tertiary: Color, val tile: Color) {
    companion object {
        val DAY = ClockPalette(Color(0xB3E8ECEE), Color(0x99C9D0D4), Color(0x6699A3A8), Color(0xFF15191C))
        val NIGHT = ClockPalette(Color(0x99FFB36A), Color(0x80E59A55), Color(0x59B87B45), Color(0xFF140E09))
    }
}

@Composable
private fun ClockText(now: Long, use24h: Boolean, palette: ClockPalette) {
    val locale = Locale.getDefault()
    val time = SimpleDateFormat(if (use24h) "HH:mm" else "h:mm", locale).format(Date(now))
    // As big as the screen's width allows (whatever its density), up to 104 sp: "00:00" is about
    // 2.5 em wide in a light face, plus room for "am/pm" in 12-hour mode.
    val width = LocalConfiguration.current.screenWidthDp - CLOCK_SIDE_PADDING * 2
    val size = minOf(CLOCK_MAX_SP, width / if (use24h) CLOCK_EM_24H else CLOCK_EM_12H)
    Row(verticalAlignment = Alignment.Bottom) {
        Text(time, color = palette.primary, fontSize = size.sp, fontWeight = FontWeight.Light, maxLines = 1)
        if (!use24h) {
            Text(
                SimpleDateFormat("a", locale).format(Date(now)).lowercase(locale),
                color = palette.secondary,
                fontSize = 26.sp,
                modifier = Modifier.padding(start = 8.dp, bottom = 22.dp)
            )
        }
    }
}

/** The date, then "☀ 17° · 🔋 78%" on its own line — each part only when known/enabled. */
@Composable
private fun InfoLine(now: Long, config: DockConfig, entities: EntityMap, battery: Int, palette: ClockPalette) {
    val locale = Locale.getDefault()
    val date =
        SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, "EEEEdMMMM"), locale)
            .format(Date(now))
            .replaceFirstChar { it.titlecase(locale) }
    val weather = config.weatherEntity?.let { entities[it] }?.let { weatherText(it) }
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(date, color = palette.secondary, fontSize = 19.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (weather != null || config.showBattery) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                weather?.let { Text(it, color = palette.secondary, fontSize = 18.sp, maxLines = 1) }
                if (weather != null && config.showBattery) Dot(palette)
                if (config.showBattery) {
                    Icon(
                        Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        tint = palette.secondary,
                        modifier = Modifier.size(18.dp)
                    )
                    Text("$battery%", color = palette.secondary, fontSize = 18.sp, maxLines = 1)
                }
            }
        }
    }
}

@Composable
private fun Dot(palette: ClockPalette) {
    Text("·", color = palette.tertiary, fontSize = 19.sp)
}

private fun weatherText(e: EntityState): String? {
    val temp = e.attrDouble("temperature")?.let { "${Math.round(it)}°" }
    val symbol = weatherSymbol(e.state)
    return listOfNotNull(symbol, temp ?: HaLabels.weatherCondition(e.state)).joinToString(" ").ifBlank { null }
}

/** A plain text symbol per HA condition — no icon assets, renders on the HA100's Android 8.1. */
private fun weatherSymbol(condition: String): String? = when (condition) {
    "sunny", "clear" -> "☀"
    "clear-night" -> "☾"
    "partlycloudy" -> "⛅"
    "cloudy", "fog" -> "☁"
    "rainy", "pouring", "lightning-rainy" -> "☂"
    "lightning" -> "⚡"
    "snowy", "snowy-rainy", "hail" -> "❄"
    else -> null
}

/** The first configured `media_player` that is playing (or paused), else any that is playing. */
private fun nowPlayingEntity(config: DockConfig, entities: EntityMap): EntityState? {
    val active = setOf("playing", "paused")
    if (config.mediaEntities.isNotEmpty()) {
        return config.mediaEntities.mapNotNull { entities[it] }.firstOrNull { it.state in active && it.hasTitle() }
    }
    return entities.values.firstOrNull { it.entityId.startsWith("media_player.") && it.state == "playing" && it.hasTitle() }
}

private fun EntityState.hasTitle(): Boolean = !attrString("media_title").isNullOrBlank()

@Composable
private fun NowPlaying(e: EntityState, client: HaClient, palette: ClockPalette) {
    val artPath = e.attrString("entity_picture_local") ?: e.attrString("entity_picture")
    val art by produceState<ImageBitmap?>(initialValue = null, artPath) {
        value = artPath?.let { runCatching { client.fetchBitmap(it) }.getOrNull() }
    }
    val subtitle =
        listOfNotNull(e.attrString("media_series_title"), e.attrString("media_artist"))
            .firstOrNull { it.isNotBlank() }
    val source = e.attrString("app_name") ?: e.friendlyName
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier =
            Modifier
                .size(88.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(palette.tile),
            contentAlignment = Alignment.Center
        ) {
            val bitmap = art
            if (bitmap != null) {
                // Dimmed like the rest of the face — full-colour artwork glares on a black screen.
                Image(
                    bitmap,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    alpha = ART_ALPHA,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = palette.tertiary)
            }
        }
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                e.attrString("media_title").orEmpty(),
                color = palette.primary,
                fontSize = 20.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            subtitle?.let { Text(it, color = palette.secondary, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(source, color = palette.tertiary, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private const val MINUTE_MS = 60_000L
private const val CLOCK_SIDE_PADDING = 24
private const val CLOCK_MAX_SP = 104f
private const val CLOCK_EM_24H = 2.9f
private const val CLOCK_EM_12H = 3.7f
private const val ART_ALPHA = 0.6f
private val DRIFT_STEPS_X = intArrayOf(0, 4, 8, 4, 0, -4, -8, -4)
private val DRIFT_STEPS_Y = intArrayOf(0, 6, 12, 6, 0, -6)
