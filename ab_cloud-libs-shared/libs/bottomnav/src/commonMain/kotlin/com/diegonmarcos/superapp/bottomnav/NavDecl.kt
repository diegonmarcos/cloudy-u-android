package com.diegonmarcos.superapp.bottomnav

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * THE navigation declaration of the fleet (#868): `build.json::ui` as an app reads it at runtime.
 *
 *   ui.bottom_nav      [<section id>, ...]   [MIN_BOTTOM]..[MAX_BOTTOM] ids, in display order. The island.
 *   ui.sections        [{id,label,icon,background?,pages:[{id,label,icon,action?,background?,pages?:[...]}]}]
 *   ui.default_section <section id>          one of ui.bottom_nav
 *   ui.style           "fleet" (default) | "search-html"   see [NavStyle]; only the apps nav-shape.json::variants
 *                                            names may declare anything but the default (rule N8)
 *
 * A section's `pages` are its TOP tab strip ([PageTabs]); a page's own `pages` are a SECOND strip
 * below it, to any depth (Cloud Me's Projects > Health > Workout > Gym is three deep). The app
 * bakes `UI_BOTTOM_NAV` (the JSON array text), `UI_SECTIONS_B64` (base64 of the sections array)
 * and `UI_DEFAULT_SECTION` into BuildConfig; [fromBuildConfig] turns them into this.
 *
 * A section's or page's `background` ([PageBackground]: "dark" default, "light", or "theme" = the
 * app's own light/dark mode) is what its tab strip is drawn over, which picks the strip's lib-owned
 * [TabSurface]. Unset on a page = its parent's.
 *
 * Parsing is FAIL-SOFT: a malformed blob yields an empty declaration and an app that opens on a
 * blank page, never one that crashes on launch. The nav-shape guard (cloud-android-nav-shape-guard)
 * is what makes a malformed declaration a build failure instead; [NavDecl.problems] states the same
 * rules at runtime.
 */

/**
 * The most items the island holds (the owner lifted the old cap of 5, 2026-10). The island never
 * scrolls: a crowded bar widens and scales down to FIT ([planIsland]), so every item is always on
 * screen. 8 is where that stops being comfortable: on a 360dp phone 8 items are 44dp cells, the one
 * case already under the 48dp touch floor (8 x 48 = 384 > 360), and a 9th would be 39dp. Mail (7) is
 * the widest fleet bar; Cloud Code (7, its own Cordova nav) is exempt.
 */
public const val MAX_BOTTOM: Int = 8

/** The fewest items the island holds: one item is no choice, so it is no navigation. */
public const val MIN_BOTTOM: Int = 2

/** Up to this many items a cell draws full-size type down to the 48dp touch floor; above it, down to
 *  bottom_nav_min_cell_width (56dp). A narrower cell is FITTED (the island widens, then scales its
 *  icon and label down, [planIsland]); every bar that fits draws exactly the pixels it always did. */
public const val EQUAL_CELLS_BOTTOM: Int = 5

/**
 * What a tab strip is drawn over, as `build.json::ui` declares it (`sections[].background`,
 * `pages[].background`). [Theme] = the page follows the app's own light/dark mode, which only the
 * app knows; [surface] turns it into the strip's [TabSurface].
 */
public enum class PageBackground(public val declared: String) {
    Dark("dark"),
    Light("light"),
    Theme("theme");

    public fun surface(darkTheme: Boolean): TabSurface = when (this) {
        Dark -> TabSurface.Dark
        Light -> TabSurface.Light
        Theme -> if (darkTheme) TabSurface.Dark else TabSurface.Light
    }

    public companion object {
        /** A declared `background`; blank is null (inherit), anything unknown is null too (the guard rejects it). */
        public fun of(text: String?): PageBackground? =
            if (text.isNullOrBlank()) null else entries.firstOrNull { it.declared == text.trim() }
    }
}

/**
 * Which look the island is drawn in (`build.json::ui.style`). [Fleet] is SuperApp's island and every
 * app's; [SearchHtml] is the ONE declared exception (the owner asked, 2026-10): Cloud Search's bar from
 * his HTML mockup, a variant of this library ([SearchHtmlIsland]) and not an app's own nav. The nav-shape
 * guard (rule N8) lets only the apps nav-shape.json::variants names declare it.
 */
public enum class NavStyle(public val declared: String) {
    Fleet("fleet"),
    SearchHtml("search-html");

    public companion object {
        /** A declared `ui.style`; blank is [Fleet], anything unknown is null (the guard rejects it). */
        public fun of(text: String?): NavStyle? =
            if (text.isNullOrBlank()) Fleet else entries.firstOrNull { it.declared == text.trim() }
    }
}

/**
 * How a section's child pages are navigated (`ui.sections[].nav_mode`, runtime-overridable per section in the app's Configs):
 *  - [OneRow] `horizontal_one_row` (the default): the pages as one strip of chips, the second top row;
 *  - [ManyRows] `horizontal_two_rows`: one row per page GROUP in one shared box, each [group label as an index dropdown] | [that
 *    group's page icons]. The older contract name `many_rows` is an alias of it;
 *  - [SideMenu] `vertical_side_menu`: a vertical left side menu, expanded (icon + label) or a semi-collapsed rail (icon + the first
 *    letters of the name). The older `one_row` is an alias of [OneRow].
 */
public enum class SecondRowMode(public val declared: String, private val aliases: List<String> = emptyList()) {
    OneRow("horizontal_one_row", listOf("one_row")),
    ManyRows("horizontal_two_rows", listOf("many_rows")),
    SideMenu("vertical_side_menu");

    public companion object {
        /** A declared `nav_mode`; blank is [OneRow], anything unknown is null (a declaration problem). The old names are accepted. */
        public fun of(text: String?): SecondRowMode? =
            if (text.isNullOrBlank()) OneRow else entries.firstOrNull { text.trim() == it.declared || text.trim() in it.aliases }
    }
}

/**
 * A named group of a section's pages (`ui.sections[].groups[]` = `{id, label, icon, pages:[page ids]}`): one row of the
 * [SecondRowMode.ManyRows] second row. [pages] are the section's own flat pages the ids refer to, in the group's order.
 */
public data class NavGroup(val id: String, val label: String, val pages: List<NavPage>, val icon: String = "")

/** A tab. [pages] is non-empty only for a container tab, which holds a strip of its own. */
public data class NavPage(
    val id: String,
    val label: String,
    /** Required (#1157): a Material symbol name; no nav item is declared without an icon. */
    val icon: String,
    /** Non-blank: a LAUNCH tab (it leaves the page for [action]) rather than a destination. */
    val action: String = "",
    val pages: List<NavPage> = emptyList(),
    /** What this page's own sub-strip is drawn over; null = its parent's. */
    val background: PageBackground? = null,
    /** True: a "|" group separator is drawn on the strip just before this page (a declared group boundary, not a page). */
    val separatorBefore: Boolean = false,
    /** True (`emoji_only`): the strip chip draws NO glyph, only its label (an emoji + text label). Additive and opt-in; the declared [icon] stays (the icon guards still hold it). */
    val emojiOnly: Boolean = false,
) {
    /** The tab that actually renders when this one is selected, walked all the way down. */
    public fun leaf(): NavPage = pages.firstOrNull()?.leaf() ?: this
}

public data class NavSection(
    val id: String,
    val label: String,
    /** Required (#1157): no nav item is declared without an icon. */
    val icon: String,
    val pages: List<NavPage> = emptyList(),
    /** What this section's tab strip is drawn over ([PageBackground.Dark] unless declared). */
    val background: PageBackground = PageBackground.Dark,
    /** `nav_mode`: one strip of chips ([SecondRowMode.OneRow], today's) or one row per group. */
    val rows: SecondRowMode = SecondRowMode.OneRow,
    /** `groups`: the page groups of [SecondRowMode.ManyRows]; they only reference [pages], which stays the one flat list. */
    val groups: List<NavGroup> = emptyList(),
) {
    /**
     * The rows of the many-rows second row: the declared [groups]; pages no group names (the guard rejects that) go in a
     * trailing group named like the section so none is unreachable; with no groups, ONE group holding all [pages].
     */
    public fun rowGroups(): List<NavGroup> {
        val named = groups.flatMap { g -> g.pages.map { it.id } }.toSet()
        val rest = pages.filter { it.id !in named }
        return groups + (if (rest.isEmpty()) emptyList() else listOf(NavGroup(id, label, rest, icon)))
    }

    /**
     * The strip [TabSurface] for this section's top strip ([pageId] null) or for the sub-strip of the
     * container page [pageId]: the nearest declared background on the path, else the section's.
     * [darkTheme] is the app's own light/dark decision, read only for a "theme" background.
     */
    public fun stripSurface(darkTheme: Boolean, pageId: String? = null): TabSurface =
        (path(pageId).lastOrNull { it.background != null }?.background ?: background).surface(darkTheme)

    /** The chain of tabs from the top strip down to [id], outermost first; empty when absent. */
    public fun path(id: String?): List<NavPage> = pathTo(pages, id)

    /** [id] resolved against the top strip AND every strip below it; the first page when [id]
     *  is not declared, null when the section has no pages. */
    public fun page(id: String?): NavPage? = path(id).lastOrNull() ?: pages.firstOrNull()

    /** Every declared page, depth-first, containers included. */
    public fun allPages(): List<NavPage> = flatten(pages)
}

public class NavDecl(
    /** `ui.bottom_nav`, in order, as declared ([bottomSections] draws at most [MAX_BOTTOM]). */
    public val bottomNav: List<String>,
    public val sections: List<NavSection>,
    /** `ui.default_section`; blank resolves to the first bottom-nav id. */
    public val defaultSection: String = "",
    /** `ui.style`: which look the island is drawn in. Fleet for every app but the declared exception. */
    public val style: NavStyle = NavStyle.Fleet,
) {
    private val index: Map<String, NavSection> = sections.associateBy { it.id }

    public fun section(id: String?): NavSection? = if (id == null) null else index[id]

    /** The island's sections, in bottom_nav order, at most [MAX_BOTTOM]; an id with no section is skipped. */
    public fun bottomSections(): List<NavSection> = bottomNav.take(MAX_BOTTOM).mapNotNull { index[it] }

    /** The strip surface for section [sectionId] (see [NavSection.stripSurface]); Dark when it is not declared. */
    public fun stripSurface(sectionId: String?, darkTheme: Boolean, pageId: String? = null): TabSurface =
        section(sectionId)?.stripSurface(darkTheme, pageId) ?: TabSurface.Dark

    /**
     * What is wrong with this declaration, as the nav-shape guard's N1 says it (empty = valid):
     * [MIN_BOTTOM]..[MAX_BOTTOM] bar ids, each a section, and a default that is one of them. Parsing
     * never throws, so this is how a test (or a debug screen) asks whether a declaration would pass.
     */
    public fun problems(): List<String> = buildList {
        if (bottomNav.size < MIN_BOTTOM) add("ui.bottom_nav has ${bottomNav.size} ids, the island needs at least $MIN_BOTTOM")
        if (bottomNav.size > MAX_BOTTOM) add("ui.bottom_nav has ${bottomNav.size} ids, the island holds at most $MAX_BOTTOM")
        bottomNav.filter { it !in index }.forEach { add("ui.bottom_nav id '$it' is not a ui.sections id") }
        if (defaultSection.isNotBlank() && defaultSection !in bottomNav) add("ui.default_section '$defaultSection' is not one of ui.bottom_nav")
    }

    /** Every section and page, at any depth, whose declaration carries no icon (#1157; empty = valid). */
    public fun iconProblems(): List<String> = buildList {
        for (s in sections) {
            if (s.icon.isBlank()) add("section '${s.id}' has no icon")
            for (p in flatten(s.pages)) if (p.icon.isBlank()) add("page '${s.id}/${p.id}' has no icon")
        }
    }

    /** Where the app opens: the declared default, else the first bar section, else the first section. */
    public fun default(): NavSection? =
        index[defaultSection] ?: bottomSections().firstOrNull() ?: sections.firstOrNull()

    /** The island's items for a View host (Android's [BottomNavIslandView]): [icon] maps a declared
     *  icon name to a drawable resource, so the lib carries no app's icon vocabulary. */
    public fun viewItems(icon: (String) -> Int): List<BottomNavViewItem> =
        bottomSections().map { BottomNavViewItem(it.id, it.label, icon(it.icon)) }

    public companion object {
        public val EMPTY: NavDecl = NavDecl(emptyList(), emptyList())

        /**
         * From the three BuildConfig fields. [uiBottomNav] is the JSON array text of ui.bottom_nav
         * (a bare comma list is accepted too); [uiSectionsB64] is base64 of ui.sections.
         */
        @OptIn(ExperimentalEncodingApi::class)
        public fun fromBuildConfig(
            uiSectionsB64: String,
            uiBottomNav: String = "",
            uiDefaultSection: String = "",
            uiStyle: String = "",
        ): NavDecl = runCatching {
            // Android's decoder skipped whitespace and tolerated missing padding; so does this.
            val b64 = Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL)
            val json = b64.decode(uiSectionsB64.filterNot { it.isWhitespace() }).decodeToString()
            parse(json, uiBottomNav, uiDefaultSection, uiStyle)
        }.getOrDefault(EMPTY)

        /**
         * From the sections array. [sections] is its JSON text, or an object whose `toString()` is
         * that text: Android callers keep passing the `org.json.JSONArray` they already hold, and
         * this common code never names the type. [bottomNav] is JSON array text or a comma list.
         * Throws on anything that is not a JSON array; [fromBuildConfig] turns that into [EMPTY].
         */
        public fun parse(sections: Any, bottomNav: String = "", defaultSection: String = "", style: String = ""): NavDecl {
            val array = MiniJson.parse(sections.toString()) as? List<*>
                ?: throw IllegalArgumentException("ui.sections is not a JSON array")
            val parsed = array.mapNotNull { (it as? Map<*, *>)?.let(::section) }
            // Kept as declared, so [problems] can see a bar that is too long; the island draws MAX_BOTTOM.
            val bar = bottomIds(bottomNav).ifEmpty { parsed.map { it.id }.take(MAX_BOTTOM) }
            return NavDecl(bar, parsed, defaultSection, NavStyle.of(style) ?: NavStyle.Fleet)
        }

        private fun bottomIds(text: String): List<String> {
            val t = text.trim()
            if (t.isEmpty()) return emptyList()
            if (t.startsWith("[")) return runCatching {
                (MiniJson.parse(t) as List<*>).mapNotNull {
                    when (it) {
                        is String -> it
                        is Map<*, *> -> (it["id"] as? String)?.ifBlank { null }
                        else -> null
                    }
                }
            }.getOrDefault(emptyList())
            return t.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun Map<*, *>.text(key: String, fallback: String = ""): String = (this[key] as? String) ?: fallback

        private fun section(o: Map<*, *>): NavSection? {
            val id = o.text("id").ifBlank { return null }
            val flat = pages(o["pages"])
            val byId = flat.associateBy { it.id }
            val groups = (o["groups"] as? List<*>)?.mapNotNull { g ->
                val go = g as? Map<*, *> ?: return@mapNotNull null
                val gid = go.text("id").ifBlank { return@mapNotNull null }
                NavGroup(gid, go.text("label", gid), (go["pages"] as? List<*>).orEmpty().mapNotNull { byId[it as? String] }, go.text("icon"))
            }.orEmpty()
            val mode = SecondRowMode.of(o.text("nav_mode")) ?: SecondRowMode.OneRow
            return NavSection(id, o.text("label", id), o.text("icon"), if (mode == SecondRowMode.OneRow) groupedStrip(flat, groups) else flat,
                PageBackground.of(o.text("background")) ?: PageBackground.Dark, mode, groups)
        }

        /**
         * #1854 `groups` on a one-row section: the one strip lists the pages in group order (pages no group names after them) and the
         * first page of every group but the first carries [NavPage.separatorBefore], so a "|" is drawn between groups. No groups: [flat].
         */
        private fun groupedStrip(flat: List<NavPage>, groups: List<NavGroup>): List<NavPage> {
            if (groups.isEmpty()) return flat
            val named = groups.flatMap { g -> g.pages.map { it.id } }.toSet()
            val firsts = groups.mapNotNull { g -> g.pages.firstOrNull()?.id }.drop(1).toSet()
            return (groups.flatMap { it.pages } + flat.filter { it.id !in named }).map { if (it.id in firsts) it.copy(separatorBefore = true) else it }
        }

        private fun pages(a: Any?): List<NavPage> =
            (a as? List<*>)?.mapNotNull { e ->
                val o = e as? Map<*, *> ?: return@mapNotNull null
                val id = o.text("id").ifBlank { return@mapNotNull null }
                NavPage(id, o.text("label", id), o.text("icon"), o.text("action"), pages(o["pages"]), PageBackground.of(o.text("background")), o["separator_before"] == true, o["emoji_only"] == true)
            } ?: emptyList()
    }
}

/** Depth-first: ids are unique per section, so the first hit is the only hit. */
private fun pathTo(pages: List<NavPage>, id: String?): List<NavPage> {
    for (p in pages) {
        if (p.id == id) return listOf(p)
        val below = pathTo(p.pages, id)
        if (below.isNotEmpty()) return listOf(p) + below
    }
    return emptyList()
}

private fun flatten(pages: List<NavPage>): List<NavPage> = pages.flatMap { listOf(it) + flatten(it.pages) }

/** The island's items for a Compose host ([BottomNavIsland]): [icon] maps a declared icon name to
 *  a painter, so the lib carries no app's icon vocabulary. */
@androidx.compose.runtime.Composable
public fun NavDecl.islandEntries(
    icon: @androidx.compose.runtime.Composable (String) -> androidx.compose.ui.graphics.painter.Painter,
): List<BottomNavEntry> = bottomSections().map { BottomNavEntry(it.id, it.label, icon(it.icon)) }

/** One item as a View-based host declares it: a stable id, its label, and a drawable resource id. */
public data class BottomNavViewItem(val id: String, val label: String, val icon: Int)
