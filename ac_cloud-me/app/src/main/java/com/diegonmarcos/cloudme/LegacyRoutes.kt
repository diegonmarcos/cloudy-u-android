package com.diegonmarcos.cloudme

/**
 * Where the destinations of the retired Cloud Me layout went, so a saved state, a SuperApp tile or a shortcut written
 * against the old ids still lands somewhere sensible instead of nowhere. The Work section is now LLC (Employed kept,
 * Endeavours became Side hustles); House is gone and its household bills, inventory and upkeep belong to Buro > Docs
 * (Assets, Services, Liabilities & Taxes); Profile > Tasks moved to its own Duties section, and #1367 merged Duties and
 * RoadMap into Tasks (Backlogs and RoadMaps), Inventory taking the Duties slot; Buro's old Docs, Fin and
 * Summary pages became the Buro groups. Old stores held no user entries (Work and House were generated or empty pages),
 * so routes are the whole migration of the navigation; the Work pages' content moved with its generator (data/regen-study.py).
 */
object LegacyRoutes {
    /** "section/page" (or "section" alone) to its new "section/page". */
    private val moved: Map<String, String> = mapOf(
        "work/employed" to "llc/employed",
        "work/endeavours" to "llc/side-hustles",
        "work" to "llc/employed",
        "house/manage" to "buro/assets",
        "house" to "buro/assets",
        "me/tasks" to "tasks/backlogs",
        // #1299 the section took the name Lnktree, so its embedded linktree site page is lnktree-site (a page id equal
        // to its section id draws two title-menu entries with one tag).
        "me/lnktree" to "lnktree/lnktree-site",
        "lnktree/lnktree" to "lnktree/lnktree-site",
        "buro/summary" to "buro/ids",
        "buro/docs" to "buro/assets",
        "buro/fin" to "buro/cashflow",
        "buro/acct" to "buro/cashflow",
        "buro/budget" to "buro/budgeting",
        "buro/portfolio" to "llc/portfolio",
        // #1350 Study and Achieves are subpages of Experiences; the old Achieves pages' content moved to its tabs.
        "study" to "experiences/cv",
        "achieves" to "experiences/geo",
        "achieves/personal" to "experiences/growth",
        "achieves/pursuits" to "experiences/endurance",
        "achieves/international" to "experiences/geo",
        "achieves/trips" to "experiences/geo",
        "achieves/experiences" to "experiences/endurance",
        // #1367 RoadMap and Duties are the two subpages of Tasks; every page of either retired section lands on its subpage.
        "roadmap" to "tasks/roadmaps",
        "duties" to "tasks/backlogs",
        // #1439 Configs was one page; it is now General | Perms | About | Navigation | Apps.
        "configs/configs" to "configs/general",
    )

    /** The new (section, page) for an old one; the arguments come back unchanged when nothing moved. */
    fun resolve(section: String, page: String?): Pair<String, String?> {
        val hit = moved["$section/${page.orEmpty()}".trimEnd('/')]
        // A page of a retired section that the table does not list still opens the section's new home.
        val fallback = if (hit == null && section in RETIRED_SECTIONS) moved.getValue(section) else null
        // #1299 a RENAMED section keeps every page: the old id is an alias of the new one (me -> lnktree).
        if (hit == null && fallback == null) RENAMED_SECTIONS[section]?.let { return it to page }
        val to = hit ?: fallback ?: return section to page
        return to.substringBefore('/') to to.substringAfter('/', "").ifBlank { null }
    }

    private val RETIRED_SECTIONS = setOf("work", "house", "roadmap", "duties")

    /** #1299 Profile (`me`) is now Lnktree: old deep links, shortcuts and saved state name `me`. */
    private val RENAMED_SECTIONS: Map<String, String> = mapOf(
        "me" to "lnktree",
        // #1350 every other page of Study (cv, roadmap) and Achieves (geo ... by50) keeps its id inside Experiences.
        "study" to "experiences",
        "achieves" to "experiences",
    )
}
