package com.diegonmarcos.cloudaccount

import android.content.Intent
import android.os.Bundle
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.diegonmarcos.superapp.accountui.AccountUi
import com.diegonmarcos.superapp.accountui.AccountUiHost
import com.diegonmarcos.superapp.accountui.AccountUiPage
import com.diegonmarcos.superapp.fleetdata.FleetDataRouting
import com.diegonmarcos.superapp.fleetdata.ui.FleetDataPage
import com.diegonmarcos.cloudaccount.hub.AppProfilesPage
import com.diegonmarcos.cloudaccount.hub.ConnectionsPage
import com.diegonmarcos.cloudaccount.hub.HubNav
import com.diegonmarcos.cloudaccount.hub.FleetServicesSetupPage
import com.diegonmarcos.cloudaccount.hub.DriveSetupPage
import com.diegonmarcos.cloudaccount.hub.ImportPage
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.hub.SamplePage
import com.diegonmarcos.cloudaccount.hub.SecretsProvidersPage
import com.diegonmarcos.cloudaccount.hub.TokensPage
import com.diegonmarcos.cloudaccount.hub.SystemDeviceAuth
import com.diegonmarcos.superapp.bottomnav.FleetChrome
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.TopBarDrawer
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop1.NavBarTopFirstRowScaffold
import com.diegonmarcos.superapp.navbartop1.rememberSuperAppAiLauncher
import com.diegonmarcos.superapp.navbartop1.titleHierarchy
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.profile.AccountConnectPage
import com.diegonmarcos.superapp.profile.FleetEndpointsPage
import com.diegonmarcos.superapp.profile.AuthProvidersPage
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.AccountPermsPage
import com.diegonmarcos.superapp.profile.AccountPlaceholderPage
import com.diegonmarcos.superapp.profile.AccountSettingsPage
import com.diegonmarcos.superapp.profile.ActivityPage
import com.diegonmarcos.superapp.profile.BackupsPage
import com.diegonmarcos.superapp.profile.DevicesMeshPage
import com.diegonmarcos.superapp.profile.ConnectionsTab
import com.diegonmarcos.superapp.profile.FleetSetupTab
import com.diegonmarcos.superapp.profile.ProfilesDevicesPage
import com.diegonmarcos.superapp.profile.ProfilesDiffPage
import com.diegonmarcos.superapp.profile.ProfilesWorkingPage
import com.diegonmarcos.superapp.profile.GrantsTab
import com.diegonmarcos.superapp.profile.SecretsTab
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import com.diegonmarcos.superapp.updater.Updater

/**
 * Cloud Account: the five sections of build.json::ui.bottom_nav (Vault, Config, Connect, Devices, Activity; #1482
 * Vault = IDs, PayCards, Secrets, Autocomplete; Config = the Fleet Config files Android, Desktop, C3; the app's own Configs
 * is General | Perms | About from the top bar), drawn
 * by libs:ui-nav-bars (bar) and libs:ui-nav-bars (strips) from the baked NavDecl. A section's pages are its top strip; a page with pages
 * of its own (Autocomplete, Android, Secrets, Backups, Devices, Status, Authentication providers) gets a second strip
 * under it, so every leaf is at most two taps from the island. Every leaf (section, page) is mapped to ONE composable
 * in [Page]; nothing is drawn in two places. The page starts below the top chrome through the one `statusBarsPadding`
 * of [AccountShell] (the framework's inset, no per-page padding).
 *
 * Links from libs:account pages that still name the old islands ("setup", "account", "settings"), the earlier
 * layouts' ids and an OPEN intent's `section` / `page` extras all go through [HubNav.resolve], so every old page and
 * deep link lands where that page lives now.
 */
class MainActivity : AppCompatActivity() {
    // Devices ▸ Backup ▸ Bundle: real file pickers behind ConnectionsTab's pickBundle / export callbacks.
    private var pendingPick: ((String) -> Unit)? = null
    private var pendingExport: Pair<String, String>? = null
    private val pickLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val onText = pendingPick; pendingPick = null
        if (uri != null && onText != null) onText(contentResolver.openInputStream(uri)?.bufferedReader()?.readText().orEmpty())
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        val pending = pendingExport; pendingExport = null
        if (uri != null && pending != null) contentResolver.openOutputStream(uri)?.use { it.write(pending.second.toByteArray()) }
    }

    // Reveal / copy of a secret: the keyguard confirm screen on phones older than BiometricPrompt-with-PIN (API 29).
    private var pendingConfirm: ((Boolean) -> Unit)? = null
    private val confirmLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val done = pendingConfirm; pendingConfirm = null
        done?.invoke(r.resultCode == RESULT_OK)
    }
    private val deviceAuth by lazy { SystemDeviceAuth(this) { intent, done -> pendingConfirm = done; confirmLauncher.launch(intent) } }

    /** The place an OPEN intent asked for (extras [EXTRA_SECTION] / [EXTRA_PAGE], old ids welcome); null = the default. */
    private val requested = mutableStateOf<Pair<String, String?>?>(null)

    private fun pickText(onText: (String) -> Unit) { pendingPick = onText; pickLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AccountUiHost.current = AccountUiHostImpl   // #1439 libs:account-ui's host hooks
        FleetChrome.apply(this)
        requested.value = deepLink(intent)
        setContent {
            CloudKitTheme(AccountHost.palette(this)) {
                CompositionLocalProvider(LocalDeviceAuth provides deviceAuth) {
                    Surface(Modifier.fillMaxSize()) { AccountShell() }
                }
            }
        }
        Updater.start(this)   // #1502 Account: cancels, never schedules (BackgroundGate.never)
        AccountForeground.onForeground(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        deepLink(intent)?.let { requested.value = it }
    }

    @Composable
    private fun AccountShell() {
        var selected by remember { mutableStateOf<String?>(NAV.default()?.id) }
        var selectedPage by remember { mutableStateOf<String?>(null) }
        // libs:account pages still link with the old island ids; HubNav maps them to where the page lives now.
        val go: (String, String) -> Unit = { s, p -> HubNav.resolve(s, p).let { (ns, np) -> selected = ns; selectedPage = np } }
        val asked = requested.value
        LaunchedEffect(asked) { if (asked != null) { selected = asked.first; selectedPage = asked.second; requested.value = null } }
        val openSection = NAV.section(selected)
        val ai = rememberSuperAppAiLauncher()
        // #1154 the first row: AI hands over to the SuperApp, Profile opens Devices > Me, Configs the app's own General | Perms | About.
        NavBarTopFirstRowScaffold(
            title = openSection?.label ?: getString(R.string.app_name),
            hierarchy = openSection?.let { titleHierarchy(it, selectedPage) }.orEmpty(),
            onNavigate = { e -> selected = e.sectionId; selectedPage = e.id.takeIf { e.depth > 0 } },
            onAccount = { selected = "devices"; selectedPage = "me" },
            onConfigs = { selected = "configs"; selectedPage = null },
            onAi = ai,
            modifier = Modifier.fillMaxSize().statusBarsPadding(),
        ) {
            TopBarDrawer { close ->
                NAV.bottomSections().forEach { sec ->
                    androidx.compose.material3.Text(
                        sec.label,
                        Modifier.fillMaxWidth().clickable { selected = sec.id; selectedPage = null; close() }.padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
            NavBarBottomHost(
                entries = NAV.islandEntries(),
                selectedId = selected,
                onSelect = { selected = it.id; selectedPage = null },
            ) {
                Column(Modifier.fillMaxSize()) {
                    val section = NAV.section(selected)
                    val path = if (section == null) emptyList() else section.path(section.page(selectedPage)?.id)
                    val leaf = path.lastOrNull()?.leaf()?.id.orEmpty()
                    if (section != null) Strips(section, path) { selectedPage = it }
                    Page(selected.orEmpty(), leaf, go)
                }
            }
        }
    }

    /**
     * The section's top strip and, under a container page, its second strip. A tap on a container pill opens
     * its first leaf; each strip's surface is the declared background (build.json ui.sections[].background)
     * through NavDecl.stripSurface, never a colour of this app's.
     */
    @Composable
    private fun Strips(section: NavSection, path: List<NavPage>, select: (String) -> Unit) {
        if (section.pages.size > 1) {
            NavBarTopSecondRow(
                pages = section.pages,
                selectedId = path.firstOrNull()?.id,
                onSelect = { p -> select(p.leaf().id) },
                underTopChrome = false,
                surface = NAV.stripSurface(section.id, darkTheme = true),
            )
        }
        val container = path.firstOrNull()?.takeIf { it.pages.size > 1 } ?: return
        NavBarTopSecondRow(
            pages = container.pages,
            selectedId = path.getOrNull(1)?.id ?: container.leaf().id,
            onSelect = { p -> select(p.leaf().id) },
            underTopChrome = false,
            surface = NAV.stripSurface(section.id, darkTheme = true, pageId = container.id),
        )
    }

    /** (section, leaf page) → its composable. Ids are build.json::ui.sections'; nothing here names a label. */
    @Composable
    private fun Page(section: String, page: String, go: (String, String) -> Unit) {
        val ctx = LocalContext.current
        val model = remember { AccountModel.get(ctx) }
        // #1443 the app's own Configs (opened from the top bar, not in the bottom nav) is General | Perms | About, the shared pages of libs:ui-nav-bars.
        if (section == "configs" && com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage(page)) {
            com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(page, onAppConfigs = { go("config", "cloud-apps") })
            return
        }
        when (section) {
            // #1439 Vault's IDs / PayCards / Autocomplete / Entries and Config are libs:account-ui's (AccountUiPage), shared with Cloud Me's profile page.
            // PUBLIC Config: no page there may show a secret value (test-account-configs-public.sh reads the lib's router).
            "config" -> AccountUiPage(AccountUi.CONFIGS, page, go)
            // PRIVATE: secrets stay masked until reveal behind device auth.
            "vault" -> when (page) {
                "ids", "cards", "entries", "personal", "sites", "snippets" -> AccountUiPage(AccountUi.PERSONAL, page, go)
                "keys" -> SecretsTab(openVault = { AccountHost.route(this, "extapp:cloud-vault") })
                "tokens" -> TokensPage()
                "grants" -> GrantsTab()
                "import" -> ImportPage(::pickText)
                else -> SecretsProvidersPage(go)
            }
            "devices" -> when (page) {
                "peer-control" -> SamplePage(section, page)
                "databases" -> BackupsPage()
                "bundle" -> ConnectionsTab(
                    pickBundle = { onText -> pendingPick = onText; pickLauncher.launch(arrayOf("application/json", "text/plain", "text/html")) },
                    export = { name, text -> pendingExport = name to text; exportLauncher.launch(name) },
                )
                "me" -> AccountUiPage(AccountUi.PERSONAL, page, go)
                "files" -> ProfilesDevicesPage()
                "working" -> ProfilesWorkingPage()
                "diff" -> ProfilesDiffPage(model)
                else -> DevicesMeshPage()
            }
            "connect" -> when (page) {
                "apps" -> AppProfilesPage()
                "endpoints" -> FleetEndpointsPage()
                // Setup: every fleet connection setting (services, Drive / Git / mounts); secrets only in the keystore-backed vault.
                "services" -> FleetServicesSetupPage()
                // #1438 moved here from Configs > Android.
                "push" -> FleetSetupPage(model)
                "perms" -> AccountPermsPage(go)
                "adb-shell" -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { ShellBrokerPanel() }
                "drive" -> DriveSetupPage()
                "hub" -> AuthProvidersPage()
                "signin" -> AccountConnectPage()
                "settings" -> AccountSettingsPage("${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_SHORT_SHA} · built ${BuildConfig.BUILD_TIMESTAMP}")
                else -> ConnectionsPage(go)
            }
            "activity" -> when (page) {
                // #1547 Fleet Data: the Cloud Account dataset browser (the accountdata provider's served tiers 1-2), the same
                // libs:fleet-data-ui page Cloud Store shows for its public datasets. Reads the provider's live manifest + cache; nothing baked.
                "fleetdata" -> FleetDataPage(FleetDataRouting.ACCOUNT, Modifier.fillMaxSize())
                else -> ActivityPage()
            }
            "configs" -> Unit   // the app's own Configs: drawn above by FleetConfigsPageBody (General | Perms | About)
            else -> AccountPlaceholderPage(section, page, "no task: undeclared section")
        }
    }

    /** Configs ▸ Setup ▸ Push (was Setup ▸ configs, spec 4.8): FleetSetupTab as it is, in a scrolling page. */
    @Composable
    private fun FleetSetupPage(model: AccountModel) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) { FleetSetupTab(model) }
    }

    companion object {
        /** OPEN intent extras: a section id and, optionally, a page id; old ids are translated by [HubNav]. */
        const val EXTRA_SECTION = "section"
        const val EXTRA_PAGE = "page"

        /** build.json::ui as baked into BuildConfig: the island's items and the section ids. */
        val NAV: NavDecl by lazy {
            NavDecl.fromBuildConfig(BuildConfig.UI_SECTIONS_B64, BuildConfig.UI_BOTTOM_NAV, BuildConfig.UI_DEFAULT_SECTION)
        }

        /** The (section, page) an intent asks for, resolved to a current place; null when it asks for none. */
        fun deepLink(intent: Intent?): Pair<String, String?>? {
            val s = intent?.getStringExtra(EXTRA_SECTION)?.trim().orEmpty()
            if (s.isEmpty()) return null
            return HubNav.resolve(s, intent?.getStringExtra(EXTRA_PAGE)?.trim()?.ifEmpty { null })
        }

    }
}
