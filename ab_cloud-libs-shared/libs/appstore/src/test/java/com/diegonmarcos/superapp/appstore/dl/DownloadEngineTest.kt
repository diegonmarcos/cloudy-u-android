package com.diegonmarcos.superapp.appstore.dl

import java.io.File
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1517 The engine against a real (fake) HTTP server: resume, validators, verification, mirrors, queue rules, restart. */
class DownloadEngineTest {
    private lateinit var dir: File
    private lateinit var cacheDir: File
    private lateinit var srv: FakeServer
    private val body = ByteArray(300_000).also { Random(7).nextBytes(it) }
    private val user = UserAction.tap("test")

    @Before fun setUp() {
        dir = Files.createTempDirectory("dl-engine").toFile()
        cacheDir = File(dir, "cache").also { it.mkdirs() }
        srv = FakeServer()
    }

    @After fun tearDown() { srv.close(); dir.deleteRecursively() }

    /** One mirror per job: /<id>.apk with its sidecar read from the same server on every attempt. */
    private fun single(job: DlJob): List<DlMirror> = listOf(DlMirror("fake") {
        val u = srv.url("/${job.id}.apk")
        DlSource(u, sha256 = RangeFetch.sidecar(u), bytes = srv.files["/${job.id}.apk"]?.body?.size?.toLong() ?: -1L)
    })

    private fun engine(
        concurrent: Int = 2, retries: Int = 3, mirrors: (DlJob) -> List<DlMirror> = ::single,
        verified: (DlJob, File) -> Unit = { _, _ -> }, failed: (DlJob) -> Unit = {},
    ) = DownloadEngine(DownloadStore(File(dir, "queue.json")), DownloadCache(cacheDir), mirrors, concurrent, retries,
        backoff = { 0L }, onVerified = verified, onFailed = failed)

    private fun spec(id: String, priority: Int = 0, user: Boolean = true) = DlSpec(id, "p.$id", 1L, id, "fleet-$id-", priority, user)

    private fun until(ms: Long = 20_000, f: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!f()) { check(System.currentTimeMillis() < end) { "timed out; server log: ${srv.log}" }; Thread.sleep(15) }
    }

    private fun DownloadEngine.finish(id: String): DlJob { until { job(id)?.state?.let { !it.active } == true }; return job(id)!! }

    private fun doneFile(j: DlJob) = File(cacheDir, j.file)

    @Test fun rangeResumeContinuesFromThePartInsteadOfRestarting() {
        srv.put("/a.apk", body)
        srv.cutAfter = 100_000
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertTrue("the second request resumes with a Range", srv.log.any { it.contains("range=bytes=100000- ") && it.endsWith("-> 206") })
        assertEquals("exactly two GETs: the cut one and the resume", 2, srv.gets("/a.apk"))
        assertEquals(FakeServer.sha(body), FakeServer.sha(doneFile(j).readBytes()))
        assertFalse("the part is gone once verified", File(cacheDir, "fleet-a-dl.apk.part").exists())
    }

    @Test fun etagMismatchRestartsCleanlyViaIfRange() {
        val entry = srv.put("/a.apk", body, "\"v1\"")
        val fresh = ByteArray(250_000).also { Random(9).nextBytes(it) }
        srv.cutAfter = 80_000
        srv.onCut = { entry.body = fresh; entry.etag = "\"v2\"" }   // the artifact is replaced between the two attempts
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertTrue("the resume asked with the old validator", srv.log.any { it.contains("ifrange=\"v1\"") })
        assertTrue("and was answered with the whole new file (200), not appended", srv.log.last().endsWith("-> 200"))
        assertEquals(FakeServer.sha(fresh), FakeServer.sha(doneFile(j).readBytes()))
    }

    @Test fun aServerThatIgnoresIfRangeButChangesTheEtagStillRestarts() {
        val entry = srv.put("/a.apk", body, "\"v1\"")
        val fresh = ByteArray(250_000).also { Random(11).nextBytes(it) }
        srv.honorIfRange = false
        srv.cutAfter = 80_000
        srv.onCut = { entry.body = fresh; entry.etag = "\"v2\"" }
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertEquals("the 206 carried another ETag: the part is dropped and refetched", FakeServer.sha(fresh), FakeServer.sha(doneFile(j).readBytes()))
    }

    @Test fun serverIgnoringRangeAnswers200AndTheEngineRestartsClean() {
        srv.put("/a.apk", body)
        srv.ignoreRange = true
        srv.cutAfter = 100_000
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertEquals(FakeServer.sha(body), FakeServer.sha(doneFile(j).readBytes()))
        assertEquals("not 400 000 bytes (a part with the whole file appended)", body.size.toLong(), doneFile(j).length())
    }

    @Test fun http416WithAWrongPartRestartsCleanly() {
        srv.put("/a.apk", body)
        // A leftover part exactly as long as the file but not its bytes: Range answers 416, the sha check rejects, the retry is clean.
        File(cacheDir, "fleet-a-dl.apk.part").writeBytes(ByteArray(body.size) { 1 })
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertTrue(srv.log.any { it.endsWith("-> 416") })
        assertEquals(FakeServer.sha(body), FakeServer.sha(doneFile(j).readBytes()))
    }

    @Test fun shaMismatchFailsThenRetriesAndSucceeds() {
        srv.put("/a.apk", body)
        val resolves = AtomicInteger()
        srv.sidecarOverride = { if (resolves.getAndIncrement() == 0) "0".repeat(64) else null }
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertEquals("two full downloads: the rejected one and the retry", 2, srv.gets("/a.apk"))
        assertTrue("the retry did not resume on top of the rejected bytes", srv.log.none { it.contains("range=bytes=") })
    }

    @Test fun aPermanentShaMismatchEndsFailedAndNeverReachesTheInstaller() {
        srv.put("/a.apk", body)
        srv.sidecarOverride = { "f".repeat(64) }
        val installed = AtomicInteger()
        val failed = AtomicInteger()
        val e = engine(retries = 2, verified = { _, _ -> installed.incrementAndGet() }, failed = { failed.incrementAndGet() }); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.FAILED, j.state)
        assertTrue(j.error, j.error.contains("sha256 mismatch"))
        // the engine publishes FAILED first and calls onFailed right after, outside its lock: wait for the callback, never assume it
        until { failed.get() >= 1 }
        assertEquals(0, installed.get())
        assertEquals(1, failed.get())
        assertFalse(File(cacheDir, "fleet-a-dl.apk.part").exists())
        assertTrue(cacheDir.listFiles().orEmpty().none { it.name.endsWith(".apk") })
    }

    @Test fun mirrorsAreTriedInDeclaredOrderAndAHardFailureMovesOnAtOnce() {
        srv.put("/a.apk", body)
        val tried = java.util.Collections.synchronizedList(ArrayList<String>())
        val e = engine(mirrors = {
            listOf(
                DlMirror("mesh") { tried += "mesh"; throw java.io.IOException("mesh down") },
                DlMirror("release") { tried += "release"; val u = srv.url("/missing.apk"); DlSource(u, sha256 = "a".repeat(64), bytes = 10) },
                DlMirror("ghcr") { tried += "ghcr"; val u = srv.url("/a.apk"); DlSource(u, sha256 = RangeFetch.sidecar(u), bytes = body.size.toLong()) },
            )
        }); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertEquals(listOf("mesh", "release", "ghcr"), tried.distinct())
        assertEquals("a 404 is not retried on the same mirror", 1, srv.gets("/missing.apk"))
    }

    @Test fun everyMirrorFailingEndsFailedWithTheWholeTrail() {
        val e = engine(mirrors = { listOf(DlMirror("mesh") { error("a") }, DlMirror("release") { error("b") }) }); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.FAILED, j.state)
        assertTrue(j.error, j.error.contains("mesh: a") && j.error.contains("release: b"))
    }

    @Test fun softFailuresRetryUpToTheMaxThenMoveToTheNextMirror() {
        srv.put("/a.apk", body)
        srv.failNext = 100
        val e = engine(retries = 3, mirrors = {
            listOf(
                DlMirror("flaky") { val u = srv.url("/a.apk"); DlSource(u, sha256 = FakeServer.sha(body), bytes = body.size.toLong()) },
                DlMirror("good") { srv.failNext = 0; val u = srv.url("/a.apk"); DlSource(u, sha256 = FakeServer.sha(body), bytes = body.size.toLong()) },
            )
        }); e.start()
        e.enqueue(spec("a"))
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertEquals("three tries on the flaky mirror (HTTP 500), then the good one", 3, srv.log.count { it.endsWith("-> 500") })
    }

    @Test fun aTabSwitchOrAnUpdateCheckDuringADownloadNeverCancelsRestartsOrDuplicatesIt() {
        srv.put("/a.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        until { (e.job("a")?.bytes ?: 0L) > 0L && e.job("a")?.state == DlState.RUNNING }
        val checks = CheckGate<String, Int>(60_000)
        val seen = HashSet<DlState>()
        repeat(25) { n ->
            // what a tab switch does: ask again (the auto chain / a re-composed page), and run an update check, forced or not
            e.enqueue(spec("a", user = n % 2 == 0))
            checks.get("a", force = n % 3 == 0) { RangeFetch.sidecar(srv.url("/a.apk")).length }
            e.job("a")?.let { seen += it.state }
        }
        assertEquals("never cancelled, paused, failed or finished early", setOf(DlState.RUNNING), seen)
        assertEquals("one transfer, however often it was asked for", 1, srv.gets("/a.apk"))
        srv.hold!!.countDown()
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertEquals(1, srv.gets("/a.apk"))
        assertEquals(FakeServer.sha(body), FakeServer.sha(doneFile(j).readBytes()))
    }

    @Test fun theConcurrencyCapHolds() {
        for (id in listOf("a", "b", "c", "d")) srv.put("/$id.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(concurrent = 2); e.start()
        for (id in listOf("a", "b", "c", "d")) e.enqueue(spec(id))
        until { srv.inFlight.get() == 2 }
        Thread.sleep(200)
        assertEquals("never more than the declared two at once", 2, srv.inFlight.get())
        assertEquals(setOf("a", "b"), e.state.value.filter { it.state == DlState.RUNNING }.map { it.id }.toSet())
        assertEquals(DlState.QUEUED, e.job("c")!!.state)
        assertEquals(DlState.QUEUED, e.job("d")!!.state)
        srv.hold!!.countDown()
        for (id in listOf("a", "b", "c", "d")) assertEquals(DlState.DONE, e.finish(id).state)
        assertTrue("the cap was never exceeded", srv.maxInFlight.get() <= 2)
    }

    @Test fun aHigherPriorityQueuedJobStartsBeforeAnOlderOne() {
        for (id in listOf("a", "c", "d")) srv.put("/$id.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(concurrent = 1); e.start()
        e.enqueue(spec("a")); e.enqueue(spec("c", priority = 0)); e.enqueue(spec("d", priority = 5))
        until { srv.inFlight.get() == 1 }
        srv.hold!!.countDown()
        for (id in listOf("a", "c", "d")) assertEquals(DlState.DONE, e.finish(id).state)
        val order = srv.log.filter { it.startsWith("/c.apk") || it.startsWith("/d.apk") }.map { it.substring(1, 2) }
        assertEquals("the higher priority started before the older one", listOf("d", "c"), order)
    }

    @Test fun pauseKeepsThePartAndResumeContinuesFromIt() {
        srv.put("/a.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        until { (e.job("a")?.bytes ?: 0L) > 0L }
        e.pause("a", user)
        assertEquals(DlState.PAUSED, e.job("a")!!.state)
        srv.hold!!.countDown()
        Thread.sleep(300)
        assertEquals("a paused job stays paused", DlState.PAUSED, e.job("a")!!.state)
        val kept = File(cacheDir, "fleet-a-dl.apk.part").length()
        assertTrue("its bytes are kept", kept > 0)
        srv.hold = null
        e.resume("a")
        val j = e.finish("a")
        assertEquals(DlState.DONE, j.state)
        assertTrue("it resumed with a Range from the kept bytes", srv.log.any { it.contains("range=bytes=") && it.endsWith("-> 206") })
        assertEquals(FakeServer.sha(body), FakeServer.sha(doneFile(j).readBytes()))
    }

    @Test fun cancelDropsThePartAndOnlyAUserActionCan() {
        srv.put("/a.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        until { (e.job("a")?.bytes ?: 0L) > 0L }
        e.cancel("a", user)
        assertEquals(DlState.CANCELLED, e.job("a")!!.state)
        srv.hold!!.countDown()
        until { !File(cacheDir, "fleet-a-dl.apk.part").exists() }
        assertEquals(DlState.CANCELLED, e.job("a")!!.state)
    }

    @Test fun theQueueSurvivesARestartAndInterruptedJobsResumeFromTheirPart() {
        srv.put("/a.apk", body, "\"v1\"")
        srv.put("/b.apk", body)
        // What a killed process leaves behind: a RUNNING job, 120 000 bytes in the part, its ETag in the store, another job queued.
        File(cacheDir, "fleet-a-dl.apk.part").writeBytes(body.copyOfRange(0, 120_000))
        DownloadStore(File(dir, "queue.json")).save(listOf(
            DlJob("a", "p.a", 1L, "a", "fleet-a-", 0, true, 1L, state = DlState.RUNNING, bytes = 120_000, total = body.size.toLong(), etag = "\"v1\""),
            DlJob("b", "p.b", 1L, "b", "fleet-b-", 0, true, 2L, state = DlState.QUEUED),
        ))
        val e = engine(); e.start()
        assertEquals(setOf("a", "b"), e.state.value.map { it.id }.toSet())
        assertEquals(DlState.DONE, e.finish("a").state)
        assertEquals(DlState.DONE, e.finish("b").state)
        assertTrue("a resumed from byte 120000 with its stored validator", srv.log.any { it.contains("/a.apk range=bytes=120000- ifrange=\"v1\" -> 206") })
        assertEquals(FakeServer.sha(body), FakeServer.sha(doneFile(e.job("a")!!).readBytes()))
    }

    @Test fun aBackgroundJobTheHostHoldsBackComesBackPausedWithItsPart() {
        srv.put("/a.apk", body)
        File(cacheDir, "fleet-a-dl.apk.part").writeBytes(body.copyOfRange(0, 1000))
        DownloadStore(File(dir, "queue.json")).save(listOf(DlJob("a", "p.a", 1L, "a", "fleet-a-", 0, false, 1L, state = DlState.RUNNING, bytes = 1000)))
        val e = engine(); e.start { it.userStarted }
        assertEquals(DlState.PAUSED, e.job("a")!!.state)
        Thread.sleep(150)
        assertEquals("nothing was fetched", 0, srv.gets("/a.apk"))
        assertTrue(File(cacheDir, "fleet-a-dl.apk.part").exists())
    }

    @Test fun aFinishedBuildIsReusedFromTheContentAddressedCache() {
        srv.put("/a.apk", body)
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        val first = e.finish("a")
        assertEquals(1, srv.gets("/a.apk"))
        e.clearFinished()
        e.enqueue(spec("a"))
        val again = e.finish("a")
        assertEquals(DlState.DONE, again.state)
        assertEquals("the second ask hit the cache, no second GET", 1, srv.gets("/a.apk"))
        assertEquals(first.file, again.file)
        assertEquals("fleet-a-p.a-1-${FakeServer.sha(body)}.apk", again.file)
    }

    @Test fun pruningNeverDeletesAnActivePartOrAProtectedFile() {
        srv.put("/a.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(); e.start()
        e.enqueue(spec("a"))
        until { (e.job("a")?.bytes ?: 0L) > 0L }
        val old = File(cacheDir, "fleet-old-p.old-1-${"1".repeat(64)}.apk").also { it.writeBytes(ByteArray(10)); it.setLastModified(1_000L) }
        val pending = File(cacheDir, "fleet-pend-p.pend-1-${"2".repeat(64)}.apk").also { it.writeBytes(ByteArray(10)); it.setLastModified(1_000L) }
        val r = e.cache.prune(maxBytes = 0, maxAgeMs = 1, protect = { it.name == pending.name })
        assertTrue(File(cacheDir, "fleet-a-dl.apk.part").exists())
        assertTrue("an active part is never a candidate", r.deleted.none { it.endsWith(".part") })
        assertFalse("the old finished file went", old.exists())
        assertTrue("what the host protects stays", pending.exists())
        srv.hold!!.countDown()
        assertEquals(DlState.DONE, e.finish("a").state)
    }

    @Test fun pruningIsLeastRecentlyUsedFirstUntilTheSizeFits() {
        val c = DownloadCache(cacheDir)
        val names = (1..4).map { i -> File(cacheDir, "f$i.apk").also { it.writeBytes(ByteArray(100)); it.setLastModified(10_000L * i) } }
        val r = c.prune(maxBytes = 250, maxAgeMs = 0, protect = { false }, now = 100_000L)
        assertEquals(listOf("f1.apk", "f2.apk"), r.deleted)
        assertTrue(names[2].exists() && names[3].exists())
    }

    @Test fun anExplicitCancelAllStopsEverythingInFlight() {
        for (id in listOf("a", "b")) srv.put("/$id.apk", body)
        srv.hold = CountDownLatch(1)
        val e = engine(); e.start()
        e.enqueue(spec("a")); e.enqueue(spec("b"))
        until { srv.inFlight.get() == 2 }
        e.cancelAll(user)
        assertEquals(setOf(DlState.CANCELLED), e.state.value.map { it.state }.toSet())
        srv.hold!!.countDown()
        assertNotNull(e.job("a"))
    }
}
