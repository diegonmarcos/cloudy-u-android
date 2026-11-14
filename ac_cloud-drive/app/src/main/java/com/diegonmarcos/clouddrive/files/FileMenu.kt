package com.diegonmarcos.clouddrive.files

import java.io.File

/**
 * #1680 ONE grouped menu for the Explorer. The per-item dropdown (Open | Share | Meta | Git) and the
 * selection dropdown (Actions | Share | Meta | Git) are both declared once in build.json
 * (`ui.files.menus.item` / `ui.files.menus.selection`: groups in order, each with its items and
 * labels) and both are drawn by the same composable (FilesScreen.GroupedFileMenu) from [resolve].
 *
 * Rules the owner set:
 *  - every declared item is ALWAYS listed; context only enables or disables it (never silently missing);
 *  - the Git group is listed only inside a git work tree ([MenuGroupDecl.gitOnly]); its rename / move
 *    go through `git mv` ([GitMv]);
 *  - an id this build does not know is listed disabled (a newer build.json never crashes an older app).
 *
 * Pure: FileMenuTest pins the groups, their order, the enablement and parity with the old flat menus.
 */
object FileMenu {

    const val ITEM = "item"
    const val SELECTION = "selection"

    data class MenuItemDecl(val id: String, val label: String)
    data class MenuGroupDecl(val id: String, val label: String, val gitOnly: Boolean, val items: List<MenuItemDecl>)

    data class Item(val id: String, val label: String, val enabled: Boolean)
    data class Group(val id: String, val label: String, val items: List<Item>)

    /** Everything the enablement needs to know about the thing the menu is for. */
    data class Context(
        /** Number of entries the menu acts on (1 for the per-item menu). */
        val count: Int = 1,
        val isDirectory: Boolean = false,
        /** Every entry is a plain local file / folder (not inside an archive, not remote). */
        val isLocal: Boolean = true,
        /** The pane shows the inside of an archive (read-only). */
        val inArchive: Boolean = false,
        /** At least one selected entry is a file (not a folder). */
        val anyFile: Boolean = true,
        /** Every entry sits inside a git work tree ([GitWorkTree]). */
        val underGit: Boolean = false,
        val canEdit: Boolean = false,
        val canEditor: Boolean = false,
        val isPdf: Boolean = false,
        val canUnzip: Boolean = false,
        val canConvert: Boolean = false,
        /** The other pane is open (dual pane) and is a local folder. */
        val otherPaneLocal: Boolean = false,
    )

    /** Ids this build can perform, per menu. Unknown ids resolve disabled. */
    val ITEM_IDS = setOf(
        "open_with", "edit", "editor", "open_other_pane", "unzip", "convert", "convert_pdf", "to_pdf",
        "copy_path", "copy_to_downloads", "copy_to_clipboard", "share",
        "rename", "delete", "properties", "compress", "bookmark",
        "git_versions", "git_rename", "git_move",
    )
    val SELECTION_IDS = setOf(
        "delete_all", "move_all", "copy_all", "copy_to_other_pane", "move_to_other_pane", "zip_all", "tar_all",
        "clear_selection", "invert_selection", "select_all",
        "copy_paths", "copy_to_downloads", "share",
        "properties", "rename",
        "git_move_all", "git_rename",
    )

    fun enabled(menu: String, id: String, c: Context): Boolean {
        val local = c.isLocal && !c.inArchive
        val file = local && !c.isDirectory
        return when (menu) {
            ITEM -> when (id) {
                "open_with" -> file
                "edit" -> c.canEdit
                "editor" -> c.canEditor
                "open_other_pane" -> c.isDirectory
                "unzip" -> c.canUnzip
                "convert" -> c.canConvert
                "convert_pdf" -> c.isPdf
                "to_pdf" -> file
                "copy_path", "properties" -> true
                "copy_to_downloads", "rename", "delete", "compress" -> local
                "copy_to_clipboard", "share" -> file
                "bookmark" -> local && c.isDirectory
                "git_versions", "git_rename", "git_move" -> local && c.underGit
                else -> false
            }
            SELECTION -> when (id) {
                "clear_selection", "invert_selection", "select_all" -> true
                "copy_paths" -> c.count > 0
                "copy_all" -> c.count > 0
                "copy_to_other_pane" -> c.count > 0 && c.otherPaneLocal
                "move_to_other_pane" -> c.count > 0 && c.otherPaneLocal && local
                "delete_all", "move_all", "zip_all", "tar_all", "copy_to_downloads", "properties" -> c.count > 0 && local
                "share" -> c.count > 0 && local && c.anyFile
                "rename" -> c.count > 0 && local
                "git_move_all" -> c.count > 0 && local && c.underGit
                "git_rename" -> c.count == 1 && local && c.underGit
                else -> false
            }
            else -> false
        }
    }

    /** The groups to draw, in declared order. A Git-only group is dropped outside a git work tree; nothing else is ever dropped. */
    fun resolve(menu: String, decl: List<MenuGroupDecl>, c: Context): List<Group> =
        decl.filter { !it.gitOnly || (c.underGit && c.isLocal && !c.inArchive) }
            .map { g -> Group(g.id, g.label, g.items.map { Item(it.id, it.label, enabled(menu, it.id, c)) }) }
            .filter { it.items.isNotEmpty() }

    /** Combined Properties of a selection: entries, files, folders and total bytes (bounded walk; call off the main thread). */
    data class Stats(val entries: Int, val files: Int, val folders: Int, val bytes: Long)

    fun stats(roots: List<File>, cancelled: () -> Boolean = { false }, depthCeiling: Int = 64): Stats {
        var files = 0; var folders = 0; var bytes = 0L
        fun walk(f: File, depth: Int) {
            if (cancelled() || depth <= 0) return
            if (f.isDirectory) { folders++; f.listFiles()?.forEach { walk(it, depth - 1) } }
            else if (f.exists()) { files++; bytes += f.length() }
        }
        roots.forEach { walk(it, depthCeiling) }
        return Stats(roots.size, files, folders, bytes)
    }
}
