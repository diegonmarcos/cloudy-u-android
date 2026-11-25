package com.diegonmarcos.cloudme

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.commit
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.bottomnav.LocalNavModeStore
import com.diegonmarcos.superapp.bottomnav.effective
import com.diegonmarcos.superapp.bottomnav.rememberNavModePrefs
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.navbartop2.NavSectionLayout
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRows
import com.diegonmarcos.superapp.health.HealthFragment
import com.diegonmarcos.superapp.uikit.KitFragmentHost
import org.json.JSONObject

/**
 * One section: its tab strip and whichever page is showing.
 *
 * A tab that declares `pages` of its own is a container — it draws another,
 * quieter strip underneath and shows one of its children, and a child may be
 * a container in turn. Buro > Fin was the first: Acct, Budget and Portfolio
 * are three views of one question, and three top-level tabs would have said
 * they were three questions. Projects > Health > Workout > Gym is the deepest,
 * at three strips. The strips are built from the page chain rather than from
 * two named fields, so the depth build.json may declare is not capped here.
 *
 * A page's content is decided by its `stack_<id>` list. When that list is a
 * single `fragment` block the library surface is hosted directly — Health,
 * Accounting, Agenda and the Wallet file browser are whole surfaces owned by
 * their modules, and wrapping them in a renderer would only add a scroll
 * container inside a scroll container. Everything else goes to [StackScreen].
 *
 * 2026-10-16 owner decision: all apps must be Compose. The strips are libs:bottomnav's PageTabs
 * composables; Agenda, Files and Web are composables of this app; only Health and Fin, which
 * are Fragments of their own libraries, are hosted through KitFragmentHost.
 */
@Composable
fun SectionScreen(sectionId: String, requestedPage: String?, modifier: Modifier = Modifier) {
    val section = Sections.byId(sectionId) ?: return
    /** The LEAF page on screen — a sub-page id when the tab is a container. */
    var pageId by rememberSaveable(sectionId, requestedPage) {
        mutableStateOf(section.page(requestedPage)?.leaf()?.id)
    }
    val page = section.page(pageId)?.leaf() ?: return
    // #1630 Configs ends with the fleet-default tail: {own tabs} | Agents, Perms, Logcat, X-Ray App, About. Agents / Logcat / X-Ray App are the
    // opt-in libs' tabs (libs:fleetconfigs-*), not declared pages: they are kept by id and drawn by FleetConfigsPageBody.
    val isConfigs = sectionId == "configs"
    val tailTab = isConfigs && com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.isOptInTail(pageId)
    val chain = section.path(page.id)
    val ctx = LocalContext.current
    val onTarget = LocalMeTarget.current

    val store = LocalNavModeStore.current ?: rememberNavModePrefs()
    // The section's declared nav_mode, unless the user chose another for it in Configs (persisted, in the backup).
    val nav = section.nav.effective(store)
    val onSelect: (Page) -> Unit = { tab ->
        // A launch tab (Buro > Account) leaves for its action and the strip keeps
        // pointing at the page the user comes back to.
        if (tab.action.isNotBlank()) onTarget(tab.action)
        else {
            // A container tab has no content of its own; tapping one opens the child
            // you were last on — which is what staying on the chain means — or its
            // first child. Tapping a leaf opens the leaf.
            val stillBelow = section.path(pageId).any { it.id == tab.id }
            pageId = if (isConfigs && com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.isOptInTail(tab.id)) tab.id
            else if (stillBelow) pageId else section.page(tab.id)?.leaf()?.id
        }
    }
    // The Samsung Health strip (#1144): icon-only pills, the selected one also shows its name.
    val icon: @Composable (Page) -> androidx.compose.ui.graphics.painter.Painter? = { p -> pageIcon(ctx, p.icon)?.let { painterResource(it) } }
    // Draws the strips [chain] implies: level 0 offers the section's own
    // tabs, level n the children of the tab chosen at level n-1, and the
    // chain says which tab is selected at each. Strips past the chain are
    // dropped, so stepping out of a container takes its strip with it.
    val levels = listOf(if (isConfigs) com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(section.pages) else section.pages) + chain.dropLast(1).map { it.pages }
    val sideMenu = nav.rows == SecondRowMode.SideMenu && section.pages.size > 1
    // #1298 the group build.json::ui.repo_tree.group names (Lnktree's Proj): its label opens the WHOLE repo outline.
    var fullTree by rememberSaveable(sectionId) { mutableStateOf(false) }
    val repoGroup = RepoTreeConfig.baked.group
    if (fullTree) RepoTreeFullDialog(onDismiss = { fullTree = false })
    val body: @Composable (Int) -> Unit = { from ->
        Column(Modifier.fillMaxSize()) {
            levels.forEachIndexed { level, siblings ->
                // One tab is not a choice — a single-tab strip reads as a broken
                // control rather than as a navigation aid. Configs is shaped that
                // way on purpose: a settings screen is a list you scroll.
                if (level >= from && siblings.size > 1) {
                    if (level == 0 && nav.rows == SecondRowMode.ManyRows) {
                        // ui.sections[].nav_mode = horizontal_two_rows: one row per page group (label dropdown | icon chips).
                        NavBarTopSecondRows(
                            section = nav, selectedId = chain.getOrNull(0)?.id, onSelect = onSelect,
                            dark = true, underTopChrome = false, iconFor = icon,
                            onGroupIndex = { g -> (repoGroup.isNotBlank() && g.id == repoGroup).also { if (it) fullTree = true } },
                        )
                    } else {
                        NavBarTopSecondRow(
                            pages = siblings,
                            selectedId = if (tailTab && level == 0) pageId else chain.getOrNull(level)?.id,
                            onSelect = onSelect,
                            dark = true,
                            underTopChrome = false,
                            iconFor = icon,
                        )
                    }
                }
            }
            if (tailTab) com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(pageId.orEmpty(), modifier = Modifier.weight(1f).fillMaxWidth())
            else PageContent(section, page, Modifier.weight(1f).fillMaxWidth())
        }
    }
    if (sideMenu) {
        // vertical_side_menu: level 0 is the menu docked at the left; the deeper strips stay above the page.
        Box(modifier.fillMaxSize()) {
            NavSectionLayout(nav, chain.getOrNull(0)?.id, onSelect, dark = true, iconFor = icon, store = store) { body(1) }
        }
    } else Box(modifier.fillMaxSize()) { body(0) }
}

@Composable
private fun PageContent(section: Section, page: Page, modifier: Modifier) {
    val ctx = LocalContext.current
    val stack = remember(section.id, page.id) { Sections.stack(ctx, section.id, page.id) }
    val single = if (stack.length() == 1) stack.optJSONObject(0) else null
    if (single != null && single.optString("kind") == "fragment") {
        // `{"kind":"fragment","id":"…"}` → the module that owns that surface.
        // An unknown id falls through to the stack renderer, which shows the
        // block's title rather than a blank screen, so a typo in JSON is
        // visible on the phone instead of silent.
        when (single.optString("id")) {
            // `metric` and `records` are only read by the metric page, which is
            // one metric of the taxonomy on a page of its own — Workout > Steps
            // is the Activity metric narrowed to steps, distance and calories.
            // Both are declared here rather than in Kotlin so that narrowing a
            // page, or adding another one, stays a JSON edit.
            "health" -> HostedFragment("${section.id}/${page.id}", modifier) { healthFragment(single) }
            "healthpage" -> com.diegonmarcos.cloudme.health.HealthPage(single.optString("page"), modifier)
            "agenda" -> AgendaScreen(AgendaMode.EVENTS, modifier)
            // #1557 Buro > Secrets: the Vaultwarden export (me-secrets pack) as authenticator-first tables.
            "secrets" -> SecretsScreen(single.optString("secret_type"), modifier)
            // #1557 Buro > ID & DD > Pay Cards / Autocomplete: the me-iddd pack.
            "iddd" -> IdddScreen(single.optString("view"), modifier)
            // #1557 Buro > Configs > Import / Export: the USB-C backup surface (SAF).
            "backup" -> BackupScreen(modifier)
            "todo" -> AgendaScreen(AgendaMode.TODOS, modifier)
            "tasks" -> TasksScreen(modifier, single.optString("subject"), single.optString("open"))
            "files" -> FilesScreen(single, modifier)
            "web" -> WebPage(single, modifier, pageId = page.id)
            // #1298 Lnktree > Proj > <area>: that registry area's tree (repos.outline.json, libs:repos).
            "repo_tree" -> RepoTreeScreen(single.optString("area"), modifier)
            // #1350 RoadMap > Folders: the notes vault's folder tree (data/repos/roadmap-tree.json).
            "folder_tree" -> FolderTreeScreen(single.optString("asset"), modifier)
            // #1398 Tasks > Inboxes | Backlogs | RoadMaps | Library > <chip>: one vault folder's notes (data/repos/vault-rows.json).
            "vault_folder" -> VaultFolderScreen(single.optString("asset"), single.optString("path"), modifier)
            // #1439 Configs > General | About: the shared Configs v2 pages of libs:ui-nav-bars, from build.json::configs_pages.
            "fleet_general" -> Box(modifier) {
                val target = LocalMeTarget.current
                com.diegonmarcos.superapp.fleetconfigs.FleetGeneralPage(
                    com.diegonmarcos.superapp.fleetconfigs.FleetConfigs.current, dark = true,
                    c = com.diegonmarcos.superapp.themebg.ThemeBackground.colors(true),
                    onAppConfigs = { target("page:configs/nav") },
                )
            }
            // #1439 Profile (the top-right avatar): Cloud Account's Personal Info + Configs, from libs:account-ui.
            "account_profile" -> com.diegonmarcos.superapp.accountui.AccountProfileScreen(modifier)
            "fleet_about" -> Box(modifier) {
                com.diegonmarcos.superapp.fleetconfigs.FleetAboutPage(
                    com.diegonmarcos.superapp.fleetconfigs.FleetConfigs.current,
                    com.diegonmarcos.superapp.themebg.ThemeBackground.colors(true),
                )
            }
            else -> StackScreen(stack, modifier)
        }
    } else {
        StackScreen(stack, modifier)
    }
}

@Suppress("DiscouragedApi")
private fun pageIcon(ctx: Context, name: String): Int? =
    if (name.isBlank()) null else ctx.resources.getIdentifier(name, "drawable", ctx.packageName).takeIf { it != 0 }

private fun healthFragment(o: JSONObject): Fragment = HealthFragment.newInstance(
    o.optString("page", HealthFragment.PAGE_SUMMARY),
    o.optString("metric"),
    (o.optJSONArray("records") ?: org.json.JSONArray()).let { arr ->
        (0 until arr.length()).map { arr.optString(it) }
    },
)

/**
 * A library Fragment as a page: one container per shown page, the fragment added into it by
 * KitFragmentHost and removed again when the page leaves the screen (a Fragment left behind
 * would keep running with no view to draw on).
 */
@Composable
internal fun HostedFragment(key: String, modifier: Modifier, create: () -> Fragment) {
    val activity = LocalContext.current.findFragmentActivity()
    val containerId = remember(key) { View.generateViewId() }
    DisposableEffect(containerId) {
        onDispose {
            val fm = activity.supportFragmentManager
            if (!fm.isDestroyed) {
                fm.findFragmentById(containerId)?.let { fm.commit(allowStateLoss = true) { remove(it) } }
            }
        }
    }
    KitFragmentHost(containerId, modifier, tag = key, create = create)
}

private fun Context.findFragmentActivity(): FragmentActivity {
    var c: Context = this
    while (c is ContextWrapper) {
        if (c is FragmentActivity) return c
        c = c.baseContext
    }
    error("a hosted Fragment needs a FragmentActivity")
}
