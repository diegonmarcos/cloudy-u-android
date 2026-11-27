package com.diegonmarcos.cloudc3.pages

/**
 * #1954 the DEPLOYED state of the fleet, in Containers. The old Orch Service and Declared pages (declared vs `docker ps`) left Orch: every field they
 * showed per container is a column of the Containers Table now (Service, Role, Compose project, Image tag, Running tag, Live state, Drift), and a
 * container that runs without being declared is a row of its own ("running, not declared"). The semantics are the Declared page's, unchanged:
 * [TopologyModel.containerState] is the one function that reads a declared container against what `docker ps` shows.
 *
 * Pure: the rows, the declared catalog, the fleet slice and the live answer in, rows out. A VM that could not be read leaves its rows "not read";
 * nothing is invented.
 */
object ContainersLive {
    const val IN_SYNC = "in sync"
    const val DECLARED_NOT_RUNNING = "declared, not running"
    const val RUNNING_NOT_DECLARED = "running, not declared"
    const val DRIFT = "drift"
    const val DEGRADED = "degraded"
    const val NOT_READ = "not read"

    /** The drift values in the order the Drift chips print them. */
    val DRIFTS = listOf(IN_SYNC, DECLARED_NOT_RUNNING, RUNNING_NOT_DECLARED, DRIFT, DEGRADED, NOT_READ)

    /** Where the undeclared containers are grouped (the Declared page's "Running but not declared"). */
    const val UNDECLARED_GROUP = "Running but not declared"

    /** The drift of a declared container from its health and state text ([TopologyModel.containerState]). */
    fun driftOf(h: TopologyModel.Health, text: String): String = when (h) {
        TopologyModel.Health.OK -> IN_SYNC
        TopologyModel.Health.BAD -> if (text.startsWith(DECLARED_NOT_RUNNING)) DECLARED_NOT_RUNNING else DRIFT
        TopologyModel.Health.WARN -> DEGRADED
        else -> NOT_READ
    }

    /**
     * [rows] with the live state, the running image tag and the drift filled in from [i] (the catalog read against `docker ps`), plus one row per
     * running container no catalog entry declares on a VM for which [extras] is true. Nothing is filled while no VM has answered ([TopologyModel.Live.deployed] empty).
     */
    fun overlay(rows: List<RoutesTableModel.Row>, i: TopologyModel.Inputs, extras: (vm: String) -> Boolean = { true }): List<RoutesTableModel.Row> {
        if (i.live.deployed.isEmpty()) return rows
        val byName = i.catalog.entries.associateBy { it.name }
        val filled = rows.map { r ->
            val e = byName[r.container]
            if (e == null || e.role == "repo") r
            else {
                val (h, text) = TopologyModel.containerState(i, e)
                val box = (i.live.deployed[e.host] as? TopologyModel.Deployed.Seen)?.boxes?.firstOrNull { it.name == e.name }
                val running = box?.image?.takeIf { it.isNotBlank() }?.let { TopologyModel.imageTag(it) }.orEmpty()
                val drift = driftOf(h, text)
                r.copy(live = text, drift = drift, runningTag = running, meta = r.meta + listOf("live" to text, "drift" to drift, "running_tag" to running))
            }
        }
        val declared = byName.keys
        val undeclared = i.live.deployed.entries.flatMap { (vm, d) ->
            if (d !is TopologyModel.Deployed.Seen || !extras(vm)) emptyList()
            else d.boxes.filter { it.name !in declared }.map { b ->
                RoutesTableModel.Row(
                    category = UNDECLARED_GROUP, container = b.name, publicUrl = "", authType = "", privateUrl = "", vmName = vm, vmAddr = "", group = UNDECLARED_GROUP,
                    meta = listOf("name" to b.name, "host" to vm, "image" to b.image, "live" to b.status, "drift" to RUNNING_NOT_DECLARED, "running_tag" to TopologyModel.imageTag(b.image)),
                    runningTag = if (b.image.isBlank()) "" else TopologyModel.imageTag(b.image), live = b.status, drift = RUNNING_NOT_DECLARED,
                )
            }
        }
        return filled + undeclared
    }

    /** "live: 96/98 up - 1 drift - 2 declared, not running - 1 running, not declared", from the rows' drift cells. */
    fun summary(rows: List<RoutesTableModel.Row>): String {
        val by = rows.map { it.drift }.filter { it.isNotBlank() }.groupingBy { it }.eachCount()
        val total = rows.count { it.drift.isNotBlank() && it.drift != RUNNING_NOT_DECLARED }
        return "live: ${by[IN_SYNC] ?: 0}/$total up" + DRIFTS.filter { it != IN_SYNC && (by[it] ?: 0) > 0 }.joinToString("") { " - ${by[it]} $it" }
    }
}
