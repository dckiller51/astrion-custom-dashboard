package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.R
import com.custom.astrion.cards.CardContext
import com.custom.astrion.config.ActivityConfig
import com.custom.astrion.config.DeviceState
import com.custom.astrion.config.DeviceStateStore
import com.custom.astrion.config.TrackedActivity

/**
 * One row of the Active Activities overlay: name (tap → its page), "Help"
 * (composed Activities only — the ones Astrion itself drives device by
 * device) and "Stop". Help unfolds [ActivityHelpPanel] under the row.
 */
@Composable
internal fun ActiveActivityRow(
    activity: TrackedActivity,
    composed: ActivityConfig?,
    ctx: CardContext,
    actions: ActivityOverlayActions,
    onClose: () -> Unit
) {
    var showHelp by remember(activity.id) { mutableStateOf(false) }
    val theme = LocalTheme.current
    Column(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(theme.insetSurface)
    ) {
        Row(
            modifier =
            Modifier
                .fillMaxWidth()
                .tapClickable(enabled = activity.page != null) {
                    activity.page?.let {
                        ctx.navigateToPage(it)
                        onClose()
                    }
                }.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(activity.name, color = theme.primaryText, fontSize = 15.sp, modifier = Modifier.weight(1f))
            if (composed != null) {
                SmallAction(stringResource(R.string.activity_help), theme.accent) { showHelp = !showHelp }
            }
            // Dedicated per-room stop: a classic Harmony Activity's generic
            // PowerOff hotkey would kill every room on that hub; this only
            // stops this room's Activity (see ActivityDispatcher.stopActivity).
            SmallAction(stringResource(R.string.stop_activity), theme.danger) { actions.stop(activity.room) }
        }
        if (showHelp && composed != null) {
            ActivityHelpPanel(composed, ctx) {
                actions.resync(activity.room)
                showHelp = false
            }
        }
    }
}

@Composable
private fun SmallAction(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    Text(
        label,
        color = color,
        fontSize = 13.sp,
        modifier = Modifier.clip(RoundedCornerShape(8.dp)).tapClickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)
    )
}

/**
 * Harmony's "Help" button, Astrion edition: lists the Activity's devices
 * with what Astrion *believes* about each (on / off / unknown, last
 * input). Tapping a device only corrects that belief — nothing is sent —
 * cycling on → off → unknown. "Fix" then replays the Activity: devices
 * marked off are powered on (waiting their power-on delay), inputs not
 * known to be set are selected again. A device left "unknown" is assumed
 * on, so a toggle-only device is never flipped by mistake.
 */
@Composable
private fun ActivityHelpPanel(activity: ActivityConfig, ctx: CardContext, onFix: () -> Unit) {
    val store = DeviceStateStore.shared
    val states by store.states.collectAsState()
    val theme = LocalTheme.current
    Column(
        modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 10.dp, bottom = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(stringResource(R.string.activity_help_hint), color = theme.mutedText, fontSize = 12.sp)
        activity.devices.forEach { d ->
            val state = states[d.deviceId] ?: DeviceState()
            DeviceStateRow(
                name = ctx.irDevices[d.deviceId]?.name ?: d.deviceId,
                state = state,
                onCycle = {
                    when (state.on) {
                        true -> store.setPower(d.deviceId, false)
                        false -> store.reset(d.deviceId)
                        null -> store.setPower(d.deviceId, true)
                    }
                }
            )
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Text(
                stringResource(R.string.activity_fix),
                color = theme.background,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                modifier =
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(theme.accent)
                    .tapClickable(focusShape = RoundedCornerShape(8.dp), onClick = onFix)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    }
}

@Composable
private fun DeviceStateRow(name: String, state: DeviceState, onCycle: () -> Unit) {
    val theme = LocalTheme.current
    val (label, color) =
        when (state.on) {
            true -> stringResource(R.string.device_state_on) to theme.accent
            false -> stringResource(R.string.device_state_off) to theme.danger
            null -> stringResource(R.string.device_state_unknown) to theme.mutedText
        }
    Row(
        modifier =
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(theme.cardSurface)
            .tapClickable(focusShape = RoundedCornerShape(8.dp), onClick = onCycle)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(name, color = theme.primaryText, fontSize = 14.sp)
            state.input?.let {
                Text(stringResource(R.string.activity_help_input, it), color = theme.mutedText, fontSize = 11.sp)
            }
        }
        Text(label, color = color, fontSize = 13.sp, fontWeight = FontWeight.Medium)
    }
}
