package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.ThemeColors

/**
 * Sensor / monitor card — the native equivalent of the stock
 * `custom:aiks-switch-monitor-card` (StatisticsCardParser): a labelled list of
 * read-only entity values (temperature, humidity, power, etc.) with their unit.
 *
 * Reads each entity's live state plus its `unit_of_measurement` attribute; no
 * history graph (kept light for the MT6580), just the current value.
 *
 * Config shape:
 *   { "type": "monitor", "options": {
 *       "title": "Sensors",
 *       "entities": [
 *         { "entity_id": "sensor.lounge_temperature", "name": "Lounge" },
 *         { "entity_id": "sensor.power_now", "name": "Power" }
 *       ],
 *       "layout": "vertical",   // optional: name above, value on its own full-width line
 *       "marquee": true         // optional: long values stay on one line and scroll
 *   } }
 *
 * Without either option a row is laid out as it always was (name left, value right, a long value
 * wraps). Both exist for sensors with long text, e.g. an AVR's audio/video info.
 */
class MonitorCard : CardRenderer {
    override val type = "monitor"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = config.string("title")
        val entities = (config.options["entities"] as? List<Map<String, Any?>>) ?: emptyList()
        val vertical = config.string("layout").equals("vertical", ignoreCase = true)
        val marquee = config.bool("marquee", false)

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ctx.theme.cardSurface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    title,
                    color = ctx.theme.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            entities.forEach { row ->
                val entityId = row["entity_id"] as? String ?: return@forEach
                val e = ctx.entities[entityId]
                val name = row["name"] as? String ?: e?.friendlyName ?: entityId
                val unit = e?.attrString("unit_of_measurement").orEmpty()
                val value = e?.state?.let { if (it == "unknown" || it == "unavailable") "—" else it } ?: "—"
                MonitorRow(name, if (unit.isBlank()) value else "$value $unit", vertical, marquee, ctx.theme)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MonitorRow(name: String, value: String, vertical: Boolean, marquee: Boolean, theme: ThemeColors) {
    val valueModifier = if (marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier
    if (vertical) {
        Column(modifier = Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(name, color = theme.mutedText, fontSize = 13.sp)
            Text(
                value,
                color = theme.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = if (marquee) 1 else Int.MAX_VALUE,
                modifier = Modifier.fillMaxWidth().then(valueModifier)
            )
        }
        return
    }
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (marquee) {
            // The name keeps its own width; the value gets the rest and scrolls inside it.
            Text(name, color = theme.mutedText, fontSize = 14.sp, maxLines = 1)
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                color = theme.primaryText,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                textAlign = TextAlign.End,
                modifier = Modifier.weight(1f).then(valueModifier)
            )
        } else {
            Text(name, color = theme.mutedText, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Text(value, color = theme.primaryText, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}
