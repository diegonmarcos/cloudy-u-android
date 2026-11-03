package com.diegonmarcos.cloudaccount

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.SemanticsMatcher
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.autofill.AutofillProfilesPage
import com.diegonmarcos.cloudaccount.configs.AppRow
import com.diegonmarcos.cloudaccount.configs.CloudAppsPage
import com.diegonmarcos.cloudaccount.configs.ComposeDecl
import com.diegonmarcos.cloudaccount.configs.ConfigsSources
import com.diegonmarcos.cloudaccount.configs.ContainerRow
import com.diegonmarcos.cloudaccount.configs.ContainersPage
import com.diegonmarcos.cloudaccount.configs.DesktopTree
import com.diegonmarcos.cloudaccount.configs.DesktopTreePage
import com.diegonmarcos.cloudaccount.configs.InfraPage
import com.diegonmarcos.cloudaccount.configs.InfraRow
import com.diegonmarcos.cloudaccount.configs.PhoneAppsPage
import com.diegonmarcos.cloudaccount.configs.RunbookTogglesPage
import com.diegonmarcos.cloudaccount.configs.TfDecl
import com.diegonmarcos.cloudaccount.configs.TreeNode
import com.diegonmarcos.cloudaccount.personal.PersonalEntriesPage
import com.diegonmarcos.cloudaccount.personal.PersonalEntry
import com.diegonmarcos.cloudaccount.personal.PersonalKind
import com.diegonmarcos.cloudaccount.personal.PersonalRepo
import com.diegonmarcos.cloudaccount.hub.AppProfilesPage
import com.diegonmarcos.cloudaccount.hub.ConnectionsPage
import com.diegonmarcos.cloudaccount.hub.DriveSetupPage
import com.diegonmarcos.cloudaccount.hub.FleetServicesSetupPage
import com.diegonmarcos.cloudaccount.hub.LocalDeviceAuth
import com.diegonmarcos.cloudaccount.hub.SecretsProvidersPage
import com.diegonmarcos.cloudaccount.hub.TokensPage
import com.diegonmarcos.superapp.navbarbottom.NavBarBottomHost
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.bottomnav.islandEntries
import com.diegonmarcos.superapp.profile.AccountHost
import com.diegonmarcos.superapp.profile.AccountModel
import com.diegonmarcos.superapp.profile.AccountPermsPage
import com.diegonmarcos.superapp.profile.AccountProfilePage
import com.diegonmarcos.superapp.profile.AccountStore
import com.diegonmarcos.superapp.profile.ActivityFeed
import com.diegonmarcos.superapp.profile.ActivityPage
import com.diegonmarcos.superapp.profile.BackupsPage
import com.diegonmarcos.superapp.profile.DevicesMeshPage
import com.diegonmarcos.superapp.profile.EndpointCheck
import com.diegonmarcos.superapp.profile.FleetEndpointsPage
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import com.diegonmarcos.superapp.uikit.CloudKitTheme
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * #1081 evidence: the real chrome of Cloud Account (the island from the baked NavDecl, the section's top strip, the
 * container's second strip) drawn over one page of each section at Galaxy A37 size, written to
 * app/build/section-screens/<section>-<page>.png (the account-screens workflow uploads the folder). The pages this
 * change added, and the strips, must render; a page that predates it is shot best-effort (a failure is written as
 * <name>.err, not a red test: its own suite owns it). Every value in the seeded vault is an obvious fake.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, qualifiers = "w384dp-h832dp-450dpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SectionScreensTest(private val section: String, private val page: String) {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    @Before fun seed() {
        val ctx: Context = ApplicationProvider.getApplicationContext()
        ConfigsPrefs.factory = { it.getSharedPreferences("screens_test", Context.MODE_PRIVATE) }
        AccountHost.mesh = null
        // The phone's model keeps its files in an EncryptedFile (Android Keystore), which Robolectric does not have: the
        // seeding used to fail there inside a runCatching and every page drew its empty state. A plain-file model is installed
        // instead, and a seed that does not land fails the test rather than shooting empty screens.
        val model = AccountModel(ctx, AccountStore(File(ctx.filesDir, "account-screens"), AccountStore.PlainIo))
        AccountModel.install(model)
        model.store.write(AccountStore.Slot.S, JSONObject("""{
          "electronics": {
            "computers": {"fedora-box": {"label": "Fedora box", "type": "notebook", "os": "fedora 43", "home_manager": "diego@fedora-box",
                "wg_peer": {"name": "fedora-box", "wg_ip": "10.0.0.2"}}},
            "phones": {"galaxy-s21": {"label": "Galaxy S21", "type": "phone", "os": "android 15",
                "wg_peer": {"name": "galaxy", "wg_ip": "10.0.0.3"}}}
          },
          "containers": {"caddy": {"image": "caddy:2", "host": "vm-1"}, "authelia": {"image": "authelia:4", "host": "vm-1"}}
        }"""), "screens-test", "2026-10-21T00:00:00Z")
        model.invalidate()
        val seeded = model.doc(AccountStore.Slot.S)?.body
        assertTrue("the vault seed did not land in slot S: the screenshots would show empty states",
            seeded != null && seeded.optJSONObject("electronics")?.optJSONObject("computers")?.has("fedora-box") == true &&
                seeded.optJSONObject("containers")?.has("caddy") == true)
        ActivityFeed.clear(ctx)
        ActivityFeed.record(ctx, "backup", "auto-backup of galaxy-s21: ok", now = 1_760_000_000_000)
        ActivityFeed.record(ctx, "secret", "revealed github.token", now = 1_760_000_100_000)
        ActivityFeed.record(ctx, "import", "imported 3 token(s)", now = 1_760_000_200_000)
    }

    @Composable private fun PageFor(go: (String, String) -> Unit) {
        when ("$section/$page") {
            "personal/ids" -> PersonalEntriesPage(PersonalKind.IDS) { personalRepo(it) }
            "personal/cards" -> PersonalEntriesPage(PersonalKind.CARDS) { personalRepo(it) }
            "personal/entries" -> PersonalEntriesPage(PersonalKind.OTHERS) { personalRepo(it) }
            "personal/me" -> AccountProfilePage(go)
            "personal/personal" -> AutofillProfilesPage()
            "configs/cloud-apps" -> CloudAppsPage { FIX_CLOUD }
            "configs/phone-apps" -> PhoneAppsPage { FIX_PHONE }
            "configs/runbook" -> RunbookTogglesPage(go, FIX_TOGGLES.toggles, state = { _, t -> t.id != "show-touches" }, set = { _, _, _ -> true })
            "configs/desktop" -> DesktopTreePage { FIX_DESKTOP }
            "configs/infra" -> InfraPage { FIX_INFRA }
            "configs/containers" -> ContainersPage { FIX_CONTAINERS }
            "connect/perms" -> AccountPermsPage(go)
            "data/providers" -> SecretsProvidersPage(go)
            "data/tokens" -> TokensPage()
            "data/databases" -> BackupsPage()
            "data/mesh" -> DevicesMeshPage()
            "connect/connections" -> ConnectionsPage(go)
            "connect/apps" -> AppProfilesPage()
            "connect/endpoints" -> FleetEndpointsPage(check = { sv -> sv.associate { it.id to EndpointCheck(meshUp = it.hasPublic, publicStatus = if (it.hasPublic) 200 else null) } })
            "connect/services" -> FleetServicesSetupPage()
            "connect/drive" -> DriveSetupPage()
            "activity/activity" -> ActivityPage()
            else -> error("no page for $section/$page")
        }
    }

    /** Personal pages over a plain test store (Robolectric has no keystore); every value is an obvious fake. */
    private fun personalRepo(ctx: Context): PersonalRepo {
        val r = PersonalRepo(ctx.getSharedPreferences("screens_personal", Context.MODE_PRIVATE))
        if (r.list(PersonalKind.CARDS).isEmpty()) {
            r.put(PersonalKind.CARDS, PersonalEntry("c1", "Everyday card", mapOf("holder" to "TEST HOLDER", "number" to "4000 0000 0000 0002", "expiry" to "12/30", "brand" to "visa")))
            r.put(PersonalKind.IDS, PersonalEntry("i1", "Passport", mapOf("type" to "passport", "number" to "X0000000", "country" to "ES", "expiry" to "2031-01-01")))
            r.put(PersonalKind.OTHERS, PersonalEntry("o1", "Library card", mapOf("value" to "LIB-0000")))
        }
        return r
    }

    @Composable private fun Chrome() {
        val nav = MainActivity.NAV
        val sec = nav.section(section)!!
        val path = sec.path(page)
        val go: (String, String) -> Unit = { _, _ -> }
        CloudKitTheme(AccountHost.palette(compose.activity)) {
            CompositionLocalProvider(LocalDeviceAuth provides com.diegonmarcos.cloudaccount.hub.DeviceAuth { _, done -> done(false) }) {
                Surface(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxSize().statusBarsPadding()) {
                        NavBarBottomHost(
                            entries = nav.islandEntries { rememberVectorPainter(Icons.Filled.Star) },
                            selectedId = section, onSelect = {},
                        ) {
                            Column(Modifier.fillMaxSize()) {
                                if (sec.pages.size > 1) NavBarTopSecondRow(sec.pages, path.firstOrNull()?.id, onSelect = {}, underTopChrome = false,
                                    surface = nav.stripSurface(sec.id, darkTheme = true))
                                val container = path.firstOrNull()?.takeIf { it.pages.size > 1 }
                                if (container != null) NavBarTopSecondRow(container.pages, page, onSelect = {}, underTopChrome = false,
                                    surface = nav.stripSurface(sec.id, darkTheme = true, pageId = container.id))
                                PageFor(go)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun shot(name: String) {
        val bmp = compose.runOnUiThread {
            val root = compose.activity.findViewById<View>(android.R.id.content)
            Bitmap.createBitmap(root.width.coerceAtLeast(1), root.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888).also { b ->
                Canvas(b).also { c -> c.drawColor(android.graphics.Color.BLACK); root.draw(c) }
            }
        }
        File(OUT, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun `the section renders under its island and strips`() {
        OUT.mkdirs()
        val name = "$section-$page"
        val must = "$section/$page" in MUST
        try {
            compose.setContent { Chrome() }
            compose.waitForIdle()
            val nodes = compose.onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true).fetchSemanticsNodes(false)
            assertTrue("$name drew nothing", nodes.isNotEmpty())
            shot(name)
        } catch (t: Throwable) {
            File(OUT, "$name.err").writeText("${t.javaClass.simpleName}: ${t.message?.replace('\n', ' ')?.take(300)}\n")
            assertTrue("$name did not render: ${t.javaClass.simpleName}: ${t.message?.take(300)}", !must)
        }
    }

    companion object {
        private val OUT = File("build/section-screens")

        // #1438 fixtures for the repo-backed pages (the shape the real loaders return; no network in a unit test).
        private val FIX_CLOUD = listOf(AppRow("com.diegonmarcos.cloudstore", "Cloud Store", "1.4.0", "app"),
            AppRow("com.diegonmarcos.cloudmail", "Cloud Mail", null, "app"), AppRow("com.diegonmarcos.superapp", "SuperApp", "2.0.1", "app"))
        private val FIX_PHONE = listOf(AppRow("org.mozilla.firefox", "Firefox", "131.0", ""), AppRow("com.whatsapp", "WhatsApp", "2.24", ""))
        private val FIX_TOGGLES = ConfigsSources.parse("""{"runbook_toggles": [
            {"id": "animations-off", "label": "Animations off", "on": "x", "off": "y", "check": "z", "on_when": "0"},
            {"id": "stay-awake", "label": "Stay awake while charging", "on": "x", "off": "y", "check": "z", "on_when": "7"},
            {"id": "show-touches", "label": "Show touches", "on": "x", "off": "y", "check": "z", "on_when": "1"}]}""")
        private val FIX_DESKTOP = listOf(DesktopTree("Nix OS and Home Manager flakes", "cloud-me-pvt-configs",
            TreeNode.of(listOf("Linux-Desktop/a1-diego-user/nix-hm-flakes/ba_flakes_desktop/src/flake.nix",
                "Linux-Desktop/a1-diego-user/nix-hm-flakes/ba_flakes_desktop/src/home-manager/home.nix",
                "Linux-Desktop/_shared/nix-os-flakes/bb_flakes_termux/src/flake.nix"), "Linux-Desktop", Regex("\\.nix$"), emptyList()), "3 file(s)"),
            DesktopTree("OS Terraform", "cloudy-u-desktop", TreeNode.of(listOf("a_OS/a.md"), "a_OS", Regex(".*"), emptyList()), "1 file(s)"))
        private val FIX_INFRA = listOf(InfraRow("c_vps/vps_oci", "oci", "Terraform: Oracle Cloud VMs", "vps",
            TfDecl.parse("provider \"oci\" {}\nresource \"oci_core_instance\" \"apps\" {}\nvariable \"region\" {}")))
        private val FIX_CONTAINERS = listOf(ContainerRow("bc-obs_alerts-api", ComposeDecl.parse(
            "services:\n  alerts-api:\n    image: ghcr.io/x/alerts-api:latest\n    environment:\n      - DB_PATH=/data/alerts.db\n      - NTFY_TOKEN=fake-value-0000\n    volumes:\n      - alerts-data:/data\n")))

        /** The pages #1081 added: they must render. */
        private val MUST = setOf("personal/ids", "personal/cards", "personal/entries", "configs/cloud-apps", "configs/phone-apps",
            "configs/runbook", "configs/desktop", "configs/infra", "configs/containers", "data/databases", "data/mesh", "activity/activity", "connect/endpoints", "connect/services", "connect/drive")

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}/{1}")
        fun pages(): List<Array<Any>> = listOf(
            "personal/ids", "personal/cards", "personal/entries", "personal/me", "personal/personal",
            "configs/cloud-apps", "configs/phone-apps", "configs/runbook", "configs/desktop", "configs/infra", "configs/containers",
            "connect/perms",
            "data/providers", "data/tokens", "data/databases", "data/mesh",
            "connect/connections", "connect/apps", "connect/endpoints", "connect/services", "connect/drive",
            "activity/activity",
        ).map { it.split('/').toTypedArray<Any>() }
    }
}
