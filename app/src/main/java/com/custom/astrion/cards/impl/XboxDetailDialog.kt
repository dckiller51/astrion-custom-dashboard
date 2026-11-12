package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ScreenCapture
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.tapClickable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Long-press details of an `xbox` card part (1.3.7): like Home Assistant's "more info" — the
 * picture, the state, then every attribute with a readable name (TrueAchievements: TA points,
 * ratio, hours, guide links…; Xbox: genres, developer, release date, description…).
 */
@Composable
internal fun XboxDetailDialog(ctx: CardContext, e: EntityState, onClose: () -> Unit) {
    val theme = ctx.theme
    Dialog(onDismissRequest = onClose) {
        ScreenCapture.TrackForScreenshots()
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .width(340.dp)
                .heightIn(max = 560.dp)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                val px = with(LocalDensity.current) { PICTURE_DP.dp.toPx() }.toInt()
                rememberImage(ctx, e.attrString("entity_picture"), px)?.let {
                    Image(
                        it,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(PICTURE_DP.dp).clip(RoundedCornerShape(12.dp))
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        e.attrString("name") ?: XboxModel.formatNumber(e.state),
                        color = theme.primaryText,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(e.friendlyName, color = theme.mutedText, fontSize = 12.sp)
                }
            }
            attributeRows(e).forEach { (key, value) ->
                Column {
                    Text(attributeLabel(key), color = theme.mutedText, fontSize = 11.sp)
                    Text(value, color = theme.primaryText, fontSize = 14.sp)
                }
            }
            Text(
                stringResource(R.string.close),
                color = theme.accent,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                modifier =
                Modifier
                    .align(Alignment.End)
                    .clip(RoundedCornerShape(12.dp))
                    .tapClickable(onClick = onClose)
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
    }
}

/** The attributes to list: known ones first, in a sensible order, then any other; never the technical ones. */
internal fun attributeRows(e: EntityState): List<Pair<String, String>> {
    // "game_names" repeats "games" (TrueAchievements' Lost Completions): only the richer one is shown.
    val skip = if (e.attributes["games"] is JsonArray) setOf("game_names") else emptySet()
    val values =
        e.attributes.entries
            .filter { (k, _) -> k !in XboxModel.HIDDEN_ATTRIBUTES && k != "name" && k !in skip }
            .mapNotNull { (k, v) -> attributeText(v)?.takeIf { it.isNotBlank() && it != "null" }?.let { k to it } }
            .filterNot { (k, v) -> XboxModel.isPlaceholder(k, v) }
            .toMap()
    val known = ATTRIBUTE_ORDER.filter { it in values }
    return (known + (values.keys - known.toSet()).sorted()).map { it to values.getValue(it) }
}

/**
 * An attribute as text: a plain value, or a list one item per line (1.4.5) — e.g. TrueAchievements'
 * Lost Completions "games": "Halo Infinite (Xbox Series): 120 / 135".
 */
private fun attributeText(v: JsonElement): String? = when (v) {
    is JsonPrimitive -> v.content
    is JsonArray -> v.mapNotNull { item -> listItemText(item) }.joinToString("\n").ifBlank { null }
    else -> null
}

private fun listItemText(item: JsonElement): String? = when (item) {
    is JsonPrimitive -> item.content
    is JsonObject -> {
        val name = (item["name"] as? JsonPrimitive)?.content
        val platform = (item["platform"] as? JsonPrimitive)?.content?.let { " ($it)" }.orEmpty()
        val progress = (item["achievements"] as? JsonPrimitive)?.content?.let { ": $it" }.orEmpty()
        name?.let { it + platform + progress }
    }
    else -> null
}

private val ATTRIBUTE_ORDER =
    listOf(
        "platform", "publisher", "developer", "genres", "release_date", "min_age", "achievements", "gamerscore",
        "ta_score", "game_completion", "progress", "game_ratio", "hours_played", "short_description",
        "display_name", "real_name", "location", "bio", "game_url", "walkthrough_url", "last_update"
    )

/** Readable names for the attributes both sources use; others get their key, made readable. */
private val ATTRIBUTE_LABELS =
    mapOf(
        "platform" to R.string.xbox_attr_platform,
        "publisher" to R.string.xbox_attr_publisher,
        "developer" to R.string.xbox_attr_developer,
        "genres" to R.string.xbox_attr_genres,
        "release_date" to R.string.xbox_attr_release_date,
        "min_age" to R.string.xbox_attr_min_age,
        "achievements" to R.string.xbox_achievements,
        "gamerscore" to R.string.xbox_gamerscore,
        "ta_score" to R.string.xbox_ta_score,
        "game_completion" to R.string.xbox_attr_completion,
        "progress" to R.string.xbox_attr_completion,
        "game_ratio" to R.string.xbox_attr_ratio,
        "hours_played" to R.string.xbox_attr_hours,
        "short_description" to R.string.xbox_attr_description,
        "game_url" to R.string.xbox_attr_game_url,
        "walkthrough_url" to R.string.xbox_attr_walkthrough_url,
        "last_update" to R.string.xbox_last_sync,
        "location" to R.string.xbox_attr_location
    )

@Composable
private fun attributeLabel(key: String): String =
    ATTRIBUTE_LABELS[key]?.let { stringResource(it) } ?: key.replace('_', ' ').replaceFirstChar { it.uppercase() }

private const val PICTURE_DP = 72
