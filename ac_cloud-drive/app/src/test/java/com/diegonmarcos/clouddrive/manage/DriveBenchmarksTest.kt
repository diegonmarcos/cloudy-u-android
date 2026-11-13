package com.diegonmarcos.clouddrive.manage

import com.diegonmarcos.superapp.fleetxray.perf.XrayBenchmarks
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1728 Cloud Drive's declared Performance benchmarks: which hot paths they cover, that each is bounded and read-only, and that each runs an instrumented path. */
class DriveBenchmarksTest {
    private val ops = DriveBenchmarks.all().map { it.op }

    @Test fun theEightHotPathsAndTheRustABAreDeclared() {
        assertEquals(listOf("xray.memory.scan", "drive.largest-folders.walk", "fsync.folder.scan", "git.status.read", "git.sync.light-check",
            "apps.local.refresh", "files.hash.sha256", "mediastore.data-strips.query", "drive.core.ab"), ops)
    }

    @Test fun everyBenchmarkIsBoundedAndSaysWhyItIsSafe() {
        DriveBenchmarks.all().forEach { b ->
            assertTrue(b.op, b.maxMs in 1..XrayBenchmarks.MAX_MS_CAP)
            assertTrue(b.op, b.repeats in 1..3)
            assertTrue(b.op, b.safety.startsWith("read-only"))
            assertTrue(b.op, b.title.isNotBlank())
        }
        assertEquals(ops.size, ops.toSet().size)
    }

    private fun source(rel: String): String {
        val roots = listOf("src/main/java/com/diegonmarcos/clouddrive/", "app/src/main/java/com/diegonmarcos/clouddrive/")
        return roots.map { File(it + rel) }.first { it.isFile }.readText()
    }

    @Test fun everyBenchmarkedOperationIsRecordedByAnInstrumentedPathOfTheApp() {
        val where = mapOf(
            "xray.memory.scan" to "manage/XrayMemoryCollector.kt", "drive.largest-folders.walk" to "manage/DataScanner.kt", "fsync.folder.scan" to "sync/RepoDiskSize.kt",
            "git.status.read" to "sync/GitSyncCoordinator.kt", "git.sync.light-check" to "sync/GitSyncCoordinator.kt", "apps.local.refresh" to "manage/AppSnapshot.kt",
            "files.hash.sha256" to "manage/DriveBenchmarks.kt", "mediastore.data-strips.query" to "manage/OverviewAppsData.kt",
            "drive.core.ab" to "manage/DriveBenchmarks.kt")
        assertEquals(ops.toSet(), where.keys)
        where.forEach { (op, file) -> assertTrue("$op is not a span in $file", source(file).contains("span(\"$op")) }
    }

    @Test fun gitSyncOperationsAreProfiledToo() {
        assertTrue(source("sync/GitSyncCoordinator.kt").contains("span(\"git.sync.op\")"))
    }

    @Test fun hashEngineIsTheKotlinOneAndOtherEnginesJoinAsTheirOwnRows() {
        assertEquals(listOf("kotlin"), DriveBenchmarks.hashEngineNames().filter { it == "kotlin" })
        DriveBenchmarks.registerHashEngine("test-engine") { "x" }
        assertTrue(DriveBenchmarks.hashEngineNames().containsAll(listOf("kotlin", "test-engine")))
        DriveBenchmarks.registerSizeEngine("test-engine") { _, _ -> 0L }
        assertTrue(DriveBenchmarks.sizeEngineNames().containsAll(listOf("kotlin", "test-engine")))
    }

    @Test fun sha256IsCorrect() {
        val f = File.createTempFile("bench", ".bin"); f.writeText("abc")
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", DriveBenchmarks.sha256Kotlin(f)); f.delete()
    }

    @Test fun sampleIsBoundedInFilesAndBytesAndSkipsTinyAndHugeFiles() {
        val dir = java.nio.file.Files.createTempDirectory("sample").toFile()
        val files = (1..10).map { i -> File(dir, "f$i").also { it.writeBytes(ByteArray(10_000)) } } + File(dir, "tiny").also { it.writeBytes(ByteArray(10)) } + File(dir, "huge").also { it.writeBytes(ByteArray(40_000)) }
        assertEquals(3, DriveBenchmarks.pickSample(files.asSequence(), maxFiles = 3, maxBytes = 1_000_000).size)
        val byBytes = DriveBenchmarks.pickSample(files.asSequence(), maxFiles = 50, maxBytes = 25_000)
        assertEquals(2, byBytes.size)
        assertTrue(DriveBenchmarks.pickSample(files.asSequence(), maxFiles = 50, maxBytes = 10_000_000, maxOne = 20_000).none { it.name == "huge" || it.name == "tiny" })
        dir.deleteRecursively()
    }
}
