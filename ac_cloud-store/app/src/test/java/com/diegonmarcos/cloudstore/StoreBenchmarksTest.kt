package com.diegonmarcos.cloudstore

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1728 Cloud Store's declared Performance benchmarks. */
class StoreBenchmarksTest {
    @Test fun theFourHotPathsAreDeclaredReadOnlyAndBounded() {
        val all = StoreBenchmarks.all()
        assertEquals(listOf("store.catalogue.parse", "store.installed.diff", "store.apk.hash", "store.phone.grouping"), all.map { it.op })
        all.forEach { b -> assertTrue(b.op, b.safety.startsWith("read-only")); assertTrue(b.op, b.maxMs in 1..60_000); assertTrue(b.op, b.repeats in 1..3) }
    }

    @Test fun everyDeclaredOperationIsASpanInTheBenchmarkSource() {
        val src = listOf("src/main/java/com/diegonmarcos/cloudstore/StoreBenchmarks.kt", "app/src/main/java/com/diegonmarcos/cloudstore/StoreBenchmarks.kt").map { File(it) }.first { it.isFile }.readText()
        StoreBenchmarks.all().forEach { assertTrue(it.op, src.contains("span(\"${it.op}\")")) }
    }

    @Test fun sha256IsCorrectAndTheSampleIsBounded() {
        val dir = java.nio.file.Files.createTempDirectory("sb").toFile()
        val f = File(dir, "a").also { it.writeText("abc") }
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", StoreBenchmarks.sha256(f))
        val files = (1..5).map { File(dir, "f$it").also { x -> x.writeBytes(ByteArray(1000)) } }
        assertEquals(2, StoreBenchmarks.pickSample(files.asSequence(), maxFiles = 2, maxBytes = 10_000).size)
        assertEquals(3, StoreBenchmarks.pickSample(files.asSequence(), maxFiles = 10, maxBytes = 3_500).size)
        dir.deleteRecursively()
    }
}
