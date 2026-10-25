package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MilitaryTech
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Percent
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stars
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.ThemeColors

/**
 * Xbox card (1.3.7): the game being played and the player's counters, from one of two sources,
 * chosen in the builder — the official **Xbox** integration, or **TrueAchievements** (custom
 * component, for TA Pro accounts). Every part is optional; a long press on the game or a counter
 * opens all its details.
 *
 * Config shape:
 *   { "type": "xbox", "options": {
 *       "source": "trueachievements",          // or "xbox" (only changes the defaults and labels)
 *       "profile_entity": "binary_sensor.dckillerpp",      // Xbox: gamerpic, name, online
 *       "game_entity": "sensor.trueachievements_dckillerpp_jeu_en_cours",
 *       "picture_entity": "image.dckillerpp_now_playing",  // optional cover (an image entity), else the game's picture
 *       "stats": [ { "entity_id": "sensor.trueachievements_dckillerpp_gamerscore", "name": "Gamerscore" }, … ],  // up to 8, two per row
 *       "sync_entity": "binary_sensor.trueachievements_dckillerpp_statut_de_connexion_ta",   // TA: last sync
 *       "show_profile": true, "show_game": true, "show_stats": true, "show_sync": true
 *   } }
 */
class XboxCard : CardRenderer {
    override val type = "xbox"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val theme = ctx.theme
        var details by remember { mutableStateOf<EntityState?>(null) }
        val profile = config.string("profile_entity")?.takeIf { config.bool("show_profile", true) }?.let { ctx.entities[it] }
        val gameId = config.string("game_entity")?.takeIf { config.bool("show_game", true) }
        val showIcons = config.bool("show_stat_icons", true)
        val stats =
            ((config.options["stats"] as? List<Map<String, Any?>>).orEmpty())
                .takeIf { config.bool("show_stats", true) }.orEmpty()
                .mapNotNull { s -> statItem(ctx, s, showIcons) }
                .take(MAX_STATS)
        val perRow = config.int("stats_per_row", DEFAULT_STATS_PER_ROW).coerceIn(1, MAX_STATS_PER_ROW)
        val sync = config.string("sync_entity")?.takeIf { config.bool("show_sync", true) }?.let { ctx.entities[it] }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            profile?.let { Profile(ctx, it) }
            if (gameId != null) {
                // The Xbox integration's sensor may carry no picture (or one Android can't load):
                // its image.<gamertag>_now_playing entity serves the cover through Home Assistant.
                val cover = config.string("picture_entity")?.let { ctx.entities[it]?.attrString("entity_picture") }
                GameBlock(ctx, ctx.entities[gameId], GameLook(config.string("source"), cover)) { details = it }
            }
            if (stats.isNotEmpty()) StatTiles(ctx, stats, perRow) { details = it }
            sync?.let { SyncRow(theme, it) }
        }
        details?.let { e -> XboxDetailDialog(ctx, ctx.entities[e.entityId] ?: e) { details = null } }
    }

    /** Gamerpic, gamertag and online / offline, from the Xbox integration's profile sensor. */
    @Composable
    private fun Profile(ctx: CardContext, e: EntityState) {
        val theme = ctx.theme
        val online = e.state == "on"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Picture(ctx, e.attrString("entity_picture"), PROFILE_PX.dp, CircleShape, theme)
            Column(Modifier.weight(1f)) {
                Text(
                    e.attrString("display_name") ?: e.friendlyName,
                    color = theme.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    stringResource(if (online) R.string.xbox_online else R.string.xbox_offline),
                    color = if (online) theme.success else theme.mutedText,
                    fontSize = 12.sp
                )
            }
        }
    }

    /** The card's source (for the "off" wording) and the cover from `picture_entity`, if any. */
    private class GameLook(val source: String?, val cover: String?)

    /** The game being played: cover, title, platform · publisher, progress bars and chips. Long press: details. */
    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GameBlock(ctx: CardContext, e: EntityState?, look: GameLook, onDetails: (EntityState) -> Unit) {
        val source = look.source
        val theme = ctx.theme
        val game = XboxModel.game(e)
        val gestures =
            if (e != null) {
                rememberLongPressKeyModifier(e.entityId) { onDetails(e) }
                    .combinedClickable(onClick = { onDetails(e) }, onLongClick = { onDetails(e) })
            } else {
                Modifier
            }
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.insetSurface)
                .then(gestures)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Picture(
                ctx,
                (look.cover ?: e?.attrString("entity_picture"))?.takeIf { game != null },
                COVER_PX.dp,
                RoundedCornerShape(12.dp),
                theme
            )
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (game == null) {
                    val offTitle = if (source == "trueachievements") R.string.xbox_no_game else R.string.xbox_console_off
                    Text(stringResource(offTitle), color = theme.mutedText, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.xbox_no_game_hint), color = theme.mutedText, fontSize = 12.sp)
                } else {
                    GameText(theme, game)
                }
            }
        }
    }

    @Composable
    private fun GameText(theme: ThemeColors, game: XboxModel.Game) {
        Text(
            game.title,
            color = theme.primaryText,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        game.subtitle?.let { Text(it, color = theme.mutedText, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        game.bars.forEach { ProgressLine(theme, it) }
        if (game.chips.isNotEmpty()) {
            Text(
                game.chips.map { chipText(it) }.joinToString("  ·  "),
                color = theme.accent,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    @Composable
    private fun chipText(chip: XboxModel.Chip): String = when (chip.kind) {
        XboxModel.Chip.Kind.COMPLETION -> "🎯 ${chip.value}"
        XboxModel.Chip.Kind.RATIO -> stringResource(R.string.xbox_ratio, chip.value)
        XboxModel.Chip.Kind.HOURS -> "⏱ ${chip.value}"
    }

    /** "🏆 29 / 139" and a thin bar when the total is known. */
    @Composable
    private fun ProgressLine(theme: ThemeColors, bar: XboxModel.Bar) {
        val (label, color) =
            when (bar.kind) {
                XboxModel.Bar.Kind.ACHIEVEMENTS -> stringResource(R.string.xbox_achievements) to theme.amber
                XboxModel.Bar.Kind.GAMERSCORE -> stringResource(R.string.xbox_gamerscore) to theme.success
                XboxModel.Bar.Kind.TA_SCORE -> stringResource(R.string.xbox_ta_score) to theme.accent
            }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Text(label, color = theme.mutedText, fontSize = 11.sp, modifier = Modifier.weight(1f))
                Text(bar.text, color = theme.primaryText, fontSize = 11.sp)
            }
            bar.fraction.ratio?.let { ratio ->
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(theme.controlBackground)
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(ratio)
                            .height(4.dp)
                            .clip(RoundedCornerShape(2.dp))
                            .background(color)
                    )
                }
            }
        }
    }

    /** The counters, two per row: icon, then value over label. Long press (or tap): details. */
    @Composable
    private fun StatTiles(ctx: CardContext, stats: List<StatItem>, perRow: Int, onDetails: (EntityState) -> Unit) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            stats.chunked(perRow).forEach { row ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // From three per row, the icon goes above the value so it still fits.
                    row.forEach { item -> StatTile(ctx, item, perRow >= STACKED_FROM, Modifier.weight(1f), onDetails) }
                    // A short last row keeps the same tile width as the others.
                    repeat(perRow - row.size) { Box(Modifier.weight(1f)) }
                }
            }
        }
    }

    /** One counter of `stats`: its entity, label, icon and icon colour. */
    private class StatItem(val e: EntityState, val name: String?, val icon: XboxModel.StatIcon, val color: Color?)

    private fun statItem(ctx: CardContext, s: Map<String, Any?>, showIcons: Boolean): StatItem? {
        val e = (s["entity_id"] as? String)?.let { ctx.entities[it] } ?: return null
        val icon = if (showIcons) XboxModel.statIcon(s["icon"] as? String, e) else XboxModel.StatIcon.None
        return StatItem(e, s["name"] as? String, icon, parseHexColor(s["color"] as? String))
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StatTile(ctx: CardContext, item: StatItem, stacked: Boolean, modifier: Modifier, onDetails: (EntityState) -> Unit) {
        val e = item.e
        val tileModifier =
            modifier
                .clip(RoundedCornerShape(14.dp))
                .background(ctx.theme.insetSurface)
                .then(rememberLongPressKeyModifier(e.entityId) { onDetails(e) })
                .combinedClickable(onClick = { onDetails(e) }, onLongClick = { onDetails(e) })
        val texts: @Composable (Alignment.Horizontal) -> Unit = { align -> StatTexts(ctx.theme, item, align) }
        if (stacked) {
            Column(
                modifier = tileModifier.padding(horizontal = 6.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                StatIconView(ctx, item)
                texts(Alignment.CenterHorizontally)
            }
        } else {
            Row(
                modifier = tileModifier.padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                StatIconView(ctx, item)
                Box(Modifier.weight(1f)) { texts(Alignment.Start) }
            }
        }
    }

    /** The value (bold) over its label. */
    @Composable
    private fun StatTexts(theme: ThemeColors, item: StatItem, align: Alignment.Horizontal) {
        Column(horizontalAlignment = align) {
            Text(
                XboxModel.statValue(item.e),
                color = theme.primaryText,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                item.name?.takeIf { it.isNotBlank() } ?: XboxModel.statLabel(item.e),
                color = theme.mutedText,
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    /** The counter's icon: built-in (tinted by its `color`, or the default for its kind), an uploaded file, or nothing. */
    @Composable
    private fun StatIconView(ctx: CardContext, item: StatItem) {
        when (val icon = item.icon) {
            XboxModel.StatIcon.None -> Unit
            is XboxModel.StatIcon.File -> {
                val px = with(LocalDensity.current) { STAT_ICON_DP.dp.toPx() }.toInt()
                rememberImage(ctx, icon.path, px)?.let {
                    Image(it, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(STAT_ICON_DP.dp))
                } ?: Box(Modifier.size(STAT_ICON_DP.dp))
            }
            is XboxModel.StatIcon.BuiltIn -> {
                val (vector, defaultTint) = statIcon(ctx.theme, icon.kind)
                val tint = item.color ?: defaultTint
                if (vector != null) {
                    Icon(vector, contentDescription = null, tint = tint, modifier = Modifier.size(STAT_ICON_DP.dp))
                } else {
                    GBadge(tint)
                }
            }
        }
    }

    /** Gamerscore's "G" in a circle, as Xbox draws it (no Material icon for it). */
    @Composable
    private fun GBadge(tint: Color) {
        Box(Modifier.size(STAT_ICON_DP.dp).clip(CircleShape).background(tint), contentAlignment = Alignment.Center) {
            Text("G", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }

    /** A Material icon and colour per kind of counter; null = the "G" badge. */
    private fun statIcon(theme: ThemeColors, kind: XboxModel.StatKind): Pair<ImageVector?, Color> = when (kind) {
        XboxModel.StatKind.GAMERSCORE -> null to theme.success
        XboxModel.StatKind.TA_SCORE -> Icons.Filled.Stars to theme.amber
        XboxModel.StatKind.PERCENT -> Icons.Filled.Percent to theme.accent
        XboxModel.StatKind.FINISHED -> Icons.Filled.EmojiEvents to theme.amber
        XboxModel.StatKind.ACHIEVEMENTS -> Icons.Filled.MilitaryTech to theme.amber
        XboxModel.StatKind.GAMES -> Icons.Filled.SportsEsports to theme.accentSecondary
        XboxModel.StatKind.PEOPLE -> Icons.Filled.People to theme.accent
        XboxModel.StatKind.OTHER -> Icons.Filled.Insights to theme.iconTint
    }

    /** TrueAchievements' last sync: green when fine, red when the component reports a problem. */
    @Composable
    private fun SyncRow(theme: ThemeColors, e: EntityState) {
        val problem = e.state == "on"
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(
                if (problem) Icons.Filled.Error else Icons.Filled.CheckCircle,
                contentDescription = null,
                tint = if (problem) theme.danger else theme.success,
                modifier = Modifier.size(20.dp)
            )
            Text(
                stringResource(if (problem) R.string.xbox_sync_problem else R.string.xbox_last_sync),
                color = theme.primaryText,
                fontSize = 13.sp,
                modifier = Modifier.weight(1f)
            )
            e.attrString("last_update")?.let { Text(it, color = theme.mutedText, fontSize = 12.sp) }
        }
    }

    /** A picture from Home Assistant (or the web), or a controller on an inset tile while there's none. */
    @Composable
    private fun Picture(ctx: CardContext, path: String?, size: Dp, shape: androidx.compose.ui.graphics.Shape, theme: ThemeColors) {
        val px = with(LocalDensity.current) { size.toPx() }.toInt()
        val image = rememberImage(ctx, path, px)
        Box(Modifier.size(size).clip(shape).background(theme.controlBackground), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(Icons.Filled.SportsEsports, contentDescription = null, tint = theme.mutedText, modifier = Modifier.size(size * 0.45f))
            }
        }
    }

    private companion object {
        const val MAX_STATS = 8
        const val DEFAULT_STATS_PER_ROW = 2
        const val MAX_STATS_PER_ROW = 4

        /** From this many counters per row, a tile stacks its icon above the value. */
        const val STACKED_FROM = 3
        const val STAT_ICON_DP = 28
        const val PROFILE_PX = 48
        const val COVER_PX = 96
    }
}
