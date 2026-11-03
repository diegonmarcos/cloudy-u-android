package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
 * INFRA - the fleet's hardware and accounts in three declared rows (#1437): Bare = Providers, VMs, Containers-as-Service, Containers-Node-Python, Storage; Personal = Desktop, Mobile, Gadgets; Domains = Private, Public (was:
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
        "domains_public" -> InfraSectionFragment.newInstance(InfraSections.DOMAINS_PUBLIC)
        "domains" -> InfraSectionFragment.newInstance(InfraSections.DOMAINS)
        else -> null
    }
}

/**
 * CONTAINERS (was Topology) - three top tabs (Agents joined 2026-10-25, moved from Ops). `topology` is the SuperApp's Topology page minus its address cards:
 * [C3StackFragment] over ui.carried_c3.stack_topology (the Index row and the Infra Apps / User Apps dashboards).
 * `table` is [RoutesTableFragment]: the former Public and Private URL pages merged into one sortable, filterable
 * table over data/infra_slice.json::routes.
 */
class ContainersFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.containersPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "topology" -> C3StackFragment.newInstance(C3StackFragment.STACK_TOPOLOGY)
        "table" -> RoutesTableFragment()
        "agents" -> AgentsFragment()
        else -> null
    }
}

/**
 * OBSERV — the SuperApp's Observability tab CARRIED WHOLE (#648): the `observability`
 * page is [C3StackFragment] over stack_observability — the Index row, the FIVE feed cards
 * (Analytics, GHA, Dagu, GH Repos, Gitea Repos), the NTFY centre and the More row. Health
 * stays as it shipped; Dagu is libs:ops' own [DaguFragment] (the SuperApp's page:c3/dagu);
 * WG mesh is [C3MeshFragment] (its page:wg/status, the More row's target).
 */
class ObservFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.observPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "observability" -> C3StackFragment.newInstance(C3StackFragment.STACK_OBSERVABILITY)
        "health" -> C3HealthFragment.newInstance(C3HealthFragment.SCOPE_ALL)
        "dagu" -> DaguFragment.newInstance()
        "mesh" -> C3MeshFragment.newInstance()
        else -> null
    }
}

/**
 * OPS (was Apps) - the five topology views of the fleet (owner request, 2026-10-25), one page each over [TopologyViewFragment]:
 * Service (logical flow), Infrastructure (compute), Orchestration (declared vs deployed), Network and Release. Each is a tree
 * grouped by its own key with a list fallback, and a node opens the same entity in the other four. Agents moved to Containers;
 * Services and Tools are gone from here.
 */
class OpsFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.opsPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "service" -> TopologyViewFragment.newInstance(TopologyModel.SERVICE)
        "infrastructure" -> TopologyViewFragment.newInstance(TopologyModel.INFRASTRUCTURE)
        "orchestration" -> TopologyViewFragment.newInstance(TopologyModel.ORCHESTRATION)
        "network" -> TopologyViewFragment.newInstance(TopologyModel.NETWORK)
        "release" -> TopologyViewFragment.newInstance(TopologyModel.RELEASE)
        else -> null
    }
}

/** CONFIGS — this app's own settings. About is real because everything it shows is baked. */
class ConfigsFragment : PagedFragment() {
    override fun pages(): List<NavPage> = Declarations.configsPages
    override fun pageFragment(pageId: String): Fragment? = when (pageId) {
        "about" -> AboutFragment()
        else -> null
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
