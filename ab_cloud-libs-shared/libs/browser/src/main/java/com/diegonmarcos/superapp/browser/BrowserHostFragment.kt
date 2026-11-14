package com.diegonmarcos.superapp.browser


import com.diegonmarcos.cloudlib.web.CloudChromeClient
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.CloudWebClient
import com.diegonmarcos.cloudlib.web.WebProfile
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.graphics.asImageBitmap
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.foundation.verticalScroll
import com.diegonmarcos.superapp.uikit.KitSettingsRow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.Fragment
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import org.json.JSONArray
import org.json.JSONObject
import com.diegonmarcos.superapp.browser.R
import java.io.File
import java.security.MessageDigest

/**
 * Tabs host — three modes:
 *
 *   GRID     tab switcher. Cards of every open tab, drawn by
 *            [BrowserTabGrid] (a Compose LazyVerticalGrid; long-press drags
 *            to reorder them, a swipe closes), with pinned tabs first and named groups as
 *            collapsible headers.
 *   DETAIL   WebView fullscreen. The address bar searches as well as
 *            navigates, and suggests from local history + open tabs.
 *   (history, bookmarks and downloads are #802 Compose pages drawn over these.)
 *
 * WHAT IS SHARED AND WHAT IS NOT. Every mechanism above lives in this
 * module and reaches every app that links it. None of the CONTENT does:
 * which tabs are pinned on a fresh install and which search engines
 * exist arrive as a [BrowserConfig] from the consuming app, and the
 * library default is an empty pin list. See [BrowserConfig].
 *
 * [toggleAllCollapsed] snaps a re-tapped Tabs view back to GRID. #825 it used to implement
 * core's Collapsible / Suppress*Swipe markers for the SuperApp's pager, which no longer hosts
 * this fragment (only cloud-browser does, and nothing there queries them), so the markers left
 * libs:core for the SuperApp, their only remaining reader.
 */
class BrowserHostFragment : Fragment() {


    /** Desktop-mode toggle — WebView UA + width override + initial scale. #802 persisted
     *  as the catalogue's `desktop_mode`, so it survives a restart and moves with the Account. */
    private val desktopMode: Boolean get() = browserSettings.bool("desktop_mode") == true

    private lateinit var prefs: BrowserTabPrefs
    private lateinit var history: BrowserHistory
    private lateinit var bookmarks: BrowserBookmarks
    private lateinit var downloads: BrowserDownloads
    private lateinit var sitePerms: BrowserSitePermissions
    /** Origins a private tab visited this session — their site storage goes when the last one closes. */
    /** androidx.webkit multi-profile: private tabs get their own cookie jar, storage and cache. */
    private val profileSupported: Boolean by lazy {
        runCatching { androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.MULTI_PROFILE) }.getOrDefault(false)
    }
    private val privateSession by lazy { PrivateSession(profileSupported) }

    /** Bind [wv] to its tab's profile. MUST run before the WebView loads anything. */
    private fun bindProfile(wv: WebView, tab: BrowserTab?) {
        val name = PrivateProfile.nameFor(tab, profileSupported)
        if (name != null) profileOfWebView[wv] = name
        TabProfile.bind(wv, name)
    }

    /** The profile each live WebView was bound to (absent = default). */
    private val profileOfWebView = java.util.WeakHashMap<WebView, String>()
    /** The cookie manager for [wv]'s profile; the process-wide one only for a default-profile WebView. */
    private fun cookiesOf(wv: WebView?): android.webkit.CookieManager = TabProfile.cookies(wv?.let { profileOfWebView[it] })

    /** The last private tab is gone: drop the profile, or failing that wipe everything in it. */
    private fun dropPrivateProfile() {
        val store = androidx.webkit.ProfileStore.getInstance()
        val deleted = runCatching { store.deleteProfile(PrivateProfile.NAME) }.getOrDefault(false)
        if (!deleted) runCatching {
            store.getProfile(PrivateProfile.NAME)?.let { p ->
                p.cookieManager.removeAllCookies(null)
                p.webStorage.deleteAllData()
            }
        }
    }
    /** The Tabs view shows normal or incognito tabs, one at a time. */
    private var tabFilter by mutableStateOf(BrowserTabsBar.Filter.NORMAL)
    private lateinit var browserSettings: BrowserSettings
    private lateinit var config: BrowserConfig
    /** #900 Block trackers: the app's declared hosts. */
    private val trackers by lazy { BrowserTrackers(config.trackerHosts) }
    /** The host of the page the WebView is on, kept for shouldInterceptRequest (WebView methods may not be called off the main thread). */
    @Volatile private var pageHostNow = ""
    /** What the page area shows: the tab switcher, or one tab's address bar over its WebView. */
    private sealed interface Screen {
        data object Grid : Screen
        /** [id] is unique per showDetail, so a switch to another tab rebuilds the whole screen. */
        class Detail(val id: Int, val tab: BrowserTab, val webView: WebView) : Screen
    }
    private var screen by mutableStateOf<Screen>(Screen.Grid)
    /** Bumped whenever the tab switcher's rows must be re-read. */
    private var gridVersion by mutableIntStateOf(0)
    private var detailSeq = 0
    /** The host view (a ComposeView): what a Snackbar anchors to. */
    private var hostView: View? = null
    private var webView: WebView? = null
    /** Pull-down-to-refresh around [webView]; the page client stops its spinner when the load ends or fails. */
    private var pullRefresh: androidx.swiperefreshlayout.widget.SwipeRefreshLayout? = null
    /** The tab [webView] shows: what a snapshot taken when the page is left is filed under. */
    private var webTabKey: String? = null
    /** Tab-switcher snapshots (build.json::ui.browser.tab_thumbnails): one cache per process. */
    private val thumbs: TabThumbnails by lazy { TabThumbnails.shared(requireContext(), config.tabThumbs) }
    private var mode: Mode = Mode.GRID
    /** The ARG_OPEN_URL is consumed once: a rotation must not reopen it over where the user went. */
    private var openUrlHandled = false

    /** #802 reader view is showing an extraction of the current page. */
    private var readerOn = false
    /** The next page start is the reader's own render, not a navigation away from it. */
    private var readerPending = false
    /** #802 the Compose surfaces drawn over the page; back closes the top one first. */
    private class OverlayEntry(val bottom: Boolean, val side: Boolean, val content: @Composable (close: () -> Unit) -> Unit)
    private val overlays = mutableStateListOf<OverlayEntry>()

    /** #868 the host shell's island opens the host's own pages: the Search add-on's page, the settings, none. */
    fun openSearch() = showSearchPage()
    fun openSettings() = showSettings()
    /** #900 a top tab of the Configs section was picked: that page replaces the one showing. */
    fun openConfigsPage(pageId: String) = showSettings(pageId)
    /** #1402 the page an id opens now (a former page id, an old anchor, redirects to the page that holds its content). */
    fun configsPageFor(pageId: String): String = config.configs.pageFor(pageId) ?: pageId
    /** #900 the ids of the Configs pages in tab order (the host shell checks its tabs against them). */
    val configsPageIds: List<String> get() = config.configs.pages.map { it.first }
    fun dismissOverlays() = closeOverlays()
    /** #893 Fav is a destination of the nav island: the saved pages (list, open, remove). */
    fun openFavourites() = showBookmarks()
    /** #1493 the host shell opens [url] in the tab that shows it, else a new one (Weby and Cloudy tiles): every overlay closes first. */
    fun openUrl(url: String) { closeOverlays(); openUrlInTab(url) }

    /** #886 Tabs is a destination of the nav island: the tab switcher, over nothing (the page is parked). */
    fun openTabs() { closeOverlays(); if (mode !is Mode.GRID) showGrid() else onOverlaysClosed?.invoke() }
    /** #886 Browser from the island: back to the tab that was open (or a new tab when there is none). */
    fun openBrowser() {
        closeOverlays()
        if (mode is Mode.GRID) {
            val t = prefs.activeTab() ?: prefs.all().firstOrNull()
            if (t != null) { prefs.setActiveId(t.key); showDetail(t) } else promptForUrl()
        } else onOverlaysClosed?.invoke()
    }
    /** True while the tab switcher is what is on screen (no sheet or page over it): the island's Tabs pill. */
    val isTabsOpen: Boolean get() = mode is Mode.GRID && overlays.isEmpty()

    /**
     * #1164 the host app draws the URL bar and the page menu itself, in the shared first row (libs:ui-nav-bars):
     * set, the page screen leaves out its own bar row. The host reads [addressDisplay], opens the address overlay with
     * [openAddress], the menu sheet with [openMenu] and runs a declared menu action with [act].
     */
    var externalBar by mutableStateOf(false)
    /** The address as the bar shows it (the engine's hint while empty); empty until the host is up. */
    val addressDisplay: String get() = runCatching { addressValue.text.ifEmpty { "Search ${engine().label}, or type a URL" } }.getOrDefault("")
    fun openAddress() { suggestState.visible = false; addressOpen = true }
    fun openMenu() = showMenuSheet()
    fun act(id: String) = runAction(id)
    /** #1164 the page scrolled by this many px (> 0: reading down); the host feeds the shared chrome's scroll-collapse with it. */
    var onWebScroll: ((Float) -> Unit)? = null
    /** #1164 a page WebView was created (before its first load) / finished a page: the host wires the shared chrome's web scroll bridge (touch, inner scrollers). */
    var onWebViewCreated: ((WebView) -> Unit)? = null
    var onWebPageFinished: ((WebView) -> Unit)? = null

    /** #868 the host shell's nav island follows the screen: called whenever the last overlay (the
     *  Search page, Settings, a sheet) is gone, so the island can put its pill back on the page. */
    var onOverlaysClosed: (() -> Unit)? = null
    /** Compose state, so the find bar redraws its count when a search (screen or API) lands. */
    private val findCount = androidx.compose.runtime.mutableStateOf<Int?>(null)

    private val roleRequest = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { }

    /**
     * #802 / #1274 a site was allowed a permission the APP does not hold yet. The Permissions page is the one place that
     * asks (libs:perms): the site is refused this time and a "Permissions needed" panel opens that page on the entries;
     * the next request after the grant goes through.
     */
    private fun permsNeeded(androidPerms: Collection<String>) {
        if (!isAdded || androidPerms.isEmpty()) return
        val ctx = requireContext()
        val ids = com.diegonmarcos.superapp.perms.PermsNeeds.idsFor(ctx, androidPerms)
        val entries = ids.mapNotNull { com.diegonmarcos.superapp.perms.Perms.catalogue(ctx).byId(it) }
        overlay { close ->
            BrowserTextPanel(com.diegonmarcos.superapp.perms.PermsBanner.title(entries),
                "Cloud Browser needs this before a site can use it.",
                listOf(SheetRow("perms", com.diegonmarcos.superapp.perms.PermsBanner.BUTTON)),
                onPick = { close(); com.diegonmarcos.superapp.perms.PermsLink.open(ctx, PERMS_FEATURE, ids) },
                onClose = { close() })
        }
    }

    /** #802 Profile ▸ Import file: a CSV / Firefox / Bitwarden / own-JSON export, merged into the profile. */
    private val importProfile = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@registerForActivityResult
        val text = runCatching { requireContext().contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() } }.getOrNull()
        val p = text?.let { runCatching { BrowserProfile.parse(null, it) }.getOrNull() }
        if (p == null || p.isEmpty) { toast("Nothing importable in that file"); return@registerForActivityResult }
        BrowserProfileStore(requireContext()).import(p)
        toast("Imported ${p.addresses.size} address(es), ${p.cards.size} card(s) (metadata only)")
    }

    private sealed class Mode {
        object GRID : Mode()
        /** #886 the tab is held by its stable id: its url changes as the tab navigates. */
        data class DETAIL(val id: String) : Mode()
    }

    /** The engine a typed query goes to: his pick, else the app's default. */
    private fun engine(): BrowserSearchEngine = config.engine(browserSettings.searchEngineId())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, s: Bundle?): View {
        val ctx = inflater.context
        prefs = BrowserTabPrefs(ctx)
        if (PrivateProfile.staleAtStart(profileSupported, prefs.all())) dropPrivateProfile()
        history = BrowserHistory(ctx)
        bookmarks = BrowserBookmarks(ctx)
        downloads = BrowserDownloads(ctx)
        sitePerms = BrowserSitePermissions(ctx)
        config = BrowserConfig.parseBase64(arguments?.getString(ARG_CONFIG_B64))
        browserSettings = BrowserSettings(ctx, config.settings)
        bookmarks.applySeed(favSeed)   // #893 Fav ships with the app's declared links, once per entry
        FavFeed.start(ctx, favSeed, bookmarks)   // #1458 GH Pages: the hub's pages.json, cached copy now, fresh copy when it arrives

        // FIRST RUN ONLY, and only with what the app configured. A library
        // default of zero tabs means an app that supplies nothing gets
        // nothing — never another app's homepage set.
        val seeded = prefs.seedOnce(config.defaultPinnedTabs, config.defaultPinnedTitles)
        if (seeded > 0) {
            android.util.Log.i(TAG, "seeded $seeded default pinned tab(s) on first run")
        }

        val root = ComposeView(ctx).apply {
            setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
            setContent { CloudKitTheme(palette()) { com.diegonmarcos.superapp.uikit.KitPage { Root() } } }
        }
        hostView = root

        val openUrl = arguments?.getString(ARG_OPEN_URL)
        // #886 a fragment that outlives its view (rotation) comes back on the tab it was showing.
        val kept = (mode as? Mode.DETAIL)?.let { prefs.byId(it.id) }
        val restore = prefs.activeTab()?.takeIf { browserSettings.bool("restore_tabs_on_start") == true }
        if (!openUrl.isNullOrBlank() && !openUrlHandled) {
            openUrlHandled = true
            openUrlInTab(openUrl)
        } else if (kept != null) {
            showDetail(kept)
        } else if (restore != null) {
            showDetail(restore)
        } else {
            showGrid()
        }
        return root
    }

    /** #802 a write from outside the screen (debug route, fleet import) shows up live. */
    override fun onResume() {
        super.onResume()
        BrowserBus.listener = { change -> if (isAdded) onBusChange(change) }
        BrowserBus.page = pageHost
        syncDownloadPolicy()
        // #1420 back from a Google sign-in in the Custom Tab: the tab reloads so the site sees the new session
        if (oauthHandedOff) { oauthHandedOff = false; webView?.reload() }
        // The download notification's tap opens the Downloads page.
        activity?.intent?.takeIf { it.getBooleanExtra(com.diegonmarcos.superapp.browser.download.DlService.EXTRA_OPEN_DOWNLOADS, false) }?.let {
            it.removeExtra(com.diegonmarcos.superapp.browser.download.DlService.EXTRA_OPEN_DOWNLOADS)
            showDownloads()
        }
    }

    /** The Downloads settings (Configs ▸ Downloads) become the download manager's queue policy. */
    private fun syncDownloadPolicy() {
        if (!downloads.managed) return
        val s = browserSettings
        com.diegonmarcos.superapp.browser.download.DlQueue.get(requireContext()).setPolicy(
            com.diegonmarcos.superapp.browser.download.DlPolicy(
                parallel = s.int("dl_parallel") ?: 3,
                wifiOnly = s.bool("dl_wifi_only") ?: false,
                allowMetered = s.bool("dl_allow_metered") ?: true,
                speedLimitKbps = s.int("dl_speed_limit_kbps") ?: 0,
                segments = s.int("dl_segments") ?: 4,
                sharedStore = s.bool("dl_shared_store") ?: false,
                seed = s.bool("torrent_seed") ?: false,
                seedRatio = s.int("torrent_seed_ratio") ?: 0,
                seedMinutes = s.int("torrent_seed_minutes") ?: 0,
                uploadKbps = s.int("torrent_upload_kbps") ?: 0,
            ))
    }

    override fun onStop() {
        saveTabState()
        super.onStop()
    }

    override fun onPause() {
        commitSwipe()
        saveTabState()   // #886 the app going away is the last chance to keep where the tab was
        BrowserBus.listener = null
        if (BrowserBus.page === pageHost) BrowserBus.page = null
        super.onPause()
    }

    private fun onBusChange(change: String) {
        when {
            change == BrowserBus.SETTINGS -> webView.also { syncDownloadPolicy() }?.let { applySettings(it); if (!BrowserNavPolicy.isFormPostResult(it.url)) it.reload() }
            change == BrowserBus.TABS -> if (mode is Mode.GRID) showGrid()
            change.startsWith(BrowserBus.OPEN) -> navigateTo(change.removePrefix(BrowserBus.OPEN))
        }
    }

    fun toggleAllCollapsed(): Boolean {
        if (mode !is Mode.GRID) { showGrid(); return true }
        return false
    }

    // ── GRID mode ────────────────────────────────────────────────────

    private fun showGrid() {
        mode = Mode.GRID
        teardownWebView()
        overlays.clear()
        onOverlaysClosed?.invoke()
        screen = Screen.Grid
        gridVersion++
    }

    /**
     * Close, honouring the pin. [BrowserTabPrefs.remove] is what refuses;
     * this only reports the refusal, so the constraint cannot drift apart
     * from the button.
     */
    private fun closeTab(tab: BrowserTab) {
        commitSwipe()   // a swipe still waiting for Undo must not make this look like the last private tab
        if (!prefs.removeById(tab.key)) {
            toast("“${tab.displayTitle}” is pinned — unpin it first")
            return
        }
        BrowserWebState.delete(requireContext(), tab.key)
        thumbs.delete(tab.key)
        if (tab.previewPath.isNotBlank()) runCatching { File(tab.previewPath).delete() }
        val left = prefs.all()
        val plan = privateSession.close(tab, left)
        showGrid()   // tears the page's WebView down first: a profile in use cannot be deleted
        plan?.let {
            if (it.deleteProfile) dropPrivateProfile()
            else BrowserClearData.endPrivateSession(it.clearOrigins, normalTabsOpen = !it.clearSessionCookies)
        }
    }

    // ── swipe to close, with Undo ────────────────────────────────────

    private val swipeClose by lazy { SwipeClose(privateSession) }

    /**
     * A card was swiped away. A pinned tab asks first (see [SwipeGesture.needsConfirm]); any other tab leaves
     * the store at once and waits in [swipeClose] for the undo window, with its saved page state and preview
     * untouched. The private teardown happens when the window closes, in [commitSwipe].
     */
    private fun swipeClose(tab: BrowserTab) {
        if (SwipeGesture.needsConfirm(tab)) {
            overlay { close ->
                AlertDialog(
                    onDismissRequest = close,
                    title = { Text("Close pinned tab?") },
                    text = { Text("“${tab.displayTitle}” is pinned. Unpin it and close it?") },
                    dismissButton = { TextButton(onClick = close) { Text("Keep") } },
                    confirmButton = {
                        TextButton(onClick = {
                            close()
                            prefs.setPinnedById(tab.key, false)
                            closeWithUndo(tab)   // undo restores the tab as it was: pinned
                        }) { Text("Unpin and close") }
                    },
                )
            }
            return
        }
        closeWithUndo(tab)
    }

    private fun closeWithUndo(tab: BrowserTab) {
        commitSwipe()
        if (!prefs.removeById(tab.key)) return
        swipeClose.begin(tab)
        showGrid()
        val bar = runCatching {
            com.google.android.material.snackbar.Snackbar
                .make(requireView(), "Closed “${tab.displayTitle}”", 5000)
                .setAction("Undo") {
                    prefs.restore(swipeClose.pending ?: return@setAction)
                    swipeClose.undo(prefs.all())
                    if (mode is Mode.GRID) showGrid()
                }
                .addCallback(object : com.google.android.material.snackbar.Snackbar.Callback() {
                    override fun onDismissed(sb: com.google.android.material.snackbar.Snackbar?, event: Int) {
                        if (event != DISMISS_EVENT_ACTION) commitSwipe()
                    }
                })
        }.getOrNull()
        if (bar == null) commitSwipe() else bar.show()
    }

    /** The undo window is over (or something else is about to happen): delete the tab's leftovers and tear down private state. */
    private fun commitSwipe() {
        val c = swipeClose.commit(prefs.all()) ?: return
        val ctx = context ?: return
        BrowserWebState.delete(ctx, c.tab.key)
        thumbs.delete(c.tab.key)
        if (c.tab.previewPath.isNotBlank()) runCatching { File(c.tab.previewPath).delete() }
        c.plan?.let {
            if (it.deleteProfile) dropPrivateProfile()
            else BrowserClearData.endPrivateSession(it.clearOrigins, normalTabsOpen = !it.clearSessionCookies)
        }
    }

    private fun grouped(c: BrowserTabGroups.Change) =
        toast(if (c.created) "Grouped as “${c.group}” — ✎ on its header renames it" else "Added to “${c.group}”")

    /** #886 a group's name and colour. */
    private fun showGroupEditor(group: String) {
        val others = prefs.all().map { it.group }.filter { it.isNotBlank() && it != group }.toSet()
        overlay { close ->
            BrowserGroupEditor(group, BrowserTabGroups.colorOf(prefs.groupColors(), group), others,
                onSave = { name, color -> close(); prefs.renameGroup(group, name, color); showGrid() },
                onUngroup = { close(); prefs.ungroup(group); showGrid() },
                onClose = close)
        }
    }

    private fun promptForGroup(tab: BrowserTab) {
        overlay { close ->
            var name by remember { mutableStateOf(tab.group) }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Group") },
                text = {
                    OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
                        label = { Text("Group name") })
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        prefs.setGroupById(tab.key, name); showGrid()
                    }) { Text("Save") }
                },
            )
        }
    }

    /** The Tabs row: dense, no minHeight, scrolls sideways rather than wrapping on a narrow screen. */
    @Composable
    private fun TabsBar() {
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(start = 6.dp, top = 4.dp, end = 6.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically) {
            for (item in BrowserTabsBar.items(tabFilter)) {
                when (item) {
                    is BrowserTabsBar.Item.Sep -> Text(BrowserTabsBar.SEP, color = Color.White.copy(alpha = 0.5f), maxLines = 1,
                        modifier = Modifier.padding(horizontal = 2.dp))
                    is BrowserTabsBar.Item.Action -> {
                        val toggle = item.id == BrowserTabsBar.Id.NORMAL || item.id == BrowserTabsBar.Id.INCOGNITO
                        Text(item.label, color = Color.White, fontSize = 13.sp, maxLines = 1,
                            fontWeight = if (item.active) FontWeight.Bold else FontWeight.Normal,
                            modifier = Modifier
                                .alpha(if (toggle && !item.active) 0.65f else 1f)
                                .then(if (item.active) Modifier.clip(RoundedCornerShape(8.dp)).background(
                                    if (item.id == BrowserTabsBar.Id.INCOGNITO) Color(0xFF4B5563) else Color(0xFF7C3AED)) else Modifier)
                                .clickable {
                                    when (item.id) {
                                        BrowserTabsBar.Id.NEW_TAB -> promptForUrl(private_ = false)
                                        BrowserTabsBar.Id.NEW_INCOGNITO -> { tabFilter = BrowserTabsBar.Filter.INCOGNITO; promptForUrl(private_ = true) }
                                        BrowserTabsBar.Id.NORMAL -> { tabFilter = BrowserTabsBar.Filter.NORMAL; showGrid() }
                                        BrowserTabsBar.Id.INCOGNITO -> { tabFilter = BrowserTabsBar.Filter.INCOGNITO; showGrid() }
                                        BrowserTabsBar.Id.HISTORY -> showHistory()
                                    }
                                }
                                .padding(horizontal = 6.dp, vertical = 3.dp))
                    }
                }
            }
        }
    }

    /**
     * New tab, as a Compose screen: the field and the same three-section suggestions as the address bar
     * (#886: the old AutoCompleteTextView-in-a-dialog dropdown overlapped its own field and the
     * keyboard). Accepts a URL or a search — same rule as the address bar. [joinTabKey]: the strip's +,
     * the new tab joins (or starts) that tab's group.
     */
    private fun promptForUrl(private_: Boolean = browserSettings.bool("private_by_default") == true, joinTabKey: String? = null) {
        val st = SuggestState().also { it.engineLabel = engine().label; it.visible = true }
        overlay { close ->
            BrowserOpenScreen(
                title = if (private_) "New private tab" else if (joinTabKey != null) "New tab in this group" else "New tab",
                hint = "Search ${engine().label}, or type a URL",
                state = st,
                onQuery = { q -> refreshSuggestions(st, q, private_) },
                onSubmit = { q -> close(); openEntry(q, private_, joinTabKey) },
                onPick = { sug -> close(); openEntry(sug.url, private_, joinTabKey) },
                onClose = close,
            )
        }
    }

    /** Resolve what was typed to a destination, open it as a new tab.
     *  Nothing typed opens the `homepage` setting, when he has set one. */
    private fun openEntry(raw: String, private_: Boolean = false, joinTabKey: String? = null) {
        val typed = raw.ifBlank { browserSettings.string("homepage").orEmpty() }
        val url = BrowserSearch.resolve(typed, engine())
        if (url.isBlank()) return
        if (joinTabKey == null) return openUrlInTab(url, private_)
        // #886 the strip's +: always a NEW tab, placed in the group of the tab it was pressed on.
        val tab = prefs.addNew(url, url, isPrivate = private_)
        prefs.startOrJoinGroup(joinTabKey, tab.key)
        prefs.setActiveId(tab.key)
        showDetail(prefs.byId(tab.key) ?: tab)
    }

    /** #886 open [url] in the tab that already shows it, else in a new one, and make it the active tab. */
    private fun openUrlInTab(url: String, private_: Boolean = false) {
        val tab = prefs.add(url, url, isPrivate = private_)
        prefs.setActiveId(tab.key)
        showDetail(tab)
    }

    // ── suggestions (#886: two labelled sections, laid out IN the page area, never a PopupWindow) ──

    /** The query the remote suggestions in [remoteRows] answer. */
    private var remoteFor = ""
    private var remoteRows: List<String> = emptyList()
    private var pendingRemote: Runnable? = null
    private val ui = android.os.Handler(android.os.Looper.getMainLooper())
    private val suggestState = SuggestState()

    /**
     * Recompute [state] for [q]: the local sections at once, then (setting `search_suggestions`, a
     * non-URL query, not a private tab) the default engine's own query suggestions after a short
     * pause, which fold into the same state when they arrive and only if the query has not moved on.
     */
    private fun refreshSuggestions(state: SuggestState, q: String, private_: Boolean) {
        state.query = q
        val e = engine()
        state.engineLabel = e.label
        fun local(remote: List<String>) = BrowserSuggest.sections(q, prefs.all(), history.all(), e, remote, favourites = bookmarks.all())
        state.sections = local(if (remoteFor == q) remoteRows else emptyList())
        pendingRemote?.let { ui.removeCallbacks(it) }
        if (BrowserRemoteSuggest.shouldAsk(browserSettings.bool("search_suggestions") == true, q, private_)) {
            val r = Runnable {
                Thread {
                    val got = BrowserRemoteSuggest.fetch(e.suggest, q)
                    ui.post { if (isAdded && state.query == q) { remoteFor = q; remoteRows = got; state.sections = local(got) } }
                }.start()
            }
            pendingRemote = r
            ui.postDelayed(r, REMOTE_SUGGEST_DELAY_MS)
        }
    }

    // ── #886 Translate page in place, and Summarise by topics ────────────────────────────────

    private val translateState = androidx.compose.runtime.mutableStateOf<PageTranslator.State>(PageTranslator.State.Idle)
    private var translateEngineLabel = ""

    private fun unwrapJs(raw: String?): String? = runCatching {
        org.json.JSONTokener(raw ?: return null).nextValue() as? String
    }.getOrNull()

    /** The translator's window onto the live page and onto threads. */
    private val translateIo = object : PageTranslator.Io {
        override fun collect(maxChars: Int, done: (String?) -> Unit) {
            val wv = webView ?: return done(null)
            wv.evaluateJavascript(BrowserPageActions.script(requireContext(), "translate_collect", maxChars)) { done(unwrapJs(it)) }
        }
        override fun apply(mapJson: String, done: (String?) -> Unit) {
            val wv = webView ?: return done(null)
            wv.evaluateJavascript(BrowserPageActions.script(requireContext(), "translate_apply").replace("__MAP__", mapJson)) { done(unwrapJs(it)) }
        }
        override fun restore(done: (String?) -> Unit) {
            val wv = webView ?: return done(null)
            wv.evaluateJavascript(BrowserPageActions.script(requireContext(), "translate_restore")) { done(unwrapJs(it)) }
        }
        override fun background(work: () -> Unit) { Thread(work).start() }
        override fun main(work: () -> Unit) { ui.post { if (isAdded) work() } }
    }
    private val translator = PageTranslator(translateIo) { st -> translateState.value = st }

    /**
     * Tier 2 of the fleet autofill (a0_docs/eng-specs/autofill-3-tier.md): the DOM engine for NON-SECRET
     * profile data from the Cloud Account SOT. Logins, passwords, codes and cards stay with Cloud Vault
     * through the Android Autofill framework, which this WebView keeps feeding (importantForAutofill).
     */
    /**
     * #1222 Web Bluetooth: Android WebView has no navigator.bluetooth, so each page gets a polyfill over a JS bridge to libs:bluetooth,
     * a native device chooser and per-origin permission memory (WebBluetooth.kt). Android's own Bluetooth permission is asked here.
     */
    private val webBluetooth by lazy {
        WebBluetooth(requireContext()) { perms, done -> done(false); permsNeeded(perms.toList()) }
    }

    /** #1225 Web Serial, WebUSB and Web NFC: the same shape as Web Bluetooth (WebHardware.kt); Android asks its own USB permission, NFC uses reader mode on this activity. */
    private val webHardware by lazy { WebHardware(requireContext()) { activity } }

    private val domAutofill by lazy {
        // #900 once the user keeps profiles of their own (Configs > Autocomplete), those fill and this Cloud Account chip steps aside.
        DomAutofill(requireContext(), { browserSettings.bool("profile_fill") != false && !ac.hasLocalProfiles() }, { browserSettings.bool("offer_save_address") != false })
    }

    /** #900 Autocomplete: profiles, per-site memory, saved passwords and cards, Cloud Vault; the fill bar, chooser and save prompt. */
    private val ac by lazy {
        AcAutocomplete(requireContext(), { browserSettings.bool(it) }, { activity as? androidx.fragment.app.FragmentActivity }, { config.addons["vault"]?.requiresPackage })
    }
    /** Bumped when a Configs > Autocomplete screen changed a store, so it re-reads. */
    private val acTick = mutableIntStateOf(0)

    private val translateMore = Runnable { translator.more() }

    private fun textToolsPort() = TextToolsClientPort(BrowserPageActions.textTools(requireContext()))

    private fun engineSetting(): String =
        browserSettings.string("translate_engine")?.takeIf { it in PageTranslate.ENGINES } ?: PageTranslate.ON_DEVICE

    private fun engineName(engine: String) = if (engine == PageTranslate.OPENROUTER) "OpenRouter (LLM)" else "On-device ML"

    private fun targetTag() = PageTranslate.targetTag(browserSettings.string("translate_target"), java.util.Locale.getDefault().language)

    /** Translate page, toggled: on = start (the chosen engine, the chosen language); on again = the originals back. */
    private fun toggleTranslate(): JSONObject {
        val ok = { JSONObject().put("ok", true).put("id", "translate") }
        if (translator.active) { translator.restore(); return ok().put("translated", false) }
        val engine = engineSetting()
        val tag = targetTag()
        translateEngineLabel = engineName(engine)
        translator.start(PageTranslate.backend(engine, textToolsPort()), tag)
        return ok().put("translated", true).put("engine", engine).put("target", tag)
    }

    private fun showTopics(wv: WebView) {
        val st = TopicsState()
        val engine = engineSetting()
        val cap = config.addons["ai"]?.config?.optJSONObject("summarize")?.optInt("input_cap_chars", 12000) ?: 12000
        st.engine = engineName(engine)
        overlay { close ->
            BrowserTopicsPanel(st,
                onCopy = {
                    val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                    clip?.setPrimaryClip(android.content.ClipData.newPlainText("topics", PageTopics.asText(st.topics)))
                    toast("Copied")
                }, onClose = close)
        }
        BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "page_topics", cap)) { r ->
            val page = PageTopics.parsePage(r?.toString())
            if (page == null) { st.loading = false; st.error = "There is no readable text on this page."; return@run }
            if (engine != PageTranslate.OPENROUTER) {
                st.topics = PageTopics.outline(page); st.note = PageTopics.ON_DEVICE_NOTE; st.loading = false
                return@run
            }
            val port = textToolsPort()
            Thread {
                val res = if (port.installed()) port.summariseWith(PageTopics.llmInput(page, cap),
                    PageTopics.prompt(PageTranslate.languageName(targetTag()))) else com.diegonmarcos.superapp.texttools.TextTools.Result.failed(com.diegonmarcos.superapp.texttools.TextTools.NOT_INSTALLED)
                val label = port.providerLabel()
                ui.post {
                    if (!isAdded) return@post
                    if (res.ok) { st.topics = PageTopics.parseTopics(res.text); if (!label.isNullOrBlank()) st.engine = "OpenRouter (LLM) · $label" }
                    else { st.error = res.error.orEmpty(); st.topics = PageTopics.outline(page)
                        st.note = "The model could not answer, so this is the page's own outline instead." }
                    st.loading = false
                }
            }.start()
        }
    }

    // ── #887 site data: offline copies, storage, cookies ─────────────────────────────────────

    private val offlineSites by lazy { OfflineSites(requireContext()) }
    private val mhtml by lazy { MhtmlOpener(requireContext()) }
    private val saveState = SaveState()

    /** Save the current page (one MHTML) or the whole site (same origin, within the Configs limits). */
    private fun startSave(wv: WebView, wholeSite: Boolean, done: (JSONObject) -> Unit) {
        val url = wv.url.orEmpty()
        val ok = { JSONObject().put("ok", true).put("id", if (wholeSite) "save_site" else "save_page") }
        if (SiteScope.origin(url).isEmpty()) return done(ok().put("ok", false).put("error", "only http(s) pages can be saved offline"))
        if (!wholeSite) {
            OfflinePageSaver.save(offlineSites, wv) { site, err ->
                toast(if (site != null) "Saved for offline: ${SiteData.human(site.bytes)} (Configs ▸ Data & storage ▸ Offline copies)" else err.orEmpty())
            }
            return done(ok().put("started", true))
        }
        if (OfflineSiteJob.active != null) return done(ok().put("ok", false).put("error", "a site is already being saved"))
        val limits = CrawlLimits.of(browserSettings.int("offline_depth"), browserSettings.int("offline_max_pages"), browserSettings.int("offline_max_mb"))
        val settle = config.addons["scraper"]?.config?.optLong("settle_ms", 800) ?: 800L
        saveState.title = SiteScope.origin(url).substringAfter("://")
        saveState.progress = null
        val job = OfflineSiteJob(requireContext(), offlineSites, url, wv.title.orEmpty(), limits, wv.settings.userAgentString, settle,
            onProgress = { saveState.progress = it },
            onDone = { site ->
                OfflineSiteJob.active = null
                toast(if (site != null) "Saved ${site.pages.size} page(s), ${SiteData.human(site.bytes)}" else "Nothing could be saved")
            },
            profile = profileOfWebView[wv])
        OfflineSiteJob.active = job
        overlay(bottom = true) { close -> BrowserSavePanel(saveState, onStop = { job.cancel() }, onClose = close) }
        job.start()
        done(ok().put("started", true).put("max_pages", limits.maxPages).put("depth", limits.depth))
    }

    private fun showOffline() {
        val sites = androidx.compose.runtime.mutableStateOf(offlineSites.list())
        overlay { close ->
            BrowserOfflineScreen(sites.value,
                onOpen = { s -> close(); openOffline(s) },
                onDelete = { s -> offlineSites.delete(s.id); sites.value = offlineSites.list() },
                onDeleteAll = { offlineSites.deleteAll(); sites.value = offlineSites.list() },
                onClose = close)
        }
    }

    /** A saved copy opens as a tab on its first page's file; links inside it that the copy holds stay inside it. */
    private fun openOffline(site: OfflineSite) {
        val first = site.pages.firstOrNull() ?: return toast("This copy has no pages")
        val url = offlineSites.fileUrl(first)
        val tab = prefs.add(url, site.title.ifBlank { site.origin })
        prefs.setActiveId(tab.key)
        showDetail(tab)
    }

    private fun showStorage() {
        val st = StorageState()
        st.host = BrowserSitePolicy.hostOf(webView?.url)
        fun measure() {
            st.items = null
            Thread { val items = BrowserStorage.breakdown(requireContext()); ui.post { if (isAdded) st.items = items } }.start()
            // Category 3, measured apart: the Cloud Account SOT and the browser-local leftovers.
            Thread { val a = BrowserAutofillData.summary(requireContext(), st.host); ui.post { if (isAdded) st.autofill = a } }.start()
        }
        measure()
        overlay { close ->
            BrowserStorageScreen(st, onClear = { ids ->
                BrowserStorage.clear(requireContext(), ids) { r ->
                    toast(if (r.optBoolean("ok")) "Cleared" else r.optString("error"))
                    if ("cookies" in ids || "dom" in ids) webView?.reload()
                    measure()
                }
            }, onClose = close,
                onForgetAutofill = { BrowserAutofillData.clearLocal(requireContext(), domAutofill); toast("Browser-local autofill data forgotten"); measure() },
                onOpenAccount = { openCloudAccount() })
        }
    }

    /** Cloud Account, where autofill profiles, site rules and snippets are edited (its OPEN action). */
    private fun openCloudAccount() {
        val i = android.content.Intent("com.diegonmarcos.cloudaccount.OPEN").setPackage(com.diegonmarcos.superapp.autofill.AutofillSot.PKG)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { startActivity(i) }.onFailure { toast("Cloud Account is not installed") }
    }

    // ── DETAIL mode (WebView) ────────────────────────────────────────

    /** Swap the active tab to [url] and draw it. */
    private fun navigateTo(url: String) {
        if (url.isBlank()) return
        openUrlInTab(url)
    }

    /**
     * Load [url]; one of the app's private names (exact host, from the baked config) is resolved first through the
     * app's loopback DNS and its port mapped from the same declaration. A failed resolution shows a page saying so:
     * the name is never passed on to the system resolver, where a `.app` name is somebody else's website.
     */
    private fun WebView.loadResolved(url: String) {
        val names = config.privateNames
        if (!names.matches(url)) { loadUrl(url); return }
        val view = this
        val appCtx = context?.applicationContext
        names.resolveAsync(url, start = { startPrivateNamesService(appCtx, names) }) { r ->
            view.post {
                when (r) {
                    is PrivateNames.Result.Ok -> view.loadUrl(r.url)
                    is PrivateNames.Result.Fail -> view.loadData(android.util.Base64.encodeToString(PrivateNames.errorPage(r).toByteArray(), android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP), "text/html; charset=utf-8", "base64")
                }
            }
        }
    }

    /**
     * Start the app that owns the private names (Cloud C3 WebServer's keep-alive foreground service) by explicit
     * intent, the component declared in build.json serving.keepalive and baked as private_names.service. The service
     * is guarded by the fleet's CONSTELLATION_DATA signature permission, so only a fleet-signed browser may.
     */
    private fun startPrivateNamesService(ctx: android.content.Context?, names: PrivateNames): Boolean {
        val target = names.service ?: return false
        if (ctx == null) return false
        return runCatching {
            ctx.startForegroundService(android.content.Intent().setClassName(target.pkg, target.cls))
            true
        }.getOrDefault(false)
    }

    /** Is [url] a page of this phone's own loopback servers (127.0.0.1 or localhost)? */
    private fun isLoopbackPage(url: String?): Boolean = BrowserSitePolicy.hostOf(url).let { it == "127.0.0.1" || it == "localhost" }

    /**
     * Page diagnostics to logcat are OFF in every build and for every reader. The emulator test turns them on for its own run with
     * `adb shell settings put global cloud_browser_page_log 1` (a global setting nothing in the app writes). Not BuildConfig.DEBUG:
     * the build that ships to the phone is the debug variant, so that gate would leave the logging on for the reader.
     */
    private fun pageLogOn(url: String?): Boolean = runCatching {
        // 1 = loopback pages only; 2 (#1140, the emulator test of a public page) = any page.
        val v = android.provider.Settings.Global.getInt(requireContext().contentResolver, "cloud_browser_page_log", 0)
        v == 2 || (v == 1 && isLoopbackPage(url))
    }.getOrDefault(false)

    /** #886 what the strip draws, as Compose state the host refreshes when a tab's page, title, icon or group changes. */
    private class StripModel(val tabs: List<BrowserTab>, val activeKey: String, val group: String, val color: Int)
    private val strip = androidx.compose.runtime.mutableStateOf(StripModel(emptyList(), "", "", 0))
    /** The address bar's text, caret and selection; written by the page's own navigation while the field is not focused. */
    private var addressValue by mutableStateOf(TextFieldValue(""))
    private var addressFocused = false
    /** Brave-style: the address overlay (search box + current-address card) is open. */
    private var addressOpen by mutableStateOf(false)
    /** Set by the detail screen once composed: gives up the field's focus and closes the keyboard. */
    private var clearAddressFocus: () -> Unit = {}

    private fun refreshStrip() {
        val all = prefs.all()
        val cur = currentTab() ?: return
        val shown = BrowserStripRules.visible(all, cur).map { t ->
            if (t.iconPath.isNotBlank()) t else faviconFile(t.url)?.let { t.copy(iconPath = it.absolutePath, id = t.key) } ?: t
        }
        strip.value = StripModel(shown, cur.key, cur.group, BrowserTabGroups.colorOf(prefs.groupColors(), cur.group))
    }

    private fun faviconFile(url: String): File? {
        val host = BrowserSitePolicy.hostOf(url).ifEmpty { return null }
        return File(File(requireContext().cacheDir, "favicons"), sha256(host) + ".png").takeIf { it.isFile }
    }

    /** #886 keep the page's favicon (small, by host) for the strip; never for a private tab. */
    private fun saveFavicon(tabKey: String, pageUrl: String?, icon: Bitmap?) {
        icon ?: return
        val tab = prefs.byId(tabKey) ?: return
        if (tab.isPrivate) return
        val host = BrowserSitePolicy.hostOf(pageUrl ?: tab.url).ifEmpty { return }
        runCatching {
            val dir = File(requireContext().cacheDir, "favicons").apply { mkdirs() }
            val f = File(dir, sha256(host) + ".png")
            val small = Bitmap.createScaledBitmap(icon, 48, 48, true)
            f.outputStream().use { small.compress(Bitmap.CompressFormat.PNG, 90, it) }
            prefs.setIcon(tabKey, f.absolutePath)
            refreshStrip()
        }
    }

    /**
     * #886 THE COMMIT: the tab [tabKey] is now on [url]. This is the write that was missing (see
     * [BrowserTabStore]): without it the store kept the url the tab was OPENED with, and a restart
     * brought back that older page. Also keeps the address text and the strip in step.
     */
    private fun onCommitted(tabKey: String, view: WebView?, url: String?) {
        if (!BrowserTabStore.shouldCommit(url) || readerOn) return
        prefs.commit(tabKey, url, view?.title)
        if (!addressFocused && url != null) addressValue = TextFieldValue(url)
        refreshStrip()
    }

    /**
     * #886 write the showing tab's WebView state (its back/forward list) to its file, and its url to
     * the store one more time. Called when the page finishes, when the app pauses or stops, before the
     * WebView is destroyed (a tab switch, the grid) — i.e. at every point the state could be lost.
     * Never for a private tab.
     */
    private fun saveTabState() {
        val wv = webView ?: return
        val tab = currentTab() ?: return
        if (!BrowserTabStore.shouldCommit(wv.url) || readerOn) return
        prefs.commit(tab.key, wv.url, wv.title)
        if (!tab.isPrivate) BrowserWebState.save(requireContext(), wv, tab.key)
    }

    private fun showDetail(tab: BrowserTab) {
        commitSwipe()
        teardownWebView()   // saves the tab being left (its back stack) and frees its WebView
        translator.abandon()
        mode = Mode.DETAIL(tab.key)
        val tabKey = tab.key
        val url = tab.url
        val ctx = requireContext()
        suggestState.visible = false
        addressValue = TextFieldValue(url)
        addressFocused = false
        addressOpen = false

        webTabKey = tabKey
        // BROWSER (#1371): JS, storage, zoom, geolocation, and #893 sign-in popups (multiple windows, window.opener)
        webView = CloudWeb.create(ctx, WebProfile.BROWSER) { bindProfile(it, tab) }.apply {   // the profile binds before anything else
            onWebViewCreated?.invoke(this)   // the scroll bridge must exist BEFORE the first load
            cookiesOf(this).setAcceptCookie(true)
            // Passkeys: WebAuthn is off in WebView unless asked for; a refusal falls back to the system browser.
            passkeyMode = PasskeySupport.apply(settings)
            val self = this
            addJavascriptInterface(object {
                @android.webkit.JavascriptInterface
                fun failed(name: String?, ms: Long) {
                    if (PasskeySupport.isWebAuthnFailure(name, ms)) self.post { offerSecureBrowser(self, null, true) }
                }
            }, PasskeySupport.JS_BRIDGE)
            // Tier 2 DOM autofill: the page engine reports focus / submit (keys and ids, never values) over this bridge.
            domAutofill.attach(this)
            webBluetooth.attach(this, tab.isPrivate)
            webHardware.attach(this, tab.isPrivate)
            ac.attach(this)
            // #802 the Android Autofill Framework (Cloud Vault's service) sees the page's fields.
            importantForAutofill = if (browserSettings.bool("autofill_enabled") == false) View.IMPORTANT_FOR_AUTOFILL_NO
                else View.IMPORTANT_FOR_AUTOFILL_YES
            // #802 a private tab keeps nothing in the HTTP cache.
            if (tab.isPrivate) settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            applySettings(this, url)
            webViewClient = object : CloudWebClient(WebProfile.BROWSER) {
                override fun onPageStarted(view: WebView?, startedUrl: String?, favicon: Bitmap?) {
                    super.onPageStarted(view, startedUrl, favicon)
                    // A real navigation leaves reader view; the reader's own render does not.
                    if (readerPending) readerPending = false else { readerOn = false; translator.abandon() }
                    domAutofill.dismiss()
                    ac.dismiss()
                    view?.let { webBluetooth.onPageStarted(it) }
                    view?.let { webHardware.onPageStarted(it) }
                    pageHostNow = BrowserSitePolicy.hostOf(startedUrl)
                    // Settings, then this host's shields: a host that was shielded must not leave JS off for the next.
                    view?.let { applySettings(it, startedUrl) }
                }

                /** #900 Block trackers: a subresource to a declared tracker host is answered empty (off the main thread: no WebView calls). */
                override fun shouldInterceptRequest(view: WebView?, request: android.webkit.WebResourceRequest?): android.webkit.WebResourceResponse? {
                    val r = request ?: return null
                    val page = pageHostNow
                    val block = page.isNotEmpty() && trackers.shouldBlock(r.url.host.orEmpty().lowercase(), page, r.isForMainFrame) && trackersBlocked(page)
                    // #1141 the Network panel's log (bounded, in memory; a private tab logs nothing).
                    if (!tab.isPrivate) DevTools.net.add(NetLog.entry(System.currentTimeMillis(), r.method, r.url.toString(), r.isForMainFrame, r.requestHeaders?.get("Accept"), block))
                    if (block) return android.webkit.WebResourceResponse("text/plain", "utf-8", java.io.ByteArrayInputStream(ByteArray(0)))
                    // #1518 a pinned site is served from its offline copy (the app's hook), cache-first
                    return BrowserHostHooks.intercept?.invoke(r)
                }

                /** #1141 the status of a failed request (WebView reports 4xx / 5xx only), onto its Network row. */
                override fun onReceivedHttpError(view: WebView?, request: android.webkit.WebResourceRequest?, errorResponse: android.webkit.WebResourceResponse?) {
                    val u = request?.url?.toString() ?: return
                    val code = errorResponse?.statusCode ?: return
                    DevTools.net.updateLast({ it.url == u && it.status == null }) { it.copy(status = code) }
                }

                /** #887 inside a saved copy, a link to a page the copy holds opens the saved page, not the network. */
                override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean {
                    val target = request?.url?.toString() ?: return false
                    // A magnet link goes to the torrent engine when this app ships one (cloud-browser does).
                    if (com.diegonmarcos.superapp.browser.download.DlMagnet.isMagnet(target) && addMagnet(target)) return true
                    // The app's private names (build.json ui.browser.private_names) go through its own loopback DNS, never the system's.
                    if (request.isForMainFrame && config.privateNames.matches(target)) { view?.loadResolved(target); return true }
                    // #893 web pages load as they are; an app link (intent:, market:) never leaves the browser: its
                    // fallback url loads, else nothing happens. #1420 Google OAuth goes to a Custom Tab.
                    when (val d = BrowserNavPolicy.decide(target)) {
                        is BrowserNavPolicy.Decision.CustomTab -> { handOffToCustomTab(d.url); return true }
                        is BrowserNavPolicy.Decision.Redirect -> { view?.loadUrl(d.url); return true }
                        BrowserNavPolicy.Decision.Block -> return true
                        BrowserNavPolicy.Decision.Load -> Unit
                    }
                    if (request.isForMainFrame && offlineSites.isOffline(view?.url)) {
                        val local = offlineSites.resolve(target)
                        if (local != null) { view?.loadUrl(local); return true }
                    }
                    return false
                }

                /** #886 fires once a navigation has COMMITTED (and for in-page routes): the url worth remembering. */
                override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
                    super.doUpdateVisitedHistory(view, visitedUrl, isReload)
                    onCommitted(tabKey, view, visitedUrl)
                }

                override fun onPageCommitVisible(view: WebView?, url: String?) {
                    super.onPageCommitVisible(view, url)
                    pullRefresh?.isRefreshing = false
                }

                override fun onReceivedError(view: WebView?, request: android.webkit.WebResourceRequest?, error: android.webkit.WebResourceError?) {
                    super.onReceivedError(view, request, error)
                    if (request?.isForMainFrame != false) pullRefresh?.isRefreshing = false
                }

                override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                    super.onPageFinished(view, finishedUrl)
                    pullRefresh?.isRefreshing = false
                    val u = finishedUrl ?: return
                    view?.let { onWebPageFinished?.invoke(it) }
                    // #1141 desktop view: the layout viewport is a desktop width even if the page's meta viewport says device-width.
                    if (desktopMode) view?.evaluateJavascript(ViewModePlan.DESKTOP_VIEWPORT_JS, null)
                    if (readerOn) return
                    // The layout numbers of a loopback page (logcat tag LocalPageMetrics), so a page that renders blank here and
                    // fine elsewhere says why (viewport, scroll size, text length) in the emulator test's evidence.
                    if (pageLogOn(u)) view?.evaluateJavascript("JSON.stringify({w:innerWidth,h:innerHeight,dpr:devicePixelRatio,sw:document.documentElement.scrollWidth,sh:document.documentElement.scrollHeight,bw:document.body?document.body.getBoundingClientRect().width:-1,bh:document.body?document.body.getBoundingClientRect().height:-1,text:document.body?document.body.innerText.length:-1,links:document.querySelectorAll('a[href]').length,js:true})") { r -> android.util.Log.d("LocalPageMetrics", "$u $r") }
                    // #1140 the page's timing seen from inside the WebView (to the console, which the page log carries): visibility, and how long a
                    // requestAnimationFrame and a timer take to fire. A page that mounts its content after two frames waits forever when frames are not produced.
                    if (pageLogOn(u)) view?.evaluateJavascript("(function(){var t0=performance.now();console.log('PageTiming visibility='+document.visibilityState+' hidden='+document.hidden+' ready='+document.readyState+' focus='+document.hasFocus());requestAnimationFrame(function(){console.log('PageTiming raf after '+Math.round(performance.now()-t0)+'ms')});setTimeout(function(){console.log('PageTiming timer after '+Math.round(performance.now()-t0)+'ms')},50);setTimeout(function(){var b=document.querySelector('.section-box');var c=b?getComputedStyle(b):null;var ud=navigator.userAgentData;console.log('PageTiming css dvh='+CSS.supports('height','100dvh')+' cv='+CSS.supports('content-visibility','auto')+' cis='+CSS.supports('contain-intrinsic-size','1px 1px')+' brands='+(ud?JSON.stringify(ud.brands):'none')+' box.height='+(c?c.height:'-')+' box.cv='+(c?c.contentVisibility:'-')+' box.display='+(c?c.display:'-')+' innerH='+innerHeight+' vv='+(window.visualViewport?Math.round(window.visualViewport.height):'-'))},2000);setTimeout(function(){console.log('PageTiming at+3s mounts='+document.querySelectorAll('[data-portal-mount]').length+' swiperSlides='+document.querySelectorAll('.swiper-slide').length+' sections='+document.querySelectorAll('.section-box').length+' sectionHeights='+Array.prototype.map.call(document.querySelectorAll('.section-box'),function(e){return Math.round(e.getBoundingClientRect().height)}).join(','))},3000)})()", null)
                    probePasskey(view, u)
                    // Tier 2: (re)install the DOM autofill engine; a private tab gets no prompts and never offers to save.
                    view?.let { domAutofill.inject(it, u, tab.isPrivate); ac.inject(it, u, tab.isPrivate) }
                    onCommitted(tabKey, view, u)
                    postDelayed({ if (webView === this@apply) saveTabState() }, 400)
                    // #887 an offline copy is not a visit.
                    if (offlineSites.isOffline(u)) return
                    if (mhtml.allowsFile(u)) return   // nor is a saved page opened from a file
                    // Snapshot for the tab switcher; a private tab follows build.json tab_thumbnails.incognito.
                    postDelayed({ if (webView === this@apply) captureTab(this@apply, tabKey) }, 600)
                    // #802 a private tab is never recorded: no history.
                    val origin = runCatching { java.net.URI(u) }.getOrNull()?.let { "${it.scheme}://${it.authority}" }
                    if (!privateSession.visit(prefs.byId(tabKey), origin)) return
                    // Item 5. Local store, no sink, no sync — see BrowserHistory.
                    history.record(u, view?.title ?: u)
                }
            }
            // Long-press on a link or image: a dense sheet; text and fields keep the system actions.
            setOnLongClickListener { v -> onPageLongPress(v as WebView) }
            setDownloadListener { dlUrl, ua, disposition, mime, _ ->
                // A .torrent (by name or application/x-bittorrent) becomes a torrent; anything else downloads.
                // #1401 Downloads, per site: block refuses, ask prompts once per site.
                val dlPerm = config.sitePerms.firstOrNull { it.id == "downloads" }
                val start = {
                    if (!downloadTorrent(dlUrl, ua, disposition, mime)) {
                        val d = download(dlUrl, ua, disposition, mime)
                        toast("Downloading ${d.file}")
                    }
                }
                if (dlPerm == null) start()
                else decide(BrowserSitePolicy.hostOf(webView?.url ?: dlUrl), listOf(dlPerm)) { ok -> if (ok) start() else toast("Download blocked for this site") }
            }
            webChromeClient = object : CloudChromeClient(activity) {
                /** #893 window.open / target=_blank: a child window the opener can post back to (sign-in popups). */
                override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                    // #1401 Pop-ups and redirects, per site.
                    if (!BrowserSitePolicy.popupAllowed(permState(BrowserSitePolicy.hostOf(view?.url), "popups"), isUserGesture)) { toast("Pop-up blocked"); return false }
                    return openPopup(transport, resultMsg)
                }

                /** A page of the phone's own loopback servers: its console goes to logcat (tag LocalPageConsole), never any other page's. */
                override fun onConsoleMessage(m: android.webkit.ConsoleMessage?): Boolean {
                    if (m != null && pageLogOn(webView?.url)) android.util.Log.d("LocalPageConsole", "${m.messageLevel()} ${m.sourceId()}:${m.lineNumber()} ${m.message()}")
                    // #1141 every page's console into the in-memory ring buffer (Dev Tools > Console); a private tab logs nothing.
                    if (m != null && !tab.isPrivate) DevTools.console.add(ConsoleLog.entry(System.currentTimeMillis(), m.messageLevel().name, m.message().orEmpty(), m.sourceId(), m.lineNumber(), webView?.url.orEmpty()))
                    // #1537 and into the shared web-core ring + logcat (the app's log export), so a page that renders wrong shows why; a private tab logs nothing
                    if (m != null && !tab.isPrivate) com.diegonmarcos.cloudlib.web.PageConsole.record(m.messageLevel().name, m.message(), m.sourceId(), m.lineNumber(), webView?.url, System.currentTimeMillis())
                    return false
                }

                override fun onReceivedTitle(view: WebView?, title: String?) {
                    val u = view?.url ?: return
                    if (BrowserTabStore.shouldCommit(u)) prefs.commit(tabKey, u, title ?: u)
                    prefs.updateTitleById(tabKey, title ?: u)
                    refreshStrip()
                }

                override fun onReceivedIcon(view: WebView?, icon: Bitmap?) {
                    saveFavicon(tabKey, view?.url, icon)
                }

                /** #1401 Fullscreen, per site: block keeps the video inline. */
                override fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback?) {
                    if (!BrowserSitePolicy.onUnlessBlocked(permState(BrowserSitePolicy.hostOf(webView?.url), "fullscreen"))) { callback?.onCustomViewHidden(); return }
                    super.onShowCustomView(view, callback)
                }

                /** #802/#1401 camera, microphone, protected media, MIDI SysEx: the site's rule, else ask; never silently granted. */
                override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
                    val host = request.origin?.host.orEmpty().lowercase()
                    val wanted = request.resources.filter { r -> config.sitePerms.any { r in it.webkit } }
                    val perms = config.sitePerms.filter { p -> wanted.any { it in p.webkit } }
                    if (wanted.isEmpty()) { request.deny(); return }
                    decide(host, perms) { ok -> if (ok) request.grant(wanted.toTypedArray()) else request.deny() }
                }

                override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                    val perm = config.sitePerms.firstOrNull { "geolocation" in it.webkit }
                    if (perm == null || origin == null) { callback?.invoke(origin, false, false); return }
                    decide(BrowserSitePolicy.hostOf(origin), listOf(perm)) { ok -> callback?.invoke(origin, ok, false) }
                }
            }
            // #886 the tab comes back where it was: its saved back stack when WebView can restore it,
            // else the page it last COMMITTED (never the url it was first opened with).
            // New text arrives as the reader scrolls: translate it too while a translation is on.
            setOnScrollChangeListener { _, _, y, _, oldY ->
                onWebScroll?.invoke((y - oldY).toFloat())
                if (translator.active) { ui.removeCallbacks(translateMore); ui.postDelayed(translateMore, 700) }
            }
            if (tab.isPrivate || !BrowserWebState.restore(ctx, this, tabKey)) loadResolved(url)
        }
        overlays.clear()
        onOverlaysClosed?.invoke()
        refreshStripFor(tab)
        screen = Screen.Detail(++detailSeq, tab, webView!!)
    }

    /** #893 the child window of a window.open, over the page; closing it (window.close, back) returns to the opener. */
    private fun openPopup(transport: WebView.WebViewTransport, msg: android.os.Message): Boolean {
        val ctx = context ?: return false
        val child = CloudWeb.create(ctx, WebProfile.BROWSER) { bindProfile(it, currentTab()) }
        PasskeySupport.apply(child.settings)
        applyViewMode(child)
        cookiesOf(child).setAcceptThirdPartyCookies(child, browserSettings.bool("block_third_party_cookies") != true)
        val dialog = android.app.Dialog(ctx, android.R.style.Theme_Black_NoTitleBar)
        child.webViewClient = object : CloudWebClient(WebProfile.BROWSER) {
            override fun shouldOverrideUrlLoading(view: WebView?, request: android.webkit.WebResourceRequest?): Boolean =
                when (val d = BrowserNavPolicy.decide(request?.url?.toString())) {
                    is BrowserNavPolicy.Decision.CustomTab -> { handOffToCustomTab(d.url); dialog.dismiss(); true }
                    BrowserNavPolicy.Decision.Load -> false
                    else -> true
                }
        }
        child.webChromeClient = object : CloudChromeClient(activity) {
            override fun onCloseWindow(window: WebView?) { dialog.dismiss() }
            override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                val t = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                return openPopup(t, resultMsg)
            }
        }
        dialog.setContentView(child)
        dialog.setOnDismissListener { runCatching { child.stopLoading(); child.destroy() } }
        dialog.setOnKeyListener { _, key, ev ->
            if (key == android.view.KeyEvent.KEYCODE_BACK && ev.action == android.view.KeyEvent.ACTION_UP) {
                if (child.canGoBack()) child.goBack() else dialog.dismiss(); true
            } else false
        }
        dialog.show()
        transport.webView = child
        msg.sendToTarget()
        return true
    }

    private fun refreshStripFor(tab: BrowserTab) {
        val all = prefs.all()
        val cur = all.firstOrNull { it.key == tab.key } ?: tab
        strip.value = StripModel(BrowserStripRules.visible(all, cur), cur.key, cur.group,
            BrowserTabGroups.colorOf(prefs.groupColors(), cur.group))
        refreshStrip()
    }

    /** Hide the dropdown, give the field up, close the keyboard. */
    private fun dismissSuggestions() {
        suggestState.visible = false
        addressOpen = false
        pendingRemote?.let { ui.removeCallbacks(it) }
        clearAddressFocus()
    }

    /** A row picked: an open tab switches to it; a search or a visited page loads in THIS tab. */
    private fun pickSuggestion(s: BrowserSuggest.Suggestion) {
        dismissSuggestions()
        if (s.source == BrowserSuggest.Source.TAB) {
            prefs.all().firstOrNull { it.url == s.url }?.let { t ->
                if (t.key != currentTab()?.key) { prefs.setActiveId(t.key); showDetail(t); return }
            }
        }
        webView?.loadResolved(s.url)
    }

    // ── Library: history, bookmarks, downloads (#802: Compose, over the page) ──

    /**
     * #1531 an app that owns a History page (Cloud Browser: Domains > History) sets this: the Tabs page's History link and the menu's
     * History open that page instead of this library's own list, so there is one history UI. Unset = the library's list below.
     */
    var onHistory: (() -> Unit)? = null

    /** Item 5's view of the on-device history, with Clear. */
    private fun showHistory() {
        onHistory?.let { it(); return }
        val all = history.all()
        overlay { close ->
            var q by remember { mutableStateOf("") }
            val visits = remember(q) { BrowserListFilter.visits(q, all) }
            Column(Modifier.fillMaxSize()) {
            SearchFilterField(q) { q = it }
            Box(Modifier.weight(1f).fillMaxWidth()) {
            BrowserListScreen("History", visits.map { ListRow(it.url, it.title.ifBlank { it.url }, it.url) },
                empty = "No history yet.", onOpen = { close(); navigateTo(it.id) }, onRemove = null,
                headerAction = "Clear" to { history.clear(); close(); showHistory() }, onClose = close)
            }
            }
        }
    }

    /** #893 Fav: the bookmarks as a list or an icon grid (persisted); tap opens, long-press edits / moves / deletes. */
    private fun showBookmarks() {
        overlay { close ->
            var rev by androidx.compose.runtime.remember { androidx.compose.runtime.mutableIntStateOf(0) }
            var view by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(bookmarks.viewMode()) }
            BrowserFavScreen(
                sections = rev.let { BrowserFavourites.sections(bookmarks.all()) }, view = view,
                icon = { u -> faviconFile(u)?.let { f -> android.graphics.BitmapFactory.decodeFile(f.path)?.asImageBitmap() } },
                onToggleView = { view = BrowserFavourites.toggled(view); bookmarks.setViewMode(view) },
                onOpen = { close(); navigateTo(it.url) },
                onDelete = { bookmarks.remove(it.url); rev++ },
                onEdit = { b, title, folder -> bookmarks.edit(b.url, title, folder); rev++ },
                // #1422 the declared chips; Webserver opens the cloud-webserver app's Pages (its `open` link)
                filters = favSeed.filters,
                collapsed = rev.let { bookmarks.collapsed() },
                onToggleGroup = { bookmarks.toggleCollapsed(it); rev++ },
                onOpenUrl = { close(); navigateTo(it) })
        }
    }

    private fun showDownloads() {
        if (downloads.managed) {
            val ctx = requireContext()
            overlay { close ->
                com.diegonmarcos.superapp.browser.download.DownloadsScreen(
                    com.diegonmarcos.superapp.browser.download.DlQueue.get(ctx),
                    onOpen = { j ->
                        // A saved page (.mhtml) opens here as a tab; the file is read through the same uri another app would get.
                        val page = if (MhtmlIntent.isMhtmlName(j.fileName)) com.diegonmarcos.superapp.browser.download.DlPublisher.shareUri(ctx, j) else null
                        if (page != null) { close(); mhtml.openUri(page) { url -> if (url != null && isAdded) openUrlInTab(url) else toast("Could not open ${j.fileName}") } }
                        else com.diegonmarcos.superapp.browser.download.DlPublisher.openIntent(ctx, j)
                        ?.let { runCatching { startActivity(it) }.onFailure { toast("No app opens this file") } } ?: toast("The file is gone") },
                    onShare = { j -> com.diegonmarcos.superapp.browser.download.DlPublisher.shareIntent(ctx, j)
                        ?.let { startActivity(it) } ?: toast("The file is gone") },
                    onClose = close)
            }
            return
        }
        val rows = downloads.all().map { ListRow(it.id.toString(), it.file, "${downloads.status(it.id)} · ${it.url}") }
        overlay { close ->
            BrowserListScreen("Downloads", rows, empty = "Nothing downloaded yet.",
                onOpen = { r -> close(); openDownload(r) },
                onRemove = null, headerAction = "Clear list" to { downloads.clear(); close(); showDownloads() },
                onClose = close)
        }
    }

    /** A saved page (.mhtml) opens here as a tab; any other download opens in the system's Downloads. */
    private fun openDownload(r: ListRow) {
        val uri = if (MhtmlIntent.isMhtmlName(r.title)) r.id.toLongOrNull()?.let { downloads.uriOf(it) } else null
        if (uri == null) { startActivity(android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)); return }
        mhtml.openUri(uri) { url -> if (url != null && isAdded) openUrlInTab(url) else toast("Could not open ${r.title}") }
    }

    // ── long-press on page content (BrowserContextMenu holds the rules) ──

    private fun onPageLongPress(wv: WebView): Boolean {
        val hit = wv.hitTestResult
        val type = hit.type; val extra = hit.extra
        when (type) {
            BrowserContextMenu.SRC_ANCHOR, BrowserContextMenu.SRC_IMAGE_ANCHOR -> {
                // The link behind the press (and its text) is only known asynchronously.
                val h = android.os.Handler(android.os.Looper.getMainLooper()) { m ->
                    val d = m.data
                    showContext(BrowserContextMenu.target(type, extra, d?.getString("url"), d?.getString("title")))
                    true
                }
                wv.requestFocusNodeHref(h.obtainMessage())
                return true
            }
            BrowserContextMenu.IMAGE -> { showContext(BrowserContextMenu.target(type, extra)); return true }
        }
        return false
    }

    private fun showContext(t: BrowserContextMenu.Target?) {
        if (t == null || !isAdded) return
        val actions = BrowserContextMenu.actions(t, hasPrivate = true)
        if (actions.isEmpty()) return
        overlay { close ->
            BrowserContextSheet(t.linkUrl ?: t.imageUrl.orEmpty(), actions, onPick = { a -> close(); runContext(a, t) }, onDismiss = close)
        }
    }

    private fun runContext(a: BrowserContextMenu.Action, t: BrowserContextMenu.Target) {
        val link = t.linkUrl.orEmpty(); val img = t.imageUrl.orEmpty()
        val from = currentTab()
        BrowserContextMenu.placement(a, from?.isPrivate == true)?.let { p ->
            return openFromPage(if (a == BrowserContextMenu.Action.IMAGE_OPEN) img else link, p, from)
        }
        val ua = webView?.settings?.userAgentString
        when (a) {
            BrowserContextMenu.Action.COPY_LINK -> copyText("url", link, "Link copied")
            BrowserContextMenu.Action.COPY_LINK_TEXT -> copyText("text", t.linkText, "Text copied")
            BrowserContextMenu.Action.SHARE_LINK -> shareText(link)
            BrowserContextMenu.Action.DOWNLOAD_LINK -> toast("Downloading ${download(link, ua, null, null).file}")
            BrowserContextMenu.Action.ADD_FAV -> { bookmarks.add(link, t.linkText.ifBlank { link }, ""); toast("Added to Fav") }
            BrowserContextMenu.Action.IMAGE_DOWNLOAD -> toast("Downloading ${download(img, ua, null, null).file}")
            BrowserContextMenu.Action.IMAGE_COPY -> copyText("url", img, "Image address copied")
            BrowserContextMenu.Action.IMAGE_SHARE -> shareText(img)
            BrowserContextMenu.Action.IMAGE_SEARCH -> openFromPage(
                BrowserContextMenu.imageSearchUrl(engine().id, img), BrowserContextMenu.Placement(true, false, from?.isPrivate == true), from)
            else -> Unit
        }
    }

    /** A NEW tab for [url] placed by [p]: in the opener's group when asked, and focused only when [p] says so. */
    private fun openFromPage(url: String, p: BrowserContextMenu.Placement, from: BrowserTab?) {
        val tab = prefs.addNew(url, url, isPrivate = p.isPrivate)
        if (p.group && from != null) prefs.startOrJoinGroup(from.key, tab.key)
        if (p.activate) { prefs.setActiveId(tab.key); showDetail(prefs.byId(tab.key) ?: tab) }
        else { toast("Opened in background"); refreshStrip() }
    }

    private fun copyText(label: String, text: String, done: String) {
        val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        clip?.setPrimaryClip(android.content.ClipData.newPlainText(label, text))
        toast(done)
    }

    private fun shareText(text: String) = startActivity(android.content.Intent.createChooser(
        android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, text)
        }, "Share"))

    /**
     * A page download, with the page's cookies, agent and referer (so a signed-in download works),
     * into the `download_dir` setting. The first one asks for the notification permission the
     * download service's progress notification needs (Android 13+).
     */
    private fun download(url: String, ua: String?, disposition: String?, mime: String?): BrowserDownloadStarted {
        askNotificationsOnce()
        return downloads.start(url, ua, webView?.url, disposition, mime, browserSettings.string("download_dir").orEmpty()) { u ->
            cookiesOf(webView).getCookie(u)   // the requesting tab's jar: a private tab's cookies are in its profile
        }
    }

    /** A magnet link into the torrent engine; false when this host has none (the link is then left alone). */
    private fun addMagnet(uri: String): Boolean {
        if (!downloads.managed) return false
        val q = com.diegonmarcos.superapp.browser.download.DlQueue.get(requireContext())
        if (q.torrent == null) return false
        askNotificationsOnce()
        val j = q.addTorrent(uri, null, browserSettings.string("download_dir").orEmpty()) ?: return false
        toast("Torrent added: ${j.fileName}")
        return true
    }

    /** A .torrent the page offers: fetched with the tab's cookies, then added as a torrent. */
    private fun downloadTorrent(url: String, ua: String?, disposition: String?, mime: String?): Boolean {
        if (!downloads.managed || !com.diegonmarcos.superapp.browser.download.DlMagnet.isTorrentFile(url, mime, disposition)) return false
        val q = com.diegonmarcos.superapp.browser.download.DlQueue.get(requireContext())
        if (q.torrent == null) return false
        askNotificationsOnce()
        q.addTorrentUrl(url, ua, webView?.url, cookiesOf(webView).getCookie(url), browserSettings.string("download_dir").orEmpty()) { j ->
            if (isAdded) toast(if (j != null) "Torrent added: ${j.fileName}" else "Could not read that .torrent")
        }
        return true
    }

    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || !downloads.managed) return
        val perm = android.Manifest.permission.POST_NOTIFICATIONS
        if (androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), perm) == android.content.pm.PackageManager.PERMISSION_GRANTED) return
        if (!downloads.firstNotificationAsk()) return
        permsNeeded(listOf(perm))
    }

    /**
     * Pin [url] to the launcher; it opens here (MainActivity takes VIEW intents).
     * #1604 the shortcut carries the page's real title and [favicon]; a page with no
     * usable favicon falls back to this app's launcher icon so the pin never fails for want of an icon.
     */
    private fun addToHome(url: String, title: String, favicon: android.graphics.Bitmap? = null): Boolean {
        val ctx = requireContext()
        if (!androidx.core.content.pm.ShortcutManagerCompat.isRequestPinShortcutSupported(ctx)) return false
        val label = title.ifBlank { url }
        val icon = favicon?.takeIf { !it.isRecycled && it.width > 0 && it.height > 0 }
            ?.let { androidx.core.graphics.drawable.IconCompat.createWithBitmap(it) }
            ?: androidx.core.graphics.drawable.IconCompat.createWithResource(ctx, ctx.applicationInfo.icon)
        val info = androidx.core.content.pm.ShortcutInfoCompat.Builder(ctx, "page:" + sha256(url).take(16))
            .setShortLabel(label.take(24))
            .setLongLabel(label.take(64))
            .setIntent(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url)).setPackage(ctx.packageName))
            .setIcon(icon)
            .build()
        return androidx.core.content.pm.ShortcutManagerCompat.requestPinShortcut(ctx, info, null)
    }

    // ── misc ─────────────────────────────────────────────────────────

    private fun captureTab(wv: WebView, tabKey: String) {
        val tab = prefs.byId(tabKey) ?: return   // a tab that just closed must not get its picture back
        thumbs.capture(wv, tabKey, tab.isPrivate)
    }

    private fun sha256(s: String): String {
        val md = MessageDigest.getInstance("SHA-256")
        return md.digest(s.toByteArray()).joinToString("") { "%02x".format(it) }.take(40)
    }

    /** #802 the catalogue's page settings onto [wv]. An undeclared key leaves WebView's own. */
    private fun applySettings(wv: WebView, url: String? = wv.url) {
        val s = wv.settings
        browserSettings.bool("load_images")?.let { s.loadsImagesAutomatically = it }
        // #900 this site's own zoom (page menu > Zoom) beats the global text size.
        val siteHost = BrowserSitePolicy.hostOf(url)
        (siteHost.takeIf { it.isNotEmpty() }?.let { sitePerms.zoom(it) } ?: browserSettings.int("text_zoom"))?.let { s.textZoom = it }
        // #893 third-party cookies are ON unless the user blocks them (WebView's own default is off, which breaks sign-in).
        cookiesOf(wv).setAcceptThirdPartyCookies(wv, browserSettings.bool("block_third_party_cookies") != true)
        applyViewMode(wv)
        applyShields(wv, url)
        applySitePerms(wv, url)
        // #887 file access only for a page of an offline copy (targetSdk 30+ has it off by default).
        // An .mhtml archive opened from outside (or copied to the cache from a content:// uri) is read the same way.
        // #802 JS: the user's setting, then this site's shield (a `deny` rule turns it off here).
        val jsHost = BrowserSitePolicy.hostOf(url)
        val js = browserSettings.bool("javascript") != false && !(jsHost.isNotEmpty() && shieldDenied(jsHost, "javascript"))
        CloudWeb.site(wv, javaScript = js, fileAccess = offlineSites.isOffline(url) || mhtml.allowsFile(url))
    }

    /** #802 per-site shields: a `deny` rule for javascript / images on this host turns it off here. */
    private fun applyShields(wv: WebView, url: String?) {
        val host = BrowserSitePolicy.hostOf(url)
        if (host.isEmpty()) return
        if (shieldDenied(host, "images")) wv.settings.loadsImagesAutomatically = false
    }

    /** #1401 the per-site permissions WebSettings and the cookie manager enforce: autoplay, storage, insecure content, 3rd-party cookies. */
    private fun applySitePerms(wv: WebView, url: String?) {
        val host = BrowserSitePolicy.hostOf(url)
        if (host.isEmpty()) return
        CloudWeb.sitePermissions(
            wv,
            mediaNeedsGesture = BrowserSitePolicy.autoplayNeedsGesture(permState(host, "autoplay")),
            storage = BrowserSitePolicy.onUnlessBlocked(permState(host, "site_storage")),
            mixedContent = BrowserSitePolicy.mixedContentMode(permState(host, "insecure_content")),
        )
        cookiesOf(wv).setAcceptThirdPartyCookies(wv, BrowserSitePolicy.onUnlessBlocked(permState(host, "third_party_cookies")))
    }

    /** #900 are trackers blocked on [host]? Its rule, else the global setting's default. Safe off the main thread. */
    private fun trackersBlocked(host: String): Boolean {
        val dflt = if (browserSettings.bool("block_trackers") == false) BrowserSitePolicy.ALLOW else BrowserSitePolicy.DENY
        return BrowserSitePolicy.resolve(sitePerms.rules(), host, "trackers", dflt) == BrowserSitePolicy.DENY
    }

    private fun shieldDenied(host: String, perm: String): Boolean =
        config.sitePerms.firstOrNull { it.id == perm }?.let { sitePerms.resolve(host, it) == BrowserSitePolicy.DENY } == true

    private fun currentTab(): BrowserTab? {
        val id = (mode as? Mode.DETAIL)?.id ?: return null
        return prefs.byId(id)
    }

    /**
     * #802 may [host] have [perms]? Its rules decide; `ask` shows the question over the
     * page and remembers the answer; an allowed permission the APP lacks goes through
     * Android's own prompt first. Every path ends in exactly one [done].
     */
    private fun decide(host: String, perms: List<BrowserSitePerm>, done: (Boolean) -> Unit) {
        val vals = perms.map { sitePerms.resolve(host, it) }
        when {
            perms.isEmpty() || BrowserSitePolicy.DENY in vals -> done(false)
            vals.all { it == BrowserSitePolicy.ALLOW } -> ensureAndroid(perms, done)
            else -> overlay { close ->
                BrowserTextPanel("$host wants: ${perms.joinToString { it.label }}",
                    "Your answer is remembered for this site; Site settings changes it.",
                    listOf(SheetRow(BrowserSitePolicy.ALLOW, "Allow"), SheetRow(BrowserSitePolicy.DENY, "Deny")),
                    onPick = { v ->
                        close()
                        perms.forEach { sitePerms.set(host, it.id, v) }
                        if (v == BrowserSitePolicy.ALLOW) ensureAndroid(perms, done) else done(false)
                    },
                    onClose = { close(); done(false) })
            }
        }
    }

    private fun ensureAndroid(perms: List<BrowserSitePerm>, done: (Boolean) -> Unit) {
        val need = perms.flatMap { it.android }.filter {
            androidx.core.content.ContextCompat.checkSelfPermission(requireContext(), it) != android.content.pm.PackageManager.PERMISSION_GRANTED
        }
        if (need.isEmpty()) done(true) else { done(false); permsNeeded(need) }
    }

    /** #1401 a permission's state on [host]: its rule, else the global default (trackers / 3rd-party cookies: the Configs switch moves it). */
    private fun permState(host: String, id: String): String {
        val perm = config.sitePerms.firstOrNull { it.id == id } ?: return BrowserSitePolicy.ASK
        return when (id) {
            "trackers" -> if (trackersBlocked(host)) BrowserSitePolicy.DENY else BrowserSitePolicy.ALLOW
            "third_party_cookies" -> BrowserSitePolicy.resolve(sitePerms.rules(), host, id,
                if (browserSettings.bool("block_third_party_cookies") == true) BrowserSitePolicy.DENY else perm.default)
            else -> sitePerms.resolve(host, perm)
        }
    }

    /** #1401 Browsing Tools > Site Perms > Perms: every permission a site can request, this site's state, the global default. */
    private fun showSiteSettings(host: String) {
        overlay { close ->
            BrowserPermsScreen("Perms", host, config.sitePerms,
                stateOf = { permState(host, it.id) }, defaultOf = { sitePerms.defaultOf(it) }, hasOwn = { sitePerms.hasOwn(host, it.id) },
                onSet = { perm, v -> sitePerms.set(host, perm.id, v); webView?.let { applySettings(it) } },
                onReset = { perm -> sitePerms.reset(host, perm.id); webView?.let { applySettings(it) } },
                onClose = { close(); webView?.reload() })
        }
    }

    /** #1401 Configs > Site settings > Sites Permissions: one default per permission for every site, then the sites with overrides. */
    private fun showSitePermsDefaults() {
        overlay { close ->
            val sites = BrowserSitePolicy.hostsWithRules(sitePerms.rules()).map { (h, r) ->
                PermSite(h, r.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" })
            }
            BrowserPermsScreen("Sites Permissions", null, config.sitePerms,
                stateOf = { sitePerms.defaultOf(it) }, defaultOf = { sitePerms.defaultOf(it) }, hasOwn = { false },
                onSet = { perm, v -> sitePerms.setDefault(perm.id, v); webView?.let { applySettings(it) } },
                onReset = {},
                onClose = close,
                sites = sites,
                onEditSite = { h -> showSiteSettings(h) },
                onResetSite = { h -> sitePerms.forget(h); close(); showSitePermsDefaults() })
        }
    }

    // ── #802 add-ons ─────────────────────────────────────────────────────

    /** Each add-on as a switch: what it is, what it may touch (in words), whether its fleet app is here. */
    private fun showAddons() {
        val cat = BrowserSettingsCatalogue(config.addons.all.map { a ->
            val may = a.permissions.joinToString("; ") { BrowserAddons.PERMISSIONS[it] ?: it }
            val needs = a.requiresPackage?.let { if (installed(it)) "" else " Needs its fleet app, not installed." }.orEmpty()
            BrowserSetting(a.id, "bool", a.defaultEnabled, label = a.label, section = "Add-ons", doc = "${a.doc} May: $may.$needs")
        })
        overlay { close ->
            BrowserSettingsScreen(cat,
                value = { id -> config.addons.enabled(id, browserSettings.stringSet("addons_enabled")) },
                onSet = { id, on ->
                    val cur = config.addons.all.filter { config.addons.enabled(it.id, browserSettings.stringSet("addons_enabled")) }.map { it.id }.toSet()
                    browserSettings.put("addons_enabled", if (on == true) cur + id else cur - id)
                },
                extra = emptyList(), onExtra = {}, onClose = close)
        }
    }

    /** #823 the assistant's conversation: kept here so it survives closing the side panel. */
    private val agentState = AgentPanelState()
    private var agentPanel: OverlayEntry? = null

    /**
     * #823 the assistant as a Compose side panel over the page (the page stays visible and usable
     * beside it), drawn by libs:ai through [BrowserAgentPanelHost]. Its consent card is the ONLY place a waiting action can be allowed.
     */
    private fun showAgentChat() {
        if (BrowserAgentHost.ask == null) return toast("The assistant is not available in this app")
        val panel = BrowserAgentPanelHost.panel ?: return toast("The assistant is not available in this app")
        if (agentPanel in overlays) return
        agentPanel = overlay(side = true) { close ->
            panel(agentState, AgentPanelActions(
                onSend = { text ->
                    agentState.lines.add(AgentLine(AgentLine.USER, text))
                    agentRun { listOfNotNull(BrowserAgentHost.ask?.invoke(text)?.let(AgentPanelText::lineFor)) }
                },
                onDecide = { pend, allow ->
                    val decide = BrowserAgentHost.decide
                    agentState.pending = null
                    agentState.lines.add(AgentPanelText.decisionLine(allow, pend.sentence))
                    if (decide != null) agentRun { listOfNotNull(AgentPanelText.lineFor(decide(pend.callId, allow))) }
                },
                onSummarize = { summarizeIntoPanel() },
                onNewChat = { BrowserAgentHost.reset?.invoke(); agentState.lines.clear(); agentState.pending = null },
                onClose = close))
        }
    }

    /** #823 the page's summary on his route (Settings ▸ Summarize with), naming the route and engine that answered. */
    private fun showPageSummary() {
        if (BrowserAgentHost.summarize == null) return toast("Summaries are not available in this app")
        showAgentChat()
        summarizeIntoPanel()
    }

    private fun summarizeIntoPanel() {
        val summarize = BrowserAgentHost.summarize ?: return
        agentState.lines.add(AgentLine(AgentLine.USER, "Summarize this page"))
        agentRun { AgentPanelText.summaryLines(summarize()) }
    }

    /** Run [work] (a model turn: network) off the main thread; its lines join the panel's transcript. */
    private fun agentRun(work: () -> List<AgentLine>) {
        agentState.busy = true
        Thread {
            val out = runCatching(work).getOrElse { listOf(AgentLine(AgentLine.NOTE, "Error: ${it.javaClass.simpleName}")) }
            view?.post { agentState.lines.addAll(out); agentState.busy = false } ?: run { agentState.busy = false }
        }.start()
    }

    /** #802 I9 / #823 the consent card: the exact action and site, in the side panel; nothing runs until he picks. */
    private fun showAgentConfirm(callId: String, sentence: String) {
        agentState.pending = AgentPending(callId, sentence)
        showAgentChat()
    }

    /**
     * #802 I9 one tool on the live page. The agent loop has already applied [AgentPolicy] and his
     * confirmation; this only executes. No branch reads the profile store or the vault.
     */
    private fun runAgentTool(name: String, a: JSONObject, wv: WebView?, url: String, done: (JSONObject) -> Unit) {
        val ok = { JSONObject().put("ok", true).put("tool", name) }
        val needPage = { done(JSONObject().put("ok", false).put("error", "no page is open")) }
        val cap = config.addons["ai"]?.config?.optInt("page_text_cap_chars", 6000) ?: 6000
        when (name) {
            "read_page", "summarize_page" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "page_text", cap)) { r -> done(r?.put("ok", true) ?: ok().put("ok", false)) }
            }
            "find_in_page" -> { wv ?: return needPage(); BrowserPageActions.find(wv, a.optString("q")) { n -> done(ok().put("matches", n)) } }
            "scrape" -> {
                wv ?: return needPage()
                val sc = config.addons["scraper"]?.config ?: JSONObject()
                val plan = ScrapeEngine.simple(a.optString("css"), a.optString("attr").ifBlank { null }, 1, null, 1)
                if (plan.columns.first().css.isBlank()) return done(ok().put("ok", false).put("error", "css is required"))
                runScrape(wv, plan, sc) { r -> done(r.put("ok", true)) }
            }
            "list_tabs" -> done(ok().put("tabs", org.json.JSONArray(prefs.all().map { JSONObject().put("url", it.url).put("title", it.title).put("pinned", it.pinned) })))
            "click" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "agent_click").replace("__CSS__", JSONObject.quote(a.optString("css")))) { r -> done(r ?: ok().put("ok", false)) }
            }
            "fill_form" -> {
                wv ?: return needPage()
                val f = a.optJSONObject("fields") ?: JSONObject()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "agent_fill").replace("__FIELDS__", f.toString())) { r -> done(r ?: ok().put("ok", false)) }
            }
            "navigate" -> { wv ?: return needPage(); wv.loadUrl(a.optString("url")); done(ok().put("url", a.optString("url"))) }
            "open_tab" -> { openEntryUrl(a.optString("url")); done(ok().put("url", a.optString("url"))) }
            "close_tab" -> { val closed = prefs.remove(a.optString("url")); BrowserBus.post(BrowserBus.TABS); done(ok().put("ok", closed)) }
            "close_all_tabs" -> { val n = prefs.closeAll(a.optBoolean("private", false)); BrowserBus.post(BrowserBus.TABS); done(ok().put("closed", n)) }
            "pin_tab" -> { prefs.setPinned(a.optString("url"), a.optBoolean("on", true)); BrowserBus.post(BrowserBus.TABS); done(ok()) }
            "bookmark_page" -> {
                if (!url.startsWith("http")) return needPage()
                BrowserBookmarks(requireContext()).add(url, wv?.title.orEmpty(), a.optString("folder"))
                done(ok().put("url", url))
            }
            else -> done(JSONObject().put("ok", false).put("error", "no tool named $name"))
        }
    }

    /** #823 Cloud Search's Search page over the browser: its engine boxes and AI chat; a result opens as a tab. */
    private fun showSearchPage() {
        val page = BrowserSearchPageHost.page ?: return toast("The Search page is not available in this app")
        val st = SuggestState().also { it.engineLabel = engine().label }
        overlay { close ->
            Column(Modifier.fillMaxSize()) {
                // #1410 the first box is the browser's address/search box with the address bar's dropdown.
                BrowserSearchBox(
                    hint = "Search ${engine().label}, or type a URL",
                    state = st,
                    onQuery = { q -> refreshSuggestions(st, q, false) },
                    onSubmit = { q -> if (q.isNotBlank()) { close(); openEntry(q) } },
                    onPick = { sug -> close(); openEntry(sug.url) },
                )
                Box(Modifier.weight(1f).fillMaxWidth()) { page({ url -> close(); openEntryUrl(url) }, close) }
            }
        }
    }

    /** #802 I8 one query, one of cloud-search's engines (the Search add-on): its results open as a new tab. */
    private fun showSearchWith() {
        val engines = config.addons.searchEngines()
        if (engines.isEmpty()) return toast("The Search add-on declares no engines")
        overlay { close ->
            var pick by remember { mutableIntStateOf(0) }
            var query by remember { mutableStateOf("") }
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Search with") },
                text = {
                    Column {
                        engines.forEachIndexed { i, e ->
                            Row(Modifier.fillMaxWidth().clickable { pick = i }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = pick == i, onClick = { pick = i })
                                Text(e.label)
                            }
                        }
                        OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                            label = { Text("Search for…") })
                    }
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        val q = query.trim()
                        if (q.isNotEmpty()) openEntryUrl(BrowserSearch.searchUrl(q, engines[pick]))
                    }) { Text("Search") }
                },
            )
        }
    }

    private fun openEntryUrl(url: String) = openUrlInTab(url)

    /** The result line the scraper sheet shows; Compose state so a finished run redraws it. */
    private val scrapeResult = androidx.compose.runtime.mutableStateOf("")

    private fun showScraper() {
        val sc = config.addons["scraper"]?.config ?: JSONObject()
        overlay { close ->
            BrowserScraperScreen(scrapeResult.value,
                onPick = { webView?.let { BrowserPageActions.run(it, BrowserPageActions.script(requireContext(), "pick")) { } }; toast("Tap an element on the page, then Use picked") },
                onUsePicked = { use -> webView?.evaluateJavascript("window.__cbPick || ''") { v -> runCatching { org.json.JSONTokener(v).nextValue() as? String }.getOrNull()?.takeIf { it.isNotBlank() }?.let(use) } },
                onRun = { css, attr, pages, next ->
                    val wv = webView ?: return@BrowserScraperScreen
                    scrapeResult.value = "Running…"
                    runScrape(wv, ScrapeEngine.simple(css, attr, pages, next, sc.optInt("max_pages", 5)), sc) { r ->
                        val rows = r.optJSONArray("rows")
                        scrapeResult.value = "${r.optInt("count")} row(s) from ${r.optInt("pages")} page(s)\n" +
                            (0 until minOf(10, rows?.length() ?: 0)).joinToString("\n") { rows!!.optJSONObject(it).toString() }
                    }
                },
                onRemote = { css ->
                    val target = webView?.url.orEmpty()
                    scrapeResult.value = "Asking the server…"
                    Thread { val r = ScrapeRemote.crawl(sc.optJSONObject("remote"), target, css); webView?.post { scrapeResult.value = r.toString(2) } }.start()
                },
                onExport = { scrapeResult.value = exportScrape(sc).toString(2) },
                onClose = close)
        }
    }

    /**
     * Run [plan] on the live page, then follow its next link page by page in a HIDDEN WebView
     * (the one on screen stays where he is), up to the plan's cap; answer the merged table.
     */
    private fun runScrape(wv: WebView, plan: ScrapeEngine.Plan, sc: JSONObject, done: (JSONObject) -> Unit) {
        val js = BrowserPageActions.script(requireContext(), "scrape").replace("__PLAN__", plan.toJson().toString())
        val pages = ArrayList<List<Map<String, String>>>()
        val settle = sc.optLong("settle_ms", 800)
        fun finish(hidden: WebView?) {
            hidden?.destroy()
            val rows = ScrapeEngine.merge(pages, sc.optInt("max_rows", 2000))
            val out = JSONObject().put("url", wv.url).put("columns", org.json.JSONArray(plan.columns.map { it.name }))
                .put("pages", pages.size).put("count", rows.size).put("rows", ScrapeEngine.json(rows))
            ScrapeEngine.last = out
            done(JSONObject(out.toString()))
        }
        fun onPage(r: JSONObject?, n: Int, hidden: WebView?) {
            pages.add(ScrapeEngine.rows(r, plan))
            val next = r?.optString("next")?.takeIf { it.startsWith("http") }
            if (next == null || n >= plan.maxPages) return finish(hidden)
            val h = hidden ?: CloudWeb.create(requireContext(), WebProfile.BROWSER) { TabProfile.bind(it, profileOfWebView[wv]) }
            var waiting = true
            h.webViewClient = object : CloudWebClient(WebProfile.BROWSER) {
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (!waiting) return
                    waiting = false
                    h.postDelayed({ BrowserPageActions.run(h, js) { onPage(it, n + 1, h) } }, settle)
                }
            }
            h.loadUrl(next)
        }
        BrowserPageActions.run(wv, js) { onPage(it, 1, null) }
    }

    /** The last result to Downloads as CSV (MediaStore, Android 10+). */
    private fun exportScrape(sc: JSONObject): JSONObject {
        val last = ScrapeEngine.last ?: return JSONObject().put("ok", false).put("error", "nothing scraped yet")
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q)
            return JSONObject().put("ok", false).put("error", "export needs Android 10+")
        val cols = (0 until last.getJSONArray("columns").length()).map { last.getJSONArray("columns").getString(it) }
        val rowsArr = last.getJSONArray("rows")
        val rows = (0 until rowsArr.length()).map { i -> rowsArr.getJSONObject(i).let { o -> cols.associateWith { o.optString(it) } } }
        val name = "scrape-${System.currentTimeMillis() / 1000}.csv"
        val dir = listOf(android.os.Environment.DIRECTORY_DOWNLOADS, BrowserBookmarkOps.normFolder(browserSettings.string("download_dir").orEmpty()))
            .filter { it.isNotEmpty() }.joinToString("/")
        val cv = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, dir)
        }
        val cr = requireContext().contentResolver
        val uri = cr.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv)
            ?: return JSONObject().put("ok", false).put("error", "MediaStore refused the file")
        cr.openOutputStream(uri)!!.use { it.write(ScrapeEngine.csv(rows, cols).toByteArray()) }
        return JSONObject().put("ok", true).put("file", "$dir/$name").put("rows", rows.size)
    }

    /** #802 Profile: what is held (masked: counts, initials, last4s), Import file, Clear. */
    private fun showProfile() {
        val m = runCatching { BrowserProfileStore(requireContext()).load().masked() }.getOrNull()
        val summary = if (m == null) "The profile store could not be opened." else
            "Identity: ${if (m.getJSONObject("identity").optBoolean("present")) m.getJSONObject("identity").optString("initials") else "none"}\n" +
            "Addresses: ${m.optInt("addresses")}\nCards (metadata only, never numbers): ${m.optInt("cards_meta")}\n\n" +
            "The Account (SuperApp ▸ Account ▸ server → runtime) brings the vault profile; Import adds a CSV, Firefox or Bitwarden export."
        overlay { close ->
            BrowserTextPanel("Profile", summary,
                listOf(SheetRow("import", "Import file…"), SheetRow("clear", "Clear the profile on this phone")),
                onPick = { r ->
                    close()
                    when (r) {
                        "import" -> importProfile.launch(arrayOf("text/*", "application/json", "application/octet-stream"))
                        "clear" -> { BrowserProfileStore(requireContext()).clear(); toast("Profile cleared") }
                    }
                }, onClose = close)
        }
    }

    private fun showClearData() {
        overlay { close ->
            BrowserClearScreen(config.clearData,
                note = "Cookies and site storage are shared by every tab: clearing them signs you out everywhere.",
                onClear = { boxes ->
                    BrowserClearData.clear(requireContext(), boxes, null) { r ->
                        toast(if (r.optBoolean("ok")) "Cleared" else r.optString("error"))
                    }
                    close()
                }, onClose = close)
        }
    }

    private fun applyViewMode(wv: WebView) {
        val s = wv.settings
        // #1421 never disguised: mobile = WebView's own default agent (not the current one, which is the desktop agent
        // right after a switch back); Google OAuth goes to a Custom Tab instead (#1420). ViewModePlan holds the values.
        val plan = ViewModePlan.of(desktopMode, config.userAgents["desktop"], config.userAgents["mobile"],
            runCatching { android.webkit.WebSettings.getDefaultUserAgent(wv.context) }.getOrNull() ?: s.userAgentString)
        CloudWeb.userAgent(wv, plan.userAgent)
        s.useWideViewPort = plan.wideViewPort
        s.loadWithOverviewMode = plan.overview
        wv.setInitialScale(plan.initialScale)
    }

    // ── #802 the declared menu, its actions, and the live-page host ───────

    /**
     * Pull down at the very top of the page to reload it. The refresh layout only claims the drag while the page cannot
     * scroll up further (the child-scroll-up callback), so the page still scrolls normally.
     */
    private fun pullRefreshAround(ctx: android.content.Context, wv: WebView): View {
        (wv.parent as? ViewGroup)?.removeView(wv)
        val accent = config.palette["accent"] ?: 0xFF7C3AED.toInt()
        val surface = config.palette["surface"] ?: 0xFF1A0033.toInt()
        return androidx.swiperefreshlayout.widget.SwipeRefreshLayout(ctx).also { sr ->
            sr.addView(wv, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            sr.setColorSchemeColors(accent)
            sr.setProgressBackgroundColorSchemeColor(surface)
            sr.setOnChildScrollUpCallback { _, _ -> wv.scrollY > 0 || wv.canScrollVertically(-1) }
            sr.setOnRefreshListener {
                wv.reload()
                sr.postDelayed({ sr.isRefreshing = false }, 15_000)   // safety net: never spin forever
            }
            pullRefresh = sr
        }
    }

    private fun palette(): KitPalette {
        val c = config.palette
        fun r(k: String, d: Int) = c[k] ?: d
        return KitPalette.fromArgb(
            r("surface", 0xFF1A0033.toInt()), r("surface_selected", 0xFF2D0A55.toInt()),
            r("text_primary", 0xFFFFFFFF.toInt()), r("text_secondary", 0x99FFFFFF.toInt()),
            r("accent", 0xFF7C3AED.toInt()), r("hairline", 0x33FFFFFF), r("tile_ink", 0xFF000000.toInt()),
        )
    }

    /** Draw [content] over the page; it is handed its own close. Back closes the top one. */
    /** [side]: a panel on the right edge, the page left visible and usable beside it (#823 the assistant). */
    private fun overlay(bottom: Boolean = false, side: Boolean = false, content: @Composable (close: () -> Unit) -> Unit): OverlayEntry? {
        if (!isAdded) return null
        val entry = OverlayEntry(bottom, side, content)
        overlays.add(entry)
        return entry
    }

    private fun closeOverlay(entry: OverlayEntry) {
        if (overlays.remove(entry) && overlays.isEmpty()) onOverlaysClosed?.invoke()
    }

    private fun closeOverlays() {
        overlays.clear()
        onOverlaysClosed?.invoke()
    }

    // ── the screen: the tab switcher, or one tab's address bar over its WebView ──────────────

    @Composable
    private fun Root() {
        Box(Modifier.fillMaxSize().background(
            androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xFF2D1B69), Color(0xFF000000), Color(0xFF2D1B69))))) {
            when (val sc = screen) {
                Screen.Grid -> GridScreen()
                is Screen.Detail -> key(sc.id) { DetailScreen(sc) }
            }
            overlays.toList().forEach { e -> key(e) { OverlayHost(e) } }
        }
    }

    @Composable
    private fun BoxScope.OverlayHost(e: OverlayEntry) {
        val close = remember(e) { { closeOverlay(e) } }
        val screenW = LocalConfiguration.current.screenWidthDp
        val place = when {
            e.side -> Modifier.align(Alignment.CenterEnd).width(minOf((screenW * 0.88f).toInt(), 480).dp).fillMaxHeight()
            e.bottom -> Modifier.align(Alignment.BottomCenter).fillMaxWidth()
            // A full-screen layer takes the touches the page below would otherwise get.
            else -> Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
        }
        Box(place) { e.content(close) }
    }

    /** #1410 the Tabs/History search bar's text. */
    private var gridQuery by androidx.compose.runtime.mutableStateOf("")

    /** #1410 the search bar on top of the Tabs and History pages: filters by title and URL as you type. */
    @Composable
    private fun SearchFilterField(value: String, onChange: (String) -> Unit) {
        OutlinedTextField(value, onChange, singleLine = true, placeholder = { Text("Search tabs and history") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp).testTag("browser:filter"))
    }

    @Composable
    private fun GridScreen() {
        val version = gridVersion
        val filter = tabFilter
        val incog = filter == BrowserTabsBar.Filter.INCOGNITO
        Column(Modifier.fillMaxSize().then(if (incog) Modifier.background(Color(0xFF121212)) else Modifier)) {
            TabsBar()
            if (PrivateProfile.showNotice(profileSupported, filter)) {
                Text(PrivateProfile.NOTICE, fontSize = 12.sp, color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
            }
            SearchFilterField(gridQuery) { gridQuery = it }
            val q = gridQuery
            val tabs = remember(version, filter, q) { BrowserListFilter.tabs(q, BrowserTabsBar.filter(prefs.all(), filter)) }
            if (tabs.isEmpty()) {
                Text(if (incog) "No incognito tabs. Tap New Incognito to open one." else "No tabs yet. Tap New Tab to open a URL or search.",
                    color = Color.White.copy(alpha = 0.7f), modifier = Modifier.padding(20.dp))
            } else {
                BrowserTabGrid(
                    rows = BrowserGridRows.build(tabs, prefs.collapsedGroups()),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    onOpen = { tab -> prefs.setActiveId(tab.key); showDetail(tab) },
                    onSwipeClose = { tab -> swipeClose(tab) },
                    onPin = { tab -> prefs.setPinnedById(tab.key, !tab.pinned); showGrid() },
                    onMenu = { tab -> showTabMenu(tab) },
                    onToggleGroup = { g ->
                        prefs.setGroupCollapsed(g, g !in prefs.collapsedGroups()); showGrid()
                    },
                    onReorder = { urls -> prefs.reorder(urls) },
                    onDropOnTab = { drag, target ->
                        prefs.dropOnTab(drag, target)?.let { c -> grouped(c); showGrid() }
                    },
                    onDropOnGroup = { drag, g ->
                        prefs.dropOnGroup(drag, g)?.let { c -> grouped(c); showGrid() }
                    },
                    onEditGroup = { g -> showGroupEditor(g) },
                    groupColors = { prefs.groupColors() },
                    thumbs = thumbs,
                )
            }
        }
    }

    @Composable
    private fun DetailScreen(sc: Screen.Detail) {
        val focus = LocalFocusManager.current
        val keyboard = LocalSoftwareKeyboardController.current
        SideEffect { clearAddressFocus = { focus.clearFocus(); keyboard?.hide() } }
        Box(Modifier.fillMaxSize()) {
        DetailBody(sc)
        if (addressOpen) {
            val tab = currentTab()
            val url = webView?.url ?: tab?.url.orEmpty()
            BrowserAddressOverlay(
                title = webView?.title ?: tab?.title.orEmpty(),
                url = url,
                hint = "Search ${engine().label}, or type a URL",
                state = suggestState,
                onQuery = { q ->
                    suggestState.visible = q.isNotBlank()
                    refreshSuggestions(suggestState, q, tab?.isPrivate == true)
                },
                onSubmit = { q ->
                    val next = BrowserSearch.resolve(q, engine())
                    dismissSuggestions()
                    // in place: the tab keeps its pin, group, order and back stack (#886)
                    if (next.isNotEmpty()) webView?.loadResolved(next)
                },
                onPick = { s -> pickSuggestion(s) },
                onCopied = { toast("Address copied") },
                onDismiss = { dismissSuggestions() },
            )
        }
        }
    }

    @Composable
    private fun DetailBody(sc: Screen.Detail) {
        Column(Modifier.fillMaxSize()) {
            if (!externalBar) Row(Modifier.fillMaxWidth().background(Color(0xCC1A0033)).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                BarButton("‹") { runAction("back") }
                BarButton("›") { runAction("forward") }
                // Address bar: a plain field. Its suggestions are the Compose panel over the page (below), so
                // there is no PopupWindow to land on top of the bar or the keyboard.
                // Brave-style: the bar shows the address; a tap opens the overlay (search box + current-address card).
                Text(
                    addressValue.text.ifEmpty { "Search ${engine().label}, or type a URL" },
                    color = if (addressValue.text.isEmpty()) Color.White.copy(alpha = 0.5f) else Color.White,
                    fontSize = 16.sp, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp).testTag("browser:address")
                        .clickable { suggestState.visible = false; addressOpen = true },
                )
            }

            // #886 the strip of tab icons for this tab's group, under the bar; on by default.
            if (browserSettings.bool("tab_strip") != false) {
                val m = strip.value
                BrowserTabStrip(m.tabs, m.activeKey, m.group, m.color,
                    onSelect = { t -> if (t.key != currentTab()?.key) { prefs.setActiveId(t.key); showDetail(prefs.byId(t.key) ?: t) } },
                    onNew = { promptForUrl(private_ = currentTab()?.isPrivate == true, joinTabKey = currentTab()?.key) },
                    onTools = { showMenuSheet() })
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { BrowserTabRowToolsIcon { showMenuSheet() } }

            // The page area: the WebView, and over it the suggestions panel, which is a CHILD of this frame
            // (so it sits under the bar and ends at the frame's bottom: above the keyboard, never over it).
            Box(Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(factory = { ctx -> pullRefreshAround(ctx, sc.webView) }, modifier = Modifier.fillMaxSize())
                BrowserSuggestOverlay(suggestState, onPick = { s -> pickSuggestion(s) }, onDismiss = { dismissSuggestions() })
                Column(Modifier.align(Alignment.BottomStart)) {
                    ac.chooser.value?.let { AcChooserSheet(it, onFill = { c -> ac.chooserFill(c) }, onDismiss = { ac.chooserDismiss() }) }
                    ac.save.value?.let { AcSaveSheet(it, onSave = { picked, pid, never, vault -> ac.confirmSave(picked, pid, never, vault) }, onDismiss = { ac.dismissSave() }) }
                    AcFillBar(ac.bar.value, onPick = { ac.pick(it) }, onDismiss = { ac.dismiss() })
                    BrowserAutofillChip(domAutofill.chip.value, domAutofill.save.value,
                        onFill = { t -> domAutofill.fill(t) }, onSnippet = { sn -> domAutofill.insert(sn) }, onDismiss = { domAutofill.dismiss() }, onSave = { yes -> domAutofill.confirmSave(yes) })
                }
                Box(Modifier.align(Alignment.TopEnd)) {
                    BrowserTranslateChip(translateState.value, translateEngineLabel, onTap = {
                        if (translateState.value is PageTranslator.State.Failed && !translator.active) translateState.value = PageTranslator.State.Idle
                        else translator.restore()
                    })
                }
            }
        }
    }

    @Composable
    private fun BarButton(label: String, onTap: () -> Unit) {
        Text(label, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.clickable { onTap() }.padding(horizontal = 8.dp))
    }

    /** What the menu's `requires` and `checked` are resolved against, right now. */
    private fun facts(): Map<String, Boolean> {
        val wv = webView
        val page = mode is Mode.DETAIL && wv != null
        return mapOf(
            "page" to page,
            "can_go_back" to (page && wv!!.canGoBack()),
            "can_go_forward" to (page && wv!!.canGoForward()),
            "reader_on" to readerOn,
            "translated" to translator.active,
            "desktop_mode" to desktopMode,
            "remote_debug" to DevTools.remoteDebugging,
            "pinned" to (currentTab()?.pinned == true),
            "site_pinned" to (page && BrowserHostHooks.fact?.invoke("site_pinned", wv!!.url.orEmpty()) == true),
            "bookmarked" to (page && bookmarks.has(wv!!.url ?: "")),
            "private_tab" to (currentTab()?.isPrivate == true),
            "profile_present" to (ac.hasLocalProfiles() || runCatching { !BrowserProfileStore(requireContext()).load().isEmpty }.getOrDefault(false)),
            "shield_js" to (page && shieldDenied(BrowserSitePolicy.hostOf(wv!!.url), "javascript")),
            "shield_images" to (page && shieldDenied(BrowserSitePolicy.hostOf(wv!!.url), "images")),
            "shield_trackers" to (page && trackersBlocked(BrowserSitePolicy.hostOf(wv!!.url))),
            "text_tools" to BrowserPageActions.textTools(requireContext()).isServingAppInstalled(),
            "can_be_default" to BrowserPageActions.canRequestDefault(requireContext()),
        )
    }

    /** Every fact: the page's, plus each add-on's `addon:<id>` (enabled, and its fleet app installed). */
    private fun allFacts(): Map<String, Boolean> = facts() + config.addons.facts(browserSettings.stringSet("addons_enabled")) { installed(it) }

    private fun installed(pkg: String) = runCatching { requireContext().packageManager.getPackageInfo(pkg, 0) }.isSuccess

    /** #1141 the shared viewer of View source and Elements. */
    private fun showSource(title: String, text: String) {
        overlay { close -> BrowserSourceViewer(title, text, onCopy = { copyText("source", it, "Copied") }, onClose = close) }
    }

    /** #1141 Dev Tools: console + REPL (0), network (1), storage (2), graphics (3) of the current page. */
    private fun showDevTools(tab: Int) {
        overlay { close -> BrowserDevToolsScreen(tab, DevToolsBridge({ webView }) { label, text -> copyText(label, text, "Copied") }, close) }
    }

    private fun showMenuSheet() {
        val facts = allFacts()
        val iconRows = config.menu.iconRows(facts).map { r -> r.map { it.sheet() } }
        val sections = config.menu.listSections(facts).map { (s, rows) -> s.label to rows.map { it.sheet() } }
        overlay { close ->
            BrowserSheet(iconRows.firstOrNull().orEmpty(), sections, onPick = { id -> close(); runAction(id) }, onDismiss = close, moreIcons = iconRows.drop(1), title = "page-tools")
        }
    }

    /** Per-tab actions. Long-press is spoken for by the drag, so: ⋮. */
    private fun showTabMenu(tab: BrowserTab) {
        val rows = listOfNotNull(
            SheetRow("pin", if (tab.pinned) "Unpin tab" else "Pin tab"),
            SheetRow("group", if (tab.group.isBlank()) "Add to group…" else "Move to group…"),
            if (tab.group.isNotBlank()) SheetRow("ungroup", "Remove from “${tab.group}”") else null,
            if (tab.pinned) null else SheetRow("close", "Close tab"),
        )
        overlay { close ->
            BrowserSheet(emptyList(), listOf(tab.displayTitle to rows), onPick = { id ->
                close()
                when (id) {
                    "pin" -> { prefs.setPinnedById(tab.key, !tab.pinned); showGrid() }
                    "group" -> promptForGroup(tab)
                    "ungroup" -> { prefs.setGroupById(tab.key, ""); showGrid() }
                    "close" -> closeTab(tab)
                }
            }, onDismiss = close)
        }
    }

    /** The Configs overlay showing now, so a top-tab switch replaces it instead of stacking a second page. */
    private var configsEntry: OverlayEntry? = null

    /**
     * #900 Configs is a section of top tabs (libs:bottomnav PageTabs, drawn by the host shell): [pageId] is the
     * tab picked (the first one when null). An app that declares no pages gets the one long settings list.
     */
    private fun showSettings(pageId: String? = null) {
        val pages = config.configs
        // #1402 a former page id (an old anchor) opens the page that holds its content now
        val page = pages.pageFor(pageId) ?: pages.first()
        configsEntry?.let { overlays.remove(it) }
        if (page == null) {
            configsEntry = overlay { close ->
                BrowserSettingsScreen(
                    config.settings, value = { browserSettings.value(it) },
                    onSet = { k, v -> browserSettings.put(k, v) },
                    extra = emptyList(), onExtra = {}, onClose = close, sections = config.settingsSections,
                )
            }
            return
        }
        configsEntry = overlay { close ->
            BrowserConfigsPageScreen(
                pageId = page, title = pages.title(page), catalogue = config.settings,
                value = { browserSettings.value(it) }, onSet = { k, v -> browserSettings.put(k, v) },
                rows = pages.resolve(page, allFacts(), config.menu.whys),
                onRow = { id -> close(); runAction(id) },
                native = { ConfigsNative(pages.nativeFor(page)) },
                groups = pages.groupsFor(page),
            )
        }
    }

    /** The native blocks of a Configs page, by the ids build.json::ui.browser.configs.native names. */
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    @Composable
    private fun ColumnScope.ConfigsNative(ids: List<String>) {
        // Autocomplete holds several sub-sections: one at a time, picked by chips. A page with one block draws it.
        var sub by remember(ids) { mutableStateOf(ids.firstOrNull()) }
        if (ids.size > 1) androidx.compose.foundation.layout.FlowRow(Modifier.padding(vertical = 6.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
            ids.forEach { id ->
                androidx.compose.material3.FilterChip(selected = sub == id, onClick = { sub = id }, label = { Text(acSubTitle(id)) },
                    modifier = Modifier.testTag("browser:ac:sub:$id"))
            }
        }
        (if (ids.size > 1) listOfNotNull(sub) else ids).forEach { id ->
            when (id) {
                "about" -> BrowserAboutBlock(aboutLines())
                "site_list" -> SiteListBlock()
                "gpu" -> BrowserHostHooks.gpuPage?.invoke(this)
                "perms_defaults" -> PermsDefaultsBlock()
                else -> AcBlock(id)
            }
        }
    }

    /** #1402 Configs > Permissions: one default per permission for every site, then the sites with overrides (the Perms component, inline). */
    @Composable
    private fun ColumnScope.PermsDefaultsBlock() {
        val tick = remember { mutableIntStateOf(0) }
        val sites = tick.intValue.let { BrowserSitePolicy.hostsWithRules(sitePerms.rules()) }.map { (h, r) ->
            PermSite(h, r.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" })
        }
        BrowserPermsList(null, config.sitePerms,
            stateOf = { sitePerms.defaultOf(it) }, defaultOf = { sitePerms.defaultOf(it) }, hasOwn = { false },
            onSet = { perm, v -> sitePerms.setDefault(perm.id, v); webView?.let { applySettings(it) } },
            onReset = {}, sites = sites,
            onEditSite = { h -> showSiteSettings(h) },
            onResetSite = { h -> sitePerms.forget(h); tick.intValue++ })
    }

    /** #1402 Configs > Sites: the sites this browser keeps something for (rules, zoom, the open page); a tap manages one. */
    @Composable
    private fun SiteListBlock() {
        val p = LocalKitPalette.current
        val tick = remember { mutableIntStateOf(0) }
        val here = BrowserSitePolicy.hostOf(webView?.url)
        val hosts = tick.intValue.let { (listOf(here) + BrowserSitePolicy.hostsWithRules(sitePerms.rules()).keys).filter { it.isNotEmpty() }.distinct() }
        if (hosts.isEmpty()) Text("No site yet: open a page, or give a site a permission of its own.", color = p.textSecondary, modifier = Modifier.padding(8.dp))
        hosts.forEach { h ->
            KitSettingsRow(h, if (h == here) "the open page · Data, Perms, more" else "Data, Perms, more",
                Modifier.testTag("browser:sites:$h"), onClick = { showSite(h) })
        }
    }

    /** #1402 one site: its Data (cookies, storage, clear), its Perms and its other per-site settings (zoom, autocomplete memory). */
    private fun showSite(host: String) {
        val url = "https://$host/"
        overlay { close ->
            val p = LocalKitPalette.current
            val tick = remember { mutableIntStateOf(0) }
            Column(Modifier.fillMaxSize().background(p.surface).verticalScroll(rememberScrollState()).padding(16.dp).testTag("browser:site:$host")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.TextButton(close) { Text("← Back") }
                    Text(host, color = p.textPrimary, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                }
                Text("Data", color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp).testTag("browser:site:data"))
                KitSettingsRow("Clear cookies of this site", "", Modifier.testTag("browser:site:clear_cookies"), onClick = {
                    BrowserStorage.clearSiteCookies(url) { n -> toast("Cleared $n cookie(s) of $host") }
                })
                KitSettingsRow("Cookies & storage: clear…", "", Modifier.testTag("browser:site:clear_data"), onClick = {
                    overlay { c2 -> BrowserClearSiteScreen(host, onClear = { boxes -> c2(); BrowserStorage.clearSiteData(url, boxes) { toast("Cleared ${boxes.size} kind(s) of data of $host") } }, onClose = c2) }
                })
                Text("Perms", color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp).testTag("browser:site:perms"))
                KitSettingsRow("Perms", "every permission this site can request", Modifier.testTag("browser:site:perms_open"), onClick = { showSiteSettings(host) })
                Text("More", color = p.textSecondary, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 10.dp).testTag("browser:site:more"))
                val zoom = tick.intValue.let { sitePerms.zoom(host) }
                KitSettingsRow("Zoom", zoom?.let { "$it% (tap to reset)" } ?: "follows the global text size", Modifier.testTag("browser:site:zoom"),
                    onClick = if (zoom != null) ({ sitePerms.setZoom(host, null); tick.intValue++ }) else null)
                KitSettingsRow("Autocomplete for this site…", "", Modifier.testTag("browser:site:autocomplete"), onClick = { showSiteAutocomplete(host) })
                KitSettingsRow("Reset every setting of this site", "", Modifier.testTag("browser:site:forget"), onClick = { sitePerms.forget(host); tick.intValue++; toast("$host follows the defaults again") })
            }
        }
    }

    private fun acSubTitle(id: String) = when (id) {
        "profiles" -> "Profiles"; "sites" -> "Sites"; "passwords" -> "Passwords"; "cards" -> "Cards"; "vault" -> "Cloud Vault"; else -> id
    }

    /** One native Autocomplete block of a Configs page, over the encrypted store. */
    @Composable
    private fun AcBlock(id: String) {
        val store = if (ac.ready.value) ac.store else null
        if (store == null) {
            ac.warm()
            Text("Opening the encrypted store...", color = LocalKitPalette.current.textSecondary, modifier = Modifier.padding(8.dp))
            return
        }
        val tick = acTick.intValue
        fun changed() { ac.refresh(); acTick.intValue += 1 }
        fun unlock(title: String, ok: () -> Unit) {
            AcUnlock.require(requireActivity(), browserSettings.bool("secrets_require_unlock") != false, title, onOk = ok, onDenied = { toast(it) })
        }
        when (id) {
            "profiles" -> {
                val list = remember(tick) { store.profiles() }
                val accountHere = remember { runCatching { com.diegonmarcos.superapp.autofill.AutofillSotClient.installed(requireContext()) }.getOrDefault(false) }
                AcProfilesSection(list,
                    onSave = { store.upsertProfile(it); changed() },
                    onDelete = { store.deleteProfile(it); changed() },
                    onDefault = { store.setDefaultProfile(it); changed() },
                    onImportAccount = if (!accountHere) null else ({
                        Thread {
                            val l = runCatching { com.diegonmarcos.superapp.autofill.AutofillSotClient.profiles(requireContext()) }.getOrDefault(emptyList())
                            ui.post { if (isAdded) { val n = store.importAccount(l); toast(if (n == 0) "Nothing new in Cloud Account" else "Imported $n profile(s)"); changed() } }
                        }.start()
                    }),
                    note = if (ac.persistent) null else "The encrypted store could not be opened: changes last until the app closes.")
            }
            "sites" -> AcSitesSection(remember(tick) { store.memory() }, null,
                onForget = { store.forgetMemory(it); changed() }, onForgetSite = { store.forgetSite(it); changed() }, onForgetAll = { store.clearMemory(); changed() })
            "passwords" -> {
                val (list, bad) = remember(tick) { store.logins() }
                AcPasswordsSection(list, bad, store.secretsAvailable,
                    onSave = { store.saveLogin(it); changed() }, onDelete = { store.deleteLogin(it); changed() },
                    reveal = { l, show -> unlock("Show saved password") { show(l.password) } })
            }
            "cards" -> {
                val (list, bad) = remember(tick) { store.cards() }
                AcCardsSection(list, bad, store.secretsAvailable,
                    onSave = { store.saveCard(it); changed() }, onDelete = { store.deleteCard(it); changed() },
                    reveal = { c, show -> unlock("Show saved card") { show(c.number) } })
            }
            "vault" -> {
                val pkg = config.addons["vault"]?.requiresPackage
                val st = remember(tick) { runCatching { VaultAutofill.status(requireContext(), pkg) }.getOrNull() ?: JSONObject() }
                val isService = !pkg.isNullOrBlank() && VaultAutofill.isVault(st.optString("autofill_service"), pkg)
                val state = AcVaultState.of(browserSettings.bool("autofill_use_vault") == true, st.optBoolean("installed"), st.optBoolean("framework_enabled"), isService)
                val all = remember(tick) { store.logins().first.map { it.source } + store.cards().first.map { it.source } }
                AcVaultSection(state, all.count { it == AcSource.LOCAL }, all.count { it == AcSource.VAULT },
                    onUseVault = { on -> browserSettings.set("autofill_use_vault", on.toString()); changed() },
                    onMakeService = { VaultAutofill.setAsService(requireContext(), pkg); changed() })
            }
        }
    }

    private fun aboutLines(): List<Pair<String, String>> {
        val ctx = requireContext()
        val pi = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull()
        val wv = runCatching { WebView.getCurrentWebViewPackage() }.getOrNull()
        return listOf(
            "App" to ctx.packageName,
            "Version" to (pi?.versionName ?: "unknown"),
            "WebView" to (wv?.let { "${it.packageName} ${it.versionName}" } ?: "unknown"),
            "Android" to "${android.os.Build.VERSION.RELEASE} (API ${android.os.Build.VERSION.SDK_INT})",
        )
    }

    private fun showFindBar() {
        overlay(bottom = true) { close ->
            BrowserFindBar(findCount.value,
                onQuery = { q -> webView?.let { BrowserPageActions.find(it, q) { n -> findCount.value = n } } },
                onNext = { forward -> webView?.findNext(forward) },
                onClose = { webView?.clearMatches(); close() })
        }
    }

    private fun showTextPanel(title: String, text: String) =
        overlay { close -> BrowserTextPanel(title, text, onClose = close) }

    private fun showGroups() {
        val groups = prefs.all().filter { it.group.isNotBlank() }.groupBy { it.group }
        val rows = groups.map { (g, tabs) -> SheetRow(g, g, why = "${tabs.size} tab(s)") }
        overlay { close ->
            BrowserTextPanel("Tab groups", if (rows.isEmpty()) "No groups yet: ⋮ on a tab card → Add to group." else "",
                rows, onPick = { g ->
                    close()
                    groups.keys.forEach { prefs.setGroupCollapsed(it, it != g) }
                    showGrid()
                }, onClose = close)
        }
    }

    private fun showSiteZoom(host: String) {
        overlay(bottom = true) { close ->
            var cur by remember { mutableStateOf(sitePerms.zoom(host)) }
            BrowserSiteZoomPanel(host, cur, browserSettings.int("text_zoom") ?: 100,
                onSet = { p -> sitePerms.setZoom(host, p); cur = p; webView?.let { applySettings(it) } }, onClose = close)
        }
    }

    /** #900 page dropdown > This site > Autocomplete for this site: what is remembered for this site only. */
    private fun showSiteAutocomplete(host: String) {
        val origin = AcSiteMemory.originKey(host)
        ac.warm()
        overlay { close ->
            val tick = remember { mutableIntStateOf(0) }
            val store = if (ac.ready.value) ac.store else null
            val entries = tick.intValue.let { store?.memory().orEmpty() }
            AcSitePage(entries, origin, onForget = { store?.forgetMemory(it); tick.intValue += 1 },
                onForgetSite = { store?.forgetSite(it); tick.intValue += 1 }, onClose = close)
        }
    }

    /** #900 Configs > Site settings: every host that has a rule of its own; tap one to change its permissions. */
    private fun showSites() {
        val hosts = BrowserSitePolicy.hostsWithRules(sitePerms.rules())
        val rows = hosts.map { (h, rules) -> ListRow(h, h, rules.entries.sortedBy { it.key }.joinToString(", ") { "${it.key} ${it.value}" }) }
        overlay { close ->
            BrowserListScreen("Sites with custom settings", rows, "No site has settings of its own yet: page menu > This site.",
                onOpen = { r -> close(); showSiteSettings(r.id) },
                onRemove = { r -> sitePerms.forget(r.id); close(); showSites() },
                onClose = close)
        }
    }

    private fun runAction(id: String) {
        runAction(id, emptyMap()) { r -> r.optString("toast").takeIf { it.isNotBlank() }?.let { toast(it) } }
    }

    /**
     * THE ONE DISPATCH of every declared menu action — the sheet, the bar buttons and
     * /api/browser/menu/act all land here. test-browser-menu-wired.sh holds that every
     * declared action id has its branch.
     */
    private fun runAction(id: String, args: Map<String, String>, done: (JSONObject) -> Unit) {
        val wv = webView
        // #886 the tab is held by id; its url is whatever it last committed.
        val tabNow = currentTab() ?: prefs.activeTab()
        val tabUrl = tabNow?.url.orEmpty()
        val url = wv?.url?.takeIf { it.startsWith("http") } ?: tabUrl
        val ok = { JSONObject().put("ok", true).put("id", id) }
        val needPage = { done(JSONObject().put("ok", false).put("error", "$id: no page is open")) }
        when (id) {
            "_facts" -> done(ok().put("facts", JSONObject(allFacts())))
            "back" -> if (wv?.canGoBack() == true) { wv.goBack(); done(ok()) } else done(ok().put("ok", false).put("why", "no earlier page"))
            "forward" -> if (wv?.canGoForward() == true) { wv.goForward(); done(ok()) } else done(ok().put("ok", false).put("why", "no later page"))
            "reload" -> { wv?.reload() ?: return needPage(); done(ok()) }
            "new_tab" -> { promptForUrl(); done(ok()) }
            "new_private_tab" -> { tabFilter = BrowserTabsBar.Filter.INCOGNITO; promptForUrl(private_ = true); done(ok().put("toast", "Private tabs keep no history; cookies are shared with normal tabs")) }
            "site_settings" -> { showSiteSettings(BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }); done(ok()) }
            "clear_data" -> { showClearData(); done(ok()) }
            "profile" -> { showProfile(); done(ok()) }
            "addons_manage" -> { showAddons(); done(ok()) }
            "scraper" -> { showScraper(); done(ok()) }
            "search_with" -> { showSearchWith(); done(ok()) }
            "search_chat" -> { showSearchPage(); done(ok()) }
            "ai_chat" -> { showAgentChat(); done(ok()) }
            "ai_summarize" -> { showPageSummary(); done(ok()) }
            "vault_fill", "vault_request_fill" -> { wv ?: return needPage(); VaultAutofill.requestFill(wv) { r -> done(r.put("ok", true)) } }
            "vault_open" -> done(VaultAutofill.open(requireContext(), config.addons["vault"]?.requiresPackage)?.let { ok().put("ok", false).put("error", it) } ?: ok())
            "vault_set_service" -> done(VaultAutofill.setAsService(requireContext(), config.addons["vault"]?.requiresPackage)?.let { ok().put("why", it) } ?: ok())
            "agent_confirm" -> { showAgentConfirm(args["call"].orEmpty(), args["sentence"].orEmpty()); done(ok().put("shown", true)) }
            "agent_tool" -> runAgentTool(args["name"].orEmpty(), AgentLoop.args(args["args"]), wv, url, done)
            "scrape_run" -> {
                wv ?: return needPage()
                val sc = config.addons["scraper"]?.config ?: JSONObject()
                val plan = ScrapeEngine.simple(args["css"].orEmpty(), args["attr"], args["pages"]?.toIntOrNull() ?: 1,
                    args["next"], sc.optInt("max_pages", 5))
                if (plan.columns.first().css.isBlank()) return done(ok().put("ok", false).put("error", "css= is required"))
                runScrape(wv, plan, sc) { r -> done(r.put("ok", true)) }
            }
            "fill_profile", "fill_dry" -> {
                wv ?: return needPage()
                val dry = id == "fill_dry"
                // #900 the user's own profiles: the chooser asks which profile and which value per kind.
                if (!dry && ac.hasLocalProfiles()) return ac.fillPage { err -> done(if (err == null) ok().put("chooser", true) else ok().put("ok", false).put("error", err)) }
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "autofill_scan")) { r ->
                    val plan = BrowserAutofillMatch.plan(r?.optJSONArray("fields") ?: org.json.JSONArray())
                    val fields = org.json.JSONArray(plan.map { (i, k) -> JSONObject().put("i", i).put("fills", k) })
                    val p = BrowserProfileStore(requireContext()).load()
                    when {
                        dry -> done(ok().put("fields", fields).put("profile_present", !p.isEmpty))   // kinds only, never values
                        p.isEmpty -> done(ok().put("ok", false).put("error", "the profile is empty: import one, or apply the Account"))
                        plan.isEmpty() -> done(ok().put("ok", false).put("error", "no fillable field on this page"))
                        p.addresses.size > 1 && args["address"] == null -> overlay { close ->
                            BrowserTextPanel("Fill with which address?", "",
                                p.addresses.mapIndexed { n, a -> SheetRow(n.toString(), a.label.ifBlank { a.city }, why = a.city) },
                                onPick = { n -> close(); runAction(id, args + ("address" to n), done) },
                                onClose = { close(); done(ok().put("ok", false).put("error", "cancelled")) })
                        }
                        else -> {
                            val ai = args["address"]?.toIntOrNull() ?: 0
                            val fill = JSONObject()
                            plan.forEach { (i, k) -> BrowserAutofillMatch.value(k, p, ai).takeIf { it.isNotBlank() }?.let { fill.put(i.toString(), it) } }
                            val js = BrowserPageActions.script(requireContext(), "autofill_fill").replace("__FILL__", fill.toString())
                            BrowserPageActions.run(wv, js) { f -> done(ok().put("filled", f?.optInt("filled") ?: 0)) }
                        }
                    }
                }
            }
            "shield_js", "shield_images" -> {
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                val perm = if (id == "shield_js") "javascript" else "images"
                val next = if (shieldDenied(host, perm)) BrowserSitePolicy.ALLOW else BrowserSitePolicy.DENY
                sitePerms.set(host, perm, next)
                wv?.let { applySettings(it); it.reload() }
                done(ok().put(perm, next))
            }
            "close_tab" -> {
                val tab = tabNow ?: return needPage()
                closeTab(tab); done(ok().put("closed", prefs.byId(tab.key) == null))
            }
            "pin_tab" -> { (tabNow ?: return needPage()).let { prefs.setPinnedById(it.key, !it.pinned) }; done(ok().put("pinned", facts().getValue("pinned"))) }
            "tab_groups" -> { showGroups(); done(ok()) }
            "find" -> {
                wv ?: return needPage()
                if (overlays.isEmpty()) showFindBar()
                BrowserPageActions.find(wv, args["q"].orEmpty()) { n -> findCount.value = n; done(ok().put("matches", n)) }
            }
            "share" -> {
                startActivity(android.content.Intent.createChooser(
                    android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(android.content.Intent.EXTRA_TEXT, url)
                    }, "Share URL"))
                done(ok())
            }
            "copy_url" -> {
                val clip = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
                clip?.setPrimaryClip(android.content.ClipData.newPlainText("url", url))
                done(ok().put("toast", "URL copied"))
            }
            "open_external" -> { openInExternalBrowser(url); done(ok()) }
            "print" -> { BrowserPageActions.print(wv ?: return needPage()); done(ok()) }
            // #1741 Convert to PDF: the print framework with an A4 sheet; "Save as PDF" is picked as the destination.
            "convert_pdf" -> {
                if (BrowserPageActions.convertToPdf(wv ?: return needPage())) done(ok().put("toast", "Choose \"Save as PDF\" as the printer"))
                else done(ok().put("ok", false).put("error", "no print service on this device"))
            }
            "desktop" -> {
                browserSettings.put("desktop_mode", !desktopMode)
                // #1141 really switch: UA + viewport onto the live WebView, then load the page again under them.
                wv?.let { applyViewMode(it); it.reload() }
                done(ok().put("desktop_mode", desktopMode))
            }
            "hard_refresh", "light_refresh" -> {
                wv ?: return needPage()
                val mode = if (id == "hard_refresh") RefreshMode.HARD else RefreshMode.LIGHT
                BrowserRefresh.run(wv, mode) { done(ok().put("cleared", JSONArray(RefreshPlan.of(mode).scopes()))) }
            }
            "dev_console" -> { showDevTools(0); done(ok()) }
            "dev_network" -> { showDevTools(1); done(ok()) }
            "dev_storage" -> { showDevTools(2); done(ok()) }
            "dev_graphics" -> { showDevTools(3); done(ok()) }
            "dev_remote_debug" -> {
                DevTools.remoteDebugging = !DevTools.remoteDebugging
                WebView.setWebContentsDebuggingEnabled(DevTools.remoteDebugging)
                done(ok().put("remote_debug", DevTools.remoteDebugging).put("toast", if (DevTools.remoteDebugging) "Remote debugging on: chrome://inspect" else "Remote debugging off"))
            }
            "dev_elements" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, "(function(){return JSON.stringify({html:document.documentElement.outerHTML.slice(0,${DOM_MAX})});})()") { r ->
                    showSource("Elements of ${BrowserSitePolicy.hostOf(url)} (live DOM)", r?.optString("html").orEmpty().ifEmpty { "(nothing to show)" })
                    done(ok())
                }
            }
            "reader" -> {
                wv ?: return needPage()
                if (readerOn) { readerOn = false; wv.loadUrl(url); done(ok().put("reader", false)) }
                else BrowserPageActions.reader(wv, config.readerCss) { r ->
                    readerOn = r != null
                    readerPending = r != null
                    done(if (r == null) ok().put("ok", false).put("error", "nothing to extract")
                        else ok().put("reader", true).put("title", r.optString("title")).put("length", r.optInt("length")))
                }
            }
            "reader_extract" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "reader")) { r ->
                    done(if (r == null) ok().put("ok", false) else ok().put("title", r.optString("title")).put("length", r.optInt("length")))
                }
            }
            "page_text" -> {
                wv ?: return needPage()
                val n = (args["n"]?.toIntOrNull() ?: 2000).coerceIn(1, 200_000)
                BrowserPageActions.run(wv, BrowserPageActions.script(requireContext(), "page_text", n)) { r ->
                    done(r?.put("ok", true) ?: ok().put("ok", false))
                }
            }
            "zoom" -> {
                // #900 zoom is for THIS site (the global text size is Configs > Appearance).
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                val v = args["value"]
                when {
                    v == null -> { showSiteZoom(host); done(ok().put("host", host)) }
                    v == "reset" -> { sitePerms.setZoom(host, null); wv?.let { applySettings(it) }; done(ok().put("host", host).put("zoom", JSONObject.NULL)) }
                    v.toIntOrNull() == null -> done(ok().put("ok", false).put("error", "value= is a percent ${BrowserSitePolicy.ZOOM_MIN}..${BrowserSitePolicy.ZOOM_MAX}, or reset"))
                    else -> { sitePerms.setZoom(host, v.toInt()); wv?.let { applySettings(it) }; done(ok().put("host", host).put("zoom", sitePerms.zoom(host))) }
                }
            }
            "view_source" -> {
                wv ?: return needPage()
                BrowserPageActions.run(wv, "(function(){return JSON.stringify({html:document.documentElement.outerHTML.slice(0,${DOM_MAX})});})()") { r ->
                    val src = r?.optString("html").orEmpty().ifEmpty { "(nothing to show)" }
                    showSource("Source of ${BrowserSitePolicy.hostOf(url)} (the page as it is now)", src)
                    done(ok().put("length", src.length))
                }
            }
            "shield_trackers" -> {
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                val next = if (trackersBlocked(host)) BrowserSitePolicy.ALLOW else BrowserSitePolicy.DENY
                sitePerms.set(host, "trackers", next)
                wv?.reload()
                done(ok().put("trackers", next))
            }
            "site_autocomplete" -> {
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                showSiteAutocomplete(host); done(ok().put("host", host))
            }
            "sites_manage" -> { showSites(); done(ok()) }
            "site_perms_manage" -> { showSitePermsDefaults(); done(ok()) }
            "translate" -> { wv ?: return needPage(); done(toggleTranslate()) }
            "summarize_topics" -> { wv ?: return needPage(); showTopics(wv); done(ok().put("started", true)) }
            "save_page" -> { wv ?: return needPage(); startSave(wv, false, done) }
            "save_site" -> { wv ?: return needPage(); startSave(wv, true, done) }
            // #1518 the offline copy in the cache the app owns (pin = serve cache-first; download = crawl + record)
            "pin_site", "download_site" -> {
                wv ?: return needPage()
                done(ok().put("toast", BrowserHostHooks.action?.invoke(id, url) ?: "Not available in this app"))
            }
            "clear_site_cookies" -> {
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                BrowserStorage.clearSiteCookies(url) { n ->
                    wv?.reload()
                    done(ok().put("cleared", n).put("host", host).put("toast", "Cleared $n cookie(s) of $host"))
                }
            }
            "clear_site_data" -> {
                val host = BrowserSitePolicy.hostOf(url).ifEmpty { return needPage() }
                overlay { close ->
                    BrowserClearSiteScreen(host, onClear = { boxes ->
                        close()
                        BrowserStorage.clearSiteData(url, boxes) { r -> wv?.reload(); toast("Cleared ${boxes.size} kind(s) of data of $host") }
                    }, onClose = close)
                }
                done(ok().put("host", host))
            }
            "offline_manage" -> { showOffline(); done(ok()) }
            "storage_manage" -> { showStorage(); done(ok()) }
            "history" -> { showHistory(); done(ok()) }
            "bookmarks" -> { showBookmarks(); done(ok()) }
            "downloads" -> { showDownloads(); done(ok()) }
            "bookmark" -> {
                wv ?: return needPage()
                val on = !bookmarks.has(url)
                if (on) bookmarks.add(url, wv.title.orEmpty(), args["folder"].orEmpty()) else bookmarks.remove(url)
                done(ok().put("bookmarked", on).put("toast", if (on) "Bookmarked" else "Bookmark removed"))
            }
            "download_page" -> {
                wv ?: return needPage()
                val d = download(url, wv.settings.userAgentString, null, null)
                done(ok().put("id", d.id).put("file", d.file).put("toast", "Downloading ${d.file}"))
            }
            "add_to_home" -> {
                val asked = addToHome(url, wv?.title.orEmpty(), wv?.favicon)
                done(ok().put("requested", asked).put("toast", if (asked) "" else "This launcher cannot pin shortcuts"))
            }
            "settings" -> { showSettings(); done(ok()) }
            "default_browser" -> {
                val i = BrowserPageActions.defaultBrowserIntent(requireContext())
                if (i != null) roleRequest.launch(i)
                done(ok().put("requested", i != null))
            }
            else -> done(JSONObject().put("ok", false).put("error", "$id: not a menu action"))
        }
    }

    /** #802 the live page, for /api/browser/menu/act and the page routes. */
    private val pageHost = object : BrowserPageHost {
        override fun facts(): Map<String, Boolean> = this@BrowserHostFragment.facts()
        override fun act(id: String, args: Map<String, String>, done: (JSONObject) -> Unit) = runAction(id, args, done)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            val wv = webView
            when {
                addressOpen || suggestState.visible -> dismissSuggestions()
                overlays.isNotEmpty() -> {
                    overlays.removeAt(overlays.lastIndex)
                    if (overlays.isEmpty()) onOverlaysClosed?.invoke()
                }
                mode is Mode.DETAIL && wv?.canGoBack() == true -> wv.goBack()
                mode !is Mode.GRID -> showGrid()
                else -> { isEnabled = false; requireActivity().onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, backCallback)
    }

    private fun openInExternalBrowser(url: String) {
        startActivity(
            android.content.Intent.createChooser(
                android.content.Intent(
                    android.content.Intent.ACTION_VIEW,
                    android.net.Uri.parse(url)),
                "Open with"))
    }

    /** Mode WebAuthn was enabled with on the current WebView (null = this WebView cannot do it). */
    private var passkeyMode: PasskeySupport.Mode? = null
    private var passkeyOfferedFor: String? = null

    /** On a Google page, read what it says; hook navigator.credentials so a failed passkey call is reported. */
    private fun probePasskey(view: WebView?, url: String) {
        view ?: return
        val host = BrowserSitePolicy.hostOf(url)
        if (url.startsWith("https://")) view.evaluateJavascript(PasskeySupport.HOOK_JS, null)
        if (host.endsWith("google.com") || host == "accounts.youtube.com") {
            view.evaluateJavascript("(document.body&&document.body.innerText||'').slice(0,6000)") { raw ->
                val text = runCatching { org.json.JSONTokener(raw).nextValue() as? String }.getOrNull()
                if (PasskeySupport.shouldOfferSecureBrowser(url, text, false, passkeyMode)) offerSecureBrowser(view, url, false)
            }
        }
    }

    /** "Continue sign-in in secure browser": the same page in the system browser (Custom Tab), where passkeys work. */
    private fun offerSecureBrowser(view: WebView, url: String?, @Suppress("UNUSED_PARAMETER") jsFailure: Boolean) {
        val target = url ?: view.url ?: return
        if (!isAdded || passkeyOfferedFor == target) return
        val launch = PasskeySupport.secureBrowserLaunch(target, browserPackage()) ?: return
        passkeyOfferedFor = target
        overlay { close ->
            AlertDialog(
                onDismissRequest = close,
                title = { Text("Passkey needed") },
                text = {
                    Text("This sign-in asks for a passkey, which this in-app page cannot provide. Continue in your secure browser. " +
                        "Cloud Browser never sees your passkey or password. The session stays in that browser.")
                },
                dismissButton = { TextButton(onClick = close) { Text("Cancel") } },
                confirmButton = {
                    TextButton(onClick = {
                        close()
                        runCatching { startActivity(PasskeySupport.secureBrowserIntent(launch)) }
                            .onFailure { toast("No browser found") }
                    }) { Text("Continue sign-in in secure browser") }
                },
            )
        }
    }

    /** #1422 the declared seed plus the derived catalog asset (absent in an app that ships none). */
    private val favSeed: FavSeed by lazy {
        val cat = runCatching { requireContext().assets.open(FavSeed.CATALOG_ASSET).bufferedReader().use { org.json.JSONObject(it.readText()) } }.getOrNull()
        config.favourites.withCatalog(cat)
    }

    /** #1420 set while a Google OAuth flow runs in the Custom Tab; onResume reloads the tab. */
    private var oauthHandedOff = false

    /** #1420 Google OAuth never runs in the WebView (Google's embedded-webview policy): a Custom Tab takes it. */
    private fun handOffToCustomTab(url: String) {
        // no other browser: an intent without a package would come back to this app and the WebView again
        val pkg = browserPackage() ?: run { toast("Google sign-in needs a browser app (Chrome, Firefox, ...)"); return }
        val launch = PasskeySupport.secureBrowserLaunch(url, pkg) ?: return
        oauthHandedOff = true
        runCatching { startActivity(PasskeySupport.secureBrowserIntent(launch)) }
            .onFailure { oauthHandedOff = false; toast("No browser found for Google sign-in") }
    }

    /** The system browser that is not us. */
    private fun browserPackage(): String? {
        val pm = requireContext().packageManager
        val probe = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://example.com"))
            .addCategory(android.content.Intent.CATEGORY_BROWSABLE)
        val all = pm.queryIntentActivities(probe, 0).map { it.activityInfo.packageName }.distinct()
        val def = pm.resolveActivity(probe, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        return PasskeySupport.pickBrowserPackage(all, requireContext().packageName, def)
    }

    private fun toast(msg: String) {
        android.widget.Toast.makeText(requireContext(), msg, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun teardownWebView() {
        saveTabState()
        // Leaving a tab (to the switcher or to another tab): its last look, while the page is still alive.
        webView?.let { wv -> webTabKey?.let { if (isAdded) runCatching { captureTab(wv, it) } } }
        webTabKey = null
        webView?.let {
            runCatching { webBluetooth.detach(it) }
            runCatching { webHardware.detach(it) }
            runCatching { it.stopLoading() }
            runCatching { it.destroy() }
        }
        webView = null
        pullRefresh = null
    }

    override fun onDestroyView() {
        teardownWebView()
        super.onDestroyView()
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    companion object {
        /** #1274 the catalogue feature (perms-catalogue.json) whose entries Cloud Browser needs. */
        const val PERMS_FEATURE = "browser"
        private const val TAG = "Browser"
        /** The declared menu section drawn as the icon row, and the one the Settings page lists. */
        private const val ICON_SECTION = "icons"
        private const val DOM_MAX = 500_000
        private const val ARG_OPEN_URL = "open_url"
        private const val ARG_CONFIG_B64 = "config_b64"
        private const val REMOTE_SUGGEST_DELAY_MS = 250L

        /**
         * @param openUrl  go straight to this page instead of the grid.
         * @param configB64 the consuming app's base64 ui.browser block.
         *        Omitted means [BrowserConfig.DEFAULT] — no pinned tabs.
         */
        fun newInstance(openUrl: String? = null, configB64: String? = null) =
            BrowserHostFragment().apply {
                arguments = Bundle().apply {
                    if (!openUrl.isNullOrBlank()) putString(ARG_OPEN_URL, openUrl)
                    if (!configB64.isNullOrBlank()) putString(ARG_CONFIG_B64, configB64)
                }
            }
    }
}
