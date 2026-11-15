package app.sterna.core.data.mail

import app.sterna.core.jmap.model.Mailbox
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A mailbox renamed on the server (JMAP `Mailbox/set` update of `name`, same id) must show its NEW
 * name in the app after the next folder refresh (#1751): the cache may never keep a name the server
 * dropped. Every refresh path re-reads `Mailbox/get` in full and writes it through `replaceAll`,
 * whose upsert overwrites `name` for an existing id.
 */
class MailboxRenameRefreshTest {

    @Test fun `a rename keeps the id and the cache row takes the server's new name`() {
        val before = Mailbox(id = "i9", name = "01 Inbox - Alerts").toEntity("acct")
        val after = Mailbox(id = "i9", name = "01_Alerts").toEntity("acct")
        assertEquals("same primary key (accountId, id), so an upsert overwrites the row", before.id, after.id)
        assertEquals("01_Alerts", after.name)
    }

    @Test fun `the folder cache is written by upsert, never insert-ignore, and replaceAll upserts first`() {
        val dao = daoSource()
        assertTrue(
            "upsertAll must be @Upsert: @Insert(IGNORE) would freeze the first name ever seen",
            Regex("""@Upsert\s+suspend fun upsertAll""").containsMatchIn(dao),
        )
        val replaceAll = dao.substringAfter("suspend fun replaceAll")
        assertTrue("replaceAll must call upsertAll before evicting", replaceAll.trimStart().contains("upsertAll(mailboxes)"))
    }

    @Test fun `every JMAP folder refresh rewrites the whole list from a fresh Mailbox get`() {
        val repo = File(root, "core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt").readText()
        val refresh = repo.substringAfter("private suspend fun refreshMailboxes(credentials: AccountCredentials, onlySubscribed: Boolean)")
            .substringBefore("/** The account's archive folder id")
        assertTrue(refresh.contains("client.getMailboxes("))
        assertTrue(refresh.contains("mailboxDao.replaceAll(credentials.id, client.getMailboxes("))
    }

    private fun daoSource() = File(root, "core/data/src/main/kotlin/app/sterna/core/data/db/MailboxDao.kt").readText()

    private companion object {
        val root: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "core/data/src/main/kotlin/app/sterna/core/data/db/MailboxDao.kt").isFile }
                ?: error("cannot locate the cloud-mail root from ${File("").absolutePath}")
        }
    }
}
