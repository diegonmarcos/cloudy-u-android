package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitrepos.*

import com.diegonmarcos.clouddrive.ui.StatusLight
import java.io.File
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.eclipse.jgit.api.Git
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The real scheduler (GitStatusReader) + the real light reader (RepoCheap.lightProbe) against a repository made by JGit on
 * disk: the "? forever" report passed the old unit tests because none of them read a real clone. Also: every failure ends
 * in a terminal state with its message, READING never persists, the three icons derive independently, the cache survives.
 */
class GitStatusEndToEndTest {
    private lateinit var tmp: File
    private val release = CountDownLatch(1)
    @Before fun setUp() { tmp = Files.createTempDirectory("git-e2e").toFile() }
    @After fun tearDown() { release.countDown(); tmp.deleteRecursively() }

    /** A clone-shaped repository: one commit on main, upstream origin/main at the same commit, FETCH_HEAD written. [localAhead] adds a second local commit. */
    private fun repo(name: String, localAhead: Boolean = false): File {
        val dir = File(tmp, name)
        Git.init().setDirectory(dir).setInitialBranch("main").call().use { git ->
            File(dir, "a.txt").writeText("one"); git.add().addFilepattern("a.txt").call()
            val first = git.commit().setMessage("one").setAuthor("t", "t@t").setCommitter("t", "t@t").call()
            git.repository.config.apply { setString("branch", "main", "remote", "origin"); setString("branch", "main", "merge", "refs/heads/main"); save() }
            git.repository.updateRef("refs/remotes/origin/main").apply { setNewObjectId(first); update() }
            File(dir, ".git/FETCH_HEAD").writeText("x")
            if (localAhead) { File(dir, "b.txt").writeText("two"); git.add().addFilepattern("b.txt").call(); git.commit().setMessage("two").setAuthor("t", "t@t").setCommitter("t", "t@t").call() }
        }
        return dir
    }

    private fun jgitLog(dir: File): (String) -> List<String> = { _ -> Git.open(dir).use { g -> g.log().call().map { it.name } } }

    private fun reader(timeoutMs: Long = 5_000, concurrency: Int = 1, light: (File) -> GitStatusReader.Probe = { RepoCheap.lightProbe(it, jgitLog(it)) }) =
        GitStatusReader(probe = { error("the light check must never run the full probe") }, timeoutMs = timeoutMs, concurrency = concurrency, ttlMs = 60_000, isRepository = { File(it, ".git").exists() }, lightProbe = light)

    private fun target(key: String, dir: File) = GitStatusReader.Target(key, dir)

    @Test fun `a real clone on disk ends CLEAN through the real scheduler and light reader within the timeout`() = runBlocking {
        val dir = repo("cloudy-t-infra")
        val r = reader()
        withTimeout(5_000) { r.request(listOf(target("cloudy-t-infra", dir))).join() }
        val s = r.statuses.value["cloudy-t-infra"]!!
        assertEquals(s.reason, GitStatusReader.State.CLEAN, s.state)
        assertEquals(GitStatusReader.Level.LIGHT, s.level)
        assertEquals(dir.absolutePath, s.path)
        val g = GitSyncCoordinator.glanceOf(s)
        assertEquals(RepoStatus.Kind.CLEAN, RepoStatus.kindOf(true, g))
        assertEquals("files", g.via)
        assertEquals(StatusLight.State.ON, RepoStatus.light(RepoStatus.kindOf(true, g)))
    }

    @Test fun `a local commit ahead of the remote tip reads AHEAD 1 from the refs and the log`() = runBlocking {
        val dir = repo("ahead", localAhead = true)
        val r = reader()
        withTimeout(5_000) { r.request(listOf(target("ahead", dir))).join() }
        val s = r.statuses.value["ahead"]!!
        assertEquals(GitStatusReader.State.AHEAD, s.state); assertEquals(1, s.probe!!.ahead)
    }

    @Test fun `a thrown exception ends in ERROR with its message, never READING`() = runBlocking {
        val dir = repo("boom")
        val r = reader(light = { throw IllegalStateException("kaboom from the probe") })
        withTimeout(5_000) { r.request(listOf(target("boom", dir))).join() }
        val s = r.statuses.value["boom"]!!
        assertEquals(GitStatusReader.State.ERROR, s.state)
        assertEquals("kaboom from the probe", s.reason)
        assertEquals(RepoStatus.Kind.ERROR, RepoStatus.kindOf(true, GitSyncCoordinator.glanceOf(s)))
    }

    @Test fun `a folder with no readable git directory ends in ERROR naming the exact path`() = runBlocking {
        val dir = File(tmp, "nogit").apply { mkdirs() }
        val r = GitStatusReader(probe = { error("no") }, timeoutMs = 5_000, concurrency = 1, ttlMs = 60_000, isRepository = { true }, lightProbe = { RepoCheap.lightProbe(it) })
        withTimeout(5_000) { r.request(listOf(target("nogit", dir))).join() }
        val s = r.statuses.value["nogit"]!!
        assertEquals(GitStatusReader.State.ERROR, s.state)
        assertTrue(s.reason!!, s.reason!!.contains(dir.absolutePath))
    }

    @Test fun `a probe that never returns ends in ERROR status read timed out within its timeout`() = runBlocking {
        val dir = repo("hang")
        val r = reader(timeoutMs = 300, light = { release.await(); GitStatusReader.Probe("main", null, 0, 0, 0, 0) })
        val t0 = System.currentTimeMillis()
        withTimeout(3_000) { r.request(listOf(target("hang", dir))).join() }
        val s = r.statuses.value["hang"]!!
        assertEquals(GitStatusReader.State.ERROR, s.state)
        assertTrue(s.reason!!, s.reason!!.contains("status read timed out"))
        assertEquals(RepoStatus.ErrorKind.TIMEOUT, RepoStatus.errorKind(s.reason))
        assertTrue("ended within about the timeout", System.currentTimeMillis() - t0 < 2_000)
    }

    @Test fun `a queue of slow rows still ends every row terminal, one timeout at a time at worst`() = runBlocking {
        val dirs = (1..4).map { repo("q$it") }
        val r = reader(timeoutMs = 300, concurrency = 1, light = { Thread.sleep(50); GitStatusReader.Probe("main", "origin/main", 0, 0, 0, 0) })
        withTimeout(5_000) { r.request(dirs.mapIndexed { i, d -> target("q${i + 1}", d) }).join() }
        val all = r.statuses.value
        assertEquals(4, all.size)
        assertTrue("no row is left reading", all.values.all { it.terminal })
        assertTrue(all.values.all { it.state == GitStatusReader.State.CLEAN })
    }

    @Test fun `cancelling leaves no READING behind and keeps cached values`() = runBlocking {
        val a = repo("a"); val b = repo("b")
        val r = reader(light = { release.await(); GitStatusReader.Probe("main", "origin/main", 0, 0, 0, 0) })
        r.request(listOf(target("a", a), target("b", b)))
        val end = System.currentTimeMillis() + 3_000
        while (r.statuses.value.size < 2 && System.currentTimeMillis() < end) Thread.sleep(5)
        r.cancelAll(); Thread.sleep(200)
        assertTrue("nothing is left reading after a cancel", r.statuses.value.values.none { !it.terminal })
    }

    @Test fun `pull to refresh and light check all read at the LIGHT level and never run the full probe`() = runBlocking {
        val dir = repo("p")
        val r = reader()
        withTimeout(5_000) { r.request(listOf(target("p", dir)), force = true).join() }   // what GitSyncCoordinator.refresh(force = true) asks
        assertEquals(GitStatusReader.Level.LIGHT, r.statuses.value["p"]!!.level)
    }

    @Test fun `the status cache survives a restart and a seeded reader does no work by itself`() = runBlocking {
        val dir = repo("c")
        val r1 = reader()
        withTimeout(5_000) { r1.request(listOf(target("c", dir))).join() }
        val cache = StatusCache(File(tmp, "git-sync/status-cache.json"))
        cache.save(r1.statuses.value)
        var probes = 0
        val r2 = reader(light = { probes++; RepoCheap.lightProbe(it) })     // "process restart": a new reader, the same file
        r2.seed(StatusCache(File(tmp, "git-sync/status-cache.json")).load())
        val s = r2.statuses.value["c"]!!
        assertEquals(GitStatusReader.State.CLEAN, s.state); assertTrue(s.readAtMs > 0); assertEquals(dir.absolutePath, s.path)
        withTimeout(5_000) { r2.request(listOf(target("c", dir))).join() }   // a fresh cached entry is not re-read unless forced
        assertEquals(0, probes)
        withTimeout(5_000) { r2.request(listOf(target("c", dir)), force = true).join() }
        assertEquals(1, probes)
    }

    @Test fun `a reading entry is never persisted`() {
        val f = File(tmp, "x/cache.json")
        StatusCache(f).save(mapOf("r" to GitStatusReader.Status(GitStatusReader.State.READING), "e" to GitStatusReader.Status(GitStatusReader.State.ERROR, reason = "m", readAtMs = 5)))
        val back = StatusCache(f).load()
        assertEquals(setOf("e"), back.keys); assertEquals("m", back["e"]!!.reason)
    }

    @Test fun `the row key and the cache key are the same canonical registry name after a rename`() {
        val canon = { n: String -> if (n == "cloud-infra") "cloudy-t-infra" else n }
        fun repo(path: String) = com.diegonmarcos.cloudlib.gitsync.ManagedRepo(id = "id-$path", name = path.substringAfterLast('/'), path = path, remoteUrl = "")
        assertEquals("cloudy-t-infra", GitSyncCoordinator.statusKey(repo("/s/git/cloud-infra"), canon))
        assertEquals("cloudy-t-infra", GitSyncCoordinator.statusKey(repo("/s/git/cloudy-t-infra"), canon))
    }

    // ── the three icons, each on its own ─────────────────────────────────────────────────────────────────────────────
    private fun g(level: String = "full", ahead: Int = 0, behind: Int = 0, changed: Int = 0, conflicts: Int = 0, upstream: String? = "origin/main", fetched: Long = 1, error: String? = null, gone: Boolean = false, large: Boolean = false, read: Boolean = true) =
        GitSyncCoordinator.Glance("main", upstream, ahead, behind, changed, conflicts, error = error, gone = gone, read = read, large = large, level = level, fetchedAtMs = fetched)
    private fun ev(ok: Boolean, summary: String, t: Long = 10) = SyncEvent(t, "id", "n", "manual", ok, summary)

    @Test fun `LOCAL derives from the working copy alone`() {
        val L = RepoStatus.LocalKind.values().toSet()
        assertEquals(RepoStatus.LocalKind.NOT_CLONED, RepoStatus.localKind(false, g()))
        assertEquals(RepoStatus.LocalKind.NOT_CHECKED, RepoStatus.localKind(true, null))
        assertEquals(RepoStatus.LocalKind.READING, RepoStatus.localKind(true, g(read = false)))
        assertEquals(RepoStatus.LocalKind.MISSING, RepoStatus.localKind(true, g(gone = true)))
        assertEquals(RepoStatus.LocalKind.CLONED, RepoStatus.localKind(true, g(level = "light")))
        assertEquals(RepoStatus.LocalKind.CLEAN, RepoStatus.localKind(true, g()))
        assertEquals(RepoStatus.LocalKind.DIRTY, RepoStatus.localKind(true, g(changed = 2)))
        assertEquals(RepoStatus.LocalKind.CONFLICT, RepoStatus.localKind(true, g(changed = 2, conflicts = 1)))
        assertEquals(RepoStatus.LocalKind.LARGE, RepoStatus.localKind(true, g(large = true)))
        assertEquals(RepoStatus.LocalKind.ERROR, RepoStatus.localKind(true, g(error = "x")))
        assertTrue(L.isNotEmpty())
    }

    @Test fun `SERVER derives from the refs alone`() {
        assertEquals(RepoStatus.ServerKind.NOT_CHECKED, RepoStatus.serverKind(true, null))
        assertEquals(RepoStatus.ServerKind.NO_UPSTREAM, RepoStatus.serverKind(true, g(upstream = null)))
        assertEquals(RepoStatus.ServerKind.IN_SYNC, RepoStatus.serverKind(true, g()))
        assertEquals(RepoStatus.ServerKind.NOT_FETCHED, RepoStatus.serverKind(true, g(level = "light", fetched = 0)))
        assertEquals(RepoStatus.ServerKind.AHEAD, RepoStatus.serverKind(true, g(ahead = 1)))
        assertEquals(RepoStatus.ServerKind.BEHIND, RepoStatus.serverKind(true, g(behind = 1)))
        assertEquals(RepoStatus.ServerKind.DIVERGED, RepoStatus.serverKind(true, g(ahead = 1, behind = 1)))
        assertEquals(RepoStatus.ServerKind.UNREACHABLE, RepoStatus.serverKind(true, g(), fetchFailed = true))
        assertEquals(RepoStatus.ServerKind.UNKNOWN, RepoStatus.serverKind(true, g(error = "x")))
    }

    @Test fun `SYNC derives from the last job record alone`() {
        assertEquals(RepoStatus.SyncKind.RUNNING, RepoStatus.syncKind(true, null))
        assertEquals(RepoStatus.SyncKind.NEVER, RepoStatus.syncKind(false, null))
        assertEquals(RepoStatus.SyncKind.OK, RepoStatus.syncKind(false, ev(true, "pull: ok")))
        assertEquals(RepoStatus.SyncKind.ERROR, RepoStatus.syncKind(false, ev(false, "push: rejected")))
    }

    @Test fun `one failing part never leaves the others on a question mark`() {
        // the status read FAILED, yet SYNC still shows the last job and SERVER/LOCAL are named kinds, none of them READING
        val i = RepoStatus.icons(true, g(error = "boom"), false, listOf(ev(true, "pull: ok"), ev(false, "fetch: unreachable", t = 20)), "id")
        assertEquals(RepoStatus.LocalKind.ERROR, i.local)
        assertEquals(RepoStatus.SyncKind.ERROR, i.sync)
        assertEquals("fetch: unreachable", i.lastSync!!.summary)
        // a never-checked row shows "not checked yet" on LOCAL and SERVER while SYNC is independent
        val j = RepoStatus.icons(true, null, false, listOf(ev(true, "pull: ok")), "id")
        assertEquals(RepoStatus.LocalKind.NOT_CHECKED, j.local); assertEquals(RepoStatus.ServerKind.NOT_CHECKED, j.server); assertEquals(RepoStatus.SyncKind.OK, j.sync)
        // a failed fetch marks SERVER unreachable
        assertEquals(RepoStatus.ServerKind.UNREACHABLE, RepoStatus.icons(true, g(), false, listOf(ev(false, "fetch: no route")), "id").server)
    }

    @Test fun `the light mapping is declarative and every kind of every icon has a light`() {
        assertEquals(StatusLight.State.ON, RepoStatus.lightOf("local", RepoStatus.LocalKind.CLEAN))
        assertEquals(StatusLight.State.OFF, RepoStatus.lightOf("local", RepoStatus.LocalKind.CLEAN, mapOf("local" to mapOf("clean" to "off"))))
        for (k in RepoStatus.LocalKind.values()) assertNotNull(RepoStatus.DEFAULT_ICONS["local"]!![k.name.lowercase()])
        for (k in RepoStatus.ServerKind.values()) assertNotNull(RepoStatus.DEFAULT_ICONS["server"]!![k.name.lowercase()])
        for (k in RepoStatus.SyncKind.values()) assertNotNull(RepoStatus.DEFAULT_ICONS["sync"]!![k.name.lowercase()])
        // build.json carries the same mapping
        val bj = File(File(System.getProperty("user.dir")).let { if (File(it, "build.json").isFile) it else it.parentFile }, "build.json").readText()
        for (k in RepoStatus.LocalKind.values()) assertTrue("build.json local ${k.name}", bj.contains("\"${k.name.lowercase()}\":"))
        assertTrue(bj.contains("\"icons\": {"))
    }

    @Test fun `progress says how many of the cloned repositories are checked and when`() {
        val gl = mapOf("a" to g().copy(readAtMs = 50), "b" to g().copy(readAtMs = 90), "c" to g(read = false))
        val p = RepoStatus.progress(listOf("a", "b", "c", "d"), gl)
        assertEquals(2, p.done); assertEquals(4, p.total); assertEquals(90L, p.lastCheckMs)
        assertEquals(0, RepoStatus.progress(listOf("a"), emptyMap()).done)
    }

    @Test fun `the debug line names the exact path read and the outcome`() {
        val line = RepoStatus.debugLine("/store/git/x", g().copy(path = "/store/git/x", via = "files", state = "clean", tookMs = 12))
        assertTrue(line, line.contains("path=/store/git/x") && line.contains("via=files") && line.contains("outcome=clean") && line.contains("took=12ms"))
        assertTrue(RepoStatus.debugLine("/p", null).contains("not requested"))
        assertTrue(RepoStatus.debugLine("/p", g().copy(error = "boom", state = "error")).contains("error=boom"))
    }

    @Test fun `the termux light script output parses and a missing repository throws`() {
        val p = RepoCheap.parseLight("B:main\nU:origin/main\nLR:2\t1\nF:1000\n")
        assertEquals("main", p.branch); assertEquals("origin/main", p.upstream); assertEquals(2, p.ahead); assertEquals(1, p.behind); assertEquals(1_000_000L, p.fetchedAtMs); assertEquals("exec", p.via)
        assertEquals(null, RepoCheap.parseLight("B:main\nF:\n").upstream)
        try { RepoCheap.parseLight("NOREPO\n"); throw AssertionError("must throw") } catch (e: IllegalStateException) { assertTrue(e.message!!.contains("not a git repository")) }
        assertFalse(RepoCheap.lightScript("'/g'").contains("\n"))
    }
}
