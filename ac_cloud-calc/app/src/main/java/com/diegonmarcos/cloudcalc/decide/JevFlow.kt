package com.diegonmarcos.cloudcalc.decide

import android.content.Context
import com.diegonmarcos.cloudcalc.DateForms
import com.diegonmarcos.cloudcalc.Declarations
import com.diegonmarcos.cloudcalc.Logic
import com.diegonmarcos.cloudcalc.clock.ClockEngine
import com.diegonmarcos.cloudcalc.clock.ClockLogic
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.superapp.decisions.Decision
import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.jev.JevRouter
import com.diegonmarcos.cloudcalc.jev.call
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jev mode end to end, shared by the screen and /api/jev/route: route the request, then RUN the
 * plan on this app's own engines — the decision model never computes. Blocks: call off the main
 * thread.
 */
object JevFlow {
    /** What a plan produced: [lines] are (label, value) rows; [mode] is where the tool lives. */
    data class Run(
        val mode: String,
        val calculation: String,
        val lines: List<Pair<String, String>>,
        val error: String,
        /** A form's slot questions, when they were asked. */
        val slots: Decision? = null,
    ) {
        val result: String get() = lines.joinToString("; ") { (l, v) -> if (l.isBlank()) v else "$l = $v" }

        fun toJson(): JSONObject = JSONObject().put("mode", mode).put("calculation", calculation).put("result", result)
            .put("lines", JSONArray().apply { lines.forEach { (l, v) -> put(JSONObject().put("label", l).put("value", v)) } })
            .put("error", error).put("slots", slots?.toJson() ?: JSONObject.NULL)
    }

    data class Answer(val outcome: JevRouter.Outcome, val model: String, val tokenSource: String, val run: Run?) {
        fun toJson(): JSONObject = JSONObject().put("model", model).put("token_source", tokenSource)
            .put("outcome", outcome.toJson()).put("run", run?.toJson() ?: JSONObject.NULL)
    }

    /** Route [request]; run the confident pick, or the offline fallback when routing failed. */
    fun ask(ctx: Context, api: CalcApi, request: String, startTimer: Boolean, attachments: List<Attachment> = emptyList()): Answer {
        val cfg = JevStore.config(ctx)
        val token = JevStore.token(ctx)
        val model = JevStore.model(ctx, ROUTE_USE)
        // Attachments ride along as content parts of the routing question; with none it is the unchanged route().
        val o = if (attachments.isEmpty()) JevRouter.route(cfg, JevStore.http, token.value, model, request)
        else JevRouter.interpret(cfg, request, Decisions.call(cfg, JevStore.http, token.value, model, Attachments.state(request, attachments), JevRouter.questions(cfg)))
        val run = when {
            o.tool != null -> pick(ctx, api, cfg, token.value, o.tool!!, request, startTimer)
            o.fallback != null -> execute(ctx, api, o.fallback!!, startTimer)
            else -> null
        }
        return Answer(o, model, token.source, run)
    }

    /** Run [tool] on [request] — a confident pick, or the candidate the user tapped. */
    fun pick(ctx: Context, api: CalcApi, cfg: JevConfig, token: String?, tool: JevConfig.Tool, request: String, startTimer: Boolean): Run {
        val plan = JevRouter.plan(cfg, JevStore.http, token, JevStore.model(ctx, ROUTE_USE), tool, request, fields(tool))
        return execute(ctx, api, plan, startTimer)
    }

    /** A form tool's fields as (id, label, default), read from build.json::ui.modes. */
    fun fields(tool: JevConfig.Tool): List<Triple<String, String, String>> =
        Declarations.mode(tool.mode)?.forms?.firstOrNull { it.id == tool.form }?.fields?.map { Triple(it.id, it.label, it.default) }.orEmpty()

    fun execute(ctx: Context, api: CalcApi, plan: JevRouter.Plan, startTimer: Boolean): Run = when (plan) {
        is JevRouter.Plan.Expression -> {
            val mode = Declarations.mode(plan.mode)
            val r = Logic.result(api.eval(plan.text, mode?.options ?: "{}"))
            Run(plan.mode, plan.text, if (r.ok) listOf("" to r.text) else emptyList(), if (r.ok) "" else r.error.ifBlank { r.messages.joinToString() })
        }
        is JevRouter.Plan.Form -> {
            val mode = Declarations.mode(plan.mode)
            val form = mode?.forms?.firstOrNull { it.id == plan.form }
            if (mode == null || form == null) Run(plan.mode, plan.form, emptyList(), "no form ${plan.form} in mode ${plan.mode}", plan.slots)
            else {
                // #1329 a date-range calc (holidays) is the form screen's; #1377 the :tools calcs and the engine's are answered here.
                val lines = form.outputs.mapNotNull { o ->
                    val r = if (o.expr.isNotEmpty()) Logic.result(api.eval(Logic.fill(o.expr, plan.values), mode.options))
                    else DateForms.toolResult(o, form, plan.values) ?: return@mapNotNull null
                    o.label to (if (r.ok) r.text else "— " + r.error.ifBlank { r.messages.joinToString() })
                }
                val calc = form.label + ": " + form.fields.joinToString(", ") { f -> f.label + " " + plan.values[f.id].orEmpty() }
                Run(plan.mode, calc, lines, "", plan.slots)
            }
        }
        is JevRouter.Plan.Timer -> when {
            plan.seconds <= 0 -> Run(TIMER_MODE, "timer", emptyList(), "the request names no duration")
            !startTimer -> Run(TIMER_MODE, "timer ${plan.seconds} s", listOf("would start" to ClockLogic.countdown(plan.seconds * 1000)), "")
            else -> {
                val id = ClockEngine.addTimer(ctx, plan.label, plan.seconds * 1000, start = true)
                Run(TIMER_MODE, "timer ${plan.seconds} s", listOf("started timer $id" to ClockLogic.countdown(plan.seconds * 1000)), "")
            }
        }
        is JevRouter.Plan.Open -> Run(plan.mode, "open", listOf("" to (Declarations.mode(plan.mode)?.label ?: plan.mode)), "")
    }

    /** Addendum A: score [result] with question [q] on the scoring model. */
    fun askAbout(ctx: Context, q: JevConfig.Question, mode: String, calculation: String, result: String, context: String): Decision {
        val cfg = JevStore.config(ctx)
        val state = JevRouter.resultState(Declarations.mode(mode)?.label ?: mode, calculation, result, context)
        return JevRouter.ask(cfg, JevStore.http, JevStore.token(ctx).value, JevStore.model(ctx, SCORE_USE), q, state)
    }

    /** The JSON a history entry keeps for a follow-up question: the question, the model, every option. */
    fun decisionRecord(q: JevConfig.Question, d: Decision): String = JSONObject()
        .put("question", q.label).put("type", q.type).put("model", d.model)
        .put("options", Decisions.optionsJson(d.options(q.id))).put("error", d.error).toString()

    /** "yes 82% · no 18%" from a [decisionRecord]. */
    fun summary(record: String): String = runCatching {
        val o = JSONObject(record)
        val opts = o.getJSONArray("options")
        o.getString("question") + ": " + (0 until opts.length()).joinToString(" · ") { i ->
            opts.getJSONObject(i).let { it.getString("label") + " " + percent(it.getDouble("p")) }
        }.ifBlank { o.optString("error") }
    }.getOrDefault("")

    fun percent(p: Double): String = "${Math.round(p * 100)}%"

    /**
     * Configs › Token's Test: GET build.json::jev.key_url with the token in force. Says whether
     * OpenRouter accepts it and its usage/limit — never the token, nor the key's label (which can
     * carry part of it).
     */
    fun testToken(ctx: Context): String {
        val t = JevStore.token(ctx)
        val v = t.value ?: return "No token: ${t.source}"
        val cfg = JevStore.config(ctx)
        val r = runCatching { JevStore.http.send(cfg.keyUrl, v, null, cfg.timeoutMs) }
            .getOrElse { return "network: ${it.message ?: it.javaClass.simpleName}" }
        if (r.code !in 200..299) return "Refused (${t.source}): HTTP ${r.code}: ${Decisions.apiError(r.body)}"
        val data = runCatching { JSONObject(r.body).getJSONObject("data") }.getOrNull() ?: JSONObject()
        val facts = listOfNotNull(
            data.opt("usage")?.takeIf { it is Number }?.let { "usage $$it" },
            data.opt("limit")?.let { if (it == JSONObject.NULL) "no limit" else "limit $$it" },
            data.opt("is_free_tier")?.takeIf { it is Boolean }?.let { if (it == true) "free tier" else "paid tier" },
        )
        return "OK — OpenRouter accepts the ${t.source} token" + if (facts.isEmpty()) "" else " (" + facts.joinToString(", ") + ")"
    }

    const val ROUTE_USE = "route"
    const val SCORE_USE = "score"
    /** The Clock's timers mode, found by kind so no mode id is spelled here. */
    val TIMER_MODE: String get() = Declarations.modes.firstOrNull { it.kind == "timers" }?.id ?: "timers"
}
