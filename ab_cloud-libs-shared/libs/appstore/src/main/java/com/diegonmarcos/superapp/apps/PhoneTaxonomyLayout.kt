package com.diegonmarcos.superapp.apps

/**
 * THE ORDER of the Phone taxonomy on screen: sections in `ui.phone_sections` order, each holding
 * the folders (sub-categories) whose label starts with its prefix, in the folders' own order, and
 * then one trailing group of the folders no section prefix claims.
 *
 * SuperApp ▸ Phone (PhoneAppsFragment.renderAllApps) and Cloud Store ▸ Phone ▸ Search's landing
 * both lay their groups out through [bucket], so the two cannot list the same taxonomy in two
 * orders. The data is shared already ([PhoneSections], [PhoneFolders], baked from SuperApp's
 * build.json into this lib); this is the shared reading of it.
 *
 * Pure Kotlin on purpose (no Android types): ac_cloud-store/test/test-store-search-landing.sh
 * compiles it with kotlinc and checks the order on a plain JVM.
 */
object PhoneTaxonomyLayout {

    /** One section of the page. [title] is null for the trailing group no prefix claims. */
    class Group<F>(val title: String?, val prefix: String, val folders: List<F>)

    /**
     * [sections] as (title, prefix) in declared order; [folders] already in display order and
     * already filtered by the caller (SuperApp keeps pinned empty folders, the Store drops every
     * empty one). Empty sections are dropped; the unclaimed group comes last, and only when it
     * holds a folder.
     */
    fun <F> bucket(sections: List<Pair<String, String>>, folders: List<F>, label: (F) -> String): List<Group<F>> {
        val bySection = LinkedHashMap<Pair<String, String>, MutableList<F>>()
        sections.forEach { bySection[it] = mutableListOf() }
        val other = mutableListOf<F>()
        for (f in folders) {
            val sec = sections.firstOrNull { label(f).startsWith(it.second) }
            if (sec != null) bySection.getValue(sec).add(f) else other.add(f)
        }
        val out = bySection.filterValues { it.isNotEmpty() }.map { (s, fs) -> Group(s.first, s.second, fs.toList()) }
        return if (other.isEmpty()) out else out + Group(null, "", other.toList())
    }

    /**
     * The folder label as DRAWN: the section prefix character ("=Browser" -> "Browser") is the section key
     * in the data and stays there; the screen shows the name only. A label no prefix claims is unchanged.
     */
    fun displayLabel(label: String, prefixes: List<String>): String {
        val p = prefixes.firstOrNull { it.isNotEmpty() && label.startsWith(it) } ?: return label
        return label.removePrefix(p).trimStart()
    }
}
