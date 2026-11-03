package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1289 the per-repository menu is data (build.json ui.sync.git.repo_menu): order, placement, no lost action, Configs-Auth only auth, AutoSync. */
class RepoMenuTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val git: JsonObject by lazy {
        Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]!!.jsonObject
    }
    private val page: Declarations.GitPageDecl by lazy { Declarations.parseGitPage(git) }
    private val menu get() = page.repoMenu

    /** Every action the detail drew before #1289 (the eight #1138 groups plus the scope-repo depth chips): none may be lost. */
    private val previous = listOf(
        "sync_no_winner", "sync_remote_wins", "sync_local_wins",
        "fetch", "pull", "push", "commit", "stats",
        "checkout_main", "branches", "unpushed", "open_folder", "web",
        "history", "runs", "actions", "prs",
        "clean_1", "clean_5", "clean_10",
        "force_pull", "force_push",
        "remote", "metadata", "sync_now", "hooks", "size", "settings",
        "clone_full", "clone_depth1", "clone_oauth", "clone_ssh",
        "clone_repo_depth1", "keep_repo_depth1", "clone_repo_bare",
    )

    private fun ids(g: String) = menu.group(g)!!.allIds

    @Test fun `the groups are declared in the owner's order and the code agrees`() {
        val declared = git["repo_menu"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertEquals(RepoActions.GROUP_ORDER, declared)
        assertEquals(declared, menu.groups.map { it.id })
        assertEquals(listOf("goto", "clone", "sync", "manual_sync", "workflow", "repo_clean", "others", "configs_auth", "configs_autosync"), declared)
    }

    @Test fun `every previously drawn action sits in exactly one group`() {
        val all = menu.allIds
        assertEquals("an action sits in two groups: " + all.groupBy { it }.filterValues { it.size > 1 }.keys, all.size, all.toSet().size)
        for (id in previous) assertEquals("$id must be in exactly one group", 1, all.count { it == id })
        for (op in page.ops) assertEquals("declared operation ${op.id} must be in exactly one group", 1, all.count { it == op.id })
    }

    @Test fun `every menu item has a handler`() {
        val known = RepoActions.PAGE_IDS + page.ops.map { it.id } + page.depthActions(Declarations.DEPTH_SCOPE_REPO).map { it.id } + page.folderActions.map { it.id }
        for (id in menu.allIds) assertTrue("$id has no handler", id in known)
    }

    @Test fun `Goto, Clone, Sync and Workflow hold what the owner placed there`() {
        assertEquals(listOf("web", "open_folder", "branches", "checkout_main"), ids("goto"))
        assertTrue(ids("clone").containsAll(listOf("clone_full", "clone_depth1") + page.depthActions(Declarations.DEPTH_SCOPE_REPO).map { it.id }))
        assertTrue(ids("sync").containsAll(listOf("sync_no_winner", "sync_remote_wins", "sync_local_wins")))
        assertEquals("unpushed", ids("workflow").first())
        assertTrue(ids("workflow").containsAll(listOf("list_dirty", "list_conflicts", "fetch", "pull", "commit", "push", "stats")))
        assertEquals(listOf("clean_1", "clean_5", "clean_10"), ids("repo_clean"))
        assertTrue(ids("others").containsAll(page.folderActions.map { it.id }))
    }

    @Test fun `Manual Sync is the four actions on one row and its Dangerous row is marked and equals the destructive ops`() {
        val m = menu.group("manual_sync")!!
        assertTrue(m.row); assertFalse(m.lines)
        assertEquals(listOf(RepoActions.STASH, RepoActions.PULL_REBASE, RepoActions.STASH_POP, RepoActions.AUTO_COMMIT, RepoActions.SOLVE_CONFLICTS), m.items.map { it.id })
        assertTrue(m.items.all { it.icon.isNotBlank() })
        val danger = m.subgroups.single()
        assertTrue(danger.danger); assertTrue(danger.row)
        for (id in danger.items.map { it.id }) assertEquals(RepoActions.Route.CONFIRM, RepoActions.route(id))
        assertEquals(page.ops.filter { it.destructive }.map { it.id }.sorted(), danger.items.map { it.id }.sorted())
        assertEquals(RepoActions.DANGEROUS.sorted(), danger.items.map { it.id }.sorted())
    }

    @Test fun `Configs-Auth keeps only the auth controls - no clone or depth item`() {
        val auth = ids("configs_auth")
        val cloneOrDepth = setOf(RepoActions.CLONE_FULL, RepoActions.CLONE_DEPTH1) + page.depthActions.map { it.id }
        assertTrue(auth.isNotEmpty())
        for (id in auth) {
            assertFalse("$id is a clone/depth item", id in cloneOrDepth)
            assertFalse("$id names a depth", id.contains("depth") || id.contains("bare"))
        }
        assertTrue("remote" in auth)
    }

    @Test fun `every group and item carries a label and a Spanish translation`() {
        for (g in menu.groups) {
            assertTrue(g.id, g.label.isNotBlank() && g.i18n["es"].orEmpty().isNotBlank())
            for (sub in g.subgroups) assertTrue(sub.id, sub.label.isNotBlank() && sub.i18n["es"].orEmpty().isNotBlank())
            for (i in g.items + g.subgroups.flatMap { it.items }) assertTrue(i.id, i.label.isNotBlank() && i.i18n["es"].orEmpty().isNotBlank())
        }
        assertEquals("Ir a", menu.group("goto")!!.labelFor("es"))
        assertEquals("Goto", menu.group("goto")!!.labelFor("fr"))
    }

    @Test fun `AutoSync defaults to 15 minutes, floored at WorkManager's 15`() {
        val a = menu.autoSync
        assertEquals(15L, a.defaultMinutes); assertEquals(15L, a.minMinutes); assertFalse(a.defaultOn)
        assertTrue(a.optionsMinutes.contains(15L)); assertTrue(a.optionsMinutes.all { it >= 15 })
        assertEquals(listOf("autosync_toggle", "autosync_every"), ids("configs_autosync"))
        val bogus = Declarations.parseRepoMenu(Json.parseToJsonElement("""{"autosync":{"default_minutes":5,"min_minutes":1,"options_minutes":[1,5,30]}}"""))
        assertEquals(15L, bogus.autoSync.defaultMinutes); assertEquals(listOf(30L), bogus.autoSync.optionsMinutes)
        assertEquals(15L, Declarations.parseRepoMenu(null).autoSync.defaultMinutes)
    }

    @Test fun `AutoSync period, toggle and the worker's tick`() {
        assertEquals(15L, AutoSyncPlan.minutes(0, 15)); assertEquals(30L, AutoSyncPlan.minutes(30, 15)); assertEquals(15L, AutoSyncPlan.minutes(5, 15))
        assertEquals(true to 15L, AutoSyncPlan.toggled(true, 0, 15))
        assertEquals(false to 60L, AutoSyncPlan.toggled(false, 60, 15))
        assertEquals(15L, AutoSyncPlan.tickMinutes(60, listOf(15, 360)))
        assertEquals(60L, AutoSyncPlan.tickMinutes(60, emptyList()))
        assertEquals(15L, AutoSyncPlan.tickMinutes(60, listOf(1)))
        assertEquals(30L, AutoSyncPlan.tickMinutes(60, listOf(0, 30)))
    }

    @Test fun `the next run is the repository's own period after its last sync, never before the worker's tick`() {
        val now = 1_700_000_000L
        assertNull(AutoSyncPlan.nextRunMinutes(false, 15, now, now, 3))
        assertEquals(15L, AutoSyncPlan.nextRunMinutes(true, 15, now, now, 3))
        assertEquals(10L, AutoSyncPlan.nextRunMinutes(true, 15, now - 5 * 60, now, null))
        assertEquals(7L, AutoSyncPlan.nextRunMinutes(true, 15, 0, now, 7))
        assertEquals(0L, AutoSyncPlan.nextRunMinutes(true, 15, now - 3600, now, null))
        // The worker serves the same period: due once 15 minutes have passed, not before.
        assertTrue(SyncSchedule.isDue(true, 15, 60, now - 15 * 60, now, false, null))
        assertFalse(SyncSchedule.isDue(true, 15, 60, now - 5 * 60, now, false, null))
    }
}
