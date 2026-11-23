package com.diegonmarcos.superapp.apps
import com.diegonmarcos.superapp.settings.GridColumns
import com.diegonmarcos.superapp.settings.redrawOnGridColumns
import com.diegonmarcos.superapp.R
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherProfilePrefs

import android.content.Context
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Phone tab of the Home Apps swipe-up sheet — Android-launcher-style
 * grouping of every installed (launchable) app into the folder
 * taxonomy declared in `build.json::ui.phone_folders`. Smart classifier
 * lives in [PhoneAppClassifier]; folder rendering follows the iOS-
 * glass-on-Samsung-grid spec the user pinned in
 * Screenshot_20260608_001142_One UI Home.jpg.
 *
 * Visual contract:
 *   • Grid of folder cards, 6 columns (from `phone_grid_columns`).
 *   • Each folder card: 2×2 mini-icon preview of its top apps inside a
 *     rounded glass-bg square, label beneath.
 *   • Tap a folder → modal dialog showing every app inside, 5 columns
 *     of full-size icons. Tap an app → launches via [LauncherApps].
 *
 * Compose since #773: one LazyColumn (mode toggle, the folder grids, the refresh bar) over the shared
 * pieces in PhoneAppsUi.kt. The caches, warm-up and smart-folder selection below are unchanged.
 */
class PhoneAppsFragment : KitComposeFragment() {

    /** A Smart Folder with the apps its rules selected. */
    internal data class SmartRendered(
        val spec: PhoneSmartFolders.SmartFolder,
        val apps: List<PhoneApp>,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        redrawOnGridColumns(GridColumns.Kind.PHONE)
    }

    override fun palette() = LauncherPalette.kit(requireContext())

    @Composable
    override fun Content() {
        val ctx = LocalContext.current
        val view = LocalView.current
        val p = LocalKitPalette.current
        // true = A–Z folders (alphabetic), false = category + smart folders.
        // In-memory only — resets to Categories each time the sheet opens.
        var alphaMode by remember { mutableStateOf(false) }
        // Bumped by the refresh affordance at the bottom: invalidates the app cache (picks up new
        // installs / uninstalls) and rebuilds the folder + smart-folder content in place.
        var epoch by remember { mutableIntStateOf(0) }
        var open by remember { mutableStateOf<OpenFolder?>(null) }
        val smart by produceState<List<SmartRendered>?>(null, epoch, alphaMode) {
            value = if (alphaMode) null else loadSmartFolders(ctx, emptySet())
        }
        // #1860 a pin accepted while the page shows appears at once: the grouping re-reads when the pin store changes.
        val pinVersion = com.diegonmarcos.superapp.launcher.pins.PinnedShortcutStore.changes.intValue
        val all = remember(epoch, alphaMode, pinVersion) { if (alphaMode) null else allApps(ctx) }
        val alpha = remember(epoch, alphaMode, pinVersion) { if (alphaMode) alphabetic(ctx) else null }
        // One LazyColumn per mode. Categories and Alphabetic are different lists, and flipping the mode inside ONE
        // LazyColumn instance sometimes left its previous measured layout in place (Robolectric run 39124977196: the
        // composition had alphaMode=true and the 26 folders built, the tree still showed the Categories layout, and a
        // second click did nothing), which made PhoneAppsComposeTest.P1 fail about one JVM in six. A keyed list starts
        // fresh (and at the top) whenever the mode flips; 24 fresh JVMs of the full suite went green with it.
        androidx.compose.runtime.key(alphaMode) {
            LazyColumn(Modifier.fillMaxSize().testTag(TAG_LIST), contentPadding = PaddingValues(8.dp, 8.dp, 8.dp, 96.dp)) {
                // Right-aligned minimalist icon toggle: folder (categories) vs sort-alpha. Active icon is
                // bright, inactive dim. A tap flips the mode.
                item(key = "toggle") {
                    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.End) {
                        ModeButton(R.drawable.ic_p_folder, !alphaMode, "Categories", TAG_MODE_CATEGORIES) { alphaMode = false }
                        ModeButton(R.drawable.ic_sort_alpha, alphaMode, "Alphabetic", TAG_MODE_ALPHA) { alphaMode = true }
                    }
                }
                if (alpha != null) {
                    folderGridItems(ctx, "alpha", alpha.first, alpha.second, GridColumns.phone(ctx), 60) { open = it }
                } else {
                    if (all != null) allAppsItems(ctx, all) { open = it }
                    smart?.takeIf { it.isNotEmpty() }?.let {
                        item(key = "smart:title") { PhoneSubhead("Smart Folders") }
                        smartFolderItems(ctx, it) { f -> open = f }
                    }
                }
                item(key = "refresh") { PhoneRefreshBar { invalidateCache(); epoch++ } }
            }
        }
        open?.let { PhoneFolderSheet(it) { open = null } }
    }

    @Composable
    private fun ModeButton(res: Int, active: Boolean, desc: String, tag: String, onPick: () -> Unit) {
        val p = LocalKitPalette.current
        val view = LocalView.current
        Icon(painterResource(res), contentDescription = desc,
            tint = if (active) p.textPrimary else p.textSecondary.copy(alpha = 0.5f),
            modifier = Modifier.clickable {
                Haptics.tap(view)
                if (!active) onPick()
            }.padding(6.dp).size(20.dp).testTag(tag))
    }

    companion object {
        fun newInstance() = PhoneAppsFragment()

        const val TAG_LIST = "phoneapps:list"
        const val TAG_MODE_CATEGORIES = "phoneapps:mode:categories"
        const val TAG_MODE_ALPHA = "phoneapps:mode:alpha"

        /**
         * A–Z (plus a trailing "#" bucket for non-letter labels) folders of every launchable app. All 26
         * letter folders are always shown — empty ones render as empty glass squares, per spec.
         */
        internal fun alphabetic(ctx: Context): Pair<List<PhoneFolders.Folder>, Map<String, List<PhoneApp>>> {
            val apps = snapshot(ctx) + com.diegonmarcos.superapp.launcher.pins.PinnedFolders.apps(ctx)
            val byLetter = LinkedHashMap<String, MutableList<PhoneApp>>()
            for (c in 'A'..'Z') byLetter[c.toString()] = mutableListOf()
            val hash = mutableListOf<PhoneApp>()
            for (app in apps) {
                val ch = app.label.trim().firstOrNull()?.uppercaseChar()
                if (ch != null && ch in 'A'..'Z') byLetter.getValue(ch.toString()).add(app) else hash.add(app)
            }
            val folders = mutableListOf<PhoneFolders.Folder>()
            val grouped = HashMap<String, List<PhoneApp>>()
            for ((letter, list) in byLetter) {
                folders.add(PhoneFolders.Folder("alpha:$letter", letter, letter, emptyList()))
                grouped["alpha:$letter"] = list.sortedBy { it.label.lowercase() }
            }
            if (hash.isNotEmpty()) {
                folders.add(PhoneFolders.Folder("alpha:#", "zz", "#", emptyList()))
                grouped["alpha:#"] = hash.sortedBy { it.label.lowercase() }
            }
            return folders to grouped
        }

        /** The folder taxonomy, through the process cache. */
        internal fun cachedFolders(): List<PhoneFolders.Folder> =
            sCachedFolders ?: PhoneFolders.loadFromBuildConfig().also { sCachedFolders = it }

        /**
         * The apps grouped by folder. The grouped cache is only reusable for the UNFILTERED set — and
         * must not be WRITTEN from a filtered one either, or the next caller that wants every app
         * silently gets somebody else's subset. A caller that filters pays one regroup.
         */
        internal fun groupedFor(apps: List<PhoneApp>, folders: List<PhoneFolders.Folder>, unfiltered: Boolean): Map<String, List<PhoneApp>> =
            if (unfiltered) sCachedGrouped ?: PhoneAppClassifier.groupByFolder(apps, folders).also { sCachedGrouped = it }
            else PhoneAppClassifier.groupByFolder(apps, folders)

        /**
         * The same Smart Folders, computed OFF the main thread: build.json declares fourteen smart folders and
         * four of them carry an install_source rule, which [PhoneSmartFolders.Rule.matches] answers with
         * PackageManager.getInstallSourceInfo ONCE PER APP (about a thousand synchronous binder round trips
         * on a phone with two hundred apps), and five more rank apps through UsageStatsManager,
         * NetworkStatsManager and the battery estimator. None of it touches a View.
         *
         * Returns the folders (possibly empty: the rules matched nothing on this device) or null when the
         * computation itself threw — a caller that cannot tell the three apart cannot stop a spinner.
         *
         * STRUCTURAL CANCELLATION: called from a produceState coroutine, so leaving the page cancels the
         * resumption and nothing downstream paints into a dead composition (the #194 crash class removed
         * rather than caught).
         */
        internal suspend fun loadSmartFolders(ctx: Context, exclude: Set<String>): List<SmartRendered>? {
            // Already computed once in this process: answer on the spot, so a rotation or a re-entry never
            // pays the thousand IPCs again.
            sCachedSmart[exclude]?.let { return it }
            // applicationContext for the background half: the probe can outlive the page that started it,
            // and holding that Activity for the duration is a leak worth not having.
            val appContext = ctx.applicationContext
            // Captured BEFORE the probe starts, checked before it publishes — see [sCacheGeneration].
            val generation = sCacheGeneration
            return withContext(Dispatchers.IO) {
                val result = runCatching { computeSmartFolders(appContext, exclude) }.getOrNull()
                // Published from INSIDE the IO block on purpose: cancellation stops the resumption, not
                // the block already running, so a page left mid-fetch still warms the cache.
                if (result != null && generation == sCacheGeneration) {
                    sCachedSmart = sCachedSmart + (exclude to result)
                }
                result
            }
        }

        /** Selection only — creates no Views, so it is safe on a background
         *  thread. Every expensive call named in [renderSmartFoldersAsync]'s
         *  documentation happens inside here. */
        internal fun computeSmartFolders(
            ctx: Context,
            exclude: Set<String>,
        ): List<SmartRendered> {
            val all  = sCachedApps ?: collectLaunchableAppsStatic(ctx).also { sCachedApps = it }
            // The master exclusion is applied PER FOLDER, not to the shared
            // master list up front. Computing it once here is not the same
            // as pre-filtering `all`: the filtered list below is the DEFAULT
            // every folder filters over, but a folder that opts out of the
            // exclusion (include_constellation) selects from the FULL list
            // instead, because its entire content IS the constellation's own
            // packages — Cloud Apps and Cloud Libs. `exclude` still keys the
            // cache (see [smartFoldersCached]), so nothing about the cache
            // contract changes; it is only the per-folder decision that moved
            // inside this computation.
            val excluded = if (exclude.isEmpty()) all else all.filter { it.packageName !in exclude }
            // Installed constellation LIBRARY packages. A lib APK ships no
            // launcher activity, so it is absent from `all`/`excluded` (both
            // derive from the launchable enumeration) — a folder whose rule
            // selects libs must therefore source from this set, not the
            // launchable list, or it always comes up empty (#474).
            val libSlots by lazy { PhoneSmartFolders.installedLibSlots(ctx) }
            return PhoneSmartFolders.loadFromBuildConfig().mapNotNull { sf ->
                val source = when {
                    // A fleet_kind=lib folder IS the installed libs — its
                    // membership comes from the fleet manifest ∩ installed
                    // packages, never from the launchable enumeration.
                    sf.selectsInstalledLibs -> libSlots
                    // The opt-in folder (Cloud Apps) selects over the FULL
                    // enumeration; every other folder keeps the excluded one.
                    sf.includeConstellation -> all
                    else -> excluded
                }
                val matches = sf.select(ctx, source)
                if (matches.isEmpty()) null else SmartRendered(sf, matches)
            }
        }

        /** [computeSmartFolders] through the process-level cache.
         *
         *  KEYED ON `exclude`, because the two callers do not pass the same
         *  set — the standalone Phone tab excludes nothing, the merged
         *  Suite→Phone page excludes the constellation's own packages. Storing
         *  one under the other's key is the subset leak sCachedGrouped already
         *  documents above; a key costs one line and cannot make that mistake. */
        internal fun smartFoldersCached(
            ctx: Context,
            exclude: Set<String>,
        ): List<SmartRendered> = sCachedSmart[exclude] ?: computeSmartFolders(ctx, exclude)
            .also { sCachedSmart = sCachedSmart + (exclude to it) }

        /** Smart-folder SELECTION cache accessor for the SYNCHRONOUS callers. */
        /** Process-level write-ahead buffer for the Phone tab data.
         *  Populated by [warmUp] from MainActivity.onCreate so the
         *  list + folder classification is ready BEFORE the user ever
         *  swipes up to Home Apps. If the user races the warm-up
         *  thread, the synchronous fallback in [onCreateView] still
         *  works — just incurs the ~600ms load on the main thread the
         *  first time. @Volatile so the warm-up writer + the Fragment
         *  reader on different threads see consistent reference
         *  publication. */
        @Volatile private var sCachedFolders: List<PhoneFolders.Folder>? = null
        @Volatile private var sCachedApps:    List<PhoneApp>? = null
        @Volatile private var sCachedGrouped: Map<String, List<PhoneApp>>? = null

        /** Smart-folder SELECTION results, keyed by the caller's `exclude`
         *  set — see [smartFoldersCached]. Separate from [sCachedGrouped]
         *  because it is a different and far more expensive computation: the
         *  grouped cache is a pure classification over data already in memory,
         *  this one is the thousand package-manager round trips
         *  [renderSmartFoldersAsync] describes. Replaced wholesale rather than
         *  mutated, so the @Volatile publication covers the whole map; a race
         *  between two callers can only cost one recomputation. */
        @Volatile private var sCachedSmart: Map<Set<String>, List<SmartRendered>> = emptyMap()

        /** Bumped by [invalidateCache]. A background selection captures this
         *  before it starts and publishes only if it still matches.
         *
         *  WITHOUT IT THE REFRESH BUTTON HAS A HOLE. A thread that started
         *  BEFORE the user refreshed is still running afterwards, holding a
         *  result computed from the app list that was just thrown away. When it
         *  finished it wrote that result into the cache — after the clear — and
         *  the next render could serve it. The section would then show the app
         *  set from before the refresh, which is the exact staleness the clear
         *  exists to prevent, and it would survive until something invalidated
         *  again. Narrow, because the thread the refresh starts has to lose a
         *  race it begins with a head start in; real, because nothing made it
         *  impossible.
         *
         *  A plain Int is enough: every [invalidateCache] caller is on the main
         *  thread (the PACKAGE_ADDED receiver and the two refresh taps), so the
         *  increment cannot interleave with itself. @Volatile is what makes the
         *  new value visible to the background threads that read it. */
        @Volatile private var sCacheGeneration = 0

        /** Invalidate every cache slot.
         *
         *  NO LONGER UNUSED. This said "Currently unused; killing + reopening
         *  the app rebuilds the cache organically", which stopped being true
         *  on 2026-09-10: SuitePhoneAppsFragment registers a
         *  PACKAGE_ADDED/PACKAGE_REMOVED receiver and calls this before
         *  rebuilding, so a Quickmark placeholder the user just installed
         *  turns into the real app in place. Without dropping the cache the
         *  rebuild would redraw the same warm snapshot and the new app would
         *  still be missing — which looks exactly like the install failing. */
        fun invalidateCache() {
            sCachedFolders = null
            sCachedApps = null
            sCachedGrouped = null
            // The smart-folder selection is derived from sCachedApps, so it is
            // stale the instant that is. Leaving it behind would make the
            // refresh button redraw yesterday's Stores and Rank folders over a
            // freshly enumerated All Apps — the two halves of one page
            // disagreeing, which reads as the refresh not having worked.
            sCachedSmart = emptyMap()
            sCacheGeneration++
        }

        /** Warm-up: kick a background Thread that enumerates installed
         *  apps + loads folder taxonomy + runs the classifier, then
         *  publishes the results to the @Volatile cache slots. Safe to
         *  call multiple times — the no-op fast path returns immediately
         *  if everything's already cached. MainActivity.onCreate fires
         *  this so the Phone tab is hot the moment the user reaches it.
         *
         *  NOT a coroutine — keeping it on a plain low-priority Thread
         *  avoids hauling in kotlinx-coroutines as an app/ dep just for
         *  one fire-and-forget warm-up. */
        fun warmUp(ctx: Context) {
            if (sCachedFolders != null && sCachedApps != null && sCachedGrouped != null) return
            Thread {
                runCatching {
                    val folders = sCachedFolders
                        ?: PhoneFolders.loadFromBuildConfig().also { sCachedFolders = it }
                    val apps = sCachedApps
                        ?: collectLaunchableAppsStatic(ctx.applicationContext)
                            .also { sCachedApps = it }
                    if (sCachedGrouped == null) {
                        sCachedGrouped = PhoneAppClassifier.groupByFolder(apps, folders)
                    }
                }
            }.apply {
                name = "PhoneAppsFragment.warmUp"
                isDaemon = true
                priority = Thread.MIN_PRIORITY
                start()
            }
        }

        /** Public snapshot for callers OUTSIDE this fragment that need
         *  the launchable-apps list — currently SuperappSearchIndex's
         *  "Phone (apps)" scope. Returns the warm-up cache when
         *  present (zero work), otherwise falls through to a fresh
         *  enumeration. Same profile-filter pipeline applies; the
         *  search scope therefore honours Guest-mode whitelisting
         *  automatically. */
        fun snapshot(ctx: Context): List<PhoneApp> =
            sCachedApps ?: collectLaunchableAppsStatic(ctx.applicationContext).also { sCachedApps = it }

        /** Static enumeration — derives LauncherApps from [ctx] each
         *  call so warm-up (no Fragment instance) + onCreateView (has
         *  one) share the exact same code path.
         *
         *  Profile filter applied last: when LauncherProfile = Guest,
         *  the behavior map (build.json::ui.launcher_profiles[guest]
         *  .behavior) declares app_filter=whitelist + whitelist=
         *  [browser], and [LauncherProfiles.allowedPackagesFor]
         *  resolves "browser" to every installed browser. The Phone
         *  tab in Guest mode therefore lists exactly the device's
         *  browsers — system browser, Chrome, Firefox, etc. — and
         *  nothing else. Personal / Work pass through (filter
         *  returns null = "no filter"). */
        /** Whether [pkg] belongs in the installed-apps list. The SuperApp's own package is NOT
         *  special-cased: it used to be dropped here, which is why Cloud ▸ All apps never showed
         *  the SuperApp's own icon. Only the profile whitelist ([allowed], null = no filter) cuts. */
        internal fun listsPackage(pkg: String, allowed: Set<String>?): Boolean =
            allowed == null || pkg in allowed

        private fun collectLaunchableAppsStatic(ctx: Context): List<PhoneApp> {
            val me = Process.myUserHandle()
            val launcher = ctx.getSystemService(Context.LAUNCHER_APPS_SERVICE) as? LauncherApps
                ?: return emptyList()
            val allowed = LauncherProfiles.allowedPackagesFor(ctx, LauncherProfilePrefs(ctx).profile)
            return runCatching {
                launcher.getActivityList(null, me).mapNotNull { info ->
                    val pkg = info.applicationInfo.packageName
                    if (!listsPackage(pkg, allowed)) return@mapNotNull null
                    PhoneApp(
                        packageName       = pkg,
                        activityComponent = info.componentName,
                        label             = info.label?.toString() ?: pkg,
                        icon              = runCatching { info.getBadgedIcon(0) }.getOrNull(),
                        user              = me,
                        firstInstallTime  = runCatching {
                            ctx.packageManager.getPackageInfo(pkg, 0).firstInstallTime
                        }.getOrDefault(0L),
                    )
                }
            }.getOrDefault(emptyList())
        }
    }
}
