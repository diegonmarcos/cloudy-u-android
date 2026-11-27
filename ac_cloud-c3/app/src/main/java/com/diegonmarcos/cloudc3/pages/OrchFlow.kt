package com.diegonmarcos.cloudc3.pages

import com.diegonmarcos.cloudc3.cloud.DaguRunsFeed
import com.diegonmarcos.cloudc3.cloud.GitHubFeed
import com.diegonmarcos.cloudc3.pages.TopologyModel.Health as H
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * #1954 Orch > Home: the pipeline flow (sources -> workflows -> builds -> registries -> deploys -> running) with a live last-run status per stage,
 * as pure functions: the declaration (data/orch_flow.json) and what the existing Orch sources answered in, a [Reading] out. The JVM suite runs the
 * same fold. Workflow names are never declared: they are the distinct run names of the runs feed.
 *
 * Fold rules (declared in orch_flow.json::status_rules): BAD = the newest run of any workflow failed; WARN = a run is in progress or the newest run
 * is older than stale_after_minutes; a source that could not be read is UNKNOWN and prints its reason, NEVER OK and never a zero.
 */
object OrchFlow {

    data class Stage(val id: String, val label: String, val from: List<String>, val open: String)
    data class Pipeline(val id: String, val label: String, val repo: String, val dagu: Boolean, val stages: List<String>)
    data class Decl(val stages: List<Stage>, val pipelines: List<Pipeline>, val runsPerRepo: Int, val staleAfterMin: Int)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private fun JsonObject.s(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    private fun JsonObject.objs(k: String) = (this[k] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun parse(text: String): Decl {
        val o = runCatching { json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return Decl(emptyList(), emptyList(), 30, 1440)
        return Decl(
            o.objs("stages").map { Stage(it.s("id"), it.s("label"), it.list("from"), it.s("open")) },
            o.objs("pipelines").map { Pipeline(it.s("id"), it.s("label"), it.s("repo"), it.s("dagu") == "true", it.list("stages")) },
            o.s("runs_per_repo").toIntOrNull() ?: 30, o.s("stale_after_minutes").toIntOrNull() ?: 1440,
        )
    }

    // ── what the sources answered ──────────────────────────────────────────

    /** One row of c3-infra-api GET /db/deploy/history (`deploy_log`: ts, service, step, success, duration_ms, output). */
    data class Deploy(val tsMillis: Long, val service: String, val step: String, val ok: Boolean, val durationMs: Long, val output: String = "")

    /** The fleet's running state, counted from `docker ps` against the declared containers. */
    data class Running(val vmsTotal: Int, val vmsRead: Int, val declared: Int, val up: Int, val down: List<String>, val notDeclared: List<String>)

    data class Inputs(
        /** GitHub Actions runs by pipeline repo, newest first. */
        val runs: Map<String, HomeLive.Live<List<GitHubFeed.Run>>> = emptyMap(),
        val releases: Map<String, HomeLive.Live<List<GitHubFeed.Release>>> = emptyMap(),
        val dagu: HomeLive.Live<List<DaguRunsFeed.Run>> = HomeLive.Live.Pending,
        val deploys: HomeLive.Live<List<Deploy>> = HomeLive.Live.Pending,
        val running: HomeLive.Live<Running> = HomeLive.Live.Pending,
        val reposDeclared: Int = 0,
        val ghcrPackages: Int = 0,
        val nowMs: Long = 0L,
    )

    /** Defensive: an array, or an object holding one (rows | history | items | deploys | data); null when it is neither. */
    fun parseDeploys(body: String): List<Deploy>? {
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val arr: JsonArray = when (root) {
            is JsonArray -> root
            is JsonObject -> listOf("rows", "history", "items", "deploys", "data").firstNotNullOfOrNull { root[it] as? JsonArray } ?: return null
            else -> return null
        }
        return arr.mapNotNull { e ->
            val o = e as? JsonObject ?: return@mapNotNull null
            fun p(k: String): String? = (o[k] as? JsonPrimitive)?.contentOrNull
            val ts = p("ts") ?: p("timestamp") ?: p("at") ?: ""
            val ms: Long = ts.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
                ?: runCatching { java.time.Instant.parse(ts).toEpochMilli() }
                    .recoverCatching { java.time.LocalDateTime.parse(ts.replace(' ', 'T')).toInstant(java.time.ZoneOffset.UTC).toEpochMilli() }
                    .getOrDefault(0L)
            val ok = p("success")?.let { it == "true" || it == "1" } ?: (p("status") == "success")
            Deploy(ms, p("service").orEmpty(), p("step").orEmpty(), ok, p("duration_ms")?.toLongOrNull() ?: 0L, p("output").orEmpty())
        }
    }

    /** The running counts from a `docker ps` read against the declared containers (the same [TopologyModel.containerState] the Containers page uses). */
    fun running(i: TopologyModel.Inputs): HomeLive.Live<Running> {
        if (i.live.deployed.isEmpty()) return HomeLive.Live.Pending
        val vms = i.slice.vms.filter { it.wgIp != null }
        val read = vms.count { i.live.deployed[it.alias] is TopologyModel.Deployed.Seen }
        if (vms.isNotEmpty() && read == 0) return HomeLive.Live.Failed((i.live.deployed.values.firstOrNull() as? TopologyModel.Deployed.Blind)?.why ?: "no VM answered")
        val onVm = i.catalog.entries.filter { e -> i.slice.vms.any { it.alias == e.host } && e.role != "repo" }
        val states = onVm.map { it to TopologyModel.containerState(i, it) }
        val declaredNames = i.catalog.entries.map { it.name }.toSet()
        val extra = i.live.deployed.values.filterIsInstance<TopologyModel.Deployed.Seen>().flatMap { it.boxes }.map { it.name }.filter { it !in declaredNames }
        return HomeLive.Live.Ok(
            Running(
                vms.size, read, states.count { it.second.first != TopologyModel.Health.UNKNOWN }, states.count { it.second.first == TopologyModel.Health.OK },
                states.filter { it.second.first == TopologyModel.Health.BAD || it.second.first == TopologyModel.Health.WARN }.map { "${it.first.name}: ${it.second.second}" }, extra,
            ),
        )
    }

    // ── the fold ───────────────────────────────────────────────────────────

    fun runHealth(r: GitHubFeed.Run): TopologyModel.Health = when {
        r.status != "completed" && r.status.isNotBlank() -> TopologyModel.Health.WARN
        r.conclusion == "success" -> TopologyModel.Health.OK
        r.conclusion == "failure" || r.conclusion == "timed_out" || r.conclusion == "startup_failure" -> TopologyModel.Health.BAD
        r.conclusion.isBlank() -> TopologyModel.Health.UNKNOWN
        else -> TopologyModel.Health.WARN
    }

    fun daguHealth(r: DaguRunsFeed.Run): TopologyModel.Health = when (r.status) {
        4 -> TopologyModel.Health.OK
        2 -> TopologyModel.Health.BAD
        else -> TopologyModel.Health.WARN
    }

    /** The newest run of each distinct workflow name. */
    fun newestPerWorkflow(runs: List<GitHubFeed.Run>): List<GitHubFeed.Run> =
        runs.groupBy { it.name }.values.map { g -> g.maxByOrNull { it.tsMillis }!! }.sortedByDescending { it.tsMillis }

    private fun worst(hs: List<TopologyModel.Health>): TopologyModel.Health = hs.filter { it != TopologyModel.Health.NONE }.minOrNull() ?: TopologyModel.Health.UNKNOWN

    /** Health of a set of runs: the worst newest-per-workflow, WARN when even the newest is stale; empty = UNKNOWN. */
    fun runsHealth(runs: List<GitHubFeed.Run>, now: Long, staleMs: Long): TopologyModel.Health {
        if (runs.isEmpty()) return TopologyModel.Health.UNKNOWN
        val h = worst(newestPerWorkflow(runs).map { runHealth(it) })
        return if (h == TopologyModel.Health.OK && now - runs.maxOf { it.tsMillis } > staleMs) TopologyModel.Health.WARN else h
    }

    /** One line of a stage's item list: what, detail, its status, and a url when it has one. */
    data class Item(val label: String, val detail: String, val health: TopologyModel.Health, val url: String = "")

    data class StageReading(val stage: Stage, val health: TopologyModel.Health, val text: String, val items: List<Item>)
    data class Cell(val pipeline: String, val stage: String, val text: String, val health: TopologyModel.Health)
    data class Reading(val stages: List<StageReading>, val cells: List<Cell>) {
        fun count(h: TopologyModel.Health): Int = stages.count { it.health == h }
        fun summary(): String =
            "Pipeline  ${count(TopologyModel.Health.OK)} ok  ${count(TopologyModel.Health.BAD) + count(TopologyModel.Health.WARN)} failing or warning  ${count(TopologyModel.Health.UNKNOWN)} unknown"
    }

    private fun ago(now: Long, ts: Long): String = if (ts <= 0) "never" else GitHubFeed.ago(maxOf(0L, now - ts))

    private fun why(l: HomeLive.Live<*>, what: String): String? = when (l) {
        HomeLive.Live.Pending -> "$what: reading..."
        is HomeLive.Live.Failed -> "$what: ${l.why}"
        is HomeLive.Live.Ok -> null
    }

    fun <T> okOrNull(l: HomeLive.Live<T>): T? = (l as? HomeLive.Live.Ok<T>)?.value

    private fun newestDagu(runs: List<DaguRunsFeed.Run>): List<DaguRunsFeed.Run> =
        runs.groupBy { it.name }.values.map { g -> g.maxByOrNull { it.startedAtMs }!! }

    fun fold(d: Decl, i: Inputs): Reading {
        val stale = d.staleAfterMin * 60_000L
        val repos = d.pipelines.filter { it.repo.isNotBlank() }.map { it.repo }.distinct()
        val pending: HomeLive.Live<List<GitHubFeed.Run>> = HomeLive.Live.Pending
        val runsByRepo: Map<String, List<GitHubFeed.Run>> = repos.associateWith { okOrNull(i.runs[it] ?: pending).orEmpty() }
        val unreadRuns = repos.mapNotNull { r -> why(i.runs[r] ?: pending, r) }
        val allRuns: List<Pair<String, GitHubFeed.Run>> = runsByRepo.flatMap { (repo, rs) -> rs.map { repo to it } }
        val daguRuns = okOrNull(i.dagu)
        fun stage(id: String): Stage = d.stages.first { it.id == id }

        fun sources(): StageReading {
            val newest = allRuns.maxByOrNull { it.second.tsMillis }
            val head = "${i.reposDeclared} repos - ${d.pipelines.size} pipelines"
            if (newest == null) return StageReading(stage("sources"), H.UNKNOWN, "$head - runs unread (${unreadRuns.firstOrNull() ?: "no runs"})", emptyList())
            val items = d.pipelines.filter { it.repo.isNotBlank() }.map { p ->
                val n = runsByRepo[p.repo].orEmpty().maxByOrNull { it.tsMillis }
                Item(
                    p.repo,
                    if (n != null) "newest run ${ago(i.nowMs, n.tsMillis)}: ${n.displayTitle.ifBlank { n.name }}" else (why(i.runs[p.repo] ?: pending, "runs") ?: "no runs"),
                    if (n == null) H.UNKNOWN else H.OK, n?.htmlUrl.orEmpty(),
                )
            }
            return StageReading(stage("sources"), if (i.nowMs - newest.second.tsMillis > stale) H.WARN else H.OK,
                "$head - last push ${ago(i.nowMs, newest.second.tsMillis)} ${newest.first}", items)
        }

        fun workflows(): StageReading {
            val gha = repos.flatMap { r -> newestPerWorkflow(runsByRepo[r].orEmpty()).map { r to it } }
            val ghaBad = gha.count { runHealth(it.second) == H.BAD }
            val dagu = newestDagu(daguRuns.orEmpty())
            val daguBad = dagu.count { daguHealth(it) == H.BAD }
            val ghaPart = if (gha.isEmpty()) "GHA unread (${unreadRuns.firstOrNull() ?: "no runs"})" else "GHA ${gha.size} workflows ($ghaBad failing)"
            val daguPart = why(i.dagu, "Dagu") ?: "Dagu ${dagu.size} DAGs ($daguBad failing)"
            val ghaH = if (gha.isEmpty()) H.UNKNOWN else worst(gha.map { runHealth(it.second) })
            val daguH = if (dagu.isEmpty()) H.UNKNOWN else worst(dagu.map { daguHealth(it) })
            return StageReading(stage("workflows"), worst(listOf(ghaH, daguH)), "$ghaPart - $daguPart",
                gha.map { (r, run) -> Item("$r / ${run.name}", "${run.conclusion.ifBlank { run.status }} ${ago(i.nowMs, run.tsMillis)}", runHealth(run), run.htmlUrl) } +
                    dagu.map { Item("dagu / ${it.name}", "status ${it.status} ${ago(i.nowMs, it.startedAtMs)}", daguHealth(it)) })
        }

        fun builds(): StageReading {
            if (allRuns.isEmpty()) return StageReading(stage("builds"), H.UNKNOWN, "builds unread (${unreadRuns.firstOrNull() ?: "no runs"})", emptyList())
            val ok = allRuns.count { runHealth(it.second) == H.OK }
            val bad = allRuns.count { runHealth(it.second) == H.BAD }
            val live = allRuns.count { runHealth(it.second) == H.WARN }
            val newestBad = repos.any { r -> runsByRepo[r].orEmpty().maxByOrNull { it.tsMillis }?.let { runHealth(it) == H.BAD } == true }
            val h = when { newestBad -> H.BAD; bad > 0 || live > 0 -> H.WARN; else -> H.OK }
            return StageReading(stage("builds"), h, "last ${allRuns.size} runs: $ok ok $bad fail $live running",
                allRuns.sortedByDescending { it.second.tsMillis }.take(12).map { (r, run) -> Item("$r / ${run.name}", "${run.conclusion.ifBlank { run.status }} ${ago(i.nowMs, run.tsMillis)}", runHealth(run), run.htmlUrl) })
        }

        fun registries(): StageReading {
            val relPending: HomeLive.Live<List<GitHubFeed.Release>> = HomeLive.Live.Pending
            val rel = repos.mapNotNull { r -> okOrNull(i.releases[r] ?: relPending)?.firstOrNull()?.let { r to it } }
            val head = "GHCR ${i.ghcrPackages} packages (declared) - Releases ${rel.size}/${repos.size} repos"
            if (rel.isEmpty()) {
                val reason = repos.firstNotNullOfOrNull { why(i.releases[it] ?: relPending, "releases") } ?: "no release read"
                return StageReading(stage("registries"), H.UNKNOWN, "$head - $reason", emptyList())
            }
            val newest = rel.maxByOrNull { it.second.tsMillis }!!
            return StageReading(stage("registries"), H.OK, "$head - newest ${newest.first} ${newest.second.tag} ${ago(i.nowMs, newest.second.tsMillis)}",
                rel.map { (r, x) -> Item(r, "${x.tag} ${ago(i.nowMs, x.tsMillis)} (${x.assets.size} assets)", H.OK, x.htmlUrl) })
        }

        fun deploys(): StageReading {
            val rows = okOrNull(i.deploys)
            if (rows == null) return StageReading(stage("deploys"), H.UNKNOWN, "deploys: ${why(i.deploys, "history")?.substringAfter(": ")}", emptyList())
            if (rows.isEmpty()) return StageReading(stage("deploys"), H.UNKNOWN, "no deploy recorded", emptyList())
            val last = rows.sortedByDescending { it.tsMillis }
            val newestPerService = last.groupBy { it.service }.values.map { it.first() }
            val h = if (newestPerService.any { !it.ok }) H.BAD else H.OK
            return StageReading(stage("deploys"), h, "last ${last.size}: ${last.count { it.ok }} ok ${last.count { !it.ok }} fail - last ${ago(i.nowMs, last.first().tsMillis)} ${last.first().service}",
                last.map { Item("${it.service} ${it.step}".trim(), "${if (it.ok) "ok" else "FAILED"} ${ago(i.nowMs, it.tsMillis)}", if (it.ok) H.OK else H.BAD) })
        }

        fun runningStage(): StageReading {
            val r = okOrNull(i.running)
            if (r == null) return StageReading(stage("running"), H.UNKNOWN, "running: ${why(i.running, "fleet")?.substringAfter(": ")}", emptyList())
            val h = when { r.vmsRead == 0 -> H.UNKNOWN; r.up < r.declared || r.vmsRead < r.vmsTotal -> H.WARN; else -> H.OK }
            return StageReading(stage("running"), h,
                "VMs ${r.vmsRead}/${r.vmsTotal} reachable - containers ${r.up}/${r.declared} up" + if (r.notDeclared.isEmpty()) "" else " - ${r.notDeclared.size} running, not declared",
                r.down.map { Item(it.substringBefore(":"), it.substringAfter(": "), H.BAD) } + r.notDeclared.map { Item(it, "running, not declared", H.WARN) })
        }

        val readers: Map<String, () -> StageReading> = mapOf(
            "sources" to ::sources, "workflows" to ::workflows, "builds" to ::builds,
            "registries" to ::registries, "deploys" to ::deploys, "running" to ::runningStage,
        )
        val stages = d.stages.map { st -> readers[st.id]?.invoke() ?: StageReading(st, H.UNKNOWN, "no reader for stage ${st.id}", emptyList()) }
        val byId = stages.associateBy { it.stage.id }
        val cells = d.pipelines.flatMap { p ->
            p.stages.map { sid ->
                val sr = byId[sid]
                val rs = runsByRepo[p.repo].orEmpty()
                val newest = rs.maxByOrNull { it.tsMillis }
                val read = p.repo.isNotBlank() && i.runs[p.repo] is HomeLive.Live.Ok<*>
                val unread = Cell(p.id, sid, why(i.runs[p.repo] ?: pending, "unread")?.substringAfter(": ") ?: "unread", H.UNKNOWN)
                when {
                    sid == "sources" && p.repo.isNotBlank() ->
                        if (!read || newest == null) unread else Cell(p.id, sid, ago(i.nowMs, newest.tsMillis), if (i.nowMs - newest.tsMillis > stale) H.WARN else H.OK)
                    sid == "workflows" && p.dagu -> {
                        val dg = newestDagu(daguRuns.orEmpty())
                        if (daguRuns == null) Cell(p.id, sid, why(i.dagu, "Dagu")?.substringAfter(": ") ?: "unread", H.UNKNOWN)
                        else Cell(p.id, sid, "${dg.size} DAGs", if (dg.isEmpty()) H.UNKNOWN else worst(dg.map { daguHealth(it) }))
                    }
                    sid == "workflows" && p.repo.isNotBlank() ->
                        if (!read) unread else newestPerWorkflow(rs).let { w -> Cell(p.id, sid, "${w.size} wf ${w.count { runHealth(it) == H.BAD }} fail", runsHealth(rs, i.nowMs, stale)) }
                    sid == "builds" && p.repo.isNotBlank() ->
                        if (!read || newest == null) unread else Cell(p.id, sid, "${newest.conclusion.ifBlank { newest.status }} ${ago(i.nowMs, newest.tsMillis)}", runHealth(newest))
                    sid == "registries" && p.repo.isNotBlank() -> {
                        val relPending: HomeLive.Live<List<GitHubFeed.Release>> = HomeLive.Live.Pending
                        val first = okOrNull(i.releases[p.repo] ?: relPending)?.firstOrNull()
                        if (first != null) Cell(p.id, sid, "rel ${first.tag}", H.OK) else Cell(p.id, sid, "no release read", H.UNKNOWN)
                    }
                    else -> Cell(p.id, sid, sr?.text?.substringBefore(" - ")?.take(28).orEmpty(), sr?.health ?: H.UNKNOWN)
                }
            }
        }
        return Reading(stages, cells)
    }
}
