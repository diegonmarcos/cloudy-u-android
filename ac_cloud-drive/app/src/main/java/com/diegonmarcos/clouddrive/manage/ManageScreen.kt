package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.superapp.perms.PermsPages
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.DriveActions
import com.diegonmarcos.clouddrive.DrivePrefs
import com.diegonmarcos.clouddrive.R
import com.diegonmarcos.clouddrive.editor.EditorHook
import com.diegonmarcos.clouddrive.storage.StorageCoordinator
import com.diegonmarcos.clouddrive.ui.SectionHeader
import com.diegonmarcos.clouddrive.files.ConfirmDialog
import com.diegonmarcos.clouddrive.files.FileOps
import com.diegonmarcos.clouddrive.files.Places
import com.diegonmarcos.clouddrive.ui.DriveMetrics
import com.diegonmarcos.clouddrive.ui.DriveTags
import com.diegonmarcos.clouddrive.ui.EmptyState
import com.diegonmarcos.clouddrive.ui.Hairline
import com.diegonmarcos.clouddrive.ui.IconCatalog
import com.diegonmarcos.clouddrive.ui.LoadingState
import com.diegonmarcos.clouddrive.ui.Pill
import com.diegonmarcos.clouddrive.ui.PillRow
import com.diegonmarcos.clouddrive.ui.topIslandInsets
import com.diegonmarcos.cloudlib.disk.AppSizes
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * MANAGE (#1537): a bottom-nav page, Home deleted. Three sub-pages of ui.sections[manage].pages: Overview (every volume
 * with its usage), Local-Apps (the installed-apps table ranked by size, cached and refreshable) and Local-Data
 * (everything in a chosen volume that is not an app, with the owner's category filter). The `when` is the ONE place
 * Kotlin names the three page ids.
 */
@Composable
fun ManageScreen(
    actions: DriveActions,
    prefs: DrivePrefs,
    storage: StorageCoordinator,
    onShowInExplorer: (String) -> Unit,
    onRoute: (tab: String, page: String) -> Unit,
    page: String? = null,
    onPageConsumed: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val pages = Declarations.managePages()
    var current by rememberSaveable { mutableStateOf(pages.firstOrNull()?.id ?: "") }
    LaunchedEffect(page) {
        if (page != null && pages.any { it.id == page }) current = page
        if (page != null) onPageConsumed()
    }
    Column(modifier.fillMaxSize().testTag(DriveTags.MANAGE)) {
        NavBarTopSecondRow(pages = pages, selectedId = current, onSelect = { current = it.id }, modifier = Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gap).testTag(DriveTags.MANAGE_STRIP), underTopChrome = false)
        Box(Modifier.weight(1f).consumeWindowInsets(topIslandInsets()).testTag(DriveTags.managePage(current))) {
            when (current) {
                Declarations.PAGE_OVERVIEW -> OverviewPage(storage)
                Declarations.PAGE_LOCAL_APPS -> LocalAppsPage(actions, prefs, onRoute)
                Declarations.PAGE_LOCAL_DATA -> DataPage(actions, prefs, onShowInExplorer)
                else -> EmptyState(IconCatalog.vectorOrDefault(Declarations.iconDefault), stringResource(R.string.chrome_unknown_tab), "")
            }
        }
    }
}

/**
 * Local Apps (the former Fleet-apps | Installed pair, merged): the ONE installed-apps table. The Fleet-apps grid page is gone
 * (owner removal, #1715); every field of it that applies to an installed app lives here (see [FleetAppsParity]). Its list is
 * cached: the last one shows at once with "updated <age>" while a background read replaces it; Refresh in the page bar,
 * pull-down, and a package add / remove / replace broadcast all re-read it.
 */
@Composable
fun LocalAppsPage(actions: DriveActions, prefs: DrivePrefs, onRoute: (tab: String, page: String) -> Unit) = AppsPage(prefs, actions = actions, onRoute = onRoute)

// ── Apps ─────────────────────────────────────────────────────────────────────

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AppsPage(
    prefs: DrivePrefs,
    collect: (android.content.Context) -> AppSnapshot.Snapshot = { AppSnapshot.collect(it) },
    actions: DriveActions? = null,
    onRoute: (tab: String, page: String) -> Unit = { _, _ -> },
    cache: LocalAppsCache? = null,
) {
    val ctx = LocalContext.current
    val store = remember(cache) { cache ?: LocalAppsCache(File(ctx.filesDir, "cloud-drive-cache/" + LocalAppsCache.FILE_NAME)) }
    var snap by remember { mutableStateOf<AppSnapshot.Snapshot?>(null) }
    var fromCache by remember { mutableStateOf(false) }
    var refreshing by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var clock by remember { mutableStateOf(System.currentTimeMillis()) }
    val saved = remember { prefs.appsSort() }
    var column by rememberSaveable { mutableStateOf(saved.first?.let { n -> AppColumn.entries.firstOrNull { it.name == n } } ?: AppTable.DEFAULT_SORT) }
    var descending by rememberSaveable { mutableStateOf(saved.second) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    // A package added / removed / replaced makes the cached list stale: mark it and re-read at once.
    DisposableEffect(ctx) {
        val rx = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, i: android.content.Intent?) { store.invalidate(); tick++ }
        }
        val f = android.content.IntentFilter().apply {
            addAction(android.content.Intent.ACTION_PACKAGE_ADDED); addAction(android.content.Intent.ACTION_PACKAGE_REMOVED); addAction(android.content.Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        runCatching { androidx.core.content.ContextCompat.registerReceiver(ctx, rx, f, androidx.core.content.ContextCompat.RECEIVER_EXPORTED) }
        onDispose { runCatching { ctx.unregisterReceiver(rx) } }
    }
    LaunchedEffect(tick) {
        refreshing = true
        withContext(Dispatchers.IO) { LocalAppsCache.load(store, { collect(ctx) }) { s, cached -> snap = s; fromCache = cached } }
        clock = System.currentTimeMillis(); refreshing = false
    }
    val doRefresh = { tick++ ; Unit }
    Column(Modifier.fillMaxSize()) {
        // The page bar: Refresh, the age of the list, and the in-app shortcuts the Fleet-apps grid carried (GitSync, RSync).
        PillRow(Modifier.padding(horizontal = DriveMetrics.gutter)) {
            Pill(stringResource(R.string.manage_apps_refresh), doRefresh, modifier = Modifier.testTag(DriveTags.APPS_REFRESH), enabled = !refreshing)
            Declarations.apps.filter { it.routeTab.isNotBlank() }.forEach { a ->
                Pill(a.icon + " " + a.label, { onRoute(a.routeTab, a.routePage) }, modifier = Modifier.testTag(DriveTags.appTile(a.label)))
            }
            Text(
                snap?.let { LocalAppsCache.ageLabel(clock, it.capturedAt) + if (refreshing) " · refreshing" else "" } ?: stringResource(R.string.manage_apps_loading),
                Modifier.weight(1f).testTag(DriveTags.APPS_UPDATED), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End, maxLines = 1,
            )
        }
        val s = snap
        if (s == null) { LoadingState(Modifier.weight(1f)); return@Column }
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(isRefreshing = refreshing, onRefresh = { tick++ }, modifier = Modifier.weight(1f).fillMaxWidth().testTag(DriveTags.APPS_PULL)) {
            Column(Modifier.fillMaxSize()) {
                if (!s.usageAccess) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap).testTag(DriveTags.APPS_USAGE_GRANT), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.manage_apps_usage_missing), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Pill(stringResource(R.string.disk_grant_usage), { AppSizes.openPermissions(ctx) }, filled = true)
                    }
                }
                if (s.shell == null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap).testTag(DriveTags.APPS_SHELL_HINT), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.manage_apps_shell_hint), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    }
                }
                // Fleet apps declared but not on this phone: catalogue only, installed from Cloud Store.
                val have = remember(s) { s.rows.map { it.pkg }.toSet() }
                val missing = remember(s) { Declarations.apps.filter { it.packageName.isNotBlank() && it.packageName !in have }.map { it.label } }
                if (missing.isNotEmpty()) {
                    Text(stringResource(R.string.manage_apps_fleet_missing, missing.joinToString(", ")), Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap).testTag(DriveTags.APPS_FLEET_MISSING), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                val rows = remember(s, column, descending) { AppTable.sort(s.rows, column, descending) }
                AppsTable(rows, column, descending, onSort = { c ->
                    if (c == column) descending = !descending else { column = c; descending = c != AppColumn.NAME && c != AppColumn.VERSION }
                    prefs.setAppsSort(column.name, descending)
                }, onOpen = { pkg -> PermsPages.openAppInfo(ctx, pkg) }, onLaunch = actions?.let { a -> { r: AppRow -> a.launchApp(r.pkg, r.fleetUrl.orEmpty()) } })
            }
        }
    }
}

/** The labels of the seven columns, in order; NAME is the wide one. */
@Composable
private fun columnLabel(c: AppColumn): String = stringResource(when (c) {
    AppColumn.NAME -> R.string.manage_apps_col_name
    AppColumn.VERSION -> R.string.manage_apps_col_version
    AppColumn.TOTAL -> R.string.manage_apps_col_total
    AppColumn.APP -> R.string.manage_apps_col_app
    AppColumn.DATA -> R.string.manage_apps_col_data
    AppColumn.CACHE -> R.string.manage_apps_col_cache
    AppColumn.RUNNING -> R.string.manage_apps_col_running
    AppColumn.RAM -> R.string.manage_apps_col_ram
})

/** Sticky header, sortable columns, one horizontal scroll shared by header and rows (the table is wider than a phone). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AppsTable(rows: List<AppRow>, column: AppColumn, descending: Boolean, onSort: (AppColumn) -> Unit, onOpen: (String) -> Unit, onLaunch: ((AppRow) -> Boolean)? = null) {
    val hs = rememberScrollState()
    val yes = stringResource(R.string.manage_yes); val no = stringResource(R.string.manage_no)
    Box(Modifier.fillMaxSize().horizontalScroll(hs)) {
        LazyColumn(Modifier.width(DriveMetrics.tableName + DriveMetrics.tableCell * AppColumn.entries.size).testTag(DriveTags.APPS_TABLE)) {
            stickyHeader {
                Row(Modifier.background(MaterialTheme.colorScheme.surfaceVariant).heightIn(min = DriveMetrics.tap).testTag(DriveTags.APPS_HEADER), verticalAlignment = Alignment.CenterVertically) {
                    AppColumn.entries.forEach { c ->
                        val arrow = if (c == column) (if (descending) " ▼" else " ▲") else ""
                        Text(columnLabel(c) + arrow, cellModifier(c).clickable { onSort(c) }.testTag(DriveTags.appsHeader(c.name)), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, textAlign = if (c == AppColumn.NAME || c == AppColumn.VERSION) TextAlign.Start else TextAlign.End, maxLines = 1)
                    }
                    if (onLaunch != null) Text(stringResource(R.string.manage_apps_col_actions), cellModifier(AppColumn.TOTAL), style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End, maxLines = 1)
                }
            }
            items(rows, key = { it.pkg }) { r ->
                Row(Modifier.heightIn(min = DriveMetrics.rowHeight).clickable { onOpen(r.pkg) }.testTag(DriveTags.APPS_ROW), verticalAlignment = Alignment.CenterVertically) {
                    Column(cellModifier(AppColumn.NAME)) {
                        Text((r.fleetIcon?.let { "$it " } ?: "") + r.label, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(r.pkg, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Text(r.versionName ?: AppTable.DASH, cellModifier(AppColumn.VERSION), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Num(AppTable.size(r.totalBytes), AppColumn.TOTAL, bold = true)
                    Num(AppTable.size(r.appBytes), AppColumn.APP)
                    Num(AppTable.size(r.dataBytes), AppColumn.DATA)
                    Num(AppTable.size(r.cacheBytes), AppColumn.CACHE)
                    Num(AppTable.running(r.running, yes, no), AppColumn.RUNNING)
                    Num(AppTable.ram(r.ramKb), AppColumn.RAM)
                    if (onLaunch != null) Text(stringResource(R.string.manage_apps_open), cellModifier(AppColumn.TOTAL).clickable { onLaunch(r) }.testTag(DriveTags.appOpen(r.pkg)), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, textAlign = TextAlign.End, maxLines = 1)
                }
                Hairline()
            }
        }
    }
}

private fun cellModifier(c: AppColumn): Modifier = Modifier.width(if (c == AppColumn.NAME) DriveMetrics.tableName else DriveMetrics.tableCell).padding(horizontal = DriveMetrics.gap)

@Composable
private fun Num(text: String, c: AppColumn, bold: Boolean = false) =
    Text(text, cellModifier(c), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = if (bold) FontWeight.SemiBold else null, textAlign = TextAlign.End, maxLines = 1)

// ── Data ─────────────────────────────────────────────────────────────────────

@Composable
private fun groupLabel(g: DataGroup): String = stringResource(when (g) {
    DataGroup.MEDIA -> R.string.manage_group_media
    DataGroup.OFFICE -> R.string.manage_group_office
    DataGroup.STRUCTURE -> R.string.manage_group_structure
    DataGroup.BINS -> R.string.manage_group_bins
    DataGroup.TMP -> R.string.manage_group_tmp
    DataGroup.BIN -> R.string.manage_group_bin
    DataGroup.OTHERS -> R.string.manage_group_others
})

@Composable
private fun kindLabel(k: DataKind): String = stringResource(when (k) {
    DataKind.VIDEOS -> R.string.manage_kind_videos
    DataKind.PICTURES -> R.string.manage_kind_pictures
    DataKind.MUSIC -> R.string.manage_kind_music
    DataKind.PDF -> R.string.manage_kind_pdf
    DataKind.OFFICE -> R.string.manage_kind_office
    DataKind.STRUCTURED -> R.string.manage_kind_structured
    DataKind.MARKDOWN -> R.string.manage_kind_markdown
    DataKind.TEXT -> R.string.manage_kind_text
    DataKind.CODE -> R.string.manage_kind_code
    DataKind.BINARIES -> R.string.manage_kind_binaries
    DataKind.APK -> R.string.manage_kind_apk
    DataKind.ARCHIVES -> R.string.manage_kind_archives
    DataKind.SCRIPTS -> R.string.manage_kind_scripts
    DataKind.TMP -> R.string.manage_kind_tmp
    DataKind.BIN -> R.string.manage_kind_bin
    DataKind.OTHERS -> R.string.manage_kind_others
})

/** The filter block: per group a tri-state toggle (on / partial / off) and one pill per subtype. */
@Composable
fun DataFilters(filter: DataFilter, onChange: (DataFilter) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter).testTag(DriveTags.DATA_FILTERS), verticalArrangement = Arrangement.spacedBy(DriveMetrics.tight)) {
        // #1537 "All" is the FIRST option and the default: every file shows until a group is picked.
        Row(verticalAlignment = Alignment.CenterVertically) {
            Pill(stringResource(R.string.manage_filter_all), { onChange(DataFilter.DEFAULT) }, modifier = Modifier.testTag(DriveTags.DATA_ALL), filled = filter.all)
        }
        DataGroup.entries.forEach { g ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                val st = when (filter.groupState(g)) { GroupState.ALL -> ToggleableState.On; GroupState.PARTIAL -> ToggleableState.Indeterminate; GroupState.NONE -> ToggleableState.Off }
                TriStateCheckbox(state = st, onClick = { onChange(filter.toggleGroup(g)) }, modifier = Modifier.size(DriveMetrics.tapSmall).testTag(DriveTags.dataGroup(g.name)))
                Text(groupLabel(g), Modifier.width(DriveMetrics.tabLabel).padding(start = DriveMetrics.gap), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
                PillRow(Modifier.weight(1f)) {
                    DataKind.inGroup(g).forEach { k -> Pill(kindLabel(k), { onChange(filter.toggle(k)) }, modifier = Modifier.testTag(DriveTags.dataKind(k.name)), filled = k in filter.on) }
                }
            }
        }
    }
}

@Composable
fun DataPage(actions: DriveActions, prefs: DrivePrefs, onShowInExplorer: (String) -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { DataScanController.get(ctx) }
    val state by controller.state.collectAsState()
    var filter by remember { mutableStateOf(DataFilter.decode(prefs.dataFilter())) }
    var folders by remember { mutableStateOf(prefs.dataFolders()) }
    var menuFor by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<String?>(null) }
    val hasAccess = remember { Places.hasAllFilesAccess(ctx) }
    // #1537 the volume switcher (ui.manage.local_data.switcher: Emulated | Termux | Nix | External drive): one volume is scanned at a time.
    val probe = remember { AndroidVolumeProbe(ctx) }
    val decl = Declarations.manage
    val volumes = remember { decl.switcher.mapNotNull { id -> decl.local.firstOrNull { it.id == id } } }
    var volume by rememberSaveable { mutableStateOf(decl.defaultVolume.takeIf { d -> volumes.any { it.id == d } } ?: volumes.firstOrNull()?.id ?: "") }
    val selected = volumes.firstOrNull { it.id == volume }
    // rootsOf asks StorageManager and stats folders: off the main thread, the scan below waits for the answer.
    val roots by androidx.compose.runtime.produceState(emptyList<java.io.File>(), volume) { value = withContext(Dispatchers.IO) { selected?.let { VolumeOverview.rootsOf(it, probe).filter { r -> r.isDirectory } }.orEmpty() } }
    LaunchedEffect(hasAccess, volume) { if (hasAccess && roots.isNotEmpty() && (state.key != volume || state.scannedAt == 0L)) controller.scan(scope, volumeRoots = roots, key = volume) }

    val shown = remember(state.items, state.key, volume, filter) { if (state.key == volume) DataScanner.filtered(state.items, filter) else emptyList() }
    val rows: List<Pair<String, Long>> = remember(shown, folders) { if (folders) DataScanner.folders(shown) else shown.map { it.path to it.size } }
    val total = remember(shown) { shown.sumOf { it.size } }

    Column(Modifier.fillMaxSize()) {
        // #1356 no title row (the terminal shortcut is the Termux tile of Home ▸ Apps): the content starts under the top bar.
        if (!hasAccess) { EmptyState(IconCatalog.vectorOrDefault("lock"), stringResource(R.string.manage_data_no_access), "", Modifier.weight(1f), stringResource(R.string.disk_grant_files)) { actions.requestStorageAccess() }; return@Column }
        PillRow(Modifier.padding(horizontal = DriveMetrics.gutter)) {
            volumes.forEach { v -> Pill(v.label, { volume = v.id }, modifier = Modifier.testTag(DriveTags.dataVolume(v.id)), filled = v.id == volume) }
        }
        DataFilters(filter) { f -> filter = f; prefs.setDataFilter(f.encode()) }
        PillRow(Modifier.padding(horizontal = DriveMetrics.gutter, vertical = DriveMetrics.gap)) {
            Pill(stringResource(R.string.manage_data_files), { folders = false; prefs.setDataFolders(false) }, filled = !folders)
            Pill(stringResource(R.string.manage_data_folders), { folders = true; prefs.setDataFolders(true) }, filled = folders)
            if (state.running) Pill(stringResource(R.string.chrome_cancel), { controller.cancel() })
            else Pill(stringResource(R.string.manage_data_rescan), { controller.scan(scope, full = true, volumeRoots = roots, key = volume) })
        }
        if (state.running) {
            Column(Modifier.fillMaxWidth().padding(horizontal = DriveMetrics.gutter).testTag(DriveTags.DATA_PROGRESS)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                val p = state.progress
                Text(if (p == null) stringResource(R.string.manage_data_scanning) else stringResource(R.string.manage_data_progress, p.dirs, p.files, FileOps.humanBytes(p.bytes), p.current), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (roots.isEmpty()) {
            val why = selected?.let { VolumeOverview.local(listOf(it), probe).firstOrNull()?.unavailable }.orEmpty()
            EmptyState(IconCatalog.vectorOrDefault("storage"), stringResource(R.string.manage_data_volume_na, selected?.label.orEmpty(), why), "", Modifier.weight(1f)); return@Column
        }
        if (rows.isEmpty() && !state.running) { EmptyState(IconCatalog.vectorOrDefault("bar_chart"), stringResource(R.string.manage_data_empty), "", Modifier.weight(1f)); return@Column }
        LazyColumn(Modifier.weight(1f)) {
            items(rows, key = { it.first }) { (path, size) ->
                Box {
                    Row(Modifier.fillMaxWidth().heightIn(min = DriveMetrics.rowHeight).clickable { menuFor = path }.padding(horizontal = DriveMetrics.gutter).testTag(DriveTags.DATA_ROW), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(path.substringAfterLast('/'), style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(path.substringBeforeLast('/'), style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(FileOps.humanBytes(size), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold)
                    }
                    DropdownMenu(expanded = menuFor == path, onDismissRequest = { menuFor = null }) {
                        val isDir = folders
                        if (!isDir) DropdownMenuItem(text = { Text(stringResource(R.string.manage_data_open)) }, onClick = { menuFor = null; open(actions, path) })
                        if (!isDir) DropdownMenuItem(text = { Text(stringResource(R.string.viewer_share)) }, onClick = { menuFor = null; actions.share(listOf(path)) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.manage_data_show)) }, onClick = { menuFor = null; onShowInExplorer(path) })
                        DropdownMenuItem(text = { Text(stringResource(R.string.chrome_delete)) }, onClick = { menuFor = null; confirm = path })
                    }
                }
                Hairline()
            }
        }
    }
    confirm?.let { path ->
        ConfirmDialog(stringResource(R.string.viewer_delete_title, path.substringAfterLast('/')), stringResource(R.string.files_delete_body), stringResource(R.string.chrome_delete), { confirm = null }) {
            confirm = null
            scope.launch {
                val ok = withContext(Dispatchers.IO) { val f = File(path); if (f.isDirectory) f.deleteRecursively() else f.delete() }
                if (ok) controller.forget(path)
            }
        }
    }
}

/** #1537 a Manage list row opens in the Editor like every file tap; only the refused Office formats go to another app. */
private fun open(actions: DriveActions, path: String) {
    if (EditorHook.canOpen(path.substringAfterLast('/'))) EditorHook.open(path) else actions.openWith(path)
}
