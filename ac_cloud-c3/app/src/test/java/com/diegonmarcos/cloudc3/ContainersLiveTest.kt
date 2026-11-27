package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.pages.ContainersLive
import com.diegonmarcos.cloudc3.pages.ContainersModel
import com.diegonmarcos.cloudc3.pages.RoutesTableModel
import com.diegonmarcos.cloudc3.pages.TopologyModel
import com.diegonmarcos.cloudc3.pages.TopologyModel.Node
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1954 (rule 16) the deployed state left Orch (the Service and Declared pages) and is in Containers: this suite builds those two views, and the Release
 * view's declared-vs-running tags, from the fixture fleet and proves each field of each container node is a field of the Containers Table rows
 * ([ContainersLive.overlay]). Fixture data only (obviously fake names); the mutations below turn the parity red.
 */
class ContainersLiveTest {

    private val inputs = TopologyFixtures.inputs
    private val rows: List<RoutesTableModel.Row> by lazy { ContainersLive.overlay(ContainersModel.rows(TopologyFixtures.catalog), inputs) }

    private fun leaves(nodes: List<Node>, path: List<Node> = emptyList()): List<Pair<Node, List<Node>>> =
        nodes.flatMap { n -> if (n.children.isEmpty()) listOf(n to path) else leaves(n.children, path + n) }

    /** Every field of every container node of the Service, Declared and Release views that the rows do not carry. */
    private fun gaps(rows: List<RoutesTableModel.Row>, i: TopologyModel.Inputs): List<String> {
        val out = mutableListOf<String>()
        val by = rows.groupBy { it.container }
        fun row(name: String, why: String): RoutesTableModel.Row? = by[name]?.firstOrNull().also { if (it == null) out += "$why: no Containers row for $name" }

        // Service page: the container (or dependency) under its service, the auth tier of the service, public vs mesh gateway
        val service = TopologyModel.build(TopologyModel.SERVICE, i)
        leaves(service).filter { it.first.kind == "container" || it.first.kind == "dependency" }.forEach { (n, path) ->
            val name = n.id.substringAfter("/c:")
            val r = row(name, "service") ?: return@forEach
            if (r.service != path.last().label) out += "service/$name: Service column '${r.service}' != '${path.last().label}'"
            if (r.live != n.metric) out += "service/$name: live state '${r.live}' != '${n.metric}'"
            if (r.drift != ContainersLive.driftOf(n.health, n.metric)) out += "service/$name: drift '${r.drift}' does not follow its health ${n.health}"
            if (n.kind == "dependency" && !r.role.contains(n.label.substringAfter(" (").removeSuffix(")"))) out += "service/$name: the dependency's engine is not in Role '${r.role}'"
        }
        TopologyModel.walk(service).map { it.first }.filter { it.kind == "service" }.forEach { s ->
            val mine = rows.filter { it.service == s.label && it.container in by.keys }
            val tier = mine.firstOrNull { it.publicUrl.isNotBlank() }?.authType.orEmpty()
            val want = if (tier.isBlank()) "mesh only" else "auth $tier"
            if (!s.metric.startsWith(want)) out += "service ${s.label}: edge '${s.metric}' is not '$want' from the rows"
        }

        // Declared page: VM > compose project > service > container with in sync / drift, and the containers running without a declaration
        val declared = TopologyModel.build(TopologyModel.ORCHESTRATION, i)
        leaves(declared).filter { it.first.kind == "container" }.forEach { (n, path) ->
            val name = n.id.substringAfterLast("/c:").substringAfterLast("/undeclared/")
            val undeclared = "/undeclared/" in n.id
            val r = (if (undeclared) rows.firstOrNull { it.container == name && it.drift == ContainersLive.RUNNING_NOT_DECLARED } else by[name]?.firstOrNull())
            if (r == null) { out += "declared/$name: no Containers row"; return@forEach }
            val vm = path.firstOrNull { it.kind == "vm" }?.label.orEmpty()
            if (undeclared) {
                if (r.live != n.metric.removePrefix("running, not declared - ")) out += "declared/$name: live '${r.live}' != '${n.metric}'"
                if (r.vmName != vm) out += "declared/$name: VM '${r.vmName}' != '$vm'"
            } else {
                val state = n.metric.removePrefix("in sync - ").removePrefix("drift - ")
                if (r.live != state) out += "declared/$name: live '${r.live}' != '$state'"
                if (r.drift != ContainersLive.driftOf(n.health, state)) out += "declared/$name: drift '${r.drift}' != '${ContainersLive.driftOf(n.health, state)}'"
                if (n.metric.startsWith("in sync") && r.drift != ContainersLive.IN_SYNC) out += "declared/$name: 'in sync' is not the drift cell"
                path.firstOrNull { it.kind == "project" && it.id.count { c -> c == '/' } == 2 }?.let { p ->
                    val folder = if (p.label == "(no compose project)") "" else p.label
                    if (r.compose != folder) out += "declared/$name: Compose project '${r.compose}' != '$folder'"
                }
                if (vm.isNotBlank() && path.any { it.kind == "vm" && it.id.startsWith("orch/${it.label}") } && r.vmName != vm) out += "declared/$name: VM '${r.vmName}' != '$vm'"
            }
        }

        // Release view: the declared and the running image tag of each container
        leaves(TopologyModel.build(TopologyModel.RELEASE, i)).filter { it.first.kind == "version" }.forEach { (n, _) ->
            val name = n.id.substringAfter("/c:")
            val r = row(name, "release") ?: return@forEach
            val m = Regex("^version (.+)$|^declared (\\S+), running (\\S+)$|^declared (\\S+) - ").find(n.metric)
            val g = m?.groupValues.orEmpty()
            val (dec, run) = when {
                g.getOrNull(1).orEmpty().isNotBlank() -> g[1] to g[1]
                g.getOrNull(2).orEmpty().isNotBlank() -> g[2] to g[3]
                else -> g.getOrNull(4).orEmpty() to ""
            }
            if (r.tag != dec) out += "release/$name: Image tag '${r.tag}' != '$dec'"
            if (r.runningTag != run) out += "release/$name: Running tag '${r.runningTag}' != '$run'"
        }
        return out
    }

    @Test
    fun `#1954 parity - every field of the removed Service and Declared pages is a column or a row of Containers`() {
        assertEquals("nothing lost", emptyList<String>(), gaps(rows, inputs))
        // the drift states the Declared page showed are all present in the fixture and in the rows
        assertTrue("in sync", rows.any { it.container == "web" && it.drift == ContainersLive.IN_SYNC })
        assertTrue("drift (unhealthy or missing next to its service)", rows.any { it.container == "web-db" && it.drift == ContainersLive.DECLARED_NOT_RUNNING })
        assertTrue("running, not declared", rows.any { it.container == "stray" && it.drift == ContainersLive.RUNNING_NOT_DECLARED && it.vmName == "vm-a" && it.live.startsWith("Up") })
        assertEquals("a VM that could not be read is not read, never in sync", ContainersLive.NOT_READ, rows.first { it.container == "api" }.drift)
        assertEquals("a declared container off every mesh VM is not read", ContainersLive.NOT_READ, rows.first { it.container == "orphan" }.drift)
        assertEquals("the running tag is the one docker ps shows", "1.3", rows.first { it.container == "web" }.runningTag)
        assertEquals("the declared tag is the catalog's", "1.2", rows.first { it.container == "web" }.tag)
        // MUTATIONS: lose one field and the parity must go red
        assertTrue("live state blanked", gaps(rows.map { it.copy(live = "") }, inputs).isNotEmpty())
        assertTrue("drift blanked", gaps(rows.map { it.copy(drift = "") }, inputs).isNotEmpty())
        assertTrue("service blanked", gaps(rows.map { it.copy(service = "") }, inputs).isNotEmpty())
        assertTrue("compose project blanked", gaps(rows.map { it.copy(compose = "") }, inputs).isNotEmpty())
        assertTrue("role blanked", gaps(rows.map { it.copy(role = "") }, inputs).isNotEmpty())
        assertTrue("running tag blanked", gaps(rows.map { it.copy(runningTag = "") }, inputs).isNotEmpty())
        assertTrue("image tag blanked", gaps(rows.map { it.copy(tag = "") }, inputs).isNotEmpty())
        assertTrue("the undeclared row dropped", gaps(rows.filter { it.drift != ContainersLive.RUNNING_NOT_DECLARED }, inputs).isNotEmpty())
        assertTrue("a container row dropped", gaps(rows.filter { it.container != "web" }, inputs).isNotEmpty())
    }

    @Test
    fun `#1954 nothing is invented before docker ps answered, and the summary counts the rows`() {
        val cold = TopologyModel.Inputs(TopologyFixtures.catalog, TopologyFixtures.slice, TopologyFixtures.infra, TopologyModel.Live(), TopologyFixtures.decl)
        val base = ContainersModel.rows(TopologyFixtures.catalog)
        assertEquals(base, ContainersLive.overlay(base, cold))
        assertTrue(base.all { it.drift.isBlank() && it.live.isBlank() })
        assertEquals("live: 1/4 up - 1 declared, not running - 1 running, not declared - 2 not read", ContainersLive.summary(rows))
    }
}
