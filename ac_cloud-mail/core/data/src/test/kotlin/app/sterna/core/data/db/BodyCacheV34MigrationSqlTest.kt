package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.filter.SourceText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * #1869 step 5: v33 -> v34 adds `email_bodies.fragment/fragmentVersion/inlineState/bytes` and the `email_parts` table.
 * Additive: every body row (and the outbox and drafts the destructive fallback would wipe) survives, the new columns read
 * their lazy defaults, and the caps' new `SUM(bytes)` reads what the old `SUM(LENGTH(...))` read.
 */
class BodyCacheV34MigrationSqlTest {
    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
        db.createStatement().use {
            it.executeUpdate(
                "CREATE TABLE `email_bodies` (`id` TEXT NOT NULL, `accountId` TEXT NOT NULL, `bodyJson` TEXT NOT NULL, " +
                    "`inlineImagesJson` TEXT NOT NULL, `fetchedAt` INTEGER NOT NULL, PRIMARY KEY(`accountId`, `id`))",
            )
            it.executeUpdate("CREATE TABLE `outbox` (`id` TEXT NOT NULL PRIMARY KEY, `subject` TEXT)")
            it.executeUpdate("CREATE TABLE `local_drafts` (`id` TEXT NOT NULL PRIMARY KEY, `subject` TEXT)")
            it.executeUpdate("INSERT INTO `email_bodies` VALUES ('e1', 'a', '{\"textBody\":\"hello\"}', '{}', 42)")
            it.executeUpdate("INSERT INTO `email_bodies` VALUES ('e2', 'a', '{\"htmlBody\":\"<p>x</p>\"}', '{\"c1\":\"data:image/png;base64,AAAA\"}', 43)")
            it.executeUpdate("INSERT INTO `email_bodies` VALUES ('e3', 'b', '{}', '{}', 44)")
            it.executeUpdate("INSERT INTO `outbox` VALUES ('o1', 'Never sent')")
            it.executeUpdate("INSERT INTO `local_drafts` VALUES ('d1', 'Half written')")
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

    private fun long(sql: String): Long = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getLong(1) } }

    /** name -> (type, notNull, default) of [table]'s columns. */
    private fun columns(table: String): Map<String, Triple<String, Int, String?>> = db.createStatement().use { st ->
        st.executeQuery("PRAGMA table_info(`$table`)").use { rs ->
            buildMap { while (rs.next()) put(rs.getString("name"), Triple(rs.getString("type"), rs.getInt("notnull"), rs.getString("dflt_value"))) }
        }
    }

    private fun bind(sql: String, accountId: String) = sql.replace(":accountId", "'$accountId'")

    @Test fun `the migration is registered and the schema version moved with it`() {
        assertTrue("an unregistered step is the destructive fallback: outbox and drafts wiped", SternaDatabase.ALL_MIGRATIONS.any { it === MIGRATION_33_34 })
        assertEquals(33, MIGRATION_33_34.startVersion)
        assertEquals(34, MIGRATION_33_34.endVersion)
        assertTrue("the schema is at v34 or later (#1952 added v35, one index)", SCHEMA_VERSION >= 34)
    }

    @Test fun `every row survives and the new columns read their lazy defaults`() {
        val legacyAccountA = long("SELECT COALESCE(SUM(LENGTH(bodyJson) + LENGTH(inlineImagesJson)), 0) FROM email_bodies WHERE accountId = 'a'")
        val legacyTotal = long("SELECT COALESCE(SUM(LENGTH(bodyJson) + LENGTH(inlineImagesJson)), 0) FROM email_bodies")
        MIGRATION_33_34.migrate(supportDb())
        assertEquals(3, long("SELECT COUNT(*) FROM email_bodies"))
        assertEquals(1, long("SELECT COUNT(*) FROM outbox WHERE subject = 'Never sent'"))
        assertEquals(1, long("SELECT COUNT(*) FROM local_drafts WHERE subject = 'Half written'"))
        assertEquals(3, long("SELECT COUNT(*) FROM email_bodies WHERE fragment IS NULL AND fragmentVersion = 0 AND inlineState = ${InlineState.UNKNOWN}"))
        assertEquals("the caps read the same size after the migration", legacyAccountA, long(bind(BODY_BYTES_FOR_ACCOUNT_SQL, "a")))
        assertEquals(legacyTotal, long(BODY_BYTES_TOTAL_SQL))
        assertTrue(legacyTotal > 0)
        assertEquals(0, long("SELECT COUNT(*) FROM email_parts"))
    }

    @Test fun `the columns have the shape Room expects for the entity`() {
        MIGRATION_33_34.migrate(supportDb())
        val b = columns("email_bodies")
        assertEquals(Triple("TEXT", 0, null), b["fragment"])
        assertEquals(Triple("INTEGER", 1, "0"), b["fragmentVersion"])
        assertEquals(Triple("INTEGER", 1, "0"), b["inlineState"])
        assertEquals(Triple("INTEGER", 1, "0"), b["bytes"])
        val p = columns("email_parts")
        assertEquals(
            setOf("accountId", "emailId", "partKey", "cid", "type", "name", "size", "inline", "file", "fileBytes"),
            p.keys,
        )
        listOf("accountId", "emailId", "partKey").forEach { assertEquals(1, p[it]!!.second) }
        listOf("cid", "type", "name", "file").forEach { assertEquals(0, p[it]!!.second) }
        listOf("size", "inline", "fileBytes").forEach { assertEquals(Triple("INTEGER", 1, null), p[it]) }
        val idx = db.createStatement().use { st ->
            st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'index' AND tbl_name = 'email_parts' AND sql IS NOT NULL").use { rs ->
                buildList { while (rs.next()) add(rs.getString(1)) }
            }
        }
        assertEquals(listOf("index_email_parts_accountId_emailId_cid"), idx)
    }

    @Test fun `the entities declare what the migration creates`() {
        val body = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailBodyEntity.kt")
        listOf("val fragment: String? = null", "val fragmentVersion: Int = 0", "val inlineState: Int = InlineState.UNKNOWN", "val bytes: Long =")
            .forEach { assertTrue("EmailBodyEntity lost `$it`", body.contains(it)) }
        assertEquals(3, Regex("@ColumnInfo\\(defaultValue = \"0\"\\)").findAll(body).count())
        val parts = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailPartEntity.kt")
        assertTrue(parts.contains("primaryKeys = [\"accountId\", \"emailId\", \"partKey\"]"))
        assertTrue(parts.contains("indices = [Index(value = [\"accountId\", \"emailId\", \"cid\"])]"))
        val database = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/SternaDatabase.kt")
        assertTrue(database.contains("EmailPartEntity::class"))
    }

    @Test fun `a new row's bytes is what the cap counted before, plus its fragment`() {
        val row = EmailBodyEntity(id = "e", accountId = "a", bodyJson = "{\"x\":1}", inlineImagesJson = "{}", fetchedAt = 1)
        assertEquals(9L, row.bytes)
        val withFragment = EmailBodyEntity(id = "e", accountId = "a", bodyJson = "{\"x\":1}", inlineImagesJson = "{}", fetchedAt = 1, fragment = "<p>1</p>")
        assertEquals(17L, withFragment.bytes)
        assertNull(row.fragment)
        assertEquals(InlineState.UNKNOWN, row.inlineState)
    }

    @Test fun `the full registered chain from v32 runs without a gap`() {
        val chain = SternaDatabase.ALL_MIGRATIONS.filter { it.startVersion in 32..33 }.sortedBy { it.startVersion }
        assertEquals(listOf(32 to 33, 33 to 34), chain.map { it.startVersion to it.endVersion })
    }
}
