package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * The conversation view's cross-query invariants, run against in-memory SQLite.
 */
class ConversationInvariantsSqlTest {
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
        id: String, threadId: String?, sortKey: Long,
        seen: Int = 1, mailbox: String = "inbox", accountId: String = "acc",
        subject: String = "subj",
    ) {
        db.prepareStatement(
            "INSERT INTO emails(id, accountId, mailboxId, threadId, subject, preview, receivedAt, " +
                "fromName, fromEmail, seen, flagged, hasAttachment, sortKey) " +
                "VALUES(?, ?, ?, ?, ?, 'prev', '', 'N', 'e', ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, threadId); ps.setString(5, subject)
            ps.setInt(6, seen); ps.setLong(7, sortKey)
            ps.executeUpdate()
        }
    }

    private fun exec(sql: String) = db.createStatement().use { it.executeUpdate(sql) }

    /** One collapsed row: the representative plus the three counts the list draws from. */
    private data class Row(
        val id: String,
        val accountId: String,
        val threadId: String?,
        val threadCount: Int,
        val threadTotal: Int,
        val threadUnread: Int,
    ) {
        /** The key the UI unfolds on: `COALESCE(threadId, id)` (ConversationExpansion.threadKey). */
        val threadKey: String get() = threadId ?: id
    }

    /**
     * The collapsed list, bound exactly as `conversationQuery` binds it: the (account, folder)
     */
    private fun conversation(
        vararg mailboxes: String,
        accountId: String? = null,
        accounts: List<String> = listOfNotNull(accountId),
        sent: List<Pair<String, String>> = emptyList(),
        sort: SortOrder = SortOrder.DATE_DESC,
        unreadOnly: Boolean = false,
    ): List<Row> {
        val scopes = accounts.flatMap { acc -> mailboxes.map { acc to it } }
        val sql = conversationSql(scopes.size, sort, unreadOnly, sent.size)
        val pairs = scopes.flatMap { listOf(it.first, it.second) }
        val args = buildList<String> {
            addAll(pairs)
            addAll(pairs); sent.forEach { add(it.first); add(it.second) }
            addAll(pairs)
        }
        return db.prepareStatement(sql).use { ps ->
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Row(
                                id = rs.getString("id"),
                                accountId = rs.getString("accountId"),
                                threadId = rs.getString("threadId"),
                                threadCount = rs.getInt("threadCount"),
                                threadTotal = rs.getInt("threadTotal"),
                                threadUnread = rs.getInt("threadUnread"),
                            ),
                        )
                    }
                }
            }
        }
    }

    /** The flat list ([pagingSql]), bound as `pagingQuery` binds it: (account id, mailbox id)
     *  pairs, account first — one per folder of each account in scope. */
    private fun flat(
        vararg mailboxes: String,
        accountId: String? = null,
        accounts: List<String> = listOfNotNull(accountId),
        sort: SortOrder = SortOrder.DATE_DESC,
        unreadOnly: Boolean = false,
    ): List<String> {
        val scopes = accounts.flatMap { acc -> mailboxes.map { acc to it } }
        val sql = pagingSql(scopes.size, sort, unreadOnly)
        return db.prepareStatement(sql).use { ps ->
            scopes.flatMap { listOf(it.first, it.second) }
                .forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /**
     * What unfolding a conversation row shows: EmailDao.cachedThreadEmails over the viewed
     */
    private fun unfold(accountId: String, vararg mailboxes: String, threadKey: String): List<String> {
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
                        else -> error("unexpected parameter :$name in cachedThreadEmails")
                    },
                )
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    /** A drawer badge aggregate, straight from the DAO source: (account, folder) → count. */
    private fun badge(functionName: String): Map<Pair<String, String>, Int> =
        db.prepareStatement(DaoQuerySource.emailDaoQuery(functionName)).use { ps ->
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) put(rs.getString("accountId") to rs.getString("mailboxId"), rs.getInt("count"))
                }
            }
        }

    // -- C1 -------------------------------------------------------------------------------
    //
    // The one fact that reframes everything else here.

    @Test fun messagesWithoutAThreadKeyGroupNothingAtAll() {
        // The SQL is protocol-agnostic: a row with no thread key is its own conversation, a row
        // with one groups with its siblings, and nothing else decides. A row without a key is an
        insert("m1", threadId = null, sortKey = 100, subject = "Re: budget")
        insert("m2", threadId = null, sortKey = 200, subject = "Re: budget")
        insert("m3", threadId = null, sortKey = 300, subject = "Re: budget")

        val rows = conversation("inbox", accountId = "acc")
        assertEquals(listOf("m3", "m2", "m1"), rows.map { it.id })
        assertEquals(listOf(1, 1, 1), rows.map { it.threadCount })
        // Stated the way the reader experiences it: turning conversation view on changes nothing.
        assertEquals(flat("inbox", accountId = "acc"), rows.map { it.id })

        // The witness: the SAME three messages, given a thread key — the one a JMAP server
        // supplies, or the one `imapThreadKey` rebuilds from the headers — collapse to a single
        // row of three. Nothing but the null threadId separates the two outcomes.
        exec("UPDATE emails SET threadId = 'T1'")
        val grouped = conversation("inbox", accountId = "acc")
        assertEquals(listOf("m3"), grouped.map { it.id })
        assertEquals(3, grouped.single().threadCount)
        assertNotEquals(flat("inbox", accountId = "acc"), grouped.map { it.id })
    }

    // -- C2 -------------------------------------------------------------------------------

    @Test fun theUnifiedListCollapsesTheKeyedAccountAndLeavesTheKeylessOneFlat() {
        // Same folder id, same view, one query — and two behaviours, decided row by row by
        // whether a thread key is present, never by the account's protocol. Account A has a
        // three-message thread under one key; account B's three rows carry no key at all (a cache
        // from before IMAP threading, or messages with no threading header).
        insert("a1", threadId = "T1", sortKey = 610, accountId = "accA")
        insert("a2", threadId = "T1", sortKey = 620, accountId = "accA")
        insert("a3", threadId = "T1", sortKey = 630, accountId = "accA")
        insert("b1", threadId = null, sortKey = 530, accountId = "accB")
        insert("b2", threadId = null, sortKey = 520, accountId = "accB")
        insert("b3", threadId = null, sortKey = 510, accountId = "accB")

        // Unified: one (account, folder) pair per configured account, never the shared id alone.
        val rows = conversation("inbox", accounts = listOf("accA", "accB"))
        assertEquals(listOf("a3", "b1", "b2", "b3"), rows.map { it.id })
        assertEquals(listOf(3, 1, 1, 1), rows.map { it.threadCount })
        // Half the list is collapsed and half is not, with nothing on screen to say why.
        assertEquals(listOf("accA"), rows.filter { it.threadCount > 1 }.map { it.accountId })
        assertEquals(listOf("accB", "accB", "accB"), rows.filter { it.threadCount == 1 }.map { it.accountId })

        // The witness: flat mode lists all six, and account B's three rows are IDENTICAL in both
        // modes — the collapse is not a property of the list, it is a property of the rows' keys.
        val flatIds = flat("inbox", accounts = listOf("accA", "accB"))
        assertEquals(listOf("a3", "a2", "a1", "b1", "b2", "b3"), flatIds)
        assertEquals(
            flatIds.filter { it.startsWith("b") },
            rows.map { it.id }.filter { it.startsWith("b") },
        )
    }

    // -- C3 -------------------------------------------------------------------------------

    @Test fun switchingBetweenConversationAndFlatViewLosesNoMessage() {
        // A thread of three and two loose messages. Whatever the mode, the folder holds five
        // messages and the reader must be able to reach all five.
        insert("t1", threadId = "T1", sortKey = 100)
        insert("t2", threadId = "T1", sortKey = 200)
        insert("t3", threadId = "T1", sortKey = 300)
        insert("s1", threadId = null, sortKey = 150)
        insert("s2", threadId = null, sortKey = 250)

        val rows = conversation("inbox", accountId = "acc")
        // What the conversation view can actually reach: each row's representative, plus what
        // unfolding that row lists (the DAO's own query, bound as the unfold binds it).
        val reachable = rows.flatMap { unfold(it.accountId, "inbox", threadKey = it.threadKey) }.toSet()
        val flatIds = flat("inbox", accountId = "acc").toSet()

        assertEquals(flatIds, reachable)
        assertEquals(setOf("t1", "t2", "t3", "s1", "s2"), reachable)
        // The chips account for every message exactly once, which is the same statement in the
        // list's own arithmetic.
        assertEquals(flatIds.size, rows.sumOf { it.threadCount })

        // The witness: the collapsed rows ALONE do not cover the folder — t1 and t2 are reachable
        // only by unfolding. Without this, the equality above would hold for a broken unfold.
        assertNotEquals(flatIds, rows.map { it.id }.toSet())
        assertEquals(setOf("t1", "t2"), flatIds - rows.map { it.id }.toSet())
    }

    // -- C4 -------------------------------------------------------------------------------

    @Test fun trashingTheNewestMemberShrinksTheChipAndHandsTheRowToTheNextMember() {
        insert("m1", threadId = "T1", sortKey = 100)
        insert("m2", threadId = "T1", sortKey = 200)
        insert("m3", threadId = "T1", sortKey = 300) // newest → the row the reader sees

        val before = conversation("inbox", accountId = "acc").single()
        assertEquals("m3", before.id)
        assertEquals(3, before.threadCount)
        assertEquals(3, before.threadTotal)
        assertEquals(listOf("m3", "m2", "m1"), unfold("acc", "inbox", threadKey = "T1"))

        exec("UPDATE emails SET mailboxId = 'trash' WHERE id = 'm3'")

        val after = conversation("inbox", accountId = "acc").single()
        assertEquals("m2", after.id) // the representative moved down to the next in-view member
        assertEquals(2, after.threadCount)
        assertEquals(listOf("m2", "m1"), unfold("acc", "inbox", threadKey = "T1")) // chip = unfold
        // threadTotal is unchanged: the message still exists, it is only somewhere else — which is
        // why the row stays expandable. A chip that had followed the total would still say 3.
        assertEquals(3, after.threadTotal)

        // The witness: the trashed member is not gone, it is the Trash view's own row now.
        val trash = conversation("trash", accountId = "acc").single()
        assertEquals("m3", trash.id)
        assertEquals(1, trash.threadCount)
    }

    // -- C5 -------------------------------------------------------------------------------

    @Test fun theDrawerBadgeCountsExactlyTheBoldRowsOfTheModeItIsShowing() {
        // A thread of three with two unread members, plus one unread loose message. In
        // conversation view the folder shows two bold ROWS; in flat view, three bold MESSAGES.
        insert("t1", threadId = "T1", sortKey = 100, seen = 0)
        insert("t2", threadId = "T1", sortKey = 200, seen = 0)
        insert("t3", threadId = "T1", sortKey = 300, seen = 1)
        insert("s1", threadId = null, sortKey = 400, seen = 0)

        val conversationBadge = badge("observeThreadUnreadCounts")[("acc" to "inbox")]
        val messageBadge = badge("observeMessageUnreadCounts")[("acc" to "inbox")]

        val boldRows = conversation("inbox", accountId = "acc").count { it.threadUnread == 0 }
        val boldMessages = flat("inbox", accountId = "acc", unreadOnly = true).size

        assertEquals(boldRows, conversationBadge)
        assertEquals(boldMessages, messageBadge)
        assertEquals(2, boldRows)
        assertEquals(3, boldMessages)
        // The unread-only FILTER agrees with the badge too — the third query describing the same
        // set. A badge that matched the list but not the filter would still strand the reader on
        // an empty "unread" screen with a lit badge.
        assertEquals(conversationBadge, conversation("inbox", accountId = "acc", unreadOnly = true).size)

        // The witness: the two modes genuinely disagree here (2 vs 3), so "badge equals rows" is
        // not being satisfied by both numbers happening to be the same. And picking the WRONG
        // aggregate for the mode is visibly wrong, which is what the drawer chooses between.
        assertNotEquals(conversationBadge, messageBadge)
        assertNotEquals(messageBadge, boldRows)
    }

    // -- C6 -------------------------------------------------------------------------------

    @Test fun aThreadSplitAcrossInboxAndArchiveGetsAChipPerFolderMatchingItsUnfold() {
        // One conversation, four messages, three folders: two still in the Inbox, one archived,
        // one reply in Sent. The Inbox row and the Archive row are two views of the same thread
        // and each must count what ITS unfold will show — Sent replies included, the other
        // folder's members excluded.
        insert("in1", threadId = "T1", sortKey = 100, mailbox = "inbox")
        insert("in2", threadId = "T1", sortKey = 200, mailbox = "inbox")
        insert("ar1", threadId = "T1", sortKey = 150, mailbox = "archive")
        insert("se1", threadId = "T1", sortKey = 250, mailbox = "sent")
        val sent = listOf("acc" to "sent")

        val fromInbox = conversation("inbox", accountId = "acc", sent = sent).single()
        assertEquals("in2", fromInbox.id) // newest IN-VIEW member, not the newer Sent reply
        assertEquals(3, fromInbox.threadCount)
        assertEquals(
            listOf("se1", "in2", "in1"),
            unfold("acc", "inbox", "sent", threadKey = fromInbox.threadKey),
        )

        val fromArchive = conversation("archive", accountId = "acc", sent = sent).single()
        assertEquals("ar1", fromArchive.id)
        assertEquals(2, fromArchive.threadCount)
        assertEquals(
            listOf("se1", "ar1"),
            unfold("acc", "archive", "sent", threadKey = fromArchive.threadKey),
        )

        // The invariant, stated once per folder: the chip IS the size of the unfold.
        listOf(fromInbox to arrayOf("inbox", "sent"), fromArchive to arrayOf("archive", "sent"))
            .forEach { (row, boxes) ->
                assertEquals(row.threadCount, unfold("acc", *boxes, threadKey = row.threadKey).size)
                // …while the account-wide total, which only gates expandability, sees all four.
                assertEquals(4, row.threadTotal)
            }

        // The witness: the two folders' unfolds are genuinely different sets, so "chip equals
        // unfold" is not a constant dressed as an invariant.
        assertNotEquals(
            unfold("acc", "inbox", "sent", threadKey = "T1").toSet(),
            unfold("acc", "archive", "sent", threadKey = "T1").toSet(),
        )
    }
}
