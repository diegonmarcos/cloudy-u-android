package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitWire
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1408 the compound ops Drive's sheet sends raw, answered in-process: no-winner merge, lease push, main, rebase continue. */
class CompoundOpsTest {
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var other: File
    private lateinit var bare: File
    private val bareUrl get() = "file://" + bare.absolutePath

    private fun write(dir: File, path: String, text: String) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }

    @Before fun setUp() {
        root = Files.createTempDirectory("git-core-compound").toFile()
        work = File(root, "work"); other = File(root, "other"); bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bare).call().close()
        JGitEngine.init(work).use { e ->
            write(work, "a.txt", "line\n"); e.stageAll(); e.commit("seed", "t", "t@x")
            e.addRemote("origin", bareUrl); assertTrue(e.push().ok)
        }
        JGitEngine.clone(bareUrl, other).close()
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun remoteMoves(text: String) = JGitEngine(other).use { o ->
        assertTrue(o.pull().ok); write(other, "a.txt", text); o.stageAll(); o.commit("server", "s", "s@x"); assertTrue(o.push().ok)
    }

    @Test fun mergeUpstreamMergesACleanChange() {
        remoteMoves("server\n")
        JGitEngine(work).use { e ->
            val r = e.mergeUpstream()
            assertTrue(r.summary, r.ok)
            assertEquals("server\n", File(work, "a.txt").readText())
        }
    }

    @Test fun mergeUpstreamStopsOnAConflictAndLeavesMarkers() {
        remoteMoves("server\n")
        JGitEngine(work).use { e ->
            write(work, "a.txt", "phone\n"); e.stageAll(); e.commit("phone", "t", "t@x")
            val r = e.mergeUpstream()
            assertFalse(r.ok)
            assertEquals("pull conflicts: 1 file(s)", r.summary)
            assertEquals("a.txt", r.details)
            assertTrue(File(work, "a.txt").readText().startsWith("<<<<<<<"))
            assertEquals(listOf("a.txt"), e.status().conflicts.map { it.path })
            assertEquals(GitErrorKind.CONFLICT, GitErrorKind.classify(r.summary))
        }
    }

    @Test fun forcePushLeaseWinsWhenTheRemoteDidNotMoveAndIsRefusedWhenItDid() {
        JGitEngine(work).use { e ->
            val br = e.status().branch!!
            write(work, "a.txt", "local\n"); e.stageAll(); e.commit("local", "t", "t@x")
            assertEquals(GitOpResult(true, "pushed $br to origin with lease"), e.forcePushLease())
        }
        remoteMoves("server\n")
        JGitEngine(work).use { e ->
            write(work, "a.txt", "local2\n"); e.stageAll(); e.commit("local2", "t", "t@x")
            val r = e.forcePushLease()
            assertFalse(r.summary, r.ok)
            assertTrue(r.summary, r.summary.startsWith("push with lease rejected: "))
        }
        // the remote kept the server's commit
        JGitEngine(other).use { o -> assertEquals("server", o.log(1).single().summary) }
    }

    @Test fun forcePushLeaseWithoutATrackingRefPushesPlainly() {
        val fresh = File(root, "fresh")
        JGitEngine.init(fresh).use { e ->
            write(fresh, "b.txt", "b\n"); e.stageAll(); e.commit("b", "t", "t@x")
            e.addRemote("origin", "file://" + File(root, "empty.git").also { org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(it).call().close() }.absolutePath)
            assertTrue(e.forcePushLease().ok)
        }
    }

    @Test fun checkoutMainFindsMainOrMasterOrSaysNeither() {
        JGitEngine(work).use { e ->
            val br = e.status().branch!!
            assertEquals(GitOpResult(true, "on $br"), e.checkoutMain())
        }
        val odd = File(root, "odd")
        org.eclipse.jgit.api.Git.init().setInitialBranch("trunk").setDirectory(odd).call().use { g ->
            write(odd, "x", "x"); g.add().addFilepattern(".").call(); g.commit().setMessage("x").setAuthor("t", "t@x").setCommitter("t", "t@x").call()
        }
        JGitEngine(odd).use { e -> assertEquals(GitOpResult(false, "no main or master branch"), e.checkoutMain()) }
        org.eclipse.jgit.api.Git.open(odd).use { g -> g.branchCreate().setName("main").call() }
        JGitEngine(odd).use { e -> assertEquals(GitOpResult(true, "on main"), e.checkoutMain()); assertEquals("main", e.status().branch) }
    }

    @Test fun rebaseContinueWithNothingInProgressIsOk() {
        JGitEngine(work).use { e -> assertEquals(GitOpResult(true, "no rebase in progress"), e.rebaseContinue()) }
    }

    @Test fun rebaseContinueFinishesAResolvedRebase() {
        remoteMoves("server\n")
        JGitEngine(work).use { e ->
            write(work, "a.txt", "phone\n"); e.stageAll(); e.commit("phone", "t", "t@x")
            assertFalse(e.pull(rebase = true).ok)
            write(work, "a.txt", "both\n"); e.stage(listOf("a.txt"))
            val r = e.rebaseContinue()
            assertTrue(r.summary, r.ok)
            assertTrue(r.summary.startsWith("rebase "))
            assertEquals("both\n", File(work, "a.txt").readText())
        }
    }

    @Test fun theWireAnswersEveryCompoundOp() {
        val c = GitCalls(File(root, "scratch"), "t") { true }
        fun op(verb: String, args: JSONObject = JSONObject()): GitOpResult {
            val a = JSONObject(c.git(GitWire.request(work, verb, args, null)))
            assertTrue("$verb: $a", a.getBoolean("ok"))
            return GitWire.json.decodeFromString(GitOpResult.serializer(), a.getString("result"))
        }
        assertTrue(op("merge_upstream").ok)
        assertTrue(op("force_push_lease").ok)
        assertTrue(op("checkout_main").ok)
        assertTrue(op("rebase_continue").ok)
        write(work, "a.txt", "dirty\n")
        assertTrue(op("stash", JSONObject().put("message", "m")).summary.startsWith("stashed "))
        for (v in listOf("shrink", "keep_depth")) {
            val r = op(v, JSONObject().put("depth", 1))
            assertFalse(r.ok); assertTrue(r.summary, r.summary.startsWith("$v rewrites") && r.summary.contains("cld.termux"))
        }
        assertEquals(GitWire.COMPOUND, setOf("merge_upstream", "force_push_lease", "stash", "checkout_main", "rebase_continue", "shrink", "keep_depth"))
        assertTrue(GitWire.COMPOUND.none { it in GitWire.VERBS })
    }
}
