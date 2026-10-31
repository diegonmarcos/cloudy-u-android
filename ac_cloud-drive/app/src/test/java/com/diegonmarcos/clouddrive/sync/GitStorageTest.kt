package com.diegonmarcos.clouddrive.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1376 the Git page's storage root detection: the git folder in any case, its repositories, the empty states. */
class GitStorageTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(parent: File, name: String) = File(parent, name).apply { File(this, ".git").mkdirs() }

    @Test fun `the git folder matches any case and prefers exact git`() {
        val r = tmp.newFolder("r")
        assertNull(GitStorage.gitFolder(r))
        File(r, "GIT").mkdirs()
        assertEquals("GIT", GitStorage.gitFolder(r)?.name)
        File(r, "Git").mkdirs()
        // case-insensitive file systems (FAT/exFAT drives) may fold these into one; on ext4 both exist and "GIT" < "Git"
        assertTrue(GitStorage.gitFolder(r)?.name in setOf("GIT", "Git"))
        File(r, "git").mkdirs()
        assertEquals("git", GitStorage.gitFolder(r)?.name)
        File(r, "gitx").mkdirs()
        assertEquals("git", GitStorage.gitFolder(r)?.name)
    }

    @Test fun `a file named git is not the folder`() {
        val r = tmp.newFolder("f"); File(r, "git").writeText("x")
        assertNull(GitStorage.gitFolder(r))
    }

    @Test fun `repositories are the sub-folders with a dot git, by name`() {
        val g = File(tmp.newFolder("s"), "Git").apply { mkdirs() }
        repo(g, "zeta"); repo(g, "Alpha"); File(g, "plain").mkdirs()
        File(g, "wt").mkdirs(); File(g, "wt/.git").writeText("gitdir: ../x")
        assertEquals(listOf("Alpha", "wt", "zeta"), GitStorage.repos(g).map { it.name })
    }

    @Test fun `views - no drive, no git folder, repos`() {
        assertEquals(GitStorage.View.NoDrive, GitStorage.view(null))
        val r = tmp.newFolder("v")
        assertTrue(GitStorage.view(r) is GitStorage.View.NoGitFolder)
        repo(File(r, "git"), "a")
        val v = GitStorage.view(r) as GitStorage.View.Repos
        assertEquals(listOf("a"), v.repos.map { it.name })
    }

    @Test fun `unknown stored ids fall back to the store`() {
        assertEquals(GitStorage.Kind.STORE, GitStorage.Kind.of(null))
        assertEquals(GitStorage.Kind.STORE, GitStorage.Kind.of("x"))
        assertEquals(GitStorage.Kind.EXTERNAL, GitStorage.Kind.of("external"))
    }
}
