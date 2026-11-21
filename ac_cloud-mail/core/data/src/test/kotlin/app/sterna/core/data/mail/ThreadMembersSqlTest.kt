package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, the selection semantics of
 */
class ThreadMembersSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE TABLE email_mailboxes(accountId TEXT, emailId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, emailId, mailboxId))")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, threadId: String?, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', 0, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setLong(5, sortKey)
            ps.executeUpdate()
        }
    }

    /**
     * The shipped `cachedThreadEmails`, run over [mailboxes] — Room expands `mailboxId IN
     */
    private fun cachedThreadEmails(accountId: String, mailboxes: List<String>, threadKey: String): List<String> {
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.emailDaoQuery("cachedThreadEmails"),
            mapOf("mailboxIds" to mailboxes.size),
        )
        var mailbox = 0
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setString(
                    i + 1,
                    when (name) {
                        "accountId" -> accountId
                        "mailboxIds" -> mailboxes[mailbox++]
                        "threadKey" -> threadKey
                        else -> error("Unexpected parameter ':$name' in EmailDao.cachedThreadEmails")
                    },
                )
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /** A single mailbox in the IN-list: the folder-scoped binding used by whole-thread swipe. */
    private fun members(accountId: String, mailbox: String, threadKey: String): List<String> =
        cachedThreadEmails(accountId, listOf(mailbox), threadKey)

    /**
     * The binding that populates an unfolded conversation: the viewed folder PLUS the account's
     */
    private fun membersViewPlusSent(accountId: String, mailbox: String, sent: String, threadKey: String): List<String> =
        cachedThreadEmails(accountId, listOf(mailbox, sent), threadKey)

    @Test fun returnsAllThreadMembersNewestFirstIncludingRepresentative() {
        insert("m1", threadId = "T1", sortKey = 100)
        insert("m2", threadId = "T1", sortKey = 300) // newest → representative
        insert("m3", threadId = "T1", sortKey = 200)
        insert("other", threadId = "T2", sortKey = 250) // different thread
        assertEquals(listOf("m2", "m3", "m1"), members("acc", "inbox", "T1"))
    }

    @Test fun excludesOtherAccountsSharingAMailboxAndThreadId() {
        insert("a1", threadId = "T1", sortKey = 100, accountId = "accA")
        insert("b1", threadId = "T1", sortKey = 200, accountId = "accB")
        assertEquals(listOf("a1"), members("accA", "inbox", "T1"))
    }

    @Test fun threadlessMessageMatchesByItsOwnIdAsKey() {
        insert("solo", threadId = null, sortKey = 100)
        insert("nope", threadId = null, sortKey = 200)
        assertEquals(listOf("solo"), members("acc", "inbox", "solo"))
    }

    @Test fun unfoldScopeIncludesSentRepliesButNeverOtherFoldersMembers() {
        insert("in1", threadId = "T1", sortKey = 100, mailbox = "inbox")
        insert("sent1", threadId = "T1", sortKey = 200, mailbox = "sent") // a reply, filed in Sent
        insert("tr1", threadId = "T1", sortKey = 300, mailbox = "trash") // deleted member
        insert("dr1", threadId = "T1", sortKey = 400, mailbox = "drafts") // unsent draft
        // The strictly folder-scoped binding (whole-thread swipe) sees only the inbox message…
        assertEquals(listOf("in1"), members("acc", "inbox", "T1"))
        // …while the unfold binding (view + Sent) interleaves the Sent reply, newest-first,
        // and keeps the trashed and draft members out: they belong to their own folder's
        // conversation (the Trash view's binding would list tr1 + sent1 instead).
        assertEquals(listOf("sent1", "in1"), membersViewPlusSent("acc", "inbox", "sent", "T1"))
        assertEquals(listOf("tr1", "sent1"), membersViewPlusSent("acc", "trash", "sent", "T1"))
    }
}
