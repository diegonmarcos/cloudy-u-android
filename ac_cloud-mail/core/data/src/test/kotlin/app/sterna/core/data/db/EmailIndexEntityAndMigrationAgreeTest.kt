package app.sterna.core.data.db

import androidx.sqlite.db.SupportSQLiteDatabase
import app.sterna.core.data.filter.SourceText
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.DriverManager

/**
 * The two sources of one schema, held to saying the same thing.
 */
class EmailIndexEntityAndMigrationAgreeTest {

    private lateinit var db: Connection

    @Before fun setUp() {
        Class.forName("org.sqlite.JDBC")
        db = DriverManager.getConnection("jdbc:sqlite::memory:")
    }

    @After fun tearDown() = db.close()

    private val entitySource: String
        get() = SourceText.read("core/data/src/main/kotlin/app/sterna/core/data/db/EmailEntity.kt")

    /** The single `indices = …` line of [EmailEntity], comments dropped, whitespace squeezed. */
    private fun indicesLine(): String {
        val lines = SourceText.codeLines(entitySource).filter { it.startsWith("indices") }
        assertEquals(
            "EmailEntity must declare its indices on ONE line starting with `indices` — this test " +
                "pins that line whole, and a line split across several is a change to review, not " +
                "something to tolerate silently. Found: $lines",
            1,
            lines.size,
        )
        return lines.single().replace(Regex("\\s+"), " ").trim()
    }

    // --- (a) the entity's declaration, pinned whole -------------------------------------------------

    /**
     * Equality on the whole line. It fails if the composite index disappears (the defect this
     */
    @Test fun theEntityDeclaresTheMailboxIndexAndTheCompositeOne() {
        assertEquals(
            "the `indices` line of EmailEntity is the schema a FRESH install gets. Changing it " +
                "without a matching migration step breaks every UPDATED install at open time, and " +
                "removing the composite index brings back the blank list on a folder switch with " +
                "the conversation view on.",
            // #1952: a third index (v35, MIGRATION_34_35, ListQueryPlan1952bTest) covers the domain-grouped list; the two before it are unchanged.
            "indices = [Index(\"mailboxId\"), Index(value = [\"accountId\", \"mailboxId\", \"sortKey\"]), " +
                "Index(value = [\"accountId\", \"mailboxId\", \"fromDomain\", \"sortKey\", \"seen\", \"id\"])],",
            indicesLine(),
        )
    }

    // --- (b) what the shipped migration really creates ----------------------------------------------

    /**
     * The executed half: the registered 26→27 step runs against a real SQLite, and the index it
     */
    @Test fun theMigrationCreatesExactlyWhatTheEntityDeclares() {
        val declared = compositeColumnsDeclaredByTheEntity()

        db.createStatement().use { st ->
            st.executeUpdate(EMAILS_CREATE_SQL)
            st.executeUpdate(EMAILS_MAILBOX_INDEX_SQL)
        }
        registeredStep26().migrate(supportDb())

        val name = "index_emails_" + declared.joinToString("_")
        assertEquals(
            "Room names an index `index_<table>_<col>_<col>…`, so the entity line above makes the " +
                "fresh install's index `$name`; the migration must create that same name, or an " +
                "updated install carries an index Room does not recognise and validateMigration " +
                "fails at open. Indexes found on `emails`: ${indexesOnEmails()}",
            listOf(name),
            indexesOnEmails().filter { it != "index_emails_mailboxId" && !it.startsWith("sqlite_autoindex") },
        )
        assertEquals(
            "and in the same column ORDER: (accountId, mailboxId) is the scope folderScopeSql " +
                "binds, sortKey last is the list's ORDER BY and the representative join",
            declared,
            indexColumns(name),
        )
        assertEquals(
            "and in the same SHAPE. `Index(value = [...])` on the entity declares no direction and " +
                "no uniqueness, so Room builds a plain ascending non-unique index on a FRESH " +
                "install; Room then reads `PRAGMA index_xinfo` and `PRAGMA index_list` back at " +
                "every open and compares `orders` and `unique` (TableInfo.Index.equals). A `DESC` " +
                "or a `UNIQUE` in the migration alone therefore diverges from the entity in a way " +
                "the column names cannot show, and validateMigration throws on every launch of an " +
                "UPDATED install — never on a fresh one, so never in a quick bench check. The way " +
                "out for the user is a reinstall, which takes the outbox with it.",
            "unique=0 partial=0 " + declared.joinToString(", ") { "$it ASC BINARY" },
            indexShape(name),
        )
    }

    /** The composite index's columns, read out of the entity's pinned `indices` line. */
    private fun compositeColumnsDeclaredByTheEntity(): List<String> {
        val match = Regex("Index\\(value = \\[([^\\]]*)]\\)").find(indicesLine())
        assertNotNull(
            "EmailEntity declares no `Index(value = [...])` on `emails`: the composite index a " +
                "fresh install would build is gone, so a fresh install and an updated one no " +
                "longer hold the same schema. Line was: ${indicesLine()}",
            match,
        )
        return match!!.groupValues[1].split(",").map { it.trim().trim('"') }
    }

    private fun registeredStep26(): androidx.room.migration.Migration {
        val step = SternaDatabase.ALL_MIGRATIONS.firstOrNull { it.startVersion == 26 }
        assertNotNull(
            "no migration starting at v26 is registered in SternaDatabase.ALL_MIGRATIONS " +
                "(registered: " +
                SternaDatabase.ALL_MIGRATIONS.joinToString { "${it.startVersion}->${it.endVersion}" } +
                "): the entity would declare an index no updated install ever creates",
            step,
        )
        return step!!
    }

    private fun supportDb(): SupportSQLiteDatabase =
        Proxy.newProxyInstance(
            SupportSQLiteDatabase::class.java.classLoader,
            arrayOf(SupportSQLiteDatabase::class.java),
        ) { _, method, args ->
            when {
                method.name == "execSQL" && args?.size == 1 -> {
                    db.createStatement().use { it.executeUpdate(args[0] as String) }
                    null
                }
                method.name == "toString" -> "SupportSQLiteDatabase(jdbc)"
                method.name == "hashCode" -> System.identityHashCode(this)
                method.name == "equals" -> false
                else -> error("Unexpected SupportSQLiteDatabase.${method.name} in a migration")
            }
        } as SupportSQLiteDatabase

    private fun indexesOnEmails(): List<String> {
        val names = mutableListOf<String>()
        db.createStatement().use { st ->
            st.executeQuery(
                "SELECT `name` FROM `sqlite_master` WHERE `type` = 'index' AND `tbl_name` = 'emails' " +
                    "ORDER BY `name`",
            ).use { rs ->
                while (rs.next()) names += rs.getString("name")
            }
        }
        return names
    }

    /**
     * The full SHAPE of [index]: `unique=<0|1> partial=<0|1>` then its key columns in index order,
     */
    private fun indexShape(index: String): String {
        var unique = "?"
        var partial = "?"
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_list(`emails`)").use { rs ->
                while (rs.next()) {
                    if (rs.getString("name") == index) {
                        unique = rs.getInt("unique").toString()
                        partial = rs.getInt("partial").toString()
                    }
                }
            }
        }
        val bySeq = sortedMapOf<Int, String>()
        db.createStatement().use { st ->
            st.executeQuery("PRAGMA index_xinfo(`$index`)").use { rs ->
                while (rs.next()) {
                    if (rs.getInt("key") != 1) continue
                    val dir = if (rs.getInt("desc") == 1) "DESC" else "ASC"
                    bySeq[rs.getInt("seqno")] = "${rs.getString("name")} $dir ${rs.getString("coll")}"
                }
            }
        }
        return "unique=$unique partial=$partial " + bySeq.values.joinToString(", ")
    }

    /** The indexed columns of [index] alone, in index order — [indexShape] without the rest. */
    private fun indexColumns(index: String): List<String> =
        indexShape(index).substringAfter(" partial=").substringAfter(" ")
            .split(", ").filter { it.isNotBlank() }.map { it.substringBefore(" ") }
}
