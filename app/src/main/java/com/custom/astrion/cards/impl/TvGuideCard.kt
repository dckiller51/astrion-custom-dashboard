package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.ScreenCapture
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

private const val LOGO_DP = 40
private const val PERCENT = 100f
private const val ROW_DP = 76
private const val MAX_LIST_DP = 520
private const val MAX_LIST_LIMIT_DP = 2000
private const val LOGOS_AT_ONCE = 3

/** The channel logos being fetched, a few at a time for every TV guide card. */
private val logoGate = Semaphore(LOGOS_AT_ONCE)

/**
 * TV guide card (1.6.0): the TV guide's channels (`sensor.epg_…`, see epg/EpgRegistry) with what's
 * on now, how far it is, and what's next. A tap opens the programme's page (synopsis, times, what's
 * next); a channel with an action (the button grid's keys: a service, a Harmony command, IR, a page)
 * gets a "Watch" button there, or runs it straight away with `"tap": "action"`.
 *
 * Config shape:
 *   { "type": "tv_guide", "options": {
 *       "title": "TV guide",
 *       "channels": [                                  // optional: none lists every guide channel
 *         { "entity_id": "sensor.epg_m6_fr", "link": "https://www.canalplus.com/live/?channel=313" },
 *         { "entity_id": "sensor.epg_france2_fr", "name": "France 2",
 *           "service": "media_player.select_source", "target_entity": "media_player.tv",
 *           "data": { "source": "France 2" } }       // the action, optional
 *       ],
 *       "show_next": true, "show_progress": true, "show_logo": true,
 *       "player": "media_player.shield",               // where a channel's "link" plays (1.6.0)
 *       "tap": "details",                              // or "action"
 *       "max_height": 520                              // dp: the list scrolls inside the card beyond
 *   } }
 *
 * A channel's own action keys are the button grid's; its service's entity is `target_entity`
 * (`entity_id` names the channel).
 */
class TvGuideCard : CardRenderer {
    override val type = "tv_guide"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val rows = (config.options["channels"] as? List<Map<String, Any?>>).orEmpty().filter { it["entity_id"] is String }
        val byId = rows.associateBy { it["entity_id"] as String }
        // The player a channel's link opens on (1.6.0): an Android TV, an Apple TV, a box…
        val player = config.string("player")
        val names = ctx.entities.mapValues { it.value.friendlyName }
        val ids = TvGuideText.channels(rows.map { it["entity_id"] as String }, names)
        val style =
            GuideStyle(
                next = config.bool("show_next", true),
                progress = config.bool("show_progress", true),
                logo = config.bool("show_logo", true),
                direct = config.string("tap") == "action"
            )
        var open by remember { mutableStateOf<String?>(null) }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ctx.theme.cardSurface)
                .padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            config.string("title")?.takeIf { it.isNotBlank() }?.let {
                Text(
                    it,
                    color = ctx.theme.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
            if (ids.isEmpty()) {
                Text(
                    stringResource(R.string.tv_guide_empty),
                    color = ctx.theme.mutedText,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            // Only the rows in view are drawn (and their logos fetched): a guide can have hundreds
            // of channels. The list scrolls inside the card, up to max_height.
            val maxDp = config.int("max_height", MAX_LIST_DP).coerceIn(ROW_DP, MAX_LIST_LIMIT_DP)
            LazyColumn(Modifier.fillMaxWidth().height((ids.size * ROW_DP).coerceAtMost(maxDp).dp)) {
                items(ids, key = { it }) { id ->
                    val row = byId[id].orEmpty()
                    val action = row.toAction(player)
                    GuideRow(ctx, ctx.entities[id], row["name"] as? String ?: names[id] ?: id, style) {
                        if (style.direct && action != null) fireButtonAction(ctx, action) else open = id
                    }
                }
            }
        }
        open?.let { id ->
            GuideDialog(ctx, ctx.entities[id], byId[id]?.get("name") as? String, (byId[id] ?: emptyMap()).toAction(player)) { open = null }
        }
    }
}

private data class GuideStyle(val next: Boolean, val progress: Boolean, val logo: Boolean, val direct: Boolean)

/** A channel row's action, the button grid's keys; its service's entity is `target_entity`. */
private fun Map<String, Any?>.toAction(player: String?): Map<String, Any?>? {
    val keys = setOf("service", "harmonyCommand", "activityId", "stopActivity", "page", "irDevice")
    // Its own action first; else its link on the card's player (1.6.0).
    if (keys.none { containsKey(it) }) return TvGuideText.linkAction(player, this["link"] as? String)
    return (this - "entity_id" - "name").let { a ->
        (a["target_entity"] as? String)?.let { a - "target_entity" + ("entity_id" to it) } ?: a
    }
}

@Composable
private fun GuideRow(ctx: CardContext, e: EntityState?, name: String, style: GuideStyle, onTap: () -> Unit) {
    val theme = ctx.theme
    val now = e?.attrString("title")
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .tapClickable(onClick = onTap)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (style.logo) ChannelLogo(ctx, e?.attrString("entity_picture"), name)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, modifier = Modifier.weight(1f))
                TvGuideText.span(e?.attrString("start"), e?.attrString("end"))?.let {
                    Text(it, color = theme.mutedText, fontSize = 12.sp, maxLines = 1)
                }
            }
            Text(
                now ?: stringResource(R.string.tv_guide_nothing),
                color = if (now != null) theme.primaryText else theme.mutedText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (style.progress && now != null) {
                Progress(theme, e?.attrString("progress")?.toFloatOrNull())
            }
            if (style.next) {
                e?.attrString("next_title")?.let { next ->
                    val at = TvGuideText.clock(e?.attrString("next_start"))
                    Text(
                        stringResource(R.string.tv_guide_next, listOfNotNull(at, next).joinToString(" · ")),
                        color = theme.mutedText,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun Progress(theme: ThemeColors, percent: Float?) {
    val f = ((percent ?: 0f) / PERCENT).coerceIn(0f, 1f)
    Box(
        Modifier
            .padding(vertical = 2.dp)
            .fillMaxWidth()
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(theme.mutedText.copy(alpha = 0.25f))
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(3.dp)
                .background(theme.accent)
        )
    }
}

@Composable
private fun ChannelLogo(ctx: CardContext, url: String?, name: String) {
    val px = with(LocalDensity.current) { LOGO_DP.dp.toPx() }.toInt()
    val image by produceState<ImageBitmap?>(null, url, px) {
        // A few at a time, decoded at the logo's size and kept: hundreds of 480 px logos fetched
        // at once over HTTPS froze the remote (1.6.0).
        value = url?.let { u -> logoGate.withPermit { ctx.client.fetchBitmap(u, px, cache = true) } }
    }
    Box(
        Modifier
            .size(LOGO_DP.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(ctx.theme.mutedText.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center
    ) {
        val logo = image
        if (logo != null) {
            Image(logo, contentDescription = name, contentScale = ContentScale.Fit, modifier = Modifier.size(LOGO_DP.dp).padding(4.dp))
        } else {
            Text(name.take(2).uppercase(), color = ctx.theme.mutedText, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/** A channel's programme: what's on (times, category, synopsis) and what's next; "Watch" when it has an action. */
@Composable
private fun GuideDialog(ctx: CardContext, e: EntityState?, name: String?, action: Map<String, Any?>?, onClose: () -> Unit) {
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
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                ChannelLogo(ctx, e?.attrString("entity_picture"), name ?: e?.friendlyName.orEmpty())
                Text(name ?: e?.friendlyName.orEmpty(), color = theme.mutedText, fontSize = 13.sp)
            }
            Text(
                e?.attrString("title") ?: stringResource(R.string.tv_guide_nothing),
                color = theme.primaryText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            val facts = listOfNotNull(TvGuideText.span(e?.attrString("start"), e?.attrString("end")), e?.attrString("category"))
            if (facts.isNotEmpty()) Text(facts.joinToString(" · "), color = theme.mutedText, fontSize = 13.sp)
            e?.attrString("sub_title")?.let { Text(it, color = theme.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium) }
            e?.attrString("description")?.let { Text(it, color = theme.primaryText, fontSize = 14.sp) }
            e?.attrString("next_title")?.let { next ->
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        R.string.tv_guide_next,
                        listOfNotNull(TvGuideText.span(e?.attrString("next_start"), e?.attrString("next_end")), next).joinToString(" · ")
                    ),
                    color = theme.mutedText,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold
                )
                e?.attrString("next_description")?.let {
                    Text(it, color = theme.mutedText, fontSize = 13.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Row(Modifier.align(Alignment.End), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (action != null) {
                    DialogButton(stringResource(R.string.tv_guide_watch), theme) {
                        fireButtonAction(ctx, action)
                        onClose()
                    }
                }
                DialogButton(stringResource(R.string.close), theme, onClose)
            }
        }
    }
}

@Composable
private fun DialogButton(label: String, theme: ThemeColors, onClick: () -> Unit) {
    Text(
        label,
        color = theme.accent,
        fontSize = 14.sp,
        fontWeight = FontWeight.SemiBold,
        modifier =
        Modifier
            .clip(RoundedCornerShape(12.dp))
            .tapClickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 8.dp)
    )
}
