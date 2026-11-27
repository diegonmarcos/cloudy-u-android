package com.diegonmarcos.cloudc3.pages

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.diegonmarcos.superapp.bottomnav.NavGroup
import com.diegonmarcos.superapp.bottomnav.NavPage
import com.diegonmarcos.superapp.bottomnav.NavSection
import com.diegonmarcos.superapp.bottomnav.SecondRowMode
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRow
import com.diegonmarcos.superapp.navbartop2.NavBarTopSecondRows

/** Test tags of the Containers page's rows (#1941; #1982 the Projects and VM rows are one two-row strip, tagged ROW_CATEGORY). */
object ContainersTags {
    const val ROW_VM = "containers_row_vm"
    const val ROW_CATEGORY = "containers_row_category"
    const val ROW_PROJECT = "containers_row_project"
    const val ROW_VIEW = "containers_row_view"
    const val ROW_FILTERS = "containers_row_filters"
    const val BODY = "containers_body"
    const val TOPO_FILTERS = "containers_topology_filters"
    fun topoLight(id: String) = "containers_topo_light_$id"
    fun topoVm(vm: String) = "containers_topo_vm_$vm"
    fun topoType(type: String) = "containers_topo_type_$type"
    /** #1953 the Topology body: a band (services, repos), a section (app, db, mcp, api, other, repo), a part (pub, pvt, all), an empty band's line. */
    fun topoBand(id: String) = "containers_topo_band_$id"
    fun topoBandEmpty(id: String) = "containers_topo_band_empty_$id"
    fun topoSection(id: String) = "containers_topo_section_$id"
    fun topoPart(section: String, part: String) = "containers_topo_part_${section}_$part"
}

/**
 * #1941 the Containers page's layout, top to bottom: 1) the project category (the Cloud Me Proj groups, plus All), 2) the project
 * names of that category, 3) Topology | Table, 4) the page's own Filters row ([filters]; the Table draws its own inside [content]), then the
 * [content]. The rows are the fleet's shared strip (libs:ui-nav-bars NavBarTopSecondRow), the one Cloud Me draws.
 */
@Composable
fun ContainersChrome(
    categories: List<String>, selectedCategory: String, onCategory: (String) -> Unit,
    projects: List<NavPage>, selectedProject: String?, onProject: (NavPage) -> Unit,
    views: List<NavPage>, selectedView: String?, onView: (NavPage) -> Unit,
    filters: @Composable () -> Unit = {},
    /** #1982 the VM row (its first chip is All): shown together with the categories as the two labelled rows Projects | ... and VM | ... (they filter together, AND). */
    vmPages: List<NavPage> = emptyList(), selectedVm: String? = null, onVm: (NavPage) -> Unit = {},
    projectsLabel: String = "Projects", vmLabel: String = "VM",
    content: @Composable ColumnScope.(Modifier) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        if (categories.isNotEmpty() && vmPages.isNotEmpty()) Box(Modifier.testTag(ContainersTags.ROW_CATEGORY)) {
            // #1982 the fleet's labelled many-rows strip (libs:ui-nav-bars, as Cloud Me Pers): "Projects | All | Unix | ..." over "VM | All | oci-apps | ...", both always drawn, each with its own selection
            val catPages = categories.map { NavPage(it, it, "ic_p_c3_stack") }
            val section = NavSection(
                "containers_rows", "Containers", "ic_p_c3_stack", catPages + vmPages, rows = SecondRowMode.ManyRows,
                groups = listOf(NavGroup("projects", projectsLabel, catPages), NavGroup("vm", vmLabel, vmPages)),
            )
            NavBarTopSecondRows(
                section = section, selectedId = null, selectedIds = setOfNotNull(selectedCategory, selectedVm),
                onSelect = { p -> if (vmPages.any { it.id == p.id }) onVm(p) else onCategory(p.id) }, underTopChrome = false,
            )
        } else if (categories.isNotEmpty()) Box(Modifier.testTag(ContainersTags.ROW_CATEGORY)) {
            NavBarTopSecondRow(
                pages = categories.map { NavPage(it, it, "ic_p_c3_stack") }, selectedId = selectedCategory,
                onSelect = { onCategory(it.id) }, underTopChrome = false,
            )
        }
        if (projects.isNotEmpty()) Box(Modifier.testTag(ContainersTags.ROW_PROJECT)) {
            NavBarTopSecondRow(pages = projects, selectedId = selectedProject, onSelect = onProject, underTopChrome = false)
        }
        if (views.isNotEmpty()) Box(Modifier.testTag(ContainersTags.ROW_VIEW)) {
            NavBarTopSecondRow(pages = views, selectedId = selectedView, onSelect = onView, underTopChrome = false)
        }
        Box(Modifier.fillMaxWidth().testTag(ContainersTags.ROW_FILTERS)) { filters() }
        content(Modifier.weight(1f).fillMaxWidth().testTag(ContainersTags.BODY))
    }
}

/** The Topology's Filters row (Status, VM, Type): the selection lives in [ContainerFilter], read by the container dashboards. */
@Composable
fun TopologyFilters(vms: List<String>, types: List<String>) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp).testTag(ContainersTags.TOPO_FILTERS)) {
        ChipRow("Status") {
            ProbeModel.Light.entries.forEach { l ->
                Chip(l.label, l.id in ContainerFilter.lights, ContainersTags.topoLight(l.id)) { ContainerFilter.lights = ContainerFilter.toggled(ContainerFilter.lights, l.id) }
            }
        }
        ChipRow("VM") {
            vms.forEach { vm -> Chip(vm, vm in ContainerFilter.vms, ContainersTags.topoVm(vm)) { ContainerFilter.vms = ContainerFilter.toggled(ContainerFilter.vms, vm) } }
        }
        ChipRow("Type") {
            types.forEach { ty -> Chip(ty, ty in ContainerFilter.types, ContainersTags.topoType(ty)) { ContainerFilter.types = ContainerFilter.toggled(ContainerFilter.types, ty) } }
        }
    }
}

/** The first two rows' logic, pure: which category a page is in and which pages a category lists. The All page (the one that is no declared project) is its own category, named by its label. */
object ContainersNav {
    private fun allPage(decl: ContainerProjects.Decl, declared: List<NavPage>) = declared.firstOrNull { decl.project(it.id) == null }

    /** The All page (the declared page that is no project). */
    fun allPageOf(decl: ContainerProjects.Decl, declared: List<NavPage>): NavPage = allPage(decl, declared) ?: declared.first()

    /** #1982 the scope id of the two rows: the project page, or the category when the All page is chosen, AND the VM page ([vmPage] blank or the All VM page = no filter on the VM row); blank = no filter at all. */
    fun scope(decl: ContainerProjects.Decl, projectPage: String, category: String, vmPage: String): String =
        listOfNotNull(
            projectPage.takeIf { decl.project(it) != null } ?: category.takeIf { it.isNotBlank() }?.let { ContainerProjects.CAT + it },
            vmPage.takeIf { it.isNotBlank() && it != decl.vm.prefix + decl.vm.all },
        ).joinToString(ContainerProjects.SCOPE_SEP)

    /** #1982 the project-name row under the Projects row: the declared projects of [category]; none for All (no filter on the row). */
    fun subProjects(decl: ContainerProjects.Decl, declared: List<NavPage>, category: String): List<NavPage> =
        if (category == allPage(decl, declared)?.label) emptyList() else pagesOf(decl, declared, category)

    /** Row 1: All first, then the declared categories (the Cloud Me Proj groups; Lib is Wiki since #1968). */
    fun categories(decl: ContainerProjects.Decl, declared: List<NavPage>): List<String> = listOfNotNull(allPage(decl, declared)?.label) + decl.categories()

    /** The category of the top page [top]. */
    fun categoryOf(decl: ContainerProjects.Decl, declared: List<NavPage>, top: String): String =
        decl.project(top)?.category ?: allPage(decl, declared)?.label.orEmpty()

    /** Row 2: the declared pages of [category]. */
    fun pagesOf(decl: ContainerProjects.Decl, declared: List<NavPage>, category: String): List<NavPage> =
        if (category == allPage(decl, declared)?.label) listOfNotNull(allPage(decl, declared))
        else decl.projectsOf(category).mapNotNull { p -> declared.firstOrNull { it.id == p.id } }
}
