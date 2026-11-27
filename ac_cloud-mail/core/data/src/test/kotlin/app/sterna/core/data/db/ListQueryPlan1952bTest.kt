package app.sterna.core.data.db

import app.sterna.core.data.mail.ListShape
import app.sterna.core.data.mail.conversationSql
import app.sterna.core.data.settings.SortOrder
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1952 the default list (grouped by sender domain) at 40 000 messages: its aggregates must be answered from the covering
 * index, never from the wide message rows, and the first page is a LIMITed read. SQLite's own plan is the witness.
 * Mutations: workflow mail-cache-1952b-mutation (index-not-covering, first-page-unbounded).
 */
class ListQueryPlan1952bTest {
    private lateinit var db: Connection
    private val index = "index_emails_accountId_mailboxId_fromDomain_sortKey_seen_id"

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        exec(
            "CREATE TABLE emails(id TEXT NOT NULL, accountId TEXT NOT NULL, mailboxId TEXT NOT NULL, threadId TEXT, sortKey INTEGER NOT NULL, " +
                "seen INTEGER NOT NULL, flagged INTEGER NOT NULL, fromEmail TEXT, fromName TEXT, fromDomain TEXT, subject TEXT, preview TEXT, " +
                "PRIMARY KEY(accountId, id))",
        )
        exec("CREATE INDEX index_emails_mailboxId ON emails(mailboxId)")
        exec("CREATE INDEX index_emails_accountId_mailboxId_sortKey ON emails(accountId, mailboxId, sortKey)")
        exec("CREATE TABLE snoozed(accountId TEXT NOT NULL, emailId TEXT NOT NULL, until INTEGER NOT NULL, PRIMARY KEY(accountId, emailId))")
        db.prepareStatement("INSERT INTO emails VALUES(?,?,?,?,?,?,?,?,?,?,?,?)").use { st ->
            for (i in 1..2_000) {
                val box = if (i % 10 == 0) "sent" else "inbox"
                listOf<Any?>("m$i", "acc", box, "t$i", 1_000_000L - i, i % 3, 0, "x@d${i % 40}.example", "X", if (i % 97 == 0) null else "d${i % 40}.example", "s$i", "p")
                    .forEachIndexed { n, v -> st.setObject(n + 1, v) }
                st.addBatch()
            }
            st.executeBatch()
        }
    }

    @After fun tearDown() = db.close()

    private fun exec(sql: String) = db.createStatement().use { it.executeUpdate(sql) }

    private val sql = conversationSql(scopeCount = 1, sort = SortOrder.DATE_DESC, unreadOnly = false, sentMailboxCount = 1, shape = ListShape(byDomain = true))
    private val binds = listOf("acc", "inbox", "acc", "inbox", "acc", "sent", "acc", "inbox")

    private fun <T> query(statement: String, read: (java.sql.ResultSet) -> T): List<T> = db.prepareStatement(statement).use { st ->
        binds.forEachIndexed { n, v -> st.setString(n + 1, v) }
        st.executeQuery().use { rs -> buildList { while (rs.next()) add(read(rs)) } }
    }

    private fun plan(): List<String> = query("EXPLAIN QUERY PLAN $sql") { it.getString(4) }

    private fun rows(): List<String> = query(sql) { "${it.getString("id")}|${it.getInt("threadCount")}|${it.getInt("threadTotal")}|${it.getInt("groupUnread")}" }

    @Test fun `the domain list's aggregates read the covering index, not the message rows`() {
        val before = rows()
        assertTrue("the fixture groups: ${before.size}", before.size in 40..80)
        exec(EMAILS_DOMAIN_LIST_INDEX_SQL)
        exec("ANALYZE")
        val plan = plan()
        val covered = plan.count { "emails USING COVERING INDEX $index" in it }
        assertEquals("the three aggregates (this folder, its chips, the account) are index-only: $plan", 3, covered)
        assertFalse("no aggregate scans the table itself: $plan", plan.any { it == "SCAN emails" })
        assertEquals("the index changes the cost, never the rows", before, rows())
    }

    @Test fun `the entity, the migration and the schema version declare that index`() {
        val entity = code("core/data/src/main/kotlin/app/sterna/core/data/db/EmailEntity.kt")
        assertTrue("Index(value = [\"accountId\", \"mailboxId\", \"fromDomain\", \"sortKey\", \"seen\", \"id\"])," in entity)
        assertTrue(index in EMAILS_DOMAIN_LIST_INDEX_SQL && "(`accountId`, `mailboxId`, `fromDomain`, `sortKey`, `seen`, `id`)" in EMAILS_DOMAIN_LIST_INDEX_SQL)
        assertTrue(SCHEMA_VERSION >= 35)
        assertTrue(SternaDatabase.ALL_MIGRATIONS.any { it.startVersion == 34 && it.endVersion == 35 })
    }

    @Test fun `the first page is a bounded read of the store`() {
        val repo = code("core/data/src/main/kotlin/app/sterna/core/data/mail/MailRepository.kt")
        assertTrue("private const val PAGE_SIZE = 50" in repo)
        assertTrue("the first load asks for three pages, never the whole folder", "initialLoadSize = PAGE_SIZE * 3," in repo)
        assertTrue("override suspend fun initialize() = InitializeAction.SKIP_INITIAL_REFRESH" in repo)
    }

    private fun code(path: String): List<String> =
        generateSequence(File("").absoluteFile) { it.parentFile }.map { File(it, path) }.first { it.isFile }
            .readLines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }
}
