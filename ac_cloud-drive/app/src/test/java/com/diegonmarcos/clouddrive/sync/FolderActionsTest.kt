package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1288 the per-folder commands: conflict naming, archive path and suffix, the Delete guard, the declaration, header parity. */
class FolderActionsTest {
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    // ── 1) rename: <name>___CONFLICT, ___CONFLICT2, ___CONFLICT3 ... ──

    @Test fun `a free repository name is taken as is`() {
        assertEquals("cloud-notes", FolderActions.renameTarget("cloud-notes") { false })
    }

    @Test fun `a taken name counts conflicts up 1, 2, 3`() {
        val taken = mutableSetOf("cloud-notes")
        assertEquals("cloud-notes___CONFLICT", FolderActions.renameTarget("cloud-notes") { it in taken })
        taken += "cloud-notes___CONFLICT"
        assertEquals("cloud-notes___CONFLICT2", FolderActions.renameTarget("cloud-notes") { it in taken })
        taken += "cloud-notes___CONFLICT2"
        assertEquals("cloud-notes___CONFLICT3", FolderActions.renameTarget("cloud-notes") { it in taken })
    }

    @Test fun `the conflict search is bounded and then refuses`() {
        assertNull(FolderActions.conflictName("x") { true })
        assertNull(FolderActions.renameTarget("x") { true })
    }

    // ── 3) archive: <store git>/<archive_root>/, suffix on a taken name ──

    @Test fun `archive dir is the declared root under the git folder and refuses unsafe roots`() {
        assertEquals(File("/s/git/z-archived"), FolderActions.archiveDir(File("/s/git"), "z-archived"))
        assertNull(FolderActions.archiveDir(File("/s/git"), "../out"))
        assertNull(FolderActions.archiveDir(File("/s/git"), "/abs"))
        assertNull(FolderActions.archiveDir(File("/s/git"), ""))
    }

    @Test fun `archive keeps the name when free and suffixes a UTC stamp then a counter`() {
        val now = 1_761_696_000_000L // 2025-10-29T00:00:00Z
        assertEquals("repo", FolderActions.archiveName("repo", now) { false })
        val taken = mutableSetOf("repo")
        assertEquals("repo__20251029-000000", FolderActions.archiveName("repo", now) { it in taken })
        taken += "repo__20251029-000000"
        assertEquals("repo__20251029-000000-2", FolderActions.archiveName("repo", now) { it in taken })
        assertNull(FolderActions.archiveName("repo", now) { true })
    }

    @Test fun `a folder inside the archive is recognised, a look-alike sibling is not`() {
        val a = File("/s/git/z-archived")
        assertTrue(FolderActions.insideArchive(File("/s/git/z-archived/repo"), a))
        assertFalse(FolderActions.insideArchive(File("/s/git/z-archived-old/repo"), a))
        assertFalse(FolderActions.insideArchive(File("/s/git/repo"), a))
    }

    @Test fun `the move script refuses an existing destination and never globs`() {
        val q: (String) -> String = { "'$it'" }
        val s = FolderActions.moveScript("/a/x", "/a/z-archived/x", q)
        assertTrue(s.contains("[ ! -e '/a/z-archived/x' ] || exit 17"))
        assertTrue(s.endsWith("mv -- '/a/x' '/a/z-archived/x'"))
        assertFalse(s.contains("*"))
        val d = FolderActions.deleteScript("/a/x", q)
        assertTrue(d.contains("exit 18") && d.endsWith("rm -rf -- '/a/x'") && !d.contains("*"))
    }

    @Test fun `the move script really moves and refuses to merge, run in sh`() {
        val sh = File("/bin/sh"); if (!sh.canExecute()) return
        val t = Files.createTempDirectory("fa").toFile()
        File(t, "src/.git").mkdirs(); File(t, "src/f").writeText("1")
        File(t, "dst").mkdirs(); File(t, "dst/keep").writeText("2")
        val q: (String) -> String = { "'" + it.replace("'", "'\\''") + "'" }
        val refused = ProcessBuilder("sh", "-c", FolderActions.moveScript(File(t, "src").path, File(t, "dst").path, q)).start().waitFor()
        assertEquals(17, refused)
        assertEquals(listOf("keep"), File(t, "dst").list()!!.toList())
        val ok = ProcessBuilder("sh", "-c", FolderActions.moveScript(File(t, "src").path, File(t, "z/src").path, q)).start().waitFor()
        assertEquals(0, ok)
        assertEquals("1", File(t, "z/src/f").readText())
        t.deleteRecursively()
    }

    // ── 3) delete guard ──

    @Test fun `a clean, fully pushed clone needs only the normal confirm`() {
        val g = FolderActions.Guard(emptyList(), 0)
        assertFalse(g.strict); assertTrue(g.reasons().isEmpty())
    }

    @Test fun `uncommitted, unpushed, no upstream or unreadable each need the typed confirm`() {
        assertTrue(FolderActions.Guard(listOf("a.txt"), 0).strict)
        assertTrue(FolderActions.Guard(emptyList(), 2).strict)
        assertTrue(FolderActions.Guard(emptyList(), null).strict)
        assertTrue(FolderActions.Guard(emptyList(), 0, "boom").strict)
        assertEquals(listOf("1 uncommitted change(s)", "3 unpushed commit(s)"), FolderActions.Guard(listOf("x"), 3).reasons())
    }

    @Test fun `the typed confirm passes only on the exact folder name`() {
        assertTrue(FolderActions.typedOk(" my-repo ", "my-repo"))
        assertFalse(FolderActions.typedOk("my-rep", "my-repo"))
        assertFalse(FolderActions.typedOk("MY-REPO", "my-repo"))
        assertFalse(FolderActions.typedOk("", ""))
    }

    @Test fun `the guard parses the cld termux answer`() {
        val g = FolderActions.parseGuard(" M a.txt\n?? b\n${FolderActions.SEP}\n4\n")
        assertEquals(listOf(" M a.txt", "?? b"), g.changed); assertEquals(4, g.unpushed)
        assertNull(FolderActions.parseGuard("${FolderActions.SEP}\n").unpushed)
        assertTrue(FolderActions.parseGuard("garbage").strict)
    }

    // ── declared kinds ──

    @Test fun `build json declares the three folder kinds and a safe archive root`() {
        val page = Declarations.parseGitPage(Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"])
        assertEquals(Declarations.FOLDER_KINDS, page.folderActions.map { it.kind }.toSet())
        assertTrue(page.folderActions.all { it.confirm && it.workers in 1..4 })
        assertEquals("z-archived", page.archiveRoot)
        assertEquals(Declarations.DEPTH_SCOPE_FOLDER, page.folderActions.first().asRun().scope)
    }

    // ── 2) table header parity ──

    @Test fun `the folder tables draw exactly the main table's columns in its order`() {
        assertEquals(RepoTable.COLUMNS, FolderTable.COLUMNS)
        assertEquals(RepoColumn.entries.toList(), FolderTable.COLUMNS)
    }

    @Test fun `a folder row prints the dash for facts a bare folder has not got`() {
        val g = GitSyncCoordinator.Glance(branch = "main", upstream = "origin/main", ahead = 1, behind = 2, changed = 0, read = true, level = "full")
        val r = FolderTable.rowOf(FolderActions.Target("/s/git/x", "https://github.com/o/y.git", "y"), g, "GitHub", 5L)
        assertEquals("x", r.name)
        assertNull(r.isPrivate); assertNull(r.lfs); assertNull(r.sizeMainBytes); assertNull(r.sizeTotalBytes); assertNull(r.branches)
        assertEquals(RepoTable.DASH, RepoTable.visibility(r.isPrivate, "Pvt", "Pub"))
        assertEquals(1, r.ahead); assertEquals(2, r.behind); assertEquals(0, r.dirtyFiles); assertEquals(5L, r.lastCommitMs)
        assertEquals("GitHub", r.provider)
    }
}
