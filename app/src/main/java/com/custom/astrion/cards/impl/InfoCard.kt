package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.HaTemplates
import com.custom.astrion.ha.LocalTemplates
import com.custom.astrion.ha.TemplateText

/**
 * Info card (1.5.6): images and text from anything in Home Assistant — a TMDB sensor's poster,
 * cast and synopsis, an EPG, a printer's progress… Each image URL and each text may be a Home
 * Assistant template (`{{ state_attr('sensor.kodi_movie_info', 'overview') }}`), updated live: a
 * simple one (`states()` / `state_attr()` only) filled on the remote, direct devices included, so
 * a Sky box's programme shows without Home Assistant ([LocalTemplates]); the others rendered by
 * Home Assistant ([HaTemplates]). A value without `{{ }}` / `{% %}` is shown as it is.
 *
 * Config shape:
 * ```json
 * { "type": "info", "options": {
 *     "layout": "stack",              // "stack" (default): blocks one under the other
 *                                     // "side": the first image on the left, the rest beside it
 *     "blocks": [
 *       { "type": "image", "url": "https://image.tmdb.org/t/p/w500{{ state_attr('sensor.kodi_movie_info','poster_path') }}",
 *         "ratio": "poster" },        // "poster" (2:3, default) | "wide" (16:9) | "square"
 *       { "type": "text", "text": "{{ states('sensor.kodi_movie_info') }}", "size": "title" },
 *       { "type": "text", "text": "{{ state_attr('sensor.kodi_movie_info','overview') }}",
 *         "size": "body",             // "title" | "subtitle" | "body" (default) | "caption"
 *         "lines": 4,                 // at most, then "…" (0 or absent: all of it)
 *         "marquee": false,           // one line that scrolls
 *         "align": "start",           // "start" | "center" | "end"
 *         "hide_missing": true },     // hidden when a value in it is missing ("None"), 1.5.7
 *       { "type": "list",             // 1.5.7: a row of pictures from a list
 *         "source": "{{ state_attr('sensor.kodi_movie_credits','cast') }}",
 *         "image": "https://image.tmdb.org/t/p/w185{profile_path}",   // an item's fields as {field}
 *         "title": "{name}", "subtitle": "{character}",
 *         "limit": 10, "ratio": "poster" }                           // poster | square | round
 *     ],
 *     "visible_if": "{{ states('media_player.kodi') }}",  // 1.5.7: hidden when false / off / idle…
 *     "hide_when_empty": true                            // 1.5.7: no card when no block has anything
 * } }
 * ```
 * An image whose URL renders empty (or ends in `None`: the sensor has no picture yet) isn't shown,
 * nor is a text that renders empty or with a missing value. Builder form: js/cards-info.js.
 */
class InfoCard : CardRenderer {
    override val type = "info"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val blocks = remember(config.options) { InfoBlock.listOf(config.options["blocks"]) }
        // Every block rendered first, so the card knows which have something to show (1.5.7).
        val rendered = blocks.mapIndexed { i, b -> key(i, b.template) { rememberTemplate(ctx, b.template) } }
        val condition = config.string("visible_if")?.takeIf { it.isNotBlank() }
        if (condition != null && !InfoBlock.truthy(rememberTemplate(ctx, condition).text)) return
        val shown = blocks.indices.filter { rendered[it].error != null || blocks[it].visible(rendered[it].text) }
        // Nothing to show (a Sky box that's off, a sensor still empty): no card at all, unless asked.
        if (shown.isEmpty() && config.bool("hide_when_empty", true)) return
        val side = config.string("layout") == "side"
        val first = shown.firstOrNull { blocks[it] is InfoBlock.Image }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ctx.theme.cardSurface)
                .padding(16.dp)
        ) {
            if (side && first != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.fillMaxWidth(SIDE_IMAGE_FRACTION)) { Block(ctx, blocks[first], rendered[first]) }
                    Blocks(ctx, shown.filter { it != first }.map { blocks[it] to rendered[it] })
                }
            } else {
                Blocks(ctx, shown.map { blocks[it] to rendered[it] })
            }
        }
    }

    @Composable
    private fun Blocks(ctx: CardContext, blocks: List<Pair<InfoBlock, TemplateText>>) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { (b, r) -> Block(ctx, b, r) }
        }
    }

    @Composable
    private fun Block(ctx: CardContext, block: InfoBlock, rendered: TemplateText) {
        if (rendered.error != null) {
            Text("⚠ ${rendered.error}", color = ctx.theme.danger, fontSize = 12.sp, modifier = Modifier.fillMaxWidth())
            return
        }
        when (block) {
            is InfoBlock.Image -> InfoBlock.imageUrl(
                rendered.text
            )?.let { Picture(ctx, it, block.ratio, round = false, Modifier.fillMaxWidth()) }
            is InfoBlock.Text -> TextBlock(ctx, block, rendered.text?.trim().orEmpty())
            is InfoBlock.ListRow -> ListBlock(ctx, block, rendered.text)
        }
    }

    @Composable
    private fun Picture(ctx: CardContext, url: String?, ratio: Float, round: Boolean, modifier: Modifier) {
        val shape = if (round) CircleShape else RoundedCornerShape(12.dp)
        BoxWithConstraints(modifier.aspectRatio(ratio).clip(shape).background(ctx.theme.insetSurface)) {
            val px = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
            url?.let { u ->
                rememberImage(ctx, u, px)?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
            }
        }
    }

    /** A row of pictures, scrolled sideways: a photo (or an empty frame), a name, a role. */
    @Composable
    private fun ListBlock(ctx: CardContext, block: InfoBlock.ListRow, rendered: String?) {
        val people = remember(rendered, block) { block.items(rendered) }
        LazyRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            items(people) { item ->
                Column(Modifier.width(LIST_ITEM_WIDTH.dp)) {
                    Picture(ctx, item.image, block.ratio, block.round, Modifier.fillMaxWidth())
                    if (item.title.isNotEmpty()) {
                        Text(
                            item.title,
                            color = ctx.theme.primaryText,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (item.subtitle.isNotEmpty()) {
                        Text(item.subtitle, color = ctx.theme.mutedText, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TextBlock(ctx: CardContext, block: InfoBlock.Text, text: String) {
        val (size, weight, muted) = block.style()
        Text(
            text,
            color = if (muted) ctx.theme.mutedText else ctx.theme.primaryText,
            fontSize = size,
            fontWeight = weight,
            textAlign = block.textAlign(),
            maxLines = if (block.marquee) 1 else block.lines.takeIf { it > 0 } ?: Int.MAX_VALUE,
            overflow = if (block.marquee) TextOverflow.Clip else TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().then(if (block.marquee) Modifier.basicMarquee(iterations = Int.MAX_VALUE) else Modifier)
        )
    }

    private data class TextStyleOf(val size: TextUnit, val weight: FontWeight, val muted: Boolean)

    private fun InfoBlock.Text.style() = when (size) {
        "title" -> TextStyleOf(20.sp, FontWeight.SemiBold, false)
        "subtitle" -> TextStyleOf(15.sp, FontWeight.Medium, false)
        "caption" -> TextStyleOf(12.sp, FontWeight.Normal, true)
        else -> TextStyleOf(14.sp, FontWeight.Normal, false)
    }

    private fun InfoBlock.Text.textAlign() = when (align) {
        "center" -> TextAlign.Center
        "end" -> TextAlign.End
        else -> TextAlign.Start
    }

    private companion object {
        const val SIDE_IMAGE_FRACTION = 0.38f
        const val LIST_ITEM_WIDTH = 76
    }
}

/**
 * [template] rendered and kept live while on screen: plain text as it is, a simple one
 * (`states()` / `state_attr()` only) filled here from the remote's entities, direct devices
 * included ([LocalTemplates]), anything else by Home Assistant.
 */
@Composable
internal fun rememberTemplate(ctx: CardContext, template: String): TemplateText {
    if (!HaTemplates.isTemplate(template)) return TemplateText(text = template)
    // Never let a template take the app down: anything that fails here goes to Home Assistant.
    runCatching { LocalTemplates.render(template, ctx.entities) }.getOrNull()?.let { return TemplateText(text = it) }
    val templates = ctx.client.templates
    val flow = remember(templates, template) { templates.acquire(template) }
    DisposableEffect(templates, template) { onDispose { templates.release(template) } }
    val value by flow.collectAsState()
    return value
}
