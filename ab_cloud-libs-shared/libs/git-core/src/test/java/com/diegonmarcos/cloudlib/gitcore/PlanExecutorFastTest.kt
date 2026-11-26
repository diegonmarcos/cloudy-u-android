package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #1934 THE FAST SLICE of the command matrix, small enough to be replayed per mutant by PIT: every executor verb, flag, route and failure path and every
 * option-driven engine verb, on tiny throwaway repositories (a local bare remote, a work clone and a second clone that moves the remote). The heavy
 * SheetCommandMatrixTest stays out of the per-mutant replay; nothing is excluded from the mutation gate.
 */
class PlanExecutorFastTest {
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var other: File
    private lateinit var bare: File
    private val url get() = "file://" + bare.absolutePath

    private fun write(dir: File, path: String, text: String) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }
    private fun step(vararg a: String, tolerant: Boolean = false) = GitStep(listOf("git") + a, tolerant)
    private fun exec(vararg steps: GitStep, binary: ((List<String>, File) -> GitOpResult)? = null): Pair<GitOpResult, PlanExecutor> {
        val ex = PlanExecutor(work, binary = binary)
        return ex.run(steps.toList()) to ex
    }
    private fun remoteMain(): String? = Git.open(bare).use { it.repository.resolve("refs/heads/main")?.name }
    private fun head(dir: File = work): String = Git.open(dir).use { it.repository.resolve("HEAD").name }
    private fun ref(r: String): String? = Git.open(work).use { it.repository.resolve(r)?.name }
    private fun text(p: String): String? = File(work, p).takeIf { it.exists() }?.readText()

    @Before fun setUp() {
        root = Files.createTempDirectory("plan-fast").toFile()
        bare = File(root, "remote.git"); work = File(root, "work"); other = File(root, "other")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
        Git.init().setInitialBranch("main").setDirectory(work).call().close()
        JGitEngine(work).use { e ->
            write(work, "a.txt", "line\n"); e.stageAll(); e.commit("seed", "t", "t@x")
            e.addRemote("origin", url); assertTrue(e.push().ok)
        }
        JGitEngine.clone(url, other).close()
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun serverCommits(path: String = "server.txt", body: String = "server\n") = JGitEngine(other).use { o ->
        assertTrue(o.pull().ok); write(other, path, body); o.stageAll(); o.commit("server", "s", "s@x"); assertTrue(o.push().ok)
    }
    private fun localCommit(path: String = "local.txt", body: String = "local\n") = JGitEngine(work).use { e -> write(work, path, body); e.stageAll(); e.commit("phone", "t", "t@x") }
    private fun detach() = Git.open(work).use { g -> g.checkout().setName(g.repository.resolve("HEAD").name).call() }

    // ── PlanExecutor.run ──────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun runJoinsEveryStepAndStopsAtTheFirstFailure() {
        val (ok, ex) = exec(step("status", "-sb"), step("fetch"))
        assertTrue(ok.summary, ok.ok); assertTrue(ok.summary.startsWith("git status -sb -> ## main...origin/main")); assertTrue(ok.summary.contains("; git fetch -> fetched origin: nothing new"))
        assertEquals(listOf(listOf("git", "status", "-sb"), listOf("git", "fetch")), ex.executed)
        val (bad, ex2) = exec(step("frobnicate"), step("status", "-sb"))
        assertFalse(bad.ok); assertTrue(bad.summary.contains("git frobnicate -> git frobnicate needs the git binary")); assertEquals(1, ex2.executed.size)
    }

    @Test fun aTolerantFailureDoesNotStopThePlan() {
        val (r, ex) = exec(step("commit", "-m", "x", tolerant = true), step("status", "-sb"))
        assertTrue(r.summary, r.ok); assertTrue(r.summary.contains("git commit -m x || true -> nothing to commit")); assertEquals(2, ex.executed.size)
    }

    @Test fun notAGitCommandIsRefusedAndThrownErrorsBecomeFailures() {
        assertFalse(exec(GitStep(listOf("ls", "-l"))).first.ok)
        assertFalse(exec(GitStep(listOf("git"))).first.ok)
        assertTrue(exec(GitStep(listOf("ls", "-l"))).first.summary.contains("not a git command"))
        val broken = PlanExecutor(File(root, "missing")).run(listOf(step("status", "-sb")))
        assertFalse(broken.ok); assertTrue(broken.summary.isNotBlank())
    }

    @Test fun submodulesAreRefusedInEveryForm() {
        for (f in listOf("--recurse-submodules", "--recurse-submodules=on-demand")) {
            val r = exec(step("fetch", f)).first
            assertFalse(r.ok); assertTrue(r.summary.contains("submodules")); assertEquals(remoteMain(), head())
        }
    }

    @Test fun addAndCommitStageAndCommitWithTheGivenMessage() {
        write(work, "n.txt", "new\n")
        val (r, _) = exec(step("add", "-A"), step("commit", "-m", "my message"))
        assertTrue(r.summary, r.ok); assertTrue(r.summary.contains("git commit -m \"my message\" -> committed "))
        assertEquals(listOf("my message", "seed"), JGitEngine(work).use { e -> e.log(5).map { it.summary } })
        assertEquals("PlanExecutor author", "cloud-drive", JGitEngine(work).use { it.log(1).first().author })
        val none = exec(step("commit", "-m", "again")).first
        assertFalse(none.ok); assertTrue(none.summary.contains("nothing to commit"))
    }

    @Test fun commitUsesTheConfiguredAuthor() {
        write(work, "n.txt", "new\n")
        val r = PlanExecutor(work, authorName = "Ann", authorEmail = "ann@x").run(listOf(step("add", "-A"), step("commit", "-m", "m")))
        assertTrue(r.ok); val c = JGitEngine(work).use { it.log(1).first() }; assertEquals("Ann", c.author); assertEquals("ann@x", c.email)
    }

    @Test fun statusReadsBranchUpstreamAndDivergence() {
        assertEquals("## main...origin/main [ahead 0, behind 0]", JGitEngine(work).use { it.statusLine().summary })
        localCommit(); write(work, "dirty.txt", "d\n")
        val s = JGitEngine(work).use { it.statusLine() }
        assertEquals("## main...origin/main [ahead 1, behind 0]", s.summary); assertEquals("dirty.txt", s.details)
        val fresh = File(root, "fresh"); Git.init().setInitialBranch("main").setDirectory(fresh).call().close()
        assertEquals("## main", JGitEngine(fresh).use { it.statusLine().summary })
    }

    // ── fetch ─────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun fetchBringsTheRemoteRefsAndDryRunChangesNothing() {
        serverCommits()
        val before = ref("refs/remotes/origin/main")
        val dry = exec(step("fetch", "--dry-run")).first
        assertTrue(dry.summary, dry.ok); assertTrue(dry.summary.contains("dry run: fetched origin")); assertEquals(before, ref("refs/remotes/origin/main"))
        val r = exec(step("fetch")).first
        assertTrue(r.summary.contains("fetched origin: 1 ref(s) updated")); assertEquals(remoteMain(), ref("refs/remotes/origin/main")); assertEquals(before != remoteMain(), true)
        assertTrue(exec(step("fetch")).first.summary.contains("fetched origin: nothing new"))
    }

    @Test fun fetchPrunesTagsAndAllRemotes() {
        Git.open(other).use { g -> g.branchCreate().setName("old").call(); g.tag().setName("v1").call(); g.push().setRemote("origin").setPushAll().setPushTags().call() }
        assertTrue(exec(step("fetch", "--tags")).first.ok)
        assertTrue(ref("refs/tags/v1") != null); assertTrue(ref("refs/remotes/origin/old") != null)
        Git.open(other).use { g -> g.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec(":refs/heads/old")).call() }
        assertTrue(exec(step("fetch")).first.ok); assertTrue(ref("refs/remotes/origin/old") != null)
        assertTrue(exec(step("fetch", "--prune")).first.ok); assertNull(ref("refs/remotes/origin/old"))
        JGitEngine(work).use { it.addRemote("backup", url) }
        assertEquals(listOf("backup", "origin"), JGitEngine(work).use { it.remoteNames() })
        val all = exec(step("fetch", "--all")).first
        assertTrue(all.ok); assertTrue(ref("refs/remotes/backup/main") != null)
    }

    @Test fun fetchFailureIsReportedAndStopsThePlan() {
        JGitEngine(work).use { it.setRemoteUrl("origin", "file://" + File(root, "gone.git").absolutePath) }
        val (r, ex) = exec(step("fetch"), step("status", "-sb"))
        assertFalse(r.ok); assertEquals(1, ex.executed.size)
    }

    // ── pull ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun pullFastForwardsAndRefusesADivergenceWithFfOnly() {
        serverCommits()
        val r = exec(step("pull", "--ff-only")).first
        assertTrue(r.summary, r.ok); assertTrue(r.summary.contains("pulled origin/main")); assertEquals(remoteMain(), head())
        localCommit("l2.txt"); serverCommits("s2.txt")
        val before = head()
        val d = exec(step("pull", "--ff-only")).first
        assertFalse(d.ok); assertTrue(d.summary.contains("pull refused: not a fast-forward")); assertEquals(before, head())
    }

    @Test fun pullMergeAndRebaseJoinADivergence() {
        localCommit(); serverCommits()
        val m = exec(step("pull", "--no-rebase")).first
        assertTrue(m.summary, m.ok); assertEquals(2, Git.open(work).use { g -> g.repository.parseCommit(g.repository.resolve("HEAD")).parentCount })
        assertEquals("server\n", text("server.txt")); assertEquals("local\n", text("local.txt"))
        tearDown(); setUp()
        localCommit(); serverCommits()
        val r = exec(step("pull", "--rebase")).first
        assertTrue(r.summary, r.ok); assertEquals(1, Git.open(work).use { g -> g.repository.parseCommit(g.repository.resolve("HEAD")).parentCount })
        assertEquals("server\n", text("server.txt")); assertEquals("local\n", text("local.txt"))
    }

    @Test fun pullConflictStopsAndNamesTheFile() {
        serverCommits("a.txt", "server\n"); localCommit("a.txt", "phone\n")
        val r = exec(step("pull", "--no-rebase")).first
        assertFalse(r.ok); assertTrue(r.summary.contains("pull conflicts: 1 file(s)")); assertEquals("a.txt", r.details)
        assertEquals(listOf("a.txt"), JGitEngine(work).use { e -> e.status().conflicts.map { it.path } })
    }

    @Test fun pullWinnerSettlesAMergeConflictFileByFile() {
        serverCommits("a.txt", "server\n"); localCommit("a.txt", "phone\n")
        val theirs = exec(step("pull", "--no-rebase", "-X", "theirs")).first
        assertTrue(theirs.summary, theirs.ok); assertTrue(theirs.summary.contains("settled for the remote")); assertEquals("server\n", text("a.txt"))
        assertTrue(JGitEngine(work).use { e -> e.status().conflicts.isEmpty() })
        tearDown(); setUp()
        serverCommits("a.txt", "server\n"); localCommit("a.txt", "phone\n")
        val ours = exec(step("pull", "--no-rebase", "-X", "ours")).first
        assertTrue(ours.summary, ours.ok); assertTrue(ours.summary.contains("settled for the local side")); assertEquals("phone\n", text("a.txt"))
    }

    @Test fun aWinnerWithRebaseIsRefusedBeforeAnythingRuns() {
        serverCommits(); val before = ref("refs/remotes/origin/main")
        val r = exec(step("pull", "--rebase", "-X", "theirs")).first
        assertFalse(r.ok); assertTrue(r.summary.contains("winner with rebase")); assertEquals(before, ref("refs/remotes/origin/main"))
        val dry = exec(step("pull", "--rebase", "-X", "ours", "--dry-run")).first
        assertFalse(dry.ok)
    }

    @Test fun pullDryRunFetchesNothingAndMovesNothing() {
        serverCommits(); val h = head(); val t = ref("refs/remotes/origin/main")
        val r = exec(step("pull", "--ff-only", "--dry-run")).first
        assertTrue(r.summary, r.ok); assertTrue(r.summary.contains("dry run: pull skipped after fetch")); assertEquals(h, head()); assertEquals(t, ref("refs/remotes/origin/main"))
    }

    @Test fun pullAutostashKeepsTheLocalEditsAndEmptiesTheStash() {
        serverCommits(); write(work, "a.txt", "edited\n"); write(work, "u.txt", "untracked\n")
        val r = exec(step("pull", "--ff-only", "--autostash")).first
        assertTrue(r.summary, r.ok); assertEquals(remoteMain(), head()); assertEquals("edited\n", text("a.txt")); assertEquals("untracked\n", text("u.txt"))
        assertEquals(0, JGitEngine(work).use { it.stashList().size })
        val clean = exec(step("pull", "--ff-only", "--autostash")).first
        assertTrue(clean.ok); assertEquals(0, JGitEngine(work).use { it.stashList().size })
    }

    @Test fun pullPruneTagsAndAllAreTheFetchSideOfAPull() {
        Git.open(other).use { g -> g.branchCreate().setName("old").call(); g.tag().setName("v1").call(); g.push().setRemote("origin").setPushAll().setPushTags().call() }
        assertTrue(exec(step("pull", "--ff-only", "--tags")).first.ok); assertTrue(ref("refs/tags/v1") != null)
        JGitEngine(work).use { it.addRemote("backup", url) }
        assertTrue(exec(step("pull", "--ff-only", "--all")).first.ok); assertTrue(ref("refs/remotes/backup/main") != null)
        Git.open(other).use { g -> g.push().setRemote("origin").setRefSpecs(org.eclipse.jgit.transport.RefSpec(":refs/heads/old")).call() }
        assertTrue(exec(step("pull", "--ff-only", "--prune")).first.ok); assertNull(ref("refs/remotes/origin/old"))
    }

    @Test fun pullFailureOfTheFetchSideStopsTheStash() {
        JGitEngine(work).use { it.setRemoteUrl("origin", "file://" + File(root, "gone.git").absolutePath) }
        write(work, "a.txt", "edited\n")
        val r = exec(step("pull", "--ff-only", "--autostash")).first
        assertFalse(r.ok); assertEquals("edited\n", text("a.txt")); assertEquals(0, JGitEngine(work).use { it.stashList().size })
    }

    @Test fun pullOfABranchNeverPushedSaysSo() {
        JGitEngine(work).use { e -> Git.open(work).use { g -> g.branchCreate().setName("topic").call(); g.checkout().setName("topic").call() }; assertEquals("topic", e.branchName()) }
        val r = exec(step("pull", "--ff-only")).first
        assertTrue(r.summary, r.ok); assertTrue(r.summary.contains("remote origin has no topic yet"))
    }

    @Test fun pullOnADetachedHeadFails() {
        detach()
        val r = exec(step("pull", "--ff-only")).first
        assertFalse(r.ok); assertTrue(r.summary.contains("detached HEAD: no current branch"))
        assertNull(JGitEngine(work).use { it.branchName() })
    }

    // ── push ──────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun pushSendsTheBranchAndDryRunSendsNothing() {
        localCommit()
        val dry = exec(step("push", "--dry-run")).first
        assertTrue(dry.summary, dry.ok); assertTrue(dry.summary.contains("dry run: ")); assertFalse(head() == remoteMain())
        val r = exec(step("push")).first
        assertTrue(r.summary.contains("pushed main to origin")); assertEquals(head(), remoteMain())
        assertTrue(exec(step("push")).first.summary.contains("already up to date"))
    }

    @Test fun aRejectedPushIsAFailureWithTheStatus() {
        serverCommits(); localCommit(); val before = remoteMain()
        val r = exec(step("push")).first
        assertFalse(r.ok); assertTrue(r.summary.contains("push rejected: ")); assertEquals(before, remoteMain())
    }

    @Test fun forcePushOverwritesAndLeaseRefusesAStaleOne() {
        serverCommits(); localCommit()
        val lease = exec(step("push", "--force-with-lease")).first
        assertFalse(lease.summary, lease.ok); assertFalse(head() == remoteMain())
        val raw = exec(step("push", "--force")).first
        assertTrue(raw.summary, raw.ok); assertEquals(head(), remoteMain())
        localCommit("l2.txt")
        val ok = exec(step("push", "--force-with-lease")).first
        assertTrue(ok.summary, ok.ok); assertEquals(head(), remoteMain())
    }

    @Test fun pushAllAndTagsSendEveryBranchAndTag() {
        Git.open(work).use { g -> g.branchCreate().setName("feature").call(); g.tag().setName("t1").call() }
        assertTrue(exec(step("push", "--all", "--tags")).first.ok)
        Git.open(bare).use { g -> assertTrue(g.repository.resolve("refs/heads/feature") != null); assertTrue(g.repository.resolve("refs/tags/t1") != null) }
        Git.open(work).use { g -> g.tag().setName("t2").call() }
        assertTrue(exec(step("push", "--tags")).first.ok)
        Git.open(bare).use { g -> assertTrue(g.repository.resolve("refs/tags/t2") != null) }
    }

    @Test fun theFirstPushOfABranchRecordsItsUpstream() {
        Git.open(work).use { g -> g.branchCreate().setName("topic").call(); g.checkout().setName("topic").call() }
        assertNull(JGitEngine(work).use { it.upstream() })
        assertTrue(exec(step("push")).first.ok)
        assertEquals("origin/topic", JGitEngine(work).use { it.upstream() })
        Git.open(work).use { g -> g.branchCreate().setName("dryonly").call(); g.checkout().setName("dryonly").call() }
        assertTrue(exec(step("push", "--dry-run")).first.ok); assertNull(JGitEngine(work).use { it.upstream() })
    }

    @Test fun pushOnADetachedHeadFails() {
        detach()
        val r = exec(step("push")).first
        assertFalse(r.ok); assertTrue(r.summary.contains("detached HEAD: no current branch"))
    }

    // ── reset, clone, binary routes ───────────────────────────────────────────────────────────────────────────────────

    @Test fun resetHardAtUpstreamIsForcePullAndNothingElseResets() {
        serverCommits(); localCommit("x.txt"); write(work, "a.txt", "edited\n")
        val r = exec(step("fetch"), step("reset", "--hard", "@{u}")).first
        assertTrue(r.summary, r.ok); assertEquals(remoteMain(), head()); assertEquals("line\n", text("a.txt"))
        val other = exec(step("reset", "--hard", "HEAD~1")).first
        assertFalse(other.ok); assertTrue(other.summary.contains("unsupported reset"))
    }

    @Test fun cloneRunsInProcessWithDepthBareAndBranch() {
        serverCommits(); Git.open(other).use { g -> g.branchCreate().setName("dev").call(); g.push().setRemote("origin").setPushAll().call() }
        val plain = File(root, "c1")
        assertTrue(PlanExecutor(work).run(listOf(step("clone", url, plain.absolutePath))).ok); assertTrue(File(plain, "server.txt").exists())
        val bareDir = File(root, "deep/c2.git")
        assertTrue(PlanExecutor(work).run(listOf(step("clone", "--bare", "--depth", "1", url, bareDir.absolutePath))).ok)
        assertTrue(File(bareDir, "HEAD").isFile); assertFalse(File(bareDir, "server.txt").exists())
        val one = File(root, "c3")
        assertTrue(PlanExecutor(work).run(listOf(step("clone", "--single-branch", "--branch", "dev", url, one.absolutePath))).ok)
        assertEquals("dev", JGitEngine(one).use { it.branchName() })
        val r = PlanExecutor(work).run(listOf(step("clone", url, File(root, "c4").absolutePath)))
        assertTrue(r.summary.endsWith("cloned into c4"))
    }

    @Test fun aFilterCloneAndDepthStepsNeedTheBinaryAndRunItWhenGiven() {
        val noBin = exec(step("clone", "--filter=blob:none", url, File(root, "f1").absolutePath)).first
        assertFalse(noBin.ok); assertTrue(noBin.summary.contains("partial-clone filter needs the git binary"))
        val seen = mutableListOf<List<String>>()
        val bin: (List<String>, File) -> GitOpResult = { a, d -> seen += a; assertEquals(work, d); GitOpResult(true, "bin ok") }
        val f = exec(step("clone", "--filter=tree:0", url, "D"), step("fetch", "--depth", "2"), step("reflog", "expire", "--all"), step("gc"), step("whatever"), binary = bin).first
        assertTrue(f.summary, f.ok); assertEquals(5, seen.size); assertEquals(listOf("git", "gc"), seen[3])
        for (v in listOf(step("fetch", "--depth", "1"), step("reflog", "expire"), step("gc"))) {
            val r = exec(v).first; assertFalse(r.ok); assertTrue(r.summary.contains("needs the git binary"))
        }
    }

    // ── the engine verbs directly ────────────────────────────────────────────────────────────────────────────────────

    @Test fun engineVerbsReturnTheirDocumentedResults() {
        JGitEngine(work).use { e ->
            assertEquals("main", e.branchName()); assertEquals(listOf("origin"), e.remoteNames())
            assertEquals("fetched origin: nothing new", e.fetchWith().summary)
            assertEquals("already up to date", e.pushWith().summary)
            assertTrue(e.pullWith().summary.startsWith("pulled origin/main"))
            assertTrue(e.pullWith(rebase = true).ok)
            assertEquals(ConflictSide.THEIRS, ConflictSide.valueOf("THEIRS"))
        }
    }
}
