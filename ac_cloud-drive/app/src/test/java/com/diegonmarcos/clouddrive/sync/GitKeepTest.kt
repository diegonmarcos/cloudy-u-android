package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1315 CHARACTERISATION of the Git layer's behaviour the reorganisation KEEPS, pinned before the first refactor commit so a
 * later milestone that changes one of these facts by accident fails here, with the rule named. Each fact is one the owner
 * already relied on (#1288 conflict-safe rename, #1282/#1314 one bare mirror, #1254 keep-depth gate, #850 status precedence,
 * #1256 persisted filters, #1317 origins through the registry, #608 declared ops with a handler).
 */
class GitKeepTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }
    private val canonical: (String) -> String = { it }

    @Test fun `rename never overwrites, a taken name becomes CONFLICT then CONFLICT2`() {
        assertEquals("x", FolderActions.renameTarget("x") { false })
        assertEquals("x___CONFLICT", FolderActions.renameTarget("x") { it == "x" })
        assertEquals("x___CONFLICT2", FolderActions.renameTarget("x") { it == "x" || it == "x___CONFLICT" })
        assertNull("every name taken refuses", FolderActions.renameTarget("x") { true })
    }

    @Test fun `archive keeps the name when free, else stamps it`() {
        assertEquals("x", FolderActions.archiveName("x", 0L) { false })
        val stamped = FolderActions.archiveName("x", 0L) { it == "x" }
        assertTrue(stamped != null && stamped.startsWith("x__") && stamped.length > 3)
    }

    @Test fun `delete asks for the typed name whenever local work could be lost`() {
        assertTrue(FolderActions.Guard(listOf(" M a.txt"), 0).strict)
        assertTrue(FolderActions.Guard(emptyList(), 2).strict)
        assertTrue("no upstream = cannot rule it out", FolderActions.Guard(emptyList(), null).strict)
        assertTrue(FolderActions.Guard(emptyList(), 0, "unreadable").strict)
        assertFalse(FolderActions.Guard(emptyList(), 0).strict)
        assertTrue(FolderActions.typedOk(" repo ", "repo"))
        assertFalse(FolderActions.typedOk("Repo", "repo"))
    }

    @Test fun `one bare mirror per repository, an existing one is updated`() {
        val plan = BareClones.plan(
            listOf(BareClones.Target("a", "https://github.com/o/a.git"), BareClones.Target("b", "https://github.com/o/b.git")),
            listOf(BareClones.Mirror("/root/s/_bare/o/a.git", "https://github.com/o/a.git")), canonical,
        )
        assertEquals(listOf("b"), plan.toClone.map { it.name })
        assertEquals(mapOf("a" to "/root/s/_bare/o/a.git"), plan.toUpdate)
    }

    @Test fun `clone missing never doubles a clone that exists under another folder name`() {
        val plan = BulkDepth.planClones(
            listOf(BulkDepth.Target("a", "https://github.com/o/a.git"), BulkDepth.Target("b", "https://github.com/o/b.git")),
            listOf(BulkDepth.Existing("/s/git/old-a", "https://github.com/o/a.git")), canonical,
        )
        assertEquals(listOf("b"), plan.toClone.map { it.name })
        assertEquals("/s/git/old-a", plan.held["a"])
    }

    @Test fun `keep depth shrinks only a clean, pushed clone with an upstream`() {
        assertNull(BulkDepth.keepSkip(true, 0, 0, 0, "origin/main", "SAFE"))
        assertEquals(BulkDepth.SKIP_NOT_CLONED, BulkDepth.keepSkip(false, 0, 0, 0, "origin/main", "SAFE"))
        for (bad in listOf(
            BulkDepth.keepSkip(true, 1, 0, 0, "origin/main", "SAFE"), BulkDepth.keepSkip(true, 0, 1, 0, "origin/main", "SAFE"),
            BulkDepth.keepSkip(true, 0, 0, 1, "origin/main", "SAFE"), BulkDepth.keepSkip(true, 0, 0, 0, null, "SAFE"),
            BulkDepth.keepSkip(true, 0, 0, 0, "origin/main", "MERGING"),
        )) assertTrue("skipped with a reason", !bad.isNullOrBlank())
    }

    @Test fun `status precedence is dirty over behind over ahead over clean`() {
        fun st(ahead: Int, behind: Int, changed: Int) = GitStatusReader.classify(GitStatusReader.Probe("main", "origin/main", ahead, behind, changed, 0), 1L).state
        assertEquals(GitStatusReader.State.DIRTY, st(1, 1, 1))
        assertEquals(GitStatusReader.State.BEHIND, st(1, 1, 0))
        assertEquals(GitStatusReader.State.AHEAD, st(1, 0, 0))
        assertEquals(GitStatusReader.State.CLEAN, st(0, 0, 0))
        assertEquals(RepoStatus.Kind.CONFLICT, RepoStatus.kindOf(true, GitSyncCoordinator.Glance(branch = "main", upstream = "origin/main", changed = 2, conflicts = 1, read = true)))
        assertEquals(RepoStatus.Kind.DIVERGED, RepoStatus.kindOf(true, GitSyncCoordinator.Glance(branch = "main", upstream = "origin/main", ahead = 1, behind = 1, read = true)))
        assertEquals(RepoStatus.Kind.NOT_CLONED, RepoStatus.kindOf(false, null))
    }

    @Test fun `filters and sort persist as one string each`() {
        val sel = mapOf("cloned" to "cloned", "status" to "dirty")
        assertEquals(sel, RepoFilters.decode(RepoFilters.encode(sel)))
        assertEquals(emptyMap<String, String>(), RepoFilters.decode(RepoFilters.encode(mapOf("cloned" to RepoFilters.ALL))))
        assertEquals(RepoColumn.LAST_COMMIT to false, RepoFilters.decodeSort(RepoFilters.encodeSort(RepoColumn.LAST_COMMIT, false)))
        assertEquals(RepoTable.DEFAULT_SORT to true, RepoFilters.decodeSort("nope:asc"))
    }

    @Test fun `an origin naming an old repository name resolves to the current one and is fixable`() {
        val sot = Declarations.parseRepoSot("""{"repos":[{"name":"cloudy-u-linux","url":"git@github.com:diegonmarcos/cloudy-u-linux.git","renamed_from":["cloud-u-linux"]}]}""")
        val names = RegistryNames(sot)
        assertEquals("cloud-u-linux", LocalRepos.repoNameOf("https://github.com/diegonmarcos/cloud-u-linux.git"))
        assertEquals("cloudy-u-linux", names.currentName("cloud-u-linux"))
        assertEquals("https://github.com/diegonmarcos/cloudy-u-linux.git", FolderActions.fixedOrigin("https://github.com/diegonmarcos/cloud-u-linux.git", names))
        assertNull(FolderActions.fixedOrigin("https://github.com/diegonmarcos/cloudy-u-linux.git", names))
        assertFalse(LocalRepos.parentless(listOf(LocalRepos.found(File("/s/git/cloudy-u-linux"), "https://github.com/diegonmarcos/cloud-u-linux.git", names)), names).isNotEmpty())
    }

    @Test fun `every declared op has a handler on the page`() {
        val ops = Json { ignoreUnknownKeys = true }.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]!!.jsonObject["ops"]!!.jsonArray
            .map { it.jsonObject["id"]!!.jsonPrimitive.content }
        val page = File(root, "app/src/main/java/com/diegonmarcos/clouddrive/sync/GitReposScreen.kt").readText()
        assertTrue(ops.size >= 14)
        ops.forEach { id -> assertTrue("op $id has a dispatch branch", page.contains("\"$id\" ->")) }
    }
}
