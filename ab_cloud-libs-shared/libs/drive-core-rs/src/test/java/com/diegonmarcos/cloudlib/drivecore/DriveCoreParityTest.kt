package com.diegonmarcos.cloudlib.drivecore

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test

/**
 * #1729 parity: the Rust core and the Kotlin reference return the same results on the same fixtures. The core is the REAL
 * libdrive_core_rs.so built for the runner (cargoHostBuild); where cargo is missing the tests that need it are skipped, unless
 * DRIVE_CORE_RS_REQUIRED=1 (CI), where its absence is a failure.
 */
class DriveCoreParityTest {
    private val logs = ArrayList<String>()
    private lateinit var root: File

    @Before fun setUp() {
        logs.clear()
        DriveCore.log = { logs += it }
        Engine.entries.forEach { DriveCore.setImpl(it, Impl.RUST) }
        root = Files.createTempDirectory("drivecore").toFile()
    }

    @After fun tearDown() { root.deleteRecursively() }

    private fun needCore() {
        val why = DriveCore.nativeUnavailableReason()
        if (System.getProperty("drive.core.required") == "1") assertNull("the Rust core must load in CI: $why", why)
        else assumeTrue("Rust core not built here: $why", why == null)
    }

    private fun file(rel: String, bytes: ByteArray): File = File(root, rel).apply { parentFile.mkdirs(); writeBytes(bytes) }
    private fun file(rel: String, size: Int): File = file(rel, ByteArray(size) { (it % 251).toByte() })

    private fun fixture() {
        file("loose.txt", 7); file("a/x.JPG", 100); file("a/deep/y.jpg", 300); file("a/deep/deeper/z", 5)
        file("b/big.mp4", 1000); file("b/.hidden", 2); file("b/trail.", 3); file("skipme/s.bin", 9999)
        file("Android/data/app/f", 8888); file("Android/media/m.png", 50); File(root, "empty").mkdirs()
        file("tab\there/odd\\name.dat", 11)
    }

    // ── hashing ────────────────────────────────────────────────────────────

    @Test fun blake3KotlinMatchesTheOfficialVectors() {
        fun h(b: ByteArray) = Blake3().also { it.update(b) }.digest().joinToString("") { "%02x".format(it) }
        assertEquals("af1349b9f5f9a1a6a0404dea36dcc9499bcb25c9adc112b7cc9a93cae41f3262", h(ByteArray(0)))
        assertEquals("6437b3ac38465133ffb63b75273a8db548c558465d79db03fd359c6cd5bd9d85", h("abc".toByteArray()))
    }

    @Test fun hashesAgreeOnAssortedSizes() {
        needCore()
        // 0, 1, one block, one chunk, a chunk + 1, several chunks (tree shapes), and a file larger than the 1 MiB buffer
        val sizes = listOf(0, 1, 63, 64, 65, 1023, 1024, 1025, 2048, 2049, 3 * 1024 + 7, 8 * 1024, 31 * 1024 + 5, 1_048_576 + 4097)
        val paths = sizes.mapIndexed { i, n -> file("h/f$i.bin", n).absolutePath }
        val rust = DriveCore.hashFiles(paths, threads = 4)
        val kotlin = DriveCore.withImpl(Engine.HASH, Impl.KOTLIN) { DriveCore.hashFiles(paths) }
        assertEquals(kotlin, rust)
        assertTrue(rust.all { it.error.isEmpty() && it.blake3.length == 64 && it.sha256.length == 64 })
        assertEquals(sizes.map { it.toLong() }, rust.map { it.bytes })
        assertTrue(logs.any { it.startsWith("engine=hash impl=rust") })
    }

    @Test fun limitAndSingleAlgorithmAgree() {
        needCore()
        val p = file("l.bin", 5000).absolutePath
        for (algos in listOf(DriveCore.SHA256, DriveCore.BLAKE3)) {
            val rust = DriveCore.hashFiles(listOf(p), algos, 2, 1500)
            val kotlin = DriveCore.withImpl(Engine.HASH, Impl.KOTLIN) { DriveCore.hashFiles(listOf(p), algos, 2, 1500) }
            assertEquals(kotlin, rust)
            assertEquals(1500L, rust[0].bytes)
        }
    }

    @Test fun anUnreadableFileIsAnErrorRowNotAnException() {
        needCore()
        val ok = file("ok.bin", 10).absolutePath
        val missing = File(root, "missing.bin").absolutePath
        val rust = DriveCore.hashFiles(listOf(missing, ok))
        val kotlin = DriveCore.withImpl(Engine.HASH, Impl.KOTLIN) { DriveCore.hashFiles(listOf(missing, ok)) }
        assertTrue(rust[0].error.isNotEmpty() && kotlin[0].error.isNotEmpty())
        assertEquals(kotlin[1], rust[1])
        assertEquals(missing, rust[0].path)
    }

    @Test fun contentUrisStayKotlinAndNeverReachTheCore() {
        val r = DriveCore.hashFiles(listOf("content://media/external/file/1"))
        assertTrue(r[0].error.startsWith("not a real file path"))
        assertTrue(logs.any { it.contains("content:// and other URIs stay Kotlin") })
        val w = runCatching { DriveCore.walk("content://tree/primary") }
        assertTrue(w.isFailure)
    }

    // ── walk ───────────────────────────────────────────────────────────────

    private val opts get() = WalkOpts(skipNames = setOf("skipme"), skipRel = setOf("Android/data"), looseLabel = "LOOSE", threads = 4, listFiles = true)

    @Test fun walksAgreeAndAggregateRight() {
        needCore()
        fixture()
        val rust = DriveCore.walk(root.path, opts)
        val kotlin = DriveCore.withImpl(Engine.WALK, Impl.KOTLIN) { DriveCore.walk(root.path, opts) }
        assertEquals(kotlin, rust)
        assertEquals(9L, rust.files)
        assertEquals(7L + 100 + 300 + 5 + 1000 + 2 + 3 + 50 + 11, rust.bytes)
        assertEquals("", rust.partial)
        assertEquals(File(root, "b").path to 1005L, rust.folders[0])
        assertEquals(SizedPath(1000, File(root, "b/big.mp4").path), rust.topFiles[0])
        assertEquals(ExtTotal(3, 10), rust.byExt[""])
        assertTrue(rust.folders.none { it.first.contains("skipme") })
        assertEquals(9, rust.listing.size)
        assertTrue(logs.any { it.startsWith("engine=walk impl=rust") })
    }

    @Test fun walksAgreeOnTopNAndCaps() {
        needCore()
        fixture()
        val o = opts.copy(topFiles = 2, topFolders = 2, listFiles = false, threads = 1)
        assertEquals(DriveCore.withImpl(Engine.WALK, Impl.KOTLIN) { DriveCore.walk(root.path, o) }, DriveCore.walk(root.path, o))
        val capped = o.copy(maxEntries = 3)
        val rust = DriveCore.walk(root.path, capped)
        val kotlin = DriveCore.withImpl(Engine.WALK, Impl.KOTLIN) { DriveCore.walk(root.path, capped) }
        assertEquals("partial, stopped after 3 entries", rust.partial)
        assertEquals(rust.partial, kotlin.partial)
    }

    @Test fun neverFollowsLinksOutOfTheRoot() {
        needCore()
        val outside = Files.createTempDirectory("outside").toFile()
        try {
            File(outside, "secret.mp4").writeBytes(ByteArray(5000)); file("mine.mp4", 1)
            Files.createSymbolicLink(File(root, "linkdir").toPath(), outside.toPath())
            Files.createSymbolicLink(File(root, "linkfile.mp4").toPath(), File(outside, "secret.mp4").toPath())
            val rust = DriveCore.walk(root.path, WalkOpts(looseLabel = "L"))
            val kotlin = DriveCore.withImpl(Engine.WALK, Impl.KOTLIN) { DriveCore.walk(root.path, WalkOpts(looseLabel = "L")) }
            assertEquals(kotlin, rust)
            assertEquals(1L, rust.bytes)
        } finally { outside.deleteRecursively() }
    }

    @Test fun dirBytesAgrees() {
        needCore()
        fixture()
        val rust = DriveCore.dirBytes(root.path)
        val kotlin = DriveCore.withImpl(Engine.WALK, Impl.KOTLIN) { DriveCore.dirBytes(root.path) }
        assertEquals(kotlin, rust)
        assertEquals(7L + 100 + 300 + 5 + 1000 + 2 + 3 + 9999 + 8888 + 50 + 11, rust)
    }

    // ── diff ───────────────────────────────────────────────────────────────

    @Test fun diffsAgree() {
        needCore()
        val l = listOf(
            ListedFile("/k", 1, 1), ListedFile("/gone", 1, 1), ListedFile("/size", 1, 1), ListedFile("/time", 1, 1),
            ListedFile("/same-hash", 1, 1, "h"), ListedFile("/diff-hash", 1, 1, "h"), ListedFile("/one-hash", 1, 1, "h"), ListedFile("/t\tab", 1, 1),
        )
        val r = listOf(
            ListedFile("/k", 1, 1), ListedFile("/new", 9, 9), ListedFile("/size", 2, 1), ListedFile("/time", 1, 2),
            ListedFile("/same-hash", 1, 5, "h"), ListedFile("/diff-hash", 1, 1, "g"), ListedFile("/one-hash", 1, 2), ListedFile("/t\tab", 2, 1),
        )
        val rust = DriveCore.diff(l, r)
        val kotlin = DriveCore.withImpl(Engine.DIFF, Impl.KOTLIN) { DriveCore.diff(l, r) }
        assertEquals(kotlin, rust)
        assertEquals(listOf('M' to "/diff-hash", 'D' to "/gone", 'A' to "/new", 'M' to "/one-hash", 'M' to "/size", 'M' to "/t\tab", 'M' to "/time"), rust.map { it.kind to it.path })
        assertTrue(DriveCore.diff(l, l).isEmpty())
    }

    @Test fun aSnapshotDiffFindsAnEditedTree() {
        needCore()
        fixture()
        val before = DriveCore.snapshot(root.path)
        file("a/x.JPG", 101); file("new/n.txt", 4); File(root, "b/big.mp4").delete()
        val d = DriveCore.diff(before, DriveCore.snapshot(root.path))
        assertEquals(listOf('M' to File(root, "a/x.JPG").path, 'D' to File(root, "b/big.mp4").path, 'A' to File(root, "new/n.txt").path), d.map { it.kind to it.path }.sortedBy { it.second })
    }

    // ── the switch and the boundary ────────────────────────────────────────

    @Test fun theSwitchSelectsTheImplementationAndIsDeclared() {
        DriveCore.configure("hash=kotlin, walk = rust,bogus=rust,diff=nonsense")
        assertEquals(Impl.KOTLIN, DriveCore.impl(Engine.HASH))
        assertEquals(Impl.RUST, DriveCore.impl(Engine.WALK))
        assertEquals(Impl.RUST, DriveCore.impl(Engine.DIFF))
        val p = file("s.bin", 10).absolutePath
        val spans = ArrayList<String>()
        DriveCore.spans = object : DriveCore.Spans { override fun <T> span(op: String, impl: String, block: () -> T): T { spans += "$op/$impl"; return block() } }
        try { DriveCore.hashFiles(listOf(p)); DriveCore.withImpl(Engine.HASH, Impl.RUST) { DriveCore.hashFiles(listOf(p)) } }
        finally { DriveCore.spans = object : DriveCore.Spans { override fun <T> span(op: String, impl: String, block: () -> T): T = block() } }
        assertEquals("drive.core.hash/kotlin", spans[0])
        assertEquals(Impl.KOTLIN, DriveCore.impl(Engine.HASH))
        if (DriveCore.nativeUnavailableReason() == null) assertEquals("drive.core.hash/rust", spans[1])
    }

    @Test fun aRustPanicIsCaughtAtTheBoundary() {
        needCore()
        val a = DriveCore.panicProbe()
        assertNotNull(a)
        assertTrue(a!!, a.startsWith("ERR\tpanic: drive-core-rs panic probe"))
    }

    @Test fun whenTheCoreIsUnavailableKotlinAnswersAndSaysWhy() {
        // the Kotlin switch never touches the library; this proves the fallback answer is complete on its own
        val p = file("k.bin", 100).absolutePath
        val r = DriveCore.withImpl(Engine.HASH, Impl.KOTLIN) { DriveCore.hashFiles(listOf(p)) }
        assertEquals(100L, r[0].bytes)
        assertFalse(r[0].sha256.isEmpty())
        assertTrue(DriveCore.nativeUnavailableReason() == null || DriveCore.nativeUnavailableReason()!!.contains("did not load"))
    }

    @Test fun theWireEscapesRoundTrip() {
        for (s in listOf("plain", "a\tb", "a\nb", "a\\b", "a\\tb", "\\", "ünï/çødé")) assertEquals(s, Wire.unesc(Wire.esc(s)))
    }
}
