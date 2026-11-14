@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.diegonmarcos.clouddrive.files

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountTree
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.ui.CapsuleBadge
import com.diegonmarcos.clouddrive.ui.DriveCard
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.ErrorState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.IslandAction
import com.diegonmarcos.clouddrive.ui.LoadingState
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.files.convert.ConverterRegistry
import com.diegonmarcos.clouddrive.ui.ProgressCard
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.superapp.bottomnav.bottomNavPillShape
import java.io.File
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * #579 FILES — the X-plore replacement (cloud-drive-redesign.md §3). Two real panes,
 * each with its own tabs, crumbs, toolbar, storage bar and list or tree; archives
 * browsed as folders; a selection bar that copies and moves BETWEEN the panes with
 * live progress; the Places sheet with the shared store first; search; and every
 * empty / loading / error state named in the spec. All state lives in
 * [FilesController]; this file only draws it.
 */

private sealed class FilesDialog {
    data class NewFolder(val pane: PaneId) : FilesDialog()
    data class NewFile(val pane: PaneId) : FilesDialog()
    data class Rename(val entry: FileOps.Entry) : FilesDialog()
    data class DeleteConfirm(val pane: PaneId, val count: Int) : FilesDialog()
    data class ZipName(val pane: PaneId, val tarGz: Boolean = false) : FilesDialog()
    /** #1680 selection menu ▸ Meta ▸ Properties: combined count and size of the selection. */
    data class SelectionProps(val entries: List<FileOps.Entry>) : FilesDialog()
    data class BulkRename(val pane: PaneId, val files: List<File>, val location: Location) : FilesDialog()
    object Duplicates : FilesDialog()
    /** #458/#577 PDF → txt/md/html/csv beside the file, through the ONE PdfConversion path. */
    data class ConvertPdf(val entry: FileOps.Entry) : FilesDialog()
    /** Row menu ▸ Unzip… (destination picker) and Convert… (registry converters). */
    data class Unzip(val entry: FileOps.Entry) : FilesDialog()
    data class Convert(val entry: FileOps.Entry) : FilesDialog()
    /** #1607 Row menu ▸ Compress: pick .zip or .tar.gz and whether it lands beside the item or in Downloads. */
    data class Compress(val entry: FileOps.Entry) : FilesDialog()
    /** The Places sheet: open in [pane], or pick a destination for the active selection. */
    data class Places(val pane: PaneId, val pickDestination: Boolean, val move: Boolean, val gitMove: List<FileOps.Entry> = emptyList()) : FilesDialog()
    /** #1644 git mv rename of one entry. */
    data class GitRename(val entry: FileOps.Entry) : FilesDialog()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilesScreen(controller: FilesController, actions: DriveActions, hasAccess: Boolean, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val ui by controller.state.collectAsState()
    val listings by controller.listings.collectAsState()
    val jobs by controller.jobs.collectAsState()
    val prefs by controller.prefs.snapshot.collectAsState()
    val props by controller.properties.collectAsState()
    val dups by controller.duplicates.collectAsState()
    var dialog by remember { mutableStateOf<FilesDialog?>(null) }
    // The inline search: the bar sits over the listing; a non-recursive scope filters it in place, a recursive one streams hits.
    val searchDecl = Declarations.files.search
    var searching by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var scopeId by remember { mutableStateOf(searchDecl.initialScope?.id ?: "") }
    val searchScope = searchDecl.scope(scopeId)
    val scopedHits by controller.scoped.collectAsState()
    // Directory reads never run on the main thread: the roots (a /storage listing) arrive from IO, the search scope waits for them.
    val storageRoots by produceState(emptyList<String>()) { value = withContext(Dispatchers.IO) { ExplorerSearchRoots.list() } }
    fun openSearch() { query = ""; scopeId = searchDecl.initialScope?.id ?: ""; searching = true }
    fun closeSearch() { searching = false; query = ""; controller.liveFilter = null; controller.clearScoped() }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    // #875 Copy path: the clipboard gets PathFormatter's text; the snack is the declared confirmation and never echoes it.
    val copyPath: (List<Location>) -> Unit = { locs ->
        if (locs.isNotEmpty()) {
            actions.copyText(PathFormatter.forLocations(locs))
            Declarations.files.rowAction(COPY_PATH)?.snack?.takeIf { it.isNotBlank() }?.let { msg -> scope.launch { snackbar.showSnackbar(msg) } }
        }
    }

    LaunchedEffect(Unit) { controller.messages.collect { snackbar.showSnackbar(it) } }
    // Convert: a freshly written file is offered to open (Editor tab: image, PDF, text, HTML all open there).
    LaunchedEffect(Unit) { controller.converted.collect { f -> if (snackbar.showSnackbar(f.name, actionLabel = ctx.getString(R.string.files_convert_open), duration = androidx.compose.material3.SnackbarDuration.Short) == androidx.compose.material3.SnackbarResult.ActionPerformed) runCatching { com.diegonmarcos.clouddrive.editor.EditorHook.open(f.path) } } }
    LaunchedEffect(ui.a.location.key, ui.b.location.key) { controller.ensureLoaded(ui.a.location); controller.ensureLoaded(ui.b.location) }

    val canBack = hasAccess && (searching || dialog != null || FilesReducer.back(ui, ui.active) != null)
    BackHandler(enabled = canBack) {
        when {
            dialog != null -> dialog = null
            searching -> closeSearch()
            else -> controller.back(ui.active)
        }
    }

    fun openEntry(pane: PaneId, e: FileOps.Entry, siblings: List<FileOps.Entry>) {
        val local = e.location as? Location.Local
        when {
            e.isDirectory -> controller.open(pane, e.location)
            local != null && ArchiveFs.isArchiveName(e.name, Declarations.files.archiveExtensions) -> controller.open(pane, Location.Archive(local.path))
            local == null -> controller.update { it } // inside an archive: extract first (the selection bar offers it)
            // #1537 EVERY file tap opens an Editor tab (image, video, audio, PDF, text, JSON, hex for binary...); only the refused Office formats go to another app.
            com.diegonmarcos.clouddrive.editor.EditorHook.canOpen(e.name) -> com.diegonmarcos.clouddrive.editor.EditorHook.open(local.path)
            else -> actions.openWith(local.path)
        }
    }

    Column(modifier.fillMaxSize().testTag(DriveTags.FILES_PANE_A + "_root")) {
        val activeLoc = ui.activePane.location
        // #1356 no title row: the Files actions sit in one compact row (the breadcrumbs show the path).
        Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                IslandAction(Icons.Filled.SwapVert, stringResource(if (ui.dualPane) R.string.files_single_pane else R.string.files_dual_pane), enabled = hasAccess) { controller.setDual(!ui.dualPane) }
                IslandAction(Icons.Filled.Search, stringResource(R.string.files_search), enabled = hasAccess) { if (searching) closeSearch() else openSearch() }
                IslandAction(Icons.Filled.FolderOpen, stringResource(R.string.files_places), enabled = hasAccess) { dialog = FilesDialog.Places(ui.active, pickDestination = false, move = false) }
                var more by remember { mutableStateOf(false) }
                IslandAction(Icons.Filled.MoreVert, stringResource(R.string.chrome_more), enabled = hasAccess) { more = true }
                DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                    val canWrite = activeLoc is Location.Local
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_new_folder)) }, enabled = canWrite, onClick = { more = false; dialog = FilesDialog.NewFolder(ui.active) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_new_file)) }, enabled = canWrite, onClick = { more = false; dialog = FilesDialog.NewFile(ui.active) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_duplicates)) }, enabled = canWrite, onClick = { more = false; (activeLoc as? Location.Local)?.let { controller.findDuplicates(it) }; dialog = FilesDialog.Duplicates })
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_select_all)) }, onClick = { more = false; controller.update { s -> FilesReducer.selectAll(s, s.active, listings[activeLoc.key]?.let { l -> controller.visible(s.activePane, l) }?.map { it.key } ?: emptyList()) } })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chrome_retry)) }, onClick = { more = false; controller.refreshActive() })
                }
                    }

        if (!hasAccess) {
            EmptyState(Icons.Filled.Lock, stringResource(R.string.files_no_access_title), stringResource(R.string.files_no_access_hint), Modifier.weight(1f), stringResource(R.string.files_grant_access)) { actions.requestStorageAccess() }
            return@Column
        }

        if (searching) {
            ExplorerSearchBar(query, { query = it }, searchDecl, scopeId, { scopeId = it }, scopedHits, onClose = { closeSearch() })
            LaunchedEffect(query, scopeId, activeLoc.key) {
                val sc = searchScope ?: return@LaunchedEffect
                controller.liveFilter = FilesController.LiveFilter(activeLoc.key, query, sc)
                val here = (activeLoc as? Location.Local)?.path
                if (here != null) controller.runScoped(ExplorerSearch.startOf(sc, here, storageRoots), query, sc, searchDecl) else controller.clearScoped()
            }
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (searching && searchScope != null && ExplorerSearch.needle(query).isNotEmpty()) {
                // #1537 search results are ONE sortable table (Name | Size | Folder | Modified | Created), recursive hits and the in-place filter alike.
                val inPlace = if (searchScope.recursive) emptyList() else listings[activeLoc.key]?.let { controller.visible(ui.activePane, it) }.orEmpty()
                ScopedResults(if (searchScope.recursive) scopedHits else FilesController.ScopedSearch(query, searchScope.id), inPlace, searchScope.recursive, searchDecl, onOpen = { e ->
                    closeSearch()
                    // a hit that is a file opens in the Editor; a folder opens in the pane.
                    if (e.isDirectory) controller.open(ui.active, e.location) else (e.location as? Location.Local)?.let { openEntry(ui.active, e, listOf(e)) }
                })
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val wide = maxWidth >= DriveMetrics.wide
                    val paneContent: @Composable (PaneId, Modifier) -> Unit = { id, m ->
                        Pane(id, ui, controller, listings, prefs, isActive = ui.active == id, modifier = m,
                            onOpenPlaces = { dialog = FilesDialog.Places(id, pickDestination = false, move = false) },
                            onOpenEntry = { e, siblings -> openEntry(id, e, siblings) },
                            onCopyPath = { loc -> copyPath(listOf(loc)) },
                            onMenu = { e, action -> dialog = when (action) {
                                EntryAction.RENAME -> FilesDialog.Rename(e)
                                EntryAction.PROPERTIES -> { controller.openProperties(e); null }
                                EntryAction.DELETE -> { controller.update { s -> FilesReducer.selectAll(s, id, listOf(e.key)) }; FilesDialog.DeleteConfirm(id, 1) }
                                EntryAction.SHARE -> { (e.location as? Location.Local)?.let { actions.share(listOf(it.path)) }; null }
                                EntryAction.OPEN_WITH -> { (e.location as? Location.Local)?.let { actions.openWith(it.path) }; null }
                                EntryAction.EDIT -> { (e.location as? Location.Local)?.let { actions.openEngine("editor", it.path) }; null }
                                EntryAction.EDITOR -> { (e.location as? Location.Local)?.let { com.diegonmarcos.clouddrive.editor.EditorHook.open(it.path) }; null }
                                EntryAction.EXTRACT_HERE -> { controller.extractHere(e); null }
                                EntryAction.UNZIP -> FilesDialog.Unzip(e)
                                EntryAction.CONVERT -> FilesDialog.Convert(e)
                                EntryAction.OPEN_OTHER_PANE -> { controller.open(ui.otherId, e.location); controller.activate(ui.otherId); null }
                                EntryAction.BOOKMARK -> { (e.location as? Location.Local)?.let { controller.prefs.toggleBookmark(it.path) }; null }
                                EntryAction.CONVERT_PDF -> FilesDialog.ConvertPdf(e)
                                EntryAction.TO_PDF -> { controller.toPdf(e); null }
                                EntryAction.COPY_PATH -> { copyPath(listOf(e.location)); null }
                                EntryAction.COPY_TO_DOWNLOADS -> { (e.location as? Location.Local)?.let { actions.copyToDownload(listOf(it.path)) }; null }
                                EntryAction.COPY_TO_CLIPBOARD -> { (e.location as? Location.Local)?.let { loc -> scope.launch { val text = withContext(Dispatchers.IO) { runCatching { File(loc.path).readText() }.getOrNull() }; if (text != null) { actions.copyText(text); snackbar.showSnackbar(ctx.getString(R.string.files_copied_to_clipboard, e.name)) } } }; null }
                                EntryAction.COMPRESS -> FilesDialog.Compress(e)
                                EntryAction.GIT_RENAME -> FilesDialog.GitRename(e)
                                EntryAction.GIT_MOVE -> FilesDialog.Places(id, pickDestination = true, move = true, gitMove = listOf(e))
                                EntryAction.GIT_OPEN -> { (e.location as? Location.Local)?.let { loc -> scope.launch { val root = withContext(Dispatchers.IO) { GitWorkTree.rootOf(loc.path) }; root?.let { actions.openEngine("git", it.absolutePath) } } }; null }
                            } })
                    }
                    when {
                        ui.dualPane && wide -> Row(Modifier.fillMaxSize()) {
                            paneContent(PaneId.A, Modifier.weight(1f).fillMaxHeight())
                            paneContent(PaneId.B, Modifier.weight(1f).fillMaxHeight())
                        }
                        ui.dualPane -> Column(Modifier.fillMaxSize()) {
                            paneContent(ui.active, Modifier.weight(1f).fillMaxWidth())
                            PaneHeader(ui.otherId, ui, listings, controller)
                        }
                        else -> paneContent(ui.active, Modifier.fillMaxSize())
                    }
                }
            }
        }

        val active = ui.activePane
        AnimatedVisibility(visible = active.selecting) {
            SelectionBar(ui, controller, onDelete = { dialog = FilesDialog.DeleteConfirm(ui.active, active.selection.size) },
                onZip = { dialog = FilesDialog.ZipName(ui.active) },
                onRename = { entries -> dialog = if (entries.size == 1) FilesDialog.Rename(entries.first()) else FilesDialog.BulkRename(ui.active, entries.mapNotNull { it.localFile }, active.location) },
                onShare = { paths -> actions.share(paths) },
                onProperties = { e -> controller.openProperties(e) },
                onCopyPath = { entries -> copyPath(entries.map { it.location }) },
                onPickDestination = { move -> dialog = FilesDialog.Places(ui.active, pickDestination = true, move = move) },
                onGitMoveAll = { entries -> dialog = FilesDialog.Places(ui.active, pickDestination = true, move = true, gitMove = entries) },
                // #1680 the grouped selection menu's items that need the screen (dialogs, Downloads, other pane).
                onMenuPick = { id, entries ->
                    val otherLocal = ui.otherPane.location as? Location.Local
                    val paths = entries.mapNotNull { (it.location as? Location.Local)?.path }
                    when (id) {
                        "delete_all" -> dialog = FilesDialog.DeleteConfirm(ui.active, active.selection.size)
                        "move_all" -> dialog = FilesDialog.Places(ui.active, pickDestination = true, move = true)
                        "copy_all" -> { if (ui.dualPane && otherLocal != null) controller.transferSelection(ui.active, otherLocal, move = false) else { dialog = FilesDialog.Places(ui.active, pickDestination = true, move = false) } }
                        "copy_to_other_pane" -> otherLocal?.let { controller.transferSelection(ui.active, it, move = false) }
                        "move_to_other_pane" -> otherLocal?.let { controller.transferSelection(ui.active, it, move = true) }
                        "zip_all" -> dialog = FilesDialog.ZipName(ui.active)
                        "tar_all" -> dialog = FilesDialog.ZipName(ui.active, tarGz = true)
                        "copy_paths" -> copyPath(entries.map { it.location })
                        "copy_to_downloads" -> if (paths.isNotEmpty()) { actions.copyToDownload(paths); scope.launch { snackbar.showSnackbar(ctx.getString(R.string.files_copying_to_downloads, paths.size)) } }
                        "share" -> actions.share(entries.filter { !it.isDirectory }.mapNotNull { (it.location as? Location.Local)?.path })
                        "properties" -> { if (entries.size == 1) controller.openProperties(entries.first()) else dialog = FilesDialog.SelectionProps(entries) }
                        "rename" -> dialog = if (entries.size == 1) FilesDialog.Rename(entries.first()) else FilesDialog.BulkRename(ui.active, entries.mapNotNull { it.localFile }, active.location)
                        "git_move_all" -> dialog = FilesDialog.Places(ui.active, pickDestination = true, move = true, gitMove = entries)
                        "git_rename" -> entries.singleOrNull()?.let { dialog = FilesDialog.GitRename(it) }
                    }
                    Unit
                })
        }
        jobs.forEach { job ->
            ProgressCard(job.title, job.detail, if (job.done) 1f else job.fraction, onCancel = if (job.done) null else ({ controller.cancel(job.id) }))
        }
        SnackbarHost(snackbar)
    }

    // ── dialogs & sheets ────────────────────────────────────────────────────
    when (val d = dialog) {
        null -> Unit
        is FilesDialog.NewFolder -> TextFieldDialog(stringResource(R.string.files_new_folder), stringResource(R.string.files_name), "", stringResource(R.string.files_create), { dialog = null }) { controller.createFolder(d.pane, it); dialog = null }
        is FilesDialog.NewFile -> TextFieldDialog(stringResource(R.string.files_new_file), stringResource(R.string.files_name), "", stringResource(R.string.files_create), { dialog = null }) { controller.createFile(d.pane, it); dialog = null }
        is FilesDialog.Rename -> TextFieldDialog(stringResource(R.string.files_rename), stringResource(R.string.files_name), d.entry.name, stringResource(R.string.files_rename), { dialog = null }) { controller.rename(d.entry, it); dialog = null }
        is FilesDialog.GitRename -> TextFieldDialog(stringResource(R.string.files_git_rename), stringResource(R.string.files_name), d.entry.name, stringResource(R.string.files_rename), { dialog = null }) { controller.gitRename(d.entry, it); dialog = null }
        is FilesDialog.DeleteConfirm -> ConfirmDialog(stringResource(R.string.files_delete_title, d.count), stringResource(R.string.files_delete_body), stringResource(R.string.chrome_delete), { dialog = null }) { controller.deleteSelection(d.pane); dialog = null }
        is FilesDialog.ZipName -> TextFieldDialog(stringResource(R.string.files_zip), stringResource(R.string.files_zip_name), if (d.tarGz) "archive.tar.gz" else "archive.zip", stringResource(R.string.files_zip), { dialog = null }) { controller.zipSelection(d.pane, it, d.tarGz); dialog = null }
        is FilesDialog.SelectionProps -> SelectionPropertiesDialog(d.entries) { dialog = null }
        is FilesDialog.BulkRename -> BulkRenameDialog(d.files, { dialog = null }) { plan -> controller.applyRenamePlan(plan, d.location); dialog = null }
        is FilesDialog.Unzip -> UnzipDialog(d.entry, remember(d.entry.key) { d.entry.localFile?.let { f -> File(f.parentFile, ArchiveExtractor.defaultFolderName(f.name)).path } ?: "" }, { dialog = null }) { dest -> controller.unzip(d.entry, dest); dialog = null }
        is FilesDialog.Convert -> ConvertDialog(d.entry, ConverterRegistry.default.forName(d.entry.name), { dialog = null }) { c -> controller.convert(d.entry, c); dialog = null }
        is FilesDialog.Compress -> CompressDialog(d.entry, { dialog = null }) { tarGz, toDownloads ->
            val downloads = if (toDownloads) @Suppress("DEPRECATION") android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS) else null
            controller.compress(d.entry, tarGz, downloads); dialog = null
        }
        is FilesDialog.ConvertPdf -> ConvertPdfDialog(d.entry, { dialog = null }) { target -> controller.convertPdf(d.entry, target); dialog = null }
        is FilesDialog.Duplicates -> DuplicatesDialog(dups, onReveal = { p -> controller.closeDuplicates(); dialog = null; controller.reveal(p) }) { controller.closeDuplicates(); dialog = null }
        is FilesDialog.Places -> PlacesSheet(d, ui, controller, prefs, actions, onDismiss = { dialog = null })
    }
    props?.let { PropertiesDialog(it) { controller.closeProperties() } }
}

// ── one pane ─────────────────────────────────────────────────────────────

private enum class EntryAction { RENAME, PROPERTIES, DELETE, SHARE, OPEN_WITH, EDIT, EDITOR, EXTRACT_HERE, UNZIP, CONVERT, OPEN_OTHER_PANE, BOOKMARK, CONVERT_PDF, COPY_PATH, COPY_TO_DOWNLOADS, COPY_TO_CLIPBOARD, COMPRESS, TO_PDF, GIT_OPEN, GIT_RENAME, GIT_MOVE }

/** #1607 map the grouped data model's action onto the screen's dispatch enum (1:1 by name for the shared ones). */
private fun EntryMenu.Action.toEntryAction(): EntryAction = when (this) {
    EntryMenu.Action.OPEN_WITH -> EntryAction.OPEN_WITH
    EntryMenu.Action.EDIT -> EntryAction.EDIT
    EntryMenu.Action.EDITOR -> EntryAction.EDITOR
    EntryMenu.Action.OPEN_OTHER_PANE -> EntryAction.OPEN_OTHER_PANE
    EntryMenu.Action.UNZIP -> EntryAction.UNZIP
    EntryMenu.Action.CONVERT -> EntryAction.CONVERT
    EntryMenu.Action.CONVERT_PDF -> EntryAction.CONVERT_PDF
    EntryMenu.Action.TO_PDF -> EntryAction.TO_PDF
    EntryMenu.Action.COPY_PATH -> EntryAction.COPY_PATH
    EntryMenu.Action.COPY_TO_DOWNLOADS -> EntryAction.COPY_TO_DOWNLOADS
    EntryMenu.Action.COPY_TO_CLIPBOARD -> EntryAction.COPY_TO_CLIPBOARD
    EntryMenu.Action.SHARE -> EntryAction.SHARE
    EntryMenu.Action.COMPRESS -> EntryAction.COMPRESS
    EntryMenu.Action.RENAME -> EntryAction.RENAME
    EntryMenu.Action.DELETE -> EntryAction.DELETE
    EntryMenu.Action.PROPERTIES -> EntryAction.PROPERTIES
    EntryMenu.Action.BOOKMARK -> EntryAction.BOOKMARK
    EntryMenu.Action.GIT_OPEN -> EntryAction.GIT_OPEN
    EntryMenu.Action.GIT_RENAME -> EntryAction.GIT_RENAME
    EntryMenu.Action.GIT_MOVE -> EntryAction.GIT_MOVE
}

/** The dropdown's group-header label; COPY_PATH keeps its declared label, the rest are string resources. */
private fun EntryMenu.Group.labelRes(): Int = when (this) {
    EntryMenu.Group.OPEN -> R.string.files_menu_group_open
    EntryMenu.Group.CONVERT -> R.string.files_menu_group_convert
    EntryMenu.Group.SHARE -> R.string.files_menu_group_share
    EntryMenu.Group.META -> R.string.files_menu_group_meta
    EntryMenu.Group.GIT -> R.string.files_menu_group_git
}

private fun EntryMenu.Action.labelRes(): Int = when (this) {
    EntryMenu.Action.OPEN_WITH -> R.string.files_open_with
    EntryMenu.Action.EDIT -> R.string.files_edit
    EntryMenu.Action.EDITOR -> R.string.files_open_in_editor
    EntryMenu.Action.OPEN_OTHER_PANE -> R.string.files_copy_to_other
    EntryMenu.Action.UNZIP -> R.string.files_unzip
    EntryMenu.Action.CONVERT -> R.string.files_convert
    EntryMenu.Action.CONVERT_PDF -> R.string.files_convert_pdf
    EntryMenu.Action.TO_PDF -> R.string.files_to_pdf
    EntryMenu.Action.COPY_PATH -> R.string.files_copy_to // unused: COPY_PATH renders via its declared action
    EntryMenu.Action.COPY_TO_DOWNLOADS -> R.string.files_copy_to_downloads
    EntryMenu.Action.COPY_TO_CLIPBOARD -> R.string.files_copy_to_clipboard
    EntryMenu.Action.SHARE -> R.string.files_share
    EntryMenu.Action.COMPRESS -> R.string.files_compress
    EntryMenu.Action.RENAME -> R.string.files_rename
    EntryMenu.Action.DELETE -> R.string.chrome_delete
    EntryMenu.Action.PROPERTIES -> R.string.files_properties
    EntryMenu.Action.BOOKMARK -> R.string.files_bookmark_add
    EntryMenu.Action.GIT_OPEN -> R.string.files_git_open
    EntryMenu.Action.GIT_RENAME -> R.string.files_git_rename
    EntryMenu.Action.GIT_MOVE -> R.string.files_git_move
}

private const val COPY_PATH = "copy_path"

private data class TreeRow(val entry: FileOps.Entry, val depth: Int)

@Composable
private fun Pane(
    id: PaneId,
    ui: FilesUiState,
    controller: FilesController,
    listings: Map<String, FilesController.Listing>,
    prefs: com.diegonmarcos.clouddrive.DrivePrefs.Snapshot,
    isActive: Boolean,
    modifier: Modifier,
    onOpenPlaces: () -> Unit,
    onOpenEntry: (FileOps.Entry, List<FileOps.Entry>) -> Unit,
    onCopyPath: (Location) -> Unit,
    onMenu: (FileOps.Entry, EntryAction) -> Unit,
) {
    val ctx = LocalContext.current
    val pane = ui.pane(id)
    val loc = pane.location
    val listing = listings[loc.key]
    // The live filter is a key: rows are built through controller.visible(), which reads it, and without the key the inline
    // search recomposed onto the cached, unfiltered rows (the emulator walk typed a query and the listing did not change).
    val rows: List<TreeRow> = remember(pane, listings, controller.liveFilter) {
        if (pane.viewMode == ViewMode.LIST) (listing?.let { controller.visible(pane, it) } ?: emptyList()).map { TreeRow(it, 0) }
        else treeRows(loc, pane, listings, controller)
    }
    val borderColour = if (isActive && ui.dualPane) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    Column(
        modifier
            .padding(horizontal = DriveMetrics.gapWide, vertical = DriveMetrics.gap)
            .testTag(if (id == PaneId.A) DriveTags.FILES_PANE_A else DriveTags.FILES_PANE_B)
            .clip(RoundedCornerShape(DriveMetrics.cardRadius))
            .border(DriveMetrics.hairline, borderColour, RoundedCornerShape(DriveMetrics.cardRadius))
            .background(MaterialTheme.colorScheme.surface)
            // Any touch on the inactive pane activates it. Not clickable{}: that merges the whole pane
            // into one semantics node, hiding the strip, crumbs, toolbar and rows from TalkBack.
            .pointerInput(isActive, id) {
                if (!isActive) awaitPointerEventScope { while (true) { awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial); controller.activate(id) } }
            },
    ) {
        TabStrip(id, pane, controller, onOpenPlaces)
        Breadcrumbs(id, loc, controller, onCopyPath)
        PaneToolbar(id, pane, controller, prefs)
        val rootLabel by produceState<String?>(null, loc) { value = withContext(Dispatchers.IO) { (loc as? Location.Local)?.let { Places.rootLabel(ctx, it.path) } } }
        if (listing?.usage != null && rootLabel != null) StorageBar(listing.usage)
        Hairline()
        // Pull down to re-scan the folder: the same reload as Retry, the thin bar along the top edge is its indicator (as on the Git page).
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = listing?.loading == true, onRefresh = { controller.refreshPane(id) }, modifier = Modifier.weight(1f).fillMaxWidth().testTag(DriveTags.FILES_REFRESH),
            indicator = {},
        ) {
            when {
                listing == null || (listing.loading && listing.entries.isEmpty() && listing.error == null) -> LoadingState()
                listing.error != null -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    ErrorState(listing.error, onRetry = { controller.reload(loc) })
                    // #1696 an error view always offers Home and Back.
                    Row(Modifier.fillMaxWidth().padding(DriveMetrics.pad), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide, Alignment.CenterHorizontally)) {
                        Pill(stringResource(R.string.files_home), { controller.home(id) }, icon = Icons.Filled.Home)
                        Pill(stringResource(R.string.files_back), { if (!controller.back(id)) controller.home(id) }, icon = Icons.AutoMirrored.Filled.ArrowBack)
                    }
                }
                rows.isEmpty() -> EmptyState(
                    if (loc.isArchive) Icons.Filled.Archive else Icons.Filled.FolderOpen,
                    stringResource(if (loc.isArchive) R.string.files_empty_archive_title else R.string.files_empty_title),
                    if (loc.isArchive) "" else stringResource(R.string.files_empty_hint),
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()), // scrollable, so a pull on an EMPTY folder still reaches PullToRefreshBox
                )
                else -> EntryList(id, pane, rows, controller, prefs.thumbnails, onOpenEntry = { e -> onOpenEntry(e, rows.map { it.entry }) }, onMenu = onMenu, onActivate = { if (!isActive) controller.activate(id) })
            }
            if (listing?.loading == true && listing.entries.isNotEmpty()) LinearProgressIndicator(Modifier.fillMaxWidth().align(Alignment.TopCenter))
        }
    }
}

private fun treeRows(root: Location, pane: PaneState, listings: Map<String, FilesController.Listing>, controller: FilesController): List<TreeRow> {
    val out = mutableListOf<TreeRow>()
    fun walk(loc: Location, depth: Int) {
        val listing = listings[loc.key] ?: run { controller.ensureLoaded(loc); return }
        controller.visible(pane, listing).forEach { e ->
            out += TreeRow(e, depth)
            if (e.isDirectory && e.key in pane.expanded && depth < FileOps.DEPTH_CEILING) walk(e.location, depth + 1)
        }
    }
    walk(root, 0)
    return out
}

/** The inactive pane collapsed to one row: its folder, its count, a chevron; tap to make it active. */
@Composable
private fun PaneHeader(id: PaneId, ui: FilesUiState, listings: Map<String, FilesController.Listing>, controller: FilesController) {
    val pane = ui.pane(id)
    val listing = listings[pane.location.key]
    val count = listing?.let { controller.visible(pane, it).size }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gapWide, vertical = DriveMetrics.gap).testTag(DriveTags.FILES_PANE_HEADER)
            .clip(RoundedCornerShape(DriveMetrics.cardRadius)).border(DriveMetrics.hairline, MaterialTheme.colorScheme.outline, RoundedCornerShape(DriveMetrics.cardRadius))
            .background(MaterialTheme.colorScheme.surface).clickable { controller.activate(id) }.padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.pad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(if (pane.location.isArchive) Icons.Filled.Archive else Icons.Filled.Folder, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(DriveMetrics.pad))
        Text(pane.location.crumbs().takeLast(2).joinToString(" › ") { it.name }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (count != null) Text(if (count == 1) stringResource(R.string.files_item_one) else stringResource(R.string.files_items, count), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Icon(Icons.Filled.ExpandMore, contentDescription = stringResource(R.string.files_pane_activate), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun TabStrip(id: PaneId, pane: PaneState, controller: FilesController, onOpenPlaces: () -> Unit) {
    Row(Modifier.fillMaxWidth().testTag(DriveTags.FILES_TAB_STRIP).padding(start = DriveMetrics.gapWide, end = DriveMetrics.tight, top = DriveMetrics.gapWide), verticalAlignment = Alignment.CenterVertically) {
        // One tab = the breadcrumb already names the folder: no duplicate title chip (tabs show from the second one on).
        LazyRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
            itemsIndexed(if (pane.tabs.size > 1) pane.tabs else emptyList()) { i, tab ->
                val selected = i == pane.activeTab
                Row(
                    Modifier.clip(bottomNavPillShape)
                        .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
                        .combinedClickable(onClick = { controller.update { FilesReducer.selectTab(it, id, i) } },
                            onLongClick = { (tab.location as? Location.Local)?.let { controller.prefs.toggleBookmark(it.path) } })
                        .padding(start = DriveMetrics.pad, end = DriveMetrics.gap, top = DriveMetrics.tight, bottom = DriveMetrics.tight),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(if (tab.location.isArchive) Icons.Filled.Archive else Icons.Filled.Folder, contentDescription = null, Modifier.size(DriveMetrics.iconSmall), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(DriveMetrics.gapWide))
                    Text(tab.location.name, style = MaterialTheme.typography.labelMedium, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(DriveMetrics.tabLabel))
                    if (pane.tabs.size > 1) IconButton(onClick = { controller.update { FilesReducer.closeTab(it, id, i) } }, Modifier.size(DriveMetrics.tapSmall)) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_close_tab), Modifier.size(DriveMetrics.iconSmall)) }
                    else Spacer(Modifier.width(DriveMetrics.gapWide))
                }
            }
        }
        IconButton(onClick = onOpenPlaces, Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.files_new_tab)) }
    }
}

@Composable
private fun Breadcrumbs(id: PaneId, loc: Location, controller: FilesController, onCopyPath: (Location) -> Unit) {
    val ctx = LocalContext.current
    // #1696 the breadcrumb starts at the root the location sits in (its label), not at '/'.
    val roots by controller.roots.collectAsState()
    val crumbs = remember(loc, roots) { FilesNav.crumbs(loc, roots) }
    val copyDeclared = remember { Declarations.files.rowAction(COPY_PATH) != null }
    // Places.rootLabel stats declared folders: read once per path, off the main thread, never per crumb per recomposition.
    val rootLabels by produceState(emptyMap<String, String?>(), crumbs) { value = withContext(Dispatchers.IO) { crumbs.filterIsInstance<Location.Local>().associate { it.path to Places.rootLabel(ctx, it.path) } } }
    val listState = rememberLazyListState()
    LaunchedEffect(crumbs.size) { if (crumbs.isNotEmpty()) listState.animateScrollToItem(crumbs.lastIndex) }
    LazyRow(Modifier.fillMaxWidth().testTag(DriveTags.FILES_BREADCRUMBS).padding(horizontal = DriveMetrics.pad, vertical = DriveMetrics.gap), state = listState, verticalAlignment = Alignment.CenterVertically) {
        itemsIndexed(crumbs) { i, crumb ->
            val last = i == crumbs.lastIndex
            // A declared place label only names an ANCESTOR; the folder you are IN always shows its real name ("git", not the place alias "Terminal clones").
            val label = (crumb as? Location.Local)?.takeIf { !last }?.let { rootLabels[it.path] } ?: crumb.name
            Text(
                label,
                Modifier.clip(bottomNavPillShape).combinedClickable(onClick = { if (!last) controller.open(id, crumb) }, onLongClick = if (copyDeclared) ({ onCopyPath(crumb) }) else null).padding(horizontal = DriveMetrics.gapWide, vertical = DriveMetrics.tight),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = if (last) FontWeight.Bold else FontWeight.Normal,
                color = if (last) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
            )
            if (!last) Icon(Icons.Filled.ChevronRight, contentDescription = null, Modifier.size(DriveMetrics.iconSmall), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PaneToolbar(id: PaneId, pane: PaneState, controller: FilesController, prefs: com.diegonmarcos.clouddrive.DrivePrefs.Snapshot) {
    var sortMenu by remember { mutableStateOf(false) }
    var filterMenu by remember { mutableStateOf(false) }
    val filter = Declarations.files.filters.firstOrNull { it.id == pane.filterId }
    val localPath = (pane.location as? Location.Local)?.path
    val bookmarked = localPath != null && localPath in prefs.bookmarks
    Row(Modifier.fillMaxWidth().testTag(DriveTags.FILES_TOOLBAR).padding(horizontal = DriveMetrics.pad, vertical = DriveMetrics.tight), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gapWide)) {
        Box {
            Pill(sortLabel(pane.sort) + if (pane.descending) " ↓" else " ↑", { sortMenu = true }, icon = Icons.Filled.Sort)
            DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                Declarations.files.sortKeys.forEach { key ->
                    DropdownMenuItem(text = { Text(sortLabel(key)) }, leadingIcon = { if (key == pane.sort) Icon(Icons.Filled.Check, null) }, onClick = { sortMenu = false; controller.update { FilesReducer.setSort(it, id, key, pane.descending) } })
                }
                DropdownMenuItem(text = { Text(stringResource(if (pane.descending) R.string.files_sort_ascending else R.string.files_sort_descending)) }, leadingIcon = { Icon(Icons.Filled.SwapVert, null) }, onClick = { sortMenu = false; controller.update { FilesReducer.setSort(it, id, pane.sort, !pane.descending) } })
            }
        }
        Box {
            Pill(filter?.label ?: stringResource(R.string.files_filter), { filterMenu = true }, icon = Icons.Filled.FilterList, filled = filter != null && filter.id != "all")
            DropdownMenu(expanded = filterMenu, onDismissRequest = { filterMenu = false }) {
                Declarations.files.filters.forEach { f ->
                    DropdownMenuItem(text = { Text(f.label) }, leadingIcon = { Icon(IconCatalog.vectorOrDefault(f.icon), null) }, trailingIcon = { if (f.id == pane.filterId) Icon(Icons.Filled.Check, null) }, onClick = { filterMenu = false; controller.update { FilesReducer.setFilter(it, id, f.id) } })
                }
            }
        }
        Spacer(Modifier.weight(1f))
        IconButton(onClick = { controller.update { FilesReducer.toggleHidden(it, id) } }, Modifier.size(DriveMetrics.tap)) { Icon(if (pane.showHidden) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, contentDescription = stringResource(if (pane.showHidden) R.string.files_hidden_hide else R.string.files_hidden_show), Modifier.size(DriveMetrics.icon)) }
        IconButton(enabled = localPath != null, onClick = { localPath?.let { controller.prefs.toggleBookmark(it) } }, modifier = Modifier.size(DriveMetrics.tap)) { Icon(if (bookmarked) Icons.Filled.Bookmark else Icons.Filled.BookmarkBorder, contentDescription = stringResource(if (bookmarked) R.string.files_bookmark_remove else R.string.files_bookmark_add), Modifier.size(DriveMetrics.icon)) }
        IconButton(onClick = { controller.update { FilesReducer.setViewMode(it, id, if (pane.viewMode == ViewMode.LIST) ViewMode.TREE else ViewMode.LIST) } }, Modifier.size(DriveMetrics.tap)) { Icon(if (pane.viewMode == ViewMode.LIST) Icons.Filled.AccountTree else Icons.Filled.ViewList, contentDescription = stringResource(if (pane.viewMode == ViewMode.LIST) R.string.files_view_tree else R.string.files_view_list), Modifier.size(DriveMetrics.icon)) }
        // #1696 Home: always there, back to the storage home from any state (an unreadable folder included).
        IconButton(onClick = { controller.home(id) }, modifier = Modifier.size(DriveMetrics.tap).testTag(DriveTags.FILES_HOME)) { Icon(Icons.Filled.Home, contentDescription = stringResource(R.string.files_home), Modifier.size(DriveMetrics.icon)) }
        IconButton(enabled = pane.location.parentOrNull() != null, onClick = { controller.up(id) }, modifier = Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.ArrowUpward, contentDescription = stringResource(R.string.files_up), Modifier.size(DriveMetrics.icon)) }
    }
}

@Composable
private fun sortLabel(key: String): String = when (key) {
    "size" -> stringResource(R.string.files_sort_size)
    "modified" -> stringResource(R.string.files_sort_modified)
    "type" -> stringResource(R.string.files_sort_type)
    else -> stringResource(R.string.files_sort_name)
}

/** #603 the pane's bar is the chrome's ONE StorageBar (ui/Chrome.kt) with this pane's caption. */
@Composable
private fun StorageBar(usage: Pair<Long, Long>) {
    com.diegonmarcos.clouddrive.ui.StorageBar(
        usage,
        stringResource(R.string.files_storage_bar, FileOps.humanBytes(usage.first), FileOps.humanBytes(usage.second)),
    )
}

@Composable
private fun EntryList(
    id: PaneId, pane: PaneState, rows: List<TreeRow>, controller: FilesController, thumbnails: Boolean,
    onOpenEntry: (FileOps.Entry) -> Unit, onMenu: (FileOps.Entry, EntryAction) -> Unit, onActivate: () -> Unit,
) {
    // #1356 the list position per pane and folder outlives a bottom-nav switch (and process death): the activity-scoped page store.
    val memoryStore = com.diegonmarcos.clouddrive.sync.LocalGitPageMemory.current
    val scrollKey = "explorer/$id/${pane.tabs.getOrNull(pane.activeTab)?.location}"
    val listState = remember(scrollKey) { memoryStore.get(scrollKey).let { androidx.compose.foundation.lazy.LazyListState(it.scrollIndex, it.scrollOffset) } }
    LaunchedEffect(scrollKey) {
        androidx.compose.runtime.snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .collect { (i, o) -> memoryStore.put(scrollKey, com.diegonmarcos.clouddrive.sync.GitPageMemory(scrollIndex = i, scrollOffset = o)) }
    }
    val selecting = pane.selecting
    val keyAt: (Offset) -> String? = { pos ->
        listState.layoutInfo.visibleItemsInfo.firstOrNull { pos.y.toInt() in it.offset until (it.offset + it.size) }?.key as? String
    }
    LazyColumn(
        Modifier.fillMaxSize().testTag(DriveTags.FILES_LIST).pointerInput(selecting) {
            // Range selection: while selecting, long-press a row and drag over the others.
            if (selecting) detectDragGesturesAfterLongPress(
                onDragStart = { pos -> keyAt(pos)?.let { k -> controller.update { FilesReducer.select(it, id, listOf(k)) } } },
                onDrag = { change, _ -> keyAt(change.position)?.let { k -> controller.update { FilesReducer.select(it, id, listOf(k)) } } },
            )
        },
        state = listState,
    ) {
        items(rows, key = { it.entry.key }) { row ->
            val e = row.entry
            EntryRow(
                entry = e, depth = row.depth, tree = pane.viewMode == ViewMode.TREE, expanded = e.key in pane.expanded,
                selected = e.key in pane.selection, selecting = selecting, thumbnails = thumbnails, controller = controller,
                onTap = { onActivate(); if (selecting) controller.update { FilesReducer.toggleSelect(it, id, e.key) } else onOpenEntry(e) },
                onLongPress = { onActivate(); controller.update { FilesReducer.toggleSelect(it, id, e.key) } },
                onToggleExpand = { controller.update { FilesReducer.toggleExpanded(it, id, e.key) }; controller.ensureLoaded(e.location) },
                onMenu = { a -> onMenu(e, a) },
            )
            Hairline(Modifier.padding(start = DriveMetrics.glyph + DriveMetrics.padWide))
        }
    }
}

@Composable
private fun EntryRow(
    entry: FileOps.Entry, depth: Int, tree: Boolean, expanded: Boolean, selected: Boolean, selecting: Boolean, thumbnails: Boolean,
    controller: FilesController, onTap: () -> Unit, onLongPress: () -> Unit, onToggleExpand: () -> Unit, onMenu: (EntryAction) -> Unit,
) {
    val fmt = remember { DateFormat.getDateInstance(DateFormat.MEDIUM) }
    val isArchive = !entry.isDirectory && ArchiveFs.isArchiveName(entry.name, Declarations.files.archiveExtensions)
    val local = entry.location as? Location.Local
    val isImage = entry.mime.startsWith("image/") && local != null
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().height(DriveMetrics.rowHeight).testTag(DriveTags.FILES_ROW)
            .background(if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface)
            .then(if (selecting) Modifier.clickable(onClick = onTap) else Modifier.combinedClickable(onClick = onTap, onLongClick = onLongPress))
            .padding(start = DriveMetrics.pad + DriveMetrics.treeIndent * depth, end = DriveMetrics.gap),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (tree) {
            if (entry.isDirectory) IconButton(onClick = onToggleExpand, Modifier.size(DriveMetrics.tapSmall)) { Icon(if (expanded) Icons.Filled.ExpandMore else Icons.Filled.ChevronRight, contentDescription = null, Modifier.size(DriveMetrics.icon)) }
            else Spacer(Modifier.width(DriveMetrics.tapSmall))
        }
        Box(Modifier.size(DriveMetrics.glyph), contentAlignment = Alignment.Center) {
            val bitmap: ImageBitmap? = if (isImage && thumbnails) produceState<ImageBitmap?>(null, entry.key) {
                value = withContext(Dispatchers.IO) { runCatching { controller.thumbnail(local!!.path)?.asImageBitmap() }.getOrNull() }
            }.value else null
            if (bitmap != null) androidx.compose.foundation.Image(bitmap, contentDescription = null, Modifier.size(DriveMetrics.glyph).clip(RoundedCornerShape(DriveMetrics.pad)), contentScale = ContentScale.Crop)
            else Icon(IconCatalog.forEntry(entry.isDirectory, entry.mime, entry.extension, isArchive), contentDescription = null, tint = if (entry.isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(DriveMetrics.glyphIcon))
            if (selected) Box(Modifier.size(DriveMetrics.icon).align(Alignment.BottomEnd).clip(bottomNavPillShape).background(MaterialTheme.colorScheme.primary), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Check, contentDescription = null, Modifier.size(DriveMetrics.iconSmall), tint = MaterialTheme.colorScheme.onPrimary)
            }
        }
        Spacer(Modifier.width(DriveMetrics.padWide))
        Column(Modifier.weight(1f)) {
            Text(entry.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val meta = when {
                entry.isDirectory -> stringResource(R.string.files_folder) + (entry.childCount?.let { " · " + if (it == 1) stringResource(R.string.files_item_one) else stringResource(R.string.files_items, it) } ?: "")
                entry.compressedSize != null -> stringResource(R.string.files_compressed, FileOps.humanBytes(entry.size), FileOps.humanBytes(entry.compressedSize))
                else -> FileOps.humanBytes(entry.size) + " · " + fmt.format(Date(entry.modified))
            }
            Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Box {
            IconButton(onClick = { menu = true }, Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chrome_more), Modifier.size(DriveMetrics.icon)) }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                // #1607 the dropdown is DATA: EntryMenu groups the actions into Open / Share / Meta / Git and
                // gates each; this block only renders the sections with their headers. Open with… / Unzip… /
                // Convert… still come from RowMenu (the archive types and the converter registry decide those).
                val rowItems = RowMenu.itemsFor(entry.name, entry.isDirectory, local != null, ConverterRegistry.default)
                val canEdit = local != null && !entry.isDirectory && (entry.mime.startsWith("text/") || entry.extension in Declarations.files.textExtensions)
                val canEditor = local != null && !entry.isDirectory && com.diegonmarcos.clouddrive.editor.EditorHook.canOpen(entry.name)
                val isPdf = local != null && entry.mime == com.diegonmarcos.clouddrive.PdfConversion.PDF_MIME
                // The .git walk-up is a disk read, so it runs off the main thread once the menu opens (default: no Git group).
                val underGit by produceState(false, menu, entry.key) {
                    value = if (menu && local != null) withContext(Dispatchers.IO) { GitWorkTree.isUnderGit(local.path) } else false
                }
                // #1680 ONE grouped component (also used by the selection bar), declared in build.json ui.files.menus.item.
                val groups = FileMenu.resolve(FileMenu.ITEM, Declarations.files.menu(FileMenu.ITEM), FileMenu.Context(
                    count = 1, isDirectory = entry.isDirectory, isLocal = local != null, inArchive = false, anyFile = !entry.isDirectory, underGit = underGit,
                    canEdit = canEdit, canEditor = canEditor, isPdf = isPdf,
                    canUnzip = RowMenu.Item.UNZIP in rowItems, canConvert = RowMenu.Item.CONVERT in rowItems,
                ))
                if (groups.isNotEmpty()) GroupedFileMenu(groups) { id -> menu = false; itemMenuAction(id)?.let(onMenu) }
                val sections = if (groups.isNotEmpty()) emptyList() else EntryMenu.sectionsFor(entry.isDirectory, local != null, rowItems, canEdit, canEditor, isPdf, underGit)
                sections.forEachIndexed { i, section ->
                    if (i > 0) HorizontalDivider()
                    DropdownMenuItem(enabled = false, text = { Text(stringResource(section.group.labelRes()), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }, onClick = {})
                    for (action in section.actions) {
                        if (action == EntryMenu.Action.COPY_PATH) {
                            Declarations.files.rowAction(COPY_PATH)?.let { a -> DropdownMenuItem(text = { Text(a.label) }, leadingIcon = { Icon(IconCatalog.vectorOrDefault(a.icon), null, Modifier.size(DriveMetrics.icon)) }, onClick = { menu = false; onMenu(EntryAction.COPY_PATH) }) }
                        } else {
                            DropdownMenuItem(text = { Text(stringResource(action.labelRes())) }, onClick = { menu = false; onMenu(action.toEntryAction()) })
                        }
                    }
                }
            }
        }
    }
}

// ── #1680 the ONE grouped menu component ─────────────────────────────────

/** Per-item menu id → the screen's action. Unknown ids do nothing (FileMenu lists them disabled). */
private fun itemMenuAction(id: String): EntryAction? = when (id) {
    "git_versions" -> EntryAction.GIT_OPEN
    else -> EntryAction.values().firstOrNull { it.name.equals(id, ignoreCase = true) }
}

/**
 * Draws [FileMenu.resolve]'s groups inside a DropdownMenu: a primary-coloured header per group, a divider
 * between groups, every item listed (disabled when the context does not allow it). Used by BOTH the
 * per-item dropdown and the selection dropdown, so the two always look the same.
 */
@Composable
private fun GroupedFileMenu(groups: List<FileMenu.Group>, onPick: (String) -> Unit) {
    val copyIcon = remember { Declarations.files.rowAction(COPY_PATH)?.icon }
    groups.forEachIndexed { i, g ->
        if (i > 0) HorizontalDivider()
        DropdownMenuItem(enabled = false, modifier = Modifier.testTag("files_menu_group_" + g.id), text = { Text(g.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }, onClick = {})
        for (item in g.items) {
            val icon = if ((item.id == "copy_path" || item.id == "copy_paths") && copyIcon != null) copyIcon else null
            DropdownMenuItem(
                text = { Text(item.label) }, enabled = item.enabled, modifier = Modifier.testTag("files_menu_item_" + item.id),
                leadingIcon = if (icon != null) ({ Icon(IconCatalog.vectorOrDefault(icon), null, Modifier.size(DriveMetrics.icon)) }) else null,
                onClick = { runCatching { onPick(item.id) } },
            )
        }
    }
}

/** #1680 combined Properties of a multi-selection: entries, files, folders, total size (walked off the main thread). */
@Composable
private fun SelectionPropertiesDialog(entries: List<FileOps.Entry>, onDismiss: () -> Unit) {
    val stats by produceState<FileMenu.Stats?>(null, entries) { value = withContext(Dispatchers.IO) { runCatching { FileMenu.stats(entries.mapNotNull { it.localFile }) }.getOrNull() } }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.files_selected, entries.size)) },
        text = {
            val s = stats
            Text(if (s == null) "…" else stringResource(R.string.files_selection_props, s.entries, s.files, s.folders, FileOps.humanBytes(s.bytes)))
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.ok)) } },
    )
}

// ── selection bar ────────────────────────────────────────────────────────

@Composable
private fun SelectionBar(
    ui: FilesUiState, controller: FilesController,
    onDelete: () -> Unit, onZip: () -> Unit, onRename: (List<FileOps.Entry>) -> Unit, onShare: (List<String>) -> Unit,
    onProperties: (FileOps.Entry) -> Unit, onCopyPath: (List<FileOps.Entry>) -> Unit, onPickDestination: (Boolean) -> Unit, onGitMoveAll: (List<FileOps.Entry>) -> Unit,
    onMenuPick: (String, List<FileOps.Entry>) -> Unit = { _, _ -> },
) {
    val pane = ui.activePane
    val inArchive = pane.location.isArchive
    val otherLocal = ui.otherPane.location as? Location.Local
    var more by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().testTag(DriveTags.FILES_SELECTION_BAR).background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = DriveMetrics.pad, vertical = DriveMetrics.pad),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(DriveMetrics.pad),
    ) {
        IconButton(onClick = { controller.update { FilesReducer.clearSelection(it, it.active) } }, Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_clear_selection)) }
        Text(stringResource(if (inArchive) R.string.files_selected_readonly else R.string.files_selected, pane.selection.size), Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, maxLines = 2)
        when {
            inArchive && ui.dualPane && otherLocal != null -> Pill(stringResource(R.string.files_extract_to_other), { controller.transferSelection(ui.active, otherLocal, move = false) }, filled = true)
            inArchive -> Pill(stringResource(R.string.files_copy_to), { onPickDestination(false) }, filled = true)
            ui.dualPane && otherLocal != null -> {
                Pill(stringResource(R.string.files_copy_to_other), { controller.transferSelection(ui.active, otherLocal, move = false) }, filled = true)
                Pill(stringResource(R.string.files_move_to_other), { controller.transferSelection(ui.active, otherLocal, move = true) })
            }
            else -> {
                Pill(stringResource(R.string.files_copy_to), { onPickDestination(false) }, filled = true)
                Pill(stringResource(R.string.files_move_to), { onPickDestination(true) })
            }
        }
        Box {
            IconButton(onClick = { more = true }, Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.chrome_more)) }
            DropdownMenu(expanded = more, onDismissRequest = { more = false }) {
                val selected = controller.selectedEntries(ui.active)
                val allUnderGit by produceState(false, selected) { value = withContext(Dispatchers.IO) { selected.isNotEmpty() && selected.all { e -> (e.location as? Location.Local)?.let { GitWorkTree.isUnderGit(it.path) } == true } } }
                // #1680 the SAME grouped component as the per-item menu, declared in build.json ui.files.menus.selection.
                val groups = FileMenu.resolve(FileMenu.SELECTION, Declarations.files.menu(FileMenu.SELECTION), FileMenu.Context(
                    count = selected.size, isDirectory = selected.isNotEmpty() && selected.all { it.isDirectory },
                    isLocal = selected.all { it.location is Location.Local }, inArchive = inArchive, anyFile = selected.any { !it.isDirectory },
                    underGit = allUnderGit, otherPaneLocal = ui.dualPane && otherLocal != null,
                ))
                if (groups.isNotEmpty()) GroupedFileMenu(groups) { id ->
                    more = false
                    when (id) {
                        "select_all" -> controller.update { s -> FilesReducer.selectAll(s, s.active, allKeys(s, controller)) }
                        "invert_selection" -> controller.update { s -> FilesReducer.invert(s, s.active, allKeys(s, controller)) }
                        "clear_selection" -> controller.update { FilesReducer.clearSelection(it, it.active) }
                        else -> onMenuPick(id, selected)
                    }
                }
                // Fallback only when ui.files.menus.selection is not declared: the previous flat list, unchanged.
                if (groups.isEmpty()) {
                if (!inArchive) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chrome_delete)) }, onClick = { more = false; onDelete() })
                    DropdownMenuItem(text = { Text(stringResource(if (selected.size == 1) R.string.files_rename else R.string.files_rename_pattern)) }, onClick = { more = false; onRename(selected) })
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_zip)) }, onClick = { more = false; onZip() })
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_share)) }, enabled = selected.any { !it.isDirectory }, onClick = { more = false; onShare(selected.filter { !it.isDirectory }.mapNotNull { (it.location as? Location.Local)?.path }) })
                }
                if (SelectionMenu.Action.MOVE_ALL in SelectionMenu.actionsFor(inArchive)) DropdownMenuItem(text = { Text(stringResource(R.string.files_move_all)) }, onClick = { more = false; onPickDestination(true) })
                if (SelectionMenu.gitActionsFor(inArchive, allUnderGit).isNotEmpty()) {
                    HorizontalDivider()
                    DropdownMenuItem(enabled = false, text = { Text(stringResource(R.string.files_menu_group_git), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary) }, onClick = {})
                    DropdownMenuItem(text = { Text(stringResource(R.string.files_git_move_all)) }, onClick = { more = false; onGitMoveAll(selected) })
                }
                DropdownMenuItem(text = { Text(stringResource(R.string.files_properties)) }, enabled = selected.size == 1, onClick = { more = false; selected.firstOrNull()?.let(onProperties) })
                Declarations.files.rowAction(COPY_PATH)?.let { a -> DropdownMenuItem(text = { Text(a.label) }, leadingIcon = { Icon(IconCatalog.vectorOrDefault(a.icon), null, Modifier.size(DriveMetrics.icon)) }, onClick = { more = false; onCopyPath(selected) }) }
                DropdownMenuItem(text = { Text(stringResource(R.string.files_select_all)) }, onClick = { more = false; controller.update { s -> FilesReducer.selectAll(s, s.active, allKeys(s, controller)) } })
                DropdownMenuItem(text = { Text(stringResource(R.string.files_invert)) }, onClick = { more = false; controller.update { s -> FilesReducer.invert(s, s.active, allKeys(s, controller)) } })
                DropdownMenuItem(text = { Text(stringResource(R.string.files_clear_selection)) }, onClick = { more = false; controller.update { FilesReducer.clearSelection(it, it.active) } })
                }
            }
        }
    }
}

private fun allKeys(s: FilesUiState, controller: FilesController): List<String> =
    controller.listings.value[s.activePane.location.key]?.let { l -> controller.visible(s.activePane, l).map { it.key } } ?: emptyList()

// ── places sheet ─────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlacesSheet(d: FilesDialog.Places, ui: FilesUiState, controller: FilesController, prefs: com.diegonmarcos.clouddrive.DrivePrefs.Snapshot, actions: DriveActions, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    val declared = remember { Places.declared(ctx) }
    // #1375 a drive mounting or leaving (UsbVolumeReceiver) re-reads the places.
    val usbTick by com.diegonmarcos.superapp.usbdrive.StorageVolumes.tick.collectAsState()
    val discovered by produceState(emptyList<Places.Place>(), prefs, hasAccess, usbTick) { value = withContext(Dispatchers.IO) { Places.discovered(ctx, prefs, hasAccess) } }
    val bookmarks = remember(prefs) { Places.bookmarks(prefs) }
    fun go(place: Places.Place) {
        when (place.kind) {
            Places.Kind.TREE -> actions.requestTreeGrant()
            Places.Kind.CONNECT -> actions.requestTreeGrant()
            else -> place.location?.let { loc ->
                if (d.pickDestination && d.gitMove.isNotEmpty()) controller.gitMoveEntries(d.gitMove, loc) else if (d.pickDestination) controller.transferSelection(d.pane, loc, d.move) else controller.openTab(d.pane, loc)
            }
        }
        onDismiss()
    }
    ModalBottomSheet(onDismissRequest = onDismiss, modifier = Modifier.testTag(DriveTags.FILES_PLACES_SHEET)) {
        LazyColumn(Modifier.fillMaxWidth().padding(bottom = DriveMetrics.padWide)) {
            item { SectionHeader(stringResource(if (d.pickDestination) (if (d.move) R.string.files_move_to else R.string.files_copy_to) else R.string.files_places)) }
            // #603 ONE HEADER PER DECLARED SECTION (ui.files.sections), in declared order, each
            // holding the places that name it: Emulated (shared storage) and Cloud-Drive-Storage
            // (the #575 shared store, a section of its own because every fleet app reads it).
            // Removable volumes stay under Places: they are discovered, never declared.
            Declarations.files.sections.forEach { section ->
                item { SectionHeader(section.label) }
                declared.filter { it.section == section.id }.forEach { p ->
                    if (p.hero) item { DriveCard(p.label, summary = p.location?.path, summaryMonospace = true, hero = true, onClick = { go(p) }) { Text(p.hint, Modifier.padding(top = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer) } }
                    else item(key = p.id) { PlaceRow(p) { go(p) } }
                }
            }
            item { SectionHeader(stringResource(R.string.files_places_section)) }
            items(discovered, key = { it.id }) { p -> PlaceRow(p) { go(p) } }
            item { SectionHeader(stringResource(R.string.files_bookmarks_section), count = bookmarks.size) }
            if (bookmarks.isEmpty()) item { Text(stringResource(R.string.files_bookmarks_none), Modifier.padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.gapWide), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            items(bookmarks, key = { it.id }) { b -> PlaceRow(b, onRemove = { controller.prefs.removeBookmark(b.location!!.path) }) { go(b) } }
        }
    }
}

@Composable
internal fun PlaceRow(p: Places.Place, onRemove: (() -> Unit)? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = DriveMetrics.padWide, vertical = DriveMetrics.pad), verticalAlignment = Alignment.CenterVertically) {
        Icon(IconCatalog.vectorOrDefault(p.icon), contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(DriveMetrics.padWide))
        Column(Modifier.weight(1f)) {
            Text(p.label, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val hint = p.hint.ifBlank { p.location?.path ?: "" }
            if (hint.isNotBlank()) Text(hint, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemove != null) IconButton(onClick = onRemove, Modifier.size(DriveMetrics.tap)) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.files_bookmark_remove), Modifier.size(DriveMetrics.icon)) }
    }
}

// ── inline search ────────────────────────────────────────────────────────

/** The storage roots a "from root" scope may start at: shared storage and each mounted volume under /storage. */
internal object ExplorerSearchRoots {
    fun list(): List<String> = buildList {
        add(android.os.Environment.getExternalStorageDirectory().path)
        File("/storage").listFiles()?.filter { it.isDirectory && it.name != "emulated" && it.name != "self" }?.forEach { add(it.path) }
    }
}

/** The compact bar (the Git page's GitSearchField, 40dp) with a close x, and ONE row of declared scope chips under it. */
@Composable
private fun ExplorerSearchBar(query: String, onQuery: (String) -> Unit, decl: Declarations.FilesSearchDecl, scopeId: String, onScope: (String) -> Unit, scoped: FilesController.ScopedSearch, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().testTag(DriveTags.FILES_SEARCH_BAR)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
            com.diegonmarcos.clouddrive.sync.GitSearchField(query, onQuery, decl.placeholder, Modifier.weight(1f).height(decl.heightDp.dp), tag = DriveTags.FILES_SEARCH_FIELD)
            IconButton(onClick = onClose, Modifier.size(DriveMetrics.tap).testTag(DriveTags.FILES_SEARCH_CLOSE)) { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.chrome_cancel)) }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = DriveMetrics.gutter).testTag(DriveTags.FILES_SEARCH_SCOPES), horizontalArrangement = Arrangement.spacedBy(DriveMetrics.gap), verticalAlignment = Alignment.CenterVertically) {
            decl.scopes.forEach { sc ->
                FilterChip(selected = sc.id == scopeId, onClick = { onScope(sc.id) }, label = { Text(sc.label, style = MaterialTheme.typography.labelMedium) }, modifier = Modifier.testTag(DriveTags.filesSearchScope(sc.id)))
            }
        }
        if (scoped.running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter))
    }
}

/** #1537 the search results as the sortable table: a recursive scope's streamed hits, or the in-place scope's filtered listing ([inPlace]). */
@Composable
private fun ScopedResults(search: FilesController.ScopedSearch, inPlace: List<FileOps.Entry>, recursive: Boolean, decl: Declarations.FilesSearchDecl, onOpen: (FileOps.Entry) -> Unit) {
    var sortColumn by rememberSaveable { mutableStateOf(SearchColumn.NAME.name) }
    var descending by rememberSaveable { mutableStateOf(false) }
    val sort = SearchSort(SearchColumn.entries.firstOrNull { it.name == sortColumn } ?: SearchColumn.NAME, descending)
    val created = remember { HashMap<String, Long>() }
    val entries = if (recursive) search.hits else inPlace
    val rows = remember(entries, sort) { SearchTable.sort(SearchTable.rows(entries) { p, m -> created.getOrPut(p) { SearchTable.createdTime(p, m) } }, sort) }
    Column(Modifier.fillMaxSize().testTag(DriveTags.FILES_SEARCH_RESULTS)) {
        if (recursive && (search.stop == ExplorerSearch.Stop.RESULTS || search.stop == ExplorerSearch.Stop.SCANNED))
            Row(Modifier.padding(horizontal = DriveMetrics.gutter)) { CapsuleBadge(stringResource(R.string.files_search_truncated, if (search.stop == ExplorerSearch.Stop.RESULTS) decl.resultCeiling else search.hits.size)) }
        when {
            rows.isEmpty() && !(recursive && search.running) -> EmptyState(Icons.Filled.Search, stringResource(R.string.files_search_none, search.query), stringResource(R.string.files_search_none_hint))
            else -> SearchResultsTable(rows, sort, onSort = { c -> val next = sort.tap(c); sortColumn = next.column.name; descending = next.descending }, onOpen = { r -> onOpen(r.entry) })
        }
    }
}
