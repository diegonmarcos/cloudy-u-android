package com.diegonmarcos.clouddrive.sync.inventory

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.RegistryNames
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.Entry
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.Kind
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory.RepoClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 THE ROWS SEEN ON THE PHONE, classified once. Registered clones whose origin still names the pre-rename repository
 * (cloudy-u-linux, cloudy_db_LFS, Projects-___, cloud-me_data-pub) are REGISTERED with a stale origin, not Parentless or
 * Unlisted; a folder under a retired name (My-ai-memory) is MISNAMED; the index repo (`self`) is listed, and its three
 * clones are ONE primary plus two DUPLICATEs; `<name>.incoming` is DEBRIS; a clone the pass found through cld.termux is
 * REGISTERED even though java.io saw nothing. Mutations proven red: dropping the `self` entry (indexRepoIsListed),
 * grouping by folder name instead of identity (threeClonesOfTheIndexRepo), reading the origin's segment raw (staleOrigins).
 */
class RepoInventoryPhoneTest {
    private val sot = Declarations.parseRepoSot("""{"self":{"name":"_git-repos-master","url":"git@github.com:diegonmarcos/_git-repos-master.git","renamed_from":["git-repos-master"]},"repos":[
        {"name":"cloudy-u-linux","url":"git@github.com:diegonmarcos/cloudy-u-linux.git","renamed_from":["cloud-u-linux"]},
        {"name":"cloudy_db_LFS","url":"git@github.com:diegonmarcos/cloudy_db_LFS.git","private":true,"renamed_from":["cloud-data-lfs"],"lfs":true},
        {"name":"Projects-___","url":"git@github.com:diegonmarcos/Projects-___.git","renamed_from":["front"]},
        {"name":"cloud-me_data-pub","url":"git@github.com:diegonmarcos/cloud-me_data-pub.git","renamed_from":["z-archived_cloud-me-pub-data"]},
        {"name":"z-archived_My-ai-memory","url":"git@github.com:diegonmarcos/z-archived_My-ai-memory.git","renamed_from":["My-ai-memory"]},
        {"name":"cloudy-u-android","url":"git@github.com:diegonmarcos/cloudy-u-android.git"},
        {"name":"never-cloned","url":"git@github.com:diegonmarcos/never-cloned.git"},
        {"name":"not-yet","url":"git@github.com:diegonmarcos/not-yet.git","status":"pending_creation"}]}""")
    private val names = RegistryNames(sot)
    private val git = "/data/user/0/cld.termux/files/home/cloud-shared-store/git"

    private fun tree(name: String, origin: String, head: Long = 0) = Entry("$git/$name", Kind.TREE, origin, "main", "origin/main", head)

    private val phone = listOf(
        tree("cloudy-u-linux", "https://github.com/diegonmarcos/cloud-u-linux.git"),
        tree("cloudy_db_LFS", "git@github.com:diegonmarcos/cloud-data-lfs.git"),
        tree("Projects-___", "ssh://git@github.com/diegonmarcos/front"),
        tree("cloud-me_data-pub", "https://github.com/diegonmarcos/z-archived_cloud-me-pub-data.git"),
        tree("My-ai-memory", "https://github.com/diegonmarcos/My-ai-memory.git"),
        tree("git-repos-master", "https://github.com/diegonmarcos/git-repos-master.git", 100),
        tree("_git-repos-master", "https://github.com/diegonmarcos/_git-repos-master.git", 200),
        tree("_git-repos-master___CONFLICT", "https://github.com/diegonmarcos/_git-repos-master.git", 300),
        tree("cloudy-u-android", "https://github.com/diegonmarcos/cloudy-u-android.git"),
        Entry("$git/cloudy-a-docs.incoming", Kind.DEBRIS),
        tree("notes", ""),
        tree("someone-else", "https://github.com/other/thing.git"),
        Entry("$git/_bare/diegonmarcos/cloudy-u-android.git", Kind.TREE, "https://github.com/diegonmarcos/cloudy-u-android.git", bare = true),
        Entry("$git/wt", Kind.GITFILE),
    )
    private val managed = setOf("$git/cloudy-u-linux", "$git/_git-repos-master", "$git/cloudy-u-android")
    private val inv = RepoInventory.classify(phone, names, sot, managed, now = 1L, via = "exec")

    private fun row(name: String) = inv.rows.first { it.name == name && it.cls != RepoClass.NOT_CLONED }

    @Test fun `registered clones with a stale origin are registered, never parentless or unlisted`() {
        for (n in listOf("cloudy-u-linux", "cloudy_db_LFS", "Projects-___", "cloud-me_data-pub")) {
            val r = row(n)
            assertEquals(n, RepoClass.STALE_ORIGIN, r.cls)
            assertTrue("$n origin is old", r.staleOrigin)
            assertEquals(names.entryOf(n)!!.github.lowercase(), r.identity)
            assertEquals(n, r.registryName)
        }
    }

    @Test fun `a folder under a retired name is misnamed and renames to the current name`() {
        val r = row("My-ai-memory")
        assertEquals(RepoClass.MISNAMED, r.cls)
        assertEquals("z-archived_My-ai-memory", r.registryName)
        assertEquals("z-archived_my-ai-memory", r.identity)
    }

    @Test fun `the index repo is listed through self and its three clones are one primary and two duplicates`() {
        val rows = inv.rows.filter { it.identity == "_git-repos-master" && it.cls != RepoClass.NOT_CLONED }
        assertEquals(3, rows.size)
        val primary = rows.single { it.cls != RepoClass.DUPLICATE }
        assertEquals("the managed clone is the primary", "$git/_git-repos-master", primary.path)
        assertEquals(RepoClass.REGISTERED, primary.cls)
        assertEquals(3, primary.copies)
        val dups = rows.filter { it.cls == RepoClass.DUPLICATE }
        assertEquals(setOf("git-repos-master", "_git-repos-master___CONFLICT"), dups.map { it.name }.toSet())
        assertTrue(dups.all { it.primaryPath == primary.path })
        assertTrue("the old-name duplicate also carries a stale origin", dups.first { it.name == "git-repos-master" }.staleOrigin)
        assertNull("never a NOT_CLONED row for a repository that has a clone", inv.rows.firstOrNull { it.identity == "_git-repos-master" && it.cls == RepoClass.NOT_CLONED })
    }

    @Test fun `a clone the pass found through cld termux is registered even though java io sees nothing`() {
        val r = row("cloudy-u-android")
        assertEquals(RepoClass.REGISTERED, r.cls)
        assertTrue(r.managed)
        assertEquals("exec", inv.via)
        assertEquals(r, inv.cloneOf("cloudy-u-android"))
    }

    @Test fun `debris, unlisted and a git file are what they are, and a bare repo is a repo folder of the one list`() {
        assertEquals(RepoClass.DEBRIS, row("cloudy-a-docs.incoming").cls)
        assertEquals(RepoClass.UNLISTED, row("notes").cls)
        assertEquals(RepoClass.UNLISTED, row("someone-else").cls)
        assertEquals("thing", row("someone-else").identity)
        val bare = row("cloudy-u-android.git")
        // #1491 no bare class: the bare copy of a repository another folder holds is a DUPLICATE row of the same list, its bare state an attribute
        assertEquals(RepoClass.DUPLICATE, bare.cls); assertTrue(bare.bare)
        assertEquals("cloudy-u-android", bare.identity)
        assertNull("a .git file is not a row of its own", inv.rows.firstOrNull { it.name == "wt" })
    }

    @Test fun `every registry repository no folder holds is one NOT_CLONED row, pending ones never`() {
        assertEquals(listOf("never-cloned"), inv.of(RepoClass.NOT_CLONED).map { it.name })
        assertNull(inv.cloneOf("never-cloned"))
    }

    @Test fun `the main table has one row per registry repository and the counts add up`() {
        assertEquals(setOf("cloudy-u-linux", "cloudy_db_LFS", "Projects-___", "cloud-me_data-pub", "My-ai-memory", "_git-repos-master", "cloudy-u-android", "never-cloned"), inv.main.map { it.name }.toSet())
        assertEquals(mapOf(RepoClass.STALE_ORIGIN to 4, RepoClass.MISNAMED to 1, RepoClass.REGISTERED to 2, RepoClass.DUPLICATE to 3, RepoClass.DEBRIS to 1, RepoClass.UNLISTED to 2, RepoClass.NOT_CLONED to 1), inv.counts)
    }

    @Test fun `without the primary being managed the folder carrying the registry name wins, else the newest`() {
        val two = listOf(tree("git-repos-master", "https://github.com/diegonmarcos/git-repos-master.git", 900), tree("_git-repos-master", "https://github.com/diegonmarcos/_git-repos-master.git", 100))
        val byName = RepoInventory.classify(two, names, sot, emptySet()).rows.filter { it.cls != RepoClass.NOT_CLONED }
        assertEquals("_git-repos-master", byName.single { it.cls != RepoClass.DUPLICATE }.name)
        val twoOld = listOf(tree("x1", "https://github.com/diegonmarcos/cloudy-u-android.git", 100), tree("x2", "https://github.com/diegonmarcos/cloudy-u-android.git", 900))
        assertEquals("x2", RepoInventory.classify(twoOld, names, sot, emptySet()).rows.filter { it.identity == "cloudy-u-android" }.single { it.cls != RepoClass.DUPLICATE }.name)
    }

    @Test fun `the one identity folds every retired name, the self entry and a URL`() {
        assertEquals("cloudy-u-linux", names.identity("https://github.com/diegonmarcos/cloud-u-linux.git"))
        assertEquals("cloudy-u-linux", names.identity("Cloud-U-Linux"))
        assertEquals("_git-repos-master", names.identity("git@github.com:diegonmarcos/git-repos-master.git"))
        assertEquals("unknown-thing", names.identity("unknown-thing"))
        assertEquals("", names.identity(""))
    }
}
