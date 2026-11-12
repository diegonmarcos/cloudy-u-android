package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.GitLight
import com.diegonmarcos.cloudlib.gitui.GitLights
import com.diegonmarcos.cloudlib.gitui.GitStatusFacts
import com.diegonmarcos.cloudlib.gitui.GitStatusField
import com.diegonmarcos.cloudlib.gitui.GitStatusWords
import com.diegonmarcos.cloudlib.gitui.GitTable
import com.diegonmarcos.cloudlib.gitui.RepoColumn
import com.diegonmarcos.cloudlib.gitui.gitStatusFields
import com.diegonmarcos.cloudlib.gitui.gitStatusTableRows
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1670 ONE table before / during / after a sync, FIVE lights from the #1645 stats, ONE status metadata group in ONE text style.
 */
class RepoFiveLightsTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val lib = File(root, "../ab_cloud-libs-shared/libs/git-ui/src/main/java/com/diegonmarcos/cloudlib/gitui")
    private val screen = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt").readText()
    private val folder = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/FolderTableUi.kt").readText()
    private val window = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/RepoStatusWindow.kt").readText()

    private fun g(read: Boolean = true, level: String = "full", ahead: Int = 0, behind: Int = 0, changed: Int = 0, upstream: String? = "origin/main",
                  detached: Boolean = false, stashed: Int = 0, error: String? = null, large: Boolean = false) =
        GitSyncCoordinator.Glance("main".takeUnless { detached }, upstream, ahead, behind, changed, 0, read = read, level = if (read) level else "", error = error, large = large, detached = detached, stashed = stashed, state = "clean")

    private fun row(glance: GitSyncCoordinator.Glance?, cloned: Boolean = true) = RepoTable.rowOf("r", null, cloned, glance, null)

    /** The sync lifecycle of one clone: never checked, light check, a sync running (the reader re-reads: READING), the post-sync full read, a failed read. */
    private val lifecycle = listOf(null, g(level = "light"), g(read = false), g(ahead = 2, changed = 1, stashed = 1), g(error = "timeout"), g(large = true))

    @Test fun theSameTableAndColumnsBeforeDuringAndAfterASync() {
        val before = RepoTable.COLUMNS
        val shapes = lifecycle.map { gl -> row(gl).let { r -> r::class to GitLights.of(r).map { it.first } } }
        // every state is the same row type with the same five lights in the same order; only the values change
        assertEquals(1, shapes.toSet().size)
        assertEquals(GitLights.COLUMNS, shapes.first().second)
        assertEquals(before, RepoTable.COLUMNS)
        // the values do change across the sync
        assertTrue(lifecycle.map { GitLights.of(row(it)) }.toSet().size > 1)
        // ONE header emission and ONE row composable for the main table; the folder tables draw the same cells and lights
        assertEquals(1, Regex("RepoTableHeader\\(sortColumn").findAll(screen).count())
        assertEquals(1, Regex("GitRepoRow\\(\\s*name = ").findAll(screen).count())
        assertTrue(screen.contains("RepoLightsCell(cells, name, onOpenStatus") && screen.contains("RepoCells(cells)"))
        assertTrue(folder.contains("RepoLightsCell(row, t.name, toggle") && folder.contains("RepoCells(row)"))
        assertFalse("no table draws the three old lights any more", screen.contains("RepoStatusCell(icons") || folder.contains("RepoStatusCell(RepoStatus.icons"))
        // the sync state never picks a different table: the table block is not conditioned on a running job / the sync state
        val block = screen.substring(screen.indexOf("fun LazyListScope.tableBlock()"), screen.indexOf("GitPageFrame(\n"))
        assertFalse(block.contains("if (running") || block.contains("jobLine?.let { RepoTable") || block.contains("refreshingRemote"))
    }

    @Test fun fiveLightsMapping() {
        fun l(r: com.diegonmarcos.cloudlib.gitui.GitTableRow<*>, c: RepoColumn) = GitLights.of(r, c)
        val clean = row(g())
        assertEquals(listOf(GitLight.YES, GitLight.NO, GitLight.NO, GitLight.NO, GitLight.NO), GitLights.of(clean).map { it.second })
        val busy = row(g(ahead = 2, behind = 1, changed = 3))
        assertEquals(GitLight.YES, l(busy, RepoColumn.AHEAD_BEHIND)); assertEquals(GitLight.YES, l(busy, RepoColumn.DIRTY)); assertEquals(GitLight.YES, l(busy, RepoColumn.UNPUSHED))
        assertEquals(GitLight.YES, l(row(g(stashed = 2)), RepoColumn.DIRTY))
        assertEquals(GitLight.YES, l(row(g(detached = true, upstream = null)), RepoColumn.DETACHED))
        // unknown cases: not cloned, not read, reading, light check (dirty), no upstream (divergence / unpushed), error, large
        assertEquals(listOf(GitLight.NO, GitLight.UNKNOWN, GitLight.UNKNOWN, GitLight.UNKNOWN, GitLight.UNKNOWN), GitLights.of(row(null, cloned = false)).map { it.second })
        for (gl in listOf(null, g(read = false), g(error = "x"), g(large = true))) assertEquals(listOf(GitLight.YES) + List(4) { GitLight.UNKNOWN }, GitLights.of(row(gl)).map { it.second })
        assertEquals(GitLight.UNKNOWN, l(row(g(level = "light")), RepoColumn.DIRTY))
        val noUp = row(g(upstream = null))
        assertEquals(GitLight.UNKNOWN, l(noUp, RepoColumn.AHEAD_BEHIND)); assertEquals(GitLight.UNKNOWN, l(noUp, RepoColumn.UNPUSHED))
        // glyphs: healthy ✓, problem ⚠, not cloned ○, unknown ·
        assertEquals("✓", GitLights.glyph(RepoColumn.CLONED, GitLight.YES)); assertEquals("○", GitLights.glyph(RepoColumn.CLONED, GitLight.NO))
        assertEquals("⚠", GitLights.glyph(RepoColumn.DIRTY, GitLight.YES)); assertEquals("·", GitLights.glyph(RepoColumn.DIRTY, GitLight.UNKNOWN))
        assertEquals(true, GitLights.problem(RepoColumn.UNPUSHED, GitLight.YES)); assertEquals(null, GitLights.problem(RepoColumn.CLONED, GitLight.NO))
        // a light says what its column says (the same row fields): Branch stays a text column, not a light
        assertFalse(RepoColumn.CURRENT_BRANCH in GitLights.COLUMNS)
        assertTrue(GitLights.COLUMNS.all { it in RepoTable.COLUMNS })
    }

    private val w = GitStatusWords("yes", "no", "never", "none", "(no upstream)", "not checked", "unknown", "light", "full")

    @Test fun metadataParityOldPlusNewEqualsMerged() {
        val facts = GitStatusFacts(true, "main", "origin/main", 3, 2, 1, 0, "full", "5m", 2, 1, 2, 1_000L, "boom", stashed = 1, detached = false,
            workingCopy = "dirty", remoteState = "diverged", syncJob = "error: failed 2m ago", large = false)
        val status = gitStatusFields(facts, w) { "T$it" }
        // NEW (#1645 table): the six rows, the same values
        assertEquals(gitStatusTableRows(facts, "yes", "no").map { it.second }, status.take(6).map { it.second })
        // OLD (the STATUS lines + the three lights): every fact it printed has a field
        val old = mapOf("cloned" to GitStatusField.CLONED, "branch" to GitStatusField.BRANCH, "upstream" to GitStatusField.UPSTREAM, "dirty" to GitStatusField.FILES,
            "level" to GitStatusField.CHECKED, "ahead/behind" to GitStatusField.DIVERGENCE, "unpushed" to GitStatusField.UNPUSHED, "fetch" to GitStatusField.LAST_FETCH,
            "error" to GitStatusField.LAST_ERROR, "local light" to GitStatusField.WORKING_COPY, "server light" to GitStatusField.REMOTE, "sync light" to GitStatusField.LAST_SYNC_JOB,
            "conflicts" to GitStatusField.CONFLICTS, "large" to GitStatusField.LARGE)
        val byField = status.toMap()
        old.values.forEach { assertTrue(it.name, it in byField) }
        assertEquals("3 (M 2 · U 1 · C 0)", byField[GitStatusField.FILES]); assertEquals("full · 5m", byField[GitStatusField.CHECKED])
        assertEquals("T1000", byField[GitStatusField.LAST_FETCH]); assertEquals("boom", byField[GitStatusField.LAST_ERROR])
        assertEquals("diverged", byField[GitStatusField.REMOTE]); assertEquals("error: failed 2m ago", byField[GitStatusField.LAST_SYNC_JOB])
        // + the repository metadata: merged = status ∪ meta, no field twice, nothing lost
        val meta = RepoStatus.MetaField.entries.map { it to it.name }
        val merged = RepoStatus.mergeFields(status, meta)
        assertEquals(merged.size, merged.map { it.first }.toSet().size)
        assertEquals(GitStatusField.entries.size + RepoStatus.MetaField.entries.size - RepoStatus.META_IN_STATUS.size, merged.size)
        val keys: List<Any?> = merged.map { it.first }
        RepoStatus.MetaField.entries.forEach { m -> assertTrue(m.name, keys.contains(m) || keys.contains(RepoStatus.META_IN_STATUS[m] as Any?)) }
        // unknown facts print the dash or their word, never crash
        val none = gitStatusFields(GitStatusFacts(false, null, null, null, null, null, null, null, null, null, null, null, null, null), w) { "" }.toMap()
        assertEquals(GitTable.DASH, none[GitStatusField.REMOTE]); assertEquals("never", none[GitStatusField.LAST_FETCH]); assertEquals("unknown", none[GitStatusField.FILES])
        // the window resolves the old three lights' words into the merged group
        assertTrue(window.contains("workingCopy = RepoStatus.kindWord(icons.local)") && window.contains("remoteState = RepoStatus.kindWord(icons.server)") && window.contains("syncJob = RepoStatus.kindWord(icons.sync)"))
        assertTrue(window.contains("fields = merged") && window.contains("fiveSections(row, name)"))
    }

    @Test fun oneTextStyle() {
        val lights = File(lib, "GitLights.kt").readText()
        val table = lights.substring(lights.indexOf("fun GitStatusTable("))
        assertTrue(table.contains("style = GitStatusStyle.label()") && table.contains("style = GitStatusStyle.value()"))
        assertFalse("no second type style in the merged group", table.contains("typography.bodySmall") || table.contains("fontFamily ="))
        val win = File(lib, "GitStatusWindowUi.kt").readText()
        val merged = win.substring(win.indexOf("if (fields != null) {"), win.indexOf("} else {", win.indexOf("if (fields != null) {")))
        assertTrue(merged.contains("GitStatusTable(fields)"))
        assertFalse("the merged path draws neither the old block nor the old metadata table", merged.contains("GitStatusBlockView(") || merged.contains("RepoMetaTable("))
    }

    @Test fun columnsSortsAndActionsKept() {
        assertEquals(RepoColumn.entries.toList(), RepoTable.COLUMNS)
        val rows = lifecycle.mapIndexed { i, gl -> RepoTable.rowOf("r$i", null, true, gl, null) }
        for (c in RepoColumn.entries) for (asc in listOf(true, false)) assertEquals(rows.size, RepoTable.sort(rows, c, asc) { it }.size)
        // the light cell still opens the status window; the row tap still opens the actions sheet; Clone stays in the window
        assertTrue(screen.contains("onOpenStatus = { statusFor = e.key }") && screen.contains("onToggle = { expanded = RepoTable.toggle(expanded, e.key) }"))
        assertTrue(window.contains("R.string.sync_clone_into_store") && window.contains("onCheckWorktree = onCheckWorktree"))
    }
}
