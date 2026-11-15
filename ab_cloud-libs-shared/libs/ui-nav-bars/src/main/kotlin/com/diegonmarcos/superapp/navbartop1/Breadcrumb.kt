package com.diegonmarcos.superapp.navbartop1

import com.diegonmarcos.superapp.bottomnav.NavSection

/** One segment of the title path. [target] is where a tap goes (null: the segment is not a destination, e.g. an open message). */
public data class Crumb(val label: String, val target: HierarchyEntry?)

/**
 * The first row's title as DATA, derived from the app's nav declaration (sections/pages tree) and nothing else:
 * [crumbs] are "{App} / {Section} / {Page} / ... / {leaf}" and [related] are the pages the dropdown lists under the
 * path: the SIBLINGS of the current page, then its CHILDREN (each tappable). [currentDepth] is the current page's depth in
 * [HierarchyEntry] terms (0 = a bottom section), so a row can indent the children.
 */
public data class TitleNav(
    val crumbs: List<Crumb>,
    val related: List<HierarchyEntry>,
    val currentDepth: Int,
    /**
     * #1758 the app's sections (the current one selected), always on offer: [related] already holds them at depth 0, but a section whose
     * tab strip is shown has its selection moved to a page, so below depth 0 they are listed HERE (a "Sections" group after the pages and the page actions).
     */
    val sections: List<HierarchyEntry> = emptyList(),
) {
    /** The path as the title shows it, left to right. */
    public val labels: List<String> get() = crumbs.map { it.label }
}

/**
 * [hierarchy] is [titleHierarchy] of the open section (empty when the app has no pages to list); [sections] are the
 * app's bottom sections (the siblings of a section, and where the app crumb goes: its first). [title] stands for the
 * section when [hierarchy] is empty. [leaf] is a page-less last segment such as the open message's subject. Pure.
 */
public fun titleNav(
    appName: String,
    title: String,
    hierarchy: List<HierarchyEntry>,
    sections: List<NavSection> = emptyList(),
    leaf: String? = null,
): TitleNav {
    val section = hierarchy.firstOrNull { it.depth == 0 }
    val declared = sections.map { HierarchyEntry(it.id, it.label, 0, it.id, selected = it.id == section?.sectionId) }
    // a section the app did not hand in (e.g. Settings, opened from the top bar, not from the island) is still a peer of the others
    val sectionPeers = if (declared.isNotEmpty() && section != null && declared.none { it.id == section.sectionId }) {
        declared + HierarchyEntry(section.sectionId, section.label, 0, section.sectionId, selected = true)
    } else declared
    val root = sectionPeers.firstOrNull() ?: section
    val crumbs = ArrayList<Crumb>()
    crumbs += Crumb(appName, root)
    if (section == null) {
        if (title.isNotBlank() && title != appName) crumbs += Crumb(title, null)
        leaf?.takeIf { it.isNotBlank() }?.let { crumbs += Crumb(it, null) }
        return TitleNav(crumbs, sectionPeers, 0)
    }
    val path = hierarchy.filter { it.depth > 0 && it.selected }
    crumbs += Crumb(section.label, section)
    path.forEach { crumbs += Crumb(it.label, it) }
    leaf?.takeIf { it.isNotBlank() }?.let { crumbs += Crumb(it, null) }

    val cur = path.lastOrNull() ?: section
    val ci = hierarchy.indexOf(cur)
    val depth = cur.depth
    val siblings: List<HierarchyEntry> = if (depth == 0) {
        sectionPeers.ifEmpty { listOf(section) }
    } else {
        // the entries sharing the current page's parent: walk back to the parent, forward over its direct children
        var p = ci - 1
        while (p >= 0 && hierarchy[p].depth >= depth) p--
        val out = ArrayList<HierarchyEntry>()
        var k = p + 1
        while (k < hierarchy.size && hierarchy[k].depth > hierarchy[p].depth) {
            if (hierarchy[k].depth == depth) out += hierarchy[k]
            k++
        }
        out
    }
    val children = ArrayList<HierarchyEntry>()
    var i = ci + 1
    while (i < hierarchy.size && hierarchy[i].depth > depth) {
        if (hierarchy[i].depth == depth + 1) children += hierarchy[i]
        i++
    }
    return TitleNav(crumbs, siblings + children, depth, if (depth == 0) emptyList() else sectionPeers)
}

/**
 * The path as one line that fits: when [fits] says the whole of it does not, leading segments are dropped and an ellipsis
 * stands for them ("... / Inbox / Subject"), so the end of the path, where the user is, always stays. The last segment alone
 * is returned when even that does not fit (the caller ellipsizes it). Pure, so it is testable without measuring text.
 */
public fun fitCrumbs(labels: List<String>, fits: (String) -> Boolean): String {
    if (labels.isEmpty()) return ""
    for (drop in 0 until labels.size) {
        val line = (if (drop > 0) listOf("…") else emptyList<String>()).plus(labels.drop(drop)).joinToString(" / ")
        if (fits(line)) return line
    }
    return labels.last()
}
