package app.sterna.core.data.mail

import androidx.sqlite.db.SupportSQLiteProgram
import app.sterna.core.data.db.RecentEmailRow
import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * What the "latest messages" home-screen widget is allowed to read, run against real SQLite.
 */
class RecentUnifiedInboxSqlTest {
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
        accountId: String = "acc1",
        sortKey: Long = 1,
    ) {
        db.prepareStatement(
            "INSERT INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, NULL, 'subj-' || ?, 'SECRET BODY', '2026-08-18T10:00:00Z', 'N', 'e@x', ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, id); ps.setInt(5, seen); ps.setLong(6, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, until: Long, accountId: String = "acc1") = db.createStatement().use {
        it.executeUpdate("INSERT INTO snoozed VALUES('$id', '$accountId', $until)")
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

    /**
     * The widget's read AS THE REPOSITORY BUILDS IT — statement and binds together.
     */
    private fun widget(scopes: List<Pair<String, String>>, limit: Int) =
        recentUnifiedInboxFrom(scopes) { s ->
            val query = recentUnifiedQuery(s, limit)
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
            db.prepareStatement(query.sql).use { ps ->
                recorded.bound.forEach { (i, v) ->
                    when (v) {
                        is Long -> ps.setLong(i, v)
                        is String -> ps.setString(i, v)
                        else -> error("the widget query bound something unexpected: $v")
                    }
                }
                ps.executeQuery().use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(
                                RecentEmailRow(
                                    id = rs.getString("id"),
                                    accountId = rs.getString("accountId"),
                                    mailboxId = rs.getString("mailboxId"),
                                    subject = rs.getString("subject"),
                                    fromName = rs.getString("fromName"),
                                    fromEmail = rs.getString("fromEmail"),
                                    seen = rs.getInt("seen") != 0,
                                    sortKey = rs.getLong("sortKey"),
                                    receivedAt = rs.getString("receivedAt"),
                                )
                            )
                        }
                    }
                }
            }
        }

    /** The ids the app's own flat list draws, in its own order, bound the way `pagingQuery` binds. */
    private fun listed(scopes: List<Pair<String, String>>): List<String> =
        db.prepareStatement(pagingSql(scopes.size, SortOrder.DATE_DESC, unreadOnly = false)).use { ps ->
            scopes.flatMap { listOf(it.first, it.second) }
                .forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    private val one = listOf("acc1" to "inbox")
    private val both = listOf("acc1" to "inbox", "acc2" to "inbox")

    @Test fun `the widget holds exactly the first rows the app's own list draws`() {
        // Distinct sort keys, so the list's order is the one SQLite must produce, not a tie.
        insert("m5", sortKey = 500, seen = 0)
        insert("m1", sortKey = 100)
        insert("m4", sortKey = 400, accountId = "acc2")
        insert("m2", sortKey = 200, seen = 0, accountId = "acc2")
        insert("m3", sortKey = 300)
        insert("elsewhere", sortKey = 900, mailbox = "archive")
        insert("snoozed", sortKey = 800)
        snooze("snoozed", System.currentTimeMillis() + 3_600_000)

        val list = listed(both)
        assertEquals(listOf("m5", "m4", "m3", "m2", "m1"), list)
        assertEquals(list.take(3), widget(both, limit = 3).rows.map { it.id })
        assertEquals(list, widget(both, limit = 50).rows.map { it.id })
    }

    @Test fun `a sibling account's identically numbered inbox stays out`() {
        // Servers number mailboxes per account (#121/#31): "inbox" in acc2 is NOT acc1's inbox.
        insert("mine", sortKey = 200)
        insert("theirs", sortKey = 300, accountId = "acc2")

        assertEquals(listOf("mine"), widget(one, limit = 10).rows.map { it.id })
        assertEquals(listOf("theirs", "mine"), widget(both, limit = 10).rows.map { it.id })
    }

    @Test fun `a snoozed message is off the widget, exactly as it is off the list`() {
        insert("here", sortKey = 100)
        insert("later", sortKey = 900)
        snooze("later", System.currentTimeMillis() + 3_600_000)

        assertEquals(listOf("here"), widget(one, limit = 10).rows.map { it.id })
        assertEquals(listed(one), widget(one, limit = 10).rows.map { it.id })
    }

    @Test fun `a lapsed snooze is back on the widget`() {
        insert("here", sortKey = 100)
        insert("lapsed", sortKey = 900)
        snooze("lapsed", System.currentTimeMillis() - 1_000)

        assertEquals(listOf("lapsed", "here"), widget(one, limit = 10).rows.map { it.id })
    }

    @Test fun `messages sharing a sort key come out in a stable, determined order`() {
        insert("aaa", sortKey = 700)
        insert("ccc", sortKey = 700)
        insert("bbb", sortKey = 700)

        val once = widget(one, limit = 10).rows.map { it.id }
        assertEquals(listOf("ccc", "bbb", "aaa"), once)
        // Stable across redraws, and stable when the widget is bounded: the row that "wins" a tie
        // must not depend on how many rows were asked for.
        assertEquals(once, widget(one, limit = 10).rows.map { it.id })
        assertEquals(once.take(2), widget(one, limit = 2).rows.map { it.id })
    }

    @Test fun `no readable account is not the same answer as an empty inbox`() {
        insert("mine", sortKey = 100)

        val unknown = widget(scopes = emptyList(), limit = 10)
        assertEquals(false, unknown.configured)
        assertEquals(emptyList<String>(), unknown.rows.map { it.id })

        // Accounts, but nothing cached in their inbox: "empty", and it must be tellable apart.
        val empty = widget(listOf("acc9" to "inbox"), limit = 10)
        assertTrue("accounts exist, so the widget may say the inbox is empty", empty.configured)
        assertEquals(emptyList<String>(), empty.rows.map { it.id })
    }

    @Test fun `the row bound is honoured`() {
        (1..10).forEach { insert("m$it", sortKey = it.toLong()) }

        assertEquals(4, widget(one, limit = 4).rows.size)
        assertEquals(listOf("m10", "m9", "m8", "m7"), widget(one, limit = 4).rows.map { it.id })
        assertEquals(10, widget(one, limit = 25).rows.size)
    }

    /**
     * The body never leaves the database — asserted on the statement the repository SHIPS, by
     */
    @Test fun `the widget's statement cannot carry the body of a message`() {
        insert("m1", sortKey = 100)

        db.prepareStatement(recentUnifiedSql(1)).use { ps ->
            ps.setString(1, "acc1"); ps.setString(2, "inbox"); ps.setInt(3, 10)
            ps.executeQuery().use { rs ->
                val meta = rs.metaData
                val columns = (1..meta.columnCount).map { meta.getColumnLabel(it).lowercase() }
                assertEquals(
                    "the widget selects exactly the columns a line may draw or open",
                    listOf(
                        "id", "accountid", "mailboxid", "subject",
                        "fromname", "fromemail", "seen", "sortkey", "receivedat",
                    ),
                    columns,
                )
                assertTrue(
                    "preview reached the widget's query: the body of a message is one draw away " +
                        "from an unlocked home screen",
                    columns.none { it == "preview" },
                )
            }
        }
    }

    /**
     * What a line actually CARRIES — every drawn field compared to a value, not just counted.
     */
    @Test fun `a line carries the fields it draws, and never the body under another name`() {
        insert("m1", sortKey = 100, seen = 0)

        val row = widget(one, limit = 10).rows.single()
        assertEquals("m1", row.id)
        assertEquals("acc1", row.accountId)
        assertEquals("inbox", row.mailboxId)
        assertEquals("subj-m1", row.subject)
        assertEquals("N", row.fromName)
        assertEquals("e@x", row.fromEmail)
        assertEquals(false, row.seen)
        assertEquals("2026-08-18T10:00:00Z", row.receivedAt)
        assertEquals(100L, row.sortKey)
        assertTrue(
            "the body of the message reached a field the widget draws",
            listOfNotNull(row.subject, row.fromName, row.fromEmail, row.receivedAt)
                .none { "SECRET" in it },
        )
    }

    /**
     * A bound that is not a bound. The number of rows is the most natural thing for a widget to
     */
    @Test fun `a non-positive row bound is not a way to read everything, nor to claim emptiness`() {
        (1..5).forEach { insert("m$it", sortKey = it.toLong()) }

        assertEquals(1, widget(one, limit = 0).rows.size)
        assertEquals(1, widget(one, limit = -1).rows.size)
        assertEquals(1, widget(one, limit = Int.MIN_VALUE).rows.size)
    }
}
