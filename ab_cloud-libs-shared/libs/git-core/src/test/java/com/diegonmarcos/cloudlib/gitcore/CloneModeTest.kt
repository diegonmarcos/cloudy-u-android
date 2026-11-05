package com.diegonmarcos.cloudlib.gitcore

import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** #1491 the five declared clone modes: the git command each stands for, and the JGit runs of the ones JGit can do. */
class CloneModeTest {
    private val full = CloneMode("full", "Full")
    private val depth3 = CloneMode("depth3", "Full Depth 3", depth = 3)
    private val mainOnly = CloneMode("bare_main", "Bare Main Only", bare = true, singleBranch = true)
    private val blobless = CloneMode("bare_blobless", "Bare Blobless", bare = true, filter = "blob:none")
    private val treeless = CloneMode("bare_treeless", "Bare Treeless", bare = true, filter = "tree:0")

    private lateinit var root: File
    private lateinit var work: File
    private val url get() = "file://" + work.absolutePath

    @Before fun setUp() {
        root = Files.createTempDirectory("clone-mode-test").toFile()
        work = File(root, "work")
        JGitEngine.init(work).use { e ->
            for (n in 1..5) { File(work, "a.txt").writeText("v$n\n"); e.stageAll(); e.commit("c$n", "T", "t@x") }
        }
        Git.open(work).use { g -> g.branchCreate().setName("side").call() }
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `each mode is the git command of the table`() {
        assertEquals("clone U D", full.args("U", "D"))
        assertEquals("clone --depth 3 U D", depth3.args("U", "D"))
        assertEquals("clone --bare --single-branch --branch main U D", mainOnly.args("U", "D", "main"))
        assertEquals("clone --bare --filter=blob:none U D", blobless.args("U", "D"))
        assertEquals("clone --bare --filter=tree:0 U D", treeless.args("U", "D"))
    }

    @Test fun `only the filter modes need the git binary and a bare mode names a dot git folder`() {
        assertEquals(listOf(false, false, false, true, true), listOf(full, depth3, mainOnly, blobless, treeless).map { it.needsGitBinary })
        assertEquals("repo.git", mainOnly.folderName("repo")); assertEquals("repo.git", blobless.folderName("repo.git")); assertEquals("repo", full.folderName("repo"))
    }

    @Test fun `Full Depth 3 through JGit keeps three commits and the working files`() {
        val dest = File(root, "d3")
        JGitEngine.clone(url, dest, depth3).close()
        assertTrue(File(dest, "a.txt").isFile)
        Git.open(dest).use { g -> assertEquals(3, g.log().call().count()) }
    }

    @Test fun `Bare Main Only through JGit holds the default branch alone`() {
        val dest = File(root, "m.git")
        JGitEngine.clone(url, dest, mainOnly).close()
        assertTrue(JGitEngine.isBare(dest))
        Git.open(dest).use { g ->
            val heads = g.branchList().call().map { it.name }
            assertEquals(listOf(JGitEngine.defaultBranch(url)).map { "refs/heads/$it" }, heads)
            assertFalse(heads.contains("refs/heads/side"))
        }
    }

    @Test fun `a filter mode is refused by JGit, never a silent full clone`() {
        for (m in listOf(blobless, treeless)) {
            val dest = File(root, "f-" + m.id)
            val e = runCatching { JGitEngine.clone(url, dest, m) }.exceptionOrNull()
            assertTrue("${m.id} must be refused", e is IllegalArgumentException)
            assertFalse("nothing was cloned", dest.exists())
        }
    }
}
