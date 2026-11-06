package com.diegonmarcos.hardtechcad.ui

import androidx.compose.foundation.clickable
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
import com.diegonmarcos.superapp.uikit.KitStoreRow
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
 * #1542 THE model page: the 3D render on top, pinned (orbit with one finger, zoom with a pinch, pan with two; Fit and Wireframe
 * under it), and the FULL declared data below, one dense row per fact (ModelDetail.rows: dimensions and bounding box, units,
 * materials, layers and components, file source, licence, hashes, conversion log). A fact the data does not declare is not drawn.
 */
@Composable
fun ModelPage(m: Model, categories: List<ModelCategory>, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val dir = remember { File(ctx.cacheDir, "cad-models") }
    val rows = remember(m, categories) { ModelDetail.rows(m, categories) }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp).testTag("model_page:" + m.id), verticalArrangement = Arrangement.spacedBy(KitStoreRow.gap)) {
        Text(stringResource(R.string.model_back), color = p.accent, fontSize = KitStoreRow.title,
            modifier = Modifier.clickable(onClick = onBack).padding(vertical = KitStoreRow.gap).testTag("model_back"))
        ModelViewer(m, dir)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(KitStoreRow.tight)) {
            GroupTitle(stringResource(R.string.model_data_title))
            rows.forEach { r ->
                Row(Modifier.fillMaxWidth().testTag("detail:" + r.key), verticalAlignment = Alignment.Top) {
                    Text(detailLabel(r.key), color = p.textSecondary, fontSize = KitStoreRow.caption, modifier = Modifier.width(96.dp))
                    Text(r.value, color = p.textPrimary, fontSize = KitStoreRow.body,
                        fontFamily = if (r.key.endsWith("sha256") || r.key == "bbox" || r.key == "log") FontFamily.Monospace else null)
                }
            }
        }
    }
}
