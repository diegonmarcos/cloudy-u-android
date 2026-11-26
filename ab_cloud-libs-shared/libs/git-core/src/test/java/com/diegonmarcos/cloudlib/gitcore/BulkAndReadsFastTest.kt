package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #1934 the ALL-REPOS sheet's semantics EXECUTED over several throwaway repositories in mixed states (clean, behind, dirty, unreachable remote, detached): the same plan
 * runs on each, every repository answers on its own, and one failing repository never stops the others. Also Test Auth (a fetch dry run) and the default-branch read.
 */
class BulkAndReadsFastTest {
    private lateinit var root: File
    private lateinit var bare: File
    private val url get() = "file://" + bare.absolutePath
    private fun write(dir: File, path: String, text: String) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }

    @Before fun setUp() {
        root = Files.createTempDirectory("bulk-fast").toFile()
        bare = File(root, "remote.git")
        Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
        val seed = File(root, "seed"); Git.init().setInitialBranch("main").setDirectory(seed).call().close()
        JGitEngine(seed).use { e -> write(seed, "a.txt", "line\n"); e.stageAll(); e.commit("seed", "t", "t@x"); e.addRemote("origin", url); assertTrue(e.push().ok) }
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun clone(name: String): File = File(root, name).also { JGitEngine.clone(url, it).close() }
    private fun remoteMain() = Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
    private fun headOf(d: File) = Git.open(d).use { it.repository.resolve("HEAD").name }
    private fun serverMoves() { val o = clone("mover"); JGitEngine(o).use { e -> write(o, "s.txt", "s\n"); e.stageAll(); e.commit("server", "s", "s@x"); assertTrue(e.push().ok) } }

    /** What the app's bulk job does: the plan on every repository, each answer kept by name, a failure recorded and the loop going on. */
    private fun runAll(repos: List<Pair<String, File>>, steps: List<GitStep>): Map<String, GitOpResult> =
        repos.associate { (n, d) -> n to runCatching { PlanExecutor(d).run(steps) }.getOrElse { GitOpResult(false, it.message ?: it.toString()) } }

    @Test fun oneFailingRepositoryDoesNotStopTheOthersAndEachAnswersOnItsOwn() {
        val clean = clone("clean"); val behind = clone("behind"); val dirty = clone("dirty"); val broken = clone("broken"); val detached = clone("detached")
        serverMoves()
        write(dirty, "a.txt", "edited\n")
        JGitEngine(broken).use { it.setRemoteUrl("origin", "file://" + File(root, "gone.git").absolutePath) }
        Git.open(detached).use { g -> g.checkout().setName(g.repository.resolve("HEAD").name).call() }
        val order = listOf("clean" to clean, "broken" to broken, "behind" to behind, "dirty" to dirty, "detached" to detached)
        val res = runAll(order, listOf(GitStep(listOf("git", "pull", "--ff-only", "--autostash"))))
        assertEquals(listOf("clean", "broken", "behind", "dirty", "detached"), res.keys.toList())
        assertTrue(res.getValue("clean").summary, res.getValue("clean").ok)
        assertFalse(res.getValue("broken").summary, res.getValue("broken").ok)
        assertTrue(res.getValue("behind").ok); assertEquals(remoteMain(), headOf(behind))
        assertTrue(res.getValue("dirty").summary, res.getValue("dirty").ok); assertEquals(remoteMain(), headOf(dirty)); assertEquals("edited\n", File(dirty, "a.txt").readText())
        assertFalse(res.getValue("detached").ok); assertTrue(res.getValue("detached").summary.contains("detached HEAD"))
        assertEquals(setOf("clean", "behind", "dirty"), res.filterValues { it.ok }.keys)
        assertEquals(setOf("broken", "detached"), res.filterValues { !it.ok }.keys)
    }

    @Test fun aBulkPushOnlyMovesTheRepositoriesThatAreAhead() {
        val ahead = clone("ahead"); val same = clone("same")
        JGitEngine(ahead).use { e -> write(ahead, "n.txt", "n\n"); e.stageAll(); e.commit("n", "t", "t@x") }
        val before = remoteMain()
        val res = runAll(listOf("same" to same, "ahead" to ahead), listOf(GitStep(listOf("git", "push"))))
        assertTrue(res.getValue("same").summary.contains("already up to date")); assertEquals(headOf(ahead), remoteMain()); assertFalse(before == remoteMain())
        assertTrue(res.getValue("ahead").summary.contains("pushed main to origin"))
    }

    @Test fun aBulkAutoCommitThenPushCommitsOnlyTheDirtyOnes() {
        val dirty = clone("d1"); val clean = clone("c1")
        write(dirty, "x.txt", "x\n")
        val steps = listOf(GitStep(listOf("git", "add", "-A")), GitStep(listOf("git", "commit", "-m", "auto"), tolerant = true), GitStep(listOf("git", "push")))
        val res = runAll(listOf("c1" to clean, "d1" to dirty), steps)
        assertTrue(res.getValue("c1").summary, res.getValue("c1").ok); assertTrue(res.getValue("d1").summary, res.getValue("d1").ok)
        assertEquals(headOf(dirty), remoteMain()); assertEquals(listOf("auto", "seed"), JGitEngine(dirty).use { e -> e.log(5).map { it.summary } })
    }

    @Test fun testAuthIsAFetchDryRunThatFailsOnAnUnreachableRemoteAndChangesNothing() {
        val d = clone("auth"); val before = headOf(d)
        val ok = PlanExecutor(d).run(listOf(GitStep(listOf("git", "fetch", "--dry-run"))))
        assertTrue(ok.summary, ok.ok); assertTrue(ok.summary.contains("dry run: fetched origin"))
        JGitEngine(d).use { it.setRemoteUrl("origin", "file://" + File(root, "gone.git").absolutePath) }
        assertFalse(PlanExecutor(d).run(listOf(GitStep(listOf("git", "fetch", "--dry-run")))).ok); assertEquals(before, headOf(d))
    }

    @Test fun theDefaultBranchIsReadFromTheRemote() {
        assertEquals("main", JGitEngine.defaultBranch(url))
    }
}
