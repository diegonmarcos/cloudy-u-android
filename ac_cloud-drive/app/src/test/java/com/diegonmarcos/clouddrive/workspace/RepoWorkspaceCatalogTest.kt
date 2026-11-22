package com.diegonmarcos.clouddrive.workspace

import com.diegonmarcos.clouddrive.sync.GitStatusReader
import com.diegonmarcos.clouddrive.sync.GitStorage
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace
import com.diegonmarcos.superapp.repoworkspace.RepoWorkspace.RepoInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * #1855 P1 libs:repo-workspace's server rules, pure: every repository on every volume (the store, its legacy mirror, Emulated,
 * an attached drive), found by folder or origin name, with the wire row the binder carries.
 */
class RepoWorkspaceCatalogTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(root: File, folder: String, name: String, head: String = "ref: refs/heads/main", origin: String = ""): File {
        val d = File(File(root, folder).apply { mkdirs() }, name)
        File(d, ".git").mkdirs()
        File(d, ".git/HEAD").writeText(head + "\n")
        if (origin.isNotEmpty()) File(d, ".git/config").writeText("[remote \"origin\"]\n\turl = $origin\n")
        return d
    }

    private fun world(): Triple<File, File, File> = Triple(tmp.newFolder("store"), tmp.newFolder("emu"), tmp.newFolder("usb"))

    @Test fun `every volume is listed and each row names its volume`() {
        val (store, emu, usb) = world()
        repo(store, "git", "alpha"); repo(emu, "Git", "beta"); repo(usb, "GIT", "gamma")
        val rows = RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(GitStorage.Roots(store, emu, listOf(usb))))
        assertEquals(listOf("alpha" to "store", "beta" to "emulated", "gamma" to "external"), rows.map { it.name to it.volume })
    }

    @Test fun `the legacy mirror counts as a store volume and one clone is listed once`() {
        val (store, emu, _) = world()
        val mirror = tmp.newFolder("mirror")
        repo(mirror, "git", "tasks"); repo(emu, "Git", "notes")
        val rows = RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(GitStorage.Roots(store, emu), mirror))
        assertEquals(listOf("notes" to "emulated", "tasks" to "store"), rows.map { it.name to it.volume })
        val same = RepoWorkspaceCatalog.list(listOf(RepoWorkspaceCatalog.Source("store", store), RepoWorkspaceCatalog.Source("store", store)).also { repo(store, "git", "a") })
        assertEquals(1, same.size)
    }

    @Test fun `a missing root, a root without a git folder and an unreadable drive are skipped`() {
        val (store, _, _) = world()
        val rows = RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(GitStorage.Roots(store, null, listOf(File(tmp.root, "gone")))))
        assertTrue(rows.isEmpty())
    }

    @Test fun `branch is read from HEAD, a detached HEAD is a short sha`() {
        val (store, _, _) = world()
        assertEquals("dev", RepoWorkspaceCatalog.branchOf(repo(store, "git", "a", "ref: refs/heads/dev")))
        assertEquals("feat/x", RepoWorkspaceCatalog.branchOf(repo(store, "git", "b", "ref: refs/heads/feat/x")))
        assertEquals("0123456", RepoWorkspaceCatalog.branchOf(repo(store, "git", "c", "0123456789abcdef0123456789abcdef01234567")))
        assertEquals("", RepoWorkspaceCatalog.branchOf(File(store, "nope")))
    }

    @Test fun `last sync is the FETCH_HEAD time, zero when never fetched`() {
        val (store, _, _) = world()
        val d = repo(store, "git", "a")
        assertEquals(0L, RepoWorkspaceCatalog.lastSyncOf(d))
        File(d, ".git/FETCH_HEAD").apply { writeText("x"); setLastModified(1_700_000_000_000L) }
        assertEquals(1_700_000_000_000L, RepoWorkspaceCatalog.lastSyncOf(d))
    }

    @Test fun `findRepo matches the folder name or the origin name, case-insensitively, and nothing else`() {
        val (store, emu, _) = world()
        repo(store, "git", "cloud-me-pvt-tasks"); repo(emu, "Git", "renamed", origin = "https://github.com/o/Cloud-Notes.git")
        val rows = RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(GitStorage.Roots(store, emu)))
        assertEquals(listOf("emulated"), RepoWorkspaceCatalog.find(rows, "CLOUD-notes").map { it.volume })
        assertEquals(listOf("store"), RepoWorkspaceCatalog.find(rows, "Cloud-Me-Pvt-Tasks").map { it.volume })
        assertTrue(RepoWorkspaceCatalog.find(rows, "absent").isEmpty())
        assertTrue(RepoWorkspaceCatalog.find(rows, "../etc").isEmpty())
        assertTrue(RepoWorkspaceCatalog.find(rows, "").isEmpty())
    }

    @Test fun `the same repository on two volumes answers both rows`() {
        val (store, emu, _) = world()
        repo(store, "git", "tasks"); repo(emu, "Git", "tasks")
        val rows = RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(GitStorage.Roots(store, emu)))
        assertEquals(setOf("store", "emulated"), RepoWorkspaceCatalog.find(rows, "tasks").map { it.volume }.toSet())
    }

    @Test fun `the cached status fills state and summary, an unread repo leaves them empty`() {
        val (store, _, _) = world()
        val a = repo(store, "git", "a"); val b = repo(store, "git", "b")
        val cached = mapOf(a.absolutePath to GitStatusReader.Status(GitStatusReader.State.DIRTY, GitStatusReader.Probe("main", "origin/main", 0, 0, 3, 0, fetchedAtMs = 42L), readAtMs = 5, path = a.absolutePath))
        val ra = RepoWorkspaceCatalog.info(a, "store", cached); val rb = RepoWorkspaceCatalog.info(b, "store", cached)
        assertEquals("dirty" to "dirty 3", ra.state to ra.summary)
        assertEquals(42L, ra.lastSyncMs)
        assertEquals("" to "", rb.state to rb.summary)
        assertEquals("main", rb.branch)
    }

    @Test fun `status answers only a path of a listed repository`() {
        val (store, _, _) = world()
        repo(store, "git", "a")
        val rows = RepoWorkspaceCatalog.list(RepoWorkspaceCatalog.sources(GitStorage.Roots(store, null)))
        assertEquals("a", RepoWorkspaceCatalog.statusOf(rows, rows[0].path + "/")?.name)
        assertNull(RepoWorkspaceCatalog.statusOf(rows, "/data/data/other/files"))
    }

    @Test fun `a clone request is accepted only for a plain repository name`() {
        assertEquals("", RepoWorkspaceCatalog.refusal("cloud-me-pvt-tasks"))
        for (bad in listOf("", "  ", ".", "..", "a/b", "..\\x", "a\nb", "x".repeat(101))) assertFalse(bad, RepoWorkspaceCatalog.refusal(bad).isEmpty())
        assertFalse(RepoWorkspaceCatalog.refusal(null).isEmpty())
    }

    @Test fun `the wire row round-trips and survives a tab in a field and a short or hostile row`() {
        val r = RepoInfo("n", "/p/n", RepoWorkspace.VOLUME_EMULATED, "main", 7L, "dirty", "dirty\t2\nx")
        val back = RepoInfo.decode(r.encode())!!
        assertEquals(r.copy(summary = "dirty 2 x"), back)
        assertEquals(RepoInfo("n", "/p", ""), RepoInfo.decode("n\t/p"))
        assertNull(RepoInfo.decode("")); assertNull(RepoInfo.decode("onlyname")); assertNull(RepoInfo.decode(null))
        assertEquals(0L, RepoInfo.decode("n\t/p\tstore\tmain\tNaN")!!.lastSyncMs)
        assertEquals(listOf("a"), RepoInfo.decodeAll(arrayOf("a\t/a", "bad")).map { it.name })
    }

    @Test fun `the contract constants name Cloud Drive and the fleet permission`() {
        assertEquals(listOf("com.diegonmarcos.clouddrive"), RepoWorkspace.SERVICE_PACKAGES)
        assertEquals("com.diegonmarcos.cloud.permission.CONSTELLATION_DATA", RepoWorkspace.PERMISSION)
        assertEquals(GitStorage.Kind.values().map { it.id }, RepoWorkspace.VOLUMES)
    }
}
