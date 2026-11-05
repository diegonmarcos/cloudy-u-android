package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1138 the actions (#1289: grouping and order are RepoMenuTest's), the confirmation gates, and the new git verbs' scripts. */
class RepoActionsTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val page: Declarations.GitPageDecl by lazy {
        val b: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
        Declarations.parseGitPage(b["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
    }

    @Test fun `the bulk verbs are declared top-bar items, and stash-push is a compound verb`() {
        // #1315 the bar is data (ui.sync.git.top_bar): Sync all / Stash & push all / Fetch all / Pull all reference the compound and op ids.
        val b: JsonObject = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject
        val page = Declarations.parseGitPage(b["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        val items = page.topBar.groups.flatMap { it.items }
        assertTrue(items.any { it.kind == "compound" && it.op == RepoActions.SYNC_NO_WINNER })
        assertTrue(items.any { it.kind == "compound" && it.op == RepoActions.STASH_PUSH && it.danger && it.confirm })
        assertTrue(items.any { it.kind == "pull" && it.force } && items.any { it.kind == "pull" && !it.force })
        assertTrue(RepoActions.isCompound(RepoActions.STASH_PUSH))
        assertEquals("sync all has no winner", "sync_no_winner", RepoActions.SYNC_NO_WINNER)
    }

    @Test fun `the Cloud Gitea way carries its owner-given name`() {
        assertEquals("Cloud Gitea (Self-hosted)", page.ways.first { it.rung == "gitea" }.label)
    }

    @Test fun `Dangerous and Repo clean ask first, every other action runs on the tap`() {
        for (id in RepoActions.DANGEROUS + RepoActions.REPO_CLEAN) assertEquals("$id must be gated", RepoActions.Route.CONFIRM, RepoActions.route(id))
        for (id in page.repoMenu.allIds - (RepoActions.DANGEROUS + RepoActions.REPO_CLEAN).toSet()) assertEquals("$id must not be gated", RepoActions.Route.RUN, RepoActions.route(id))
    }

    @Test fun `repo clean keeps depth 1, 5 or 10 and the sync verbs and the trim are the compound ones`() {
        assertEquals(listOf(1, 5, 10), RepoActions.REPO_CLEAN.map { RepoActions.cleanDepth(it) })
        assertEquals(0, RepoActions.cleanDepth("fetch"))
        for (id in listOf("sync_no_winner", "sync_remote_wins", "sync_local_wins", "clean_1", "clean_5", "clean_10")) assertTrue(id, RepoActions.isCompound(id))
        for (id in listOf("fetch", "force_pull", "force_push", "sync_now")) assertFalse(id, RepoActions.isCompound(id))
    }

    // ── the terminal-root scripts of the verbs the engine has no method for ──
    private val dir = "/root/cloud-shared-store/git/r"
    private fun plan(verb: String, args: JSONObject = JSONObject(), auth: JSONObject? = null) = TermuxGitWire.plan(verb, dir, args, auth)

    @Test fun `repo clean is local only - it fetches to a depth, expires the reflog and collects, and never pushes`() {
        val p = plan("shrink", JSONObject().put("depth", 5)).first!!
        assertTrue(p.script.contains("fetch --depth 5 'origin'"))
        assertTrue(p.script.contains("reflog expire --expire=now --all"))
        assertTrue(p.script.contains("gc --prune=now"))
        assertFalse(p.script.contains("push"))
        assertTrue(p.network)
        for (bad in listOf(0, -1, 1001)) assertNull("depth $bad", plan("shrink", JSONObject().put("depth", bad)).first)
    }

    @Test fun `sync-local-wins pushes with a lease, never a bare force`() {
        val p = plan("force_push_lease").first!!
        assertTrue(p.script.contains("push --force-with-lease 'origin'"))
        assertFalse(p.script.contains("push --force 'origin'"))
    }

    @Test fun `sync-no-winner merges the upstream and aborts a conflicted merge only when a merge is in progress`() {
        val p = plan("merge_upstream", JSONObject().put("name", "n").put("email", "e@x")).first!!
        assertTrue(p.script.contains("fetch 'origin'"))
        assertTrue(p.script.contains("merge --no-edit '@{u}'"))
        assertTrue(p.script.contains("MERGE_HEAD") && p.script.contains("merge --abort"))
        assertFalse("a refused merge must never hard-reset the tree", p.script.contains("reset --hard"))
    }

    @Test fun `sync-remote-wins backs up local changes, untracked included, before the reset`() {
        val p = plan("stash", JSONObject().put("name", "n").put("email", "e@x").put("message", "before remote-wins")).first!!
        assertTrue(p.script.contains("stash push --include-untracked -m 'before remote-wins'"))
        assertFalse(p.network)
    }

    @Test fun `the new verbs answer a GitOpResult and are still quoted and credential-free in the script`() {
        for (v in listOf("force_push_lease", "merge_upstream", "stash", "shrink")) assertTrue(v, v in TermuxGitWire.OPS)
        val auth = JSONObject().put("kind", "https").put("username", "u").put("secret", "SECRET-TOKEN")
        val p = plan("force_push_lease", JSONObject(), auth).first!!
        assertFalse(p.script.contains("SECRET-TOKEN")); assertTrue(p.stdin.startsWith("Authorization: Basic "))
        assertNotNull(plan("merge_upstream").first)
    }

    @Test fun `#1195 a repository that is not cloned keeps the remote actions and disables the clone-required ones`() {
        for (id in listOf("web", RepoActions.PRS, RepoActions.RUNS, RepoActions.CLONE_OAUTH, RepoActions.CLONE_SSH)) assertFalse(id, RepoActions.needsClone(id))
        for (id in listOf("fetch", "pull", "push", "commit", "stats", RepoActions.OPEN_FOLDER, RepoActions.CHECKOUT_MAIN, RepoActions.BRANCHES, RepoActions.UNPUSHED, RepoActions.SYNC_NO_WINNER, RepoActions.STASH_PUSH, "force_pull", "force_push", RepoActions.CLEAN_1, "size", "hooks", RepoActions.SYNC_NOW, RepoActions.SETTINGS)) assertTrue(id, RepoActions.needsClone(id))
        // Every action of every group is either available without a clone or explained as needing one: nothing is silently missing.
        page.repoMenu.allIds.forEach { id -> assertEquals(id !in RepoActions.NO_CLONE_NEEDED, RepoActions.needsClone(id)) }
    }
}
