package app.sterna.ui.settings

import app.sterna.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1814 FIELD-PARITY TEST for the Configs reorganisation (rule 16: a reorg moves and renames, it never removes).
 *
 * Cloud Mail had TWO settings menus: (A) the app's own settings hub and its NavHost, (B) the fleet's Configs strip
 * (General | Agents, Perms, Logcat, X-Ray App, About). Both are now ONE vertical tree ([SETTINGS_TREE]): Accounts, Text Enhancer, UI, App.
 * This file ENUMERATES every page, route and setting of BOTH previous menus (the lists below are the previous version, written out) and
 * proves each is reachable in the new tree. It fails when one is dropped, and [every dropped item is detected] proves THAT, by removing
 * each item in turn and demanding the check name it (the mutation proof).
 */
class SettingsTreeParityTest {

    // --- menu A, the previous settings hub + NavHost -----------------------------------------------------------------------------

    /** Every route of the previous settings graph (the hub's start destination included). */
    private val oldRoutes = listOf(
        "hub", "accounts", "addAccount", "account/{id}", "sharedAccount/{id}",
        "appearance", "reading", "notifications", "vacation", "filters", "alertAccounts",
        "privacy", "storage", "update", "backup",
        "textAiRouting", "textEnhance", "textResume", "textAnswer", "textTranslation",
    )

    /** The rows of the previous hub (route they opened, or the in-place content / URL). */
    private val oldHubRows = listOf(
        "accounts", "alertAccounts",                                                           // top rows
        "textAiRouting", "textEnhance", "textResume", "textAnswer", "textTranslation",         // Text section (TEXT_TOOLS_ENTRIES)
        "appearance", "reading", "notifications", "privacy", "storage", "backup", "update",    // App section
        "inline:navigation",                                                                   // Navigation section (the card)
        "vacation", "filters",                                                                 // This account section
        "url:version", "url:source", "url:license", "url:author",                              // About section
    )

    /** The sections of one account's detail screen, and the focus page each now lives on (every one is also on the full "account/{id}" screen). */
    private val oldAccountSections = mapOf(
        "settings_account_section" to FOCUS_OTHERS,
        "settings_account_colour_section" to FOCUS_OTHERS,
        "settings_account_notifications_section" to FOCUS_OTHERS,
        "settings_server_settings_section" to FOCUS_SERVER,
        "gw_title" to FOCUS_SERVER,
        "ep_title" to FOCUS_SERVER,
        "settings_protocol_section" to FOCUS_SERVER,
        "settings_identities_section" to FOCUS_SIGNATURES,
        "settings_sync_section" to FOCUS_FOLDERS,
        "settings_pgp_section" to FOCUS_OTHERS,
        "settings_storage_section" to FOCUS_OTHERS,
    )

    // --- menu B, the fleet Configs strip --------------------------------------------------------------------------------------

    private val oldFleetPages = listOf("general", "agents", "perms", "logcat", "xray", "about")

    /** Everything both menus had, as the keys [settingsTreeReach] holds. */
    private fun expectedKeys(): Set<String> =
        (oldRoutes.filter { it != "hub" && !it.contains("{") } + oldHubRows + oldFleetPages.map { "fleet/$it" } +
            oldAccountSections.values.toSet().map { "accountPage/$it" } + "accounts").toSet()

    /** The previous items [tree] no longer reaches. Empty = parity. */
    private fun gaps(tree: List<TreeNode>): Set<String> = expectedKeys() - settingsTreeReach(tree)

    private fun TreeNode.without(route: String): TreeNode? =
        if (this.route == route) null else TreeNode(id, kind, this.route, title, icon, summary, group, children.mapNotNull { it.without(route) })

    private fun without(route: String): List<TreeNode> = SETTINGS_TREE.mapNotNull { it.without(route) }

    @Test
    fun `every page route and setting of both previous menus is reachable in the tree`() {
        assertEquals("previous items the tree no longer reaches", emptySet<String>(), gaps(SETTINGS_TREE))
    }

    /** THE MUTATION PROOF: drop each previous item from the tree in turn; the parity check must name it. */
    @Test
    fun `every dropped item is detected`() {
        val undetected = expectedKeys().filter { key -> key !in gaps(without(key)) }
        assertEquals("dropping these from the tree went unnoticed", emptyList<String>(), undetected)
        assertTrue(expectedKeys().size >= 30)
    }

    /** Every route the tree points at is registered in the graph, so a row cannot open nothing. */
    @Test
    fun `every route of the tree is a registered destination`() {
        val sources = listOf(SCREEN, TREE).joinToString("\n") { it.readText() }
        val registered = Regex("""composable\("([^"]+)"\)""").findAll(sources).map { it.groupValues[1] }.toList()
        val matchers = registered.map { r -> Regex("^" + r.split(Regex("""\{[^}]+\}""")).joinToString("[^/]+") { Regex.escape(it) } + "$") }
        val dangling = SETTINGS_TREE.asSequence().flatMap { it.walk() }
            .filter { it.kind != TreeKind.URL && it.kind != TreeKind.INLINE && it.kind != TreeKind.SELECTOR }
            .map { it.route }.filter { r -> matchers.none { it.matches(r) } }.toList()
        assertEquals("tree routes with no composable()", emptyList<String>(), dangling)
        for (r in oldRoutes) assertTrue("the old route '$r' is no longer registered", r in registered)
    }

    /** The per-account sections each sit under exactly the focus page the map says, and the full screen (focus null) still draws them all. */
    @Test
    fun `every section of the account screen sits on one focus page`() {
        val lines = SCREEN.readLines()
        val found = oldAccountSections.keys.associateWith { key ->
            val at = lines.indexOfFirst { it.trim() == "SettingsSection(stringResource(R.string.$key)) {" }
            assertTrue("the account screen lost its section $key", at >= 0)
            val show = (at downTo 0).firstNotNullOfOrNull { i -> Regex("""^\s*if \(show\((FOCUS_\w+)\)\) \{$""").find(lines[i])?.groupValues?.get(1) }
            show?.let { FOCUS_NAMES[it] }
        }
        assertEquals(oldAccountSections, found)
        assertTrue("the focus filter must show everything when no focus is given", "fun show(page: String) = focus == null || focus == page" in SCREEN.readText())
    }

    @Test
    fun `top level order is Accounts, Text Enhancer, UI, App`() {
        assertEquals(listOf("accounts", "text", "ui", "app"), SETTINGS_TREE.map { it.id })
        assertEquals(
            listOf(R.string.settings_accounts_title, R.string.settings_tree_text_enhancer, R.string.settings_tree_ui, R.string.settings_group_app),
            SETTINGS_TREE.map { it.title },
        )
        val strings = File(RES, "values/strings.xml").readText()
        assertEquals(listOf("Accounts", "Text Enhancer", "UI", "App"), listOf("settings_accounts_title", "settings_tree_text_enhancer", "settings_tree_ui", "settings_group_app").map { stringOf(strings, it) })
    }

    @Test
    fun `accounts sub-pages are Server, Signatures, Folders, Filter, Vacation Answer, Others`() {
        val accounts = SETTINGS_TREE.first { it.id == "accounts" }
        // #1828 the account selector is the first row; the six sub-pages follow it, unchanged.
        assertEquals(listOf("accountSelector", "server", "signatures", "folders", "filter", "vacation", "accountsOthers"), accounts.children.map { it.id })
        val strings = File(RES, "values/strings.xml").readText()
        assertEquals(
            listOf("Server", "Signatures", "Folders", "Filter", "Vacation Answer", "Others"),
            listOf("settings_tree_server", "settings_tree_signatures", "settings_tree_folders", "settings_tree_filter", "settings_vacation_title", "settings_tree_others").map { stringOf(strings, it) },
        )
        assertEquals(accounts.children.drop(1).map { it.title }, listOf(R.string.settings_tree_server, R.string.settings_tree_signatures, R.string.settings_tree_folders, R.string.settings_tree_filter, R.string.settings_vacation_title, R.string.settings_tree_others))
        // Others holds what fits none of the first five: the accounts list (shared accounts, per-account notifications), add account, Alert accounts, per-account settings
        assertEquals(setOf("accounts", "addAccount", "alertAccounts", "accountPage/others"), accounts.children.last().children.map { it.route }.toSet())
    }

    @Test
    fun `text enhancer holds every text tool, UI holds appearance reading and navigation, App holds the old App section and every fleet page`() {
        assertEquals(TEXT_TOOLS_ENTRIES.map { it.route }, SETTINGS_TREE.first { it.id == "text" }.children.map { it.route })
        assertEquals(listOf("appearance", "reading", "inline:navigation"), SETTINGS_TREE.first { it.id == "ui" }.children.map { it.route })
        val app = SETTINGS_TREE.first { it.id == "app" }.children.map { it.route }
        assertEquals(listOf("notifications", "privacy", "storage", "backup", "update"), app.take(5))
        assertEquals(oldFleetPages.map { "fleet/$it" }, app.filter { it.startsWith("fleet/") })
        // #1828 the app's own About rows now live on the ONE About page (fleet/about), drawn above the fleet's About body.
        val about = SETTINGS_TREE.first { it.id == "app" }.children.first { it.route == "fleet/about" }
        assertEquals(listOf("url:version", "url:source", "url:license", "url:author"), about.children.map { it.route })
        assertEquals(about.children, ABOUT_ROWS)
        assertTrue("App lists no About row of its own any more", app.none { it.startsWith("url:") })
        val tree = TREE.readText()
        assertTrue("the About page draws the app rows", tree.contains("if (id == \"about\") {") && tree.contains("ABOUT_ROWS.forEach { AboutRow(it)"))
    }

    // --- #1828 the unfolded hub, the account selector, Navigation in the page's style ------------------------------------------

    @Test
    fun `the hub is unfolded - every section is a heading named after it with its pages as rows`() {
        val tree = TREE.readText()
        val hub = tree.substring(tree.indexOf("internal fun SettingsTreePage("), tree.indexOf("internal fun accountRouteFor("))
        assertTrue(hub.contains("SETTINGS_TREE.forEach { section ->"))
        assertTrue(hub.contains("SettingsSection(stringResource(section.title)) {"))
        assertTrue("a nested section is a sub-heading with its rows", hub.contains("SettingsSection(stringResource(child.title)) { child.children.forEach { row(it) } }"))
        assertTrue("section pages still draw their groups", hub.contains("\"configs\" -> stringResource(R.string.settings_tree_configs)"))
        assertTrue("tree/<id> pages stay registered", tree.contains("composable(\"tree/{section}\")"))
    }

    @Test
    fun `account pages open the selected account, the picker only when none is selected`() {
        val a = app.sterna.core.data.account.StoredAccount(id = "a1", server = "s", username = "me@x.example")
        val server = SETTINGS_TREE.first { it.id == "accounts" }.children.first { it.id == FOCUS_SERVER }
        assertEquals("accountFocus/$FOCUS_SERVER/a1", accountRouteFor(server, "a1", listOf(a)))
        assertEquals("no account selected: the picker", "accountPage/$FOCUS_SERVER", accountRouteFor(server, null, listOf(a)))
        assertEquals("a stale selection: the picker", "accountPage/$FOCUS_SERVER", accountRouteFor(server, "gone", listOf(a)))
        val filter = SETTINGS_TREE.first { it.id == "accounts" }.children.first { it.id == "filter" }
        assertEquals("a non-account page keeps its route", "filters", accountRouteFor(filter, "a1", listOf(a)))
        assertTrue("the picker route is still registered", TREE.readText().contains("composable(\"accountPage/{focus}\")"))
    }

    @Test
    fun `the selector picks the current account and offers Add account and Manage accounts`() {
        val tree = TREE.readText()
        val sel = tree.substring(tree.indexOf("private fun AccountSelectorRow("), tree.indexOf("private fun NavigationModeRows("))
        assertTrue(sel.contains("onSelect(a.id)"))
        assertTrue(sel.contains("onOpen(\"addAccount\")"))
        assertTrue(sel.contains("onOpen(\"accounts\")"))
        assertTrue(tree.contains("onSelectAccount = { id -> accountsViewModel.switchTo(id); onAccountsChanged() }"))
    }

    @Test
    fun `Navigation is drawn with the page's own rows, every option and the default kept`() {
        val tree = TREE.readText()
        val nav = tree.substring(tree.indexOf("private fun NavigationModeRows("))
        assertTrue("the fleet card (its own palette) is no longer drawn here", "NavModeConfigCard(" !in tree.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n"))
        assertTrue(nav.contains("SettingChoiceRow("))
        assertTrue("all three modes", nav.contains("options = SecondRowMode.entries"))
        assertTrue("the declared one is marked", nav.contains("if (m == declared) \" (\$default)\""))
        assertTrue("choosing the default clears the override", nav.contains("store.setMode(section.id, if (m == declared) null else m)"))
        assertTrue("the same sections the card offered", nav.contains("it.allPages().size > 1 || it.id in NAV_DYNAMIC_SECTIONS"))
        assertEquals(setOf("mail"), NAV_DYNAMIC_SECTIONS)
    }

    /** The fleet pages are the lib's own: FleetConfigsExtensions.TAIL_IDS (General first), drawn through the lib's page body (no copy). */
    @Test
    fun `the fleet pages are the shared ones, reused not copied`() {
        val ext = File(ROOT.parentFile, "ab_cloud-libs-shared/libs/ui-nav-bars/src/main/kotlin/com/diegonmarcos/superapp/fleetconfigs/FleetConfigsExtensions.kt")
        assertTrue("cannot read ${ext.path}", ext.isFile)
        val tail = Regex("""TAIL_IDS: List<String> = listOf\(([^)]*)\)""").find(ext.readText())!!.groupValues[1]
            .let { Regex(""""([a-z_]+)"""").findAll(it).map { m -> m.groupValues[1] }.toList() }
        assertEquals(tail, oldFleetPages)
        assertEquals(oldFleetPages, FLEET_PAGE_IDS)
        val tree = TREE.readText()
        for (needle in listOf("FleetConfigsPageBody(", "isFleetConfigsPage(", "FleetConfigsExtensions.withTail(")) assertTrue("SettingsTree.kt no longer uses $needle", needle in tree)
    }

    /** Every entry point lands in the tree: the bottom-nav Configs button, the old "settings" route, the drawer, the launcher shortcuts. */
    @Test
    fun `every entry point lands in the tree`() {
        val bar = File(ROOT, "app/src/main/kotlin/app/sterna/ui/MailBottomNav.kt").readText()
        assertTrue("the Configs button must open the settings route", Regex("""onConfigs = \{[^\n]*nav\.navigate\("settings"\)""").containsMatchIn(bar))
        assertTrue("the horizontal fleet strip is no longer drawn for Cloud Mail", "FleetConfigsScreen(" !in bar.lines().filterNot { it.trim().startsWith("//") }.joinToString("\n"))
        val app = File(ROOT, "app/src/main/kotlin/app/sterna/ui/SternaApp.kt").readText()
        assertTrue(app.contains("route = \"settings?accountId={accountId}&section={section}\""))
        assertTrue(app.contains("initialSection = entry.arguments?.getString(\"section\")"))
        val manifest = File(ROOT, "app/src/main/AndroidManifest.xml").readText()
        assertTrue("the lib's Configs activity must be aliased to MainActivity", manifest.contains("<activity-alias") && manifest.contains("android:targetActivity=\".MainActivity\""))
        assertTrue(SCREEN.readText().contains("settingsTreeDestinations(nav, accountsViewModel, onBack, onAccountsChanged)"))
        assertTrue("\"hub\" is the start destination and is registered by the tree", TREE.readText().contains("composable(\"hub\")"))
    }

    private fun stringOf(xml: String, key: String): String =
        Regex("""<string name="$key">(.*?)</string>""").find(xml)!!.groupValues[1]

    private companion object {
        val FOCUS_NAMES = mapOf("FOCUS_SERVER" to FOCUS_SERVER, "FOCUS_SIGNATURES" to FOCUS_SIGNATURES, "FOCUS_FOLDERS" to FOCUS_FOLDERS, "FOCUS_OTHERS" to FOCUS_OTHERS)

        /** The module directory (ac_cloud-mail), found by walking up from the working directory. */
        val ROOT: File by lazy {
            generateSequence(File("").absoluteFile) { it.parentFile }
                .firstOrNull { File(it, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt").isFile }
                ?: error("cannot locate the module root from ${File("").absolutePath}")
        }
        val RES: File by lazy { File(ROOT, "app/src/main/res") }
        val SCREEN: File by lazy { File(ROOT, "app/src/main/kotlin/app/sterna/ui/settings/SettingsScreen.kt") }
        val TREE: File by lazy { File(ROOT, "app/src/main/kotlin/app/sterna/ui/settings/SettingsTree.kt") }
    }
}
