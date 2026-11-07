package com.custom.astrion.cards.impl

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.R
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.ForwardHotkeys
import com.custom.astrion.ui.ThemeColors
import com.custom.astrion.ui.tapClickable
import java.io.File
import kotlinx.serialization.json.JsonPrimitive

/**
 * Pop-ups the remote cards open from a button (1.4.9): every source / app of a player in a grid,
 * and a 0–9 keypad. Shared by the TV remote, Apple TV remote and media player cards.
 */
internal object QuickPickers {
    /** What [entityId] can switch to: its `source_list` (apps, inputs…), in its own order. */
    fun sourcesOf(ctx: CardContext, entityId: String?): List<String> =
        entityId?.let { ctx.entities[it]?.attrStringList("source_list") }.orEmpty().filter { it.isNotBlank() }

    /**
     * [sources] with the card's own apps first, in the card's order ([preferred]: their names or
     * packages, as listed), then the others in the player's order (1.4.9) — so the card's app
     * list, reordered in the builder, also orders the pop-up.
     */
    fun ordered(sources: List<String>, preferred: List<String>): List<String> {
        val keys = preferred.map { iconKey(it) }
        val rank = sources.associateWith { s -> keys.indexOf(iconKey(s)).let { if (it < 0) Int.MAX_VALUE else it } }
        return sources.withIndex().sortedWith(compareBy({ rank.getValue(it.value) }, { it.index })).map { it.value }
    }

    /** `media_player.select_source` — what a picked tile does. */
    fun selectSource(ctx: CardContext, entityId: String, source: String) {
        ctx.client.callService(ServiceCall.of("media_player", "select_source", entityId, "source" to source))
    }

    /**
     * [name] reduced to letters and digits, lowercase, accents dropped: "Disney+" → "disney",
     * "Apple TV" → "appletv", "Musique" → "musique". Icons are matched on it.
     */
    fun iconKey(name: String): String = java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .lowercase()
        .replace(Regex("[^a-z0-9]"), "")

    /**
     * The icons uploaded on the web page (/sdcard/astrion/icons), by [iconKey] of their file name:
     * an app with no icon in the card gets the file named like it (1.4.9), e.g. `Netflix.png`
     * or `disney.png` for "Disney+".
     */
    fun uploadedIcons(): Map<String, String> = runCatching {
        val root = File(Environment.getExternalStorageDirectory(), "astrion/icons")
        fun icons(dir: File) = dir.listFiles().orEmpty().filter { it.isFile && it.extension.lowercase() in IMAGE_EXTENSIONS }
        // 1.5.2: a direct device's imported icons are in its folder; one uploaded at the top wins.
        val nested = root.listFiles().orEmpty().filter { it.isDirectory }.flatMap { icons(it) }
        (nested + icons(root)).associate { iconKey(it.nameWithoutExtension) to it.absolutePath }
    }.getOrDefault(emptyMap())

    private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp")

    /** The keypad's keys, row by row; "" is an empty cell, "OK" sends [okCommand]. */
    val KEYPAD_ROWS = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("", "0", "OK"))
}

/**
 * Every source / app of [entityId] as tiles (its icon from [icons] when the card gave one, else
 * its initials); the current one is outlined. A tap selects it and closes the pop-up.
 */
@Composable
internal fun SourcePickerDialog(
    ctx: CardContext,
    entityId: String,
    icons: Map<String, String?> = emptyMap(),
    preferred: List<String> = emptyList(),
    onPick: (String) -> Unit = { QuickPickers.selectSource(ctx, entityId, it) },
    onClose: () -> Unit
) {
    val theme = ctx.theme
    val sources = QuickPickers.ordered(QuickPickers.sourcesOf(ctx, entityId), preferred)
    val current = ctx.entities[entityId]?.attrString("source")
    val uploaded = remember { QuickPickers.uploadedIcons() }
    // Icons the player gives itself (an LG TV's apps and inputs, 1.5.2), after the card's and the uploaded ones.
    val own =
        (ctx.entities[entityId]?.attr("astrion_source_icons") as? kotlinx.serialization.json.JsonObject)
            ?.mapValues { (it.value as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
    PickerFrame(theme, stringResource(R.string.picker_sources), onClose) {
        if (sources.isEmpty()) {
            Text(
                stringResource(R.string.picker_no_sources),
                color = theme.mutedText,
                fontSize = 13.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            return@PickerFrame
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(GRID_COLUMNS),
            modifier = Modifier.heightIn(max = GRID_MAX_HEIGHT.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            itemsIndexed(sources, key = { _, s -> s }) { index, source ->
                val selected = source == current
                Box(
                    modifier =
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .then(if (selected) Modifier.border(2.dp, theme.accent, RoundedCornerShape(14.dp)) else Modifier)
                        .padding(4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val icon = icons[source] ?: uploaded[QuickPickers.iconKey(source)] ?: own[source]
                    AtvAppTile(ctx, AtvApp(source, source, icon, null), index, TILE.dp, TILE_LABEL.dp) {
                        onPick(source)
                        onClose()
                    }
                }
            }
        }
    }
}

/**
 * Where a keypad's keys go (1.4.9) and what each one sends: an IR device of the remote's own
 * blaster or an extender (`keypad_ir_device`), a Harmony device (`keypad_harmony_device`, optional
 * `keypad_hub`), or a `remote` entity (`keypad_entity`, else the card's own; `keypad_remote_device`
 * adds `device` for Home Assistant's Harmony remote). `keypad_commands` renames keys, e.g.
 * `{"1": "Num1", "ok": "Select"}`; a digit sends itself by default, OK [ok].
 */
internal class KeypadTarget(
    private val irDevice: String?,
    private val harmonyDevice: String?,
    private val hub: String?,
    private val remote: String?,
    private val remoteDevice: String?,
    private val commands: Map<String, String>,
    private val ok: String?
) {
    /** What [key] ("0"–"9" or "OK") sends; null: nothing (OK without a command). */
    fun commandOf(key: String): String? = commands[key.lowercase()]?.takeIf { it.isNotBlank() } ?: if (key == "OK") ok else key

    val hasOk: Boolean get() = commandOf("OK") != null

    fun send(ctx: CardContext, key: String) {
        val command = commandOf(key) ?: return
        when {
            irDevice != null -> ctx.sendIrCommand(irDevice, command)
            harmonyDevice != null -> ctx.sendHarmonyCommand(harmonyDevice, command, hub)
            remote != null -> {
                val data = listOfNotNull(
                    "command" to command,
                    remoteDevice?.let { "device" to it }
                ).associate { (k, v) -> k to JsonPrimitive(v) }
                ctx.client.callService(ServiceCall("remote", "send_command", remote, data))
            }
        }
    }

    companion object {
        /** The card's keypad target; [defaultRemote] / [defaultOk]: the card's own. Null when it has none. */
        fun from(config: CardConfig, defaultRemote: String?, defaultOk: String?): KeypadTarget? {
            fun text(key: String) = config.string(key)?.trim()?.takeIf { it.isNotEmpty() }
            val ir = text("keypad_ir_device")
            val harmony = text("keypad_harmony_device")
            val remote = text("keypad_entity") ?: defaultRemote
            if (ir == null && harmony == null && remote == null) return null
            val commands =
                (config.options["keypad_commands"] as? Map<*, *>).orEmpty()
                    .mapNotNull { (k, v) -> (v as? String)?.let { k.toString().lowercase() to it } }
                    .toMap()
            // A command set for the card's own remote (DPAD_CENTER) means nothing to an IR or Harmony device.
            val ok = text("keypad_ok") ?: defaultOk.takeIf { ir == null && harmony == null }
            return KeypadTarget(ir, harmony, text("keypad_hub"), remote, text("keypad_remote_device"), commands, ok)
        }
    }
}

/**
 * A 0–9 keypad: each key calls [onKey] with "0"–"9" or "OK" (the card's [KeypadTarget] turns it
 * into a command), and the pop-up stays open, for a channel number. OK only with [showOk].
 */
@Composable
internal fun KeypadDialog(theme: ThemeColors, showOk: Boolean, onKey: (String) -> Unit, onClose: () -> Unit) {
    PickerFrame(theme, stringResource(R.string.picker_keypad), onClose) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            QuickPickers.KEYPAD_ROWS.forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { key ->
                        when {
                            key.isEmpty() || (key == "OK" && !showOk) -> Spacer(Modifier.size(KEY.dp))
                            else -> KeypadKey(key, theme) { onKey(key) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun KeypadKey(label: String, theme: ThemeColors, onClick: () -> Unit) {
    Box(
        modifier =
        Modifier
            .size(KEY.dp)
            .clip(CircleShape)
            .background(theme.controlBackground)
            .tapClickable(focusShape = CircleShape, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = theme.primaryText, fontSize = if (label.length > 1) 16.sp else 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** The pop-up's frame: a title, a close button, then [content]. */
@Composable
internal fun PickerFrame(theme: ThemeColors, title: String, onClose: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        // The remote's buttons keep working while the pop-up is open (1.4.9).
        ForwardHotkeys()
        Column(
            modifier =
            Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(theme.cardSurface)
                .width(FRAME_WIDTH.dp)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, color = theme.primaryText, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = theme.mutedText,
                    modifier = Modifier.size(32.dp).clip(CircleShape).tapClickable(onClick = onClose).padding(5.dp)
                )
            }
            content()
        }
    }
}

private const val GRID_COLUMNS = 3
private const val GRID_MAX_HEIGHT = 480
private const val TILE = 56
private const val TILE_LABEL = 90
private const val KEY = 64
private const val FRAME_WIDTH = 340
