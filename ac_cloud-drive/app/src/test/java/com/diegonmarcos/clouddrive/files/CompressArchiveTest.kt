package com.diegonmarcos.clouddrive.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1607 Compress: the new .tar.gz writer produces a real archive the app's own extractor reads back
 * byte-for-byte, and the .zip writer (reused) does the same. A folder's tree is preserved.
 */
class CompressArchiveTest {

    private val noProgress = FileOps.Progress { _, _, _ -> }
    private val live = { false }
    private fun tmp(): File = Files.createTempDirectory("compress").toFile()

    private fun tree(root: File) {
        File(root, "a.txt").writeText("alpha")
        File(root, "sub").mkdirs()
        File(root, "sub/b.txt").writeText("bravo-bravo")
    }

    @Test fun tarGzRoundTripsThroughTheAppsExtractor() {
        val dir = tmp(); val src = File(dir, "data").apply { mkdirs() }; tree(src)
        val out = File(dir, "data.tar.gz")
        val n = FileOps.archiveTarGz(listOf(src), out, noProgress, live)
        assertEquals(2, n)
        assertTrue(out.isFile && out.length() > 0)
        assertEquals(ArchiveExtractor.Kind.TAR_GZ, ArchiveExtractor.kindOf(out.name))
        val dest = File(dir, "back").apply { mkdirs() }
        ArchiveExtractor.extract(out, dest, noProgress, live)
        assertEquals("alpha", File(dest, "data/a.txt").readText())
        assertEquals("bravo-bravo", File(dest, "data/sub/b.txt").readText())
    }

    @Test fun zipAndTarGzBothRefuseAnExistingTarget() {
        val dir = tmp(); val f = File(dir, "f.txt").apply { writeText("x") }
        val out = File(dir, "x.tar.gz").apply { writeText("already here") }
        runCatching { FileOps.archiveTarGz(listOf(f), out, noProgress, live) }.also { assertTrue(it.isFailure) }
        val zout = File(dir, "x.zip").apply { writeText("already here") }
        runCatching { FileOps.archive(listOf(f), zout, noProgress, live) }.also { assertTrue(it.isFailure) }
    }

    @Test fun aFailedTarGzLeavesNoPartialArchive() {
        val dir = tmp(); val f = File(dir, "f.txt").apply { writeText("x") }
        val out = File(dir, "cancelled.tar.gz")
        runCatching { FileOps.archiveTarGz(listOf(f), out, noProgress) { true } }
        assertTrue("partial archive must be cleaned up", !out.exists())
    }
}
