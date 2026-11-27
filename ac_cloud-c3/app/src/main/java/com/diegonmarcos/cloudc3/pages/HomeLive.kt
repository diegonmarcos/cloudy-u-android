package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.cloudc3.Declarations
import com.diegonmarcos.cloudc3.cloud.DaguRunsFeed
import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.cloudc3.cloud.StatusLight
import com.diegonmarcos.superapp.bottomnav.NavDecl
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1969 Home: one dense row per declared section and page with a live summary and a light; a tap opens the page.
 * Which source feeds a row is DECLARED in build.json::ui.home (section_rows / section_pages / pages / default); the rows themselves come from the
 * nav declaration, so declaring a page adds its row with no edit. Every source is one the pages already use (FleetDown, GitHubFeed, DaguRunsFeed,
 * OpsLive, the mail stack answer); a source that could not be read shows its REASON on the row, never a zero.
 */
object HomeLive {
    data class Decl(val default: String = "declared", val sectionRows: Map<String, String> = emptyMap(), val sectionPages: Map<String, String> = emptyMap(), val pages: Map<String, String> = emptyMap())

    val SOURCES = setOf("declared", "containers_project", "infra_vms", "infra_domains", "projects_status", "orch_runs", "dagu_runs", "ops_reports", "ops_uptime", "mail_state")

    /** What one source read: a value, or why it could not be read. */
    sealed class Live<out T> {
        object Pending : Live<Nothing>()
        data class Ok<T>(val value: T) : Live<T>()
        data class Failed(val why: String) : Live<Nothing>()
    }

    data class Inputs(
        val down: Live<FleetDown.Report> = Live.Pending,
        val runs: Live<List<GitHubFeed.Run>> = Live.Pending,
        val dagu: Live<List<DaguRunsFeed.Run>> = Live.Pending,
        val uptime: Live<List<OpsLive.Uptime>> = Live.Pending,
        val reports: Live<List<OpsLive.Report>> = Live.Pending,
        val mail: Live<MailStack.Answer> = Live.Pending,
        val measured: Set<String> = emptySet(),
    )

    data class Reading(val light: StatusLight.State?, val text: String)
    data class Row(val section: String, val page: String?, val depth: Int, val label: String, val source: String, val target: String, val reading: Reading)

    fun parse(text: String): Decl {
        val o = Declarations.element(text) as? kotlinx.serialization.json.JsonObject ?: return Decl()
        fun map(k: String) = (o[k] as? kotlinx.serialization.json.JsonObject)?.mapNotNull { (a, v) -> (v as? kotlinx.serialization.json.JsonPrimitive)?.content?.let { a to it } }?.toMap().orEmpty()
        return Decl((o["default"] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: "declared", map("section_rows"), map("section_pages"), map("pages"))
    }

    fun sourceOf(d: Decl, section: String, page: String?): String =
        if (page == null) d.sectionRows[section] ?: d.default else d.pages[page] ?: d.sectionPages[section] ?: d.default

    private fun flat(pages: List<NavPage>, depth: Int = 0): List<Pair<NavPage, Int>> = pages.flatMap { listOf(it to depth) + flat(it.pages, depth + 1) }

    /** One row per declared section (Home excluded) and per page at every depth, in the declared order. */
    fun rows(nav: NavDecl, d: Decl, inputs: Inputs, decl: ContainerProjects.Decl, catalog: ContainersModel.Catalog, slice: FleetModel.Slice): List<Row> =
        nav.sections.filter { it.id != "home" }.flatMap { s ->
            val src = sourceOf(d, s.id, null)
            listOf(Row(s.id, null, 0, s.label, src, s.id, read(src, s.id, null, s.allPages().size, inputs, decl, catalog, slice))) +
                flat(s.pages).map { (p, depth) ->
                    val ps = sourceOf(d, s.id, p.id)
                    Row(s.id, p.id, depth + 1, p.label, ps, "page:c3/${p.id}", read(ps, s.id, p.id, p.pages.size, inputs, decl, catalog, slice))
                }
        }

    private fun <T> unread(l: Live<T>, what: String): Reading? = when (l) {
        Live.Pending -> Reading(StatusLight.State.UNKNOWN, "$what: reading...")
        is Live.Failed -> Reading(StatusLight.State.UNKNOWN, "$what unavailable: ${l.why}")
        is Live.Ok -> null
    }

    fun read(source: String, section: String, page: String?, children: Int, i: Inputs, decl: ContainerProjects.Decl, catalog: ContainersModel.Catalog, slice: FleetModel.Slice): Reading = when (source) {
        "containers_project" -> {
            val pid = page?.removeSuffix("_topology")?.removeSuffix("_table")?.takeIf { it != "all" }
            val boxes = ContainerProjects.scope(decl, catalog, pid).entries.filter { it.role != "repo" }
            val repos = ContainerProjects.scope(decl, catalog, pid).entries.count { it.role == "repo" }
            val d = i.down
            val (st, why) = if (boxes.isEmpty()) InfraProjects.NONE to "" else when (d) {
                is Live.Failed -> InfraProjects.UNKNOWN to d.why
                Live.Pending -> InfraProjects.UNKNOWN to "reading..."
                is Live.Ok -> InfraProjects.statusOf(boxes, d.value, i.measured)
            }
            Reading(when (st) { InfraProjects.UP -> StatusLight.State.ON; InfraProjects.DOWN -> StatusLight.State.OFF; InfraProjects.NONE -> null; else -> StatusLight.State.UNKNOWN },
                "${boxes.size} containers, $repos repos - " + if (why.isBlank()) st else "$st ($why)")
        }
        "infra_vms" -> unread(i.down, "VMs") ?: ((i.down as Live.Ok).value.let { r -> when (r) {
            is FleetDown.Report.Measured -> {
                val vms = r.declaredPerVm.size; val blind = r.blind.size
                Reading(if (blind == 0) StatusLight.State.ON else StatusLight.State.UNKNOWN, "$vms VMs declared, ${vms - blind} reachable" + if (blind > 0) " (cannot list: ${r.blind.keys.joinToString()})" else "")
            }
            is FleetDown.Report.Unreachable -> Reading(StatusLight.State.UNKNOWN, "VMs unavailable: ${r.kind.name}: ${r.detail}")
            FleetDown.Report.NothingDeclared -> Reading(StatusLight.State.UNKNOWN, "VMs: no container is declared on a mesh VM")
        } })
        "infra_domains" -> Reading(null, "${slice.domains.size} domains, ${slice.domains.sumOf { it.records.size }} records, ${slice.publicDomains.size} public mailbox domains")
        "projects_status" -> {
            val rows = InfraProjects.rows(decl, catalog, slice.domains.map { it.name }, (i.down as? Live.Ok)?.value, i.measured)
            val by = rows.groupingBy { it.status }.eachCount()
            val unreadable = (i.down as? Live.Failed)?.why ?: if (i.down == Live.Pending) "reading..." else ""
            Reading(when { (by[InfraProjects.DOWN] ?: 0) > 0 -> StatusLight.State.OFF; (by[InfraProjects.UNKNOWN] ?: 0) > 0 -> StatusLight.State.UNKNOWN; else -> StatusLight.State.ON },
                "${rows.size} projects: ${by[InfraProjects.UP] ?: 0} up, ${by[InfraProjects.DOWN] ?: 0} down, ${by[InfraProjects.UNKNOWN] ?: 0} unknown" + if (unreadable.isNotBlank()) " ($unreadable)" else "")
        }
        "orch_runs" -> unread(i.runs, "GitHub runs") ?: (i.runs as Live.Ok).value.let { r ->
            if (r.isEmpty()) Reading(StatusLight.State.UNKNOWN, "no run among the latest")
            else { val bad = r.count { it.conclusion == "failure" }
                Reading(if (bad > 0) StatusLight.State.OFF else StatusLight.State.ON, "last ${r.size} runs: $bad failing, newest ${GitHubFeed.ago(System.currentTimeMillis() - r.first().tsMillis)} ago") }
        }
        "dagu_runs" -> unread(i.dagu, "Dagu runs") ?: (i.dagu as Live.Ok).value.let { r ->
            if (r.isEmpty()) Reading(StatusLight.State.UNKNOWN, "no run recorded")
            else { val bad = r.count { it.status == 2 }
                Reading(if (bad > 0) StatusLight.State.OFF else StatusLight.State.ON, "last ${r.size} runs: $bad failed") }
        }
        "ops_reports" -> unread(i.reports, "Reports") ?: (i.reports as Live.Ok).value.let { r ->
            val bad = r.count { !it.ok || it.stale }
            Reading(if (bad > 0) StatusLight.State.OFF else StatusLight.State.ON, "${r.size} reports: $bad failing or stale")
        }
        "ops_uptime" -> unread(i.uptime, "Uptime") ?: (i.uptime as Live.Ok).value.let { u ->
            val low = u.filter { it.percent < 99.0 }
            Reading(if (low.isEmpty()) StatusLight.State.ON else StatusLight.State.OFF, "${u.size} VMs: ${low.size} under 99% uptime")
        }
        "mail_state" -> unread(i.mail, "Mail stack") ?: (i.mail as Live.Ok).value.let { a ->
            val bad = a.probes.values.count { it.state == MailStack.Health.BAD }; val warn = a.probes.values.count { it.state == MailStack.Health.WARN }
            Reading(when { bad > 0 -> StatusLight.State.OFF; warn > 0 -> StatusLight.State.UNKNOWN; else -> StatusLight.State.ON }, "${a.probes.size} probes: $bad bad, $warn warn" + a.generatedAt.takeIf { it.isNotBlank() }?.let { ", updated $it" }.orEmpty())
        }
        else -> Reading(null, if (children > 0) "$children subpages" else "declared")
    }
}

object HomeTags {
    fun row(section: String, page: String?) = "home_row_${section}_${page ?: "section"}"
}

/** The rows, dense, one line each: light glyph, label, summary. A tap hands the row's target (a tab id or `page:c3/<id>`) to [onOpen]. */
@Composable
fun ColumnScope.HomePagesCard(rows: List<HomeLive.Row>, onOpen: (String) -> Unit) {
    val ctx = LocalContext.current
    val p = LocalKitPalette.current
    C3Card {
        Text("Pages", color = p.accent, style = MaterialTheme.typography.titleMedium)
        rows.forEach { r ->
            val colour = r.reading.light?.let { Color(StatusLight.colour(ctx, it)) } ?: p.textSecondary
            val glyph = r.reading.light?.let { StatusLight.glyph(it) } ?: "-"
            Text("$glyph ${r.label} - ${r.reading.text}", color = if (r.page == null) p.accent else colour, style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.fillMaxWidth().padding(start = (r.depth * 10).dp, top = 3.dp, bottom = 3.dp).clickable { onOpen(r.target) }.testTag(HomeTags.row(r.section, r.page)))
        }
    }
}

/** Reads the Home sources the pages already read (GitHubFeed, DaguRunsFeed, OpsLive, the mail stack answer); each failure keeps its reason. FleetDown is read by the Home page itself. */
object HomeLiveLoader {
    suspend fun load(ctx: android.content.Context, release: TopologyModel.ReleaseSource?): HomeLive.Inputs {
        val src = ApiFleetLive(ctx)
        val runs: HomeLive.Live<List<GitHubFeed.Run>> = if (release == null) HomeLive.Live.Failed("ui.topology.release is not declared") else {
            val feed = GitHubFeed.runs(ctx, release.owner, release.repo, perPage = 30)
            val r = feed.items.filter { it.name.contains(release.workflow, ignoreCase = true) }.take(8)
            if (feed.status != GitHubFeed.Status.OK && r.isEmpty()) HomeLive.Live.Failed(if (feed.status == GitHubFeed.Status.RATE_LIMITED) "GitHub rate limit reached" else "GitHub unreachable") else HomeLive.Live.Ok(r)
        }
        val dagu: HomeLive.Live<List<DaguRunsFeed.Run>> = runCatching {
            val prefs = com.diegonmarcos.superapp.ops.dagu.DaguPrefs(ctx)
            DaguRunsFeed.recentRuns(prefs.serverUrl, prefs.bearerToken, 10)
        }.fold({ HomeLive.Live.Ok(it) }, { HomeLive.Live.Failed(it.message ?: it.javaClass.simpleName) })
        suspend fun <T> via(path: String, parse: (String) -> T?): HomeLive.Live<T> = when (val r = src.get(path)) {
            is FleetLive.Result.Body -> parse(r.text)?.let { HomeLive.Live.Ok(it) } ?: HomeLive.Live.Failed("unreadable answer from $path")
            is FleetLive.Result.Failed -> HomeLive.Live.Failed(r.why)
        }
        return HomeLive.Inputs(
            runs = runs, dagu = dagu,
            uptime = via(OpsLive.UPTIME_PATH) { OpsLive.parseUptime(it) }, reports = via(OpsLive.REPORTS_PATH) { OpsLive.parseReports(it) },
            mail = via(Declarations.mailStack.apiPath) { MailStack.parseAnswer(it) },
        )
    }
}
