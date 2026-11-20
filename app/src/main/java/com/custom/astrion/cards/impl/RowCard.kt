package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.cards.Visibility

/**
 * Generic horizontal container: lays out child cards side by side with equal
 * weight. Lets the file config build rows (e.g. two cover buttons) without a
 * dedicated card type per combination.
 *
 * Config shape:
 *   { "type": "row", "options": { "cards": [
 *       { "type": "cover", "options": { "entity_id": "cover.blinds", "name": "Sofa" } },
 *       { "type": "cover", "options": { "entity_id": "cover.blinds", "name": "Bed" } }
 *   ] } }
 *
 * A child whose own `visibility` rules don't hold is left out (see Visibility); the others share its room.
 */
class RowCard : CardRenderer {
    override val type = "row"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val children = (config.options["cards"] as? List<Map<String, Any?>>) ?: emptyList()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            children.forEachIndexed { i, child ->
                val childType = child["type"] as? String ?: return@forEachIndexed
                val childOptions = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                val renderer = CardRegistry.get(childType) ?: return@forEachIndexed
                if (!Visibility.isVisible(childOptions["visibility"], ctx.entities)) return@forEachIndexed
                // Keyed by position in the full list, so a child keeps its own state while one before it hides.
                key(i) {
                    Box(modifier = Modifier.weight(1f)) {
                        renderer.Render(CardConfig(childType, childOptions), ctx)
                    }
                }
            }
        }
    }
}
