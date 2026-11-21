package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.filter.SourceText
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #1837 phase 3: v32 -> v33 adds `email_mailboxes`. The repo's migration tests run the registered step on in-memory SQLite (no schema
 * export exists to feed Room's MigrationTestHelper), and pin the DDL to what Room generates for [EmailMailboxEntity], which is
 * what `validateMigration` compares at open: same columns, NOT NULL, primary key order, index name and columns.
 */
class FolderMembershipMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use { it.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, mailboxId TEXT, PRIMARY KEY(accountId, id))") }
        db.createStatement().use { it.executeUpdate("INSERT INTO emails VALUES('1','a','inbox')") }
    }

    @After fun tearDown() = db.close()

    private fun supportDb(): SupportSQLiteDatabase = Proxy.newProxyInstance(
        SupportSQLiteDatabase::class.java.classLoader,
        arrayOf(SupportSQLiteDatabase::class.java),
    ) { _, method, args ->
        when {
            method.name == "execSQL" && args?.size == 1 -> { db.createStatement().use { it.executeUpdate(args[0] as String) }; null }
            method.name == "toString" -> "SupportSQLiteDatabase(jdbc)"
            method.name == "hashCode" -> 0
            method.name == "equals" -> false
            else -> error("Unexpected SupportSQLiteDatabase.${method.name} in a migration")
        }
    } as SupportSQLiteDatabase

    @Test fun `the step is registered, the schema version moved with it, and no row is rewritten`() {
        assertTrue(SternaDatabase.ALL_MIGRATIONS.any { it === MIGRATION_32_33 })
        assertEquals(32, MIGRATION_32_33.startVersion)
        assertEquals(33, MIGRATION_32_33.endVersion)
        assertTrue(SCHEMA_VERSION >= 33)
        MIGRATION_32_33.migrate(supportDb())
        db.createStatement().use { st -> st.executeQuery("SELECT COUNT(*) FROM emails").use { rs -> rs.next(); assertEquals(1, rs.getInt(1)) } }
        // idempotent (IF NOT EXISTS): a half-applied upgrade can be run again
        MIGRATION_32_33.migrate(supportDb())
    }

    @Test fun `the table has the columns, NOT NULL and primary key order Room generates for the entity`() {
        MIGRATION_32_33.migrate(supportDb())
        val cols = ArrayList<Triple<String, Boolean, Int>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA table_info(`email_mailboxes`)").use { rs ->
                while (rs.next()) cols += Triple(rs.getString("name"), rs.getInt("notnull") == 1, rs.getInt("pk"))
            }
        }
        assertEquals(
            listOf(Triple("accountId", true, 1), Triple("emailId", true, 2), Triple("mailboxId", true, 3)),
            cols.sortedBy { it.third.takeIf { p -> p > 0 } ?: 99 }.take(3),
        )
        val idx = ArrayList<Pair<String, List<String>>>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`email_mailboxes`)").use { rs ->
                while (rs.next()) {
                    val name = rs.getString("name")
                    if (rs.getString("origin") == "c") idx += name to emptyList()
                }
            }
        }
        assertEquals(listOf("index_email_mailboxes_accountId_mailboxId"), idx.map { it.first })
        val indexed = ArrayList<String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_info(`index_email_mailboxes_accountId_mailboxId`)").use { rs -> while (rs.next()) indexed += rs.getString("name") }
        }
        assertEquals(listOf("accountId", "mailboxId"), indexed)
    }

    @Test fun `the entity declares exactly that table and index and the database lists it`() {
        val entity = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailMailboxEntity.kt")
        val code = SourceText.codeLines(entity).joinToString(" ")
        assertTrue(code, code.contains("tableName = \"email_mailboxes\""))
        assertTrue(code, code.contains("primaryKeys = [\"accountId\", \"emailId\", \"mailboxId\"]"))
        assertTrue(code, code.contains("indices = [Index(value = [\"accountId\", \"mailboxId\"])]"))
        val database = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/SternaDatabase.kt")
        assertTrue("SternaDatabase must list the entity", database.contains("EmailMailboxEntity::class"))
    }

    @Test fun `a write through upsertAll clears the message's extra memberships and the projection writes them back`() {
        val dao = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailDao.kt")
        assertTrue(dao.contains("suspend fun upsertAll(emails: List<EmailEntity>) {"))
        assertTrue(dao.contains("clearMemberships(account, it)"))
        assertTrue(dao.contains("suspend fun applyProjection("))
        val core = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/mail/RustMailCore.kt")
        assertTrue(core.contains("emailDao.applyProjection("))
    }
}
