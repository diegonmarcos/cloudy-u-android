package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #1452 the Git page lists, scans and counts duplicates in ONE environment. The bug: the inventory walked the store's git folder,
 * the registered clones' parents and all of shared storage in one pass, so /storage/emulated/0/GIT/... showed under Cloud-Store
 * (list and Duplicate clones). These tests run the real walk over a store and a shared-storage tree side by side.
 */
class GitEnvironmentScopeTest {
    private val SHARED = "/storage/" + "emulated/0"

    @get:Rule val tmp = TemporaryFolder()

    private fun repo(parent: File, name: String) = File(parent, name).apply { File(this, ".git").mkdirs() }

    /** A store with git/cloud-a, shared storage with GIT/_git-repos-master and CloudDrive/git/cloud-me-pvt-x (+ a copy of cloud-a), a drive with git/drive-a. */
    private inner class Fixture {
        val store = tmp.newFolder("store")
        val emulated = tmp.newFolder("emulated")
        val drive = tmp.newFolder("drive")
        init {
            repo(File(store, "git").apply { mkdirs() }, "cloud-a")
            repo(File(emulated, "GIT").apply { mkdirs() }, "_git-repos-master")
            repo(File(emulated, "GIT").apply { mkdirs() }, "cloud-a")
            repo(File(emulated, "CloudDrive/git").apply { mkdirs() }, "cloud-me-pvt-x")
            repo(File(drive, "git").apply { mkdirs() }, "drive-a")
        }
        val roots = GitStorage.Roots(store, emulated, listOf(drive))
        /** The declared roots as the coordinator expands them: {store_git}, {repo_parents} (a polluted registry), {external}. */
        val declared = listOf(File(store, "git"), File(emulated, "GIT"), emulated)
        fun scan(kind: GitStorage.Kind, extra: List<File> = emptyList()): List<String> {
            val r = GitStorage.scanRoots(kind, declared + extra, roots)
            return InventoryScript.walkIo(r, 3, 4000, Long.MAX_VALUE, emptyList()).map { File(it.path).relativeTo(tmp.root).path }.sorted()
        }
    }

    @Test fun `the store scan finds the store's repositories only`() {
        assertEquals(listOf("store/git/cloud-a"), Fixture().scan(GitStorage.Kind.STORE))
    }

    @Test fun `the emulated scan finds shared storage only, not the store or the drive`() {
        assertEquals(
            listOf("emulated/CloudDrive/git/cloud-me-pvt-x", "emulated/GIT/_git-repos-master", "emulated/GIT/cloud-a"),
            Fixture().scan(GitStorage.Kind.EMULATED))
    }

    @Test fun `the external scan walks the attached drive only`() {
        val f = Fixture()
        assertEquals(listOf("drive/git/drive-a"), f.scan(GitStorage.Kind.EXTERNAL, extra = listOf(f.drive)))
    }

    @Test fun `a registry parent on shared storage is not a store scan root`() {
        val f = Fixture()
        val roots = GitStorage.scanRoots(GitStorage.Kind.STORE, f.declared + File(f.emulated, "GIT"), f.roots)
        assertEquals(listOf(File(f.store, "git").absolutePath), roots.map { it.absolutePath })
    }

    @Test fun `paths classify into the first environment they lie under`() {
        val f = Fixture()
        assertEquals(GitStorage.Kind.STORE, GitStorage.kindOf(File(f.store, "git/x").absolutePath, f.roots))
        assertEquals(GitStorage.Kind.EXTERNAL, GitStorage.kindOf(File(f.drive, "git/x").absolutePath, f.roots))
        assertEquals(GitStorage.Kind.EMULATED, GitStorage.kindOf(File(f.emulated, "GIT/x").absolutePath, f.roots))
        // a sibling that only shares the prefix is not inside
        assertEquals(null, GitStorage.kindOf(f.store.absolutePath + "-other/git/x", f.roots))
    }

    @Test fun `the shared storage aliases are folded into Emulated`() {
        val roots = GitStorage.Roots(File("/data/store"), File(SHARED))
        assertTrue(GitStorage.inEnvironment(GitStorage.Kind.EMULATED, "/sdcard/GIT/_git-repos-master", roots))
        assertTrue(GitStorage.inEnvironment(GitStorage.Kind.EMULATED, "/storage/self/primary/CloudDrive/git/cloud-me-pvt-x", roots))
        assertFalse(GitStorage.inEnvironment(GitStorage.Kind.STORE, SHARED + "/GIT/_git-repos-master", roots))
        assertFalse(GitStorage.inEnvironment(GitStorage.Kind.STORE, SHARED + "/CloudDrive/git/cloud-me-pvt-x", roots))
    }

    @Test fun `a path under no known root is the store's`() {
        val roots = GitStorage.Roots(File("/data/store"), File(SHARED))
        assertTrue(GitStorage.inEnvironment(GitStorage.Kind.STORE, "/data/user/0/com.termux/files/home/x", roots))
        assertFalse(GitStorage.inEnvironment(GitStorage.Kind.EMULATED, "/data/user/0/com.termux/files/home/x", roots))
    }
}
