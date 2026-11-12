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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
 *         "align": "start" }          // "start" | "center" | "end"
 *     ]
 * } }
 * ```
 * An image whose URL renders empty (or ends in `None`: the sensor has no picture yet) isn't shown,
 * nor is a text that renders empty. Builder form: js/cards-info.js.
 */
class InfoCard : CardRenderer {
    override val type = "info"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val blocks = remember(config.options) { InfoBlock.listOf(config.options["blocks"]) }
        val side = config.string("layout") == "side"
        val first = blocks.indexOfFirst { it is InfoBlock.Image }
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(ctx.theme.cardSurface)
                .padding(16.dp)
        ) {
            if (side && first >= 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Box(Modifier.fillMaxWidth(SIDE_IMAGE_FRACTION)) { Block(ctx, blocks[first]) }
                    Blocks(ctx, blocks.filterIndexed { i, _ -> i != first })
                }
            } else {
                Blocks(ctx, blocks)
            }
        }
    }

    @Composable
    private fun Blocks(ctx: CardContext, blocks: List<InfoBlock>) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            blocks.forEach { Block(ctx, it) }
        }
    }

    @Composable
    private fun Block(ctx: CardContext, block: InfoBlock) {
        when (block) {
            is InfoBlock.Image -> ImageBlock(ctx, block)
            is InfoBlock.Text -> TextBlock(ctx, block)
        }
    }

    @Composable
    private fun ImageBlock(ctx: CardContext, block: InfoBlock.Image) {
        val url = rememberTemplate(ctx, block.url).text?.trim()
        if (url.isNullOrEmpty() || url.endsWith("None") || url.endsWith("/")) return
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(block.ratio)
                .clip(RoundedCornerShape(12.dp))
                .background(ctx.theme.insetSurface)
        ) {
            val px = with(LocalDensity.current) { maxWidth.toPx() }.toInt()
            rememberImage(ctx, url, px)?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun TextBlock(ctx: CardContext, block: InfoBlock.Text) {
        val rendered = rememberTemplate(ctx, block.text)
        val error = rendered.error
        val text = error?.let { "⚠ $it" } ?: rendered.text?.trim()
        if (text.isNullOrEmpty()) return
        val (size, weight, muted) = block.style()
        Text(
            text,
            color = if (error != null) ctx.theme.danger else if (muted) ctx.theme.mutedText else ctx.theme.primaryText,
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
