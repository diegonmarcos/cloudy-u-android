package com.diegonmarcos.cloudlib.gitrepos

import com.diegonmarcos.cloudlib.gitrepos.AutoCommit.FileStat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.eclipse.jgit.dircache.DirCacheIterator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1419 Auto Commit: the message rule, its 4 KB cut, the folder grouping and counts, the all-repos filter, the writer fallback. */
class AutoCommitTest {
    private val three = listOf(
        FileStat("a/x.kt", 10, 2, 2048), FileStat("a/y.kt", 5, 0, 1024),
        FileStat("b/c/z.md", 1, 7, 512), FileStat("root.txt", 3, 3, 100),
    )

    @Test fun subjectCountsFilesLinesSizeAndFolders() {
        assertEquals("auto: 4 files (+19 −12, 3.6 KB) in 3 folders", AutoCommit.subject(three))
        assertEquals("auto: 1 file (+1 −0, 10 B) in 1 folder", AutoCommit.subject(listOf(FileStat("f", 1, 0, 10))))
        assertEquals("1.2 MB", AutoCommit.size(1_258_291))
    }

    @Test fun foldersGroupByFullPathWithTheirOwnCounts() {
        val f = AutoCommit.folders(three)
        assertEquals(listOf("/", "a", "b/c"), f.map { it.path })
        assertEquals(AutoCommit.FolderStat("a", 2, 15, 2, 3072), f[1])
        assertEquals(AutoCommit.FolderStat("b/c", 1, 1, 7, 512), f[2])
    }

    @Test fun bodyListsFoldersThenFiles() {
        val m = AutoCommit.message(three)
        assertEquals(
            "auto: 4 files (+19 −12, 3.6 KB) in 3 folders\n\n" +
                "/ — 1 file (+3 −3, 100 B)\na — 2 files (+15 −2, 3.0 KB)\nb/c — 1 file (+1 −7, 512 B)\n\n" +
                "a/x.kt (+10 −2)\na/y.kt (+5 −0)\nb/c/z.md (+1 −7)\nroot.txt (+3 −3)",
            m,
        )
    }

    @Test fun longListsStopAtTheLimitAndCountTheRest() {
        val many = (1..500).map { FileStat("src/pkg/File$it.kt", it, 1, 100) }
        val m = AutoCommit.message(many)
        assertTrue(m.toByteArray().size <= AutoCommit.LIMIT)
        val shown = m.lines().count { it.startsWith("src/pkg/File") }
        assertTrue(shown in 50 until 500)
        assertTrue(m, m.endsWith("…and ${500 - shown} more"))
        // a tighter limit shows fewer and still counts every one left out
        val small = AutoCommit.message(many, 600)
        assertTrue(small.toByteArray().size <= 600)
        val s = small.lines().count { it.startsWith("src/pkg/File") }
        assertTrue(small.endsWith("…and ${500 - s} more"))
        assertFalse(AutoCommit.message(three).contains("more"))
    }

    @Test fun parsesARealStagedDiff() {
        val dir = Files.createTempDirectory("ac").toFile()
        Git.init().setDirectory(dir).call().use { g ->
            File(dir, "keep.txt").writeText("one\ntwo\nthree\n")
            File(dir, "gone.txt").writeText("x\ny\n")
            g.add().addFilepattern(".").call()
            g.commit().setMessage("base").setAuthor("t", "t@t").setCommitter("t", "t@t").setSign(false).call()
            File(dir, "keep.txt").writeText("one\nTWO\nthree\nfour\n")
            File(dir, "gone.txt").delete()
            File(dir, "sub/dir").mkdirs(); File(dir, "sub/dir/new.kt").writeText("a\nb\nc\n")
            g.add().addFilepattern(".").call(); g.add().setUpdate(true).addFilepattern(".").call()
            val out = ByteArrayOutputStream()
            DiffFormatter(out).use { df ->
                df.setRepository(g.repository)
                val head = CanonicalTreeParser().apply { g.repository.newObjectReader().use { r -> reset(r, g.repository.resolve("HEAD^{tree}")) } }
                df.format(head, DirCacheIterator(g.repository.readDirCache()))
            }
            val stats = AutoCommit.stats(out.toString()) { p -> File(dir, p).let { if (it.isFile) it.length() else 0L } }
            assertEquals(listOf("gone.txt", "keep.txt", "sub/dir/new.kt"), stats.map { it.path })
            assertEquals(listOf(0 to 2, 2 to 1, 3 to 0), stats.map { it.added to it.deleted })
            assertEquals(listOf(0L, 19L, 6L), stats.map { it.bytes })
            assertEquals("auto: 3 files (+5 −3, 25 B) in 2 folders", AutoCommit.subject(stats))
        }
        dir.deleteRecursively()
    }

    @Test fun ruleNeverAsksTheModel() {
        var asked = 0
        val w = AutoCommit.write(three, "", AutoCommit.Writer.RULE, "m", true) { _, _ -> asked++; "x" }
        assertEquals(0, asked); assertEquals("rule", w.by); assertEquals(AutoCommit.message(three), w.message)
    }

    @Test fun modelAnswerIsUsedAndGetsTheStatAndTruncatedDiff() {
        var sent = ""
        val diff = "d".repeat(AutoCommit.DIFF_FOR_MODEL + 500)
        val w = AutoCommit.write(three, diff, AutoCommit.Writer.MODEL, "or/m", true) { m, p -> sent = p; assertEquals("or/m", m); "```\nFix sync\n\nbody\n```" }
        assertEquals("Fix sync\n\nbody", w.message); assertEquals("model or/m", w.by)
        assertTrue(sent.contains("a — 2 files (+15 −2, 3.0 KB)"))
        assertTrue(sent.contains("…(truncated)")); assertFalse(sent.contains("d".repeat(AutoCommit.DIFF_FOR_MODEL + 1)))
    }

    @Test fun modelFailuresFallBackToTheRule() {
        val rule = AutoCommit.message(three)
        fun w(online: Boolean, model: String?, ask: ((String, String) -> String?)?) = AutoCommit.write(three, "", AutoCommit.Writer.MODEL, model, online, ask)
        val cases = listOf(
            w(true, "m") { _, _ -> throw IllegalStateException("HTTP 502") } to "rule (fallback: HTTP 502)",
            w(true, "m") { _, _ -> null } to "rule (fallback: empty answer)",
            w(true, "m") { _, _ -> "  " } to "rule (fallback: empty answer)",
            w(false, "m") { _, _ -> error("must not be called offline") } to "rule (fallback: offline)",
            w(true, "") { _, _ -> "x" } to "rule (fallback: no model picked)",
            w(true, "m", null) to "rule (fallback: no model client)",
        )
        for ((got, by) in cases) { assertEquals(by, got.by); assertEquals(rule, got.message) }
        assertEquals(AutoCommit.Writer.RULE, AutoCommit.Writer.of("bogus"))
        assertEquals(AutoCommit.Writer.MODEL, AutoCommit.Writer.of("openrouter"))
    }

    @Test fun allReposRunsOnTheDirtyOnes() {
        val dirty = mapOf("clean" to 0, "dirty" to 3, "unknown" to null)
        assertEquals(listOf("dirty", "unknown"), AutoCommit.targets(listOf("clean", "dirty", "unknown")) { dirty[it] })
    }
}
