package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.settings.redrawOnGridColumns
import com.diegonmarcos.superapp.BuildConfig
import com.diegonmarcos.superapp.launcher.AppInstall
import com.diegonmarcos.superapp.launcher.AppLongPressMenu
import com.diegonmarcos.superapp.launcher.BackHandler
import com.diegonmarcos.superapp.search.SearchEntry
import com.diegonmarcos.superapp.search.SearchFrame
import com.diegonmarcos.superapp.search.SearchSession
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.SelfTile
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.ui.snack
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.datamanager.AppUsageProvider
import com.diegonmarcos.superapp.ui.AppIconCache
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.util.Base64
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import org.json.JSONArray
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Suite section's Phone tab — curated phone apps grouped under the
 * SAME titled sections used by Suite/Cloud (AI · Data Primary · Data
 * Apps · Tools Primary · Tools Dashboards · Configs). Source of truth
 * = build.json::sections[id=suite].phone_app_groups, baked into
 * BuildConfig.UI_SUITE_PHONE_GROUPS_B64 by app/build.gradle.
 *
 * Render shape mirrors GroupedTilesFragment (Cloud-side):
 *   • One subhead per group title.
 *   • ONE sideways-scrollable line of icon tiles below it, never two —
 *     the same HorizontalScrollView strip GroupedTilesFragment.tileRow
 *     uses, for the same reason it adopted one. the phone column count
 *     now sets how many tiles are visible at rest rather than where the
 *     line breaks, because there is no longer a line break. See
 *     [tileStrip].
 *   • A group with no declared entries at all is skipped, so the
 *     visual layout never has dead headers. A group whose apps merely
 *     are not INSTALLED still renders — see below.
 *
 * A CURATED ENTRY FOR A PACKAGE THAT IS NOT INSTALLED RENDERS AS A
 * PLACEHOLDER, and that is the opposite of what this comment used to
 * say. It read "Missing packages (uninstalled, or wrong package guess)
 * silently skipped", and it was accurate: resolve() returned null and
 * mapNotNull dropped the entry, so a tile the owner had deliberately
 * put in build.json was simply not on the page and nothing explained
 * its absence. Since 2026-09-10 a curated entry always draws a tile —
 * the real app when installed, otherwise an outlined glyph with a
 * "Not installed" caption whose tap starts the install (AppInstall).
 *
 * THE INVERSION IS SCOPED TO THE CURATED LIST. Everything else on this
 * page — All Apps, Smart Folders, Active Apps, Last Apps — is
 * ENUMERATED from what the device actually has, and a not-installed
 * placeholder in a list built by enumerating installed apps would be
 * incoherent. Those paths still go through resolve() and still show
 * only installed apps. The difference is not cosmetic: it is the
 * difference between a list somebody wrote and a list the device
 * reported.
 *
 * Compose since #773: one LazyColumn; every Quickmarks line is ONE sideways-scrolling LazyRow (#260); All Apps
 * and Smart Folders are produced after the first frame, Smart Folders off the main thread and collapsed at
 * birth (#261).
 */
class SuitePhoneAppsFragment : KitComposeFragment(), BackHandler {

    /** Phone ▸ Apps' search: the same shared SearchFrame/SearchSession Cloud ▸ Apps mounts. */
    private var search: SearchSession? = null

    override fun tryHandleBack(): Boolean = search?.handleBack() == true

    override fun onDestroyView() {
        super.onDestroyView()
        search = null
    }

    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        redrawOnGridColumns(GridColumns.Kind.PHONE)
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    /** One curated entry as build.json declares it: the package, plus the
     *  name to show when that package is NOT installed. `label` is a
     *  fallback and nothing else — an installed app always draws its own
     *  PackageManager label, so the two cannot disagree on a real device.
     *  It exists because a placeholder has no launcher label to borrow. */
    internal data class Entry(val pkg: String, val label: String)
    internal data class Folder(val label: String, val entries: List<Entry>)
    internal data class Group(
        val title: String,
        val entries: List<Entry>,
        val folders: List<Folder>,
    )
    /** Resolved app payload — populated after LauncherApps lookup so
     *  both the plain-grid tile and the folder-dialog tile can launch
     *  via the same `pkg` + `label` + `icon` tuple. */
    internal data class AppInfo(
        val pkg: String,
        val label: String,
        /** Null ONLY when [installed] is false. A package the device does not
         *  have has no launcher icon to draw, which is the entire reason
         *  R.drawable.ic_app_not_installed exists. */
        val icon: Drawable?,
        val installed: Boolean = true,
    )

    @Composable
    override fun Content() {
        val session = remember { SearchSession(this, SearchEntry.PHONE_APPS_PAGE).also { search = it } }
        SearchFrame(session, "Search", Modifier.fillMaxSize()) { SuitePhoneAppsBody() }
    }

    companion object {
        fun newInstance() = SuitePhoneAppsFragment()

        const val TAG_LIST = "suitephone:list"
        const val TAG_SMART_HEADER = "suitephone:smart:header"
        const val TAG_SMART_STATUS = "suitephone:smart:status"
        fun groupTag(title: String) = "suitephone:group:$title"
        fun strip(title: String) = "suitephone:strip:$title"
        fun tileTag(pkg: String) = "suitephone:tile:$pkg"
        fun folderTileTag(label: String) = "suitephone:foldertile:$label"
        const val TAG_NONE_DECLARED = "suitephone:none-declared"

        internal fun parseGroups(): List<Group> = runCatching {
            val json = String(Base64.decode(BuildConfig.UI_SUITE_PHONE_GROUPS_B64, Base64.DEFAULT))
            val arr = JSONArray(json)
            // Plain mutable list + for-loop because `continue` inside an
            // inline-lambda body (buildList { ... }) is an experimental
            // Kotlin feature gated behind a compiler flag — using a
            // regular for-loop sidesteps the gate entirely.
            val out = mutableListOf<Group>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val title = o.optString("title")
                if (title.isBlank()) continue
                val entryList = parseEntries(o.optJSONArray("packages"))
                // Optional `folders` array — One-UI-style nested folders
                // inside the group. Each folder = { label, packages[] }.
                // Absent or empty → group renders as a flat grid (legacy
                // behaviour, no schema break).
                val folderList = mutableListOf<Folder>()
                val foldersArr = o.optJSONArray("folders")
                if (foldersArr != null) {
                    for (k in 0 until foldersArr.length()) {
                        val fo = foldersArr.optJSONObject(k) ?: continue
                        val fLabel = fo.optString("label")
                        if (fLabel.isBlank()) continue
                        val fEntries = parseEntries(fo.optJSONArray("packages"))
                        if (fEntries.isNotEmpty()) folderList.add(Folder(fLabel, fEntries))
                    }
                }
                out.add(Group(title, entryList, folderList))
            }
            out
        }.getOrDefault(emptyList())

        /** One `packages` array → [Entry] list. An element is either a bare
         *  package string (the shape every entry had before 2026-09-10) or an
         *  object {"pkg", "label"}. Both forms stay valid on purpose: the label
         *  is only needed where a placeholder might be drawn, so requiring it
         *  everywhere would be churn for no behaviour. */
        internal fun parseEntries(arr: JSONArray?): List<Entry> {
            if (arr == null) return emptyList()
            val out = mutableListOf<Entry>()
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i)
                val pkg = if (obj != null) obj.optString("pkg").trim() else arr.optString(i).trim()
                if (pkg.isBlank()) continue
                val declared = if (obj != null) obj.optString("label").trim() else ""
                out.add(Entry(pkg, if (declared.isNotBlank()) declared else derivedLabel(pkg)))
            }
            return out
        }

        /** Label of LAST RESORT for a placeholder whose entry declared none:
         *  the final segment of the package id, capitalised
         *  ("com.foxdebug.acode" → "Acode").
         *
         *  This is deliberately dumb and it is not a naming scheme. It gets
         *  "ch.protonmail.android" wrong ("Android"), and the fix for that is to
         *  declare `label` on the entry, not to grow a table of segments to
         *  ignore here — the names live in build.json, which is where the rest of
         *  this page's data lives. Every entry shipped on 2026-09-10 declares
         *  one; this only catches an entry somebody adds later without. */
        internal fun derivedLabel(pkg: String): String {
            val tail = pkg.substringAfterLast('.')
            if (tail.isEmpty()) return pkg
            return tail.replaceFirstChar { it.uppercase() }
        }
    }
}

// ───────────────────────────── the page ─────────────────────────────

/**
 * The Suite ▸ Phone body, shared by the fragment and the Home swipe sheet's Phone tab.
 *
 * WHAT HAPPENS AFTER THE INSTALL FINISHES. Without the receiver the placeholder stayed a placeholder until the
 * user left the tab and came back — the install worked and the page disagreed, which reads exactly like the install
 * having failed. PACKAGE_ADDED / PACKAGE_REMOVED arrive from the platform when any package appears or goes away; an
 * install turns a placeholder into the real app, and an uninstall turns a real app back into a placeholder.
 *
 * PhoneAppsFragment.invalidateCache() FIRST, and that ordering is the whole trick: the tiles are drawn from a warm
 * snapshot taken at launch; rebuilding without dropping it would redraw the same stale list. The receiver lives
 * exactly as long as the composition, so a page nobody is looking at does not rebuild itself.
 */
@Composable
internal fun SuitePhoneAppsBody() {
    val ctx = LocalContext.current
    var epoch by remember { mutableIntStateOf(0) }
    androidx.compose.runtime.DisposableEffect(Unit) {
        val packageChanged = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                PhoneAppsFragment.invalidateCache()
                epoch++
            }
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        // NOT_EXPORTED because these are protected system broadcasts — nothing else may deliver them, and from
        // API 34 a runtime receiver has to say which it is or the registration throws.
        ContextCompat.registerReceiver(ctx, packageChanged, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        onDispose { runCatching { ctx.unregisterReceiver(packageChanged) } }
    }
    SuitePhonePage(epoch) { epoch++ }
}

/** What the lazy Smart Folders section can be: still working, done, empty, or failed. */
private sealed interface SmartState {
    object Loading : SmartState
    class Done(val folders: List<PhoneAppsFragment.SmartRendered>) : SmartState
    object Failed : SmartState
}

/** Width of one tile inside a [TileStrip]: derived from build.json::ui.phone_grid_columns, not a new
 *  constant, so a strip shows exactly as many apps at rest as the wrapped grid used to fit on a line. */
internal fun cellWidth(ctx: Context): Dp {
    val columns = GridColumns.phone(ctx)
    val d = ctx.resources.displayMetrics.density
    // The page carries 8 dp of padding on each side.
    val usable = ctx.resources.displayMetrics.widthPixels - 2 * (8 * d).toInt()
    return (usable / columns / d).dp.coerceAtLeast(48.dp)
}

private typealias Entry = SuitePhoneAppsFragment.Entry
private typealias AppInfo = SuitePhoneAppsFragment.AppInfo

@Composable
private fun SuitePhonePage(epoch: Int, refresh: () -> Unit) {
    val ctx = LocalContext.current
    // Phone is the THIRD-PARTY half of the launcher. Everything the constellation already offers a way into
    // lives one tab over in Cloud ▸ Apps, so the ENUMERATED sections drop it. Quickmarks is a curated list and
    // is left alone on purpose.
    val ourApps = remember(epoch) { Sections.constellationPackages(ctx.packageName) }
    // READ THE WARM CACHE, DO NOT RE-ENUMERATE: PhoneAppsFragment.warmUp already enumerated launcher activities and
    // decoded icons on a background thread at launch.
    val byPkg = remember(epoch) { PhoneAppsFragment.snapshot(ctx).associateBy { it.packageName } }
    // Resolve [pkg] to an installed app, or null. The warm cache is a LOOKUP TABLE here, not the guest list: it
    // drops this app's own package and applies the launcher-profile whitelist, and a Quickmark legitimately names
    // both, so a direct PackageManager resolve backs it up.
    fun resolve(pkg: String): AppInfo? {
        byPkg[pkg]?.let { app ->
            val icon = app.icon ?: AppIconCache.load(ctx, pkg)
            if (icon != null) return AppInfo(pkg = pkg, label = app.label, icon = icon)
        }
        val pm = ctx.packageManager
        return runCatching {
            val ai = pm.getApplicationInfo(pkg, 0)
            AppInfo(pkg = pkg, label = pm.getApplicationLabel(ai).toString(),
                icon = AppIconCache.load(ctx, pkg) ?: error("no icon for $pkg"))
        }.getOrNull()
    }
    // THE ONE PLACE THE MISSING-PACKAGE FILTER IS LIFTED, and only for curated entries: the owner wrote this list, so
    // the answer to "not installed" is a placeholder offering to fix that, not silence. curated() cannot return null,
    // which makes the guarantee structural rather than a convention.
    fun curated(entry: Entry): AppInfo =
        resolve(entry.pkg)
            ?: AppInfo(entry.pkg, entry.label, icon = null, installed = false)
    val groups = remember(epoch) { SuitePhoneAppsFragment.parseGroups() }
    var open by remember { mutableStateOf<SuiteOpen?>(null) }
    var phoneOpen by remember { mutableStateOf<OpenFolder?>(null) }
    // Below the fold: produced AFTER the first frame — All Apps is a lot of view construction, and Smart Folders
    // is about a thousand blocking package-manager IPCs (#261), so it gets a background thread.
    val all by produceState<AllApps?>(null, epoch) { value = withContext(Dispatchers.Default) { allApps(ctx, exclude = ourApps) } }
    val smart by produceState<SmartState>(SmartState.Loading, epoch) {
        value = PhoneAppsFragment.loadSmartFolders(ctx, ourApps)?.let { SmartState.Done(it) } ?: SmartState.Failed
    }
    // COLLAPSED AT BIRTH — the whole point of #261: the page is finished and interactive without the section.
    var expanded by remember { mutableStateOf(false) }
    var userChose by remember { mutableStateOf(false) }
    val smartDone = (smart as? SmartState.Done)?.folders
    // Auto-expand when the result arrives with content — unless the user has already chosen: "expand when
    // ready" is not "expand no matter what they just did".
    androidx.compose.runtime.LaunchedEffect(smart) { if (smartDone?.isNotEmpty() == true && !userChose) expanded = true }
    val note = when {
        smart is SmartState.Loading -> "loading…"
        smart is SmartState.Failed -> "unavailable"
        smartDone.isNullOrEmpty() -> "none"
        else -> ""
    }

    LazyColumn(Modifier.fillMaxSize().testTag(SuitePhoneAppsFragment.TAG_LIST),
        contentPadding = PaddingValues(start = 8.dp, top = 8.dp, end = 8.dp, bottom = 96.dp)) {
        item(key = "h:quick") { PhoneSubhead("Quickmarks") }
        var anyRendered = false
        for (group in groups) {
            val packageTiles = group.entries.map(::curated)
            // A folder is skipped only when it DECLARES nothing.
            val folderTiles = group.folders.map { folder ->
                folder to folder.entries.map(::curated)
            }.filter { (_, contents) -> contents.isNotEmpty() }
            if (packageTiles.isEmpty() && folderTiles.isEmpty()) continue
            anyRendered = true
            item(key = "g:${group.title}") {
                Column {
                    PhoneSubhead(group.title)
                    // Packages first, then folder tiles, on ONE line — so the folder cards flow naturally as the
                    // next cell after the last app.
                    TileStrip(SuitePhoneAppsFragment.strip(group.title)) {
                        for (a in packageTiles) item(key = "t:${a.pkg}") { AppTile(a) }
                        for ((folder, contents) in folderTiles) item(key = "f:${folder.label}") {
                            FolderTile(folder.label, contents) { open = SuiteOpen(folder.label, contents) }
                        }
                    }
                }
            }
        }
        if (!anyRendered) item(key = "none") {
            // Reachable only when the curated data declares NOTHING — "not installed" no longer lands here.
            Text("No curated apps declared — add a group to build.json::sections[id=phone].phone_app_groups.",
                color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(vertical = 8.dp).testTag(SuitePhoneAppsFragment.TAG_NONE_DECLARED))
        }
        // ── Active Apps — apps that are STILL RUNNING (a foreground service, or in the foreground), not "recently
        //    used". AppUsageProvider.activeNow via libs:datamanager; hidden when the grant is missing or nothing
        //    launchable matches. Constellation packages filtered out.
        if (BuildConfig.UI_SUITE_ACTIVE_APPS_ENABLED) {
            usageSection(BuildConfig.UI_SUITE_ACTIVE_APPS_TITLE,
                AppUsageProvider.activeNow(ctx).asSequence().filter { it !in ourApps }.mapNotNull { resolve(it) }
                    .take(BuildConfig.UI_SUITE_ACTIVE_APPS_LIMIT.coerceAtLeast(1)).toList())
        }
        // ── Last Apps — the apps most recently OPENED (MOVE_TO_FOREGROUND events only), most recent first.
        if (BuildConfig.UI_SUITE_LAST_APPS_ENABLED) {
            usageSection(BuildConfig.UI_SUITE_LAST_APPS_TITLE,
                AppUsageProvider.lastOpened(ctx).asSequence().filter { it !in ourApps }.mapNotNull { resolve(it) }
                    .take(BuildConfig.UI_SUITE_LAST_APPS_LIMIT.coerceAtLeast(1)).toList())
        }
        // ── All Apps — every installed app grouped by purpose, the rendering PhoneAppsFragment uses standalone.
        item(key = "div:1") { PhoneSectionDivider() }
        all?.let { data ->
            item(key = "h:allapps") { PhoneSubhead("All Apps") }
            allAppsItems(ctx, data) { phoneOpen = it }
        }
        // ── Smart Folders — lazy: the header lands now, the contents arrive off a background thread.
        item(key = "div:2") { PhoneSectionDivider() }
        item(key = "smart:header") {
            val chevron = if (expanded) "▾" else "▸"
            val state = if (expanded) "expanded" else "collapsed"
            // The chevron is invisible to a screen reader, and so is the difference between a section that is
            // still working and one that came back empty. Both go in, in words.
            Text(if (note.isEmpty()) "Smart Folders  $chevron" else "Smart Folders  $chevron  $note",
                color = LocalKitPalette.current.textPrimary, style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.fillMaxWidth()
                    .clickable {
                        expanded = !expanded
                        // Recorded BEFORE any result can arrive, so an answer landing a moment later leaves
                        // their choice alone.
                        userChose = true
                    }
                    .padding(start = 4.dp, top = 12.dp, bottom = 4.dp)
                    .semantics { contentDescription = if (note.isEmpty()) "Smart Folders, $state" else "Smart Folders, $state, $note" }
                    .testTag(SuitePhoneAppsFragment.TAG_SMART_HEADER))
        }
        if (expanded) {
            when {
                smartDone?.isNotEmpty() == true -> smartFolderItems(ctx, smartDone) { phoneOpen = it }
                else -> item(key = "smart:status") {
                    Text(if (smart is SmartState.Failed) "Smart Folders could not be built. Refresh at the bottom of the page to try again."
                         else if (smart is SmartState.Loading) "Working in the background…"
                         else "No Smart Folder matches the apps on this device.",
                        color = LocalKitPalette.current.textSecondary, style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 8.dp).testTag(SuitePhoneAppsFragment.TAG_SMART_STATUS))
                }
            }
        }
        // Refresh belongs at the VERY BOTTOM.
        item(key = "refresh") { PhoneRefreshBar { PhoneAppsFragment.invalidateCache(); refresh() } }
    }
    open?.let { SuiteFolderSheet(it) { open = null } }
    phoneOpen?.let { PhoneFolderSheet(it) { phoneOpen = null } }
}

private class SuiteOpen(val label: String, val contents: List<AppInfo>)

/** A subheading plus ONE sideways-scrollable line of app tiles — the shape both Quickmarks usage sections (Active
 *  Apps, Last Apps) render. Draws nothing at all when [apps] is empty, so a missing usage-access grant leaves no
 *  orphan heading behind. THESE COUNT AS QUICKMARKS LINES TOO (#260). */
private fun LazyListScope.usageSection(title: String, apps: List<AppInfo>) {
    if (apps.isEmpty()) return
    item(key = "u:$title") {
        Column {
            PhoneSubhead(title)
            TileStrip(SuitePhoneAppsFragment.strip(title)) { for (a in apps) item(key = "t:${a.pkg}") { AppTile(a) } }
        }
    }
}

/**
 * ONE horizontally-scrollable line of tiles (#260): every row of apps in the Quickmarks area stays on a single line
 * and scrolls sideways when the tiles do not fit. A LazyRow lays its items out in one row by construction — there
 * is no chunking into rows, so the second line the old LinearLayout-per-chunk code drew cannot come back. The cells
 * are fixed-width ([cellWidth]), never weighted.
 */
@Composable
private fun TileStrip(tag: String, content: LazyListScope.() -> Unit) {
    LazyRow(Modifier.fillMaxWidth().testTag(tag), content = content)
}

/**
 * One icon tile. NOT-INSTALLED TREATMENT: the tile keeps its exact cell geometry so a placeholder does not reflow
 * the grid around it; what changes is the ink. The launcher icon is replaced by R.drawable.ic_app_not_installed (a
 * dashed outline holding a download arrow — an empty slot, not a broken icon), the app name drops to the palette's
 * secondary text colour, and a third line spells out "Not installed" in the accent colour. EVERY COLOUR COMES FROM
 * THE PALETTE roles, so every launcher theme stays legible.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppTile(app: AppInfo) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val p = LocalKitPalette.current
    val cell = cellWidth(ctx)
    val desc = if (app.installed) app.label else stringResource(R.string.phone_app_not_installed_tile_hint, app.label)
    // EXACTLY ONE CLICK TARGET IN THIS CELL: a tile in this launcher has already shipped firing twice per tap
    // (#195, 149 duplicate deliveries in a single trace) because two views in the same cell both dispatched.
    Column(
        // FIXED WIDTH, NOT A WEIGHT: this cell lives in a sideways-scrolling row, where a weight resolves to nothing.
        Modifier.width(cell)
            .combinedClickable(
                onClick = {
                    if (app.installed) {
                        if (!SelfTile.open(ctx, app.pkg)) runCatching {
                            val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                            if (intent != null) ctx.startActivity(intent)
                        }
                    } else {
                        // AppInstall always returns a message, started or refused, and it is always shown. A TAP THAT
                        // DOES NOTHING IS WORSE THAN A TILE THAT WAS NOT THERE.
                        view.snack(AppInstall.start(ctx, app.pkg, app.label).message)
                    }
                },
                // Long-press → stock options menu (shortcuts + App info + Uninstall). A package that is not
                // installed has no App-info screen and nothing to uninstall, so it has no long-press at all.
                onLongClick = if (app.installed) ({ AppLongPressMenu.show(ctx, app.pkg) }) else null,
            )
            .padding(6.dp)
            .semantics { contentDescription = desc }
            .testTag(SuitePhoneAppsFragment.tileTag(app.pkg)),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val sz = (GridColumns.iconPx((52 * ctx.resources.displayMetrics.density).toInt(),
            (cell.value * ctx.resources.displayMetrics.density).toInt(), (12 * ctx.resources.displayMetrics.density).toInt(),
            (24 * ctx.resources.displayMetrics.density).toInt()) / ctx.resources.displayMetrics.density).dp
        if (app.installed) AppIcon(app.icon, sz)
        else Icon(painterResource(R.drawable.ic_app_not_installed), contentDescription = null, tint = p.textSecondary, modifier = Modifier.size(sz))
        Text(app.label, color = if (app.installed) p.textPrimary else p.textSecondary, fontSize = 11.sp, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
        if (!app.installed) {
            Text(stringResource(R.string.phone_app_not_installed), color = p.accent, fontSize = 9.sp, maxLines = 1,
                overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** Folder tile rendered in-line with the package grid — same fixed cell as a single-app tile so it slots seamlessly.
 *  Shows a 2×2 preview of the first four members (a not-installed member shows the placeholder glyph rather than a
 *  blank slot, which would claim the folder has fewer than four apps); a tap opens the sheet with every app. */
@Composable
private fun FolderTile(label: String, contents: List<AppInfo>, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    val cell = 52.dp
    val mini = (cell - 16.dp) / 2
    Column(Modifier.width(cellWidth(ctx)).clickable(onClick = onOpen).padding(6.dp)
        .testTag(SuitePhoneAppsFragment.folderTileTag(label)),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(cell).clip(RoundedCornerShape(16.dp)).background(p.surface)
            .border(1.dp, p.hairline, RoundedCornerShape(16.dp)).padding(5.dp)) {
            Column {
                for (r in 0..1) Row {
                    for (c in 0..1) {
                        val member = contents.getOrNull(r * 2 + c)
                        Box(Modifier.size(mini).padding(1.dp)) {
                            when {
                                member == null -> Unit
                                member.installed -> AppIcon(member.icon, mini - 2.dp)
                                else -> Icon(painterResource(R.drawable.ic_app_not_installed), contentDescription = null,
                                    tint = p.textSecondary, modifier = Modifier.size(mini - 2.dp))
                            }
                        }
                    }
                }
            }
        }
        Text(label, color = p.textPrimary, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
    }
}

/** The modal showing every app inside a folder. A tap launches + dismisses; a not-installed member starts its install
 *  (dismissed BEFORE the snackbar is raised, because a snackbar behind a modal is a message nobody reads). */
@Composable
private fun SuiteFolderSheet(open: SuiteOpen, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    // Match the phone grid column count (default 6), not a hardcoded 5.
    val cols = GridColumns.phone(ctx)
    val iconDp = remember(cols) { expandedIconDp(ctx, cols) }
    FolderSheetFrame(open.label, onDismiss) {
        for (row in open.contents.chunked(cols)) {
            Row(Modifier.fillMaxWidth()) {
                for (app in row) {
                    val desc = if (app.installed) app.label else stringResource(R.string.phone_app_not_installed_tile_hint, app.label)
                    ExpandedAppTile(SuitePhoneAppsFragment.tileTag("sheet:${app.pkg}"),
                        if (app.installed) app.label else desc, app.icon, app.installed, cols, iconDp, desc, Modifier.weight(1f),
                        onClick = {
                            if (app.installed) {
                                if (!SelfTile.open(ctx, app.pkg)) runCatching {
                                    val intent = ctx.packageManager.getLaunchIntentForPackage(app.pkg)
                                    if (intent != null) ctx.startActivity(intent)
                                }
                                onDismiss()
                            } else {
                                onDismiss()
                                view.snack(AppInstall.start(ctx, app.pkg, app.label).message)
                            }
                        },
                        onLong = if (app.installed) ({ onDismiss(); AppLongPressMenu.show(ctx, app.pkg) }) else null)
                }
                for (k in row.size until cols) Spacer(Modifier.weight(1f))
            }
        }
    }
}
