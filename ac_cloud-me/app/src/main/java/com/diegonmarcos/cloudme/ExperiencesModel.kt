package com.diegonmarcos.cloudme

/**
 * The pure half of Experiences' Done | Planned view (the `ledger` block with `"view": "done_plan"`, see
 * [ExperiencesBlock]). Every Experiences tab is one ledger of two collections in the store `experiences_user`: the
 * DONE side (no `move_stamp`) and the PLANNED side (its `move_stamp` set; "Mark done" moves a record across). This
 * object turns those two collections into items with a status, a date, a category and an optional link, and derives
 * everything the page shows above the list: the per-category counts, the progress share and, on By 30 / 40 / 50, the
 * age-milestone timeline. Nothing here touches Android, so the rules are unit-tested as they are.
 */
enum class ExpStatus(val word: String) { DONE("Done"), IN_PROGRESS("In progress"), PLANNED("Planned") }

data class ExpItem(
    val collection: String,
    /** The record id in [collection]; `builtin:<n>` / `suggestion:<n>` for declared rows, which are read-only. */
    val id: String,
    val rec: Rec,
    val status: ExpStatus,
    val title: String,
    /** Done: the date it was done. Planned: the target date. Blank when unknown. */
    val date: String,
    val category: String,
    val note: String,
    val link: String,
    val readOnly: Boolean,
    /** >= 0: a declared suggestion ("Accept" stores it as the owner's own planned item). */
    val suggestion: Int = -1,
)

/** One category's tally: "12 done · 5 planned". */
data class CatCount(val category: String, val done: Int, val planned: Int) {
    val total: Int get() = done + planned
    val label: String get() = "$done done · $planned planned"
}

/** One age milestone of By 30 / 40 / 50: what was done before that age and what is planned by it. */
data class Milestone(val age: Int, val year: Int, val done: Int, val planned: Int, val reached: Boolean)

object ExperiencesModel {
    const val OTHER = "Other"
    const val STATUS = "status"
    const val LINK = "link"
    /** Where "Mark done" keeps the target date it replaces with the done date. */
    const val TARGET = "target"

    /** The DONE side is the collection that moves without stamping, the PLANNED side the one that stamps. */
    fun doneDef(defs: List<LedgerDef>): LedgerDef? = defs.firstOrNull { it.moveStamp.isBlank() }
    fun planDef(defs: List<LedgerDef>): LedgerDef? = defs.firstOrNull { it.moveStamp.isNotBlank() }

    fun status(rec: Rec, planned: Boolean): ExpStatus = when {
        !planned -> ExpStatus.DONE
        rec[STATUS].orEmpty().trim().equals(ExpStatus.IN_PROGRESS.word, ignoreCase = true) -> ExpStatus.IN_PROGRESS
        else -> ExpStatus.PLANNED
    }

    /** Every item of the tab: declared rows and the owner's records on both sides, plus the open suggestions. */
    fun items(defs: List<LedgerDef>, data: LedgerData, group: String = "category"): List<ExpItem> {
        fun item(def: LedgerDef, id: String, r: Rec, planned: Boolean, readOnly: Boolean, sg: Int = -1) = ExpItem(
            def.collection, id, r, status(r, planned), r[def.fields.firstOrNull()?.id ?: "title"].orEmpty(),
            r["date"].orEmpty(), r[group].orEmpty().trim().ifEmpty { OTHER }, r["note"].orEmpty(), r[LINK].orEmpty(),
            readOnly, sg,
        )
        val out = mutableListOf<ExpItem>()
        for ((def, planned) in listOfNotNull(doneDef(defs)?.let { it to false }, planDef(defs)?.let { it to true })) {
            def.builtin.forEachIndexed { n, r -> out += item(def, "builtin:$n", r, planned, true) }
            data.rows(def.collection).forEach { r -> out += item(def, r["id"].orEmpty(), r, planned, false) }
            val first = def.fields.firstOrNull()?.id ?: "title"
            val taken = data.rows(def.collection).map { it[first] }.toSet()
            def.suggestions.forEachIndexed { n, r -> if (r[first] !in taken) out += item(def, "suggestion:$n", r, true, true, n) }
        }
        return out
    }

    /** Done on one side, planned and in progress on the other. */
    fun split(items: List<ExpItem>): Pair<List<ExpItem>, List<ExpItem>> = items.partition { it.status == ExpStatus.DONE }

    /** Done: newest first. Planned: in progress first, then the nearest target. Undated items go last, by title. */
    fun sorted(items: List<ExpItem>): List<ExpItem> =
        if (items.all { it.status == ExpStatus.DONE })
            items.sortedWith(compareBy<ExpItem> { it.date.isBlank() }.thenByDescending { it.date }.thenBy { it.title.lowercase() })
        else
            items.sortedWith(compareBy<ExpItem> { it.status != ExpStatus.IN_PROGRESS }.thenBy { it.date.isBlank() }.thenBy { it.date }.thenBy { it.title.lowercase() })

    /** [query] matches title, note, category, link and every other value, case-insensitive; a blank [category] is all. */
    fun filter(items: List<ExpItem>, query: String, category: String?): List<ExpItem> {
        val q = query.trim().lowercase()
        return items.filter { i ->
            (category.isNullOrBlank() || i.category == category) &&
                (q.isEmpty() || i.rec.values.any { it.lowercase().contains(q) } || i.category.lowercase().contains(q))
        }
    }

    /** Groups by category, biggest group first, each group sorted by date ([sorted]). */
    fun groups(items: List<ExpItem>): List<Pair<String, List<ExpItem>>> =
        items.groupBy { it.category }.map { (c, l) -> c to sorted(l) }
            .sortedWith(compareByDescending<Pair<String, List<ExpItem>>> { it.second.size }.thenBy { it.first })

    /** Per category: done vs planned (in progress counts as planned), the biggest first. */
    fun counts(items: List<ExpItem>): List<CatCount> =
        items.groupBy { it.category }.map { (c, l) -> CatCount(c, l.count { it.status == ExpStatus.DONE }, l.count { it.status != ExpStatus.DONE }) }
            .sortedWith(compareByDescending<CatCount> { it.total }.thenBy { it.category })

    /** The done share of everything, 0..1; nothing at all is 0. */
    fun progress(done: Int, planned: Int): Float = if (done + planned == 0) 0f else done.toFloat() / (done + planned)

    /**
     * "Mark done": the planned record moves to the done side and its date becomes [today], always. A target date it
     * had is kept as [TARGET]; the planned-only status is dropped.
     */
    fun markDone(data: LedgerData, plan: String, done: String, id: String, today: String): LedgerData {
        val rec = data.rows(plan).firstOrNull { it["id"] == id } ?: return data
        val target = rec["date"].orEmpty()
        val moved = rec - "id" - STATUS + ("date" to today) + (if (target.isNotBlank() && target != today) mapOf(TARGET to target) else emptyMap())
        return data.removed(plan, id).added(done, moved)
    }

    /** "Mark undone": back to planned, its target date restored (blank when it never had one). */
    fun markUndone(data: LedgerData, done: String, plan: String, id: String): LedgerData {
        val rec = data.rows(done).firstOrNull { it["id"] == id } ?: return data
        val back = rec - "id" - "date" - TARGET + (rec[TARGET]?.takeIf { it.isNotBlank() }?.let { mapOf("date" to it) } ?: emptyMap())
        return data.removed(done, id).added(plan, back)
    }

    /** Planned ↔ in progress on a planned record. */
    fun withStatus(data: LedgerData, plan: String, id: String, status: ExpStatus): LedgerData {
        val rec = data.rows(plan).firstOrNull { it["id"] == id } ?: return data
        val next = if (status == ExpStatus.IN_PROGRESS) rec + (STATUS to ExpStatus.IN_PROGRESS.word) else rec - STATUS
        return data.replaced(plan, id, next - "id")
    }

    fun year(date: String?): Int? = date?.trim()?.let { Regex("""^(\d{4})""").find(it)?.groupValues?.get(1)?.toIntOrNull() }

    /**
     * The age an item counts towards: the first milestone age the person had not reached in the item's year
     * (done in 2015 by someone born in 1988 counts for "by 30"); an item dated after the last milestone counts for the
     * last one; an undated item counts for the tab it was filed under, [own].
     */
    fun bucket(year: Int?, own: Int, born: Int, ages: List<Int>): Int {
        if (year == null) return own
        val sortedAges = ages.sorted()
        return sortedAges.firstOrNull { year < born + it } ?: sortedAges.last()
    }

    /** The By 30 / 40 / 50 timeline over the collections `by<age>_done` / `by<age>_todo` of the whole store. */
    fun milestones(born: Int, ages: List<Int>, data: LedgerData, thisYear: Int): List<Milestone> {
        val done = ages.associateWith { 0 }.toMutableMap()
        val planned = ages.associateWith { 0 }.toMutableMap()
        for (a in ages) {
            data.rows("by${a}_done").forEach { r -> bucket(year(r["date"]), a, born, ages).let { done[it] = done.getValue(it) + 1 } }
            data.rows("by${a}_todo").forEach { r -> bucket(year(r["date"]), a, born, ages).let { planned[it] = planned.getValue(it) + 1 } }
        }
        return ages.sorted().map { a -> Milestone(a, born + a, done.getValue(a), planned.getValue(a), thisYear >= born + a) }
    }

    /** The leading emoji of a category ("🌿 Personal Growth" → 🌿), else [fallback]. */
    fun icon(category: String, fallback: String): String {
        val head = category.trim().substringBefore(' ')
        return if (head.isNotEmpty() && head != category.trim() && head.none { it.isLetterOrDigit() }) head else fallback
    }

    /** The category without its leading emoji, for chips and headers that already draw the icon. */
    fun name(category: String): String = if (icon(category, "") != "") category.trim().substringAfter(' ').trim() else category.trim()
}
