package com.diegonmarcos.hardtechcad.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.max
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.diegonmarcos.hardtechcad.R
import com.diegonmarcos.superapp.cad.Model
import com.diegonmarcos.superapp.cad.ModelCategory
import com.diegonmarcos.superapp.cad.ModelDetail
import com.diegonmarcos.superapp.uikit.GroupTitle
import com.diegonmarcos.superapp.cad.ModelTable
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.io.File

@Composable
private fun detailLabel(key: String): String = stringResource(when (key) {
    "name" -> R.string.detail_name
    "category" -> R.string.detail_category
    "extension" -> R.string.detail_extension
    "software" -> R.string.detail_software
    "size" -> R.string.detail_size
    "updated" -> R.string.detail_updated
    "geometry" -> R.string.detail_geometry
    "dimensions" -> R.string.detail_dimensions
    "bbox" -> R.string.detail_bbox
    "units" -> R.string.detail_units
    "materials" -> R.string.detail_materials
    "layers" -> R.string.detail_layers
    "components" -> R.string.detail_components
    "source" -> R.string.detail_source
    "licence" -> R.string.detail_licence
    "sha256" -> R.string.detail_sha256
    "view_sha256" -> R.string.detail_view_sha256
    "view_tool" -> R.string.detail_view_tool
    "derived_sha256" -> R.string.detail_derived_sha256
    "derived_tool" -> R.string.detail_derived_tool
    "conversion" -> R.string.detail_conversion
    else -> R.string.detail_log
})

/**
 * #1542 THE model page: the 3D render on top (orbit with one finger, zoom with a pinch, pan with two; Fit and Wireframe under it), and the FULL
 * declared data below, one row per fact (ModelDetail.rows: dimensions and bounding box, units, materials, layers and components, file source,
 * licence, hashes, conversion log). A fact the data does not declare is not drawn.
 * #1664 redesigned around the viewer: a header (back, name, category and software), the viewer taking most of the screen height with its presets,
 * reset, wireframe, background and full-screen controls, the key facts as tiles (format, triangles, vertices, size), then the full data card.
 * Full screen keeps the SAME viewer (same WebView, same model) and hides everything else.
 */
@Composable
fun ModelPage(m: Model, categories: List<ModelCategory>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val dir = remember { File(ctx.cacheDir, "cad-models") }
    val rows = remember(m, categories) { ModelDetail.rows(m, categories) }
    var full by rememberSaveable(m.id) { mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize().testTag("model_page:" + m.id)) {
        val avail = if (maxHeight.value.isFinite()) maxHeight else 640.dp
        val viewerH = if (full) max(320.dp, avail - 150.dp) else max(320.dp, avail * 0.58f)
        Column(
            Modifier.fillMaxSize().then(if (full) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(horizontal = CadType.gutter, vertical = CadType.tight),
            verticalArrangement = Arrangement.spacedBy(CadType.gap),
        ) {
            if (!full) Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.model_back), color = p.accent, fontSize = CadType.label, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(onClick = onBack).padding(vertical = CadType.tight).testTag("model_back"))
                Text(m.name, color = p.textPrimary, fontSize = CadType.display, fontWeight = FontWeight.SemiBold, lineHeight = CadType.display * 1.2f, modifier = Modifier.testTag("model_title"))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(ModelTable.categoryLabel(m, categories), color = p.textSecondary, fontSize = CadType.label)
                    CadTag("." + ModelTable.extension(m), mono = true)
                    if (m.software.isNotBlank()) CadTag(m.software)
                }
            }
            ModelViewer(m, dir, height = viewerH, full = full, onFull = { full = !full })
            if (!full) {
                FactTiles(m)
                Column(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(CadType.corner)).background(p.surface).border(1.dp, p.hairline, RoundedCornerShape(CadType.corner)).padding(CadType.gutter),
                    verticalArrangement = Arrangement.spacedBy(CadType.tight),
                ) {
                    GroupTitle(stringResource(R.string.model_data_title))
                    rows.forEachIndexed { i, r ->
                        if (i > 0) HorizontalDivider(color = p.hairline, thickness = 1.dp)
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag("detail:" + r.key), verticalAlignment = Alignment.Top) {
                            Text(detailLabel(r.key), color = p.textSecondary, fontSize = CadType.label, modifier = Modifier.width(112.dp))
                            Text(r.value, color = p.textPrimary, fontSize = if (r.key.endsWith("sha256") || r.key == "bbox" || r.key == "log") CadType.caption else CadType.body,
                                fontFamily = if (r.key.endsWith("sha256") || r.key == "bbox" || r.key == "log") FontFamily.Monospace else null)
                        }
                    }
                }
            }
        }
    }
}

/** #1664 the four facts read first, as tiles: the view's format, triangles, vertices and the original's size. */
@Composable
private fun FactTiles(m: Model) {
    val facts = listOf(
        "format" to (stringResource(R.string.fact_format) to (m.format.uppercase() + (m.view?.let { " → " + it.format.uppercase() } ?: ""))),
        "triangles" to (stringResource(R.string.fact_triangles) to ModelTable.countText(ModelTable.triangles(m)).let { if (ModelTable.triangles(m) < 0) "-" else it }),
        "vertices" to (stringResource(R.string.fact_vertices) to ModelTable.countText(ModelTable.vertices(m)).let { if (ModelTable.vertices(m) < 0) "-" else it }),
        "size" to (stringResource(R.string.fact_size) to ModelTable.sizeText(m.size)),
    )
    Column(verticalArrangement = Arrangement.spacedBy(CadType.tight), modifier = Modifier.testTag("model_facts")) {
        facts.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(CadType.tight)) {
                pair.forEach { (key, kv) -> FactTile(kv.first, kv.second, Modifier.weight(1f).testTag("fact:$key")) }
            }
        }
    }
}

@Composable
private fun FactTile(label: String, value: String, modifier: Modifier) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(CadType.corner)
    Column(modifier.clip(shape).background(p.surface).border(1.dp, p.hairline, shape).padding(horizontal = CadType.gap, vertical = 10.dp)) {
        Text(label.uppercase(), color = p.textSecondary, fontSize = CadType.caption, fontWeight = FontWeight.SemiBold)
        Text(value, color = p.textPrimary, fontSize = CadType.title, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}
