package com.diegonmarcos.hardtechcad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCategory
import com.diegonmarcos.superapp.cad.ModelManifest
import com.diegonmarcos.superapp.cad.ModelTable
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

/** #1664 the pinned first column (the model's name) and the columns that scroll sideways beside it: every column reachable, the name always in view. */
internal val PINNED = ModelTable.Column.NAME
internal val SCROLLED = COLUMNS.filter { it != PINNED }

/** #1664 readable widths: a 15 sp row needs room; the scrolled block is wider than a phone on purpose (it scrolls, the name stays). */
internal fun widthOf(c: ModelTable.Column): Dp = when (c) {
    ModelTable.Column.NAME -> 168.dp
    ModelTable.Column.CATEGORY -> 140.dp
    ModelTable.Column.EXTENSION -> 64.dp
    ModelTable.Column.SOFTWARE -> 156.dp
    ModelTable.Column.SIZE -> 88.dp
    ModelTable.Column.GEOMETRY -> 148.dp
    ModelTable.Column.UPDATED -> 112.dp
}

private val HEADER_H = 44.dp
private val ROW_H = 56.dp

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
 * Updated. A header tap sorts a column (a second tap flips it); a row tap opens the model page.
 * #1664 readable: 15 sp rows, the Name column pinned while the other columns scroll sideways (Vert / Tri and Updated are reachable), and below the
 * table every model again as a CARD with its preview image (rendered from its GLB), from the SAME filtered and sorted list; a card tap opens the model.
 */
@Composable
fun ModelsPage(catalog: ModelCatalog?, onOpen: (Model) -> Unit) {
    val p = LocalKitPalette.current
    if (catalog == null) { Text(stringResource(R.string.models_loading), color = p.textSecondary, fontSize = CadType.body); return }
    var category by rememberSaveable { mutableStateOf(ModelTable.ALL) }
    var extension by rememberSaveable { mutableStateOf(ModelTable.ALL) }
    var software by rememberSaveable { mutableStateOf(ModelTable.ALL) }
    var sortColumn by rememberSaveable { mutableStateOf(ModelTable.Column.NAME.name) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val sort = ModelTable.Sort(ModelTable.Column.valueOf(sortColumn), descending)
    // ONE list for the table and the cards (#1664)
    val rows = ModelTable.shown(catalog.models, ModelTable.Filters(category, extension, software), sort)
    Column(Modifier.testTag("models_page"), verticalArrangement = Arrangement.spacedBy(CadType.gap)) {
        CadHeading(stringResource(R.string.models_title), stringResource(R.string.models_count, rows.size, catalog.models.size), Modifier.testTag("models_count"))
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(CadType.corner)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(CadType.corner))
                .padding(CadType.gutter).testTag("models_filters"),
            verticalArrangement = Arrangement.spacedBy(CadType.tight),
        ) {
            FilterRow(stringResource(R.string.models_filter_model), ModelTable.categoryOptions(catalog.models, catalog.categories), category, "model") { category = it }
            FilterRow(stringResource(R.string.models_filter_extension), ModelTable.extensionOptions(catalog.models), extension, "extension") { extension = it }
            FilterRow(stringResource(R.string.models_filter_software), ModelTable.softwareOptions(catalog.models), software, "software") { software = it }
        }
        ModelsTable(rows, catalog.categories, sort, onSort = { c -> val next = ModelTable.toggle(sort, c); sortColumn = next.column.name; descending = next.descending }, onOpen = onOpen)
        if (rows.isEmpty()) Text(stringResource(R.string.models_none), color = p.textSecondary, fontSize = CadType.body, modifier = Modifier.testTag("models_empty"))
        else {
            CadHeading(stringResource(R.string.models_cards_title), stringResource(R.string.models_cards_hint), Modifier.padding(top = CadType.tight))
            ModelCards(rows, catalog.categories, onOpen)
        }
    }
}

@Composable
private fun FilterRow(title: String, options: List<Pair<String, String>>, selected: String, group: String, onSelect: (String) -> Unit) {
    val p = LocalKitPalette.current
    Text(title, color = p.textSecondary, fontSize = CadType.label, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = CadType.tight / 2))
    CadChips(options, selected, group, onSelect)
}

/** The table: the pinned Name column on the left, the other columns in one block that scrolls sideways; every row the same height in both. */
@Composable
private fun ModelsTable(rows: List<Model>, categories: List<ModelCategory>, sort: ModelTable.Sort, onSort: (ModelTable.Column) -> Unit, onOpen: (Model) -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(CadType.corner)
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxWidth().clip(shape).background(p.surface).border(1.dp, p.hairline, shape).testTag("models_table")) {
        Row(Modifier.fillMaxWidth()) {
            Column(Modifier.width(widthOf(PINNED)).testTag("models_pinned")) {
                HeaderCell(PINNED, sort, onSort)
                rows.forEach { m -> Cell(m, PINNED, categories, Modifier.clickable { onOpen(m) }.testTag("model_row:" + m.id)) }
            }
            Box(Modifier.width(1.dp).height(HEADER_H + ROW_H * rows.size).background(p.hairline))
            Box(Modifier.weight(1f).horizontalScroll(scroll).testTag("models_scrolled")) {
                Column {
                    Row { SCROLLED.forEach { HeaderCell(it, sort, onSort) } }
                    rows.forEach { m -> Row(Modifier.clickable { onOpen(m) }.testTag("model_row_cells:" + m.id)) { SCROLLED.forEach { c -> Cell(m, c, categories) } } }
                }
            }
        }
        if (scroll.maxValue > 0) Text(
            stringResource(if (scroll.value < scroll.maxValue) R.string.models_scroll_hint else R.string.models_scroll_end),
            color = p.textSecondary, fontSize = CadType.caption, textAlign = TextAlign.End,
            modifier = Modifier.fillMaxWidth().background(p.surfaceSelected.copy(alpha = 0.4f)).padding(horizontal = CadType.gap, vertical = 6.dp).testTag("models_scroll_hint"),
        )
    }
}

@Composable
private fun HeaderCell(c: ModelTable.Column, sort: ModelTable.Sort, onSort: (ModelTable.Column) -> Unit) {
    val p = LocalKitPalette.current
    val arrow = if (c == sort.column) (if (sort.descending) " ▼" else " ▲") else ""
    val end = c == ModelTable.Column.SIZE || c == ModelTable.Column.GEOMETRY
    Box(Modifier.width(widthOf(c)).height(HEADER_H).background(p.surfaceSelected).clickable { onSort(c) }.padding(horizontal = CadType.gap).testTag("models_header:" + c.name),
        contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
        Text(label(c) + arrow, color = if (c == sort.column) p.accent else p.textPrimary, fontSize = CadType.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
    }
}

@Composable
private fun Cell(m: Model, c: ModelTable.Column, categories: List<ModelCategory>, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val end = c == ModelTable.Column.SIZE || c == ModelTable.Column.GEOMETRY
    Column(modifier.width(widthOf(c)).height(ROW_H)) {
        Box(Modifier.weight(1f).fillMaxWidth().padding(horizontal = CadType.gap), contentAlignment = if (end) Alignment.CenterEnd else Alignment.CenterStart) {
            val mono = c == ModelTable.Column.EXTENSION || c == ModelTable.Column.SIZE || c == ModelTable.Column.GEOMETRY || c == ModelTable.Column.UPDATED
            val text = when (c) {
                ModelTable.Column.NAME -> m.name
                ModelTable.Column.CATEGORY -> ModelTable.categoryLabel(m, categories)
                ModelTable.Column.EXTENSION -> "." + ModelTable.extension(m)
                ModelTable.Column.SOFTWARE -> m.software.ifBlank { "-" }
                ModelTable.Column.SIZE -> ModelTable.sizeText(m.size)
                ModelTable.Column.GEOMETRY -> ModelTable.geometryText(m)
                ModelTable.Column.UPDATED -> m.updated.ifBlank { "-" }
            }
            Text(text, color = if (c == ModelTable.Column.NAME) p.accent else p.textPrimary, fontSize = if (mono) CadType.label else CadType.body,
                fontWeight = if (c == ModelTable.Column.NAME) FontWeight.SemiBold else FontWeight.Normal, fontFamily = if (mono) FontFamily.Monospace else null,
                maxLines = if (c == ModelTable.Column.NAME) 2 else 1, overflow = TextOverflow.Ellipsis, lineHeight = CadType.title)
        }
        HorizontalDivider(color = p.hairline, thickness = 1.dp)
    }
}

/** #1664 every shown model as a card with its preview image, two per row. */
@Composable
private fun ModelCards(rows: List<Model>, categories: List<ModelCategory>, onOpen: (Model) -> Unit) {
    Column(Modifier.fillMaxWidth().testTag("models_cards"), verticalArrangement = Arrangement.spacedBy(CadType.gap)) {
        rows.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(CadType.gap)) {
                pair.forEach { m -> ModelCard(m, categories, Modifier.weight(1f)) { onOpen(m) } }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ModelCard(m: Model, categories: List<ModelCategory>, modifier: Modifier, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(CadType.corner)
    Column(modifier.clip(shape).background(p.surface).border(1.dp, p.hairline, shape).clickable(onClick = onClick).testTag("model_card:" + m.id)) {
        ModelThumb(ModelTable.thumb(m), Modifier.fillMaxWidth().aspectRatio(4f / 3f))
        Column(Modifier.padding(CadType.gap), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(m.name, color = p.textPrimary, fontSize = CadType.body, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = CadType.title)
            Text(ModelTable.categoryLabel(m, categories), color = p.textSecondary, fontSize = CadType.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                CadTag("." + ModelTable.extension(m), mono = true)
                CadTag(m.software.ifBlank { "-" }, modifier = Modifier.weight(1f, fill = false))
            }
            Text(ModelTable.sizeText(m.size), color = p.textSecondary, fontSize = CadType.label, fontFamily = FontFamily.Monospace)
        }
    }
}
