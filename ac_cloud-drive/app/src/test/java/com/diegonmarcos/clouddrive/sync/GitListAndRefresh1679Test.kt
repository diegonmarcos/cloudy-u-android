package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #1679 Cloud Drive > Sync > Git: no automatic clone, no built-in default list, ONE row model and ONE sheet before / during / after
 * a listing change, a cached list shown with no network on page open, pull-to-refresh re-reads the listing, the Refresh menu
 * (Refresh repo list | Fetch status | Fetch | Check lite | Check full) and rule 16 parity of every toolbar / sheet action.
 */
class GitListAndRefresh1679Test {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val main = File(root, "app/src/main/java/com/diegonmarcos/clouddrive")
    private val screen = File(main, "sync/GitReposScreen.kt").readText()
    private val git get() = Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]!!.jsonObject

    private fun repo(name: String, owner: String = "o") = GitHubRepos.Repo(name, owner, false, false, "main", "d", 1, "u", "p", "https://h/$owner/$name.git", "git@h:$owner/$name.git", "https://h/$owner/$name", 0, "Kotlin")

    // ── 1. no automatic clone ────────────────────────────────────────────────────────────────

    @Test fun `app start, workers and page open trigger zero clones`() {
        val clone = Regex("""(\.cloneInto\(|StoreGit\.clone\(|GitConnect\.clone\(|GitEngine\.clone\(|cloneMissing\(|\.cloneMode\()""")
        for (f in listOf("MainActivity.kt", "GitSyncWorker.kt", "UnisonSyncWorker.kt", "agents/ScreenshotAgentWorker.kt")) {
            assertFalse("$f starts a clone by itself", clone.containsMatchIn(File(main, f).readText()))
        }
        // The seed worker clones only behind the explicit gate, and the seed is OFF in the declaration.
        val seed = File(main, "StoreSeed.kt").readText()
        assertTrue(seed.indexOf("!CloneTrigger.mayClone(explicit)") in 0 until seed.indexOf("StoreGit.clone("))
        assertTrue(seed.contains("if (!BuildConfig.SEED_ENABLED) return Result.success()"))
        val storage = Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["storage"]!!.jsonObject
        assertEquals("false", storage["seed"]!!.jsonObject["enabled"]!!.jsonPrimitive.content)
        val manifest = Json.parseToJsonElement(File(root, "data/drive-git-repos.json").readText()).jsonObject["repos"]!!.jsonArray
        assertTrue("no repository is marked seed", manifest.none { it.jsonObject["seed"]?.jsonPrimitive?.content == "true" })
        // The page open: the cached list is drawn, the credential is learned, nothing is listed and nothing is cloned.
        val openEffect = screen.substringAfter("// #1679 NO LISTING ON OPEN").substringBefore("\n    }\n")
        assertTrue(openEffect.contains("ghCheck(list = false)"))
        assertFalse("the page open lists", openEffect.contains("fetchListing(") || openEffect.contains("fetchGhListing("))
        assertFalse("the page open clones", clone.containsMatchIn(openEffect))
        // Every clone call of the page sits in a user-tapped function (clone / cloneViaFleet / cloneViaGh / the host row's lambda / the Clone menu).
        assertFalse("a LaunchedEffect clones", Regex("""LaunchedEffect\([^)]*\)\s*\{[^}]*(cloneInto|cloneMode|cloneMissing)\(""").containsMatchIn(screen))
    }

    @Test fun `auto-cloned copies are listed for removal, clean ones removed, dirty ones kept`() {
        val gitRoot = "/store/git"
        val repos = listOf(
            ManagedRepo(id = "1", name = "cloudy-u-linux", path = "$gitRoot/cloudy-u-linux", remoteUrl = "u1"),
            ManagedRepo(id = "2", name = "mine", path = "$gitRoot/mine", remoteUrl = "u2"),
            ManagedRepo(id = "3", name = "cloudy-u-android", path = "/elsewhere/cloudy-u-android", remoteUrl = "u3"),
            ManagedRepo(id = "4", name = "old", path = "$gitRoot/old-name", remoteUrl = "u4"),
        )
        val names = git["auto_cloned_cleanup"]!!.jsonObject["names"]!!.jsonArray.map { it.jsonPrimitive.content }
        assertTrue(names.containsAll(listOf("cloudy-u-linux", "cloudy-u-android", "z-archived_front-data", "Projects-___")))
        val c = AutoClonedCleanup.candidates(repos, names + "renamed", gitRoot) { if (it == "old-name") "renamed" else it }
        assertEquals("only declared names directly in the store's git folder", listOf("1", "4"), c.map { it.id })
        val t = c.map { FolderActions.Target(it.path, it.remoteUrl, File(it.path).name) }
        val plan = AutoClonedCleanup.plan(t, mapOf(t[0].path to FolderActions.Guard(emptyList(), 0), t[1].path to FolderActions.Guard(listOf("a.txt"), 0)))
        assertEquals(listOf(t[0]), plan.remove)
        assertEquals(listOf(t[1]), plan.keep.map { it.first })
        assertTrue("an unread folder is kept, never removed blind", AutoClonedCleanup.plan(t, emptyMap()).remove.isEmpty())
        assertEquals("cloudy-u-linux", AutoClonedCleanup.parse("""{"git":{"auto_cloned_cleanup":{"names":["cloudy-u-linux"]}}}""").names.single())
        assertTrue("a broken block is empty, not a crash", AutoClonedCleanup.parse("{not json").names.isEmpty())
    }

    // ── 2. no built-in default list ──────────────────────────────────────────────────────────

    @Test fun `there is no built-in default list, the empty state offers Refresh`() {
        assertFalse(screen.contains("registryRows.ifEmpty { page.publicRepos }"))
        assertTrue(screen.contains("val publicRows = cloudPublic.repos.map {"))
        assertTrue(screen.contains("if (!setup && allEntries.isEmpty()) item(key = \"empty-refresh\")"))
        assertTrue(screen.contains("DriveTags.SYNC_GIT_EMPTY_REFRESH"))
    }

    // ── 3. one row model, one sheet ─────────────────────────────────────────────────────────

    @Test fun `the open sheet follows the same clone across a listing change`() {
        // before the listing: the clone's own row; during: same; after: the listing's row holds the same clone.
        val before = listOf("other-id1" to "id1", "other-id2" to "id2")
        val during = before
        val after = listOf("own-gh-o/a" to "id1", "own-gh-o/b" to null, "other-id2" to "id2")
        assertNull("unchanged rows keep the key", GitSheetKeys.follow("other-id1", before, during))
        assertEquals("own-gh-o/a", GitSheetKeys.follow("other-id1", during, after))
        assertNull(GitSheetKeys.follow("other-id2", during, after))
        // the listing goes away again (signed out): back to the clone's own row
        assertEquals("other-id1", GitSheetKeys.follow("own-gh-o/a", after, before))
        assertNull("nothing open", GitSheetKeys.follow("", before, after))
        // ONE sheet: a single ModalBottomSheet, the Refresh row and a STATUS block in both states.
        assertEquals(1, Regex("""ModalBottomSheet\(onDismissRequest = \{ expanded = "" \}, modifier = Modifier\.testTag\(DriveTags\.SYNC_GIT_DETAIL_SHEET\)\)""").findAll(screen).count())
        val sheet = screen.substringAfter("DriveTags.SYNC_GIT_DETAIL_SHEET)) {").substringBefore("repos.firstOrNull { settingsFor")
        assertTrue(sheet.indexOf("GitSheetRefreshRow(") in 0 until sheet.indexOf("if (e.cloned == null)"))
        val notCloned = screen.substringAfter("private fun GitRepoNotClonedBox(").substringBefore("/** One monospace fact line")
        assertTrue("the not-cloned sheet opens on the same STATUS block", notCloned.contains("RepoStatusBlock(RepoStatus.block(false, null)"))
        assertTrue(screen.contains("GitSheetKeys.follow(expanded, rowKeysBefore, rowKeys)"))
        assertEquals("one row model", 1, Regex("""private class TableEntry\(""").findAll(screen).count())
    }

    // ── 4. cache ────────────────────────────────────────────────────────────────────────────

    @Test fun `the listing cache round-trips and a broken file reads as empty`() {
        val tmp = TemporaryFolder().also { it.create() }
        try {
            val f = File(tmp.root, GitListingCache.FILE)
            val cache = GitListingCache(f)
            assertTrue(cache.load().isEmpty())
            cache.put(GitListingCache.SLOT_OWN, GitListingCache.Slot(listOf(repo("a"), repo("b")), 1000L, complete = false, viaGh = true))
            cache.put(GitListingCache.SLOT_FLEET, GitListingCache.Slot(listOf(repo("c", "x")), 2000L, rungId = "gitea"))
            val back = GitListingCache(f).load()
            assertEquals(listOf("a", "b"), back[GitListingCache.SLOT_OWN]!!.repos.map { it.name })
            assertEquals(repo("a"), back[GitListingCache.SLOT_OWN]!!.repos.first())
            assertTrue(back[GitListingCache.SLOT_OWN]!!.viaGh)
            assertEquals("gitea", back[GitListingCache.SLOT_FLEET]!!.rungId)
            assertEquals(2000L, GitListingCache.newest(back.values))
            f.writeText("{broken")
            assertTrue(GitListingCache(f).load().isEmpty())
        } finally { tmp.delete() }
    }

    @Test fun `the cached list is shown with no network on open, pull-to-refresh re-reads it`() {
        assertTrue(screen.contains("LaunchedEffect(Unit) { listingSlots = withContext(Dispatchers.IO) { listingCache.load() } }"))
        assertTrue(screen.contains("listingSlots[GitListingCache.SLOT_OWN]?.let { listing = GitListing(loaded = true"))
        // the Cloud tab: no fetch in its view effect, only in the explicit refresh
        val cloudEffect = screen.substringAfter("LaunchedEffect(view, listingSlots) {").substringBefore("\n    }")
        assertFalse(cloudEffect.contains("CloudRepos.fetch"))
        assertEquals("CloudRepos.fetch runs only in refreshCloudPublic", 1, Regex("""CloudRepos\.fetch\(""").findAll(screen).count())
        assertTrue(screen.substringAfter("fun refreshCloudPublic()").substringBefore("\n    }\n").contains("CloudRepos.fetch("))
        // the fleet listing no longer runs on page open
        assertFalse(screen.contains("LaunchedEffect(view, setup) {\n        if (view == GitView.ME && !setup && FleetSession.present)"))
        // pull-to-refresh and the Refresh menu re-read the listing
        assertTrue(screen.contains("onRefresh = { coordinator.refresh(force = true); refreshListings(registry = false);"))
        assertTrue(screen.contains("\"refresh_list\" -> { refreshListings(); coordinator.scanUnlisted() }"))
        assertTrue("the cache age is shown", screen.contains("R.string.git_list_as_of") && screen.contains("DriveTags.SYNC_GIT_LIST_AS_OF"))
        // every successful listing is cached
        assertEquals(3, Regex("""cacheListing\(GitListingCache\.SLOT_""").findAll(screen).count())
    }

    // ── 5. the Refresh menu ─────────────────────────────────────────────────────────────────

    @Test fun `the Refresh menu items and their git commands`() {
        val g = git["top_bar"]!!.jsonObject["groups"]!!.jsonArray[0].jsonObject
        assertEquals("Refresh", g["label"]!!.jsonPrimitive.content)
        val items = g["items"]!!.jsonArray.map { it.jsonObject }
        assertEquals(listOf("Refresh repo list", "Fetch status", "Fetch", "Check lite", "Check full"), items.map { it["label"]!!.jsonPrimitive.content })
        assertEquals(GitSheetRefresh.Route.REFRESH_LIST, GitSheetRefresh.route("refresh_list"))
        assertEquals(GitSheetRefresh.Route.FETCH_STATUS, GitSheetRefresh.route("fetch_status"))
        assertEquals(GitSheetRefresh.Route.FETCH, GitSheetRefresh.route("op", GitSyncCoordinator.OP_FETCH))
        assertEquals(GitSheetRefresh.Route.CHECK_FULL, GitSheetRefresh.route("check_full"))
        assertNull(GitSheetRefresh.route("op", "push"))
        // Fetch status is read-only: --dry-run and remote show origin, nothing else touches the clone.
        val script = StoreGit.fetchStatusScript("/g/repo")
        assertTrue(script.contains("fetch --dry-run -v") && script.contains("remote show origin"))
        assertFalse(Regex("""git "\$@" (pull|push|merge|reset|checkout|commit|fetch (?!--dry-run))""").containsMatchIn(script))
        assertEquals(listOf("git fetch --dry-run -v", "git remote show origin"), FetchStatus.COMMANDS)
        val out = """
            ${'$'} git fetch --dry-run -v
            From https://github.com/o/r
               1a2b3c4..5d6e7f8  main       -> origin/main
             = [up to date]      dev        -> origin/dev
            ${'$'} git remote show origin
            * remote origin
              HEAD branch: main
                main pushes to main (local out of date)
        """.trimIndent()
        assertEquals(1, FetchStatus.incoming(out).size)
        assertEquals("main", FetchStatus.headBranch(out))
        assertEquals("fetch would update 1 ref(s) · remote HEAD main · 1 branch(es) out of date", FetchStatus.summary(out))
        assertEquals("fetch would bring nothing", FetchStatus.summary(""))
        // the toolbar dispatch has a branch for each new kind, the coordinator runs them on all shown repos and per repo
        assertTrue(screen.contains("\"fetch_status\" -> coordinator.fetchStatusAll(scopeTargets)"))
        val coord = File(main, "sync/GitSyncCoordinator.kt").readText()
        assertTrue(coord.contains("fun fetchStatusAll(") && coord.contains("fun fetchStatus(repo: ManagedRepo)"))
    }

    // ── rule 16 parity ──────────────────────────────────────────────────────────────────────

    @Test fun `rule 16 every toolbar and sheet action of the previous version is still there`() {
        val ids = git["top_bar"]!!.jsonObject["groups"]!!.jsonArray.flatMap { gr -> gr.jsonObject["items"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content } }
        val previous = listOf(
            "check_lite", "check_full", "sync_server_wins", "sync_local_wins", "sync_all", "stash_all", "pull_rebase_all", "stash_pop_all", "auto_commit_all", "conflicts_all",
            "clone_all_full", "clone_all_depth3", "clone_all_bare_main", "clone_all_bare_blobless", "clone_all_bare_treeless", "logs",
            "check_light", "check_remote", "check_sizes", "scan_inventory", "cancel_checks", "pull_all", "force_pull_all", "stash_push_all", "fetch_all", "shrink_all",
            "last_report", "show_archived", "legend", "advanced",
        )
        assertEquals("lost toolbar actions", emptyList<String>(), previous.filterNot { it in ids })
        assertTrue(ids.containsAll(listOf("refresh_list", "fetch_status", "fetch_now", "remove_auto_cloned")))
        assertEquals(listOf("check", "sync", "clone", "logs", "more"), git["top_bar"]!!.jsonObject["groups"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content })
        // the sheet keeps both boxes (clone actions, ops, groups, metadata) and the Filters / Parentless / Unlisted panels stay
        for (s in listOf("GitRepoNotClonedBox(", "GitRepoOpsBox(", "RepoActionGroupsView(", "GitSheetTail(", "ParentlessPanel(", "UnlistedPanel(", "showFilters = true", "RepoStatusWindow(")) assertTrue(s, screen.contains(s))
        for (k in listOf("refresh_list", "fetch_status", "remove_auto_cloned", "check_light", "check_full", "op", "compound", "pull", "depth", "open_logs", "legend", "open_advanced", "open_conflicts"))
            assertTrue("no dispatch for $k", screen.contains("\"$k\" ->"))
    }
}
