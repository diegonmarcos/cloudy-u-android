package com.diegonmarcos.clouddrive.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** #1607 the Git group shows only under a git working tree; this pins the walk-up detection. */
class GitWorkTreeTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun findsTheRootFromADeepFileWhenAGitDirExists() {
        val repo = tmp.newFolder("repo")
        File(repo, ".git").mkdir()
        val deep = File(repo, "a/b/c").apply { mkdirs() }
        val file = File(deep, "note.txt").apply { writeText("x") }
        assertEquals(repo.absoluteFile, GitWorkTree.rootOf(file.path))
        assertTrue(GitWorkTree.isUnderGit(file.path))
    }

    @Test fun aGitFileGitlinkAlsoCounts() {
        val repo = tmp.newFolder("worktree")
        File(repo, ".git").writeText("gitdir: /elsewhere/.git/worktrees/wt")
        val file = File(repo, "f").apply { writeText("x") }
        assertEquals(repo.absoluteFile, GitWorkTree.rootOf(file.path))
    }

    @Test fun noGitAnywhereMeansNoRoot() {
        val plain = tmp.newFolder("plain")
        val file = File(plain, "x").apply { writeText("x") }
        assertNull(GitWorkTree.rootOf(file.path))
        assertFalse(GitWorkTree.isUnderGit(file.path))
    }

    @Test fun aDirectoryThatIsItselfTheRoot() {
        val repo = tmp.newFolder("r")
        File(repo, ".git").mkdir()
        assertEquals(repo.absoluteFile, GitWorkTree.rootOf(repo.path))
    }
}
