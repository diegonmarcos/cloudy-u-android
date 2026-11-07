package com.diegonmarcos.superapp.launcher.folders

import org.json.JSONArray
import org.json.JSONObject

/**
 * The editable two-table model behind Configs ▸ Launcher ▸ Folders (pure; no Android).
 *
 *   Table A  [FolderEditModel.folders]  — the folder structure (Section = the folder's PARENT, id, label, icon, order).
 *   Table B  [FolderEditModel.apps]     — every app's allocation to a folder (+ order).
 *
 * Hierarchy: Section (parent) → Folder → App. An app's [AppRow.folderId] is null when it was never assigned
 * ("No folder"), [OTHERS_FOLDER_ID] when explicitly parked in Others, or a folder id. An id that names no
 * Table A folder (e.g. the folder was deleted) is treated as Others. "No folder" and "Others" are disjoint and
 * together are the needs-classification set. Immutable: every edit returns a new model.
 */
data class AppRow(val pkg: String, val name: String, val folderId: String?, val order: Int = 999)

/** Label for the pinned needs-classification group. */
const val NEEDS_TITLE = "Needs classification"

data class FolderEditModel(
    val folders: List<FolderDef>,
    val apps: List<AppRow>,
    /** Ids added in this session: only these may be renamed (an existing id is what assignments reference). */
    val newFolderIds: Set<String> = emptySet(),
) {
    private val byId get() = folders.associateBy { it.id }

    fun def(id: String?): FolderDef? = if (id == null) null else byId[id]

    /** Never assigned at all. */
    fun isNoFolder(r: AppRow) = r.folderId == null
    /** In the Others bucket: explicitly, or because its folder no longer exists. */
    fun isOthers(r: AppRow) = r.folderId != null && (r.folderId == OTHERS_FOLDER_ID || !byId.containsKey(r.folderId))
    fun needsClassification(r: AppRow) = isNoFolder(r) || isOthers(r)

    val noFolderCount get() = apps.count(::isNoFolder)
    val othersCount get() = apps.count(::isOthers)
    val needsCount get() = noFolderCount + othersCount

    /** Live app count of a Table A folder. */
    fun appCount(folderId: String) = apps.count { it.folderId == folderId }

    /** The parent section shown for a row: its folder's section, "Others" for the bucket, "" when it has no folder. */
    fun sectionOf(r: AppRow): String = when {
        isNoFolder(r) -> ""
        isOthers(r) -> OTHERS_SECTION
        else -> byId.getValue(r.folderId!!).section
    }

    fun folderLabel(r: AppRow): String = when {
        isNoFolder(r) -> "No folder"
        isOthers(r) -> OTHERS_FOLDER_LABEL
        else -> byId.getValue(r.folderId!!).label
    }

    /** Table A's own order: sections by first appearance, then folder order, then id. */
    fun orderedFolders(): List<FolderDef> {
        val idx = LinkedHashMap<String, Int>()
        folders.forEach { idx.putIfAbsent(it.section, idx.size) }
        return folders.sortedWith(compareBy({ idx[it.section] ?: 0 }, { it.order }, { it.id }))
    }

    // ---- Table A edits ----

    fun addFolder(): FolderEditModel {
        var n = 1
        while (folders.any { it.id == "new_folder_$n" }) n++
        val id = "new_folder_$n"
        val def = FolderDef(folders.lastOrNull()?.section ?: "Folders", id, "New folder", "", (folders.maxOfOrNull { it.order } ?: 0) + 10)
        return copy(folders = folders + def, newFolderIds = newFolderIds + id)
    }

    fun updateFolder(id: String, f: (FolderDef) -> FolderDef): FolderEditModel =
        copy(folders = folders.map { if (it.id == id) f(it).copy(id = id) else it })

    /** Rename a folder added this session; the apps pointing at it follow. Rejected (unchanged) when blank, taken or reserved. */
    fun renameFolderId(old: String, new: String): FolderEditModel {
        val n = new.trim()
        if (old !in newFolderIds || n.isEmpty() || n == OTHERS_FOLDER_ID || folders.any { it.id == n && n != old }) return this
        return copy(
            folders = folders.map { if (it.id == old) it.copy(id = n) else it },
            apps = apps.map { if (it.folderId == old) it.copy(folderId = n) else it },
            newFolderIds = newFolderIds - old + n,
        )
    }

    /** Delete a folder; its apps fall to Others. */
    fun deleteFolder(id: String): FolderEditModel = copy(
        folders = folders.filterNot { it.id == id },
        apps = apps.map { if (it.folderId == id) it.copy(folderId = OTHERS_FOLDER_ID) else it },
        newFolderIds = newFolderIds - id,
    )

    // ---- Table B edits ----

    fun setFolder(pkg: String, folderId: String?): FolderEditModel =
        copy(apps = apps.map { if (it.pkg == pkg) it.copy(folderId = folderId) else it })

    fun setOrder(pkg: String, order: Int): FolderEditModel =
        copy(apps = apps.map { if (it.pkg == pkg) it.copy(order = order) else it })

    /** Add (or re-target) an app row by package. */
    fun addApp(pkg: String, name: String, folderId: String?): FolderEditModel {
        val p = pkg.trim()
        if (p.isEmpty()) return this
        if (apps.any { it.pkg == p }) return setFolder(p, folderId)
        return copy(apps = apps + AppRow(p, name.trim().ifEmpty { p.substringAfterLast('.') }, folderId))
    }

    // ---- views ----

    /** The assignments to persist: every app that has a folder. */
    fun toAssignments(): List<FolderAssignment> = apps.filter { it.folderId != null }.map { FolderAssignment(it.pkg, it.folderId!!, it.order) }

    /** The live grid preview: exactly what the launcher resolves from these two tables (empty folders hidden). */
    fun preview(): List<ResolvedSection> =
        FolderTables.resolve(folders, toAssignments(), emptyMap(), apps.map { FolderApp(it.pkg, it.name) })

    companion object {
        /** Build from Table A, Table B and the enumerated apps: enumerated apps first, then assigned-but-absent packages. */
        fun build(folders: List<FolderDef>, assignments: List<FolderAssignment>, apps: List<FolderApp>): FolderEditModel {
            val asg = assignments.associateBy { it.pkg }
            val rows = LinkedHashMap<String, AppRow>()
            for (a in apps) rows.putIfAbsent(a.pkg, AppRow(a.pkg, a.label.ifBlank { a.pkg.substringAfterLast('.') }, asg[a.pkg]?.folderId, asg[a.pkg]?.order ?: 999))
            for (a in assignments) rows.putIfAbsent(a.pkg, AppRow(a.pkg, a.pkg.substringAfterLast('.'), a.folderId, a.order))
            return FolderEditModel(folders, rows.values.toList())
        }
    }
}

// ---- table views: filter / sort / group ----

/** One displayed line of a table: a group header or a data row (numbered). */
sealed interface TItem
data class THeader(val key: String, val title: String, val level: Int, val count: Int) : TItem
data class FolderItem(val def: FolderDef, val n: Int) : TItem
data class AppItem(val row: AppRow, val n: Int) : TItem

data class SortSpec(val col: String, val asc: Boolean = true) {
    /** Header click: same column flips direction, a new column starts ascending. */
    fun toggled(c: String) = if (col == c) copy(asc = !asc) else SortSpec(c)
}

object ACol { const val SECTION = "section"; const val ID = "id"; const val LABEL = "label"; const val ORDER = "order" }
object BCol { const val APP = "app"; const val PACKAGE = "package"; const val FOLDER = "folder"; const val ORDER = "order" }

enum class AppGroup { FOLDER, PARENT, NONE }
enum class AppFilter { ALL, NEEDS, NO_FOLDER, OTHERS }

private fun <T> List<T>.sortedBy2(sort: SortSpec?, key: (T, String) -> Comparable<*>): List<T> {
    if (sort == null) return this
    @Suppress("UNCHECKED_CAST")
    val cmp = compareBy<T> { key(it, sort.col) as Comparable<Any> }
    return sortedWith(if (sort.asc) cmp else cmp.reversed())
}

/** Table A lines: filtered by [query], sorted by [sort] (null = Table A's own order), optionally grouped by Section (parent). */
fun FolderEditModel.folderItems(query: String, sort: SortSpec?, groupBySection: Boolean): List<TItem> {
    val q = query.trim().lowercase()
    val rows = orderedFolders()
        .filter { q.isEmpty() || listOf(it.section, it.id, it.label, it.icon).any { s -> s.lowercase().contains(q) } }
        .sortedBy2(sort) { f, c -> when (c) { ACol.SECTION -> f.section.lowercase(); ACol.ID -> f.id.lowercase(); ACol.LABEL -> f.label.lowercase(); else -> f.order } }
    val out = ArrayList<TItem>()
    var n = 0
    if (groupBySection) {
        for ((section, list) in rows.groupBy { it.section }) {
            out += THeader("a:$section", section, 0, list.size)
            for (f in list) out += FolderItem(f, ++n)
        }
    } else for (f in rows) out += FolderItem(f, ++n)
    return out
}

/**
 * Table B lines. Grouped views pin the needs-classification set (No folder + Others) first, then the real folders in
 * Table A's order ([AppGroup.FOLDER]) or Parent → Folder ([AppGroup.PARENT]); [AppGroup.NONE] is a flat sortable list.
 */
fun FolderEditModel.appItems(query: String, filter: AppFilter, sort: SortSpec?, group: AppGroup): List<TItem> {
    val q = query.trim().lowercase()
    val rows = apps
        .filter {
            when (filter) {
                AppFilter.ALL -> true
                AppFilter.NEEDS -> needsClassification(it)
                AppFilter.NO_FOLDER -> isNoFolder(it)
                AppFilter.OTHERS -> isOthers(it)
            }
        }
        .filter { q.isEmpty() || listOf(it.name, it.pkg, folderLabel(it), sectionOf(it)).any { s -> s.lowercase().contains(q) } }
        .let { l ->
            if (sort != null) l.sortedBy2(sort) { r, c ->
                when (c) { BCol.APP -> r.name.lowercase(); BCol.PACKAGE -> r.pkg; BCol.FOLDER -> folderLabel(r).lowercase(); else -> r.order }
            } else l.sortedWith(compareBy<AppRow>({ if (group == AppGroup.NONE) 0 else it.order }, { it.name.lowercase() }))
        }
    val out = ArrayList<TItem>()
    var n = 0
    if (group == AppGroup.NONE) { for (r in rows) out += AppItem(r, ++n); return out }
    val needs = rows.filter(::needsClassification)
    if (needs.isNotEmpty()) { out += THeader("b:needs", NEEDS_TITLE, 0, needs.size); for (r in needs) out += AppItem(r, ++n) }
    val ordered = orderedFolders()
    if (group == AppGroup.FOLDER) {
        for (f in ordered) {
            val list = rows.filter { it.folderId == f.id }
            if (list.isEmpty()) continue
            out += THeader("b:f:${f.id}", f.label, 0, list.size)
            for (r in list) out += AppItem(r, ++n)
        }
    } else {
        for (section in ordered.map { it.section }.distinct()) {
            val inSection = ordered.filter { it.section == section }.map { f -> f to rows.filter { it.folderId == f.id } }.filter { it.second.isNotEmpty() }
            if (inSection.isEmpty()) continue
            out += THeader("b:s:$section", section, 0, inSection.sumOf { it.second.size })
            for ((f, list) in inSection) {
                out += THeader("b:f:${f.id}", f.label, 1, list.size)
                for (r in list) out += AppItem(r, ++n)
            }
        }
    }
    return out
}

// ---- persistence format (the same shape the served datasets use, so FolderTables.parse* reads it back) ----

fun FolderTables.foldersToJson(list: List<FolderDef>): String = JSONObject().put("folders", JSONArray().also { a ->
    list.forEach { a.put(JSONObject().put("section", it.section).put("id", it.id).put("label", it.label).put("icon", it.icon).put("order", it.order)) }
}).toString()

fun FolderTables.assignmentsToJson(list: List<FolderAssignment>): String = JSONObject().put("assignments", JSONArray().also { a ->
    list.forEach { a.put(JSONObject().put("package", it.pkg).put("folder", it.folderId).put("order", it.order)) }
}).toString()
