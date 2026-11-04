package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import com.diegonmarcos.clouddrive.Declarations
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1313 stale temp pack cleanup: what goes, what stays, when the pass is skipped, the bytes, the declaration. */
class StalePackCleanerTest {
    private lateinit var git: File
    private val now = System.currentTimeMillis()
    private val hour = 3_600_000L
    private val patterns = StalePackCleaner.DEFAULT_PATTERNS
    private val root = File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }

    @Before fun setUp() { git = Files.createTempDirectory("git1313").toFile() }
    @After fun tearDown() { git.deleteRecursively() }

    private fun pack(clone: String): File = File(git, "$clone/.git/objects/pack").apply { mkdirs() }
    private fun bare(owner: String, repo: String): File = File(git, "_bare/$owner/$repo.git/objects/pack").apply { mkdirs() }
    private fun file(dir: File, name: String, bytes: Int, ageHours: Long): File =
        File(dir, name).apply { writeBytes(ByteArray(bytes)); setLastModified(now - ageHours * hour) }
    private fun sweep(minAge: Int = 6, running: Boolean = false) =
        StalePackCleaner.sweep(StalePackCleaner.packDirs(git, "_bare"), patterns, minAge, now, running)

    @Test fun `old matching files go from working clones and bare mirrors, the bytes are summed`() {
        val w = pack("repo-a"); val b = bare("o", "repo-b")
        val a1 = file(w, "incoming_123456.pack", 1000, 48); val a2 = file(w, "incoming_123456.idx", 200, 48)
        val b1 = file(b, "tmp_pack_aB3xYz", 5000, 10); val b2 = file(b, "tmp_idx_aB3xYz", 30, 10); val b3 = file(b, "tmp_rev_Q1", 7, 10)
        val r = sweep()
        assertEquals(5, r.deleted); assertEquals(6237L, r.bytes)
        assertTrue(listOf(a1, a2, b1, b2, b3).none { it.exists() })
        assertEquals("Cleaned 5 stale pack files (6.1 KB)", StalePackCleaner.message(r))
    }

    @Test fun `fresh files, real packs, other names and symlinks stay`() {
        val w = pack("repo-a")
        val fresh = file(w, "incoming_9.pack", 100, 1)
        val borderline = file(w, "tmp_pack_x", 100, 5)
        val real = listOf("pack-0123abcd.pack", "pack-0123abcd.idx", "pack-0123abcd.rev", "pack-0123abcd.keep", "pack-0123abcd.promisor").map { file(w, it, 100, 999) }
        val other = listOf("multi-pack-index", "commit-graph", "incoming.txt", "my_tmp_pack_1", "incoming_1.pack.bak").map { file(w, it, 100, 999) }
        val target = file(git, "outside.bin", 100, 999)
        val link = File(w, "incoming_777.pack"); Files.createSymbolicLink(link.toPath(), target.toPath())
        val r = sweep()
        assertEquals(0, r.deleted); assertEquals(0L, r.bytes); assertEquals("", StalePackCleaner.message(r))
        assertTrue((listOf(fresh, borderline, target) + real + other).all { it.exists() })
        assertTrue("the symlink and its target are untouched", Files.isSymbolicLink(link.toPath()) && target.exists())
    }

    @Test fun `pack-star is never a candidate even if a pattern would match it`() {
        val w = pack("repo-a")
        val real = file(w, "pack-deadbeef.pack", 100, 999)
        val r = StalePackCleaner.sweep(listOf(w), listOf("pack-*", "*"), 6, now, false)
        assertEquals(0, r.deleted); assertTrue(real.exists())
        assertFalse(StalePackCleaner.safePattern("pack-*")); assertFalse(StalePackCleaner.safePattern("*.pack")); assertFalse(StalePackCleaner.safePattern("../x"))
    }

    @Test fun `only objects pack is swept, nothing elsewhere in a clone`() {
        val w = pack("repo-a")
        val elsewhere = file(File(git, "repo-a"), "incoming_1.pack", 100, 999)
        val inGit = file(File(git, "repo-a/.git"), "tmp_pack_1", 100, 999)
        val deep = file(File(git, "repo-a/.git/objects/pack").also { File(it, "sub").mkdirs() }, "sub/tmp_pack_2", 100, 999)
        assertEquals(0, sweep().deleted)
        assertTrue(elsewhere.exists() && inGit.exists() && deep.exists() && w.isDirectory)
    }

    @Test fun `the whole pass is skipped while a git job runs`() {
        val f = file(pack("repo-a"), "incoming_1.pack", 100, 99)
        val r = sweep(running = true)
        assertEquals(0, r.deleted); assertTrue(r.skipped.isNotEmpty()); assertTrue(f.exists())
        assertTrue(StalePackCleaner.busy(1, 0, false, false)); assertTrue(StalePackCleaner.busy(0, 1, false, false))
        assertTrue(StalePackCleaner.busy(0, 0, true, false)); assertTrue(StalePackCleaner.busy(0, 0, false, true))
        assertFalse(StalePackCleaner.busy(0, 0, false, false))
        assertEquals(1, sweep().deleted)
    }

    @Test fun `the age gate is the declared hours`() {
        val f = file(pack("repo-a"), "incoming_1.pack", 100, 7)
        assertEquals(0, sweep(minAge = 8).deleted); assertTrue(f.exists())
        assertEquals(1, sweep(minAge = 6).deleted); assertFalse(f.exists())
    }

    @Test fun `human sizes`() {
        assertEquals("1.2 GB", StalePackCleaner.humanBytes((1.25 * (1L shl 30)).toLong() - 1))
        assertEquals("462.0 MB", StalePackCleaner.humanBytes(462L shl 20)); assertEquals("12 B", StalePackCleaner.humanBytes(12))
        assertEquals("Cleaned 1 stale pack file (1.0 KB)", StalePackCleaner.message(StalePackCleaner.Result(1, 1024)))
    }

    @Test fun `the cld termux script does the same on a real tree`() {
        org.junit.Assume.assumeTrue(File("/bin/sh").canExecute() && ProcessBuilder("find", "--version").start().waitFor() == 0)
        val w = pack("repo-a"); val b = bare("o", "r")
        val gone = listOf(file(w, "incoming_1.pack", 1000, 48), file(b, "tmp_pack_Ab", 24, 48))
        val stay = listOf(file(w, "pack-1.pack", 10, 999), file(w, "tmp_pack_new", 10, 1), file(b, "pack-2.idx", 10, 999), file(w, "other", 10, 999))
        val target = file(git, "t.bin", 10, 999); Files.createSymbolicLink(File(w, "incoming_9.idx").toPath(), target.toPath())
        val sh = StalePackCleaner.script(git.absolutePath, "_bare", patterns, 6) { "'$it'" }
        val p = ProcessBuilder("sh", "-c", sh).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText(); assertEquals(out, 0, p.waitFor())
        assertEquals(StalePackCleaner.Result(2, 1024), StalePackCleaner.parse(out))
        assertTrue(gone.none { it.exists() }); assertTrue(stay.all { it.exists() } && target.exists() && Files.isSymbolicLink(File(w, "incoming_9.idx").toPath()))
        assertTrue("a script without a safe pattern does nothing", StalePackCleaner.script("/g", "_bare", listOf("pack-*", "*"), 6) { "'$it'" } == "true")
    }

    @Test fun `declaration - build json block, safe defaults and clamps`() {
        val g = Json.parseToJsonElement(File(root, "build.json").readText()).jsonObject["ui"]!!.jsonObject["sync"]!!.jsonObject["git"]
        val d = Declarations.parseGitPage(g).stalePackCleanup
        assertTrue(d.enabled); assertEquals(6, d.minAgeHours)
        assertEquals(listOf("incoming_*.pack", "incoming_*.idx", "tmp_pack_*", "tmp_idx_*", "tmp_rev_*"), d.patterns)
        assertTrue(g!!.jsonObject["_doc_stale_pack_cleanup"].toString().length > 100)
        fun parse(block: String) = Declarations.parseGitPage(Json.parseToJsonElement("""{"stale_pack_cleanup": $block}""")).stalePackCleanup
        assertEquals(Declarations.GitStalePackDecl(), Declarations.parseGitPage(Json.parseToJsonElement("{}")).stalePackCleanup)
        assertEquals(1, parse("""{"min_age_hours": 0}""").minAgeHours); assertEquals(24 * 90, parse("""{"min_age_hours": 999999}""").minAgeHours)
        assertEquals("unsafe patterns are dropped, none left = defaults", StalePackCleaner.DEFAULT_PATTERNS, parse("""{"patterns": ["pack-*", "*", "../x", "a/b"]}""").patterns)
        assertEquals(listOf("incoming_*"), parse("""{"patterns": ["incoming_*", "pack-*"]}""").patterns)
        assertFalse(parse("""{"enabled": false}""").enabled)
    }
}
