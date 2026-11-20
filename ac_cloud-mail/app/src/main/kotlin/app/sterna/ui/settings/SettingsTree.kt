package app.sterna.ui.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material.icons.filled.BeachAccess
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FilterAlt
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.RssFeed
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.sterna.BuildConfig
import app.sterna.R
import app.sterna.core.data.account.StoredAccount
import app.sterna.ui.mailNav
import app.sterna.ui.navigateOnce
import app.sterna.ui.rememberLeaveOnce
import app.sterna.ui.theme.LocalSternaDarkTheme
import app.sterna.ui.theme.MailMetrics
import com.diegonmarcos.superapp.bottomnav.LocalNavModeStore
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.rememberNavModePrefs
import com.diegonmarcos.superapp.navbartop2.navModeLabel
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions
import com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody
import com.diegonmarcos.superapp.fleetconfigs.isFleetConfigsPage

/**
 * #1814 Cloud Mail's Configs is ONE vertical tree: Accounts, Text Enhancer, UI, App. It replaces the two menus Mail had (the app's own
 * settings hub and the fleet's horizontal General | Agents, Perms, Logcat, X-Ray App, About strip) for Cloud Mail only; the other apps
 * keep the shared strip. REORGANISATION, never a reduction (rule 16): every page and setting of both old menus is a node of this tree
 * or a section of a page it opens. SettingsTreeParityTest enumerates both old menus and fails when one is not reachable.
 *
 * The tree is DATA: the hub and the section pages draw it, the test walks it.
 */
internal enum class TreeKind {
    /** A page that lists its children (a route `tree/<id>`). */
    SECTION,
    /** A screen of the settings graph, opened by its route. */
    PAGE,
    /** An account picker (route `accountPage/<focus>`); the account it opens shows only the [focus] sections. */
    ACCOUNT_PAGE,
    /** A shared Configs page of the fleet (route `fleet/<id>`), drawn by the lib's FleetConfigsPageBody. */
    FLEET,
    /** A row that opens a URL (the About rows). */
    URL,
    /** Content drawn in place on the page (the Navigation card). */
    INLINE,
    /** #1828 the account selector row: which account every account page of the tree opens; it also adds and manages accounts. */
    SELECTOR,
}

internal class TreeNode(
    val id: String,
    val kind: TreeKind,
    val route: String,
    @StringRes val title: Int,
    val icon: ImageVector,
    @StringRes val summary: Int = 0,
    /** Consecutive nodes of one group sit under one heading: "" none, "text", "app", "account" (this account), "configs", "about". */
    val group: String = "",
    val children: List<TreeNode> = emptyList(),
)

/** The sections of one account's detail screen a picker page shows (the rest are hidden there, all of them are on the "Accounts" list's full screen too). */
internal const val FOCUS_SERVER = "server"
internal const val FOCUS_SIGNATURES = "signatures"
internal const val FOCUS_FOLDERS = "folders"
internal const val FOCUS_OTHERS = "others"

/** The fleet Configs pages, in the lib's strip order: General, then FleetConfigsExtensions.TAIL_IDS (Agents, Perms, Logcat, X-Ray App, About). */
internal val FLEET_PAGE_IDS = listOf("general", "agents", "perms", "logcat", "xray", "about")

private fun fleetNode(id: String, icon: ImageVector) =
    TreeNode("fleet_$id", TreeKind.FLEET, "fleet/$id", 0, icon, group = "configs")

private fun urlNode(id: String, @StringRes title: Int, icon: ImageVector) =
    TreeNode("about_$id", TreeKind.URL, "url:$id", title, icon, group = "about")

private fun page(id: String, route: String, @StringRes title: Int, icon: ImageVector, @StringRes summary: Int, group: String = "") =
    TreeNode(id, TreeKind.PAGE, route, title, icon, summary, group)

private fun accountPage(focus: String, @StringRes title: Int, icon: ImageVector) =
    TreeNode(focus, TreeKind.ACCOUNT_PAGE, "accountPage/$focus", title, icon, R.string.settings_tree_pick_account)

/** The app's own About rows, drawn on the merged About page (fleet/about). Declared BEFORE [SETTINGS_TREE], which uses it (top-level vals initialise in file order). */
internal val ABOUT_ROWS: List<TreeNode> = listOf(
    urlNode("version", R.string.settings_about_version, Icons.Filled.Info),
    urlNode("source", R.string.settings_about_source, Icons.Filled.Code),
    urlNode("license", R.string.settings_about_license, Icons.Filled.Description),
    urlNode("author", R.string.settings_about_author, Icons.Filled.Person),
)

/** The tree, top to bottom. */
internal val SETTINGS_TREE: List<TreeNode> = listOf(
    TreeNode(
        "accounts", TreeKind.SECTION, "tree/accounts", R.string.settings_accounts_title, Icons.Filled.Person, R.string.settings_accounts_summary,
        children = listOf(
            // #1828 first row of Configs: the account every account page below opens (no picker per page any more).
            TreeNode("accountSelector", TreeKind.SELECTOR, "selector:account", R.string.settings_tree_account_selector, Icons.Filled.Person),
            accountPage(FOCUS_SERVER, R.string.settings_tree_server, Icons.Filled.Storage),
            accountPage(FOCUS_SIGNATURES, R.string.settings_tree_signatures, Icons.Filled.Edit),
            accountPage(FOCUS_FOLDERS, R.string.settings_tree_folders, Icons.Filled.Folder),
            page("filter", "filters", R.string.settings_tree_filter, Icons.Filled.FilterAlt, R.string.settings_filters_summary, "account"),
            page("vacation", "vacation", R.string.settings_vacation_title, Icons.Filled.BeachAccess, R.string.settings_vacation_summary, "account"),
            TreeNode(
                "accountsOthers", TreeKind.SECTION, "tree/accountsOthers", R.string.settings_tree_others, Icons.Filled.MoreHoriz,
                children = listOf(
                    page("accountsList", "accounts", R.string.settings_accounts_title, Icons.Filled.Person, R.string.settings_accounts_summary),
                    page("addAccount", "addAccount", R.string.settings_add_account, Icons.Filled.Add, 0),
                    page("alertAccounts", "alertAccounts", R.string.settings_alert_accounts_title, Icons.Filled.RssFeed, R.string.settings_alert_accounts_summary),
                    accountPage(FOCUS_OTHERS, R.string.settings_tree_account_settings, Icons.Filled.Person),
                ),
            ),
        ),
    ),
    TreeNode(
        "text", TreeKind.SECTION, "tree/text", R.string.settings_tree_text_enhancer, Icons.Filled.AutoFixHigh,
        children = TEXT_TOOLS_ENTRIES.map { page(it.route, it.route, it.title, it.icon, it.summary, "text") },
    ),
    TreeNode(
        "ui", TreeKind.SECTION, "tree/ui", R.string.settings_tree_ui, Icons.Filled.Palette,
        children = listOf(
            page("appearance", "appearance", R.string.settings_appearance_title, Icons.Filled.Palette, R.string.settings_appearance_summary),
            page("reading", "reading", R.string.settings_reading_title, Icons.AutoMirrored.Filled.List, R.string.settings_reading_summary),
            TreeNode("navigation", TreeKind.INLINE, "inline:navigation", R.string.settings_navigation_title, Icons.AutoMirrored.Filled.List),
        ),
    ),
    TreeNode(
        "app", TreeKind.SECTION, "tree/app", R.string.settings_group_app, Icons.Filled.Settings,
        children = listOf(
            page("notifications", "notifications", R.string.settings_notifications_title, Icons.Filled.Notifications, R.string.settings_notifications_summary, "app"),
            page("privacy", "privacy", R.string.settings_privacy_title, Icons.Filled.Lock, R.string.settings_privacy_summary, "app"),
            page("storage", "storage", R.string.settings_storage_title, Icons.Filled.Storage, R.string.settings_storage_summary, "app"),
            page("backup", "backup", R.string.settings_backup_title, Icons.Filled.Backup, R.string.settings_backup_summary, "app"),
            page("update", "update", R.string.settings_update_title, Icons.Filled.Refresh, R.string.settings_update_summary, "app"),
            fleetNode("general", Icons.Filled.Settings),
            fleetNode("agents", Icons.Filled.Hub),
            fleetNode("perms", Icons.Filled.Lock),
            fleetNode("logcat", Icons.Filled.Code),
            fleetNode("xray", Icons.Filled.Search),
            // #1828 ONE About: the fleet's About page also shows the app's own About rows (version, source, licence, author).
            TreeNode(
                "fleet_about", TreeKind.FLEET, "fleet/about", 0, Icons.Filled.Info, group = "configs",
                children = ABOUT_ROWS,
            ),
        ),
    ),
)

internal fun TreeNode.walk(): Sequence<TreeNode> = sequenceOf(this) + children.asSequence().flatMap { it.walk() }

/** Every route / row key the tree reaches (sections included), the set the parity test compares both old menus against. */
internal fun settingsTreeReach(tree: List<TreeNode> = SETTINGS_TREE): Set<String> = tree.asSequence().flatMap { it.walk() }.map { it.route }.toSet()

/** The route of [accountId] under the picker [focus]: a shared mailbox keeps its own two-setting screen (settingsRouteFor). */
internal fun focusRouteFor(focus: String, accountId: String, accounts: List<StoredAccount>): String =
    if (sharedAccountFor(accountId, accounts) != null) settingsRouteFor(accountId, accounts) else "accountFocus/$focus/$accountId"

/** The destinations of the tree, registered in the settings graph beside the old routes (all of which stay). */
internal fun NavGraphBuilder.settingsTreeDestinations(
    nav: NavController,
    accountsViewModel: AccountsViewModel,
    onBack: () -> Unit,
    onAccountsChanged: () -> Unit,
) {
    composable("hub") { entry ->
        val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
        val currentId by accountsViewModel.currentId.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val leaveOnce = rememberLeaveOnce(entry)
        SettingsTreePage(
            node = null,
            onBack = onBack,
            onOpen = { route -> entry.navigateOnce { nav.navigate(route) } },
            onOpenUrl = { url -> leaveOnce { openUrl(context, url) } },
            currentAccountLabel = accounts.firstOrNull { it.id == currentId }?.label().orEmpty(),
            accounts = accounts,
            currentId = currentId,
            onSelectAccount = { id -> accountsViewModel.switchTo(id); onAccountsChanged() },
        )
    }
    composable("tree/{section}") { entry ->
        val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
        val currentId by accountsViewModel.currentId.collectAsStateWithLifecycle()
        val context = LocalContext.current
        val leaveOnce = rememberLeaveOnce(entry)
        val id = entry.arguments?.getString("section").orEmpty()
        val node = SETTINGS_TREE.asSequence().flatMap { it.walk() }.firstOrNull { it.kind == TreeKind.SECTION && it.id == id }
        SettingsTreePage(
            node = node,
            onBack = { entry.navigateOnce { nav.popBackStack() } },
            onOpen = { route -> entry.navigateOnce { nav.navigate(route) } },
            onOpenUrl = { url -> leaveOnce { openUrl(context, url) } },
            currentAccountLabel = accounts.firstOrNull { it.id == currentId }?.label().orEmpty(),
            accounts = accounts,
            currentId = currentId,
            onSelectAccount = { aid -> accountsViewModel.switchTo(aid); onAccountsChanged() },
        )
    }
    composable("accountPage/{focus}") { entry ->
        val focus = entry.arguments?.getString("focus").orEmpty()
        val title = SETTINGS_TREE.asSequence().flatMap { it.walk() }.firstOrNull { it.kind == TreeKind.ACCOUNT_PAGE && it.id == focus }?.title
        AccountsScreen(
            viewModel = accountsViewModel,
            title = title?.let { stringResource(it) },
            onBack = { entry.navigateOnce { nav.popBackStack() } },
            onOpenAccount = { id -> entry.navigateOnce { nav.navigate(focusRouteFor(focus, id, accountsViewModel.accounts.value)) } },
            onAddAccount = { entry.navigateOnce { nav.navigate("addAccount") } },
            onAccountsChanged = onAccountsChanged,
        )
    }
    composable("accountFocus/{focus}/{id}") { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val focus = entry.arguments?.getString("focus").orEmpty()
        val accounts by accountsViewModel.accounts.collectAsStateWithLifecycle()
        if (sharedAccountFor(id, accounts) != null) {
            SharedAccountScreen(
                accountId = id,
                viewModel = accountsViewModel,
                onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },
                onAccountsChanged = onAccountsChanged,
            )
        } else {
            AccountDetailScreen(
                accountId = id,
                viewModel = accountsViewModel,
                onBack = { entry.navigateOnce { if (!nav.popBackStack()) onBack() } },
                onSignedOut = {
                    onAccountsChanged()
                    entry.navigateOnce { nav.popBackStack() }
                },
                onAccountsChanged = onAccountsChanged,
                focus = focus,
            )
        }
    }
    // The shared Configs pages (General, Agents, Perms, Logcat, X-Ray App, About) of the fleet strip, drawn by the lib's own page body.
    composable("fleet/{id}") { entry ->
        val id = entry.arguments?.getString("id").orEmpty()
        val strip = remember { fleetStrip() }
        val context = LocalContext.current
        val leaveOnce = rememberLeaveOnce(entry)
        DetailScaffold(title = strip.firstOrNull { it.id == id }?.label ?: id, onBack = { entry.navigateOnce { nav.popBackStack() } }) { padding ->
            if (isFleetConfigsPage(id)) {
                Column(Modifier.fillMaxSize().padding(padding)) {
                    // #1828 the merged About: the app's own rows first, then the fleet's About body.
                    if (id == "about") {
                        SettingsSection(stringResource(R.string.settings_about_section)) {
                            ABOUT_ROWS.forEach { AboutRow(it) { url -> leaveOnce { openUrl(context, url) } } }
                        }
                    }
                    FleetConfigsPageBody(
                        id = id,
                        dark = LocalSternaDarkTheme.current,
                        // General's "Open Cloud Mail configs": this tree is Mail's configs, so it returns to the App page.
                        onAppConfigs = { entry.navigateOnce { nav.popBackStack("tree/{section}", false) } },
                        modifier = Modifier.weight(1f),
                    )
                }
            } else {
                Box(Modifier.fillMaxSize().padding(padding))
            }
        }
    }
}

/** The fleet strip in the lib's order, [General + own tabs] | Agents, Perms, Logcat, X-Ray App, About; Agents, Logcat and X-Ray App only when their lib is linked. */
private fun fleetStrip(): List<NavPage> =
    FleetConfigsExtensions.withTail(listOf(NavPage("general", "General", "settings")))

/**
 * One page of the tree: the hub ([node] null) or a section.
 *
 * #1828 the hub is UNFOLDED one level: each top-level section is a heading named after it (Accounts, Text Enhancer, UI, App) with
 * its pages as rows under it, a nested section a sub-heading with its own rows; the section pages (`tree/<id>`) still exist and
 * draw as before. The account selector is the first row, and every account page opens the selected account directly
 * ([accountRoute]); with no account yet it falls back to the picker.
 */
@Composable
internal fun SettingsTreePage(
    node: TreeNode?,
    onBack: () -> Unit,
    onOpen: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    currentAccountLabel: String,
    accounts: List<StoredAccount> = emptyList(),
    currentId: String? = null,
    onSelectAccount: (String) -> Unit = {},
) {
    val strip = remember { fleetStrip() }
    val accountRoute: (TreeNode) -> String = { n -> accountRouteFor(n, currentId, accounts) }
    val row: @Composable (TreeNode) -> Unit = { n ->
        TreeRow(n, strip, currentAccountLabel, accounts, currentId, onOpen, onOpenUrl, onSelectAccount, accountRoute)
    }
    DetailScaffold(title = if (node == null) stringResource(R.string.settings_hub_title) else stringResource(node.title), onBack = onBack) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            if (node == null) {
                SETTINGS_TREE.forEach { section ->
                    SettingsSection(stringResource(section.title)) {
                        section.children.forEach { child ->
                            if (child.kind == TreeKind.SECTION) {
                                SettingsSection(stringResource(child.title)) { child.children.forEach { row(it) } }
                            } else {
                                row(child)
                            }
                        }
                    }
                }
            } else {
                // Consecutive rows of one group sit under one heading, as the old hub's groups did.
                val groups = node.children.fold(mutableListOf<MutableList<TreeNode>>()) { acc, n ->
                    if (acc.isNotEmpty() && acc.last().first().group == n.group) acc.last().add(n) else acc.add(mutableListOf(n))
                    acc
                }
                groups.forEach { g ->
                    val heading = when (g.first().group) {
                        "account" -> if (currentAccountLabel.isNotBlank()) stringResource(R.string.settings_group_current_account, currentAccountLabel)
                        else stringResource(R.string.settings_group_current_account_generic)
                        "text" -> stringResource(R.string.settings_text_section)
                        "app" -> stringResource(R.string.settings_group_app)
                        "configs" -> stringResource(R.string.settings_tree_configs)
                        "about" -> stringResource(R.string.settings_about_section)
                        else -> null
                    }
                    val body: @Composable () -> Unit = { g.forEach { row(it) } }
                    if (heading != null) SettingsSection(heading, body) else body()
                }
            }
        }
    }
}

/** #1828 where an account page opens: the selected account's focus screen, or the picker when there is no account selected. */
internal fun accountRouteFor(n: TreeNode, currentId: String?, accounts: List<StoredAccount>): String =
    if (n.kind == TreeKind.ACCOUNT_PAGE && currentId != null && accounts.any { it.id == currentId }) focusRouteFor(n.id, currentId, accounts) else n.route

/** One row of the tree, whatever its kind. */
@Composable
private fun TreeRow(
    n: TreeNode,
    strip: List<NavPage>,
    currentAccountLabel: String,
    accounts: List<StoredAccount>,
    currentId: String?,
    onOpen: (String) -> Unit,
    onOpenUrl: (String) -> Unit,
    onSelectAccount: (String) -> Unit,
    accountRoute: (TreeNode) -> String,
) {
    when (n.kind) {
        TreeKind.FLEET -> strip.firstOrNull { it.id == n.route.removePrefix("fleet/") }?.let { SettingsCategoryRow(n.icon, it.label, "") { onOpen(n.route) } }
        TreeKind.URL -> AboutRow(n, onOpenUrl)
        TreeKind.INLINE -> NavigationModeRows()
        TreeKind.SELECTOR -> AccountSelectorRow(accounts, currentId, onSelectAccount, onOpen)
        TreeKind.ACCOUNT_PAGE -> SettingsCategoryRow(
            n.icon, stringResource(n.title),
            currentAccountLabel.ifBlank { stringResource(R.string.settings_tree_pick_account) },
        ) { onOpen(accountRoute(n)) }
        else -> SettingsCategoryRow(n.icon, stringResource(n.title), if (n.summary != 0) stringResource(n.summary) else "") { onOpen(n.route) }
    }
}

/**
 * #1828 the account selector: the selected account (name and address); a tap lists every account to pick from, and offers
 * Add account and Manage accounts. Picking one makes it the app's current account, the one every account page opens.
 */
@Composable
private fun AccountSelectorRow(
    accounts: List<StoredAccount>,
    currentId: String?,
    onSelect: (String) -> Unit,
    onOpen: (String) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val current = accounts.firstOrNull { it.id == currentId }
    SettingsCategoryRow(
        Icons.Filled.Person,
        stringResource(R.string.settings_tree_account_selector),
        current?.let { listOf(it.label(), it.username).distinct().joinToString(" · ") } ?: stringResource(R.string.settings_tree_account_selector_none),
    ) { open = true }
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(stringResource(R.string.settings_tree_account_selector)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    accounts.forEach { a ->
                        Row(
                            Modifier.fillMaxWidth()
                                .selectable(selected = a.id == currentId, role = Role.RadioButton) { onSelect(a.id); open = false }
                                .padding(vertical = MailMetrics.s8),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = a.id == currentId, onClick = null)
                            Spacer(Modifier.width(MailMetrics.s12))
                            Column(Modifier.weight(1f)) {
                                Text(a.label(), style = MaterialTheme.typography.bodyLarge)
                                if (a.username != a.label()) {
                                    Text(a.username, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(vertical = MailMetrics.s8))
                    SettingsCategoryRow(Icons.Filled.Add, stringResource(R.string.settings_add_account), "") { open = false; onOpen("addAccount") }
                    SettingsCategoryRow(Icons.Filled.Person, stringResource(R.string.settings_tree_manage_accounts), stringResource(R.string.settings_accounts_summary)) {
                        open = false; onOpen("accounts")
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.settings_tree_close)) } },
        )
    }
}

/**
 * #1828 UI ▸ Navigation in the page's own style: one choice row per section with child pages (the same data and store the
 * fleet's NavModeConfigCard uses: every section with more than one page, plus Mail's run-time folder section), each offering
 * One row, Two rows and Side menu with the declared one marked "(default)"; choosing the default clears the override.
 */
@Composable
private fun NavigationModeRows() {
    val store = LocalNavModeStore.current ?: rememberNavModePrefs()
    Text(
        stringResource(R.string.settings_navigation_child_pages),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MailMetrics.s16, vertical = MailMetrics.s4),
    )
    mailNav.sections.filter { it.allPages().size > 1 || it.id in NAV_DYNAMIC_SECTIONS }.forEach { section ->
        val declared = section.rows
        val default = stringResource(R.string.settings_navigation_default)
        SettingChoiceRow(
            title = section.label,
            options = SecondRowMode.entries,
            selected = store.mode(section.id) ?: declared,
            optionLabel = { m -> navModeLabel(m) + if (m == declared) " ($default)" else "" },
            onSelect = { m -> store.setMode(section.id, if (m == declared) null else m) },
        )
    }
}

/** Sections whose child pages come from the app's data at run time (Mail's folders and labels), offered whatever the declaration lists. */
internal val NAV_DYNAMIC_SECTIONS = setOf("mail")

/** The About rows hand a URL to the app's browser through [onOpenUrl] (the double-tap guard); no Context here. */
@Composable
internal fun AboutRow(n: TreeNode, onOpenUrl: (String) -> Unit) {
    val (summary, url) = when (n.route.removePrefix("url:")) {
        "version" -> "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) · ${BuildConfig.VERSION_DATE}" to "$REPO_URL/releases"
        "source" -> REPO_URL.removePrefix("https://") to REPO_URL
        "license" -> "GPL-3.0-only" to "$REPO_URL/src/branch/main/LICENSE"
        else -> "emon" to "https://codeberg.org/emon"
    }
    SettingsCategoryRow(n.icon, stringResource(n.title), summary) { onOpenUrl(url) }
}
