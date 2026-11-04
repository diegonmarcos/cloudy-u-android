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
        assertEquals(GitStorage.Listing.NoDrive, GitStorage.view(null))
        val r = tmp.newFolder("v")
        assertTrue(GitStorage.view(r) is GitStorage.Listing.NoGitFolder)
        repo(File(r, "git"), "a")
        val v = GitStorage.view(r) as GitStorage.Listing.Repos
        assertEquals(listOf("a"), v.repos.map { it.name })
    }

    @Test fun `unknown stored ids fall back to the store`() {
        assertEquals(GitStorage.Kind.STORE, GitStorage.Kind.of(null))
        assertEquals(GitStorage.Kind.STORE, GitStorage.Kind.of("x"))
        assertEquals(GitStorage.Kind.EXTERNAL, GitStorage.Kind.of("external"))
    }

    // #1356 clone onto a volume: the target picker, the target folder, and the states that stop a clone.
    private fun t(kind: GitStorage.Kind, root: File?, label: String = kind.id, raw: Boolean = false) = GitStorage.Target(kind, label, root, raw = raw)

    @Test fun `the picker lists Cloud-Store, Emulated and each drive by label, defaulting to the selected storage`() {
        val store = t(GitStorage.Kind.STORE, tmp.newFolder("s")); val emu = t(GitStorage.Kind.EMULATED, tmp.newFolder("e"))
        val a = t(GitStorage.Kind.EXTERNAL, tmp.newFolder("a"), "SANDISK"); val b = t(GitStorage.Kind.EXTERNAL, tmp.newFolder("b"), "KINGSTON")
        val all = GitStorage.targets(store, emu, listOf(a, b))
        assertEquals(listOf("store", "emulated", "SANDISK", "KINGSTON"), all.map { it.label })
        assertEquals(emu, GitStorage.defaultTarget(all, GitStorage.Kind.EMULATED))
        assertEquals(a, GitStorage.defaultTarget(all, GitStorage.Kind.EXTERNAL))
        assertEquals(store, GitStorage.defaultTarget(all, GitStorage.Kind.STORE))
        // no drive attached: External is still a choice, with no root
        val none = GitStorage.targets(store, emu, emptyList())
        assertEquals(3, none.size); assertNull(none[2].root); assertEquals(GitStorage.Kind.EXTERNAL, none[2].kind)
    }

    @Test fun `a clone lands in the volume's git folder, any case`() {
        val root = tmp.newFolder("drive"); val git = File(root, "GIT").apply { mkdirs() }
        assertEquals(GitStorage.CloneAt.Ready(File(git, "tiny")), GitStorage.cloneAt(t(GitStorage.Kind.EXTERNAL, root), "tiny"))
    }

    @Test fun `a missing git folder offers to create root-git, no drive and a raw drive refuse`() {
        val root = tmp.newFolder("bare")
        assertEquals(GitStorage.CloneAt.NeedsFolder(File(root, "git")), GitStorage.cloneAt(t(GitStorage.Kind.EMULATED, root), "x"))
        assertEquals(GitStorage.CloneAt.NoDrive, GitStorage.cloneAt(t(GitStorage.Kind.EXTERNAL, null), "x"))
        assertEquals(GitStorage.CloneAt.RawReadOnly, GitStorage.cloneAt(t(GitStorage.Kind.EXTERNAL, null, raw = true), "x"))
        assertEquals(GitStorage.Listing.NoDrive, GitStorage.view(null))
        assertEquals(GitStorage.Listing.RawOnly, GitStorage.view(null, rawAttached = true))
        assertEquals(GitStorage.Listing.NoGitFolder(root.absolutePath), GitStorage.view(root))
    }

    @Test fun `write access - mounted and writable, needs the SAF grant, raw is read-only`() {
        assertEquals(GitStorage.Access.WRITABLE, GitStorage.access(raw = false, canWrite = true))
        assertEquals(GitStorage.Access.NEEDS_GRANT, GitStorage.access(raw = false, canWrite = false))
        assertEquals(GitStorage.Access.RAW_READ_ONLY, GitStorage.access(raw = true, canWrite = true))
        assertTrue(GitStorage.onVolume("/storage/" + "emulated/0/git/a")); assertTrue(GitStorage.onVolume("/storage/1A2B-3C4D/GIT/a"))
        assertTrue(!GitStorage.onVolume("/data/data/x/git")); assertTrue(!GitStorage.onVolume("/storage/self/primary/git"))
    }
}
