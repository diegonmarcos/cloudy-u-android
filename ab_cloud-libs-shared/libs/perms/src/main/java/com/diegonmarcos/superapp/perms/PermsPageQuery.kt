package com.diegonmarcos.superapp.perms

/**
 * #1546 the organized Perms page as data (PermsPageQueryTest, PermsRestoreTest): every catalogue declaration is a row
 * (not-applicable ones too, with their reason), and the page filters it by status, type, section, category, permission
 * group, protection, feature and fleet app, searches it, sorts it and groups it by any of those axes with per-group counts.
 * Nothing here removes a row from the catalogue: a filter only hides, "All" shows every declaration.
 */

/** The status filter chips, in page order; [ALL] shows every row. */
enum class StatusFilter(val word: String) {
    ALL("All"), MISSING("Missing"), GRANTED("Granted"), DENIED_PERMANENTLY("Denied permanently"), NEEDS_SHELL("Needs shell"),
    UNKNOWN("Check manually"), INFO("Info"), NOT_APPLICABLE("Not applicable"),
}

/** How the platform protects a grant: the "type" axis next to [PermKind]. */
enum class Protection(val word: String, val detail: String) {
    DANGEROUS("Dangerous", "runtime permission: the system dialog grants it"),
    APPOP("App-op / special", "special access: a system settings page (or an appops shell line) grants it"),
    SIGNATURE_DEV("Signature / development", "development permission: only pm grant over a shell grants it"),
    OEM("OEM setting", "vendor setting Android does not let an app read: follow the guide"),
    SYSTEM_TOGGLE("System toggle", "a device switch, not a grant of this app"),
}

/** The axes the page can group by. [CATEGORY] is the #1519 organization, [SECTION] the pre-#1519 one. */
enum class GroupBy(val word: String) {
    CATEGORY("Category"), SECTION("Section"), TYPE("Type"), PROTECTION("Protection"), GROUP("Permission group"),
    STATUS("Status"), FEATURE("Feature"), APP("Fleet app"), NONE("Flat list"),
}

enum class SortBy(val word: String) { MISSING_FIRST("Missing first"), CATALOGUE("Catalogue order"), NAME("Name"), TYPE("Type"), PROTECTION("Protection") }

/** The page's whole filter state; the default shows every row, grouped by category, missing first. */
data class PermsQuery(
    val text: String = "",
    val status: StatusFilter = StatusFilter.ALL,
    val kinds: Set<PermKind> = emptySet(),
    val sections: Set<PermSection> = emptySet(),
    val categories: Set<PermCategory> = emptySet(),
    val protections: Set<Protection> = emptySet(),
    val groups: Set<String> = emptySet(),
    val features: Set<String> = emptySet(),
    val apps: Set<String> = emptySet(),
    /** The pre-#1519 "Missing only" chip (on by default when a feature asked for rows: the highlight). */
    val missingOnly: Boolean = false,
    val highlight: Set<String>? = null,
    val groupBy: GroupBy = GroupBy.CATEGORY,
    val sortBy: SortBy = SortBy.MISSING_FIRST,
) {
    val active: Boolean get() = text.isNotBlank() || status != StatusFilter.ALL || kinds.isNotEmpty() || sections.isNotEmpty() ||
        categories.isNotEmpty() || protections.isNotEmpty() || groups.isNotEmpty() || features.isNotEmpty() || apps.isNotEmpty() || missingOnly
}

/** One group of the organized page: its key, title, rows and counts. */
data class QueryGroup(val key: String, val title: String, val rows: List<PermsPage.Row>) {
    val counted: List<PermsPage.Row> get() = rows.filter { it.counted }
    val granted: Int get() = counted.count { it.status.state == PermState.GRANTED }
    val missing: Int get() = rows.count { it.missing && !it.info }
    val count: String get() = "$granted/${counted.size}"
}

object PermsPageQuery {
    fun protection(e: PermEntry): Protection = when {
        e.section == PermSection.SYSTEM -> Protection.SYSTEM_TOGGLE
        e.kind == PermKind.RUNTIME -> Protection.DANGEROUS
        e.kind == PermKind.ADB_ONLY -> Protection.SIGNATURE_DEV
        e.check == "manual" || e.oem.isNotEmpty() -> Protection.OEM
        else -> Protection.APPOP
    }

    fun status(r: PermsPage.Row): StatusFilter = when {
        r.status.state == PermState.NOT_APPLICABLE -> StatusFilter.NOT_APPLICABLE
        r.status.state == PermState.GRANTED -> StatusFilter.GRANTED
        r.info -> StatusFilter.INFO
        r.status.state == PermState.UNKNOWN -> StatusFilter.UNKNOWN
        r.status.state == PermState.PERMANENTLY_DENIED -> StatusFilter.DENIED_PERMANENTLY
        r.entry.kind == PermKind.ADB_ONLY -> StatusFilter.NEEDS_SHELL
        else -> StatusFilter.MISSING
    }

    /** A status chip matches its own rows; MISSING also takes the permanently denied and needs-shell ones (all are missing). */
    fun statusMatches(f: StatusFilter, r: PermsPage.Row): Boolean = when (f) {
        StatusFilter.ALL -> true
        StatusFilter.MISSING -> r.missing && !r.info
        else -> status(r) == f
    }

    /** The number each status chip shows. */
    fun counts(rows: List<PermsPage.Row>): Map<StatusFilter, Int> = StatusFilter.values().associateWith { f -> rows.count { statusMatches(f, it) } }

    fun searchable(r: PermsPage.Row, apps: List<String>): String = with(r.entry) {
        listOf(id, label, permission, group, kind.name, section.title, r.category.title, why, lookFor, instruction, r.status.reason,
            protection(this).word, features.joinToString(" "), apps.joinToString(" ")).joinToString(" ").lowercase()
    }

    fun matches(r: PermsPage.Row, q: PermsQuery, appsOf: (String) -> List<String> = { emptyList() }): Boolean {
        val e = r.entry
        val hl = q.highlight
        // the pre-#1519 rule, kept exactly: Missing only hides granted rows, a highlighted row is never hidden by it when missing
        if (!((hl == null || e.id in hl || !q.missingOnly) && (!q.missingOnly || r.missing || r.status.state == PermState.UNKNOWN))) return false
        if (!statusMatches(q.status, r)) return false
        if (q.kinds.isNotEmpty() && e.kind !in q.kinds) return false
        if (q.sections.isNotEmpty() && e.section !in q.sections) return false
        if (q.categories.isNotEmpty() && r.category !in q.categories) return false
        if (q.protections.isNotEmpty() && protection(e) !in q.protections) return false
        if (q.groups.isNotEmpty() && e.group !in q.groups) return false
        if (q.features.isNotEmpty() && e.features.none { it in q.features }) return false
        if (q.apps.isNotEmpty() && appsOf(e.id).none { it in q.apps }) return false
        val words = q.text.trim().lowercase().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isNotEmpty()) { val hay = searchable(r, appsOf(e.id)); if (words.any { it !in hay }) return false }
        return true
    }

    private fun rank(r: PermsPage.Row): Int = when (status(r)) {
        StatusFilter.DENIED_PERMANENTLY -> 0; StatusFilter.MISSING, StatusFilter.NEEDS_SHELL -> 1; StatusFilter.UNKNOWN -> 2
        StatusFilter.INFO -> 3; StatusFilter.GRANTED -> 4; else -> 5
    }

    fun sort(rows: List<PermsPage.Row>, by: SortBy): List<PermsPage.Row> = when (by) {
        SortBy.CATALOGUE -> rows
        SortBy.MISSING_FIRST -> rows.sortedBy { rank(it) }
        SortBy.NAME -> rows.sortedBy { it.entry.label.lowercase() }
        SortBy.TYPE -> rows.sortedBy { it.entry.kind.ordinal }
        SortBy.PROTECTION -> rows.sortedBy { protection(it.entry).ordinal }
    }

    /** The keys a row is filed under for [by] (a feature / app axis can file one row under several groups). */
    fun keys(r: PermsPage.Row, by: GroupBy, appsOf: (String) -> List<String>): List<Pair<String, String>> = when (by) {
        GroupBy.CATEGORY -> listOf(r.category.name to r.category.title)
        GroupBy.SECTION -> listOf(r.entry.section.name to r.entry.section.title)
        GroupBy.TYPE -> listOf(r.entry.kind.name to kindTitle(r.entry.kind))
        GroupBy.PROTECTION -> protection(r.entry).let { listOf(it.name to it.word) }
        GroupBy.GROUP -> listOf(r.entry.group to r.entry.group.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() })
        GroupBy.STATUS -> status(r).let { listOf(it.name to it.word) }
        GroupBy.FEATURE -> r.entry.features.ifEmpty { listOf("none") }.map { it to it }
        GroupBy.APP -> appsOf(r.entry.id).ifEmpty { listOf("no fleet app") }.map { it to it }
        GroupBy.NONE -> listOf("ALL" to "All permissions")
    }

    fun kindTitle(k: PermKind): String = when (k) {
        PermKind.RUNTIME -> "Runtime"; PermKind.SPECIAL -> "Special access"; PermKind.APPOPS -> "App-ops"; PermKind.ADB_ONLY -> "ADB-only"
    }

    /** The order of an axis' groups: the enum order where the axis is an enum, else alphabetical. */
    private fun order(by: GroupBy, key: String): String = when (by) {
        GroupBy.CATEGORY -> "%02d".format(PermCategory.values().indexOfFirst { it.name == key })
        GroupBy.SECTION -> "%02d".format(PermSection.values().indexOfFirst { it.name == key })
        GroupBy.TYPE -> "%02d".format(PermKind.values().indexOfFirst { it.name == key })
        GroupBy.PROTECTION -> "%02d".format(Protection.values().indexOfFirst { it.name == key })
        GroupBy.STATUS -> "%02d".format(StatusFilter.values().indexOfFirst { it.name == key })
        else -> key.lowercase()
    }

    /** The organized page: matching rows, sorted, grouped by [PermsQuery.groupBy]; empty groups left out. */
    fun query(rows: List<PermsPage.Row>, q: PermsQuery, appsOf: (String) -> List<String> = { emptyList() }): List<QueryGroup> {
        val shown = sort(rows.filter { matches(it, q, appsOf) }, q.sortBy)
        val by = LinkedHashMap<String, Pair<String, MutableList<PermsPage.Row>>>()
        for (r in shown) for ((k, t) in keys(r, q.groupBy, appsOf)) by.getOrPut(k) { t to ArrayList() }.second += r
        return by.entries.sortedBy { order(q.groupBy, it.key) }.map { QueryGroup(it.key, it.value.first, it.value.second) }
    }

    /**
     * #1550 (pre-#1519 parity) the groups "Missing only" empties: shown with their full granted/applicable count and the line
     * "nothing missing", so a fully granted section stays on the page instead of vanishing. Empty unless [q] is missing-only.
     */
    fun nothingMissing(rows: List<PermsPage.Row>, q: PermsQuery, appsOf: (String) -> List<String> = { emptyList() }): List<QueryGroup> {
        if (!q.missingOnly) return emptyList()
        val shown = query(rows, q, appsOf).map { it.key }.toSet()
        return query(rows, q.copy(missingOnly = false), appsOf).filter { it.key !in shown }
    }

    /** #1550 (pre-#1519 parity) each group's header counts the WHOLE group (granted / applicable), not only the rows a filter left. */
    fun fullGroups(rows: List<PermsPage.Row>, q: PermsQuery, appsOf: (String) -> List<String> = { emptyList() }): Map<String, QueryGroup> =
        query(rows, q.copy(missingOnly = false), appsOf).associateBy { it.key }

    /** Every value a chip row can offer, from the rows themselves (data-driven: a new catalogue group gets its chip). */
    fun groupsPresent(rows: List<PermsPage.Row>): List<String> = rows.map { it.entry.group }.filter { it.isNotEmpty() }.distinct().sorted()
    fun featuresPresent(rows: List<PermsPage.Row>): List<String> = rows.flatMap { it.entry.features }.distinct().sorted()
    fun kindsPresent(rows: List<PermsPage.Row>): List<PermKind> = PermKind.values().filter { k -> rows.any { it.entry.kind == k } }
    fun sectionsPresent(rows: List<PermsPage.Row>): List<PermSection> = PermSection.values().filter { s -> rows.any { it.entry.section == s } }
    fun categoriesPresent(rows: List<PermsPage.Row>): List<PermCategory> = PermCategory.values().filter { c -> rows.any { it.category == c } }

    /** Bulk targets over the rows on screen. */
    fun dialogTargets(rows: List<PermsPage.Row>): List<PermEntry> = rows.filter { RowAction.GRANT in it.actions }.map { it.entry }
    fun shellTargets(rows: List<PermsPage.Row>): List<PermEntry> = rows.filter { RowAction.SHELL in it.actions }.map { it.entry }

    /** The per-permission detail lines (what it is, how it is protected, who uses it, its state). */
    fun detail(r: PermsPage.Row, apps: List<String>, liveProtection: String? = null): List<Pair<String, String>> = with(r.entry) {
        listOfNotNull(
            "What" to why.ifBlank { label },
            permission.takeIf { it.isNotEmpty() }?.let { "Permission" to it },
            "Type" to kindTitle(kind) + " · section " + section.title + " · category " + r.category.title,
            "Protection" to protection(this).word + ": " + protection(this).detail + (liveProtection?.let { " · system: $it" } ?: ""),
            "Group" to group,
            "State" to status(r).word + (r.status.reason.takeIf { it.isNotBlank() }?.let { " · $it" } ?: ""),
            (if (minSdk > 1 || maxSdk < Int.MAX_VALUE) "API" to listOfNotNull(minSdk.takeIf { it > 1 }?.let { "from $it" }, maxSdk.takeIf { it < Int.MAX_VALUE }?.let { "up to $it" }).joinToString(" ") else null),
            requires.takeIf { it.isNotEmpty() }?.let { "Requires" to it.joinToString(", ") },
            oem.takeIf { it.isNotEmpty() }?.let { "Vendor" to it },
            "Used by features" to features.joinToString(", ").ifEmpty { "-" },
            "Used by fleet apps" to apps.joinToString(", ").ifEmpty { "-" },
            lookFor.takeIf { it.isNotBlank() }?.let { "Look for" to it },
            instruction.takeIf { it.isNotBlank() }?.let { "How" to it },
            adb.takeIf { it.isNotEmpty() }?.let { "Shell" to it.joinToString(" ; ") },
            "Actions" to r.actions.joinToString(", ") { it.word }.ifEmpty { "-" },
        )
    }
}
