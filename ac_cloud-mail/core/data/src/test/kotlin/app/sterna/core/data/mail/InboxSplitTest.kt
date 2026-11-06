package app.sterna.core.data.mail

import app.sterna.core.data.settings.SortOrder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * The Inbox's "Alerts" and "no Alerts" views are a strict partition of the Inbox: every message in
 * exactly one, so Alerts + no-Alerts = Inbox. Run through the shipped SQL on in-memory SQLite, with the
 * rules declared in build.json::mail_inbox_split.
 */
class InboxSplitTest {
    private lateinit var db: Connection
    private val scope = listOf("acc" to "inbox")

    private val rules: InboxSplitRules by lazy {
        val root = Regex(""""mail_inbox_split"\s*:\s*(\{.*?\})\s*,\s*\n\s*"ui"""", RegexOption.DOT_MATCHES_ALL)
            .find(File("../../build.json").readText())?.groupValues?.get(1)
            ?: error("build.json::mail_inbox_split is not declared")
        InboxSplit.parse(root)
    }

    private val senders = listOf(
        "alerts@acme.io", "NoReply@Shop.example", "ci@github.com", "n@mail.github.com", "bob@x.io",
        "Amy@X.io", "security@bank.example", "friend@gmail.com", "status@uptime.dev", "bot@sentry.io",
        "x@notgithub.com", "x@github.com.evil.io", "", "not an address", "monitoring@a.b.c",
    )

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
        }
        senders.forEachIndexed { i, from -> insert("m$i", "inbox", from, i.toLong()) }
        insert("nullsender", "inbox", null, 99)
        insert("elsewhere", "sent", "alerts@acme.io", 100) // another folder: in neither view of the Inbox
    }

    @After fun tearDown() = db.close()

    private fun insert(id: String, mailbox: String, from: String?, key: Long) {
        db.prepareStatement("INSERT INTO emails VALUES(?, 'acc', ?, NULL, 's', 'p', '', NULL, ?, 0, 0, 0, ?, NULL, NULL)").use { ps ->
            ps.setString(1, id); ps.setString(2, mailbox); ps.setString(3, from); ps.setLong(4, key)
            ps.executeUpdate()
        }
    }

    private fun ids(alerts: AlertScope): List<String> =
        db.prepareStatement(pagingSql(scope.size, SortOrder.DATE_DESC, false, ListShape(alerts = alerts, alertRules = rules))).use { ps ->
            ps.setString(1, "acc"); ps.setString(2, "inbox")
            ps.executeQuery().use { rs -> buildList { while (rs.next()) add(rs.getString("id")) } }
        }

    private fun count(alerts: AlertScope): Int =
        db.prepareStatement(listCountSql(scope.size, ListShape(alerts = alerts, alertRules = rules))).use { ps ->
            ps.setString(1, "acc"); ps.setString(2, "inbox")
            ps.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
        }

    @Test fun `Alerts and no Alerts partition the Inbox - no overlap, union equals the Inbox`() {
        val all = ids(AlertScope.ALL)
        val alerts = ids(AlertScope.ALERTS)
        val rest = ids(AlertScope.NO_ALERTS)
        assertEquals(senders.size + 1, all.size) // the folder's own messages, none from elsewhere
        assertTrue("overlap: ${alerts.intersect(rest.toSet())}", alerts.intersect(rest.toSet()).isEmpty())
        assertEquals(all.toSet(), (alerts + rest).toSet())
        assertEquals(all.size, alerts.size + rest.size)
        assertTrue(alerts.isNotEmpty() && rest.isNotEmpty())
    }

    @Test fun `the counts shown are the lists' own and add up`() {
        val c = AlertCounts(count(AlertScope.ALL), count(AlertScope.ALERTS), count(AlertScope.NO_ALERTS))
        assertEquals(ids(AlertScope.ALL).size, c.all)
        assertEquals(ids(AlertScope.ALERTS).size, c.alerts)
        assertEquals(ids(AlertScope.NO_ALERTS).size, c.noAlerts)
        assertTrue(c.partitions)
    }

    @Test fun `the Kotlin rule and the SQL rule agree on every sender`() {
        val alertIds = ids(AlertScope.ALERTS).toSet()
        senders.forEachIndexed { i, from ->
            assertEquals("'$from'", InboxSplit.isAlert(rules, from), "m$i" in alertIds)
        }
        assertFalse("no sender address is a no-Alert, never neither", InboxSplit.isAlert(rules, null))
        assertTrue("nullsender" in ids(AlertScope.NO_ALERTS))
    }

    @Test fun `the rules are deterministic and match the declared domain and its subdomains only`() {
        assertTrue(InboxSplit.isAlert(rules, "ci@github.com"))
        assertTrue(InboxSplit.isAlert(rules, "n@mail.github.com"))
        assertFalse(InboxSplit.isAlert(rules, "x@notgithub.com"))
        assertFalse(InboxSplit.isAlert(rules, "x@github.com.evil.io"))
        assertTrue(InboxSplit.isAlert(rules, " ALERTS@acme.io "))
        assertFalse(InboxSplit.isAlert(rules, "bob@x.io"))
        repeat(3) { assertEquals(ids(AlertScope.ALERTS), ids(AlertScope.ALERTS)) }
    }

    @Test fun `the partition dedupes by Message-ID, falling back to the UID`() {
        data class Msg(val uid: String, val messageId: String?, val from: String?)
        val msgs = listOf(
            Msg("1", "<a@x>", "alerts@acme.io"),
            Msg("2", "<a@x>", "alerts@acme.io"), // same message filed twice: one copy
            Msg("3", "<b@x>", "bob@x.io"),
            Msg("4", null, "bob@x.io"),
            Msg("4", null, "bob@x.io"), // no Message-ID, same UID: one copy
            Msg("5", "<c@x>", null),
        )
        val (alerts, rest) = InboxSplit.partition(rules, msgs, { it.messageId ?: "uid:${it.uid}" }, { it.from })
        assertEquals(listOf("1"), alerts.map { it.uid })
        assertEquals(listOf("3", "4", "5"), rest.map { it.uid })
        assertEquals(4, alerts.size + rest.size)
        assertTrue(alerts.intersect(rest.toSet()).isEmpty())
    }

    @Test fun `rules reject anything that is not a plain token`() {
        val bad = runCatching { InboxSplit.parse("""{"alert_local_parts":["a' OR 1=1 --"],"alert_sender_domains":[]}""") }
        assertTrue(bad.isFailure)
    }
}
