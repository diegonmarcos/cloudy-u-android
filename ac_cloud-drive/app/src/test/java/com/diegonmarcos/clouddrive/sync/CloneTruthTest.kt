package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.Entry
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.Kind
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory.RepoClass
import com.diegonmarcos.clouddrive.sync.jobs.GitJobs
import com.diegonmarcos.cloudlib.gitsync.ManagedRepo
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * THE GIT TAB'S COUNTS ARE THE DISK'S. N repositories are registered (declared) but only M folders exist: cloned = M, the rest
 * are "not cloned"; deleting a folder drops the count at once; an empty git folder is 0 cloned. A clone matches its configured
 * repository by origin URL (any folder name) and, with no origin readable yet, by folder name, so a clone just made from the list
 * is never "not listed"; a truly unknown folder is. A clone-all over a plan where one clone fails still accounts for every repository.
 */
class CloneTruthTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(root: File, name: String) = ManagedRepo(id = name, name = name, path = File(root, name).absolutePath)
    private fun clone(root: File, name: String) { File(root, "$name/.git").mkdirs() }
    private val labels = { k: String -> if (k == "c_cloned") "cloned" else "not cloned" }
    private fun summary(total: Int, cloned: Int) = GitTopBarState.summary(listOf("cloned", "not_cloned"), total, cloned, 0, 0, 0, "", labels)

    @Test fun `declared N with M folders on disk counts M cloned, deleting one drops it, an empty folder is zero`() {
        val root = tmp.newFolder("git")
        val declared = (1..18).map { repo(root, "r$it") }
        (1..5).forEach { clone(root, "r$it") }
        assertEquals(5, CloneTruth.live(declared, CloneTruth::isCloneDir).size)
        assertEquals("5 cloned · 13 not cloned", summary(declared.size, CloneTruth.live(declared, CloneTruth::isCloneDir).size))
        File(root, "r3").deleteRecursively()
        assertEquals(4, CloneTruth.live(declared, CloneTruth::isCloneDir).size)
        (1..18).forEach { File(root, "r$it").deleteRecursively() }
        assertEquals(0, CloneTruth.live(declared, CloneTruth::isCloneDir).size)
        assertEquals("0 cloned · 18 not cloned", summary(18, 0))
    }

    @Test fun `a plain folder without git and a bare mirror are told apart`() {
        val root = tmp.newFolder("g2")
        File(root, "plain").mkdirs()
        File(root, "bare.git/objects").mkdirs(); File(root, "bare.git/HEAD").writeText("ref: refs/heads/main")
        assertEquals(listOf("bare.git"), listOf("plain", "bare.git").filter { CloneTruth.isCloneDir(File(root, it)) })
    }

    private val sot = Declarations.parseRepoSot("""{"repos":[
        {"name":"alpha","url":"git@github.com:o/alpha.git"},{"name":"beta","url":"git@github.com:o/beta.git"},{"name":"gamma","url":"git@github.com:o/gamma.git"}]}""")
    private fun classify(vararg e: Entry) = RepoInventory.classify(e.toList(), RegistryNames(sot), sot, emptySet())

    @Test fun `a configured repo cloned under another folder name is cloned, never not listed`() {
        val inv = classify(Entry("/s/git/my-alpha", Kind.TREE, "https://github.com/o/Alpha.git"))
        val row = inv.rows.first { it.path == "/s/git/my-alpha" }
        assertTrue(row.isClone && row.cls != RepoClass.UNLISTED)
        assertEquals(RepoClass.MISNAMED, row.cls)
        assertEquals(setOf("beta", "gamma"), inv.of(RepoClass.NOT_CLONED).map { it.name }.toSet())
    }

    @Test fun `a clone whose origin is not readable yet matches by folder name, an unknown folder is not listed`() {
        val inv = classify(Entry("/s/git/beta", Kind.TREE, ""), Entry("/s/git/stranger", Kind.TREE, "https://github.com/x/stranger.git"), Entry("/s/git/notes", Kind.TREE, ""))
        assertEquals(RepoClass.REGISTERED, inv.rows.first { it.path == "/s/git/beta" }.cls)
        assertEquals(setOf("/s/git/stranger", "/s/git/notes"), inv.of(RepoClass.UNLISTED).map { it.path }.toSet())
    }

    @Test fun `clone all accounts for every repository when one fails`() = runBlocking {
        val j = GitJobs(CoroutineScope(SupervisorJob() + Dispatchers.Default)) { System.currentTimeMillis() }
        val names = (1..6).map { "r$it" }
        val cloned = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        j.submit(GitJobs.Spec("clone_all", "clone_mode", "Clone all", names.map { GitJobs.Target("/s/git/$it", it) }, workers = 2) { t ->
            if (t.name == "r4") GitJobs.Outcome(GitJobs.Status.FAIL, "auth") else { cloned += t.name; GitJobs.Outcome(GitJobs.Status.OK, "cloned") }
        })!!.join()
        val s = j.jobs.value.single()
        assertEquals(names.filter { it != "r4" }.toSet(), cloned)
        assertEquals(names.toSet(), (s.ok.keys + s.failed.keys + s.skipped.keys))
        assertEquals(mapOf("r4" to "auth"), s.failed)
    }
}
