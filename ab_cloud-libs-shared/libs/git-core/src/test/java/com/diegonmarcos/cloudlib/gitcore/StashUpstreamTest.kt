package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitOpResult
import com.diegonmarcos.cloudlib.gitsync.GitWire
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
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

/** #1408 stash push/pop/list (clean, untracked, conflicting pop), upstream, and the same verbs over the wire. */
class StashUpstreamTest {
    private lateinit var root: File
    private lateinit var work: File
    private lateinit var bare: File
    private val bareUrl get() = "file://" + bare.absolutePath

    private fun write(path: String, text: String, dir: File = work) { File(dir, path).apply { parentFile.mkdirs(); writeText(text) } }
    private fun read(path: String) = File(work, path).readText()

    @Before fun setUp() {
        root = Files.createTempDirectory("git-core-stash").toFile()
        work = File(root, "work"); bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bare).call().close()
    }
    @After fun tearDown() { root.deleteRecursively() }

    private fun seeded(): JGitEngine {
        val e = JGitEngine.init(work)
        write("a.md", "one\n"); e.stageAll(); e.commit("seed", "t", "t@x")
        return e
    }

    @Test fun stashOnACleanTreeIsANoOpAndPopWithoutStashIsToo() {
        seeded().use { e ->
            assertEquals(GitOpResult(true, "nothing to stash"), e.stashPush())
            assertTrue(e.stashList().isEmpty())
            assertEquals(GitOpResult(true, "no stash to pop"), e.stashPop())
        }
    }

    @Test fun stashPushHidesChangesAndPopRestoresAndDrops() {
        seeded().use { e ->
            write("a.md", "two\n"); write("new.md", "fresh\n")
            val r = e.stashPush("wip")
            assertTrue(r.ok); assertTrue(r.summary.startsWith("stashed "))
            assertEquals(16, r.summary.length)
            assertEquals("one\n", read("a.md"))
            assertFalse(File(work, "new.md").exists())
            assertTrue(e.status().isClean)
            assertEquals(listOf("wip"), e.stashList())

            assertEquals(GitOpResult(true, "popped stash"), e.stashPop())
            assertEquals("two\n", read("a.md"))
            assertEquals("fresh\n", read("new.md"))
            assertTrue(e.stashList().isEmpty())
        }
    }

    @Test fun stashWithoutUntrackedLeavesUntrackedFiles() {
        seeded().use { e ->
            write("a.md", "two\n"); write("new.md", "fresh\n")
            assertTrue(e.stashPush(includeUntracked = false).ok)
            assertTrue(File(work, "new.md").exists())
            assertEquals("one\n", read("a.md"))
        }
    }

    @Test fun conflictingPopKeepsTheStashAndNamesTheFile() {
        seeded().use { e ->
            write("a.md", "mine\n"); assertTrue(e.stashPush("mine").ok)
            write("a.md", "theirs\n"); e.stageAll(); e.commit("theirs", "t", "t@x")
            val r = e.stashPop()
            assertFalse(r.ok)
            assertEquals("stash pop conflicts: 1 file(s)", r.summary)
            assertEquals("a.md", r.details)
            assertEquals(1, e.stashList().size)
            assertEquals(GitErrorKind.CONFLICT, GitErrorKind.classify(r.summary))
        }
    }

    @Test fun upstreamIsSetReadAndRefusedWithoutRemoteOrBranch() {
        JGitEngine.init(work).use { e ->
            assertNull(e.upstream())   // unborn: no branch name to read yet is fine, no config
            write("a.md", "x\n"); e.stageAll(); e.commit("c", "t", "t@x")
            assertNull(e.upstream())
            assertEquals(GitOpResult(false, "no remote 'origin'"), e.setUpstream())
            e.addRemote("origin", bareUrl)
            val br = e.status().branch
            assertEquals(GitOpResult(true, "upstream of $br is origin/$br"), e.setUpstream())
            assertEquals("origin/$br", e.upstream())
            assertEquals(GitOpResult(true, "upstream of $br is origin/trunk"), e.setUpstream("origin", "trunk"))
            assertEquals("origin/trunk", e.upstream())
            assertEquals(GitOpResult(true, "upstream of $br is origin/$br"), e.setUpstream("origin", " "))
        }
    }

    @Test fun upstreamNeedsBothRemoteAndMerge() {
        seeded().use { e ->
            val br = e.status().branch
            org.eclipse.jgit.api.Git.open(work).use { g -> g.repository.config.apply { setString("branch", br, "remote", "origin"); save() } }
            assertNull(e.upstream())
        }
    }

    @Test fun theWireAnswersStashAndUpstreamVerbs() {
        seeded().close()
        val c = GitCalls(File(root, "scratch"), "t") { true }
        fun call(verb: String, args: JSONObject = JSONObject()) = JSONObject(c.git(GitWire.request(work, verb, args, null)))
        write("a.md", "two\n")
        val pushed = call("stash_push", JSONObject().put("message", "w"))
        assertTrue(pushed.getBoolean("ok"))
        val list = GitWire.json.decodeFromString(ListSerializer(String.serializer()), call("stash_list").getString("result"))
        assertEquals(listOf("w"), list)
        val popped = GitWire.json.decodeFromString(GitOpResult.serializer(), call("stash_pop").getString("result"))
        assertEquals(GitOpResult(true, "popped stash"), popped)
        assertEquals("", call("upstream").getString("result"))
        val up = GitWire.json.decodeFromString(GitOpResult.serializer(), call("set_upstream").getString("result"))
        assertFalse(up.ok)
        val bad = call("commit", JSONObject().put("message", "m"))   // no name/email: refused
        assertFalse(bad.getBoolean("ok"))
        assertEquals(GitErrorKind.classify(bad.getString("error")).name, bad.getString("kind"))
        assertTrue("stash_push" in GitWire.VERBS && "upstream" in GitWire.VERBS)
    }

    @Test fun anyStorageRootClonesSyncsAndStashes() {
        // A volume-shaped root (spaces, nested, like /storage/1A2B-3C4D/My Repos): the engine takes any File.
        org.eclipse.jgit.api.Git.cloneRepository().setURI(bareUrl).setDirectory(File(root, "seed")).call().use { g ->
            write("s.md", "s\n", File(root, "seed")); g.add().addFilepattern(".").call(); g.commit().setMessage("s").setAuthor("t", "t@x").setCommitter("t", "t@x").call()
            g.push().setRefSpecs(org.eclipse.jgit.transport.RefSpec("HEAD:refs/heads/master")).call()
        }
        val vol = File(root, "storage/1A2B-3C4D/My Repos/r")
        JGitEngine.clone(bareUrl, vol).use { e ->
            assertTrue(JGitEngine.isRepository(vol))
            assertEquals("s\n", File(vol, "s.md").readText())
            File(vol, "s.md").writeText("t\n")
            assertTrue(e.stashPush().ok); assertEquals("s\n", File(vol, "s.md").readText())
            assertTrue(e.stashPop().ok); assertEquals("t\n", File(vol, "s.md").readText())
            assertTrue(e.sync("v", "t", "t@x").ok)
        }
    }
}
