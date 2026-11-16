package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import java.util.Locale

/**
 * A film's year, rating and synopsis, when the player gives them (a Zidoo's poster wall, 1.5.8)
 * or TMDB found them (1.5.9): [lines] lines, a tap shows up to [openLines]. Nothing when neither.
 * The media remote card's player view and the player's details pop-up.
 */
@Composable
internal fun MediaSynopsis(e: EntityState, theme: ThemeColors, lines: Int = 3, openLines: Int = Int.MAX_VALUE) {
    val overview = e.attrString("astrion_overview")
    val meta =
        listOfNotNull(
            e.attrInt("astrion_year")?.toString(),
            e.attrDouble("astrion_rating")?.let { "★ " + String.format(Locale.ROOT, "%.1f", it) }
        ).joinToString(" · ")
    if (overview == null && meta.isEmpty()) return
    var open by remember(overview) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().tapClickable { open = !open },
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (meta.isNotEmpty()) Text(meta, color = theme.mutedText, fontSize = 12.sp, maxLines = 1)
        overview?.let {
            Text(
                it,
                color = theme.mutedText,
                fontSize = 13.sp,
                maxLines = if (open) openLines else lines,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center
            )
        }
    }
}
