package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wallpaper
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.appletv.AppleTvRegistry
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable

/**
 * Apple TV remote styled like a Siri Remote.
 *
 * Features a circular trackpad (d-pad + select), Menu/Home buttons, and Play/Pause.
 * Commands bypass Home Assistant. Two ways to reach the Apple TV:
 *
 *  - **Direct** (`appleTv`): the Apple TV paired in the Devices page, driven over
 *    its own Companion link — no hub, no Home Assistant.
 *  - **Harmony** (`deviceId` [+ `hub`]): via `ctx.sendHarmonyCommand`.
 *
 * Config shapes:
 * ```json
 * { "type": "apple_tv_remote", "options": { "appleTv": "media_player.appletv_salon" } }
 * { "type": "apple_tv_remote", "options": { "deviceId": "62846050", "hub": "<localId>" } }
 * ```
 * `appleTv` is a paired Apple TV's entity id (or local id); when present it wins over
 * `deviceId`. `hub` is optional — a HarmonyHubConfig.localId; omit it to use the first
 * configured hub.
 *
 * `buttons` (optional) picks which buttons sit in the row between the trackpad and the
 * Play/Pause button, from [ExtraButtons.CATALOG]'s ids — e.g. `["Menu", "Siri"]`. Defaults
 * to `["Menu", "Home"]`, the row this card has always shown.
 */
object ExtraButtons {
    /**
     * One buildable extra button: [command] is what's actually sent (works for both a direct
     * Apple TV, which is tolerant of case/spacing, and a Harmony hub, which expects it verbatim).
     */
    data class Spec(val command: String, val label: String, val icon: ImageVector?)

    val CATALOG: List<Spec> =
        listOf(
            Spec("Menu", "Menu", Icons.Filled.Menu),
            Spec("Home", "Home", Icons.Filled.Home),
            Spec("Siri", "Siri", Icons.Filled.Mic),
            Spec("Screensaver", "Screensaver", Icons.Filled.Wallpaper),
            Spec("Guide", "Guide", Icons.Filled.List),
            Spec("VolumeUp", "Vol +", Icons.Filled.VolumeUp),
            Spec("VolumeDown", "Vol −", Icons.Filled.VolumeDown),
            Spec("ControlCenter", "Control Center", Icons.Filled.Apps)
        )
    private val byCommand = CATALOG.associateBy { it.command.lowercase() }

    val DEFAULT_IDS = listOf("Menu", "Home")

    /** Falls back to just the command as its own label for a button id this catalog doesn't (yet) know. */
    fun resolve(id: String): Spec = byCommand[id.lowercase()] ?: Spec(id, id, null)
}

class AppleTvRemoteCard : CardRenderer {
    override val type = "apple_tv_remote"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val appleTv = config.string("appleTv")?.takeIf { it.isNotBlank() }
        val deviceId = config.string("deviceId")
        if (appleTv == null && deviceId == null) return
        val hub = config.string("hub") // HarmonyHubConfig.localId; falls back to the first hub if absent

        fun send(command: String) {
            if (appleTv != null) {
                ctx.client.callService(ServiceCall.of(AppleTvRegistry.REMOTE_DOMAIN, "send_command", appleTv, "command" to command))
            } else if (deviceId != null) {
                ctx.sendHarmonyCommand(deviceId, command, hub)
            }
        }

        var isPlaying by remember { mutableStateOf(true) }

        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(ctx.theme.cardSurface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            Trackpad(
                onUp = { send("DirectionUp") },
                onDown = { send("DirectionDown") },
                onLeft = { send("DirectionLeft") },
                onRight = { send("DirectionRight") },
                onSelect = { send("Select") },
                theme = ctx.theme
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                ButtonRow(config.stringList("buttons").ifEmpty { ExtraButtons.DEFAULT_IDS }, ::send, ctx.theme)
            }

            Box(
                modifier =
                Modifier
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(ctx.theme.controlBackground)
                    .tapClickable(focusShape = CircleShape) {
                        // Harmony has separate Play/Pause codes, so we track the state ourselves;
                        // a direct Apple TV has a real play/pause toggle and needs no guess.
                        send(
                            if (appleTv != null) {
                                "PlayPause"
                            } else if (isPlaying) {
                                "Pause"
                            } else {
                                "Play"
                            }
                        )
                        isPlaying = !isPlaying
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = ctx.theme.primaryText
                )
            }
        }
    }

    @Composable
    private fun Trackpad(
        onUp: () -> Unit,
        onDown: () -> Unit,
        onLeft: () -> Unit,
        onRight: () -> Unit,
        onSelect: () -> Unit,
        theme: ThemeColors
    ) {
        Box(
            modifier =
            Modifier
                .size(220.dp)
                .clip(CircleShape)
                .background(theme.controlBackground)
                .tapClickable(focusShape = CircleShape, onClick = onSelect),
            contentAlignment = Alignment.Center
        ) {
            EdgeIcon(Icons.Filled.KeyboardArrowUp, Alignment.TopCenter, onUp, theme)
            EdgeIcon(Icons.Filled.KeyboardArrowDown, Alignment.BottomCenter, onDown, theme)
            EdgeIcon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, Alignment.CenterStart, onLeft, theme)
            EdgeIcon(Icons.AutoMirrored.Filled.KeyboardArrowRight, Alignment.CenterEnd, onRight, theme)
            Box(
                modifier =
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(theme.controlBackground)
            )
        }
    }

    @Composable
    private fun EdgeIcon(icon: ImageVector, align: Alignment, onClick: () -> Unit, theme: ThemeColors) {
        Box(
            modifier =
            Modifier
                .fillMaxSize()
                .padding(14.dp),
            contentAlignment = align
        ) {
            Box(
                modifier =
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .tapClickable(focusShape = CircleShape, onClick = onClick),
                contentAlignment = Alignment.Center
            ) {
                Icon(icon, contentDescription = null, tint = theme.mutedText)
            }
        }
    }

    @Composable
    private fun ButtonRow(buttons: List<String>, send: (String) -> Unit, theme: ThemeColors) {
        buttons.forEach { id ->
            val spec = ExtraButtons.resolve(id)
            PillButton(icon = spec.icon, label = spec.label, theme = theme) { send(spec.command) }
        }
    }

    @Composable
    private fun PillButton(icon: ImageVector? = null, label: String, theme: ThemeColors, onClick: () -> Unit) {
        Row(
            modifier =
            Modifier
                .clip(RoundedCornerShape(50))
                .background(theme.controlBackground)
                .tapClickable(focusShape = RoundedCornerShape(50), onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            icon?.let { Icon(it, contentDescription = null, tint = theme.primaryText) }
            Text(label, color = theme.primaryText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }
}
