package com.diegonmarcos.cloudaccount

import android.app.Activity
import android.content.ClipboardManager
import android.content.Context
import android.util.Base64
import androidx.test.core.app.ApplicationProvider
import com.diegonmarcos.cloudaccount.hub.AppProfiles
import com.diegonmarcos.cloudaccount.hub.AppSetup
import com.diegonmarcos.cloudaccount.hub.AuthMethod
import com.diegonmarcos.cloudaccount.hub.CellStatus
import com.diegonmarcos.cloudaccount.hub.DeviceAuth
import com.diegonmarcos.cloudaccount.hub.HubNav
import com.diegonmarcos.cloudaccount.hub.Probe
import com.diegonmarcos.cloudaccount.hub.ProviderSetup
import com.diegonmarcos.cloudaccount.hub.Providers
import com.diegonmarcos.cloudaccount.hub.RevealGate
import com.diegonmarcos.cloudaccount.hub.SecretClipboard
import com.diegonmarcos.cloudaccount.hub.SetupAll
import com.diegonmarcos.cloudaccount.hub.SetupResult
import com.diegonmarcos.cloudaccount.hub.SetupTarget
import com.diegonmarcos.cloudaccount.hub.StepKind
import com.diegonmarcos.cloudaccount.hub.StepResult
import com.diegonmarcos.cloudaccount.hub.SystemDeviceAuth
import com.diegonmarcos.cloudaccount.hub.TokenImport
import com.diegonmarcos.superapp.fleetconfig.FleetPolicy
import com.diegonmarcos.superapp.profile.AccountFleet
import com.diegonmarcos.superapp.profile.SetupPlan
import com.diegonmarcos.superapp.settings.AccountVault
import com.diegonmarcos.superapp.settings.ConfigsPrefs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import java.io.File
import java.time.Instant

/**
 * The reorganised Cloud Account (Vault, Config, Connect, Devices, Activity): the nav order, every old page still
 * reachable, the Connections table built from what the account vault stores, the token import parser, reveal
 * behind device auth, the self-clearing copy, Setup per provider / app and Setup all, and no value in a log.
 * Every value here is an obvious fake.
 */
@RunWith(RobolectricTestRunner::class)
class HubTest {
    private lateinit var ctx: Context
    private val now: Instant = Instant.parse("2026-10-13T12:00:00Z")

    @Before fun setUp() {
        ctx = ApplicationProvider.getApplicationContext()
        ctx.getSharedPreferences("hub_test", Context.MODE_PRIVATE).edit().clear().commit()
        ConfigsPrefs.factory = { it.getSharedPreferences("hub_test", Context.MODE_PRIVATE) }
        ShadowLog.clear()
    }

    /** A JWT-shaped fake (`{"alg":"none"}.{"exp":…}.x`) built here, so no token-like literal sits in the source. */
    private fun fakeJwt(exp: Long): String {
        fun b64(s: String) = Base64.encodeToString(s.toByteArray(), Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
        return b64("{\"alg\":\"none\"}") + "." + b64("{\"exp\":$exp}") + ".x"
    }

    // ── nav ──────────────────────────────────────────────────────────────

    @Test fun bottomNavIsGroupsBusinessAllPeopleConfigs() {
        // #1625 the contacts bottom nav: Groups | Business | All | People | Configs, default All.
        assertEquals(listOf("groups", "business", "all", "people", "configs"), HubNav.ORDER)
        assertEquals("groups,business,all,people,configs", BuildConfig.UI_BOTTOM_NAV)
        assertEquals(HubNav.ORDER, MainActivity.NAV.bottomSections().map { it.id })
        assertEquals(listOf("Groups", "Business", "All", "People", "Configs"), MainActivity.NAV.bottomSections().map { it.label })
        assertEquals("all", MainActivity.NAV.default()?.id)
        // #1625 the app's own Configs (General | Perms | About) is now the fifth bottom-nav item.
        assertNotNull(MainActivity.NAV.section("configs"))
        assertTrue("configs" in MainActivity.NAV.bottomSections().map { it.id })
        // the identity/vault sections stay declared (reachable by deep link) but are off the bottom bar.
        assertTrue(listOf("vault", "config", "connect", "devices", "activity").all { MainActivity.NAV.section(it) != null })
        assertTrue(listOf("vault", "config", "connect", "devices", "activity").none { it in MainActivity.NAV.bottomSections().map { s -> s.id } })
        assertTrue("no Setup, Profiles, Secrets or Home section any more", listOf("setup", "profiles", "secrets", "home").all { MainActivity.NAV.section(it) == null })
    }

    @Test fun everyOldPageIsReachable() {
        val old = mapOf(
            // the redesign's five islands
            "account" to listOf("", "profile", "connect", "autofill", "sites", "snippets", "import"),
            "setup" to listOf("", "runbook", "apps", "configs", "perms", "adb-shell"),
            "settings" to listOf(""),
            // the four islands of 2026-10-13 (#964) and of 2026-10-14
            "profiles" to listOf("", "apps", "autofill", "personal", "sites", "snippets", "phone", "devices", "working", "diff"),
            "secrets" to listOf("", "providers", "connections", "bundle", "secrets", "grants"),
            "home" to listOf("", "home", "account", "connections", "connect", "import"),
            // #1482 the layout before Vault / Config / Devices
            "personal" to listOf("", "ids", "cards", "entries", "me", "others", "autocomplete", "personal", "sites", "snippets", "wallet"),
            "data" to listOf("", "providers", "keys", "tokens", "grants", "import", "databases", "bundle", "mesh", "files", "working", "diff", "devices", "backups"),
            "configs" to listOf("settings", "setup", "runbook", "apps", "configs", "perms", "adb-shell", "android", "cloud-apps", "phone-apps", "desktop", "c3", "infra", "containers", "repos"),
        )
        for ((s, pages) in old) for (p in pages) {
            val (ns, np) = HubNav.resolve(s, p)
            val sec = MainActivity.NAV.section(ns)
            assertNotNull("$s/$p -> $ns: not a section", sec)
            assertTrue("$s/$p -> $ns/$np: not a page", np == null || sec!!.allPages().any { it.id == np })
            assertTrue("$s/$p has an explicit place", HubNav.LEGACY.containsKey(s to p))
        }
        // every LEGACY target is a declared LEAF (a container pill is no destination)
        for ((from, to) in HubNav.LEGACY) {
            val page = MainActivity.NAV.section(to.first)?.allPages()?.firstOrNull { it.id == to.second }
            assertNotNull("$from -> $to: not a declared page", page)
            assertTrue("$from -> $to: a container, not a leaf", page!!.pages.isEmpty())
        }
        // the links libs:account pages send with the old ids
        assertEquals("connect" to "adb-shell", HubNav.resolve("setup", "adb-shell"))
        assertEquals("connect" to "signin", HubNav.resolve("account", "connect"))
        assertEquals("connect" to "perms", HubNav.resolve("setup", "perms"))
        assertEquals("config" to "cloud-apps", HubNav.resolve("setup", "apps"))
        assertEquals("vault" to "cards", HubNav.resolve("personal", "wallet"))
        assertEquals("devices" to "working", HubNav.resolve("profiles", "working"))
        assertEquals("connect" to "settings", HubNav.resolve("settings", ""))
        // the 2026-10-13 ids: Home's one page became four, Bundle got its own id
        assertEquals("devices" to "me", HubNav.resolve("home", "home"))
        assertEquals("vault" to "import", HubNav.resolve("home", "import"))
        assertEquals("devices" to "bundle", HubNav.resolve("secrets", "connections"))
        assertEquals("vault" to "keys", HubNav.resolve("secrets", "secrets"))
        // a current id passes through, an unknown section opens the default
        assertEquals("vault" to "providers", HubNav.resolve("data", "providers"))
        assertEquals("vault" to "sites", HubNav.resolve("personal", "sites"))
        assertEquals("config" to "runbook", HubNav.resolve("configs", "runbook"))
        assertEquals("configs" to "about", HubNav.resolve("configs", "about"))
        assertEquals("vault" to "ids", HubNav.resolve("vault", "ids"))
        assertEquals("all" to null, HubNav.resolve("nowhere", "x"))
    }

    @Test fun strippedPagesSitUnderOneContainerEach() {
        val nav = MainActivity.NAV
        fun path(section: String, page: String) = nav.section(section)!!.path(page).map { it.id }
        assertEquals(listOf("autocomplete", "personal"), path("vault", "personal"))
        assertEquals(listOf("autocomplete", "sites"), path("vault", "sites"))
        assertEquals(listOf("secrets", "providers"), path("vault", "providers"))
        assertEquals(listOf("secrets", "entries"), path("vault", "entries"))
        assertEquals(listOf("backup", "databases"), path("devices", "databases"))
        assertEquals(listOf("backup", "bundle"), path("devices", "bundle"))
        assertEquals(listOf("backup", "me"), path("devices", "me"))
        assertEquals(listOf("peer-control"), path("devices", "peer-control"))
        assertEquals(listOf("android", "runbook"), path("config", "runbook"))
        assertEquals(listOf("android", "cloud-apps"), path("config", "cloud-apps"))
        assertEquals(listOf("android", "phone-apps"), path("config", "phone-apps"))
        assertEquals(listOf("c3", "infra"), path("config", "infra"))
        assertEquals(listOf("c3", "containers"), path("config", "containers"))
        assertEquals(listOf("fleetsetup", "adb-shell"), path("connect", "adb-shell"))
        assertEquals(listOf("status", "apps"), path("connect", "apps"))
        assertEquals(listOf("auth", "signin"), path("connect", "signin"))
        assertEquals(listOf("auth", "hub"), path("connect", "hub"))
        assertEquals(listOf("fleetsetup", "services"), path("connect", "services"))
        assertEquals(listOf("fleetsetup", "drive"), path("connect", "drive"))
        assertEquals(listOf("hub", "signin", "settings"), nav.section("connect")!!.page("auth")!!.pages.map { it.id })
        assertEquals("hub", nav.section("connect")!!.page("auth")!!.leaf().id)
        assertEquals(listOf("desktop"), path("config", "desktop"))
        // the owner's tabs, exactly and in order
        fun tabs(section: String) = nav.section(section)!!.pages.map { it.id }
        assertEquals(listOf("ids", "cards", "secrets", "autocomplete"), tabs("vault"))
        assertEquals(listOf("android", "desktop", "c3", "repos"), tabs("config"))  // #1482 the Fleet Config files, #1445 Repos
        assertEquals(listOf("general", "perms", "about"), tabs("configs"))  // #1443 the app's own Configs, not in the bottom nav
        assertEquals(listOf("mesh", "peer-control", "backup", "files", "working", "diff"), tabs("devices"))
        assertEquals(listOf("status", "auth", "fleetsetup"), tabs("connect"))
        assertEquals(listOf("activity", "fleetdata"), tabs("activity"))  // #1547 Fleet Data: the Cloud Account dataset browser
        // a container opens on its first leaf; a section opens on its first page's leaf
        assertEquals("personal", nav.section("vault")!!.page("autocomplete")!!.leaf().id)
        assertEquals(listOf("cloud-apps", "phone-apps", "runbook"), nav.section("config")!!.page("android")!!.pages.map { it.id })
        assertEquals(listOf("infra", "containers"), nav.section("config")!!.page("c3")!!.pages.map { it.id })
        assertEquals(listOf("providers", "keys", "tokens", "grants", "import", "entries"), nav.section("vault")!!.page("secrets")!!.pages.map { it.id })
        assertEquals(listOf("databases", "bundle", "me"), nav.section("devices")!!.page("backup")!!.pages.map { it.id })
        assertEquals("general", nav.section("configs")!!.page(null)!!.leaf().id)  // #1443
        assertEquals("ids", nav.section("vault")!!.page(null)!!.leaf().id)
        assertEquals("cloud-apps", nav.section("config")!!.page(null)!!.leaf().id)
        assertEquals("mesh", nav.section("devices")!!.page(null)!!.leaf().id)
        // one place per thing: no leaf id repeats inside a section
        for (s in nav.sections) {
            val ids = s.allPages().filter { it.pages.isEmpty() }.map { it.id }
            assertEquals("${s.id}: a leaf id repeats", ids.size, ids.toSet().size)
            assertEquals("${s.id}: a page id repeats", s.allPages().size, s.allPages().map { it.id }.toSet().size)
        }
        assertTrue(nav.problems().isEmpty())
    }

    @Test fun openIntentDeepLinksResolveThroughTheSameMap() {
        fun i(section: String?, page: String?) = android.content.Intent("com.diegonmarcos.cloudaccount.OPEN").apply {
            if (section != null) putExtra(MainActivity.EXTRA_SECTION, section)
            if (page != null) putExtra(MainActivity.EXTRA_PAGE, page)
        }
        assertNull("no extras = the default", MainActivity.deepLink(i(null, null)))
        assertNull(MainActivity.deepLink(null))
        assertEquals("connect" to "adb-shell", MainActivity.deepLink(i("setup", "adb-shell")))
        assertEquals("devices" to "bundle", MainActivity.deepLink(i("secrets", "connections")))
        assertEquals("devices" to "me", MainActivity.deepLink(i("home", "")))
        assertEquals("vault" to "snippets", MainActivity.deepLink(i("account", "snippets")))
        assertEquals("all" to null, MainActivity.deepLink(i("bogus", "x")))
    }

    // ── Connections table ────────────────────────────────────────────────

    @Test fun connectionsMatrixIsBuiltFromStoredAuthState() {
        val v = AccountVault(ctx)
        v.putConnection("forge.github.token", "fake-github-value")
        v.putConnection("fleet.bearer", "fake-fleet-value")
        v.putConnection("fleet.bearer_expires", "2020-01-01")
        v.putConnection("cloudflare.oauth_session", fakeJwt(now.epochSecond + 3600))
        v.putConnection("cloudflare.api_token", fakeJwt(now.epochSecond - 60))
        v.putConnection("openrouter.api_key", "fake-openrouter-value")
        v.putConnection("dns.preset", "fake-preset")
        v.putConnection("device.id", "fake-device")

        val rows = Providers.matrix(AccountVault(ctx).connections(), now).associateBy { it.provider.id }
        assertTrue(rows.keys.containsAll(listOf("cloudflare", "google", "github", "cloud-authelia", "openrouter")))
        assertFalse("nothing stored for Gitea or Mail: no row", rows.containsKey("gitea") || rows.containsKey("mail"))
        fun cell(p: String, m: AuthMethod) = rows.getValue(p).cells.getValue(m).status
        assertEquals(CellStatus.CONNECTED, cell("github", AuthMethod.API_TOKEN))
        assertEquals(CellStatus.MISSING, cell("github", AuthMethod.SSH))
        assertEquals(CellStatus.MISSING, cell("google", AuthMethod.WEB_OAUTH))
        assertEquals(CellStatus.NOT_USED, cell("google", AuthMethod.SSH))
        assertEquals(CellStatus.EXPIRED, cell("cloud-authelia", AuthMethod.API_TOKEN))
        assertEquals(CellStatus.CONNECTED, cell("cloudflare", AuthMethod.WEB_OAUTH))
        assertEquals("a JWT past its exp is expired", CellStatus.EXPIRED, cell("cloudflare", AuthMethod.API_TOKEN))
        assertEquals(CellStatus.CONNECTED, cell("openrouter", AuthMethod.API_TOKEN))
        val all = Providers.secrets(AccountVault(ctx).connections(), now).values.flatten().map { it.path }
        assertFalse("non-credentials are not secrets", all.any { it == "dns.preset" || it == "device.id" || it.endsWith("_expires") })
    }

    // ── token import ─────────────────────────────────────────────────────

    @Test fun parsesEnvFileWithExportQuotesCommentsAndExpiry() {
        val r = TokenImport.parse("""
            # tokens for the phone
            export GITHUB_TOKEN="fake-github-value"
            CF_API_TOKEN='fake-cloudflare-value'
            CF_API_TOKEN_EXPIRES=2027-01-31
            CLOUDFLARE_ACCOUNT_ID=fake-account
            OPENROUTER_API_KEY=fake-openrouter-value # trailing note
        """.trimIndent())
        assertEquals("env", r.format)
        assertEquals(1, r.skipped) // the account id is not a credential
        val by = r.rows.associateBy { it.name }
        assertEquals("github", by.getValue("GITHUB_TOKEN").provider)
        assertEquals("forge.github.token", by.getValue("GITHUB_TOKEN").path)
        assertEquals("fake-github-value", by.getValue("GITHUB_TOKEN").value)
        assertEquals("cloudflare", by.getValue("CF_API_TOKEN").provider)
        assertEquals("cloudflare.api_token", by.getValue("CF_API_TOKEN").path)
        assertTrue(by.getValue("CF_API_TOKEN").expires.startsWith("2027-02-01"))
        assertEquals("fake-openrouter-value", by.getValue("OPENROUTER_API_KEY").value)
        assertEquals("openrouter.api_key", by.getValue("OPENROUTER_API_KEY").path)
    }

    @Test fun parsesPlainKeyValue() {
        val r = TokenImport.parse("gitea_token=fake-gitea-value\nfleet_bearer=fake-fleet-value\nnote=hello")
        assertEquals("key=value", r.format)
        assertEquals(listOf("forge.gitea.token", "fleet.bearer"), r.rows.map { it.path })
        assertEquals(1, r.skipped)
    }

    @Test fun parsesJsonTokenListAndJsonObject() {
        val list = TokenImport.parse("""{"tokens":[
            {"provider":"Google","type":"oauth","name":"refresh_token","value":"fake-google-value","expires":"2027-03-01T00:00:00Z"},
            {"provider":"github","type":"ssh","name":"github_ssh_key","value":"fake-ssh-value"},
            {"name":"no value here"}]}""")
        assertEquals("json", list.format)
        assertEquals(1, list.skipped)
        assertEquals(AuthMethod.WEB_OAUTH, list.rows[0].type)
        assertEquals("google", list.rows[0].provider)
        assertEquals("2027-03-01T00:00:00Z", list.rows[0].expires)
        assertEquals(AuthMethod.SSH, list.rows[1].type)

        val obj = TokenImport.parse("""{"cloudflare":{"api_token":"fake-cloudflare-value","api_token_expires":"2027-01-01"},"dns":{"preset":"x"}}""")
        assertEquals(listOf("cloudflare.api_token"), obj.rows.map { it.path })
        assertTrue(obj.rows[0].expires.isNotEmpty())
    }

    @Test fun maskedDisplayNeverShowsTheValue() {
        val row = TokenImport.parse("GITHUB_TOKEN=fake-github-value").rows.single()
        assertFalse(row.masked.contains("fake"))
        assertFalse(row.masked.contains("value"))
        assertTrue(row.masked.startsWith("sha256:"))
        assertFalse("a row prints masked", row.toString().contains("fake-github-value"))
    }

    @Test fun importAllStoresSelectedRowsInTheAccountVault() {
        val rows = TokenImport.parse("GITHUB_TOKEN=fake-github-value\nCF_API_TOKEN=fake-cloudflare-value\nCF_API_TOKEN_EXPIRES=2027-01-01\nOPENROUTER_API_KEY=fake-openrouter-value").rows
            .map { if (it.name == "OPENROUTER_API_KEY") it.copy(selected = false) else it }
        val v = AccountVault(ctx)
        val n = TokenImport.importAll(rows) { p, x -> v.putConnection(p, x) }
        assertEquals(2, n)
        assertEquals("fake-github-value", v.connection("forge.github.token"))
        assertEquals("fake-cloudflare-value", v.connection("cloudflare.api_token"))
        assertNotNull(v.connection("cloudflare.api_token_expires"))
        assertNull("a deselected row is not imported", v.connection("openrouter.api_key"))
        // and the Connections table sees what was imported
        val m = Providers.matrix(v.connections(), now).associateBy { it.provider.id }
        assertEquals(CellStatus.CONNECTED, m.getValue("github").cells.getValue(AuthMethod.API_TOKEN).status)
    }

    // ── reveal + copy ────────────────────────────────────────────────────

    @Test fun revealRequiresDeviceAuth() {
        var reads = 0; var shown: String? = null; var refused = 0
        val read = { reads++; "fake-secret-value" }
        RevealGate(DeviceAuth { _, done -> done(false) }).reveal("test", read, { shown = it }, { refused++ })
        assertEquals("refused auth never reads the value", 0, reads)
        assertNull(shown); assertEquals(1, refused)
        RevealGate(DeviceAuth { _, done -> done(true) }).reveal("test", read, { shown = it }, { refused++ })
        assertEquals(1, reads); assertEquals("fake-secret-value", shown)
    }

    @Test fun phoneWithoutScreenLockRefusesReveal() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        var launched = false; var result: Boolean? = null
        SystemDeviceAuth(activity) { _, _ -> launched = true }.authenticate("test") { result = it }
        assertEquals(false, result)
        assertFalse(launched)
    }

    @Test fun copiedSecretIsSensitiveAndClearsItself() {
        val cm = ctx.getSystemService(ClipboardManager::class.java)
        var pending: (() -> Unit)? = null; var delay = 0L
        SecretClipboard.copy(ctx, "fake-secret-value") { ms, r -> delay = ms; pending = r }
        assertEquals(SecretClipboard.CLEAR_AFTER_MS, delay)
        assertEquals("fake-secret-value", cm.primaryClip?.getItemAt(0)?.text?.toString())
        assertTrue(cm.primaryClipDescription!!.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
        pending!!()
        assertTrue(cm.primaryClip?.getItemAt(0)?.text.isNullOrEmpty())

        // something copied since is left alone
        SecretClipboard.copy(ctx, "fake-secret-value") { _, r -> pending = r }
        cm.setPrimaryClip(android.content.ClipData.newPlainText("mine", "kept"))
        pending!!()
        assertEquals("kept", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }

    // ── Setup ────────────────────────────────────────────────────────────

    @Test fun providerSetupTestsWithTheStoredCredential() {
        val v = AccountVault(ctx)
        v.putConnection("forge.github.token", "fake-github-value")
        val calls = ArrayList<Pair<String, String>>()
        val gh = Providers.byId("github")
        val ok = ProviderSetup(gh, { v.connections() }, Probe { u, a -> calls += u to a; 200 }, { now }).run()
        assertTrue(ok.line, ok.ok)
        assertEquals(listOf(StepKind.LOGIN, StepKind.ENDPOINT, StepKind.TEST), ok.steps.map { it.kind })
        assertEquals(gh.probeUrl, calls.single().first)
        assertEquals("Bearer fake-github-value", calls.single().second)
        assertFalse("a result line never carries the value", ok.steps.any { it.line.contains("fake-github-value") })

        val refused = ProviderSetup(gh, { v.connections() }, Probe { _, _ -> 401 }, { now }).run()
        assertFalse(refused.ok); assertTrue(refused.line.contains("401"))

        calls.clear()
        val missing = ProviderSetup(Providers.byId("google"), { v.connections() }, Probe { u, a -> calls += u to a; 200 }, { now }).run()
        assertFalse(missing.ok); assertTrue(calls.isEmpty())

        v.putConnection("cloudflare.api_token", "fake-cloudflare-value"); v.putConnection("cloudflare.api_token_expires", "2020-01-01")
        val expired = ProviderSetup(Providers.byId("cloudflare"), { v.connections() }, Probe { u, a -> calls += u to a; 200 }, { now }).run()
        assertFalse(expired.ok); assertTrue(expired.line.contains("re-auth")); assertTrue("an expired credential is not sent", calls.isEmpty())
    }

    @Test fun appSetupRunsLoginEndpointTestAndSkipsWhatIsNotInstalled() {
        var pushes = 0
        val missing = AppSetup("app:x", "X", { false }, { emptyList() }, { pushes++; true to "pushed" }).run()
        assertFalse(missing.ok); assertEquals(0, pushes); assertEquals(StepKind.LOGIN, missing.steps.single().kind)
        val done = AppSetup("app:y", "Y", { true }, { listOf("y_prefs.server_url") }, { pushes++; true to "3 key(s)" }).run()
        assertTrue(done.ok); assertEquals(1, pushes); assertEquals(3, done.steps.size)
        assertTrue(done.steps[1].line.contains("server_url"))
    }

    @Test fun setupAllRunsEveryTargetInOrderPastFailures() {
        val ran = ArrayList<String>()
        fun t(id: String, body: () -> Boolean) = object : SetupTarget {
            override val id = id; override val label = id
            override fun run(): SetupResult { ran += id; return SetupResult(id, id, listOf(StepResult(StepKind.TEST, body(), "done"))) }
        }
        val seen = ArrayList<String>()
        val out = SetupAll.run(listOf(
            t("provider:a") { true },
            t("provider:b") { false },
            t("app:c") { throw IllegalStateException("fake-secret-value") },
            t("app:d") { true },
        )) { seen += it.id }
        assertEquals(listOf("provider:a", "provider:b", "app:c", "app:d"), ran)
        assertEquals(ran, seen)
        assertEquals(listOf(true, false, false, true), out.map { it.ok })
        assertFalse("an exception's message never reaches the result", out[2].line.contains("fake-secret-value"))
    }

    // ── per-app profiles ─────────────────────────────────────────────────

    @Test fun appProfileRowReportsStateWithoutValues() {
        val cfg = JSONObject("""{"mail_jmap_prefs":{"mail_jmap_prefs":{"server_url":"https://mail.example.invalid","password":"fake-mail-value","auto_sync":true}}}""")
        val r = AppProfiles.row(AppProfiles.App("mail", "Mail", "com.example.mail"), true, cfg, 2)
        assertEquals(true, r.signedIn)
        assertEquals("signed in", r.state)
        assertEquals("https://mail.example.invalid", r.endpoint)
        assertEquals(listOf("auto_sync=true"), r.sync)
        assertFalse("a credential is reported as present, never carried", r.toString().contains("fake-mail-value"))
        assertEquals("not installed", AppProfiles.row(AppProfiles.App("x", "X", "p"), false, null, 0).state)
    }

    // ── Cloud Chat's setup schema (#991) ─────────────────────────────────

    /** The fleet manifest as the SuperApp bakes it: the file in the tree when the test runs there, else this APK's. */
    private fun fleetManifest(): FleetPolicy.Manifest {
        val f = File("../../ab_cloud-libs-shared/libs/fleetconfig-model/src/main/assets/fleet-config.json")
        return if (f.exists()) FleetPolicy.Manifest(JSONObject(f.readText())) else FleetPolicy.manifest(ctx)
    }

    @Test fun cloudChatHasASetupSchema() {
        // Profiles ▸ Apps lists the fleet's apps: Cloud Chat is one of them
        assertTrue("Cloud Chat is a fleet app", AccountFleet.fleetApps().any { it.id == "chat" })
        val m = fleetManifest()
        val chat = m.apps.getValue("chat")
        assertEquals("com.diegonmarcos.comms.chat", chat.pkg)
        val store = m.stores.getValue("chat_setup")
        assertEquals("prefs", store.kind)
        assertTrue("ac_cloud-chat" in store.usedBy)
        // the schema: Cloud Account's chat.server_url / chat.server_name land in chat_setup, nothing else is pushed
        val plan = SetupPlan.build(m, JSONObject("""{"chat":{"server_url":"https://chat.example.invalid","server_name":"Cloud"}}"""), null)
        val ap = plan.of("chat")
        assertNotNull("the plan has an entry for Cloud Chat", ap)
        assertEquals(setOf("chat_setup.server_url", "chat_setup.server_name"), ap!!.items.map { "${it.store}.${it.key}" }.toSet())
        assertEquals("com.diegonmarcos.comms.chat", ap.items.first().pkg)
        // its Setup: login = installed, endpoint = the server link, test = the push's answer
        val endpoints = ap.items.filter { it.key.endsWith("url") }.map { "${it.store}.${it.key}" }
        val done = AppSetup("app:chat", "Cloud Chat", { true }, { endpoints }, { true to "2 key(s)" }).run()
        assertTrue(done.line, done.ok)
        assertEquals("chat_setup.server_url", done.steps[1].line)
        val absent = AppSetup("app:chat", "Cloud Chat", { false }, { endpoints }, { true to "x" }).run()
        assertFalse(absent.ok)
        // and the row Profiles ▸ Apps draws once the link is captured: configured, endpoint shown, no credential
        val captured = JSONObject("""{"chat_setup":{"chat_setup":{"server_url":"https://chat.example.invalid","server_name":"Cloud"}}}""")
        val row = AppProfiles.row(AppProfiles.App("chat", "Cloud Chat", chat.pkg), true, captured, ap.items.size)
        assertEquals("configured", row.state)
        assertEquals("https://chat.example.invalid", row.endpoint)
        assertEquals(listOf("chat_setup"), row.stores)
        assertEquals(2, row.planned)
    }

    // ── nothing secret in a log ──────────────────────────────────────────

    @Test fun noSecretReachesTheLog() {
        val v = AccountVault(ctx)
        val rows = TokenImport.parse("GITHUB_TOKEN=fake-logged-value").rows
        TokenImport.importAll(rows) { p, x -> v.putConnection(p, x) }
        Providers.matrix(v.connections(), now)
        ProviderSetup(Providers.byId("github"), { v.connections() }, Probe { _, _ -> 200 }, { now }).run()
        RevealGate(DeviceAuth { _, done -> done(true) }).reveal("t", { v.connection("forge.github.token")?.toString() }, {})
        assertFalse(ShadowLog.getLogs().any { it.msg?.contains("fake-logged-value") == true })

        val dir = File("src/main/java/com/diegonmarcos/cloudaccount/hub")
        val files = dir.listFiles { f -> f.name.endsWith(".kt") }.orEmpty()
        assertTrue("hub sources found from ${File(".").absolutePath}", files.isNotEmpty())
        for (f in files) {
            val code = f.readText().lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n")
            for (bad in listOf("Log.", "println(", "printStackTrace", "revealAiKey(")) assertFalse("${f.name} uses $bad", code.contains(bad))
        }
    }
}
