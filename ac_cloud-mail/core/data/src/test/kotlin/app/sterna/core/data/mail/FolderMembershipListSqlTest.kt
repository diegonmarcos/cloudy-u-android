package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * #1837 phase 3: a label lists a message that is also in the Inbox (the shipped list SQL, on in-memory SQLite). The message is ONE
 * `emails` row filed under its primary mailbox (inbox); `email_mailboxes` says it is also in `lbl`. A folder named in `memberPairs` adds
 * that membership term; every other folder keeps the legacy text and plan.
 */
class FolderMembershipListSqlTest {
    private lateinit var db: Connection
    private val lbl = listOf("acc" to "lbl")

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                "CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT, subject TEXT, preview TEXT, receivedAt TEXT, " +
                    "fromName TEXT, fromEmail TEXT, seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, " +
                    "authClass INTEGER, fromDomain TEXT, PRIMARY KEY(accountId, id))",
            )
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, PRIMARY KEY(accountId, emailId))")
            st.executeUpdate("CREATE TABLE email_mailboxes(accountId TEXT, emailId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, emailId, mailboxId))")
        }
        //     id        mailbox  thread  seen key
        insert("both", "inbox", "T1", 0, 400)   // in the Inbox AND the label
        insert("only", "lbl", "T2", 0, 300)     // the label only
        insert("inb", "inbox", "T3", 1, 200)    // the Inbox only
        insert("sib", "lbl", "T4", 0, 100, account = "other") // another account's folder of the same id
        member("acc", "both", "inbox"); member("acc", "both", "lbl"); member("acc", "only", "lbl"); member("acc", "inb", "inbox")
        member("other", "sib", "lbl")
    }

    @After fun tearDown() = db.close()

    private fun insert(id: String, mailbox: String, thread: String, seen: Int, key: Long, account: String = "acc") {
        db.prepareStatement("INSERT INTO emails VALUES(?, ?, ?, ?, 's', 'p', '', 'N', 'e', ?, 0, 0, ?, 0, 'd.org')").use { ps ->
            ps.setString(1, id); ps.setString(2, account); ps.setString(3, mailbox); ps.setString(4, thread)
            ps.setInt(5, seen); ps.setLong(6, key); ps.executeUpdate()
        }
    }

    private fun member(account: String, id: String, mailbox: String) {
        db.prepareStatement("INSERT INTO email_mailboxes VALUES(?, ?, ?)").use { ps ->
            ps.setString(1, account); ps.setString(2, id); ps.setString(3, mailbox); ps.executeUpdate()
        }
    }

    private fun ids(sql: String, args: List<String>): List<String> = db.prepareStatement(sql).use { ps ->
        args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
        ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("id") else null }.toList() }
    }

    private fun flat(scopes: List<Pair<String, String>>, members: Set<Int>) =
        ids(pagingSql(scopes.size, SortOrder.DATE_DESC, false, ListShape.NONE, members), scopeArgs(scopes, members))

    @Test fun `without the membership term a label lists only what is filed in it (the legacy list, unchanged)`() {
        assertEquals(listOf("only"), flat(lbl, emptySet()))
        assertEquals(
            "the legacy text is the anonymous form it always was",
            "(emails.accountId = ? AND emails.mailboxId = ?)", folderScopeSql(1, "emails"),
        )
    }

    @Test fun `with the membership term the label lists the message that is also in the Inbox, once, in order`() {
        assertEquals(listOf("both", "only"), flat(lbl, setOf(0)))
    }

    @Test fun `the Inbox keeps listing it too, and another account's folder of the same id is not selected`() {
        assertEquals(listOf("both", "inb"), flat(listOf("acc" to "inbox"), setOf(0)))
        assertFalse("sib" in flat(lbl, setOf(0)))
        assertEquals(listOf("both", "only", "sib"), flat(listOf("acc" to "lbl", "other" to "lbl"), setOf(0, 1)))
    }

    @Test fun `memberPairIndexes names the scopes the core filed extra messages in`() {
        val scopes = listOf("acc" to "inbox", "acc" to "lbl")
        assertEquals(setOf(1), memberPairIndexes(scopes, setOf("acc" to "lbl", "zzz" to "lbl")))
        assertEquals(emptySet<Int>(), memberPairIndexes(scopes, emptySet()))
    }

    @Test fun `the selection and the count read the same rows as the list`() {
        val scopes = lbl
        assertEquals(
            setOf("both", "only"),
            ids(selectionIdsSql(1, false, ListShape.NONE, setOf(0)).replace("SELECT accountId, id", "SELECT id"), scopeArgs(scopes, setOf(0))).toSet(),
        )
        db.prepareStatement(listCountSql(1)).use { ps -> ps.setString(1, "acc"); ps.setString(2, "lbl"); ps.executeQuery().use { it.next(); assertEquals(1, it.getInt(1)) } }
    }

    @Test fun `the conversation list binds four values per member pair in each of its three clauses plus the Sent pairs, and finds the message in the label`() {
        val sent = listOf("acc" to "sent")
        val q = conversationQuery(lbl, SortOrder.DATE_DESC, false, accountId = "acc", sentMailboxes = sent, shape = ListShape.NONE, memberScopes = setOf("acc" to "lbl"))
        val rows = db.prepareStatement(q.sql).use { ps ->
            val args = q.argsAsList()
            args.forEachIndexed { i, a -> ps.setString(i + 1, a as String) }
            ps.executeQuery().use { rs -> generateSequence { if (rs.next()) rs.getString("id") else null }.toList() }
        }
        assertEquals(listOf("both", "only"), rows)
    }

    @Test fun `a label's unread count counts memberships, so the message also in the Inbox counts in both`() {
        // the SHIPPED badge statements, read from EmailDao
        val message = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("observeMessageUnreadCounts")).first
        val thread = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery("observeThreadUnreadCounts")).first
        fun unread(sql: String, mailbox: String): Int = db.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                var n = 0
                while (rs.next()) if (rs.getString("accountId") == "acc" && rs.getString("mailboxId") == mailbox) n = rs.getInt("count")
                n
            }
        }
        assertEquals("both + only", 2, unread(message, "lbl"))
        assertEquals("both", 1, unread(message, "inbox"))
        assertEquals("threads: T1 + T2", 2, unread(thread, "lbl"))
        assertEquals(1, unread(thread, "inbox"))
        // seen it in the Inbox: seen everywhere
        db.createStatement().use { it.executeUpdate("UPDATE emails SET seen = 1 WHERE id = 'both'") }
        assertEquals(1, unread(message, "lbl"))
        assertEquals(0, unread(message, "inbox"))
    }

    private fun androidx.sqlite.db.SimpleSQLiteQuery.argsAsList(): List<Any?> {
        val out = ArrayList<Any?>()
        val binder = object : androidx.sqlite.db.SupportSQLiteProgram {
            override fun bindNull(index: Int) { while (out.size < index) out.add(null) }
            override fun bindLong(index: Int, value: Long) { while (out.size < index) out.add(null); out[index - 1] = value }
            override fun bindDouble(index: Int, value: Double) { while (out.size < index) out.add(null); out[index - 1] = value }
            override fun bindString(index: Int, value: String) { while (out.size < index) out.add(null); out[index - 1] = value }
            override fun bindBlob(index: Int, value: ByteArray) { while (out.size < index) out.add(null); out[index - 1] = value }
            override fun clearBindings() = out.clear()
            override fun close() {}
        }
        bindTo(binder)
        return out
    }
}
