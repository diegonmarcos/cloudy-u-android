package com.diegonmarcos.clouddrive.sync

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** The status scheduler's contract after the memory/timeout report: bounded concurrency, cancellation, the size guard, the cheap facts. */
class RepoSchedulerTest {
    private lateinit var tmp: File
    private val release = CountDownLatch(1)

    @Before fun setUp() { tmp = Files.createTempDirectory("repo-scheduler").toFile() }
    @After fun tearDown() { release.countDown(); tmp.deleteRecursively() }

    private fun clone(name: String, onDemand: Boolean = false): GitStatusReader.Target {
        val d = File(tmp, name); File(d, ".git").mkdirs(); return GitStatusReader.Target(name, d, onDemand)
    }
    private fun probe() = GitStatusReader.Probe("main", "origin/main", 0, 0, 0, 0)
    private fun waitFor(what: String, cond: () -> Boolean) { val end = System.currentTimeMillis() + 5_000; while (!cond()) { check(System.currentTimeMillis() < end) { "timed out: $what" }; Thread.sleep(5) } }

    @Test fun neverMoreProbesThanTheDeclaredConcurrency() = runBlocking {
        val now = AtomicInteger(); val peak = AtomicInteger(); val calls = AtomicInteger()
        val reader = GitStatusReader(
            probe = { calls.incrementAndGet(); val n = now.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; Thread.sleep(30); now.decrementAndGet(); probe() },
            timeoutMs = 5_000, concurrency = 1, ttlMs = 60_000,
        )
        withTimeout(10_000) { reader.request((1..6).map { clone("r$it") }).join() }
        assertEquals(6, calls.get())
        assertEquals("one repository at a time", 1, peak.get())
        assertTrue(reader.statuses.value.values.all { it.state == GitStatusReader.State.CLEAN })
    }

    @Test fun closingTheScreenCancelsTheReadsStillQueued() = runBlocking {
        val started = AtomicInteger()
        val reader = GitStatusReader(probe = { started.incrementAndGet(); release.await(); probe() }, timeoutMs = 30_000, concurrency = 1, ttlMs = 60_000)
        reader.request(listOf(clone("a"), clone("b"), clone("c")))
        waitFor("first probe started") { started.get() == 1 }
        reader.cancelAll()
        release.countDown()
        waitFor("running probe returns") { !reader.busy("a") }
        Thread.sleep(100)
        assertEquals("the queued reads never started", 1, started.get())
        assertFalse(reader.busy("b")); assertFalse(reader.busy("c"))
        // and they can be asked again later: nothing stays claimed
        withTimeout(5_000) { reader.request(listOf(clone("b"))).join() }
        assertEquals(GitStatusReader.State.CLEAN, reader.statuses.value["b"]!!.state)
    }

    @Test fun aLargeRepositoryIsSkippedByThePassAndReadOnDemand() = runBlocking {
        val probed = ConcurrentList()
        val reader = GitStatusReader(probe = { probed.add(it.name); probe() }, timeoutMs = 5_000, concurrency = 1, ttlMs = 60_000, tooLarge = { it.name == "big" })
        withTimeout(5_000) { reader.request(listOf(clone("big"), clone("small")), level = GitStatusReader.Level.FULL).join() }
        assertEquals(listOf("small"), probed.items)
        assertEquals(GitStatusReader.State.LARGE, reader.statuses.value["big"]!!.state)
        assertTrue(reader.statuses.value["big"]!!.terminal)
        val g = GitSyncCoordinator.glanceOf(reader.statuses.value["big"]!!)
        assertTrue(g.large && g.read)
        assertEquals(RepoStatus.Kind.LARGE, RepoStatus.kindOf(true, g))
        // tapping "Check working tree": on demand, forced, the guard does not apply
        withTimeout(5_000) { reader.request(listOf(clone("big", onDemand = true)), force = true).join() }
        assertEquals(listOf("small", "big"), probed.items)
        assertEquals(GitStatusReader.State.CLEAN, reader.statuses.value["big"]!!.state)
    }

    @Test fun lightLevelNeverTouchesTheWorktreeAndFullIsOneAtATime() = runBlocking {
        val light = ConcurrentList(); val full = ConcurrentList()
        val now = AtomicInteger(); val peak = AtomicInteger()
        val reader = GitStatusReader(
            probe = { d -> val n = now.incrementAndGet(); peak.updateAndGet { maxOf(it, n) }; Thread.sleep(20); now.decrementAndGet(); full.add(d.name); GitStatusReader.Probe("main", "origin/main", 0, 0, 2, 0) },
            lightProbe = { d -> light.add(d.name); GitStatusReader.Probe("main", "origin/main", 1, 0, 0, 0) },
            timeoutMs = 5_000, concurrency = 2, ttlMs = 60_000, tooLarge = { it.name == "big" },
        )
        val ts = listOf(clone("a"), clone("big"), clone("c"))
        withTimeout(5_000) { reader.request(ts).join() }
        assertEquals("light reads every repository, large ones too, with the refs probe only", listOf("a", "big", "c"), light.items.sorted())
        assertTrue("no worktree probe in the light pass", full.items.isEmpty())
        val s = reader.statuses.value
        assertTrue(s.values.all { it.level == GitStatusReader.Level.LIGHT && it.state == GitStatusReader.State.AHEAD })
        // a fresh LIGHT result is not read again by another LIGHT ask
        withTimeout(5_000) { reader.request(ts).join() }
        assertEquals(3, light.items.size)
        // FULL upgrades it: the worktree is scanned, one at a time, the large one is skipped (confirmed individually later)
        withTimeout(5_000) { reader.request(ts, level = GitStatusReader.Level.FULL).join() }
        assertEquals(listOf("a", "c"), full.items.sorted())
        assertEquals(1, peak.get())
        assertEquals(GitStatusReader.Level.FULL, reader.statuses.value["a"]!!.level)
        assertEquals(GitStatusReader.State.DIRTY, reader.statuses.value["a"]!!.state)
        assertEquals(GitStatusReader.State.LARGE, reader.statuses.value["big"]!!.state)
        // a fresh FULL result satisfies a later LIGHT ask: it is not downgraded
        withTimeout(5_000) { reader.request(ts).join() }
        assertEquals(GitStatusReader.State.DIRTY, reader.statuses.value["a"]!!.state)
        assertEquals(GitStatusReader.Level.FULL, reader.statuses.value["a"]!!.level)
        // confirmed individually: the large repository is scanned now
        withTimeout(5_000) { reader.request(listOf(clone("big", onDemand = true)), force = true).join() }
        assertEquals(GitStatusReader.State.DIRTY, reader.statuses.value["big"]!!.state)
    }

    @Test fun lightGlanceHasNoDirtyCount_andTheCaptionNamesLevelAndAge() {
        val st = GitStatusReader.Status(GitStatusReader.State.AHEAD, GitStatusReader.Probe("main", "origin/main", 2, 0, 0, 0), readAtMs = 1_000, level = GitStatusReader.Level.LIGHT)
        val g = GitSyncCoordinator.glanceOf(st)
        assertEquals("light", g.level)
        assertEquals(null, RepoStatus.block(true, g).dirty)
        assertEquals(2, RepoStatus.block(true, g).ahead)
        assertEquals(null, RepoTable.rowOf("x", null, true, g, null).dirtyFiles)
        assertEquals("L 5m", RepoStatus.levelCaption(g, 1_000 + 5 * 60_000))
        val full = GitSyncCoordinator.glanceOf(st.copy(level = GitStatusReader.Level.FULL, probe = GitStatusReader.Probe("main", "origin/main", 0, 0, 3, 0)))
        assertEquals("F <1m", RepoStatus.levelCaption(full, 1_500))
        assertEquals("F 2h", RepoStatus.levelCaption(full, 1_000 + 2 * 3_600_000))
        assertEquals(3, RepoTable.rowOf("x", null, true, full, null).dirtyFiles)
        assertEquals("", RepoStatus.levelCaption(null, 5)); assertEquals("", RepoStatus.levelCaption(GitSyncCoordinator.Glance(), 5))
    }

    @Test fun aheadBehindFromRefsOnly() {
        assertEquals(RepoCheap.Refs(0, 0, false), RepoCheap.aheadBehind("a", "a", emptyList()))
        assertEquals(RepoCheap.Refs(2, 0, false), RepoCheap.aheadBehind("c", "a", listOf("c", "b", "a")))
        assertEquals("remote tip unknown locally: behind, exact count needs the full check", RepoCheap.Refs(0, 1, true), RepoCheap.aheadBehind("c", "z", listOf("c", "b", "a")))
        assertNull(RepoCheap.aheadBehind("c", null, emptyList()))
    }

    private class ConcurrentList { val items = java.util.Collections.synchronizedList(mutableListOf<String>()); fun add(s: String) { items.add(s) } }

    @Test fun theSizeGuardStopsCountingAtTheBound() {
        val d = File(tmp, "w").apply { mkdirs() }
        File(d, ".git").mkdirs(); File(d, ".git/huge").writeText("not counted")
        repeat(5) { File(d, "f$it").writeText("x") }
        File(d, "sub").mkdirs(); repeat(5) { File(d, "sub/g$it").writeText("x") }
        assertFalse(RepoCheap.tooLarge(d, maxFiles = 11, maxMs = 5_000))
        assertTrue(RepoCheap.tooLarge(d, maxFiles = 10, maxMs = 5_000))
        assertTrue("a count that cannot finish in time is large", RepoCheap.tooLarge(d, maxFiles = 1_000, maxMs = 0, clock = object : () -> Long { var t = 0L; override fun invoke() = t++ }))
    }

    @Test fun headAndUpstreamAreReadFromTheGitFilesWithoutTheEngine() {
        val d = File(tmp, "h"); File(d, ".git").mkdirs()
        File(d, ".git/HEAD").writeText("ref: refs/heads/main\n")
        File(d, ".git/config").writeText("[core]\n\tbare = false\n[branch \"main\"]\n\tremote = origin\n\tmerge = refs/heads/main\n[branch \"other\"]\n\tremote = up\n\tmerge = refs/heads/x\n")
        assertEquals(RepoCheap.Head("main", "origin/main"), RepoCheap.head(d))
        File(d, ".git/HEAD").writeText("ref: refs/heads/other\n")
        assertEquals(RepoCheap.Head("other", "up/x"), RepoCheap.head(d))
        File(d, ".git/HEAD").writeText("ref: refs/heads/new\n")
        assertEquals("no upstream configured", RepoCheap.Head("new", null), RepoCheap.head(d))
        File(d, ".git/HEAD").writeText("0123456789abcdef0123456789abcdef01234567\n")
        assertEquals("detached", RepoCheap.Head(null, null, detached = true), RepoCheap.head(d))
        assertNull(RepoCheap.upstreamOf("", "main"))
        assertTrue(File(d, ".git").exists()); assertFalse(File(File(tmp, "nope"), ".git").exists())
    }
}
