package com.diegonmarcos.superapp.appstore

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.updater.Fleet
import com.diegonmarcos.superapp.updater.FleetIdentity
import com.diegonmarcos.superapp.updater.Updater
import kotlin.concurrent.thread
import org.json.JSONArray
import org.json.JSONObject

/**
 * Store ▸ Cloud Constellation (#563) — the page that used to BE the store
 * ("Constellation AppStore"), now the first of Store's two subpages. superapp
 * is the fleet manager: install / update / uninstall / open every
 * constellation APK. Its sibling [StorePhoneFragment] covers every APK that is
 * NOT in the fleet.
 *
 * IDENTITY vs TITLE. "Constellation" survives here as a display word only —
 * the subpage's label, which build.json declares beside its id. Nothing that
 * ROUTES to this page (class, page id, target) says constellation; the
 * fleet's own names (constellation-fleet.json, CONSTELLATION_DATA,
 * [ConstellationWorker]) are the fleet's identity, not the store's, and
 * stay. test-store-identity.sh holds that line.
 *
 * Each app's status is fetched on its OWN thread (concurrently), so one slow or
 * unreachable image never blocks the others — the previous single-thread loop
 * was why Dialer showed no status and unpublished Chat looked "stuck". Fleet
 * list is data-driven from BuildConfig.CONSTELLATION_FLEET_B64.
 *
 * Compose: every row is a function of three maps this page holds — the network [states], the cache
 * [stages] and the [checking] marks — so a check thread landing only writes its answer and the row
 * redraws itself; nothing here reaches into a row.
 */
class StoreCloudFragment : KitComposeFragment() {

    private val fleet by lazy { Fleet.parse(BuildConfig.CONSTELLATION_FLEET_B64) }

    /** One tab: a group the fleet data declares, and every row drawn in it. */
    private class Tab(val id: String, val label: String, val blurb: String, val rows: List<Fleet.App>)

    // The same baked JSON Fleet.parse reads, for the three things that are this
    // page's business and not the updater's: the declared groups, the members
    // of each, and the reference catalogue. Read here rather than added to
    // Fleet.App, so no updater, worker or grant path ever sees a reference row.
    private val fleetJson by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.CONSTELLATION_FLEET_B64, Base64.DEFAULT))) }
            .getOrElse { JSONObject() }
    }
    private fun fleetObjects(key: String): List<JSONObject> =
        fleetJson.optJSONArray(key)?.let { array -> (0 until array.length()).map { array.getJSONObject(it) } }.orEmpty()

    private val catalogue by lazy { fleetObjects("catalogue").associateBy { it.getString("id") } }
    // Third-party reference rows: no APK, package or asset. Built BLOCKED unless
    // the data says installable, so every row, chip and batch path treats them the
    // way it already treats an unpublished app - and Fleet.installAllLocked drops
    // a blocked entry before it checks status, so no batch ever fetches one.
    private val references by lazy {
        catalogue.values.map { entry ->
            Fleet.App(id = entry.getString("id"), label = entry.optString("label", entry.getString("id")),
                pkg = "", altId = null, registry = "", namespace = "", image = "", tag = "",
                asset = "", assets = emptyMap(), releaseUrl = "", repoUrl = "", ghcrPage = "",
                blocked = !entry.optBoolean("installable", false), kind = "",
                // A catalogue row's `version` IS our version of it: the content
                // address regen.sh computed from the tree (#618/#624). Carried
                // so the one identity pattern has a real version to print here
                // too, instead of the marker for a row that does declare one.
                declaredVersionName = entry.optString("version").takeIf { it.isNotEmpty() })
        }
    }
    // #405: an ML lib states its application in its own id -
    // lib-ml-{t|l}-{domain}-{name}, where domain is voice, text, image, sensor
    // or tabular. The ML tabs group by that, so the heading a row is drawn under
    // is READ OFF THE ROW and there is no second list of applications to keep in
    // step with the names. A row that is not an ML lib has no application; every
    // such row sits in one unnamed run, which is every tab but the two ML ones.
    private val mlApplication = Regex("^lib-ml-[tl]-([a-z0-9]+)-")
    private fun applicationOf(id: String): String? =
        mlApplication.find(id)?.groupValues?.get(1)

    // #642: THE LIBS TAB'S TABLES, and not a second grouping mechanism.
    //
    // An APP is filed by `app_categories` (below), derived from the SuperApp's Cloud > Apps tile
    // groups. A LIB has no tile there, and the launcher classifier (the old
    // app grouping) returned nothing for every one of them, so all 46 rows fell
    // into ONE unnamed run: the flat list. So the fleet data declares each lib's category as the SAME
    // (heading, order) pair, and [shelfOf] hands it to the code that already
    // draws the Apps tables. Nothing below this line knows a lib from an app.
    //
    // DATA, both directions: the categories, their order and their membership
    // are `lib_apks.categories` in ab_cloud-libs-shared/lib-apks/build.json,
    // carried here by regen.sh in the same {id,label,members} shape as the
    // groups. Adding a lib or a whole category is that edit and nothing else —
    // no label, id or count is written here. A lib no category lists has no
    // shelf and heads "Other", exactly like an app the launcher does not file.
    private val libShelves: Map<String, AppStoreHost.Shelf> by lazy {
        val filed = HashMap<String, AppStoreHost.Shelf>()
        fleetObjects("lib_categories").forEachIndexed { index, category ->
            // The ORDER key is the declaration's own index, zero-padded so it
            // still sorts as a string beside the host's folder orders.
            val shelf = AppStoreHost.Shelf(
                category.optString("label", category.getString("id")), "%03d".format(index))
            val members = category.optJSONArray("members")
            for (i in 0 until (members?.length() ?: 0))
                members?.optString(i)?.takeIf { it.isNotEmpty() }?.let { filed[it] = shelf }
        }
        filed
    }

    /** The shelf a row is filed on: its declared lib category, else its declared app
     *  category (`app_categories`, DERIVED by regen.sh from the SuperApp's Cloud > Apps
     *  tile groups: same titles, same order, each app in the group that opens it, the rest
     *  in a declared "Other"). ONE lookup, so the heading drawn over a run and the key that
     *  orders it cannot read different answers. The launcher's classification is NOT
     *  consulted: that was a second category assignment, and it filed apps elsewhere. */
    private fun shelfOf(app: Fleet.App): AppStoreHost.Shelf? = libShelves[app.id] ?: appShelves[app.id]

    /** Position of an app inside its category = its tile's position on the SuperApp page. */
    private val appRank: Map<String, Int> by lazy {
        val rank = HashMap<String, Int>()
        fleetObjects("app_categories").forEach { category ->
            val members = category.optJSONArray("members")
            for (i in 0 until (members?.length() ?: 0)) members?.optString(i)?.takeIf { it.isNotEmpty() }?.let { rank[it] = i }
        }
        rank
    }

    private val appShelves: Map<String, AppStoreHost.Shelf> by lazy {
        val filed = HashMap<String, AppStoreHost.Shelf>()
        fleetObjects("app_categories").forEachIndexed { index, category ->
            val shelf = AppStoreHost.Shelf(
                category.optString("label", category.getString("id")), "%03d".format(index))
            val members = category.optJSONArray("members")
            for (i in 0 until (members?.length() ?: 0))
                members?.optString(i)?.takeIf { it.isNotEmpty() }?.let { filed[it] = shelf }
        }
        filed
    }

    /** The heading a row is drawn under: its ML application (#405) when its
     *  name declares one, else its shelf, else none. */
    private fun headingOf(app: Fleet.App): String? =
        applicationOf(app.id)?.replaceFirstChar { it.uppercase() } ?: shelfOf(app)?.heading

    // Tabs are a VIEW over the fleet: one per group data/regen.sh declares, in
    // its declared order, holding the members it lists and skipping a group with
    // no rows - never a hardcoded list here. Sorted at this single point rather
    // than at each call site: the rows, the detail pane and the copy dump all
    // read these lists, so ordering here orders the whole page. Heading first
    // (ML application, else the shelf's declared order; unshelved rows last),
    // then display name (#334), so each heading is one contiguous run and
    // the list can head it with a single pass and no regrouping.
    private val tabs by lazy {
        val everyRow = (fleet + references).associateBy { it.id }
        fleetObjects("groups").map { group ->
            val members = group.optJSONArray("members")
            val rows = (0 until (members?.length() ?: 0))
                .mapNotNull { index -> members?.optString(index)?.let(everyRow::get) }
            Tab(group.getString("id"), group.optString("label", group.getString("id")), group.optString("blurb"),
                rows.sortedWith(compareBy(
                    { applicationOf(it.id) ?: shelfOf(it)?.order ?: UNSHELVED },
                    { appRank[it.id] ?: 0 },
                    { it.label.lowercase() })))
        }.filter { it.rows.isNotEmpty() }
    }

    private val main = Handler(Looper.getMainLooper())

    // ── the page's state ──────────────────────────────────────────────────────
    // Last known state per app, kept so the filter chips can re-slice the list
    // WITHOUT re-hitting the network - re-checking 24 libs to hide 21 of them
    // would make filtering slower than scrolling.
    private val states = mutableStateMapOf<String, Fleet.State>()
    /** #774 the cache stage per app: StoreStages owns the logic; this only draws. */
    private val stages = mutableStateMapOf<String, StoreStages.Stage>()
    /** Apps whose network check is in flight (their meta line says "checking…"). */
    private val checking = mutableStateMapOf<String, Boolean>()
    private val expanded = mutableStateMapOf<String, Boolean>()
    private var tab by mutableIntStateOf(0)
    private var filter by mutableIntStateOf(0)
    /** Bumped when the page comes back to the front: the bar re-reads the device. */
    private var resume by mutableIntStateOf(0)
    /** The Details sheets, newest on top (a lib's own Details opens over its app's). */
    private val details = mutableStateListOf<Pair<Fleet.App, Fleet.State?>>()
    /** A row to scroll to (opened from the progress line). */
    private var scrollTarget by mutableStateOf<String?>(null)
    private var downgrade by mutableStateOf<Downgrade?>(null)
    private var stopNoChannel by mutableStateOf<Fleet.App?>(null)
    @Volatile private var composed = false
    private val rowTops = HashMap<String, Float>()
    private var columnTop = 0f

    /** #785 the live progress row: a tap on it opens the app's row. */
    private val progress by lazy { StoreProgressPanel(fleet) { openDetail(it) } }

    // amber, green, grey, red, orange, blue
    private val cUp = 0xFF48BB78.toInt(); private val cUpd = 0xFFED8936.toInt()
    private val cMiss = 0xFF63B3ED.toInt(); private val cBlk = 0xFFF56565.toInt()
    private val cErr = 0xFFECC94B.toInt(); private val cDim = Ink.DIM

    override fun palette(): KitPalette = StoreSearchPage.palette(requireContext())

    // #878 who uses which lib: data/lib-consumers.json (its `group` names the tab that carries the
    // two sections), generated by data/regen.sh from every app's build.json and manifests
    // (cloud-android-lib-consumers.py). Nothing here knows a lib or an app by name.
    private val libConsumers by lazy {
        runCatching { JSONObject(String(Base64.decode(BuildConfig.LIB_CONSUMERS_B64, Base64.DEFAULT))) }
            .getOrElse { JSONObject() }
    }

    /** The question the downgrade gate asks the person, and the way to answer it. */
    private class Downgrade(val app: Fleet.App, val candidate: Long, val installed: Long, val answer: (Boolean) -> Unit)

    /** #812 Opening the Store is reading the badge: it clears. A later chain
     *  that still finds updates pending posts it again. */
    override fun onResume() {
        super.onResume()
        resume++
        context?.let { c -> runCatching { StoreAuto.onPending(c.applicationContext, 0) } }
        // #858 back in front: resolve handed-over installs and show a prompt
        // that is still pending again, then repaint the rows.
        context?.applicationContext?.let { c ->
            thread(name = "store-install-watch") {
                // Repaint only when a handover was resolved: the first resume
                // must not jump ahead of #857's updates-first refresh.
                if (runCatching { StoreInstallWatch.onForeground(c, fleet) }.getOrDefault(false))
                    main.post { if (isAdded && composed) checkAll(c, current()) }
            }
        }
    }

    /**
     * THE DOWNGRADE GATE'S UI, and the only place that shows one.
     *
     * Called on [Fleet.commit]'s caller thread — always a background
     * `fleet-install-*`/`fleet-update-all` thread here, never main. Posts the
     * confirmation to the main thread and BLOCKS this one on a latch until
     * the human answers, because [Fleet.DowngradePolicy.allowDowngrade] is a
     * synchronous question: [Fleet.commit] cannot proceed past it either way
     * without an answer.
     *
     * FAIL CLOSED. If the page is gone (the app was backgrounded or
     * killed mid-install) there is nothing to show and nobody to ask, so this
     * refuses rather than guessing — the same "no answer means no" the
     * unconditional default already enforces for every other caller.
     */
    private fun confirmDowngrade(app: Fleet.App, candidateCode: Long, installedCode: Long): Boolean {
        if (!isAdded) return false
        val latch = java.util.concurrent.CountDownLatch(1)
        // No @Volatile needed: CountDownLatch's await()/countDown() pair
        // already establishes happens-before, same as any other latch-guarded
        // handoff between threads.
        var proceed = false
        main.post {
            if (!isAdded || !composed) { latch.countDown(); return@post }
            downgrade = Downgrade(app, candidateCode, installedCode) { yes ->
                proceed = yes; downgrade = null; latch.countDown()
            }
        }
        latch.await()
        return proceed
    }

    // ── the page ──────────────────────────────────────────────────────────────

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val scroll = rememberScrollState()
        DisposableEffect(Unit) {
            composed = true
            progress.attach()
            // THE ONLY PLACE Fleet.downgradePolicy IS EVER ASSIGNED.
            //
            // #496: Fleet.commit refuses a downgrade unconditionally by default —
            // every caller that never touches this var keeps that exact
            // behaviour (background workers, other constellation-hosting apps
            // that never open this page). Assigning it here, scoped to exactly
            // while this page is visible, is deliberate: a policy that shows a
            // dialog needs a live screen to show it on, and one that outlived
            // this page would fire from a LATER background pass with no
            // screen to draw on — see confirmDowngrade's own fail-closed note.
            Fleet.downgradePolicy = Fleet.DowngradePolicy { app, candidateCode, installedCode ->
                confirmDowngrade(app, candidateCode, installedCode)
            }
            onDispose {
                composed = false
                progress.detach()
                // Restore the unconditional-refuse default the moment this page is
                // no longer visible. Any downgrade a background pass hits after this
                // must be refused, not asked — there is nothing left to ask it on.
                Fleet.downgradePolicy = Fleet.DowngradePolicy { _, _, _ -> false }
                downgrade?.answer?.invoke(false)
            }
        }
        val shown = tabs.getOrNull(tab)
        // #857/#861 Wi-Fi + Auto-update: pending updates download on their own
        // max-priority queue (appstore-priority.json), fully async. The check
        // never waits on it: it runs now, always, and always settles.
        LaunchedEffect(tab) {
            shown?.rows?.takeIf { it.isNotEmpty() }?.let { rows ->
                StorePriority.startUpdatesAsync(ctx, fleet)
                checkAll(ctx, rows)
            }
        }
        LaunchedEffect(scrollTarget) {
            val id = scrollTarget ?: return@LaunchedEffect
            withFrameNanos { }   // the opened row has been laid out
            rowTops[id]?.let { scroll.animateScrollTo((it - columnTop).toInt().coerceAtLeast(0)) }
            scrollTarget = null
        }

        Column(Modifier.fillMaxWidth().verticalScroll(scroll).padding(sdp(StoreDensity.S12))
            .onGloballyPositioned { columnTop = it.positionInRoot().y }) {
            // No title line: the host's toolbar names the page ("Store") and its
            // tab strip names this subpage, both from their one declaration in
            // build.json. A third copy here is how the old "Constellation
            // AppStore" heading outlived the rename it described.
            StoreCaption((tabs.map { "${it.rows.size} ${it.label}" } + "superapp is the fleet manager").joinToString(" · "))
            TabBar()
            // Each blurb is data beside its group, so the caption naming the out-of-process engines moves
            // with the engines. (#896.3: Apps Mesh left this page for Access, so every tab is a group.)
            if (shown != null) FleetPage(ctx, shown.rows, shown.blurb, shown.id == libConsumers.optString("group"))
        }

        details.lastOrNull()?.let { (app, state) ->
            ApkDetailSheet.Sheet(app, state, { details.removeAt(details.lastIndex) }, { details.add(it to null) })
        }
        downgrade?.let { d ->
            AlertDialog(
                onDismissRequest = {},
                properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
                modifier = Modifier.testTag("store-downgrade"),
                title = { Text("Downgrade — ${d.app.label}") },
                text = {
                    Text("This moves the device BACKWARDS:\n\n" +
                        "  installed   versionCode ${d.installed}\n" +
                        "  available   versionCode ${d.candidate}\n\n" +
                        "The available build is OLDER than what is on this device. Proceeding " +
                        "installs it anyway. Refusing is the safe choice and is what happens by " +
                        "default — only continue if you mean to roll back.")
                },
                dismissButton = { com.diegonmarcos.superapp.uikit.KitTextButton(onClick = { d.answer(false) }) { Text("Cancel (recommended)") } },
                confirmButton = { com.diegonmarcos.superapp.uikit.KitTextButton(onClick = { d.answer(true) }) { Text("Install anyway") } },
            )
        }
        stopNoChannel?.let { app ->
            AlertDialog(
                onDismissRequest = { stopNoChannel = null },
                title = { Text(FleetActions.label(ctx, "stop") + " — " + app.label) },
                text = { Text(ctx.getString(R.string.store_fleet_stop_no_channel)) },
                confirmButton = {
                    com.diegonmarcos.superapp.uikit.KitTextButton(onClick = {
                        stopNoChannel = null
                        Fleet.installedId(ctx, app)?.let { pkg ->
                            runCatching { startActivity(PhoneAppActions.appInfo(pkg)) }
                                .onFailure { Toast.makeText(ctx, "${app.label}: ${it.message}", Toast.LENGTH_LONG).show() }
                        }
                    }) { Text(FleetActions.label(ctx, "app_settings")) }
                },
            )
        }
    }

    // ── tabs: one per declared group ──────────────────────────────────────────
    /**
     * A declared group is a TYPE OF APK: Apps, Libs, Lite-ML, Tiny-ML partition the fleet, and
     * picking one narrows what the page is showing you, so they are the segmented `tab` style (#671/#732).
     * The Commits and CI-CD feeds, Perms and Apps Mesh used to be a second line of page chips; they
     * are bottom-nav pages now (#896 Feed, #896.3 Access), so this page is one line.
     *
     * The strip itself is [StoreTabs.Bar], the same builder Phone draws with. WHICH TABS EXIST is
     * not written here: it is the declared groups.
     */
    @Composable
    private fun TabBar() {
        val groupTab = rememberStoreControls().groupTab
        StoreTabs.Bar(listOf(tabs.map { StoreControls.Control(it.label, "", groupTab) }), tab) { index ->
            if (tab != index) { tab = index; filter = 0 }
        }
    }

    /** A fleet app's detail (from the progress row): its group's tab, the row expanded and
     *  scrolled into view. An app no tab holds has no row to open. */
    private fun openDetail(app: Fleet.App) {
        val target = tabs.indexOfFirst { t -> t.rows.any { it.id == app.id } }
        if (target < 0) return
        tab = target; filter = 0; expanded[app.id] = true
        scrollTarget = app.id
    }

    @Composable
    private fun FleetPage(ctx: Context, list: List<Fleet.App>, blurb: String, hasLibSections: Boolean) {
        StoreCaption(blurb)
        // The Store's one top bar (#565), shared with Phone Apps through [StoreBar]; only the three batch
        // verbs are this page's. Update all is `fleet`, which Fleet.parse built from `apps` alone:
        // reference rows are not in it, so the pass cannot fetch or fail on one.
        StoreBar.Bar(StoreBar.Verbs(
            checkAll = { checkAll(ctx) },
            installAll = { installMissing(ctx) },
            updateAll = { updateAll(ctx, "the whole fleet", fleet) },
            downloadAll = { downloadAll(ctx, fleet) },
        ), resume = resume)
        // Sits under the buttons that start the work and above the summary line that describes the
        // result: what the fleet is doing RIGHT NOW.
        progress.Panel()
        // The status area: declared vs installed and the updates' cached / to-download split, for the
        // whole fleet registry, from the checks already made. Re-read on every progress event.
        val snap = states.toMap()
        StoreTally.Area(listOf(progress.tick, snap, resume), { c -> tallyItems(c, snap) })
        if (list.isEmpty()) { StoreCaption("Nothing here yet."); return }
        // The Libs tab has two sections: the installable engines (this list, with
        // who binds each) and, below it, the libs that only compile in (info rows).
        if (hasLibSections) SectionHeading("Runtime engines — installable APKs")
        StoreRow.Summary(summaryOf(list))
        // Filter chips. With two dozen libs the answer to "too much scrolling" is
        // to stop scrolling: pick the slice you came for. Counts come from the
        // cached [states], so a chip is instant and never re-checks.
        // #793 the shared chip (StoreBar.Chip), the same one Apps Mesh filters with; the line is
        // StoreRow.Filters, the one the Phone page draws its state chips with.
        StoreRow.Filters(listOf("All", "⬆ Updates", "◯ Missing", "✓ Installed"), filter, { i -> if (filter != i) filter = i },
            tags = FILTER_TAGS)
        FleetList(ctx, list, hasLibSections)
        if (hasLibSections) BuildTimeLibs()
    }

    /** True when [app] belongs in the current filter. An app whose state has not
     *  landed yet only shows under "All" - guessing would flicker it in and out. */
    private fun inFilter(app: Fleet.App): Boolean = when (filter) {
        1 -> states[app.id] is Fleet.State.UpdateAvailable
        2 -> states[app.id] is Fleet.State.Missing
        3 -> states[app.id] is Fleet.State.Installed
        else -> true
    }

    @Composable
    private fun FleetList(ctx: Context, list: List<Fleet.App>, libSplit: Boolean) {
        val shown = list.filter { inFilter(it) }
        if (shown.isEmpty()) { StoreCaption("Nothing in this filter."); return }
        // One heading per run of rows sharing a heading. The list is already
        // sorted by heading, so a change of heading is the only place one can
        // belong. Unshelved rows sort last; once a list has had headings they
        // get their own "Other" so they do not read as part of the run above.
        var heading: String? = null
        // Don't mix ML libs with the others: on the Libs page the ML engines (the ml- rule
        // applicationOf already reads off the id) form their own run under their own header.
        val (ml, plain) = if (libSplit) shown.partition { applicationOf(it.id) != null } else emptyList<Fleet.App>() to shown
        for (app in plain + ml) {
            if (ml.isNotEmpty() && app === ml.first()) {
                SectionHeading("Machine-learning engines")
                heading = null
            }
            val here = headingOf(app) ?: if (heading != null) OTHER else null
            if (here != null && here != heading) StoreHeading(here, cUpd)
            heading = here
            FleetRow(ctx, app)
        }
    }

    /** The fleet registry as [StoreTally] items: PackageManager for installed, the checks' release sizes,
     *  the APK cache for what is already downloaded. Local reads only; called off the main thread. */
    private fun tallyItems(c: Context, snap: Map<String, Fleet.State>): List<StoreTally.Item> {
        val cache = StoreTally.cache(c)
        return fleet.map { app ->
            val id = Fleet.installedId(c, app)
            val pkg = id ?: app.pkg
            val s = snap[app.id]
            val release = when (s) {
                is Fleet.State.UpdateAvailable, is Fleet.State.Missing -> s.bytes.takeIf { it > 0 } ?: -1L
                else -> -1L
            }
            val update = s is Fleet.State.UpdateAvailable
            StoreTally.Item(
                installed = id != null,
                installedBytes = if (id != null) StoreTally.apkBytes(c, id) else -1L,
                releaseBytes = release,
                update = update,
                cachedBytes = if (update) StoreTally.cachedUpdate(cache, pkg, id?.let { StoreTally.installedCode(c, it) }) else -1L,
            )
        }
    }

    private fun summaryOf(list: List<Fleet.App>): String {
        val known = list.mapNotNull { states[it.id] }
        val upd = known.count { it is Fleet.State.UpdateAvailable }
        val miss = known.count { it is Fleet.State.Missing }
        val bytes = known.sumOf { it.bytes }
        return buildString {
            append("${list.size} total")
            if (known.size < list.size) append("  ·  ${list.size - known.size} checking")
            if (upd > 0) append("  ·  $upd update${if (upd == 1) "" else "s"}")
            if (miss > 0) append("  ·  $miss missing")
            if (bytes > 0) append("  ·  ${human(bytes)}")
        }
    }

    // ── one COLLAPSED row per app; the full card is one tap away ─────────────
    // Seven lines per entry x 24 libs was twelve screens of scrolling. The row
    // keeps what you scan by (state, name, version, size) and defers what you
    // only ever inspect (package, image, sha, links, permissions) into a detail
    // pane. Nothing is dropped - it moves behind the chevron.

    /** What one row draws, decided in one pure place from its state, its stage and whether a check is out. */
    private class RowUi(
        val dot: String, val dotColor: Int, val meta: String, val metaColor: Int, val full: String, val fullColor: Int,
        val quick: Pair<String, Int>?, val error: StoreRowError.Look?,
    )

    /**
     * One state -> dot, collapsed meta line, quick button, full status line, error area; then the
     * #774 cache stages over it: a row with an APK in the cache, a verb running, or a stage that stopped
     * says THAT — "cached (ready to install)", "downloading 41%", "install did not finish: cancelled" —
     * and only the verbs [StoreStages.Stage.actions] allows are live. The quick button is always the
     * NEXT stage, so one tap takes over from exactly where a stage (or the auto chain) stopped.
     */
    private fun rowUi(ctx: Context, app: Fleet.App, s: Fleet.State?, stg: StoreStages.Stage?, isChecking: Boolean): RowUi {
        var dot = "·"; var dotColor = cDim
        var meta = "checking…"; var metaColor = cDim
        var full = "checking…"; var fullColor = cDim
        var quick: Pair<String, Int>? = null
        var error: StoreRowError.Look? = null
        if (s != null) {
            val (glyph, color) = when (s) {
                is Fleet.State.Installed       -> "✓" to cUp
                is Fleet.State.UpdateAvailable -> "⬆" to cUpd
                is Fleet.State.Missing         -> "◯" to cMiss
                is Fleet.State.Blocked         -> "⛔" to cBlk
                is Fleet.State.Error           -> "⚠" to cErr
            }
            dot = glyph; dotColor = color

            // #631: ONE identity pattern, and neither line here composes it. Both
            // ask [FleetIdentity] — the collapsed row for the fields you scan by,
            // the expanded row for all three — so the version, the sha and the size
            // cannot come out in two shapes, and neither line can invent a field
            // this device could not source.
            fun identity(fields: Set<FleetIdentity.Field>) = FleetIdentity.of(app, s, fields)
            metaColor = color
            meta = listOf(when (s) {
                is Fleet.State.Installed       -> "installed"
                is Fleet.State.UpdateAvailable -> "update"
                is Fleet.State.Missing         -> "not installed"
                is Fleet.State.Blocked         -> if (app.id in catalogue) "reference · not installable" else "not published"
                is Fleet.State.Error           -> s.message
            }, if (s is Fleet.State.Blocked || s is Fleet.State.Error) "" else identity(FleetIdentity.SCAN))
                .filter { it.isNotEmpty() }.joinToString(FleetIdentity.SEP)

            fullColor = color
            val id = identity(FleetIdentity.FULL)
            val head = when (s) {
                is Fleet.State.Installed       -> "✓ up to date"
                is Fleet.State.UpdateAvailable -> "⬆ update available"
                is Fleet.State.Missing         -> "◯ not installed  ·  tap Install"
                is Fleet.State.Blocked         -> if (app.id in catalogue) "⛔ reference row — a third-party library with nothing to install" else "⛔ not published yet"
                is Fleet.State.Error           -> "⚠ ${s.message}"
            }
            full = if (s is Fleet.State.Blocked || s is Fleet.State.Error || id.isEmpty()) head
                   else head + FleetIdentity.SEP + id

            quick = when (s) {
                is Fleet.State.UpdateAvailable -> "⬆" to cUpd
                is Fleet.State.Missing         -> "⬇" to 0xFF2B6CB0.toInt()
                else                           -> null
            }
            error = StoreRowError.of(null, (s as? Fleet.State.Error)?.message)
        }
        if (stg != null) {
            val busy = stg.id == "downloading" || stg.id == "installing"
            if (busy || stg.cached != null || stg.failedAt != null) {
                val color = if (stg.failedAt != null) cErr else cUpd
                meta = stg.text; metaColor = color
                full = stg.text; fullColor = color
            }
            // #831 a stopped stage: ⚠ mark, short meta, the whole reason in the row's error area.
            if (stg.failedAt != null) error = StoreRowError.of(stg.text, null)
            val verb = stg.actions.firstOrNull()
            when {
                verb == null -> quick = null
                // Install from a cached APK, or Clear: say which. Install with
                // nothing cached is the whole chain (#784) — the state already
                // drew ⬆ / ⬇ for it, and the tap runs the chain.
                stg.cached != null || verb == StoreStages.CLEAR -> quick =
                    (if (verb == StoreStages.INSTALL && stg.failedAt == StoreStages.INSTALL)
                        StoreInstallWatch.load(ctx).retryLabel else FleetActions.label(ctx, verb)) to
                    (if (verb == StoreStages.INSTALL) 0xFF7C3AED.toInt() else 0xFF4A4A55.toInt())
            }
        }
        if (error != null) {
            dot = error.glyph; dotColor = cErr
            meta = error.meta; metaColor = cErr
        }
        if (isChecking) { meta = "checking…"; metaColor = cDim }
        return RowUi(dot, dotColor, meta, metaColor, full, fullColor, quick, error)
    }

    @Composable
    private fun FleetRow(ctx: Context, app: Fleet.App) {
        // THE Store row (StoreRow), the same card and head the Phone page draws.
        val s = states[app.id]
        val stg = stages[app.id]
        val ui = rowUi(ctx, app, s, stg, checking[app.id] == true)
        val open = expanded[app.id] == true
        StoreRow.Item(app.label, open, { expanded[app.id] = !open },
            Modifier.onGloballyPositioned { rowTops[app.id] = it.positionInRoot().y }, tag = "store-row:${app.id}",
            dot = ui.dot, dotColor = ui.dotColor, meta = ui.meta, metaColor = ui.metaColor,
            // The per-app action, on the collapsed row on purpose: updating ONE app is the common case, and
            // making it expand-then-tap would cost two taps for the thing people do most.
            quick = ui.quick?.let { (label, fill) -> StoreQuick(label, fill) { next(ctx, app) } },
            headTag = "store-head:${app.id}", metaTag = "store-meta:${app.id}", dotTag = "store-dot:${app.id}",
            below = {
                ui.error?.let { ErrorArea(ctx, app, it) }
                MigrationArea(ctx, app, stg)
            }) { FleetDetail(ctx, app, ui, stg) }
    }

    /**
     * SharedUidMigration: the one-time card for an app whose update changes its Linux uid.
     * Read again whenever the row's stage is (every check / verb repaints it), so each
     * step lights up as the device moves: backup on disk, old build gone, new one in.
     */
    @Composable
    private fun MigrationArea(ctx: Context, app: Fleet.App, stg: StoreStages.Stage?) {
        val rec = remember(stg) { SharedUidMigration.record(ctx, app.pkg)?.takeIf { !it.done } } ?: return
        val plan = SharedUidMigration.plan(app.pkg) ?: return
        val path = plan.backupPath(app.pkg)
        val installed = remember(stg) { StoreStages.installedKey(ctx, app) }
        val backup = remember(stg) { SharedUidMigration.backupOf(path, rec.backupStartedAt) }
        val waits = remember(stg) {
            SharedUidMigration.waitsFor(plan.order, app.pkg) { p -> SharedUidMigration.stillOld(ctx, p) }
        }
        fun repaint() { val s2 = StoreStages.stage(ctx, app, states[app.id]); main.post { stages[app.id] = s2 } }
        androidx.compose.foundation.layout.Box(Modifier.padding(start = sdp(StoreDensity.S12), end = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S8))) {
            SharedUidMigrationCard(app.pkg, app.label, rec, path, installed, backup, waits,
                onBackup = { thread(name = "migrate-backup-${app.id}") {
                    SharedUidMigration.backupStarted(ctx, app.pkg)
                    val m = SharedUidMigration.startBackup(ctx, app.pkg, path)
                    main.post { Toast.makeText(ctx, "${app.label}: $m", Toast.LENGTH_LONG).show() }
                    Thread.sleep(3_000); repaint()
                } },
                onUninstall = { runCatching { startActivity(SharedUidMigration.uninstallIntent(app.pkg)) } },
                onInstall = { runStage(ctx, app, StoreStages.INSTALL) },
                onOpen = { openApp(ctx, app.pkg) },
                onDone = { SharedUidMigration.dismiss(ctx, app.pkg); thread { repaint() } },
                onMigrate = { thread(name = "migrate-auto-${app.id}") {
                    val owner = plan.order.firstOrNull() ?: app.pkg
                    val h = SharedUidMigration.Hooks(
                        installedNow = { StoreStages.installedKey(ctx, app) },
                        archiveOk = { SharedUidMigration.archiveExists(ctx, owner, path) },
                        waitsFor = { SharedUidMigration.waitsFor(plan.order, app.pkg) { p -> SharedUidMigration.stillOld(ctx, p) } },
                        startBackup = {
                            SharedUidMigration.backupStarted(ctx, app.pkg)
                            val m = SharedUidMigration.startBackup(ctx, app.pkg, path)
                            main.post { Toast.makeText(ctx, "${app.label}: $m", Toast.LENGTH_LONG).show() }
                        },
                        uninstall = { runCatching { startActivity(SharedUidMigration.uninstallIntent(app.pkg)) } },
                        install = { runStage(ctx, app, StoreStages.INSTALL) },
                        open = { openApp(ctx, app.pkg) },
                        sleep = { Thread.sleep(it) },
                        progress = { repaint() },
                    )
                    val out = SharedUidMigration.runAuto(rec, h)
                    main.post { Toast.makeText(ctx, if (out == SharedUidMigration.Outcome.DONE) "${app.label}: migrated, restore in the app" else "${app.label}: migration timed out, use the steps", Toast.LENGTH_LONG).show() }
                } })
        }
    }

    /** #831 The row's error area: the WHOLE reason (folded when long, tap to
     *  expand) and the ways out — Retry (the quick button's own next stage, which
     *  the row hides on a failed one), APK↗ (the direct download in the browser,
     *  otherwise only behind the chevron) and, for a DNS failure, the DNS page. */
    @Composable
    private fun ErrorArea(ctx: Context, app: Fleet.App, look: StoreRowError.Look) {
        var folded by remember(app.id, look.error) { mutableStateOf(true) }
        Column(Modifier.padding(start = sdp(StoreDensity.S12), end = sdp(StoreDensity.S12), bottom = sdp(StoreDensity.S8))
            .testTag("store-error:${app.id}")) {
            Text(look.error + if (StoreRowError.foldable(look.error)) "  (tap to expand)" else "",
                color = cErr.ink(), fontSize = StoreDensity.T_META.sp,
                maxLines = if (folded) StoreRowError.FOLDED_LINES else Int.MAX_VALUE, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.tap { folded = !folded })
            androidx.compose.foundation.layout.Row(Modifier.padding(top = sdp(StoreDensity.S4))) {
                StoreBar.Button(StoreRowError.RETRY_BUTTON, cUpd, { next(ctx, app) }, tag = "store-error:retry:${app.id}")
                if (app.releaseUrl.isNotEmpty()) StoreBar.Button(StoreRowError.APK_BUTTON, Ink.GREY_FILL, {
                    runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(app.abiReleaseUrl.ifEmpty { app.releaseUrl }))) }
                }, tag = "store-error:apk:${app.id}")
                // DNS only for a DNS failure with a page to open.
                if (look.dnsButton && dnsPageIntent(ctx) != null)
                    StoreBar.Button(StoreRowError.DNS_BUTTON, Ink.GREY_FILL, { openDnsPage(ctx) }, tag = "store-error:dns:${app.id}")
            }
        }
    }

    /** The Intent that opens the host's DNS page, or null when there is none to
     *  open: [AppStoreHost.dnsPagePackage]'s launcher when the host names one
     *  (Cloud Store → SuperApp) and it is installed, else [AppStoreHost.launchActivity]. */
    private fun dnsPageIntent(ctx: Context): Intent? {
        if (AppStoreHost.dnsPageExtras.isEmpty()) return null
        val pkg = AppStoreHost.dnsPagePackage
        val base = if (pkg != null) ctx.packageManager.getLaunchIntentForPackage(pkg)
                   else AppStoreHost.launchActivity?.let { Intent(ctx, it) }
        return base?.apply {
            AppStoreHost.dnsPageExtras.forEach { (k, v) -> putExtra(k, v) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }

    private fun openDnsPage(ctx: Context) {
        val intent = dnsPageIntent(ctx) ?: return
        runCatching { startActivity(intent) }
    }

    /** Everything the old always-visible card carried, now behind the chevron. */
    @Composable
    private fun FleetDetail(ctx: Context, app: Fleet.App, ui: RowUi, stg: StoreStages.Stage?) {
        Column {
            // The chip must offer the SAME bytes the updater would install, so it
            // uses the per-ABI asset too — handing an x86_64 user the arm64 APK is
            // the manual version of the bug Fleet.App.abiReleaseUrl fixes.
            val links = listOfNotNull(
                ("APK↗" to app.abiReleaseUrl).takeIf { app.releaseUrl.isNotEmpty() },
                ("GH↗" to app.repoUrl).takeIf { app.repoUrl.isNotEmpty() },
                ("PKG↗" to app.ghcrPage).takeIf { app.ghcrPage.isNotEmpty() })
            if (links.isNotEmpty()) androidx.compose.foundation.layout.Row {
                for ((label, url) in links) Text(label, color = cMiss.ink(), fontSize = StoreDensity.T_CAPTION.sp,
                    modifier = Modifier.tap { runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
                        .padding(end = sdp(StoreDensity.S8), top = sdp(StoreDensity.S2), bottom = sdp(StoreDensity.S2)))
            }

            // A reference row has no package or image to show. What it has is what
            // the library is for, and nothing on it can be opened or removed.
            val reference = catalogue[app.id]
            if (reference != null) StoreCaption(reference.optString("description"))
            else Text(app.pkg + "  ·  " + app.image, color = cDim.ink(), fontSize = StoreDensity.T_CAPTION.sp, fontFamily = FontFamily.Monospace)
            runtimeCallers(app.id)?.let { StoreCaption("Called by ${it.size} at runtime (bound over IPC): ${it.joinToString(", ")}") }

            Text(ui.full, color = ui.fullColor.ink(), fontSize = StoreDensity.T_META.sp,
                modifier = Modifier.testTag("store-full:${app.id}").padding(top = sdp(StoreDensity.S2), bottom = sdp(StoreDensity.S4)))
            if (reference != null) return

            // Order and captions are appstore-fleet-actions.json; this only says
            // what each declared id DOES. Direct install lives on the Details
            // sheet now (still one tap from here), and Stop sits before Uninstall.
            // Laid out by StoreRow.Actions, the Phone page's own button lines.
            StoreRow.Actions(FleetActions.row(ctx).mapNotNull { rowAction(ctx, app, it, stg) })
        }
    }

    // #878 who uses which lib
    private fun appNames(a: JSONArray?): List<String> = (0 until (a?.length() ?: 0)).map {
        a!!.getString(it).substringAfterLast('/').replace(Regex("^a[a-z]_"), "")
    }
    /** The apps that bind engine row [id] over IPC, or null when the data names none. */
    private fun runtimeCallers(id: String): List<String>? =
        appNames(libConsumers.optJSONObject("runtime")?.optJSONArray(id)).takeIf { it.isNotEmpty() }

    /** Section 2 of the Libs tab: every shared lib that is not an engine. Not installable,
     *  so an info row each: name, what it is, the apps that compile it in. */
    @Composable
    private fun BuildTimeLibs() {
        val libs = libConsumers.optJSONArray("build_time") ?: return
        SectionHeading("Build-time shared libs — compiled in, nothing to install")
        StoreCaption("${libs.length()} libs. Each is compiled into the apps listed, so it ships inside their APKs.")
        // Don't mix ML libs with the others: the generator flags each lib (`ml`, the ml- id rule)
        // and the two kinds are drawn as separate runs under their own headers.
        val all = (0 until libs.length()).map { libs.getJSONObject(it) }
        for ((title, group) in listOf("Shared libs" to all.filter { !it.optBoolean("ml") },
                                      "Machine-learning libs" to all.filter { it.optBoolean("ml") })) {
            if (group.isEmpty()) continue
            GroupHeader("lib_group_$title", title, group.size) { for (lib in group) LibCard(lib) }
        }
    }

    @Composable
    private fun LibCard(lib: JSONObject) {
        val compiledBy = appNames(lib.optJSONArray("compiled_by"))
        val engines = lib.optJSONArray("engines")?.let { e -> (0 until e.length()).map { e.getString(it) } }.orEmpty()
        var folded by remember(lib.getString("id")) { mutableStateOf(true) }
        Column(Modifier.fillMaxWidth().padding(vertical = sdp(StoreDensity.S2)).background(Ink.CARD.ink())
            .padding(horizontal = sdp(StoreDensity.S12), vertical = sdp(StoreDensity.S8))) {
            Text(lib.getString("id"), color = Ink.WHITE.ink(), fontSize = StoreDensity.T_TITLE.sp, fontWeight = FontWeight.Bold)
            StoreCaption(lib.optString("description"))
            Text(buildString {
                append(if (compiledBy.isEmpty()) "Compiled into no app directly" else "Compiled into ${compiledBy.size}: ${compiledBy.joinToString(", ")}")
                if (engines.isNotEmpty()) append("  ·  inside ${if (engines.size > 3) "${engines.size} engine APKs" else engines.joinToString(", ")}")
                lib.optString("primary").takeIf { it.isNotEmpty() }
                    ?.let { append("  ·  primary: ${appNames(JSONArray().put(it)).first()}") }
            }, color = cMiss.ink(), fontSize = StoreDensity.T_CAPTION.sp, maxLines = if (folded) 2 else Int.MAX_VALUE,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.tap { folded = !folded })
        }
    }

    /** A section or group separator: larger than a row title, with room above and below, so the
     *  headers stay prominent while the rows under them stay compact. */
    @Composable
    private fun SectionHeading(t: String) {
        Text(t, color = Ink.WHITE.ink(), fontSize = (StoreDensity.T_TITLE * 1.4f).sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(top = sdp(StoreDensity.S12 * 2), bottom = sdp(StoreDensity.S8)))
    }

    /** Each lib group's collapsed state, per user, in this app's prefs (keyed by the group's title). */
    private val groupPrefs by lazy { requireContext().getSharedPreferences("store_lib_groups", Context.MODE_PRIVATE) }
    private fun isCollapsed(key: String) = runCatching { groupPrefs.getBoolean(key, false) }.getOrDefault(false)
    private fun setCollapsed(key: String, v: Boolean) { runCatching { groupPrefs.edit().putBoolean(key, v).apply() } }

    /** A collapsible group header: chevron, title and count. Tapping toggles [content] and remembers it. */
    @Composable
    private fun GroupHeader(key: String, title: String, count: Int, content: @Composable () -> Unit) {
        var closed by remember(key) { mutableStateOf(isCollapsed(key)) }
        Text("${if (closed) "›" else "⌄"}  $title ($count)", color = cUpd.ink(), fontSize = (StoreDensity.T_TITLE * 1.25f).sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("store-group:$key").tap { closed = !closed; setCollapsed(key, closed) }
                .padding(top = sdp(StoreDensity.S12 * 2), bottom = sdp(StoreDensity.S8)))
        if (!closed) content()
    }

    /** The rows of the visible tab. Perms draws no batch actions, so it has none. */
    private fun current(): List<Fleet.App> = tabs.getOrNull(tab)?.rows.orEmpty()

    // ── concurrent status — one thread per app, independent + non-blocking ───
    private fun checkAll(ctx: Context, list: List<Fleet.App> = current()) {
        // #804 a Store refresh is an auto-chain trigger: what it is about to
        // show as pending is what the chain downloads and installs (gated by
        // the toggle and Wi-Fi only in the worker, like every other trigger).
        ConstellationWorker.kick(ctx, StoreAuto.TRIGGER_STORE_REFRESH)
        for (app in list) {
            main.post { checking[app.id] = true }
            thread(name = "fleet-check-${app.id}") {
                val st = Fleet.status(ctx, app)
                val stg = StoreStages.stage(ctx, app, st)
                // A filtered-out app still has a state worth recording, and it has no row to post to.
                main.post { states[app.id] = st; stages[app.id] = stg; checking.remove(app.id) }
            }
        }
    }

    /** The one size format, owned by [FleetIdentity] (#631) so the summary
     *  line and the progress line cannot drift from the row's own size. */
    private fun human(b: Long): String = FleetIdentity.human(b)

    /** The quick button: whatever stage comes next for this row. */
    private fun next(ctx: Context, app: Fleet.App) =
        runStage(ctx, app, stages[app.id]?.actions?.firstOrNull() ?: StoreStages.DOWNLOAD)

    /**
     * Run one #774 stage verb off the main thread, repainting the row from
     * [StoreStages.stage] twice a second while it runs (download %), then once
     * from the network when it ends. Download only fetches; Install is the whole
     * chain (#784) — Download if nothing installable is cached, install, Clear.
     */
    private fun runStage(ctx: Context, app: Fleet.App, verb: String) {
        thread(name = "fleet-$verb-${app.id}") {
            val ticker = thread(name = "fleet-$verb-tick-${app.id}") {
                try {
                    while (true) {
                        Thread.sleep(500)
                        val s = StoreStages.stage(ctx, app)
                        main.post { stages[app.id] = s }
                    }
                } catch (e: InterruptedException) { }
            }
            val done = when (verb) {
                StoreStages.DOWNLOAD -> StoreStages.download(ctx, app)
                StoreStages.INSTALL -> StoreStages.install(ctx, app, states[app.id])
                else -> StoreStages.clear(ctx, app)
            }
            ticker.interrupt()
            if (done.failedAt != null)
                main.post { Toast.makeText(ctx, "${app.label}: ${done.text}", Toast.LENGTH_LONG).show() }
            val st = Fleet.status(ctx, app)
            val stg = StoreStages.stage(ctx, app, st)
            main.post { states[app.id] = st; stages[app.id] = stg }
        }
    }

    /** One declared row button. Null only for Install on an unpublished app,
     *  which has nothing to install. An id with no handler is still drawn and
     *  says so on tap — never a button that does nothing. */
    private fun rowAction(ctx: Context, app: Fleet.App, a: FleetActions.Action, stg: StoreStages.Stage?): StoreVerb? {
        val tag = "store-verb:${a.id}:${app.id}"
        fun verb(onClick: () -> Unit) = StoreVerb(a.label, a.color, onClick, tag)
        return when (a.id) {
            // #496: installed vs. available, side by side, plus Direct install and
            // App settings — see ApkDetailSheet.
            "details" -> verb { details.add(app to states[app.id]) }
            "open" -> verb { openApp(ctx, Fleet.installedId(ctx, app) ?: app.pkg) }
            // #774 three stages, three buttons; which are live is StoreStages' call.
            "download" -> if (app.blocked) null else stageVerb(ctx, app, a, StoreStages.DOWNLOAD, stg, tag)
            "install" -> if (app.blocked) null else stageVerb(ctx, app, a, StoreStages.INSTALL, stg, tag)
            "clear" -> if (app.blocked) null else stageVerb(ctx, app, a, StoreStages.CLEAR, stg, tag)
            "stop" -> verb { stop(ctx, app) }
            "uninstall" -> verb {
                runCatching { Fleet.uninstall(ctx, Fleet.installedId(ctx, app) ?: app.pkg) }
                    .onFailure { Toast.makeText(ctx, "Uninstall: ${it.message}", Toast.LENGTH_LONG).show() }
            }
            else -> StoreVerb(a.label, 0xFF4A4A55.toInt(), {
                Toast.makeText(ctx, "'${a.id}' is declared in ${FleetActions.ASSET} but this build has no handler for it",
                    Toast.LENGTH_LONG).show()
            }, tag)
        }
    }

    /** A stage button: live only while no verb runs and the stage allows it; otherwise dimmed, in its colour. */
    private fun stageVerb(ctx: Context, app: Fleet.App, a: FleetActions.Action, verb: String, stg: StoreStages.Stage?, tag: String): StoreVerb {
        val busy = stg != null && (stg.id == "downloading" || stg.id == "installing")
        val live = stg == null || (!busy && verb in stg.actions)
        return StoreVerb(a.label, a.color, { runStage(ctx, app, verb) }, tag, live = live, alpha = if (live) null else 0.35f)
    }

    /**
     * Force-stop through the shell channel ladder — the same door Phone Apps'
     * Stop uses ([PhoneAppActions.forceStop]). With no channel armed it says
     * so and offers App settings, where Android's own Force stop lives.
     */
    private fun stop(ctx: Context, app: Fleet.App) {
        val pkg = Fleet.installedId(ctx, app)
            ?: return Toast.makeText(ctx, "${app.label}: not installed — nothing to stop", Toast.LENGTH_SHORT).show()
        if (SelfStop.isSelf(ctx, pkg)) return SelfStop.stop(ctx, activity)
        thread(name = "fleet-stop-${app.id}") {
            val out = PhoneAppActions.forceStop(ctx, pkg)
            main.post {
                if (!isAdded) return@post
                when {
                    out == null -> stopNoChannel = app
                    out.contains("OK") -> Toast.makeText(ctx,
                        getString(R.string.store_phone_stopped, app.label), Toast.LENGTH_SHORT).show()
                    else -> Toast.makeText(ctx,
                        getString(R.string.store_phone_failed, app.label, out.trim()), Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // "Update" — only entries ALREADY installed that have a newer image.
    // [targets] is passed in rather than read from current(), so one button can
    // stay tab-scoped while another spans apps + libs.
    private fun updateAll(ctx: Context, what: String, targets: List<Fleet.App>) {
        // Update the HOST first, through libs:updater's own channel, which does
        // not read the fleet at all. That is what makes this button a repair
        // tool rather than one more thing that breaks along with the list: when
        // the baked fleet is empty — as it shipped after libs:appstore's fleet
        // path broke in the module move — every fleet-driven action is a no-op,
        // so the store cannot pull the very build that would repopulate it.
        // checkNow does not care whether the fleet parsed.
        Toast.makeText(ctx, "Updating $what (+ SuperApp)…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-update-all") {
            // #784 StoreStages.updateAll: every cached build newer than what is
            // installed goes in with NO network; online, the rest get the same
            // Download → Install → Clear chain a row's Install runs. Missing
            // libs are taken, missing apps are Install all's (Mode.AUTO's rule).
            // The report is per app — installed / skipped / needs download /
            // failed at <stage> — because a count of zero has meant "everything
            // is current" and "everything failed" alike before.
            val batch = StoreStages.updateAll(ctx, targets)
            main.post { report("Update all", batch) }
            // The HOST update goes LAST. It still runs unconditionally, so the
            // button stays the repair tool it was built to be when the baked
            // fleet is empty — an empty fleet just makes the batch above a
            // fast no-op. But running it FIRST meant a successful self-install
            // replaced the APK and Android killed the process, taking the
            // whole fleet batch with it. UpdateWorker already orders it this
            // way for exactly that reason.
            Updater.checkNow(ctx)
            checkAll(ctx)
        }
    }

    /**
     * #784 "Download all": every update (or missing entry) into the cache,
     * nothing installed — pre-fetch on Wi-Fi, Update all later, offline if need
     * be. What does not fit in the REAL free storage (less the declared
     * reserve, #812) is skipped rather than evicting the rest. #812 no dialog:
     * progress and the outcome are drawn in the bar under the buttons.
     */
    private fun downloadAll(ctx: Context, targets: List<Fleet.App>) {
        thread(name = "fleet-download-all") {
            val done = StoreStages.downloadAll(ctx, targets)
            main.post { report("Download all", done) }
            checkAll(ctx)
        }
    }

    /** #812 A batch's outcome, IN the page's progress bar — never a dialog.
     *  A failure already drawn there ([StoreStages.progress]) keeps the bar. */
    private fun report(title: String, b: StoreStages.Batch) {
        if (!isAdded) return
        progress.report(title, b)
    }

    // "Install all" — only apps not yet on the device.
    private fun installMissing(ctx: Context) {
        Toast.makeText(ctx, "Installing missing apps…", Toast.LENGTH_SHORT).show()
        thread(name = "fleet-install-all") {
            val n = Fleet.installAll(ctx, current(), Fleet.Mode.MISSING)
            main.post {
                Toast.makeText(ctx, if (n == 0) "All apps already installed" else "$n install(s) queued",
                    Toast.LENGTH_LONG).show()
            }
            checkAll(ctx)
        }
    }

    private fun openApp(ctx: Context, pkg: String) {
        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
        if (i != null) startActivity(i) else Toast.makeText(ctx, "Not installed", Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Sorts after every folder order and every application name. */
        const val UNSHELVED = "￿"
        const val OTHER = "Other"
        /** Test tags of the filter chips: All, Updates, Missing, Installed. */
        val FILTER_TAGS = listOf("store-filter:all", "store-filter:updates", "store-filter:missing", "store-filter:installed")
    }
}
