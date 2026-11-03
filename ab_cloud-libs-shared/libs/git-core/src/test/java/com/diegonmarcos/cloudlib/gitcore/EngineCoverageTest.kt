package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitBranchInfo
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitCommitInfo
import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitWire
import kotlinx.serialization.builtins.ListSerializer
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1408 the branches the first suites left open: every wire verb dispatched, staged discard, path/staged diffs,
 *  the upstream the first push and force push record, pull without an upstream, status order, the config saved. */
class EngineCoverageTest {
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var bare: File
    private val bareUrl get() = "file://" + bare.absolutePath

    private fun write(path: String, text: String, dir: File = work) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }

    @Before fun setUp() {
        root = Files.createTempDirectory("git-core-cov").toFile()
        work = File(root, "work"); bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bare).call().close()
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun seeded(): JGitEngine {
        val e = JGitEngine.init(work)
        write("a.md", "one\n"); e.stageAll(); e.commit("seed", "t", "t@x")
        return e
    }

    /** A fresh config read from disk (not the engine's cached one). */
    private fun diskConfig(key: String, branch: String) =
        org.eclipse.jgit.api.Git.open(work).use { it.repository.config.apply { load() }.getString("branch", branch, key) }

    @Test fun unstageAllUnstagesEveryStagedFileAndKeepsTheEdits() {
        seeded().use { e ->
            write("a.md", "two\n"); write("b.md", "b\n"); e.stageAll()
            assertEquals(2, e.status().staged.size)
            e.unstageAll()
            val s = e.status()
            assertTrue(s.staged.isEmpty())
            assertEquals(listOf("a.md", "b.md"), s.files.map { it.path })
            assertEquals("two\n", File(work, "a.md").readText())
        }
    }

    @Test fun discardOfAStagedEditUnstagesThenRestores() {
        seeded().use { e ->
            write("a.md", "two\n"); e.stage(listOf("a.md"))
            e.discard("a.md")
            assertEquals("one\n", File(work, "a.md").readText())
            assertTrue(e.status().isClean)
            e.discard("not-there.md")   // nothing to discard: no throw
            assertTrue(e.status().isClean)
        }
    }

    @Test fun diffByPathStagedAndUntracked() {
        seeded().use { e ->
            assertEquals("", e.diff("a.md"))            // tracked, unchanged
            assertEquals("", e.diff("missing.md"))      // no such file
            write("a.md", "two\n")
            assertTrue(e.diff("a.md").contains("+two"))
            assertEquals("", e.diff("a.md", staged = true))
            e.stage(listOf("a.md"))
            assertTrue(e.diff("a.md", staged = true).contains("+two"))
            assertEquals("", e.diff("a.md"))
            write("n.md", "x\ny\n")
            assertEquals("--- /dev/null\n+++ b/n.md\n+x\n+y\n", e.diff("n.md"))
            assertEquals("", e.diff("n.md", staged = true))
            assertTrue(e.diff().contains("a.md"))
        }
    }

    @Test fun statusListsFilesInPathOrder() {
        seeded().use { e ->
            write("z.md", "z\n"); write("b.md", "b\n"); write("m/k.md", "k\n")
            assertEquals(listOf("b.md", "m/k.md", "z.md"), e.status().files.map { it.path })
        }
    }

    @Test fun firstPushRecordsTheUpstreamOnDiskAndASecondIsUpToDate() {
        seeded().use { e ->
            e.addRemote("origin", bareUrl)
            val br = e.status().branch!!
            assertNull(diskConfig("remote", br))
            val r = e.push()
            assertEquals(GitOpResult(true, "pushed $br to origin"), r)
            assertEquals("origin", diskConfig("remote", br))
            assertEquals("refs/heads/$br", diskConfig("merge", br))
            assertEquals("origin/$br", e.upstream())
            assertEquals(GitOpResult(true, "already up to date"), e.push())
        }
    }

    @Test fun firstForcePushRecordsTheUpstreamToo() {
        seeded().use { e ->
            e.addRemote("origin", bareUrl)
            val br = e.status().branch!!
            assertEquals(GitOpResult(true, "force-pushed $br to origin"), e.forcePush())
            assertEquals("origin", diskConfig("remote", br))
            assertEquals("refs/heads/$br", diskConfig("merge", br))
        }
    }

    @Test fun setUpstreamIsSavedToDisk() {
        seeded().use { e ->
            e.addRemote("origin", bareUrl)
            val br = e.status().branch!!
            assertTrue(e.setUpstream("origin", "trunk").ok)
            assertEquals("refs/heads/trunk", diskConfig("merge", br))
        }
    }

    @Test fun pullWithoutUpstreamWhenTheRemoteLacksTheBranch() {
        seeded().use { e ->
            e.addRemote("origin", bareUrl)
            val br = e.status().branch!!
            assertEquals(GitOpResult(true, "remote origin has no $br yet — nothing to pull"), e.pull())
        }
    }

    @Test fun pullWithoutUpstreamWhenTheRemoteHasTheBranch() {
        seeded().use { e -> e.addRemote("origin", bareUrl); e.push() }
        val other = File(root, "other")
        JGitEngine.clone(bareUrl, other).use { o ->
            write("c.md", "c\n", other); o.stageAll(); o.commit("c", "t", "t@x"); assertTrue(o.push().ok)
        }
        JGitEngine(work).use { e ->
            val br = e.status().branch!!
            org.eclipse.jgit.api.Git.open(work).use { g -> g.repository.config.apply { unsetSection("branch", br); save() } }
            val r = e.pull()
            assertTrue(r.summary, r.ok)
            assertTrue(r.summary.startsWith("pulled origin/$br"))
            assertEquals("c\n", File(work, "c.md").readText())
        }
    }

    @Test fun forcePullWithoutTheRemoteBranchRefusesAndKeepsTheTree() {
        seeded().use { e ->
            e.addRemote("origin", bareUrl)
            val br = e.status().branch!!
            assertEquals(GitOpResult(false, "remote origin has no $br — nothing to reset onto"), e.forcePull())
            assertEquals("one\n", File(work, "a.md").readText())
        }
    }

    @Test fun syncWithoutTheNamedRemoteCommitsAndSaysSo() {
        seeded().use { e ->
            write("a.md", "two\n")
            val r = e.sync("m", "t", "t@x")
            assertEquals(true, r.ok)
            assertTrue(r.summary, r.summary.startsWith("committed ") && r.summary.endsWith(", no remote 'origin'"))
            val clean = e.sync("m", "t", "t@x")
            assertEquals("nothing to commit, no remote 'origin'", clean.summary)
        }
    }

    @Test fun logByPathAndBranchesNameEveryRef() {
        seeded().use { e ->
            write("b.md", "b\n"); e.stageAll(); e.commit("b", "t", "t@x")
            assertEquals(listOf("b"), e.log(path = "b.md").map { it.summary })
            assertEquals(2, e.log().size)
            e.addRemote("origin", bareUrl); e.push(); e.fetch()
            val names = e.branches()
            assertTrue(names.any { it.isCurrent && !it.isRemote && it.sha.length == 40 })
            assertTrue(names.any { it.isRemote && it.fullName.startsWith("refs/remotes/origin/") })
            assertEquals(names.sortedWith(compareBy({ it.isRemote }, { it.name })), names)
        }
    }

    @Test fun everyWireVerbIsDispatched() {
        seeded().close()
        val c = GitCalls(File(root, "scratch"), "t") { true }
        fun call(verb: String, args: JSONObject = JSONObject()): JSONObject = JSONObject(c.git(GitWire.request(work, verb, args, null)))
        fun ok(verb: String, args: JSONObject = JSONObject()): String = call(verb, args).also { assertTrue("$verb: $it", it.getBoolean("ok")) }.optString("result")

        write("a.md", "two\n"); write("b.md", "b\n")
        ok("stage_all")
        assertEquals(2, JGitEngine(work).use { it.status().staged.size })
        ok("unstage", JSONObject().put("paths", JSONArray(listOf("b.md"))))
        assertEquals(listOf("a.md"), JGitEngine(work).use { e -> e.status().staged.map { it.path } })
        ok("unstage_all")
        assertTrue(JGitEngine(work).use { it.status().staged.isEmpty() })
        ok("stage", JSONObject().put("paths", JSONArray(listOf("a.md"))))
        assertTrue(ok("diff", JSONObject().put("staged", true)).contains("+two"))
        ok("discard", JSONObject().put("path", "b.md"))
        assertFalse(File(work, "b.md").exists())
        val commit = GitWire.json.decodeFromString(GitCommitInfo.serializer(), ok("commit", JSONObject().put("message", "m").put("name", "t").put("email", "t@x")))
        assertEquals("m", commit.summary)
        assertTrue(ok("commit_diff", JSONObject().put("sha", commit.sha)).contains("+two"))
        assertEquals(listOf("m", "seed"), GitWire.json.decodeFromString(ListSerializer(GitCommitInfo.serializer()), ok("log")).map { it.summary })
        assertEquals(listOf("m"), GitWire.json.decodeFromString(ListSerializer(GitCommitInfo.serializer()), ok("log", JSONObject().put("max", 1))).map { it.summary })
        ok("add_remote", JSONObject().put("name", "origin").put("url", bareUrl))
        ok("set_remote_url", JSONObject().put("name", "origin").put("url", bareUrl).put("push", true))
        val pushed = GitWire.json.decodeFromString(GitOpResult.serializer(), ok("push"))
        assertTrue(pushed.summary, pushed.ok)
        assertTrue(GitWire.json.decodeFromString(GitOpResult.serializer(), ok("fetch")).ok)
        assertTrue(GitWire.json.decodeFromString(GitOpResult.serializer(), ok("pull")).ok)
        assertTrue(GitWire.json.decodeFromString(GitOpResult.serializer(), ok("force_push")).ok)
        assertTrue(GitWire.json.decodeFromString(GitOpResult.serializer(), ok("force_pull")).ok)
        assertTrue(GitWire.json.decodeFromString(GitOpResult.serializer(), ok("sync", JSONObject().put("message", "s").put("name", "t").put("email", "t@x"))).ok)
        assertTrue(GitWire.json.decodeFromString(ListSerializer(GitBranchInfo.serializer()), ok("branches")).isNotEmpty())
        assertTrue(ok("remotes").contains("origin"))
        assertTrue(ok("upstream").startsWith("origin/"))
        ok("remove_remote", JSONObject().put("name", "origin"))
        assertFalse(ok("remotes").contains("origin"))
        ok("abort_merge")
        ok("mark_resolved", JSONObject().put("path", "a.md"))
        assertEquals("", ok("stash_list").removeSurrounding("[", "]"))
        val unknown = JSONObject(c.git(GitWire.request(work, "nope", JSONObject(), null)))
        assertFalse(unknown.getBoolean("ok")); assertTrue(unknown.getString("error").contains("nope"))
        val fresh = File(root, "fresh")
        ok2(c, fresh, "init"); assertTrue(JGitEngine.isRepository(fresh))
        val cloned = File(root, "cloned")
        ok2(c, cloned, "clone", JSONObject().put("url", bareUrl)); assertTrue(File(cloned, "a.md").isFile)
        assertEquals(GitChange.MODIFIED, JGitEngine(work).use { e -> write("a.md", "three\n"); e.status().unstaged.single().unstaged })
    }

    private fun ok2(c: GitCalls, dir: File, verb: String, args: JSONObject = JSONObject()) =
        JSONObject(c.git(GitWire.request(dir, verb, args, null))).also { assertTrue("$verb: $it", it.getBoolean("ok")) }

    @Test fun aLongResultComesInChunksAndAnUnknownChunkIsRefused() {
        seeded().close()
        val c = GitCalls(File(root, "scratch"), "t") { true }
        write("big.md", "x".repeat(GitCalls.CHUNK + 10) + "\n")
        val first = JSONObject(c.git(GitWire.request(work, "diff", JSONObject().put("path", "big.md"), null)))
        assertTrue(first.getBoolean("more"))
        assertEquals(GitCalls.CHUNK, first.getString("result").length)
        val rest = JSONObject(c.chunk(JSONObject().put("id", first.getString("id")).put("offset", GitCalls.CHUNK)))
        assertFalse(rest.getBoolean("more"))
        assertTrue((first.getString("result") + rest.getString("result")).endsWith("x\n"))
        assertFalse(JSONObject(c.chunk(JSONObject().put("id", first.getString("id")))).getBoolean("ok"))
    }

    @Test fun sharedStorageNeedsTheGrantOtherRootsDoNot() {
        assertTrue(GitCalls.touchesSharedStorage(File("/storage/emulated/0/x")))
        assertTrue(GitCalls.touchesSharedStorage(File("/sdcard/x")))
        assertTrue(GitCalls.touchesSharedStorage(File("/mnt/media_rw/1A2B/x")))
        assertTrue(GitCalls.touchesSharedStorage(File("/data/media/0/x")))
        assertFalse(GitCalls.touchesSharedStorage(File("/data/data/pkg/files/x")))
        assertFalse(GitCalls.touchesSharedStorage(work))
        seeded().close()
        val denied = GitCalls(File(root, "scratch"), "t") { false }
        assertTrue(JSONObject(denied.git(GitWire.request(work, "status", JSONObject(), null))).getBoolean("ok"))
        assertEquals(false, JSONObject(denied.info()).getBoolean("files_access"))
    }
}
