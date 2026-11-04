package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** #1195 the Size on disk column: the walk, the cache keyed by path + HEAD / index / folder mtimes, incremental refresh, and no walk on the main thread. */
@RunWith(RobolectricTestRunner::class)
class RepoDiskSizeTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun repo(name: String, bytes: Int): File {
        val d = tmp.newFolder(name)
        File(d, ".git").mkdirs()
        File(d, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(d, "a.txt").writeBytes(ByteArray(bytes))
        return d
    }

    private fun onWorker(block: () -> Unit) {
        var err: Throwable? = null
        val t = Thread { try { block() } catch (e: Throwable) { err = e } }
        t.start(); t.join()
        err?.let { throw it }
    }

    @Test fun `the walk sums the working tree and the git folder`() = onWorker {
        val d = repo("r", 1000)
        assertEquals(1000L + "ref: refs/heads/main\n".length, RepoDiskSize.compute(d))
    }

    @Test fun `a walk on the main thread is refused`() {
        // Robolectric runs the test body on the main looper thread: this is the StrictMode-style check.
        try { RepoDiskSize.compute(repo("m", 10)); fail("a disk walk ran on the main thread") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("main thread")) }
    }

    @Test fun `an unchanged clone is served from the cache and walked zero times`() = onWorker {
        val d = repo("c", 500)
        var walks = 0
        val walk: (File) -> Long = { walks++; RepoDiskSize.compute(it) }
        val first = RepoDiskSize.refresh(emptyMap(), listOf(d), walk = walk)
        assertEquals(1, walks)
        val second = RepoDiskSize.refresh(first, listOf(d), walk = walk)
        assertEquals("fingerprint unchanged: no second walk", 1, walks)
        assertEquals(first, second)
    }

    @Test fun `a moved HEAD or index invalidates only that clone`() = onWorker {
        val a = repo("a", 100); val b = repo("b", 200)
        var walks = 0
        val walk: (File) -> Long = { walks++; RepoDiskSize.compute(it) }
        val first = RepoDiskSize.refresh(emptyMap(), listOf(a, b), walk = walk)
        assertEquals(2, walks)
        File(a, "new.bin").writeBytes(ByteArray(50))
        File(a, ".git/index").writeBytes(ByteArray(10))
        File(a, ".git/HEAD").setLastModified(System.currentTimeMillis() + 5_000)
        val second = RepoDiskSize.refresh(first, listOf(a, b), walk = walk)
        assertEquals("only the changed clone is walked again", 3, walks)
        assertTrue(second.getValue(a.absolutePath).bytes > first.getValue(a.absolutePath).bytes)
        assertEquals(first.getValue(b.absolutePath), second.getValue(b.absolutePath))
    }

    @Test fun `force walks everything and a removed clone drops out of the cache`() = onWorker {
        val a = repo("fa", 100); val b = repo("fb", 100)
        var walks = 0
        val walk: (File) -> Long = { walks++; RepoDiskSize.compute(it) }
        val first = RepoDiskSize.refresh(emptyMap(), listOf(a, b), walk = walk)
        RepoDiskSize.refresh(first, listOf(a, b), force = true, walk = walk)
        assertEquals(4, walks)
        b.deleteRecursively()
        val third = RepoDiskSize.refresh(first, listOf(a, b), walk = walk)
        assertEquals(setOf(a.absolutePath), third.keys)
    }

    @Test fun `the cache round-trips through its persisted text and ignores garbage`() {
        val m = mapOf("/s/a" to RepoDiskSize.Entry("fp|1|2", 42L), "/s/with space" to RepoDiskSize.Entry("fp|3", 7L))
        assertEquals(m, RepoDiskSize.decode(RepoDiskSize.encode(m)))
        assertEquals(emptyMap<String, RepoDiskSize.Entry>(), RepoDiskSize.decode("not\ta\tnumber\nplain junk"))
        assertEquals(emptyMap<String, RepoDiskSize.Entry>(), RepoDiskSize.decode(null))
    }

    @Test fun `the table prints a dash for a repo that is not cloned and sorts the column`() {
        val notCloned = RepoTable.rowOf("x", false, cloned = false, glance = null, remote = null, diskBytes = 999)
        assertNull(notCloned.sizeDiskBytes)
        assertEquals("—", RepoTable.size(notCloned.sizeDiskBytes))
        val big = RepoTable.rowOf("big", false, true, null, null, diskBytes = 5_000_000)
        val small = RepoTable.rowOf("small", false, true, null, null, diskBytes = 1_000)
        val rows = listOf(notCloned, big, small)
        assertEquals(listOf("small", "big", "x"), RepoTable.sort(rows, RepoColumn.SIZE_DISK, true) { it }.map { it.name })
        assertEquals(listOf("big", "small", "x"), RepoTable.sort(rows, RepoColumn.SIZE_DISK, false) { it }.map { it.name })
        assertFalse(RepoColumn.entries.indexOf(RepoColumn.SIZE_DISK) < RepoColumn.entries.indexOf(RepoColumn.SIZE_TOTAL))
    }
}
