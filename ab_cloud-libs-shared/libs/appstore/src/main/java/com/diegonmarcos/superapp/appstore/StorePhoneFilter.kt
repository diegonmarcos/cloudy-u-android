package com.diegonmarcos.superapp.appstore

/**
 * #1791 Search > Phone's filter rows over the central catalogue ([StoreCatalogue]), pure and JVM-testable. Each row starts with All
 * and holds one pick; the rows combine with AND. The rows themselves (which field, which chips) are the catalogue's `filters`.
 *
 * A live search hit that is not in the catalogue has no category, Play Integrity tier or dimension, so it only passes a row left on All
 * (or a row it can answer: licence from the sources that found it, source from the source that found it).
 */
object StorePhoneFilter {
    const val ALL = "all"

    /** The picks: row id -> value id; a row absent (or [ALL]) is unfiltered. */
    class State(val picks: Map<String, String> = emptyMap()) {
        fun of(row: String): String = picks[row] ?: ALL
        fun with(row: String, value: String): State = State(if (value == ALL) picks - row else picks + (row to value))
        val active: Boolean get() = picks.isNotEmpty()
    }

    fun tag(row: String, value: String) = "store-search:phone-filter:$row:$value"

    /** Does [e] pass every row. */
    fun matches(cat: StoreCatalogue.Catalogue, e: StoreCatalogue.Entry, s: State): Boolean =
        cat.rows.all { r -> val v = s.of(r.id); v == ALL || e.get(r.field) == v }

    fun apply(cat: StoreCatalogue.Catalogue, s: State, exclude: Set<String> = emptySet()): List<StoreCatalogue.Entry> =
        cat.entries.filter { it.pkg !in exclude && matches(cat, it, s) }

    /** The entries a chip would leave given the other rows as they are (the count drawn on the chip). */
    fun count(cat: StoreCatalogue.Catalogue, s: State, row: String, value: String, exclude: Set<String> = emptySet()): Int =
        apply(cat, s.with(row, value), exclude).size

    /** Free-text query over the catalogue itself (name, developer, package), so a listed app is found with no network. */
    fun matchQuery(list: List<StoreCatalogue.Entry>, query: String): List<StoreCatalogue.Entry> {
        val q = query.trim().lowercase()
        return if (q.isEmpty()) emptyList() else list.filter { q in it.name.lowercase() || q in it.developer.lowercase() || q in it.pkg.lowercase() }
    }

    /** The search source id an entry is installed by: the listing store it came from (fdroid | play | github), as before the catalogue. */
    fun resultSource(e: StoreCatalogue.Entry): String = e.get("source_listed").ifBlank { StoreSearchPage.PLAY }

    /** A catalogue entry as a search result (the same row, tile and verbs as any hit). */
    fun result(e: StoreCatalogue.Entry): StoreSuggest.Result =
        StoreSuggest.Result("cat:${e.pkg}", e.pkg, e.name, e.developer.ifBlank { e.get("licence_spdx") }, listOf(resultSource(e)), e.get("licence") == "open",
            e.storeUrl, e.apk.ifBlank { e.storeUrl }, e.icon)

    /** The catalogue-style source of a live hit (it carries no catalogue row): Aurora is Play's anonymous session, the Phone page's only Play searcher. */
    private fun sourceOfHit(r: StoreSuggest.Result): String = when (r.primary) {
        SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY -> "fdroid"
        StoreSearchPage.PLAY -> "aurora"
        else -> "other"
    }

    /** Whether a live search hit passes [s]: its catalogue row when it has one, else what its sources can say. */
    fun matchesHit(cat: StoreCatalogue.Catalogue, byPkg: Map<String, StoreCatalogue.Entry>, r: StoreSuggest.Result, s: State, isOpen: (StoreSuggest.Result) -> Boolean): Boolean {
        byPkg[r.pkg]?.let { return matches(cat, it, s) }
        return cat.rows.all { row ->
            val v = s.of(row.id)
            if (v == ALL) true else when (row.field) {
                "licence" -> v == if (isOpen(r)) "open" else "closed"
                "source" -> v == sourceOfHit(r) || (v == "playstore" && sourceOfHit(r) == "aurora")
                else -> false
            }
        }
    }

    private val ALL_SOURCES = setOf(StoreSearchPage.DECLARED, SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.GITHUB, StoreSearchPage.PLAY)

    /** The Phone search sources worth running for [s]; the declared list always runs (the user's own apps). Never empty. */
    fun sources(s: State): Set<String> {
        var out = ALL_SOURCES
        when (s.of("licence")) {
            "open" -> out = out - StoreSearchPage.PLAY
            "closed" -> out = out - setOf(SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.GITHUB)
        }
        when (s.of("source")) {
            "fdroid" -> out = out - setOf(StoreSearchPage.GITHUB, StoreSearchPage.PLAY)
            "aurora", "playstore" -> out = out - setOf(SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.GITHUB)
            "other" -> out = out - setOf(SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.PLAY)
        }
        if (s.of("play_integrity") !in setOf(ALL, "none")) out = out - setOf(SourceResolver.KIND_FDROID, SourceResolver.KIND_IZZY, StoreSearchPage.GITHUB)
        return out + StoreSearchPage.DECLARED
    }

    /** The picks a subpage opens with: Open source and Non open source are the licence row, Games the category row, Play Integrity none (grouped by tier). */
    fun preset(sub: String): State = when (sub) {
        StorePhoneSearch.OPEN -> State(mapOf("licence" to "open"))
        StorePhoneSearch.CLOSED -> State(mapOf("licence" to "closed"))
        StorePhoneSearch.GAMES -> State(mapOf("category" to "games"))
        else -> State()
    }

    /** Entries grouped by the value of the row's field in its declared order, an empty group dropped unless [keepEmpty] (the tiers page keeps every tier), a value no row declares last. */
    fun groupBy(cat: StoreCatalogue.Catalogue, row: String, list: List<StoreCatalogue.Entry>, keepEmpty: Boolean = false): List<Pair<StoreCatalogue.Value, List<StoreCatalogue.Entry>>> {
        val r = cat.rows.firstOrNull { it.id == row } ?: return emptyList()
        val declared = r.values + list.map { it.get(r.field) }.filter { v -> r.values.none { it.id == v } }.distinct().map { StoreCatalogue.Value(it, it.ifBlank { "?" }) }
        return declared.mapNotNull { v -> list.filter { it.get(r.field) == v.id }.takeIf { keepEmpty || it.isNotEmpty() }?.let { v to it } }
    }

    /** The detail line of an entry's tier: what the catalogue says and how sure it is. */
    fun tierLine(cat: StoreCatalogue.Catalogue, e: StoreCatalogue.Entry): String {
        val label = cat.tiers.firstOrNull { it.id == e.get("play_integrity") }?.label ?: e.get("play_integrity")
        return "Play Integrity: $label (${e.get("play_integrity_basis").ifBlank { "unverified" }})" + e.get("note").takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
    }
}
