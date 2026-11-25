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
            Declarations.homeTree.forEach { HomeNode(it, 0) { l -> open(state, l) } }
        }
    }
}

private fun open(state: CalcState, l: Declarations.HomeLeaf) {
    state.showSection(Declarations.sectionOf(l.page))
    state.tab = l.page
    l.mode?.let { state.modeByTab[l.page] = it }
}

/** #1909 the generic recursive renderer of Declarations.homeTree: a heading per group, indented one step per depth, its tiles and sub-groups under it. */
@Composable
private fun HomeNode(node: Declarations.HomeGroup, depth: Int, onOpen: (Declarations.HomeLeaf) -> Unit) {
    val indent = Modifier.padding(start = (INDENT * depth).dp)
    if (depth == 0) TileGroupHeader(node.label, indent) else TileSubgroupHeader(node.label, Modifier.padding(start = (INDENT * (depth - 1)).dp))
    // Consecutive tiles of a group form one block; a sub-group breaks the block.
    val rows = mutableListOf<Any>()
    node.items.forEach { item ->
        if (item is Declarations.HomeLeaf && rows.lastOrNull() is MutableList<*>) {
            @Suppress("UNCHECKED_CAST") (rows.last() as MutableList<Declarations.HomeLeaf>) += item
        } else rows += if (item is Declarations.HomeLeaf) mutableListOf(item) else item
    }
    rows.forEach { r ->
        if (r is Declarations.HomeGroup) HomeNode(r, depth + 1, onOpen)
        else {
            @Suppress("UNCHECKED_CAST") val leaves = r as List<Declarations.HomeLeaf>
            Column(Modifier.padding(start = (INDENT * (depth + 1)).dp).testTag(CalcTags.homeGroup(node.id))) {
                leaves.chunked(Declarations.tileColumns).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(vertical = CHIP_GAP / 2), horizontalArrangement = Arrangement.spacedBy(CHIP_GAP)) {
                        row.forEach { l ->
                            Box(Modifier.weight(1f)) {
                                CompactTileCore(l.id, l.label, { onOpen(l) }, Modifier.fillMaxWidth()) { m, tint ->
                                    Icon(IconCatalog.vector(l.icon), contentDescription = null, tint = tint, modifier = m)
                                }
                            }
                        }
                        repeat(Declarations.tileColumns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}

private const val INDENT = 12
