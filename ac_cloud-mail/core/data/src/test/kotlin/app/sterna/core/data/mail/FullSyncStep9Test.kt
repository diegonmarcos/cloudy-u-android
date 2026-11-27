package app.sterna.core.data.mail

import app.sterna.core.data.filter.SourceText
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1869 step 9: the planner fixes (metered text window, calendar parts, local ciphertext, inline state, IMAP batch,
 *  serial sync passes, local mailbox membership). */
class FullSyncStep9Test {
    private val wifi = PrefetchConditions(connected = true, unmetered = true, batterySaver = false)
    private val metered = PrefetchConditions(connected = true, unmetered = false, batterySaver = false)
    private val saver = PrefetchConditions(connected = true, unmetered = true, batterySaver = true)
    private val now = 1_800_000_000_000L
    private val inbox = FullSyncFolder("inbox", inbox = true)

    private class Port(val n: Int, val calendar: List<AttachmentJob> = emptyList(), val sealed: List<String> = emptyList()) : FullSyncPort {
        val bodies = mutableListOf<List<String>>()
        val calendarStored = mutableListOf<AttachmentJob>()
        val sealedStored = mutableListOf<String>()
        var attachmentBytesNow = 0L
        override suspend fun folders() = listOf(FullSyncFolder("inbox", inbox = true))
        override suspend fun syncHeaders(folder: FullSyncFolder) {}
        override suspend fun backfillHeaders(folder: FullSyncFolder, cutoffMillis: Long?) = true
        override suspend fun missingBodies(folder: FullSyncFolder, limit: Int) =
            (1..n).map { "m$it" }.filter { id -> bodies.none { id in it } }.take(limit)
        override suspend fun fetchBodies(folder: FullSyncFolder, ids: List<String>) { bodies += ids }
        override suspend fun missingAttachments(limit: Int) = emptyList<AttachmentJob>()
        override suspend fun fetchAttachment(job: AttachmentJob) {}
        override suspend fun missingCalendarParts(limit: Int) = calendar.filter { it !in calendarStored }.take(limit)
        override suspend fun fetchCalendarPart(job: AttachmentJob) { calendarStored += job; attachmentBytesNow += job.bytes }
        override suspend fun missingCiphertext(limit: Int) = sealed.filter { it !in sealedStored }.take(limit)
        override suspend fun fetchCiphertext(emailId: String) { sealedStored += emailId }
        override suspend fun bodyBytes() = 0L
        override suspend fun attachmentBytes() = attachmentBytesNow
        override suspend fun evict() = 0
    }

    private fun engine(port: Port, c: () -> PrefetchConditions, policy: AttachmentPolicy = AttachmentPolicy.OFF, attachCap: Long = ATTACHMENT_CACHE_MAX_BYTES_PER_ACCOUNT) =
        FullSync(port, c, policy, attachmentCapBytes = attachCap)

    @Test fun `a metered run fetches a text window of about 200, serially, in batches, no chain`() = runTest {
        val port = Port(500)
        val r = engine(port, { metered }).run(OfflineWindow.THREE, now)
        assertEquals(FULL_SYNC_METERED_TEXT_PER_RUN, r.bodies)
        assertEquals(200, FULL_SYNC_METERED_TEXT_PER_RUN)
        assertTrue(port.bodies.all { it.size <= PREFETCH_BATCH })
        assertEquals(FullSyncStop.BUDGET, r.stop)
        assertTrue("#1952 any link: 300 bodies are still missing, the run chains a continuation", r.morePending)
        assertEquals(PrefetchBudget(200, PREFETCH_BATCH, 1), fullSyncBodyBudget(metered))
        assertEquals("unmetered keeps the list-sync throttle", prefetchBudget(wifi, PrefetchTrigger.LIST_SYNC), fullSyncBodyBudget(wifi))
        assertFalse("battery saver: nothing", fullSyncBodyBudget(saver).allowed)
    }

    @Test fun `calendar parts are stored on any link and under any policy, bounded per run and in size`() = runTest {
        val jobs = (1..60).map { AttachmentJob("c$it", "ics$it", 2_000) } + AttachmentJob("big", "ics-big", FULL_SYNC_CALENDAR_MAX_BYTES + 1)
        val port = Port(0, calendar = jobs)
        val r = engine(port, { metered }, policy = AttachmentPolicy.OFF).run(OfflineWindow.THREE, now)
        assertEquals(FULL_SYNC_CALENDAR_PER_RUN, r.calendarParts)
        assertTrue(port.calendarStored.none { it.emailId == "big" })
        assertEquals("nothing else is downloaded with the policy OFF", 0, r.attachments)
    }

    @Test fun `calendar parts and ciphertext respect the attachments cap and battery saver`() = runTest {
        val port = Port(0, calendar = listOf(AttachmentJob("c", "ics", 10)), sealed = listOf("s1"))
        port.attachmentBytesNow = ATTACHMENT_CACHE_MAX_BYTES_PER_ACCOUNT
        val full = engine(port, { wifi }).run(OfflineWindow.THREE, now)
        assertEquals(0, full.calendarParts)
        assertEquals(0, full.ciphertexts)
        val port2 = Port(0, calendar = listOf(AttachmentJob("c", "ics", 10)), sealed = listOf("s1"))
        val saved = engine(port2, { saver }).run(OfflineWindow.THREE, now)
        assertEquals(0, saved.calendarParts + saved.ciphertexts)
    }

    @Test fun `stored ciphertext keeps its raw source locally, bounded per run`() = runTest {
        val port = Port(0, sealed = (1..70).map { "s$it" })
        val r = engine(port, { metered }).run(OfflineWindow.THREE, now)
        assertEquals(FULL_SYNC_CIPHERTEXT_PER_RUN, r.ciphertexts)
        val again = engine(port, { metered }).run(OfflineWindow.THREE, now)
        assertEquals(20, again.ciphertexts)
    }

    @Test fun `the repository wiring - inline state, local ciphertext, IMAP batch, serial passes, local membership`() {
        val repo = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val missing = repo.substringAfter("override suspend fun missingInlineImages(limit: Int): List<String> {").substringBefore("override suspend fun fetchInlineImages(")
        assertTrue("the work list is inlineState, not a LIKE scan", missing.contains("emailBodyDao.idsAwaitingInlineImages("))
        assertFalse(missing.contains("idsMissingInlineImages("))
        val fetch = repo.substringAfter("override suspend fun fetchInlineImages(emailId: String) {").substringBefore("override suspend fun missingCalendarParts(")
        assertTrue("an image that could not be stored no longer starves the budget", fetch.contains("InlineState.FAILED_PERMANENT"))
        val raw = repo.substringAfter("private suspend fun fetchRawSource(").substringBefore("private fun localCiphertextFile(")
        assertTrue("every fetched source is offered to the local keep", raw.contains("keepLocalCiphertext(credentials.id, emailId, raw)"))
        val decrypt = repo.substringAfter("suspend fun decryptMessage(").substringBefore("private fun pgpAttachmentBytes(")
        assertTrue("the decrypt reads the local ciphertext before any network", decrypt.contains("val raw = runCatching { localRaw ?: fetchRawSource(credentials, email, emailId) }"))
        assertTrue(decrypt.contains("?: localRaw?.let { cachedEmail(credentials.id, emailId) }"))
        val keep = repo.substringAfter("private fun keepLocalCiphertext(").substringBefore("    /**")
        assertTrue("only ciphertext is written", keep.contains("if (runCatching { MimeParser.detectCrypto(raw) }.getOrNull() == null) return"))
        assertTrue(repo.contains("val batched = prefetchImapSources(credentials, ids)"))
        assertTrue(repo.contains("batched.forEach { (mailboxId, uids) -> imap.dropPrefetchedSources(credentials, mailboxId, uids) }"))
        assertTrue(repo.contains("return syncSerial.withLock { FullSync(port, conditions, policy, bodyCapBytes = bodyCacheLimit.bytes, stopRequested = stopRequested).run(window, nowMillis) }"))
        assertTrue(repo.contains("syncSerial.withLock { syncMailCoreSerial(credentials, budgetMs) }"))
        assertTrue(repo.contains("syncSerial.withLock { OfflineSync(offlinePort(credentials)).run(window, limit, nowMillis) }"))
        val membership = repo.substringAfter("suspend fun localMailboxMembership(").substringBefore("suspend fun mailboxMembership(")
        assertFalse("local membership makes no call", membership.contains("connect(") || membership.contains("client."))
        val imap = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/ImapMailService.kt")
        val batch = imap.substringAfter("suspend fun prefetchSources(").substringBefore("fun dropPrefetchedSources(")
        assertEquals("one session for the whole batch", 1, Regex("onMailbox\\(").findAll(batch).count())
        val fetchSource = imap.substringAfter("    suspend fun fetchSource(").substringBefore("    /**")
        assertTrue("a batched source never answers a read that opposes a numbering",
            fetchSource.contains("if (expectedUidValidity == null) prefetchedSources.remove(sourceKey(credentials, mailboxId, uid))?.let { return it }"))
        val vm = SourceText.read("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        assertTrue(vm.contains("refreshMailboxIds(local = true)"))
    }
}
