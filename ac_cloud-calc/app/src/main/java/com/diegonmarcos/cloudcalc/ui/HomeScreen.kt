package com.diegonmarcos.cloudcalc.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.superapp.launcher.IndexTileGrid
import com.diegonmarcos.superapp.launcher.IndexTiles
import com.diegonmarcos.superapp.launcher.TileGroupHeader
import com.diegonmarcos.superapp.launcher.TileSubgroupHeader
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette

/** Tiles per row on Home (Cloud SuperApp's Cloud page default). */
private const val HOME_COLUMNS = 4

/**
 * #1893 Home (it took the AI page's slot): every tool of the app as an icon tile, in the sections of build.json::ui.hierarchy
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
        IndexTileGrid(
            HOME_COLUMNS,
            node.pages.map { id ->
                val t = Declarations.tabs.firstOrNull { it.id == id }
                IndexTiles.Cell(label = t?.label ?: id, icon = IconCatalog.vector(t?.icon.orEmpty()), onClick = { onOpen(id) })
            },
            Modifier.testTag(CalcTags.homeGroup(node.id)),
        )
    }
    node.children.forEach { HomeNode(it, depth + 1, onOpen) }
}
