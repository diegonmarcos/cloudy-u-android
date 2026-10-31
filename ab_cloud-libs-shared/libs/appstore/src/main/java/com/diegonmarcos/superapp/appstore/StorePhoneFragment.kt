package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.adbdebug.ShellChannels
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.cache.ApkCache
import kotlin.concurrent.thread

/**
 * Store ▸ Phone Apps (#563, #564, #565, #571) — every app that BELONGS on this
 * phone, grouped by the host's central classification exactly as
 * [StoreCloudFragment] groups the fleet: what is installed, PLUS every fleet
 * app the constellation manifest declares, PLUS every external app the
 * install-source map declares. On a fresh phone the list is therefore full,
 * with every row saying "not installed" and how it installs.
 *
 * Each row carries a state line — installed version, update available, not
 * installed, or the 'needs Play' badge — and its buttons: [PhoneAppActions.of]
 * for an installed app (Update, Open, Stop, Remove, App info, origin) and
 * [PhoneAppActions.forMissing] for one that is not (Install, origin). Install
 * and Update route through the ONE path per kind: fleet → [FleetInstall],
 * external → [ExternalInstall] over its declared ladder, Play-only → the Play
 * page, never a download.
 *
 * Above the rows: the Cloud tab's own top bar ([StoreBar]). Check all probes
 * every row (fleet: [Fleet.status]; external: [SourceResolver.check]); Install
 * all and Update all walk the rows that can be served without any other store,
 * and say how many were skipped because they need Play. Then Export / Import
 * of the app inventory ([AppInventory]); an import shows its plan
 * ([StoreImport]) before anything acts.
 *
 * Compose: the rows, the state each shows ([states]), the filters and the sort are state this page
 * holds, so a check landing or a chip tapped only writes its value and the list redraws itself.
 */
class StorePhoneFragment : KitComposeFragment() {

    private class AppRow(val pkg: String, val label: String, val shelf: AppStoreHost.Shelf?,
                         val fleetApp: Fleet.App?, val external: SourceResolver.External?,
                         val installed: Boolean, val actions: List<PhoneAppActions.Action>) {
        /** This store can put it on the phone by itself. */
        val direct: Boolean get() = (fleetApp != null && !fleetApp.blocked) || (external?.needsPlay == false)
    }

    /** What one enumeration found: the rows, their resolved state and what the APK cache holds. */
    private class Loaded(val rows: List<AppRow>, val cfg: SourceResolver.Config,
                         val states: Map<String, SourceResolver.Check>, val cached: Map<String, ApkCache.Entry>)

    private val cDim = Ink.DIM
    private val cHead = 0xFFED8936.toInt()
    private val cUp = 0xFF48BB78.toInt()
    private val cUpd = 0xFFF6AD55.toInt()
    private val cMiss = 0xFF9F7AEA.toInt()
    private val cBadge = 0xFFE53E3E.toInt()
    private val main = Handler(Looper.getMainLooper())

    private var rows by mutableStateOf<List<AppRow>>(emptyList())
    /** The first enumeration has not landed yet. */
    private var loading by mutableStateOf(true)
    private var loaded by mutableStateOf(false)
    private var failure by mutableStateOf<String?>(null)
    // #619 the row filter. Declared (the full set rows() builds: installed ∪ fleet ∪ external) is the
    // default, and the mode Profile ▸ Store deep-links into; Installed is only rows already on the
    // device. No second list: it filters AppRow.installed, the state rows() already resolved through
    // PackageManager. Redrawing re-renders the SAME rows, so flipping the page never re-probes or
    // re-enumerates. #896 Installed | Search | Declared are the page's top tabs ([StorePages]).
    private var page by mutableStateOf(PAGE_DECLARED)
    // #627 which declared store source the page is showing, or null for All.
    // COMPOSES with the page rather than replacing it: the tab chooses the
    // store, the page chooses declared-vs-installed, and the render applies both to
    // the SAME rows() output — so every view is a subset of Declared, by
    // construction, exactly as #619 required.
    private var sourceTab by mutableStateOf<SourceResolver.Kind?>(null)
    /** The trait row's choice (Play Integrity / Private / Open source), or null for All; both pages. */
    private var trait by mutableStateOf<StoreSourceTabs.Trait?>(null)
    /** The state chips, the Cloud page's: 0 All, 1 Updates, 2 Missing, 3 Installed. */
    private var stateFilter by mutableIntStateOf(0)
    /** Installed only: 0 All, 1 Declared, 2 Not declared. */
    private var declFilter by mutableIntStateOf(0)
    private var cfg by mutableStateOf<SourceResolver.Config?>(null)
    private val states = mutableStateMapOf<String, SourceResolver.Check>()
    /** Packages whose probe is in flight (their state line says "checking…"). */
    private val checking = mutableStateMapOf<String, Boolean>()
    // #625 what the APK cache holds, per package, as of the last reload: the
    // row's own answer to "is the download still here, and is it the thing I
    // have installed?". Read from ApkCache's download records — no second
    // bookkeeping, and no hashing on the UI thread.
    private val cached = mutableStateMapOf<String, ApkCache.Entry>()
    // #666 the clear-cache label, carrying the REAL count and megabytes it would free, recomputed on
    // every reload rather than described. Null = not measured yet.
    private var cacheLabel by mutableStateOf<String?>(null)
    // Set when a tap has already taken the provably-installed bytes and the only
    // thing left is unproven: the next tap discards the only copy, and the toast
    // has already said so in those words. It stays armed across reloads on
    // purpose — leaving the tab does not un-warn the user — and is cleared by the
    // discard itself, or as soon as a later install proves an entry redundant.
    private var armedToDiscard = false
    // Sizes (StoreSizes / StoreSizeProbe): the list's order, persisted per phone; whether usage access
    // is granted (no grant: "—" until the APK fallback lands, and the one-tap grant row); which rows
    // have their detail open; the package-change watch that drops a stale size.
    private val viewPrefs by lazy { requireContext().getSharedPreferences(VIEW_PREFS, Context.MODE_PRIVATE) }
    private val sortStore = object : StoreSizes.Store {
        override fun get(key: String) = runCatching { viewPrefs.getString(key, null) }.getOrNull()
        override fun put(key: String, value: String) { runCatching { viewPrefs.edit().putString(key, value).apply() } }
    }
    private var sort by mutableStateOf(StoreSizes.Sort.SHELF)
    private var sizeGranted by mutableStateOf(true)
    /** Bumped when sizes were measured or a mark changed: every row re-reads what is not state. */
    private var redrawTick by mutableIntStateOf(0)
    private var resume by mutableIntStateOf(0)
    private val openRows = mutableStateMapOf<String, Boolean>()
    private var importPlan by mutableStateOf<AppInventory.Plan?>(null)
    private var stopObserving: (() -> Unit)? = null
    private var stopSizeWatch: (() -> Unit)? = null
    private val progress = StoreProgressPanel()

    // #565 export / import. Registered at construction, as the Activity Result
    // API requires; the system picker owns where the file lives.
    private val exportDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) exportTo(uri)
    }
    private val importDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) importFrom(uri)
    }

    override fun palette(): KitPalette = StoreSearchPage.palette(requireContext())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sort = StoreSizes.loadSort(sortStore)
        page = StorePages.page(SECTION, PAGE_DECLARED)
    }

    // Reloaded on every return: Remove and App info leave for a system
    // screen, and what they changed must not be drawn stale.
    override fun onResume() {
        super.onResume()
        resume++
        reload()
        // #570 Account ▸ Fleet ▸ Apps ▸ Apply list to Store: the declared inventory, same path as a picked file.
        StoreImport.takePending()?.let { importText(it) }
    }

    // ── the page ──────────────────────────────────────────────────────────────

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val searchMode = page == PAGE_SEARCH
        val installedOnly = page == PAGE_INSTALLED
        DisposableEffect(Unit) {
            page = StorePages.page(SECTION, PAGE_DECLARED)
            progress.attach()
            stopObserving = StorePages.observe(SECTION) { main.post {
                // Leaving Search re-reads the rows: a declare made there changes Declared.
                val was = page == PAGE_SEARCH
                page = StorePages.page(SECTION, PAGE_DECLARED)
                if (was && page != PAGE_SEARCH) reload()
            } }
            stopSizeWatch = StoreSizeProbe.watch(ctx) { main.post { if (isAdded) measureSizes() } }
            onDispose {
                stopObserving?.invoke(); stopObserving = null; stopSizeWatch?.invoke(); stopSizeWatch = null
                progress.detach()
            }
        }
        // The page's TOP TABS: Installed | Search | Declared, in build.json::ui phone.pages order. Drawn here by
        // the Cloud page's own strip builder unless the host draws them (StorePages.hostDrawsStrip).
        val groupTab = rememberStoreControls().groupTab
        val strip: (@Composable () -> Unit)? = if (StorePages.hostDrawsStrip) null else {
            {
                StoreTabs.Bar(listOf(listOf(
                    StoreControls.Control(ctx.getString(R.string.store_phone_filter_installed), "", groupTab),
                    StoreControls.Control(ctx.getString(R.string.store_phone_filter_search), "", groupTab),
                    StoreControls.Control(ctx.getString(R.string.store_phone_filter_declared), "", groupTab))),
                    PAGES.indexOf(page).coerceAtLeast(0)) { StorePages.select(SECTION, PAGES[it]) }
            }
        }
        StorePage.Frame(strip) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(sdp(StoreDensity.S12))) {
                if (!searchMode) PageBody(ctx, installedOnly)
                // The Search page (between Installed and Declared) replaces the list and its controls. It
                // stays composed (zero size) while the others show, so its query and results survive a switch.
                Column(if (searchMode) Modifier.fillMaxWidth() else Modifier.size(0.dp)) {
                    StoreSearchPage.Content { cfg ?: runCatching { PhoneAppActions.resolver(PhoneAppActions.sources(ctx)) }.getOrNull() }
                }
            }
        }
        importPlan?.let { plan -> StoreImport.PlanDialog(plan) { importPlan = null } }
    }

    @Composable
    private fun PageBody(ctx: Context, installedOnly: Boolean) {
        // THE ACTION BAR sits right under the top tabs, exactly where the Cloud page's header does (#896.3):
        // the SAME bar the Cloud tab draws. #571: Install all and Update all are real verbs here now - they
        // walk every row this store can serve itself (fleet path, vendor APK, F-Droid) and report the rows
        // that need Play. Then export / import / clear cache, and the store-source strip.
        StoreBar.Bar(StoreBar.Verbs(
            checkAll = { checkAll() }, installAll = { installAll() }, updateAll = { updateAll() }), resume = resume)
        progress.Panel()
        // The status area: declared rows vs installed and the updates' cached / to-download split, from
        // the rows and checks this page already holds. Re-read on every progress event.
        val tallyRows = rows; val tallyStates = states.toMap()
        StoreTally.Area(listOf(progress.tick, tallyRows, tallyStates, resume), { c -> tallyItems(c, tallyRows, tallyStates) })
        // The page's file verbs: the SAME shared action buttons, laid out by StoreRow.Actions as every row's are.
        // Installed owns Export and the APK cache (what is on this phone); Declared owns Import (a list to install).
        StoreRow.Actions(buildList {
            if (installedOnly) add(StoreVerb(ctx.getString(R.string.store_export), FILE_FILL, { exportDoc.launch(EXPORT_NAME) }, "store-phone:export"))
            if (!installedOnly) add(StoreVerb(ctx.getString(R.string.store_import), FILE_FILL, { importDoc.launch(IMPORT_TYPES) }, "store-phone:import"))
            // #625 the manual door onto the cache. The app owns eviction now, so
            // the user needs a way to say "drop it all" that does not mean
            // Settings > Clear cache - which no longer reaches these bytes, on
            // purpose, because the OS doing that silently WAS the bug.
            // #666 the label states the measured count and megabytes; reload()
            // rewrites it from ApkCache.plan, so it is never an estimate.
            if (installedOnly) add(StoreVerb(cacheLabel ?: ctx.getString(R.string.store_cache_clear), FILE_FILL, { clearCache() }, "store-phone:cache"))
        })
        if (loaded) Controls(ctx, installedOnly)
        StoreCaption(ctx.getString(R.string.store_phone_caption))
        when {
            loading && !loaded -> StoreCaption(ctx.getString(R.string.store_phone_loading))
            failure != null -> StoreCaption(ctx.getString(R.string.store_phone_list_failed, failure))
            else -> RowList(ctx, installedOnly)
        }
    }

    /** The store strip, the trait row, the state chips and the sort: everything that narrows the list. */
    @Composable
    private fun Controls(ctx: Context, installedOnly: Boolean) {
        // #627 the tab strip is drawn from the DECLARED kinds, so a change in the asset is a change on the
        // screen with nothing in between. Rows with no external ladder (fleet members) belong to no
        // store tab — they come from the constellation, not from a store, and
        // pretending otherwise would put them under whichever tab was listed
        // first.
        val kinds = cfg?.kinds.orEmpty()
        // The store strip only on Installed: a declared app's store is not chosen yet (its ladder is).
        if (installedOnly && kinds.isNotEmpty())
            StoreSourceTabs.Strip(kinds, sourceTab, { k -> if (sourceTab?.id != k?.id) sourceTab = k })
        StoreSourceTabs.Traits(trait, { t -> if (trait != t) trait = t })
        if (installedOnly) {
            val labels = listOf(R.string.store_phone_decl_all, R.string.store_phone_decl_yes, R.string.store_phone_decl_no).map { ctx.getString(it) }
            androidx.compose.foundation.layout.Row {
                val style = rememberStoreControls().filter
                labels.forEachIndexed { i, label ->
                    StoreBar.Chip(label, i == declFilter, { if (declFilter != i) declFilter = i }, first = i == 0,
                        tag = "store-phone:decl:$i", style = style)
                }
            }
        }
        // The state chips: the Cloud page's line, drawn by the same StoreRow.Filters.
        StoreRow.Filters(listOf("All", "⬆ Updates", "◯ Missing", "✓ Installed"), stateFilter,
            { i -> if (stateFilter != i) stateFilter = i },
            tags = listOf("store-phone:state:all", "store-phone:state:updates", "store-phone:state:missing", "store-phone:state:installed"))
        // The sort: the page's shelf order, or size (tap again to flip largest / smallest first). Persisted.
        val sizeLabel = if (sort == StoreSizes.Sort.SIZE_ASC) ctx.getString(R.string.store_phone_sort_size_asc)
                        else ctx.getString(R.string.store_phone_sort_size_desc)
        StoreRow.Filters(listOf(ctx.getString(R.string.store_phone_sort_shelf), sizeLabel),
            if (sort == StoreSizes.Sort.SHELF) 0 else 1,
            { i ->
                sort = if (i == 0) StoreSizes.Sort.SHELF else if (sort == StoreSizes.Sort.SHELF) StoreSizes.Sort.SIZE_DESC
                       else StoreSizes.toggleSize(sort)
                StoreSizes.saveSort(sortStore, sort)
            },
            tags = listOf("store-phone:sort:shelf", "store-phone:sort:size"))
        // No usage access: sizes are the APK's alone, and ONE tap opens the screen that grants it.
        if (StoreSizes.needsGrantRow(sizeGranted)) {
            StoreRow.Actions(listOf(StoreVerb(ctx.getString(R.string.store_phone_size_grant), GRANT_FILL, { grantUsageAccess(ctx) }, GRANT_TAG)))
            StoreCaption(ctx.getString(R.string.store_phone_size_grant_caption))
        }
    }

    @Composable
    private fun RowList(ctx: Context, installedOnly: Boolean) {
        redrawTick
        // #619 Declared shows every row rows() built; Installed keeps only the
        // ones on the device. The declared set is never rebuilt here — it is
        // filtered, so Installed is by construction a subset of Declared.
        val tab = sourceTab.takeIf { installedOnly }
        val shown = rows
            .filter { tab == null || it.external?.inTab(tab) == true }
            // Installed = on this phone; Declared = what the source map declares, installed or not.
            .filter { if (installedOnly) it.installed else it.external?.declared == true }
            .filter { traitOk(ctx, it) }
            .filter { stateOk(it) }
            .filter { declOk(it, installedOnly) }
            .sortedWith(StoreSizes.comparator(sort, { r: AppRow -> StoreSizeProbe.cached(r.pkg)?.totalBytes }, SHELF_ORDER))
        val missing = shown.count { !it.installed }
        val play = shown.count { !it.installed && !it.direct }
        StoreRow.Summary(ctx.getString(R.string.store_phone_count, shown.size) + "  ·  " +
            ctx.getString(R.string.store_phone_count_missing, missing, play))
        // Two groups, each with its own Install all: what our Store fully
        // manages, and what the VENDOR ties to Google Play (Play Integrity / a
        // Play-installer check). The second still installs from Play's servers;
        // it is grouped apart because it may refuse to run.
        val (needPlay, managed) = shown.partition { integrity(ctx, it) == SourceResolver.Integrity.PLAY }
        for ((title, group) in listOf(R.string.store_phone_group_managed to managed, R.string.store_phone_group_play to needPlay)) {
            if (group.isEmpty()) continue
            androidx.compose.foundation.layout.Row(
                Modifier.padding(top = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S4)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(ctx.getString(title, group.size), color = Ink.WHITE.ink(), fontSize = StoreDensity.T_TITLE.sp,
                    fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                val targets = group.filter { !it.installed && it.direct }
                if (targets.isNotEmpty()) StoreBar.Button(ctx.getString(R.string.store_phone_group_install_all, targets.size), null, {
                    Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_start, targets.size), Toast.LENGTH_SHORT).show()
                    batch(ctx, targets)
                }, tag = "store-phone:group-install:$title")
            }
            var heading: String? = null
            for (r in group) {
                // Sorted by size, the rows are one ranking: no shelf headings cutting it up.
                if (sort == StoreSizes.Sort.SHELF) {
                    val here = r.shelf?.heading ?: if (heading != null) ctx.getString(R.string.store_phone_other) else null
                    if (here != null && here != heading) StoreHeading(here, cHead)
                    heading = here
                }
                PhoneRow(ctx, r)
            }
        }
    }

    /** The trait row: Play Integrity (must come from Play), Open source, Private (declared and not open). */
    private fun traitOk(ctx: Context, r: AppRow): Boolean = when (trait) {
        null -> true
        StoreSourceTabs.Trait.PLAY_INTEGRITY -> integrity(ctx, r) == SourceResolver.Integrity.PLAY
        StoreSourceTabs.Trait.OPEN_SOURCE -> r.external?.openSource == true
        // An undeclared app is unknown, not private: only a declared, non-open row is.
        StoreSourceTabs.Trait.PRIVATE -> r.external?.let { it.declared && !it.openSource } == true
    }

    /** Installed's declared filter; Declared itself already is the declared rows. */
    private fun declOk(r: AppRow, installedOnly: Boolean): Boolean = when (declFilter.takeIf { installedOnly } ?: 0) {
        1 -> r.external?.declared == true
        2 -> r.external?.declared != true
        else -> true
    }

    /** Where an installed app came from, as the rungs it would be declared with. */
    private fun originKinds(ctx: Context, pkg: String): List<String> {
        val installer = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 30) ctx.packageManager.getInstallSourceInfo(pkg).installingPackageName
            else @Suppress("DEPRECATION") ctx.packageManager.getInstallerPackageName(pkg)
        }.getOrNull()
        // The F-Droid client's package comes from the one map (its kind's `installer`), never from here.
        val fdroidInstaller = cfg?.kind(SourceResolver.KIND_FDROID)?.installer
        return if (installer != null && installer == fdroidInstaller) listOf(SourceResolver.KIND_FDROID, SourceResolver.KIND_PLAY_ANON)
               else listOf(SourceResolver.KIND_PLAY_ANON)
    }

    /** The state chips; an update shows once Check all has probed the row. */
    private fun stateOk(r: AppRow): Boolean = when (stateFilter) {
        1 -> states[r.pkg] is SourceResolver.Check.UpdateAvailable
        2 -> !r.installed
        3 -> r.installed
        else -> true
    }

    /** The row's integrity: declared, raised to PLAY by this phone's own "did not run". */
    private fun integrity(ctx: Context, r: AppRow): SourceResolver.Integrity =
        r.external?.let { IntegrityMarks.effective(ctx, it) } ?: SourceResolver.Integrity.NONE

    /** The row's head as the Cloud page draws it: status dot left, then the short state and the size on the right. */
    private class PhoneHead(val dot: String, val color: Int, val meta: String)

    /** The short "who has it" label of an app we cannot install ourselves, from the app's own declared hand-off. */
    private fun needsLabel(ctx: Context, r: AppRow): String {
        val handoff = r.external?.handoff
        return when {
            r.external?.unresolved != null -> "no public source"
            handoff is SourceResolver.Source.Store -> cfg?.kinds?.firstOrNull { it.id == handoff.kind }?.label ?: handoff.kind
            else -> ctx.getString(R.string.store_phone_badge_needs_play)
        }
    }

    private fun headOf(ctx: Context, r: AppRow): PhoneHead {
        if (checking[r.pkg] == true) return PhoneHead("·", cDim, ctx.getString(R.string.store_phone_checking))
        val size = if (r.installed) StoreSizes.rowLabel(StoreSizeProbe.cached(r.pkg)) else StoreSizes.NONE
        val (dot, color, state) = when (val s = states[r.pkg] ?: SourceResolver.Check.Unknown(null, "")) {
            is SourceResolver.Check.Installed -> Triple("✓", cUp, "installed  ·  " + s.versionName)
            is SourceResolver.Check.UpdateAvailable -> Triple("⬆", cUpd, "update  ·  " + s.versionName)
            is SourceResolver.Check.NotInstalled ->
                if (s.needsPlay) Triple("◯", cBadge, needsLabel(ctx, r))
                else Triple("◯", cMiss, "not installed")
            is SourceResolver.Check.Unknown -> Triple("·", cDim, "unknown")
        }
        return PhoneHead(dot, color, state + (if (size == StoreSizes.NONE) "" else "  ·  $size"))
    }

    /** THE Store row ([StoreRow.Item]), exactly as the Cloud page draws it: the head (status dot, name, short
     *  state + size, quick verb, chevron) and, behind the chevron, the state line, the row's buttons EXPANDED
     *  in full-width lines, the package and the size breakdown. */
    @Composable
    private fun PhoneRow(ctx: Context, r: AppRow) {
        redrawTick
        val open = openRows[r.pkg] == true
        val head = headOf(ctx, r)
        // The collapsed quick verb is the row's own Install / Update, when it can run.
        val quickAction = r.actions.firstOrNull { (it.kind == PhoneAppActions.Kind.INSTALL || it.kind == PhoneAppActions.Kind.UPDATE) && it.disabledReason == null }
        StoreRow.Item(r.label, open, { openRows[r.pkg] = !open }, tag = "store-phone-row:${r.pkg}",
            dot = head.dot, dotColor = head.color, meta = head.meta, metaColor = head.color,
            quick = quickAction?.let { a -> StoreQuick(if (a.kind == PhoneAppActions.Kind.UPDATE) "⬆" else "⬇",
                if (a.kind == PhoneAppActions.Kind.UPDATE) 0xFFED8936.toInt() else 0xFF2B6CB0.toInt()) { act(ctx, r, a) } },
            headTag = "store-phone-head:${r.pkg}", metaTag = STATE_TAG_PREFIX + r.pkg) {
            // The full state line: version / update / not installed / needs Play, tagged with the package so a
            // test reads the rendered verdict, not this source.
            val (text, colour) = if (checking[r.pkg] == true) ctx.getString(R.string.store_phone_checking) to cDim
                else stateLine(ctx, states[r.pkg] ?: SourceResolver.Check.Unknown(null, ""), r)
            Text(text, color = colour.ink(), fontSize = StoreDensity.T_META.sp, modifier = Modifier.testTag(STATE_LINE_TAG_PREFIX + r.pkg))
            // The Play Integrity badge: an app the vendor ties to Google Play. We
            // still install it; the badge says it may refuse to run. Tagged so a
            // test reads the rendered badge.
            if (integrity(ctx, r) == SourceResolver.Integrity.PLAY)
                Text(ctx.getString(R.string.store_phone_integrity_badge), color = cBadge.ink(), fontSize = StoreDensity.T_CAPTION.sp,
                    modifier = Modifier.testTag(INTEGRITY_TAG_PREFIX + r.pkg))
            // #625 cached-vs-installed, per row. "cached, matches what is
            // installed" is the state in which the bytes are about to be reaped;
            // "cached, NOT installed" is a download waiting for its install - the
            // thing that used to disappear.
            cached[r.pkg]?.let { e ->
                val rec = e.record ?: return@let
                val here = SourceResolver.installed(ctx, r.pkg)?.second
                val mb = (e.bytes / 1_000_000).coerceAtLeast(1L)
                Text(if (here == rec.versionCode) ctx.getString(R.string.store_cache_matches, rec.versionCode, mb)
                     else ctx.getString(R.string.store_cache_waiting, rec.versionCode, mb, here?.toString() ?: "—"),
                    color = (if (here == rec.versionCode) cUp else cUpd).ink(), fontSize = StoreDensity.T_CAPTION.sp,
                    modifier = Modifier.testTag(CACHE_TAG_PREFIX + r.pkg))
            }
            Text(r.pkg, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp, fontFamily = FontFamily.Monospace,
                maxLines = 1, overflow = TextOverflow.MiddleEllipsis)
            Text(if (r.installed) StoreSizes.breakdown(StoreSizeProbe.cached(r.pkg)) else StoreSizes.NONE,
                color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp, modifier = Modifier.testTag(SIZE_DETAIL_TAG_PREFIX + r.pkg))
            val verbs = ArrayList<StoreVerb>()
            r.actions.forEach { a ->
                verbs += StoreVerb(a.label, null, { act(ctx, r, a) }, "store-phone:verb:${a.kind.name}:${r.pkg}",
                    alpha = if (a.disabledReason == null) null else 0.4f,
                    description = a.disabledReason?.let { "${a.label}: $it" } ?: a.label)
            }
            // Detected at runtime: an UNKNOWN app this store installed that did not
            // run is marked PLAY on this phone (persisted, exported with the inventory).
            val ext = r.external
            // Declared is the user's list: any foreign row can join or leave it. These two are Phone's extra buttons.
            if (ext != null) verbs += StoreVerb(
                ctx.getString(if (ext.declared) R.string.store_phone_undeclare else R.string.store_phone_declare), null, {
                    if (ext.declared) DeclaredEdits.undeclare(ctx, r.pkg)
                    else DeclaredEdits.declare(ctx, r.pkg, r.label, originKinds(ctx, r.pkg))
                    reload()
                }, "store-phone:declare:${r.pkg}")
            if (r.installed && ext != null && IntegrityMarks.canReport(ctx, ext)) verbs += StoreVerb(
                ctx.getString(R.string.store_phone_did_not_run), null, {
                    IntegrityMarks.mark(ctx, r.pkg)
                    Toast.makeText(ctx, ctx.getString(R.string.store_phone_marked_play, r.label), Toast.LENGTH_LONG).show()
                    redrawTick++
                }, "store-phone:did-not-run:${r.pkg}")
            // EXPANDED, as the Cloud page lays its buttons out: never a sideways scroller hiding half of them.
            StoreRow.Actions(verbs)
        }
    }

    /** One [SourceResolver.Check] → the row's state line and colour. */
    private fun stateLine(ctx: Context, s: SourceResolver.Check, r: AppRow): Pair<String, Int> {
        // #627 A KIND'S NAME COMES FROM THE DECLARATION, ONCE. This was a
        // `when` over three KIND_* constants mapping to three string resources —
        // a second list of the stores that exist, three lines long, and adding
        // Samsung or Aurora to the asset would have shown them on the row's
        // ladder as the empty string. The fleet is not a declared store kind, so
        // it keeps its own label; everything else is looked up.
        fun via(k: String?) = when (k) {
            null -> ""
            SourceResolver.VIA_FLEET -> ctx.getString(R.string.store_phone_source_fleet)
            else -> cfg?.kinds?.firstOrNull { it.id == k }?.label ?: k
        }
        val ladder = r.external?.sources?.joinToString(" → ") { via(it.kind) } ?: via(SourceResolver.VIA_FLEET)
        return when (s) {
            is SourceResolver.Check.NotInstalled -> {
                // #627 NAME THE STORE THAT ACTUALLY HAS IT. "needs Play" is the
                // right sentence for a Play hand-off and the wrong one for an app
                // only the Galaxy Store publishes, so the store comes from the
                // app's own first hand-off rung rather than from a constant.
                val handoff = r.external?.handoff
                val text = when {
                    !s.needsPlay -> ctx.getString(R.string.store_phone_state_not_installed, ladder)
                    r.external?.unresolved != null ->
                        ctx.getString(R.string.store_phone_state_unresolved, r.external?.unresolved.orEmpty())
                    handoff is SourceResolver.Source.Store ->
                        ctx.getString(R.string.store_phone_state_needs_store, via(handoff.kind))
                    else -> ctx.getString(R.string.store_phone_state_needs_play,
                        ctx.getString(R.string.store_phone_badge_needs_play))
                }
                text to (if (s.needsPlay) cBadge else cMiss)
            }
            is SourceResolver.Check.Installed -> {
                val note = when (s.note) {
                    SourceResolver.Note.NONE -> if (s.via == null) "" else "  ·  " + via(s.via)
                    SourceResolver.Note.NO_FEED -> "  ·  " + ctx.getString(R.string.store_phone_note_no_feed)
                    SourceResolver.Note.PLAY_MANAGES -> "  ·  " + ctx.getString(R.string.store_phone_note_play)
                    // #627 the same honesty for every other hand-off store: it
                    // owns the update and we have no feed, said rather than
                    // rounded to "up to date".
                    SourceResolver.Note.STORE_MANAGES -> "  ·  " +
                        ctx.getString(R.string.store_phone_note_store, via(s.via))
                    SourceResolver.Note.UNDECLARED -> "  ·  " + ctx.getString(R.string.store_phone_note_undeclared)
                    SourceResolver.Note.NOT_COMPARABLE -> "  ·  " + ctx.getString(R.string.store_phone_note_not_comparable)
                }
                (ctx.getString(R.string.store_phone_state_installed, s.versionName, s.versionCode) + note) to cUp
            }
            is SourceResolver.Check.UpdateAvailable ->
                ctx.getString(R.string.store_phone_state_update, s.versionName, s.remote, via(s.via)) to cUpd
            is SourceResolver.Check.Unknown ->
                ctx.getString(R.string.store_phone_state_unknown, s.versionName ?: "—", s.reason) to cDim
        }
    }

    // ── loading ───────────────────────────────────────────────────────────────

    private fun reload(then: (() -> Unit)? = null) {
        val ctx = context ?: return
        loading = true
        // Enumerating, classifying and probing a few hundred packages is
        // PackageManager IPC — off the main thread, the #261 lesson.
        val app = ctx.applicationContext
        thread(name = "store-phone-apps") {
            val built = runCatching { rows(app) }
            main.post {
                if (!isAdded) return@post
                loading = false
                built.onSuccess { l ->
                    rows = l.rows; cfg = l.cfg; failure = null; loaded = true
                    states.clear(); states.putAll(l.states)
                    cached.clear(); cached.putAll(l.cached)
                    refreshCacheLabel(); measureSizes(); then?.invoke()
                }.onFailure { failure = it.message }
            }
        }
    }

    override fun onDestroyView() {
        stopObserving?.invoke(); stopObserving = null; stopSizeWatch?.invoke(); stopSizeWatch = null
        progress.detach()
        super.onDestroyView()
    }

    /** Every launchable app, fleet included, as [AppInventory] JSON. */
    private fun exportTo(uri: Uri) {
        val app = requireContext().applicationContext
        thread(name = "store-export") {
            val result = runCatching {
                val entries = AppInventory.entriesFor(app, AppInventory.launchable(app))
                app.contentResolver.openOutputStream(uri, "wt")!!.use { it.write(AppInventory.toJson(entries, IntegrityMarks.marked(app)).toByteArray()) }
                entries.size
            }
            toastLater(app, result.fold({ app.getString(R.string.store_export_done, it) },
                { app.getString(R.string.store_file_failed, it.message) }))
        }
    }

    /** Read a file, diff it against this phone, show the plan. Acts on nothing. */
    private fun importFrom(uri: Uri) {
        val app = requireContext().applicationContext
        thread(name = "store-import") {
            val text = runCatching { app.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() } }
            main.post { if (isAdded) text.onSuccess { importText(it) }
                .onFailure { Toast.makeText(app, app.getString(R.string.store_file_failed, it.message), Toast.LENGTH_LONG).show() } }
        }
    }

    /** Diff an inventory (file or Account hand-off) against this phone, show the plan. Acts on nothing. */
    private fun importText(text: String) {
        val app = requireContext().applicationContext
        thread(name = "store-import") {
            val result = runCatching {
                val wanted = AppInventory.parse(text)
                val pm = app.packageManager
                val installed = wanted.map { it.pkg }.filter { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }.toSet()
                AppInventory.plan(wanted, installed, AppInventory.fleetPackages(), PhoneAppActions.sources(app))
            }
            main.post {
                if (!isAdded) return@post
                result.onSuccess { importPlan = it }
                    .onFailure { Toast.makeText(app, app.getString(R.string.store_file_failed, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    /**
     * The DECLARED list: installed launchable apps ∪ fleet apps (kind app) ∪
     * the resolver's external apps, each with its actions and its local state.
     * Sorted: shelf order, then label; unshelved last.
     */
    private fun rows(ctx: Context): Loaded {
        val pm = ctx.packageManager
        val fleetList = Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64)
        val fleet = PhoneAppActions.fleetByPackage(fleetList)
        val sources = PhoneAppActions.sources(ctx)
        val resolver = PhoneAppActions.resolver(sources)
        val shellReady = ShellChannels.active(ctx) != null
        // The Phone page is the FOREIGN apps' page: a fleet app — installed or not — is the Cloud
        // page's (constellation install / update all), never declared, listed or installed here.
        val declared = LinkedHashMap<String, String>()
        declared.putAll(AppInventory.launchable(ctx).filterKeys { it !in fleet })
        resolver.apps.values.filter { it.pkg !in fleet }.forEach { declared.putIfAbsent(it.pkg, it.label) }
        DeclaredEdits.addedPackages(ctx).filterKeys { it !in fleet }.forEach { (p, l) -> declared.putIfAbsent(p, l) }
        val shelves = AppStoreHost.classify(ctx, declared)
        val states = HashMap<String, SourceResolver.Check>()
        val cached = HashMap<String, ApkCache.Entry>()
        ApkCache.entries(ctx).forEach { e ->
            val r = e.record ?: return@forEach
            // Newest cached build per package wins the row's line; the older one
            // is what eviction takes first.
            val had = cached[r.pkg]?.record
            if (had == null || r.versionCode > had.versionCode) cached[r.pkg] = e
        }
        val list = declared.map { (pkg, label) ->
            val installed = runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess
            val fa = fleet[pkg]
            val ext = if (fa == null) DeclaredEdits.apply(ctx, resolver, pkg, SourceResolver.resolve(resolver, pkg)) else null
            val actions = if (installed) PhoneAppActions.of(ctx, pkg, fa, shellReady, sources)
                          else PhoneAppActions.forMissing(ctx, ext ?: SourceResolver.resolve(resolver, pkg), fa, sources, resolver)
            states[pkg] = when {
                ext != null -> SourceResolver.local(ctx, ext)
                installed -> SourceResolver.installed(ctx, pkg)?.let { SourceResolver.Check.Installed(it.first, it.second, null, SourceResolver.Note.NONE) }
                    ?: SourceResolver.Check.Unknown(null, "")
                else -> SourceResolver.Check.NotInstalled(SourceResolver.VIA_FLEET, needsPlay = false)
            }
            AppRow(pkg, label, shelves[pkg], fa, ext, installed, actions)
        }.sortedWith(SHELF_ORDER)
        return Loaded(list, resolver, states, cached)
    }

    /** The declared rows as [StoreTally] items: PackageManager APK bytes, the checks' verdicts, the APK cache.
     *  Local reads only; called off the main thread. */
    private fun tallyItems(c: Context, list: List<AppRow>, st: Map<String, SourceResolver.Check>): List<StoreTally.Item> {
        val cache = StoreTally.cache(c)
        return list.map { r ->
            val update = st[r.pkg] is SourceResolver.Check.UpdateAvailable
            StoreTally.Item(
                installed = r.installed,
                installedBytes = if (r.installed) StoreTally.apkBytes(c, r.pkg) else -1L,
                update = update,
                cachedBytes = if (update) StoreTally.cachedUpdate(cache, r.pkg, StoreTally.installedCode(c, r.pkg)) else -1L,
            )
        }
    }

    /** Sizes for every installed row, measured off the main thread (cached; see [StoreSizeProbe]), then repainted. */
    private fun measureSizes() {
        val app = context?.applicationContext ?: return
        val pkgs = rows.filter { it.installed }.map { it.pkg }
        thread(name = "store-phone-sizes") {
            val granted = StoreSizeProbe.granted(app)
            runCatching { StoreSizeProbe.measure(app, pkgs) }
            main.post { if (isAdded) { sizeGranted = granted; redrawTick++ } }
        }
    }

    /** #1274 the one-tap grant: the host's Permissions page on usage access; this lib never opens a settings page. */
    private fun grantUsageAccess(ctx: Context) = StoreSizeProbe.openPermissions(ctx)

    /** Check all: rebuild the rows, then probe every one off the main thread. */
    private fun checkAll() = reload {
        val ctx = requireContext(); val app = ctx.applicationContext
        val resolver = cfg ?: return@reload
        for (r in rows) {
            checking[r.pkg] = true
            thread(name = "store-phone-check-${r.pkg}") {
                val s = runCatching {
                    r.fleetApp?.let { SourceResolver.ofFleet(Fleet.status(app, it)) }
                        ?: SourceResolver.check(app, resolver, r.external ?: SourceResolver.resolve(resolver, r.pkg))
                }.getOrElse { SourceResolver.Check.Unknown(null, it.message ?: it.javaClass.simpleName) }
                main.post { if (isAdded) { states[r.pkg] = s; checking.remove(r.pkg) } }
            }
        }
    }

    /** Install all: every declared app not on the phone that this store can serve itself. */
    private fun installAll() {
        val ctx = requireContext()
        val targets = rows.filter { !it.installed && it.direct }
        val play = rows.count { !it.installed && !it.direct }
        if (targets.isEmpty()) { Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_none), Toast.LENGTH_LONG).show(); return }
        Toast.makeText(ctx, ctx.getString(R.string.store_phone_install_all_start, targets.size, play), Toast.LENGTH_LONG).show()
        batch(ctx, targets)
    }

    /** Update all: every row Check all found an update for. Never runs a probe itself. */
    private fun updateAll() {
        val ctx = requireContext()
        val targets = rows.filter { states[it.pkg] is SourceResolver.Check.UpdateAvailable && it.direct }
        if (targets.isEmpty()) { Toast.makeText(ctx, ctx.getString(R.string.store_phone_update_none), Toast.LENGTH_LONG).show(); return }
        Toast.makeText(ctx, ctx.getString(R.string.store_phone_batch_start, targets.size), Toast.LENGTH_SHORT).show()
        batch(ctx, targets)
    }

    /**
     * #625 DOWNLOAD THEM ALL, THEN INSTALL THEM ONE BY ONE.
     *
     * This used to be one loop calling [installOne] per row — resolve,
     * download, install, next — so every confirmation dialog was followed by a
     * wait on the next app's network fetch, and a batch interrupted half way
     * had installed some apps and not even downloaded the rest. [BatchInstall]
     * is the ONE engine that owns the ordering (the same two-phase shape
     * [Fleet.installAllLocked] already used for the constellation pass), so
     * this fragment cannot hold a different opinion about it.
     *
     * Installs stay strictly sequential inside phase 2 — each one may raise the
     * system confirm sheet — and a download failure costs only its own app.
     */
    private fun batch(ctx: Context, targets: List<AppRow>) {
        val app = ctx.applicationContext
        val resolver = cfg ?: PhoneAppActions.resolver(PhoneAppActions.sources(app))
        thread(name = "store-phone-batch") {
            val outcomes = BatchInstall.run(
                app,
                targets.map { BatchInstall.Target(it.pkg, it.label, it.fleetApp, it.external) },
                BatchInstall.engine(resolver)
            ) { phase, t, i, n ->
                UpdateProgress.beginBatch(
                    (if (phase == BatchInstall.Phase.DOWNLOAD) "↓ " else "") + t.label, i, n)
            }
            UpdateProgress.endBatch()
            // Per-app, never a bare count: a failure you cannot name is a
            // failure nobody can act on. The cached bytes of a failed install
            // are still on disk, and the row's cache line now says so.
            val failed = outcomes.filter { !it.installed }
            failed.forEach { o ->
                toastLater(app, app.getString(R.string.store_phone_failed, o.target.label,
                    o.message ?: app.getString(R.string.store_cache_kept)))
            }
            toastLater(app, app.getString(R.string.store_phone_batch_done,
                outcomes.size - failed.size, failed.size))
            main.post { if (isAdded) reload() }
        }
    }

    /**
     * #666 the user's own "clear cache", in two tiers so that a tap can never be
     * the thing that loses a download.
     *
     * Tier one takes only the bytes [ApkCache.clearRedundant] can PROVE are
     * redundant — the installed package hashes to them. Whatever is left is the
     * only copy of something whose install is not proven (a failed install, a
     * download interrupted mid-update), so it is kept and the toast says, in
     * real counts and megabytes, that a second tap discards it. Tier two runs
     * only on that second tap. #625's data loss must not come back through this
     * button.
     */
    private fun clearCache() {
        val app = requireContext().applicationContext
        val discard = armedToDiscard
        thread(name = "store-cache-clear") {
            val e = if (discard) ApkCache.clear(app) else ApkCache.clearRedundant(app)
            val left = if (discard) ApkCache.Plan(emptyList(), emptyList()) else ApkCache.plan(app)
            toastLater(app, when {
                discard -> app.getString(R.string.store_cache_discarded,
                    e.deleted.size, e.freedBytes / 1_000_000)
                left.kept.isEmpty() -> app.getString(R.string.store_cache_cleared,
                    e.deleted.size, e.freedBytes / 1_000_000)
                // #233: a surface that cannot act must say so. Name the count and
                // the megabytes, and name what a second tap would cost.
                else -> app.getString(R.string.store_cache_some_kept,
                    e.deleted.size, e.freedBytes / 1_000_000,
                    left.kept.size, left.keptBytes / 1_000_000)
            })
            main.post {
                if (!isAdded) return@post
                armedToDiscard = left.kept.isNotEmpty()
                reload()
            }
        }
    }

    /** #666 the clear-cache label, measured. Off the main thread — deciding
     *  redundancy hashes every cached APK and the installed package beside it. */
    private fun refreshCacheLabel() {
        val app = context?.applicationContext ?: return
        val armed = armedToDiscard
        thread(name = "store-cache-plan") {
            val p = runCatching { ApkCache.plan(app) }.getOrNull() ?: return@thread
            // An install that landed since the warning turns an unproven entry
            // redundant, and then there is nothing left to warn about: disarm,
            // rather than offer to discard nothing.
            val stillArmed = armed && p.kept.isNotEmpty()
            val free = if (stillArmed) p.keptBytes else p.redundantBytes
            val count = if (stillArmed) p.kept.size else p.redundant.size
            main.post {
                if (!isAdded) return@post
                armedToDiscard = stillArmed
                cacheLabel = when {
                    stillArmed -> app.getString(R.string.store_cache_clear_discard, count, free / 1_000_000)
                    count == 0 -> app.getString(R.string.store_cache_clear_empty)
                    else -> app.getString(R.string.store_cache_clear_n, count, free / 1_000_000)
                }
            }
        }
    }

    /** The ONE path per kind. Blocking. */
    private fun installOne(app: Context, r: AppRow): String? {
        val resolver = cfg ?: PhoneAppActions.resolver(PhoneAppActions.sources(app))
        // Not `fleetApp?.let { } ?: external`: a fleet install that SUCCEEDS
        // returns null, and that elvis would have gone on to run the external path.
        val fleetApp = r.fleetApp
        return if (fleetApp != null) FleetInstall.run(app, fleetApp)
               else ExternalInstall.run(app, resolver, r.external ?: SourceResolver.resolve(resolver, r.pkg))
    }

    private fun act(ctx: Context, r: AppRow, a: PhoneAppActions.Action) {
        a.disabledReason?.let { Toast.makeText(ctx, it, Toast.LENGTH_LONG).show(); return }
        when (a.kind) {
            PhoneAppActions.Kind.INSTALL, PhoneAppActions.Kind.UPDATE -> {
                Toast.makeText(ctx, ctx.getString(
                    if (a.kind == PhoneAppActions.Kind.INSTALL) R.string.store_phone_installing else R.string.store_phone_updating, r.label),
                    Toast.LENGTH_SHORT).show()
                val app = ctx.applicationContext
                thread(name = "store-phone-install-${r.pkg}") {
                    installOne(app, r)?.let { msg -> toastLater(app, app.getString(R.string.store_phone_failed, r.label, msg)) }
                    main.post { if (isAdded) reload() }
                }
            }
            PhoneAppActions.Kind.STOP -> if (SelfStop.isSelf(ctx, r.pkg)) SelfStop.stop(ctx, activity) else thread(name = "store-phone-stop") {
                val out = PhoneAppActions.forceStop(ctx, r.pkg)
                toastLater(ctx, if (out?.contains("OK") == true) ctx.getString(R.string.store_phone_stopped, r.label)
                                else ctx.getString(R.string.store_phone_failed, r.label, out?.trim() ?: ctx.getString(R.string.store_phone_why_no_shell)))
            }
            else -> a.intent?.let { i ->
                runCatching { startActivity(i) }
                    .onFailure { Toast.makeText(ctx, ctx.getString(R.string.store_phone_failed, a.label, it.message), Toast.LENGTH_LONG).show() }
            }
        }
    }

    private fun toastLater(ctx: Context, msg: String) =
        main.post { Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show() }

    companion object {
        /** The Play Integrity badge of a row, tagged [INTEGRITY_TAG_PREFIX] + package. */
        const val INTEGRITY_TAG_PREFIX = "store-integrity:"
        /** The state line of a row is tagged [STATE_TAG_PREFIX] + package. */
        const val STATE_TAG_PREFIX = "store-phone-state:"
        /** The full state line behind the chevron, tagged [STATE_LINE_TAG_PREFIX] + package. */
        const val STATE_LINE_TAG_PREFIX = "store-phone-state-line:"
        /** #625 the cached-vs-installed line, tagged [CACHE_TAG_PREFIX] + package.
         *  Absent when nothing for that package is in the cache — a test reads
         *  the rendered answer rather than this source. */
        const val CACHE_TAG_PREFIX = "store-phone-cache:"
        /** The row's size slot ("48 MB" / "48 MB APK" / "—"), tagged [SIZE_TAG_PREFIX] + package. */
        const val SIZE_TAG_PREFIX = "store-phone-size:"
        /** The size breakdown behind the chevron, tagged [SIZE_DETAIL_TAG_PREFIX] + package. */
        const val SIZE_DETAIL_TAG_PREFIX = "store-phone-size-detail:"
        /** The one-tap usage-access grant row, drawn only while the grant is missing. */
        const val GRANT_TAG = "store-phone-size-grant"
        /** This page's view prefs (the sort). Device-local: declared in fleet-config.json. */
        const val VIEW_PREFS = "store_phone_view"
        private const val FILE_FILL = 0xFF2B6CB0.toInt()
        private const val GRANT_FILL = 0xFFB7791F.toInt()
        /** The page's base order: shelf, then label; unshelved last. A size sort breaks ties with it. */
        private val SHELF_ORDER: Comparator<AppRow> = compareBy({ it.shelf?.order ?: UNSHELVED }, { it.label.lowercase() })
        /** The build.json::ui section id this page is, and its two child pages (#896). */
        const val SECTION = "phone"
        const val PAGE_INSTALLED = "installed"
        const val PAGE_DECLARED = "declared"
        const val PAGE_SEARCH = "search"
        /** build.json::ui phone.pages order: Installed | Search | Declared. */
        val PAGES = listOf(PAGE_INSTALLED, PAGE_SEARCH, PAGE_DECLARED)
        private const val UNSHELVED = "￿"
        private const val EXPORT_NAME = "cloud-sa-apps.json"
        // A .json picked from Downloads is as often octet-stream as json.
        private val IMPORT_TYPES = arrayOf("application/json", "application/octet-stream", "text/plain")
    }
}
