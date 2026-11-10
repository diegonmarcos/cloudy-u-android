package com.diegonmarcos.cloudlib.editorcore

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.superapp.uikit.KitDensity

/** Test tags of the Editor shell. */
object EditorTags {
    const val STRIP = "editor_strip"
    const val MODE_BAR = "editor_mode_bar"
    const val CONTENT = "editor_content"
    const val UNSUPPORTED = "editor_unsupported"
    const val EXPORT = "editor_export"
    fun tab(path: String) = "editor_tab_$path"
    fun close(path: String) = "editor_tab_close_$path"
    fun mode(mode: EditorMode) = "editor_mode_${mode.name.lowercase()}"
    fun export(action: EditorExport) = "editor_export_${action.name.lowercase()}"
}

/** The words of the shell; the host passes its own translation, the defaults are English. */
data class EditorLabels(
    val view: String = "View",
    val edit: String = "Edit",
    val served: String = "Served",
    val close: String = "Close tab",
    val unsupported: String = "Unsupported format",
    val viewOnly: String = "View only",
    val export: String = "Export",
    val exportClipboard: String = "Copy to clipboard",
    val exportDownload: String = "Copy to Download",
    val exportPath: String = "Copy path",
)

/** #1607 the Export dropdown's three actions, carried out by the host against the active tab's path. */
enum class EditorExport { CLIPBOARD, DOWNLOAD, PATH }

/**
 * The Editor shell: the tab strip, the View | Edit toggle of the active tab, and the active tab's body. [content] draws the
 * body for a tab and its [Route] (the host maps the route's viewer to the owning lib); an unsupported route never reaches
 * it and shows [EditorLabels.unsupported]. [empty] is drawn when no tab is open.
 */
@Composable
fun EditorHost(
    tabs: EditorTabs,
    onTabs: (EditorTabs) -> Unit,
    modifier: Modifier = Modifier,
    labels: EditorLabels = EditorLabels(),
    router: FileTypeRouter = remember { FileTypeRouter.default() },
    empty: @Composable () -> Unit = {},
    onExport: ((EditorExport, String) -> Unit)? = null,
    content: @Composable (tab: EditorTab, route: Route) -> Unit,
) {
    Column(modifier.fillMaxSize()) {
        if (tabs.tabs.isNotEmpty()) {
            EditorTabStrip(tabs, onActivate = { onTabs(tabs.activate(it)) }, onClose = { onTabs(tabs.close(it)) })
        }
        val active = tabs.active
        if (active == null) {
            Box(Modifier.weight(1f).fillMaxWidth()) { empty() }
        } else {
            val route = remember(active.path) { router.routeFile(java.io.File(active.path)) }
            ModeBar(active.mode, route, labels, onMode = { onTabs(tabs.setMode(active.path, it)) }, exportFor = onExport?.let { cb -> { action -> cb(action, active.path) } })
            Box(Modifier.weight(1f).fillMaxWidth().testTag(EditorTags.CONTENT)) {
                key(active.path, active.mode) {
                    if (route.supported) content(active, route)
                    else Text(
                        labels.unsupported + ": ." + route.extension,
                        Modifier.padding(KitDensity.large).testTag(EditorTags.UNSUPPORTED),
                        style = MaterialTheme.typography.bodyMedium,
                        fontSize = KitDensity.body,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The strip alone: one dense chip per opened file, the active one lit, each with its close button. */
@Composable
fun EditorTabStrip(tabs: EditorTabs, onActivate: (String) -> Unit, onClose: (String) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2).testTag(EditorTags.STRIP),
        horizontalArrangement = Arrangement.spacedBy(KitDensity.rowPadH / 2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (t in tabs.tabs) {
            val on = t.path == tabs.activePath
            Row(
                Modifier.testTag(EditorTags.tab(t.path)).clip(RoundedCornerShape(KitDensity.small))
                    .background(if (on) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)
                    .clickable(role = Role.Tab) { onActivate(t.path) }
                    .padding(start = KitDensity.small, end = KitDensity.rowPadV / 2, top = KitDensity.rowPadV / 2, bottom = KitDensity.rowPadV / 2),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    t.title, Modifier.widthIn(max = KitDensity.avatar * 3), maxLines = 1, overflow = TextOverflow.Ellipsis,
                    fontSize = KitDensity.body, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Icon(
                    Icons.Filled.Close, contentDescription = null,
                    Modifier.testTag(EditorTags.close(t.path)).padding(start = KitDensity.rowPadV / 2).size(KitDensity.glyph - KitDensity.small).clickable(role = Role.Button) { onClose(t.path) },
                    tint = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * The Obsidian-style toggle of the active tab: View | Edit, or the plain "view only" word for a viewer with no Edit mode.
 * #1607 the Export dropdown ([exportFor] non-null) sits at the end, after the View/Edit controls (top-right).
 */
@Composable
private fun ModeBar(mode: EditorMode, route: Route, labels: EditorLabels, onMode: (EditorMode) -> Unit, exportFor: ((EditorExport) -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().height(KitDensity.glyph + KitDensity.small).padding(horizontal = KitDensity.rowPadH).testTag(EditorTags.MODE_BAR),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (!route.supported) return@Row
        if (route.modes.size < 2) {
            Text(labels.viewOnly, fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            for (m in route.modes) {
                val on = m == mode
                Text(
                    when (m) { EditorMode.View -> labels.view; EditorMode.Edit -> labels.edit; EditorMode.Served -> labels.served },
                    Modifier.testTag(EditorTags.mode(m)).padding(start = KitDensity.rowPadH / 2).clip(RoundedCornerShape(KitDensity.small))
                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .clickable(role = Role.Tab) { onMode(m) }
                        .padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV / 2),
                    fontSize = KitDensity.body,
                    color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (exportFor != null) ExportMenu(labels, exportFor)
    }
}

/** #1607 the Export dropdown: whole-file Copy to clipboard, Copy to Download, Copy path — carried out by the host. */
@Composable
private fun ExportMenu(labels: EditorLabels, onExport: (EditorExport) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Text(
        labels.export,
        Modifier.testTag(EditorTags.EXPORT).padding(start = KitDensity.rowPadH).clip(RoundedCornerShape(KitDensity.small))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(role = Role.Button) { open = true }
            .padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV / 2),
        fontSize = KitDensity.body,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
        DropdownMenuItem(text = { Text(labels.exportClipboard) }, modifier = Modifier.testTag(EditorTags.export(EditorExport.CLIPBOARD)), onClick = { open = false; onExport(EditorExport.CLIPBOARD) })
        DropdownMenuItem(text = { Text(labels.exportDownload) }, modifier = Modifier.testTag(EditorTags.export(EditorExport.DOWNLOAD)), onClick = { open = false; onExport(EditorExport.DOWNLOAD) })
        DropdownMenuItem(text = { Text(labels.exportPath) }, modifier = Modifier.testTag(EditorTags.export(EditorExport.PATH)), onClick = { open = false; onExport(EditorExport.PATH) })
    }
}
