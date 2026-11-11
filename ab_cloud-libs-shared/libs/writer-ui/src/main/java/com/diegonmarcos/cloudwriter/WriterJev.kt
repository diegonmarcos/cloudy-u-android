package com.diegonmarcos.cloudwriter

import android.content.Context
import com.diegonmarcos.cloudwriter.core.Answer
import com.diegonmarcos.cloudwriter.core.Route
import com.diegonmarcos.superapp.decisions.link.Advice
import com.diegonmarcos.superapp.decisions.link.DecisionsLink
import org.json.JSONObject
import java.util.Calendar

/**
 * #882 (Jev wave 1, spec section 3 item 9): which ROUTE a function runs on when the owner never chose one.
 *
 * Only an UNCHOSEN route is advised ([com.diegonmarcos.cloudwriter.core.Routing.chosen]); a saved one is never
 * touched. The decision model may say "on-device" for a function the default would send to a model. It holds no
 * key (the engine does), waits at most [WAIT_MS] and never on the main thread, and every doubt is no advice =
 * the default route. The OBSERVED result is the outcome: the route that actually answered ([observed]).
 */
object WriterJev {

    private const val WAIT_MS = 1200L
    private const val TTL_MS = 30L * 60 * 1000
    private val memo = Advice.Suggestions()

    private fun slot(f: WriterRoutes.Function, online: Boolean) = "${f.key}:$online"

    /** The advised route, or null (no opinion, or nothing worth asking). Blocking up to [WAIT_MS]; null on the main thread. */
    fun advice(ctx: Context, f: WriterRoutes.Function): Route? {
        val online = WriterRoutes.online(ctx)
        val k = slot(f, online)
        memo.peek(k)?.let { return routeOf(it.pick) }
        val app = ctx.applicationContext
        val got = Advice.within(WAIT_MS) {
            val o = DecisionsLink.outcome(app, "writer_route",
                JSONObject().put("function", f.key).put("online", online).put("hour", Calendar.getInstance().get(Calendar.HOUR_OF_DAY)),
                questions())
            Advice.Suggestion(o.verdictOrNull?.pick("route", setOf("model", "ml")).orEmpty(), o.callOrNull)
        } ?: return null
        memo.put(k, got, TTL_MS)
        return routeOf(got.pick)
    }

    internal fun routeOf(pick: String?): Route? = Route.values().firstOrNull { it.id == pick && !pick.isNullOrEmpty() }

    internal fun questions(): JSONObject = JSONObject().put(
        "route", JSONObject().put("type", "choice").put(
            "instructions",
            "Which route should the writing app's `function` (speech-to-text or translation) run on, given whether the phone is `online` at hour `hour`?",
        ).put("criteria", JSONObject()
            .put("model", "A hosted model: better quality, needs the network and costs a little.")
            .put("ml", "The phone's own offline engine: instant, free and private, rougher quality.")),
    )

    /** [answer] is what the advised route really did: report the route that ANSWERED (a model failure that fell back to on-device says ml). */
    fun observed(ctx: Context, f: WriterRoutes.Function, advised: Route?, answer: Answer) {
        if (advised == null) return
        val answered = answer.route ?: return
        val k = slot(f, WriterRoutes.online(ctx))
        val s = memo.peek(k) ?: return
        val call = s.call ?: return
        memo.put(k, Advice.Suggestion(s.pick, null), TTL_MS)   // report once per decision
        Advice.explicit(ctx, "writer_route", call, JSONObject().put("route", answered.id))
    }
}
