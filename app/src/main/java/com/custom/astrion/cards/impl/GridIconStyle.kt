package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.ui.decodeIconSampled

/**
 * How a scene or button grid draws its icons (1.5.0). The icon used to be a fixed 28 / 32 dp
 * square, so a big PNG looked as small as a small one and a rectangular logo shrank further.
 *
 * - `icon_size` (dp): the icon's height; a wide logo gets the width its shape needs (up to the tile).
 * - `icon_fit`: `contain` (whole logo, its shape kept: default), `fill` (fills the tile's width
 *   at that height, cropping what spills over), `stretch` (stretched to the tile's width).
 * - `icon_trim`: cuts the logo's transparent margins first.
 */
internal data class GridIconStyle(val size: Int, val fit: String, val trim: Boolean) {
    val stretches: Boolean get() = fit == FIT_FILL || fit == FIT_STRETCH

    companion object {
        const val FIT_CONTAIN = "contain"
        const val FIT_FILL = "fill"
        const val FIT_STRETCH = "stretch"
        private const val MAX_SIZE = 200

        fun of(config: CardConfig, defaultSize: Int): GridIconStyle = GridIconStyle(
            size = config.int("icon_size", defaultSize).takeIf { it > 0 }?.coerceAtMost(MAX_SIZE) ?: defaultSize,
            fit = config.string("icon_fit")?.takeIf { it == FIT_FILL || it == FIT_STRETCH } ?: FIT_CONTAIN,
            trim = config.bool("icon_trim", false)
        )
    }
}

/** The icon at [path], decoded sharp enough for [style] (a wide logo at full tile width). */
@Composable
internal fun rememberGridIcon(path: String?, style: GridIconStyle, heightDp: Int = style.size): ImageBitmap? {
    // The longer side is decoded at up to 4× the height: a 16:9 or wider logo stays sharp.
    val targetPx = with(LocalDensity.current) { (heightDp * WIDE_FACTOR).dp.toPx() }.toInt()
    return remember(path, targetPx, style.trim) { path?.let { decodeIconSampled(it, targetPx, style.trim) } }
}

/** [bitmap] drawn as [style] says, [heightDp] tall; a spacer of that height when there is none. */
@Composable
internal fun GridIconImage(bitmap: ImageBitmap?, description: String?, style: GridIconStyle, heightDp: Int = style.size) {
    if (bitmap == null) {
        Spacer(Modifier.size(heightDp.dp))
        return
    }
    val aspect = if (bitmap.height > 0) bitmap.width.toFloat() / bitmap.height else 1f
    val modifier =
        if (style.stretches) {
            Modifier.height(heightDp.dp).fillMaxWidth()
        } else {
            Modifier.height(heightDp.dp).width((heightDp * aspect).dp)
        }
    val scale =
        when (style.fit) {
            GridIconStyle.FIT_FILL -> ContentScale.Crop
            GridIconStyle.FIT_STRETCH -> ContentScale.FillBounds
            else -> ContentScale.Fit
        }
    Image(bitmap = bitmap, contentDescription = description, modifier = modifier, contentScale = scale)
}

private const val WIDE_FACTOR = 4
