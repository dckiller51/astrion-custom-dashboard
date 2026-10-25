package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** An empty cell of a `button_grid` / `scene_grid` (1.3.7): `{ "spacer": true }`, to place the others. */
internal fun isGridSpacer(item: Map<String, Any?>): Boolean = item["spacer"] == true

/**
 * The rows of a `button_grid` / `scene_grid`: [columns] cells per row, empty
 * cells for `spacer` items, and — with [centerLastRow] — an incomplete last
 * row centred instead of left-aligned (1.3.7: a keypad's lone 0 under the 8).
 */
@Composable
internal fun GridRows(
    items: List<Map<String, Any?>>,
    columns: Int,
    centerLastRow: Boolean,
    cell: @Composable RowScope.(Map<String, Any?>) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp)) {
        items.chunked(columns).forEach { row ->
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(GRID_GAP_DP.dp)) {
                val missing = (columns - row.size).toFloat()
                val lead = if (centerLastRow) missing / 2f else 0f
                if (lead > 0f) Spacer(Modifier.weight(lead))
                row.forEach { item -> if (isGridSpacer(item)) Spacer(Modifier.weight(1f)) else cell(item) }
                if (missing - lead > 0f) Spacer(Modifier.weight(missing - lead))
            }
        }
    }
}

private const val GRID_GAP_DP = 10
