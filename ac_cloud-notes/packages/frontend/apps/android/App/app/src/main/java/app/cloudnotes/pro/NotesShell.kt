package app.cloudnotes.pro

import app.cloudnotes.pro.theme.ThemeMode
import app.cloudnotes.pro.theme.CloudNotesTheme
import androidx.compose.foundation.isSystemInDarkTheme
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.viewinterop.AndroidView
import com.diegonmarcos.superapp.ai.AiFleetChatPanel
import com.diegonmarcos.superapp.bottomnav.ChromeWebScroll
import com.diegonmarcos.superapp.bottomnav.HistoryBarItem
import com.diegonmarcos.superapp.bottomnav.currentNavUi
import com.diegonmarcos.superapp.bottomnav.rememberAppTopBarController
import com.diegonmarcos.superapp.bottomnav.LocalNavChromeCollapse
import com.diegonmarcos.superapp.bottomnav.LocalNavUiDefault
import com.diegonmarcos.superapp.bottomnav.NavUiDecl
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.navEntry
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.navbartop2.NavUiConfigCard
import org.json.JSONArray

/** One page the editor reports for the drawer's tree: its id and title. */
internal data class DrawerDoc(val id: String, val title: String)

/**
 * What the web half pushes into the drawer: `window.CloudNotesHost.setDocs(json)` with
 * `[{"id","title"}, ...]` (see native-nav-bridge.tsx). Malformed JSON leaves the list as it was.
 */
internal object DrawerDocs {
    fun parse(json: String): List<DrawerDoc>? = runCatching {
        val a = JSONArray(json)
        (0 until a.length()).map {
            val o = a.getJSONObject(it)
            DrawerDoc(o.getString("id"), o.optString("title", "").ifBlank { "Untitled" })
        }
    }.getOrNull()
}

/**
 * Cloud Notes' one shell (#1164), the shared nav scaffold every migrated app uses: the first row (hamburger with the doc tree /
 * section drawer, the section title island with its pages, AI = libs:ai's shared chat, Profile top-right launching Cloud Account,
 * Configs asking the web half to open Cloud Notes's own settings), the second row (the open section's pages), the shared bottom
 * nav (build.json::ui) and the shared page background. The editor WebView (with the open-file button, in [Content]'s container) is the content; its scroll (and its inner scrollers')
 * feeds the ONE chrome collapse through [ChromeWebScroll], so reading down hides the first row and compacts the other two.
 * The shell owns every inset: the web half adds no status-bar or system-bar padding of its own (setup.ts).
 */
internal class NotesShell(
    val nav: NativeNav,
    private val gatewayUrl: String,
    private val run: (String) -> Unit,
) {
    /** The editor's pages, newest first, as the web half last reported them. */
    private var docs by mutableStateOf(emptyList<DrawerDoc>())

    /**
     * #1369 the theme the web half resolved (CloudNotesTheme.onThemeChanged -> MainActivity -> [setDark]); the whole shell wears
     * Obsidian's light or dark theme with it. Starts on the system's until the web half reports.
     */
    private var darkState by mutableStateOf<Boolean?>(null)

    fun setDark(dark: Boolean) { darkState = dark }

    /** Configs > UI (the shared nav UI card) is open; its "All settings" opens the web half's own settings. */
    private var configsOpen by mutableStateOf(false)

    /** The object registered on the WebView as `CloudNotesHost`; the bridge thread may write the state. */
    inner class Host {
        @JavascriptInterface
        fun setDocs(json: String) {
            DrawerDocs.parse(json)?.let { docs = it }
        }
    }

    fun host(): Host = Host()

    @Composable
    fun Content(container: ViewGroup, web: WebView) {
        val dark = darkState ?: isSystemInDarkTheme()
        val section = nav.decl.section(nav.sectionId)
        val sectionEntries = nav.decl.bottomSections().map { navEntry(it.id, it.label, it.icon) }
        // #1369 build.json::ui.nav_ui.bottom = "history": in Clean mode the SAME bottom nav holds Obsidian mobile's history bar.
        val historyEntries = HistoryBarItem.entries.map { navEntry(it.id, it.label, it.icon) }
        val topBar = rememberAppTopBarController()
        // The nav mode Cloud Notes starts in is build.json::ui.nav_ui (CLEAN); the user's own choice in Configs > UI wins.
        CompositionLocalProvider(LocalNavUiDefault provides NavUiDecl.fromBuildConfig(BuildConfig.UI_NAV_UI_B64)) {
        CloudNotesTheme(if (dark) ThemeMode.Dark else ThemeMode.Light) {
            if (configsOpen) ConfigsUi(dark)
            NavBarTopFirstRowScaffold(
                title = section?.label ?: nav.sectionId,
                hierarchy = section?.let { titleHierarchy(it, nav.pageId) }.orEmpty(),
                onNavigate = { e -> if (e.depth > 0) nav.openPage(e.sectionId, e.id) else nav.open(e.sectionId) },
                onAccount = rememberAccountLauncher(),
                onConfigs = { configsOpen = true },
                aiPanel = { AiFleetChatPanel(gatewayUrl) },
                sections = nav.decl.bottomSections(),
                configsUi = false, // Configs opens Configs > UI itself (ConfigsUi below)
                modifier = Modifier.fillMaxSize().statusBarsPadding(),
                dark = dark,
                controller = topBar,
            ) {
                TopBarDrawer { close -> Drawer(close) }
                val history = currentNavUi().historyBar
                NavBarBottomHost(
                    entries = if (history) historyEntries else sectionEntries,
                    selectedId = if (history) null else nav.sectionId,
                    onSelect = { e ->
                        when (HistoryBarItem.of(e.id).takeIf { history }) {
                            HistoryBarItem.Back -> if (web.canGoBack()) web.goBack()
                            HistoryBarItem.Forward -> if (web.canGoForward()) web.goForward()
                            HistoryBarItem.New -> nav.openPage("pages", "new")
                            HistoryBarItem.Tabs -> nav.open("pages")
                            HistoryBarItem.Menu -> topBar.openDrawer()
                            null -> nav.open(e.id)
                        }
                    },
                    dark = dark,
                ) {
                    Column(Modifier.fillMaxSize()) {
                        NavBarTopSecondRow(
                            pages = section?.pages.orEmpty(),
                            selectedId = nav.pageId,
                            onSelect = { nav.openPage(it) },
                            dark = dark,
                            underTopChrome = false,
                            surface = nav.decl.stripSurface(nav.sectionId, dark),
                        )
                        Box(Modifier.weight(1f).fillMaxWidth()) {
                            val collapse = LocalNavChromeCollapse.current
                            AndroidView(
                                factory = { (container.parent as? ViewGroup)?.removeView(container); container },
                                modifier = Modifier.fillMaxSize(),
                                update = { ChromeWebScroll.attach(web, collapse) },
                            )
                            // Search > Ask: the fleet's shared AI chat (libs:ai) over the editor, between the second row and the bottom nav.
                            if (nav.askShown) {
                                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) { AiFleetChatPanel(gatewayUrl) }
                            }
                        }
                    }
                }
            }
        }
        }
    }

    /** Configs > UI: the shared nav card (mode Classic / Clean, scroll-hide), and the way on to the web half's own settings. */
    @Composable
    private fun ConfigsUi(dark: Boolean) {
        Dialog(onDismissRequest = { configsOpen = false }) {
            Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.padding(16.dp).testTag("notes_configs_ui")) {
                    Text("Configs \u25B8 UI", color = MaterialTheme.colorScheme.onSurface)
                    NavUiConfigCard(Modifier.padding(vertical = 12.dp), dark = dark)
                    TextButton(onClick = { configsOpen = false; run(NativeNavScript.openSettings()) }) { Text("All settings") }
                }
            }
        }
    }

    @Composable
    private fun ColumnScope.Drawer(close: () -> Unit) {
        Eyebrow("Notes")
        nav.decl.bottomSections().forEach { s ->
            NavigationDrawerItem(
                label = { Text(s.label) },
                selected = s.id == nav.sectionId,
                onClick = { nav.open(s.id); close() },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("notes_drawer_section_${s.id}"),
            )
        }
        Eyebrow("Pages")
        if (docs.isEmpty()) {
            Text(
                "No pages yet",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
            )
        }
        docs.forEach { d ->
            NavigationDrawerItem(
                label = { Text(d.title, maxLines = 1) },
                selected = false,
                onClick = { run(NativeNavScript.openDoc(d.id)); close() },
                modifier = Modifier.padding(horizontal = 12.dp).testTag("notes_drawer_doc_${d.id}"),
            )
        }
    }

    @Composable
    private fun Eyebrow(text: String) {
        Text(
            text.uppercase(),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp),
        )
    }
}
