package com.diegonmarcos.clouddrive.manage

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Manage ▸ Data's scanner: size ranking, never leaving a root, skipping app sandboxes, the cache and cancel. */
class DataScannerTest {
    private fun file(dir: File, name: String, bytes: Int) = File(dir, name).apply { parentFile.mkdirs(); writeBytes(ByteArray(bytes)) }

    @Test fun ranksBigToSmallAndClassifies() {
        val root = Files.createTempDirectory("scan").toFile()
        file(root, "a/small.jpg", 10); file(root, "b/big.mp4", 300); file(root, "mid.pdf", 100); file(root, "noise.unknown", 999)
        val r = DataScanner().scan(listOf(root))
        assertTrue(r.complete)
        // #1537 an unknown extension is Others, so "All" (the default) really lists every file
        assertEquals(listOf("noise.unknown", "big.mp4", "mid.pdf", "small.jpg"), r.items.map { File(it.path).name })
        assertEquals(listOf(999L, 300L, 100L, 10L), r.items.map { it.size })
        assertEquals(DataKind.OTHERS, r.items.first { it.path.endsWith("noise.unknown") }.kind)
        assertEquals(r.items.map { it.path }, DataScanner.filtered(r.items, DataFilter.DEFAULT).map { it.path })
        val media = DataScanner.filtered(r.items, DataFilter.DEFAULT.toggleGroup(DataGroup.MEDIA))
        assertEquals(listOf("big.mp4", "small.jpg"), media.map { File(it.path).name })
        assertEquals(listOf(300L, 10L), DataScanner.folders(media).map { it.second })
        assertEquals(listOf("noise.unknown"), DataScanner.filtered(r.items, DataFilter.DEFAULT.toggle(DataKind.OTHERS)).map { File(it.path).name })
    }

    @Test fun neverFollowsSymlinksOutOfTheRoots() {
        val root = Files.createTempDirectory("root").toFile()
        val outside = Files.createTempDirectory("outside").toFile()
        file(outside, "secret.mp4", 5000); file(root, "mine.mp4", 1)
        Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "linkfile.mp4").toPath(), File(outside, "secret.mp4").toPath())
        val r = DataScanner().scan(listOf(root))
        assertEquals(listOf("mine.mp4"), r.items.map { File(it.path).name })
        assertTrue(r.items.all { it.path.startsWith(root.toPath().toRealPath().toString()) })
    }

    @Test fun skipsAppSandboxesButKeepsAndroidMedia() {
        val root = Files.createTempDirectory("emu").toFile()
        file(root, "Android/data/com.x/cache.mp4", 50); file(root, "Android/obb/com.x/main.zip", 50); file(root, "Android/media/com.whatsapp/v.mp4", 40)
        val names = DataScanner().scan(listOf(root)).items.map { File(it.path).name }
        assertEquals(listOf("v.mp4"), names)
    }

    @Test fun nestedRootIsScannedOnce() {
        val root = Files.createTempDirectory("emu").toFile()
        val store = File(root, "CloudDrive").apply { mkdirs() }
        file(store, "repo/a.mp4", 7)
        val r = DataScanner().scan(listOf(root, store))
        assertEquals(1, r.items.size)
    }

    @Test fun reusesUnchangedDirectoriesAndRoundTripsTheCache() {
        val root = Files.createTempDirectory("inc").toFile()
        file(root, "d/x.mp4", 3)
        val first = DataScanner().scan(listOf(root))
        val cache = DataScanner.decodeCache(DataScanner.encodeCache(first.cache))
        val second = DataScanner().scan(listOf(root), cache)
        assertTrue("an unchanged tree is served from the cache", second.reused >= 2)
        assertEquals(first.items.map { it.path to it.size }, second.items.map { it.path to it.size })
    }

    @Test fun cancelStopsTheWalk() {
        val root = Files.createTempDirectory("cancel").toFile()
        repeat(20) { file(root, "d$it/f.mp4", 1) }
        var n = 0
        val r = DataScanner().scan(listOf(root), cancelled = { ++n > 5 })
        assertFalse(r.complete)
        assertTrue(r.items.size < 20)
    }
}
