package app.sterna.core.data.mail

import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1869 the cache read path: the list and the message are served from local data with the network stubbed out, both
 * failing and hanging. The behaviour half runs the pure read helpers; the wiring half pins that the list's paging
 * source and the reader's body read are Room reads that reach no client.
 */
class CacheReadPath1869Test {

    private var networkCalls = 0
    private val failingNetwork: suspend () -> String = { networkCalls++; error("network stubbed out") }
    private val hangingNetwork: suspend () -> String = { networkCalls++; awaitCancellation() }

    @Test fun `a stored message is served with the network failing, and the network is never asked`() = runTest {
        assertEquals("cached body", OfflineFirst.read(local = { "cached body" }, network = failingNetwork))
        assertEquals(0, networkCalls)
    }

    @Test fun `a stored message is served at once with the network hanging`() = runTest {
        val body = OfflineFirst.read(local = { "cached body" }, network = hangingNetwork)
        assertEquals("cached body", body)
        assertEquals("no virtual time passed: nothing waited", 0L, testScheduler.currentTime)
        assertEquals(0, networkCalls)
    }

    @Test fun `an unreadable stored row is a miss, never an exception on the open path`() = runTest {
        val read = readCachedOrPurge<String>(read = { error("Row too big to fit into CursorWindow") }, purge = {})
        assertEquals(null, read)
    }

    @Test fun `the list pages over Room and its mediator never refreshes on open`() {
        val paged = DaoQuerySource.mailFunctionBody("MailRepository", "pagedFolder")
        assertTrue("rows come from the Room paging source", "emailDao.pagingSource(" in paged && "emailDao.conversationPagingSource(" in paged)
        assertFalse("the list function itself reaches no client", "client." in paged || "connect(" in paged)
        val repo = DaoQuerySource.mailSource("MailRepository")
        assertTrue("SKIP_INITIAL_REFRESH: opening a folder costs no fetch", "override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH" in repo)
    }

    @Test fun `the reader's body read is Room plus a decode off the caller's thread, and nothing else`() {
        val read = DaoQuerySource.mailFunctionBody("MailRepository", "cachedMessage")
        assertTrue("emailBodyDao.byId(accountId, emailId)" in read)
        assertTrue("decoded on Default, not on the reader's Main", "withContext(Dispatchers.Default) {" in read)
        for (call in listOf("client.", "connect(", "openEmail(", "downloadAttachment(", "fetchInlineImages(")) {
            assertFalse("cachedMessage must not call $call", call in read)
        }
    }

    @Test fun `the inline-image prefetch never opens ciphertext`() {
        val repo = DaoQuerySource.mailSource("MailRepository")
        val missing = repo.substringAfter("override suspend fun missingInlineImages(limit: Int): List<String> {").substringBefore("override suspend fun fetchInlineImages(")
        assertTrue("encrypted bodies are skipped", "if (cryptoKindOf(email) != null || email.inlineImageParts().isEmpty()) continue" in missing)
        val fetch = repo.substringAfter("override suspend fun fetchInlineImages(emailId: String) {").substringBefore("private fun downloadableParts(")
        assertTrue("if (cryptoKindOf(cached.email) != null) return" in fetch)
        for (forbidden in listOf("decryptMessage(", "pgpEngine", "importAutocryptKey", "originalSendersOf(")) {
            assertFalse("the inline phase must not name $forbidden", forbidden in fetch || forbidden in missing)
        }
    }
}
