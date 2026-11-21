package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Verifies, against in-memory SQLite, the drawer-badge aggregates
 * ([app.sterna.core.data.db.EmailDao.observeThreadUnreadCounts] /
 * [app.sterna.core.data.db.EmailDao.observeMessageUnreadCounts]): per (accountId, mailboxId),
 * the number of unread threads (conversation mode) or unread messages (flat mode), with the
 * list's not-snoozed filter, and cross-checks the thread aggregate against [conversationSql]'s
 * bold rows — the badge must equal what the list shows.
 *
 * The two aggregates are read out of the shipped DAO by [DaoQuerySource], not retyped, so changing
 * the DAO's SQL changes this test's SQL with it — a retyped copy would keep passing against a
 * query the drawer no longer runs.
 */
class UnreadBadgeSqlTest {
    private lateinit var db: Connection

    private val threadBadgeSql = badgeSql("observeThreadUnreadCounts")

    private val messageBadgeSql = badgeSql("observeMessageUnreadCounts")

    /**
     * The shipped statement of a badge aggregate. Both take no argument today; should one gain a
     * parameter, this fails loudly rather than handing SQLite an unbound `?`.
     */
    private fun badgeSql(functionName: String): String {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery(functionName))
        check(order.isEmpty()) { "EmailDao.$functionName now takes $order — this test must bind them" }
        return sql
    }

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
        id: String, threadId: String?, seen: Int, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, 'subj', 'prev', '', 'N', 'e', ?, 0, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setInt(5, seen); ps.setLong(6, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, untilMillis: Long, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO snoozed VALUES(?, ?, ?)").use {
            it.setString(1, id); it.setString(2, accountId); it.setLong(3, untilMillis); it.executeUpdate()
        }
    }

    private fun setSeen(id: String, seen: Int) {
        db.prepareStatement("UPDATE emails SET seen = ? WHERE id = ?").use {
            it.setInt(1, seen); it.setString(2, id); it.executeUpdate()
        }
    }

    /** Run a badge aggregate; returns (accountId, mailboxId) → count (absent = no badge). */
    private fun counts(sql: String): Map<Pair<String, String>, Int> =
        db.prepareStatement(sql).use { ps ->
            ps.executeQuery().use { rs ->
                buildMap {
                    while (rs.next()) {
                        put(rs.getString("accountId") to rs.getString("mailboxId"), rs.getInt("count"))
                    }
                }
            }
        }

    /** The list's bold rows: conversationSql rows with threadUnread = 0 for one (account, folder). */
    private fun boldConversationRows(accountId: String, mailbox: String): Int {
        val sql = conversationSql(scopeCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false)
        return db.prepareStatement(sql).use { ps ->
            // One (account, folder) scope, bound account first, three times over.
            ps.setString(1, accountId); ps.setString(2, mailbox)
            ps.setString(3, accountId); ps.setString(4, mailbox)
            ps.setString(5, accountId); ps.setString(6, mailbox)
            ps.executeQuery().use { rs ->
                var bold = 0
                while (rs.next()) if (rs.getInt("threadUnread") == 0) bold++
                bold
            }
        }
    }

    @Test fun threadBadgeCountsUnreadThreadsNotMessages() {
        // T1 has TWO unread members in the inbox → one bold row, so the badge says 1, not 2.
        insert("m1", threadId = "T1", seen = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, sortKey = 200)
        insert("r1", threadId = "T2", seen = 1, sortKey = 300) // all-read thread → no badge
        insert("s1", threadId = null, seen = 0, sortKey = 400) // standalone unread → counts

        assertEquals(mapOf(("acc" to "inbox") to 2), counts(threadBadgeSql))
        assertEquals(2, boldConversationRows("acc", "inbox"))
    }

    @Test fun readingANonTopMemberInItsFolderClearsThatFolderBadge() {
        // The saga's critical cell: a conversation in Trash whose top message is read but an
        // older member is unread — the Trash row is bold, the badge must say 1, and reading
        // the non-top member must clear both.
        insert("old", threadId = "T1", seen = 0, sortKey = 100, mailbox = "trash")
        insert("top", threadId = "T1", seen = 1, sortKey = 200, mailbox = "trash")

        assertEquals(mapOf(("acc" to "trash") to 1), counts(threadBadgeSql))
        assertEquals(1, boldConversationRows("acc", "trash"))

        setSeen("old", 1)
        assertEquals(emptyMap<Pair<String, String>, Int>(), counts(threadBadgeSql))
        assertEquals(0, boldConversationRows("acc", "trash"))
    }

    @Test fun threadBadgeIsFolderScoped() {
        // T1: read member in the Inbox, unread member filed in Trash — only Trash badges it (the
        // Inbox row is not bold: its in-folder part is read). T2 is unread on BOTH sides, and it is
        // what makes the folder scope legible in the numbers: the two folders must disagree.
        //
        // Without it the case survived dropping `mailboxId` from the aggregate's inner GROUP BY —
        // the saga's regression, one badge for the whole thread instead of one per folder. SQLite
        // hands a bare mailboxId back from an arbitrary row of the group, and it happened to pick
        // the one the assertion named. No arrangement of one row per group can produce {1, 2}.
        insert("in1", threadId = "T1", seen = 1, sortKey = 200, mailbox = "inbox")
        insert("tr1", threadId = "T1", seen = 0, sortKey = 100, mailbox = "trash")
        insert("in2", threadId = "T2", seen = 0, sortKey = 300, mailbox = "inbox")
        insert("tr2", threadId = "T2", seen = 0, sortKey = 50, mailbox = "trash")

        assertEquals(mapOf(("acc" to "inbox") to 1, ("acc" to "trash") to 2), counts(threadBadgeSql))
        assertEquals(1, boldConversationRows("acc", "inbox"))
        assertEquals(2, boldConversationRows("acc", "trash"))
    }

    @Test fun snoozedUnreadIsExcludedFromBothBadges() {
        // The two are in DIFFERENT folders, so the aggregates say WHICH one survives and not merely
        // how many: with both in the Inbox the counts are symmetric, and reversing the comparison
        // (`until > now` → `<`) — hiding every message whose snooze has EXPIRED and badging the ones
        // still asleep — read exactly the same, 1 and 1.
        insert("z1", threadId = null, seen = 0, sortKey = 100) // snoozed into the future → hidden
        insert("z2", threadId = null, seen = 0, sortKey = 200, mailbox = "archive") // expired → visible
        snooze("z1", untilMillis = Long.MAX_VALUE)
        snooze("z2", untilMillis = 1)

        assertEquals(mapOf(("acc" to "archive") to 1), counts(threadBadgeSql))
        assertEquals(mapOf(("acc" to "archive") to 1), counts(messageBadgeSql))
    }

    @Test fun badgesAreAccountScoped() {
        // Two accounts whose inbox shares the same server-assigned mailbox id (and thread id):
        // each account keeps its own badge; neither inflates the other.
        insert("a1", threadId = "T1", seen = 0, sortKey = 100, accountId = "accA")
        insert("b1", threadId = "T1", seen = 0, sortKey = 200, accountId = "accB")
        insert("b2", threadId = "T1", seen = 0, sortKey = 300, accountId = "accB")

        assertEquals(
            mapOf(("accA" to "inbox") to 1, ("accB" to "inbox") to 1),
            counts(threadBadgeSql),
        )
        assertEquals(
            mapOf(("accA" to "inbox") to 1, ("accB" to "inbox") to 2),
            counts(messageBadgeSql),
        )
    }

    @Test fun snoozeInOneAccountKeepsTheSiblingAccountsBadge() {
        // Sub-accounts of one login (issue #31) can mint the SAME email id: account A
        // snoozing its "e1" must clear only its own badge, not account B's.
        insert("e1", threadId = null, seen = 0, sortKey = 100, accountId = "accA")
        insert("e1", threadId = null, seen = 0, sortKey = 200, accountId = "accB")
        snooze("e1", untilMillis = Long.MAX_VALUE, accountId = "accA")

        assertEquals(mapOf(("accB" to "inbox") to 1), counts(threadBadgeSql))
        assertEquals(mapOf(("accB" to "inbox") to 1), counts(messageBadgeSql))
    }

    @Test fun messageBadgeCountsUnreadMessagesPerFolder() {
        insert("m1", threadId = "T1", seen = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, sortKey = 200) // same thread, still 2 messages flat
        insert("m3", threadId = null, seen = 1, sortKey = 300) // read → not counted
        insert("t1", threadId = null, seen = 0, sortKey = 400, mailbox = "trash")

        assertEquals(
            mapOf(("acc" to "inbox") to 2, ("acc" to "trash") to 1),
            counts(messageBadgeSql),
        )
    }

    @Test fun emptyCacheYieldsNoBadgeRows() {
        // A never-synced folder has no cached rows: the aggregate returns nothing for it, the
        // repository maps that to 0 and the drawer draws no badge (never a made-up count).
        assertEquals(emptyMap<Pair<String, String>, Int>(), counts(threadBadgeSql))
        assertEquals(emptyMap<Pair<String, String>, Int>(), counts(messageBadgeSql))
    }
}
