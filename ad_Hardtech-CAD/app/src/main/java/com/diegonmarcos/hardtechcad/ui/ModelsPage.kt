package com.diegonmarcos.hardtechcad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCategory
import com.diegonmarcos.superapp.cad.ModelManifest
import com.diegonmarcos.superapp.cad.ModelTable
import com.diegonmarcos.superapp.uikit.KitFilterChips
import com.diegonmarcos.superapp.uikit.KitStoreCaption
import com.diegonmarcos.superapp.uikit.KitStoreName
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.KitStoreRowLine
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The declared model data (assets/cad/models.json, bundled in the APK): every model, the declared categories. */
class ModelCatalog(val models: List<Model>, val categories: List<ModelCategory>)

/** Reads the bundled declaration once per composition; null while it loads. No network: the table and the model data are on the phone. */
@Composable
fun rememberModelCatalog(): ModelCatalog? {
    val ctx = LocalContext.current
    var catalog by remember { mutableStateOf<ModelCatalog?>(null) }
    LaunchedEffect(Unit) {
        val text = withContext(Dispatchers.IO) { runCatching { ctx.assets.open(MODELS_ASSET).use { it.readBytes().toString(Charsets.UTF_8) } }.getOrDefault("") }
        catalog = ModelCatalog(ModelManifest.parse(text), ModelManifest.categories(text))
    }
    return catalog
}

private const val MODELS_ASSET = "cad/models.json"

private val COLUMNS = ModelTable.Column.entries

private fun widthOf(c: ModelTable.Column): Dp = when (c) {
    ModelTable.Column.NAME -> 150.dp
    ModelTable.Column.CATEGORY -> 96.dp
    ModelTable.Column.EXTENSION -> 56.dp
    ModelTable.Column.SOFTWARE -> 72.dp
    ModelTable.Column.SIZE -> 64.dp
    ModelTable.Column.GEOMETRY -> 104.dp
    ModelTable.Column.UPDATED -> 76.dp
}

@Composable
private fun label(c: ModelTable.Column): String = stringResource(when (c) {
    ModelTable.Column.NAME -> R.string.models_col_name
    ModelTable.Column.CATEGORY -> R.string.models_col_category
    ModelTable.Column.EXTENSION -> R.string.models_col_extension
    ModelTable.Column.SOFTWARE -> R.string.models_col_software
    ModelTable.Column.SIZE -> R.string.models_col_size
    ModelTable.Column.GEOMETRY -> R.string.models_col_geometry
    ModelTable.Column.UPDATED -> R.string.models_col_updated
})

/**
 * #1542 THE models page: three filters at the top (Model | Extension | Software, each "All" by default and each derived from the
 * declared data) and every model as ONE sortable table: Name | Category | Extension | Software | Size | Vertices / Triangles |
 * Updated. A header tap sorts a column (a second tap flips it); a row tap opens the model page. Dense rows of the ui-kit type ramp.
 */
@Composable
fun ModelsPage(catalog: ModelCatalog?, onOpen: (Model) -> Unit) {
    val p = LocalKitPalette.current
    if (catalog == null) { Text(stringResource(R.string.models_loading), color = p.textSecondary, fontSize = KitStoreRow.body); return }
    var category by rememberSaveable { mutableStateOf(ModelTable.ALL) }
    var extension by rememberSaveable { mutableStateOf(ModelTable.ALL) }
    var software by rememberSaveable { mutableStateOf(ModelTable.ALL) }
    var sortColumn by rememberSaveable { mutableStateOf(ModelTable.Column.NAME.name) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val sort = ModelTable.Sort(ModelTable.Column.valueOf(sortColumn), descending)
    val rows = ModelTable.sort(ModelTable.filter(catalog.models, ModelTable.Filters(category, extension, software)), sort)
    Column(Modifier.testTag("models_page"), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
        FilterRow(stringResource(R.string.models_filter_model), ModelTable.categoryOptions(catalog.models, catalog.categories), category, "model") { category = it }
        FilterRow(stringResource(R.string.models_filter_extension), ModelTable.extensionOptions(catalog.models), extension, "extension") { extension = it }
        FilterRow(stringResource(R.string.models_filter_software), ModelTable.softwareOptions(catalog.models), software, "software") { software = it }
        val total = COLUMNS.fold(0.dp) { acc, c -> acc + widthOf(c) }
        Box(Modifier.horizontalScroll(rememberScrollState())) {
            Column(Modifier.width(total).testTag("models_table")) {
                Row(Modifier.background(p.surfaceSelected).padding(horizontal = KitStoreRow.padH, vertical = KitStoreRow.tight), verticalAlignment = Alignment.CenterVertically) {
                    COLUMNS.forEach { c ->
                        val arrow = if (c == sort.column) (if (sort.descending) " ▼" else " ▲") else ""
                        KitStoreName(label(c) + arrow, Modifier.width(widthOf(c)).clickable {
                            val next = ModelTable.toggle(sort, c); sortColumn = next.column.name; descending = next.descending
                        }.padding(horizontal = KitStoreRow.tight).testTag("models_header:" + c.name), color = p.textPrimary)
                    }
                }
                rows.forEach { m ->
                    KitStoreRowLine(Modifier.testTag("model_row:" + m.id), onClick = { onOpen(m) }) {
                        COLUMNS.forEach { c ->
                            val cell = Modifier.width(widthOf(c)).padding(horizontal = KitStoreRow.tight)
                            when (c) {
                                ModelTable.Column.NAME -> KitStoreName(m.name, cell, color = p.accent)
                                ModelTable.Column.CATEGORY -> KitStoreCaption(ModelTable.categoryLabel(m, catalog.categories), cell, color = p.textPrimary)
                                ModelTable.Column.EXTENSION -> KitStoreCaption("." + ModelTable.extension(m), cell, color = p.textPrimary, mono = true)
                                ModelTable.Column.SOFTWARE -> KitStoreCaption(m.software.ifBlank { "-" }, cell, color = p.textPrimary)
                                ModelTable.Column.SIZE -> KitStoreCaption(ModelTable.sizeText(m.size), cell, color = p.textPrimary, mono = true, align = TextAlign.End)
                                ModelTable.Column.GEOMETRY -> KitStoreCaption(ModelTable.geometryText(m), cell, color = p.textPrimary, mono = true, align = TextAlign.End)
                                ModelTable.Column.UPDATED -> KitStoreCaption(m.updated.ifBlank { "-" }, cell, color = p.textPrimary, mono = true)
                            }
                        }
                    }
                }
            }
        }
        if (rows.isEmpty()) Text(stringResource(R.string.models_none), color = p.textSecondary, fontSize = KitStoreRow.body, modifier = Modifier.testTag("models_empty"))
    }
}

@Composable
private fun FilterRow(title: String, options: List<Pair<String, String>>, selected: String, group: String, onSelect: (String) -> Unit) {
    val p = LocalKitPalette.current
    Text(title, color = p.textSecondary, fontSize = KitStoreRow.caption)
    KitFilterChips(options, selected, onSelect, group = group)
}
