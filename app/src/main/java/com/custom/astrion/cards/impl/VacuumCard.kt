package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.HaLabels
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Robot vacuum card: the map (from a Roborock/Xiaomi map image entity, which
 * already renders the robot moving), start/pause/dock/locate controls, a
 * fan-speed (cleaning-mode) dropdown, and room buttons that start a segment clean.
 *
 * Room buttons fire a per-room "clean this segment" service call — by
 * default `vacuum.send_command app_segment_clean` with the segment id (the
 * Roborock/Xiaomi room id from your map), but configurable via
 * `room_clean_action` for integrations that use their own service instead
 * (e.g. Dreame's `dreame_vacuum.vacuum_clean_segment`) — see [RoomCleanAction].
 * The map image is fetched from the entity's `entity_picture` — no
 * blocked-zone editing, just a live-ish view.
 *
 * The vacuum's activity state (docked/cleaning/paused/idle/returning/error) is
 * translated via `HaLabels.vacuumState()` — see assets/ha_labels/<lang>.json.
 * Fan-speed / cleaning-mode names (e.g. "Quiet", "Turbo", "Max") are
 * integration-specific (not a fixed HA-wide set), so `HaLabels.vacuumFanSpeed()`
 * only covers the common ones there; anything else still falls back to a
 * best-effort prettified version of the raw value (see `HaLabels.fallback()`).
 *
 * Config shape:
 *   { "type": "vacuum", "options": {
 *       "entity_id": "vacuum.roborock",
 *       "map_image": "image.roborock_map",
 *       "map_rotation": 90,
 *       "map_height": 200,
 *       "rooms": [ { "name": "Kitchen", "id": 18 }, ... ],
 *       "room_clean_action": {
 *         "domain": "dreame_vacuum",
 *         "service": "vacuum_clean_segment",
 *         "parameter": "segments"
 *       }
 *   } }
 *
 * `room_clean_action` is optional — see [roomCleanAction] doc below for what
 * it changes and why it defaults to the Roborock/Xiaomi-style call.
 *
 * The same options map can be reused as the "vacuum" overlay block on a
 * `picture_elements` floorplan card (see [PictureElementsCard]) — tapping the
 * robot icon there opens this exact content in a popup via [VacuumPanelContent].
 */
class VacuumCard : CardRenderer {
    override val type = "vacuum"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        Column(
            modifier =
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(ctx.theme.cardSurface)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            VacuumPanelContent(config.options, ctx)
        }
    }
}

/**
 * The vacuum panel's actual content (map, controls, cleaning mode, room
 * buttons) with no outer container — call this inside whatever container you
 * want (a plain card, or a Dialog for the floorplan popup).
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun VacuumPanelContent(options: Map<String, Any?>, ctx: CardContext) {
    val entityId = options["entity_id"] as? String ?: return
    val e = ctx.entities[entityId]
    val state = e?.state ?: "unknown"
    val name = options["name"] as? String ?: e?.friendlyName ?: stringResource(R.string.vacuum_default_name)
    val fanSpeed = e?.attrString("fan_speed")
    val fanList = e?.attrStringList("fan_speed_list") ?: emptyList()
    val mapEntity = options["map_image"] as? String
    val mapHeight = (options["map_height"] as? Number)?.toInt() ?: 200
    val rooms = (options["rooms"] as? List<Map<String, Any?>>) ?: emptyList()
    val roomCleanAction = (options["room_clean_action"] as? Map<String, Any?>)?.let { RoomCleanAction.fromOptions(it) }

    // Degrees clockwise to rotate the map so it matches the floorplan card's
    // orientation above it (the vacuum map's native orientation rarely
    // matches an arbitrary hand-drawn/rendered floorplan).
    val rotation = (options["map_rotation"] as? Number)?.toInt() ?: 0

    val mapPic = mapEntity?.let { ctx.entities[it]?.attrString("entity_picture") }
    var mapBmp by remember(mapPic, rotation) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mapPic, rotation) {
        val fetched = mapPic?.let { ctx.client.fetchBitmap(it) } ?: return@LaunchedEffect
        mapBmp = if (rotation == 0) fetched else rotateVacuumBitmap(fetched, rotation)
    }

    var fanExpanded by remember { mutableStateOf(false) }

    fun vac(service: String) = ctx.client.callService(ServiceCall("vacuum", service, entityId))

    // Default: the original hardcoded Roborock/Xiaomi-style call, unchanged
    // for backward compatibility with every existing dashboard.json. Only
    // used when `room_clean_action` isn't set.
    fun cleanSegmentDefault(id: Int) {
        ctx.client.callService(
            ServiceCall(
                "vacuum",
                "send_command",
                entityId,
                mapOf(
                    "command" to JsonPrimitive("app_segment_clean"),
                    "params" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(id)))))
                )
            )
        )
    }

    fun cleanSegment(id: Int) {
        if (roomCleanAction != null) {
            ctx.client.callService(
                ServiceCall.of(
                    roomCleanAction.domain,
                    roomCleanAction.service,
                    entityId,
                    roomCleanAction.parameter to id
                )
            )
        } else {
            cleanSegmentDefault(id)
        }
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            name,
            color = ctx.theme.primaryText,
            fontSize = 16.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f)
        )
        Text(HaLabels.vacuumState(state), color = ctx.theme.mutedText, fontSize = 13.sp)
    }

    // Map (includes the robot, rooms, path — rendered by the integration).
    // Fixed height (configurable) so the whole card clears the pager dots.
    mapBmp?.let { bmp ->
        Box(
            modifier =
            Modifier
                .fillMaxWidth()
                .height(mapHeight.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(ctx.theme.background),
            contentAlignment = Alignment.Center
        ) {
            Image(
                bitmap = bmp,
                contentDescription = stringResource(R.string.vacuum_map_label),
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.9f, scaleY = 1.9f),
                contentScale = ContentScale.Fit
            )
        }
    }

    // Controls.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally)
    ) {
        VacuumCtrlBtn(Icons.Filled.PlayArrow, ctx.theme, accent = true) { vac("start") }
        VacuumCtrlBtn(Icons.Filled.Pause, ctx.theme) { vac("pause") }
        VacuumCtrlBtn(Icons.Filled.Home, ctx.theme) { vac("return_to_base") }
        VacuumCtrlBtn(Icons.Filled.MyLocation, ctx.theme) { vac("locate") }
    }

    // Fan speed / cleaning mode dropdown.
    if (fanList.isNotEmpty()) {
        Box {
            Row(
                modifier =
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(ctx.theme.controlBackground)
                    .tapClickable { fanExpanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.vacuum_fan_speed_caption), color = ctx.theme.mutedText, fontSize = 11.sp)
                    Text(fanSpeed?.let(HaLabels::vacuumFanSpeed) ?: "—", color = ctx.theme.primaryText, fontSize = 15.sp)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = ctx.theme.iconTint)
            }
            DropdownMenu(
                expanded = fanExpanded,
                onDismissRequest = { fanExpanded = false },
                modifier = Modifier.background(ctx.theme.controlBackground)
            ) {
                fanList.forEach { f ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                HaLabels.vacuumFanSpeed(f),
                                color = if (f == fanSpeed) ctx.theme.accent else ctx.theme.primaryText,
                                fontSize = 14.sp
                            )
                        },
                        onClick = {
                            fanExpanded = false
                            ctx.client.callService(
                                ServiceCall.of("vacuum", "set_fan_speed", entityId, "fan_speed" to f)
                            )
                        }
                    )
                }
            }
        }
    }

    // Room selection → segment clean.
    if (rooms.isNotEmpty()) {
        rooms.chunked(3).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                chunk.forEach { room ->
                    val label = room["name"] as? String ?: "?"
                    val id = (room["id"] as? Number)?.toInt()
                    Box(
                        modifier =
                        Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(ctx.theme.controlBackground)
                            .tapClickable(enabled = id != null) { id?.let { cleanSegment(it) } },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(label, color = ctx.theme.primaryText, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

/**
 * Which HA service to call for a room-clean button, and where the raw
 * room/segment id (as configured in a `rooms[].id`) goes. Not every vacuum
 * integration speaks the Roborock/Xiaomi-style `vacuum.send_command
 * app_segment_clean` (the default when this isn't set at all — see
 * [VacuumPanelContent]'s own `cleanSegmentDefault`); e.g. Dreame instead
 * exposes `dreame_vacuum.vacuum_clean_segment` with a plain `segments`
 * field. This covers that common shape: `domain.service` with the id
 * written as a single scalar service-data field named [parameter] —
 * `entity_id` is always sent as the service call's `target`, same as every
 * other card ([ServiceCall.of]).
 *
 * Config (under a `vacuum` card's `options.room_clean_action`):
 *   { "domain": "dreame_vacuum", "service": "vacuum_clean_segment", "parameter": "segments" }
 *
 * This intentionally doesn't try to cover every possible service-data
 * shape (e.g. Roborock's own nested `params: [[id]]` list) — that one stays
 * hardcoded as the default specifically so it needs no configuration at
 * all for the integration most Astrion users are already on.
 */
data class RoomCleanAction(val domain: String, val service: String, val parameter: String) {
    companion object {
        fun fromOptions(map: Map<String, Any?>): RoomCleanAction? {
            val domain = map["domain"] as? String ?: return null
            val service = map["service"] as? String ?: return null
            val parameter = (map["parameter"] as? String)?.takeIf { it.isNotBlank() } ?: return null
            return RoomCleanAction(domain, service, parameter)
        }
    }
}

@Composable
private fun VacuumCtrlBtn(icon: ImageVector, theme: ThemeColors, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (accent) theme.accentSecondary else theme.controlBackground)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

/** Rotate a bitmap by whole-degree steps (clockwise), swapping width/height as needed. */
private fun rotateVacuumBitmap(src: ImageBitmap, degrees: Int): ImageBitmap {
    val android = src.asAndroidBitmap()
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(android, 0, 0, android.width, android.height, matrix, true).asImageBitmap()
}
