package com.diegonmarcos.cloudcalc.debugapi

import android.content.Context
import com.diegonmarcos.cloudcalc.BuildConfig
import com.diegonmarcos.cloudcalc.decide.JevFlow
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.cloudcalc.engine.CalcClient
import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.devtools.AppDebugServer
import org.json.JSONObject

/**
 * The Jev section on the fleet debug API (#770), so routing can be verified with the screen locked:
 *
 *   /api/jev/route?q=convert+3+ft+to+cm   the routing decision (every option's probability, the
 *                                         model, latency, cost), the chosen tool, and the result
 *                                         THIS app's engines computed; a timer is only started
 *                                         with run=1
 *   /api/jev/config                       the routing table in force, default or edited, the
 *                                         models per use, where the token comes from (masked)
 *
 * The token is never in an answer: requests are serialised without it and the source is masked.
 * Same path as the screen (JevFlow), so a green here is the app's real path.
 */
object JevDebugApi {
    @Volatile private var registered = false

    fun register(ctx: Context) {
        if (registered) return
        registered = true
        val app = ctx.applicationContext
        val client = CalcClient(app)
        AppDebugServer.route(
            BuildConfig.DEBUG_API_JEV_GROUP,
            listOf(
                AppDebugServer.Op("route", "q=<request>&run=<1 to really start a timer, default 0>", "route a natural-language request through the decision model and run the picked tool on this app's engines"),
                AppDebugServer.Op("config", "", "the routing table in force, models per use, token source (masked)"),
            ),
        ) { op, q ->
            when (op) {
                "route" -> {
                    val request = q["q"].orEmpty().trim()
                    if (request.isEmpty()) JSONObject().put("ok", false).put("error", "q is empty").toString()
                    else JevFlow.ask(app, client, request, startTimer = q["run"] == "1").toJson().toString()
                }
                "config" -> config(app).toString()
                else -> null
            }
        }
    }

    fun config(ctx: Context): JSONObject {
        val token = JevStore.token(ctx)
        return JSONObject()
            .put("edited", JevStore.isEdited(ctx))
            .put("config", JSONObject(JevStore.configJson(ctx)))
            .put("models", JSONObject().apply {
                JevStore.config(ctx).uses.keys.sorted().forEach { use ->
                    put(use, JSONObject().put("choice", JevStore.modelChoice(ctx, use)).put("runs_on", JevStore.model(ctx, use)))
                }
            })
            .put("catalogue", JSONObject().put("models", JevStore.catalogue(ctx).size).put("fetched_at", JevStore.catalogueAt(ctx)))
            .put("token", JSONObject().put("present", token.present).put("masked", if (token.present) "held by the AI keys engine" else Decisions.mask(null)).put("source", token.source))
    }
}
