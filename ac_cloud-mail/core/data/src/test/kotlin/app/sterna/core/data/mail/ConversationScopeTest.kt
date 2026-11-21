package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The chip and the unfold ask ONE question about the same conversation.
 */
class ConversationScopeTest {
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

    private fun insert(id: String, sortKey: Long, mailbox: String, accountId: String = "accA", threadId: String? = "T1") {
        db.prepareStatement(
            "INSERT INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', 1, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, threadId); ps.setLong(5, sortKey)
            ps.executeUpdate()
        }
    }

    /**
     * The chip on the collapsed row, and the id it is drawn on.
     */
    private fun chip(viewed: List<String>, resolution: List<Pair<String, String>>, accountId: String?): Pair<String, Int> {
        val sent = ConversationScope.sentFolders(resolution, accountId)
        // The viewed folders as (account, folder) scope pairs — the shape the list binds since
        // Codeberg #121. A null [accountId] (unified) is the fixture's own accounts.
        val scopes = (accountId?.let { listOf(it) } ?: resolution.map { it.first }.distinct())
            .flatMap { acc -> viewed.map { acc to it } }
        val sql = conversationSql(scopes.size, SortOrder.DATE_DESC, unreadOnly = false, sentMailboxCount = sent.size)
        val pairs = scopes.flatMap { listOf(it.first, it.second) }
        val args = buildList<String> {
            addAll(pairs)
            addAll(pairs); sent.forEach { add(it.first); add(it.second) }
            addAll(pairs)
        }
        return db.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs ->
                check(rs.next()) { "the fixture produced no collapsed row" }
                val row = rs.getString("id") to rs.getInt("threadCount")
                check(!rs.next()) { "the fixture produced more than one collapsed row" }
                row
            }
        }
    }

    /**
     * The messages the row unfolds into, REPRESENTATIVE INCLUDED — the row itself draws it, and
     */
    private fun unfold(viewed: List<String>, resolution: List<Pair<String, String>>, accountId: String, threadKey: String = "T1"): List<String> {
        val folders = ConversationScope.folders(viewed, resolution, accountId).toList()
        val (sql, order) = DaoQuerySource.bindOrder(
            DaoQuerySource.emailDaoQuery("cachedThreadEmails"),
            mapOf("mailboxIds" to folders.size),
        )
        var next = 0
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                ps.setString(
                    i + 1,
                    when (name) {
                        "accountId" -> accountId
                        "mailboxIds" -> folders[next++ % folders.size] // the list occurs twice since #1837 (own folder OR membership)
                        "threadKey" -> threadKey
                        else -> error("unexpected parameter :$name in cachedThreadEmails")
                    },
                )
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /** A thread the user has answered: two messages in the Inbox, the reply filed in Sent. */
    private fun anAnsweredThread() {
        insert("in1", sortKey = 100, mailbox = "inbox")
        insert("in2", sortKey = 200, mailbox = "inbox")
        insert("se1", sortKey = 300, mailbox = "sentbox")
    }

    // -- the invariant ---------------------------------------------------------------------

    @Test fun `the unfold lists exactly the messages the chip counted`() {
        anAnsweredThread()
        val resolution = listOf("accA" to "sentbox")

        val (row, count) = chip(listOf("inbox"), resolution, accountId = "accA")
        val listed = unfold(listOf("inbox"), resolution, accountId = "accA")

        assertEquals("in2", row) // newest IN-VIEW member; the Sent reply is newer and does not lead
        assertEquals(3, count)
        assertEquals(count, listed.size)
        assertEquals(setOf("in1", "in2", "se1"), listed.toSet())

        // The witness: the Sent reply is genuinely what makes it three. Drop it from the
        // resolution and the answer changes — this is not a fixture where Sent plays no part.
        assertEquals(2, chip(listOf("inbox"), emptyList(), accountId = "accA").second)
    }

    @Test fun `a Sent folder that did not resolve shrinks the chip and the unfold together`() {
        // The failure the fix is about: the unfold's own lookup came back empty (a swallowed
        // error, a folder cache not synced yet) while the chip had already counted the Sent reply.
        // Now one resolution feeds both, so an empty one is simply a smaller, honest conversation.
        anAnsweredThread()

        val (_, count) = chip(listOf("inbox"), emptyList(), accountId = "accA")
        val listed = unfold(listOf("inbox"), emptyList(), accountId = "accA")

        assertEquals(count, listed.size)
        assertEquals(2, count)
        assertEquals(setOf("in1", "in2"), listed.toSet())

        // The witness: the same fixture, resolved, says three on BOTH sides. The agreement above
        // is not the fixture being small — it is the two sides moving together.
        val resolved = listOf("accA" to "sentbox")
        assertEquals(3, chip(listOf("inbox"), resolved, accountId = "accA").second)
        assertEquals(3, unfold(listOf("inbox"), resolved, accountId = "accA").size)
        // And the divergence the reader saw — a chip of 3 over an unfold of 2 — needs the two
        // sides to be given different resolutions, which no longer happens.
        assertNotEquals(
            chip(listOf("inbox"), resolved, accountId = "accA").second,
            unfold(listOf("inbox"), emptyList(), accountId = "accA").size,
        )
    }

    @Test fun `a sibling account's Sent folder widens neither the chip nor the unfold`() {
        // Same-server accounts carry colliding folder ids: here account B's Sent folder has the id
        // that account A uses for an ordinary folder. A's conversation must ignore it — and both
        // sides must ignore it, or the chip counts a folder the unfold refuses to list.
        anAnsweredThread()
        val siblings = listOf("accB" to "sentbox")

        val (_, count) = chip(listOf("inbox"), siblings, accountId = "accA")
        val listed = unfold(listOf("inbox"), siblings, accountId = "accA")

        assertEquals(count, listed.size)
        assertEquals(2, count)

        // The witness: the very same pair, pinned to A instead, does widen both to three — so the
        // pairs are reaching the queries, and it is the ACCOUNT that keeps them out.
        val own = listOf("accA" to "sentbox")
        assertEquals(3, chip(listOf("inbox"), own, accountId = "accA").second)
        assertEquals(3, unfold(listOf("inbox"), own, accountId = "accA").size)
    }

    @Test fun `a thread answered from another account keeps its own Sent folder in the unified list`() {
        // The unified list shows both accounts' conversations, each answered into its own Sent
        // folder. Account B's row must be scoped to B's Sent folder even while account A is the
        // one on screen — the unfold takes the REPRESENTATIVE's account, not the current one.
        insert("a1", sortKey = 100, mailbox = "inbox", accountId = "accA")
        insert("aSent", sortKey = 110, mailbox = "sentA", accountId = "accA")
        insert("b1", sortKey = 200, mailbox = "inbox", accountId = "accB")
        insert("bSent", sortKey = 210, mailbox = "sentB", accountId = "accB")
        val resolution = listOf("accA" to "sentA", "accB" to "sentB")

        // The unified list binds no account: every account's pairs go in, each pinned in SQL.
        assertEquals(resolution, ConversationScope.sentFolders(resolution, accountId = null))

        listOf("accA" to setOf("a1", "aSent"), "accB" to setOf("b1", "bSent")).forEach { (account, expected) ->
            val listed = unfold(listOf("inbox"), resolution, accountId = account)
            assertEquals(expected, listed.toSet())
            // …and the chip of that account's row says the same number.
            assertEquals(listed.size, chip(listOf("inbox"), resolution, accountId = account).second)
        }

        // The witness: the two accounts' Sent folders are different folders, so scoping B's row to
        // A's would be visible — it would list one message, not two.
        assertEquals(setOf("b1"), unfold(listOf("inbox"), listOf("accA" to "sentA"), accountId = "accB").toSet())
    }

    // -- the resolution itself -------------------------------------------------------------

    @Test fun `the viewed folders are always in scope, resolution or not`() {
        // Archive plus Sent, and the same two folders when nothing resolves.
        assertEquals(
            setOf("archive", "sentbox"),
            ConversationScope.folders(listOf("archive"), listOf("accA" to "sentbox"), "accA"),
        )
        assertEquals(setOf("archive"), ConversationScope.folders(listOf("archive"), emptyList(), "accA"))
    }

    @Test fun `a repeated resolution is not bound twice`() {
        // The pairs feed positional binds in the chip query; a duplicate would add a clause that
        // says nothing and shift nothing else, but it is bound per pair, so it must not pile up.
        val doubled = listOf("accA" to "sentbox", "accA" to "sentbox")
        assertEquals(listOf("accA" to "sentbox"), ConversationScope.sentFolders(doubled, "accA"))
    }

    @Test fun `a colliding Sent id does not pull a sibling account's reply into the conversation`() {
        // Both accounts' Sent folder carries the id "sentbox" and both threads the id "T1" — what
        // a server numbering its objects per account routinely produces. The scope is a folder id
        // for the chip's SQL and for the unfold alike, so the account has to hold on both sides.
        anAnsweredThread()
        insert("other", sortKey = 400, mailbox = "sentbox", accountId = "accB", threadId = "T1")
        val resolution = listOf("accA" to "sentbox", "accB" to "sentbox")

        val listed = unfold(listOf("inbox"), resolution, accountId = "accA")
        assertEquals(setOf("in1", "in2", "se1"), listed.toSet())
        assertTrue("accB's message must not be listed under accA", "other" !in listed)
        assertEquals(listed.size, chip(listOf("inbox"), resolution, accountId = "accA").second)
    }
}
