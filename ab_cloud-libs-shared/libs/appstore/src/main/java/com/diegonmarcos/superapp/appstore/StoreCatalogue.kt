package com.diegonmarcos.superapp.appstore

import org.json.JSONObject

/**
 * #1791 THE Store's own app catalogue (assets/store-catalogue.json): one row per package, the one list every Search > Phone page filters.
 * Pure parsing, no Android types besides org.json, so a JVM test proves nothing was dropped. A row keeps EVERY string field it was given
 * ([Entry.attrs]); the filter rows (id, field, label and values of each chip) are data too, so Kotlin names no chip label.
 */
object StoreCatalogue {
    const val ASSET = "store-catalogue.json"

    class Value(val id: String, val label: String)
    /** One filter row: [field] is the entry attribute it tests; its chips are "all" then [values]. */
    class Row(val id: String, val field: String, val label: String, val values: List<Value>)
    class Entry(val attrs: Map<String, String>) {
        fun get(field: String): String = attrs[field].orEmpty()
        val pkg get() = get("pkg")
        val name get() = get("name").ifBlank { pkg }
        val developer get() = get("developer")
        val icon get() = get("icon")
        val storeUrl get() = get("store_url")
        val apk get() = get("apk")
    }
    class Catalogue(val rows: List<Row>, val entries: List<Entry>, val allLabel: String, val tiers: List<Value>, val problems: List<String> = emptyList())

    val EMPTY = Catalogue(emptyList(), emptyList(), "", emptyList())

    /** Parses the asset; never throws: a bad document is an empty catalogue that says why. */
    fun parse(text: String?): Catalogue {
        if (text.isNullOrBlank()) return Catalogue(emptyList(), emptyList(), "", emptyList(), listOf("empty"))
        val root = runCatching { JSONObject(text) }.getOrElse { return Catalogue(emptyList(), emptyList(), "", emptyList(), listOf("not JSON")) }
        val rows = ArrayList<Row>(); val entries = ArrayList<Entry>(); val problems = ArrayList<String>()
        root.optJSONArray("filters")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { o ->
            val id = o.optString("id").trim()
            val vs = ArrayList<Value>()
            o.optJSONArray("values")?.let { v -> for (j in 0 until v.length()) v.optJSONObject(j)?.let { x -> vs += Value(x.optString("id"), x.optString("label").ifBlank { x.optString("id") }) } }
            if (id.isNotEmpty() && rows.none { it.id == id }) rows += Row(id, o.optString("field").ifBlank { id }, o.optString("label").ifBlank { id }, vs)
        } }
        root.optJSONArray("apps")?.let { a -> for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val pkg = o.optString("pkg").trim()
            if (pkg.isEmpty() || entries.any { it.pkg == pkg }) { problems += "entry ${i + 1}: no package or duplicate"; continue }
            entries += Entry(o.keys().asSequence().associateWith { k -> o.optString(k) })
        } }
        val tiers = ArrayList<Value>()
        root.optJSONArray("play_integrity_tiers")?.let { a -> for (i in 0 until a.length()) a.optJSONObject(i)?.let { tiers += Value(it.optString("id"), it.optString("label")) } }
        return Catalogue(rows, entries, root.optString("all_label").ifBlank { "All" }, tiers, problems)
    }
}
