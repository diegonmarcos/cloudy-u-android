package com.diegonmarcos.cloudlib.gitcore

import com.diegonmarcos.cloudlib.gitsync.GitWire
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1452 Clone Bare All (no hist): `--bare --depth 1` through JGit (CloneCommand.setBare + setDepth): the repository only, no working tree, latest commit only. */
class BareCloneTest {
    private lateinit var root: File
    private lateinit var work: File

    @Before fun setUp() {
        root = Files.createTempDirectory("bare-clone-test").toFile()
        work = File(root, "work")
        JGitEngine.init(work).use { e ->
            for (n in 1..3) {
                File(work, "a.txt").writeText("v$n\n"); File(work, "dir").mkdirs(); File(work, "dir/b.txt").writeText("b$n\n")
                e.stageAll(); e.commit("c$n", "T", "t@x")
            }
        }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `a bare depth 1 clone has the repository only - no working tree, no dot git, one commit`() {
        val dest = File(root, "out/repo.git")
        JGitEngine.clone("file://" + work.absolutePath, dest, depth = 1, bare = true).close()
        assertTrue(JGitEngine.isBare(dest))
        assertFalse("a bare clone has no .git folder", File(dest, ".git").exists())
        assertFalse("no working-tree files", File(dest, "a.txt").exists() || File(dest, "dir").exists())
        assertFalse(JGitEngine.isRepository(dest))
        Git.open(dest).use { g ->
            assertTrue(g.repository.isBare)
            assertEquals("depth 1: only the latest commit", 1, g.log().call().count())
        }
        assertTrue("shallow", File(dest, "shallow").isFile)
    }

    @Test fun `a normal depth 1 clone still has its working files and a dot git`() {
        val dest = File(root, "out/normal")
        JGitEngine.clone("file://" + work.absolutePath, dest, depth = 1).close()
        assertTrue(File(dest, "a.txt").isFile)
        assertTrue(JGitEngine.isRepository(dest))
        assertFalse(JGitEngine.isBare(dest))
    }

    @Test fun `the wire verb carries the bare flag`() {
        val dest = File(root, "out/wire.git")
        val calls = GitCalls(File(root, "scratch"), "7.8.0", { true })
        val out = org.json.JSONObject(calls.git(GitWire.request(dest, "clone", org.json.JSONObject().put("url", "file://" + work.absolutePath).put("depth", 1).put("bare", true), null)))
        assertTrue(out.toString(), out.getBoolean("ok"))
        assertTrue(JGitEngine.isBare(dest))
    }
}
