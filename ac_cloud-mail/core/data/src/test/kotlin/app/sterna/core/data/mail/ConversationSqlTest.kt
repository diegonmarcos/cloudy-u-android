package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager

/**
 * Runs the real conversation-grouping SQL ([conversationSql]) against an in-memory
 * SQLite engine, so the thread collapse / count / unread / snooze logic is verified
 * without an Android device.
 */
class ConversationSqlTest {
    private lateinit var db: Connection

    /** Two same-server accounts whose inbox carries the same server-assigned id — the unified
     *  list's scope for them: one (account, folder) pair EACH, never the shared id alone. */
    private val BOTH_ACCOUNTS = listOf("accA" to "inbox", "accB" to "inbox")

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
        id: String, threadId: String?, seen: Int, flagged: Int, sortKey: Long,
        mailbox: String = "inbox", accountId: String = "acc",
        // Only the sort-order cases care about these; every other case leaves them equal so
        // the subject/sender orders tie and the discriminating column is the one under test.
        subject: String = "subj", fromName: String = "N",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, ?, ?, 'prev', '', ?, 'e', ?, ?, 0, ?)",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox); ps.setString(4, threadId)
            ps.setString(5, subject); ps.setString(6, fromName)
            ps.setInt(7, seen); ps.setInt(8, flagged); ps.setLong(9, sortKey)
            ps.executeUpdate()
        }
    }

    private fun snooze(id: String, untilMillis: Long, accountId: String = "acc") {
        db.prepareStatement("INSERT INTO snoozed VALUES(?, ?, ?)").use {
            it.setString(1, id); it.setString(2, accountId); it.setLong(3, untilMillis); it.executeUpdate()
        }
    }

    /**
     * Run the grouping SQL over [scopes] — (account id, mailbox id) pairs, as the app binds them
     * ([conversationQuery]); the default is the single-account "acc"/"inbox" fixture. Returns rows
     * as maps.
     */
    private fun run(
        sort: SortOrder = SortOrder.DATE_DESC,
        unreadOnly: Boolean = false,
        scopes: List<Pair<String, String>> = listOf("acc" to "inbox"),
    ): List<Map<String, Any?>> {
        val sql = conversationSql(scopeCount = scopes.size, sort = sort, unreadOnly = unreadOnly)
        return db.prepareStatement(sql).use { ps ->
            // in-view sub-query, in-view count sub-query, outer WHERE — each binds every scope,
            // account id first.
            val args = (1..3).flatMap { scopes.flatMap { (acc, mb) -> listOf(acc, mb) } }
            args.forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            mapOf(
                                "id" to rs.getString("id"),
                                "threadCount" to rs.getInt("threadCount"),
                                "threadTotal" to rs.getInt("threadTotal"),
                                "threadUnread" to rs.getInt("threadUnread"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun collapsesThreadToLatestMessageWithCountAndUnread() {
        // A 2-message thread (m2 newest, m1 read / m2 unread) + a standalone read message.
        insert("m1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200)
        insert("s1", threadId = null, seen = 1, flagged = 0, sortKey = 150)

        val rows = run()
        assertEquals(2, rows.size)
        // Newest-first: the thread's representative (m2) then the standalone.
        assertEquals("m2", rows[0]["id"])
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(0, rows[0]["threadUnread"]) // 0 = has an unread message
        assertEquals("s1", rows[1]["id"])
        assertEquals(1, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadUnread"]) // 1 = all read
    }

    @Test fun unreadOnlyKeepsThreadsWithAnyUnread() {
        insert("m1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100)
        insert("m2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200) // unread → thread kept
        insert("s1", threadId = null, seen = 1, flagged = 0, sortKey = 150) // read → dropped

        val rows = run(unreadOnly = true)
        assertEquals(1, rows.size)
        assertEquals("m2", rows[0]["id"])
    }

    @Test fun snoozedMessagesAreExcluded() {
        insert("s1", threadId = null, seen = 0, flagged = 0, sortKey = 100)
        insert("s2", threadId = null, seen = 0, flagged = 0, sortKey = 300) // would be first…
        snooze("s2", untilMillis = Long.MAX_VALUE) // …but snoozed into the future

        val rows = run()
        assertEquals(1, rows.size)
        assertEquals("s1", rows[0]["id"])
    }

    @Test fun accountScopeExcludesOtherAccountSharingAMailboxId() {
        // Two accounts whose inbox shares the same server-assigned mailbox id ("inbox")
        // — the case that made a single-account folder view show a mix of both accounts.
        insert("a1", threadId = null, seen = 1, flagged = 0, sortKey = 100, accountId = "accA")
        insert("b1", threadId = null, seen = 1, flagged = 0, sortKey = 200, accountId = "accB")

        assertEquals(listOf("a1"), runScoped("accA")) // only account A's mail; b1 is excluded
    }

    /** Single-account bound run for [accountId]'s "inbox"; returns the row ids. */
    private fun runScoped(accountId: String): List<String> =
        run(scopes = listOf(accountId to "inbox")).map { it["id"] as String }

    @Test fun sameEmailIdAcrossAccountsStaysScoped() {
        // Two sub-accounts of one login (issue #31) whose server minted the SAME email id
        // in the SAME-named mailbox — the composite (accountId, id) key case. Each scoped
        // view must show exactly its own row.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, accountId = "accA")
        insert("e1", threadId = null, seen = 1, flagged = 0, sortKey = 200, accountId = "accB")

        assertEquals(listOf("e1"), runScoped("accA"))
        assertEquals(listOf("e1"), runScoped("accB"))
    }

    @Test fun snoozeIsScopedToItsAccount() {
        // Account A snoozes its "e1"; account B's message that happens to share the id
        // (same-server sub-accounts, issue #31) must stay visible.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, accountId = "accB")
        snooze("e1", untilMillis = Long.MAX_VALUE, accountId = "accA")

        assertEquals(emptyList<String>(), runScoped("accA")) // snoozed away
        assertEquals(listOf("e1"), runScoped("accB")) // untouched by A's snooze
    }

    /** Run the SQL as bound in production for the Inbox of account "acc" with a Sent folder:
     *  `g` and the outer WHERE scope to the Inbox; the chip sub-query `c` also takes the
     *  account-pinned ("acc", "sent") pair. */
    private fun runWithSent(): List<Map<String, Any?>> {
        val sql = conversationSql(
            scopeCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false, sentMailboxCount = 1,
        )
        return db.prepareStatement(sql).use { ps ->
            ps.setString(1, "acc"); ps.setString(2, "inbox") // in-view sub-query g
            ps.setString(3, "acc"); ps.setString(4, "inbox"); ps.setString(5, "acc"); ps.setString(6, "sent") // chip c
            ps.setString(7, "acc"); ps.setString(8, "inbox") // outer WHERE
            ps.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            mapOf(
                                "id" to rs.getString("id"),
                                "threadCount" to rs.getInt("threadCount"),
                                "threadTotal" to rs.getInt("threadTotal"),
                            ),
                        )
                    }
                }
            }
        }
    }

    @Test fun chipCountsFolderPlusSentMembersMatchingTheUnfoldedConversation() {
        // A thread with one message in the Inbox and its reply filed in Sent: the unfolded
        // conversation shows both, so the chip says 2 — while the representative stays the
        // in-view (Inbox) message.
        insert("in1", threadId = "T1", seen = 0, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, mailbox = "sent")

        val rows = runWithSent()
        assertEquals(1, rows.size)
        assertEquals("in1", rows[0]["id"])      // representative is the in-view (Inbox) message…
        assertEquals(2, rows[0]["threadCount"]) // …the chip counts Inbox + Sent members…
        assertEquals(2, rows[0]["threadTotal"]) // …and the account-wide total gates expandability.
    }

    @Test fun chipIgnoresTrashMembersEvenWithSentBound() {
        // Folder member + Sent reply + trashed member: the unfolded conversation shows 2
        // (Inbox + Sent), so the chip says 2; the trashed member neither counts nor elects
        // the representative, but keeps threadTotal at 3.
        insert("in1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, mailbox = "sent")
        insert("tr1", threadId = "T1", seen = 1, flagged = 0, sortKey = 300, mailbox = "trash")

        val rows = runWithSent()
        assertEquals(1, rows.size)
        assertEquals("in1", rows[0]["id"]) // representative = newest IN-VIEW member, not tr1/sent1
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(3, rows[0]["threadTotal"])
    }

    @Test fun threadWithOnlySentMembersSurfacesNoRow() {
        // Row presence stays strictly folder-scoped: Sent members widen the chip of an
        // in-folder thread but never conjure a row of their own into the Inbox.
        insert("sent1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "sent")

        assertEquals(0, runWithSent().size)
    }

    @Test fun threadCountIgnoresMembersOutsideTheViewedFolder() {
        // No Sent folder bound: two members in the viewed folder, one filed elsewhere —
        // chip 2, total 3.
        insert("in1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "inbox")
        insert("in2", threadId = "T1", seen = 0, flagged = 0, sortKey = 200, mailbox = "inbox")
        insert("tr1", threadId = "T1", seen = 1, flagged = 0, sortKey = 300, mailbox = "trash")

        val rows = run()
        assertEquals(1, rows.size)
        assertEquals("in2", rows[0]["id"]) // representative = newest IN-VIEW member, not tr1
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(3, rows[0]["threadTotal"])
    }

    @Test fun countsDoNotMixAccountsSharingMailboxAndThreadIds() {
        // Unified view (no account bind): two accounts whose server assigned the same
        // mailbox AND thread ids — one row EACH (grouping is per (account, thread)), and
        // the counts stay per the representative's account.
        insert("a1", threadId = "T1", seen = 0, flagged = 0, sortKey = 200, accountId = "accA")
        insert("b1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, accountId = "accB")

        val rows = run(scopes = BOTH_ACCOUNTS)
        assertEquals(2, rows.size)
        assertEquals("a1", rows[0]["id"])
        assertEquals(1, rows[0]["threadCount"]) // accB's b1 must not inflate the chip
        assertEquals(1, rows[0]["threadTotal"])
        assertEquals("b1", rows[1]["id"])
        assertEquals(1, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadTotal"])
    }

    @Test fun unifiedViewKeepsBothAccountsConversationsWhenThreadIdsCollide() {
        // The data-loss case: two accounts of the same server carry the SAME thread id, each
        // with a 2-message conversation. Grouped on the thread key alone, the two collapsed
        // into a single row and only the newest account's conversation survived — the other
        // account's mail was simply absent from the unified list.
        insert("a1", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, accountId = "accA")
        insert("a2", threadId = "T1", seen = 0, flagged = 0, sortKey = 400, accountId = "accA")
        insert("b1", threadId = "T1", seen = 1, flagged = 0, sortKey = 200, accountId = "accB")
        insert("b2", threadId = "T1", seen = 1, flagged = 0, sortKey = 300, accountId = "accB")

        val rows = run(scopes = BOTH_ACCOUNTS)
        assertEquals(2, rows.size)
        // One representative per (account, thread), each with its own count and unread state.
        assertEquals("a2", rows[0]["id"])
        assertEquals(2, rows[0]["threadCount"])
        assertEquals(0, rows[0]["threadUnread"]) // accA's conversation has an unread member
        assertEquals("b2", rows[1]["id"])
        assertEquals(2, rows[1]["threadCount"])
        assertEquals(1, rows[1]["threadUnread"]) // accB's is fully read
    }

    @Test fun unreadOnlyKeepsEachAccountsThreadIndependently() {
        // Colliding thread ids again, filtered: accB's conversation is unread and must show
        // even though accA's — grouped under the same thread key — is fully read.
        insert("a1", threadId = "T1", seen = 1, flagged = 0, sortKey = 400, accountId = "accA")
        insert("b1", threadId = "T1", seen = 0, flagged = 0, sortKey = 100, accountId = "accB")

        val rows = run(unreadOnly = true, scopes = BOTH_ACCOUNTS)
        assertEquals(listOf("b1"), rows.map { it["id"] })
    }

    @Test fun sameEmailIdAndNoThreadAcrossAccountsKeepsBothRows() {
        // Thread-less messages group on their own id, so a colliding EMAIL id is the same
        // collapse hazard: both accounts' messages must still be listed.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, accountId = "accB")

        assertEquals(2, run(scopes = BOTH_ACCOUNTS).size)
    }

    @Test fun sentPairDoesNotLeakACollidingMailboxIdAcrossAccounts() {
        // Unified view: account B's Sent folder id ("X") collides with a plain folder of
        // account A. A's chip must not count its "X"-folder member (X is not A's Sent) —
        // the pair binding pins the Sent widening to account B — while B's chip still
        // counts its Sent reply.
        insert("a1", threadId = "T1", seen = 0, flagged = 0, sortKey = 200, accountId = "accA")
        insert("ax", threadId = "T1", seen = 1, flagged = 0, sortKey = 100, mailbox = "X", accountId = "accA")
        insert("b1", threadId = "T2", seen = 0, flagged = 0, sortKey = 300, accountId = "accB")
        insert("bx", threadId = "T2", seen = 1, flagged = 0, sortKey = 250, mailbox = "X", accountId = "accB")

        val sql = conversationSql(
            scopeCount = 2, sort = SortOrder.DATE_DESC, unreadOnly = false, sentMailboxCount = 1,
        )
        val rows = db.prepareStatement(sql).use { ps ->
            ps.setString(1, "accA"); ps.setString(2, "inbox"); ps.setString(3, "accB"); ps.setString(4, "inbox") // g
            // chip c: both accounts' inboxes + accB's Sent pair
            ps.setString(5, "accA"); ps.setString(6, "inbox"); ps.setString(7, "accB"); ps.setString(8, "inbox")
            ps.setString(9, "accB"); ps.setString(10, "X")
            ps.setString(11, "accA"); ps.setString(12, "inbox"); ps.setString(13, "accB"); ps.setString(14, "inbox") // outer
            ps.executeQuery().use { rs ->
                buildList { while (rs.next()) add(rs.getString("id") to rs.getInt("threadCount")) }
            }
        }
        assertEquals(listOf("b1" to 2, "a1" to 1), rows)
    }

    // -- sort orders and the favourite pin (issue #111) -----------------------------------
    //
    // Until 1.4.5 every ORDER BY was prefixed with `flagged DESC`, so each of the five menu
    // entries silently meant "…, except the starred ones". Pinning is now its own entry,
    // SortOrder.FLAGGED_FIRST, and the other five must order on their own criterion alone.
    //
    // One fixture serves all six orders, arranged so that the starred rows LOSE under every
    // non-pinning order — otherwise the assertion would pass whether or not the prefix is
    // there. `p` is unread, mid-dated, first alphabetically by subject AND sender; `s2`/`s`
    // are starred, read, and sort behind it on every criterion but the pin.
    private fun insertSortFixture() {
        insert("p", threadId = null, seen = 0, flagged = 0, sortKey = 200, subject = "aaa", fromName = "Anna")
        insert("s", threadId = null, seen = 1, flagged = 1, sortKey = 100, subject = "zzz", fromName = "Zoe")
        insert("s2", threadId = null, seen = 1, flagged = 1, sortKey = 300, subject = "mmm", fromName = "Mona")
    }

    private fun ids(rows: List<Map<String, Any?>>) = rows.map { it["id"] }

    @Test fun conversationOrdersDoNotPinFavourites() {
        insertSortFixture()

        assertEquals(listOf("s2", "p", "s"), ids(run(SortOrder.DATE_DESC)))
        assertEquals(listOf("s", "p", "s2"), ids(run(SortOrder.DATE_ASC)))
        assertEquals(listOf("p", "s2", "s"), ids(run(SortOrder.SUBJECT)))
        assertEquals(listOf("p", "s2", "s"), ids(run(SortOrder.SENDER)))
        // The starkest case: "Unread first" used to put a starred READ message above an
        // unread one, which is the one thing the entry's name promises it will not do.
        assertEquals(listOf("p", "s2", "s"), ids(run(SortOrder.UNREAD_FIRST)))
    }

    @Test fun conversationFavouritesFirstPinsWhenChosen() {
        insertSortFixture()

        // The pre-1.4.5 behaviour, now reachable on purpose: starred at the top, newest-first
        // inside each group.
        assertEquals(listOf("s2", "s", "p"), ids(run(SortOrder.FLAGGED_FIRST)))
    }

    @Test fun favouritesFirstOrdersOnTheStarTheRowActuallyDraws() {
        // Collapsed rows draw the REPRESENTATIVE message's star, and that is what the order
        // follows — not "any message of the thread is starred". Ordering on the thread-wide
        // MAX(flagged) was tried and rejected: a thread would then sit at the top wearing an
        // empty star, and tapping that star twice would not shift it, because an invisible
        // older message held it there. Pinning the reader cannot see or undo is the very
        // WYSIWYG break this change removes.
        //
        // Thread T carries a star on an OLD message; its representative t2 is unstarred, so T
        // does not pin. Thread U's representative u2 IS starred, so U does.
        insert("t1", threadId = "T", seen = 1, flagged = 1, sortKey = 100) // starred, older, not drawn
        insert("t2", threadId = "T", seen = 1, flagged = 0, sortKey = 200) // representative, unstarred
        insert("u1", threadId = "U", seen = 1, flagged = 0, sortKey = 250)
        insert("u2", threadId = "U", seen = 1, flagged = 1, sortKey = 300) // representative, starred
        insert("plain", threadId = null, seen = 1, flagged = 0, sortKey = 400)

        val rows = run(SortOrder.FLAGGED_FIRST)
        assertEquals(listOf("u2", "plain", "t2"), ids(rows))
        assertEquals(2, rows[0]["threadCount"])
        // Worth stating plainly: this distinction arises on both protocols now that IMAP rows
        // carry a header-rebuilt threadId (imapThreadKey). Only a message with a null threadId —
        // one with no threading header at all — is its own thread, and there the representative's
        // flag IS the thread's.
    }

    @Test fun favouritesFirstCoexistsWithTheUnreadOnlyFilter() {
        // The only combination of the new order left unexercised: FLAGGED_FIRST alongside
        // unreadOnly, whose HAVING MIN(seen) = 0 sits in the same grouped query.
        insert("fu", threadId = null, seen = 0, flagged = 1, sortKey = 100) // starred + unread → kept, pinned
        insert("fr", threadId = null, seen = 1, flagged = 1, sortKey = 400) // starred but read → filtered out
        insert("pu", threadId = null, seen = 0, flagged = 0, sortKey = 300) // plain + unread → kept
        insert("pr", threadId = null, seen = 1, flagged = 0, sortKey = 200) // plain + read → filtered out

        // The star pins inside the filtered set; it does not smuggle a read message back in.
        assertEquals(listOf("fu", "pu"), ids(run(SortOrder.FLAGGED_FIRST, unreadOnly = true)))
        assertEquals(listOf("fu", "pu"), runFlat("acc" to "inbox", sort = SortOrder.FLAGGED_FIRST, unreadOnly = true))
    }

    // -- flat (uncollapsed) mode, colliding mailbox ids -----------------------------------
    //
    // Conversation mode is exercised above; the same views also run FLAT, through a different
    // query ([pagingSql]), which carried the same defect and is fixed the same way. Stalwart
    // numbers mailboxes per account from "a", so on one server EVERY account's Inbox is "a", its
    // Trash "b", its Junk "c" — the ids collide wholesale, which is what makes this worth pinning
    // rather than assuming (Codeberg #107 probe, #121 fix).

    /** Flat list over [scopes] — (account id, mailbox id) pairs, one for a folder view, one per
     *  account for the unified inbox. Bind order per scope: account id, then mailbox id. */
    private fun runFlat(
        vararg scopes: Pair<String, String>,
        sort: SortOrder = SortOrder.DATE_DESC,
        unreadOnly: Boolean = false,
    ): List<String> {
        val sql = pagingSql(scopeCount = scopes.size, sort = sort, unreadOnly = unreadOnly)
        return db.prepareStatement(sql).use { ps ->
            scopes.flatMap { listOf(it.first, it.second) }
                .forEachIndexed { i, a -> ps.setString(i + 1, a) }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }
    }

    @Test fun flatUnifiedSpansAccountsThatShareTheirInboxId() {
        // The intended case: ten Stalwart accounts all call their Inbox "a", and all ten must
        // surface. They do because ten PAIRS are bound, one per account — not because the account
        // is left out of the filter.
        insert("a1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("b1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("b1", "a1"), runFlat("accA" to "a", "accB" to "a"))
    }

    @Test fun flatFolderViewStaysPinnedToItsOwnAccount() {
        // Same rows, single-folder view: the account bind is what keeps a sibling account's
        // identically-numbered Inbox out (issues #31/#92).
        insert("a1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("b1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("a1"), runFlat("accA" to "a"))
        assertEquals(listOf("b1"), runFlat("accB" to "a"))
    }

    @Test fun flatUnifiedDoesNotSelectOnTheMailboxIdStringAloneAcrossRoles() {
        // Was a characterisation test of the defect, and is now its assertion the other way round.
        // The unified list used to match the id STRING, not "this account's Inbox": a row filed
        // under ANOTHER account's folder that happened to carry the bound id was listed as if it
        // were inbox mail. Binding the pair ends it — accB's Trash is not in the scope, so its
        // row is not in the list, whatever accB numbered that folder.
        insert("inbox1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("trash1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("inbox1"), runFlat("accA" to "a"))
    }

    @Test fun flatUnifiedKeepsBothRowsWhenTheEmailIdsCollideToo() {
        // Same-server accounts can be handed the same EMAIL id as well (issue #31, composite
        // (accountId, id) key). The flat list must show one row per account, not one row.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")

        assertEquals(listOf("e1", "e1"), runFlat("accA" to "a", "accB" to "a"))
        assertEquals(listOf("e1"), runFlat("accA" to "a"))
    }

    @Test fun flatSnoozeStaysScopedToItsAccountAcrossACollidingId() {
        // The snooze correlate is account-qualified in the flat filter too: account A snoozing
        // "e1" must not hide account B's same-id inbox message from the unified list.
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("e1", threadId = null, seen = 0, flagged = 0, sortKey = 200, mailbox = "a", accountId = "accB")
        snooze("e1", untilMillis = Long.MAX_VALUE, accountId = "accA")

        assertEquals(listOf("e1"), runFlat("accA" to "a", "accB" to "a")) // only accB's survives…
        assertEquals(listOf("e1"), runFlat("accB" to "a"))                // …and it is accB's
        assertEquals(emptyList<String>(), runFlat("accA" to "a"))
    }

    // -- the reported defect: a row whose account is gone (Codeberg #121) ------------------
    //
    // Fastmail (JMAP) + Mail.ru (IMAP), "All inboxes": some messages appeared TWICE — once with
    // their account chip, in the right state, and once without a chip, bold and starred. The
    // unlabelled twin is a row of an account that is no longer configured: its mailbox id came
    // from the server and survived the account being removed and added back, while the account id
    // is a locally minted UUID and did not. Scoped on the folder id alone, the old rows stayed in
    // the list; the chip is resolved per account (InboxScreen), so they drew none.
    //
    // Each case below has its WITNESS: the same row under a KNOWN account, which must still be
    // listed. Without it a query that returned nothing at all would pass just as well.

    /** One message per account in the same server-numbered inbox — accA is configured, "gone" is
     *  the account the user removed. */
    private fun anOrphanBesideItsTwin() {
        insert("live", threadId = null, seen = 1, flagged = 0, sortKey = 100, mailbox = "a", accountId = "accA")
        insert("ghost", threadId = null, seen = 0, flagged = 1, sortKey = 200, mailbox = "a", accountId = "gone")
    }

    @Test fun conversationUnifiedDropsRowsOfAnAccountThatNoLongerExists() {
        anOrphanBesideItsTwin()

        assertEquals(listOf("live"), run(scopes = listOf("accA" to "a")).map { it["id"] })
    }

    @Test fun conversationUnifiedStillListsTheSameRowUnderAKnownAccount() {
        // The witness for the case above: "gone" listed once it IS a configured account.
        anOrphanBesideItsTwin()

        assertEquals(
            listOf("ghost", "live"),
            run(scopes = listOf("accA" to "a", "gone" to "a")).map { it["id"] },
        )
    }

    @Test fun flatUnifiedDropsRowsOfAnAccountThatNoLongerExists() {
        // Both modes, or the ghosts come back by switching the list to flat.
        anOrphanBesideItsTwin()

        assertEquals(listOf("live"), runFlat("accA" to "a"))
    }

    @Test fun flatUnifiedStillListsTheSameRowUnderAKnownAccount() {
        anOrphanBesideItsTwin()

        assertEquals(listOf("ghost", "live"), runFlat("accA" to "a", "gone" to "a"))
    }

    // -- flat mode: sort orders and the favourite pin (issue #111) -------------------------
    //
    // The flat query had NO favourite-pin coverage at all before this — every case above
    // inserts flagged = 0, so the prefix could have been removed or kept and nothing here
    // would have noticed. Conversation view is ON by default, so this is the second list
    // rather than the main one, but it is a wholly separate query (pagingSql) that carried
    // the same defect, and one whose only pin coverage was the one it never had.

    @Test fun flatOrdersDoNotPinFavourites() {
        insertSortFixture()

        assertEquals(listOf("s2", "p", "s"), runFlat("acc" to "inbox", sort = SortOrder.DATE_DESC))
        assertEquals(listOf("s", "p", "s2"), runFlat("acc" to "inbox", sort = SortOrder.DATE_ASC))
        assertEquals(listOf("p", "s2", "s"), runFlat("acc" to "inbox", sort = SortOrder.SUBJECT))
        assertEquals(listOf("p", "s2", "s"), runFlat("acc" to "inbox", sort = SortOrder.SENDER))
        assertEquals(listOf("p", "s2", "s"), runFlat("acc" to "inbox", sort = SortOrder.UNREAD_FIRST))
    }

    @Test fun flatFavouritesFirstPinsWhenChosen() {
        insertSortFixture()

        assertEquals(listOf("s2", "s", "p"), runFlat("acc" to "inbox", sort = SortOrder.FLAGGED_FIRST))
    }
}
