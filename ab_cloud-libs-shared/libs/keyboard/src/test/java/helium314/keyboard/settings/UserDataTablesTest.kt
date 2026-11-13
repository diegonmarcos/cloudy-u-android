// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Autocomplete user-data tables: schemes, add / edit / delete, persistence through the declared store, no data baked in. */
class UserDataTablesTest {
    private class Mem : UserDataBacking {
        var text: String? = null
        override fun read() = text
        override fun write(value: String) { text = value }
    }

    private var n = 0
    private fun tables(m: Mem = Mem()) = UserDataTables(m) { "id${n++}" }

    @Test fun theFiveTablesAndTheirSchemes() {
        assertEquals(listOf("Personal Data", "Keys", "Urls", "Notes", "Others"), UserTable.entries.map { it.title })
        val p = UserTable.PERSONAL
        listOf("Name", "Last name", "Mail", "Address", "Phone").forEach { assertTrue(it, it in p.suggested) }
        assertEquals(1, UserTable.KEYS.maskedColumn)
        UserTable.entries.forEach { assertTrue(it.columns.size >= 2) }
        assertEquals(UserTable.URLS, UserTable.of("urls"))
        assertNull(UserTable.of("nope"))
    }

    @Test fun startsEmpty_noDataIsBakedIn() {
        val t = tables()
        assertTrue(t.all().isEmpty())
        UserTable.entries.forEach { assertTrue(t.rows(it).isEmpty()) }
    }

    @Test fun addEditDeletePersistAndReload() {
        val m = Mem()
        val t = tables(m)
        val a = t.add(UserTable.PERSONAL, listOf("Mail", "me@example.org"))!!
        val k = t.add(UserTable.KEYS, listOf("Server", "s3cret", "prod"))!!
        assertEquals(listOf("Mail", "me@example.org"), t.rows(UserTable.PERSONAL).single().cells)
        assertTrue(t.edit(a.id, listOf("Mail", "other@example.org")))
        assertTrue(t.delete(k.id))
        // a new instance over the same backing sees exactly the persisted state
        val again = UserDataTables(m)
        assertEquals(listOf("other@example.org"), again.all().map { it.cells[1] })
        assertTrue(again.rows(UserTable.KEYS).isEmpty())
        assertFalse(t.delete("missing"))
        assertFalse(t.edit("missing", listOf("x", "y")))
    }

    @Test fun refusesAnEmptyFirstCellAndTooLongCellsAndFullTables() {
        val t = tables()
        assertNull(t.add(UserTable.URLS, listOf("  ", "https://example.org")))
        assertNotNull(UserDataTables.problemOf(UserTable.URLS, listOf("", "x")))
        assertNull(t.add(UserTable.NOTES, listOf("t", "x".repeat(UserDataTables.MAX_CELL + 1))))
        repeat(UserDataTables.MAX_ROWS) { assertNotNull(t.add(UserTable.OTHERS, listOf("l$it", "v"))) }
        assertNull(t.add(UserTable.OTHERS, listOf("one more", "v")))
        val id = t.rows(UserTable.OTHERS).first().id
        assertFalse(t.edit(id, listOf("", "v")))
    }

    @Test fun cellsAreFittedToTheColumns() {
        val t = tables()
        assertEquals(listOf("A", ""), t.add(UserTable.PERSONAL, listOf("A"))!!.cells)
        assertEquals(3, t.add(UserTable.KEYS, listOf("n", "k", "x", "extra"))!!.cells.size)
    }

    @Test fun unreadableStoredTextNeverThrows() {
        val m = Mem().also { it.text = "{not json" }
        assertTrue(UserDataTables(m).all().isEmpty())
        m.text = """{"rows":[{"t":"bogus","id":"1","c":["a"]},{"t":"keys","id":"","c":["a"]},{"t":"keys","id":"2","c":["a","b"]}]}"""
        assertEquals(listOf("2"), UserDataTables(m).all().map { it.id })
    }

    @Test fun storeIsDeclaredInFleetConfigAndNothingIsBaked() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.firstOrNull { File(it, "ab_cloud-libs-shared/libs/fleetconfig-model").isDirectory } ?: return
        val manifest = File(root, "ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json").readText()
        assertTrue(manifest.contains("\"autocomplete_userdata\""))
        val app = File(root, "ac_cloud-keyboard/app/src/main/java/com/diegonmarcos/cloudkeyboard/App.kt").readText()
        assertTrue(app.contains("SafeEncryptedPrefs.prefs(ctx, \"autocomplete_userdata\")"))
        assertTrue(File("src/main/java/helium314/keyboard/settings/KeyboardSettingsExtras.kt").readText().contains("userDataPrefs"))
    }
}
