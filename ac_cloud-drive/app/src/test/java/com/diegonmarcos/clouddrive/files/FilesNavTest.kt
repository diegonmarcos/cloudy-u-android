package com.diegonmarcos.clouddrive.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1696 Explorer navigation for a non-root app: Up guarded by access, Home, restored-path recovery, root crumbs. */
class FilesNavTest {
    private val home = Location.Local("/storage/emulated/0")
    private val roots = listOf("/storage/emulated/0", "/storage/emulated/0/Download", "/storage/ABCD-1234", "/data/user/0/app/files")
    // What a non-root app can list: inside its roots only (/storage/emulated, /storage and / are not).
    private val canList: (String) -> Boolean = { p -> FilesNav.rootOf(p, roots) != null }

    private fun state(loc: Location) = FilesUiState.initial(loc, home, "name", false, true)

    @Test fun `up from the emulated home stops at the root boundary`() {
        val d = FilesNav.up(home, roots, canList)
        assertEquals(FilesNav.UpDecision.Stop(FilesNav.StopReason.ROOT_BOUNDARY), d)
    }

    @Test fun `up even when the parent lists stops at a root whose parent is in no root`() {
        assertEquals(FilesNav.UpDecision.Stop(FilesNav.StopReason.ROOT_BOUNDARY), FilesNav.up(home, roots) { true })
    }

    @Test fun `up inside a root goes to the parent`() {
        assertEquals(FilesNav.UpDecision.Go(home), FilesNav.up(Location.Local("/storage/emulated/0/Download"), roots, canList))
        assertEquals(FilesNav.UpDecision.Go(Location.Local("/storage/emulated/0/a")), FilesNav.up(Location.Local("/storage/emulated/0/a/b"), roots, canList))
    }

    @Test fun `up into an unreadable parent inside a root stops with unreadable`() {
        val d = FilesNav.up(Location.Local("/storage/emulated/0/a/b"), roots) { it != "/storage/emulated/0/a" }
        assertEquals(FilesNav.UpDecision.Stop(FilesNav.StopReason.UNREADABLE), d)
    }

    @Test fun `up at the filesystem top stops`() {
        assertEquals(FilesNav.UpDecision.Stop(FilesNav.StopReason.TOP), FilesNav.up(Location.Local("/storage"), roots, canList))
    }

    @Test fun `up inside an archive walks the zip, then out to its folder`() {
        val zip = "/storage/emulated/0/x.zip"
        assertEquals(FilesNav.UpDecision.Go(Location.Archive(zip, "")), FilesNav.up(Location.Archive(zip, "a"), roots, canList))
        assertEquals(FilesNav.UpDecision.Go(home), FilesNav.up(Location.Archive(zip, ""), roots, canList))
    }

    @Test fun `a probe that throws is unreadable, never a crash`() {
        val d = FilesNav.up(Location.Local("/storage/emulated/0/a"), roots) { throw SecurityException("no") }
        assertTrue(d is FilesNav.UpDecision.Stop)
    }

    @Test fun `home returns from any state`() {
        listOf(Location.Local("/storage/emulated"), Location.Local("/"), Location.Archive("/storage/emulated/0/x.zip", "a/b"), Location.Local("/storage/ABCD-1234/m")).forEach { loc ->
            assertEquals(home, FilesNav.home(state(loc), PaneId.A, home).pane(PaneId.A).location)
        }
        val s = state(home)
        assertEquals(s, FilesNav.home(s, PaneId.A, home))
    }

    @Test fun `an unreadable restored path falls back to home and drops unreadable history`() {
        val stuck = FilesUiState(
            a = PaneState(listOf(PaneTab(Location.Local("/storage/emulated"), history = listOf(Location.Local("/storage"), home)))),
            b = PaneState(listOf(PaneTab(Location.Local("/storage/emulated/0/Download")))),
        )
        val (fixed, moved) = FilesNav.recover(stuck, home, canList)
        assertEquals(1, moved)
        assertEquals(home, fixed.a.location)
        assertEquals(listOf<Location>(home), fixed.a.current.history)
        assertEquals(Location.Local("/storage/emulated/0/Download"), fixed.b.location)
        val (same, none) = FilesNav.recover(fixed, home, canList)
        assertEquals(0, none); assertEquals(fixed, same)
    }

    @Test fun `breadcrumb starts at the root, not at slash`() {
        val crumbs = FilesNav.crumbs(Location.Local("/storage/emulated/0/Download/x"), roots)
        assertEquals(listOf("/storage/emulated/0", "/storage/emulated/0/Download", "/storage/emulated/0/Download/x"), crumbs.map { (it as Location.Local).path })
        // Outside every root the full chain stays.
        assertEquals(Location.Local("/mnt/x").crumbs(), FilesNav.crumbs(Location.Local("/mnt/x"), roots))
    }

    @Test fun `roots list holds volumes and app dirs and the deepest root wins`() {
        assertEquals("/storage/ABCD-1234", FilesNav.rootOf("/storage/ABCD-1234/DCIM", roots))
        assertEquals("/storage/emulated/0/Download", FilesNav.rootOf("/storage/emulated/0/Download/a", roots))
        assertEquals(null, FilesNav.rootOf("/storage/emulated", roots))
    }

    /** Rule 16 field parity: every Explorer toolbar action and the error Retry stay next to the new Home. */
    @Test fun `explorer keeps every previous action`() {
        val src = File("src/main/java/com/diegonmarcos/clouddrive/files/FilesScreen.kt").readText()
        listOf("R.string.files_up", "R.string.files_view_tree", "R.string.files_bookmark_add", "R.string.files_hidden_show", "R.string.files_filter", "R.string.files_places",
            "R.string.files_new_tab", "R.string.files_close_tab", "FilesReducer.setSort", "FilesReducer.setFilter", "onRetry = { controller.reload(loc) }", "R.string.files_home", "R.string.files_back")
            .forEach { assertTrue("missing $it", it in src) }
    }
}
