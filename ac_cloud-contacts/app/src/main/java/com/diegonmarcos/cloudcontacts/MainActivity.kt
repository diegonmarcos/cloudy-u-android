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
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.rememberAccountLauncher
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.HierarchyEntry
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Contacts: the shared nav pattern (#1164) around the contacts.html WebView. The first row (Profile top-right opens Cloud
 * Account), the bottom nav and the page background are the shared libs'; the sections are build.json::ui (People, Import, About)
 * and a tap runs the page's own switchTab(). The page's bottom bar is hidden in the shell (contacts.html, `native-nav`), and
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
        var section by remember { mutableStateOf(NAV.default()?.id ?: "people") }
        val go: (String) -> Unit = { id ->
            section = id
            webView.evaluateJavascript("switchTab('$id')", null)
        }
        val current = NAV.section(section)
        NavBarTopFirstRowScaffold(
            title = current?.label ?: getString(R.string.app_name),
            // The title island lists the three sections (each a single page), so a pick switches the page's tab.
            hierarchy = NAV.bottomSections().map { HierarchyEntry(it.id, it.label, 0, it.id, selected = it.id == section) },
            onNavigate = { e -> go(e.sectionId) },
            onAccount = rememberAccountLauncher(),
            onConfigs = { go("about") },
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
                ChromeWebView(webView)
            }
        }
    }

    companion object {
        /** build.json::ui as baked into BuildConfig: the bottom nav's items (People, Import, About) and their sections. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }
    }
}
