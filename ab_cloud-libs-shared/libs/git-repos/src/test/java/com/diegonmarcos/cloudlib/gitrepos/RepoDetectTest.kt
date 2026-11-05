package com.diegonmarcos.cloudlib.gitrepos

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1491 one list for every repo folder: working trees and bare repositories side by side, no bare-only section. */
class RepoDetectTest {
    private lateinit var root: File

    @Before fun setUp() {
        root = Files.createTempDirectory("repo-detect").toFile()
        File(root, "alpha/.git").mkdirs()
        File(root, "beta.git/objects").mkdirs(); File(root, "beta.git/HEAD").writeText("ref: refs/heads/main\n")
        File(root, "gamma/.git").mkdirs()
        File(root, "plain").mkdirs()
        File(root, "notbare.git").mkdirs()
        File(root, "file.txt").writeText("x")
    }

    @After fun tearDown() { root.deleteRecursively() }

    @Test fun `one list holds the working trees and the bare repository`() {
        val repos = RepoDetect.scan(root)
        assertEquals(listOf("alpha", "beta", "gamma"), repos.map { it.repoName })
        assertEquals(listOf(false, true, false), repos.map { it.bare })
        assertTrue("the bare repo sits between the others: no separate section", repos.indexOfFirst { it.bare } in 1 until repos.lastIndex)
    }

    @Test fun `a plain folder, a dot git folder without HEAD and a file are not repos`() {
        assertNull(RepoDetect.detect(File(root, "plain")))
        assertNull(RepoDetect.detect(File(root, "notbare.git")))
        assertNull(RepoDetect.detect(File(root, "file.txt")))
    }

    @Test fun `every command points git at a bare repo with --git-dir and a working tree with -C`() {
        val wt = RepoDetect.detect(File(root, "alpha"))!!; val bare = RepoDetect.detect(File(root, "beta.git"))!!
        for (verb in listOf("status", "fetch", "pull", "worktree")) {
            assertEquals(listOf("-C", wt.dir.path, verb), RepoDetect.git(wt, verb))
            assertEquals(listOf("--git-dir", bare.dir.path, verb), RepoDetect.git(bare, verb))
        }
        assertEquals(listOf("--git-dir", bare.dir.path, "worktree", "add", "/w", "main"), RepoDetect.git(bare, "worktree", "add", "/w", "main"))
        assertEquals(File(root, "beta.git"), bare.gitDir); assertEquals(File(root, "alpha/.git"), wt.gitDir)
    }

    @Test fun `the shell prefix picks -C or --git-dir where the folder is`() {
        val s = RepoDetect.shellPrefix("/s/x.git", { "'$it'" })
        assertTrue(s, s.contains("[ -e '/s/x.git/.git' ]") && s.contains("echo -C") && s.contains("echo --git-dir") && s.endsWith("'/s/x.git'"))
    }
}
