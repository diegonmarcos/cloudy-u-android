package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/**
 * THE decisions engine (#881): the one place the fleet asks a decision model anything, on behalf of an
 * app that holds no key. [decide] applies, in this order, everything the design requires, and every way
 * out but an answer is `{"ok":false,"reason":...}`, which the caller reads as "no opinion" and answers
 * with TODAY'S behaviour (a Jev answer only reorders, pre-selects or suggests):
 *
 *   unknown_use / misconfigured / app_not_allowed / disabled     the declaration
 *   bad_questions / option_not_allowed                           the question shape, before anything is sent
 *   no_consent                                                   the user's grant for this app and use
 *   state_too_large                                              after redaction, never truncated
 *   (cache hit: answered here, free, even offline)
 *   offline / metered / battery_saver                            the phone's state
 *   breaker_open                                                 after repeated failures
 *   no_key                                                       the Account token, read in THIS process
 *   over_budget / app_quota / rate_limited                       the spend ledger
 *   network / http / malformed                                   the call itself
 *
 * The static deny lists of a site run before it ever calls [decide]; the engine adds a second, separate
 * line (consent, redaction, caps), it does not replace the first.
 *
 * [token] is called only when a call is about to be made and its value is passed to the one POST and
 * nowhere else: it is in no result, no journal line and no log.
 */
class Engine(
    val policy: Policy,
    private val http: Http,
    private val token: () -> String?,
    private val env: Env,
    private val ledger: Ledger,
    private val cache: AnswerCache,
    private val breaker: CircuitBreaker,
    private val consent: ConsentBook,
    private val journal: Journal,
    private val clock: () -> Long,
) {
    private val redactor = Redactor(policy.redact)

    fun decide(app: String, request: JSONObject): JSONObject {
        val name = request.optString("use")
        return try {
            run(app, name, request)
        } catch (e: Exception) {
            refuse(app, name, null, "exception")
        }
    }

    private fun run(app: String, name: String, request: JSONObject): JSONObject {
        policy.rejected[name]?.let { return refuse(app, name, null, "misconfigured") }
        val use = policy.uses[name] ?: return refuse(app, name, null, "unknown_use")
        if (app !in use.apps) return refuse(app, name, use, "app_not_allowed")
        if (!use.enabled) return refuse(app, name, use, "disabled")
        val questions = request.optJSONObject("questions")
        Verdicts.check(questions, use)?.let { return refuse(app, name, use, it) }
        if (!consent.granted(app, use)) return refuse(app, name, use, "no_consent")
        if (!request.has("state")) return refuse(app, name, use, "bad_request")
        val cleaned = redactor.clean(request.opt("state"))
        if (!redactor.fits(cleaned.state)) return refuse(app, name, use, "state_too_large")
        val key = sha256(name + "\u0000" + canon(cleaned.state) + "\u0000" + canon(questions))
        val call = key.take(12)
        if (use.ttlS > 0) {
            cache.get(key)?.let {
                journal.record(app, name, "cached", JSONObject().put("class", use.cls.wire).put("call", call))
                return held(app, use, call) ?: answered(use, it, true, call)
            }
        }
        if (!env.online() && policy.suppress.offline) return refuse(app, name, use, "offline")
        if (env.metered() && policy.suppress.metered) return refuse(app, name, use, "metered")
        if (env.batterySaver() && policy.suppress.batterySaver) return refuse(app, name, use, "battery_saver")
        if (!breaker.allow()) return refuse(app, name, use, "breaker_open")
        val key0 = token()
        if (key0.isNullOrBlank()) return refuse(app, name, use, "no_key")
        ledger.admit(app, name, use.maxCallsPerHour)?.let { return refuse(app, name, use, it.wire) }
        val t0 = clock()
        val d = Decisions.post(policy.endpoint, policy.timeoutMs, http, key0, policy.model, cleaned.state ?: JSONObject.NULL, questions!!, clock)
        val latency = clock() - t0
        if (!d.ok) {
            breaker.failure()
            return refuse(app, name, use, if (d.status == 0) "network" else "http", latency)
        }
        ledger.charge(d.cost)
        val results = Verdicts.read(d.answers!!, questions, use.threshold)
        if (results == null) {
            breaker.failure()
            return refuse(app, name, use, "malformed", latency)
        }
        breaker.success()
        if (use.ttlS > 0) cache.put(key, results, use.ttlS)
        val scores = JSONObject()
        for (id in results.keys()) scores.put(id, results.getJSONObject(id).getDouble("p"))
        journal.record(app, name, "answered", JSONObject().put("class", use.cls.wire).put("latency_ms", latency)
            .put("cost", d.cost ?: JSONObject.NULL).put("scores", scores).put("call", call).put("picks", picks(results))
            .put("masked", cleaned.masked).put("dropped", cleaned.dropped))
        return held(app, use, call) ?: answered(use, results, false, call)
    }

    /**
     * Record the user's choice for [app]'s [use]. Only an app the policy names as a consent setter (the
     * one that hosts the switch) may call it: an app cannot grant itself the right to send the user's mail.
     */
    fun setConsent(caller: String, request: JSONObject): JSONObject {
        val app = request.optString("app")
        val name = request.optString("use")
        if (caller !in policy.consentSetters) return refuse(caller, name, null, "not_a_consent_setter")
        // #1799 an image use's grant is kept in the same book, so the same switch host records it.
        val use = policy.uses[name] ?: policy.image?.uses?.get(name)?.asUse() ?: return refuse(caller, name, null, "unknown_use")
        if (app !in use.apps) return refuse(caller, name, use, "app_not_allowed")
        if (!request.has("granted") || request.opt("granted") !is Boolean) return refuse(caller, name, use, "bad_request")
        val granted = request.getBoolean("granted")
        consent.set(app, name, granted)
        journal.record(caller, name, "consent", JSONObject().put("for", app).put("granted", granted))
        return JSONObject().put("ok", true).put("use", name).put("app", app).put("granted", granted)
    }

    /** What an app (or the debug route) may know: whether things work, never what was asked or answered. */
    fun status(app: String): JSONObject {
        val uses = JSONObject()
        for ((n, u) in policy.uses) {
            uses.put(n, JSONObject().put("enabled", u.enabled).put("class", u.cls.wire)
                .put("allowed_for_app", app in u.apps).put("consent", consent.granted(app, u)))
        }
        return JSONObject().put("ok", true).put("contract", Policy.CONTRACT)
            .put("token", !token().isNullOrBlank())
            .put("breaker_open", breaker.isOpen())
            .put("online", env.online()).put("metered", env.metered()).put("battery_saver", env.batterySaver())
            .put("spent_today_usd", ledger.spentToday()).put("daily_usd_cap", policy.budget.dailyUsdCap)
            .put("uses", uses).put("rejected", JSONArray(policy.rejected.keys.toList()))
    }

    /**
     * What really happened to decision [request]`.call` of the caller's [use], reported by the caller from
     * the observed result (the user moved the mail, undid the change, dismissed the alert). Joined to the
     * decision by the call id; the journal holds no state, so the label is the option or yes/no only.
     */
    fun outcome(caller: String, request: JSONObject): JSONObject {
        val name = request.optString("use")
        val use = policy.uses[name] ?: return refuse(caller, name, null, "unknown_use")
        if (caller !in use.apps) return refuse(caller, name, use, "app_not_allowed")
        val call = request.optString("call")
        val label = request.opt("label")
        if (call.isEmpty() || call.length > 64 || !(label is JSONObject || label is String || label is Boolean)) {
            return refuse(caller, name, use, "bad_request")
        }
        journal.record(caller, name, "outcome", JSONObject().put("call", call).put("label", label))
        return JSONObject().put("ok", true).put("use", name).put("call", call)
    }

    /** `gate_closed` (with the call id, so its outcome can still be recorded) while the use's evidence is not in. */
    private fun held(app: String, use: UseDecl, call: String): JSONObject? {
        val gate = use.gate ?: return null
        if (Evidence.open(gate, journal.lines(policy.journalMax))) return null
        journal.record(app, use.name, "held", JSONObject().put("class", use.cls.wire).put("call", call))
        return JSONObject().put("ok", false).put("use", use.name).put("reason", "gate_closed").put("call", call)
    }

    /** The confident pick of each question: a noul's yes/no, a choice's option, a score's level. */
    private fun picks(results: JSONObject): JSONObject {
        val out = JSONObject()
        for (id in results.keys()) {
            val r = results.getJSONObject(id)
            if (!r.optBoolean("confident")) continue
            when (r.getString("type")) {
                "noul" -> out.put(id, r.getBoolean("value"))
                "choice" -> out.put(id, r.getString("pick"))
                else -> out.put(id, r.getString("level"))
            }
        }
        return out
    }

    private fun answered(use: UseDecl, results: JSONObject, cached: Boolean, call: String): JSONObject =
        JSONObject().put("ok", true).put("use", use.name).put("class", use.cls.wire).put("cached", cached)
            .put("threshold", use.threshold).put("call", call).put("results", results)
            .also { if (use.cls == UseClass.GATING) it.put("advice_only", true) }

    private fun refuse(app: String, use: String, decl: UseDecl?, reason: String, latency: Long? = null): JSONObject {
        val extra = JSONObject().put("reason", reason)
        decl?.let { extra.put("class", it.cls.wire) }
        latency?.let { extra.put("latency_ms", it) }
        journal.record(app, use, "refused", extra)
        return JSONObject().put("ok", false).put("use", use).put("reason", reason)
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** A JSON value with object keys sorted, so the same state always hashes the same. */
    private fun canon(x: Any?): String = when (x) {
        is JSONObject -> x.keys().asSequence().sorted().joinToString(",", "{", "}") { "\"$it\":" + canon(x.opt(it)) }
        is JSONArray -> (0 until x.length()).joinToString(",", "[", "]") { canon(x.opt(it)) }
        is String -> JSONObject.quote(x)
        null -> "null"
        else -> x.toString()
    }
}
