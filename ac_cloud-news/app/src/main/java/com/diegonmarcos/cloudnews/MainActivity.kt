package com.diegonmarcos.cloudnews

import com.diegonmarcos.cloudlib.web.CloudChromeClient
import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile
import android.os.Bundle
import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody
import com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.themebg.ThemeBackground
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.HierarchyEntry
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud News: the shared nav pattern (#1164) around the news.html WebView. The first row (Profile top-right opens Cloud
 * Account), the bottom nav and the page background are the shared libs'; the sections are build.json::ui (Media, Events, News,
 * Saved, Configs) and a tap runs the page's own switchTab(). The page's bottom bar is hidden in the shell (news.html,
 * `native-nav`), and its scroll is wired into the one chrome collapse ([ChromeWebView]).
 */
class MainActivity : AppCompatActivity() {
    private lateinit var webView: WebView
    private var mediaPlayer: MediaPlayerOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)
        webView = CloudWeb.create(this, WebProfile.APP_PAGE)
        // Without this the entire UI is a black box: the whole app is one
        // WebView, so a JS error or a diagnostic console.log went nowhere and
        // the devtools logcat endpoint could only ever show the Kotlin side.
        // Anything the page logs now lands in logcat under "NewsWeb", which
        // means /api/diagnostics/logcat can see it from another machine.
        webView.webChromeClient = object : CloudChromeClient(this) {
            override fun onConsoleMessage(m: ConsoleMessage): Boolean {
                val where = "${m.sourceId()?.substringAfterLast('/') ?: "?"}:${m.lineNumber()}"
                val line = "[$where] ${m.message()}"
                when (m.messageLevel()) {
                    ConsoleMessage.MessageLevel.ERROR -> Log.e("NewsWeb", line)
                    ConsoleMessage.MessageLevel.WARNING -> Log.w("NewsWeb", line)
                    else -> Log.i("NewsWeb", line)
                }
                return true
            }
        }
        // SECURITY: attaching a JavascriptInterface is only safe because this
        // WebView loads a LOCAL asset we ship (file:///android_asset/news.html)
        // and never navigates to remote content. Any remote page loaded here
        // would gain the same Java-callable surface as our own UI. Article
        // links are therefore handed to the system browser via
        // NewsBridge.openExternal(), never opened in this WebView.
        webView.addJavascriptInterface(NewsBridge(this), "NewsBridge")
        // the Media tab: its own fetch door and the in-app YouTube player (same local-asset-only rule as above)
        mediaPlayer = MediaPlayerOverlay(this, webView) { webView.evaluateJavascript("if (window.MediaUI) MediaUI.expand()", null) }
        webView.addJavascriptInterface(MediaBridge(this) { mediaPlayer }, "MediaBridge")
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val p = mediaPlayer
                if (p != null && p.inFullscreen()) p.leaveFullscreen() else { isEnabled = false; onBackPressedDispatcher.onBackPressed(); isEnabled = true }
            }
        })
        // the scroll bridge must exist BEFORE the first load (a JavaScript interface added later only reaches later pages); the shell sets its target
        com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(webView, null)
        webView.loadUrl("file:///android_asset/news.html")
        setContent { NewsShell() }
        Updater.start(this)
    }

    override fun onPause() { mediaPlayer?.onPause(); super.onPause() }
    override fun onResume() { super.onResume(); mediaPlayer?.onResume() }
    override fun onDestroy() { mediaPlayer?.destroy(); super.onDestroy() }

    @Composable
    private fun NewsShell() {
        var section by remember { mutableStateOf(NAV.default()?.id ?: "feed") }
        var configsPage by remember { mutableStateOf("media") }
        val go: (String) -> Unit = { id ->
            section = id
            webView.evaluateJavascript("switchTab('$id')", null)
            if (id == "configs") webView.evaluateJavascript("switchConfigs('$configsPage')", null)
        }
        // #1470 Configs: Media | Events | News | Apify are the page's own groups (switchConfigs), General | Perms | About the fleet's shared pages.
        val pickConfigs: (String) -> Unit = { id ->
            configsPage = id
            if (!isFleetConfigsPage(id)) webView.evaluateJavascript("switchConfigs('$id')", null)
        }
        val current = NAV.section(section)
        NavBarTopFirstRowScaffold(
            title = current?.label ?: getString(R.string.app_name),
            // The title island lists the five sections (each a single page), so a pick switches the page's tab.
            // #1483 under the open Configs its pages (the page's own groups + the fleet tail), so the dropdown lists each and a pick lands on it
            hierarchy = NAV.bottomSections().flatMap { s ->
                if (s.id == section && s.id == "configs")
                    titleHierarchy(s.copy(pages = com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(s.pages)), configsPage)
                else listOf(HierarchyEntry(s.id, s.label, 0, s.id, selected = s.id == section))
            },
            onNavigate = { e -> if (e.depth > 0 && e.sectionId == "configs") pickConfigs(e.id) else go(e.sectionId) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { go("configs") },
            onAi = rememberSuperAppAiLauncher(),
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            TopBarDrawer { close ->
                NAV.bottomSections().forEach { sec ->
                    Text(
                        sec.label,
                        Modifier.fillMaxWidth().clickable { go(sec.id); close() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            NavBarBottomHost(
                entries = NAV.islandEntries(),
                selectedId = section,
                onSelect = { go(it.id) },
            ) {
                // #1470 Configs is Media | Events | News | Apify (the page's own groups) then General | Perms | About (the fleet's shared pages, libs:ui-nav-bars, drawn over the page).
                val configsOpen = section == "configs"
                Column(Modifier.fillMaxSize()) {
                    if (configsOpen) {
                        NavBarTopSecondRow(
                            // #1630 {own tabs} | Agents, Perms, Logcat, X-Ray App, About
                            pages = com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(current?.pages.orEmpty()), selectedId = configsPage,
                            onSelect = { pickConfigs(it.id) }, dark = true, underTopChrome = false,
                        )
                    }
                    Box(Modifier.weight(1f).fillMaxWidth()) {
                        ChromeWebView(webView)
                        if (configsOpen && isFleetConfigsPage(configsPage)) {
                            Box(Modifier.fillMaxSize().background(ThemeBackground.colors(true).top).testTag("news:configs:page:$configsPage")) {
                                FleetConfigsPageBody(configsPage, onAppConfigs = { pickConfigs("news") })
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        /** build.json::ui as baked into BuildConfig: the bottom nav's items (Media, Events, News, Saved, Configs) and their sections. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }
    }
}
