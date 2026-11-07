package com.diegonmarcos.superapp.configs

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.diegonmarcos.superapp.launcher.folders.ACol
import com.diegonmarcos.superapp.launcher.folders.AppFilter
import com.diegonmarcos.superapp.launcher.folders.DataMode
import com.diegonmarcos.superapp.launcher.folders.FolderExport
import com.diegonmarcos.superapp.launcher.folders.FolderScope
import com.diegonmarcos.superapp.launcher.folders.AppGroup
import com.diegonmarcos.superapp.launcher.folders.AppItem
import com.diegonmarcos.superapp.launcher.folders.BCol
import com.diegonmarcos.superapp.launcher.folders.FolderEditModel
import com.diegonmarcos.superapp.launcher.folders.FolderItem
import com.diegonmarcos.superapp.launcher.folders.NEEDS_TITLE
import com.diegonmarcos.superapp.launcher.folders.OTHERS_FOLDER_ID
import com.diegonmarcos.superapp.launcher.folders.OTHERS_FOLDER_LABEL
import com.diegonmarcos.superapp.launcher.folders.SortSpec
import com.diegonmarcos.superapp.launcher.folders.THeader
import com.diegonmarcos.superapp.launcher.folders.appItems
import com.diegonmarcos.superapp.launcher.folders.folderItems
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * The editor's UI state: the run-time model (editable, what the device applies), the declarative model (the SoT, read-only)
 * and every view setting. Hoisted so tests can drive it.
 */
class FolderEditorState(runtime: FolderEditModel, declarative: FolderEditModel = runtime, val scope: FolderScope = FolderScope.PHONE) {
    var runtimeModel by mutableStateOf(runtime)
    val declarativeModel: FolderEditModel = declarative
    var mode by mutableStateOf(DataMode.RUNTIME)

    /** The model the tables show under the current [mode]. */
    val model: FolderEditModel get() = if (mode == DataMode.DECLARATIVE) declarativeModel else runtimeModel

    var dirty by mutableStateOf(false)
    var notice by mutableStateOf<String?>(null)

    var aQuery by mutableStateOf("")
    var aSort by mutableStateOf<SortSpec?>(null)
    var aGroupSection by mutableStateOf(true)

    var bQuery by mutableStateOf("")
    var bSort by mutableStateOf<SortSpec?>(null)
    var bGroup by mutableStateOf(AppGroup.FOLDER)
    var bFilter by mutableStateOf(AppFilter.ALL)

    var previewOpen by mutableStateOf(true)

    /** Apps whose run-time folder differs from the declared one (declared-vs-actual drift). */
    val drift: Int get() = runtimeModel.apps.count { r -> declarativeModel.apps.firstOrNull { it.pkg == r.pkg }?.folderId != r.folderId }

    /** Apply an edit to the run-time model and mark it unsaved. The declarative view is the SoT and is read-only here. */
    fun edit(f: (FolderEditModel) -> FolderEditModel) {
        if (mode == DataMode.DECLARATIVE) return
        runtimeModel = f(runtimeModel); dirty = true
    }

    /** Delete a Table A folder; its apps fall to Others and the page says so. */
    fun deleteFolder(id: String) {
        if (mode == DataMode.DECLARATIVE) return
        val moved = model.appCount(id)
        val label = model.def(id)?.label ?: id
        edit { it.deleteFolder(id) }
        notice = "Deleted \"$label\": $moved app(s) moved to Others."
    }

    /** Copy All payload for the selected scope and mode. */
    fun copyAllText() = FolderExport.copyAll(scope, mode, model)

    /** Export All documents (the two datasets) for the selected scope and mode. */
    fun exportAllDocs() = FolderExport.exportAll(scope, mode, model)
}

object FolderTablesTags {
    const val SAVE = "folders:save"
    const val RESET = "folders:reset"
    const val NOTICE = "folders:notice"
    const val COPY_ALL = "folders:copyall"
    const val EXPORT_ALL = "folders:exportall"
    fun mode(m: DataMode) = "folders:mode:${m.label}"
    const val A_FILTER = "fa:filter"
    const val A_GROUP = "fa:group"
    const val A_ADD = "fa:add"
    const val B_FILTER = "fb:filter"
    const val B_ADD = "fb:add"
    const val B_ADD_PKG = "fb:add:pkg"
    const val B_ADD_NAME = "fb:add:name"
    const val PREVIEW_TOGGLE = "fp:toggle"
    fun aRow(id: String) = "fa:row:$id"
    fun aCell(col: String, id: String) = "fa:$col:$id"
    fun aSort(col: String) = "fa:sort:$col"
    fun aCount(id: String) = "fa:count:$id"
    fun aDel(id: String) = "fa:del:$id"
    fun bRow(pkg: String) = "fb:row:$pkg"
    fun bFolder(pkg: String) = "fb:folder:$pkg"
    fun bOpt(pkg: String, fid: String) = "fb:opt:$pkg:$fid"
    fun bSection(pkg: String) = "fb:section:$pkg"
    fun bOrder(pkg: String) = "fb:order:$pkg"
    fun bDeclared(pkg: String) = "fb:declared:$pkg"
    fun bDel(pkg: String) = "fb:del:$pkg"
    fun bSort(col: String) = "fb:sort:$col"
    fun bChip(f: AppFilter) = "fb:chip:${f.name.lowercase()}"
    fun bGroup(g: AppGroup) = "fb:group:${g.name.lowercase()}"
    fun bHeader(key: String) = "fb:header:$key"
    fun tile(fid: String) = "fp:tile:$fid"
}

/** Dense grid metrics, all derived from the kit's density tokens: a cell unit is a fraction of the glyph box. */
private val UNIT: Dp get() = KitDensity.glyph * 0.8f
private val ACTIONS_W: Dp get() = UNIT * 2
private val HAIR: Dp get() = KitDensity.rule

/**
 * Table A (folder structure), Table B (app allocation) and the read-only live grid preview, as one dense, flat data grid.
 * Edits go to [state] (run-time mode only); [onSave] persists them as the on-device override, [onReset] reverts to the
 * served defaults, [onCopy]/[onExport] hand the Copy All / Export All payloads of the current scope and mode to the host.
 * The shipped defaults (data/fleet/launcher-folders|launcher-folder-assignments) are changed by a repo edit.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderTablesScreen(
    state: FolderEditorState, onSave: () -> Unit, onReset: () -> Unit,
    modifier: Modifier = Modifier,
    onCopy: (String) -> Unit = {}, onExport: (List<Pair<String, String>>) -> Unit = {},
) {
    val p = LocalKitPalette.current
    val m = state.model
    val editable = state.mode == DataMode.RUNTIME
    val aItems = m.folderItems(state.aQuery, state.aSort, state.aGroupSection)
    val bItems = m.appItems(state.bQuery, state.bFilter, state.bSort, state.bGroup)
    val aScroll = rememberScrollState()
    val bScroll = rememberScrollState()
    LazyColumn(modifier.fillMaxWidth()) {
        // ---- one thin action toolbar (Save/Reset | Declarative/Run-Time | Copy All/Export All) ----
        item(key = "bar") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2),
                modifier = Modifier.padding(bottom = KitDensity.small / 2)) {
                Pill("Save", FolderTablesTags.SAVE, false, editable && state.dirty) { onSave() }
                Pill("Reset", FolderTablesTags.RESET, false) { onReset() }
                Pill("Declarative", FolderTablesTags.mode(DataMode.DECLARATIVE), state.mode == DataMode.DECLARATIVE) { state.mode = DataMode.DECLARATIVE }
                Pill("Run-Time (${state.drift} drift)", FolderTablesTags.mode(DataMode.RUNTIME), editable) { state.mode = DataMode.RUNTIME }
                Pill("Copy All", FolderTablesTags.COPY_ALL, false) { onCopy(state.copyAllText()) }
                Pill("Export All", FolderTablesTags.EXPORT_ALL, false) { onExport(state.exportAllDocs()) }
                Text(if (!editable) "${state.scope.key} / declarative (read-only SoT)" else if (state.dirty) "${state.scope.key} / run-time / unsaved" else "${state.scope.key} / run-time / saved",
                    color = if (state.dirty && editable) p.warn else p.textSecondary, fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace)
            }
        }
        state.notice?.let { n ->
            item(key = "notice") {
                Text(n, color = p.warn, fontSize = KitDensity.caption, modifier = Modifier.fillMaxWidth()
                    .border(HAIR, p.warn).clickable { state.notice = null }.testTag(FolderTablesTags.NOTICE))
            }
        }

        // ---------------- Table A ----------------
        item(key = "a-title") { Toolbar("A  Folder structure (${m.folders.size})", state.aQuery, { state.aQuery = it }, FolderTablesTags.A_FILTER, "filter") {
            Pill(if (state.aGroupSection) "Group: Parent" else "Group: off", FolderTablesTags.A_GROUP, state.aGroupSection) { state.aGroupSection = !state.aGroupSection }
            Pill("+ Folder", FolderTablesTags.A_ADD, false, editable) { state.edit { it.addFolder() } }
        } }
        item(key = "a-head") {
            TableRow(aScroll, header = true) {
                HCell("#", 1)
                HSort("Parent", ACol.SECTION, 3, state.aSort, FolderTablesTags.aSort(ACol.SECTION)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.SECTION) }
                HSort("Folder ID", ACol.ID, 3, state.aSort, FolderTablesTags.aSort(ACol.ID)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.ID) }
                HSort("Label", ACol.LABEL, 3, state.aSort, FolderTablesTags.aSort(ACol.LABEL)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.LABEL) }
                HCell("Icon", 3)
                HSort("Ord", ACol.ORDER, 1, state.aSort, FolderTablesTags.aSort(ACol.ORDER)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.ORDER) }
                HCell("Apps", 1)
                Box(Modifier.width(ACTIONS_W))
            }
        }
        items(aItems, key = { if (it is THeader) it.key else "a:${(it as FolderItem).def.id}" }) { item ->
            when (item) {
                is THeader -> GroupHeader(item, null)
                is FolderItem -> {
                    val f = item.def
                    val isNew = f.id in m.newFolderIds && editable
                    TableRow(aScroll, Modifier.testTag(FolderTablesTags.aRow(f.id))) {
                        Cell(1) { Num(item.n.toString()) }
                        Cell(3) { EditCell(f.section, FolderTablesTags.aCell("section", f.id), accent = true, enabled = editable) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(section = v) } } } }
                        Cell(3) {
                            if (isNew) EditId(f.id, FolderTablesTags.aCell("id", f.id)) { v -> state.edit { it.renameFolderId(f.id, v) } }
                            else Mono(f.id, FolderTablesTags.aCell("id", f.id))
                        }
                        Cell(3) { EditCell(f.label, FolderTablesTags.aCell("label", f.id), enabled = editable) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(label = v) } } } }
                        Cell(3) { EditCell(f.icon, FolderTablesTags.aCell("icon", f.id), mono = true, enabled = editable) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(icon = v) } } } }
                        Cell(1) { EditInt(f.order, FolderTablesTags.aCell("order", f.id), editable) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(order = v) } } } }
                        Cell(1) { Num(m.appCount(f.id).toString(), FolderTablesTags.aCount(f.id)) }
                        Box(Modifier.width(ACTIONS_W)) { if (editable) Action("del", FolderTablesTags.aDel(f.id)) { state.deleteFolder(f.id) } }
                    }
                }
                else -> Unit
            }
        }

        // ---------------- Table B ----------------
        item(key = "b-title") {
            Toolbar("B  App allocation (${m.apps.size})", state.bQuery, { state.bQuery = it }, FolderTablesTags.B_FILTER, "filter", topGap = true) {
                // EXACTLY three filters.
                Pill("All (${m.apps.size})", FolderTablesTags.bChip(AppFilter.ALL), state.bFilter == AppFilter.ALL) { state.bFilter = AppFilter.ALL }
                Pill("No Folders (${m.noFolderCount})", FolderTablesTags.bChip(AppFilter.NO_FOLDER), state.bFilter == AppFilter.NO_FOLDER, tint = if (m.noFolderCount > 0) p.warn else null) { state.bFilter = AppFilter.NO_FOLDER }
                Pill("Others (${m.othersCount})", FolderTablesTags.bChip(AppFilter.OTHERS), state.bFilter == AppFilter.OTHERS, tint = if (m.othersCount > 0) p.warn else null) { state.bFilter = AppFilter.OTHERS }
                Text("|", color = p.hairline, fontSize = KitDensity.caption)
                Pill("By Folder", FolderTablesTags.bGroup(AppGroup.FOLDER), state.bGroup == AppGroup.FOLDER) { state.bGroup = AppGroup.FOLDER }
                Pill("By Parent", FolderTablesTags.bGroup(AppGroup.PARENT), state.bGroup == AppGroup.PARENT) { state.bGroup = AppGroup.PARENT }
                Pill("Flat", FolderTablesTags.bGroup(AppGroup.NONE), state.bGroup == AppGroup.NONE) { state.bGroup = AppGroup.NONE }
            }
        }
        item(key = "b-head") {
            TableRow(bScroll, header = true) {
                HCell("#", 1)
                HSort("App", BCol.APP, 3, state.bSort, FolderTablesTags.bSort(BCol.APP)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.APP) }
                HSort("Package", BCol.PACKAGE, 6, state.bSort, FolderTablesTags.bSort(BCol.PACKAGE)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.PACKAGE) }
                HSort("Folder", BCol.FOLDER, 3, state.bSort, FolderTablesTags.bSort(BCol.FOLDER)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.FOLDER) }
                HCell("Parent", 3)
                HCell("Declared", 3)
                HSort("Ord", BCol.ORDER, 1, state.bSort, FolderTablesTags.bSort(BCol.ORDER)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.ORDER) }
                Box(Modifier.width(ACTIONS_W))
            }
        }
        items(bItems, key = { if (it is THeader) it.key else "b:${(it as AppItem).row.pkg}" }) { item ->
            when (item) {
                is THeader -> GroupHeader(item, FolderTablesTags.bHeader(item.key))
                is AppItem -> {
                    val r = item.row
                    val needs = m.needsClassification(r)
                    val declaredRow = state.declarativeModel.apps.firstOrNull { it.pkg == r.pkg }
                    val declaredLabel = declaredRow?.let { state.declarativeModel.folderLabel(it) } ?: "-"
                    val drifted = declaredRow?.folderId != r.folderId
                    TableRow(bScroll, Modifier.testTag(FolderTablesTags.bRow(r.pkg))) {
                        Cell(1) { Num(item.n.toString()) }
                        Cell(3) { Text(r.name, color = p.textPrimary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Cell(6) { Mono(r.pkg, null) }
                        Cell(3) { FolderDropdown(m, r.pkg, m.folderLabel(r), needs, editable) { fid -> state.edit { it.setFolder(r.pkg, fid) } } }
                        Cell(3) { Text(m.sectionOf(r).ifEmpty { "-" }, color = p.accent, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(FolderTablesTags.bSection(r.pkg))) }
                        Cell(3) { Text(declaredLabel, color = if (drifted) p.warn else p.textSecondary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.testTag(FolderTablesTags.bDeclared(r.pkg))) }
                        Cell(1) { EditInt(r.order, FolderTablesTags.bOrder(r.pkg), editable) { v -> state.edit { it.setOrder(r.pkg, v) } } }
                        Box(Modifier.width(ACTIONS_W)) { if (editable) Action("unassign", FolderTablesTags.bDel(r.pkg)) { state.edit { it.setFolder(r.pkg, null) } } }
                    }
                }
                else -> Unit
            }
        }
        item(key = "b-add") {
            var pkg by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(UNIT * 6)) { EditCell(pkg, FolderTablesTags.B_ADD_PKG, mono = true, hint = "package", enabled = editable) { pkg = it } }
                Box(Modifier.width(UNIT * 3)) { EditCell(name, FolderTablesTags.B_ADD_NAME, hint = "app name", enabled = editable) { name = it } }
                Pill("+ App", FolderTablesTags.B_ADD, false, editable) {
                    if (pkg.isNotBlank()) { state.edit { it.addApp(pkg, name, null) }; pkg = ""; name = "" }
                }
            }
        }

        // ---------------- Live grid preview (read-only mirror) ----------------
        item(key = "p-title") {
            Row(Modifier.padding(top = KitDensity.small / 2), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                Text("LIVE GRID (${m.preview().sumOf { it.folders.size }} folders)", color = p.textSecondary, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                Pill(if (state.previewOpen) "Hide" else "Show", FolderTablesTags.PREVIEW_TOGGLE, false) { state.previewOpen = !state.previewOpen }
            }
        }
        if (state.previewOpen) {
            for (section in m.preview()) {
                item(key = "ps:${section.title}") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(HAIR)) {
                        Text(section.title, color = p.accent, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold)
                        for (f in section.folders) {
                            Text("${f.def.label} \u00b7 ${f.apps.size}", color = p.textPrimary, fontSize = KitDensity.caption, maxLines = 1,
                                modifier = Modifier.border(HAIR, p.hairline).background(p.surface).padding(horizontal = KitDensity.small / 2)
                                    .semantics(mergeDescendants = true) {}.testTag(FolderTablesTags.tile(f.def.id)))
                        }
                    }
                }
            }
        }
        item(key = "foot") {
            Text("Save = on-device override (this scope). Reset = served defaults. Shipped defaults: repo edit of data/fleet/launcher-folders + launcher-folder-assignments.",
                color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(top = KitDensity.small / 2))
        }
    }
}

// ---------------- building blocks (dense, flat) ----------------

@Composable
private fun Toolbar(title: String, query: String, onQuery: (String) -> Unit, tag: String, hint: String, topGap: Boolean = false, actions: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    FlowRow(Modifier.padding(top = if (topGap) KitDensity.small else KitDensity.interactiveFloor, bottom = KitDensity.small / 2),
        horizontalArrangement = Arrangement.spacedBy(KitDensity.small / 2), verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
        Text(title, color = p.textPrimary, fontSize = KitDensity.caption, fontWeight = FontWeight.Bold)
        Box(Modifier.width(UNIT * 3).border(HAIR, p.hairline).padding(horizontal = KitDensity.small / 2)) { EditCell(query, tag, hint = hint, onChange = onQuery) }
        actions()
    }
}

@Composable
private fun Pill(label: String, tag: String, on: Boolean, enabled: Boolean = true, tint: Color? = null, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val ink = tint ?: if (on) p.accent else p.textSecondary
    Text(label, color = if (enabled) ink else p.hairline, fontSize = KitDensity.caption, maxLines = 1,
        modifier = Modifier.border(HAIR, if (on) p.accent else p.hairline).background(if (on) p.surfaceSelected else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = KitDensity.small / 2).testTag(tag))
}

@Composable
private fun TableRow(scroll: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier, header: Boolean = false, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(scroll).let { if (header) it.background(p.surfaceSelected) else it }, verticalAlignment = Alignment.CenterVertically) { content() }
        Box(Modifier.fillMaxWidth().height(HAIR).background(p.hairline))
    }
}

@Composable
private fun Cell(units: Int, content: @Composable () -> Unit) =
    Box(Modifier.width(UNIT * units).padding(horizontal = HAIR * 2), contentAlignment = Alignment.CenterStart) { content() }

@Composable
private fun HCell(text: String, units: Int) {
    val p = LocalKitPalette.current
    Cell(units) { Text(text.uppercase(), color = p.textSecondary, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun HSort(text: String, col: String, units: Int, sort: SortSpec?, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val arrow = if (sort != null && sort.col == col) (if (sort.asc) "\u25b2" else "\u25bc") else "\u2195"
    Cell(units) {
        Text(text.uppercase() + arrow, color = if (sort != null && sort.col == col) p.accent else p.textSecondary, fontSize = KitDensity.caption,
            fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.clickable(onClick = onClick).testTag(tag))
    }
}

@Composable
private fun GroupHeader(h: THeader, tag: String?) {
    val p = LocalKitPalette.current
    val needs = h.title == NEEDS_TITLE
    Text("${h.title} (${h.count})", color = if (needs) p.warn else p.accent, fontSize = KitDensity.caption, fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth().background(p.surface)
            .padding(start = HAIR * 2 + KitDensity.small * h.level)
            .let { if (tag != null) it.testTag(tag) else it })
}

@Composable
private fun Num(text: String, tag: String? = null) {
    val p = LocalKitPalette.current
    Text(text, color = p.textSecondary, fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun Mono(text: String, tag: String?) {
    val p = LocalKitPalette.current
    Text(text, color = p.textSecondary, fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace, maxLines = 1, overflow = TextOverflow.Ellipsis,
        modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
}

@Composable
private fun Action(label: String, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(label, color = p.bad, fontSize = KitDensity.caption, maxLines = 1, modifier = Modifier.clickable(onClick = onClick).padding(horizontal = HAIR * 2).testTag(tag))
}

@Composable
private fun EditCell(value: String, tag: String, mono: Boolean = false, accent: Boolean = false, hint: String = "", enabled: Boolean = true, onChange: (String) -> Unit) {
    val p = LocalKitPalette.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true, enabled = enabled,
        textStyle = TextStyle(color = if (accent) p.accent else p.textPrimary, fontSize = KitDensity.caption,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(p.accent),
        decorationBox = { inner -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = p.textSecondary, fontSize = KitDensity.caption); inner() } },
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** A new folder's id: typed locally and committed on focus loss / Done, because the row is keyed by the id. */
@Composable
private fun EditId(value: String, tag: String, onCommit: (String) -> Unit) {
    val p = LocalKitPalette.current
    var text by remember(value) { mutableStateOf(value) }
    BasicTextField(
        value = text, onValueChange = { text = it }, singleLine = true,
        textStyle = TextStyle(color = p.textPrimary, fontSize = KitDensity.caption, fontFamily = FontFamily.Monospace),
        cursorBrush = SolidColor(p.accent),
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onCommit(text) }),
        modifier = Modifier.fillMaxWidth().testTag(tag).onFocusChanged { if (!it.isFocused && text != value) onCommit(text) },
    )
}

@Composable
private fun EditInt(value: Int, tag: String, enabled: Boolean, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    EditCell(text, tag, mono = true, enabled = enabled) { t -> text = t; t.trim().toIntOrNull()?.let(onChange) }
}

/** Table B's Folder cell: the options are Table A's folders (+ Others); picking one sets the folder and the Parent follows. */
@Composable
private fun FolderDropdown(m: FolderEditModel, pkg: String, label: String, needs: Boolean, enabled: Boolean, onPick: (String) -> Unit) {
    val p = LocalKitPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text("$label \u25be", color = if (needs) p.warn else p.textPrimary, fontSize = KitDensity.caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.border(HAIR, if (needs) p.warn else p.hairline)
                .clickable(enabled = enabled) { open = true }.padding(horizontal = HAIR * 2).testTag(FolderTablesTags.bFolder(pkg)))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (f in m.orderedFolders()) {
                DropdownMenuItem(text = { Text("${f.label}  \u00b7  ${f.section}", fontSize = KitDensity.caption) },
                    modifier = Modifier.testTag(FolderTablesTags.bOpt(pkg, f.id)), onClick = { open = false; onPick(f.id) })
            }
            DropdownMenuItem(text = { Text(OTHERS_FOLDER_LABEL, fontSize = KitDensity.caption) },
                modifier = Modifier.testTag(FolderTablesTags.bOpt(pkg, OTHERS_FOLDER_ID)), onClick = { open = false; onPick(OTHERS_FOLDER_ID) })
        }
    }
}
