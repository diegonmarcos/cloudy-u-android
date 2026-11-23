package com.diegonmarcos.cloudme

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The pure half of Buro and LLC's user data (ticket: Cloud Me restructure). Both sections keep what the owner types as
 * LEDGERS: named collections of flat records, declared by the page's `ledger` block in data/ui/<section>/<page>.json
 * (fields, labels, types). One store per section (SharedPreferences `buro_user` / `llc_user`, declared in
 * fleet-config.json so the backup and the new-phone migration carry them), one JSON string per store. Nothing here
 * is seeded: a collection nobody typed into is empty and the page says so.
 *
 * Money is text in the store and [BigDecimal] in arithmetic, so a sum of cents never drifts.
 */
typealias Rec = Map<String, String>

/** One typed field of a ledger. [type] is text | money | date | choice | number (plain, e.g. cm or kg) | image (a local photo path). */
data class LedgerField(val id: String, val label: String, val type: String, val options: List<String>, val required: Boolean)

/** One collection of a ledger block: its records are `LedgerData.rows(collection)`. */
data class LedgerDef(
    val collection: String, val title: String, val fields: List<LedgerField>, val empty: String,
    /** Declared read-only rows shown with the user's (the page's own facts); never stored. */
    val builtin: List<Rec> = emptyList(),
    /** Declared proposals: Accept stores one as the user's own record (editable); accepted ones stop showing. */
    val suggestions: List<Rec> = emptyList(),
    /** Non-blank: collection a record can be moved to ("Mark done"), with [moveLabel] on the button. */
    val moveTo: String = "", val moveLabel: String = "",
    /** Non-blank: field set to today's date by the move when the record has none ("Mark done" stamps the done date). */
    val moveStamp: String = "",
    /** #1367 declared records copied into the store ONCE (see [LedgerSeed]); from then on they are the owner's. */
    val seed: List<Rec> = emptyList(),
) {
    companion object {
        fun parse(o: JSONObject): LedgerDef = LedgerDef(
            o.getString("collection"), o.optString("title"),
            (o.optJSONArray("fields") ?: JSONArray()).let { a ->
                (0 until a.length()).map { a.getJSONObject(it) }.map { f ->
                    LedgerField(f.getString("id"), f.optString("label", f.getString("id")), f.optString("type", "text"),
                        (f.optJSONArray("options") ?: JSONArray()).let { op -> (0 until op.length()).map { op.getString(it) } },
                        f.optBoolean("required", false))
                }
            },
            o.optString("empty", "Nothing here yet."),
            recs(o.optJSONArray("builtin")), recs(o.optJSONArray("suggestions")),
            o.optString("move_to"), o.optString("move_label", "Move"), o.optString("move_stamp"),
            recs(o.optJSONArray("seed")),
        )

        private fun recs(a: JSONArray?): List<Rec> =
            (0 until (a?.length() ?: 0)).mapNotNull { a!!.optJSONObject(it) }.map { r -> r.keys().asSequence().associateWith { k -> r.optString(k) } }
    }
}

/** Every collection of one store. Immutable: each change returns a new value that the caller saves. */
class LedgerData(private val data: Map<String, List<Rec>> = emptyMap()) {
    fun rows(collection: String): List<Rec> = data[collection].orEmpty()

    fun collections(): Set<String> = data.keys

    /** Adds [values] as a new record with the next free `id` (never reused within a collection). */
    fun added(collection: String, values: Rec): LedgerData {
        val rows = rows(collection)
        val next = (rows.mapNotNull { it["id"]?.removePrefix("r")?.toLongOrNull() }.maxOrNull() ?: 0L) + 1
        return LedgerData(data + (collection to rows + (values + ("id" to "r$next"))))
    }

    fun removed(collection: String, id: String): LedgerData =
        LedgerData(data + (collection to rows(collection).filterNot { it["id"] == id }))

    /** Replaces the record [id] of [collection] with [values] (same id); an unknown id changes nothing. */
    fun replaced(collection: String, id: String, values: Rec): LedgerData =
        LedgerData(data + (collection to rows(collection).map { if (it["id"] == id) values + ("id" to id) else it }))

    /** Moves the record [id] from [from] to the end of [to] (new id there). A non-blank [stampField] that the record
     *  leaves blank gets [today]: marking an item done records when. */
    fun moved(from: String, id: String, to: String, stampField: String = "", today: String = ""): LedgerData {
        val rec = rows(from).firstOrNull { it["id"] == id } ?: return this
        val stamped = if (stampField.isNotBlank() && today.isNotBlank() && rec[stampField].isNullOrBlank()) rec + (stampField to today) else rec
        return removed(from, id).added(to, stamped - "id")
    }

    /** Every collection but [collection]. */
    fun without(collection: String): LedgerData = LedgerData(data - collection)

    fun toJson(): String = JSONObject().put("v", 1).put("collections", JSONObject().apply {
        data.forEach { (name, rows) -> put(name, JSONArray().apply { rows.forEach { r -> put(JSONObject(r)) } }) }
    }).toString()

    companion object {
        /** Blank or malformed text is an empty ledger, never a crash. */
        fun fromJson(text: String?): LedgerData {
            if (text.isNullOrBlank()) return LedgerData()
            return runCatching {
                val cs = JSONObject(text).optJSONObject("collections") ?: return LedgerData()
                LedgerData(cs.keys().asSequence().associateWith { name ->
                    val a = cs.optJSONArray(name) ?: JSONArray()
                    (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { o -> o.keys().asSequence().associateWith { k -> o.optString(k) } }
                })
            }.getOrDefault(LedgerData())
        }
    }
}

object LedgerModel {
    fun money(s: String?): BigDecimal? = s?.trim()?.replace(',', '.')?.takeIf { it.isNotEmpty() }?.toBigDecimalOrNull()

    /** `yyyy-MM-dd`, with a real month and day. */
    fun isDate(s: String?): Boolean {
        val m = s?.let { Regex("""(\d{4})-(\d{2})-(\d{2})""").matchEntire(it.trim()) } ?: return false
        val (y, mo, d) = m.destructured
        val days = intArrayOf(31, if (y.toInt() % 4 == 0 && (y.toInt() % 100 != 0 || y.toInt() % 400 == 0)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
        return mo.toInt() in 1..12 && d.toInt() in 1..days[mo.toInt() - 1]
    }

    /** Field id to the sentence saying what is wrong; empty means the values may be saved. */
    fun validate(def: LedgerDef, values: Rec): Map<String, String> = buildMap {
        for (f in def.fields) {
            val v = values[f.id].orEmpty().trim()
            when {
                v.isEmpty() -> if (f.required) put(f.id, "${f.label} is required")
                f.type == "money" && money(v) == null -> put(f.id, "${f.label} must be a number")
                f.type == "number" && (money(v)?.signum() ?: -1) < 0 -> put(f.id, "${f.label} must be a number, zero or more")
                f.type == "date" && !isDate(v) -> put(f.id, "${f.label} must be a date, yyyy-mm-dd")
                f.type == "choice" && v !in f.options -> put(f.id, "${f.label} must be one of ${f.options.joinToString(", ")}")
            }
        }
    }

    /** Only the declared fields, trimmed, blanks dropped: what [LedgerData.added] stores. */
    fun clean(def: LedgerDef, values: Rec): Rec =
        def.fields.mapNotNull { f -> values[f.id]?.trim()?.takeIf { it.isNotEmpty() }?.let { f.id to it } }.toMap()

    fun fmt(v: BigDecimal, currency: String): String = v.setScale(2, RoundingMode.HALF_UP).toPlainString() + (if (currency.isBlank()) "" else " $currency")
}
