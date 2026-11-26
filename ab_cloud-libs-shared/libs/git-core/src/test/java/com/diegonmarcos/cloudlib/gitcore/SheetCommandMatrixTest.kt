package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import org.eclipse.jgit.api.Git
import org.junit.AfterClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.BeforeClass
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #1934 THE COMMAND MATRIX: every option combination of the control sheet that changes the argv is EXECUTED with the engine the app ships
 * (JGitEngine through PlanExecutor) against throwaway repositories (a local bare "remote" plus a clone prepared in each state: clean, dirty,
 * ahead, behind, diverged, conflicted, detached, stashed), and the resulting state (refs, working tree, stash, conflicts, exit code, error text)
 * is asserted. Option combinations that normalise to the SAME argv are run once. The case counts are printed ("MATRIX ...") for the CI log.
 */
class SheetCommandMatrixTest {
    enum class St { CLEAN, DIRTY, AHEAD, BEHIND, DIVERGED, CONFLICTED, DETACHED, STASHED }

    /** One prepared repository: [work] tracks the bare [remote]; the state is already applied. */
    class Fx(val root: File, val state: St) {
        val remote = File(root, "remote.git")
        val work = File(root, "work")
        val url get() = "file://" + remote.absolutePath
        fun remoteMain(): String? = Git.open(remote).use { it.repository.resolve("refs/heads/main")?.name }
        fun head(): String = Git.open(work).use { it.repository.resolve("HEAD").name }
        fun ref(r: String): String? = Git.open(work).use { it.repository.resolve(r)?.name }
        fun text(p: String): String? = File(work, p).takeIf { it.exists() }?.readText()
        fun parents(): Int = Git.open(work).use { g -> g.repository.parseCommit(g.repository.resolve("HEAD")).parentCount }
        fun stash(): Int = JGitEngine(work).use { it.stashList().size }
        fun conflicts(): List<String> = JGitEngine(work).use { e -> e.status().conflicts.map { it.path } }
        fun subjects(): List<String> = JGitEngine(work).use { e -> e.log(50).map { it.summary } }
        fun snap() = listOf(head(), remoteMain(), ref("refs/remotes/origin/main"), stash().toString(), text("a.txt"), text("b.txt"), text("u.txt"))
    }

    companion object {
        private lateinit var base: File
        private val templates = mutableMapOf<St, File>()
        private var seq = 0
        var cases = 0
        var distinct = 0

        private fun write(dir: File, path: String, text: String) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }

        private fun build(state: St): File {
            val root = File(base, "tpl-" + state.name.lowercase()); root.mkdirs()
            val bare = File(root, "remote.git"); val work = File(root, "work"); val other = File(root, "other")
            Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
            val url = "file://" + bare.absolutePath
            Git.init().setInitialBranch("main").setDirectory(work).call().close()
            JGitEngine(work).use { e ->
                write(work, "a.txt", "line\n"); write(work, "b.txt", "b\n"); e.stageAll(); e.commit("seed", "t", "t@x")
                e.addRemote("origin", url); assertTrue(e.push().ok)
            }
            JGitEngine.clone(url, other).close()
            fun remoteMoves(path: String, text: String) = JGitEngine(other).use { o ->
                assertTrue(o.pull().ok); write(other, path, text); o.stageAll(); o.commit("server", "s", "s@x"); assertTrue(o.push().ok)
            }
            fun localCommit(path: String, text: String) = JGitEngine(work).use { e -> write(work, path, text); e.stageAll(); e.commit("phone", "t", "t@x") }
            when (state) {
                St.CLEAN -> {}
                St.DIRTY -> { write(work, "b.txt", "b-dirty\n"); write(work, "u.txt", "untracked\n") }
                St.AHEAD -> localCommit("local.txt", "local\n")
                St.BEHIND -> remoteMoves("server.txt", "server\n")
                St.DIVERGED -> { remoteMoves("server.txt", "server\n"); localCommit("local.txt", "local\n") }
                St.CONFLICTED -> { remoteMoves("a.txt", "server\n"); localCommit("a.txt", "phone\n") }
                St.DETACHED -> Git.open(work).use { g -> g.checkout().setName(g.repository.resolve("HEAD").name).call() }
                St.STASHED -> JGitEngine(work).use { e -> write(work, "b.txt", "b-stashed\n"); assertTrue(e.stashPush("t").ok) }
            }
            other.deleteRecursively()
            return root
        }

        @BeforeClass @JvmStatic fun prepare() {
            base = Files.createTempDirectory("sheet-matrix").toFile()
            St.values().forEach { templates[it] = build(it) }
        }

        @AfterClass @JvmStatic fun cleanup() {
            println("MATRIX cases=$cases distinct_argv_plans=$distinct")
            base.deleteRecursively()
            // the matrix really ran: 2352 distinct sync plans + 46 core plans x 8 states + 144 clone plans + the targeted cases
            assertTrue("matrix ran only $cases cases", cases >= 2600)
        }

        /** A fresh copy of the template of [state], its origin pointed at the copied bare remote. */
        fun fixture(state: St): Fx {
            val root = File(base, "run-" + (seq++)); templates.getValue(state).copyRecursively(root)
            val fx = Fx(root, state)
            JGitEngine(fx.work).use { it.setRemoteUrl("origin", fx.url) }
            return fx
        }

        private fun bools(n: Int): List<List<Boolean>> = (0 until (1 shl n)).map { m -> (0 until n).map { (m shr it) and 1 == 1 } }

        /** The FULL product of the Sync tab's options, normalised and de-duplicated by the argv they produce. */
        fun syncPlans(): List<SyncOptions> {
            val all = mutableListOf<SyncOptions>()
            for (d in listOf("fetch", "pull", "push", "both")) for (s in listOf("ff", "merge", "rebase", "reset")) for (w in listOf("none", "remote", "local"))
                for (dy in listOf("none", "autostash", "autocommit")) for (sc in listOf("current", "all")) for (f in bools(6))
                    all += SyncOptions(d, s, w, dy, "chore: auto-sync", sc, f[0], f[1], f[2], f[3], f[4], f[5])
            return all.distinctBy { it.steps().map { st -> st.argv } }
        }

        fun clonePlans(): List<CloneOptions> {
            val all = mutableListOf<CloneOptions>()
            for (dest in listOf("store", "workspace", "mirror")) for (ws in listOf("working", "bare")) for (br in listOf("all", "main"))
                for ((h, depth) in listOf("deep" to 3, "shallow" to 1, "shallow" to 3, "shallow" to 10)) for (t in listOf(true, false)) for (b in listOf(true, false))
                    all += CloneOptions(dest, ws, br, "main", h, depth, t, b)
            return all.distinctBy { it.step("U", it.destPath("r", "S", "W", "M")).argv }
        }
    }

    private fun exec(fx: Fx, o: SyncOptions): Pair<GitOpResult, PlanExecutor> {
        val ex = PlanExecutor(fx.work)
        return ex.run(o.steps()) to ex
    }

    // ── preview == executed argv, for the whole product ─────────────────────────────────────────────────────────────────────

    @Test fun previewEqualsExecutedArgvForEveryDistinctSyncPlan() {
        val plans = syncPlans()
        distinct += plans.size
        plans.forEachIndexed { i, o ->
            val st = St.values()[i % St.values().size]
            val fx = fixture(st)
            val before = fx.snap()
            val (res, ex) = exec(fx, o)
            cases++
            val argv = o.steps().map { it.argv }
            assertEquals("executed argv is a prefix of the previewed plan: " + GitStep.preview(o.steps()), argv.take(ex.executed.size), ex.executed)
            if (res.ok) assertEquals("an ok run executed the whole preview: " + GitStep.preview(o.steps()), argv.size, ex.executed.size)
            if (o.unsupported() != null) { assertFalse(GitStep.preview(o.steps()) + " must fail, the engine cannot do it", res.ok); assertTrue(res.summary.isNotBlank()) }
            val n = o.normalized()
            if (n.dryRun) assertEquals("a dry run changes nothing: " + GitStep.preview(o.steps()), before, fx.snap())
            if (n.direction == "fetch" && n.unsupported() == null) assertEquals("fetch never moves HEAD", templateHead(st), fx.head())
            fx.root.deleteRecursively()
        }
    }

    private fun templateHead(st: St): String = Git.open(File(templates.getValue(st), "work")).use { it.repository.resolve("HEAD").name }

    // ── the core matrix: direction x strategy x winner x dirty handling, EVERY repository state, state asserted ─────────────

    @Test fun coreMatrixAcrossEveryRepositoryState() {
        val core = syncPlans().filter { it.scope == "current" && !it.prune && !it.tags && !it.submodules && !it.forceLease && !it.forceRaw && !it.dryRun }
        assertTrue("core plans: ${core.size}", core.size >= 20)
        for (o in core) for (st in St.values()) {
            val fx = fixture(st)
            val remoteBefore = fx.remoteMain(); val headBefore = fx.head()
            val (res, ex) = exec(fx, o)
            cases++
            val n = o.normalized(); val tag = "[" + GitStep.preview(o.steps()) + " @ " + st + "] "
            assertTrue(tag + "argv", o.steps().map { it.argv }.take(ex.executed.size) == ex.executed)
            if (o.unsupported() != null) { assertFalse(tag + "unsupported must fail", res.ok); continue }
            if (n.dirty == "autocommit" && st == St.DIRTY && n.direction != "fetch" && n.strategy != "reset") assertTrue(tag + "auto-commit made its commit", fx.subjects().contains("chore: auto-sync"))
            if (n.direction == "fetch") {
                assertEquals(tag, headBefore, fx.head()); assertEquals(tag, remoteBefore, fx.remoteMain())
                assertTrue(tag + res.summary, res.ok); assertEquals(tag, fx.remoteMain(), fx.ref("refs/remotes/origin/main"))
                continue
            }
            if (st == St.DETACHED) { assertFalse(tag + "no branch to " + n.direction + ": " + res.summary, res.ok); assertEquals(tag, remoteBefore, fx.remoteMain()); continue }
            if (n.pulls && n.strategy == "reset") {
                assertTrue(tag + res.summary, res.ok); assertEquals(tag, fx.remoteMain(), fx.head()); assertTrue(tag + "tracked edits discarded", fx.text("b.txt") != "b-dirty\n"); continue
            }
            if (n.pulls && n.dirty != "autocommit") pullExpectation(fx, n, st, res, headBefore, tag)
            if (n.direction == "push" && n.dirty != "autocommit") pushExpectation(fx, st, res, remoteBefore, tag)
            if (n.dirty == "autostash" && st == St.DIRTY && n.pulls && res.ok) { assertEquals(tag + "stash popped back", 0, fx.stash()); assertEquals(tag, "b-dirty\n", fx.text("b.txt")) }
            fx.root.deleteRecursively()
        }
    }

    private fun pullExpectation(fx: Fx, n: SyncOptions, st: St, res: GitOpResult, headBefore: String, tag: String) {
        if (n.direction == "both") return          // the push half is asserted on its own below
        val remote = fx.remoteMain()
        when (n.strategy) {
            "ff" -> when (st) {
                St.BEHIND -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, remote, fx.head()) }
                St.DIVERGED, St.CONFLICTED -> { assertFalse(tag + "ff-only refuses a divergence", res.ok); assertEquals(tag, headBefore, fx.head()) }
                St.CLEAN, St.AHEAD, St.STASHED -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, headBefore, fx.head()) }
                else -> {}
            }
            "merge" -> when (st) {
                St.BEHIND -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, remote, fx.head()) }
                St.DIVERGED -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, 2, fx.parents()); assertNotNull(tag, fx.text("server.txt")); assertNotNull(tag, fx.text("local.txt")) }
                St.CONFLICTED -> when (n.winner) {
                    "none" -> { assertFalse(tag + res.summary, res.ok); assertEquals(tag, listOf("a.txt"), fx.conflicts()) }
                    "remote" -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, "server\n", fx.text("a.txt")); assertTrue(tag, fx.conflicts().isEmpty()) }
                    else -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, "phone\n", fx.text("a.txt")); assertTrue(tag, fx.conflicts().isEmpty()) }
                }
                else -> {}
            }
            "rebase" -> when (st) {
                St.BEHIND -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, remote, fx.head()) }
                St.DIVERGED -> if (n.winner == "none") { assertTrue(tag + res.summary, res.ok); assertEquals(tag, 1, fx.parents()); assertNotNull(tag, fx.text("server.txt")); assertNotNull(tag, fx.text("local.txt")) }
                St.CONFLICTED -> assertFalse(tag + res.summary, res.ok)
                else -> {}
            }
        }
    }

    private fun pushExpectation(fx: Fx, st: St, res: GitOpResult, remoteBefore: String?, tag: String) {
        when (st) {
            St.AHEAD -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, fx.head(), fx.remoteMain()) }
            St.BEHIND, St.DIVERGED, St.CONFLICTED -> { assertFalse(tag + "a plain push is refused: " + res.summary, res.ok); assertEquals(tag, remoteBefore, fx.remoteMain()) }
            St.CLEAN, St.DIRTY, St.STASHED -> { assertTrue(tag + res.summary, res.ok); assertEquals(tag, remoteBefore, fx.remoteMain()) }
            else -> {}
        }
    }

    // ── flags that change state, each asserted on the state they act on ─────────────────────────────────────────────────────

    private fun opts(vararg f: Pair<String, Any>): SyncOptions {
        var o = SyncOptions(direction = "push", strategy = "ff")
        f.forEach { (k, v) -> o = when (k) {
            "direction" -> o.copy(direction = v as String); "strategy" -> o.copy(strategy = v as String); "scope" -> o.copy(scope = v as String)
            "prune" -> o.copy(prune = v as Boolean); "tags" -> o.copy(tags = v as Boolean); "forceLease" -> o.copy(forceLease = v as Boolean)
            "forceRaw" -> o.copy(forceRaw = v as Boolean); "dryRun" -> o.copy(dryRun = v as Boolean); else -> error(k) } }
        return o
    }

    @Test fun forcePushOverwritesTheRemoteAndLeaseRefusesAStaleOne() {
        for (st in listOf(St.AHEAD, St.DIVERGED, St.BEHIND, St.CONFLICTED)) {
            val raw = fixture(st); val r = exec(raw, opts("forceRaw" to true)).first; cases++
            assertTrue("force push @ $st: ${r.summary}", r.ok); assertEquals(raw.head(), raw.remoteMain())
            val lease = fixture(st); val before = lease.remoteMain(); val l = exec(lease, opts("forceLease" to true)).first; cases++
            if (st == St.AHEAD) { assertTrue(l.summary, l.ok); assertEquals(lease.head(), lease.remoteMain()) }
            else { assertFalse("lease @ $st must refuse a remote that moved: ${l.summary}", l.ok); assertEquals(before, lease.remoteMain()) }
        }
    }

    @Test fun dryRunTouchesNothing() {
        for (st in St.values()) for (d in listOf("fetch", "pull", "push", "both")) for (s in listOf("ff", "reset")) {
            val fx = fixture(st); val before = fx.snap()
            val res = exec(fx, SyncOptions(direction = d, strategy = s, dryRun = true)).first; cases++
            assertEquals("dry run $d/$s @ $st (${res.summary})", before, fx.snap())
        }
    }

    @Test fun fetchPrunesDeletedRemoteBranchesAndTagsAreFetchedAndPushed() {
        val fx = fixture(St.CLEAN)
        val other = File(fx.root, "other"); JGitEngine.clone(fx.url, other).close()
        Git.open(other).use { g ->
            write(other, "t.txt", "t\n"); g.add().addFilepattern(".").call(); g.commit().setMessage("t").setAuthor("t", "t@x").call()
            g.branchCreate().setName("old").call(); g.tag().setName("v1").call()
            g.push().setRemote("origin").setPushAll().setPushTags().call()
        }
        assertTrue(exec(fx, opts("direction" to "fetch", "tags" to true)).first.ok); cases++
        assertNotNull("tag v1 fetched with --tags", fx.ref("refs/tags/v1")); assertNotNull(fx.ref("refs/remotes/origin/old"))
        Git.open(other).use { g -> g.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec(":refs/heads/old")).call() }
        assertTrue(exec(fx, opts("direction" to "fetch")).first.ok); cases++
        assertNotNull("without --prune the stale tracking ref stays", fx.ref("refs/remotes/origin/old"))
        assertTrue(exec(fx, opts("direction" to "fetch", "prune" to true)).first.ok); cases++
        assertEquals("--prune removes it", null, fx.ref("refs/remotes/origin/old"))
        // push --tags and --all
        val p = fixture(St.AHEAD)
        Git.open(p.work).use { g -> g.tag().setName("local-tag").call(); g.branchCreate().setName("feature").call() }
        assertTrue(exec(p, opts("tags" to true, "scope" to "all")).first.ok); cases++
        Git.open(p.remote).use { g -> assertNotNull(g.repository.resolve("refs/tags/local-tag")); assertNotNull(g.repository.resolve("refs/heads/feature")) }
    }

    private fun write(dir: File, path: String, text: String) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }

    // ── clone: destination x workspace x branches x depth x data included, executed and the clone inspected ─────────────────

    private fun git(dir: File, vararg a: String): GitOpResult {
        val p = ProcessBuilder(listOf("git") + a).directory(dir).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
        return GitOpResult(code == 0, out.trim().lineSequence().firstOrNull().orEmpty(), out)
    }

    private val binary: (List<String>, File) -> GitOpResult = { argv, dir -> dir.mkdirs(); git(dir, *argv.drop(1).toTypedArray()) }

    @Test fun cloneMatrixIsExecutedAndInspected() {
        val fx = fixture(St.CLEAN)
        val other = File(fx.root, "other"); JGitEngine.clone(fx.url, other).close()
        Git.open(other).use { g ->
            for (i in 1..4) { write(other, "h$i.txt", "$i\n"); g.add().addFilepattern(".").call(); g.commit().setMessage("h$i").setAuthor("t", "t@x").call() }
            g.branchCreate().setName("dev").call(); g.push().setRemote("origin").setPushAll().call()
        }
        git(fx.remote, "config", "uploadpack.allowFilter", "true"); git(fx.remote, "config", "uploadpack.allowAnySHA1InWant", "true")
        val plans = clonePlans(); assertTrue(plans.size >= 40)
        distinct += plans.size
        plans.forEachIndexed { i, c ->
            val dest = File(fx.root, "clone-$i")
            val step = c.step(fx.url, dest.absolutePath)
            val ex = PlanExecutor(fx.root, binary = binary)
            val res = ex.run(listOf(step)); cases++
            val tag = "[" + step.line().replace(fx.root.absolutePath, "<root>") + "] "
            assertTrue(tag + res.summary + res.details, res.ok)
            assertEquals(tag + "executed argv == preview argv", listOf(step.argv), ex.executed)
            assertTrue(tag, dest.exists())
            val bare = c.workspace == "bare"
            assertEquals(tag + "bare has no work tree", !bare, File(dest, "h4.txt").exists())
            val gitDir = if (bare) dest else File(dest, ".git")
            Git.open(gitDir).use { g ->
                val heads = (g.repository.refDatabase.getRefsByPrefix("refs/heads/") + g.repository.refDatabase.getRefsByPrefix("refs/remotes/origin/")).map { it.name.substringAfterLast('/') }.distinct()
                if (c.branches == "main") assertEquals(tag + "single branch", listOf("main"), heads.filter { it != "HEAD" }) else assertTrue(tag + "all branches: $heads", "dev" in heads && "main" in heads)
                val n = org.eclipse.jgit.revwalk.RevWalk(g.repository).use { w -> w.markStart(w.parseCommit(g.repository.resolve("HEAD"))); w.count() }
                if (c.history == "shallow") assertTrue(tag + "depth ${c.depth}: $n commits", n <= c.depth) else assertEquals(tag + "full history", 5, n)
                if (c.filter.isNotBlank()) assertEquals(tag + "partial clone filter", c.filter, g.repository.config.getString("remote", "origin", "partialclonefilter"))
            }
        }
    }

    // ── workflow and manage actions ────────────────────────────────────────────────────────────────────────────────────

    @Test fun workflowAndManageActions() {
        // Stage & Commit, Commits Unpushed, Status, List Dirty, Commit History
        var fx = fixture(St.DIRTY)
        JGitEngine(fx.work).use { e ->
            val s = e.status(); assertEquals(listOf("b.txt", "u.txt"), s.files.map { it.path }.sorted()); cases++     // List Dirty
            assertEquals("## main...origin/main [ahead 0, behind 0]", e.statusLine().summary); cases++               // Status
            e.stageAll(); e.commit("wf commit", "t", "t@x"); cases++                                                 // Stage & Commit
            assertEquals(1, e.status().ahead); assertEquals("wf commit", e.log(1).first().summary); cases++          // Commits Unpushed, Commit History
            assertEquals(listOf("main", "origin/main"), e.branches().map { it.name }.sorted()); cases++              // Branches: List
        }
        // List Conflicts + Conflict Solver
        fx = fixture(St.CONFLICTED)
        JGitEngine(fx.work).use { e ->
            assertFalse(e.mergeUpstream().ok); assertEquals(listOf("a.txt"), e.status().conflicts.map { it.path }); cases++
            e.resolve("a.txt", com.diegonmarcos.cloudlib.gitsync.ConflictSide.THEIRS); assertTrue(e.status().conflicts.isEmpty()); cases++
        }
        // Stash / Stash Pop / Pull (Rebase) / Checkout main / Fix origin
        fx = fixture(St.DIRTY)
        JGitEngine(fx.work).use { e ->
            assertTrue(e.stashPush("m").ok); assertEquals(1, e.stashList().size); assertEquals("b\n", fx.text("b.txt")); cases++
            assertTrue(e.stashPop().ok); assertEquals(0, e.stashList().size); assertEquals("b-dirty\n", fx.text("b.txt")); cases++
            assertTrue(e.checkoutMain().ok); cases++
            e.setRemoteUrl("origin", "file:///elsewhere.git"); assertEquals("file:///elsewhere.git", e.remotes().first { it.name == "origin" }.fetchUrl); cases++
        }
        fx = fixture(St.BEHIND)
        JGitEngine(fx.work).use { e -> assertTrue(e.pull(rebase = true).ok); assertEquals(fx.remoteMain(), fx.head()); cases++ }
        // Repo Clean (keep depth N + gc) on a clone with history
        val src = fixture(St.CLEAN)
        Git.open(src.work).use { g -> for (i in 1..4) { write(src.work, "h$i.txt", "$i\n"); g.add().addFilepattern(".").call(); g.commit().setMessage("h$i").setAuthor("t", "t@x").call() }; g.push().setRemote("origin").call() }
        for (depth in listOf(1, 5, 10)) {
            val c = File(src.root, "clean-$depth"); assertTrue(git(src.root, "clone", src.url, c.absolutePath).ok)
            val ex = PlanExecutor(c, binary = binary); val res = ex.run(ManagePlan.keepDepth(depth)); cases++
            assertTrue("keep depth $depth: ${res.summary} ${res.details}", res.ok)
            assertEquals(ManagePlan.keepDepth(depth).map { it.argv }, ex.executed)
            val n = git(c, "rev-list", "--count", "HEAD").details.trim().toInt()
            assertEquals("kept depth $depth", minOf(depth, 5), n)
            assertEquals(src.remoteMain(), git(src.remote, "rev-parse", "main").details.trim())   // the remote is untouched
        }
    }
}
