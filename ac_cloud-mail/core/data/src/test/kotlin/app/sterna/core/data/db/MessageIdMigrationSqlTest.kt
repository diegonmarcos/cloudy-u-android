package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.mail.toEmail
import app.sterna.core.data.mail.toEntity
import app.sterna.core.jmap.model.Email
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** #1802: v31 -> v32 adds `emails.messageId`, the key a Sent row asks the fleet mail API for its delivery stages by. */
class MessageIdMigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate("CREATE TABLE emails(id TEXT, accountId TEXT, fromDomain TEXT, PRIMARY KEY(accountId, id))")
            it.executeUpdate("INSERT INTO emails VALUES('1','a','example.org')")
            it.executeUpdate("INSERT INTO emails VALUES('2','b',NULL)")
        }
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

    @Test fun `the migration adds a nullable column and keeps every row and column`() {
        MIGRATION_31_32.migrate(supportDb())
        db.createStatement().use { st ->
            st.executeQuery("SELECT id, fromDomain, messageId FROM emails ORDER BY id").use { rs ->
                assertTrue(rs.next()); assertEquals("example.org", rs.getString(2)); assertNull(rs.getString(3))
                assertTrue(rs.next()); assertNull(rs.getString(2)); assertNull(rs.getString(3))
            }
        }
        assertEquals(31, MIGRATION_31_32.startVersion)
        assertEquals(32, MIGRATION_31_32.endVersion)
    }

    @Test fun `the migration is registered and the schema version moved with it`() {
        assertTrue(SternaDatabase.ALL_MIGRATIONS.any { it === MIGRATION_31_32 })
        assertTrue(SCHEMA_VERSION >= 32)
    }

    @Test fun `the Message-ID round-trips through the cached row`() {
        val e = Email(id = "s1", accountId = "a", mailboxId = "sent", messageId = listOf("<s1@fleet>"))
        val row = e.toEntity("a", "sent")
        assertEquals("<s1@fleet>", row.messageId)
        assertEquals(listOf("<s1@fleet>"), row.toEmail().messageId)
        assertEquals(emptyList<String>(), row.copy(messageId = null).toEmail().messageId)
    }
}
