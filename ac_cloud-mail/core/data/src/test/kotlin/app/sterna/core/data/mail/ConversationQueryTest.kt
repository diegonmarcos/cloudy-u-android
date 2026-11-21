package app.sterna.core.data.mail

import androidx.sqlite.db.SupportSQLiteProgram
import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The chip's query AS THE APP BUILDS IT — statement and binds together.
 */
class ConversationQueryTest {
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
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, PRIMARY KEY(accountId, emailId))")
            st.executeUpdate("CREATE TABLE email_mailboxes(accountId TEXT, emailId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, emailId, mailboxId))")
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(id: String, sortKey: Long, mailbox: String, accountId: String = "accA", threadId: String? = "T1", seen: Int = 1) {
        db.prepareStatement(
            "INSERT INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, threadId); ps.setInt(5, seen); ps.setLong(6, sortKey)
            ps.executeUpdate()
        }
    }

    /**
     * Room binds a query by handing it a statement to write itself into; this records what it
     * writes, in the positions it writes them. Nothing here decides the order — the query does.
     */
    private class RecordingStatement : SupportSQLiteProgram {
        val bound = sortedMapOf<Int, Any?>()
        override fun bindNull(index: Int) { bound[index] = null }
        override fun bindLong(index: Int, value: Long) { bound[index] = value }
        override fun bindDouble(index: Int, value: Double) { bound[index] = value }
        override fun bindString(index: Int, value: String) { bound[index] = value }
        override fun bindBlob(index: Int, value: ByteArray) { bound[index] = value }
        override fun clearBindings() = bound.clear()
        override fun close() = Unit
    }

    /** One collapsed row: the id the chip is drawn on, and the number on it. */
    private data class Row(val id: String, val threadCount: Int, val threadTotal: Int)

    /**
     * The collapsed list, run through the shipped [conversationQuery]: its SQL, its arguments, its
     * order. The only thing this test contributes is a JDBC statement to pour them into.
     */
    private fun rows(
        mailboxIds: List<String>,
        sent: List<Pair<String, String>>,
        accountId: String? = null,
        // The accounts whose [mailboxIds] the list is scoped to — one for a folder view, several
        // for the unified inbox. Defaults to [accountId]'s, so only unified cases say it.
        accounts: List<String> = listOfNotNull(accountId),
        sort: SortOrder = SortOrder.DATE_DESC,
        unreadOnly: Boolean = false,
    ): List<Row> {
        val scopes = accounts.flatMap { acc -> mailboxIds.map { acc to it } }
        val query = conversationQuery(scopes, sort, unreadOnly, accountId, sent)
        val recorded = RecordingStatement().also { query.bindTo(it) }
        assertEquals(
            "the query declares an argument count its own bindTo does not fill",
            query.argCount,
            recorded.bound.size,
        )
        assertEquals(
            "every placeholder in the statement must get exactly one argument",
            query.sql.count { it == '?' },
            recorded.bound.size,
        )
        return db.prepareStatement(query.sql).use { ps ->
            recorded.bound.forEach { (index, value) -> ps.setString(index, value as String) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Row(rs.getString("id"), rs.getInt("threadCount"), rs.getInt("threadTotal")))
                    }
                }
            }
        }
    }

    /** A thread the user has answered: two messages in the Inbox, the reply filed in Sent. */
    private fun anAnsweredThread() {
        insert("in1", sortKey = 100, mailbox = "inbox")
        insert("in2", sortKey = 200, mailbox = "inbox")
        insert("se1", sortKey = 300, mailbox = "sentbox")
    }

    @Test fun `the shipped assembly counts the thread's Sent reply on the chip`() {
        anAnsweredThread()

        val row = rows(listOf("inbox"), sent = listOf("accA" to "sentbox"), accountId = "accA").single()

        assertEquals("in2", row.id) // newest IN-VIEW member; the Sent reply is newer and does not lead
        assertEquals(3, row.threadCount)

        // The witness: the same fixture with nothing resolved says two. The Sent pair is genuinely
        // reaching the statement, in the position the statement expects it.
        assertEquals(2, rows(listOf("inbox"), sent = emptyList(), accountId = "accA").single().threadCount)
    }

    @Test fun `the account filter and the Sent pairs do not change places`() {
        // The bind order is the whole risk here: the chip sub-query takes the mailbox ids, then a
        // PAIR per Sent folder, then the account id. Swap any two and the query still runs. This
        // fixture makes every one of those slots matter — a sibling account's folder carries the id
        // this account uses for Sent (servers number folders per account, #92).
        anAnsweredThread()
        insert("other", sortKey = 400, mailbox = "sentbox", accountId = "accB")

        val mine = rows(listOf("inbox"), sent = listOf("accA" to "sentbox", "accB" to "sentbox"), accountId = "accA").single()
        assertEquals(3, mine.threadCount) // accB's message in the same-id folder is not mine

        // The witness: accB's row exists and is counted on its own terms, so "3" is not the query
        // failing to see the sibling at all.
        insert("b1", sortKey = 500, mailbox = "inbox", accountId = "accB")
        assertEquals(2, rows(listOf("inbox"), sent = listOf("accB" to "sentbox"), accountId = "accB").single().threadCount)
    }

    @Test fun `the unified list binds no account and still keeps each row to its own`() {
        // The other call site (pagedMailbox): accountId = null, every account's pairs bound at once.
        insert("a1", sortKey = 100, mailbox = "inbox", accountId = "accA")
        insert("aSent", sortKey = 110, mailbox = "sentA", accountId = "accA")
        insert("b1", sortKey = 200, mailbox = "inbox", accountId = "accB", threadId = "T1")
        insert("bSent", sortKey = 210, mailbox = "sentB", accountId = "accB", threadId = "T1")

        val rows = rows(listOf("inbox"), sent = listOf("accA" to "sentA", "accB" to "sentB"), accountId = null, accounts = listOf("accA", "accB"))

        assertEquals(listOf("b1", "a1"), rows.map { it.id })
        assertEquals(listOf(2, 2), rows.map { it.threadCount })

        // The witness: scoped to only ONE account's Sent folder, the other account's row shrinks —
        // the pairs are read per row, not as one pooled set of folder ids.
        assertEquals(
            listOf(1, 2), // b1's row (drawn first) loses its Sent reply; a1's keeps its own
            rows(listOf("inbox"), sent = listOf("accA" to "sentA"), accountId = null, accounts = listOf("accA", "accB")).map { it.threadCount },
        )
    }

    @Test fun `the unread filter and the sort ride on the same binds`() {
        // The sort and the HAVING clause change the statement's shape but not its arguments; a
        // query that bound them by position would come apart here.
        insert("in1", sortKey = 100, mailbox = "inbox", seen = 0)
        insert("in2", sortKey = 200, mailbox = "inbox")
        insert("se1", sortKey = 300, mailbox = "sentbox")
        insert("solo", sortKey = 400, mailbox = "inbox", threadId = "T2")

        val unread = rows(listOf("inbox"), sent = listOf("accA" to "sentbox"), accountId = "accA", unreadOnly = true)
        assertEquals(listOf("in2"), unread.map { it.id })
        assertEquals(3, unread.single().threadCount) // still counts the read members and the reply

        val oldestFirst = rows(listOf("inbox"), sent = listOf("accA" to "sentbox"), accountId = "accA", sort = SortOrder.DATE_ASC)
        assertEquals(listOf("in2", "solo"), oldestFirst.map { it.id })

        // The witness: without the filter the loose read message is there too, so the filter is
        // doing the work and not the fixture.
        assertNotEquals(
            unread.map { it.id },
            rows(listOf("inbox"), sent = listOf("accA" to "sentbox"), accountId = "accA").map { it.id },
        )
    }

    @Test fun `the shipped assembly leaves out a row whose account is not in the scope`() {
        // Codeberg #121, through the real assembly rather than a retyped bind: the unified list
        // is given the accounts it knows, and a row of an account it does not know — one the user
        // removed, whose server-assigned folder id outlived it — is not in the list.
        insert("live", sortKey = 100, mailbox = "inbox", accountId = "accA", threadId = null)
        insert("ghost", sortKey = 200, mailbox = "inbox", accountId = "gone", threadId = null, seen = 0)

        val listed = rows(listOf("inbox"), sent = emptyList(), accountId = null, accounts = listOf("accA"))
        assertEquals(listOf("live"), listed.map { it.id })

        // The witness: the same row IS listed once its account is one of the scoped ones, so the
        // exclusion is the account scope and not the fixture, the sort or the snooze filter.
        assertEquals(
            listOf("ghost", "live"),
            rows(listOf("inbox"), sent = emptyList(), accountId = null, accounts = listOf("accA", "gone")).map { it.id },
        )
    }

    @Test fun `the account-wide total ignores the Sent resolution entirely`() {
        // threadTotal only gates the expand affordance and must not move with the chip's scope —
        // it counts the thread everywhere, including the folders the conversation excludes.
        anAnsweredThread()
        insert("tr1", sortKey = 50, mailbox = "trash")

        assertEquals(4, rows(listOf("inbox"), sent = listOf("accA" to "sentbox"), accountId = "accA").single().threadTotal)
        assertEquals(4, rows(listOf("inbox"), sent = emptyList(), accountId = "accA").single().threadTotal)
    }
}
