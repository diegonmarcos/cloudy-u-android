package com.diegonmarcos.cloudc3.pages

import android.os.Bundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.superapp.uikit.KitComposeFragment
import com.diegonmarcos.superapp.uikit.KitDensity
import com.diegonmarcos.superapp.uikit.KitPalette
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import java.time.LocalDate
import kotlinx.coroutines.launch

/** The Infra section's page ids (build.json::ui.sections[infra].pages) - the screens below answer exactly these. */
object InfraSections {
    const val PROVIDERS = "providers"
    const val MACHINES = "machines"
    const val SERVERLESS = "serverless"
    const val NODE_PYTHON = "node_python"
    const val STORAGE = "storage"
    const val DESKTOP = "desktop"
    const val MOBILE = "mobile"
    const val GADGETS = "gadgets"
    const val CONTROL = "control"
    const val DOMAINS = "domains"
    const val DOMAINS_PUBLIC = "domains_public"
    val ALL = listOf(PROVIDERS, MACHINES, SERVERLESS, NODE_PYTHON, STORAGE, DESKTOP, MOBILE, GADGETS, CONTROL, DOMAINS, DOMAINS_PUBLIC)
}

/** Test tags of the Infra pages, for the declaration-to-screen suite. */
object InfraTags {
    fun screen(section: String) = "infra_screen_$section"
    fun row(section: String, id: String) = "infra_row_${section}_$id"
    fun head(section: String, id: String) = "infra_head_${section}_$id"
    const val LIVE_NOTE = "infra_live_note"
    const val IDENTITIES_NOTE = "infra_identities_note"
    const val IDENTITIES_TOKEN = "infra_identities_token"
}

/** One Infra page by its declared id, over the slice and the live state. */
@Composable
fun InfraSectionScreen(
    section: String, slice: FleetModel.Slice, live: FleetLiveState, today: LocalDate = LocalDate.now(),
    identities: PrivateIdentitiesState = PrivateIdentitiesState(), onToken: (String) -> Unit = {},
    apis: ApiProviders.Decl = Declarations.apiProviders,
    fleet: FleetEndpoints.Decl = Declarations.fleetEndpoints,
    mcp: C3Mcp.Decl = Declarations.c3Mcp,
) {
    when (section) {
        InfraSections.PROVIDERS -> ProvidersScreen(slice, live)
        InfraSections.MACHINES -> MachinesScreen(slice)
        InfraSections.SERVERLESS -> ServerlessScreen(slice)
        InfraSections.STORAGE -> StorageScreen(slice, live)
        InfraSections.NODE_PYTHON -> NodePythonScreen(slice)
        InfraSections.DESKTOP -> DevicesScreen(InfraSections.DESKTOP, slice)
        InfraSections.MOBILE -> DevicesScreen(InfraSections.MOBILE, slice)
        InfraSections.GADGETS -> DevicesScreen(InfraSections.GADGETS, slice)
        InfraSections.CONTROL -> OpsCatalogScreen(InfraTags.screen(InfraSections.CONTROL), "hier_control")
        InfraSections.DOMAINS_PUBLIC -> PublicDomainsScreen(slice, identities, onToken)
        InfraSections.DOMAINS -> DomainsScreen(slice, live, today)
        else -> apis.groupOfPage(section)?.let { if (it.id == fleet.group) FleetEndpointsScreen(section, it, fleet) else if (it.id == mcp.group) C3McpScreen(section, it, mcp) else ApiProvidersScreen(section, it, apis) }
            ?: Text("Unknown Infra page: $section", color = LocalKitPalette.current.bad)
    }
}

// ── APIs (#1471): the third-party API providers of one declared group (data/api_providers.json); secret NAMES only ──

@Composable
fun ApiProvidersScreen(section: String, group: ApiProviders.Group, apis: ApiProviders.Decl) {
    val rows = apis.of(group)
    Page(section, "${rows.size} ${group.label} API providers") {
        if (rows.isEmpty()) {
            DenseRow(InfraTags.row(section, "none"), "None declared", "", listOf("No ${group.label} API provider is declared in the fleet"))
        }
        rows.forEach { p ->
            DenseRow(
                InfraTags.row(section, p.id), p.label, p.category,
                listOf(
                    "Apps: ${p.apps.joinToString(", ").ifBlank { "none declared" }}",
                    p.containers.takeIf { it.isNotEmpty() }?.let { "Containers: ${it.joinToString(", ")}" }.orEmpty(),
                    "Auth: ${p.auth.ifBlank { "not declared" }}",
                    "Secret name: ${p.secrets.joinToString(", ") { s -> if (s.kind.isBlank()) s.name else "${s.name} (${s.kind})" }.ifBlank { "none" }}",
                ),
                links = p.links,
            )
        }
    }
}

// ── APIs > C3 Endpoints (#1718): the fleet services' public and mesh URLs, moved here from Cloud C3 Webserver > API ──

@Composable
fun FleetEndpointsScreen(section: String, group: ApiProviders.Group, fleet: FleetEndpoints.Decl) {
    Page(section, fleet.summary()) {
        if (fleet.services.isEmpty()) {
            DenseRow(InfraTags.row(section, "none"), "None declared", "", listOf("No ${group.label} endpoint is declared (data/api_providers.json::fleet_endpoints is empty)"))
        }
        fleet.services.forEach { s ->
            DenseRow(InfraTags.row(section, s.id), s.id, listOf(s.kind, s.policy).filter { it.isNotBlank() }.joinToString(" - "), FleetEndpoints.lines(s), links = FleetEndpoints.links(s))
        }
    }
}

// ── APIs > C3 MCP (#1725): the MCP servers, their tools and c3-api routes, moved here from Cloud C3 Webserver > API ──

@Composable
fun C3McpScreen(section: String, group: ApiProviders.Group, mcp: C3Mcp.Decl) {
    Page(section, mcp.summary()) {
        if (mcp.servers.isEmpty()) {
            DenseRow(InfraTags.row(section, "none"), "None declared", "", listOf("No ${group.label} server is declared (data/api_providers.json::mcp_catalogue is empty)"))
        }
        mcp.servers.forEach { s ->
            DenseRow(InfraTags.head(section, s.name), s.name, "${s.auth} - ${s.toolsCount} tools", C3Mcp.lines(s))
            s.tools.forEach { t ->
                DenseRow(InfraTags.row(section, s.name + "/" + t.tool), t.tool, if (t.mcpOnly) "mcp_only" else s.name, C3Mcp.lines(t))
            }
        }
    }
}

// ── Providers ───────────────────────────────────────────────────────────────

@Composable
fun ProvidersScreen(slice: FleetModel.Slice, live: FleetLiveState) {
    Page(InfraSections.PROVIDERS, "${slice.providers.size} providers - ${slice.vms.size} VMs") {
        slice.providers.forEach { p ->
            val vmNames = p.vms.mapNotNull { id -> slice.vms.firstOrNull { it.id == id } }.joinToString(", ") { it.alias.ifBlank { it.id } }
            DenseRow(
                InfraTags.row(InfraSections.PROVIDERS, p.id), p.label, p.plan ?: "plan not declared",
                listOf(
                    "Account: ${p.account ?: "not declared"}",
                    p.use?.let { "Use: $it" }.orEmpty(),
                    FleetModel.costLine(p, live.costs[p.id], live.notes["cost:${p.id}"]),
                    "VMs: ${vmNames.ifBlank { "none" }}",
                ),
                links = p.links,
            )
        }
    }
}

// ── VMs ─────────────────────────────────────────────────────────────────────

@Composable
fun MachinesScreen(slice: FleetModel.Slice) {
    Page(InfraSections.MACHINES, "${slice.vms.size} VMs on ${slice.vms.map { it.provider }.distinct().size} providers") {
        slice.vms.forEach { v ->
            DenseRow(
                InfraTags.row(InfraSections.MACHINES, v.id), "${v.alias.ifBlank { v.id }} (${v.id})",
                slice.provider(v.provider)?.label ?: v.provider,
                listOf(FleetModel.vmSpec(v), FleetModel.vmNet(v), v.desc.orEmpty()),
            )
        }
    }
}

// ── Containers-as-Service: the PLATFORMS that run containers (the containers are the Containers page's) ──

@Composable
fun ServerlessScreen(slice: FleetModel.Slice) {
    Page(InfraSections.SERVERLESS, "${slice.containerPlatforms.size} container platforms") {
        if (slice.containerPlatforms.isEmpty()) {
            DenseRow(InfraTags.row(InfraSections.SERVERLESS, "none"), "None declared", "", listOf("No managed container platform is declared in the fleet"))
        }
        slice.containerPlatforms.forEach { p ->
            DenseRow(
                InfraTags.row(InfraSections.SERVERLESS, p.id), p.label,
                p.services?.let { "$it services" + (p.live?.let { l -> " - $l live" } ?: "") }.orEmpty(),
                listOfNotNull(
                    p.provider?.let { "Provider: $it" }, p.project?.let { "Project: $it" },
                    p.regions.takeIf { it.isNotEmpty() }?.let { "Region: ${it.joinToString(", ")}" }, p.role?.let { "Use: $it" },
                ),
                links = p.links,
            )
        }
    }
}

// ── Storage: hardware and backends only ─────────────────────────────────────

@Composable
fun StorageScreen(slice: FleetModel.Slice, live: FleetLiveState) {
    val rows = FleetModel.storageRows(slice, live.disks, live.phone)
    Page(InfraSections.STORAGE, "${rows.size} storage entries - buckets, drives, disks, devices") {
        FleetModel.STORAGE_KINDS.forEach { kind ->
            val ofKind = rows.filter { it.kind == kind }
            if (ofKind.isNotEmpty()) {
                GroupHeader(InfraTags.head(InfraSections.STORAGE, kind), kind)
                ofKind.forEach { r ->
                    val vmId = slice.vms.firstOrNull { it.alias.ifBlank { it.id } == r.name }?.id
                    val note = vmId?.let { live.notes["disks:$it"] }
                    DenseRow(
                        InfraTags.row(InfraSections.STORAGE, "$kind/${r.name}"), r.name, r.usage ?: if (kind == "VM disk") "declared" else "",
                        listOf(r.detail, note?.let { "live unavailable: $it" }.orEmpty()),
                    )
                }
            }
        }
    }
}

// ── Containers-Node-Python: static / Node / Python hosting (Firebase Hosting, GitHub Pages) ──

@Composable
fun NodePythonScreen(slice: FleetModel.Slice) {
    Page(InfraSections.NODE_PYTHON, "${slice.hosting.size} hosting and runtime platforms") {
        if (slice.hosting.isEmpty()) {
            DenseRow(InfraTags.row(InfraSections.NODE_PYTHON, "none"), "None declared", "", listOf("No hosting platform is declared in the fleet"))
        }
        slice.hosting.forEach { h ->
            DenseRow(
                InfraTags.row(InfraSections.NODE_PYTHON, h.id), h.label, h.plan ?: "plan not declared",
                listOfNotNull(h.provider?.let { "Provider: $it" }, h.role?.let { "Use: $it" }, h.state?.let { "State: $it" }, h.sites.takeIf { it.isNotEmpty() }?.let { "Sites: ${it.joinToString(", ")}" }),
                links = h.links,
            )
        }
    }
}

// ── Domains ▸ Public: mailbox domains the owner uses, and the owner's email identities (private data, run time) ──

@Composable
fun PublicDomainsScreen(slice: FleetModel.Slice, identities: PrivateIdentitiesState = PrivateIdentitiesState(), onToken: (String) -> Unit = {}) {
    // the declared public addresses first, then any the optional private source adds (no duplicates)
    val all = Declarations.publicIdentities + identities.items.filter { p -> Declarations.publicIdentities.none { it.address.equals(p.address, true) } }
    Page(InfraSections.DOMAINS_PUBLIC, "${slice.publicDomains.size} public domains - ${all.size} email identities") {
        if (slice.publicDomains.isEmpty()) {
            DenseRow(InfraTags.row(InfraSections.DOMAINS_PUBLIC, "none"), "None declared", "", listOf("No public domain is declared in the fleet"))
        }
        slice.publicDomains.forEach { d ->
            DenseRow(InfraTags.row(InfraSections.DOMAINS_PUBLIC, d.name), d.name, d.provider.orEmpty(), listOfNotNull(d.role))
        }
        GroupHeader(InfraTags.head(InfraSections.DOMAINS_PUBLIC, "identities"), "Email identities")
        all.forEach { i ->
            DenseRow(InfraTags.row(InfraSections.DOMAINS_PUBLIC, "id:${i.address}"), i.address, i.provider, listOf(i.label))
        }
        identities.note?.let {
            Text(it, color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption,
                modifier = Modifier.testTag(InfraTags.IDENTITIES_NOTE).padding(start = 4.dp, bottom = 2.dp))
        }
        if (identities.needsToken) TokenField(onToken)
    }
}

/** The owner's GitHub token for the private repo: typed once, kept in the encrypted store, never drawn back. */
@Composable
private fun TokenField(onToken: (String) -> Unit) {
    var typed by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    Row(Modifier.fillMaxWidth().padding(start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.OutlinedTextField(
            typed, { typed = it }, Modifier.weight(1f).testTag(InfraTags.IDENTITIES_TOKEN),
            label = { Text("GitHub token (kept encrypted)") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
        )
        androidx.compose.material3.TextButton({ onToken(typed); typed = "" }, enabled = typed.isNotBlank()) { Text("Connect") }
    }
}

// ── Personal: Desktop | Mobile | Gadgets (one device list, split by kind) ────

@Composable
fun DevicesScreen(section: String, slice: FleetModel.Slice) {
    val kinds = FleetModel.personalKinds(section, slice)
    Page(section, "${slice.devices.count { it.kind in kinds }} devices") {
        kinds.forEach { kind ->
            GroupHeader(InfraTags.head(section, kind), FleetModel.kindLabel(kind))
            val ofKind = slice.devices.filter { it.kind == kind }
            if (ofKind.isEmpty()) {
                DenseRow(InfraTags.row(section, "none-$kind"), "None declared", "", listOf("No ${FleetModel.kindLabel(kind).lowercase()} is declared in the fleet"))
            }
            ofKind.forEach { d ->
                DenseRow(
                    InfraTags.row(section, d.id), d.model, FleetModel.kindLabel(d.kind),
                    listOf(
                        "OS: ${d.os ?: "not declared"}",
                        "WireGuard: wg0 ${d.wgIp ?: "-"} | wg-public ${d.wgPublicIp ?: "-"}",
                        "Role: ${d.role ?: "not declared"}",
                    ),
                )
            }
        }
    }
}

// ── Domains ─────────────────────────────────────────────────────────────────

@Composable
fun DomainsScreen(slice: FleetModel.Slice, live: FleetLiveState, today: LocalDate = LocalDate.now()) {
    Page(InfraSections.DOMAINS, "${slice.domains.size} domain - ${slice.domains.sumOf { it.records.size }} declared records") {
        slice.domains.forEach { d ->
            val days = FleetModel.daysUntil(d.expires, today)
            DenseRow(
                InfraTags.row(InfraSections.DOMAINS, d.name), d.name, "registrar ${d.registrar}",
                listOf(
                    "Registrar: ${d.registrar} | Admin: ${d.admin}",
                    "Renewal paid: ${d.renewalPaid} | Expires: ${d.expires}${days?.let { " ($it days)" }.orEmpty()}",
                    "Next renewal: ${d.nextRenewal} (auto-renew ${if (d.autoRenew) "on" else "off"})",
                    "Nameservers: ${d.nameservers.joinToString(", ")}",
                ),
            )
            val groups = FleetModel.MAIL_GROUPS + FleetModel.otherGroups(d)
            groups.forEach { g ->
                val recs = FleetModel.recordsOf(d, g)
                if (recs.isNotEmpty()) {
                    GroupHeader(InfraTags.head(InfraSections.DOMAINS, g), if (g in FleetModel.MAIL_GROUPS) "Mail: $g" else g)
                    if (g == "MTA-STS" && d.mtaStsMode != null) {
                        Text("Policy mode ${d.mtaStsMode}, max_age ${d.mtaStsMaxAge}", color = LocalKitPalette.current.textSecondary,
                            fontSize = KitDensity.caption, modifier = Modifier.padding(start = 4.dp, bottom = 2.dp))
                    }
                    recs.forEachIndexed { i, r ->
                        DenseRow(
                            InfraTags.row(InfraSections.DOMAINS, "${r.type}:${r.name}:$i"), "${r.type}  ${r.name}",
                            listOfNotNull(r.priority?.let { "prio $it" }, r.ttl?.let { "ttl $it" }, r.proxied?.let { if (it) "proxied" else "dns only" }).joinToString(" | "),
                            listOf(r.content, r.comment.orEmpty()),
                        )
                    }
                }
            }
        }
        LiveDnsBlock(live)
    }
}

/** What c3-infra-api sees in the zone (GET /cloud-data/cloudflare-dns), or why it could not say. */
@Composable
private fun LiveDnsBlock(live: FleetLiveState) {
    val recs = live.dns
    val why = live.notes["dns"]
    GroupHeader(InfraTags.head(InfraSections.DOMAINS, "live"), "Live zone view (c3-infra-api)")
    when {
        recs != null -> {
            Text("${recs.size} records seen by the API", color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption,
                modifier = Modifier.testTag(InfraTags.LIVE_NOTE).padding(start = 4.dp, bottom = 2.dp))
            recs.forEachIndexed { i, r ->
                DenseRow(InfraTags.row(InfraSections.DOMAINS, "live:${r.type}:${r.name}:$i"), "${r.type}  ${r.name}", r.comment.orEmpty(), listOf(r.content))
            }
        }
        why != null -> Text("Live view unavailable: $why. Showing the declared records only.", color = LocalKitPalette.current.warn,
            fontSize = KitDensity.caption, modifier = Modifier.testTag(InfraTags.LIVE_NOTE).padding(start = 4.dp, bottom = 2.dp))
        else -> Text("Live view not read yet.", color = LocalKitPalette.current.textSecondary, fontSize = KitDensity.caption,
            modifier = Modifier.testTag(InfraTags.LIVE_NOTE).padding(start = 4.dp, bottom = 2.dp))
    }
}

// ── the dense building blocks ───────────────────────────────────────────────

@Composable
private fun Page(section: String, summary: String, content: @Composable () -> Unit) {
    val p = LocalKitPalette.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp).testTag(InfraTags.screen(section))) {
        Text(summary, color = p.textSecondary, fontSize = KitDensity.caption, modifier = Modifier.padding(start = 4.dp, bottom = 3.dp))
        content()
    }
}

@Composable
private fun GroupHeader(tag: String, title: String) {
    Text(title, color = LocalKitPalette.current.accent, fontSize = KitDensity.caption,
        modifier = Modifier.testTag(tag).padding(start = 4.dp, top = 6.dp, bottom = 2.dp))
}

/** A compact bordered row: a title, an accent on the right, then small lines. Links open in the browser. */
@Composable
private fun DenseRow(tag: String, title: String, accent: String, lines: List<String>, links: List<FleetModel.Link> = emptyList()) {
    val p = LocalKitPalette.current
    val shape = RoundedCornerShape(6.dp)
    val uri = LocalUriHandler.current
    Column(
        Modifier.fillMaxWidth().padding(bottom = 3.dp).background(p.surface, shape).border(1.dp, p.hairline, shape)
            .padding(horizontal = 8.dp, vertical = 4.dp).testTag(tag).semantics(mergeDescendants = true) {},
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, color = p.textPrimary, fontSize = KitDensity.body, modifier = Modifier.weight(1f))
            if (accent.isNotBlank()) Text(accent, color = p.accent, fontSize = KitDensity.caption)
        }
        lines.filter { it.isNotBlank() }.forEach { Text(it, color = p.textSecondary, fontSize = KitDensity.caption) }
        links.forEach { l ->
            Text("${l.label}: ${l.url}", color = p.accent, fontSize = KitDensity.caption,
                modifier = Modifier.clickable { runCatching { uri.openUri(l.url) } })
        }
    }
}

// ── the tab fragment ────────────────────────────────────────────────────────

/** One Infra page as a tab fragment: the baked slice, refreshed live from c3-infra-api when the page opens. */
class InfraSectionFragment : KitComposeFragment() {

    override fun palette(): KitPalette = c3KitPalette(requireContext())

    @Composable
    override fun Content() {
        val section = remember { arguments?.getString(ARG_SECTION).orEmpty() }
        val ctx = LocalContext.current
        val live = remember { FleetLiveState() }
        val slice = remember { Declarations.fleet }
        val identities = remember { PrivateIdentitiesState() }
        val scope = androidx.compose.runtime.rememberCoroutineScope()
        val source = remember { Declarations.privateSources[PRIVATE_IDENTITIES] }
        if (section == InfraSections.DOMAINS_PUBLIC) LaunchedEffect(section) { identities.refresh(ctx.applicationContext, source) }
        LaunchedEffect(section) {
            try {
                live.refresh(slice, ApiFleetLive(ctx.applicationContext))
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                live.notes = live.notes + ("dns" to "${e.javaClass.simpleName}: ${e.message.orEmpty()}")
            }
        }
        InfraSectionScreen(section, slice, live, identities = identities, onToken = { t ->
            PrivateData.saveToken(ctx, t)
            scope.launch { identities.refresh(ctx.applicationContext, source) }
        })
    }

    companion object {
        /** The key of the owner's email identities in build.json ui.private_sources. */
        private const val PRIVATE_IDENTITIES = "identities"
        private const val ARG_SECTION = "infra_section"
        fun newInstance(section: String) = InfraSectionFragment().apply {
            arguments = Bundle().apply { putString(ARG_SECTION, section) }
        }
    }
}
