package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 the declared top bar: every group and item of ui.sync.git.top_bar parses with a known kind, the audit's groups and
 * verbs are there, an exclusive item is disabled (with its reason) while a writing job runs, a scoped verb with nothing in
 * scope is disabled, and the summary prints the declared counters in order. Mutations proven red: ignoring `exclusive`
 * (exclusiveWaits), ignoring the scope (nothingInScope), fixed counter order (summaryFollowsTheDeclaredOrder).
 */
class GitTopBarStateTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val page by lazy { Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]) }
    private val bar get() = page.topBar
    private val labels: (String) -> String = { bar.label(it) }

    @Test fun `the bar is declared with groups, known kinds, the audit verbs and depth actions that exist`() {
        assertEquals(listOf("check", "sync", "clone", "logs", "more"), bar.groups.map { it.id })
        // #1356 Logs is a one-item button; More holds the rest. #1452 Check and Clone are dropdowns like Sync.
        assertEquals("logs", bar.groups[3].items.single().id)
        assertEquals(listOf("check_lite", "check_full"), bar.groups[0].items.map { it.id })
        assertEquals(listOf("check_light", "check_full"), bar.groups[0].items.map { it.kind })
        assertEquals(listOf("clone_all_full", "clone_all_depth3", "clone_all_bare_main", "clone_all_bare_blobless", "clone_all_bare_treeless"), bar.groups[2].items.map { it.id })
        // #1356 Sync is a dropdown: Sync All then Manual Sync
        assertEquals(listOf("Sync All", "Sync All", "Sync All", "Manual Sync", "Manual Sync", "Manual Sync", "Manual Sync", "Manual Sync"), bar.groups[1].items.map { it.section })
        assertEquals(listOf("sync_remote_wins", "sync_local_wins", "sync_no_winner", "stash", "pull_rebase", "stash_pop", "auto_commit", ""), bar.groups[1].items.map { it.op })
        assertEquals(listOf("repos", "app"), bar.logs.tabs.map { it.first })
        val items = bar.groups.flatMap { it.items }
        assertTrue(items.isNotEmpty() && items.all { it.kind in Declarations.TOP_KINDS })
        assertEquals(items.map { it.id }.distinct(), items.map { it.id })
        for (k in listOf("check_light", "check_full", "check_remote", "check_sizes", "open_logs", "legend", "toggle_filter")) assertTrue(k, items.any { it.kind == k })
        items.filter { it.kind == "depth" }.forEach { assertNotNull("${it.id} names a declared scope-all depth action", page.depthActions(Declarations.DEPTH_SCOPE_ALL).firstOrNull { a -> a.id == it.action }) }
        items.filter { it.kind == "compound" }.forEach { assertTrue(it.id, RepoActions.isCompound(it.op)) }
        items.filter { it.kind == "toggle_filter" }.forEach { assertNotNull(it.id, page.filters.firstOrNull { f -> f.id == it.filter }?.option(it.option)) }
        assertTrue("danger always confirms", items.filter { it.danger }.all { it.confirm })
        assertTrue("the checks are not exclusive", items.filter { it.kind.startsWith("check") }.none { it.exclusive })
        assertTrue(bar.counters.isNotEmpty() && bar.search)
    }

    @Test fun `an exclusive item waits while a writing job runs, a read does not`() {
        val sync = bar.item("sync_all")!!; val light = bar.item("check_light")!!
        assertNull(GitTopBarState.reason(sync, null, 3, labels))
        val why = GitTopBarState.reason(sync, "Clone missing", 3, labels)
        assertNotNull(why); assertTrue(why!!.contains("Clone missing"))
        assertNull(GitTopBarState.reason(light, "Clone missing", 3, labels))
    }

    @Test fun `a scoped verb with nothing in scope is disabled and says so`() {
        assertEquals(labels("nothing_in_scope"), GitTopBarState.reason(bar.item("fetch_all")!!, null, 0, labels))
        assertEquals(labels("nothing_in_scope"), GitTopBarState.reason(bar.item("pull_all")!!, null, 0, labels))
        assertNull(GitTopBarState.reason(bar.item("clone_all_full")!!, null, 0, labels))
    }

    @Test fun `the summary follows the declared counters in order`() {
        val s = GitTopBarState.summary(listOf("cloned", "needs_you", "ahead", "behind", "checked_age"), 10, 7, 2, 1, 3, "5m", labels)
        assertEquals("7 ${labels("c_cloned")} · 2 ${labels("c_needs_you")} · 1↑ · 3↓ · ${labels("c_checked")} 5m", s)
        assertEquals("3 ${labels("c_not_cloned")}", GitTopBarState.summary(listOf("not_cloned", "bogus"), 10, 7, 0, 0, 0, "", labels))
        assertEquals("", GitTopBarState.summary(listOf("checked_age"), 1, 1, 0, 0, 0, "", labels))
    }
}
