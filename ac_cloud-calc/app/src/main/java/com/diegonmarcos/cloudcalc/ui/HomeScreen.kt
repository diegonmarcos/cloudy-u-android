package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.superapp.launcher.CHIP_GAP
import com.diegonmarcos.superapp.launcher.CompactTileCore
import com.diegonmarcos.superapp.launcher.TileGroupHeader
import com.diegonmarcos.superapp.launcher.TileSubgroupHeader
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/**
 * #1893 Home (it took the AI page's slot): every tool of the app as the SuperApp's launcher tile (icon, label below), ui.tile_columns per row, in the sections of build.json::ui.sections[home].hierarchy
 * (1 Calculations, 2 Toolkit; their groups as sub-headings), drawn by libs:tile-grid, the very component Cloud SuperApp's Cloud page
 * uses. A tile opens its page through the section it belongs to.
 */
@Composable
fun HomeMode() {
    val state = LocalCalcState.current
    val c = MaterialTheme.colorScheme
    val palette = KitPalette(c.surfaceVariant, c.surfaceVariant, c.onSurface, c.onSurfaceVariant, c.primary, c.outline, c.onPrimary)
    CloudKitTheme(palette) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 12.dp).testTag(CalcTags.HOME)) {
            Declarations.hierarchy.forEach { HomeNode(it, 0) { page -> open(state, page) } }
        }
    }
}

private fun open(state: CalcState, page: String) {
    state.showSection(Declarations.sectionOf(page))
    state.tab = page
}

@Composable
private fun HomeNode(node: Declarations.HNode, depth: Int, onOpen: (String) -> Unit) {
    if (depth == 0) TileGroupHeader(node.label) else TileSubgroupHeader(node.label)
    if (node.pages.isNotEmpty()) {
        Column(Modifier.testTag(CalcTags.homeGroup(node.id))) {
            node.pages.chunked(Declarations.tileColumns).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(vertical = CHIP_GAP / 2), horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                    row.forEach { id ->
                        val t = Declarations.tabs.firstOrNull { it.id == id }
                        Box(Modifier.weight(1f)) {
                            CompactTileCore(id, t?.label ?: id, { onOpen(id) }, Modifier.fillMaxWidth()) { m, tint ->
                                Icon(IconCatalog.vector(t?.icon.orEmpty()), contentDescription = null, tint = tint, modifier = m)
                            }
                        }
                    }
                    repeat(Declarations.tileColumns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
    node.children.forEach { HomeNode(it, depth + 1, onOpen) }
}
