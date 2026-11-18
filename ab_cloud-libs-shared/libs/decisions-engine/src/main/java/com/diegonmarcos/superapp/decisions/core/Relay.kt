package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import java.net.UnknownHostException

/**
 * #1801 one AI provider the relay can reach (decisions.json::relay.providers): its label for the key-settings screen and
 * its NAMED endpoints. A caller names an endpoint ("chat", "decisions"), never a URL, so a request can only ever take the
 * key to an address this declaration lists.
 */
data class RelayProvider(val id: String, val label: String, val endpoints: Map<String, String>)

/**
 * #1801 one declared RELAY use (decisions.json::relay.uses): an app that used to hold the provider key and make the call
 * itself (Search's AI chat, Browser's Search add-on, Calc's Jev, Writer's model routes, the image scan's OpenRouter route,
 * the keyboard's Text tools). The same identity and consent rules as a decision use, plus the [provider] whose key it spends
 * and the [endpoints] of that provider it may name.
 */
data class RelayUseDecl(
    val name: String,
    val enabled: Boolean,
    val maxCallsPerHour: Int,
    val consentRequired: Boolean,
    val content: String,
    val apps: Set<String>,
    val provider: String,
    val endpoints: Set<String>,
) {
    /** The consent book keys a grant by app and use name; this is the view of the use it reads. */
    fun asUse(): UseDecl = UseDecl(name, enabled, 1.0, emptySet(), UseClass.USER_FACING, 0, maxCallsPerHour, consentRequired, content, apps)
}

/**
 * The relay block of decisions.json: the providers and their endpoints, the timeout, the size limits, its own budget and
 * breaker (so a chat cannot spend the decisions' day nor trip their breaker), suppression, and the uses. A block that does
 * not validate throws [IllegalArgumentException]; [Policy.parse] then serves no relay use at all.
 */
class RelayPolicy private constructor(
    val providers: Map<String, RelayProvider>,
    val timeoutMs: Int,
    val maxBodyBytes: Int,
    val maxAnswerBytes: Int,
    val budget: BudgetRules,
    val suppress: SuppressRules,
    val breaker: BreakerRules,
    val uses: Map<String, RelayUseDecl>,
    val rejected: Map<String, String>,
) {
    /** Provider id to label, what the key book's status and the key-settings screen list. */
    fun labels(): Map<String, String> = providers.mapValues { it.value.label }

    companion object {
        fun parse(d: JSONObject, sensitive: Set<String>): RelayPolicy {
            val ps = d.optJSONObject("providers") ?: throw IllegalArgumentException("relay.providers is missing")
            val providers = LinkedHashMap<String, RelayProvider>()
            for (id in ps.keys().asSequence().filter { !it.startsWith("_") }) {
                val p = ps.optJSONObject(id) ?: throw IllegalArgumentException("relay.providers.$id is not an object")
                val eps = p.optJSONObject("endpoints") ?: throw IllegalArgumentException("relay.providers.$id.endpoints is missing")
                val map = LinkedHashMap<String, String>()
                for (n in eps.keys().asSequence().filter { !it.startsWith("_") }) {
                    val url = eps.optString(n)
                    require(url.startsWith("https://")) { "relay.providers.$id.endpoints.$n must be an https URL" }
                    map[n] = url
                }
                require(map.isNotEmpty()) { "relay.providers.$id declares no endpoint" }
                providers[id] = RelayProvider(id, p.optString("label").ifBlank { id }, map)
            }
            require(providers.isNotEmpty()) { "relay.providers declares no provider" }
            val timeout = d.optInt("timeout_ms", 0)
            require(timeout in 1000..300000) { "relay.timeout_ms must be 1000..300000" }
            val maxBody = d.optInt("max_body_bytes", 0)
            require(maxBody > 0) { "relay.max_body_bytes must be positive" }
            val maxAnswer = d.optInt("max_answer_bytes", 0)
            require(maxAnswer > 0) { "relay.max_answer_bytes must be positive" }
            val b = d.optJSONObject("budget") ?: throw IllegalArgumentException("relay.budget is missing")
            val budget = BudgetRules(b.pos("daily_usd_cap"), b.pos("per_app_daily_calls").toInt(), b.pos("est_call_usd"))
            val s = d.optJSONObject("suppress") ?: throw IllegalArgumentException("relay.suppress is missing")
            val suppress = SuppressRules(s.optBoolean("metered", true), s.optBoolean("offline", true), s.optBoolean("battery_saver", true))
            val br = d.optJSONObject("breaker") ?: throw IllegalArgumentException("relay.breaker is missing")
            val breaker = BreakerRules(br.pos("failures").toInt(), br.pos("open_s").toLong())
            val uses = LinkedHashMap<String, RelayUseDecl>()
            val rejected = LinkedHashMap<String, String>()
            val declared = d.optJSONObject("uses") ?: JSONObject()
            for (name in declared.keys().asSequence().filter { !it.startsWith("_") }) {
                val u = declared.optJSONObject(name)
                val problem = if (u == null) "is not an object" else check(u, sensitive, providers)
                if (problem != null) rejected[name] = problem else uses[name] = decl(name, u!!)
            }
            return RelayPolicy(providers, timeout, maxBody, maxAnswer, budget, suppress, breaker, uses, rejected)
        }

        private fun check(u: JSONObject, sensitive: Set<String>, providers: Map<String, RelayProvider>): String? {
            if (u.optInt("max_calls_per_hour", 0) < 1) return "max_calls_per_hour must be at least 1"
            if (u.optJSONArray("apps").strings().isEmpty()) return "apps names no app, so nothing may call it"
            val consent = u.optString("consent", "required")
            if (consent != "required" && consent != "implicit") return "consent must be required or implicit"
            if (consent == "implicit" && u.optString("content") in sensitive) return "content ${u.optString("content")} always needs consent: required"
            val p = providers[u.optString("provider")] ?: return "provider must be one of relay.providers"
            val eps = u.optJSONArray("endpoints").strings()
            if (eps.isEmpty()) return "endpoints names no endpoint"
            eps.firstOrNull { it !in p.endpoints }?.let { return "endpoint $it is not one of ${p.id}'s" }
            return null
        }

        private fun decl(name: String, u: JSONObject) = RelayUseDecl(
            name = name,
            enabled = u.optBoolean("enabled", false),
            maxCallsPerHour = u.getInt("max_calls_per_hour"),
            consentRequired = u.optString("consent", "required") == "required",
            content = u.optString("content"),
            apps = u.optJSONArray("apps").strings().toSet(),
            provider = u.getString("provider"),
            endpoints = u.optJSONArray("endpoints").strings().toSet(),
        )

        private fun JSONObject.pos(key: String): Double {
            val v = optDouble(key, Double.NaN)
            require(v > 0.0) { "relay.$key must be a positive number" }
            return v
        }
    }
}

/** What one relay call answered: [answer] is the wire's JSON (never the key), [body] the provider's answer when ok. */
class RelayAnswer(val answer: JSONObject, val body: ByteArray?)

/**
 * #1801 THE RELAY: an app hands over a declared use, an endpoint name and the request body it used to POST itself, and gets
 * the provider's status code and answer back. The key is read from the [keys] book in THIS process (the app's override
 * first, then the fleet key) and added to the one HTTPS call; the body never carries it and the caller never sees it.
 * [run] applies, in this order, and every way out but an answer is `{"ok":false,"reason":...}`:
 *
 *   no_relay_policy / misconfigured / unknown_use / app_not_allowed / disabled    the declaration
 *   bad_endpoint / bad_request / body_too_large                                   the request, before anything is sent
 *                                                                                  (`method: GET` sends no body: a key-usage read)
 *   no_consent                                                                    the user's grant for this app and use
 *   offline / metered / battery_saver                                            the phone's state
 *   breaker_open                                                                  after repeated server or network failures
 *   no_key                                                                        the key book, read in THIS process
 *   over_budget / app_quota / rate_limited                                        the relay ledger (its own)
 *   network / answer_too_large                                                    the call itself
 *
 * A provider's HTTP error is NOT a refusal: it comes back `ok` with its `code` and body, so the app reads it exactly as it
 * did when it made the call itself (its own error wording, its own parse). The journal gets one line per call (app, use,
 * endpoint, ✓/✗, reason, code, sizes, latency, cost): never the body, the answer or the key.
 */
class RelayEngine(
    private val policy: RelayPolicy?,
    private val http: Http,
    private val keys: KeyBook,
    private val env: Env,
    private val ledger: Ledger?,
    private val breaker: CircuitBreaker?,
    private val consent: ConsentBook,
    private val journal: Journal,
    private val clock: () -> Long,
) {
    /** The largest body a call may hand over (0 = no relay use is served); the service reads one byte more. */
    val maxBodyBytes: Int get() = policy?.maxBodyBytes ?: 0

    /** Provider id to label of every relay provider (empty with no relay block). */
    fun providers(): Map<String, String> = policy?.labels().orEmpty()

    /** Every app a relay use lists, in declaration order: the ones the AI keys screen offers a per-app override for. */
    fun apps(): List<String> = policy?.uses?.values?.flatMap { it.apps }?.distinct().orEmpty()

    fun run(app: String, request: JSONObject, body: ByteArray): RelayAnswer {
        val name = request.optString("use")
        return try {
            go(app, name, request, body)
        } catch (e: Exception) {
            refuse(app, name, "exception")
        }
    }

    private fun go(app: String, name: String, request: JSONObject, body: ByteArray): RelayAnswer {
        val p = policy ?: return refuse(app, name, "no_relay_policy")
        val ledger = ledger!!
        val breaker = breaker!!
        p.rejected[name]?.let { return refuse(app, name, "misconfigured") }
        val use = p.uses[name] ?: return refuse(app, name, "unknown_use")
        if (app !in use.apps) return refuse(app, name, "app_not_allowed")
        if (!use.enabled) return refuse(app, name, "disabled")
        val endpoint = request.optString("endpoint")
        if (endpoint !in use.endpoints) return refuse(app, name, "bad_endpoint")
        val url = p.providers[use.provider]?.endpoints?.get(endpoint) ?: return refuse(app, name, "bad_endpoint")
        // A GET (a provider's key-usage read: Calc's Configs › Token › Test) sends no body; anything else is a JSON POST.
        val get = request.optString("method") == "GET"
        val text: String? = if (get) null else {
            if (body.isEmpty()) return refuse(app, name, "bad_request")
            if (body.size > p.maxBodyBytes) return refuse(app, name, "body_too_large")
            body.toString(Charsets.UTF_8).also { t -> if (runCatching { JSONObject(t) }.isFailure) return refuse(app, name, "bad_request") }
        }
        if (!consent.granted(app, use.asUse())) return refuse(app, name, "no_consent")
        if (!env.online() && p.suppress.offline) return refuse(app, name, "offline", NO_NETWORK)
        if (env.metered() && p.suppress.metered) return refuse(app, name, "metered")
        if (env.batterySaver() && p.suppress.batterySaver) return refuse(app, name, "battery_saver")
        if (!breaker.allow()) return refuse(app, name, "breaker_open")
        val key = keys.get(use.provider, app)
        if (key.isNullOrBlank()) return refuse(app, name, "no_key", noKey(p.providers[use.provider]?.label ?: use.provider))
        ledger.admit(app, name, use.maxCallsPerHour)?.let { return refuse(app, name, it.wire) }
        val t0 = clock()
        val res = try {
            http.send(url, key, text, p.timeoutMs)
        } catch (e: Exception) {
            breaker.failure()
            val sizes = JSONObject().put("endpoint", endpoint).put("bytes_in", text?.length ?: 0).put("latency_ms", clock() - t0)
            return refuse(app, name, "network", transport(e), sizes)
        }
        val latency = clock() - t0
        val out = res.body.toByteArray(Charsets.UTF_8)
        val sizes = JSONObject().put("endpoint", endpoint).put("code", res.code).put("bytes_in", if (get) 0 else body.size).put("latency_ms", latency)
        if (res.code >= 500) breaker.failure() else breaker.success()
        val cost = costOf(res.body)
        ledger.charge(cost)
        if (out.size > p.maxAnswerBytes) return refuse(app, name, "answer_too_large", extra = sizes)
        journal.record(app, name, if (res.code in 200..299) "answered" else "http", JSONObject(sizes.toString()).put("kind", KIND)
            .put("bytes_out", out.size).put("cost", cost ?: JSONObject.NULL))
        return RelayAnswer(
            JSONObject().put("ok", true).put("use", name).put("code", res.code).put("bytes", out.size)
                .put("cost", cost ?: JSONObject.NULL).put("latency_ms", latency),
            out,
        )
    }

    /** The relay uses as the status method reports them: names and flags, never content. */
    fun status(app: String): JSONObject {
        val p = policy ?: return JSONObject().put("served", false)
        val uses = JSONObject()
        for ((n, u) in p.uses) {
            uses.put(n, JSONObject().put("enabled", u.enabled).put("allowed_for_app", app in u.apps)
                .put("consent", consent.granted(app, u.asUse())).put("provider", u.provider).put("key_present", keys.get(u.provider, app) != null))
        }
        return JSONObject().put("served", true).put("breaker_open", breaker!!.isOpen())
            .put("spent_today_usd", ledger!!.spentToday()).put("daily_usd_cap", p.budget.dailyUsdCap)
            .put("uses", uses).put("rejected", JSONArray(p.rejected.keys.toList()))
    }

    private fun refuse(app: String, use: String, reason: String, message: String? = null, extra: JSONObject? = null): RelayAnswer {
        val line = JSONObject((extra ?: JSONObject()).toString()).put("kind", KIND).put("reason", reason)
        journal.record(app, use, "refused", line)
        val answer = JSONObject().put("ok", false).put("use", use).put("reason", reason)
        message?.let { answer.put("message", it) }
        return RelayAnswer(answer, null)
    }

    companion object {
        const val KIND = "relay"
        const val NO_NETWORK = "No network. Connect and try again."

        /** The sentence a screen shows for `no_key`: where the key is set now. */
        fun noKey(label: String): String = "No $label key in the AI keys engine — set one in Cloud Lib Decisions Engine ▸ AI keys"

        fun transport(e: Throwable): String =
            if (e is UnknownHostException) NO_NETWORK else "The request did not complete (" + e.javaClass.simpleName + ")."

        /** usage.cost of an OpenAI-shape answer when it is a real number, else null (the ledger charges the estimate). */
        fun costOf(body: String): Double? = runCatching {
            JSONObject(body).optJSONObject("usage")?.opt("cost")?.takeIf { it is Number }?.let { (it as Number).toDouble() }
        }.getOrNull()
    }
}
