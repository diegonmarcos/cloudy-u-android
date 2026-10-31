package com.diegonmarcos.superapp.launcher

import android.content.Context
import android.os.Bundle
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.mail.MailPages
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemePrefs
import com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment
import com.diegonmarcos.superapp.launcher.themes.minimalist.MinimalistBlackFragment

/**
 * Owns launcher navigation POLICY + state, separated from the Activity's view
 * MECHANISM. The Activity implements [NavHost] (fragment swaps, tab/bottom-nav
 * sync, chrome — operations that genuinely belong to the view layer) and this
 * controller orchestrates them: which fragment a section shows, the
 * horizontal-swipe walk-list, section-page routing, and the walk cursor.
 *
 * State here:
 *   • [walkIndex] / [inWalkNav] — the swipe walk-list cursor + its re-entrancy
 *     guard. currentSection/currentLabel stay on the host (their setter has a
 *     view side-effect — the Sirius star) and are read/written via [NavHost].
 */
class LauncherNavController(private val host: NavHost) {

    /** The two fallback headings, owned by [Sections] so the grid and the
     *  menus ([Sections.headingOf]) cannot name them differently. */
    private val GROUP_PAGES = Sections.GROUP_PAGES
    private val GROUP_ACTIONS = Sections.GROUP_ACTIONS

    /** Cursor into [Sections.swipeWalk]; authoritative for swipe stepping,
     *  re-synced when the user navigates by other means (tail of [goSection]). */
    var walkIndex: Int = 0
    /** Guards the walk re-sync from firing while a walk step drives goSection. */
    var inWalkNav: Boolean = false

    /** Which tab each strip is currently sitting on. A [SectionTabsFragment]
     *  is destroyed and rebuilt every time it is (re-)entered, and its own
     *  selection dies with it — so the strip can only be restored to the tab
     *  the user left if that tab is remembered OUT here, on the controller,
     *  which outlives the fragment. Written by the strip on every selection,
     *  read wherever a strip is built.
     *
     *  The key is a section id for a section strip and
     *  [SectionTabsFragment.pageTabKey] for a page strip, so Configs ▸
     *  Launcher's tab cannot be mistaken for the Configs SECTION's. */
    private val activeTabBySection = mutableMapOf<String, String>()

    /** Record the tab a strip is now showing. Called by [SectionTabsFragment]
     *  for the landing tab and for every user selection after it, and by
     *  [openSectionPage] when a deep link names a tab rather than a page. */
    fun recordActiveTab(tabKey: String, pageId: String) {
        activeTabBySection[tabKey] = pageId
    }

    /** The tab [tabKey] was last left on, or "" if it has not been visited.
     *  Blank is the pre-existing behaviour (strip falls to [startIndex]), so an
     *  unvisited section is unaffected. */
    fun activeTabFor(tabKey: String): String = activeTabBySection[tabKey].orEmpty()

    fun goHome() {
        val ctx = host.navContext()
        host.currentSection = "home"
        host.currentLabel = ctx.getString(R.string.section_home)
        host.setSectionTitle(host.currentLabel)
        // Each theme that declares a home pane of its own gets it; everything
        // else gets the 3D cube.
        val themePrefs = LauncherThemePrefs(ctx)
        // The default-launcher question used to be asked FIRST, and it answered for
        // every theme. On a phone whose home button belongs to One UI Home — which
        // is this phone — that meant picking a mode repainted the palette and then
        // handed back the 3D cube anyway. Minimalist Black is a mode of THIS app,
        // not a launcher replacement, so the theme decides; being the default
        // launcher only ever mattered to the plain Cloud theme.
        val homePane: Fragment = when (themePrefs.theme) {
            LauncherTheme.CloudMinimalistBlack -> MinimalistBlackFragment.newInstance()
            else                               -> Home3DFragment.newInstance()
        }
        host.swapContent(homePane, clearBackStack = true)
        host.syncBottomNav("home")
        host.syncDrawerTab(0)
        host.invalidateMenu()
        host.applyLauncherChrome()
    }

    /** [initialPage] — land on this page of [id] instead of stopping at the
     *  section's page grid. Used by walk stops and `page:<section>/<page>`
     *  deep links. Blank ⇒ the grid (phones), or the first real page seeded
     *  into the detail pane (tablets). */
    fun goSection(id: String, label: String, initialPage: String = "") {
        if (id == "home") { goHome(); return }
        host.currentSection = id
        host.currentLabel = label
        host.setSectionTitle(label)
        // App Tabs LRU — skip the apptabs section itself.
        if (id != "apptabs") runCatching {
            host.recordSection(id, label, Sections.byId(id)?.iconName ?: "")
        }

        val section = Sections.byId(id)
        // Aggregators used to fork here into two bespoke tab hosts (Cloud|Phone,
        // Apps|Admin). Their children are declared `pages` now, so the single
        // data-driven [SectionTabsFragment] covers every tabbed section — see
        // [isTabbed] for which ones those are.
        val content: Fragment = when {
            section == null -> SectionFragment.forSection(id, label)
            // Single-page section (e.g. wg) — the section IS that page: open
            // it directly instead of rendering a pointless 1-tile grid. Opt-in
            // via build.json single_page; action pages still need the grid tap.
            section.singlePage && section.pages.size == 1 && section.pages.first().action.isBlank() -> {
                val pg = section.pages.first()
                if (id != "apptabs") runCatching {
                    host.recordPage(id, pg.id, pg.label, pg.iconName ?: "")
                }
                (SectionPages.pagesFor(id, includeHidden = true).firstOrNull { it.id == pg.id }?.factory?.invoke())
                    ?: SectionFragment.forSection(id, pg.id)
            }
            // Tablet, more pages than we have panes for (Configs' 12, Mail's
            // 9, Drive's …). The section's own page list becomes the LEFT
            // rail and the detail column renders whatever the user taps —
            // that is exactly the drawer's section menu, so reuse it rather
            // than growing a second list widget. Phones keep the tile grid.
            // ponytail: reuses SectionMenuFragment as-is; its taps already
            // route through onDrawerPageSelected -> openSectionPage, which
            // targets the detail pane on two-pane. The only thing the grid
            // shows that the rail does not is the radial-menu extras.
            //
            // COUNT WHAT WILL ACTUALLY CLAIM A PANE, which is not the same as
            // counting pages. This branch exists because past MAX_PANES there
            // are no stable pane host ids left to hand out — so the quantity it
            // must weigh is the number of panes the strip would ask for, and
            // [SectionTabsFragment] decides that with `pages.filter {
            // it.action.isBlank() }`: a page carrying an `action` is a LAUNCH
            // tab that fires `extapp:` and leaves the app, so it wears a pill
            // and never claims a pane.
            //
            // Counting `pages` instead made a launch tab cost a pane it does
            // not use. C3 is where that became reachable: it has two content
            // pages and, since WatchTower joined Watchdog and Morpheus, three
            // launch tabs. Five visible pages tripped `5 > 4` and a TABLET
            // silently got the rail instead of the strip — the whole C3 surface
            // restructured by adding a tab that renders nothing. Phones were
            // unaffected, which is exactly how this would have reached him
            // undetected.
            host.isTwoPane() &&
                section.pages.count { it.action.isBlank() } > SectionTabsFragment.MAX_PANES ->
                SectionMenuFragment.newInstance(id)
            // Tabbed section — the strip over one pane per page on a tablet,
            // over a single swapping pane on a phone. The branch above already
            // took anything a tablet has too many pages to pane, so page count
            // no longer gates this: a phone keeps its strip at any size.
            isTabbed(section) -> SectionTabsFragment.newInstance(id, initialPage)
            section.pages.isNotEmpty() -> sectionGrid(section, label)
            section.defaultChildren.isNotEmpty() -> TileGridFragment.newInstance(
                title = label,
                tiles = section.defaultChildren.mapIndexed { i, lbl ->
                    TileGridFragment.Tile(id = "stub:$id:$i", label = lbl, iconRes = 0) })
            else -> SectionFragment.forSection(id, label)
        }
        host.swapContent(content, clearBackStack = true)
        host.syncBottomNav(id)
        host.syncDrawerTab(1)
        host.invalidateMenu()

        // Keep the swipe walk cursor in sync when the user arrives by ANY means
        // other than a swipe. Skipped during walk nav (which sets it itself).
        if (!inWalkNav) {
            val stops = Sections.swipeWalk()
            val idx = stops.indexOfFirst {
                it.section == id &&
                    (it.page == null || it.page == initialPage)
            }
            if (idx >= 0) walkIndex = idx
        }

        // A tabbed section is already showing every page it has — the strip
        // owns [initialPage] (it selects that tab) and there is no detail pane
        // to seed, so nothing more to do here.
        if (content is SectionTabsFragment) return

        // Land on a specific page when asked. On tablets, fall back to the
        // section's first real page so the 60% detail pane opens with content
        // instead of the "Select an item" placeholder — the master keeps
        // showing the page grid, which is the whole point of the split.
        val landing = initialPage.ifBlank {
            if (host.isTwoPane())
                section?.pages?.firstOrNull { it.action.isBlank() }?.id.orEmpty()
            else ""
        }
        if (landing.isNotBlank() && section?.pages?.any { it.id == landing } == true) {
            openSectionPage(id, landing)
        }
    }

    /**
     * True when [section] renders as ONE tab strip over its pages
     * ([SectionTabsFragment]) — one pane per page on a tablet — rather than a
     * grid of page icons. Declared per section via build.json `tabs`, so no
     * section id is named here.
     *
     * One guard on top of the flag: TWO pages that can actually fill a pane.
     * An action page dispatches a target instead of producing a fragment, so
     * it cannot be counted towards that — but it no longer DISQUALIFIES the
     * section either. It used to: C3's Watchdog and Morpheus launch tabs would
     * have silently demoted the whole strip back to a page grid. The strip
     * gives such a page a tab and no pane (see [SectionTabsFragment]), which is
     * the behaviour that was missing, not a reason to bail out.
     *
     * Past [SectionTabsFragment.MAX_PANES] there are no stable pane host ids
     * left — such a section falls through to the page grid, which on a tablet
     * already IS page icons on the left with the one you pick rendered on the
     * right (configs' 12 pages, mail's 9, tools' 8).
     */
    private fun isTabbed(section: Sections.Section): Boolean =
        section.tabs && section.pages.count { it.action.isBlank() } >= 2

    fun openSectionPage(sectionId: String, pageId: String, args: Bundle? = null) {
        // An old page id (build.json page_aliases, e.g. config/about -> config/xray) reopens as its current page, whoever
        // asked: a page: tile already resolved it, App-Tabs history and launcher shortcuts arrive here directly.
        Sections.canonicalPage(sectionId, pageId).let { (s, p) -> if (s != sectionId || p != pageId) { openSectionPage(s, p, args); return } }
        // #894 Cloud Store installed: a Store page opens Cloud Store itself (placeholder only if it will not start).
        if (com.diegonmarcos.superapp.apps.CloudStoreHandoff.intercept(host.navContext(), sectionId, pageId)) return
        // A page MERGED INTO another page's tab strip keeps its own id as a
        // navigation target. Configs ▸ One-Hand is Launcher's second tab now,
        // and `page:config/onehand` is spoken by more places than could be
        // rewritten safely — launcher shortcuts, edge gestures, the radial
        // menus, and the App-Tabs history already stored on the device — so
        // the id resolves to "the owner page, on that tab" instead of dying
        // with the standalone entry. Recorded BEFORE the open because the
        // strip reads its landing tab off the controller as it builds.
        Sections.tabOwnerOf(sectionId, pageId)?.let { owner ->
            recordActiveTab(SectionTabsFragment.pageTabKey(sectionId, owner.id), pageId)
            openSectionPage(sectionId, owner.id, args)
            return
        }
        // Does this open name one of a TABBED section's own tabs? Asked HERE,
        // before the base is established, because the answer decides which tab
        // the base should land on. It used to be asked further down, after the
        // base had already been built on a different tab — see [tabbedOwner]'s
        // second use for what that cost.
        val tabbedOwner = Sections.byId(sectionId)
            ?.takeIf { sec -> isTabbed(sec) && sec.pages.any { it.id == pageId } }
        // Establish the section grid as the back-stack BASE *first*, so Back from
        // this child returns to its parent section (e.g. Configs), not wherever it
        // was launched from (Home, the Home-Apps sheet, the Canopus arc menu).
        // This MUST run before the action dispatch below: an action-page (e.g.
        // Configs ▸ Constellation → action:constellation) otherwise rendered
        // straight over Home — "in the home-screen" — because the early return
        // skipped the base, leaving currentSection="home" so Back went Home and
        // the page never landed in its own section. No-op when already in the
        // section — preserves any existing in-section back stack.
        //
        // Whether it ran is remembered, because the tabbed branch further down
        // has to know: a base that ran has already put the strip on the right
        // tab, and rebuilding it would be the second of the two builds this
        // function used to pay for.
        var baseWasEstablished = false
        if (host.currentSection != sectionId) {
            baseWasEstablished = true
            // Land the base on the tab the section was left on. Without this the
            // rebuilt strip falls to [SectionTabsFragment.startIndex]'s default —
            // tab 0 — so Back out of a page opened from tab N returned to tab 0,
            // not to the tab the page was opened from.
            //
            // …unless THIS open names a tab, in which case land on that one and
            // be done. Both used to run on a first open of a tab from outside
            // its section: the base built the whole strip on the remembered tab
            // and the branch below immediately tore it down and built it again
            // on the requested one, so one tap paid for two full section builds
            // and showed the wrong tab in between. Arriving on the right tab is
            // the same end state for half the work.
            goSection(sectionId, Sections.byId(sectionId)?.label ?: sectionId,
                if (tabbedOwner != null) pageId else activeTabFor(sectionId))
        }
        // Pages that declare an `action` dispatch it instead of opening a fragment.
        //
        // [NavHost.routeTarget], not [NavHost.dispatchTarget]: this is the shell
        // FINISHING a navigation the user already clicked once, not a new click.
        // A screen-opening action page is reached by arriving here FROM its own
        // target — `action:constellation` routes to dispatchHomeAction, which
        // sends it here to establish the section base, and the line below sends
        // that same target back to complete it. Through dispatchTarget (which is
        // the tile-click entry point) the round trip billed one tap as two:
        // two `onTileClicked` log lines 7–49ms apart, two haptic pulses, and two
        // Recently-Used and App-Tabs recordings. routeTarget performs the target
        // without re-counting the tap.
        val pageAction = Sections.byId(sectionId)?.pages
            ?.firstOrNull { it.id == pageId }?.action.orEmpty()
        if (pageAction.isNotBlank()) { host.routeTarget(pageAction); return }
        if (sectionId != "apptabs") runCatching {
            val pageEntry = Sections.byId(sectionId)?.pages?.firstOrNull { it.id == pageId }
            host.recordPage(sectionId, pageId, pageEntry?.label ?: pageId, pageEntry?.iconName ?: "")
        }
        // Tabbed section: every page is already on screen (tablet) or one tap
        // away on the strip (phone), so "open page X" means SELECT it, not
        // push a second copy over the top.
        //
        // Only when X IS one of the tabs. A hidden page is routable but has no
        // tab, and handing it to goSection asked the strip to select something
        // it does not list — [SectionTabsFragment.startIndex] answers -1 and
        // falls back to tab 0, so the target silently became the section's
        // FIRST page. That is what "page:c3/health opens Observability" and
        // "the C3 tiles do not open" were: not a routing failure, a tab
        // selection that quietly succeeded on the wrong page.
        if (tabbedOwner != null) {
            // Only an open from INSIDE the section still has to move the strip:
            // when the base above ran, it already built it on this very tab.
            if (!baseWasEstablished) goSection(sectionId, tabbedOwner.label, pageId)
            host.closeDrawerIfOpen()
            return
        }
        syncModeForPage(pageId)
        val frag = pageFragment(sectionId, pageId, args)
        host.closeDrawerIfOpen()
        // On tablets the page opens in the side-by-side DETAIL pane; the MASTER
        // (section grid) keeps owning the shell chrome, so a ShellOverride page
        // must NOT take it over there. Single-pane phones apply chrome as usual.
        if (!host.isTwoPane()) host.applyChrome(frag)
        // Tag by logical page, not instance: re-opening the same page (e.g.
        // tapping Configs ▸ AI again from the drawer without pressing Back)
        // must replace its existing back-stack slot rather than stack a
        // second live copy — see [NavHost.pushContent].
        host.pushContent(frag, "page:$sectionId/$pageId")
    }

    /**
     * The page→Fragment routing, shared by [openSectionPage] (which pushes one
     * onto the back stack / into the detail pane) and [SectionTabsFragment]
     * (which commits one into each pane). Deliberately PURE — no chrome, no
     * mode side-effects — so a caller rendering N pages at once doesn't fire
     * them N times. Callers that open a single page pair it with
     * [syncModeForPage].
     */
    fun pageFragment(sectionId: String, pageId: String, args: Bundle? = null): Fragment {
        val section = Sections.byId(sectionId)
        // allPages, not pages: `page:<section>/<id>` resolves against every
        // DECLARED page — that is [Sections.Section.allPages]'s whole contract.
        // Looking in the visible list only worked while no hidden page was a
        // facet; C3's Observability and Topology are both, and both would have
        // fallen through to the generic placeholder instead of rendering their
        // stack_<id> data.
        val page = section?.allPages?.firstOrNull { it.id == pageId }
        return when {
            sectionId == "mail" -> MailPages.fragmentFor(pageId, args)
            section != null && page != null && page.facet -> aggregatorPage(section, page)
            else -> SectionPages.pagesFor(sectionId, includeHidden = true).firstOrNull { it.id == pageId }
                ?.factory?.invoke() ?: SectionFragment.forSection(sectionId, pageId)
        }
    }

    /**
     * A page that DECLARES a ModePrefs mode also SETS it, so the Home grid,
     * drawer and bottom-nav icon variants follow the page the user just landed
     * on — what the retired `mode:` target used to do. Separate from
     * [pageFragment] so the tab strip can fire it on SELECTION only: with every
     * pane rendered at once, doing it at render time would fire once per pane
     * and the last one would win.
     *
     * Reads `build.json::pages[].mode` rather than matching the page id against
     * "apps"/"admin". The old id test made the mode a hostage of the naming:
     * Cloud's C3 page had to keep the id `admin` purely so this line would fire.
     */
    fun syncModeForPage(pageId: String) {
        Sections.modeForPageId(pageId)?.let { host.applyMode(it) }
    }

    /**
     * A section's own page grid — Pages and Actions as two labelled groups,
     * off the same `is_action` flag the bottom star splits its two arcs by,
     * with the extras declared on that section's radial node (KDE Connect,
     * Animations) merged in so the grid and the star list the same
     * actions from one declaration.
     *
     * Pulled out of [goSection] because Cloud ▸ Configs has to render THE
     * Configs grid, not a second list of the same pages maintained beside it.
     */
    private fun sectionGrid(section: Sections.Section, title: String): Fragment {
        val ctx = host.navContext()
        val own = section.pages.map { p ->
            TileGridFragment.Tile(
                // FULLY QUALIFIED on purpose. The bare "page:<id>" form this
                // used to emit resolves against MainActivity.currentSection,
                // which is only the right section while you are standing in
                // it — mirrored into the Cloud tab, every tile would have
                // opened page:cloud/<id> and found nothing.
                id = if (p.separator) "${TileGridFragment.SEPARATOR_PREFIX}${p.id}"
                     else if (p.action.isNotBlank()) p.action else "page:${section.id}/${p.id}",
                label = p.label,
                iconRes = p.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                // The heading comes from the PAGE (#649) — `group`/`subgroup` in
                // build.json — so a section can declare two levels (Configs:
                // Launcher; Setup ▸ Apps / Observability) without a word of
                // it living here. A page that declares none falls back to the
                // one default heading, which is every other section.
                group = if (p.isAction) GROUP_ACTIONS else p.group.ifBlank { GROUP_PAGES },
                subgroup = if (p.isAction) "" else p.subgroup,
                rowBreak = p.rowBreak)
        }
        val actions = own.filter { it.group == GROUP_ACTIONS } + starActionsOf(section)
        // NOT `group == GROUP_PAGES`: a page under a DECLARED heading is still a
        // page, and testing for the fallback name dropped every one of them off
        // the grid the moment #649 gave Configs its own headings.
        val pages = own.filterNot { it.group == GROUP_ACTIONS }
        // No actions AND no declared headings? Drop the headings entirely — a lone "PAGES" banner over every other grid
        // is noise. A section that DID declare its own headings keeps them either way.
        val tiles = if (actions.isEmpty() && pages.all { it.group == GROUP_PAGES && it.subgroup.isEmpty() })
                        pages.map { it.copy(group = "") }
                    else pages + actions
        // Configs wears the same page as Cloud ▸ Apps (#1075): one tile component, one set of size tokens.
        if (section.id == "config") return ConfigsFragment.newInstance(tiles)
        return TileGridFragment.newInstance(title = title, tiles = tiles, columns = section.gridColumns)
    }

    /** The extras declared on a section's radial node (KDE Connect,
     *  Animations), so the grid and the star list the same actions
     *  from one declaration in build.json. */
    private fun starActionsOf(section: Sections.Section): List<TileGridFragment.Tile> {
        val ctx = host.navContext()
        return com.diegonmarcos.superapp.onehand.CircularMenu.config().nodes
            .firstOrNull { it.childKey == section.id }?.actions.orEmpty()
            .map { TileGridFragment.Tile(it.target, it.label, Sections.iconResFor(ctx, it.iconName), GROUP_ACTIONS) }
    }

    /** A section's Actions as tiles — its `is_action` pages plus its star
     *  extras. Borrowed by a facet that declares `actions_from_section`, so
     *  the actions are declared once and rendered wherever they are wanted. */
    private fun actionTilesOf(section: Sections.Section): List<TileGridFragment.Tile> {
        val ctx = host.navContext()
        return section.pages.filter { it.isAction }.map { p ->
            TileGridFragment.Tile(
                id = if (p.action.isNotBlank()) p.action else "page:${section.id}/${p.id}",
                label = p.label,
                iconRes = p.iconName?.let { Sections.iconResFor(ctx, it) } ?: 0,
                group = GROUP_ACTIONS)
        } + starActionsOf(section)
    }

    /**
     * Render a FACET page (build.json `"facet": true`) — a child of an
     * aggregator section that shows the SECTION's own tile/stack data rather
     * than a [SectionPages] factory. The facet id is the `tiles_<id>` /
     * `stack_<id>` suffix, so which renderer wins is decided purely by which
     * lists build.json declares. Was the `mode`/`tab` fork in [goSection]
     * before pages absorbed both.
     */
    private fun aggregatorPage(section: Sections.Section, page: Sections.Page): Fragment {
        val title = "${section.label} · ${page.label}"
        val ownTiles = section.tilesByPage[page.id].orEmpty()
        val mirrored = page.mirrorSection.takeIf { it.isNotBlank() }?.let { Sections.byId(it) }
        // `mirror_page` — this facet IS that ONE page of another section.
        // Configs ▸ Panel ▸ Notify is the case: the tab renders THE ntfy page,
        // by asking for the very fragment `page:communication/my-rss` opens,
        // so there is no second copy of that screen to drift from it.
        //
        // Self-reference is dropped rather than followed: a page mirroring
        // itself would re-enter this function forever, and a tab that renders
        // nothing is a visibly empty pane the owner can report — a stack
        // overflow on arrival is not.
        val mirroredPage = page.mirrorPage
            .split('/', limit = 2)
            .takeIf { it.size == 2 && it.none(String::isBlank) }
            ?.let { (sec, pid) -> (sec to pid).takeIf { sec != section.id || pid != page.id } }
        return when {
            mirroredPage != null -> pageFragment(mirroredPage.first, mirroredPage.second)
            // `mirror_section` — this facet IS that section. Cloud ▸ Configs
            // shows THE Configs page, Actions and all, instead of a second
            // copy of the same page list drifting beside it.
            //
            // Mirroring shows the section the way the section shows ITSELF, so
            // a TABBED one arrives as its tab strip rather than as a grid of
            // its tabs. Cloud ▸ C3 is that case: C3 is two tabs, Observability
            // and Topology, and rendering it as two icons you have to tap
            // through is not a mirror of it.
            mirrored != null && isTabbed(mirrored) ->
                SectionTabsFragment.newInstance(mirrored.id, activeTabFor(mirrored.id))
            mirrored != null -> sectionGrid(mirrored, page.label)
            // What the PAGE declares, in order — stack_<id>, then tiles_<id>.
            // Only a page that declares neither falls back to the section-wide
            // tile_groups; checking tile_groups first meant a section that had
            // any (Cloud) served the same grouped grid to every page.
            Sections.aggregatorIsStack(section, page.id) ->
                AggregatorStackFragment.newInstance(section.id, section.label, page.id)
            ownTiles.isNotEmpty() -> {
                val ctx = host.navContext()
                val actions = page.actionsFromSection.takeIf { it.isNotBlank() }
                    ?.let { Sections.byId(it) }?.let { actionTilesOf(it) }.orEmpty()
                val pages = ownTiles.map { t ->
                    TileGridFragment.Tile(
                        id = t.target, label = t.label,
                        iconRes = Sections.iconResFor(ctx, t.iconName),
                        // Headed only when there is a second group to tell it
                        // apart from — same rule as sectionGrid, where a lone
                        // "PAGES" banner over every other grid is noise.
                        group = if (actions.isEmpty()) "" else GROUP_PAGES)
                }
                TileGridFragment.newInstance(title, pages + actions)
            }
            // Cloud ▸ Apps pins its search bar under the grid (the fragment draws it for
            // the cloud section's apps page only; other pages keep the plain grid).
            section.tileGroups.isNotEmpty() ->
                GroupedTilesFragment.newInstance(section.id, search = page.id == "apps")
            else -> {
                val ctx = host.navContext()
                TileGridFragment.newInstance(title, Sections.aggregatorTilesFor(section, page.id)
                    .map { t ->
                        TileGridFragment.Tile(
                            id = t.target, label = t.label,
                            iconRes = Sections.iconResFor(ctx, t.iconName))
                    })
            }
        }
    }

    // ── horizontal-swipe walk-list (build.json::ui.swipe_walk) ───────────
    /** Step the circular walk-list, wrapping. +1 = next (left-swipe). */
    fun walkStep(direction: Int) {
        val stops = Sections.swipeWalk()
        if (stops.isEmpty()) return
        val n = stops.size
        walkIndex = ((walkIndex + direction) % n + n) % n
        navigateWalkStop(stops[walkIndex])
    }

    /** Render one walk stop: a section page. */
    fun navigateWalkStop(stop: Sections.WalkStop) {
        inWalkNav = true
        try {
            val label = Sections.byId(stop.section)?.label ?: stop.section
            goSection(stop.section, label, stop.page.orEmpty())
        } finally {
            inWalkNav = false
        }
        host.tabHaptic()
    }

    /**
     * The view-mechanism surface the controller drives. Implemented by the
     * Activity (where the views, FragmentManager, drawer and chrome live).
     */
    interface NavHost {
        var currentSection: String
        var currentLabel: String
        fun navContext(): Context
        fun isDefaultLauncher(): Boolean
        /** True on tablets (sw600dp) where opened pages render in a side-by-side
         *  DETAIL pane and the section grid (master) stays visible. */
        fun isTwoPane(): Boolean
        fun setSectionTitle(label: String)
        fun swapContent(content: Fragment, clearBackStack: Boolean)
        /** [tag] identifies the LOGICAL page (e.g. "page:configs/ai"), not the
         *  Fragment instance. Passing it lets the host collapse a re-open of
         *  the same page into its existing back-stack slot instead of stacking
         *  a duplicate — see [pushContent]'s implementation for why that
         *  matters: every re-open otherwise leaves a live, un-destroyed
         *  Fragment behind, and pages that call registerForActivityResult
         *  (AiFragment, WireGuardFragment, ProfileFragment, …) leak one launcher
         *  registration per stale copy into the Activity's saved-state Bundle. */
        fun pushContent(content: Fragment, tag: String? = null)
        fun applyChrome(fragment: Fragment)
        fun applyLauncherChrome()
        fun syncBottomNav(sectionId: String)
        fun syncDrawerTab(index: Int)
        fun invalidateMenu()
        fun closeDrawerIfOpen()
        /** A USER CLICKED this target: felt, logged and counted as an open,
         *  then routed. For a target the shell is dispatching to itself, use
         *  [routeTarget] — see [openSectionPage]'s page-action hop. */
        fun dispatchTarget(target: String)
        /** Perform [target] under the tile grammar WITHOUT counting a click.
         *  The shell re-enters its own router while completing one tap, and
         *  every such re-entry through [dispatchTarget] was a second tap. */
        fun routeTarget(target: String)
        fun applyMode(mode: String)
        fun tabHaptic()
        fun recordSection(id: String, label: String, icon: String)
        fun recordPage(sectionId: String, pageId: String, label: String, icon: String)
        fun recordTarget(target: String, label: String, icon: String)
    }
}
