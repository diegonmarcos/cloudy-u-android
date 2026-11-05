package com.diegonmarcos.clouddrive.editor

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import com.diegonmarcos.cloudlib.editorcore.EditorHost
import com.diegonmarcos.cloudlib.editorcore.EditorMode
import com.diegonmarcos.cloudlib.editorcore.EditorTab
import com.diegonmarcos.cloudlib.editorcore.EditorTabs
import com.diegonmarcos.cloudlib.editorcore.PrefsTabsStore
import com.diegonmarcos.cloudlib.editorcore.Route
import com.diegonmarcos.cloudlib.editorcore.Viewers
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
import kotlinx.coroutines.withContext

/**
 * #1484 the Editor tab: app-side glue only. The tab strip, the View | Edit toggle and the type routing are libs:editor-core,
 * the viewers and editors are libs:wysiwyg, libs:media-viewer, libs:pdf and libs:odf; this file picks the lib for a route,
 * keeps the tabs across restarts and takes the files Explorer hands over ([EditorHook]).
 */
@Composable
fun EditorScreen(actions: DriveActions, openInExplorer: (String) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val store = remember { PrefsTabsStore(ctx, "cloud-drive-editor") }
    var tabs by remember { mutableStateOf(store.load()) }
    LaunchedEffect(tabs) { store.save(tabs) }
    val pending by EditorHook.pending.collectAsState()
    LaunchedEffect(pending) {
        pending?.let { tabs = tabs.open(it); EditorHook.pending.value = null }
    }
    EditorHost(
        tabs = tabs, onTabs = { tabs = it }, modifier = modifier.fillMaxSize().testTag(DriveTags.EDITOR), router = EditorHook.router,
        empty = { EmptyState(IconCatalog.vectorOrDefault("edit_note"), stringResource(R.string.editor_empty_title), stringResource(R.string.editor_empty_hint)) },
    ) { tab, route -> EditorBody(tab, route) }
}

@Composable
private fun EditorBody(tab: EditorTab, route: Route) {
    val editing = tab.mode == EditorMode.Edit
    when (route.viewer) {
        Viewers.WYSIWYG -> TextBody(tab.path, TextFormat.of(route.format), editing)
        Viewers.MEDIA -> MediaViewer(tab.path, MediaKind.of(route.format) ?: MediaKind.Image)
        Viewers.PDF -> PdfViewer(File(tab.path), editing)
        Viewers.ODF -> OdfViewer(File(tab.path), editing)
    }
}

/** Text, Markdown and HTML: read the file, show it, and write edits back a moment after the last keystroke (and when the tab is left). */
@Composable
private fun TextBody(path: String, format: TextFormat, editing: Boolean) {
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
    if (editing) WysiwygEditor(loaded, format, { pending.set(it); tick++ }) else WysiwygView(loaded, format)
}

private const val MAX_TEXT_BYTES = 5L * 1024 * 1024
private const val SAVE_DELAY_MS = 600L
