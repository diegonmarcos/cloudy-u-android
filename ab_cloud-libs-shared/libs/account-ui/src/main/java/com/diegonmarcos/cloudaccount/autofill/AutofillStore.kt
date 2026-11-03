package com.diegonmarcos.cloudaccount.autofill

import android.content.Context
import android.content.SharedPreferences
import com.diegonmarcos.superapp.autofill.AutofillAddress
import com.diegonmarcos.superapp.autofill.AutofillContact
import com.diegonmarcos.superapp.autofill.AutofillProfile
import com.diegonmarcos.superapp.autofill.AutofillRows
import com.diegonmarcos.superapp.autofill.AutofillSot
import com.diegonmarcos.superapp.autofill.ContactKind
import com.diegonmarcos.superapp.autofill.SiteRule
import com.diegonmarcos.superapp.autofill.Snippet
import org.json.JSONArray
import org.json.JSONObject

/**
 * The non-secret autofill Source of Truth at rest (a0_docs/eng-specs/autofill-3-tier.md):
 * the SharedPreferences file `autofill_sot`, one JSON array per table (profiles, addresses, contacts,
 * rules, snippets), declared in fleet-config.json (class config, so a new phone gets the same data).
 *
 * A row is `column → text`, exactly the columns [AutofillSot.columns] names for its table:
 * anything else a writer sends is DROPPED here, so no caller can smuggle a column (a
 * "password", a card number, an ID document) into the store. Rule rows must be valid
 * ([SiteRule.valid]); an address or contact must belong to an existing profile, and deleting a
 * profile deletes its addresses and contacts. One default per table (per profile for children).
 * Every write is synchronous (commit), so the provider answers what is really stored.
 */
class AutofillStore(context: Context, private val clock: () -> Long = System::currentTimeMillis) {
    private val sp: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(path: String) = "${path}_json"

    @Synchronized
    fun rows(path: String): List<Map<String, String>> {
        val a = runCatching { JSONArray(sp.getString(key(path), "[]")) }.getOrElse { JSONArray() }
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o ->
            AutofillSot.columns(path).associateWith { c -> o.optString(c, "") }
        }
    }

    @Synchronized
    fun row(path: String, id: Long): Map<String, String>? = rows(path).firstOrNull { it[AutofillSot.ID] == id.toString() }

    /** Inserts a row; answers its id, or null when the table is unknown or the row is not acceptable. */
    @Synchronized
    fun insert(path: String, values: Map<String, String?>): Long? {
        val cols = AutofillSot.columns(path).ifEmpty { return null }
        val id = sp.getLong(K_NEXT_ID, 1L)
        val clean = clean(path, cols, values) + mapOf(AutofillSot.ID to id.toString(), AutofillSot.UPDATED_AT to clock().toString())
        if (!acceptable(path, clean)) return null
        val all = rows(path).toMutableList()
        if (clean[AutofillSot.IS_DEFAULT] == "1") clearDefaults(path, all, clean)
        all += clean
        write(path, all, id + 1)
        return id
    }

    /** Merges [values] into row [id]; answers the number of rows changed (0 or 1). */
    @Synchronized
    fun update(path: String, id: Long, values: Map<String, String?>): Int {
        val cols = AutofillSot.columns(path).ifEmpty { return 0 }
        val all = rows(path).toMutableList()
        val i = all.indexOfFirst { it[AutofillSot.ID] == id.toString() }.takeIf { it >= 0 } ?: return 0
        val merged = all[i] + clean(path, cols, values) + (AutofillSot.UPDATED_AT to clock().toString())
        if (!acceptable(path, merged)) return 0
        if (merged[AutofillSot.IS_DEFAULT] == "1") clearDefaults(path, all, merged)
        all[i] = merged
        write(path, all, null)
        return 1
    }

    @Synchronized
    fun delete(path: String, id: Long): Int {
        val all = rows(path)
        val kept = all.filterNot { it[AutofillSot.ID] == id.toString() }
        if (kept.size == all.size) return 0
        write(path, kept, null)
        if (path == AutofillSot.PATH_PROFILES) for (child in AutofillSot.CHILD_PATHS) {
            write(child, rows(child).filterNot { it[AutofillSot.PROFILE_ID] == id.toString() }, null)
        }
        return 1
    }

    /** One default per table; for addresses and contacts one per profile (and per contact kind). */
    private fun clearDefaults(path: String, all: MutableList<Map<String, String>>, row: Map<String, String>) {
        fun same(r: Map<String, String>) = when (path) {
            AutofillSot.PATH_ADDRESSES -> r[AutofillSot.PROFILE_ID] == row[AutofillSot.PROFILE_ID]
            AutofillSot.PATH_CONTACTS -> r[AutofillSot.PROFILE_ID] == row[AutofillSot.PROFILE_ID] && r[AutofillSot.C_KIND] == row[AutofillSot.C_KIND]
            else -> true
        }
        all.replaceAll { if (same(it)) it + (AutofillSot.IS_DEFAULT to "0") else it }
    }

    // ── typed views for the editor ───────────────────────────────────────
    fun profiles(): List<AutofillProfile> =
        AutofillRows.assemble(rows(AutofillSot.PATH_PROFILES), rows(AutofillSot.PATH_ADDRESSES), rows(AutofillSot.PATH_CONTACTS))
    fun rules(): List<SiteRule> = rows(AutofillSot.PATH_RULES).map(AutofillRows::rule)
    fun snippets(): List<Snippet> = rows(AutofillSot.PATH_SNIPPETS).map(AutofillRows::snippet)

    private fun upsert(path: String, id: Long, cols: Map<String, String>): Long? =
        if (id > 0 && update(path, id, cols) == 1) id else insert(path, cols)

    /**
     * Saves [p] with its addresses and contacts: the profile row, then every child under its id; a child
     * the editor removed (present in the store, absent from [p]) is deleted. Answers the profile id.
     */
    @Synchronized
    fun save(p: AutofillProfile): Long? {
        val pid = upsert(AutofillSot.PATH_PROFILES, p.id, AutofillRows.columns(p)) ?: return null
        val keepA = p.addresses.filterNot { it.isEmpty }.mapNotNull { upsert(AutofillSot.PATH_ADDRESSES, it.id, AutofillRows.columns(it, pid)) }.toSet()
        val keepC = p.contacts.filter { it.value.isNotBlank() }.mapNotNull { upsert(AutofillSot.PATH_CONTACTS, it.id, AutofillRows.columns(it, pid)) }.toSet()
        for ((path, keep) in listOf(AutofillSot.PATH_ADDRESSES to keepA, AutofillSot.PATH_CONTACTS to keepC)) {
            rows(path).filter { it[AutofillSot.PROFILE_ID] == pid.toString() && (it[AutofillSot.ID]?.toLongOrNull() ?: 0) !in keep }
                .forEach { delete(path, it[AutofillSot.ID]!!.toLong()) }
        }
        return pid
    }
    fun save(r: SiteRule): Long? = upsert(AutofillSot.PATH_RULES, r.id, AutofillRows.columns(r))
    fun save(s: Snippet): Long? = upsert(AutofillSot.PATH_SNIPPETS, s.id, AutofillRows.columns(s))

    private fun clean(path: String, cols: List<String>, values: Map<String, String?>): Map<String, String> =
        values.filterKeys { it in cols && it != AutofillSot.ID && it != AutofillSot.UPDATED_AT }
            .mapValues { (k, v) -> normalise(path, k, v.orEmpty()) }

    private fun normalise(path: String, col: String, v: String): String = when {
        path == AutofillSot.PATH_RULES && col == AutofillSot.R_DOMAIN -> v.trim().lowercase().removePrefix("https://").removePrefix("http://").substringBefore('/')
        col == AutofillSot.IS_DEFAULT || col == AutofillSot.R_ENABLED -> if (v == "1" || v.equals("true", true)) "1" else "0"
        else -> v.take(MAX_VALUE)
    }

    private fun acceptable(path: String, row: Map<String, String>): Boolean = when (path) {
        AutofillSot.PATH_RULES -> AutofillRows.rule(row).valid
        AutofillSot.PATH_SNIPPETS -> row[AutofillSot.S_TEXT].orEmpty().isNotBlank()
        AutofillSot.PATH_ADDRESSES -> profileExists(row[AutofillSot.PROFILE_ID])
        AutofillSot.PATH_CONTACTS -> profileExists(row[AutofillSot.PROFILE_ID]) &&
            row[AutofillSot.C_KIND] in ContactKind.ALL && row[AutofillSot.C_VALUE].orEmpty().isNotBlank()
        else -> true
    }

    private fun profileExists(id: String?) = !id.isNullOrBlank() && rows(AutofillSot.PATH_PROFILES).any { it[AutofillSot.ID] == id }

    private fun write(path: String, rows: List<Map<String, String>>, nextId: Long?) {
        val a = JSONArray(); rows.forEach { r -> a.put(JSONObject(r)) }
        val ed = sp.edit().putString(key(path), a.toString())
        if (nextId != null) ed.putLong(K_NEXT_ID, nextId)
        ed.commit()
    }

    companion object {
        /** Declared in fleet-config.json (stores.autofill_sot). */
        const val FILE = "autofill_sot"
        private const val K_NEXT_ID = "next_id"
        private const val MAX_VALUE = 2000
    }
}
