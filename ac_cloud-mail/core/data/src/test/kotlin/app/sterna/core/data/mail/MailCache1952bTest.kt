package app.sterna.core.data.mail

import androidx.datastore.preferences.core.mutablePreferencesOf
import app.sterna.core.data.db.BodyCacheRow
import app.sterna.core.data.settings.bodyCacheLimitFrom
import app.sterna.core.data.settings.writeBodyCacheLimit
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1952 fully cached: the body cap and its eviction order, the day window, the two settings, the resume after a restart and
 * the cold-start order. Mutations: workflow mail-cache-1952b-mutation (each leg names the test here that must fail).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MailCache1952bTest {
    private val day = 86_400_000L
    private val now = 1_000L * day
    private val gb = 1024L * 1024 * 1024
    private val wifi = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)
    private val metered = PrefetchConditions(connected = true, unmetered = false, batterySaver = false)

    /** four bodies of 1 GB each, m1 the oldest */
    private val rows = (1..4).map { BodyCacheRow("m$it", sortKey = now - (500 - it * 100) * day, bytes = gb) }

    // -- the cap and the eviction order ---------------------------------------------------------------------------

    @Test fun `past the cap the oldest bodies go first and the newest stay`() {
        assertEquals("4 GB under a 3 GB cap: the one oldest body goes", listOf("m1"), planBodyEviction(rows, cutoffMillis = null, maxBytes = BodyCacheLimit.GB_3.bytes))
        assertEquals(listOf("m1", "m2", "m3"), planBodyEviction(rows, cutoffMillis = null, maxBytes = BodyCacheLimit.GB_1.bytes))
        assertEquals("under the cap nothing goes", emptyList<String>(), planBodyEviction(rows, cutoffMillis = null, maxBytes = BodyCacheLimit.GB_5.bytes))
    }

    @Test fun `shrinking the limit evicts at once`() {
        assertEquals(emptyList<String>(), planBodyEviction(rows, null, BodyCacheLimit.GB_10.bytes))
        assertEquals("10 GB -> 2 GB: the two oldest go", listOf("m1", "m2"), planBodyEviction(rows, null, BodyCacheLimit.GB_2.bytes))
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue("the eviction reads the setting", "val drop = planBodyEviction(emailBodyDao.bodyRows(accountId), cutoff, bodyCacheLimit.bytes)" in repo)
        assertTrue("the fill stops at the setting", repo.any { "FullSync(port, conditions, policy, bodyCapBytes = bodyCacheLimit.bytes, stopRequested = stopRequested)" in it })
        val app = code("app/src/main/kotlin/app/sterna/SternaApplication.kt")
        assertTrue(
            "a changed limit is applied live",
            "settingsRepository.bodyCacheLimit.collect { mailRepository.bodyCacheLimit = it; if (!first) mailRepository.applyCacheLimits(); first = false }" in app,
        )
        assertTrue(
            "a changed window is applied live",
            "settingsRepository.offlineWindow.collect { mailRepository.offlineWindow = it; if (!first) mailRepository.applyCacheLimits(); first = false }" in app,
        )
        // headers are never evicted: the eviction deletes from the body table and the attachment files only
        val evict = repo.dropWhile { !it.startsWith("suspend fun evictBodies(") }.takeWhile { !it.startsWith("}.getOrDefault(0)") }
        assertTrue(evict.isNotEmpty() && evict.none { "emailDao.delete" in it })
    }

    @Test fun `a day window excludes older bodies and All keeps every one`() {
        assertEquals("All: no age limit", emptyList<String>(), planBodyEviction(rows, OfflineWindow.ALL.cutoffMillis(now), 100 * gb))
        // m1 400 days old, m2 300, m3 200, m4 100
        assertEquals(listOf("m1"), planBodyEviction(rows, OfflineWindow.TWELVE.cutoffMillis(now), 100 * gb))
        assertEquals(listOf("m1", "m2", "m3"), planBodyEviction(rows, OfflineWindow.SIX.cutoffMillis(now), 100 * gb))
        assertEquals(listOf("m1", "m2", "m3", "m4"), planBodyEviction(rows, OfflineWindow.THREE.cutoffMillis(now), 100 * gb))
        assertEquals(listOf(30, 90, 180, 365, 0), OfflineWindow.entries.map { it.days })
    }

    // -- the settings ---------------------------------------------------------------------------------------------

    @Test fun `the body cache limit round-trips through the settings store`() {
        val prefs = mutablePreferencesOf()
        assertEquals("nothing stored: the declared default", BodyCacheLimit.DEFAULT, bodyCacheLimitFrom(prefs))
        for (limit in BodyCacheLimit.entries) {
            writeBodyCacheLimit(prefs, limit)
            assertEquals(limit, bodyCacheLimitFrom(prefs))
        }
        assertEquals(BodyCacheLimit.DEFAULT, BodyCacheLimit.from("NOT_A_LIMIT"))
        for (w in OfflineWindow.entries) assertEquals(w, OfflineWindow.from(w.name))
        val settings = code("core/data/src/main/kotlin/app/sterna/core/data/settings/SettingsRepository.kt")
        assertTrue("dataStore.edit { writeBodyCacheLimit(it, limit) }" in settings)
        assertTrue("dataStore.data.map(::bodyCacheLimitFrom)" in settings)
    }

    @Test fun `the defaults and the choices are the declaration's`() {
        val decl = repoFile("build.json").readText().substringAfter("\"mail_cache\": {").substringBefore("\n  },")
        fun declared(key: String) = Regex("\"$key\": (\"[^\"]*\"|[a-z0-9_]+)").find(decl)?.groupValues?.get(1)?.trim('"') ?: error("build.json::mail_cache has no $key")
        assertEquals(declared("cap_mb").toInt(), BodyCacheLimit.DEFAULT.mb)
        assertEquals(declared("cap_mb").toLong() * 1024 * 1024, BODY_CACHE_MAX_BYTES_PER_ACCOUNT)
        assertEquals(declared("cap_choices_mb"), BodyCacheLimit.entries.joinToString(",") { it.mb.toString() })
        assertEquals(declared("default_window_days").toInt(), OfflineWindow.DEFAULT.days)
        assertEquals(declared("window_choices_days").split(",").map { it.toInt() }.sorted(), OfflineWindow.entries.map { it.days }.sorted())
        assertEquals("oldest_first", declared("eviction"))
        assertEquals("false", declared("headers_evicted"))
        assertEquals("all", declared("other_folders"))
        assertEquals(declared("other_folders_pages_per_run_metered").toInt(), FULL_SYNC_PAGES_PER_FOLDER_METERED)
        assertEquals(declared("prefetch_concurrency").toInt(), PREFETCH_MAX_CONCURRENCY)
        assertEquals(declared("cold_start_paint_wait_ms").toLong(), COLD_START_PAINT_WAIT_MS)
    }

    // -- resume after a restart -----------------------------------------------------------------------------------

    private class Store(val total: Int) : FullSyncPort {
        val bodies = LinkedHashSet<String>()
        val fetched = mutableListOf<String>()
        var stopAfterBatches = Int.MAX_VALUE
        var batches = 0
        val stopped: Boolean get() = batches >= stopAfterBatches
        override suspend fun folders() = listOf(FullSyncFolder("inbox", inbox = true))
        override suspend fun syncHeaders(folder: FullSyncFolder) {}
        override suspend fun backfillHeaders(folder: FullSyncFolder, cutoffMillis: Long?) = true
        // newest first: the highest number is the newest
        override suspend fun missingBodies(folder: FullSyncFolder, limit: Int) = (total downTo 1).map { "m$it" }.filter { it !in bodies }.take(limit)
        override suspend fun fetchBodies(folder: FullSyncFolder, ids: List<String>) { bodies += ids; fetched += ids; batches++ }
        override suspend fun missingAttachments(limit: Int) = emptyList<AttachmentJob>()
        override suspend fun fetchAttachment(job: AttachmentJob) {}
        override suspend fun bodyBytes() = bodies.size * 1_000L
        override suspend fun attachmentBytes() = 0L
        override suspend fun evict() = 0
    }

    @Test fun `a run stopped by a restart resumes where it stopped and fetches nothing twice`() = runTest {
        val store = Store(total = 200)
        store.stopAfterBatches = 2
        val first = FullSync(store, { wifi }, AttachmentPolicy.OFF, stopRequested = { store.stopped }).run(OfflineWindow.ALL, now)
        assertEquals(FullSyncStop.STOPPED, first.stop)
        assertEquals("two batches landed before the process went", 2 * PREFETCH_BATCH, store.bodies.size)
        assertEquals("newest first", (200 downTo 151).map { "m$it" }, store.fetched)
        // the restart: a NEW engine over the same store (nothing but the store survives)
        store.stopAfterBatches = Int.MAX_VALUE
        val second = FullSync(store, { wifi }, AttachmentPolicy.OFF).run(OfflineWindow.ALL, now)
        assertEquals("only what the store lacked", 150, second.bodies)
        assertEquals("no body fetched twice", store.fetched.size, store.fetched.toSet().size)
        assertEquals("the resume continues at the next older message", "m150", store.fetched[50])
        assertEquals(200, store.bodies.size)
        assertFalse(second.morePending)
    }

    @Test fun `the header backfill resumes after the oldest header held, never from zero`() {
        assertEquals(HeaderResume("m-oldest", 1), HeaderResume.after("m-oldest"))
        assertEquals("an empty folder is the only read from the top", HeaderResume(null, 0), HeaderResume.after(null))
        assertEquals(1_200, HeaderResume.positionWithoutAnchor(1_200))
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue("val resume = HeaderResume.after(emailDao.oldestRepresentativeEmailId(credentials.id, folder.id))" in repo)
    }

    @Test fun `the background fill continues on any connection`() = runTest {
        val store = Store(total = 500)
        val r = FullSync(store, { metered }, AttachmentPolicy.OFF).run(OfflineWindow.ALL, now)
        assertEquals(FULL_SYNC_METERED_TEXT_PER_RUN, r.bodies)
        assertTrue("bodies are still missing: a continuation is chained on a metered link too", r.morePending)
        assertEquals("bounded concurrency", 2, PREFETCH_MAX_CONCURRENCY)
        assertEquals(1, fullSyncBodyBudget(metered).concurrency)
    }

    // -- cold start: rows from the store before the first server call ---------------------------------------------

    @Test fun `on a cold start the first network call waits for the painted rows`() = runTest {
        val log = mutableListOf<String>()
        val gate = ColdStartGate(log = { log += it.substringBefore(" ms=") }, now = { 0L })
        gate.arm()
        val order = mutableListOf<String>()
        launch { gate.awaitRows(); gate.network("connect"); order += "network" }
        runCurrent()
        advanceTimeBy(COLD_START_PAINT_WAIT_MS / 2)
        assertEquals("no server call while the rows are not painted", emptyList<String>(), order)
        order += "rows"; gate.rowsPainted()
        runCurrent()
        assertEquals(listOf("rows", "network"), order)
        assertEquals(listOf("cold-start armed", "cold-start rows-painted", "cold-start first-network what=connect after-rows=true"), log)
    }

    @Test fun `an empty store does not hold the first sync and a worker without the activity never waits`() = runTest {
        val gate = ColdStartGate()
        gate.arm()
        var ran = false
        launch { gate.awaitRows(); ran = true }
        advanceTimeBy(COLD_START_PAINT_WAIT_MS - 1); runCurrent()
        assertFalse(ran)
        advanceTimeBy(2); runCurrent()
        assertTrue("nothing to paint: the sync starts after the bounded wait", ran)
        val unarmed = ColdStartGate()
        var worker = false
        launch { unarmed.awaitRows(); worker = true }
        runCurrent()
        assertTrue(worker)
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val connect = repo.dropWhile { !it.startsWith("private suspend fun connect(credentials: AccountCredentials): Context {") }.take(4)
        assertTrue("every server connection of the repository goes through the gate: $connect", connect.any { it.startsWith("ColdStart.awaitRows()") })
        assertTrue(code("app/src/main/kotlin/app/sterna/MainActivity.kt").any { it.startsWith("app.sterna.core.data.mail.ColdStart.arm()") })
        assertTrue("app.sterna.core.data.mail.ColdStart.rowsPainted()" in code("app/src/main/kotlin/app/sterna/ui/inbox/CacheBanner.kt"))
    }

    private fun code(path: String): List<String> =
        repoFile(path).readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    private fun repoFile(path: String): File =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.firstOrNull { it.isFile }
            ?: error("cannot find $path from ${File("").absolutePath}")
}
