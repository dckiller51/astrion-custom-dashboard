package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowLeft
import androidx.compose.material.icons.automirrored.filled.ArrowRight
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * TV / Android-TV remote card.
 *
 * Reuses your existing Home Assistant `remote.*` command map — the same
 * mechanism your working aiks-tv-card uses. Each button fires:
 *   remote.send_command  with { entity_id, command }
 * against your Android TV remote entity, e.g. `remote.android_tv_10_0_1_248`.
 *
 * Unlike the stock card, the layout is fully yours: a D-pad cluster, a transport
 * row, and volume — all native Compose, resized for the 480x800 panel.
 *
 * Config shape:
 *   { "type": "tv_remote", "options": {
 *       "name": "Living room TV",
 *       "remote_entity": "remote.living_room_tv",
 *       // optional: where the volume / mute keys go when it isn't the TV
 *       // itself (e.g. a soundbar's own remote.* entity)
 *       "mute_entity": "remote.soundbar",
 *       // optional: the TV's media_player, for the app buttons. Defaults to
 *       // media_player.<same object id as remote_entity> — what the Android
 *       // TV Remote integration creates (1.2.9; it used to default to one
 *       // hard-coded entity of the original author's setup).
 *       "media_entity": "media_player.living_room_tv",
 *       "show_volume": true,     // optional: the vol− / mute / vol+ row (1.2.9)
 *       "apps": [ { "name": "Netflix", "app": "com.netflix.ninja" },
 *                 { "name": "Night mode", "service": "script.night", "entity_id": null, "data": {} } ],
 *       "commands": { "power": "POWER", "volume_up": "VOLUME_UP" }   // optional renames
 *   } }
 *
 * Command values below match the Android TV Remote integration's
 * (DPAD_UP/DOWN/LEFT/RIGHT/CENTER, BACK, HOME, MENU, POWER, VOLUME_UP/DOWN,
 * MUTE); any key can be renamed through `commands`.
 */
class TvRemoteCard : CardRenderer {
    override val type = "tv_remote"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val remoteEntity = config.string("remote_entity") ?: return
        val muteEntity = config.string("mute_entity") ?: remoteEntity
        val name = config.string("name") ?: "TV"

        // Per-button command names, overridable from config. Defaults match an
        // Android TV `remote.send_command` entity (DPAD_*, HOME, BACK, ...).
        val commands = (config.options["commands"] as? Map<String, Any?>).orEmpty()

        fun c(key: String, default: String): String = (commands[key] as? String) ?: default

        fun send(command: String, entity: String = remoteEntity) {
            ctx.client.callService(
                ServiceCall.of(
                    domain = "remote",
                    service = "send_command",
                    entityId = entity,
                    "command" to command
                )
            )
        }

        // App-launch buttons. Each entry is either { name, app } (launched via
        // media_player.play_media on `media_entity`) or an explicit
        // { name, service: "domain.service", entity_id, data }.
        val mediaEntity = config.string("media_entity") ?: "media_player.${remoteEntity.substringAfter('.')}"
        val showVolume = config.bool("show_volume", true)
        val apps = (config.options["apps"] as? List<Map<String, Any?>>) ?: DEFAULT_APPS

        fun launch(app: Map<String, Any?>) {
            val service = app["service"] as? String
            if (service != null) {
                val domain = service.substringBefore('.')
                val svc = service.substringAfter('.')
                val entity = app["entity_id"] as? String
                val data = (app["data"] as? Map<String, Any?>).orEmpty()
                ctx.client.callService(
                    ServiceCall.of(domain, svc, entity, *data.entries.map { it.key to it.value }.toTypedArray())
                )
            } else {
                val appId = app["app"] as? String ?: return
                ctx.client.callService(
                    ServiceCall.of(
                        "media_player",
                        "play_media",
                        mediaEntity,
                        "media_content_type" to "app",
                        "media_content_id" to appId
                    )
                )
            }
        }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // Header row: name + power
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(name, color = ctx.theme.primaryText, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                RoundIconButton(Icons.Filled.PowerSettingsNew, ctx.theme, tint = ctx.theme.danger) {
                    send(c("power", "POWER"))
                }
            }

            // D-pad cluster
            DPad(
                onUp = { send(c("up", "DPAD_UP")) },
                onDown = { send(c("down", "DPAD_DOWN")) },
                onLeft = { send(c("left", "DPAD_LEFT")) },
                onRight = { send(c("right", "DPAD_RIGHT")) },
                onCenter = { send(c("center", "DPAD_CENTER")) },
                theme = ctx.theme
            )

            // Navigation row: back / home / menu
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowBack, ctx.theme) { send(c("back", "BACK")) }
                RoundIconButton(Icons.Filled.Home, ctx.theme) { send(c("home", "HOME")) }
                RoundIconButton(Icons.Filled.Menu, ctx.theme) { send(c("menu", "MENU")) }
            }

            // Volume row: sent to mute_entity when set (a soundbar), else the TV.
            if (showVolume) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    RoundIconButton(Icons.AutoMirrored.Filled.VolumeDown, ctx.theme) {
                        send(c("volume_down", "VOLUME_DOWN"), muteEntity)
                    }
                    RoundIconButton(Icons.AutoMirrored.Filled.VolumeOff, ctx.theme) { send(c("mute", "MUTE"), muteEntity) }
                    RoundIconButton(Icons.AutoMirrored.Filled.VolumeUp, ctx.theme) {
                        send(c("volume_up", "VOLUME_UP"), muteEntity)
                    }
                }
            }

            // App-launch buttons, two per row.
            apps.chunked(2).forEach { pair ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    pair.forEach { app ->
                        AppButton(
                            label = app["name"] as? String ?: "App",
                            modifier = Modifier.weight(1f),
                            theme = ctx.theme
                        ) { launch(app) }
                    }
                    if (pair.size == 1) Spacer(Modifier.weight(1f))
                }
            }
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

    private companion object {
        val DEFAULT_APPS =
            listOf(
                mapOf("name" to "Netflix", "app" to "com.netflix.ninja"),
                mapOf("name" to "YouTube", "app" to "com.google.android.youtube.tv"),
                mapOf("name" to "Prime Video", "app" to "com.amazon.amazonvideo.livingroom"),
                mapOf("name" to "Plex", "app" to "com.plexapp.android")
            )
    }

    @Composable
    private fun DPad(
        onUp: () -> Unit,
        onDown: () -> Unit,
        onLeft: () -> Unit,
        onRight: () -> Unit,
        onCenter: () -> Unit,
        theme: ThemeColors
    ) {
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(180.dp)
                .clip(RoundedCornerShape(90.dp))
                .background(theme.controlBackground),
            contentAlignment = Alignment.Center
        ) {
            // Up
            Box(Modifier.align(Alignment.TopCenter).padding(top = 12.dp)) {
                RoundIconButton(Icons.Filled.KeyboardArrowUp, theme, onClick = onUp)
            }
            // Down
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp)) {
                RoundIconButton(Icons.Filled.KeyboardArrowDown, theme, onClick = onDown)
            }
            // Left
            Box(Modifier.align(Alignment.CenterStart).padding(start = 12.dp)) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowLeft, theme, onClick = onLeft)
            }
            // Right
            Box(Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)) {
                RoundIconButton(Icons.AutoMirrored.Filled.ArrowRight, theme, onClick = onRight)
            }
            // Center / OK
            Box(
                modifier =
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(theme.controlBackground)
                    .tapClickable(focusShape = CircleShape, onClick = onCenter),
                contentAlignment = Alignment.Center
            ) {
                Text("OK", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
    }

    @Composable
    private fun RoundIconButton(icon: ImageVector, theme: ThemeColors, tint: Color = theme.iconTint, onClick: () -> Unit) {
        Box(
            modifier =
            Modifier
                .size(52.dp)
                .clip(CircleShape)
                .background(theme.controlBackground)
                .tapClickable(focusShape = CircleShape, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = tint)
        }
    }
}
