package com.diegonmarcos.clouddrive.sync

import com.diegonmarcos.cloudlib.gitui.*

import com.diegonmarcos.clouddrive.Declarations

/**
 * #1256/#1315 THE FILTERS of the Git table, evaluated from the DECLARATION (build.json ui.sync.git.filters): an option's meaning
 * is its declared predicates (kinds, cloned, lfs, private, hub, archived, level), never a Kind name typed here. Filters combine
 * (AND); a filter whose selection is its declared default is "off"; the search narrows by name. Counts give every option the
 * number of rows it would leave with the OTHER filters in force. The selection is persisted as one prefs string
 * ([encode]/[decode]). Pure, JVM-tested (RepoFiltersTest).
 */
object RepoFilters {
    const val ALL = "all"

    /** What a filter can look at for one row: only facts the table already holds. */
    data class Facts(
        val cloned: Boolean, val status: RepoStatus.Kind?, val lfs: Boolean?, val isPrivate: Boolean?, val hub: Boolean?,
        val archived: Boolean = false, /** "light" | "full" | "" = the status read's level. */ val level: String = "", val name: String = "",
    )

    /** The option of [filter] the selection means (its declared default when none is stored). */
    fun selected(filter: Declarations.GitFilterDecl, selection: Map<String, String>): String =
        selection[filter.id]?.takeIf { o -> filter.options.any { it.id == o } } ?: filter.default

    /** The declared predicates of [o] against [f]; the `all` option (or an unknown one) matches everything. */
    fun matches(o: Declarations.GitFilterOptionDecl?, f: Facts): Boolean {
        if (o == null || o.id == ALL) return true
        if (o.kinds.isNotEmpty() && (f.status == null || f.status.name.lowercase() !in o.kinds)) return false
        if (o.cloned != null && o.cloned != f.cloned) return false
        if (o.lfs != null && f.lfs != o.lfs) return false
        if (o.private != null && f.isPrivate != o.private) return false
        if (o.hub != null && (f.hub == true) != o.hub) return false
        if (o.archived != null && f.archived != o.archived) return false
        if (o.level.isNotBlank() && f.level != o.level) return false
        return true
    }

    fun matches(filter: Declarations.GitFilterDecl, selection: Map<String, String>, f: Facts): Boolean = matches(filter.option(selected(filter, selection)), f)

    /** The rows every filter and the search leave. */
    fun <T> apply(items: List<T>, filters: List<Declarations.GitFilterDecl>, selection: Map<String, String>, query: String = "", facts: (T) -> Facts): List<T> {
        val q = query.trim()
        return items.filter { item ->
            val f = facts(item)
            (q.isBlank() || f.name.contains(q, ignoreCase = true)) && filters.all { matches(it, selection, f) }
        }
    }

    /** For each option of [filter]: how many rows it would leave, with the other filters and the search in force. */
    fun <T> counts(items: List<T>, filters: List<Declarations.GitFilterDecl>, selection: Map<String, String>, query: String, filter: Declarations.GitFilterDecl, facts: (T) -> Facts): Map<String, Int> {
        val base = apply(items, filters.filter { it.id != filter.id }, selection, query, facts).map(facts)
        return filter.options.associate { o -> o.id to base.count { matches(o, it) } }
    }

    /** How many filters are off their default (the badge on the Filters button). */
    fun active(filters: List<Declarations.GitFilterDecl>, selection: Map<String, String>): Int = filters.count { selected(it, selection) != it.default }

    /** The next stored value when [option] of [filter] is tapped: the option, or the default when it was already on (a toggle). */
    fun toggle(filter: Declarations.GitFilterDecl, selection: Map<String, String>, option: String): String = if (selected(filter, selection) == option) filter.default else option

    fun encode(sel: Map<String, String>): String = sel.filterValues { it != ALL }.entries.joinToString(";") { "${it.key}=${it.value}" }
    fun decode(s: String?): Map<String, String> = s.orEmpty().split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 && it[0].isNotBlank() }?.let { it[0] to it[1] } }.toMap()

    /** The persisted sort: "COLUMN:asc|desc"; an unknown column falls back to the default. */
    fun encodeSort(c: RepoColumn, asc: Boolean): String = c.name + ":" + (if (asc) "asc" else "desc")
    fun decodeSort(s: String?): Pair<RepoColumn, Boolean> {
        val p = s.orEmpty().split(':')
        val c = RepoColumn.entries.firstOrNull { it.name == p.getOrNull(0) } ?: return RepoTable.DEFAULT_SORT to true
        return c to (p.getOrNull(1) != "desc")
    }
}
