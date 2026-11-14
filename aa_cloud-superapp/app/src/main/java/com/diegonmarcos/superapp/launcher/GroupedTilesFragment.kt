package com.diegonmarcos.superapp.launcher
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.apps.AllApps
import com.diegonmarcos.superapp.apps.OpenFolder
import com.diegonmarcos.superapp.apps.PhoneFolderSheet
import com.diegonmarcos.superapp.apps.PhoneRefreshBar
import com.diegonmarcos.superapp.apps.PhoneSectionDivider
import com.diegonmarcos.superapp.apps.PhoneSubhead
import com.diegonmarcos.superapp.apps.allApps
import com.diegonmarcos.superapp.apps.allAppsItems
import com.diegonmarcos.superapp.extinstall.extInstallRing
import com.diegonmarcos.superapp.search.SearchFrame
import com.diegonmarcos.superapp.search.SearchSession
import com.diegonmarcos.superapp.search.SearchEntry
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

import android.content.Context
import android.os.Bundle
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Aggregator section that renders themed sub-groups stacked vertically.
 * Used when [Sections.Section.tileGroups] is non-empty (Suite currently).
 *
 * Layout:
 *   ┌─ Group title ────────────────
 *   │  [tile] [tile] [tile] [tile] [tile]
 *   └──────────────────────────────
 *   ┌─ Next group title ───────────
 *   │  [tile] [tile]
 *   └──────────────────────────────
 *
 * Tile clicks bubble up through the same TileGridFragment.TileClickListener
 * the activity already implements, so deep-link grammar (section: / page: /
 * action: / http(s):) routes identically to the flat aggregator path.
 *
 * Compose since #773: one LazyColumn; each group's tiles are ONE sideways-scrolling LazyRow, so a group of
 * any size stays on a single line. [GroupedTilesPage] is the body, shared with the Home swipe sheet's Cloud tab.
 */
class GroupedTilesFragment : KitComposeFragment(), BackHandler {

    /** Cloud ▸ Apps' search: the SuperApp's one search (search/SearchPanel), inline at the top. */
    private var search: SearchSession? = null

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val sectionId = arguments?.getString(ARG_SECTION_ID).orEmpty()
        val withSearch = arguments?.getBoolean(ARG_SEARCH) == true && sectionId == "cloud"
        val click = { id: String -> (activity as? TileGridFragment.TileClickListener)?.onTileClicked(id); Unit }
        if (!withSearch) { GroupedTilesPage(sectionId, click); return }
        // The bar sits directly under the page's tab icons, the results drop over the grid.
        val session = remember { SearchSession(this, SearchEntry.CLOUD_APPS_PAGE).also { search = it } }
        SearchFrame(session, "Search", Modifier.fillMaxSize()) { GroupedTilesPage(sectionId, click) }
    }

    /** Back with a query clears it (the full grid returns) before Back leaves the page. */
    override fun tryHandleBack(): Boolean = search?.handleBack() == true

    override fun onDestroyView() {
        super.onDestroyView()
        search = null
    }

    companion object {
        private const val ARG_SECTION_ID = "section_id"
        private const val ARG_SEARCH = "search"

        const val TAG_LIST = "groupedtiles:list"
        fun groupTag(title: String) = "groupedtiles:group:$title"
        fun tileTag(target: String) = "groupedtiles:tile:$target"
        const val TAG_RECENT_EMPTY = "groupedtiles:recent-empty"

        /** Where an empty search sends its query: the Cloud Search app (ac_cloud-search). */
        const val CLOUD_SEARCH_TARGET = "extapp:cloud-search"

        /** The Actions group: the SAME list the all-apps star draws on its inner ring
         *  (build.json::onehand.circular_menu.actions), read from that block rather than copied into
         *  tile_groups. Copying is what let the Configs grid and the Configs star disagree about how many
         *  actions exist. Suite/Cloud only: repeating the global actions in Home, Labs and Configs would be
         *  the same five tiles four times over. */
        internal fun starActions(): List<Sections.AggTile> =
            com.diegonmarcos.superapp.onehand.CircularMenu.config().actions
                .map { Sections.AggTile(it.target, it.label, it.iconName, it.target) }

        /** The Recently Used smart folder's tiles: the one real per-tile signal the Cloud tiles have
         *  (RecentCloudTiles, recorded centrally from onTileClicked). */
        internal fun recentTiles(ctx: Context): List<Sections.AggTile> {
            val allTiles = Sections.all().flatMap { it.tileGroups }.flatMap { it.destinations }.associateBy { it.target }
            return RecentCloudTiles.recent(ctx).mapNotNull { allTiles[it] }
        }

        /** [search] puts the SuperApp's search at the top of the page — Cloud ▸ Apps asks for
         *  it; the Home sheet's Cloud tab does not (the sheet carries the same search above its
         *  Cloud | Phone body, for both tabs). */
        fun newInstance(sectionId: String, search: Boolean = false): GroupedTilesFragment = GroupedTilesFragment().apply {
            arguments = Bundle().apply {
                putString(ARG_SECTION_ID, sectionId)
                putBoolean(ARG_SEARCH, search)
            }
        }
    }
}

/** What sits below the fold of Cloud ▸ Apps, computed after the first frame. */
private class BelowFold(val all: AllApps, val recent: List<Sections.AggTile>)

@Composable
internal fun GroupedTilesPage(sectionId: String, click: (String) -> Unit) {
    val ctx = LocalContext.current
    val section = Sections.byId(sectionId)
    val menu = sectionId == "cloud"
    // #1736 a retired app (appstore-retired-apps.json) never appears in the Cloud list, whatever a declaration says.
    val retired = remember(ctx) { if (menu) runCatching { CloudAppMenu.retiredPackages(ctx) }.getOrDefault(emptySet()) else emptySet() }
    val groups = remember(section, retired) {
        val ext = runCatching { Sections.externalApps() }.getOrDefault(emptyList())
        section?.tileGroups.orEmpty().map { it.copy(tiles = CloudAppMenu.dropRetired(it.tiles, ext, retired)) }
    }
    var folder by remember { mutableStateOf<Sections.AggTile?>(null) }
    var apps by remember { mutableStateOf<OpenFolder?>(null) }
    var epoch by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    // ── Below the fold: built AFTER the first frame ──────────────────────────
    // Quickmarks + Actions are what the user opens this page for and it is ~30 tiles; All Apps and Smart
    // Folders follow. Computing them off the main thread keeps the tab from freezing on the previous page.
    val below by produceState<BelowFold?>(null, sectionId, epoch) {
        value = if (sectionId != "cloud") null else withContext(Dispatchers.Default) {
            BelowFold(allApps(ctx, only = Sections.constellationPackages(ctx.packageName)), GroupedTilesFragment.recentTiles(ctx))
        }
    }
    LazyColumn(Modifier.fillMaxSize().testTag(GroupedTilesFragment.TAG_LIST), contentPadding = PaddingValues(TILE_PAGE_PADDING)) {
        // Suite is the only section merging in the generic "All Apps" grid + a "Recently Used" smart folder
        // below its Quickmarks — other users (Home, Labs, Configs, …) keep their plain grouped tiles.
        if (sectionId == "cloud") item(key = "h:quick") { GroupTitle("Quickmarks") }
        groups.forEachIndexed { gi, g ->
            item(key = "g:$gi") {
                Column {
                    GroupTitle(g.title)
                    TileStrip(g.tiles, click, { folder = it }, menu = menu)
                }
            }
        }
        // ── Actions, after the declared groups ──
        if (sectionId == "cloud") {
            val actions = GroupedTilesFragment.starActions()
            if (actions.isNotEmpty()) item(key = "g:actions") {
                Column { GroupTitle("Actions"); TileStrip(actions, click, { folder = it }) }
            }
        }
        val b = below
        if (b != null) {
            // ── All Apps (#563) — the same section Phone ▸ Apps has (#249), mirrored: Phone draws every
            //    installed app EXCEPT the fleet's, this draws ONLY the fleet's, through the one renderer and
            //    the one taxonomy.
            item(key = "div:1") { PhoneSectionDivider() }
            item(key = "h:allapps") { GroupTitle("All Apps") }
            allAppsItems(ctx, b.all) { apps = it }
            // ── Smart Folders → Recently Used: always shown (with an empty-state line) so the section does
            //    not disappear before the user has opened any Cloud tile.
            item(key = "div:2") { PhoneSectionDivider() }
            item(key = "h:smart") { GroupTitle("Smart Folders") }
            item(key = "h:recent") { GroupTitle("Recently Used") }
            if (b.recent.isNotEmpty()) item(key = "recent") { TileStrip(b.recent, click, { folder = it }, menu = menu) }
            else item(key = "recent-empty") {
                Text("Open a few Cloud tiles and they'll show up here", color = LocalKitPalette.current.textSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 4.dp, end = 4.dp, bottom = 8.dp).testTag(GroupedTilesFragment.TAG_RECENT_EMPTY))
            }
            item(key = "refresh") { PhoneRefreshBar { com.diegonmarcos.superapp.apps.PhoneAppsFragment.invalidateCache(); epoch++ } }
        }
    }
    folder?.let { f -> TileFolderSheet(f, onDismiss = { folder = null }, onPick = { child -> click(child.target) }) }
    apps?.let { PhoneFolderSheet(it) { apps = null } }
}

@Composable
internal fun GroupTitle(text: String) {
    Text(text, color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 12.dp, bottom = 4.dp).testTag(GroupedTilesFragment.groupTag(text)))
}

/** The page padding of every tile page (Cloud ▸ Apps and Configs). */
internal val TILE_PAGE_PADDING = 12.dp

/** Width of one tile cell: THE size token of Cloud ▸ Apps and Configs alike (both draw [TileCell] in a cell this wide). */
internal val TILE_CELL_WIDTH = 60.dp

/** One group of tiles: [TileCell]s in [TILE_CELL_WIDTH] cells. [wrap] = false is one horizontally-scrollable line per
 *  group (Cloud ▸ Apps: tiles stay on a single line, the user scrolls right for overflow); [wrap] = true wraps the same
 *  cells onto further lines (Configs, which has no sideways scrolling). One component, so the two cannot drift apart. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun TileStrip(tiles: List<Sections.AggTile>, click: (String) -> Unit, openFolder: (Sections.AggTile) -> Unit, wrap: Boolean = false, menu: Boolean = false) {
    // A separator is no item: it rides on the right edge of the tile before it (zero width, so every tile keeps its slot).
    val shown = tiles.withIndex().filter { !it.value.separator }
    val cell: @Composable (Int, Sections.AggTile) -> Unit = { i, tile ->
        Box(Modifier.width(TILE_CELL_WIDTH)) {
            TileCell(tile, click, openFolder, menu)
            tiles.getOrNull(i + 1)?.takeIf { it.separator }?.let {
                TileDivider(tileSepTag(it.id), TILE_CELL_ICON_CENTRE, 0.5.dp, Modifier.align(Alignment.TopEnd), double = it.double)
            }
        }
    }
    if (wrap) androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth()) { for ((i, t) in shown) cell(i, t) }
    else LazyRow(Modifier.fillMaxWidth()) { items(shown) { (i, tile) -> cell(i, tile) } }
}

/** Distance from a strip tile's top to its icon's centre: 6 dp padding + half the 32 dp glyph. */
private val TILE_CELL_ICON_CENTRE = 22.dp

/** A tile in a strip. A `separator` is never passed here: [TileStrip] draws it as a divider on the previous tile's edge. */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
internal fun TileCell(tile: Sections.AggTile, click: (String) -> Unit, openFolder: (Sections.AggTile) -> Unit, menu: Boolean = false) {
    val ctx = LocalContext.current
    // #1736/#1739 Cloud ▸ Apps: a long-press on a fleet app opens its dropdown (Actions / Configs / Urls); "Open" there is the old tap.
    var menuItems by remember { mutableStateOf<List<CloudAppMenu.Item>?>(null) }
    val view = LocalView.current
    val p = LocalKitPalette.current
    // #571: an extapp tile whose app is not on the phone draws the #249 placeholder Phone ▸ Apps uses — same
    // glyph, same strings — instead of an icon that promises an app. The tap is unchanged: it still reaches
    // launchExternalApp, which installs from the Store's asset.
    val missing = remember(tile.target) { Sections.extappMissing(ctx, tile.target) }
    val iconRes = if (missing) R.drawable.ic_app_not_installed else tile.iconRes.takeIf { it != 0 } ?: Sections.iconResFor(ctx, tile.iconName)
    val soon = tile.comingSoon
    val hint = if (missing) stringResource(R.string.phone_app_not_installed_tile_hint, tile.label) else null
    Column(
        // Fixed-width cells so the sideways row shows ~6 tiles at a time on a typical phone width.
        Modifier.width(TILE_CELL_WIDTH)
            .combinedClickable(
                enabled = !soon,
                // #1739: the dropdown is on TAP AND HOLD; a plain tap launches the app as before #1736.
                onLongClick = if (menu && tile.children.isEmpty()) ({
                    val items = runCatching { CloudAppMenu.forTarget(ctx, tile.target) }.getOrNull()
                    if (CloudAppMenu.opensMenu(longPress = true, hasMenu = items != null)) {
                        view.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                        menuItems = items
                    }
                }) else null,
            ) {
                // A folder HOLDS destinations instead of being one, so it opens the popup and the entries inside
                // dispatch. A folder opens a POPUP, which is not a tile click and reaches no dispatcher, so it buzzes
                // for itself. A destination does not: onTileClicked IS the per-tap bookkeeping (log, haptic, drawer
                // close, Recently-Used, App-Tabs) and #195 put it in one place so one tap could not pay for it twice.
                if (tile.children.isNotEmpty()) { Haptics.tap(view); openFolder(tile) }
                else click(tile.target)
            }
            .extInstallRing(tile.target, 32.dp, iconTop = 6.dp)
            .padding(6.dp)
            .alpha(if (soon) 0.4f else 1f)
            .semantics { if (hint != null) contentDescription = hint; if (soon) disabled() }
            .testTag(GroupedTilesFragment.tileTag(tile.target)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (iconRes != 0) {
            // A tile that declares tint:false keeps its glyph's own colours; the placeholder is always tinted.
            Icon(painterResource(iconRes), contentDescription = null,
                tint = if (missing) p.textSecondary else if (tile.tint) p.textPrimary else androidx.compose.ui.graphics.Color.Unspecified,
                modifier = Modifier.size(32.dp).testTag(tileIconTag(tile.target)))
        }
        Text(tile.label, color = if (missing) p.textSecondary else p.textPrimary.copy(alpha = 0.8f), maxLines = 2,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, fontSize = 12.sp,
            modifier = Modifier.padding(top = 4.dp))
        if (missing) {
            Text(stringResource(R.string.phone_app_not_installed), color = p.accent, fontSize = 9.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
        }
        menuItems?.let { items ->
            CloudAppMenu.Menu(items, onDismiss = { menuItems = null }, onPick = { item ->
                menuItems = null
                CloudAppMenu.run(ctx, item, click)
            })
        }
    }
}
