package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.R
import com.diegonmarcos.cloudc3.cloud.C3HealthFragment
import com.diegonmarcos.cloudc3.cloud.C3MeshFragment
import com.diegonmarcos.cloudc3.cloud.C3StackFragment
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.ops.dagu.DaguFragment
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #648 the three paged content tabs. Each one is only two things: WHICH declaration it
 * reads, and WHICH fragment each of its declared page ids resolves to. Everything else —
 * the strip, the selection, the swap, the state restore — is [PagedFragment].
 *
 * Every page id below resolves to a real, working fragment. That is not a coincidence, it
 * is the rule this ticket added: a page is DECLARED when it is implemented and not before,
 * so there is no "not built yet" body anywhere in this app and no flag that could excuse
 * one. The pages still to arrive (the GHA / Dagu / commit feeds, the ntfy centre, the
 * container dashboards) are absent from build.json until their fragments exist.
 */

/**
 * INFRA - the fleet's hardware and accounts in four declared rows (#1437, #1471): Bare = Providers, VMs, Containers-as-Service, Containers-Node-Python, Storage; APIs = one page per group of data/api_providers.json (AI, Scraper); Personal = Desktop, Mobile, Gadgets; Domains = Private, Public (was:
 * Electronics, Domains. Each page is [InfraSectionFragment] over data/fleet_slice.json (derived from the fleet's own
 * declarations, never typed here), refreshed live from c3-infra-api where it can say more. Containers - the DBs, APIs
 * and MCPs among them - are NOT on this section: they live in Containers (Topology and Table).
 */
class InfraFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.infraPages
    override fun section() = Declarations.nav.section("infra")
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "providers" -> InfraSectionFragment.newInstance(InfraSections.PROVIDERS)
        "machines" -> InfraSectionFragment.newInstance(InfraSections.MACHINES)
        "serverless" -> InfraSectionFragment.newInstance(InfraSections.SERVERLESS)
        "storage" -> InfraSectionFragment.newInstance(InfraSections.STORAGE)
        "node_python" -> InfraSectionFragment.newInstance(InfraSections.NODE_PYTHON)
        "desktop" -> InfraSectionFragment.newInstance(InfraSections.DESKTOP)
        "mobile" -> InfraSectionFragment.newInstance(InfraSections.MOBILE)
        "gadgets" -> InfraSectionFragment.newInstance(InfraSections.GADGETS)
        "control" -> InfraSectionFragment.newInstance(InfraSections.CONTROL)
        "domains_public" -> InfraSectionFragment.newInstance(InfraSections.DOMAINS_PUBLIC)
        "domains" -> InfraSectionFragment.newInstance(InfraSections.DOMAINS)
        "projects" -> InfraSectionFragment.newInstance(InfraSections.PROJECTS)
        else -> Declarations.apiProviders.groupOfPage(pageId)?.let { InfraSectionFragment.newInstance(pageId) }
    }
}

/**
 * CONTAINERS (was Topology) - #1941 four rows: the project category (the Cloud Me Proj groups, plus All), the project names of that
 * category, Topology | Table, and the page's own Filters row (see [ContainersChrome]). Every project is a declared page (build.json) with a
 * nested Topology | Table switch (#1454). `topology` is the SuperApp's Topology page minus its address cards: [C3StackFragment] over
 * ui.carried_c3.stack_topology, scoped to the project; `table` is [RoutesTableFragment]: the sortable, filterable table over the
 * same containers. Which project a container belongs to is data/container_projects.json (ContainerProjects), never code. Agents
 * moved to Ops (#1454).
 */
class ContainersFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.containersPages
    override fun pageFragment(pageId: String): Fragment? = null

    @Composable
    override fun Content() {
        val decl = Declarations.containerProjects
        val allPages = remember { pages() }
        // #1971 two modes: Projects (the pages that are All or a project) and VM (the `vm-` pages, one per derived host)
        val declared = remember { allPages.filterNot { decl.isVmPage(it.id) } }
        val vmPages = remember { allPages.filter { decl.isVmPage(it.id) } }
        val top = selectedTop ?: declared.first().id
        val vmMode = decl.isVmPage(top)
        val leaf = selectedLeaf
        LaunchedEffect(top, leaf) {
            val tab = arguments?.getString(PagedFragment.ARG_TAB)
            val page = leaf ?: top
            if (tab != null) (activity as? PagedFragment.PageListener)?.onPageShown(tab, page)
        }
        val category = if (vmMode) "" else ContainersNav.categoryOf(decl, declared, top)
        val categories = if (vmMode) emptyList() else ContainersNav.categories(decl, declared)
        fun pagesOf(c: String): List<NavPage> = ContainersNav.pagesOf(decl, declared, c)
        val views = allPages.firstOrNull { it.id == top }?.pages.orEmpty()
        val topology = leaf?.endsWith("_topology") == true
        val version = ContainersSource.version
        ContainersChrome(
            categories = categories, selectedCategory = category,
            onCategory = { c -> pagesOf(c).firstOrNull()?.let { openPage(it.id) } },
            projects = if (vmMode) vmPages else pagesOf(category), selectedProject = top, onProject = { openPage(it.id) },
            modes = if (vmPages.isEmpty()) emptyList() else decl.vm.modes.map { (id, label) -> NavPage(id, label, "ic_p_c3_stack") }, selectedMode = if (vmMode) "vm" else "projects",
            onMode = { m -> (if (m.id == "vm") vmPages else declared).firstOrNull()?.let { openPage(it.id) } },
            views = views, selectedView = leaf, onView = { selectLeaf(it.id) },
            filters = {
                if (topology) {
                    val scoped = remember(version, top) { ContainerProjects.scope(decl, ContainersSource.current(), top.takeIf { decl.scopeKnown(it) }) }
                    TopologyFilters(
                        vms = remember(scoped) { RoutesTableModel.vmOptions(scoped.entries.map { RoutesTableModel.Row("", "", "", "", "", it.host, "") }) },
                        types = remember(scoped) { RoutesTableModel.typeOptions(scoped.entries.map { RoutesTableModel.Row("", "", "", "", "", "", "", type = decl.typeOf(it)) }, decl.types) },
                    )
                }
            },
        ) { modifier ->
            if (leaf != null) C3FragmentHost(
                fm = childFragmentManager, containerId = R.id.page_container, key = leaf, modifier = modifier,
            ) { leafFragment(top, leaf) ?: UndeclaredPageFragment.newInstance(leaf) }
        }
    }

    /** A nested page `<project>_<view>` (build.json): the project is its parent page, the view the rest of its id. */
    override fun leafFragment(parentId: String, leafId: String): Fragment? = when (leafId.removePrefix("${parentId}_")) {
        "topology" -> C3StackFragment.newInstance(C3StackFragment.STACK_TOPOLOGY, parentId)
        "table" -> RoutesTableFragment.newInstance(parentId)
        else -> null
    }
}

/**
 * ORCH (#1942) - the Orchestration pages that lived in Ops (CI/CD flow, the Service, Infrastructure, Declared and Release topology
 * views, Agents) plus Dagu (libs:ops' own [DaguFragment], moved here from the old Observ tab) and, per repo, the GitHub release
 * table and the GHCR package table ([OrchRegistryFragment], loaded lazily). Page ids keep their `orch_` names.
 */
class OrchFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.orchPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "orch_flow" -> OpsMapFragment.newInstance("flow")
        "orch_service" -> TopologyViewFragment.newInstance(TopologyModel.SERVICE)
        "orch_infrastructure" -> TopologyViewFragment.newInstance(TopologyModel.INFRASTRUCTURE)
        "orch_orchestration" -> TopologyViewFragment.newInstance(TopologyModel.ORCHESTRATION)
        "orch_release" -> TopologyViewFragment.newInstance(TopologyModel.RELEASE)
        "orch_agents" -> AgentsFragment()
        "orch_dagu" -> DaguFragment.newInstance()
        "orch_gh_releases" -> OrchRegistryFragment.newInstance(OrchRegistryFragment.KIND_RELEASES)
        "orch_ghcr" -> OrchRegistryFragment.newInstance(OrchRegistryFragment.KIND_GHCR)
        else -> null
    }
}

/**
 * OPS (#1480, #1495: the catalog pages Security, Tests, Metrics, Alerts, Notify draw their API routes and MCP tools from data/c3_hierarchy.json via [OpsMapFragment]): Network Mail | Health Obsv (Orchestration moved to the Orch tab, #1942), one top page each, every one a nested strip of views (build.json,
 * ids `<page>_<view>`). Network and Mail are the maps of data/ops_maps.json ([OpsMapFragment], derived from mesh.json, infra_slice.json
 * and containers_declared.json); Orchestration is the CI/CD flow map, then the topology views and Agents ([TopologyViewFragment] over
 * ui.topology); Health is [OpsHealthFragment]; Observability is the logs catcher [OpsLogsFragment].
 */
class OpsFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.opsPages
    override fun pageFragment(pageId: String): Fragment? = null

    /** A nested page `<page>_<view>`: the view is the rest of its id; the Ops pivot links open the topology views by that view id. */
    override fun leafFragment(parentId: String, leafId: String): Fragment? = when (leafId.removePrefix("${parentId}_")) {
        "wg" -> OpsMapFragment.newInstance("wg")
        "auth" -> OpsMapFragment.newInstance("auth")
        "dns" -> OpsMapFragment.newInstance("dns")
        "network" -> TopologyViewFragment.newInstance(TopologyModel.NETWORK)
        "tables" -> MailStackFragment.newInstance("tables")
        "topology_inbound" -> MailStackFragment.newInstance("inbound")
        "topology_outbound" -> MailStackFragment.newInstance("outbound")
        "mesh" -> C3MeshFragment.newInstance()
        "uptime" -> OpsHealthFragment()
        "health" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_ALL)
        "stack" -> C3StackFragment.newInstance(C3StackFragment.STACK_OBSERVABILITY)
        "logs" -> OpsLogsFragment()
        "security" -> OpsMapFragment.newInstance("hier_$leafId")
        "tests" -> OpsMapFragment.newInstance("hier_$leafId")
        "metrics" -> OpsMapFragment.newInstance("hier_$leafId")
        "alerts" -> OpsMapFragment.newInstance("hier_$leafId")
        "notify" -> OpsMapFragment.newInstance("hier_$leafId")
        else -> null
    }
}

/**
 * CONFIGS — #1443 starts General | Perms | About (the fleet's shared pages of libs:ui-nav-bars), then this app's own settings.
 * Its own build-info card is [AboutFragment], real because everything it shows is baked.
 */
class ConfigsFragment : PagedFragment() {
    // #1630 {own tabs} | Agents, Perms, Logcat, X-Ray App, About: the declared pages through the fleet tail (the tail pages are libs:ui-nav-bars').
    override fun pages(): List<NavPage> = com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.withTail(Declarations.configsPages)
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "general" -> FleetConfigsPageFragment.newInstance(pageId)
        "perms" -> FleetConfigsPageFragment.newInstance(pageId)
        "about" -> FleetConfigsPageFragment.newInstance(pageId)
        "build" -> AboutFragment()
        "plugins" -> OpsMapFragment.newInstance("hier_plugins")
        // #1630 Agents / Logcat / X-Ray App are the opt-in libs' tail tabs (withTail above), not declared pages
        else -> if (com.diegonmarcos.superapp.fleetconfigs.FleetConfigsExtensions.isOptInTail(pageId)) FleetConfigsPageFragment.newInstance(pageId) else null
    }
}

/** #1443 One of the fleet's shared Configs pages (General, Perms, About) as a tab page; the pages are libs:ui-nav-bars', drawn once for the fleet. */
class FleetConfigsPageFragment : KitComposeFragment() {
    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        com.diegonmarcos.superapp.fleetconfigs.FleetConfigsPageBody(arguments?.getString(ARG_PAGE).orEmpty(), onAppConfigs = { (parentFragment as? PagedFragment)?.openPage("build") })
    }

    companion object {
        private const val ARG_PAGE = "page"
        fun newInstance(pageId: String) = FleetConfigsPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
        }
    }
}

/**
 * Drawn ONLY when a declared page id resolves to no fragment — a declaration that is not
 * true. It names the id and says what is wrong, because the alternative (an empty pane) is
 * indistinguishable from a page that legitimately has nothing to show. It is not a
 * placeholder to ship behind: test-c3-shell.sh fails the build if any declared page reaches
 * it, so on a shipped APK this class is unreachable by construction.
 */
class UndeclaredPageFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val p = LocalKitPalette.current
        Text(
            stringResource(R.string.page_has_no_body, arguments?.getString(ARG_PAGE) ?: ""),
            color = p.textSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 24.dp, end = 16.dp, bottom = 16.dp),
        )
    }

    companion object {
        private const val ARG_PAGE = "page"
        fun newInstance(pageId: String) = UndeclaredPageFragment().apply {
            arguments = Bundle().apply { putString(ARG_PAGE, pageId) }
        }
    }
}
