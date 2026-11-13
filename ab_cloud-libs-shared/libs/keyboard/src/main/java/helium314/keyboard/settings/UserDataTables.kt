// SPDX-License-Identifier: GPL-3.0-only
package helium314.keyboard.settings

import org.json.JSONArray
import org.json.JSONObject

/**
 * The user data Autocomplete draws on, as five tables the owner edits on Writer > Configs Writer > Autocomplete. The schemes
 * (which tables, which columns, which fields are suggested) are declared here ONCE; no row is declared here or shipped in
 * the APK: every row is what the user typed, kept in the app's declared store (fleet-config `autocomplete_userdata`).
 * Pure (org.json only), so the model is tested on the JVM through an in-memory [UserDataBacking].
 */
enum class UserTable(val id: String, val title: String, val columns: List<String>, val suggested: List<String> = emptyList(), val maskedColumn: Int = -1) {
    /** Name, last name, mail, address, phone, etc.: one row per field; [suggested] are the scheme's field names, not data. */
    PERSONAL("personal", "Personal Data", listOf("Field", "Value"),
        listOf("Name", "Last name", "Mail", "Address", "Phone", "City", "Postal code", "Country", "Birthday", "Company")),
    KEYS("keys", "Keys", listOf("Name", "Key", "Note"), maskedColumn = 1),
    URLS("urls", "Urls", listOf("Name", "Url")),
    NOTES("notes", "Notes", listOf("Title", "Text")),
    OTHERS("others", "Others", listOf("Label", "Value"));

    companion object {
        fun of(id: String): UserTable? = entries.firstOrNull { it.id == id }
    }
}

/** One row of a table: [cells] hold one value per column of [table], in order. */
data class UserRow(val table: UserTable, val id: String, val cells: List<String>) {
    /** The row's first cell, its name in a list. */
    val name: String get() = cells.firstOrNull().orEmpty()
}

/** Where the one JSON document lives. The phone's is the app's encrypted prefs; a JVM test hands in a variable. */
interface UserDataBacking {
    fun read(): String?
    fun write(value: String)
}

class UserDataTables(private val backing: UserDataBacking, private val newId: () -> String = { java.util.UUID.randomUUID().toString() }) {
    /** Every row of every table; empty when nothing is stored or the stored text is unreadable (never throws). */
    fun all(): List<UserRow> = runCatching {
        val text = backing.read() ?: return emptyList()
        val a = JSONObject(text).optJSONArray("rows") ?: return emptyList()
        (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            val t = UserTable.of(o.optString("t")) ?: return@mapNotNull null
            val c = o.optJSONArray("c") ?: return@mapNotNull null
            UserRow(t, o.optString("id").ifBlank { return@mapNotNull null }, fit(t, (0 until c.length()).map { c.optString(it) }))
        }
    }.getOrDefault(emptyList())

    fun rows(table: UserTable): List<UserRow> = all().filter { it.table == table }

    /** Why [cells] cannot be saved in [table], or null when they can. */
    fun problem(table: UserTable, cells: List<String>): String? = problemOf(table, cells)

    /** Adds a row to [table]; null when it was refused ([problem], or the table is full). */
    fun add(table: UserTable, cells: List<String>): UserRow? {
        val c = fit(table, cells)
        if (problem(table, c) != null || rows(table).size >= MAX_ROWS) return null
        val row = UserRow(table, newId(), c)
        save(all() + row)
        return row
    }

    /** Replaces the cells of row [id]; false when there is no such row or the cells were refused. */
    fun edit(id: String, cells: List<String>): Boolean {
        val cur = all()
        val old = cur.firstOrNull { it.id == id } ?: return false
        val c = fit(old.table, cells)
        if (problem(old.table, c) != null) return false
        save(cur.map { if (it.id == id) it.copy(cells = c) else it })
        return true
    }

    /** Removes row [id]; false when there was none. */
    fun delete(id: String): Boolean {
        val cur = all()
        if (cur.none { it.id == id }) return false
        save(cur.filterNot { it.id == id })
        return true
    }

    private fun save(rows: List<UserRow>) = backing.write(JSONObject().put("v", 1).put("rows", JSONArray().also { a ->
        rows.forEach { r -> a.put(JSONObject().put("t", r.table.id).put("id", r.id).put("c", JSONArray(r.cells))) }
    }).toString())

    companion object {
        /** Why [cells] cannot be saved in [table], or null when they can (no store needed: the dialog asks before it saves). */
        fun problemOf(table: UserTable, cells: List<String>): String? = when {
            cells.firstOrNull().orEmpty().isBlank() -> "${table.columns.first()} cannot be empty"
            cells.any { it.length > MAX_CELL } -> "A value is longer than $MAX_CELL characters"
            else -> null
        }
        const val MAX_ROWS = 500
        const val MAX_CELL = 4000
        /** The cells padded or cut to the table's column count. */
        fun fit(table: UserTable, cells: List<String>): List<String> =
            List(table.columns.size) { cells.getOrNull(it).orEmpty().trim() }
    }
}
