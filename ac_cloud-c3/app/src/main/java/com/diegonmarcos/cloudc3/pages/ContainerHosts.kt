package com.diegonmarcos.cloudc3.pages

/**
 * #1971 the hosts of the Containers > VM mode, DERIVED, never typed: every fleet VM that holds a declared container (fleet_slice.json vms; the
 * container's `host` is the VM alias) and every mesh-client device (fleet_slice.json electronics with a wg_ip: the PC and the phones), in the
 * fleet's order. A container whose host is none of them lands on the `unknown` page with its reason (container_projects.json::vm_view).
 */
object ContainerHosts {
    data class Host(val id: String, val label: String)

    fun derive(slice: FleetModel.Slice, catalog: ContainersModel.Catalog): List<Host> {
        val used = catalog.entries.filter { it.role != "repo" }.map { it.host }.toSet()
        return slice.vms.filter { it.alias in used || it.id in used }.map { Host(it.alias.ifBlank { it.id }, it.alias.ifBlank { it.id }) } +
            slice.devices.filter { !it.wgIp.isNullOrBlank() }.map { Host(it.id, it.model.replace(" ", "")) }
    }

    /** Where [e] is listed in VM mode: its host id, or the unknown id; a repo has no host (null: All only). */
    fun pageOf(decl: ContainerProjects.Decl, e: ContainersModel.Entry): String? =
        if (e.role == "repo") null else if (e.host in decl.hostIds) decl.vm.prefix + e.host else decl.vm.prefix + decl.vm.unknown
}
