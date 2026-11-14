package com.diegonmarcos.clouddrive.files

import com.diegonmarcos.clouddrive.Declarations
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1680 the ONE grouped Explorer menu, read from the real build.json (ui.files.menus): the per-item
 * menu is Open | Share | Meta | Git, the selection menu Actions | Share | Meta | Git, both resolved by
 * the same [FileMenu.resolve] (and drawn by the same FilesScreen.GroupedFileMenu). Items are never
 * silently missing; the Git group exists only inside a git work tree; rule 16 parity with the old
 * flat menus (EntryMenu, the selection bar's previous list and its pills).
 */
class FileMenuTest {
    @get:Rule val tmp = TemporaryFolder()

    private val root = File(System.getProperty("user.dir") ?: ".").let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val files = Declarations.parseFiles(
        Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["files"].toString(),
    )
    private val itemDecl = files.menu(FileMenu.ITEM)
    private val selDecl = files.menu(FileMenu.SELECTION)

    private fun ids(groups: List<FileMenu.Group>) = groups.flatMap { g -> g.items.map { it.id } }
    private fun on(groups: List<FileMenu.Group>) = groups.flatMap { g -> g.items.filter { it.enabled }.map { it.id } }.toSet()

    @Test fun itemMenuGroupsInOrder() {
        val g = FileMenu.resolve(FileMenu.ITEM, itemDecl, FileMenu.Context(underGit = true))
        assertEquals(listOf("open", "convert", "share", "meta", "git"), g.map { it.id })
        assertEquals(listOf("Open", "Convert", "Share", "Meta", "Git"), g.map { it.label })
        val share = g.first { it.id == "share" }.items.map { it.id }
        assertEquals(listOf("copy_path", "copy_to_downloads", "copy_to_clipboard", "share"), share)
        val meta = g.first { it.id == "meta" }.items.map { it.id }
        assertTrue(meta.containsAll(listOf("rename", "delete", "properties")))
        // #1740 section move: Open keeps only opening actions; unzip / convert / PDF / zip live in Convert, none lost.
        assertEquals(listOf("editor", "edit", "open_with", "open_other_pane"), g.first { it.id == "open" }.items.map { it.id })
        assertEquals(listOf("unzip", "compress", "convert", "to_pdf", "convert_pdf"), g.first { it.id == "convert" }.items.map { it.id })
        assertFalse("compress" in meta)
        assertTrue(g.first { it.id == "open" }.items.map { it.id }.containsAll(listOf("editor", "open_with")))
        assertEquals(listOf("git_rename", "git_move", "git_versions"), g.first { it.id == "git" }.items.map { it.id })
    }

    @Test fun selectionMenuGroupsInOrder() {
        val g = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2, underGit = true, otherPaneLocal = true))
        assertEquals(listOf("actions", "share", "meta", "git"), g.map { it.id })
        assertTrue(g.first { it.id == "actions" }.items.map { it.id }.containsAll(listOf("delete_all", "move_all", "copy_all", "zip_all", "tar_all", "clear_selection", "invert_selection", "select_all")))
        assertEquals(listOf("copy_paths", "copy_to_downloads", "share"), g.first { it.id == "share" }.items.map { it.id })
        assertTrue("properties" in g.first { it.id == "meta" }.items.map { it.id })
        assertEquals(listOf("git_move_all", "git_rename"), g.first { it.id == "git" }.items.map { it.id })
    }

    @Test fun bothMenusUseTheSameShapeAndResolver() {
        // Same component: same group type, same Share/Meta/Git group ids, both from one declaration block.
        val a = FileMenu.resolve(FileMenu.ITEM, itemDecl, FileMenu.Context(underGit = true)).map { it.id }
        val b = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 1, underGit = true)).map { it.id }
        assertEquals(a.drop(2), b.drop(1))
        assertEquals(setOf(FileMenu.ITEM, FileMenu.SELECTION), files.menus.keys)
    }

    @Test fun everyDeclaredIdIsKnown() {
        itemDecl.flatMap { it.items }.forEach { assertTrue("item ${it.id}", it.id in FileMenu.ITEM_IDS) }
        selDecl.flatMap { it.items }.forEach { assertTrue("selection ${it.id}", it.id in FileMenu.SELECTION_IDS) }
        assertTrue((itemDecl + selDecl).flatMap { it.items }.all { it.label.isNotBlank() })
    }

    @Test fun itemsAreDisabledNotMissing() {
        val folder = FileMenu.resolve(FileMenu.ITEM, itemDecl, FileMenu.Context(isDirectory = true, anyFile = false))
        val file = FileMenu.resolve(FileMenu.ITEM, itemDecl, FileMenu.Context(canEditor = true))
        assertEquals(ids(file).filterNot { it.startsWith("git_") }, ids(folder))
        assertFalse("copy_to_clipboard" in on(folder)); assertTrue("copy_to_clipboard" in on(file))
        assertFalse("share" in on(folder))
        assertTrue("compress" in on(folder) && "copy_to_downloads" in on(folder))
        val arch = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2, inArchive = true, isLocal = false))
        assertEquals(ids(FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2))), ids(arch))
        assertEquals(setOf("clear_selection", "invert_selection", "select_all", "copy_all", "copy_paths"), on(arch))
        assertFalse(FileMenu.enabled(FileMenu.ITEM, "no_such_action", FileMenu.Context()))
    }

    @Test fun gitGroupOnlyInsideAGitWorkTree() {
        assertTrue(FileMenu.resolve(FileMenu.ITEM, itemDecl, FileMenu.Context(underGit = false)).none { it.id == "git" })
        assertTrue(FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 3, underGit = false)).none { it.id == "git" })
        assertTrue(FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 3, underGit = true, inArchive = true)).none { it.id == "git" })
        val one = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 1, underGit = true))
        val many = FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 3, underGit = true))
        assertTrue("git_rename" in on(one)); assertFalse("git_rename" in on(many)); assertTrue("git_move_all" in on(many))
        // rename / move go through git mv: the planner stages old -> new inside the one work tree.
        val repo = tmp.newFolder("r").also { File(it, ".git").mkdir(); File(it, "d").mkdir(); File(it, "x.txt").writeText("x") }
        assertTrue(GitWorkTree.isUnderGit(File(repo, "x.txt").path))
        assertEquals("d/x.txt", GitMv.planMove(File(repo, "x.txt"), File(repo, "d")).newRel)
        assertEquals("y.txt", GitMv.planRename(File(repo, "x.txt"), "y.txt").newRel)
    }

    /** Rule 16: every item the previous flat menus offered is still there, now in a group. */
    @Test fun parityWithThePreviousMenus() {
        val sel = ids(FileMenu.resolve(FileMenu.SELECTION, selDecl, FileMenu.Context(count = 2, underGit = true, otherPaneLocal = true)))
        // previous selection overflow: Delete, Rename, Zip, Share, Move All…, Git Move All, Properties, Copy path, Select all, Invert, Clear
        // previous bar pills: Copy → other pane, Move → other pane, Copy to…, Move to…
        listOf("delete_all", "rename", "zip_all", "share", "move_all", "git_move_all", "properties", "copy_paths",
            "select_all", "invert_selection", "clear_selection", "copy_to_other_pane", "move_to_other_pane", "copy_all").forEach { assertTrue("selection lost $it", it in sel) }
        // previous per-item menu (EntryMenu): every action maps onto a declared item id.
        val item = ids(FileMenu.resolve(FileMenu.ITEM, itemDecl, FileMenu.Context(underGit = true))).toSet()
        val old = EntryMenu.Action.values().map { if (it == EntryMenu.Action.GIT_OPEN) "git_versions" else it.name.lowercase() }
        old.forEach { assertTrue("item lost $it", it in item) }
        // and the old SelectionMenu data is covered too
        SelectionMenu.Action.values().forEach { assertTrue("selection lost $it", it.name.lowercase() in sel) }
    }

    @Test fun combinedPropertiesCountAndSize() {
        val d = tmp.newFolder("p").also { File(it, "a").writeText("12345"); File(it, "s").mkdir(); File(it, "s/b").writeText("123") }
        val f = File(tmp.root, "c").also { it.writeText("12") }
        val s = FileMenu.stats(listOf(d, f, File(tmp.root, "missing")))
        assertEquals(3, s.entries); assertEquals(3, s.files); assertEquals(2, s.folders); assertEquals(10L, s.bytes)
    }

    @Test fun compressZipAndTarGzForASelection() {
        val dir = tmp.newFolder("z").also { File(it, "a.txt").writeText("aaa"); File(it, "b.txt").writeText("bb") }
        val srcs = listOf(File(dir, "a.txt"), File(dir, "b.txt"))
        val zip = File(dir, "all.zip"); val tgz = File(dir, "all.tar.gz")
        FileOps.archive(srcs, zip, FileOps.Progress { _, _, _ -> }) { false }
        FileOps.archiveTarGz(srcs, tgz, FileOps.Progress { _, _, _ -> }) { false }
        assertTrue(zip.length() > 0 && tgz.length() > 0)
        assertEquals(ArchiveExtractor.Kind.ZIP, ArchiveExtractor.kindOf(zip.name))
        assertEquals(ArchiveExtractor.Kind.TAR_GZ, ArchiveExtractor.kindOf(tgz.name))
    }

    /** #1740 field parity against the previous (pre-Convert) item menu: every id it declared is still declared. */
    @Test fun convertSectionMoveParity() {
        val before = listOf("editor", "edit", "open_with", "open_other_pane", "unzip", "convert", "convert_pdf",
            "copy_path", "copy_to_downloads", "copy_to_clipboard", "share", "rename", "delete", "properties", "compress", "bookmark",
            "git_rename", "git_move", "git_versions")
        val now = itemDecl.flatMap { it.items.map { i -> i.id } }
        before.forEach { assertTrue("lost $it", it in now) }
        assertEquals(before.size + 1, now.size) // plus to_pdf only
    }
}
