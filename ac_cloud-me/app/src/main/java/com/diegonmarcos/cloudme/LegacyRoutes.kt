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
        // to its section id draws two title-menu entries with one tag). #1901 Lnktree is a bar section again, its pages carry the lt- prefix (page ids are unique in the app).
        "me/lnktree" to "lnktree/lt-lnktree-site",
        "lnktree/lnktree" to "lnktree/lt-lnktree-site",
        "buro/summary" to "buro/ids",
        "buro/docs" to "buro/assets",
        "buro/fin" to "buro/cashflow",
        "buro/acct" to "buro/cashflow",
        "buro/budget" to "buro/budgeting",
        "buro/portfolio" to "llc/portfolio",
        // #1350 Study and Achieves were subpages of Experiences, the old Achieves pages' content moved to its tabs; #1901 CV was under Prof (#1937: Prof > More is removed, Study resolves to Prof > Profiles)
        // and the Achieves tabs under Pers (the ids below are the live ones; ui.renamed maps the ids of the layout in between).
        "study" to "prof/prof-web",
        "achieves" to "pers/geo",
        "achieves/personal" to "pers/growth",
        "achieves/pursuits" to "pers/endurance",
        "achieves/international" to "pers/geo",
        "achieves/trips" to "pers/geo",
        "achieves/experiences" to "pers/endurance",
        // #1367 RoadMap and Duties are the two subpages of Tasks; every page of either retired section lands on its subpage.
        "roadmap" to "tasks/roadmaps",
        "duties" to "tasks/backlogs",
        // #1439 Configs was one page; it is now General | Perms | About | Navigation | Apps.
        "configs/configs" to "configs/general",
        // #1199 N12 a page id names one page in the app: the duplicated ids were disambiguated.
        "buro/dd" to "buro/personal-dd",
        "buro/nav" to "buro/nav-value",
        "llc/assets" to "llc/company-assets",
        "llc/calendar" to "llc/llc-calendar",
    )

    /** The new (section, page) for an old one; the arguments come back unchanged when nothing moved. */
    fun resolve(section: String, page: String?): Pair<String, String?> = renamed(resolveLegacy(section, page), table)

    /** ui.renamed as the running app has it: read from the `renamed.json` asset by [load] (MainActivity.onCreate), nothing renamed before that. */
    @Volatile private var table: Renamed = Renamed.EMPTY

    /** Reads build.json::ui.renamed, which app/build.gradle writes to the `renamed.json` asset (data is read at runtime, not baked into BuildConfig). */
    fun load(ctx: android.content.Context) { table = Renamed.fromAsset(ctx) }

    /** A test hands in the table it declares. */
    fun install(t: Renamed) { table = t }

    /**
     * #1901 the second stage: build.json::ui.renamed, the section and page ids of the layout before Health became Pers, Experiences Prof and
     * Socials Proj. Applied after the table above (which still names the ids of earlier layouts, some of them the ids of this one's predecessor),
     * once, to its result: every entry of ui.renamed already names a page of the live tree.
     */
    fun renamed(at: Pair<String, String?>, table: Renamed): Pair<String, String?> {
        val (section, page) = at
        val key = if (page.isNullOrBlank()) section else "$section/$page"
        table.pages[key]?.let { return it.substringBefore('/') to it.substringAfter('/', "").ifBlank { null } }
        // A page the table does not list keeps its id inside the section that took the old one's place.
        table.sections[section]?.let { return it to page }
        return at
    }

    /** build.json::ui.renamed, parsed: `sections` (old id -> section id) and `pages` ("old section/old page" -> "section/page"). */
    data class Renamed(val sections: Map<String, String>, val pages: Map<String, String>) {
        companion object {
            val EMPTY = Renamed(emptyMap(), emptyMap())

            fun parse(o: org.json.JSONObject?): Renamed {
                fun map(k: String): Map<String, String> = o?.optJSONObject(k)?.let { m -> m.keys().asSequence().associateWith { m.getString(it) } }.orEmpty()
                return Renamed(map("sections"), map("pages"))
            }

            const val ASSET = "renamed.json"

            /** The shipped declaration (asset [ASSET], written by app/build.gradle from build.json::ui.renamed); fail-soft to "nothing renamed". */
            fun fromAsset(ctx: android.content.Context): Renamed = runCatching {
                parse(org.json.JSONObject(ctx.assets.open(ASSET).bufferedReader().use { it.readText() }))
            }.getOrDefault(EMPTY)
        }
    }

    /** The first stage on its own: the retired layouts' table, no ui.renamed. */
    fun resolveLegacy(section: String, page: String?): Pair<String, String?> {
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
