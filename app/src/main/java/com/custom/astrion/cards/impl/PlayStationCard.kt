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
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.SportsEsports
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
import androidx.compose.ui.graphics.Shape
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
 * PlayStation card (1.5.8): the player's profile, the game being played and the trophies, from
 * Home Assistant's official **PlayStation Network** integration ([PlayStationModel]). Tap a part
 * for all its details. The builder fills every entity from the account in one click.
 *
 * Config shape:
 *   { "type": "playstation", "options": {
 *       "profile_entity": "sensor.zavie_online_id",          // name and avatar
 *       "status_entity": "sensor.zavie_online_status",
 *       "plus_entity": "binary_sensor.zavie_subscribed_to_playstation_plus",
 *       "game_entity": "media_player.playstation_5",         // or the account's "now playing" sensor
 *       "picture_entity": "image.zavie_now_playing",         // optional cover (an image entity)
 *       "level_entity": "sensor.zavie_trophy_level",
 *       "progress_entity": "sensor.zavie_next_level",         // % to the next level
 *       "platinum_entity": "…", "gold_entity": "…", "silver_entity": "…", "bronze_entity": "…",
 *       "show_profile": true, "show_game": true, "show_trophies": true
 *   } }
 * Entity ids follow Home Assistant's language when the integration was added (French:
 * `sensor.zavie_statut_en_ligne`…), hence the builder's detection.
 */
class PlayStationCard : CardRenderer {
    override val type = "playstation"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        var details by remember { mutableStateOf<EntityState?>(null) }
        val entity = { key: String -> config.string(key)?.let { ctx.entities[it] } }
        val profile = entity("profile_entity")?.takeIf { config.bool("show_profile", true) }
        val gameEntity = entity("game_entity")?.takeIf { config.bool("show_game", true) }
        val showTrophies = config.bool("show_trophies", true) &&
            (config.string("level_entity") != null || PlayStationModel.Trophy.entries.any { config.string(it.option) != null })

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            profile?.let { Profile(ctx, it, entity("status_entity"), entity("plus_entity")) { e -> details = e } }
            if (config.string("game_entity") != null && config.bool("show_game", true)) {
                val cover = entity("picture_entity")?.attrString("entity_picture")
                GameBlock(ctx, gameEntity, cover) { e -> details = e }
            }
            if (showTrophies) Trophies(ctx, config, entity) { e -> details = e }
        }
        details?.let { e -> XboxDetailDialog(ctx, ctx.entities[e.entityId] ?: e) { details = null } }
    }

    /** Avatar, online ID, status (coloured) and a PS Plus badge. */
    @Composable
    private fun Profile(ctx: CardContext, e: EntityState, status: EntityState?, plus: EntityState?, onDetails: (EntityState) -> Unit) {
        val theme = ctx.theme
        Row(
            Modifier.fillMaxWidth().then(tappable(e, onDetails)),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Picture(ctx, e.attrString("entity_picture"), PROFILE_DP.dp, CircleShape, theme)
            Column(Modifier.weight(1f)) {
                Text(
                    e.state,
                    color = theme.primaryText,
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (status != null) {
                    val (label, color) = statusLook(theme, PlayStationModel.status(status))
                    Text(stringResource(label), color = color, fontSize = 12.sp)
                }
            }
            if (plus?.state == "on") {
                Text(
                    "PS Plus",
                    color = Color.Black,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(GOLD).padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }

    private fun statusLook(theme: ThemeColors, s: PlayStationModel.Status): Pair<Int, Color> = when (s) {
        PlayStationModel.Status.ONLINE -> R.string.psn_online to theme.success
        PlayStationModel.Status.ON_PS_APP -> R.string.psn_online_app to theme.accent
        PlayStationModel.Status.AWAY -> R.string.psn_away to theme.amber
        PlayStationModel.Status.OFFLINE, PlayStationModel.Status.UNKNOWN -> R.string.psn_offline to theme.mutedText
    }

    /** Cover and title of the game being played, or what the console is doing instead. */
    @Composable
    private fun GameBlock(ctx: CardContext, e: EntityState?, cover: String?, onDetails: (EntityState) -> Unit) {
        val theme = ctx.theme
        val game = PlayStationModel.game(e, cover)
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(theme.insetSurface)
                .then(if (e != null) tappable(e, onDetails) else Modifier)
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Picture(ctx, game?.picture, COVER_DP.dp, RoundedCornerShape(12.dp), theme)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (game != null) {
                    Text(stringResource(R.string.psn_playing), color = theme.mutedText, fontSize = 11.sp)
                    Text(
                        game.title,
                        color = theme.primaryText,
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    val off = PlayStationModel.consoleOff(e)
                    Text(
                        stringResource(if (off) R.string.psn_console_off else R.string.psn_no_game),
                        color = theme.mutedText,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (!off) Text(stringResource(R.string.psn_no_game_hint), color = theme.mutedText, fontSize = 12.sp)
                }
            }
        }
    }

    /** The trophy level with its bar to the next one, then platinum · gold · silver · bronze. */
    @Composable
    private fun Trophies(ctx: CardContext, config: CardConfig, entity: (String) -> EntityState?, onDetails: (EntityState) -> Unit) {
        val theme = ctx.theme
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(theme.insetSurface).padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            entity("level_entity")?.let { level -> Level(theme, level, entity("progress_entity"), onDetails) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PlayStationModel.Trophy.entries.forEach { kind ->
                    val e = config.string(kind.option)?.let { ctx.entities[it] }
                    TrophyTile(theme, kind, e, Modifier.weight(1f), onDetails)
                }
            }
        }
    }

    @Composable
    private fun Level(theme: ThemeColors, level: EntityState, progress: EntityState?, onDetails: (EntityState) -> Unit) {
        val ratio = PlayStationModel.progress(progress)
        Column(Modifier.fillMaxWidth().then(tappable(level, onDetails)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                Text(
                    stringResource(R.string.psn_level, PlayStationModel.count(level)?.toString() ?: "—"),
                    color = theme.primaryText,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                ratio?.let {
                    Text(
                        stringResource(R.string.psn_next_level, (it * PERCENT).toInt()),
                        color = theme.mutedText,
                        fontSize = 11.sp
                    )
                }
            }
            ratio?.let {
                Box(Modifier.fillMaxWidth().height(BAR_DP.dp).clip(RoundedCornerShape(3.dp)).background(theme.controlBackground)) {
                    Box(Modifier.fillMaxWidth(it).height(BAR_DP.dp).clip(RoundedCornerShape(3.dp)).background(theme.accent))
                }
            }
        }
    }

    @Composable
    private fun TrophyTile(
        theme: ThemeColors,
        kind: PlayStationModel.Trophy,
        e: EntityState?,
        modifier: Modifier,
        onDetails: (EntityState) -> Unit
    ) {
        Column(
            modifier.clip(RoundedCornerShape(12.dp)).background(theme.controlBackground)
                .then(if (e != null) tappable(e, onDetails) else Modifier).padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Icon(Icons.Filled.EmojiEvents, contentDescription = null, tint = trophyColor(kind), modifier = Modifier.size(TROPHY_DP.dp))
            Text(
                PlayStationModel.count(e)?.toString() ?: "—",
                color = theme.primaryText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1
            )
            Text(
                stringResource(trophyLabel(kind)),
                color = theme.mutedText,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }

    private fun trophyColor(kind: PlayStationModel.Trophy) = when (kind) {
        PlayStationModel.Trophy.PLATINUM -> PLATINUM
        PlayStationModel.Trophy.GOLD -> GOLD
        PlayStationModel.Trophy.SILVER -> SILVER
        PlayStationModel.Trophy.BRONZE -> BRONZE
    }

    private fun trophyLabel(kind: PlayStationModel.Trophy) = when (kind) {
        PlayStationModel.Trophy.PLATINUM -> R.string.psn_platinum
        PlayStationModel.Trophy.GOLD -> R.string.psn_gold
        PlayStationModel.Trophy.SILVER -> R.string.psn_silver
        PlayStationModel.Trophy.BRONZE -> R.string.psn_bronze
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun tappable(e: EntityState, onDetails: (EntityState) -> Unit): Modifier =
        rememberLongPressKeyModifier(e.entityId) { onDetails(e) }
            .combinedClickable(onClick = { onDetails(e) }, onLongClick = { onDetails(e) })

    /** A picture from Home Assistant (or PlayStation's servers), or a controller while there's none. */
    @Composable
    private fun Picture(ctx: CardContext, path: String?, size: Dp, shape: Shape, theme: ThemeColors) {
        val px = with(LocalDensity.current) { size.toPx() }.toInt()
        val image = rememberImage(ctx, path, px)
        Box(Modifier.size(size).clip(shape).background(theme.controlBackground), contentAlignment = Alignment.Center) {
            if (image != null) {
                Image(image, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            } else {
                Icon(
                    Icons.Filled.SportsEsports,
                    contentDescription = null,
                    tint = theme.mutedText,
                    modifier = Modifier.size(size * ICON_SHARE)
                )
            }
        }
    }

    private companion object {
        const val PROFILE_DP = 48
        const val COVER_DP = 96
        const val TROPHY_DP = 22
        const val BAR_DP = 6
        const val ICON_SHARE = 0.45f
        const val PERCENT = 100f

        // PlayStation's trophy colours.
        val PLATINUM = Color(0xFFB4C8DC)
        val GOLD = Color(0xFFE2B33D)
        val SILVER = Color(0xFFBFC5CB)
        val BRONZE = Color(0xFFC4824C)
    }
}
