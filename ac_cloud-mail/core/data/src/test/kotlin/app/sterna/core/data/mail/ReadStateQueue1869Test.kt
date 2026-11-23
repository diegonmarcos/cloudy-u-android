package app.sterna.core.data.mail

import app.sterna.core.data.filter.SourceText
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1869 step 11: the offline read-state queue. */
class ReadStateQueue1869Test {
    @get:Rule val tmp = TemporaryFolder()

    private fun file() = File(tmp.root, "pending-read-state.tsv")

    @Test fun `a queued change survives the process, the latest state wins`() {
        val q = ReadStateQueue(file())
        q.put("acc", "m1", true)
        q.put("acc", "m2", true)
        q.put("acc", "m1", false)
        val again = ReadStateQueue(file())
        assertEquals(listOf(ReadStateQueue.Entry("acc", "m2", true), ReadStateQueue.Entry("acc", "m1", false)), again.pending("acc"))
        assertEquals(2, again.size)
    }

    @Test fun `odd ids round-trip`() {
        val q = ReadStateQueue(file())
        q.put("a\tb", "INBOX/12\nx", true)
        assertEquals(ReadStateQueue.Entry("a\tb", "INBOX/12\nx", true), ReadStateQueue(file()).pending("a\tb").single())
    }

    @Test fun `offline the flush stops at the first failure and keeps everything, online it empties the queue`() = runBlocking {
        val q = ReadStateQueue(file())
        q.put("acc", "m1", true); q.put("acc", "m2", true); q.put("other", "m9", true)
        var online = false
        val sent = mutableListOf<String>()
        val send: suspend (ReadStateQueue.Entry) -> Unit = { e -> if (!online) error("offline"); sent += e.emailId }
        assertEquals(0, q.flush("acc", send))
        assertEquals(2, q.pending("acc").size)
        online = true
        assertEquals(2, q.flush("acc", send))
        assertEquals(listOf("m1", "m2"), sent)
        assertTrue(q.pending("acc").isEmpty())
        assertEquals("another account's changes wait for that account", 1, q.pending("other").size)
        assertTrue(ReadStateQueue(file()).pending("acc").isEmpty())
    }

    @Test fun `a toggle queued while the flush runs is not lost`() = runBlocking {
        val q = ReadStateQueue(file())
        q.put("acc", "m1", true)
        q.flush("acc") { q.put("acc", "m1", false) }
        assertEquals(listOf(ReadStateQueue.Entry("acc", "m1", false)), q.pending("acc"))
    }

    @Test fun `bounded, oldest out, and an account can be dropped`() {
        val q = ReadStateQueue(null, max = 3)
        (1..5).forEach { q.put("acc", "m$it", true) }
        assertEquals(listOf("m3", "m4", "m5"), q.pending("acc").map { it.emailId })
        q.dropAccount("acc")
        assertEquals(0, q.size)
    }

    @Test fun `the reader marks through the queue, the sync passes flush it first`() {
        val repo = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        val mark = repo.substringAfter("suspend fun markReadQueued(").substringBefore("suspend fun flushReadQueue(")
        val local = mark.indexOf("emailDao.setSeen(credentials.id, emailId, seen)")
        val queued = mark.indexOf("readQueue.put(credentials.id, emailId, seen)")
        val flush = mark.indexOf("flushReadQueue(credentials)")
        assertTrue("local first, then queued, then the server", local in 0 until queued && queued < flush)
        assertFalse("nothing in the local half reaches the network", mark.substring(0, queued).contains("connect("))
        assertEquals(2, Regex("if \\(markRead\\) bgScope\\.launch \\{ runCatching \\{ markReadQueued\\(credentials, emailId, true\\) \\} \\}").findAll(repo).count())
        val full = repo.substringAfter("suspend fun fullSync(").substringBefore("return syncSerial.withLock {")
        assertTrue(full.contains("runCatching { flushReadQueue(credentials) }"))
        val offline = repo.substringAfter("suspend fun syncOffline(").substringBefore("return syncSerial.withLock {")
        assertTrue(offline.contains("runCatching { flushReadQueue(credentials) }"))
        val vm = SourceText.read("app/src/main/kotlin/app/sterna/ui/message/MessageViewModel.kt")
        assertTrue(vm.contains("runCatching { repo.markReadQueued(credentials, current.id, true) }"))
        assertTrue(vm.contains("fun markUnread(onDone: () -> Unit) = act(onDone) { c, id -> repo.markReadQueued(c, id, false) }"))
    }
}
