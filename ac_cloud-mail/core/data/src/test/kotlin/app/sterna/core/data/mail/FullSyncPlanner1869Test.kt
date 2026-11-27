package app.sterna.core.data.mail

import app.sterna.core.data.account.StoredAccount
import app.sterna.core.data.account.SyncWindow
import app.sterna.core.data.account.syncWindowChoices
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1869 the background sync planner ([FullSync]) that keeps the last 90 days local: the window it walks, the deltas of
 * a warm cache, resuming after the job is killed, the per-run budgets, and the inline-image phase that lets the reader
 * paint a message's images from disk.
 */
class FullSyncPlanner1869Test {

    private val day = 86_400_000L
    private val now = 2_000L * day
    private val wifi = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)
    private val metered = PrefetchConditions(connected = true, unmetered = false, batterySaver = false)
    private val saver = PrefetchConditions(connected = true, unmetered = true, batterySaver = true)

    private val inbox = FullSyncFolder("inbox", inbox = true)
    private val archive = FullSyncFolder("archive", inbox = false)

    /** In-memory mailbox: [messages] per folder (id `<folder>-<n>`, n = 1 newest), [inlineOf] ids carrying inline images. */
    private class Port(
        val folders: List<FullSyncFolder>,
        val messages: MutableMap<String, Int>,
        val inlineOf: Set<String> = emptySet(),
        var edgeAfterPages: Int = 1,
    ) : FullSyncPort {
        val calls = mutableListOf<String>()
        val bodies = mutableSetOf<String>()
        val fetchedBodies = mutableListOf<String>()
        val inlineStored = mutableSetOf<String>()
        val cutoffs = mutableListOf<Long?>()
        var bodyBytesNow = 0L
        var onBodies: () -> Unit = {}
        private val pages = mutableMapOf<String, Int>()

        override suspend fun folders() = folders
        override suspend fun syncHeaders(folder: FullSyncFolder) { calls += "delta:${folder.id}" }
        override suspend fun backfillHeaders(folder: FullSyncFolder, cutoffMillis: Long?): Boolean {
            cutoffs += cutoffMillis
            val n = (pages[folder.id] ?: 0) + 1
            pages[folder.id] = n
            return n >= edgeAfterPages
        }
        override suspend fun missingBodies(folder: FullSyncFolder, limit: Int) =
            (1..(messages[folder.id] ?: 0)).map { "${folder.id}-$it" }.filter { it !in bodies }.take(limit)
        override suspend fun fetchBodies(folder: FullSyncFolder, ids: List<String>) {
            calls += "bodies"
            bodies += ids
            fetchedBodies += ids
            bodyBytesNow += ids.size * 10L
            onBodies()
        }
        override suspend fun missingAttachments(limit: Int) = emptyList<AttachmentJob>()
        override suspend fun fetchAttachment(job: AttachmentJob) {}
        override suspend fun missingInlineImages(limit: Int) =
            bodies.filter { it in inlineOf && it !in inlineStored }.sortedBy { it.substringAfter('-').toInt() }.take(limit)
        override suspend fun fetchInlineImages(emailId: String) {
            calls += "inline"
            inlineStored += emailId
            bodyBytesNow += 10L
        }
        override suspend fun bodyBytes() = bodyBytesNow
        override suspend fun attachmentBytes() = 0L
        override suspend fun evict() = 0
    }

    private fun engine(port: Port, c: () -> PrefetchConditions = { wifi }, stop: () -> Boolean = { false }, bodyCap: Long = BODY_CACHE_MAX_BYTES_PER_ACCOUNT) =
        FullSync(port, c, AttachmentPolicy.WIFI_ONLY, bodyCapBytes = bodyCap, stopRequested = stop)

    // -- the 90-day window --------------------------------------------------------------------------------------

    @Test fun `the declared window defaults to every message and the 90-day choice stops the walk at exactly 90 days back`() = runTest {
        assertEquals("#1952 fully cached", OfflineWindow.ALL, OfflineWindow.DEFAULT)
        assertEquals(90, OfflineWindow.THREE.days)
        assertEquals("an unknown or missing stored value reads as the default, never less", OfflineWindow.DEFAULT, OfflineWindow.from(null))
        assertEquals(OfflineWindow.DEFAULT, OfflineWindow.from("NOT_A_WINDOW"))
        assertEquals("the per-account window defaults to 90 days too", SyncWindow.DAYS_90, StoredAccount(id = "a", server = "s", username = "u").syncWindow)
        assertTrue("and the picker still offers it", SyncWindow.DAYS_90 in syncWindowChoices())
        val port = Port(listOf(inbox, archive), mutableMapOf("inbox" to 3, "archive" to 2))
        engine(port).run(OfflineWindow.THREE, now)
        assertEquals("every folder's backfill is bounded by now - 90 days", listOf(now - 90 * day, now - 90 * day), port.cutoffs)
    }

    @Test fun `the keep-offline picker keeps every choice it had`() {
        assertEquals(listOf("ONE", "THREE", "SIX", "TWELVE", "ALL"), OfflineWindow.entries.map { it.name })
        assertEquals(listOf(30, 90, 180, 365, 0), OfflineWindow.entries.map { it.days })
    }

    // -- deltas ---------------------------------------------------------------------------------------------------

    @Test fun `a warm cache costs one delta per folder and fetches only what arrived since`() = runTest {
        val messages = mutableMapOf("inbox" to 10, "archive" to 4)
        val port = Port(listOf(inbox, archive), messages)
        engine(port).run(OfflineWindow.THREE, now)
        assertEquals(14, port.fetchedBodies.size)
        port.calls.clear(); port.fetchedBodies.clear()
        val idle = engine(port).run(OfflineWindow.THREE, now)
        assertEquals("nothing new: deltas only", listOf("delta:inbox", "delta:archive"), port.calls)
        assertEquals(0, idle.bodies)
        messages["inbox"] = 12 // the delta wrote two new header rows
        port.calls.clear()
        val next = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(listOf("inbox-11", "inbox-12"), port.fetchedBodies)
        assertEquals(2, next.bodies)
    }

    // -- resume after kill ------------------------------------------------------------------------------------------

    @Test fun `a run killed mid-window resumes where it stopped, refetches nothing, and completes the window`() = runTest {
        val port = Port(listOf(inbox, archive), mutableMapOf("inbox" to 60, "archive" to 30))
        var killed = false
        port.onBodies = { killed = true }
        val first = engine(port, stop = { killed }).run(OfflineWindow.THREE, now)
        assertEquals(FullSyncStop.STOPPED, first.stop)
        assertEquals("one batch landed before the kill", PREFETCH_BATCH, port.fetchedBodies.size)
        port.onBodies = {}
        val second = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(90 - PREFETCH_BATCH, second.bodies)
        assertEquals("no body was fetched twice", port.fetchedBodies.size, port.fetchedBodies.toSet().size)
        assertEquals(90, port.bodies.size)
        assertEquals("newest first across the resume", (1..60).map { "inbox-$it" }, port.fetchedBodies.filter { it.startsWith("inbox") })
        assertFalse(second.morePending)
    }

    // -- budgets ------------------------------------------------------------------------------------------------------

    @Test fun `an unmetered run spends its body budget and chains a continuation that finishes the window`() = runTest {
        val port = Port(listOf(inbox), mutableMapOf("inbox" to PREFETCH_PASS_MAX_IDS + 30))
        val first = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(PREFETCH_PASS_MAX_IDS, first.bodies)
        assertTrue(first.morePending)
        val second = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(30, second.bodies)
        assertFalse(second.morePending)
    }

    @Test fun `a metered run takes a text-only window and the bounded header backfill, no inline images`() = runTest {
        val port = Port(listOf(inbox), mutableMapOf("inbox" to 100), inlineOf = setOf("inbox-1"), edgeAfterPages = 3)
        val r = engine(port, { metered }).run(OfflineWindow.THREE, now)
        assertEquals(minOf(100, FULL_SYNC_METERED_TEXT_PER_RUN), r.bodies)
        assertEquals("#1952 the header backfill runs on a metered link too, up to its far edge", 3, r.headerPages)
        assertEquals(0, r.inlineImages)
        assertFalse("nothing is left: no continuation", r.morePending)
    }

    // -- inline images ------------------------------------------------------------------------------------------------

    @Test fun `inline images of the stored bodies are fetched after the bodies, newest first`() = runTest {
        val port = Port(listOf(inbox, archive), mutableMapOf("inbox" to 5, "archive" to 3), inlineOf = setOf("inbox-2", "archive-1", "inbox-4"))
        val r = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(3, r.inlineImages)
        assertEquals(setOf("inbox-2", "archive-1", "inbox-4"), port.inlineStored)
        assertTrue("after every body", port.calls.lastIndexOf("bodies") < port.calls.indexOf("inline"))
        port.calls.clear()
        val again = engine(port).run(OfflineWindow.THREE, now)
        assertEquals("stored once, never refetched", 0, again.inlineImages)
        assertFalse("inline" in port.calls)
    }

    @Test fun `the inline budget is per run and a continuation finishes it`() = runTest {
        val n = FULL_SYNC_INLINE_PER_RUN + 7
        val port = Port(listOf(inbox), mutableMapOf("inbox" to n), inlineOf = (1..n).map { "inbox-$it" }.toSet())
        val first = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(FULL_SYNC_INLINE_PER_RUN, first.inlineImages)
        assertTrue(first.morePending)
        val second = engine(port).run(OfflineWindow.THREE, now)
        assertEquals(7, second.inlineImages)
        assertFalse(second.morePending)
    }

    @Test fun `no inline image on battery saver or when the body cap is reached`() = runTest {
        assertFalse(inlineImagesAllowed(saver))
        assertFalse(inlineImagesAllowed(metered))
        assertTrue(inlineImagesAllowed(wifi))
        val port = Port(listOf(inbox), mutableMapOf("inbox" to 9), inlineOf = setOf("inbox-1"))
        // the bodies alone fill the cap: 9 bodies x 10 bytes = 90 = 90% of a 100-byte cap
        val r = engine(port, bodyCap = 100).run(OfflineWindow.THREE, now)
        assertEquals(9, r.bodies)
        assertEquals(0, r.inlineImages)
        assertEquals(FullSyncStop.CAP, r.stop)
    }

    @Test fun `a stop request ends the inline phase too`() = runTest {
        var stop = false
        val port = Port(listOf(inbox), mutableMapOf("inbox" to 3), inlineOf = setOf("inbox-1", "inbox-2", "inbox-3"))
        val killing = object : FullSyncPort by port {
            override suspend fun fetchInlineImages(emailId: String) { port.fetchInlineImages(emailId); stop = true }
        }
        val r = FullSync(killing, { wifi }, AttachmentPolicy.WIFI_ONLY, stopRequested = { stop }).run(OfflineWindow.THREE, now)
        assertEquals(1, r.inlineImages)
        assertEquals(FullSyncStop.STOPPED, r.stop)
    }
}
