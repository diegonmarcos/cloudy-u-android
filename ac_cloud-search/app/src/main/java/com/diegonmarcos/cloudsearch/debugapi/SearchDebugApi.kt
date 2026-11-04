package com.diegonmarcos.cloudsearch.debugapi

import android.content.Context
import com.diegonmarcos.cloudsearch.BuildConfig
import com.diegonmarcos.cloudsearch.data.Services
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONArray
import org.json.JSONObject

/**
 * Cloud Search on the fleet debug API (libs:devtools AppDebugServer: loopback, fleet token), so the
 * app can be checked with the phone locked:
 *
 *   /api/search/verticals                    every vertical, its subpages, and every source with
 *                                            its kind, whether it is enabled, and why not
 *   /api/search/query?v=image&q=cat&city=berlin    the real query through the engine and cache the screens
 *                                            use: listings + each source's status (ok, cached,
 *                                            stale, error, skipped, link, disabled)
 *
 * The group is build.json::ui.debug_api.group. No op is named `state` (GET /api/state's key), and
 * nothing here reads the AI token.
 */
object SearchDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        AppDebugServer.route(
            BuildConfig.DEBUG_API_GROUP,
            listOf(
                AppDebugServer.Op("verticals", "", "every vertical, subpage and source (kind, enabled, why)"),
                AppDebugServer.Op("query", "v=<vertical id>&q=<search term>&city=<city id, optional>", "run a search through the app's engine and cache: listings + per-source status"),
                AppDebugServer.Op("agents", "", "#913 the draft-only agents, their last run, the model and the budget (never the token)"),
                AppDebugServer.Op("runs", "n=<count, default 10>", "#913 the run log with each run's audit lines"),
                AppDebugServer.Op("reports", "", "#913 what agents published, newest first"),
                AppDebugServer.Op("report", "id=<report id>", "#913 one report with its drafts"),
                AppDebugServer.Op("cloud", "kind=apps|messages&q=<text>", "#913 the Cloud Search section: fleet apps, or Cloud Mail messages through the read-only agent door"),
            ),
        ) { op, q ->
            when (op) {
                "verticals" -> verticals(Services.get(app)).toString()
                "query" -> query(Services.get(app), q).toString()
                "agents" -> agents(Services.get(app)).toString()
                "runs" -> runs(Services.get(app), q).toString()
                "reports" -> reports(Services.get(app)).toString()
                "report" -> report(Services.get(app), q).toString()
                "cloud" -> cloud(Services.get(app), q).toString()
                else -> null
            }
        }
    }

    fun verticals(s: Services): JSONObject = JSONObject()
        .put("default", s.cfg.defaultVertical)
        .put("verticals", JSONArray(com.diegonmarcos.cloudsearch.ui.appVerticals(s.cfg).map { v ->
            JSONObject().put("id", v.id).put("label", v.label).put("title", v.title)
                .put("subpages", JSONArray(v.subpages))
                .put("sources", JSONArray(v.sources.mapNotNull { s.cfg.sources[it] }.map { src ->
                    JSONObject().put("id", src.id).put("label", src.label).put("kind", src.kind).put("enabled", src.enabled)
                        .put("fetched", src.fetches).put("why", src.why)
                }))
        }))

    fun query(s: Services, q: Map<String, String>): JSONObject {
        val v = q["v"].orEmpty()
        val shown = com.diegonmarcos.cloudsearch.ui.appVerticals(s.cfg).map { it.id }
        if (v !in shown) return JSONObject().put("ok", false).put("error", "v must be one of $shown")
        val r = s.engine.query(v, q["q"].orEmpty(), q["city"] ?: s.prefs.city)
        return JSONObject().put("ok", true).put("vertical", r.vertical).put("q", r.q).put("city", r.city)
            .put("count", r.listings.size).put("sources", s.engine.statusJson(r.statuses))
            .put("listings", JSONArray(r.listings.map { it.toJson() }))
    }

    private fun noAgents() = JSONObject().put("ok", false).put("error", "build.json::search.agents is not declared")

    /** #913 the agents with their last run, the model and the budget. The token is never part of the answer. */
    fun agents(s: Services): JSONObject {
        val svc = s.agents ?: return noAgents()
        val last = svc.runs.all()
        return JSONObject().put("ok", true).put("mode", "draft_only")
            .put("model", svc.prefs.model).put("budget_run_usd", svc.prefs.budgetRunUsd).put("budget_day_usd", svc.prefs.budgetDayUsd)
            .put("spent_today_usd", svc.prefs.spentToday(System.currentTimeMillis()))
            .put("agents", JSONArray(svc.agents.agents.map { a ->
                JSONObject().put("id", a.id).put("label", a.label).put("mode", "draft_only").put("uses_llm", a.usesLlm)
                    .put("side", a.side).put("category", a.category).put("legacy_ids", JSONArray(a.legacyIds))
                    .put("sources", JSONArray(svc.prefs.sources(a).map { it.id })).put("filters", JSONObject(svc.prefs.filters(a) as Map<*, *>))
                    .put("last_run", last.firstOrNull { a.owns(it.agentId) }?.let { JSONObject().put("id", it.id).put("status", it.status).put("summary", it.summary).put("ended_at", it.endedAt) } ?: JSONObject.NULL)
            }))
    }

    fun runs(s: Services, q: Map<String, String>): JSONObject {
        val svc = s.agents ?: return noAgents()
        val n = (q["n"]?.toIntOrNull() ?: 10).coerceIn(1, 100)
        return JSONObject().put("ok", true).put("runs", JSONArray(svc.runs.all().take(n).map { it.toJson() }))
    }

    fun reports(s: Services): JSONObject {
        val svc = s.agents ?: return noAgents()
        return JSONObject().put("ok", true).put("reports", JSONArray(svc.reports.newestFirst().map { r ->
            JSONObject().put("id", r.id).put("agent", r.agentId).put("kind", r.kind).put("title", r.title).put("created_at", r.createdAt)
                .put("summary", r.summary).put("items", r.items.size)
        }))
    }

    fun report(s: Services, q: Map<String, String>): JSONObject {
        val svc = s.agents ?: return noAgents()
        val r = svc.reports.get(q["id"].orEmpty()) ?: return JSONObject().put("ok", false).put("error", "no report with that id")
        return JSONObject().put("ok", true).put("report", r.toJson())
    }

    fun cloud(s: Services, q: Map<String, String>): JSONObject {
        val cloud = s.cfg.cloud ?: return JSONObject().put("ok", false).put("error", "build.json::search.cloud is not declared")
        return when (q["kind"]) {
            "apps" -> {
                val all = com.diegonmarcos.cloudsearch.core.CloudConfig.fleetApps(String(android.util.Base64.decode(BuildConfig.FLEET_APPS_B64, android.util.Base64.DEFAULT), Charsets.UTF_8))
                JSONObject().put("ok", true).put("apps", JSONArray(cloud.apps(all, q["q"].orEmpty()).map { JSONObject().put("id", it.id).put("label", it.label).put("package", it.pkg) }))
            }
            "messages" -> {
                val svc = s.agents ?: return noAgents()
                runCatching { svc.mail.messages("", q["q"].orEmpty(), cloud.messagesSince(System.currentTimeMillis()), cloud.messagesLimit) }.fold(
                    { rows -> JSONObject().put("ok", true).put("messages", JSONArray(rows.map { JSONObject().put("id", it.id).put("subject", it.subject).put("from", it.fromEmail).put("received_at", it.receivedAt) })) },
                    { JSONObject().put("ok", false).put("error", it.message ?: it.javaClass.simpleName) },
                )
            }
            else -> JSONObject().put("ok", false).put("error", "kind must be apps or messages")
        }
    }
}
