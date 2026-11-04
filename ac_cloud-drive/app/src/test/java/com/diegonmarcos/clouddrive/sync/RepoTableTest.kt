package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Test

/** #1138 the repo table's pure rules: sorting by any column, the tap toggle, how each cell prints, where the facts come from. */
class RepoTableTest {
    private fun row(name: String, pvt: Boolean? = false, main: Long? = null, total: Long? = null, br: Int? = null, dirty: Int? = null, a: Int? = null, b: Int? = null) =
        RepoTableRow(name, pvt, main, total, br, dirty, a, b)

    private fun names(rows: List<RepoTableRow>, c: RepoColumn, asc: Boolean) = RepoTable.sort(rows, c, asc) { it }.map { it.name }

    @Test fun `a header tap sorts ascending and the same header again flips it`() {
        assertEquals(RepoColumn.SIZE_TOTAL to true, RepoTable.tap(RepoColumn.SIZE_TOTAL, RepoColumn.NAME, false))
        assertEquals(RepoColumn.NAME to false, RepoTable.tap(RepoColumn.NAME, RepoColumn.NAME, true))
        assertEquals(RepoColumn.NAME to true, RepoTable.tap(RepoColumn.NAME, RepoColumn.NAME, false))
    }

    @Test fun `name sorts case-insensitively in both directions`() {
        val rows = listOf(row("beta"), row("Alpha"), row("gamma"))
        assertEquals(listOf("Alpha", "beta", "gamma"), names(rows, RepoColumn.NAME, true))
        assertEquals(listOf("gamma", "beta", "Alpha"), names(rows, RepoColumn.NAME, false))
    }

    @Test fun `sizes and branches sort numerically and unknown always sinks to the bottom`() {
        val rows = listOf(row("a", main = 10, total = 500, br = 3), row("b", main = 2000, total = null, br = 12), row("c", main = null, total = 40, br = null))
        assertEquals(listOf("a", "b", "c"), names(rows, RepoColumn.SIZE_MAIN, true))
        assertEquals(listOf("b", "a", "c"), names(rows, RepoColumn.SIZE_MAIN, false))
        assertEquals(listOf("c", "a", "b"), names(rows, RepoColumn.SIZE_TOTAL, true))
        assertEquals(listOf("a", "c", "b"), names(rows, RepoColumn.SIZE_TOTAL, false))
        assertEquals(listOf("a", "b", "c"), names(rows, RepoColumn.BRANCHES, true))
        assertEquals(listOf("b", "a", "c"), names(rows, RepoColumn.BRANCHES, false))
    }

    @Test fun `ahead-behind sorts by ahead first and behind breaks the tie`() {
        val rows = listOf(row("x", a = 2, b = 5), row("y", a = 2, b = 1), row("z", a = 0, b = 9), row("n"))
        assertEquals(listOf("z", "y", "x", "n"), names(rows, RepoColumn.AHEAD_BEHIND, true))
        assertEquals(listOf("x", "y", "z", "n"), names(rows, RepoColumn.AHEAD_BEHIND, false))
    }

    @Test fun `dirty sorts by the changed-file count and pvt-pub groups the visibility`() {
        assertEquals(listOf("b", "a", "c"), names(listOf(row("a", dirty = 4), row("b", dirty = 0), row("c", dirty = null)), RepoColumn.DIRTY, true))
        assertEquals(listOf("e", "d"), names(listOf(row("d", pvt = true), row("e", pvt = false)), RepoColumn.VISIBILITY, true))
    }

    @Test fun `ties break by name`() {
        val rows = listOf(row("b", br = 1), row("a", br = 1))
        assertEquals(listOf("a", "b"), names(rows, RepoColumn.BRANCHES, true))
        assertEquals(listOf("a", "b"), names(rows, RepoColumn.BRANCHES, false))
    }

    @Test fun `cells print a dash for unknown and the facts otherwise`() {
        assertEquals("—", RepoTable.size(null))
        assertEquals("—", RepoTable.count(null))
        assertEquals("7", RepoTable.count(7))
        assertEquals("—", RepoTable.dirty(null, "yes", "no"))
        assertEquals("no", RepoTable.dirty(0, "yes", "no"))
        assertEquals("yes · 3", RepoTable.dirty(3, "yes", "no"))
        assertEquals("↑2 ↓5", RepoTable.aheadBehind(2, 5))
        assertEquals("—", RepoTable.aheadBehind(2, null))
        assertEquals("Pvt", RepoTable.visibility(true, "Pvt", "Pub"))
        assertEquals("Pub", RepoTable.visibility(false, "Pvt", "Pub"))
        assertEquals("—", RepoTable.visibility(null, "Pvt", "Pub"))
    }

    private val stats = RepoRemoteStats.Stats(sizeTotalKb = 2, sizeMainBytes = 1000, branches = 4, isPrivate = true, defaultBranch = "main", fetchedAtMs = 1)

    @Test fun `a row joins the cached remote facts with the local glance`() {
        val glance = GitSyncCoordinator.Glance(branch = "main", upstream = "origin/main", ahead = 2, behind = 5, changed = 3, conflicts = 1, read = true)
        val r = RepoTable.rowOf("repo", null, true, glance, stats)
        assertEquals(true, r.isPrivate); assertEquals(1000L, r.sizeMainBytes); assertEquals(2048L, r.sizeTotalBytes); assertEquals(4, r.branches)
        assertEquals(4, r.dirtyFiles); assertEquals(2, r.ahead); assertEquals(5, r.behind)
    }

    @Test fun `a repo that is not cloned, not read, or has no upstream has unknown local facts, and no remote data is unknown too`() {
        val glance = GitSyncCoordinator.Glance(branch = "main", upstream = null, ahead = 0, behind = 0, changed = 0, read = true)
        val noUpstream = RepoTable.rowOf("repo", false, true, glance, null)
        assertEquals(0, noUpstream.dirtyFiles); assertNull(noUpstream.ahead); assertNull(noUpstream.behind); assertNull(noUpstream.sizeMainBytes); assertNull(noUpstream.branches)
        val notCloned = RepoTable.rowOf("repo", false, false, glance, stats)
        assertNull(notCloned.dirtyFiles); assertNull(notCloned.ahead); assertEquals(4, notCloned.branches)
        val unread = RepoTable.rowOf("repo", false, true, GitSyncCoordinator.Glance(), stats)
        assertNull(unread.dirtyFiles)
        val gone = RepoTable.rowOf("repo", false, true, GitSyncCoordinator.Glance(gone = true, read = true), stats)
        assertNull(gone.dirtyFiles)
    }

    @Test fun `the default sort is name then visibility, and public and private mix in one list`() {
        val rows = listOf(row("b", pvt = true), row("a", pvt = true), row("a", pvt = false), row("c", pvt = false))
        val sorted = RepoTable.sort(rows, RepoTable.DEFAULT_SORT, true) { it }
        assertEquals(listOf("a" to false, "a" to true, "b" to true, "c" to false), sorted.map { it.name to it.isPrivate })
    }

    @Test fun `a second tap on the open repo closes its menu and a tap on another moves it`() {
        assertEquals("x", RepoTable.toggle("", "x"))
        assertEquals("", RepoTable.toggle("x", "x"))
        assertEquals("y", RepoTable.toggle("x", "y"))
    }

    @Test fun `the summary counts total, cloned, not cloned, dirty, ahead and behind`() {
        val s = RepoTable.summarize(listOf(
            true to row("a", dirty = 2, a = 1, b = 0), true to row("b", dirty = 0, a = 0, b = 3), false to row("c"), true to row("d", dirty = 1, a = 2, b = 2),
        ))
        assertEquals(RepoTable.Summary(total = 4, cloned = 3, notCloned = 1, dirty = 2, ahead = 2, behind = 2), s)
    }

    @Test fun `#1238 the columns are declared in order - status first, name second, LFS right after Pvt-Pub`() {
        val cols = RepoColumn.entries
        assertEquals(RepoColumn.STATUS, cols.first())
        assertEquals(RepoColumn.NAME, cols[1])
        assertEquals(RepoColumn.LFS, cols[cols.indexOf(RepoColumn.VISIBILITY) + 1])
    }

    @Test fun `#1238 the LFS cell prints LFS or Normal from the registry flag and a dash when the registry does not list the repo`() {
        assertEquals("LFS", RepoTable.lfs(true, "LFS", "Normal"))
        assertEquals("Normal", RepoTable.lfs(false, "LFS", "Normal"))
        assertEquals("—", RepoTable.lfs(null, "LFS", "Normal"))
    }

    @Test fun `#1238 LFS sorts normal before lfs ascending and unknown sinks`() {
        val rows = listOf(row("a").copy(lfs = true), row("b").copy(lfs = false), row("c").copy(lfs = null))
        assertEquals(listOf("b", "a", "c"), names(rows, RepoColumn.LFS, true))
        assertEquals(listOf("a", "b", "c"), names(rows, RepoColumn.LFS, false))
    }

    @Test fun `#1238 the status column sorts by the real status kind`() {
        val rows = listOf(row("a").copy(status = RepoStatus.Kind.CLEAN), row("b").copy(status = RepoStatus.Kind.NOT_CLONED), row("c").copy(status = RepoStatus.Kind.DIRTY), row("d"))
        assertEquals(listOf("b", "c", "a", "d"), names(rows, RepoColumn.STATUS, true))
    }

    @Test fun `#1238 a row carries the registry lfs flag and the status kind it was built from`() {
        val notCloned = RepoTable.rowOf("r", false, false, null, null, lfs = true)
        assertEquals(true, notCloned.lfs); assertEquals(RepoStatus.Kind.NOT_CLONED, notCloned.status)
        val clean = RepoTable.rowOf("r", false, true, GitSyncCoordinator.Glance(branch = "main", upstream = "origin/main", read = true), null, lfs = false)
        assertEquals(false, clean.lfs); assertEquals(RepoStatus.Kind.CLEAN, clean.status)
        assertNull(RepoTable.rowOf("r", false, false, null, null).lfs)
    }

    @Test fun `#1452 no column is frozen - the name scrolls with the rest`() {
        assertTrue(RepoTable.PINNED.isEmpty())
    }

    @Test fun `#1452 the Status Message column follows the name and sorts by its text, empty last`() {
        val cols = RepoColumn.entries
        assertEquals(RepoColumn.MESSAGE, cols[cols.indexOf(RepoColumn.NAME) + 1])
        val rows = listOf(row("a").copy(message = "zeta"), row("b").copy(message = ""), row("c").copy(message = "alpha"))
        assertEquals(listOf("c", "a", "b"), names(rows, RepoColumn.MESSAGE, true))
        assertEquals(listOf("a", "c", "b"), names(rows, RepoColumn.MESSAGE, false))
    }
}
