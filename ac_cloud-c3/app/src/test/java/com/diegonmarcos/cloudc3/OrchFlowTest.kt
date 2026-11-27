package com.diegonmarcos.cloudc3

import com.diegonmarcos.cloudc3.cloud.DaguRunsFeed
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.cloudc3.pages.HomeLive
import com.diegonmarcos.cloudc3.pages.OrchFlow
import com.diegonmarcos.cloudc3.pages.TopologyModel.Health
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1954 the Orch > Home flow: the declaration (data/orch_flow.json) and the fold of what the sources answered into one status per stage. */
class OrchFlowTest {

    private val decl = Declarations.orchFlow
    private val repos = decl.pipelines.map { it.repo }.filter { it.isNotBlank() }.distinct()
    private val now = 1_800_000_000_000L

    private fun run(name: String, concl: String, agoMin: Long, status: String = "completed") =
        GitHubFeed.Run(name, "t", status, concl, "https://example.test/run", now - agoMin * 60_000)

    private fun inputs(runs: Map<String, HomeLive.Live<List<GitHubFeed.Run>>>, deploys: HomeLive.Live<List<OrchFlow.Deploy>> = HomeLive.Live.Pending) =
        OrchFlow.Inputs(runs = runs, deploys = deploys, reposDeclared = 51, ghcrPackages = 60, nowMs = now)

    @Test
    fun `the declaration parses six stages in flow order and the declared pipelines`() {
        assertEquals(listOf("sources", "workflows", "builds", "registries", "deploys", "running"), decl.stages.map { it.id })
        assertEquals(listOf("android", "containers", "infra", "front", "dagu"), decl.pipelines.map { it.id })
        decl.pipelines.forEach { p -> p.stages.forEach { s -> assertTrue("${p.id} names the undeclared stage $s", s in decl.stages.map { it.id }) } }
    }

    @Test
    fun `every stage opens a declared page`() {
        val nav = com.diegonmarcos.cloudc3.Declarations.nav
        val ids = nav.sections.flatMap { s -> s.allPages().map { it.id } }.toSet()
        decl.stages.forEach { assertTrue("stage ${it.id} opens ${it.open}, which no section declares", it.open in ids) }
    }

    @Test
    fun `every pipeline repo is a declared repo`() {
        val declared = Declarations.containerProjects.repos.flatMap { listOf(it.name, it.github) }.toSet()
        decl.pipelines.filter { it.repo.isNotBlank() }.forEach { assertTrue("${it.repo} is not in data/repos_declared.json", it.repo in declared) }
    }

    @Test
    fun `a failing newest run is bad, an unread source is unknown and never ok, a stale newest run is warn`() {
        val fail = OrchFlow.fold(decl, inputs(repos.associateWith { HomeLive.Live.Ok(listOf(run("Ship", "failure", 5), run("Ship", "success", 600))) }))
        assertEquals(Health.BAD, fail.stages.first { it.stage.id == "builds" }.health)
        assertEquals(Health.BAD, fail.stages.first { it.stage.id == "workflows" }.health)
        val ok = OrchFlow.fold(decl, inputs(repos.associateWith { HomeLive.Live.Ok(listOf(run("Ship", "success", 5))) }))
        assertEquals(Health.OK, ok.stages.first { it.stage.id == "builds" }.health)
        assertEquals(Health.OK, ok.stages.first { it.stage.id == "sources" }.health)
        val none = OrchFlow.fold(decl, inputs(repos.associateWith { HomeLive.Live.Failed("GitHub rate limit reached") }))
        none.stages.forEach { assertTrue("${it.stage.id} is ${it.health} with nothing read", it.health == Health.UNKNOWN) }
        assertTrue("the reason is printed, not a zero", none.stages.first { it.stage.id == "builds" }.text.contains("rate limit"))
        val stale = OrchFlow.fold(decl, inputs(repos.associateWith { HomeLive.Live.Ok(listOf(run("Ship", "success", decl.staleAfterMin + 10L))) }))
        assertEquals(Health.WARN, stale.stages.first { it.stage.id == "sources" }.health)
        val running = OrchFlow.fold(decl, inputs(repos.associateWith { HomeLive.Live.Ok(listOf(run("Ship", "", 1, status = "in_progress"))) }))
        assertEquals(Health.WARN, running.stages.first { it.stage.id == "workflows" }.health)
    }

    @Test
    fun `a failed deploy is bad, unread history is unknown, and the lane cells are the declared stages only`() {
        val r = repos.associateWith { HomeLive.Live.Ok(listOf(run("Ship", "success", 5))) }
        val bad = OrchFlow.fold(decl, inputs(r, HomeLive.Live.Ok(listOf(OrchFlow.Deploy(now - 1000, "caddy", "up", false, 5), OrchFlow.Deploy(now - 9000, "caddy", "up", true, 5)))))
        assertEquals(Health.BAD, bad.stages.first { it.stage.id == "deploys" }.health)
        val unread = OrchFlow.fold(decl, inputs(r, HomeLive.Live.Failed("NOT_FOUND")))
        assertEquals(Health.UNKNOWN, unread.stages.first { it.stage.id == "deploys" }.health)
        assertTrue(unread.stages.first { it.stage.id == "deploys" }.text.contains("NOT_FOUND"))
        decl.pipelines.forEach { p -> assertEquals("${p.id} cells", p.stages, bad.cells.filter { it.pipeline == p.id }.map { it.stage }) }
    }

    @Test
    fun `the deploy history answer parses whether it is an array or wrapped, and garbage is not a list`() {
        val a = OrchFlow.parseDeploys("""[{"ts":"2026-11-27T10:00:00Z","service":"caddy","step":"up","success":true,"duration_ms":1200,"output":"ok"}]""")
        assertNotNull(a); assertEquals("caddy", a!!.single().service); assertTrue(a.single().ok); assertEquals(1200L, a.single().durationMs)
        assertEquals(1, OrchFlow.parseDeploys("""{"rows":[{"ts":1700000000,"service":"x","step":"s","success":false}]}""")!!.size)
        assertEquals(null, OrchFlow.parseDeploys("not json"))
    }

    @Test
    fun `Dagu failures count in the workflows stage`() {
        val r = repos.associateWith { HomeLive.Live.Ok(listOf(run("Ship", "success", 5))) }
        val i = OrchFlow.Inputs(runs = r, dagu = HomeLive.Live.Ok(listOf(DaguRunsFeed.Run("backup", 2, now - 60_000, now))), reposDeclared = 1, nowMs = now)
        val w = OrchFlow.fold(decl, i).stages.first { it.stage.id == "workflows" }
        assertEquals(Health.BAD, w.health)
        assertTrue(w.text.contains("Dagu 1 DAGs (1 failing)"))
    }
}
