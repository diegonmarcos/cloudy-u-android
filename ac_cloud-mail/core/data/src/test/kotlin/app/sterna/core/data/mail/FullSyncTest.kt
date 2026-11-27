package app.sterna.core.data.mail

import app.sterna.core.data.db.BodyCacheRow
import app.sterna.core.data.settings.attachmentPolicyFrom
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import app.sterna.core.jmap.model.Email
import app.sterna.core.jmap.model.EmailHeader
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1768: the background full sync engine, its throttle, attachment policy, caps and the key-import rule. */
class FullSyncTest {

    private val day = 86_400_000L
    private val now = 1_000L * day
    private val wifi = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)
    private val metered = PrefetchConditions(connected = true, unmetered = false, batterySaver = false)
    private val saver = PrefetchConditions(connected = true, unmetered = true, batterySaver = true)
    private val offline = PrefetchConditions(connected = false, unmetered = false, batterySaver = false)

    private val inbox = FullSyncFolder("inbox", inbox = true)
    private val archive = FullSyncFolder("archive", inbox = false)
    private val junk = FullSyncFolder("junk", inbox = false)

    /** Folders with `n` messages each (newest first ids), a header chunk size of 100, bodies/attachments in memory. */
    private class FakePort(
        val folders: List<FullSyncFolder>,
        val messages: Map<String, Int>,
        /** how many header pages each folder still needs to reach the window's far edge */
        var pagesNeeded: Int = 0,
        val attachments: Int = 0,
        val attachmentBytes: Long = 1_000,
    ) : FullSyncPort {
        val calls = mutableListOf<String>()
        val bodyStore = mutableSetOf<String>()
        val bodyBatches = mutableListOf<Pair<String, List<String>>>()
        val attachmentStore = mutableListOf<AttachmentJob>()
        var bodyBytesNow = 0L
        var attachmentBytesNow = 0L
        var bytesPerBody = 100L
        var failBodiesFor: String? = null
        var storeNothing = false
        var onFetch: () -> Unit = {}
        var evictions = 0
        val backfillPages = mutableMapOf<String, Int>()

        override suspend fun folders() = folders.also { calls += "folders" }
        override suspend fun syncHeaders(folder: FullSyncFolder) { calls += "headers:${folder.id}" }
        override suspend fun backfillHeaders(folder: FullSyncFolder, cutoffMillis: Long?): Boolean {
            val n = (backfillPages[folder.id] ?: 0) + 1
            backfillPages[folder.id] = n
            calls += "backfill:${folder.id}"
            return n >= pagesNeeded
        }
        override suspend fun missingBodies(folder: FullSyncFolder, limit: Int) =
            (1..(messages[folder.id] ?: 0)).map { "${folder.id}-$it" }.filter { it !in bodyStore }.take(limit)
        override suspend fun fetchBodies(folder: FullSyncFolder, ids: List<String>) {
            if (failBodiesFor == folder.id) error("server said no")
            calls += "bodies:${folder.id}"
            bodyBatches += folder.id to ids
            if (!storeNothing) bodyStore += ids
            bodyBytesNow += ids.size * bytesPerBody
            onFetch()
        }
        override suspend fun missingAttachments(limit: Int) =
            (1..attachments).map { AttachmentJob("m$it", "blob$it", attachmentBytes) }.filter { it !in attachmentStore }.take(limit)
        override suspend fun fetchAttachment(job: AttachmentJob) {
            calls += "attachment"
            attachmentStore += job
            attachmentBytesNow += job.bytes
        }
        override suspend fun bodyBytes() = bodyBytesNow
        override suspend fun attachmentBytes() = attachmentBytesNow
        override suspend fun evict(): Int { evictions++; return 0 }
    }

    private fun engine(port: FakePort, c: () -> PrefetchConditions = { wifi }, policy: AttachmentPolicy = AttachmentPolicy.WIFI_ONLY,
                       bodyCap: Long = BODY_CACHE_MAX_BYTES_PER_ACCOUNT, attachCap: Long = ATTACHMENT_CACHE_MAX_BYTES_PER_ACCOUNT,
                       stop: () -> Boolean = { false }) =
        FullSync(port, c, policy, bodyCap, attachCap, stop)

    // -- schedule and backoff ------------------------------------------------------------------------------------

    @Test fun `the job runs every 15 minutes without push and relaxes with push, which triggers it itself`() {
        assertEquals(15L, FullSyncSchedule.periodMinutes(hasPush = false))
        assertTrue("with push the state change triggers the run; the period is only a safety net",
            FullSyncSchedule.periodMinutes(hasPush = true) > 15L)
        assertTrue(FullSyncSchedule.BACKOFF_SECONDS >= 10)
        assertTrue(FullSyncSchedule.MAX_ATTEMPTS in 3..20)
    }

    // -- incremental ---------------------------------------------------------------------------------------------

    @Test fun `a second run on a synced window does no body, backfill or attachment fetch, only the incremental header check`() = runTest {
        val port = FakePort(listOf(inbox, archive), mapOf("inbox" to 30, "archive" to 10), pagesNeeded = 1, attachments = 2)
        val first = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(40, first.bodies)
        assertEquals(2, first.attachments)
        port.calls.clear()
        port.bodyBatches.clear()
        val second = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(0, second.bodies)
        assertEquals(0, second.attachments)
        assertTrue("no Email/get for bodies, no attachment download", port.bodyBatches.isEmpty() && "attachment" !in port.calls)
        assertEquals("headers are checked incrementally per folder, inbox first", listOf("headers:inbox", "backfill:inbox", "headers:archive", "backfill:archive"),
            port.calls.filter { it.startsWith("headers") || it.startsWith("backfill") })
        assertFalse(second.morePending)
    }

    @Test fun `new mail after a run is the only body fetched by the next run`() = runTest {
        val messages = mutableMapOf("inbox" to 20)
        val port = FakePort(listOf(inbox), messages)
        engine(port).run(OfflineWindow.THREE, now)
        port.bodyBatches.clear()
        // two new messages arrive (the incremental header sync wrote their rows)
        val grown = FakePort(listOf(inbox), mapOf("inbox" to 22)).also { it.bodyStore += port.bodyStore }
        engine(grown).run(OfflineWindow.THREE, now)
        assertEquals(listOf("inbox-21", "inbox-22"), grown.bodyBatches.single().second)
    }

    // -- the full window across chunks ----------------------------------------------------------------------------

    @Test fun `the whole window is filled across chunks, newest first, every folder, inbox first`() = runTest {
        val port = FakePort(listOf(archive, junk, inbox), mapOf("inbox" to 60, "archive" to 30, "junk" to 5), pagesNeeded = 3)
        val result = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(95, result.bodies)
        assertEquals("every message of every folder has its body", 95, port.bodyStore.size)
        assertEquals("inbox first", "inbox", port.bodyBatches.first().first)
        assertEquals("batched Email/get, 25 a call, newest first", listOf(25, 25, 10), port.bodyBatches.filter { it.first == "inbox" }.map { it.second.size })
        assertEquals((1..60).map { "inbox-$it" }, port.bodyBatches.filter { it.first == "inbox" }.flatMap { it.second })
        assertEquals("headers: 3 chunks in each of the 3 folders", 9, result.headerPages)
        assertFalse(result.morePending)
    }

    @Test fun `a window longer than one run's page budget continues in the next run`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 5), pagesNeeded = FULL_SYNC_PAGES_PER_FOLDER + 5)
        val first = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(FULL_SYNC_PAGES_PER_FOLDER, first.headerPages)
        assertTrue("the far edge is not reached: a continuation is owed", first.morePending)
        val second = engine(port).run(OfflineWindow.THREE, now)
        assertFalse("the second run reaches the edge", second.morePending)
    }

    @Test fun `a run that spends its body budget asks for a continuation and the next run finishes the window`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to PREFETCH_PASS_MAX_IDS + 50))
        val first = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(PREFETCH_PASS_MAX_IDS, first.bodies)
        assertEquals(FullSyncStop.BUDGET, first.stop)
        assertTrue(first.morePending)
        val second = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(50, second.bodies)
        assertFalse(second.morePending)
    }

    // -- throttle ------------------------------------------------------------------------------------------------

    @Test fun `no network and battery saver do nothing at all`() = runTest {
        for (c in listOf(offline, saver)) {
            val port = FakePort(listOf(inbox), mapOf("inbox" to 10), attachments = 1)
            val r = engine(port, { c }, AttachmentPolicy.ALWAYS).run(OfflineWindow.THREE, now)
            assertEquals(emptyList<String>(), port.calls)
            assertEquals(if (c.connected) FullSyncStop.BATTERY_SAVER else FullSyncStop.OFFLINE, r.stop)
            assertFalse("the periodic run and the connectivity trigger come back by themselves", r.morePending)
        }
    }

    @Test fun `battery saver switched on mid-run stops the rest`() = runTest {
        var c = wifi
        val port = FakePort(listOf(inbox), mapOf("inbox" to 100))
        port.onFetch = { c = saver }
        val r = engine(port, { c }).run(OfflineWindow.THREE, now)
        assertEquals("one batch only", 25, r.bodies)
        assertEquals(FullSyncStop.BATTERY_SAVER, r.stop)
    }

    // #1869 step 9: a metered run now fills a TEXT-only window of FULL_SYNC_METERED_TEXT_PER_RUN (it was one batch of
    // PREFETCH_METERED_MAX_IDS); 100 missing bodies are all fetched, still with no backfill, no attachments, no chain.
    @Test fun `a metered link takes the incremental headers and a text-only window, with no continuation loop`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 100), pagesNeeded = 4, attachments = 3)
        val r = engine(port, { metered }).run(OfflineWindow.THREE, now)
        assertEquals(minOf(100, FULL_SYNC_METERED_TEXT_PER_RUN), r.bodies)
        assertEquals("#1952 a metered link backfills its bounded pages", FULL_SYNC_PAGES_PER_FOLDER_METERED, r.headerPages)
        assertFalse("everything landed: nothing is chained", r.morePending)
        assertEquals("Wi-Fi only default: no attachments on a metered link", 0, r.attachments)
    }

    @Test fun `a stop request ends the run at the next step`() = runTest {
        var stop = false
        val port = FakePort(listOf(inbox), mapOf("inbox" to 100))
        port.onFetch = { stop = true }
        val r = engine(port, stop = { stop }).run(OfflineWindow.THREE, now)
        assertEquals(25, r.bodies)
        assertEquals(FullSyncStop.STOPPED, r.stop)
    }

    // -- failures ------------------------------------------------------------------------------------------------

    @Test fun `a failing folder is counted for the backoff and the other folders still sync`() = runTest {
        val port = FakePort(listOf(inbox, archive), mapOf("inbox" to 5, "archive" to 5))
        port.failBodiesFor = "inbox"
        val r = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(1, r.errors)
        assertEquals(5, r.bodies)
        assertTrue(port.bodyStore.all { it.startsWith("archive") })
    }

    @Test fun `a body that is never stored does not make the run loop`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 3))
        port.storeNothing = true // e.g. ciphertext the inbox path declines to cache
        val r = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(1, port.bodyBatches.size)
        assertEquals(3, r.bodies)
    }

    // -- attachments by policy ----------------------------------------------------------------------------------

    @Test fun `the policy table`() {
        assertTrue(attachmentsAllowed(AttachmentPolicy.ALWAYS, wifi))
        assertTrue(attachmentsAllowed(AttachmentPolicy.ALWAYS, metered))
        assertTrue(attachmentsAllowed(AttachmentPolicy.WIFI_ONLY, wifi))
        assertFalse(attachmentsAllowed(AttachmentPolicy.WIFI_ONLY, metered))
        for (c in listOf(wifi, metered)) assertFalse(attachmentsAllowed(AttachmentPolicy.OFF, c))
        for (p in AttachmentPolicy.entries) {
            assertFalse("$p with no network", attachmentsAllowed(p, offline))
            assertFalse("$p under battery saver", attachmentsAllowed(p, saver))
        }
    }

    @Test fun `Wi-Fi only downloads attachments on Wi-Fi and none on a metered link`() = runTest {
        val onWifi = FakePort(listOf(inbox), mapOf("inbox" to 3), attachments = 4)
        assertEquals(4, engine(onWifi, { wifi }, AttachmentPolicy.WIFI_ONLY).run(OfflineWindow.THREE, now).attachments)
        val onMobile = FakePort(listOf(inbox), mapOf("inbox" to 3), attachments = 4)
        assertEquals(0, engine(onMobile, { metered }, AttachmentPolicy.WIFI_ONLY).run(OfflineWindow.THREE, now).attachments)
        assertTrue(onMobile.attachmentStore.isEmpty())
    }

    @Test fun `Always downloads on a metered link too, in a smaller run`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 3), attachments = 50)
        val r = engine(port, { metered }, AttachmentPolicy.ALWAYS).run(OfflineWindow.THREE, now)
        assertEquals(FULL_SYNC_ATTACHMENTS_PER_RUN_METERED, r.attachments)
    }

    @Test fun `Off downloads no attachment in the background, whatever the link`() = runTest {
        for (c in listOf(wifi, metered)) {
            val port = FakePort(listOf(inbox), mapOf("inbox" to 3), attachments = 4)
            val r = engine(port, { c }, AttachmentPolicy.OFF).run(OfflineWindow.THREE, now)
            assertEquals(0, r.attachments)
            assertTrue("bodies are still kept", r.bodies > 0 || c === metered)
            assertTrue(port.attachmentStore.isEmpty())
        }
    }

    // -- storage cap and eviction ---------------------------------------------------------------------------------

    @Test fun `the body fill stops at the cap and fetches nothing once it is reached`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 500))
        port.bytesPerBody = 100
        // cap 10 000 bytes: the fill ceiling is 9 000 = 90 bodies, reached after four batches of 25 (100 bodies)
        val r = engine(port, bodyCap = 10_000).run(OfflineWindow.THREE, now)
        assertEquals(FullSyncStop.CAP, r.stop)
        assertEquals(100, r.bodies)
        assertFalse("a capped window is not chased by continuations", r.morePending)
        port.bodyBatches.clear()
        engine(port, bodyCap = 10_000).run(OfflineWindow.THREE, now)
        assertTrue("already at the cap: no fetch", port.bodyBatches.isEmpty())
    }

    @Test fun `the attachment fill stops at its cap`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 1), attachments = 100, attachmentBytes = 1_000)
        val r = engine(port, attachCap = 10_000).run(OfflineWindow.THREE, now) // ceiling 9 000: nine files
        assertEquals(9, r.attachments)
        assertEquals(FullSyncStop.CAP, r.stop)
    }

    @Test fun `eviction runs before and after the fill, and the cap trims the oldest first while the fill leaves room`() = runTest {
        val port = FakePort(listOf(inbox), mapOf("inbox" to 3))
        engine(port).run(OfflineWindow.THREE, now)
        assertEquals(2, port.evictions)
        // the fill ceiling is below the cap, so a run that stopped at the ceiling never feeds the eviction (no churn)
        assertTrue(FILL_CEILING_PERCENT < 100)
        val rows = listOf(BodyCacheRow("new", now - day, 500), BodyCacheRow("old", now - 80 * day, 500), BodyCacheRow("gone", now - 120 * day, 500))
        assertEquals("outside the window goes, then the oldest past the cap",
            listOf("gone", "old"), planBodyEviction(rows, now - 90 * day, maxBytes = 600))
        assertEquals("the same plan trims attachment files past their cap",
            listOf("old"), planBodyEviction(rows.take(2), null, maxBytes = 600))
    }

    // -- settings ------------------------------------------------------------------------------------------------

    @Test fun `Download attachments defaults to Wi-Fi only`() {
        assertEquals(AttachmentPolicy.WIFI_ONLY, AttachmentPolicy.DEFAULT)
        assertEquals(AttachmentPolicy.WIFI_ONLY, AttachmentPolicy.from(null))
        assertEquals(AttachmentPolicy.WIFI_ONLY, AttachmentPolicy.from("garbage"))
        assertEquals(AttachmentPolicy.WIFI_ONLY, attachmentPolicyFrom(emptyPreferences()))
        val stored = mutablePreferencesOf()
        stored[stringPreferencesKey("offline_attachment_policy")] = "OFF"
        assertEquals(AttachmentPolicy.OFF, attachmentPolicyFrom(stored))
        assertEquals(listOf("ALWAYS", "WIFI_ONLY", "OFF"), AttachmentPolicy.entries.map { it.name })
    }

    // -- encrypted mail outside the inbox ---------------------------------------------------------------------------

    @Test fun `a non-inbox body is stored raw, ciphertext included, and nothing in the store path can import a key`() = runTest {
        val emails = listOf(Email(id = "enc1", subject = "secret"), Email(id = "plain1", subject = "hi"))
        val headers = mapOf(
            "enc1" to listOf(EmailHeader("Autocrypt", "addr=spoof@example.org; keydata=AAAA"), EmailHeader("X-Original-Sender", "a@b.c")),
            "plain1" to emptyList(),
        )
        val stored = mutableListOf<Email>()
        val n = storeRawBodies(emails, headers, originOf = { h -> h.firstOrNull { it.name == "X-Original-Sender" }?.value }) { stored += it; true }
        assertEquals(2, n)
        assertEquals(listOf("enc1", "plain1"), stored.map { it.id })
        assertEquals("the origin header rides along", "a@b.c", stored[0].originalSender)
        assertNull(stored[1].originalSender)
        // The structure is the guarantee: storeRawBodies takes no key-import callback and no engine at all.
        val src = DaoQuerySource.mailSource("FullSync")
        val fn = src.substringAfter("internal suspend fun storeRawBodies(")
        assertFalse("importAutocryptKey" in fn || "PgpEngine" in fn || "updateAutocryptPeer" in fn)
    }

    @Test fun `the raw fetch and the folder routing never reach OpenKeychain for a folder that is not the inbox`() {
        val raw = DaoQuerySource.mailFunctionBody("MailRepository", "fetchAndStoreRawBodies")
        for (forbidden in listOf("importAutocryptKey", "originalSendersOf", "pgpEngine", "updateAutocryptPeer", "importAutocryptPeer")) {
            assertFalse("the raw path must not name $forbidden", forbidden in raw)
        }
        assertTrue("it stores through storeRawBodies", "storeRawBodies(" in raw)
        val repo = DaoQuerySource.mailSource("MailRepository")
        assertTrue(
            "only an inbox folder takes the inbox path (key handling pinned elsewhere); every other folder is stored raw",
            "if (folder.inbox) fetchAndStoreBodies(credentials, ids) else fetchAndStoreRawBodies(credentials, ids)" in repo,
        )
        assertEquals(
            "the inbox flag comes from the server's role, nothing else",
            1, Regex("""FullSyncFolder\(it\.id, inbox = it\.role == "inbox"\)""").findAll(repo).count(),
        )
        // the engine itself never names keys, so a third path cannot be added there
        val engineSrc = DaoQuerySource.mailSource("FullSync").substringBefore("internal suspend fun storeRawBodies(")
        assertFalse("importAutocryptKey" in engineSrc || "PgpEngine" in engineSrc)
    }

    // -- the open path ---------------------------------------------------------------------------------------------

    @Test fun `app open renders list and bodies from the cache with no network call for synced mail`() {
        // the list: Paging over Room, the mediator does not refresh on its own
        val repo = DaoQuerySource.mailSource("MailRepository")
        assertTrue("the folder mediator skips the initial refresh: the list is Room only until the user scrolls past it",
            "override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH" in repo)
        // the body: the cache branch of openMessage never reaches the network
        val open = DaoQuerySource.mailFunctionBody("MailRepository", "openMessage")
        val cached = open.indexOf("cachedMessage(credentials.id, emailId)?.let")
        val network = open.indexOf("openEmail(credentials, emailId, markRead)")
        assertTrue(cached in 1 until network)
        val hit = open.substring(cached, network)
        for (call in listOf("connect(", "client.", "openEmail(", "fetchInlineImages(", "downloadAttachment(")) {
            assertFalse("a stored body opens without $call", call in hit.replace("bgScope.launch { runCatching { ensureInlineImages(", ""))
        }
    }
}
