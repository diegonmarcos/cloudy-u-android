package app.sterna.core.data.mail

import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1837: no `email_mailboxes` row outlives its message or its account. The shipped DAO statements run on in-memory SQLite; the
 * transactions that chain them are pinned as source (nothing here can run Room).
 */
class MembershipOrphanSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { st ->
            st.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, id))")
            st.executeUpdate("CREATE TABLE email_mailboxes(accountId TEXT, emailId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, emailId, mailboxId))")
            st.executeUpdate("INSERT INTO emails VALUES('m1','a','inbox'),('m2','a','lbl'),('m3','b','inbox')")
            st.executeUpdate(
                "INSERT INTO email_mailboxes VALUES('a','m1','inbox'),('a','m1','lbl'),('a','m2','lbl'),('a','gone','lbl'),('b','m3','inbox'),('b','gone','inbox')",
            )
        }
    }

    @After fun tearDown() = db.close()

    private fun run(function: String, account: String? = null): Int {
        val (sql, order) = DaoQuerySource.bindOrder(DaoQuerySource.emailDaoQuery(function))
        return db.prepareStatement(sql).use { ps ->
            order.forEachIndexed { i, _ -> ps.setString(i + 1, account) }
            ps.executeUpdate()
        }
    }

    private fun memberships(): List<String> = db.createStatement().use { st ->
        st.executeQuery("SELECT accountId || ':' || emailId || ':' || mailboxId AS k FROM email_mailboxes ORDER BY k").use { rs ->
            generateSequence { if (rs.next()) rs.getString("k") else null }.toList()
        }
    }

    @Test fun `the sweep deletes the memberships of messages that have no row, in the named account only`() {
        assertEquals(1, run("deleteOrphanMemberships", "a"))
        assertEquals(listOf("a:m1:inbox", "a:m1:lbl", "a:m2:lbl", "b:gone:inbox", "b:m3:inbox"), memberships())
    }

    @Test fun `deleting a message's row then sweeping takes its memberships and keeps the others`() {
        db.createStatement().use { it.executeUpdate("DELETE FROM emails WHERE accountId = 'a' AND id = 'm1'") }
        run("deleteOrphanMemberships", "a")
        assertEquals(listOf("a:m2:lbl", "b:gone:inbox", "b:m3:inbox"), memberships())
    }

    @Test fun `the global sweep takes the memberships of a removed account and of deleted rows, and keeps live ones`() {
        db.createStatement().use { it.executeUpdate("DELETE FROM emails WHERE accountId = 'a'") }
        assertEquals(5, run("deleteAllOrphanMemberships"))
        assertEquals(listOf("b:m3:inbox"), memberships())
    }

    @Test fun `the sweeps are wired after the projection page and after the core pass`() {
        assertTrue("the projection no longer sweeps orphans", "deleteOrphanMemberships(accountId)" in DaoQuerySource.daoFunctionBody("EmailDao", "applyProjection"))
        assertTrue("the core pass no longer sweeps orphans", "deleteAllOrphanMemberships" in DaoQuerySource.mailFunctionBody("MailRepository", "coreInbox"))
    }
}
