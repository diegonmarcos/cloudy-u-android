package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.GitStatusFacts
import com.diegonmarcos.cloudlib.gitui.GitTable
import com.diegonmarcos.cloudlib.gitui.RepoColumn
import com.diegonmarcos.cloudlib.gitui.gitStatusTableRows
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1645 the light git stats: computed from real git state, shown in the status table, and sortable as columns. */
class RepoLightStatsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun glance(branch: String? = "main", upstream: String? = "origin/main", ahead: Int = 0, behind: Int = 0, changed: Int = 0, detached: Boolean = false, stashed: Int = 0) =
        GitSyncCoordinator.Glance(branch, upstream, ahead, behind, changed, 0, read = true, level = "full", state = "clean", detached = detached, stashed = stashed)

    private fun row(name: String, g: GitSyncCoordinator.Glance?, cloned: Boolean = true) = RepoTable.rowOf(name, null, cloned, g, null)

    @Test fun theColumnsLeadWithTheSixLightStatsInOrder() {
        val c = RepoTable.COLUMNS
        assertEquals(listOf(RepoColumn.CLONED, RepoColumn.AHEAD_BEHIND, RepoColumn.DIRTY, RepoColumn.DETACHED, RepoColumn.UNPUSHED, RepoColumn.CURRENT_BRANCH), c.subList(c.indexOf(RepoColumn.NAME) + 1, c.indexOf(RepoColumn.NAME) + 7))
        assertEquals(RepoColumn.MESSAGE, c[c.indexOf(RepoColumn.CURRENT_BRANCH) + 1])
        assertEquals(RepoColumn.LAST_COMMIT, c.last())
    }

    @Test fun rowFactsComeFromTheRealGlance() {
        val r = row("a", glance(ahead = 3, behind = 1, changed = 2, stashed = 4))
        assertEquals(true, r.cloned); assertEquals(3, r.ahead); assertEquals(1, r.behind); assertEquals(3, r.unpushed)
        assertEquals(2, r.dirtyFiles); assertEquals(4, r.stashed); assertEquals(false, r.detached); assertEquals("main", r.branch)
        val d = row("d", glance(branch = null, upstream = null, detached = true))
        assertEquals(true, d.detached); assertEquals(null, d.branch); assertEquals(null, d.unpushed)
        val n = row("n", null, cloned = false)
        assertEquals(false, n.cloned); assertEquals(null, n.detached); assertEquals(null, n.stashed); assertEquals(null, n.branch)
    }

    @Test fun everyNewColumnSortsAndUnknownSinksLast() {
        val rows = listOf(row("clean", glance()), row("dirty", glance(changed = 5, stashed = 1)), row("ahead", glance(ahead = 9, behind = 2)), row("det", glance(branch = null, upstream = null, detached = true)), row("none", null, cloned = false))
        fun order(c: RepoColumn, asc: Boolean) = RepoTable.sort(rows, c, asc) { it }.map { it.name }
        assertEquals("dirty", order(RepoColumn.DIRTY, false).first())
        assertEquals("ahead", order(RepoColumn.UNPUSHED, false).first())
        assertEquals("ahead", order(RepoColumn.AHEAD_BEHIND, false).first())
        assertEquals("det", order(RepoColumn.DETACHED, false).first())
        assertEquals("none", order(RepoColumn.CLONED, true).first())
        assertEquals(listOf("ahead", "clean", "dirty"), order(RepoColumn.CURRENT_BRANCH, true).take(3).sorted())
        for (c in RepoColumn.entries) assertEquals(5, order(c, true).size)
        assertEquals("none", order(RepoColumn.UNPUSHED, true).last())
    }

    @Test fun statusTableRowsAreTheSixRequestedInOrder() {
        val rows = gitStatusTableRows(GitStatusFacts(true, "main", "origin/main", 2, 2, 0, 0, "full", "1m", 3, 1, 3, null, null, stashed = 2, detached = false), "yes", "no")
        assertEquals(6, rows.size)
        assertEquals(listOf("yes", "+3/-1", "yes · 2 · stash 2", "no", "3", "main"), rows.map { it.second })
        val unread = gitStatusTableRows(GitStatusFacts(false, null, null, null, null, null, null, null, null, null, null, null, null, null), "yes", "no")
        assertEquals(listOf("no", GitTable.DASH, GitTable.DASH, GitTable.DASH, GitTable.DASH, GitTable.DASH), unread.map { it.second })
    }

    @Test fun detachedAndStashAreReadFromRealGitFiles() {
        assertTrue(RepoCheap.isDetachedHead("0123456789abcdef0123456789abcdef01234567\n"))
        assertFalse(RepoCheap.isDetachedHead("ref: refs/heads/main\n"))
        val dir = tmp.newFolder("r"); val git = File(dir, ".git").apply { mkdirs() }
        File(git, "HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        assertEquals(true, RepoCheap.head(dir).detached)
        assertEquals(0, RepoCheap.stashCount(git))
        File(git, "logs/refs").mkdirs(); File(git, "logs/refs/stash").writeText("a b c\nd e f\n")
        assertEquals(2, RepoCheap.stashCount(git))
        val p = RepoCheap.lightProbe(dir)
        assertTrue(p.detached); assertEquals(2, p.stashed)
        File(git, "HEAD").writeText("ref: refs/heads/main\n")
        assertFalse(RepoCheap.lightProbe(dir).detached)
        val exec = RepoCheap.parseLight("B:\nS:3\nD:1\nF:\n")
        assertTrue(exec.detached); assertEquals(3, exec.stashed)
    }
}
