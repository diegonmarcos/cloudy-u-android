package com.diegonmarcos.cloudbrowser

import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.unit.dp
import androidx.fragment.app.commit
import com.diegonmarcos.cloudbrowser.offline.GpuPageBlock
import com.diegonmarcos.cloudbrowser.sites.CloudyPage
import com.diegonmarcos.cloudbrowser.sites.DomainsPage
import com.diegonmarcos.cloudbrowser.sites.SitesDecl
import com.diegonmarcos.cloudbrowser.sites.VisitLogStore
import com.diegonmarcos.cloudbrowser.sites.WebyPage
import com.diegonmarcos.superapp.bottomnav.BottomNavCollapse
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavChromeScroll
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottom
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.themebg.themeBackground
import com.diegonmarcos.superapp.browser.BrowserHostFragment
import com.diegonmarcos.superapp.browser.BrowserSearchPageHost
import com.diegonmarcos.superapp.browser.MhtmlOpener
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitFragmentHost
import com.diegonmarcos.superapp.uikit.KitFragmentHostState
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import com.diegonmarcos.superapp.updater.UpdateOverlayFragment
import com.diegonmarcos.superapp.updater.UpdateProgress
import com.diegonmarcos.superapp.updater.Updater

/**
 * Single-activity shell for Cloud Browser. Hosts [BrowserHostFragment] full-screen.
 * Handles VIEW intents (http/https, and .mhtml / .mht archives by file/content uri) so other apps can open links and saved pages here.
 * Wires the self-updater (Updater) so the app can silently update itself from GHCR.
 *
 * [BrowserHostFragment] has no host interface, but it is NOT self-configuring:
 * libs:browser is shared by reference and deliberately ships no default
 * tabs and no engine list of its own. This activity is where Cloud
 * Browser's own content — the four first-run pinned tabs, the DuckDuckGo
 * default — crosses from build.json::ui.browser into the shared host.
 * Change those four URLs in build.json; no other app is affected.
 *
 * #868 The bottom bar is libs:bottomnav's island, fed by NavDecl from build.json::ui: Browser is
 * the host fragment's page, Tabs its tab switcher (#886), Search and Configs open the Search add-on's
 * page and the browser settings OVER it (the host's own overlays), Configs under a strip of top tabs (#900: libs:bottomnav
 * PageTabs, one per config category, declared in ui.sections[configs].pages), and the pill follows the host: it
 * goes back to Browser (or Tabs, when the switcher is showing) the moment the last overlay closes.
 *
 * #1164 the chrome is the fleet's shared nav pattern: libs:ui-nav-bars's row (the URL bar is its title island, a tap
 * opens the host's address overlay; the page menu with Dev Tools is its Configs icon; Profile top-right opens Cloud Account),
 * libs:ui-nav-bars's tabs for Configs, libs:ui-nav-bars's bar on libs:theme-background, and the WebView's scroll
 * fed to the one shared scroll-collapse (BrowserHostFragment.onWebScroll -> BottomNavCollapse.onScrollDelta).
 *
 * 2026-10-16 owner decision: all apps must be Compose. The shell is a Compose screen under CloudKitTheme
 * (libs:ui-kit): the host fragment sits in a KitFragmentHost and the island is libs:bottomnav's
 * BottomNavIsland composable.
 */
class MainActivity : AppCompatActivity() {

    private val mhtml by lazy { MhtmlOpener(this) }
    /** #1741 set by the fleet's Convert to PDF door (imageview/ConvertPdfActivity): print the page once it has loaded. */
    private var convertPdfPending = false

    companion object {
        const val EXTRA_CONVERT_PDF = "com.diegonmarcos.cloudbrowser.extra.CONVERT_PDF"
    }

    /**
     * #1741 what an incoming VIEW / SEND shows: a picture on the image page, an HTML file handed for conversion as the rendered page,
     * a text file as text (#1642), anything else through the saved-page / link opener. [done] gets the url to show, or null for none.
     */
    private fun openIncoming(i: Intent?, done: (String?) -> Unit) {
        val deliver: (String?) -> Unit = { u -> if (!isFinishing && !isDestroyed) done(u) }
        val toHtml = i?.getBooleanExtra(EXTRA_CONVERT_PDF, false) == true
        val afterHtml: (String?) -> Unit = { htmlUrl ->
            if (htmlUrl != null) deliver(htmlUrl)
            else com.diegonmarcos.cloudbrowser.textopen.TextIntents.open(this, i) { textUrl ->
                if (textUrl != null) deliver(textUrl)
                else mhtml.open(i) { url -> deliver(url?.takeIf { it.isNotBlank() }) }
            }
        }
        com.diegonmarcos.cloudbrowser.imageview.ImageIntents.open(this, i) { imgUrl ->
            if (imgUrl != null) deliver(imgUrl)
            else if (toHtml) com.diegonmarcos.cloudbrowser.imageview.ImageIntents.openHtml(this, i, afterHtml)
            else afterHtml(null)
        }
    }

    /** #1518 the max refresh rate, only while a WebGL / WebGPU canvas page is fullscreen (reverted otherwise, and under Super Saving). */
    private val refreshRate by lazy { com.diegonmarcos.cloudlib.web.GpuRefreshRate(this) }

    /** The island's selected item (null = none); the host moves it through [BrowserHostFragment.onOverlaysClosed]. */
    private var selectedId by mutableStateOf<String?>(null)
    /** #900 the Configs top tab showing (a ui.sections[configs].pages id), while Configs is the island's item. */
    private var configsPage by mutableStateOf<String?>(null)
    /** #1493 the top tab showing under Cloudy or Domains (a ui.sections[<id>].pages id); sites/SitesUi.kt draws it. */
    private var sitesPage by mutableStateOf<String?>(null)
    /** #1493 the declaration behind Weby, Cloudy and Domains (build.json::ui, baked with its sibling declarations). */
    private val sites: SitesDecl by lazy { SitesDecl.parseBase64(BuildConfig.SITES_DECL_B64) }
    /** #1164 the live host fragment (Compose state: the first row reads its address). */
    private var hostFragment by mutableStateOf<BrowserHostFragment?>(null)
    /** #1164 the shared chrome's scroll-collapse; the WebView's scroll listener feeds it. */
    private var chromeCollapse: BottomNavCollapse? = null
    private var scrollAcc = 0f
    /** The link or saved page that started this activity, handed to the host fragment once. */
    private var pendingOpenUrl: String? = null
    private val decl: NavDecl by lazy {
        NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // The host fragment is re-added by KitFragmentHost once Compose has a container for it.
        super.onCreate(KitFragmentHostState.withoutFragments(savedInstanceState))
        FleetChrome.apply(this)
        // #1518 the offline copy and the GPU page plug into the shared host; fullscreen events drive the refresh rate
        com.diegonmarcos.cloudbrowser.offline.SiteOffline.install(this)
        com.diegonmarcos.cloudbrowser.offline.SiteOffline.seedDeclared(this)
        com.diegonmarcos.superapp.browser.BrowserHostHooks.gpuPage = { GpuPageBlock() }
        com.diegonmarcos.cloudlib.web.CloudChromeClient.fullscreenListener =
            com.diegonmarcos.cloudlib.web.CloudChromeClient.FullscreenListener { on -> runOnUiThread { refreshRate.fullscreen(on) } }
        supportFragmentManager.addFragmentOnAttachListener { _, f ->
            // The pill follows the host: Tabs while the tab switcher is what is showing, else Browser.
            if (f is BrowserHostFragment) {
                f.externalBar = true
                f.onWebScroll = { d -> onWebScroll(d) }
                // the page's touch and inner scrollers feed the same collapse (a held drag hides every bar, the release shrinks them)
                f.onWebViewCreated = { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, chromeCollapse); refreshRate.watch(it) }
                f.onWebPageFinished = {
                    com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, chromeCollapse); com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(it)
                    // #1741 the fleet's Convert to PDF door: the page it handed has loaded, so raise the print framework once.
                    if (convertPdfPending && (it.url?.startsWith("file://") == true || it.url?.startsWith("http") == true)) { convertPdfPending = false; it.postDelayed({ f.act("convert_pdf") }, 800) }
                }
                // #1531 the Tabs page's History link opens Domains > History, the one history page
                f.onHistory = { openHistory() }
                f.onOverlaysClosed = {
                    selectedId = decl.section(if (f.isTabsOpen) "tabs" else "browser")?.id
                    chromeCollapse?.restore()
                }
                hostFragment = f
            }
        }
        selectedId = decl.default()?.id
        fun show(openUrl: String?) {
            pendingOpenUrl = openUrl
            setContent {
            val palette = KitPalette(
                surface = colorResource(R.color.browser_surface),
                surfaceSelected = colorResource(R.color.browser_selected),
                textPrimary = colorResource(R.color.browser_text),
                textSecondary = colorResource(R.color.browser_text_dim),
                accent = colorResource(R.color.browser_accent),
                hairline = colorResource(R.color.browser_hairline),
                tileInk = colorResource(R.color.browser_ink),
            )
            CloudKitTheme(palette) {
                // While the keyboard is up the island would sit between it and the page, taking the room the
                // address suggestions need; it comes back the moment the keyboard goes.
                val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
                // #1157 one scroll connection for the whole chrome: the first row, the tabs and the bar read it.
                NavChromeScroll { collapse ->
                    SideEffect { chromeCollapse = collapse }
                    Column(
                        Modifier.fillMaxSize().themeBackground(true)
                            .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
                            .imePadding(),
                    ) {
                        Box(Modifier.weight(1f).fillMaxWidth()) { Chrome() }
                        if (!imeVisible) Island()
                    }
                }
            }
            }
        }
        // A link or saved page (.mhtml / .mht) that started this activity opens once; a recreation brings the
        // browser back on its own tabs. A content:// archive is copied to the cache first, so the shell waits for it.
        // A magnet: link or a .torrent handed to the app is queued (and the Downloads page opens), never loaded as a page.
        val torrent = savedInstanceState == null && com.diegonmarcos.cloudbrowser.torrent.TorrentIntents.consume(this, intent)
        if (savedInstanceState != null || torrent) show(null)
        else {
            convertPdfPending = intent?.getBooleanExtra(EXTRA_CONVERT_PDF, false) == true
            // #1741 a picture shows on the image page, #1642 a text file as text; anything else goes to the saved-page / link opener.
            openIncoming(intent) { url -> show(url) }
        }

        Updater.start(this)
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    /**
     * The island: ui.bottom_nav through [NavDecl], the icon of a section its libs:ui-icons catalog glyph. A tap on Search / Configs asks the live host to draw that page over itself; a tap
     * on Browser closes whatever is open. Weby, Cloudy and Domains (#1493) are sites/'s pages. The island's look is the lib's.
     */
    @androidx.compose.runtime.Composable
    private fun Island() {
        val entries = decl.islandEntries()
        NavBarBottom(
            entries = entries,
            selectedId = selectedId,
            onSelect = { e -> if (e.id != selectedId || e.id != decl.default()?.id) openSection(e.id) },
            dark = true,
        )
    }

    /**
     * The first row over the page. The title island is the URL bar while a page is showing (a tap opens the host's address
     * overlay) and the section's name otherwise (on Configs it lists the config pages); the row's ⋮ opens Configs (Browsing Tools is
     * the tab row's icon); AI opens the Search page; the hamburger's drawer holds the page actions.
     */
    @Composable
    private fun Chrome() {
        val host = hostFragment
        val section = selectedId?.let { decl.section(it) }
        val onPage = selectedId == "browser"
        val title = if (onPage) host?.addressDisplay.orEmpty().ifEmpty { section?.label.orEmpty() } else section?.label.orEmpty()
        NavBarTopFirstRowScaffold(
            title = title,
            hierarchy = when (selectedId) {
                "configs" -> section?.let { titleHierarchy(it, configsPage) }.orEmpty()
                "cloudy", "domains" -> section?.let { titleHierarchy(it, sitesPage) }.orEmpty()
                else -> emptyList()
            },
            // #1483 every declared section (bar and off-bar) is a peer in the title dropdown: a user reaches every page from it
            sections = decl.sections,
            onNavigate = { e -> if (e.depth == 0) openSection(e.sectionId) else if (selectedId == "cloudy" || selectedId == "domains") sitesPage = e.id else openConfigsPage(e.id) },
            onAccount = rememberAccountLauncher(),
            // #1401 the app bar's ⋮ opens the app's Configs, like every fleet app; Browsing Tools is the tab row's icon.
            onConfigs = { openSection("configs") },
            onAi = { openSection("search") },
            dark = true,
            onTitleClick = if (onPage) ({ hostFragment?.openAddress() }) else null,
        ) {
            TopBarDrawer { close ->
                val p = LocalKitPalette.current
                for ((id, label) in listOf("back" to "Back", "forward" to "Forward", "reload" to "Reload", "new_tab" to "New tab", "new_private_tab" to "New private tab")) {
                    Text(
                        label, color = p.textPrimary, style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.fillMaxWidth().clickable { hostFragment?.act(id); close() }.padding(horizontal = 20.dp, vertical = 14.dp)
                            .testTag("drawer_action_$id"),
                    )
                }
            }
            Column(Modifier.fillMaxSize()) {
                // #900 Configs is a section of top tabs (one per config category), sideways-scrolling when they do not fit.
                if (selectedId == "configs") {
                    // #1630 {own tabs} | Agents, Perms, Logcat, X-Ray App, About
                    val tabs = remember { com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(decl.section("configs")?.pages.orEmpty()) }
                    NavBarTopSecondRow(tabs, configsPage, onSelect = { pg -> openConfigsPage(pg.id) }, dark = true, underTopChrome = false)
                }
                // #1493 Cloudy and Domains: their sub-pages as top tabs (ui.sections[<id>].pages).
                if (selectedId == "cloudy" || selectedId == "domains") {
                    val tabs = remember(selectedId) { decl.section(selectedId.orEmpty())?.pages.orEmpty() }
                    NavBarTopSecondRow(tabs, sitesPage, onSelect = { pg -> sitesPage = pg.id }, dark = true, underTopChrome = false)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    KitFragmentHost(R.id.fragment_container, Modifier.fillMaxSize()) {
                        BrowserHostFragment.newInstance(pendingOpenUrl, BuildConfig.UI_BROWSER_CONFIG_B64)
                    }
                    // #1402/#1443 Configs > General | Perms | About are the fleet's pages (libs:ui-nav-bars FleetConfigsPageBody), over the host's page.
                    if (selectedId == "configs" && com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage(configsPage)) {
                        val c = com.diegonmarcos.superapp.themebg.ThemeBackground.colors(true)
                        Box(Modifier.fillMaxSize().background(c.top).testTag("browser:configs:page:$configsPage")) {
                            com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(configsPage.orEmpty(), onAppConfigs = { openConfigsPage("sites") })
                        }
                    }
                    // #1493 Weby, Cloudy and Domains over the host's page (sites/SitesUi.kt); Back returns to the page.
                    if (selectedId == "weby" || selectedId == "cloudy" || selectedId == "domains") {
                        androidx.activity.compose.BackHandler { openSection("browser") }
                        val c = com.diegonmarcos.superapp.themebg.ThemeBackground.colors(true)
                        Box(Modifier.fillMaxSize().background(c.top).testTag("browser:sites:${selectedId}")) {
                            when (selectedId) {
                                "weby" -> WebyPage(sites) { url -> openUrl(url) }
                                "cloudy" -> CloudyPage(sitesPage, sites) { url -> openUrl(url) }
                                else -> DomainsPage(sitesPage, sites) { url -> openUrl(url) }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * The WebView scrolled by [delta] px (> 0: reading down). Small moves and the jitter of the page resizing under the
     * row are ignored: the shared collapse follows a deliberate scroll of about 48 px either way.
     */
    private fun onWebScroll(delta: Float) {
        if (delta == 0f) return
        if (scrollAcc != 0f && (scrollAcc > 0f) != (delta > 0f)) scrollAcc = 0f
        scrollAcc += delta
        if (scrollAcc > 48f || scrollAcc < -48f) { chromeCollapse?.onScrollDelta(scrollAcc); scrollAcc = 0f }
    }

    private fun openSection(id: String) {
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BrowserHostFragment ?: return
        host.dismissOverlays()
        when (id) {
            // The page (or a new tab when none is open) / the tab switcher: the host's own screens.
            "browser" -> host.openBrowser()
            "tabs" -> host.openTabs()
            // Fav: the bookmarks (list or icon grid) over the page.
            "favourites" -> host.openFavourites()
            "search" -> {
                host.openSearch()
                // No Search page installed: the host only says so, nothing opened, the pill stays.
                if (BrowserSearchPageHost.page == null) return
            }
            "configs" -> {
                configsPage = decl.section("configs")?.pages?.firstOrNull()?.id
                host.openSettings()
            }
            // #1493 Weby, Cloudy and Domains are drawn by the shell (sites/), not by the host: nothing of the host stays open.
            "weby" -> Unit
            "cloudy", "domains" -> sitesPage = decl.section(id)?.pages?.firstOrNull()?.id
        }
        // closeOverlays reset the pill to Browser; an overlay that opened puts it on its own item.
        selectedId = id
    }

    /** #1531 the History link of the Tabs page and of the menu: the Domains > History page. */
    private fun openHistory() {
        val (section, page) = com.diegonmarcos.cloudbrowser.sites.SitesRoute.history { s, pg -> decl.section(s)?.pages.orEmpty().any { it.id == pg } } ?: return
        openSection(section)
        sitesPage = page
    }

    /** #1493 a Weby or Cloudy tile: the host opens the site in a tab and the island goes back to Browser. */
    private fun openUrl(url: String) {
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BrowserHostFragment ?: return
        host.openUrl(url)
        selectedId = decl.section("browser")?.id
    }

    /** A Configs top tab was picked: the host swaps the page under it. */
    private fun openConfigsPage(id: String) {
        // #1443 the shared General | Perms | About pages are drawn by the shell over the host's page; the host keeps its own.
        if (com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage(id)) { configsPage = id; return }
        val host = supportFragmentManager.findFragmentById(R.id.fragment_container) as? BrowserHostFragment ?: return
        configsPage = host.configsPageFor(id)   // #1402 old anchors land on their new page
        host.openConfigsPage(id)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // VIEW intent from another app: replace fragment with the new URL.
        // BrowserHostFragment takes its URL via newInstance args only.
        if (com.diegonmarcos.cloudbrowser.torrent.TorrentIntents.consume(this, intent)) { setIntent(intent); return }
        // A saved page (.mhtml / .mht) is copied to the cache first when it arrives as a content:// stream.
        val load: (String?) -> Unit = { target ->
            val url = target?.takeIf { it.isNotBlank() }
            if (url != null && !isFinishing && !isDestroyed) supportFragmentManager.commit(allowStateLoss = true) {
                replace(
                    R.id.fragment_container,
                    BrowserHostFragment.newInstance(url, BuildConfig.UI_BROWSER_CONFIG_B64),
                )
            }
        }
        // #1741 a picture first, #1642 a text file next; anything else keeps the saved-page / link path unchanged.
        convertPdfPending = intent.getBooleanExtra(EXTRA_CONVERT_PDF, false)
        openIncoming(intent) { target -> load(target) }
    }

    override fun onStart() {
        super.onStart()
        refreshRate.start()
    }

    override fun onStop() {
        super.onStop()
        refreshRate.stop()   // leaving the app always hands the display back to the system's own rate
        // #1493 the visit log keeps what the browser's capped history shows, so Domains lists every site ever visited.
        Thread { runCatching { VisitLogStore(applicationContext).sync() } }.start()
    }

    override fun onDestroy() {
        super.onDestroy()
        com.diegonmarcos.cloudlib.web.CloudChromeClient.fullscreenListener = null
        UpdateProgress.setListener(null)
    }

    private fun handleUpdateState(state: UpdateProgress.State) {
        val tag = "update_overlay"
        val frag = supportFragmentManager.findFragmentByTag(tag)
        // An unattended pass, or a manual one the user minimized, draws
        // nothing. Failed states are never suppressed.
        if (UpdateProgress.suppressed(this, state)) {
            frag?.let { supportFragmentManager.commit(allowStateLoss = true) { remove(it) } }
            return
        }
        when (state) {
            is UpdateProgress.State.Idle -> {
                frag?.let { supportFragmentManager.commit { remove(it) } }
            }
            else -> {
                if (frag == null) {
                    supportFragmentManager.commit {
                        add(android.R.id.content, UpdateOverlayFragment.newInstance(), tag)
                    }
                } else {
                    (frag as? UpdateOverlayFragment)?.applyState(state)
                }
            }
        }
    }
}
