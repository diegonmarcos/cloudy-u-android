package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.ConflictSide
import com.diegonmarcos.cloudlib.gitsync.GitAuth
import com.diegonmarcos.cloudlib.gitsync.GitChange
import com.diegonmarcos.cloudlib.gitsync.GitFileStatus

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The manager's verbs, proven on REAL repositories: every test drives the same
 * JGit code that runs on the phone, against temp directories, pushing and
 * pulling over the file:// transport so no network and no credential is
 * involved. Nothing is mocked; a verb that stops doing what its name says
 * fails here, in the unit phase of every cloud-drive ship.
 */
class JGitEngineTest {

    private lateinit var root: File
    private lateinit var work: File
    private lateinit var bare: File
    /** JGit normalises a file URI to file:///…; Java's File.toURI() prints file:/…, so the test speaks JGit's form. */
    private val bareUrl: String get() = "file://" + bare.absolutePath

    private fun write(dir: File, path: String, text: String) {
        val f = File(dir, path); f.parentFile.mkdirs(); f.writeText(text)
    }

    @Before fun setUp() {
        root = Files.createTempDirectory("git-sync-test").toFile()
        work = File(root, "work")
        bare = File(root, "remote.git")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(bare).call().close()
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun initIsARepositoryAndStatusStartsClean() {
        assertFalse(JGitEngine.isRepository(work))
        JGitEngine.init(work).use { e ->
            assertTrue(JGitEngine.isRepository(work))
            val s = e.status()
            assertTrue(s.isClean)
            assertEquals("SAFE", s.repositoryState)
            assertTrue(e.log().isEmpty())      // unborn branch: no NoHeadException leaks out
        }
    }

    @Test fun untrackedThenStagedThenCommittedThenModified() {
        JGitEngine.init(work).use { e ->
            write(work, "notes/a.md", "hello\n")
            var s = e.status()
            assertEquals(listOf(GitFileStatus("notes/a.md", unstaged = GitChange.UNTRACKED)), s.files)
            assertTrue(s.staged.isEmpty())

            e.stage(listOf("notes/a.md"))
            s = e.status()
            assertEquals(GitChange.ADDED, s.staged.single().staged)
            assertTrue(s.unstaged.isEmpty())

            // Unstage on an UNBORN branch: the index entry goes, the file stays.
            e.unstage(listOf("notes/a.md"))
            s = e.status()
            assertEquals(GitChange.UNTRACKED, s.unstaged.single().unstaged)
            assertTrue(File(work, "notes/a.md").isFile)

            e.stageAll()
            val c = e.commit("first", "Tester", "t@example.com")
            assertEquals("first", c.summary)
            assertEquals("Tester", c.author)
            assertTrue(c.parents.isEmpty())
            assertTrue(e.status().isClean)
            assertEquals(listOf(c.sha), e.log().map { it.sha })

            write(work, "notes/a.md", "hello\nworld\n")
            s = e.status()
            assertEquals(GitChange.MODIFIED, s.unstaged.single().unstaged)
            val d = e.diff("notes/a.md")
            assertTrue("diff shows the added line: $d", d.contains("+world"))
            assertFalse(d.contains("-hello"))

            e.stage(listOf("notes/a.md"))
            assertTrue(e.diff("notes/a.md", staged = true).contains("+world"))
            // Edited again after staging: BOTH sides set on the one path.
            write(work, "notes/a.md", "hello\nworld\n!\n")
            val both = e.status().files.single()
            assertEquals(GitChange.MODIFIED, both.staged)
            assertEquals(GitChange.MODIFIED, both.unstaged)

            // Unstage on a BORN branch goes through reset --mixed on the path.
            e.unstage(listOf("notes/a.md"))
            val after = e.status().files.single()
            assertNull(after.staged)
            assertEquals(GitChange.MODIFIED, after.unstaged)
        }
    }

    @Test fun deletedFileIsStagedAsDeletionAndDiscardRestores() {
        JGitEngine.init(work).use { e ->
            write(work, "keep.txt", "k\n"); write(work, "gone.txt", "g\n")
            e.stageAll(); e.commit("base", "T", "t@x")
            File(work, "gone.txt").delete()
            assertEquals(GitChange.DELETED, e.status().unstaged.single().unstaged)
            e.stage(listOf("gone.txt"))
            assertEquals(GitChange.DELETED, e.status().staged.single().staged)
            e.unstage(listOf("gone.txt"))
            e.discard("gone.txt")
            assertTrue(File(work, "gone.txt").isFile)
            assertTrue(e.status().isClean)

            write(work, "scratch.txt", "x")
            e.discard("scratch.txt")   // untracked: deleted outright
            assertFalse(File(work, "scratch.txt").exists())
        }
    }

    @Test fun untrackedDiffShowsWholeFileAndCommitDiffAgainstParentAndEmptyTree() {
        JGitEngine.init(work).use { e ->
            write(work, "new.txt", "one\ntwo\n")
            val whole = e.diff("new.txt")
            assertTrue(whole.contains("+one") && whole.contains("+two"))
            e.stageAll(); val first = e.commit("root", "T", "t@x")
            val rootDiff = e.commitDiff(first.sha)
            assertTrue("root commit diffs against the empty tree: $rootDiff", rootDiff.contains("+one"))
            write(work, "new.txt", "one\ntwo\nthree\n"); e.stageAll(); val second = e.commit("more", "T", "t@x")
            val d = e.commitDiff(second.sha)
            assertTrue(d.contains("+three")); assertFalse(d.contains("+one"))
            assertEquals(listOf(first.sha), second.parents)
            assertEquals(listOf(second.sha, first.sha), e.log().map { it.sha })
            assertEquals(1, e.log(max = 1).size)
        }
    }

    @Test fun amendRewritesTheTip() {
        JGitEngine.init(work).use { e ->
            write(work, "a", "1"); e.stageAll(); val c1 = e.commit("typo", "T", "t@x")
            val c2 = e.commit("fixed", "T", "t@x", amend = true)
            assertEquals(listOf(c2.sha), e.log().map { it.sha })
            assertEquals("fixed", e.log().single().summary)
            assertFalse(c1.sha == c2.sha)
        }
    }

    @Test fun remotesAreListedAddedRenamedAndRemoved() {
        JGitEngine.init(work).use { e ->
            assertTrue(e.remotes().isEmpty())
            e.addRemote("origin", bareUrl)
            val r = e.remotes().single()
            assertEquals("origin", r.name)
            assertEquals(bareUrl, r.fetchUrl)
            assertEquals(r.fetchUrl, r.pushUrl)
            e.setRemoteUrl("origin", "https://example.invalid/x.git", push = true)
            assertEquals("https://example.invalid/x.git", e.remotes().single().pushUrl)
            assertEquals(bareUrl, e.remotes().single().fetchUrl)
            e.removeRemote("origin")
            assertTrue(e.remotes().isEmpty())
        }
    }

    @Test fun pushSetsUpstreamThenPullAndFetchTrackAheadBehind() {
        JGitEngine.init(work).use { e ->
            write(work, "f", "1\n"); e.stageAll(); e.commit("c1", "T", "t@x")
            e.addRemote("origin", bareUrl)
            assertNull(e.status().upstream)
            val pushed = e.push("origin")
            assertTrue(pushed.summary, pushed.ok)
            val s = e.status()
            // Whatever the initial branch is named on this machine (JGit honours
            // init.defaultBranch), the upstream recorded by the first push is
            // origin/<that branch> — read, not assumed.
            val branch = s.branch!!
            assertEquals("origin/$branch", s.upstream)
            assertEquals(0, s.ahead); assertEquals(0, s.behind)
            assertTrue(e.push("origin").summary.contains("up to date"))

            write(work, "f", "2\n"); e.stageAll(); e.commit("c2", "T", "t@x")
            assertEquals(1, e.status().ahead)
            val branches = e.branches()
            assertTrue(branches.any { !it.isRemote && it.isCurrent && it.name == branch })
            assertTrue(branches.any { it.isRemote && it.name == "origin/$branch" })
        }

        // A second clone commits and pushes; the first is then BEHIND until it pulls.
        val other = File(root, "other")
        JGitEngine.clone(bareUrl, other).use { o ->
            write(other, "g", "from other\n"); o.stageAll(); o.commit("c3", "O", "o@x")
            assertTrue(o.push("origin").ok)
        }
        JGitEngine(work).use { e ->
            assertTrue(e.fetch("origin").ok)
            val s = e.status()
            assertEquals(1, s.ahead); assertEquals(1, s.behind)
            val pulled = e.pull("origin", rebase = true)
            assertTrue(pulled.summary, pulled.ok)
            assertTrue(File(work, "g").isFile)
            assertEquals(0, e.status().behind)
            assertTrue(e.push("origin").ok)
            assertEquals(0, e.status().ahead)
        }
    }

    @Test fun conflictSurfacesInStatusAndResolvesOursOrTheirs() {
        // Seed the remote with one file.
        JGitEngine.init(work).use { e ->
            write(work, "shared.txt", "base\n"); e.stageAll(); e.commit("base", "T", "t@x")
            e.addRemote("origin", bareUrl); assertTrue(e.push("origin").ok)
        }
        val other = File(root, "other")
        JGitEngine.clone(bareUrl, other).use { o ->
            write(other, "shared.txt", "theirs\n"); o.stageAll(); o.commit("theirs", "O", "o@x"); assertTrue(o.push("origin").ok)
        }
        JGitEngine(work).use { e ->
            write(work, "shared.txt", "ours\n"); e.stageAll(); e.commit("ours", "T", "t@x")
            val pulled = e.pull("origin", rebase = false)
            assertFalse("a diverging edit must not report success: ${pulled.summary}", pulled.ok)
            assertTrue(pulled.summary, pulled.summary.contains("conflict"))
            val s = e.status()
            assertEquals("MERGING", s.repositoryState)
            val c = s.conflicts.single()
            assertEquals("shared.txt", c.path)
            assertNotNull(c.stageState)
            assertTrue(File(work, "shared.txt").readText().contains("<<<<<<<"))

            e.resolve("shared.txt", ConflictSide.THEIRS)
            assertEquals("theirs\n", File(work, "shared.txt").readText())
            assertTrue(e.status().conflicts.isEmpty())
            e.commit("merge", "T", "t@x")
            assertEquals("SAFE", e.status().repositoryState)
            assertTrue(e.push("origin").ok)
            // Diverge the remote once more, so the other clone's next pull is a
            // real three-way conflict and not a clean merge over an unchanged side.
            write(work, "shared.txt", "work again\n"); e.stageAll(); e.commit("work again", "T", "t@x")
            assertTrue(e.push("origin").ok)
        }

        // The mirror case: OURS, then abort instead of committing.
        JGitEngine(other).use { o ->
            write(other, "shared.txt", "mine again\n"); o.stageAll(); o.commit("again", "O", "o@x")
            assertFalse(o.pull("origin", rebase = false).ok)
            o.resolve("shared.txt", ConflictSide.OURS)
            assertEquals("mine again\n", File(other, "shared.txt").readText())
            o.abortMerge()
            assertEquals("SAFE", o.status().repositoryState)
            assertTrue(o.status().isClean)

            // Same divergence, third way through: the user edits the markers away
            // by hand and marks the path resolved.
            assertFalse(o.pull("origin", rebase = false).ok)
            assertTrue(File(other, "shared.txt").readText().contains("======="))
            write(other, "shared.txt", "hand merged\n")
            o.markResolved("shared.txt")
            assertTrue(o.status().conflicts.isEmpty())
            assertEquals(GitChange.MODIFIED, o.status().staged.single().staged)
            o.commit("hand merge", "O", "o@x")
            assertEquals("SAFE", o.status().repositoryState)
            assertTrue(o.push("origin").ok)
        }
    }

    @Test fun syncCommitsPullsAndPushesInOneTap() {
        JGitEngine.init(work).use { e ->
            write(work, "n.md", "v1\n")
            val noRemote = e.sync("sync", "T", "t@x")
            assertTrue(noRemote.ok)
            assertTrue(noRemote.summary, noRemote.summary.contains("committed") && noRemote.summary.contains("no remote"))
            e.addRemote("origin", bareUrl)
            write(work, "n.md", "v2\n")
            val r = e.sync("sync", "T", "t@x")
            assertTrue(r.summary, r.ok)
            assertTrue(r.summary.contains("committed") && r.summary.contains("pushed"))
            assertEquals(0, e.status().ahead)
            val again = e.sync("sync", "T", "t@x")
            assertTrue(again.summary, again.ok && again.summary.contains("nothing to commit"))
        }
    }

    /**
     * #608 the two force verbs, each proven to do what a plain push/pull CANNOT:
     * the same divergence is driven twice, once resolved by overwriting the remote
     * and once by overwriting the local side, and the plain verb is shown to refuse
     * it first — otherwise the test would pass on an engine where `forcePush` is a
     * synonym for `push`.
     */
    @Test fun forcePushOverwritesTheRemoteAndForcePullOverwritesTheLocalSide() {
        JGitEngine.init(work).use { e ->
            write(work, "f.txt", "base\n"); e.stageAll(); e.commit("base", "T", "t@x")
            e.addRemote("origin", bareUrl)
            assertTrue(e.push("origin").ok)
        }
        val other = File(root, "other")
        JGitEngine.clone(bareUrl, other).use { o ->
            write(other, "f.txt", "theirs\n"); o.stageAll(); o.commit("theirs", "O", "o@x")
            assertTrue(o.push("origin").ok)
        }
        // work rewrites its own tip: a plain push is a non-fast-forward and must be REJECTED.
        JGitEngine(work).use { e ->
            write(work, "f.txt", "ours\n"); e.stageAll(); e.commit("ours", "T", "t@x")
            val plain = e.push("origin")
            assertFalse("a diverged push must be rejected: ${plain.summary}", plain.ok)
            val forced = e.forcePush("origin")
            assertTrue(forced.summary, forced.ok)
            assertTrue(forced.summary, forced.summary.contains("force-pushed"))
            assertEquals(0, e.status().ahead)
        }
        // other is now on a history the remote no longer has: a plain pull conflicts,
        // a force pull throws the local side away and lands exactly on the remote tip.
        JGitEngine(other).use { o ->
            write(other, "f.txt", "mine again\n"); o.stageAll(); o.commit("again", "O", "o@x")
            assertFalse("a diverged pull must not report success", o.pull("origin", rebase = false).ok)
            o.abortMerge()
            val forced = o.forcePull("origin")
            assertTrue(forced.summary, forced.ok)
            assertTrue(forced.summary, forced.summary.contains("force-pulled"))
            assertEquals("ours\n", File(other, "f.txt").readText())
            assertTrue(o.status().isClean)
            assertEquals(0, o.status().ahead)
            assertEquals(0, o.status().behind)
        }
        // A force pull against a remote that has no such branch says so; it does not reset onto nothing.
        val fresh = File(root, "fresh")
        val empty = File(root, "empty.git")
        org.eclipse.jgit.api.Git.init().setBare(true).setDirectory(empty).call().close()
        JGitEngine.init(fresh).use { f ->
            write(fresh, "x", "1"); f.stageAll(); f.commit("only", "T", "t@x")
            f.addRemote("origin", "file://" + empty.absolutePath)
            val r = f.forcePull("origin")
            assertFalse(r.summary, r.ok)
            assertTrue(r.summary, r.summary.contains("nothing to reset onto"))
        }
    }

    @Test fun sessionAuthNeverPrintsItsValueAndOnlyTouchesHttpTransports() {
        // #669 the session is an Authelia cookie held in process memory; a stray
        // string template or log line on the carrier must not leak it.
        val session = GitAuth.Session(header = "Cookie", value = "authelia_session=SECRET")
        assertFalse("$session", "$session".contains("SECRET"))
        assertTrue("$session", "$session".contains("<redacted>"))
        // On a non-HTTP transport the callback is a no-op, so a Session against a
        // local/file remote clones anonymously instead of crashing: clone, fetch
        // and pull all take the same withAuth path.
        JGitEngine.init(work).use { e ->
            write(work, "f", "1\n"); e.stageAll(); e.commit("c1", "T", "t@x")
            e.addRemote("origin", bareUrl)
            assertTrue(e.push("origin").ok)
        }
        val other = File(root, "session-clone")
        JGitEngine.clone(bareUrl, other, auth = session).use { o ->
            assertTrue(File(other, "f").isFile)
            assertTrue(o.fetch("origin", auth = session).ok)
            assertTrue(o.pull("origin", auth = session).ok)
        }
    }

    @Test fun branchesAreReadOnlyByConstruction() {
        // The fleet rule: a manager may display branches, never create them. The
        // engine's public surface has no verb that does — checked by name so a
        // future "helpful" addition fails here before it ships.
        val verbs = JGitEngine::class.java.methods.map { it.name }.toSet()
        for (forbidden in listOf("createBranch", "deleteBranch", "checkoutBranch", "branchCreate", "renameBranch")) {
            assertFalse("engine exposes $forbidden", forbidden in verbs)
        }
        assertTrue("branches" in verbs)
    }
}
