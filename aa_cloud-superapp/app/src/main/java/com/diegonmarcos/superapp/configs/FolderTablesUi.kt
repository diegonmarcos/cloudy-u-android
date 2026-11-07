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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.diegonmarcos.superapp.launcher.folders.ACol
import com.diegonmarcos.superapp.launcher.folders.AppFilter
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
import com.diegonmarcos.superapp.uikit.KitButton
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitOutlinedButton
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/** The editor's UI state: the model plus every view setting (query, sort, group, filter). Hoisted so tests can drive it. */
class FolderEditorState(initial: FolderEditModel) {
    var model by mutableStateOf(initial)
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

    /** Apply an edit to the model and mark it unsaved. */
    fun edit(f: (FolderEditModel) -> FolderEditModel) { model = f(model); dirty = true }

    /** Delete a Table A folder; its apps fall to Others and the page says so. */
    fun deleteFolder(id: String) {
        val moved = model.appCount(id)
        val label = model.def(id)?.label ?: id
        edit { it.deleteFolder(id) }
        notice = "Deleted \"$label\": $moved app(s) moved to Others."
    }
}

object FolderTablesTags {
    const val SAVE = "folders:save"
    const val RESET = "folders:reset"
    const val NOTICE = "folders:notice"
    const val A_FILTER = "fa:filter"
    const val A_GROUP = "fa:group"
    const val A_ADD = "fa:add"
    const val B_FILTER = "fb:filter"
    const val B_NEEDS = "fb:needs"
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
    fun bDel(pkg: String) = "fb:del:$pkg"
    fun bSort(col: String) = "fb:sort:$col"
    fun bChip(f: AppFilter) = "fb:chip:${f.name.lowercase()}"
    fun bGroup(g: AppGroup) = "fb:group:${g.name.lowercase()}"
    fun bHeader(key: String) = "fb:header:$key"
    fun tile(fid: String) = "fp:tile:$fid"
}

private val ACTIONS_W: Dp get() = KitDensity.glyph * 2

/**
 * Table A (folder structure), Table B (app allocation) and the read-only live grid preview, in one scrolling page.
 * Edits go to [state]; [onSave] persists them as the on-device override, [onReset] reverts to the served defaults.
 * The shipped defaults (data/fleet/launcher-folders|launcher-folder-assignments) are a repo edit, not edited here.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FolderTablesScreen(state: FolderEditorState, onSave: () -> Unit, onReset: () -> Unit, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    val m = state.model
    val aItems = m.folderItems(state.aQuery, state.aSort, state.aGroupSection)
    val bItems = m.appItems(state.bQuery, state.bFilter, state.bSort, state.bGroup)
    val aScroll = rememberScrollState()
    val bScroll = rememberScrollState()
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        item(key = "bar") {
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically) {
                KitButton(onClick = onSave, enabled = state.dirty, modifier = Modifier.testTag(FolderTablesTags.SAVE)) { Text("Save") }
                KitOutlinedButton(onClick = onReset, modifier = Modifier.testTag(FolderTablesTags.RESET)) { Text("Reset") }
                Text(if (state.dirty) "Unsaved edits" else "Saved", color = if (state.dirty) p.warn else p.textSecondary, fontSize = KitDensity.caption)
            }
        }
        state.notice?.let { n ->
            item(key = "notice") {
                Text(n, color = p.warn, fontSize = KitDensity.body, modifier = Modifier.fillMaxWidth()
                    .border(KitDensity.rule, p.warn, RoundedCornerShape(KitDensity.corner)).padding(KitDensity.small)
                    .clickable { state.notice = null }.testTag(FolderTablesTags.NOTICE))
            }
        }

        // ---------------- Table A ----------------
        item(key = "a-title") { TableBar("Table A - Folder structure", state.aQuery, { state.aQuery = it }, FolderTablesTags.A_FILTER, "Filter folders") {
            FilterPill(if (state.aGroupSection) "Group: Parent (Section)" else "Group: off", FolderTablesTags.A_GROUP, state.aGroupSection) { state.aGroupSection = !state.aGroupSection }
            FilterPill("+ Add folder", FolderTablesTags.A_ADD, false) { state.edit { it.addFolder() } }
        } }
        item(key = "a-head") {
            TableRow(aScroll) {
                HCell("#", 1)
                HSort("Parent (Section)", ACol.SECTION, 4, state.aSort, FolderTablesTags.aSort(ACol.SECTION)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.SECTION) }
                HSort("Folder ID", ACol.ID, 3, state.aSort, FolderTablesTags.aSort(ACol.ID)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.ID) }
                HSort("Label", ACol.LABEL, 3, state.aSort, FolderTablesTags.aSort(ACol.LABEL)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.LABEL) }
                HCell("Icon", 4)
                HSort("Order", ACol.ORDER, 2, state.aSort, FolderTablesTags.aSort(ACol.ORDER)) { state.aSort = (state.aSort ?: SortSpec("")).toggled(ACol.ORDER) }
                HCell("Apps", 1)
                Box(Modifier.width(ACTIONS_W))
            }
        }
        items(aItems, key = { if (it is THeader) it.key else "a:${(it as FolderItem).def.id}" }) { item ->
            when (item) {
                is THeader -> GroupHeader(item, null)
                is FolderItem -> {
                    val f = item.def
                    val isNew = f.id in m.newFolderIds
                    TableRow(aScroll, Modifier.testTag(FolderTablesTags.aRow(f.id))) {
                        Cell(1) { Num(item.n.toString()) }
                        Cell(4) { EditCell(f.section, FolderTablesTags.aCell("section", f.id), accent = true) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(section = v) } } } }
                        Cell(3) {
                            if (isNew) EditId(f.id, FolderTablesTags.aCell("id", f.id)) { v -> state.edit { it.renameFolderId(f.id, v) } }
                            else Mono(f.id, FolderTablesTags.aCell("id", f.id))
                        }
                        Cell(3) { EditCell(f.label, FolderTablesTags.aCell("label", f.id)) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(label = v) } } } }
                        Cell(4) { EditCell(f.icon, FolderTablesTags.aCell("icon", f.id), mono = true) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(icon = v) } } } }
                        Cell(2) { EditInt(f.order, FolderTablesTags.aCell("order", f.id)) { v -> state.edit { it.updateFolder(f.id) { d -> d.copy(order = v) } } } }
                        Cell(1) { Num(m.appCount(f.id).toString(), FolderTablesTags.aCount(f.id)) }
                        Box(Modifier.width(ACTIONS_W)) { Action("Delete", FolderTablesTags.aDel(f.id)) { state.deleteFolder(f.id) } }
                    }
                }
                else -> Unit
            }
        }

        // ---------------- Table B ----------------
        item(key = "b-title") {
            Column(verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                TableBar("Table B - App allocation", state.bQuery, { state.bQuery = it }, FolderTablesTags.B_FILTER, "Filter apps") {
                    FilterPill("Group: Folder", FolderTablesTags.bGroup(AppGroup.FOLDER), state.bGroup == AppGroup.FOLDER) { state.bGroup = AppGroup.FOLDER }
                    FilterPill("Group: Parent", FolderTablesTags.bGroup(AppGroup.PARENT), state.bGroup == AppGroup.PARENT) { state.bGroup = AppGroup.PARENT }
                    FilterPill("Flat", FolderTablesTags.bGroup(AppGroup.NONE), state.bGroup == AppGroup.NONE) { state.bGroup = AppGroup.NONE }
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                    val needs = m.needsCount
                    Text("$NEEDS_TITLE ($needs)", color = if (needs > 0) p.warn else p.textSecondary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner))
                            .border(KitDensity.rule, if (needs > 0) p.warn else p.hairline, RoundedCornerShape(KitDensity.corner))
                            .background(if (state.bFilter == AppFilter.NEEDS) p.surfaceSelected else Color.Transparent)
                            .clickable { state.bFilter = if (state.bFilter == AppFilter.NEEDS) AppFilter.ALL else AppFilter.NEEDS }
                            .padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV).testTag(FolderTablesTags.B_NEEDS))
                    FilterPill("No folder (${m.noFolderCount})", FolderTablesTags.bChip(AppFilter.NO_FOLDER), state.bFilter == AppFilter.NO_FOLDER) {
                        state.bFilter = if (state.bFilter == AppFilter.NO_FOLDER) AppFilter.ALL else AppFilter.NO_FOLDER }
                    FilterPill("Others (${m.othersCount})", FolderTablesTags.bChip(AppFilter.OTHERS), state.bFilter == AppFilter.OTHERS) {
                        state.bFilter = if (state.bFilter == AppFilter.OTHERS) AppFilter.ALL else AppFilter.OTHERS }
                }
            }
        }
        item(key = "b-head") {
            TableRow(bScroll) {
                HCell("#", 1)
                HSort("App", BCol.APP, 3, state.bSort, FolderTablesTags.bSort(BCol.APP)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.APP) }
                HSort("Package", BCol.PACKAGE, 6, state.bSort, FolderTablesTags.bSort(BCol.PACKAGE)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.PACKAGE) }
                HSort("Folder", BCol.FOLDER, 4, state.bSort, FolderTablesTags.bSort(BCol.FOLDER)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.FOLDER) }
                HCell("Parent (Section)", 4)
                HSort("Order", BCol.ORDER, 2, state.bSort, FolderTablesTags.bSort(BCol.ORDER)) { state.bSort = (state.bSort ?: SortSpec("")).toggled(BCol.ORDER) }
                Box(Modifier.width(ACTIONS_W))
            }
        }
        items(bItems, key = { if (it is THeader) it.key else "b:${(it as AppItem).row.pkg}" }) { item ->
            when (item) {
                is THeader -> GroupHeader(item, FolderTablesTags.bHeader(item.key))
                is AppItem -> {
                    val r = item.row
                    val needs = m.needsClassification(r)
                    TableRow(bScroll, Modifier.testTag(FolderTablesTags.bRow(r.pkg))) {
                        Cell(1) { Num(item.n.toString()) }
                        Cell(3) { Text(r.name, color = p.textPrimary, fontSize = KitDensity.body, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        Cell(6) { Mono(r.pkg, null) }
                        Cell(4) { FolderDropdown(m, r.pkg, m.folderLabel(r), needs) { fid -> state.edit { it.setFolder(r.pkg, fid) } } }
                        Cell(4) { Text(m.sectionOf(r).ifEmpty { "-" }, color = p.accent, fontSize = KitDensity.body, fontWeight = FontWeight.SemiBold, maxLines = 1,
                            modifier = Modifier.testTag(FolderTablesTags.bSection(r.pkg))) }
                        Cell(2) { EditInt(r.order, FolderTablesTags.bOrder(r.pkg)) { v -> state.edit { it.setOrder(r.pkg, v) } } }
                        Box(Modifier.width(ACTIONS_W)) { Action("Unassign", FolderTablesTags.bDel(r.pkg)) { state.edit { it.setFolder(r.pkg, null) } } }
                    }
                }
                else -> Unit
            }
        }
        item(key = "b-add") {
            var pkg by remember { mutableStateOf("") }
            var name by remember { mutableStateOf("") }
            Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.width(KitDensity.glyph * 6)) { EditCell(pkg, FolderTablesTags.B_ADD_PKG, mono = true, hint = "package") { pkg = it } }
                Box(Modifier.width(KitDensity.glyph * 3)) { EditCell(name, FolderTablesTags.B_ADD_NAME, hint = "app name") { name = it } }
                FilterPill("+ Add app", FolderTablesTags.B_ADD, false) {
                    if (pkg.isNotBlank()) { state.edit { it.addApp(pkg, name, null) }; pkg = ""; name = "" }
                }
            }
        }

        // ---------------- Live grid preview (read-only mirror) ----------------
        item(key = "p-title") {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                Text("Live grid preview", color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                FilterPill(if (state.previewOpen) "Hide" else "Show", FolderTablesTags.PREVIEW_TOGGLE, false) { state.previewOpen = !state.previewOpen }
            }
        }
        if (state.previewOpen) {
            for (section in m.preview()) {
                item(key = "ps:${section.title}") {
                    Column(verticalArrangement = Arrangement.spacedBy(KitDensity.small / 2)) {
                        Text(section.title.uppercase(), color = p.textSecondary, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                            for (f in section.folders) {
                                Row(Modifier.clip(RoundedCornerShape(KitDensity.corner)).border(KitDensity.rule, p.hairline, RoundedCornerShape(KitDensity.corner))
                                    .background(p.surface).padding(KitDensity.small).testTag(FolderTablesTags.tile(f.def.id)),
                                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(KitDensity.small)) {
                                    Box(Modifier.size(KitDensity.dot * 2).clip(RoundedCornerShape(KitDensity.dot / 2)).background(p.surfaceSelected))
                                    Text("${f.def.label} · ${f.apps.size}", color = p.textPrimary, fontSize = KitDensity.body, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }
        }
        item(key = "foot") {
            Text("Save writes the on-device override (this scope only); Reset reverts to the served defaults. The shipped defaults " +
                "(data/fleet/launcher-folders and launcher-folder-assignments) are changed by a repo edit.",
                color = p.textSecondary, fontSize = KitDensity.caption)
        }
    }
}

// ---------------- building blocks ----------------

@Composable
private fun TableBar(title: String, query: String, onQuery: (String) -> Unit, tag: String, hint: String, actions: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(KitDensity.small)) {
        Text(title, color = p.textPrimary, fontSize = KitDensity.title, fontWeight = FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(KitDensity.small), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(KitDensity.glyph * 5).border(KitDensity.rule, p.hairline, RoundedCornerShape(KitDensity.corner))
                .padding(horizontal = KitDensity.small, vertical = KitDensity.rowPadV)) { EditCell(query, tag, hint = hint, onChange = onQuery) }
            actions()
        }
    }
}

@Composable
private fun FilterPill(label: String, tag: String, on: Boolean, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    Text(label, color = if (on) p.accent else p.textSecondary, fontSize = KitDensity.body,
        modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner))
            .border(KitDensity.rule, if (on) p.accent else p.hairline, RoundedCornerShape(KitDensity.corner))
            .background(if (on) p.surfaceSelected else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = KitDensity.medium, vertical = KitDensity.rowPadV).testTag(tag))
}

@Composable
private fun TableRow(scroll: androidx.compose.foundation.ScrollState, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Row(modifier.fillMaxWidth().horizontalScroll(scroll).border(KitDensity.rule, p.hairline).padding(vertical = KitDensity.rowPadV / 2),
        verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun Cell(units: Int, content: @Composable () -> Unit) =
    Box(Modifier.width(KitDensity.glyph * units).padding(horizontal = KitDensity.small / 2), contentAlignment = Alignment.CenterStart) { content() }

@Composable
private fun HCell(text: String, units: Int) {
    val p = LocalKitPalette.current
    Cell(units) { Text(text.uppercase(), color = p.textSecondary, fontSize = KitDensity.caption, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun HSort(text: String, col: String, units: Int, sort: SortSpec?, tag: String, onClick: () -> Unit) {
    val p = LocalKitPalette.current
    val arrow = if (sort != null && sort.col == col) (if (sort.asc) " ▲" else " ▼") else " ↕"
    Cell(units) {
        Text(text.uppercase() + arrow, color = if (sort != null && sort.col == col) p.accent else p.textSecondary, fontSize = KitDensity.caption,
            fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.clickable(onClick = onClick).testTag(tag))
    }
}

@Composable
private fun GroupHeader(h: THeader, tag: String?) {
    val p = LocalKitPalette.current
    val needs = h.title == NEEDS_TITLE
    Text("${h.title} (${h.count})", color = if (needs) p.warn else p.accent, fontSize = KitDensity.body, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.fillMaxWidth().background(p.surfaceSelected)
            .padding(start = KitDensity.small + KitDensity.medium * h.level, top = KitDensity.small / 2, bottom = KitDensity.small / 2)
            .let { if (tag != null) it.testTag(tag) else it })
}

@Composable
private fun Num(text: String, tag: String? = null) {
    val p = LocalKitPalette.current
    Text(text, color = p.textSecondary, fontSize = KitDensity.body, modifier = if (tag != null) Modifier.testTag(tag) else Modifier)
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
    Text(label, color = p.bad, fontSize = KitDensity.caption, modifier = Modifier.clickable(onClick = onClick).padding(KitDensity.small / 2).testTag(tag))
}

@Composable
private fun EditCell(value: String, tag: String, mono: Boolean = false, accent: Boolean = false, hint: String = "", onChange: (String) -> Unit) {
    val p = LocalKitPalette.current
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = TextStyle(color = if (accent) p.accent else p.textPrimary, fontSize = if (mono) KitDensity.caption else KitDensity.body,
            fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default),
        cursorBrush = SolidColor(p.accent),
        decorationBox = { inner -> Box { if (value.isEmpty() && hint.isNotEmpty()) Text(hint, color = p.textSecondary, fontSize = KitDensity.body); inner() } },
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
private fun EditInt(value: Int, tag: String, onChange: (Int) -> Unit) {
    var text by remember(value) { mutableStateOf(value.toString()) }
    EditCell(text, tag, mono = true) { t -> text = t; t.trim().toIntOrNull()?.let(onChange) }
}

/** Table B's Folder cell: the options are Table A's folders (+ Others); picking one sets the folder and the Parent follows. */
@Composable
private fun FolderDropdown(m: FolderEditModel, pkg: String, label: String, needs: Boolean, onPick: (String) -> Unit) {
    val p = LocalKitPalette.current
    var open by remember { mutableStateOf(false) }
    Box {
        Text("$label ▾", color = if (needs) p.warn else p.textPrimary, fontSize = KitDensity.body, maxLines = 1,
            modifier = Modifier.clip(RoundedCornerShape(KitDensity.corner))
                .border(KitDensity.rule, if (needs) p.warn else p.hairline, RoundedCornerShape(KitDensity.corner))
                .clickable { open = true }.padding(horizontal = KitDensity.small, vertical = KitDensity.small / 2).testTag(FolderTablesTags.bFolder(pkg)))
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            for (f in m.orderedFolders()) {
                DropdownMenuItem(text = { Text("${f.label}  ·  ${f.section}", fontSize = KitDensity.body) },
                    modifier = Modifier.testTag(FolderTablesTags.bOpt(pkg, f.id)), onClick = { open = false; onPick(f.id) })
            }
            DropdownMenuItem(text = { Text(OTHERS_FOLDER_LABEL, fontSize = KitDensity.body) },
                modifier = Modifier.testTag(FolderTablesTags.bOpt(pkg, OTHERS_FOLDER_ID)), onClick = { open = false; onPick(OTHERS_FOLDER_ID) })
        }
    }
}
