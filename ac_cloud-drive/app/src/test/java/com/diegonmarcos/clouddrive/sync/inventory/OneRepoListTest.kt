package com.diegonmarcos.clouddrive.sync.inventory

import com.diegonmarcos.clouddrive.Declarations
import com.diegonmarcos.clouddrive.sync.RegistryNames
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.Entry
import com.diegonmarcos.clouddrive.sync.inventory.InventoryScript.Kind
import com.diegonmarcos.clouddrive.sync.inventory.RepoInventory.RepoClass
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1491 ONE list of repo folders: a working tree and a bare `<repo>.git` folder sit in the SAME list and the SAME classes; there is no
 * bare-only class, table or section, and bare is an attribute of the row. Mutation: re-adding a BARE class, or a "bare" table in
 * GitInventoryUi, turns the last two tests red.
 */
class OneRepoListTest {
    private val sot = Declarations.parseRepoSot("""{"repos":[
        {"name":"alpha","url":"git@github.com:diegonmarcos/alpha.git"},
        {"name":"beta","url":"git@github.com:diegonmarcos/beta.git"}]}""")
    private val names = RegistryNames(sot)
    private val git = "/store/git"

    @Test fun `one list holds a working tree and a bare repo, both registered rows of the main table`() {
        val entries = listOf(
            Entry("$git/alpha", Kind.TREE, "https://github.com/diegonmarcos/alpha.git", "main", "origin/main"),
            Entry("$git/beta.git", Kind.TREE, "https://github.com/diegonmarcos/beta.git", bare = true),
        )
        val inv = RepoInventory.classify(entries, names, sot, emptySet())
        assertEquals(listOf("alpha", "beta.git"), inv.main.map { it.name })
        assertEquals(setOf(RepoClass.REGISTERED), inv.main.map { it.cls }.toSet())
        assertEquals(listOf(false, true), inv.main.map { it.bare })
        assertEquals("the bare repo is a clone like the other", listOf(true, true), inv.main.map { it.isClone })
        assertEquals(mapOf(RepoClass.REGISTERED to 2), inv.counts)
        assertEquals("beta.git", inv.cloneOf("beta")?.name)
    }

    @Test fun `a bare repo carrying the repository name is not misnamed, a wrong name is`() {
        val ok = RepoInventory.classify(listOf(Entry("$git/beta.git", Kind.TREE, "https://github.com/diegonmarcos/beta.git", bare = true)), names, sot, emptySet())
        assertEquals(RepoClass.REGISTERED, ok.rows.single { it.bare }.cls)
        val wrong = RepoInventory.classify(listOf(Entry("$git/zzz.git", Kind.TREE, "https://github.com/diegonmarcos/beta.git", bare = true)), names, sot, emptySet())
        assertEquals(RepoClass.MISNAMED, wrong.rows.single { it.bare }.cls)
    }

    @Test fun `the java io walk lists the working tree and the bare repo in one list`() {
        val root = Files.createTempDirectory("one-list").toFile()
        try {
            File(root, "alpha/.git").mkdirs()
            File(root, "beta.git/objects").mkdirs(); File(root, "beta.git/HEAD").writeText("ref: refs/heads/main\n")
            val found = InventoryScript.walkIo(listOf(root), 2, 100, Long.MAX_VALUE, emptyList())
            assertEquals(setOf("alpha", "beta.git"), found.map { it.name }.toSet())
            assertTrue(found.all { it.kind == Kind.TREE })
            assertEquals(mapOf("alpha" to false, "beta.git" to true), found.associate { it.name to it.bare })
        } finally { root.deleteRecursively() }
    }

    @Test fun `there is no bare class and no bare-only section`() {
        assertEquals(emptyList<String>(), RepoClass.values().map { it.name }.filter { it.contains("BARE") })
        assertEquals(emptyList<String>(), Kind.values().map { it.name }.filter { it.contains("BARE") })
        val ui = File("src/main/java/com/diegonmarcos/clouddrive/sync/GitInventoryUi.kt").readText()
        assertFalse("GitInventoryUi draws no bare table", Regex("""tableId\s*=\s*"bare"|RepoClass\.BARE""").containsMatchIn(ui))
        assertEquals(setOf("duplicates", "debris"), Regex("""tableId\s*=\s*"(\w+)"""").findAll(ui).map { it.groupValues[1] }.toSet())
    }
}
