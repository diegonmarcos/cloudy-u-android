package com.diegonmarcos.cloudcontacts

import com.diegonmarcos.cloudlib.web.CloudWeb
import com.diegonmarcos.cloudlib.web.WebProfile
import android.Manifest
import android.annotation.SuppressLint
import android.graphics.Color
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
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
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody
import com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.themebg.themeBackground
import com.diegonmarcos.superapp.bottomnav.FleetChrome
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
 * Cloud Contacts: the shared nav pattern (#1164) around the contacts.html WebView. The first row (Profile top-right opens Cloud
 * Account), the bottom nav and the page background are the shared libs'; the sections are build.json::ui, #1671: Groups | Business |
 * All | People | Configs. A tap on the first four runs the page's own switchTab(); Configs is the fleet strip ({General, Import,
 * Channels} | Agents, Perms, Logcat, X-Ray App, About), its own tabs drawn by the page and the shared ones by libs:ui-nav-bars.
 * The page's bottom bar is hidden in the shell (contacts.html, `native-nav`), and
 * its scroll is wired into the one chrome collapse ([ChromeWebView]).
 *
 * The whole UI lives in assets/contacts.html (HTML/Tailwind); ContactsBridge is the Kotlin-JS seam. The activity owns only
 * the two Android launchers the bridge cannot host itself: the READ_CONTACTS runtime permission and the SAF picker for
 * LinkedIn/Instagram export files.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var bridge: ContactsBridge

    // #1515 READ_CONTACTS is granted in Configs > Perms (libs:perms), never asked from here: the page opens on the contacts_read
    // row and the bridge hears the outcome when the user is back.
    private var askedContacts = false

    override fun onResume() {
        super.onResume()
        if (askedContacts) {
            askedContacts = false
            bridge.onPermissionResult(checkSelfPermission(Manifest.permission.READ_CONTACTS) == android.content.pm.PackageManager.PERMISSION_GRANTED)
        }
    }

    private val importPicker =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            bridge.onFilePicked(uri)
        }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        FleetChrome.apply(this)

        webView = CloudWeb.create(this, WebProfile.APP_PAGE).apply {
            setBackgroundColor(Color.parseColor("#0B0F14"))
        }
        bridge = ContactsBridge(
            activity = this,
            webView = webView,
            requestContactsPermission = { askedContacts = true; com.diegonmarcos.superapp.perms.PermsLink.open(this, "contacts", listOf("contacts_read")) },
            launchImportPicker = { importPicker.launch("*/*") },
        )
        webView.addJavascriptInterface(bridge, "Native")

        // SECURITY: only our bundled asset is ever loaded (file:///android_asset).
        // the scroll bridge must exist BEFORE the first load (a JavaScript interface added later only reaches later pages); the shell sets its target
        com.diegonmarcos.superapp.bottomnav.ChromeWebScroll.attach(webView, null)
        webView.loadUrl("file:///android_asset/contacts.html")

        setContent { ContactsShell() }

        Updater.start(this)
    }

    @Composable
    private fun ContactsShell() {
        var section by remember { mutableStateOf(NAV.default()?.id ?: "all") }
        var configsPage by remember { mutableStateOf<String?>(null) }
        // #1671 a bottom-nav tap on a web section runs the page's own switchTab(); Configs is native (the fleet strip) and
        // hands its two own tabs (Import, Channels) to the same WebView. The WebView stays composed under every page.
        val showWeb: (String) -> Unit = { tab -> webView.evaluateJavascript("switchTab('$tab')", null) }
        val goConfigs: (String?) -> Unit = { page ->
            section = "configs"
            configsPage = page
            webTabOf(page)?.let(showWeb)
        }
        val go: (String) -> Unit = { id ->
            if (id == "configs") goConfigs(configsPage) else { section = id; showWeb(id) }
        }
        val current = NAV.section(section)
        NavBarTopFirstRowScaffold(
            title = current?.label ?: getString(R.string.app_name),
            // #1483 every section, and under the open one its own pages (Configs: the declared pages + the fleet tail), so the title
            // dropdown lists the page a pick lands on and the title path ends in it
            hierarchy = NAV.bottomSections().flatMap { s ->
                if (s.id == section && s.id == "configs") titleHierarchy(s.copy(pages = FleetConfigsExtensions.withTail(s.pages)), configsLeaf(s, configsPage))
                else listOf(HierarchyEntry(s.id, s.label, 0, s.id, selected = s.id == section))
            },
            onNavigate = { e -> if (e.depth > 0 && e.sectionId == "configs") goConfigs(e.id) else go(e.sectionId) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { goConfigs(null) },
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
                Column(Modifier.fillMaxSize()) {
                    val configs = if (section == "configs") NAV.section("configs") else null
                    val page = configsLeaf(configs, configsPage)
                    if (configs != null) {
                        NavBarTopSecondRow(
                            // Configs ends with the fleet-default tail: {General + own tabs} | Agents, Perms, Logcat, X-Ray App, About.
                            pages = FleetConfigsExtensions.withTail(configs.pages),
                            selectedId = page,
                            onSelect = { p -> goConfigs(p.leaf().id) },
                            underTopChrome = false,
                            surface = NAV.stripSurface(configs.id, darkTheme = true),
                        )
                    }
                    Box(Modifier.fillMaxSize()) {
                        ChromeWebView(webView)
                        // A shared Configs page (General, Perms, About, and the opt-in Agents / Logcat / X-Ray App) covers the WebView.
                        if (configs != null && webTabOf(page) == null && isFleetConfigsPage(page)) {
                            Box(Modifier.fillMaxSize().themeBackground(dark = true)) {
                                FleetConfigsPageBody(page, onAppConfigs = { goConfigs("import") })
                            }
                        }
                    }
                }
            }
        }
    }

    /** The Configs page to draw: the selected one when it is declared or an opt-in tail tab, else the first (General). */
    private fun configsLeaf(configs: NavSection?, selected: String?): String {
        if (configs == null) return ""
        if (selected != null && (configs.path(selected).lastOrNull()?.id == selected || FleetConfigsExtensions.isOptInTail(selected))) return selected
        return configs.pages.firstOrNull()?.leaf()?.id.orEmpty()
    }

    /** Configs' own tabs live in contacts.html: Import is its Import panel, Channels its channel-registry panel. */
    private fun webTabOf(page: String?): String? = when (page) {
        "import" -> "import"
        "channels" -> "about"
        else -> null
    }

    companion object {
        /** build.json::ui as baked into BuildConfig: the bottom nav Groups | Business | All | People | Configs (#1671) and its sections. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }
    }
}
