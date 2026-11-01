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

    /** The island's selected item (null = none); the host moves it through [BrowserHostFragment.onOverlaysClosed]. */
    private var selectedId by mutableStateOf<String?>(null)
    /** #900 the Configs top tab showing (a ui.sections[configs].pages id), while Configs is the island's item. */
    private var configsPage by mutableStateOf<String?>(null)
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
        supportFragmentManager.addFragmentOnAttachListener { _, f ->
            // The pill follows the host: Tabs while the tab switcher is what is showing, else Browser.
            if (f is BrowserHostFragment) {
                f.externalBar = true
                f.onWebScroll = { d -> onWebScroll(d) }
                // the page's touch and inner scrollers feed the same collapse (a held drag hides every bar, the release shrinks them)
                f.onWebViewCreated = { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, chromeCollapse) }
                f.onWebPageFinished = { com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(it, chromeCollapse); com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.inject(it) }
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
        else mhtml.open(intent) { url -> if (!isFinishing && !isDestroyed) show(url?.takeIf { it.isNotBlank() }) }

        Updater.start(this)
        UpdateProgress.setListener { state ->
            runOnUiThread { handleUpdateState(state) }
        }
    }

    /**
     * The island: ui.bottom_nav through [NavDecl], the icon of a section its libs:ui-icons catalog glyph. A tap on Search / Configs asks the live host to draw that page over itself; a tap
     * on Browser closes whatever is open. The island's look is the lib's.
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
            hierarchy = if (selectedId == "configs") section?.let { titleHierarchy(it, configsPage) }.orEmpty() else emptyList(),
            onNavigate = { e -> if (e.depth == 0) openSection(e.sectionId) else openConfigsPage(e.id) },
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
                    val tabs = remember { decl.section("configs")?.pages.orEmpty() }
                    NavBarTopSecondRow(tabs, configsPage, onSelect = { pg -> openConfigsPage(pg.id) }, dark = true, underTopChrome = false)
                }
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    KitFragmentHost(R.id.fragment_container, Modifier.fillMaxSize()) {
                        BrowserHostFragment.newInstance(pendingOpenUrl, BuildConfig.UI_BROWSER_CONFIG_B64)
                    }
                    // #1402 Configs > About is the fleet's About page (libs:ui-nav-bars), over the host's page.
                    if (selectedId == "configs" && configsPage == "about") {
                        val c = com.diegonmarcos.superapp.themebg.ThemeBackground.colors(true)
                        Box(Modifier.fillMaxSize().background(c.top).testTag("browser:configs:page:about")) {
                            com.diegonmarcos.superapp.fleetconfigs.FleetAboutPage(com.diegonmarcos.superapp.fleetconfigs.FleetConfigs.current, c)
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
        }
        // closeOverlays reset the pill to Browser; an overlay that opened puts it on its own item.
        selectedId = id
    }

    /** A Configs top tab was picked: the host swaps the page under it. */
    private fun openConfigsPage(id: String) {
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
        mhtml.open(intent) { target ->
            val url = target?.takeIf { it.isNotBlank() }
            if (url != null && !isFinishing && !isDestroyed) supportFragmentManager.commit(allowStateLoss = true) {
                replace(
                    R.id.fragment_container,
                    BrowserHostFragment.newInstance(url, BuildConfig.UI_BROWSER_CONFIG_B64),
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
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
