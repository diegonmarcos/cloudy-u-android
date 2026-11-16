package app.sterna.core.data.mail

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet

/**
 * The four folder reads that used to materialise a whole mailbox, and the one thing that makes
 */
class BoundedFolderReadsSqlTest {
    private lateinit var db: Connection

    private data class Key(val accountId: String, val id: String)

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
                    recipientsJson TEXT,
                    PRIMARY KEY(accountId, id)
                )
                """.trimIndent(),
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun insert(
        id: String,
        sortKey: Long,
        seen: Int = 1,
        accountId: String = "acc",
        mailbox: String = "inbox",
        receivedAt: String = "",
    ) {
        db.prepareStatement(
            "INSERT INTO emails VALUES(?, ?, ?, NULL, 'subj', 'prev', ?, 'N', 'n@e', ?, 0, 0, ?, '[]')",
        ).use { ps ->
            ps.setString(1, id); ps.setString(2, accountId); ps.setString(3, mailbox)
            ps.setString(4, receivedAt); ps.setInt(5, seen); ps.setLong(6, sortKey)
            ps.executeUpdate()
        }
    }

    /**
     * Run one shipped DAO statement, binding its named parameters from [args] BY TYPE — a bound
     */
    internal fun <T> query(function: String, args: Map<String, Any>, read: (ResultSet) -> T): List<T> {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery(function))
        check(order.toSet() == args.keys) {
            "EmailDao.$function binds $order — this test offers ${args.keys}"
        }
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, name ->
                when (val v = args.getValue(name)) {
                    is Int -> ps.setInt(i + 1, v)
                    is Long -> ps.setLong(i + 1, v)
                    else -> ps.setString(i + 1, v.toString())
                }
            }
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
        }
    }

    private fun newestIds(limit: Int, accountId: String = "acc", mailbox: String = "inbox"): List<String> =
        query(
            "newestIds",
            mapOf("accountId" to accountId, "mailboxId" to mailbox, "limit" to limit),
        ) { it.getString("id") }

    private fun unreadKeys(limit: Int, accountId: String = "acc", mailbox: String = "inbox"): List<Key> =
        query(
            "unreadKeys",
            mapOf("accountId" to accountId, "mailboxId" to mailbox, "limit" to limit),
        ) { Key(it.getString("accountId"), it.getString("id")) }

    private fun receivedSince(
        since: Long,
        limit: Int,
        accountId: String = "acc",
        mailbox: String = "inbox",
    ): List<String> =
        query(
            "receivedSince",
            mapOf("accountId" to accountId, "mailboxId" to mailbox, "since" to since, "limit" to limit),
        ) { it.getString("id") }

    private fun baselineIds(since: Long, accountId: String = "acc", mailbox: String = "inbox"): List<String> =
        query(
            "idsReceivedSince",
            mapOf("accountId" to accountId, "mailboxId" to mailbox, "since" to since),
        ) { it.getString("id") }

    // -- B3: the body prefetch reads the newest few, not the folder ------------------------------

    @Test fun `the newest ids stop at the limit, and the rest of the folder is never read`() {
        // 60 rows for a bound of 20. The old code returned the same twenty — after selecting all
        // sixty entities. Executing the shipped statement is what tells the two apart: strip the
        // LIMIT and this hands back sixty ids.
        (1..60).forEach { insert("m$it", sortKey = it.toLong()) }
        val ids = newestIds(20)
        assertEquals(20, ids.size)
        assertEquals((60 downTo 41).map { "m$it" }, ids)
    }

    @Test fun `a folder smaller than the bound comes back whole`() {
        (1..3).forEach { insert("m$it", sortKey = it.toLong()) }
        assertEquals(listOf("m3", "m2", "m1"), newestIds(20))
    }

    @Test fun `the newest ids are this account's folder, not a same-server sibling's`() {
        // Stalwart numbers mailboxes per account, so "inbox" is a different folder in each (#121).
        insert("mine", sortKey = 10, accountId = "acc")
        insert("theirs", sortKey = 99, accountId = "other")
        assertEquals(listOf("mine"), newestIds(20, accountId = "acc"))
        assertEquals(listOf("theirs"), newestIds(20, accountId = "other"))
    }

    @Test fun `the newest ids are this folder, not the account's other folders`() {
        insert("here", sortKey = 10, mailbox = "inbox")
        insert("elsewhere", sortKey = 99, mailbox = "archive")
        assertEquals(listOf("here"), newestIds(20, mailbox = "inbox"))
    }

    // -- B4/B5: unread is resolved by the statement ----------------------------------------------

    @Test fun `the unread rows are found even when they all sit past the bound`() {
        // The bench that tells a SQL `seen = 0` from a Kotlin filter: the six NEWEST rows are read,
        // the four unread ones are the oldest in the folder. A statement that bounds first and
        // filters afterwards ("SELECT * … LIMIT 3", then drop the read ones in Kotlin) returns
        // NOTHING here, and "mark all read" would quietly stop marking anything.
        (1..6).forEach { insert("read$it", sortKey = (100 + it).toLong(), seen = 1) }
        (1..4).forEach { insert("unread$it", sortKey = it.toLong(), seen = 0) }
        assertEquals(
            listOf("unread4", "unread3", "unread2", "unread1"),
            unreadKeys(limit = 10).map { it.id },
        )
    }

    @Test fun `more unread than the bound stops at the bound, newest first`() {
        (1..40).forEach { insert("u$it", sortKey = it.toLong(), seen = 0) }
        val keys = unreadKeys(limit = 10)
        assertEquals(10, keys.size)
        assertEquals((40 downTo 31).map { "u$it" }, keys.map { it.id })
    }

    @Test fun `read mail is never returned`() {
        insert("read", sortKey = 9, seen = 1)
        insert("unread", sortKey = 1, seen = 0)
        assertEquals(listOf("unread"), unreadKeys(limit = 10).map { it.id })
    }

    @Test fun `each unread row carries the account it belongs to`() {
        // The unified view asks over several accounts at once and two same-server accounts can
        // mint the SAME email id (#31): a bare id would send the mark-read and the notification
        // dismissal to whichever account answered first.
        insert("e1", sortKey = 5, seen = 0, accountId = "acc")
        insert("e1", sortKey = 5, seen = 0, accountId = "other")
        assertEquals(listOf(Key("acc", "e1")), unreadKeys(limit = 10, accountId = "acc"))
        assertEquals(listOf(Key("other", "e1")), unreadKeys(limit = 10, accountId = "other"))
    }

    // -- B6: the notifier's candidates ------------------------------------------------------------

    @Test fun `rows older than the floor are left out, and the floor itself is in`() {
        insert("old", sortKey = 99)
        insert("edge", sortKey = 100)
        insert("new", sortKey = 101)
        assertEquals(listOf("new", "edge"), receivedSince(since = 100, limit = 500))
    }

    @Test fun `an undated row is still handed over`() {
        // `sortKey = 0` is what a missing or unparseable receivedAt maps to, and the notifier's own
        // age floor lets exactly those through ("never suppresses a real arrival"). Dropping them
        // here would silence a real arrival the notifier was written to keep.
        insert("dated", sortKey = 5_000)
        insert("undated", sortKey = 0, receivedAt = "")
        assertEquals(setOf("dated", "undated"), receivedSince(since = 1_000, limit = 500).toSet())
    }

    @Test fun `the candidate read stops at its cap, newest first`() {
        // Literals on both sides. Written as `assertEquals(NOTIFY_CANDIDATE_MAX, ids.size)` over a
        // bench sized `NOTIFY_CANDIDATE_MAX + 25`, this passes for EVERY value of the constant,
        // mutated ones included — an expectation recomputed from the decision it is judging.
        (1..525).forEach { insert("m%04d".format(it), sortKey = 1_000L + it) }
        val ids = receivedSince(since = 0, limit = 500)
        assertEquals(500, ids.size)
        assertEquals("m0525", ids.first())
        assertEquals("m0026", ids.last())
    }

    @Test fun `the candidates are this account's folder only`() {
        insert("mine", sortKey = 5_000, accountId = "acc")
        insert("theirs", sortKey = 5_000, accountId = "other")
        insert("elsewhere", sortKey = 5_000, mailbox = "archive")
        assertEquals(listOf("mine"), receivedSince(since = 0, limit = 500))
    }

    @Test fun `the baseline read has no cap and takes the same rows`() {
        (1..525).forEach { insert("m%04d".format(it), sortKey = 1_000L + it) }
        insert("undated", sortKey = 0, receivedAt = "")
        insert("ancient", sortKey = 5)
        insert("sibling", sortKey = 2_000, accountId = "other")
        val remembered = baselineIds(since = 1_000).toSet()
        assertEquals(526, remembered.size)
        assertTrue("the undated row must be remembered too", "undated" in remembered)
        assertTrue("a row past the floor must not be", "ancient" !in remembered)
        assertTrue("another account's row must not be", "sibling" !in remembered)
        assertTrue(
            "every candidate has to be remembered, or the cap decides what is forgotten",
            receivedSince(since = 1_000, limit = 500).all { it in remembered },
        )
    }

    // -- ties at the frontier -----------------------------------------------------------------------

    @Test fun `equal sortKeys are broken by id, so the frontier is a function of the rows`() {
        // Three rows share a sortKey and the LIMIT cuts through them. Without `, id DESC` SQLite
        // answers from the physical order — and this table is rewritten at every sync, so the row
        listOf("c", "a", "b").forEach { insert("tie-$it", sortKey = 500) }
        insert("newer", sortKey = 900)
        assertEquals(listOf("newer", "tie-c", "tie-b"), newestIds(3))
    }

    @Test fun `the same rows in a different physical order give the same answer`() {
        val ids = listOf("z", "m", "a", "q")
        ids.forEach { insert("t-$it", sortKey = 500, seen = 0) }
        val first = unreadKeys(limit = 2).map { it.id }
        db.createStatement().use { it.executeUpdate("DELETE FROM emails") }
        ids.reversed().forEach { insert("t-$it", sortKey = 500, seen = 0) }
        assertEquals(
            "the answer changed when the rows were merely re-inserted in another order",
            first,
            unreadKeys(limit = 2).map { it.id },
        )
        assertEquals(listOf("t-z", "t-q"), first)
    }

    @Test fun `undated rows are perfect ties and still ordered`() {
        // sortKey = 0 makes a block of exact ties right at the bottom of the candidate read, which
        // is where the cap cuts. Deterministic or not at all.
        listOf("c", "a", "b").forEach { insert("u-$it", sortKey = 0, receivedAt = "") }
        assertEquals(listOf("u-c", "u-b"), receivedSince(since = 10, limit = 2))
    }

    // -- the call sites -----------------------------------------------------------------------------
    // Whole lines, never `contains` on a fragment: a fragment match is blind to any mutation that
    // LENGTHENS the line, which is precisely the shape "…, PREFETCH_COUNT * 100)" has.

    private fun bodyLines(function: String): List<String> =
        DaoQuerySource.mailFunctionBody("MailRepository", function).lines().map { it.trim() }

    private fun assertLine(function: String, line: String) {
        val lines = bodyLines(function)
        assertTrue(
            "MailRepository.$function no longer contains the line:\n  $line\nits body is:\n" +
                lines.joinToString("\n"),
            line in lines,
        )
    }

    /**
     * [forbidden] is per call site, not a blanket list: `prefetchInboxBodies` legitimately filters
     */
    private fun assertNoUnboundedRead(function: String, vararg forbidden: String = arrayOf("getByMailbox", ".take(")) {
        val body = DaoQuerySource.mailFunctionBody("MailRepository", function)
        forbidden.forEach {
            assertTrue(
                "MailRepository.$function is bounding in Kotlin again ('$it') — the point of the " +
                    "statement's bound is that the rows are never read at all",
                it !in body,
            )
        }
    }

    @Test fun `the body prefetch reads a bounded number of ids in the statement and nothing else`() {
        // #1763: the prefetch covers the offline window, not 20 messages, so the read is `newestWithoutBody(.., limit)` with the
        // limit taken from the budget; the bound is still the statement's (a LIMIT), never a Kotlin `take` on a folder read.
        assertTrue(
            "the prefetch read must still be handed its limit",
            bodyLines("bodyPrefetchPort").any { it.startsWith("emailDao.newestWithoutBody(") },
        )
        assertTrue("the statement carries the LIMIT", "LIMIT :limit" in DaoQuerySource.emailDaoQuery("newestWithoutBody"))
        assertNoUnboundedRead("bodyPrefetchPort")
        assertNoUnboundedRead("prefetchInboxBodies")
        for (trigger in PrefetchTrigger.entries) {
            val budget = prefetchBudget(PrefetchConditions.UNRESTRICTED, trigger)
            assertTrue("a prefetch pass is bounded (${budget.maxIds} ids)", budget.maxIds <= 5_000)
        }
    }

    @Test fun `the unread fallback carries the same bound as the server walk beside it`() {
        // The argument, not just the call: the two branches of unreadIds answer one question about
        // one folder, and answering it at two different sizes is how the fallback got no bound at
        // all in the first place.
        assertLine("unreadIds", "emailDao.unreadKeys(credentials.id, mailboxId, UNREAD_RESOLVE_MAX).map { it.id }")
        assertTrue(
            "the server branch of unreadIds no longer walks to UNREAD_RESOLVE_MAX — the bound the " +
                "fallback copies has moved, and the two are out of step again",
            "while (ids.size < UNREAD_RESOLVE_MAX) {" in bodyLines("unreadIds"),
        )
        assertNoUnboundedRead("unreadIds", "getByMailbox", ".take(", ".filter {")
    }

    @Test fun `mark-all-read reads unread keys, bounded, once per scope`() {
        // The iteration line as well as the read: `scopes.drop(1).flatMap` passes every "no
        // unbounded read" rule there is and silently skips the first account of a unified
        // "mark all read".
        assertLine("cachedUnreadKeys", "return scopes.flatMap { (accountId, mailboxId) ->")
        assertLine("cachedUnreadKeys", "emailDao.unreadKeys(accountId, mailboxId, UNREAD_RESOLVE_MAX)")
        assertNoUnboundedRead("cachedUnreadKeys", "getByMailbox", ".take(", ".filter {")
    }

    @Test fun `one notification pass reads its candidates and its baseline at ONE floor`() {
        // One `since`, bound into both statements. Two calls to the clock would let the id read
        // start a hair later than the hydrated one and miss a row it had just offered — a row
        // that is then announced on the next pass.
        assertLine("notifyRead", "val since = notifyCandidateFloor(System.currentTimeMillis())")
        assertLine(
            "notifyRead",
            "emails = emailDao.receivedSince(accountId, mailboxId, since, NOTIFY_CANDIDATE_MAX).map { it.toEmail() },",
        )
        assertLine("notifyRead", "baselineIds = emailDao.idsReceivedSince(accountId, mailboxId, since),")
        assertNoUnboundedRead("notifyRead", "getByMailbox", ".take(", ".filter {")
    }

    @Test fun `the watched-folder refresh hands over both sets, not the folder`() {
        assertLine("refreshAccountFolders", "val read = notifyRead(credentials.id, mailbox.id)")
        assertLine("refreshAccountFolders", "emails = read.emails,")
        assertLine("refreshAccountFolders", "baselineIds = read.baselineIds,")
        assertTrue(
            "refreshAccountFolders reads a whole folder again — this runs in a push service, " +
                "where an OutOfMemoryError is a crash loop and not a report",
            "getByMailbox" !in DaoQuerySource.mailFunctionBody("MailRepository", "refreshAccountFolders"),
        )
    }

    @Test fun `the memory nets stay the size of memory nets`() {
        // Order of magnitude, not the exact figure: these two are the ONLY thing standing between
        // a deep folder and an OutOfMemoryError once the sync window is unpinned, and both are
        // #1763: the prefetch net is PREFETCH_PASS_MAX_IDS (ids per pass, ids only, read with a LIMIT); it replaced PREFETCH_COUNT = 20.
        assertTrue("PREFETCH_PASS_MAX_IDS is $PREFETCH_PASS_MAX_IDS — that is a folder read, not a prefetch", PREFETCH_PASS_MAX_IDS <= 5_000)
        assertTrue("NOTIFY_CANDIDATE_MAX is $NOTIFY_CANDIDATE_MAX rows in a push service", NOTIFY_CANDIDATE_MAX <= 2_000)
    }

    @Test fun `no unbounded whole-folder read is left in the DAO to pick up again`() {
        // The replaced statement is deleted rather than left dead beside its replacements: a
        // folder-wide `SELECT *` is the shortest thing to reach for, and this file's whole subject
        // is that the short thing was the bug. (EmailDao.deleteNotIn / deleteNotInSparing are the
        // standing example of what a dead unbounded statement costs — see #29.)
        assertEquals(null, DaoQuerySource.queryOrNull("EmailDao", "getByMailbox"))
    }
}
