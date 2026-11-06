package com.diegonmarcos.hardtechcad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechcad.Decl
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.CadFormats
import com.diegonmarcos.superapp.cad.CadLibraries
import com.diegonmarcos.superapp.cad.CadLibrary
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.uikit.KitCard
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File

/**
 * #1404 A model-library sub-page of MCAD (ui.libraries[id]): the viewer with the page's default model, the Core Difference
 * table of every library side by side, and the formats with a one-line purpose, their viewer lib or store-only reason and the
 * library's models of that format (tap one to view it). The models are the bundled declaration (assets/cad/models.json), shared with the models table.
 */
@Composable
fun LibraryPage(id: String, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val libs = remember { CadLibraries.parse(Decl.libraries) }
    val lib = libs.firstOrNull { it.id == id } ?: run { onBack(); return }
    val dir = remember { File(ctx.cacheDir, "cad-models") }
    val models = rememberModelCatalog()?.models
    var selected by remember(id) { mutableStateOf<Model?>(null) }
    LaunchedEffect(id, models) {
        if (selected == null && models != null) selected = CadLibraries.defaultModel(lib, models)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).testTag("library:$id"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.lib_back), color = p.accent, fontSize = KitStoreRow.title,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = 6.dp))
        KitSectionHeader(lib.title, lib.subtitle)
        // The viewer stays pinned above the scrolling content: a WebView inside a scrolling column did not paint on the emulator.
        selected?.let { m -> ModelViewer(m, dir) } ?: Box(Modifier.fillMaxWidth().height(300.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF0E1116)))
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GroupTitle(stringResource(R.string.lib_core_title))
        CoreTable(libs, lib)

        GroupTitle(stringResource(R.string.lib_formats_title))
        lib.formats.forEach { f ->
            val route = CadFormats.route(f.exts.firstOrNull().orEmpty())
            val how = when (route) {
                is CadFormats.Route.Viewer -> stringResource(R.string.lib_viewer, route.lib)
                is CadFormats.Route.StoreOnly -> route.reason
            }
            KitCard(Modifier.testTag("format:${f.label}")) {
                KitSettingsRow(title = f.label, subtitle = f.purpose + "\n" + how)
                val ms = models?.let { CadLibraries.modelsOf(lib, f, it) }.orEmpty()
                if (ms.isEmpty()) Text(stringResource(R.string.lib_models_none), color = p.textSecondary, fontSize = KitStoreRow.meta)
                ms.forEach { m ->
                    Text("• " + m.name + "  (" + mb(m.size) + ", " + m.licence + ")",
                        color = if (m.id == selected?.id) p.accent else p.textPrimary, fontSize = KitStoreRow.body,
                        modifier = Modifier.fillMaxWidth().clickable { selected = m }.padding(vertical = 4.dp))
                }
            }
        }
        }
    }
}

/** Core Difference: 3 rows (geometry, need, standards), one column per declared library, this page's column highlighted. */
@Composable
private fun CoreTable(libs: List<CadLibrary>, current: CadLibrary) {
    val p = LocalKitPalette.current
    val labels = mapOf(
        "geometry" to stringResource(R.string.lib_core_geometry),
        "need" to stringResource(R.string.lib_core_need),
        "standards" to stringResource(R.string.lib_core_standards),
    )
    KitCard(Modifier.testTag("core_table")) {
        Row(Modifier.fillMaxWidth()) {
            Cell(stringResource(R.string.lib_aspect), p.textSecondary, bold = true, w = 0.8f)
            libs.forEach { Cell(it.title, if (it.id == current.id) p.accent else p.textSecondary, bold = true) }
        }
        CadLibraries.CORE_ROWS.forEach { row ->
            Row(Modifier.fillMaxWidth()) {
                Cell(labels[row].orEmpty(), p.textSecondary, w = 0.8f)
                libs.forEach { Cell(it.core[row].orEmpty(), if (it.id == current.id) p.textPrimary else p.textSecondary, bold = row == "standards") }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Cell(t: String, c: Color, bold: Boolean = false, w: Float = 1f) =
    Text(t, color = c, fontSize = KitStoreRow.meta, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, modifier = Modifier.weight(w).padding(4.dp))

private fun mb(bytes: Long) = "%.1f MB".format(bytes / 1_000_000.0)
