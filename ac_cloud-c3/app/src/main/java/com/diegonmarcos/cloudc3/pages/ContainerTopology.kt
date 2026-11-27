package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.diegonmarcos.superapp.uikit.LocalKitPalette
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #1953 the Containers > Topology body as a pure plan of data/container_projects.json `topology_layout`: two bands (services: Apps, DB, MCP, API,
 * Other; then Repos), each a list of sections bound to Types, each section split in Public / Private parts where the project declares it, each part
 * the old `<group> · <category>` sub-headers with their entity names. Nothing is typed here: bands, sections, labels and the split are declared.
 */
object ContainerTopology {

    data class Sub(val label: String, val names: List<String>)
    data class Part(val id: String, val label: String, val subs: List<Sub>) { val count: Int get() = subs.sumOf { it.names.size } }
    data class Section(val id: String, val label: String, val types: List<String>, val parts: List<Part>) { val count: Int get() = parts.sumOf { it.count } }
    data class Band(val id: String, val label: String, val sections: List<Section>, val note: String = "") { val count: Int get() = sections.sumOf { it.count } }
    /** [paths] = an attached repo's path inside the repo on this project (lib-chronos -> 1.Modern-science), drawn as the tile's second line. */
    data class Plan(val bands: List<Band>, val paths: Map<String, String> = emptyMap()) {
        fun section(id: String): Section? = bands.flatMap { it.sections }.firstOrNull { it.id == id }
        fun names(): List<String> = bands.flatMap { b -> b.sections.flatMap { s -> s.parts.flatMap { p -> p.subs.flatMap { it.names } } } }
    }

    data class SectionDecl(val id: String, val label: String, val types: List<String>)
    data class BandDecl(val id: String, val label: String, val sections: List<SectionDecl>)
    data class Layout(val bands: List<BandDecl>, val splitLabels: Map<String, String>, val split: Map<String, List<String>>)

    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.l(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.objs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun layout(decl: ContainerProjects.Decl): Layout {
        val o = decl.layout ?: return Layout(emptyList(), emptyMap(), emptyMap())
        val bands = o.objs("bands").map { b -> BandDecl(b.s("id"), b.s("label"), b.objs("sections").map { SectionDecl(it.s("id"), it.s("label"), it.l("types")) }) }
        val labels = (o["split_labels"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonPrimitive)?.contentOrNull?.let { k to it } }.toMap()
        val split = (o["split"] as? JsonObject).orEmpty().mapNotNull { (k, v) -> (v as? JsonObject)?.let { k to it.l("sections") } }.toMap()
        return Layout(bands, labels, split)
    }

    /**
     * The plan of [scoped] (ContainerProjects.scope of the project, its filters applied) for [projectId] (null = All). Every entity of [scoped] is drawn
     * exactly once: in the section whose types hold its Type, else (an undeclared Type) in the section holding the last declared Type (Other).
     */
    fun plan(decl: ContainerProjects.Decl, scoped: ContainersModel.Catalog, projectId: String?): Plan {
        val lay = layout(decl)
        val all = lay.bands.flatMap { it.sections }
        val fallback = all.firstOrNull { decl.types.lastOrNull() in it.types }?.id ?: all.lastOrNull()?.id
        val sectionOf = scoped.entries.associate { e -> e.name to (all.firstOrNull { decl.typeOf(e) in it.types }?.id ?: fallback) }
        val byName = scoped.entries.associateBy { it.name }
        val splitHere = projectId?.let { lay.split[it] }.orEmpty()
        fun subsOf(sectionId: String, keep: (ContainersModel.Entry) -> Boolean): List<Sub> = scoped.groups.flatMap { g ->
            g.categories.mapNotNull { cat ->
                val names = cat.names.filter { n -> sectionOf[n] == sectionId && byName[n]?.let(keep) == true }
                if (names.isEmpty()) null else Sub("${g.label} · ${cat.label}", names)
            }
        }
        val bands = lay.bands.map { b ->
            Band(b.id, b.label, b.sections.map { s ->
                val parts = if (s.id in splitHere) listOf(
                    Part("pub", lay.splitLabels["pub"] ?: "pub", subsOf(s.id) { it.isPublic }),
                    Part("pvt", lay.splitLabels["pvt"] ?: "pvt", subsOf(s.id) { !it.isPublic }),
                ) else listOf(Part("all", "", subsOf(s.id) { true }))
                Section(s.id, s.label, s.types, parts)
            })
        }
        val paths = if (projectId == null) emptyMap() else scoped.entries.mapNotNull { e ->
            decl.repoOf(e)?.attached?.firstOrNull { it.project == projectId && it.path.isNotBlank() }?.let { e.name to it.path }
        }.toMap()
        // #1971 VM pages: the repos band is "Not deployed (repos)" with its reason (All only); the unknown-host band names each undeclared host with its reason
        val vm = decl.vm
        val out = if (projectId == null || !decl.isVmPage(projectId)) bands else bands.map { b ->
            when {
                b.id == vm.reposBand && vm.reposBandLabel.isNotBlank() -> b.copy(label = vm.reposBandLabel, note = vm.reposReason)
                projectId == vm.prefix + vm.unknown && b.id != vm.reposBand ->
                    b.copy(note = scoped.entries.groupBy { it.host }.entries.joinToString(" ") { (h, es) -> vm.unknownReason.replace("{host}", "\"$h\"") + " (${es.size})" })
                else -> b
            }
        }
        return Plan(out, paths)
    }

    /** The one line a band with nothing in it prints (the separation always shows). */
    fun emptyNote(band: Band, projectLabel: String): String =
        "No ${band.sections.joinToString(", ") { it.label }} declared for $projectLabel"

    /** Parity: every (name, group, category) of [scoped] that the plan does NOT draw under its `<group> · <category>` sub-header; empty = nothing lost. */
    fun parity(plan: Plan, scoped: ContainersModel.Catalog): List<String> {
        val drawn = plan.bands.flatMap { b -> b.sections.flatMap { s -> s.parts.flatMap { p -> p.subs.flatMap { sub -> sub.names.map { sub.label to it } } } } }
        val want = scoped.groups.flatMap { g -> g.categories.flatMap { c -> c.names.map { "${g.label} · ${c.label}" to it } } }
        val lost = (want - drawn.toSet()).map { (l, n) -> "$n under $l" }
        val twice = drawn.groupingBy { it.second }.eachCount().filter { it.value > 1 }.keys.map { "$it drawn twice" }
        return lost + twice
    }
}

/** #1953 a band heading (Apps / DB / MCP / API, Repos) with its count. */
@Composable
fun TopologyBandHeader(band: ContainerTopology.Band, modifier: Modifier = Modifier) {
    val p = LocalKitPalette.current
    Text("${band.label}  ${band.count}", color = p.accent, fontSize = 20.sp, fontWeight = FontWeight.Bold,
        modifier = modifier.testTag(ContainersTags.topoBand(band.id)).padding(start = 4.dp, top = 12.dp, end = 4.dp, bottom = 6.dp))
    if (band.note.isNotBlank()) Text(band.note, color = p.textSecondary, fontSize = 12.sp, modifier = Modifier.testTag(ContainersTags.topoBand(band.id) + "_note").padding(start = 4.dp, bottom = 6.dp))
}

/** #1953 the line a band with no entity prints instead of vanishing. */
@Composable
fun TopologyBandEmpty(band: ContainerTopology.Band, projectLabel: String) {
    val p = LocalKitPalette.current
    Text(ContainerTopology.emptyNote(band, projectLabel), color = p.textSecondary, fontSize = 12.sp,
        modifier = Modifier.testTag(ContainersTags.topoBandEmpty(band.id)).padding(start = 4.dp, bottom = 8.dp))
}

/**
 * #1953 one section's body: its parts (Public / Private where declared, with counts), each part's `<group> · <category>` sub-headers, and the
 * entity tiles drawn by [tiles] (the fragment's own grid, lights and tap), so the tiles are unchanged.
 */
@Composable
fun TopologySectionBody(
    section: ContainerTopology.Section,
    tiles: @Composable (names: List<String>) -> Unit,
) {
    val p = LocalKitPalette.current
    Column(Modifier.testTag(ContainersTags.topoSection(section.id))) {
        for (part in section.parts) {
            Column(Modifier.testTag(ContainersTags.topoPart(section.id, part.id))) {
                if (part.label.isNotBlank()) Text("${part.label} (${part.count})", color = p.accent, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(top = 14.dp, bottom = 4.dp))
                for (sub in part.subs) {
                    Text(sub.label, color = p.textSecondary, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp))
                    tiles(sub.names)
                }
            }
        }
    }
}
