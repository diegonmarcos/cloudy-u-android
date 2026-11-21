package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * What "Select all" is allowed to take from the folder behind the list, run against real SQLite
 */
class SelectionIdsSqlTest {
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

    private fun insert(
        id: String,
        seen: Int = 1,
        mailbox: String = "inbox",
        accountId: String = "acc",
        sortKey: Long = 1,
    ) {
        db.prepareStatement(
            "INSERT INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, NULL, 'subj', 'prev', '', 'N', 'e', ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setInt(4, seen); ps.setLong(5, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, until: Long, accountId: String = "acc") = db.createStatement().use {
        it.executeUpdate("INSERT INTO snoozed VALUES('$id', '$accountId', $until)")
    }

    /** The ids the flat list draws, bound as `pagingQuery` binds it: (account, mailbox) pairs. */
    private fun listed(scopes: List<Pair<String, String>>, unreadOnly: Boolean): List<String> =
        run(pagingSql(scopes.size, SortOrder.DATE_DESC, unreadOnly), scopes)

    /** The ids "Select all" takes, bound as `selectionIdsQuery` binds it: the same pairs. */
    private fun selected(scopes: List<Pair<String, String>>, unreadOnly: Boolean): List<String> =
        run(selectionIdsSql(scopes.size, unreadOnly), scopes)

    private fun run(sql: String, scopes: List<Pair<String, String>>): List<String> =
        db.prepareStatement(sql).use { ps ->
            scopes.flatMap { listOf(it.first, it.second) }
                .forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }.sorted()

    private val inbox = listOf("acc" to "inbox")

    @Test fun `with the unread funnel on, select all takes the unread only`() {
        insert("read1", seen = 1)
        insert("unread1", seen = 0)
        insert("read2", seen = 1)

        assertEquals(listOf("unread1"), selected(inbox, unreadOnly = true))
        assertEquals(listed(inbox, unreadOnly = true), selected(inbox, unreadOnly = true))
    }

    @Test fun `with the funnel off, select all still takes the whole folder`() {
        insert("read1", seen = 1)
        insert("unread1", seen = 0)

        assertEquals(listOf("read1", "unread1"), selected(inbox, unreadOnly = false))
        assertEquals(listed(inbox, unreadOnly = false), selected(inbox, unreadOnly = false))
    }

    @Test fun `a snoozed message is off the list, so it is not selected either`() {
        insert("here", seen = 0)
        insert("later", seen = 0)
        snooze("later", System.currentTimeMillis() + 3_600_000)

        assertEquals(listOf("here"), selected(inbox, unreadOnly = false))
        assertEquals(listed(inbox, unreadOnly = false), selected(inbox, unreadOnly = false))
    }

    @Test fun `a lapsed snooze is back on the list, so it is selected again`() {
        insert("here", seen = 0)
        insert("lapsed", seen = 0)
        snooze("lapsed", System.currentTimeMillis() - 1_000)

        assertEquals(listOf("here", "lapsed"), selected(inbox, unreadOnly = false))
        assertEquals(listed(inbox, unreadOnly = false), selected(inbox, unreadOnly = false))
    }

    @Test fun `the selection stays inside the scoped account, funnel or no funnel`() {
        // Servers number mailboxes per account (issue #121/#31): a sibling account's "inbox" must
        // not join the selection just because the id string matches.
        insert("mine", seen = 0)
        insert("theirs", seen = 0, accountId = "other")

        assertEquals(listOf("mine"), selected(inbox, unreadOnly = false))
        assertEquals(listOf("mine"), selected(inbox, unreadOnly = true))
        assertEquals(listed(inbox, unreadOnly = true), selected(inbox, unreadOnly = true))
    }

    @Test fun `the unified selection spans one folder per account, filtered the same way`() {
        insert("mine-unread", seen = 0)
        insert("mine-read", seen = 1)
        insert("theirs-unread", seen = 0, accountId = "other")
        insert("theirs-read", seen = 1, accountId = "other")

        val unified = listOf("acc" to "inbox", "other" to "inbox")
        assertEquals(listOf("mine-unread", "theirs-unread"), selected(unified, unreadOnly = true))
        assertEquals(listed(unified, unreadOnly = true), selected(unified, unreadOnly = true))
        assertEquals(listed(unified, unreadOnly = false), selected(unified, unreadOnly = false))
    }

    @Test fun `the two queries agree row for row over every filter state`() {
        // The drift detector, stated as a property rather than a case: whatever is in the folder,
        // the ids on screen and the ids the tap takes are the same set.
        insert("a", seen = 0)
        insert("b", seen = 1)
        insert("c", seen = 0)
        insert("d", seen = 1, mailbox = "archive")
        snooze("c", System.currentTimeMillis() + 3_600_000)

        listOf(true, false).forEach { unreadOnly ->
            assertEquals(
                "the list and Select all must hold the same ids (unreadOnly=$unreadOnly)",
                listed(inbox, unreadOnly),
                selected(inbox, unreadOnly),
            )
        }
    }
}
