package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * A card's app shortcuts (`apps`, `apps_style`): the TV remote card's since 1.3, the media player
 * card's too since 1.4.9. `{ name, app }` opens the app on [mediaEntity] (media_player.play_media,
 * type app); `{ name, service: "domain.service", entity_id, data }` calls that service.
 */
internal class AppShortcuts(
    private val ctx: CardContext,
    val apps: List<Map<String, Any?>>,
    private val mediaEntity: String
) {
    @Suppress("UNCHECKED_CAST")
    fun launch(app: Map<String, Any?>) {
        val service = app["service"] as? String
        if (service != null) {
            val data = (app["data"] as? Map<String, Any?>).orEmpty()
            ctx.client.callService(
                ServiceCall(
                    service.substringBefore('.'),
                    service.substringAfter('.'),
                    app["entity_id"] as? String,
                    data.mapValues { JsonPlain.toJson(it.value) }
                )
            )
        } else {
            val appId = app["app"] as? String ?: return
            ctx.client.callService(
                ServiceCall.of("media_player", "play_media", mediaEntity, "media_content_type" to "app", "media_content_id" to appId)
            )
        }
    }

    /** [app] as an icon tile: its `icon` (file or URL), else its initials on `color`. */
    fun tileOf(app: Map<String, Any?>): AtvApp {
        val label = app["name"] as? String ?: "App"
        return AtvApp(
            source = (app["app"] as? String) ?: label,
            label = label,
            icon = (app["icon"] as? String)?.takeIf { it.isNotBlank() },
            color = parseHexColor(app["color"] as? String)
        )
    }

    /** The apps in the card's order (names, then packages), to order the sources pop-up. */
    fun order(): List<String> = apps.flatMap { listOfNotNull(it["name"] as? String, it["app"] as? String) }

    /** The icons the card gives its apps, by name and by package — for the sources pop-up. */
    fun icons(): Map<String, String?> = apps.flatMap { app ->
        val icon = (app["icon"] as? String)?.takeIf { it.isNotBlank() }
        listOfNotNull(app["name"] as? String, app["app"] as? String).map { it to icon }
    }.filter { it.second != null }.toMap()

    /** The shortcuts: labelled icons ([iconsStyle], up to [ICONS_PER_ROW] a row) or text buttons, two a row. */
    @Composable
    fun Rows(iconsStyle: Boolean, cardPadding: Int) {
        if (iconsStyle) {
            val width = cardInnerWidthDp(cardPadding).dp
            apps.chunked(ICONS_PER_ROW).forEach { row ->
                AtvAppsRow(ctx, row.map(::tileOf), ICON_TILE.dp, labels = true, rowWidth = width) { tile ->
                    row.firstOrNull { tileOf(it) == tile }?.let(::launch)
                }
            }
        } else {
            apps.chunked(2).forEach { pair ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    pair.forEach { app ->
                        AppButton(app["name"] as? String ?: "App", Modifier.weight(1f), ctx.theme) { launch(app) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    companion object {
        const val ICONS_PER_ROW = 5
        private const val ICON_TILE = 52

        @Suppress("UNCHECKED_CAST")
        fun appsOf(options: Map<String, Any?>): List<Map<String, Any?>>? = options["apps"] as? List<Map<String, Any?>>
    }
}

@Composable
private fun AppButton(label: String, modifier: Modifier, theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        modifier
            .height(48.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(theme.controlBackground)
            .tapClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = theme.primaryText, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
