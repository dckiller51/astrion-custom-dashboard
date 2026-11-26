package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import java.text.DateFormat
import java.util.Date
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/** A source's sheet, as the player answers `astrion/source_details` (1.6.3: a PC's game). */
private class Sheet(val o: JsonObject) {
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    fun num(k: String) = (o[k] as? JsonPrimitive)?.longOrNull ?: 0L

    fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull == true

    fun list(k: String) = (o[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }.orEmpty()
}

/**
 * The sheet a tap on a cover opens, in the sources pop-up (1.6.3), as a film's: the background,
 * the cover, the title, year, studio and genres, play time, the description, and **Launch**
 * (**Turn on and launch** while the PC is off). [cover] shows at once; the rest when the player answers.
 */
@Composable
internal fun SourceSheet(ctx: CardContext, entityId: String, source: String, cover: String?, onBack: () -> Unit, onLaunch: () -> Unit) {
    val theme = ctx.theme
    val sheet by produceState<Sheet?>(initialValue = null, entityId, source) {
        val fields = JsonObject(mapOf("entity_id" to JsonPrimitive(entityId), "source" to JsonPrimitive(source)))
        val reply = runCatching { ctx.client.request(SHEET_REQUEST, fields) }.getOrNull()
        val ok = (reply?.get("success") as? JsonPrimitive)?.booleanOrNull == true
        value = (reply?.get("result") as? JsonObject)?.takeIf { ok }?.let { Sheet(it) }
    }
    val s = sheet
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(
            modifier = Modifier.heightIn(max = BODY_MAX_HEIGHT.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            s?.str("background")?.let { Background(ctx, it) }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                SheetCover(ctx, s?.str("cover") ?: cover, source)
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        source,
                        color = theme.primaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis
                    )
                    s?.let { Facts(theme, it) }
                }
            }
            if (s != null) {
                Text(
                    s.str("description") ?: stringResource(R.string.game_sheet_no_description),
                    color = if (s.str("description") != null) theme.primaryText else theme.mutedText,
                    fontSize = 13.sp,
                    lineHeight = 18.sp
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            SheetButton(
                theme,
                Icons.AutoMirrored.Filled.ArrowBack,
                stringResource(R.string.game_sheet_back),
                primary = false,
                onClick = onBack
            )
            val launch = if (s != null && !s.bool("reachable")) R.string.game_sheet_wake_launch else R.string.game_sheet_launch
            SheetButton(
                theme,
                Icons.Filled.PlayArrow,
                stringResource(launch),
                primary = true,
                modifier = Modifier.weight(1f),
                onClick = onLaunch
            )
        }
    }
}

/** Year · studio, genres, scores, play time and last session: the lines that are known. */
@Composable
private fun Facts(theme: ThemeColors, s: Sheet) {
    val lines =
        listOfNotNull(
            listOfNotNull(s.num("year").takeIf { it > 0 }?.toString(), s.list("studios").take(2).joinToString(", ").ifBlank { null })
                .joinToString(" · ")
                .ifBlank { null },
            s.list("genres").take(MAX_GENRES).joinToString(", ").ifBlank { null },
            listOfNotNull(
                s.num("critic").takeIf { it > 0 }?.let { stringResource(R.string.game_sheet_critic, it.toInt()) },
                s.num("community").takeIf { it > 0 }?.let { stringResource(R.string.game_sheet_community, it.toInt()) }
            ).joinToString(" · ").ifBlank { null },
            playtime(s.num("playtime")),
            s.num("last_played").takeIf { it > 0 }?.let {
                stringResource(R.string.game_sheet_last_played, DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * MS_PER_S)))
            }
        )
    lines.forEach { Text(it, color = theme.mutedText, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis) }
}

@Composable
private fun playtime(seconds: Long): String {
    if (seconds <= 0) return stringResource(R.string.game_sheet_never_played)
    val hours = seconds / S_PER_HOUR
    val time =
        if (hours > 0) {
            stringResource(
                R.string.game_sheet_hours,
                hours.toInt()
            )
        } else {
            stringResource(R.string.game_sheet_minutes, (seconds / S_PER_MIN).toInt().coerceAtLeast(1))
        }
    return stringResource(R.string.game_sheet_playtime, time)
}

@Composable
private fun Background(ctx: CardContext, path: String) {
    val px = with(LocalDensity.current) { BACKGROUND_WIDTH.dp.toPx() }.toInt()
    val image = rememberImage(ctx, path, px) ?: return
    Image(
        image,
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = Modifier.fillMaxWidth().aspectRatio(BACKGROUND_RATIO).clip(RoundedCornerShape(12.dp))
    )
}

@Composable
private fun SheetCover(ctx: CardContext, path: String?, name: String) {
    val px = with(LocalDensity.current) { COVER_WIDTH.dp.toPx() }.toInt()
    val image = rememberImage(ctx, path, px)
    Box(
        modifier =
        Modifier
            .width(COVER_WIDTH.dp)
            .aspectRatio(COVER_RATIO)
            .clip(RoundedCornerShape(8.dp))
            .background(ctx.theme.insetSurface),
        contentAlignment = Alignment.Center
    ) {
        if (image != null) {
            Image(image, contentDescription = name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun SheetButton(
    theme: ThemeColors,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    primary: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val shape = RoundedCornerShape(50)
    Row(
        modifier =
        modifier
            .clip(shape)
            .background(if (primary) theme.accent else theme.controlBackground)
            .tapClickable(focusShape = shape, onClickLabel = label, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val color = if (primary) theme.background else theme.primaryText
        Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(18.dp))
        Text(label, color = color, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private const val SHEET_REQUEST = "astrion/source_details"
private const val BODY_MAX_HEIGHT = 430
private const val BACKGROUND_WIDTH = 308
private const val BACKGROUND_RATIO = 16f / 9f
private const val COVER_WIDTH = 84
private const val COVER_RATIO = 2f / 3f
private const val MAX_GENRES = 4
private const val MS_PER_S = 1000L
private const val S_PER_HOUR = 3600L
private const val S_PER_MIN = 60L
