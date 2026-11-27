package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.cloudc3.cloud.FleetDown
import com.diegonmarcos.superapp.uikit.KitStoreRow
import com.diegonmarcos.superapp.uikit.LocalKitPalette

/**
 * #1969 Infra > Projects: one row per declared project (data/container_projects.json, the Cloud Me > Proj set; Wiki holds the former Lib, #1968).
 * The columns, the filter chips and the row-tap page are DECLARED in container_projects.json::projects_table; this object only fills them.
 * Live status never claims what it did not measure: it is up, down, or unknown WITH its reason.
 */
object InfraProjects {
    const val UP = "up"
    const val DOWN = "down"
    const val UNKNOWN = "unknown"
    const val NONE = "no containers"

    data class Row(
        val id: String, val category: String, val project: String, val pub: Int, val pvt: Int, val containers: Int,
        val vms: List<String>, val domains: List<String>, val status: String, val reason: String, val githubLinks: Int, val pagesLinks: Int, val page: String,
    ) { val repos: Int get() = pub + pvt }

    fun rows(decl: ContainerProjects.Decl, catalog: ContainersModel.Catalog, domainNames: List<String>, report: FleetDown.Report?, measured: Set<String>): List<Row> =
        decl.projects.map { p ->
            val ents = ContainerProjects.scope(decl, catalog, p.id).entries
            val boxes = ents.filter { it.role != "repo" }
            val repos = decl.repos.filter { r -> r.attached.any { it.project == p.id } }
            val text = boxes.joinToString(" ") { "${it.dns} ${it.publicUrl}" }
            val (status, reason) = statusOf(boxes, report, measured)
            Row(p.id, p.category, p.label.removePrefix("${p.category}_"), repos.count { it.visibility == "pub" }, repos.count { it.visibility != "pub" }, boxes.size,
                boxes.map { it.host }.filter { it.isNotBlank() }.distinct().sorted(), domainNames.filter { it in text }.distinct(), status, reason,
                repos.count { it.githubUrl.isNotBlank() }, repos.count { it.pagesUrl.isNotBlank() }, decl.openPage.replace("{project}", p.id))
        }

    /** (status, reason): up / down / unknown with the reason / no containers; the reason is blank only for a measured answer. */
    fun statusOf(boxes: List<ContainersModel.Entry>, report: FleetDown.Report?, measured: Set<String>): Pair<String, String> {
        if (boxes.isEmpty()) return NONE to "no container is declared on this project"
        return when (report) {
            null -> UNKNOWN to "not measured yet"
            is FleetDown.Report.Unreachable -> UNKNOWN to "${report.kind.name}: ${report.detail}"
            FleetDown.Report.NothingDeclared -> UNKNOWN to "no container is declared on a mesh VM"
            is FleetDown.Report.Measured -> {
                val mine = boxes.map { it.name }.filter { it in measured }
                val downNames = report.down.values.flatten().map { it.name }.toSet()
                val down = mine.count { it in downNames }
                val blind = boxes.filter { it.name in measured && it.host in report.blind.keys }.size
                when {
                    down > 0 -> DOWN to "$down of ${mine.size} measured containers are not running"
                    mine.isEmpty() -> UNKNOWN to "none of its ${boxes.size} containers is read by the live check"
                    blind > 0 -> UNKNOWN to "$blind containers sit on a VM the API could not list"
                    else -> UP to ""
                }
            }
        }
    }

    fun cell(r: Row, key: String): String = when (key) {
        "category" -> r.category
        "project" -> r.project
        "repos" -> "${r.repos} (${r.pub}/${r.pvt})"
        "containers" -> r.containers.toString()
        "vms" -> r.vms.joinToString(", ").ifBlank { "-" }
        "domains" -> r.domains.joinToString(", ").ifBlank { "-" }
        "status" -> if (r.reason.isBlank()) r.status else "${r.status}: ${r.reason}"
        "links" -> "GitHub ${r.githubLinks} - Pages ${r.pagesLinks}"
        else -> ""
    }

    /** The value a column sorts by (numbers numerically, text alphabetically). */
    private fun sortKey(r: Row, key: String): Comparable<*> = when (key) {
        "repos" -> r.repos; "containers" -> r.containers; "vms" -> r.vms.size; "domains" -> r.domains.size; "links" -> r.githubLinks + r.pagesLinks
        "status" -> r.status; else -> cell(r, key).lowercase()
    }

    @Suppress("UNCHECKED_CAST")
    fun sort(rows: List<Row>, key: String?, asc: Boolean): List<Row> =
        if (key == null) rows else rows.sortedWith { a, b -> (sortKey(a, key) as Comparable<Any>).compareTo(sortKey(b, key)).let { if (asc) it else -it } }

    fun filter(decl: ContainerProjects.Decl, rows: List<Row>, query: String, picks: Map<String, String>): List<Row> {
        val q = query.trim().lowercase()
        return rows.filter { r ->
            picks.all { (k, v) -> if (k == "status") r.status == v else cell(r, k) == v } &&
                (q.isEmpty() || decl.projectsColumns.any { (k, _) -> cell(r, k).lowercase().contains(q) })
        }
    }

    fun choices(rows: List<Row>, key: String): List<String> = rows.map { if (key == "status") it.status else cell(it, key) }.distinct().sorted()
}

object ProjectsTags {
    const val SCREEN = "infra_projects_screen"
    const val SEARCH = "infra_projects_search"
    fun row(id: String) = "infra_projects_row_$id"
    fun head(key: String) = "infra_projects_head_$key"
    fun filter(key: String, v: String) = "infra_projects_filter_${key}_$v"
}

private fun mono(size: Float, c: Color, w: FontWeight = FontWeight.Normal) = TextStyle(fontFamily = FontFamily.Monospace, fontSize = size.sp, color = c, fontWeight = w)

/** The Projects table: search, filter chips, sortable headers, a row tap opens that project's Containers topology through [onOpen] ("page:c3/<id>"). */
@Composable
fun ProjectsScreen(decl: ContainerProjects.Decl, catalog: ContainersModel.Catalog, domainNames: List<String>, report: FleetDown.Report?, measured: Set<String>, onOpen: (String) -> Unit = {}) {
    val p = LocalKitPalette.current
    var query by remember { mutableStateOf("") }
    var sortKey by remember { mutableStateOf<String?>(null) }
    var asc by remember { mutableStateOf(true) }
    val picked = remember { mutableStateMapOf<String, String>() }
    val all = remember(decl, catalog, domainNames, report, measured) { InfraProjects.rows(decl, catalog, domainNames, report, measured) }
    val shown = InfraProjects.sort(InfraProjects.filter(decl, all, query, picked.toMap()), sortKey, asc)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag(InfraTags.screen(InfraSections.PROJECTS))) {
        Text("${shown.size}/${all.size} projects", color = p.accent, fontSize = KitStoreRow.meta, modifier = Modifier.padding(KitStoreRow.padH, 4.dp))
        BasicTextField(query, { query = it }, singleLine = true, textStyle = mono(13f, p.textPrimary), cursorBrush = SolidColor(p.accent),
            modifier = Modifier.fillMaxWidth().padding(KitStoreRow.padH, 4.dp).border(1.dp, p.hairline, RoundedCornerShape(6.dp)).padding(8.dp).testTag(ProjectsTags.SEARCH),
            decorationBox = { inner -> if (query.isEmpty()) Text("search the projects", style = mono(13f, p.textSecondary)); inner() })
        decl.projectsFilters.forEach { f ->
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH)) {
                Text("$f ", style = mono(11f, p.textSecondary), modifier = Modifier.padding(end = 4.dp, top = 4.dp))
                InfraProjects.choices(all, f).forEach { v ->
                    val on = picked[f] == v
                    Text(v, style = mono(11f, if (on) p.tileInk else p.textPrimary),
                        modifier = Modifier.padding(end = 4.dp).background(if (on) p.textPrimary else Color.Transparent, RoundedCornerShape(4.dp))
                            .border(1.dp, p.hairline, RoundedCornerShape(4.dp)).clickable { if (on) picked.remove(f) else picked[f] = v }.padding(6.dp, 3.dp).testTag(ProjectsTags.filter(f, v)))
                }
            }
        }
        Column(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = KitStoreRow.padH)) {
            Row(Modifier.background(p.surfaceSelected)) {
                decl.projectsColumns.forEach { (k, label) ->
                    Text(label + if (sortKey == k) (if (asc) " ^" else " v") else "", style = mono(11f, p.accent, FontWeight.Bold),
                        modifier = Modifier.width(150.dp).clickable { if (sortKey == k) asc = !asc else { sortKey = k; asc = true } }.padding(4.dp).testTag(ProjectsTags.head(k)))
                }
            }
            if (shown.isEmpty()) Text("no project matches the search and filters", style = mono(11f, p.textSecondary), modifier = Modifier.padding(4.dp))
            shown.forEach { r ->
                Row(Modifier.border(0.5.dp, p.hairline).clickable { onOpen("page:c3/${r.page}") }.testTag(ProjectsTags.row(r.id))) {
                    decl.projectsColumns.forEach { (k, _) ->
                        val v = InfraProjects.cell(r, k)
                        val ink = if (k == "status") when (r.status) { InfraProjects.UP -> p.ok; InfraProjects.DOWN -> p.bad; else -> p.textSecondary } else p.textPrimary
                        Text(v, style = mono(11f, ink), modifier = Modifier.width(150.dp).padding(4.dp))
                    }
                }
            }
        }
    }
}
