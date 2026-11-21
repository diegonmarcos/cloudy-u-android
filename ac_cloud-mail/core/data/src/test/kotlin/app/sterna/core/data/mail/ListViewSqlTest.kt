package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/** The list view's grouping, ranking and filters, run through the shipped SQL on in-memory SQLite. */
class ListViewSqlTest {
    private lateinit var db: Connection
    private val scope = listOf("acc" to "inbox")

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate(
                """
                CREATE TABLE emails(
                    id TEXT, accountId TEXT, mailboxId TEXT, threadId TEXT,
                    subject TEXT, preview TEXT, receivedAt TEXT, fromName TEXT, fromEmail TEXT,
                    seen INTEGER, flagged INTEGER, hasAttachment INTEGER, sortKey INTEGER, authClass INTEGER, fromDomain TEXT,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
            st.executeUpdate("CREATE TABLE snoozed(emailId TEXT, accountId TEXT, until INTEGER, PRIMARY KEY(accountId, emailId))")
            st.executeUpdate("CREATE TABLE email_mailboxes(accountId TEXT, emailId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, emailId, mailboxId))")
        }
        // authClass: 1 Ga Code, 2 Gb Link to auth, 0 Gc No Auth, null not classified
        //        id   thread subject  from    seen flag att auth key
        insert("m1", "T1", "Alpha", "zed@x.io", 1, 0, 0, 0, 100)
        insert("m2", "T1", "Alpha", "amy@x.io", 0, 1, 0, 2, 200)
        insert("m3", null, "Bravo", "amy@x.io", 1, 1, 1, 1, 300)
        insert("m4", null, "Charlie", "bob@x.io", 0, 0, 1, 1, 400)
        insert("m5", null, "Delta", "Amy@X.io", 1, 0, 0, null, 50)
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String, thread: String?, subject: String, from: String, seen: Int, flagged: Int, att: Int, auth: Int?, key: Long,
        // what the mappers store; null is a row cached before the domain index existed
        domain: String? = SenderDomain.indexed(from),
    ) {
        db.prepareStatement("INSERT INTO emails VALUES(?, 'acc', 'inbox', ?, ?, 'p', '', NULL, ?, ?, ?, ?, ?, ?, ?)").use { ps ->
            ps.setString(1, id); ps.setString(2, thread); ps.setString(3, subject); ps.setString(4, from)
            ps.setInt(5, seen); ps.setInt(6, flagged); ps.setInt(7, att); ps.setLong(8, key); if (auth == null) ps.setNull(9, java.sql.Types.INTEGER) else ps.setInt(9, auth)
            ps.setString(10, domain)
            ps.executeUpdate()
        }
    }

    private fun flat(sort: SortOrder = SortOrder.DATE_DESC, unread: Boolean = false, shape: ListShape = ListShape.NONE): List<String> =
        db.prepareStatement(pagingSql(scope.size, sort, unread, shape)).use { ps ->
            scope.flatMap { listOf(it.first, it.second) }.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    private fun grouped(sort: SortOrder = SortOrder.DATE_DESC, unread: Boolean = false, shape: ListShape = ListShape.NONE): List<Pair<String, Int>> =
        groupedRows(sort, unread, shape).map { it.first to it.second }

    /** (representative id, count, unread in the group) per group row. */
    private fun groupedRows(sort: SortOrder = SortOrder.DATE_DESC, unread: Boolean = false, shape: ListShape = ListShape.NONE): List<Triple<String, Int, Int>> =
        db.prepareStatement(conversationSql(scope.size, sort, unread, 0, shape)).use { ps ->
            (1..3).flatMap { scope.flatMap { listOf(it.first, it.second) } }.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(Triple(rs.getString("id"), rs.getInt("threadCount"), rs.getInt("groupUnread"))) } }
        }

    // -- grouping ----------------------------------------------------------------------------

    @Test fun `no grouping lists every message`() {
        assertEquals(listOf("m4", "m3", "m2", "m1", "m5"), flat())
    }

    @Test fun `group by subject is the conversation view`() {
        assertEquals(listOf("m4" to 1, "m3" to 1, "m2" to 2, "m5" to 1), grouped())
    }

    @Test fun `group by sender collapses one address to its newest message, case-insensitively`() {
        val rows = grouped(shape = ListShape(bySender = true))
        assertEquals(listOf("m4" to 1, "m3" to 3, "m1" to 1), rows)
    }

    @Test fun `group by domain joins subdomains, keeps addresses apart from it, and counts unread`() {
        insert("m6", null, "Echo", "n@Notifications.GitHub.com", 0, 0, 0, 0, 500)
        insert("m7", null, "Fox", "o@github.com", 1, 0, 0, 0, 450)
        insert("m8", null, "Golf", "not an address", 0, 0, 0, 0, 60)
        insert("m9", null, "Hotel", "p@q.example.co.uk", 1, 0, 0, 0, 70, domain = null)
        // github.com (m6 + m7), x.io (all five), then the row not indexed yet alone, then "(unknown)"
        assertEquals(
            listOf(Triple("m6", 2, 1), Triple("m4", 5, 2), Triple("m9", 1, 0), Triple("m8", 1, 1)),
            groupedRows(shape = ListShape(byDomain = true)),
        )
        // Group by Sender is unchanged: one address per group
        assertEquals(listOf("m6" to 1, "m7" to 1, "m4" to 1, "m3" to 3, "m1" to 1, "m9" to 1, "m8" to 1), grouped(shape = ListShape(bySender = true)))
        // ranked by sender, domain groups are ranked by the domain: "" ((unknown)) and the unindexed row first
        assertEquals(listOf("m8", "m9", "m6", "m4"), grouped(SortOrder.SENDER, shape = ListShape(byDomain = true)).map { it.first })
        // the filters still narrow which groups show
        assertEquals(listOf("m6" to 2, "m4" to 5, "m8" to 1), grouped(unread = true, shape = ListShape(byDomain = true)))
    }

    @Test fun `the domain the heading names is the domain the group was keyed by`() {
        for (from in listOf("zed@x.io", "n@Notifications.GitHub.com", "p@q.example.co.uk", "broken")) {
            assertEquals(SenderDomain.label(SenderDomain.indexed(from)), SenderDomain.label(SenderDomain.registrable(from)))
        }
    }

    // -- ranking -----------------------------------------------------------------------------

    @Test fun `rank newest first, by sender, by subject`() {
        assertEquals(listOf("m4", "m3", "m2", "m1", "m5"), flat(SortOrder.DATE_DESC))
        // by sender: amy (m2, m3, m5 - one address in any case), then bob, then zed
        val bySender = flat(SortOrder.SENDER)
        assertEquals(setOf("m2", "m3", "m5"), bySender.take(3).toSet())
        assertEquals(listOf("m4", "m1"), bySender.drop(3))
        // by subject: Alpha (m1, m2), Bravo, Charlie, Delta
        val bySubject = flat(SortOrder.SUBJECT)
        assertEquals(setOf("m1", "m2"), bySubject.take(2).toSet())
        assertEquals(listOf("m3", "m4", "m5"), bySubject.drop(2))
    }

    // -- filters, alone and combined ---------------------------------------------------------

    @Test fun `only starred`() = assertEquals(listOf("m3", "m2"), flat(shape = ListShape(starred = true)))
    @Test fun `only unread`() = assertEquals(listOf("m4", "m2"), flat(unread = true))
    @Test fun `only with attachments`() = assertEquals(listOf("m4", "m3"), flat(shape = ListShape(attachments = true)))
    @Test fun `auth with codes, auth links and any auth use the index`() {
        assertEquals(listOf("m4", "m3"), flat(shape = ListShape(auth = AuthFilter.CODES)))
        assertEquals(listOf("m2"), flat(shape = ListShape(auth = AuthFilter.LINKS)))
        assertEquals(listOf("m4", "m3", "m2"), flat(shape = ListShape(auth = AuthFilter.ANY)))
        assertEquals("not-yet-classified rows are in no auth class", false, "m5" in flat(shape = ListShape(auth = AuthFilter.ANY)))
    }

    @Test fun `filters combine`() {
        assertEquals(listOf("m3"), flat(shape = ListShape(starred = true, attachments = true)))
        assertEquals(listOf("m4"), flat(unread = true, shape = ListShape(attachments = true, auth = AuthFilter.CODES)))
        assertEquals(listOf("m3"), flat(shape = ListShape(starred = true, attachments = true, auth = AuthFilter.CODES)))
        assertEquals(emptyList<String>(), flat(unread = true, shape = ListShape(starred = true, auth = AuthFilter.CODES)))
    }

    @Test fun `the filters apply to the grouped list too`() {
        assertEquals(listOf("m3" to 1), grouped(shape = ListShape(starred = true, auth = AuthFilter.CODES)))
        assertEquals(listOf("m4" to 1), grouped(unread = true, shape = ListShape(auth = AuthFilter.CODES)))
    }

    @Test fun `select all takes exactly the filtered rows`() {
        val shape = ListShape(starred = true)
        db.prepareStatement(selectionIdsSql(scope.size, false, shape)).use { ps ->
            ps.setString(1, "acc"); ps.setString(2, "inbox")
            val ids = ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
            assertEquals(flat(shape = shape).sorted(), ids.sorted())
        }
    }

    // -- the index ---------------------------------------------------------------------------

    @Test fun `a row with no preview is not classified yet`() {
        assertNull(AuthIndex.indexed("Subject", null))
        assertEquals(0, AuthIndex.indexed("Lunch?", "see you at noon"))
        assertEquals(1, AuthIndex.indexed("Your code", "Your code is 482913."))
    }

    @Test fun `the classifier uses the reading pane's extractor and no second one`() {
        val src = java.io.File("src/main/kotlin/app/sterna/core/data/text/AuthClassifier.kt").readText()
        assertTrue(src.contains("extractVerificationCode(subject = subject, bodyText = bodyText, html = html)"))
        val app = java.io.File("../../app/src/main/kotlin/app/sterna/ui/message/MessageScreen.kt")
        if (app.isFile) assertTrue(app.readText().contains("return extractVerificationCode("))
    }
}
