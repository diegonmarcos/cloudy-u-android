package com.diegonmarcos.superapp.bgpolicy

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * #1506 The phone side of the c3 peers-control tools (peers.bg_policy_get / bg_policy_set / super_saving_on / super_saving_off):
 * the same [BgEngine] and [SuperSavingRuntime] the screens use, answered as JSON on the debug API (bgpolicy/get, bgpolicy/set,
 * supersaving/on, supersaving/off). A coordinator call is a user-level action from afar: it runs once per request, never on a
 * schedule, and without the shell every answer says so instead of pretending.
 */
object BgPolicyApi {
    const val MAX_APPS = 400

    /** `scope=fleet|all` (default fleet), `app=<pkg>` for one package. */
    fun get(ctx: Context, query: Map<String, String>): String {
        val app = ctx.applicationContext
        val cfg = BgHost.config(app)
        val shell = BgHost.shell(app)
        val o = JSONObject().put("available", shell != null).put("channel", shell?.channel ?: "none")
            .put("super_saving_active", SuperSavingRuntime.active(app))
        val ess = BgHost.essential(app, cfg)
        val one = query["app"]
        val pkgs = BgHost.packages(app).filter { p -> (one != null && p.pkg == one) || (one == null && (query["scope"] == "all" || cfg.isFleet(p.pkg))) }
        o.put("essential", JSONArray(pkgs.filter { ess.isEssential(it.pkg) }.map { it.pkg }))
        val engine = BgHost.engine(app, shell, cfg)
        val rows = engine.readAll(pkgs, cfg)
        if (rows == null) return o.put("apps", JSONArray()).put("note", BgOutcome.NO_ADB).toString()
        o.put("apps", JSONArray(rows.take(MAX_APPS).map { (p, s) ->
            JSONObject().put("pkg", p.pkg).put("label", p.label).put("fleet", cfg.isFleet(p.pkg)).put("essential", ess.isEssential(p.pkg))
                .put("run_any_in_background", s.runAny).put("run_in_background", s.runIn).put("bucket", Bucket.name(s.bucket))
                .put("doze_exempt", s.idleWhitelisted).put("running", s.running).put("restricted", s.restricted)
        }))
        return o.toString()
    }

    /** `action=allow|restrict|force_stop|restrict_all|restore|disable|enable`, `app=<pkg>` (not for restrict_all / restore), `component=<class>` (disable / enable). */
    fun set(ctx: Context, query: Map<String, String>): String {
        val app = ctx.applicationContext
        val action = query["action"].orEmpty()
        val pkg = query["app"].orEmpty()
        val needsApp = action in setOf("allow", "restrict", "force_stop", "disable", "enable")
        if (action !in setOf("allow", "restrict", "force_stop", "restrict_all", "restore", "disable", "enable")) return error("unknown action '$action'")
        if (needsApp && !Regex("[A-Za-z0-9_.]+").matches(pkg)) return error("missing or bad app")
        val shell = BgHost.shell(app) ?: return JSONObject().put("ok", false).put("skipped", true).put("note", BgOutcome.NO_ADB).toString()
        val cfg = BgHost.config(app)
        val e = BgHost.engine(app, shell, cfg)
        val outs: List<BgOutcome> = when (action) {
            "allow" -> listOf(e.allow(pkg))
            "restrict" -> listOf(e.restrict(pkg))
            "force_stop" -> listOf(e.forceStop(pkg))
            "restrict_all" -> e.restrictAll(BgHost.packages(app), cfg)
            "restore" -> e.restore()
            else -> listOf(e.component(pkg, query["component"].orEmpty().also { if (it.isEmpty()) return error("missing component") }, enable = action == "enable"))
        }
        return JSONObject().put("ok", outs.isNotEmpty() && outs.all { it.ok || it.skipped }).put("done", outs.count { it.ok }).put("skipped", outs.count { it.skipped })
            .put("failed", outs.count { !it.ok && !it.skipped })
            .put("results", JSONArray(outs.take(60).map { JSONObject().put("pkg", it.pkg).put("action", it.action.name).put("ok", it.ok).put("note", it.note) })).toString()
    }

    fun superSavingOn(ctx: Context): String {
        val app = ctx.applicationContext
        return report(SuperSavingRuntime.turnOn(app, BgHost.shell(app)), SuperSavingRuntime.active(app))
    }

    fun superSavingOff(ctx: Context): String {
        val app = ctx.applicationContext
        return report(SuperSavingRuntime.turnOff(app, BgHost.shell(app)), SuperSavingRuntime.active(app))
    }

    private fun report(r: List<SsResult>, active: Boolean): String = JSONObject().put("active", active).put("summary", SuperSavingRuntime.summary(r))
        .put("steps", JSONArray(r.map { JSONObject().put("id", it.id).put("state", it.state.name.lowercase()).put("note", it.note).put("needs_adb", it.needsAdb) })).toString()

    private fun error(m: String) = JSONObject().put("ok", false).put("error", m).toString()
}
