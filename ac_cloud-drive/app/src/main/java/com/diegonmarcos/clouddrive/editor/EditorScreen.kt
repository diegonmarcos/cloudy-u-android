package com.diegonmarcos.clouddrive.editor

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.cloudlib.editorcore.EditorExport
import com.diegonmarcos.cloudlib.editorcore.EditorHost
import com.diegonmarcos.cloudlib.editorcore.EditorMode
import com.diegonmarcos.cloudlib.editorcore.EditorTab
import com.diegonmarcos.cloudlib.editorcore.EditorTabs
import com.diegonmarcos.cloudlib.editorcore.FileTabsStore
import com.diegonmarcos.cloudlib.editorcore.HexView
import com.diegonmarcos.cloudlib.editorcore.Route
import com.diegonmarcos.cloudlib.editorcore.Viewers
import com.diegonmarcos.cloudlib.jsonviewer.JsonViewer
import com.diegonmarcos.cloudlib.localserve.ServedPage
import com.diegonmarcos.cloudlib.mediaviewer.MediaKind
import com.diegonmarcos.cloudlib.mediaviewer.MediaViewer
import com.diegonmarcos.cloudlib.odf.OdfViewer
import com.diegonmarcos.cloudlib.pdf.PdfViewer
import com.diegonmarcos.cloudlib.wysiwyg.TextFormat
import com.diegonmarcos.cloudlib.wysiwyg.WysiwygEditor
import com.diegonmarcos.cloudlib.wysiwyg.WysiwygView
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * #1484 the Editor tab: app-side glue only. The tab strip, the View | Edit toggle and the type routing are libs:editor-core,
 * the viewers and editors are libs:wysiwyg, libs:media-viewer, libs:pdf and libs:odf; this file picks the lib for a route,
 * keeps the tabs across restarts and takes the files Explorer hands over ([EditorHook]).
 */
@Composable
fun EditorScreen(actions: DriveActions, openInExplorer: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { FileTabsStore(ctx, "cloud-drive-editor-tabs.txt") }
    var tabs by remember { mutableStateOf(store.load()) }
    LaunchedEffect(tabs) { store.save(tabs) }
    val pending by EditorHook.pending.collectAsState()
    val scope = rememberCoroutineScope()
    var explorerOpen by rememberSaveable { mutableStateOf(false) }
    BackHandler(enabled = explorerOpen) { explorerOpen = false }
    LaunchedEffect(pending) {
        pending?.let { tabs = tabs.open(it); EditorHook.pending.value = null; explorerOpen = false }
    }
    // #1714 the file explorer panel: the hamburger sits at the top-left of the editor content (below the app bar), the panel slides over it.
    Box(modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize()) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
        IconButton(onClick = { explorerOpen = true }, modifier = Modifier.size(DriveMetrics.tap).testTag(DriveTags.EDITOR_MENU)) { Icon(Icons.Filled.Folder, stringResource(R.string.editor_explorer_open), Modifier.size(DriveMetrics.glyphIcon)) }
        androidx.compose.material3.Text(stringResource(R.string.editor_explorer_files), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    EditorHost(
        tabs = tabs, onTabs = { tabs = it }, modifier = Modifier.weight(1f).fillMaxWidth().testTag(DriveTags.EDITOR), router = EditorHook.router,
        // the empty state scrolls, so the shell's chrome collapse follows it like on every other page (a viewer owns its own scrolling)
        empty = { Box(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { EmptyState(IconCatalog.vectorOrDefault("edit_note"), stringResource(R.string.editor_empty_title), stringResource(R.string.editor_empty_hint)) } },
        // #1607 Export: whole-file Copy to clipboard (read off the main thread), Copy to Download (MediaStore), Copy path.
        onExport = { action, path ->
            when (action) {
                EditorExport.CLIPBOARD -> scope.launch { val text = withContext(Dispatchers.IO) { runCatching { File(path).readText() }.getOrNull() }; if (text != null) actions.copyText(text) }
                EditorExport.DOWNLOAD -> actions.copyToDownload(listOf(path))
                EditorExport.PATH -> actions.copyText(path)
            }
        },
    ) { tab, route -> EditorBody(tab, route) }
    }
    if (explorerOpen) Row(Modifier.fillMaxSize()) {
        EditorExplorerPanel(actions, tabs.activePath, { explorerOpen = false }, Modifier.fillMaxWidth(0.88f))
        Box(Modifier.weight(1f).fillMaxHeight().background(Color.Black.copy(alpha = 0.4f)).clickable { explorerOpen = false })
    }
    }
}

@Composable
private fun EditorBody(tab: EditorTab, route: Route) {
    val editing = tab.mode == EditorMode.Edit
    when (route.viewer) {
        // Markdown and HTML: View = the rendered WYSIWYG, editable; Edit = the raw source; Served (HTML) = the page from a loopback server.
        Viewers.WYSIWYG -> {
            val format = TextFormat.of(route.format)
            when {
                tab.mode == EditorMode.Served -> ServedPage(File(tab.path))
                // #1537 SVG: View = the picture from the loopback server (no bitmap decoder draws it), Edit = the XML source.
                route.format == "svg" && !editing -> ServedPage(File(tab.path))
                editing -> TextBody(tab.path, TextFormat.Text, editable = true)
                format == TextFormat.Text -> TextBody(tab.path, TextFormat.Text, editable = false)
                else -> TextBody(tab.path, format, editable = true)
            }
        }
        // JSON: View = the tree viewer; Edit falls back to the source editor.
        Viewers.JSON -> if (editing) TextBody(tab.path, TextFormat.Text, editable = true) else TextBody(tab.path, TextFormat.Text, editable = false, json = true)
        // #1537 images: View = zoom, pan, rotate and the EXIF Info panel, Edit = rotate, crop, annotate, save; audio and video are view only.
        Viewers.MEDIA -> MediaViewer(tab.path, MediaKind.of(route.format) ?: MediaKind.Image, edit = editing)
        // #1537 a binary file, or a type nothing else claims: the hex dump.
        Viewers.HEX -> HexView(File(tab.path))
        Viewers.PDF -> PdfViewer(File(tab.path), editing)
        Viewers.ODF -> OdfViewer(File(tab.path), editing)
    }
}

/** Text, Markdown and HTML: read the file, show it, and write edits back a moment after the last keystroke (and when the tab is left). */
@Composable
private fun TextBody(path: String, format: TextFormat, editable: Boolean, json: Boolean = false) {
    var text by remember(path) { mutableStateOf<String?>(null) }
    LaunchedEffect(path) { text = withContext(Dispatchers.IO) { runCatching { File(path).takeIf { it.length() <= MAX_TEXT_BYTES }?.readText() }.getOrNull() ?: "" } }
    val pending = remember(path) { AtomicReference<String?>(null) }
    var tick by remember(path) { mutableIntStateOf(0) }
    fun flush() { pending.getAndSet(null)?.let { runCatching { File(path).writeText(it) } } }
    LaunchedEffect(tick) {
        if (tick > 0) { delay(SAVE_DELAY_MS); withContext(Dispatchers.IO) { flush() } }
    }
    DisposableEffect(path) { onDispose { flush() } }
    val loaded = text ?: return
    when {
        json -> JsonViewer(loaded)
        editable -> WysiwygEditor(loaded, format, { pending.set(it); tick++ })
        else -> WysiwygView(loaded, format)
    }
}

private const val MAX_TEXT_BYTES = 5L * 1024 * 1024
private const val SAVE_DELAY_MS = 600L
