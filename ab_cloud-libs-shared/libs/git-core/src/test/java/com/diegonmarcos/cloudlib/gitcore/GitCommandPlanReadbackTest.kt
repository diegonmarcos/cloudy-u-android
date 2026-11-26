package com.diegonmarcos.cloudlib.gitcore

import org.eclipse.jgit.api.Git
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1934 every property of the option model reads back what was set (both truth values), and the engine's exact answers: kills the getter and message mutants PIT lists. */
class GitCommandPlanReadbackTest {
    @Test fun syncOptionsReadBackEveryField() {
        val on = SyncOptions("pull", "rebase", "local", "autostash", "msg", "all", true, true, true, true, true, true)
        assertEquals("pull", on.direction); assertEquals("rebase", on.strategy); assertEquals("local", on.winner); assertEquals("autostash", on.dirty)
        assertEquals("msg", on.commitMessage); assertEquals("all", on.scope)
        assertTrue(on.prune); assertTrue(on.tags); assertTrue(on.submodules); assertTrue(on.forceLease); assertTrue(on.forceRaw); assertTrue(on.dryRun)
        val off = SyncOptions()
        assertEquals("both", off.direction); assertEquals("ff", off.strategy); assertEquals("none", off.winner); assertEquals("none", off.dirty)
        assertEquals("chore: auto-sync", off.commitMessage); assertEquals("current", off.scope)
        assertFalse(off.prune); assertFalse(off.tags); assertFalse(off.submodules); assertFalse(off.forceLease); assertFalse(off.forceRaw); assertFalse(off.dryRun)
        assertTrue(on.pulls); assertFalse(on.pushes); assertTrue(SyncOptions(direction = "both").pushes); assertTrue(SyncOptions(direction = "both").pulls); assertTrue(SyncOptions(direction = "pull").pulls); assertFalse(SyncOptions(direction = "pull").pushes)
        assertTrue(SyncOptions(direction = "push").pushes); assertFalse(SyncOptions(direction = "push").pulls); assertFalse(SyncOptions(direction = "fetch").pulls)
    }

    @Test fun cloneOptionsReadBackEveryField() {
        val c = CloneOptions("mirror", "bare", "main", "dev", "shallow", 7, false, false)
        assertEquals("mirror", c.dest); assertEquals("bare", c.workspace); assertEquals("main", c.branches); assertEquals("dev", c.branch); assertEquals("shallow", c.history)
        assertEquals(7, c.depth); assertFalse(c.trees); assertFalse(c.blobs)
        val d = CloneOptions()
        assertEquals("store", d.dest); assertEquals("working", d.workspace); assertEquals("all", d.branches); assertEquals("main", d.branch); assertEquals("deep", d.history)
        assertEquals(3, d.depth); assertTrue(d.trees); assertTrue(d.blobs)
    }

    @Test fun presetsAndStepsReadBack() {
        val o = SyncOptions(direction = "fetch")
        val sp = SyncPreset("id1", "Label 1", o); assertEquals("id1", sp.id); assertEquals("Label 1", sp.label); assertEquals(o, sp.options)
        val c = CloneOptions(depth = 5)
        val cp = ClonePreset("id2", "Label 2", c); assertEquals("id2", cp.id); assertEquals("Label 2", cp.label); assertEquals(c, cp.options)
        val s = GitStep(listOf("git", "x"), true); assertEquals(listOf("git", "x"), s.argv); assertTrue(s.tolerant); assertFalse(GitStep(listOf("git")).tolerant)
    }

    // ── the engine's exact answers ─────────────────────────────────────────────────────────────────────────────────

    private fun write(dir: File, path: String, text: String) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }

    @Test fun pullAnswersWithTheMergeStatusAndTheWinnerMessage() {
        val root = Files.createTempDirectory("readback").toFile()
        try {
            val bare = File(root, "r.git"); val url = "file://" + bare.absolutePath
            Git.init().setBare(true).setInitialBranch("main").setDirectory(bare).call().close()
            val work = File(root, "w"); Git.init().setInitialBranch("main").setDirectory(work).call().close()
            JGitEngine(work).use { e -> write(work, "a.txt", "line\n"); e.stageAll(); e.commit("seed", "t", "t@x"); e.addRemote("origin", url); assertTrue(e.push().ok) }
            val other = File(root, "o"); JGitEngine.clone(url, other).close()
            JGitEngine(work).use { e -> assertEquals("pulled origin/main: already up to date", e.pullWith().summary) }
            JGitEngine(other).use { o -> write(other, "s.txt", "s\n"); o.stageAll(); o.commit("server", "s", "s@x"); assertTrue(o.push().ok) }
            val remote = Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
            JGitEngine(work).use { e -> assertEquals("pulled origin/main: fast forward", e.pullWith().summary); assertEquals(remote, Git.open(work).use { it.repository.resolve("HEAD").name }) }
            // a conflicting change settled for the remote, then for the local side
            JGitEngine(other).use { o -> o.pull(); write(other, "a.txt", "server\n"); o.stageAll(); o.commit("srv", "s", "s@x"); assertTrue(o.push().ok) }
            JGitEngine(work).use { e -> write(work, "a.txt", "phone\n"); e.stageAll(); e.commit("phone", "t", "t@x")
                val r = e.pullWith(favor = com.diegonmarcos.cloudlib.gitsync.ConflictSide.THEIRS)
                assertEquals("pulled origin/main: merged, 1 conflict(s) settled for the remote", r.summary)
                assertEquals("Merge origin/main (remote wins)", e.log(1).first().summary) }
            JGitEngine(other).use { o -> o.pull(); write(other, "a.txt", "server2\n"); o.stageAll(); o.commit("srv2", "s", "s@x"); assertTrue(o.push().ok) }
            JGitEngine(work).use { e -> write(work, "a.txt", "phone2\n"); e.stageAll(); e.commit("phone2", "t", "t@x")
                val r = e.pullWith(favor = com.diegonmarcos.cloudlib.gitsync.ConflictSide.OURS)
                assertEquals("pulled origin/main: merged, 1 conflict(s) settled for the local side", r.summary)
                assertEquals("Merge origin/main (local wins)", e.log(1).first().summary) }
            // force pull names the commit it reset to
            JGitEngine(other).use { o -> o.pull(); write(other, "z.txt", "z\n"); o.stageAll(); o.commit("z", "s", "s@x"); assertTrue(o.push().ok) }
            val tip = Git.open(bare).use { it.repository.resolve("refs/heads/main").name }
            JGitEngine(work).use { e -> assertEquals("force-pulled origin/main: reset to " + tip.take(8), e.forcePull().summary) }
            // the default branch follows the remote's HEAD
            assertEquals("main", JGitEngine.defaultBranch(url))
            Git.open(bare).use { g -> g.branchCreate().setName("trunk").setStartPoint("main").call(); val ru = g.repository.updateRef("HEAD", true); ru.link("refs/heads/trunk") }
            assertEquals("trunk", JGitEngine.defaultBranch(url))
            val empty = File(root, "empty.git"); Git.init().setBare(true).setInitialBranch("main").setDirectory(empty).call().close()
            try { JGitEngine.defaultBranch("file://" + empty.absolutePath); fail("an empty remote advertises no default branch") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("no default branch advertised")) }
        } finally { root.deleteRecursively() }
    }
}
