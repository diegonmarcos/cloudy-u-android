package com.diegonmarcos.superapp
import com.diegonmarcos.superapp.mail.jmapClient
import com.diegonmarcos.superapp.mail.signedIn
import com.diegonmarcos.superapp.ui.snack
import com.diegonmarcos.superapp.system.Trace
import com.diegonmarcos.superapp.system.ShellOverride
import com.diegonmarcos.superapp.system.ModePrefs
import com.diegonmarcos.superapp.system.BackgroundOrchestrator
import com.diegonmarcos.superapp.ui.Haptics
import com.diegonmarcos.superapp.ui.ShellFxState
import com.diegonmarcos.superapp.launcher.Pages
import com.diegonmarcos.superapp.launcher.TileGridFragment
import com.diegonmarcos.superapp.launcher.WebPageFragment
import com.diegonmarcos.superapp.launcher.CircularMenuTree
import com.diegonmarcos.superapp.launcher.Sections
import com.diegonmarcos.superapp.launcher.StackAnchors
import com.diegonmarcos.superapp.launcher.SectionPages
import com.diegonmarcos.superapp.launcher.SectionMenuFragment
import com.diegonmarcos.superapp.launcher.SectionTabsFragment
import com.diegonmarcos.superapp.launcher.PlaceholderDrawerFragment
import com.diegonmarcos.superapp.launcher.LauncherStatusStrip
import com.diegonmarcos.superapp.launcher.HomeDrawerFragment
import com.diegonmarcos.superapp.launcher.themes.cloud.Home3DFragment
import com.diegonmarcos.superapp.launcher.DetailPlaceholderFragment
import com.diegonmarcos.superapp.devtools.DevControlBridge
import com.diegonmarcos.superapp.settings.LauncherProfiles
import com.diegonmarcos.superapp.settings.LauncherTheme
import com.diegonmarcos.superapp.settings.LauncherThemes
import com.diegonmarcos.superapp.settings.LauncherThemePrefs
import com.diegonmarcos.superapp.ui.LauncherPalette
import com.diegonmarcos.superapp.settings.LauncherProfilePrefs
import com.diegonmarcos.superapp.settings.LauncherConfigFragment
import com.diegonmarcos.superapp.apps.PhoneAppsFragment
import com.diegonmarcos.superapp.launcher.RecentCloudTiles
import com.diegonmarcos.superapp.battery.EnergyWatchdog
import com.diegonmarcos.superapp.battery.BatterySessionWorker
import com.diegonmarcos.superapp.search.SearchOpener
import com.diegonmarcos.superapp.network.WgState

import com.diegonmarcos.superapp.shell.SuppressVerticalSwipe
import com.diegonmarcos.superapp.launcher.LauncherToolbarFx
import com.diegonmarcos.superapp.launcher.LauncherNavController
import com.diegonmarcos.superapp.onehand.ArcMenu
import com.diegonmarcos.superapp.onehand.CanopusStar
import com.diegonmarcos.superapp.onehand.CircularMenu
import com.diegonmarcos.superapp.onehand.SiriusStar
import com.diegonmarcos.superapp.media.MusicIslandController

import com.diegonmarcos.superapp.shell.SuppressHorizontalSwipe

import com.diegonmarcos.superapp.shell.Collapsible

import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.drawerlayout.widget.DrawerLayout
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.FragmentTransaction
import com.google.android.material.appbar.AppBarLayout
import com.diegonmarcos.superapp.bottomnav.BottomNavIslandView
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.ui.ShellBottomNav
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.PageTabsView
import com.diegonmarcos.superapp.updater.Updater
import com.diegonmarcos.superapp.mail.MailHost
import com.diegonmarcos.superapp.mail.MailPages
import com.diegonmarcos.superapp.launcher.BackHandler
// WalletHost removed — libs:wallet moved to ac_cloud-wallet (constellation APK).


/** The tag the old update overlay was attached under (#812: removed). */
private const val UPDATE_OVERLAY_TAG = "update_overlay"
private const val DRAWER_TAB_HOME = "home"
/** Fallback for the legacy open_home_apps action ids; the swipes themselves are declared in build.json. */
private const val CLOUD_APPS_TARGET = "page:cloud/apps"
private const val DRAWER_TAB_SECTION = "section"

/**
 * Top-level shell.
 *
 * Bottom nav: 5 buttons. Center is **Home** (master index). Tap any button
 * → right pane shows a [TileGridFragment] of that section's sub-pages.
 *
 * Drawer: paginated via two tabs — [Home] and [<currentSection>]. Tapping
 * either drawer tab also lands the right pane on the matching TileGrid:
 * the tab title is itself a link to the section index, mirroring the
 * bottom-nav behaviour.
 *
 * All navigation taxonomy is read from [Sections], which mirrors
 * `build.json::ui.sections`. There is no hardcoded list of sections.
 */
open class ShellActivity : AppCompatActivity(),
    HomeDrawerFragment.NavigationListener,
    TileGridFragment.TileClickListener,
    com.diegonmarcos.superapp.devtools.DevControlBridge.ActivityHost,
    com.diegonmarcos.superapp.devcontrol.NavIslandSource,
    MailHost,
    SearchOpener,
    com.diegonmarcos.superapp.apptabs.AppTabsHost,
    com.diegonmarcos.superapp.perms.PermsHost,
    LauncherNavController.NavHost {

    /** Must match REAPPLY_SENTINEL in the stalwart sorter (crate/src/main.rs). */
    private val REAPPLY_SENTINEL = ".reapply"

    /** Navigation policy + walk-list state; the Activity is its view host.
     *  `internal` so [SectionTabsFragment] can reuse the page→Fragment
     *  routing when filling its panes instead of duplicating it. */
    internal val nav = LauncherNavController(this)

    /** [AppTabsHost] — card-tap callback from libs:launcher-apptabs. Routes a
     *  recorded LRU entry back to its original destination. Sections
     *  go through goSection; pages through openSectionPage; external
     *  apps fire a normal launch intent. */
    override fun onAppTabPicked(entry: com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry) {
        when (entry) {
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.SectionEntry ->
                goSection(entry.sectionId, entry.label)
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.PageEntry ->
                openSectionPage(entry.sectionId, entry.pageId)
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry -> runCatching {
                packageManager.getLaunchIntentForPackage(entry.packageName)?.let { startActivity(it) }
            }
            is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.TargetEntry ->
                dispatchTarget(entry.target)
        }
    }

    private val TAG = "MainActivity"
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var bottomNav: BottomNavIslandView
    private lateinit var toolbarFx: LauncherToolbarFx
    private lateinit var drawerTabs: PageTabsView

    // The section alone is NOT enough to decide whether the stars belong on
    // screen. They are the last children of the root frame, so they float over
    // everything, and plenty of surfaces appear without the section changing at
    // all: the app-drawer sheet, the search sheet and the notification centre
    // are added to overlay_container while currentSection stays "home", and a
    // page pushed on the back stack can do the same. That is why the stars were
    // showing across pages — the gate was right, the trigger was too narrow.
    // [refreshStars] is now the single answer, and it is asked again whenever
    // the screen can have changed: section set, back stack, resume.
    override var currentSection: String = ""
        set(value) {
            field = value
            refreshStars()
        }

    /** Set once the layout and the star wrappers exist; before that every
     *  refresh is a no-op rather than a findViewById on a missing view. */
    private var starsReady = false

    /**
     * Home stars are for the HOME SCREEN: the home section, with nothing
     * covering it. Anything on the back stack or in overlay_container means a
     * page or sheet is in front, so they go away — passing a section that
     * cannot match `show_on_section` rather than reaching into the lib's own
     * visibility rules.
     */
    private fun refreshStars() {
        if (!starsReady) return
        val homeScreen = currentSection == "home" &&
            supportFragmentManager.backStackEntryCount == 0 &&
            supportFragmentManager.findFragmentById(R.id.overlay_container) == null
        val section = if (homeScreen) currentSection else ""
        siriusStar.update(section); canopusStar.update(section); centauriStar.update(section)
        recentTabsStar.update(section); polarisStar.update(section)
    }
    override var currentLabel:   String = ""

    // All THREE home stars live in libs:launcher-onehand — Sirius/Canopus/Centauri are
    // all generic widget classes there now. Sirius and Canopus need app-side
    // content (the section/page tree), so MainActivity builds their Host here
    // and injects it; Centauri needs none (pure platform API), so it takes no
    // Host at all. iconBitmap is identical for Sirius and Canopus — both
    // resolve build.json icon names via Sections.iconResFor, same as every
    // other launcher surface.
    /**
     * Ask the server-side sorter to re-apply every routing rule to every
     * message now, by creating the `.reapply` sentinel mailbox over JMAP.
     *
     * Deliberately NOT a GitHub workflow_dispatch: that needs a PAT, and a
     * token has no business shipping inside an APK. A JMAP mailbox is just a
     * tag, so this reuses the mail credentials the user already entered, needs
     * no new endpoint and no new secret. The sorter notices the sentinel within
     * one tick, destroys it, and runs a full pass.
     *
     * The rules themselves still come from the data-driven files: edit, commit,
     * push, deploy -- then tap this to apply them to mail that already arrived.
     */
    // The strings live in libs:mail, and android.nonTransitiveRClass=true means
    // a library's resources are NOT in the app's R - they have to be reached
    // through that library's own R. Unqualified R.string here is what broke
    // 0777d9dd, 3334e872, eccd3f4f and every build since.
    private fun reapplyMailRules(anchor: android.view.View) {
        val prefs = com.diegonmarcos.superapp.mail.JmapPrefs(this)
        if (!prefs.signedIn(this)) {
            anchor.snack(getString(com.diegonmarcos.superapp.mail.R.string.mail_messages_login_first))
            return
        }
        anchor.snack(getString(com.diegonmarcos.superapp.mail.R.string.reapply_rules_sent))
        Thread {
            val client = prefs.jmapClient(this)
            val msg = when (val s = client.discover()) {
                is com.diegonmarcos.superapp.mail.JmapClient.Result.Success ->
                    when (val r = client.createMailbox(s.value, REAPPLY_SENTINEL)) {
                        is com.diegonmarcos.superapp.mail.JmapClient.Result.Success ->
                            getString(com.diegonmarcos.superapp.mail.R.string.reapply_rules_ok)
                        is com.diegonmarcos.superapp.mail.JmapClient.Result.HttpError ->
                            getString(com.diegonmarcos.superapp.mail.R.string.reapply_rules_failed, "HTTP ${r.code}")
                        is com.diegonmarcos.superapp.mail.JmapClient.Result.NetworkError ->
                            getString(com.diegonmarcos.superapp.mail.R.string.reapply_rules_failed, r.message)
                    }
                is com.diegonmarcos.superapp.mail.JmapClient.Result.HttpError ->
                    getString(com.diegonmarcos.superapp.mail.R.string.reapply_rules_failed, "HTTP ${s.code}")
                is com.diegonmarcos.superapp.mail.JmapClient.Result.NetworkError ->
                    getString(com.diegonmarcos.superapp.mail.R.string.reapply_rules_failed, s.message)
            }
            runOnUiThread { anchor.snack(msg) }
        }.start()
    }


    private fun iconBitmapFor(name: String, sizePx: Int): android.graphics.Bitmap? {
        if (name.isBlank() || sizePx <= 0) return null
        val resId = Sections.iconResFor(this, name)
        if (resId == 0) return null
        val d = androidx.core.content.ContextCompat.getDrawable(this, resId) ?: return null
        val bmp = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, sizePx, sizePx); d.draw(android.graphics.Canvas(bmp))
        return bmp
    }

    private val siriusStar by lazy {
        SiriusStar(
            activity = this,
            star = findViewById(R.id.all_apps_sirius_star),
            host = object : CircularMenu.Host {
                override fun navigate(target: String) { onTileClicked(target) }
                override fun iconBitmap(name: String, sizePx: Int) = iconBitmapFor(name, sizePx)
                override fun childrenOf(key: String) = CircularMenuTree.childrenOf(this@ShellActivity, key)
            },
            twinkleEnabled = {
                com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).anim("star_twinkle")
            },
        )
    }
    private val canopusStar by lazy {
        CanopusStar(
            activity = this,
            star = findViewById(R.id.configs_canopus_star),
            island = findViewById(R.id.bottom_nav_island),
            host = object : ArcMenu.Host {
                override fun navigate(target: String) { onTileClicked(target) }
                override fun iconBitmap(name: String, sizePx: Int) = iconBitmapFor(name, sizePx)
                override fun itemsFor(section: String) =
                    SectionPages.pagesFor(section).map {
                        // Honour a page's declared action target (Update All, ...)
                        // exactly like CircularMenuTree does. Forcing page:<sec>/<id>
                        // both pointed those entries at a page that isn't there and
                        // kept them off the arc-menu's inner actions arc.
                        ArcMenu.Item(it.label, it.iconName, it.action.ifBlank { "page:$section/${it.id}" }, it.isAction)
                    } +
                        // Inner ring (KDE Connect, Animations),
                        // read from this star's own block. It used to hang off
                        // the Configs node in circular_menu.nodes, which broke
                        // when that ring stopped listing Configs at all.
                        CircularMenu.actionsOf("arc_menu")
                            .map { ArcMenu.Item(it.label, it.iconName, it.target, true) }
            },
        )
    }
    // Centauri lives in libs:launcher-onehand (like Sirius/Canopus) — its content
    // (last 9 recent apps) is pure platform API, no app Host needed, so it
    // takes the star View + island anchor directly instead of a Host.
    private val centauriStar by lazy {
        com.diegonmarcos.superapp.onehand.CentaurusStar(
            this,
            findViewById(R.id.active_apps_centauri_star),
            findViewById(R.id.bottom_nav_island),
        )
    }
    /**
     * Recent Tabs — the 4th star, Centauri's twin on the right of the midway
     * row. Built from CanopusStar rather than a class of its own because it IS
     * the generic host-driven arc star: same widget, same fan, and only the
     * three placement parameters and the Host differ.
     *
     * Its Host is here for the same reason Canopus's is: the CONTENT is
     * app-specific. The list is the App Tabs LRU — the history this app already
     * records at its navigation chokepoint — filtered to the destinations that
     * still exist and capped in data.
     */
    private val recentTabsStar by lazy {
        CanopusStar(
            activity = this,
            star = findViewById(R.id.recent_tabs_star),
            island = findViewById(R.id.bottom_nav_island),
            host = object : ArcMenu.Host {
                override fun navigate(target: String) { openRecentTab(target) }
                override fun iconBitmap(name: String, sizePx: Int) = iconBitmapFor(name, sizePx)
                override fun itemsFor(section: String): List<ArcMenu.Item> {
                    // `section` is ignored — this star's subject is the history,
                    // not a build.json section (that field is Canopus's).
                    val items = recentTabEntries().map {
                        // The entry's own key IS the target: it round-trips
                        // through [onAppTabPicked], the re-navigation dispatcher
                        // App Tabs already uses, so a tap here and a tap on an
                        // App Tabs card cannot diverge.
                        ArcMenu.Item(recentTabLabel(it), recentTabIcon(it), "recenttab:${it.key}")
                    }
                    // ArcMenu.open() refuses an empty list, so a fresh install
                    // would give a star that does nothing when touched. One
                    // inert entry says "empty" instead of "broken". action=false
                    // so it sits on the OUTER arc, where the entries it stands in
                    // for would be — an empty inner ring under an empty outer one
                    // reads as two failures rather than one honest blank.
                    return items.ifEmpty {
                        listOf(ArcMenu.Item(getString(R.string.recent_tabs_empty), "", "", false))
                    }
                }
            },
            // Centauri's row, mirrored: same half-way anchor and size, the
            // offset positive instead of negative.
            anchorFraction = 0.5f,
            sizeBumpSp = 2f,
            offsetXDp = CircularMenu.config().starPairOffsetXDp,
        )
    }

    /**
     * Polaris — the 5th star, at the TOP of the screen, and the only one that
     * opens no menu: a tap navigates straight to
     * build.json::onehand.search_star.target, by default action:open_search.
     *
     * It goes through [onTileClicked] rather than calling [openSearchSheet]
     * directly, for the same reason every other star's Host does: the target
     * is a data string, and the tile dispatcher is the one place that knows
     * what data strings mean. Pointing this star somewhere else stays a
     * build.json edit.
     */
    private val polarisStar by lazy {
        com.diegonmarcos.superapp.onehand.PolarisStar(
            activity = this,
            star = findViewById(R.id.search_polaris_star),
            navigate = { target -> onTileClicked(target) },
        )
    }

    /** The history this star draws: newest first, in-app destinations only,
     *  the ones that still exist, capped at build.json::ui.app_tabs.star_cap. */
    private fun recentTabEntries(): List<com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry> =
        runCatching {
            com.diegonmarcos.superapp.apptabs.pickRecentTabs(
                com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).all(),
                ::recentTabStillExists,
                BuildConfig.UI_APP_TABS_STAR_CAP,
            )
        }.getOrDefault(emptyList())

    /**
     * Does this stored destination still exist? The owner restructures
     * build.json constantly, so an entry written last week can name a section
     * or page that has since been renamed or deleted. Answering false here is
     * what keeps the star from navigating into nothing.
     *
     * `includeHidden` because a hidden page is still a real, routable
     * destination — Configs' Launcher ▸ Controls tab is reached as page:config/controls and
     * would otherwise be judged dead the moment it was recorded.
     */
    private fun recentTabStillExists(
        e: com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry,
    ): Boolean = when (e) {
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.SectionEntry ->
            Sections.byId(e.sectionId) != null
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.PageEntry ->
            SectionPages.pagesFor(e.sectionId, includeHidden = true).any { it.id == e.pageId }
        // A raw target is dispatched, not resolved against the section tree;
        // the dispatcher already no-ops on a target it does not recognise.
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.TargetEntry -> true
        // Filtered out before this is ever asked; an Android app is Centauri's.
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry -> false
    }

    /** The page's real name, exactly as the section tree spells it — the label
     *  stored with the entry is a snapshot and goes stale when a page is
     *  renamed, which the owner does often. Falls back to the snapshot. */
    private fun recentTabLabel(
        e: com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry,
    ): String = when (e) {
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.SectionEntry ->
            Sections.byId(e.sectionId)?.label ?: e.label
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.PageEntry ->
            SectionPages.pagesFor(e.sectionId, includeHidden = true)
                .firstOrNull { it.id == e.pageId }?.label ?: e.label
        // Named, not `else`: every Entry subtype carries its own `label`, but the
        // sealed base declares only `ts` and `key`, so an `else` branch leaves `e`
        // typed as Entry and `e.label` does not resolve. Matching each remaining
        // subtype restores the smart cast — and keeps this exhaustive the way
        // recentTabIcon below already is, so a fifth Entry fails here at compile
        // time instead of silently taking a fallback.
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.TargetEntry -> e.label
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry -> e.label
    }

    /** Same drawable name every other launcher surface uses for this
     *  destination, resolved by the Host's shared iconBitmapFor. */
    private fun recentTabIcon(
        e: com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry,
    ): String = when (e) {
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.SectionEntry ->
            Sections.byId(e.sectionId)?.iconName ?: e.iconName
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.PageEntry ->
            SectionPages.pagesFor(e.sectionId, includeHidden = true)
                .firstOrNull { it.id == e.pageId }?.iconName?.ifBlank { e.iconName } ?: e.iconName
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.TargetEntry -> e.iconName
        is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry -> ""
    }

    /**
     * Open an arc entry. The key is re-resolved against the live history rather
     * than trusted from the target string, so an entry evicted or invalidated
     * between drawing the arc and releasing the finger simply does nothing
     * instead of navigating somewhere that is gone.
     */
    private fun openRecentTab(target: String) {
        val key = target.removePrefix("recenttab:")
        if (key == target || key.isBlank()) return   // the empty-state entry
        recentTabEntries().firstOrNull { it.key == key }?.let { onAppTabPicked(it) }
    }

    /** Latest gesture/navigation-bar inset captured by the edge-to-edge
     *  listener — applyChrome adds it to the BottomNav clearance so
     *  content doesn't slide under the nav bar. */
    private var bottomSystemInset: Int = 0
    /** Real status-bar inset captured from the OS the moment
     *  [applyEdgeToEdgeInsets]'s listener fires. Samsung One UI reports
     *  a taller bar than `android.R.dimen.status_bar_height` (because of
     *  the camera cutout), so the resource lookup under-sizes the launcher
     *  strip's negative topMargin — the strip lands BELOW the bar zone
     *  where the toolbar island covers it. Using this cached real value
     *  in [applyLauncherChrome] keeps the strip flush with y=0. */
    private var topSystemInset: Int = 0

    /** Global UI mode (apps | admin). Hot-swaps which aggregator
     *  tile-list renders in Communication/Infos/Suite/Tools. */
    private lateinit var modePrefs: ModePrefs
    private val currentMode: String get() = modePrefs.mode

    /**
     * #601: bake the Scale slider's size in-process, before any layout is
     * inflated. This used to be a no-op — the slider's factor only ever
     * reached the screen through `wm density` / `settings put system
     * font_scale`, both device-wide writes gated on wireless debugging — so
     * a fresh install rendered at the bare phone size no matter what
     * build.json shipped as the default step. Wrapping the base Context
     * here needs no channel and no grant: [SystemDisplay.wrap] reads the
     * stored scale (default 3 → 0.85, LauncherSettingsPrefs.Config.scale)
     * and returns a Configuration-overridden Context this Activity — and
     * every Fragment it hosts — inflates from instead.
     */
    override fun attachBaseContext(base: android.content.Context) {
        super.attachBaseContext(com.diegonmarcos.superapp.system.SystemDisplay.wrap(base))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Trace.i(TAG, "onCreate enter")
        // BEFORE super.onCreate: this is the only moment Android lets a theme
        // be chosen. Every XML layout in the app resolves its `?attr/` colours
        // against whatever style is installed here, so this one line is what
        // carries the active mode into all 24 layouts and the window backdrop.
        // Called later it would compile, run, and change nothing on screen.
        com.diegonmarcos.superapp.ui.LauncherStyle.apply(this)
        super.onCreate(savedInstanceState)
        // #1687 the low-power mode follows the system power saving from the first frame.
        runCatching { com.diegonmarcos.superapp.system.PowerSaving.watch(this) }
        try {
            // Edge-to-edge: content draws beneath the status / nav bars,
            // we pad the AppBar and BottomNav to keep them visible. The
            // theme already declares transparent system bars + light-icon
            // tinting so the bars stay readable.
            FleetChrome.apply(this)

            setContentView(R.layout.activity_main)
            // The galaxy, the island's wave and neon frame and the music line are Compose layers now.
            ShellFxState.install(this, findViewById(R.id.galaxy_backdrop))
            findViewById<androidx.compose.ui.platform.ComposeView>(R.id.launcher_status_strip)?.let { LauncherStatusStrip.install(this, it) }
            // The shell, not the pages, owns where the top row ends: every content host is padded to the row's bottom edge (#1080).
            com.diegonmarcos.superapp.ui.ShellTopInset.install(findViewById(android.R.id.content))
            // App Tabs LRU window size — data-driven from build.json::ui.app_tabs.cap.
            com.diegonmarcos.superapp.apptabs.AppTabPrefs.cap = BuildConfig.UI_APP_TABS_CAP
            modePrefs = ModePrefs(this)
            currentLabel = getString(R.string.section_home)
            siriusStar.setup(); canopusStar.setup(); centauriStar.setup(); recentTabsStar.setup()
            polarisStar.setup()
            starsReady = true
            refreshStars()

            // The top row is Compose (ui/ShellToolbar.kt): hamburger | island | Back, no ActionBar. The Dynamic Island carries
            // identity instead of a title. Search lives in the Home Apps page, not here.
            findViewById<androidx.compose.ui.platform.ComposeView>(R.id.toolbar)?.setContent {
                com.diegonmarcos.superapp.uikit.CloudKitTheme(com.diegonmarcos.superapp.ui.LauncherPalette.kit(this)) {
                    com.diegonmarcos.superapp.ui.ShellToolbarRow(
                        onMenu = { Haptics.tap(bottomNav); drawerLayout.openDrawer(androidx.core.view.GravityCompat.START) },
                        onBack = { goBack() },
                        tic = com.diegonmarcos.superapp.ui.ShellFxState.allAnim,
                    )
                }
            }
            findViewById<androidx.compose.ui.platform.ComposeView>(R.id.main_menu_orb_glyph)?.setContent {
                com.diegonmarcos.superapp.uikit.CloudKitTheme(com.diegonmarcos.superapp.ui.LauncherPalette.kit(this)) {
                    com.diegonmarcos.superapp.ui.OrbGlyph(R.drawable.ic_hamburger_asymmetric)
                }
            }
            findViewById<androidx.compose.ui.platform.ComposeView>(R.id.top_wallet_orb_icon)?.setContent {
                com.diegonmarcos.superapp.uikit.CloudKitTheme(com.diegonmarcos.superapp.ui.LauncherPalette.kit(this)) {
                    com.diegonmarcos.superapp.ui.OrbGlyph(
                        com.diegonmarcos.superapp.ui.ShellToolbarState.walletIcon.takeIf { it != 0 } ?: R.drawable.ic_wallet)
                }
            }

            refreshToolbar()

            drawerLayout = findViewById(R.id.drawer_layout)
            // DrawerLayout with fitsSystemWindows="true" otherwise paints
            // a `?attr/colorPrimaryDark` (blue-gray) scrim across the
            // status-bar area, hiding our galaxy beneath. Force-clear it.
            drawerLayout.setStatusBarBackground(null)
            bottomNav = findViewById(R.id.bottom_nav_island)
            drawerTabs = findViewById(R.id.drawer_tabs)

            applyEdgeToEdgeInsets()

            // Revolut-style Home-root corner orbs: the same two actions the
            // toolbar carried, in detached liquid-glass circles. Left IS the
            // hamburger; right launches the wallet's home app — target
            // DECLARED in build.json::ui.top_orbs.right (a tile target: section:config,
            // or extapp:<id> for an app), read from UI_TOP_ORBS_B64,
            // never hardcoded here. Visibility is toggled with atHomeRoot in
            // onPrepareOptionsMenu alongside the bar-dissolve.
            findViewById<android.view.View>(R.id.main_menu_orb)?.setOnClickListener {
                Haptics.tap(it)
                drawerLayout.openDrawer(androidx.core.view.GravityCompat.START)
            }
            findViewById<android.view.View>(R.id.top_wallet_orb)?.setOnClickListener {
                Haptics.tap(it)
                // #879 the same dispatcher a tile tap goes through: `section:` and `extapp:` alike.
                topOrbTarget().takeIf { t -> t.isNotBlank() }?.let { t -> onTileClicked(t) }
            }
            bindTopOrb()
            // Bind the central Dynamic Island label — "{INITIALS} · {Mode}".
            // Updated whenever onPrepareOptionsMenu fires (mode toggle,
            // back-stack change, drawer open) AND after a profile edit.
            refreshDynamicIsland()
            // Tap the SECOND toolbar pill → fire whatever
            // `ui.dynamic_island_action` resolves to. (The FIRST pill is
            // music_playing_island, the now-playing mini-island; its target
            // is the package that owns the live media session, so it is
            // discovered at runtime and is not declarable.) The declared
            // value is `extapp:cloud-myterminal`, which opens Cloud
            // MyTerminal through ui.external_apps and offers the install
            // when it is absent; the supported forms are
            // build.json::ui._vocab_dynamic_island_action and the build
            // fails on anything else. Parsed at runtime so editing
            // build.json + rebuilding is the only edit needed.
            findViewById<androidx.compose.ui.platform.ComposeView>(R.id.dynamic_island)?.let { island ->
                island.setContent {
                    com.diegonmarcos.superapp.uikit.KitDenseMaterial(androidx.compose.material3.MaterialTheme.colorScheme) {
                        com.diegonmarcos.superapp.ui.DynamicIsland(onTap = { Haptics.tap(island); dispatchDynamicIslandAction() })
                    }
                }
            }

            drawerSectionLabel = currentLabel
            setDrawerTabs(drawerSectionLabel, 0)
            drawerTabs.onSelect   = { onDrawerTabPicked(if (it.id == DRAWER_TAB_HOME) 0 else 1) }
            drawerTabs.onReselect = { onDrawerTabPicked(if (it.id == DRAWER_TAB_HOME) 0 else 1) }

            // Fill the bottom-nav island from build.json::ui.bottom_nav with
            // mode-aware icons (icon_apps/icon_admin swap the glyph).
            refreshBottomNavIconsForMode()

            bottomNav.onSelect = { onBottomNavPicked(it) }
            bottomNav.onReselect = reselect@{ tappedSection ->
                Trace.i(TAG, "bnv-reselect: tapped=$tappedSection current=$currentSection")
                // RULE 1 — Home BNV always returns to the 3D Home screen.
                //   Even if BNV's selected id "matches" the visible fragment
                //   (e.g. Tabs/Config left BNV pointing at Home and the
                //   user is in a non-BNV section), Home means Home.
                if (tappedSection == "home" && currentSection != "home") {
                    fireGeminiPattern()
                    goHome()
                    return@reselect
                }
                // RULE 2 — Other BNV slots with a different currentSection:
                //   take the user there.
                if (tappedSection != currentSection) {
                    fireGeminiPattern()
                    goSection(tappedSection, Sections.byId(tappedSection)?.label ?: tappedSection)
                    return@reselect
                }
                // RULE 3 — True same-section re-tap.
                //   Ask the current fragment's Collapsible handler to
                //   fold/unfold its panels.
                Haptics.tap(bottomNav)
                val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
                (cur as? Collapsible)?.toggleAllCollapsed()
            }

            if (savedInstanceState == null) {
                // Default landing per build.json::ui.default_section, taken as a
                // bottom-nav pick so the pill and the screen start together.
                val target = Sections.defaultSectionId()
                onBottomNavPicked(if (target in Sections.bottomNavIds()) target else "home")
            }

            // Warm-up the Phone tab data path on a low-priority Thread
            // so by the time the user swipes up to Home Apps → Phone,
            // the LauncherApps enumeration + icon load + folder
            // classification (~600ms) is already done. If the user
            // races the warm-up, PhoneAppsFragment.onCreateView falls
            // back to a synchronous load — same blocking shape as
            // before warm-up existed.
            PhoneAppsFragment.warmUp(this)

            // Launcher-icon shortcut (long-press launcher icon) carries a
            // shortcut_action extra. Same target grammar as tile clicks.
            handleShortcutIntent(intent)

            // Re-apply chrome after every back-stack change so that the
            // restored Fragment's ShellOverride takeover (or default) takes
            // effect — runOnCommit would have worked but it's mutually
            // exclusive with addToBackStack, so we use the listener instead.
            supportFragmentManager.addOnBackStackChangedListener {
                val frag = supportFragmentManager.findFragmentById(R.id.fragment_container)
                // A full-bleed modal overlay (Notification Center / Search),
                // when present, dictates the shell chrome; otherwise the
                // primary content does. Keeps islands correct whether or not
                // the underlying section is a ShellOverride.
                val chromeFrag = supportFragmentManager.findFragmentById(R.id.overlay_container) ?: frag
                chromeFrag?.let { applyChrome(it) }
                // When the back stack drains AND the resulting visible
                // fragment is the originally-committed Home3DFragment,
                // sync currentSection back to "home". popBackStack()
                // itself doesn't go through goHome(), so without this
                // the field stays stale and onPrepareOptionsMenu keeps
                // showing the Back arrow at the Home root.
                // GUARD: skip during a goSection swap — its clearBackStack pop
                // fires this listener while the OLD Home3DFragment is still
                // mounted (the replace hasn't run yet), which would clobber the
                // just-set currentSection back to "home" (broke Home→Configs:
                // no Back arrow + dead page:<id> tiles).
                if (!inSectionSwap && supportFragmentManager.backStackEntryCount == 0 && frag is Home3DFragment) {
                    currentSection = "home"
                    currentLabel = getString(R.string.section_home)
                }
                // A page or sheet appearing is the ground truth for "is the home
                // screen actually in front", and neither goes through the section
                // setter — which is why the stars were floating over pages. Asked
                // here as well, so backing out brings them back on their own.
                refreshStars()
                // Tablet: popping the last DETAIL page leaves the detail pane
                // empty — restore the "Select an item" placeholder so the
                // master-detail split never shows a blank right column.
                if (isTwoPane() && supportFragmentManager.backStackEntryCount == 0 &&
                    currentSection != "home" &&
                    supportFragmentManager.findFragmentById(R.id.detail_container) !is DetailPlaceholderFragment) {
                    supportFragmentManager.beginTransaction()
                        .replace(R.id.detail_container, DetailPlaceholderFragment.newInstance())
                        .commitAllowingStateLoss()
                }
                // Toolbar's right-side Back action toggles visibility with
                // the back-stack depth; invalidate to redraw.
                refreshToolbar()
            }

            installNavSwipeGesture()
            toolbarFx = LauncherToolbarFx(this, bottomNav) { onTileClicked(it) }
            toolbarFx.install()

            // #894 Cloud Store updates this app with the rest of the fleet: no self-update schedule here.
            if (com.diegonmarcos.superapp.apps.CloudStoreHandoff.ownsInstalls(applicationContext))
                Updater.cancel(applicationContext)
            else Updater.start(applicationContext)
            Trace.i(TAG, "onCreate done")
        } catch (t: Throwable) {
            Trace.e(TAG, "onCreate FAILED", t)
            throw t
        }
    }

    // ── swipe-to-cycle bottom nav ────────────────────────────────────────

    private lateinit var navSwipeGesture: android.view.GestureDetector

    private fun installNavSwipeGesture() {
        val edgeIgnorePx = (24f * resources.displayMetrics.density)
        val minSwipePx   = (100f * resources.displayMetrics.density)
        navSwipeGesture = android.view.GestureDetector(this,
            object : android.view.GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: android.view.MotionEvent): Boolean = true
                override fun onLongPress(e: android.view.MotionEvent) { Trace.i(TAG, "detector long-press at x=${e.x} y=${e.y}") }

                override fun onFling(
                    e1: android.view.MotionEvent?, e2: android.view.MotionEvent,
                    vX: Float, vY: Float,
                ): Boolean {
                    if (e1 == null) return false
                    Trace.i(TAG, "fling dx=${e2.x - e1.x} dy=${e2.y - e1.y} vY=$vY section=$currentSection backStack=${supportFragmentManager.backStackEntryCount}")
                    // Drawer (left pane) open → it owns ALL gestures inside
                    // it (vertical scroll of its menu, horizontal swipe-to-
                    // close). Don't fight it with cycle-section or
                    // app-drawer-sheet flings; let DrawerLayout handle
                    // everything until it's closed again.
                    if (drawerLayout.isDrawerOpen(GravityCompat.START)) return false
                    val dx = e2.x - e1.x
                    val dy = e2.y - e1.y
                    val absDx = Math.abs(dx); val absDy = Math.abs(dy)

                    // VERTICAL swipes — ONLY meaningful when on the Home
                    // section (where the declared swipe-up / swipe-down
                    // targets open: build.json onehand.home_swipes). Off-home, the
                    // activity-level detector MUST defer entirely to
                    // the fragment so list scrolling, pull-to-refresh,
                    // sheet drag-close, WebView pan, etc. all own
                    // their own vertical gestures cleanly. Short-circuit
                    // BEFORE evaluating the vertical-fling thresholds
                    // so the detector doesn't even claim to have seen
                    // a vertical gesture off-home — eliminating the
                    // conflict the user reported.
                    // "Home screen" means the Home ROOT — currentSection stays
                    // "home" for every fragment pushed while inside the Home
                    // section (e.g. Suite:Phone:All), so that alone isn't
                    // enough: also require an empty back stack, mirroring
                    // atHomeRoot in onPrepareOptionsMenu. Otherwise swipe
                    // up/down leaks into those pages and fights their scroll.
                    val atHomeRoot = currentSection == "home" &&
                        supportFragmentManager.backStackEntryCount == 0
                    if (atHomeRoot &&
                        absDy > absDx * 1.4f && absDy > minSwipePx && Math.abs(vY) > 600f) {
                        val consumed = handleVerticalFling(dy, e1.x)
                        if (consumed) Haptics.tap(bottomNav)
                        return consumed
                    }

                    // HORIZONTAL swipes are ALSO home-root-only, same reason.
                    if (!atHomeRoot) return false
                    // Drawer owns left-edge swipes; ignore those.
                    if (e1.x < edgeIgnorePx) return false
                    if (absDx < minSwipePx) return false
                    if (absDx < absDy * 1.4f) return false
                    if (Math.abs(vX) < 600f) return false
                    // Some fragments (WebView in desktop view mode etc.)
                    // need to own horizontal flings — skip the cycle so
                    // the inner content can pan freely.
                    val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
                    if ((cur as? SuppressHorizontalSwipe)?.suppressHorizontalSwipe() == true) {
                        return false
                    }
                    // Horizontal swipe = step the circular walk-list (Comms →
                    // Infos·Apps → Infos·Admin → Home-Apps·Cloud → … → Labs·
                    // Admin → wrap). Left-swipe (dx<0) = next, right = prev.
                    fireGeminiPattern()
                    val prefs = com.diegonmarcos.superapp.settings.HomeSwipePrefs(this@ShellActivity)
                    val swipeAction = if (dx < 0) prefs.left else prefs.right
                    if (swipeAction == "walk_step_next" || swipeAction == "walk_step_prev") {
                        walkStep(direction = if (dx < 0) +1 else -1)
                    } else {
                        handleHomeSwipeAction(swipeAction)
                    }
                    return true
                }
            })
    }

    /** Fire the configured action on swipe-up/down while on Home. Returns
     *  true if the gesture was consumed. */
    private fun handleVerticalFling(dy: Float, startX: Float): Boolean {
        // Some fragments (browser/WebView in detail mode etc.) need to
        // own vertical flings — let the inner content scroll, pull-to-
        // refresh, or fire its own swipe-up navigation without the
        // activity stealing the gesture to open the app drawer sheet.
        val cur = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if ((cur as? SuppressVerticalSwipe)?.suppressVerticalSwipe() == true) {
            return false
        }
        return when {
            dy < 0 && currentSection == "home" -> {
                handleHomeSwipeAction(com.diegonmarcos.superapp.settings.HomeSwipePrefs(this).up); true
            }
            dy > 0 && currentSection == "home" -> {
                // Swipe down is split by the half it STARTS in: left -> Notify, right -> Quick Actions (declared, editable).
                val prefs = com.diegonmarcos.superapp.settings.HomeSwipePrefs(this)
                handleHomeSwipeAction(com.diegonmarcos.superapp.launcher.HomeSwipeRouter.downAction(
                    startX, resources.displayMetrics.widthPixels, prefs.downLeft, prefs.downRight)); true
            }
            else -> false
        }
    }

    /** The last Home gesture action dispatched; read by the emulator test (HomeSwipeGestureTest). */
    @Volatile internal var lastHomeAction: String? = null

    /** Dispatch a home-screen swipe action by name. Values come from
     *  build.json::onehand.home_swipes baked into BuildConfig. */
    private fun handleHomeSwipeAction(action: String) {
        // build.json declares these prefixed ("action:open_home_apps_phone");
        // the Configs picker (HomeSwipePrefs.ACTIONS) stores them bare. Strip
        // once so both vocabularies hit the same branches.
        Trace.i(TAG, "home swipe action=$action")
        lastHomeAction = action.removePrefix("action:")
        when (val a = action.removePrefix("action:")) {
            "open_last_superapp_page" -> {
                val last = runCatching {
                    com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).all()
                        .firstOrNull { it !is com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry }
                }.getOrNull()
                if (last != null) onAppTabPicked(last) else routeTarget(CLOUD_APPS_TARGET)
            }
            "open_last_android_app" -> {
                val last = runCatching {
                    com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).all()
                        .filterIsInstance<com.diegonmarcos.superapp.apptabs.AppTabPrefs.Entry.ExternalAppEntry>()
                        .firstOrNull()
                }.getOrNull()
                if (last != null) {
                    runCatching { startActivity(packageManager.getLaunchIntentForPackage(last.packageName)?.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                } else routeTarget(CLOUD_APPS_TARGET)
            }
            "open_last_cloud_app" -> {
                val last = runCatching {
                    com.diegonmarcos.superapp.launcher.HomeSwipeRouter.lastCloudApp(
                        com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).all(),
                        com.diegonmarcos.superapp.launcher.HomeSwipeRouter.fleetPackages(Sections.externalApps()))
                }.getOrNull()
                val launch = last?.let { packageManager.getLaunchIntentForPackage(it.packageName) }
                if (launch != null) runCatching { startActivity(launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
                else routeTarget(CLOUD_APPS_TARGET)
            }
            "toggle_light" -> com.diegonmarcos.superapp.light.Light.engine(this).quickTap()
            "open_cloud_calc" -> onTileClicked("extapp:cloud-calc")
            "walk_step_next" -> { fireGeminiPattern(); walkStep(+1) }
            "walk_step_prev" -> { fireGeminiPattern(); walkStep(-1) }
            // Target-string actions ("page:phone/apps") route through the tile
            // grammar. Anything else IS a home action — hand it to the one
            // dispatcher that knows them all (open_home_apps_phone, ...).
            else             -> if (a.contains(':')) onTileClicked(a) else dispatchHomeAction(a)
        }
    }

    /** Step `direction` through the circular walk-list, wrapping at both
     *  ends. +1 = next (left-swipe), -1 = prev (right-swipe). */
    private fun walkStep(direction: Int) = nav.walkStep(direction)

    /**
     * The home swipes are read HERE, ahead of every view, so they also saw the
     * stars' own press-drag-release. Dragging from the Configs star out to
     * Store is a fast sideways move on the Home root: the detector took it for
     * a swipe-right and opened the last Android app, then the star's release
     * opened Store as well. One gesture, two destinations. A gesture that
     * STARTS on a star belongs to that star, so the detector never sees it.
     */
    private val starGestureGate = com.diegonmarcos.superapp.launcher.StarGestureGate { x, y ->
        // Every home star: the drag ones (Sirius, Canopus, Centauri, Recent
        // Tabs) and Polaris, whose tap is no swipe but is no less the star's.
        intArrayOf(R.id.all_apps_sirius_star, R.id.configs_canopus_star,
                   R.id.active_apps_centauri_star, R.id.recent_tabs_star,
                   R.id.search_polaris_star).any { id ->
            val v = findViewById<View>(id) ?: return@any false
            if (!v.isShown) return@any false
            val at = IntArray(2); v.getLocationInWindow(at)
            x >= at[0] && x < at[0] + v.width && y >= at[1] && y < at[1] + v.height
        }
    }

    // ── drawn shapes (circle, C): build.json onehand.shape_gestures ──────────
    private val strokePts = ArrayList<Pair<Float, Float>>()
    private var strokeTracking = false
    private val trailState = com.diegonmarcos.superapp.launcher.GestureTrailState()
    private val shapeTrailHost by lazy {
        androidx.compose.ui.platform.ComposeView(this).also { cv ->
            cv.setContent { com.diegonmarcos.superapp.launcher.GestureTrail(trailState, 6f * resources.displayMetrics.density) }
            addContentView(cv, android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    /** Feeds the stroke; true when this UP ended a drawn SHAPE (long and curved), which the swipe detector must not also see. */
    private fun trackStroke(ev: android.view.MotionEvent): Boolean {
        val rec = com.diegonmarcos.superapp.launcher.ShapeRecogniser
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN -> {
                strokePts.clear(); strokePts += ev.x to ev.y; trailState.begin()
                strokeTracking = currentSection == "home" && supportFragmentManager.backStackEntryCount == 0 &&
                    !drawerLayout.isDrawerOpen(GravityCompat.START)
            }
            android.view.MotionEvent.ACTION_MOVE -> if (strokeTracking) {
                strokePts += ev.x to ev.y
                val len = rec.pathLength(strokePts)
                val gap = Math.hypot((ev.x - strokePts[0].first).toDouble(), (ev.y - strokePts[0].second).toDouble())
                val d = resources.displayMetrics.density
                // The trail shows once the stroke is clearly not a straight swipe.
                if (len > 40f * d && gap / len < 0.8) { shapeTrailHost; trailState.add(ev.x, ev.y) }
            }
            android.view.MotionEvent.ACTION_UP -> if (strokeTracking) {
                strokeTracking = false
                strokePts += ev.x to ev.y
                trailState.end()
                val cfg = rec.declared
                if (rec.classify(strokePts, cfg, resources.displayMetrics.density) == com.diegonmarcos.superapp.launcher.ShapeRecogniser.Kind.SHAPE) {
                    val m = rec.recognise(strokePts, cfg)
                    Trace.i(TAG, "shape stroke -> ${m?.id} d=${m?.distance}")
                    if (m != null) {
                        Haptics.tap(bottomNav)
                        handleHomeSwipeAction(com.diegonmarcos.superapp.settings.HomeSwipePrefs(this).shape(m.id, m.action))
                    }
                    return true
                }
            }
            android.view.MotionEvent.ACTION_CANCEL -> { strokeTracking = false; trailState.end() }
        }
        return false
    }

    /** The event the swipe detector sees: a long curved stroke is a shape the recogniser took, so the detector gets a CANCEL
     *  instead of its UP and a circle can not also fire a fling. */
    private fun strokeAwareEvent(ev: android.view.MotionEvent): android.view.MotionEvent {
        if (!trackStroke(ev)) return ev
        return android.view.MotionEvent.obtain(ev).also { it.action = android.view.MotionEvent.ACTION_CANCEL }
    }

    override fun dispatchTouchEvent(ev: android.view.MotionEvent): Boolean {
        val admitted = ::navSwipeGesture.isInitialized &&
            starGestureGate.admits(ev.actionMasked == android.view.MotionEvent.ACTION_DOWN, ev.x, ev.y)
        if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN || ev.actionMasked == android.view.MotionEvent.ACTION_UP)
            Trace.i(TAG, "touch ${ev.actionMasked} x=${ev.x} y=${ev.y} detector=$admitted")
        if (admitted) navSwipeGesture.onTouchEvent(strokeAwareEvent(ev))
        return super.dispatchTouchEvent(ev)
    }


    // ── bottom nav ────────────────────────────────────────────────────────

    private val haptHandler = android.os.Handler(android.os.Looper.getMainLooper())

    private fun onBottomNavPicked(id: String) {
        bottomNav.selectedId = id
        // The island itself fires the section-change haptic rhythm on this tap (FleetHaptics).
        // "Home" fully resets to the home root (pop pages, close drawer) — same as
        // the system HOME button — not just a section switch.
        if (id == "home") resetToHome() else goSection(id, Sections.byId(id)?.label ?: id)
    }

    /** Gemini-like rhythm for any section-change action (bottom-nav tap,
     *  bottom-nav re-tap landing on a new section, horizontal swipe):
     *    t=0     press buzz (the click)
     *    ~250ms  PAUSE (thinking…)
     *    t=250…490ms: 4 LOW_TICKs at 80ms intervals — "the answer"
     *    t=560ms end buzz — "answer done"
     *  Posted on Handler(mainLooper) so the runnables persist even if the
     *  listener view goes away mid-transition. */
    private fun fireGeminiPattern() {
        haptHandler.removeCallbacksAndMessages(null)
        val anchor = bottomNav
        Haptics.gestureStart(anchor)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 250)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 330)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 410)
        haptHandler.postDelayed({ Haptics.segmentTick(anchor) }, 490)
        haptHandler.postDelayed({ Haptics.gestureEnd(anchor) }, 560)
    }

    // ── drawer tab navigation ─────────────────────────────────────────────

    /** The drawer's two-pill strip: [Home] [<current section>]. [index] is the selected one. */
    private var drawerSectionLabel: String = ""

    private fun setDrawerTabs(sectionLabel: String, index: Int) {
        drawerTabs.pages = listOf(
            NavPage(DRAWER_TAB_HOME, getString(R.string.drawer_tab_home), "home"),
            NavPage(DRAWER_TAB_SECTION, sectionLabel, "apps"),
        )
        drawerTabs.selectedId = if (index == 0) DRAWER_TAB_HOME else DRAWER_TAB_SECTION
    }

    private fun onDrawerTabPicked(position: Int) {
        if (position == 0) goHome()
        else {
            // The 2nd tab acts as the current-section "home link". Re-tap
            // also returns the right pane to the section TileGrid.
            goSection(currentSection.ifEmpty { Sections.defaultSectionId() }, currentLabel)
        }
    }

    // ── navigation actions ────────────────────────────────────────────────

    /** Land the right pane on the master Home TileGrid. */

    private fun goHome() = nav.goHome()

    /** Apply launcher-mode chrome based on (isDefaultLauncher, theme):
     *
     *   Cloud Theme + launcher    → hide system status bar, show our
     *                               the status strip (Time +
     *                               Battery), hide nothing else.
     *   Minimalist Black + launcher → hide system status bar AND hide
     *                               toolbar island + bottom nav (pure
     *                               terminal screen).
     *   Not launcher              → show system status bar, hide our
     *                               strip, leave toolbar + bottom nav
     *                               at their normal visibility.
     *
     * Idempotent — safe to call from onResume, theme changes, and
     * goHome / goSection. */
    override fun applyLauncherChrome() {
        val isLauncher = isDefaultLauncher()
        val theme = LauncherThemePrefs(this).theme
        val strip = findViewById<View>(R.id.launcher_status_strip)
        val toolbarIsland = findViewById<View>(R.id.toolbar_island)
        val bottomNavIsland = findViewById<View>(R.id.bottom_nav_island)

        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        if (isLauncher) {
            controller.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
            // Samsung One UI re-shows the system bars on the slightest
            // movement unless we pin the "swipe to reveal transiently"
            // behaviour. Without this, the system status bar paints back
            // over our strip the moment the user touches anything.
            controller.systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            controller.show(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        }
        when {
            isLauncher && theme == LauncherTheme.Cloud -> {
                // OWN the status-bar zone — when in launcher mode the
                // system bars are HIDDEN (`controller.hide(statusBars())`
                // above), so the OS dispatches sys.top = 0 to the insets
                // listener and shell_linear's paddingTop stays 0. That
                // means the strip's frame already sits at screen y=0; we
                // do NOT want a negative topMargin (that would push it
                // off-screen above the visible area — the bug the user
                // saw in c062214). Pick the strip height: use the cached
                // real top inset only if it was ever observed > 0 (i.e.
                // the listener fired while the system bars happened to
                // be momentarily visible — typically the very first
                // dispatch before hide() takes effect), otherwise fall
                // back to the resource baseline. Both give the inner row
                // enough vertical room for 13sp text + 15dp battery icon.
                strip?.let {
                    val barH = if (topSystemInset > 0) topSystemInset else statusBarHeightPx()
                    val lp = it.layoutParams as? android.view.ViewGroup.MarginLayoutParams
                    if (lp != null) {
                        lp.topMargin = 0
                        // WRAP_CONTENT (was fixed barH): the strip now stacks
                        // Line 0 (black band sized to barH) + Line 1 (info row)
                        // + hairline, so its height is the sum, not one barH.
                        lp.height = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
                        it.layoutParams = lp
                    }
                    // Drop the legacy py=3dp padding the view set in init
                    // so the text isn't squeezed against the bottom edge.
                    it.setPadding(it.paddingLeft, 0, it.paddingRight, 0)
                    it.visibility = View.VISIBLE
                }
                // Push the toolbar island BELOW the (now two-line) strip with a
                // 6dp gap. The strip is WRAP_CONTENT, so its final height isn't
                // known until layout: seed with barH (Line 0) for the first
                // pass, then correct to the real measured height post-layout.
                val barH = if (topSystemInset > 0) topSystemInset else statusBarHeightPx()
                setToolbarIslandTopMargin(toolbarIsland, barH + dp(6))
                // The strip is Compose now, and it GROWS after its first layout (its readings arrive after composition: the
                // dots rows, the pets, the clock). A one-shot correction measured it too small and left the toolbar island
                // under the strip - the taller, higher-elevation layer - which then ate the island's taps. Follow its height.
                if (strip != null && toolbarIsland != null) {
                    com.diegonmarcos.superapp.launcher.StripIslandGap.follow(strip, toolbarIsland, dp(6))
                }
                toolbarIsland?.visibility = View.VISIBLE
                bottomNavIsland?.visibility = if (pageOwnsBottomNav()) View.GONE else View.VISIBLE   // #1719 a page with its own bottom nav keeps it
            }
            // No isLauncher here, deliberately. This theme is a mode of this app;
            // gating its chrome on owning the home button meant that on a phone
            // where One UI Home owns it the mode kept the full toolbar and
            // bottom-nav islands, so the "mode" was a repaint. See LauncherNavController.
            theme == LauncherTheme.CloudMinimalistBlack -> {
                // The dark terminal mode — Samsung / Apple / Pixel-style.
                // No top strip and no bottom-nav island anywhere, and
                // the window background is pure black for OLED
                // self-emission savings (the oled_black feature flag
                // drives this).
                strip?.visibility = View.GONE
                bottomNavIsland?.visibility = View.GONE

                // ...but the toolbar island is NOT decoration: it is
                // where action_back lives. Hiding it on every screen
                // turned the mode into a trap with no way out of any
                // submenu — the owner reported exactly that ("as you
                // remove the top buttons nothing now has a back
                // button"). So it goes away only on the mode's OWN home
                // screen, which draws its own header, and comes back on
                // every other section. It no longer drags the default
                // look in with it: since LauncherStyle installs this
                // mode's Material3 style on the Activity, the island
                // resolves its `?attr/` colours against this mode.
                val atHomeRoot = currentSection == "home" &&
                    supportFragmentManager.backStackEntryCount == 0
                toolbarIsland?.visibility = if (atHomeRoot) View.GONE else View.VISIBLE
                if (!atHomeRoot) {
                    // In launcher mode the system status bar is hidden, so
                    // sys.top is dispatched as 0 and nothing reserves that
                    // strip of screen — the island would sit under the
                    // camera cutout. Reserve it ourselves.
                    val barH = if (topSystemInset > 0) topSystemInset else statusBarHeightPx()
                    setToolbarIslandTopMargin(
                        toolbarIsland, (if (isLauncher) barH else 0) + dp(6),
                    )
                }
            }
            else -> {
                strip?.visibility = View.GONE
                // Restore the XML default — system status bar is visible
                // here, DrawerLayout pads the content by sys.top, so a
                // 6dp gap from THAT padded edge is already correct.
                setToolbarIslandTopMargin(toolbarIsland, dp(6))
                toolbarIsland?.visibility = View.VISIBLE
                bottomNavIsland?.visibility = if (pageOwnsBottomNav()) View.GONE else View.VISIBLE   // #1719 a page with its own bottom nav keeps it
            }
        }
        // The backdrop every fragment is drawn over, from the theme's own
        // palette. Unconditional, and that is the point: it used to be one
        // `setBackgroundColor(Color.BLACK)` inside a single dark-mode arm and
        // nothing in any other arm, so switching AWAY from that mode left
        // the decor black — the gradient did not come back until the process
        // was killed. A surface painted in one branch has to be repainted in
        // all of them, which is what a palette read outside the `when` gives.
        // The row may have moved or been shown/hidden above: re-seat the content hosts under it.
        com.diegonmarcos.superapp.ui.ShellTopInset.apply(findViewById(android.R.id.content))
        window.setBackgroundDrawableResource(LauncherPalette.forTheme(this, theme).windowRes)

        // Apply the theme's background_pause / wireguard_required
        // policy. Reads features map declared in build.json::ui
        // .launcher_themes[theme].features so this stays declarative —
        // a theme with background_pause=true tears down the Maps
        // tracker + WorkManager periodic jobs; flipping back to Cloud
        // is a no-op (producers re-arm on their own lifecycle).
        runCatching {
            BackgroundOrchestrator.applyForTheme(this, LauncherThemes.featuresFor(theme))
        }
        // Profile-level WireGuard policy — Guest profile pins the
        // tunnel OFF so a borrowed phone can't see private infra.
        runCatching {
            val profile = LauncherProfilePrefs(this).profile
            val behavior = LauncherProfiles.behaviorFor(profile)
            if (behavior.wireguardOff) {
                WgState.requestTunnelDown(this)
            }
        }
    }

    /** dp → px convenience for chrome math. */
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    /** Parse the island action (launcher/DynamicIslandAction reads it) and dispatch. Formats:
     *   • `app:<packageName>`  → launch that app's main activity. For a
     *     THIRD-PARTY package, whose id is the only handle we have on it.
     *   • `extapp:<id>`        → resolve `id` in build.json::ui.external_apps
     *     and hand it to [launchExternalApp], which walks fork → hub → alt
     *     and then OFFERS THE INSTALL from the entry's install_apk_url. This
     *     is the form a FLEET app must use: `app:` pins one literal package
     *     and can do nothing but apologise when it is missing, which is how
     *     the island spent #675 bound to an upstream terminal id that no
     *     fleet build installs.
     *   • `shortcut:<id>`      → fire a shortcut-style intent (currently
     *     unused, reserved for future bindings like `shortcut:wallet`).
     *  Tolerant of an uninstalled target — shows a short Toast instead of
     *  silently falling back, so the user's binding stays authoritative.
     *
     *  There is no blank-value default here, and there must never be one
     *  again: the previous `ifBlank { "notifications" }` named the
     *  launcher's drop-down shade, the second notification centre #515
     *  deleted. The forms are declared in
     *  build.json::ui._vocab_dynamic_island_action and app/build.gradle
     *  fails the build on an absent, blank or unlisted value, so by the
     *  time this reads BuildConfig the value is already one of the
     *  branches below. */
    private fun dispatchDynamicIslandAction() {
        com.diegonmarcos.superapp.launcher.DynamicIslandAction.dispatch(
            this, launchExternalApp = { launchExternalApp(it) }, shortcut = { handleShortcutById(it) },
        )
    }

    /** Best-effort shortcut dispatch — re-uses [handleShortcutIntent]'s
     *  vocabulary by synthesising an intent with the shortcut_action extra.
     *  Lets `ui.dynamic_island_action = "shortcut:wallet"` reach the same
     *  code path as the launcher-icon long-press shortcuts. */
    private fun handleShortcutById(id: String) {
        val syn = android.content.Intent().apply { putExtra("shortcut_action", id) }
        handleShortcutIntent(syn)
    }

    /** Apply a topMargin to the toolbar island. Tolerant of a null
     *  view + non-margin layout params (returns silently). */
    private fun setToolbarIslandTopMargin(island: View?, marginPx: Int) {
        val lp = island?.layoutParams as? android.view.ViewGroup.MarginLayoutParams ?: return
        if (lp.topMargin != marginPx) {
            lp.topMargin = marginPx
            island.layoutParams = lp
        }
    }

    /** Called by [LauncherConfigFragment] right after writing a new
     *  theme into [LauncherThemePrefs]. Re-applies the launcher chrome
     *  and, if the user is currently on Home, rebuilds the home pane
     *  so a Minimalist Black ↔ Cloud swap takes effect immediately. */
    fun notifyLauncherThemeChanged() {
        // A mode is more than a palette: it also names a Material3 style, and
        // Android resolves a style ONCE per Activity, at inflate time. Every
        // XML layout in the app has already resolved its `?attr/` colours by
        // the time we get here, so re-running the chrome would recolour the
        // code-drawn views and leave all 24 XML surfaces in the old mode —
        // which is exactly the "submenus still same style as default" report.
        // Recreating is not a workaround for that, it IS the mechanism.
        //
        // The palette cache is process-scoped, and recreate() does not end the
        // process — so it is dropped here, before the new Activity can read it.
        // It keys on the theme id and would notice the switch on its own, but
        // relying on that makes correctness depend on a detail of the cache
        // rather than on the switch itself.
        LauncherPalette.invalidate()
        com.diegonmarcos.superapp.ui.LauncherStyle.restartForModeChange(this)
    }

    /** Configs → Launcher → Others — re-applies settings whose views live in the
     *  activity shell (not recreated on a chrome re-render), so toggling them
     *  takes effect LIVE: stars (galaxy backdrop) + animal pets, plus device-wide
     *  system brightness. Eye protection is the ANDROID SYSTEM night-light (opened
     *  from the picker), not a custom overlay. Cube/haptics re-read themselves. */
    private fun applyLauncherSettings() {
        val prefs = com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this)
        ShellFxState.refresh(this)
        runCatching {
            val b = prefs.brightness
            if (b >= 0 && android.provider.Settings.System.canWrite(this)) {
                android.provider.Settings.System.putInt(contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                android.provider.Settings.System.putInt(contentResolver,
                    android.provider.Settings.System.SCREEN_BRIGHTNESS, b.coerceIn(0, 255))
            }
        }
    }

    /** System status bar pixel height. Reads the platform's
     *  android.R.dimen.status_bar_height resource so we match Samsung /
     *  Pixel / OEM-specific values without guessing. Falls back to 24dp
     *  on the rare device that doesn't expose the dimen. */
    private fun statusBarHeightPx(): Int {
        val resId = resources.getIdentifier("status_bar_height", "dimen", "android")
        return if (resId > 0) resources.getDimensionPixelSize(resId)
               else (24 * resources.displayMetrics.density).toInt()
    }

    /** True when THIS instance is the home screen — i.e. it is the
     *  [HomeActivity] door AND our package is the resolved default Home
     *  handler. Drives launcher chrome (hidden system status bar, the
     *  the status strip, edge-to-edge window).
     *
     *  The `is HomeActivity` half is not redundant. Resolving CATEGORY_HOME
     *  answers a question about the PACKAGE, and since the split into two
     *  doors both activities live in the same package — so without this the
     *  app door would dress itself as the home screen too, hiding the system
     *  status bar and drawing the launcher strip while sitting in a
     *  split-screen half. Mirrored from
     *  [LauncherConfigFragment.isDefaultLauncher], which stays package-level
     *  on purpose: it answers "should we offer the Set-as-default button". */
    override fun isDefaultLauncher(): Boolean {
        if (this !is HomeActivity) return false
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).apply {
            addCategory(android.content.Intent.CATEGORY_HOME)
        }
        val resolved = packageManager.resolveActivity(intent, 0)
        return resolved?.activityInfo?.packageName == packageName
    }

    /** Land the right pane on the given section's TileGrid (or placeholder
     *  if the section has no declared sub-pages). */
    private fun goSection(id: String, label: String, initialPage: String = "") =
        nav.goSection(id, label, initialPage)

    /** Public entry so transient UI (e.g. the status-strip network popup's
     *  KDE Connect row) can navigate to a section. */
    fun openSection(id: String) = goSection(id, Sections.byId(id)?.label ?: id)

    override fun syncBottomNav(sectionId: String) {
        // Only the bar's own sections move the pill; any other section leaves it where it was.
        if (sectionId in Sections.bottomNavIds()) bottomNav.selectedId = sectionId
    }

    override fun syncDrawerTab(index: Int) {
        // PageTabsView.selectedId only moves the pill and never calls back, so no re-entry guard.
        if (index == 1) drawerSectionLabel = currentLabel   // the second pill names the section last opened
        setDrawerTabs(drawerSectionLabel, index)
        showDrawerPage(if (index == 0) DrawerPage.HOME else DrawerPage.SECTION)
    }

    // ── NavHost view-mechanism (orchestrated by LauncherNavController) ───
    override fun navContext(): android.content.Context = this
    override fun setSectionTitle(label: String) = Unit   // the bar carries no title; the Dynamic Island carries identity
    override fun invalidateMenu() = refreshToolbar()
    override fun dispatchTarget(target: String) = onTileClicked(target)
    override fun tabHaptic() = Haptics.tap(bottomNav)
    override fun closeDrawerIfOpen() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
    }
    override fun applyMode(mode: String) {
        modePrefs.mode = mode
        refreshBottomNavIconsForMode()
        refreshToolbar()
    }
    /** sw600dp tablet → master-detail split (see view_content_panes.xml).
     *  Declarative breakpoint via the bool resource qualifier — no dp math. */
    private val twoPane: Boolean by lazy { resources.getBoolean(R.bool.two_pane) }
    /** The detail pane only exists in the tablet layout; null on phones. */
    private val hasDetailPane: Boolean get() = findViewById<View?>(R.id.detail_container) != null
    override fun isTwoPane(): Boolean = twoPane && hasDetailPane

    override fun pushContent(content: Fragment, tag: String?) {
        // Tablet master-detail: opened pages render in the right-hand DETAIL
        // pane while the master (section grid) stays visible on the left.
        // They still go on the shared back stack so Back pops the detail
        // page first, exactly like the phone single-pane flow.
        // …but only while that pane is actually on screen. It is collapsed to
        // GONE on Home and under a self-splitting tabbed section, and a
        // fragment committed into a GONE container renders nowhere.
        val detailShown = findViewById<View?>(R.id.detail_container)?.visibility == View.VISIBLE
        val target = if (isTwoPane() && detailShown) R.id.detail_container
                     else R.id.fragment_container
        // Re-opening a page already somewhere on the back stack (e.g. the
        // user drills Configs ▸ AI ▸ Back-to-grid ▸ AI again, or a drawer tap
        // lands on the page already showing) used to ADD a second live
        // instance on top instead of returning to the first — the old one
        // never got destroyed, sat STOPPED in the back stack, and its
        // registerForActivityResult launchers (AiFragment's import/export
        // pickers, WireGuardFragment's, ProfileFragment's, …) stayed
        // registered forever. Over a long session that grows the Activity's
        // saved-state Bundle past Binder's ~1MB transaction limit and the
        // next onStop() crashes with TransactionTooLargeException. Popping
        // any existing slot for this same logical page first bounds the
        // back stack to one entry per distinct page, no matter how many
        // times it's revisited.
        if (tag != null) supportFragmentManager.popBackStackImmediate(tag, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        supportFragmentManager.beginTransaction()
            .setTransition(FragmentTransaction.TRANSIT_FRAGMENT_OPEN)
            .replace(target, content)
            .addToBackStack(tag)
            .commit()
    }
    override fun recordSection(id: String, label: String, icon: String) {
        runCatching { com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).recordSection(id, label, icon) }
    }
    override fun recordPage(sectionId: String, pageId: String, label: String, icon: String) {
        runCatching {
            com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                .recordPage(sectionId, pageId, label = label, iconName = icon)
        }
    }
    override fun recordTarget(target: String, label: String, icon: String) {
        runCatching {
            com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                .recordTarget(target, label = label, iconName = icon)
        }
    }

    /** True while a [swapContent] section change is mid-flight — the
     *  clearBackStack pop fires the OnBackStackChangedListener BEFORE the
     *  content replace runs, so without this guard the listener sees the old
     *  Home3DFragment + an empty stack and wrongly resets currentSection to
     *  "home". Cleared via runOnCommit after the new content is in place. */
    private var inSectionSwap = false

    override fun swapContent(content: Fragment, clearBackStack: Boolean) {
        if (clearBackStack) {
            inSectionSwap = true
            supportFragmentManager.popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE)
        }
        // applyChrome before commit — runOnCommit is mutually exclusive with
        // addToBackStack and we want a single uniform path. The OnBackStack-
        // ChangedListener installed in onCreate handles re-apply on back.
        applyChrome(content)
        // Custom slide-fade animation gives the haptic SEGMENT_TICK
        // pulses something visual to ride on.
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.fade_in,  R.anim.fade_out,
                R.anim.fade_in,  R.anim.fade_out,
            )
            .replace(R.id.fragment_container, content)
            // Clears AFTER the replace commits — by then the stale-Home3D pop
            // listener has already fired (and been skipped). A no-op swap with
            // clearBackStack=false never sets the flag, so this is safe.
            .runOnCommit { inSectionSwap = false }
            .commit()
        if (isTwoPane()) syncDetailPaneForMaster(content)
    }

    /**
     * Keep the tablet DETAIL pane consistent with the freshly-swapped MASTER:
     *   • Home → collapse the detail column (GONE). The master is then the
     *     only visible weighted child, so it expands to fill the full width
     *     and Home renders edge-to-edge like on phones.
     *   • Any section → show the detail column with the "Select an item"
     *     placeholder, ready for the first page the user opens from the grid.
     * Called only when [isTwoPane]; uses [currentSection] which the
     * controller sets before swapContent.
     */
    private fun syncDetailPaneForMaster(master: Fragment) {
        val detail = findViewById<View>(R.id.detail_container) ?: return
        val divider = findViewById<View?>(R.id.pane_divider)
        // A tabbed section splits ITSELF — its tab strip has to span the FULL
        // width with one pane per page underneath, so the activity's 40/60
        // column would cut it in half. Collapse the detail column exactly the
        // way Home does and let the master expand edge-to-edge. Tested on the
        // fragment we were handed, not on findFragmentById: swapContent's
        // commit() is async, so the container still holds the OLD master here.
        if (currentSection == "home" || master is SectionTabsFragment) {
            detail.visibility = View.GONE
            divider?.visibility = View.GONE
        } else {
            detail.visibility = View.VISIBLE
            divider?.visibility = View.VISIBLE
            supportFragmentManager.beginTransaction()
                .replace(R.id.detail_container, DetailPlaceholderFragment.newInstance())
                .commitAllowingStateLoss()
        }
    }

    /** Edge-to-edge insets handler. Pads the AppBar (top inset → status
     *  bar) and the BottomNav (bottom inset → gesture nav). The
     *  drawer pulls insets onto its own panel via the framework default. */
    private fun applyEdgeToEdgeInsets() {
        // Pad the SHELL LinearLayout itself with status-bar inset (top) +
        // gesture-nav inset (bottom). The LinearLayout then represents
        // the SAFE AREA — the AppBarLayout sits at the top of it, the
        // bottom_nav_island sits at the bottom of it. Neither island can
        // be eaten by a system bar because they're physically constrained
        // inside the padded region.
        val shell = findViewById<View>(R.id.shell_linear)
        val galaxy = findViewById<View>(R.id.galaxy_backdrop)
        ViewCompat.setOnApplyWindowInsetsListener(shell) { v, insets ->
            val sys = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.updatePadding(top = sys.top, bottom = sys.bottom)
            bottomSystemInset = sys.bottom
            topSystemInset = sys.top
            // The galaxy view needs to extend INTO the padded inset zones
            // so the stars / comets fill the status + nav bar area too.
            // Negative margins push it past the shell's padding boundary;
            // shell_linear has clipChildren=false + clipToPadding=false so
            // the overflow renders instead of being chopped.
            (galaxy?.layoutParams as? android.widget.FrameLayout.LayoutParams)?.also { lp ->
                lp.topMargin = -sys.top
                lp.bottomMargin = -sys.bottom
                galaxy.layoutParams = lp
            }
            // Re-apply the launcher chrome now that the REAL top inset is
            // known — the first applyLauncherChrome() call in onCreate ran
            // before this listener fired, so it used the stale-but-safe
            // resource fallback. Refresh now with the actual Samsung /
            // Pixel / OEM-reported height so the strip sits at y=0.
            applyLauncherChrome()
            insets
        }
    }

    /** #1719 True when the page on screen (a modal overlay first) draws its own bottom nav ([ShellOverride.ownsBottomNav]): Configs > Network and NG Firewall. */
    private fun pageOwnsBottomNav(): Boolean {
        val top = supportFragmentManager.findFragmentById(R.id.overlay_container) ?: supportFragmentManager.findFragmentById(R.id.fragment_container)
        return (top as? ShellOverride)?.ownsBottomNav() == true
    }

    /**
     * Take-over contract: a content Fragment that implements [ShellOverride]
     * can hide our shell chrome so it renders its own. Default (no interface
     * implementation) keeps everything visible.
     */
    override fun applyChrome(fragment: Fragment) {
        val override = fragment as? ShellOverride
        val ownsToolbar = override?.ownsToolbar() ?: false
        val ownsBottom  = override?.ownsBottomNav() ?: false
        findViewById<View>(R.id.toolbar_island).visibility =
            if (ownsToolbar) View.GONE else View.VISIBLE
        // Hide the WHOLE bottom-nav-island card when a fragment owns its
        // own bottom chrome, so the fragment_container's
        // bottom_toTopOf constraint collapses to parent.bottom and the
        // fragment fills the full height (ConstraintLayout handles
        // GONE views by reducing them to their constrained edge).
        findViewById<View>(R.id.bottom_nav_island).visibility =
            if (ownsBottom) View.GONE else View.VISIBLE
    }

    // ── drawer pagination (internal pages) ────────────────────────────────

    private enum class DrawerPage { HOME, SECTION }

    private fun showDrawerPage(page: DrawerPage) {
        when (page) {
            DrawerPage.HOME -> {
                supportFragmentManager.beginTransaction()
                    .replace(R.id.drawer_content, HomeDrawerFragment.newInstance())
                    .commitAllowingStateLoss()
            }
            DrawerPage.SECTION -> {
                // Drawer's section pane is a flat list of:
                //   • sub-pages (build.json::ui.sections[X].pages[]) for
                //     regular sections, OR
                //   • the aggregator's tiles + stack-panel titles for
                //     aggregator sections (Suite / Tools / Communication
                //     / Infos), where the page list is empty by design.
                // The chip-row above it is redundant in both cases.
                val sec = Sections.byId(currentSection)
                val pages = SectionPages.pagesFor(currentSection)
                val isAggregator = sec?.isAggregator == true
                val frag: Fragment = when {
                    pages.isNotEmpty() || isAggregator ->
                        SectionMenuFragment.newInstance(currentSection)
                    else -> PlaceholderDrawerFragment.newInstance(currentLabel)
                }
                supportFragmentManager.beginTransaction()
                    .replace(R.id.drawer_content, frag)
                    .commitAllowingStateLoss()
            }
        }
    }

    private fun showDrawerSectionPage(page: SectionPages.Page) {
        // Pages that are pure action launchers (keyboard, update, import…) carry
        // an action string from build.json. Dispatch via onTileClicked so they
        // behave identically whether reached from the tab strip or a drawer item.
        if (page.action.isNotBlank()) {
            onTileClicked(page.action)
            return
        }
        supportFragmentManager.beginTransaction()
            .replace(R.id.drawer_content, page.factory())
            .commitAllowingStateLoss()
    }

    /**
     * Launch a URI tile target, honouring the `intent://#Intent;…;end`
     * convention's `S.browser_fallback_url` extra. The standard Android
     * stack does NOT auto-fall-back when the target app isn't installed
     * — Chrome implements that itself. We replicate that behaviour:
     *
     *   1. Parse the URI.
     *   2. Try startActivity.
     *   3. On ActivityNotFoundException (or any throwable) check for
     *      `browser_fallback_url` String extra and launch that as a
     *      plain ACTION_VIEW.
     *   4. If still nothing fires, surface the failure as a snack so
     *      taps don't fail silently.
     */
    internal fun launchUri(uri: String) {
        if (uri.isBlank()) return

        // http(s):// → the app's OWN embedded browser, the same
        // [WebPageFragment] Cloud ▸ Linktree renders in.
        //
        // This used to hand every web URL to the Cloud-Browser APK by
        // package-targeted ACTION_VIEW, which meant a plain link tile —
        // MySocials, PM Boards — took the user OUT of superapp on the first
        // tap, and when Cloud-Browser was not installed it triggered an APK
        // download that dropped the URL on the floor. A link with no
        // app-specific handler is content this app can render itself, so it
        // renders it: no task switch, no install prompt, no lost URL.
        //
        // The distinction that matters is handler-specific vs. plain web, not
        // which page the tile happens to sit on — so this is the branch that
        // changed, not the two pages that complained. Everything below still
        // leaves: app:// resolves a package, and Intent.parseUri covers
        // intent:// and every custom scheme, all of which name an app that
        // no WebView can stand in for.
        if (uri.startsWith("http://") || uri.startsWith("https://")) {
            openEmbeddedBrowser(uri)
            return
        }

        // Custom scheme: app://<package>?fallback=<encoded-url>
        // → open the package's main launcher activity via PackageManager.
        //   getLaunchIntentForPackage. This actually fires the app's home
        //   screen (not ACTION_VIEW which most apps don't filter for).
        //   Falls back to the URL via ACTION_VIEW if the app isn't
        //   installed.
        //   Both spellings arrive here: `app://com.x?fallback=…` carries an
        //   authority Uri.parse can read, and `app:com.x` is the bare opaque form
        //   the launcher themes' home_apps use. For the opaque one host and
        //   authority are both null, so the package is its scheme-specific part.
        if (uri.startsWith("app:")) {
            val u = android.net.Uri.parse(uri)
            val pkg = u.host ?: u.authority ?: u.schemeSpecificPart?.trimStart('/')
            // Opaque ("app:com.x") has no query to read, and asking anyway throws
            // UnsupportedOperationException — which would turn a missing app into
            // a crash instead of a fallback.
            val fallback = if (u.isOpaque) null else u.getQueryParameter("fallback")
            // #1453 The SuperApp's own tile, in launcher mode: no second door. Launching MainActivity from the home screen
            // opened Home in its own task with the system status bar; the tile means Configs, in this home screen.
            if (pkg != null && isDefaultLauncher()) {
                com.diegonmarcos.superapp.launcher.SelfTile.routeFor(packageName, pkg)?.let { routeTarget(it); return }
            }
            val launch = pkg?.let { packageManager.getLaunchIntentForPackage(it) }
            if (launch != null) {
                // #1153 NEW_TASK gives the app its own task; LAUNCH_ADJACENT puts it beside this one when the
                // window is split (ignored outside multi-window), so the Configs page can stay open next to it.
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT)
                runCatching {
                    val label = runCatching {
                        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
                    }.getOrDefault(pkg)
                    com.diegonmarcos.superapp.apptabs.AppTabPrefs(this)
                        .recordExternalApp(pkg, label)
                    startActivity(launch); return
                }
            }
            if (!fallback.isNullOrBlank()) {
                val fb = android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(fallback),
                ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
                runCatching { startActivity(fb); return }
            }
            findViewById<View>(R.id.fragment_container).snack("App not installed: $pkg")
            return
        }

        // http(s), obsidian://, intent://… — Intent.parseUri.
        val parsed: android.content.Intent? = runCatching {
            android.content.Intent.parseUri(uri, android.content.Intent.URI_INTENT_SCHEME)
        }.getOrNull()
        if (parsed == null) {
            findViewById<View>(R.id.fragment_container).snack("Bad URI: $uri")
            return
        }
        val fallback = parsed.getStringExtra("browser_fallback_url")
        parsed.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val primary = runCatching { startActivity(parsed); true }.getOrDefault(false)
        if (primary) return
        if (!fallback.isNullOrBlank()) {
            val fb = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(fallback),
            ).apply { addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK) }
            val ok = runCatching { startActivity(fb); true }.getOrDefault(false)
            if (ok) return
        }
        findViewById<View>(R.id.fragment_container).snack("No app handles: $uri")
    }

    /**
     * Render a web URL inside superapp, on the back stack, as an ordinary
     * page. Same push the drawer's Business Card and every section page use,
     * so Back, the toolbar and the tablet detail pane all behave here exactly
     * as they do everywhere else — the embedded browser is a page, not a mode.
     *
     * Tagged by URL so re-tapping the same link returns to the copy already
     * open instead of stacking a second live WebView behind the first; see
     * [pushContent] for what an untagged re-open costs.
     */
    private fun openEmbeddedBrowser(url: String) {
        val frag = WebPageFragment.newInstance(url)
        if (!isTwoPane()) applyChrome(frag)
        pushContent(frag, "web:$url")
    }

    /**
     * Open a companion app declared in build.json::ui.external_apps.
     *
     * Grammar: `extapp:<appId>/<forkKey>#<target>` (both optional).
     *
     * The `#<target>` half is a destination INSIDE the companion app, split off
     * first so it can never be mistaken for part of a fork key — the same
     * fragment convention `page:c3/observability#ntfy` already uses in
     * [onTileClicked]. It travels as the `shortcut_action` extra, which is the
     * extra THIS app reads on its own launcher intent
     * ([handleShortcutIntent]), carrying the same target grammar. So a
     * constellation app that wants to be deep-linkable implements the handler
     * it would have needed for its own shortcuts and nothing else; Cloud-Me is
     * the first, for the Fin and Health dashboard tiles. An app that ignores
     * the extra simply opens at its own front door, which is what every
     * fragment-less target already does.
     *
     * SINGLE_TOP|CLEAR_TOP only when a target is present: a plain launch must
     * keep resuming the companion app exactly where the user left it, but a
     * deep link that silently resumed some other page would be a dead icon
     * wearing a live one's clothes.
     *
     * Resolution, in priority order — first hit wins:
     *   1. the specific fork package (forks[forkKey]),
     *   2. the hub package (hub_package),
     *   3. neither installed → download + install install_apk_url (a direct
     *      APK URL — Cloud-Comms-Hub.apk on a GitHub release) targeting
     *      install_package, via libs:updater's PackageInstaller flow.
     *
     * Per-fork launch degrades to the hub automatically: in Cloud-Comms'
     * one-icon model the forks ship without a launcher activity, so
     * getLaunchIntentForPackage returns null for them and we fall through
     * to the hub switcher — exactly the intended behaviour.
     */
    /** The right corner orb's TARGET - build.json::ui.top_orbs.right, a tile target in the one
     *  grammar (`section:<id>`, `extapp:<id>`), baked as UI_TOP_ORBS_B64 and dispatched by
     *  [onTileClicked] like any tile. Blank (missing declaration) disables the orb's tap rather
     *  than guessing a destination: the target has moved twice already (cloud-wallet, cloud-me,
     *  now the Configs section) and a hardcoded fallback here is how it would silently move back. */
    private fun topOrbTarget(): String = runCatching {
        org.json.JSONObject(String(android.util.Base64.decode(
            BuildConfig.UI_TOP_ORBS_B64, android.util.Base64.NO_WRAP)))
            .optString("right")
    }.getOrDefault("")

    /** The orb wears its target: a `section:` target's declared icon and label (content description);
     *  an app target keeps the layout's own glyph and caption. */
    private fun bindTopOrb() {
        val section = topOrbTarget().takeIf { it.startsWith("section:") }
            ?.let { Sections.byId(it.removePrefix("section:")) } ?: return
        findViewById<android.view.View>(R.id.top_wallet_orb)?.contentDescription = section.label
        com.diegonmarcos.superapp.ui.ShellToolbarState.walletIcon = Sections.iconResFor(this, section.iconName)
    }

    private fun launchExternalApp(rawPayload: String) {
        val payload = rawPayload.substringBefore(StackAnchors.FRAGMENT)
        val deepTarget = rawPayload.substringAfter(StackAnchors.FRAGMENT, "")
            .takeIf { it.isNotBlank() }
        val parts = payload.split("/", limit = 2)
        val app = Sections.externalApp(parts[0]) ?: run {
            findViewById<View>(R.id.fragment_container).snack("Unknown app: ${parts[0]}")
            return
        }
        val forkKey = parts.getOrNull(1)?.takeIf { it.isNotBlank() }
        // Most-specific installed package first: the fork, the hub, then the stock-resigned alt id.
        if (com.diegonmarcos.superapp.launcher.ExternalAppLaunch.launchInstalled(this, app, forkKey, deepTarget)) return
        // Nothing installed → install the companion APK (user-confirmed).
        // Already downloading/installing it: the tap opens Cancel / Restart / Details instead.
        if (app.installPackage.isNotBlank() &&
            com.diegonmarcos.superapp.extinstall.ExtTileRing.handleBusyTap(this, app.installPackage)) return
        if (app.installApkUrl.isBlank() || app.installPackage.isBlank()) {
            findViewById<View>(R.id.fragment_container)
                .snack(getString(R.string.phone_install_no_source, app.label))
            return
        }
        // ABI. build.json::ui.external_apps gives every companion exactly ONE
        // flat install_apk_url, and it is always the arm64 asset because that
        // is the first variant in each app's build.json. Cloud Writer is the
        // first companion published in two variants (Cloud-Writer.apk /
        // Cloud-Writer-x86_64.apk), so on an x86_64 device that flat URL is
        // the half-way ABI dimension all over again — the same defect
        // Fleet.App.abiReleaseUrl was written to close on the update path,
        // reappearing on the FIRST-INSTALL path, which had never carried an
        // ABI at all. It ends in INSTALL_FAILED_NO_MATCHING_ABIS, after the
        // user has accepted the install.
        //
        // The per-ABI asset names are NOT restated in external_apps. They are
        // already in data/constellation-fleet.json, which regen.sh generates
        // from each app's own release.variants[] — so this asks the manifest
        // rather than adding a fourth place to declare an app's identity, and
        // it selects with AbiUpdateTag, the ONE ABI rule the updater owns.
        //
        // Fails closed to the declared URL at every step: no fleet entry, no
        // assets map, a URL that does not end in the fleet's own asset name,
        // or no ABI match all return install_apk_url byte for byte. Every
        // entry that works today has an empty assets map and is unchanged.
        val fleetApp = runCatching {
            com.diegonmarcos.superapp.updater.Fleet
                .parse(BuildConfig.CONSTELLATION_FLEET_B64)
                .firstOrNull { it.pkg == app.installPackage }
        }.getOrNull()
        val installUrl = fleetApp
            ?.takeIf { it.asset.isNotBlank() && it.assets.isNotEmpty() }
            ?.takeIf { app.installApkUrl.endsWith("/${it.asset}") }
            ?.let { f ->
                val picked = com.diegonmarcos.superapp.updater.AbiUpdateTag
                    .currentFrom(f.assets, f.asset)
                if (picked == f.asset) null
                else app.installApkUrl.removeSuffix(f.asset) + picked
            }
            ?: app.installApkUrl
        com.diegonmarcos.superapp.extinstall.ExtAppInstalls.start(
            this, app.installPackage, app.label, installUrl,
        )
        findViewById<View>(R.id.fragment_container)
            .snack(getString(R.string.phone_install_downloading, app.label))
    }

    // ── tile click dispatch ──────────────────────────────────────────────

    override fun onTileClicked(tileId: String) {
        Trace.i(TAG, "onTileClicked tileId=$tileId")
        Haptics.tap(bottomNav)
        // If the click came from the drawer (SectionMenuFragment tile row,
        // HomeDrawerFragment action row, etc.) close the drawer first so
        // the user sees the content surface, not the drawer overlay.
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        }
        // Suite → Cloud "Recently Used" smart folder — the one real
        // per-tile signal Cloud tiles have (no pin/favorite/most-used
        // tracking exists otherwise). Recorded here, the single dispatch
        // chokepoint, so every surface that fires a tile click
        // contributes, not just the Suite page itself.
        RecentCloudTiles.recordOpen(this, tileId)
        // App Tabs capture — the single dispatch chokepoint. `action:` targets
        // are real "pages" (Constellation, Suite·All) but aren't recorded by
        // goSection/openSectionPage, so they never reached the Tabs shelf. If
        // the target is a named page in the build.json::ui.pages registry,
        // record it here. `section:`/`page:`/`extapp:` are deliberately
        // excluded — their own paths already record.
        if (tileId.startsWith("action:")) {
            runCatching { Pages.byTarget(tileId)?.let { recordTarget(tileId, it.label, it.icon) } }
        }
        routeTarget(tileId)
    }

    /**
     * THE ROUTER, WITHOUT THE CLICK — everything above this point in
     * [onTileClicked] is what a TAP means (it was felt, it was logged, it
     * closed the drawer, it counts as an open), and everything below is what a
     * TARGET means. They are separated because one tap can legitimately re-enter
     * the router, and re-entering through [onTileClicked] made it a second tap.
     *
     * `action:constellation` is that case. Its declaring page
     * (build.json::sections[config].pages[constellation]) opens a SCREEN, so
     * [dispatchHomeAction] first sends it to [LauncherNavController.openSectionPage]
     * to establish Configs as the back-stack base; that function then dispatches
     * the page's own declared action — the same `action:constellation` — back
     * here to finish the job. Going back in via [onTileClicked] logged a second
     * `onTileClicked`, fired a second [Haptics.tap], and recorded a second open
     * into both the Recently-Used folder and the App-Tabs shelf, 7–49ms after
     * the first, for one tap. Coming back in HERE, the tap is counted once and
     * the navigation still completes.
     *
     * Grammar: section:X | page:X/Y[#anchor] | action:X | extapp:X | URI | stub:X
     */
    override fun routeTarget(tileId: String) {
        when {
            tileId.startsWith("section:") -> {
                val id = Sections.canonicalId(tileId.removePrefix("section:"))
                if (id == "home") goHome() else goSection(id, Sections.byId(id)?.label ?: id)
            }
            tileId.startsWith("page:") -> {
                // An optional `#<anchor>` fragment travels with the page, the
                // way it does on the web: `page:c3/observability#ntfy` opens
                // the page AND lands on the anchor that page declares. Split
                // it off before the section/page split so a fragment can
                // never be mistaken for part of a page id.
                val raw = tileId.removePrefix("page:")
                val payload = raw.substringBefore(StackAnchors.FRAGMENT)
                val fragment = raw.substringAfter(StackAnchors.FRAGMENT, "")
                // Two forms: "<sectionId>/<pageId>" (deep-link from Home
                // grouped tiles) or just "<pageId>" (within current section).
                val parts = payload.split("/", limit = 2)
                // Old ids (Boards was `infos`; Work-PM / C3-PM became Tasks) resolve here, so a deep link from another
                // app or an older build still lands on the page that replaced it.
                val (section, page) = Sections.canonicalPage(
                    if (parts.size == 2) parts[0] else currentSection,
                    if (parts.size == 2) parts[1] else parts[0])
                // Requested BEFORE navigating: the destination stack consumes
                // it as it builds, and on a tabbed section that build can
                // happen synchronously inside openSectionPage.
                StackAnchors.requestPending("$section/$page", fragment)
                openSectionPage(section, page, null)
            }
            tileId.startsWith("action:") -> dispatchHomeAction(tileId.removePrefix("action:"))
            // extapp:<appId>/<forkKey> — open a companion app (Cloud-Comms),
            // installing it from build.json::ui.external_apps if absent.
            tileId.startsWith("extapp:") -> launchExternalApp(tileId.removePrefix("extapp:"))
            // Any URI with a scheme — http(s), obsidian://, intent://… — is
            // handed off via launchUri so the browser_fallback_url extra
            // (intent:// convention) actually fires when the target app
            // isn't installed.
            //
            // `intent:` is listed explicitly because the ://-contains test
            // misses it: the Intent-scheme URI for an activity with no data
            // (`intent:#Intent;action=android.settings.WIFI_SETTINGS;end`)
            // has no authority, so every system-settings tile fell through
            // this `when` and did nothing at all. Intent.parseUri in
            // launchUri has always understood the form.
            //
            // `app:` is listed for exactly the same reason. `app://com.x` reached
            // launchUri through the ://-contains test, but the authority-less
            // `app:com.x` — which is the spelling the Power Saving theme's
            // home_apps are written in — matched no branch, so ten of its twelve
            // tiles rendered, took the tap, and did nothing.
            tileId.startsWith("http://") || tileId.startsWith("https://") ||
                tileId.startsWith("intent:") || tileId.startsWith("app:") ||
                (tileId.contains("://") && !tileId.startsWith("section:") &&
                 !tileId.startsWith("page:") && !tileId.startsWith("action:") &&
                 !tileId.startsWith("stub:")) -> launchUri(tileId)
            tileId.startsWith("stub:") ->
                findViewById<View>(R.id.fragment_container).snack(tileId)
        }
    }

    /** Action-tile dispatcher — `action_type` values come from
     *  build.json::ui.home_actions[].action_type. URL-shaped action_types
     *  (anything with a `://` separator) hand off to Intent.ACTION_VIEW so
     *  the system picks the installed handler — same scheme dispatch the
     *  tile click router uses. */
    private fun dispatchHomeAction(actionType: String) {
        // An action that OPENS A SCREEN listed by a section (Configs ▸
        // Constellation) must render inside that section, or the toolbar Back
        // and the system gesture both leave for whatever launched it — the
        // Home row, the Home-Apps sheet, the arc menu — instead of Configs.
        // [LauncherNavController.openSectionPage] establishes the section grid
        // as the back-stack base and then dispatches the action right back
        // here, by which point currentSection matches and this is a no-op.
        Sections.screenPageForTarget("action:$actionType")
            ?.takeIf { it.first != currentSection }
            ?.let { (sectionId, pageId) -> openSectionPage(sectionId, pageId); return }
        val anchor = findViewById<View>(R.id.fragment_container)
        when {
            actionType == "check_updates" ->
                // #894 Cloud Store runs the checks; this opens it on the fleet tab (or offers to
                // install it). SuperApp checks nothing and notifies nothing about updates itself.
                routeTarget("page:config/store-cloud")
            actionType == "import_configs" -> {
                // #1693 Import configs lives in Cloud Account now (libs:account is Cloud Account's): the shortcut opens it there,
                // or offers to install Cloud Account, and says so instead of failing silently.
                com.diegonmarcos.superapp.apps.AccountHandoff.openAt(this, "connect", "import-configs")
                    ?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_LONG).show() }
            }
            // Config → Update tile: update EVERY constellation app. Opens
            // Store ▸ Cloud Constellation (per-app progress) and kicks
            // Update-all. The shared update overlay (with Cancel) shows install
            // progress. The Store is an ordinary tabbed page since #563, so
            // this is a page: target, not an action with its own branch here.
            actionType == "update_all" ->
                // #894 Installs are Cloud Store's: this opens it (or the Install Cloud Store button).
                routeTarget("page:config/store-cloud")
            // ── Sirius-star inner-ring actions (build.json::onehand.circular_menu
            //    nodes[Configs].actions). Radial-menu only — deliberately NOT
            //    Configs pages, so the Configs grid and Home row stay unchanged.
            actionType == "kde_connect_now" -> {
                com.diegonmarcos.superapp.kdeconnect.KdeConnectManager.connectFirstAsync()
                findViewById<View>(R.id.fragment_container)?.snack("KDE Connect: connecting…")
            }
            // Configs > Network > Apps Access: Cloud Store's Access section, else the Store's install offer.
            // #1719 Configs > Network > OS Perms: Cloud Store > Access > Android Perms directly, else the install offer.
            actionType == "open_os_perms" -> {
                if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.open(this, "access", "android")) launchExternalApp("cloud-store")
            }
            actionType == "open_apps_access" -> {
                if (!com.diegonmarcos.superapp.apps.CloudStoreHandoff.open(this, "access")) launchExternalApp("cloud-store")
            }
            // #1737 Configs > Network > Proxy: the SuperApp has no proxy page, so the system network settings (the Wi-Fi network's Proxy field), else Settings.
            actionType == "open_proxy_settings" -> {
                val tries = listOf(android.provider.Settings.ACTION_WIRELESS_SETTINGS, android.provider.Settings.ACTION_SETTINGS)
                tries.any { act ->
                    runCatching { startActivity(android.content.Intent(act).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
                }
                Unit
            }
            // Configs > Network > Webserver: the Cloud C3 Webserver app, else localhost in Cloud Browser.
            actionType == "open_webserver" -> {
                if (com.diegonmarcos.superapp.launcher.WebserverLaunch.open(this) ==
                    com.diegonmarcos.superapp.launcher.WebserverLaunch.Opened.NONE) {
                    openEmbeddedBrowser(com.diegonmarcos.superapp.launcher.WebserverLaunch.FALLBACK_URL)
                }
            }
            actionType == "toggle_animations" -> {
                val prefs = com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this)
                val on = !prefs.toggle("all_anim")
                prefs.setToggle("all_anim", on)
                applyLauncherSettings()          // re-applies the live views (stars/waves/pets)
                findViewById<View>(R.id.fragment_container)
                    ?.snack("Animations ${if (on) "on" else "off"}")
            }
            // The Home Apps overlay sheet is gone (it was a stale copy of Cloud ▸ Apps): the
            // old action ids open the real pages. Prefer the page: target in new data.
            actionType == "open_home_apps" -> routeTarget(CLOUD_APPS_TARGET)
            actionType == "open_home_apps_phone" -> routeTarget("page:phone/apps")
            // Cloud Notification Center — same surface the top-bar bell
            // icon opens. Wiring it here means any data-driven entry
            // (tile target / drawer action) can route to it without code.
            // Sirius inner ring "Search" — the same sheet the top-bar search
            // icon and the launcher shortcut open. handleShortcutIntent
            // already routed action:open_search; the star goes through
            // dispatchHomeAction instead, which did not.
            actionType == "open_search" -> openSearchSheet()
            // Sirius inner ring "Camera". Was
            // intent://#Intent;action=android.media.action.STILL_IMAGE_CAMERA;end,
            // which Intent.parseUri turns into that action PLUS `intent://` as
            // the intent's DATA — an action+data pair no camera filter matches,
            // so it resolved to nothing and snacked "No app handles".
            actionType == "open_camera" -> openCamera()
            actionType == "open_screenshots" -> openScreenshots()
            // The Data Manager (libs:datamanager), the dialog Configs > About > Data Usage
            // opens; the Data badge's button lands here.
            actionType == "open_data_manager" -> runCatching {
                com.diegonmarcos.superapp.datamanager.DataUsageDialog()
                    .show(supportFragmentManager, com.diegonmarcos.superapp.datamanager.DataUsageDialog.TAG)
            }
            actionType == "reapply_mail_rules" -> reapplyMailRules(anchor)
            // Configs → Keyboard now hands off to the standalone Cloud-Keyboard
            // app via extapp:cloud-keyboard (ui.external_apps[cloud-keyboard]);
            // the old in-APK keyboard_settings component launch is retired.
            actionType.contains("://") -> launchUri(actionType)
            else -> anchor.snack("action:$actionType")
        }
    }

    // ── MailHost (libs:mail → shell bridge) ──────────────────────────────

    override fun openMailPage(pageId: String, args: Bundle?) {
        openSectionPage("mail", pageId, args)
    }

    /** Generic section-page navigation — used by drawer menu items and the
     *  MailHost bridge. The page fragment is resolved via the per-section
     *  factory in [SectionPages]; mail pages with args (e.g. MESSAGES carrying
     *  folder_id) go through [MailPages.fragmentFor]. */
    fun openSectionPage(sectionId: String, pageId: String, args: Bundle? = null) =
        nav.openSectionPage(sectionId, pageId, args)

    // Every chip that opens the ADB Shell page lands on Configs > ADB Shell, in the frame: App.onCreate installs
    // AdbShellLink.hostOpener (#1682 P3 step 1), so this activity implements no ADB class.

    /** #1274 every feature banner's "Open Permissions" lands on Configs > Setup > Perms (PermsLink carries the highlight). */
    override fun openPermsPage(): Boolean { openSectionPage("config", "perms"); return true }

    // ── HomeDrawerFragment delegate (data-driven from Sections) ──────────

    override fun onDrawerSectionSelected(sectionId: String, label: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        goSection(sectionId, label)
    }

    override fun onDrawerPageSelected(sectionId: String, pageId: String, label: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        // If the page declares an `action` in build.json, dispatch via the
        // same grammar tile clicks use (action: / http: / intent: …) —
        // otherwise the drawer opens a "Coming soon" placeholder for what
        // is meant to be an action tile. See Configs ▸ Update.
        val pageAction = Sections.byId(sectionId)?.pages
            ?.firstOrNull { it.id == pageId }?.action.orEmpty()
        if (pageAction.isNotBlank()) {
            onTileClicked(pageAction)
            return
        }
        when {
            pageId.startsWith("child-") ->
                goSection(sectionId, Sections.byId(sectionId)?.label ?: sectionId)
            // "<parent>/<sub>" compound id from the 2-level drawer. Open the
            // parent page — most parents already list their sub-pages inline
            // (e.g. MailPagePlaceholder for Settings shows all 11 sub-tabs).
            pageId.contains('/') ->
                openSectionPage(sectionId, pageId.substringBefore('/'), null)
            else ->
                openSectionPage(sectionId, pageId, null)
        }
    }

    override fun onDrawerActionSelected(actionType: String) {
        drawerLayout.closeDrawer(GravityCompat.START)
        dispatchHomeAction(actionType)
    }

    override fun onDrawerBusinessCardOpen() {
        // The business card lived in libs:account's Account page, which Cloud Account
        // redesign task 3 deleted: the drawer's identity row now opens Cloud Account
        // (or starts its install when it is absent).
        drawerLayout.closeDrawer(GravityCompat.START)
        com.diegonmarcos.superapp.apps.AccountHandoff.openOrInstall(this)?.let { msg ->
            android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    /** Dynamic Island is now an [com.diegonmarcos.superapp.ui.IslandWave] (purely decorative
     *  animated triple-sine waveform — Samsung-music-island vibe).
     *  The old "{INITIALS} · {Mode}" text label is gone, so this is
     *  intentionally a no-op kept on the existing call sites for any
     *  future re-introduction of text overlay. */
    private fun refreshDynamicIsland() { /* no-op — see IslandWave */ }

    override fun onDrawerModeToggle() {
        // Flip global Apps↔Admin. Don't close the drawer — the user just
        // tapped the identity row and may want to see the flip take
        // effect there, and may keep navigating from the drawer.
        Haptics.tap(drawerLayout)
        modePrefs.toggle()
        refreshDynamicIsland()
        // Bottom-nav icons can be mode-aware (Section.iconForMode); rebind
        // here so Infos/Tools switch glyphs immediately without waiting
        // for a process restart. Same data path drives the home_groups
        // tiles + drawer rows.
        refreshBottomNavIconsForMode()
        // No section re-render: an aggregator's body is its PAGE GRID now, and
        // that is mode-independent. Re-entering goSection here would also kick
        // a tablet's detail pane back to the section's first page.
    }

    /** Refill the bottom-nav island for the current Apps/Admin mode: each item's icon is its
     *  section's iconForMode and its label the section's label, both from build.json, so
     *  renaming a section is a build.json edit. Safe to call repeatedly. */
    private fun refreshBottomNavIconsForMode() =
        ShellBottomNav.configure(bottomNav, modePrefs.mode, findViewById(R.id.fragment_container))

    // ── toolbar (right-side Back action) ─────────────────────────────────

    /** Redraw the top row for the current section and back-stack depth. Called wherever the old options menu was invalidated. */
    private fun refreshToolbar() {
        // anywhere except a clean Home (Home with no back-stack entries is the "root" - back is meaningless there)
        val atHomeRoot = currentSection == "home" && supportFragmentManager.backStackEntryCount == 0
        // Revolut top: at the Home root the full-width bar DISSOLVES - no glass slab, no hamburger in the bar - and the two corner
        // orbs carry menu + wallet as detached circles (the centre islands keep their own pills). Off the root the classic row
        // returns: hamburger | island | back.
        com.diegonmarcos.superapp.ui.ShellToolbarState.atHomeRoot = atHomeRoot
        val orbVis = if (atHomeRoot) android.view.View.VISIBLE else android.view.View.GONE
        findViewById<android.view.View>(R.id.main_menu_orb)?.visibility = orbVis
        findViewById<android.view.View>(R.id.top_wallet_orb)?.visibility = orbVis
        // NO toolbar_island background, EVER. The first orbs commit "restored" bg_liquid_glass here off the Home root - but the
        // layout declares no background on that island at all, so this code was not restoring anything: it was INVENTING a
        // full-width slab that appeared the moment a section was selected. Icons and the centre islands float over the
        // wallpaper on every screen.
    }

    /** The Back control: one level up. */
    private fun goBack() {
        // First, give the visible fragment a chance to handle it internally - Wallet's Compose state machine (cards
        // Selected/Full/Config) wants Back to unwind one Compose step at a time before popping the activity back-stack. Only
        // fragments implementing BackHandler participate; everything else falls through.
        val top = supportFragmentManager.findFragmentById(R.id.fragment_container)
        if ((top as? BackHandler)?.tryHandleBack() == true) return
        // ONE LEVEL up - matches system Back. Single-pop is predictable and composes: tap twice to skip two levels.
        if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else if (!navigateUpToParent() && currentSection != "home") {
            goHome()
        }
    }

    /** Back at a section ROOT (empty back stack): if the current section
     *  declares a parent (build.json::sections[*].parent), navigate UP to it
     *  instead of Home/exit. Data-driven — e.g. WireGuard → Configs. Returns
     *  true when it navigated to a parent. */
    private fun navigateUpToParent(): Boolean {
        if (supportFragmentManager.backStackEntryCount > 0) return false
        val parentId = Sections.byId(currentSection)?.parent ?: return false
        goSection(parentId, Sections.byId(parentId)?.label ?: parentId)
        return true
    }

    override fun onBackPressed() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
            drawerLayout.closeDrawer(GravityCompat.START)
        } else if ((supportFragmentManager.findFragmentById(R.id.fragment_container) as? BackHandler)
                ?.tryHandleBack() == true) {
            // handled in the page — e.g. Cloud ▸ Apps clearing its search back to the full grid,
            // the same chance the toolbar Back already gives it
        } else if (supportFragmentManager.backStackEntryCount > 0) {
            supportFragmentManager.popBackStack()
        } else if (navigateUpToParent()) {
            // handled — landed on the parent section
        } else {
            @Suppress("DEPRECATION")
            super.onBackPressed()
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        // Pressing the system HOME button while we're the default launcher
        // re-delivers our launcher intent here (ACTION_MAIN + CATEGORY_HOME, no
        // shortcut_action). This used to call resetToHome(), so coming back from
        // another app always dumped you on the home page and threw away wherever
        // you actually were. HOME now resumes the last page instead: keep the
        // section and the back stack, and only close the drawer, which is a
        // transient overlay rather than a place you were.
        if (intent.hasCategory(android.content.Intent.CATEGORY_HOME)) {
            if (drawerLayout.isDrawerOpen(GravityCompat.START)) {
                drawerLayout.closeDrawer(GravityCompat.START)
            }
        } else handleShortcutIntent(intent)
    }

    /** Return to a clean Home root: close the drawer, drop any pushed pages and
     *  overlays, and select the home section. Mirrors what a fresh launch shows. */
    private fun resetToHome() {
        if (drawerLayout.isDrawerOpen(GravityCompat.START)) drawerLayout.closeDrawer(GravityCompat.START)
        supportFragmentManager.popBackStackImmediate(null,
            androidx.fragment.app.FragmentManager.POP_BACK_STACK_INCLUSIVE)
        goHome()
    }

    private fun handleShortcutIntent(intent: android.content.Intent?) {
        val target = intent?.getStringExtra("shortcut_action") ?: return
        if (target == "action:open_search") openSearchSheet()
        else onTileClicked(target)
        intent.removeExtra("shortcut_action")
    }

    /** Single source of truth for "show the search sheet": the Home star
     *  (Polaris, action:open_search), Sirius' Search and the launcher
     *  long-press → "Search" shortcut. The sheet is the SuperApp's one search
     *  ([com.diegonmarcos.superapp.search.SearchSheetFragment]), the same
     *  panel Cloud ▸ Apps and the swipe sheet show inline. */
    override fun openSearchSheet() {
        // Don't stack multiple sheets if the user double-taps.
        val tag = com.diegonmarcos.superapp.search.SearchSheetFragment.BACK_STACK_TAG
        if (supportFragmentManager.findFragmentByTag(tag) != null) return
        supportFragmentManager.beginTransaction()
            .setCustomAnimations(
                R.anim.slide_in_up,  R.anim.fade_out,
                R.anim.fade_in,      R.anim.slide_out_down,
            )
            .add(R.id.overlay_container,
                com.diegonmarcos.superapp.search.SearchSheetFragment.newInstance(), tag)
            .addToBackStack(tag)
            .commit()
    }

    /**
     * Open the camera the user actually uses.
     *
     * 1. [MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA] with no data — the
     *    standard "open the camera app" action. The OS resolves it, so
     *    whatever the user set as their default camera is what opens, which on
     *    a Samsung is Samsung's.
     * 2. If nothing answers that (some OEM cameras ship without the filter),
     *    fall through build.json::ui.camera_apps in order and launch the first
     *    package that is installed. Data-driven, so adding an OEM is a JSON
     *    line, not a code change.
     *
     * Package visibility is fine on Android 11+ because the launcher already
     * holds QUERY_ALL_PACKAGES for app discovery.
     */
    /** Open the DCIM/Screenshots folder directly. DocumentsUI tree URI is the
     *  vendor-neutral way in (no root, no gallery-app bucket intents — those
     *  are OEM-specific); ACTION_VIEW on the document URI lands the system
     *  Files UI inside the folder. */
    private fun openScreenshots() {
        val uri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADCIM%2FScreenshots")
        val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
            .setDataAndType(uri, "vnd.android.document/directory")
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(view); true }.getOrDefault(false)) return
        // Some OEM Files apps only answer the generic browse action.
        val browse = android.content.Intent("android.provider.action.BROWSE")
            .setData(uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { startActivity(browse); true }.getOrDefault(false)) return
        findViewById<View>(R.id.fragment_container)?.snack("No file browser handles Screenshots")
    }

    private fun openCamera() {
        val still = android.content.Intent(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        if (packageManager.resolveActivity(still, 0) != null &&
            runCatching { startActivity(still); true }.getOrDefault(false)
        ) return

        for ((pkg, label) in cameraFallbacks()) {
            val launch = packageManager.getLaunchIntentForPackage(pkg) ?: continue
            launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(launch); true }.getOrDefault(false)) {
                com.diegonmarcos.superapp.apptabs.AppTabPrefs(this).recordExternalApp(pkg, label)
                return
            }
        }
        findViewById<View>(R.id.fragment_container)?.snack("No camera app found")
    }

    /** build.json::ui.camera_apps as (package, label), in declared order. */
    private fun cameraFallbacks(): List<Pair<String, String>> = runCatching {
        val arr = org.json.JSONArray(
            String(android.util.Base64.decode(BuildConfig.UI_CAMERA_APPS_B64, android.util.Base64.NO_WRAP)))
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            o.optString("package") to o.optString("label")
        }.filter { it.first.isNotBlank() }
    }.getOrDefault(emptyList())

    // ── DevControlBridge.ActivityHost ────────────────────────────────────

    override fun navIsland() = if (::bottomNav.isInitialized) bottomNav else null

    /** Music-playing mini-island controller (lazy: allocated on first resume). */
    private val musicIsland by lazy { MusicIslandController(this, findViewById(R.id.music_playing_island)) }

    // Foreground energy sampler — fine-resolution watchdog samples while
    // the screen is on / the app is resumed. Background coarse samples
    // come from BatterySessionWorker (15-min). Stopped in onPause so it
    // never runs while backgrounded.
    private val energySamplerHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val energySamplerRunnable = object : Runnable {
        override fun run() {
            runCatching { EnergyWatchdog.sample(this@ShellActivity) }
            energySamplerHandler.postDelayed(this, com.diegonmarcos.superapp.system.LowPowerMode.scaled(60_000L)) // #1687
        }
    }

    override fun onResume() {
        super.onResume()
        // First-run (and while still missing): Install unknown apps, then Default home.
        com.diegonmarcos.superapp.system.FirstRunPerms.maybeAsk(this)
        com.diegonmarcos.superapp.extinstall.ExtAppInstalls.refresh(this)
        // Coming back from another app can land on any surface — re-ask rather
        // than trusting whatever the stars were left showing.
        refreshStars()
        // Configs → Launcher → Others: stars/pets live re-apply + system brightness.
        applyLauncherSettings()
        // Arm the screensaver idle timer.
        resetIdleTimer()
        // Floating Top Nav Bar — start the overlay service iff enabled in
        // build.json AND the user granted "display over other apps". The
        // service self-hides while we're foreground and surfaces the bubble
        // once the user leaves. Idempotent (START_STICKY); no-op otherwise.
        // hostForeground = reliable "we're in SuperApp" signal so the floating
        // circle never shows here (the in-app trigger is the Sirius Star).
        com.diegonmarcos.superapp.floatingnav.FloatingNavService.hostEnter()
        com.diegonmarcos.superapp.floatingnav.FloatingNavService.startIfPermitted(this)
        com.diegonmarcos.superapp.onehand.OneHandController.startIfEnabled(this)
        com.diegonmarcos.superapp.devtools.DevControlBridge.register(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
        // Re-apply launcher chrome — user may have toggled us as the
        // default Home handler from system Settings while we were
        // backgrounded, and the system status bar / our strip need to
        // sync. Idempotent.
        applyLauncherChrome()
        musicIsland.resume()
        // Kick a sample now + every 60s while resumed.
        energySamplerHandler.removeCallbacks(energySamplerRunnable)
        energySamplerHandler.post(energySamplerRunnable)
        // RECOVERY BANNER. Hosted here rather than on a home fragment because
        // any of the five home surfaces can be the user's default, and a
        // warning most of the fleet never sees is not a warning. It renders
        // nothing at all unless the device has something wrong with its own
        // update chain, so the steady-state cost is one findViewById.
        com.diegonmarcos.superapp.recovery.RecoveryBanner.attach(this, findViewById(R.id.overlay_container))
        // The advisory feed is the only channel that can deliver words NEWER
        // than this build to a device that can no longer update — see
        // AdvisoryFeed. Self-throttled to once an hour; off the main thread.
        kotlin.concurrent.thread(name = "advisory-feed") {
            com.diegonmarcos.superapp.recovery.AdvisoryFeed.poll(this)
            val fleet = runCatching {
                com.diegonmarcos.superapp.updater.Fleet.parse(
                    com.diegonmarcos.superapp.updater.BuildConfig.CONSTELLATION_FLEET_B64)
            }.getOrDefault(emptyList())
            com.diegonmarcos.superapp.recovery.RecoveryNotifier.post(
                this, com.diegonmarcos.superapp.updater.Advisory.current(this, fleet))
        }
    }

    override fun onPause() {
        // We're leaving SuperApp → the floating circle may now appear.
        com.diegonmarcos.superapp.floatingnav.FloatingNavService.hostExit()
        com.diegonmarcos.superapp.devtools.DevControlBridge.unregister(this)
        com.diegonmarcos.superapp.updater.UpdateProgress.setListener(null)
        com.diegonmarcos.superapp.recovery.RecoveryBanner.detach()
        musicIsland.pause()
        energySamplerHandler.removeCallbacks(energySamplerRunnable)
        idleHandler.removeCallbacks(idleRunnable)
        super.onPause()
    }

    // ── Screensaver idle timer (Configs → Launcher → Screensaver) ────────
    private val idleHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val idleRunnable = Runnable {
        if (!com.diegonmarcos.superapp.floatingnav.ScreensaverService.isRunning) {
            runCatching {
                startService(android.content.Intent(this,
                    com.diegonmarcos.superapp.floatingnav.ScreensaverService::class.java))
            }
        }
    }

    /** (Re)arm the screensaver idle timer from the data-driven timeout
     *  (build.json::launcher_settings.screensaver_timeout, default 10s; 0 =
     *  never). Called on resume + every user interaction. */
    private fun resetIdleTimer() {
        idleHandler.removeCallbacks(idleRunnable)
        val secs = runCatching {
            com.diegonmarcos.superapp.settings.LauncherSettingsPrefs(this).screensaverTimeout
        }.getOrDefault(0)
        if (secs > 0) idleHandler.postDelayed(idleRunnable, secs * 1000L)
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        resetIdleTimer()
    }

    /**
     * #812 NO PROGRESS POPUP. The full-screen update overlay is gone from the
     * SuperApp: progress (app, stage, version, bytes/%, batch position) and
     * Cancel live in the Store page's own bar under its buttons (#785), the
     * same line `/api/store/progress` returns. This only removes an overlay a
     * previous build may have left attached, and lets a finished pass go Idle.
     */
    private fun handleUpdateState(state: com.diegonmarcos.superapp.updater.UpdateProgress.State) {
        if (supportFragmentManager.isStateSaved) return
        runCatching {
            supportFragmentManager.findFragmentByTag(UPDATE_OVERLAY_TAG)?.let {
                supportFragmentManager.beginTransaction().remove(it).commitAllowingStateLoss()
            }
            if (state is com.diegonmarcos.superapp.updater.UpdateProgress.State.Done) {
                findViewById<View>(R.id.fragment_container)?.postDelayed({
                    com.diegonmarcos.superapp.updater.UpdateProgress.reset()
                }, 1200)
            }
        }
    }

    override fun onTileFromServer(target: String) = onTileClicked(target)

    override fun onActionFromServer(actionType: String) {
        dispatchHomeAction(actionType)
    }

    override fun firePresetHaptic(preset: String) {
        val v = bottomNav
        when (preset) {
            "tick"          -> Haptics.segmentTick(v)
            "start"         -> Haptics.gestureStart(v)
            "end"           -> Haptics.gestureEnd(v)
            "gemini_stream" -> {
                Haptics.gestureStart(v)
                v.postDelayed({ Haptics.segmentTick(v) }, 100)
                v.postDelayed({ Haptics.segmentTick(v) }, 180)
                v.postDelayed({ Haptics.gestureEnd(v) }, 260)
            }
        }
    }

    override fun stateSnapshot(): Map<String, String> = mapOf(
        "section" to currentSection,
        "label"   to currentLabel,
        "mode"    to currentMode,
    )
}
