package com.diegonmarcos.superapp.browser

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.uikit.KitSectionHeader
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #900 ONE Configs page: what the top tab [pageId] (libs:bottomnav PageTabs, drawn by the host shell above this
 * overlay) selects. The catalogue settings whose `section` is [pageId], then the page's global [rows] (the actions
 * the page dropdown no longer carries), then [native] (the blocks libs:browser draws itself: Autocomplete, Passwords,
 * Cards, Vault, About). Nothing here names a page or a setting.
 */
@Composable
fun BrowserConfigsPageScreen(
    pageId: String,
    title: String,
    catalogue: BrowserSettingsCatalogue,
    value: (String) -> Any?,
    onSet: (String, Any) -> Unit,
    rows: List<SheetRow>,
    onRow: (String) -> Unit,
    native: @Composable ColumnScope.() -> Unit = {},
    /** #1402 the page's titled groups: each draws its settings, then its rows, under its title (SheetRow.caption = the row's group). */
    groups: List<BrowserConfigGroup> = emptyList(),
) {
    val p = LocalKitPalette.current
    var tick by remember { mutableStateOf(0) }
    val mine = catalogue.settings.filter { it.section == pageId }
    val known = groups.map { it.id }.toSet()
    @Composable
    fun block(settings: List<BrowserSetting>, rs: List<SheetRow>) {
        settings.forEach { s ->
            val v = tick.let { value(s.key) }   // reading tick re-reads the store after a write
            BrowserSettingRow(s, v) { x -> onSet(s.key, x); tick += 1 }
        }
        if (rs.isNotEmpty()) {
            HorizontalDivider(color = p.hairline, modifier = Modifier.padding(top = 6.dp))
            rs.forEach { r ->
                KitSettingsRow(r.label, if (r.enabled) "" else r.why.orEmpty(), Modifier.testTag("browser:menu:${r.id}"),
                    onClick = if (r.enabled) ({ onRow(r.id) }) else null)
            }
        }
    }
    Column(
        Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp).testTag("browser:configs:page:$pageId"),
    ) {
        KitSectionHeader(title, "", Modifier.testTag("browser:configs:title:$pageId"))
        // ungrouped first (a setting or row naming no declared group), then each group under its title
        block(mine.filter { it.group !in known }, rows.filter { (it.caption ?: "") !in known })
        groups.forEach { g ->
            val gs = mine.filter { it.group == g.id }
            val gr = rows.filter { it.caption == g.id }
            if (gs.isEmpty() && gr.isEmpty()) return@forEach
            KitSectionHeader(g.label, "", Modifier.padding(top = 10.dp).testTag("browser:configs:group:${g.id}"))
            block(gs, gr)
        }
        native()
    }
}

/** About: key / value lines (the host gathers them: version, build, WebView). */
@Composable
fun BrowserAboutBlock(lines: List<Pair<String, String>>) {
    val p = LocalKitPalette.current
    Column(Modifier.testTag("browser:about")) {
        lines.forEach { (k, v) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(k, color = p.textSecondary, style = MaterialTheme.typography.bodyMedium)
                Text(v, color = p.textPrimary, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/** #900 zoom for THIS site: the percent, Reset back to the global text size. */
@Composable
fun BrowserSiteZoomPanel(host: String, current: Int?, global: Int, onSet: (Int?) -> Unit, onClose: () -> Unit) {
    val p = LocalKitPalette.current
    var f by remember(current) { mutableFloatStateOf((current ?: global).toFloat()) }
    Box(Modifier.fillMaxSize().background(Color(0x99000000)).clickable(onClick = onClose)) {
        Column(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth().heightIn(max = 320.dp)
                .background(p.surface, RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(16.dp).testTag("browser:zoom"),
        ) {
            Text("Zoom on $host", color = p.textPrimary, style = MaterialTheme.typography.titleLarge)
            Text(if (current == null) "${f.toInt()}% (the global text size)" else "${f.toInt()}% on this site",
                color = p.textSecondary, modifier = Modifier.padding(top = 4.dp))
            Slider(f, { f = it }, valueRange = BrowserSitePolicy.ZOOM_MIN.toFloat()..BrowserSitePolicy.ZOOM_MAX.toFloat(),
                onValueChangeFinished = { onSet(f.toInt()) }, modifier = Modifier.testTag("browser:zoom:slider"))
            Row {
                TextButton({ onSet(null) }, modifier = Modifier.testTag("browser:zoom:reset")) { Text("Reset") }
                TextButton(onClose) { Text("Done") }
            }
        }
    }
}
