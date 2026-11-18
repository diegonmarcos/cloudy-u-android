package com.diegonmarcos.superapp.decisions.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * What a use is for, and so what the engine enforces on it (jev-fleet-usage.md section 2.1):
 *  - [USER_FACING] may pre-select or re-rank what the user sees;
 *  - [BACKGROUND] must cache and never re-ask: a declaration without a ttl_s is refused;
 *  - [GATING] answers advice only (a suggestion, or a confirm in place of a refusal), never an action.
 */
enum class UseClass(val wire: String) {
    USER_FACING("user_facing"), BACKGROUND("background"), GATING("gating");

    companion object {
        fun of(wire: String?): UseClass? = values().firstOrNull { it.wire == wire }
    }
}

/**
 * One declared use, the SAME shape the host gate's jev-gate.json::uses declares (enabled, threshold,
 * allowed, class, ttl_s, max_calls_per_hour) plus what only a phone needs: the [apps] that may call it
 * (the engine reads the caller's identity from the binder, so an app cannot name another's use) and
 * the consent default.
 *
 * [allowed] is the closed set of option keys a choice question of this use may offer; empty = no
 * restriction. [consentRequired] true = the user must grant the use to the app before anything is sent
 * (default off); a use whose [content] is in the policy's sensitive set is always required.
 */
data class UseDecl(
    val name: String,
    val enabled: Boolean,
    val threshold: Double,
    val allowed: Set<String>,
    val cls: UseClass,
    val ttlS: Long,
    val maxCallsPerHour: Int,
    val consentRequired: Boolean,
    val content: String,
    val apps: Set<String>,
    /** Null = always served; else observe-only until the journal's evidence opens it (see [GateDecl]). */
    val gate: GateDecl? = null,
)

data class BudgetRules(val dailyUsdCap: Double, val perAppDailyCalls: Int, val estCallUsd: Double)
data class BreakerRules(val failures: Int, val openS: Long)
data class SuppressRules(val metered: Boolean, val offline: Boolean, val batterySaver: Boolean)

/** [patterns] are (regex, Java replacement) pairs applied to every string, in order. */
data class RedactRules(
    val maxChars: Int,
    val mask: String,
    val patterns: List<Pair<Regex, String>>,
    val dropKeys: Set<String>,
    val secretKey: Regex,
)

/**
 * The engine's whole declaration: the engine's own decisions.json asset (#881), parsed once. It is one
 * file for the whole fleet, not a copy in each app's build.json, so every threshold, cap and consent
 * default is tuned from one place and an app cannot ship a looser copy.
 *
 * A use that does not validate is NOT served: it is listed in [rejected] with the reason, and the engine
 * answers `misconfigured` for it. A block that is itself unusable (no https endpoint, no model, a cap
 * that is not a number) throws [IllegalArgumentException]: the engine then has no policy and answers
 * nothing, which every caller reads as "no opinion".
 */
class Policy private constructor(
    val endpoint: String,
    val model: String,
    val provider: String,
    val timeoutMs: Int,
    val budget: BudgetRules,
    val suppress: SuppressRules,
    val breaker: BreakerRules,
    val redact: RedactRules,
    val consentSetters: Set<String>,
    val sensitiveContent: Set<String>,
    val cacheMax: Int,
    val journalMax: Int,
    val uses: Map<String, UseDecl>,
    val rejected: Map<String, String>,
    /** #1799 the image block (decisions.json::image); null when it is absent or does not validate ([imageProblem] says why). */
    val image: ImagePolicy? = null,
    val imageProblem: String? = null,
) {
    companion object {
        /** #1799 2: the image method. Matches the service's CONTRACT meta-data. */
        const val CONTRACT = 2

        /** [d] is the whole decisions.json document. */
        fun parse(d: JSONObject): Policy {
            val endpoint = d.optString("endpoint")
            require(endpoint.startsWith("https://")) { "endpoint must be an https URL" }
            val model = d.optString("model").also { require(it.isNotBlank()) { "model is empty" } }
            val provider = d.optString("provider").also { require(it.isNotBlank()) { "provider is empty" } }
            val timeout = d.optInt("timeout_ms", 0).also { require(it in 1000..60000) { "timeout_ms must be 1000..60000" } }
            val b = d.optJSONObject("budget") ?: throw IllegalArgumentException("budget is missing")
            val budget = BudgetRules(
                b.positive("daily_usd_cap"), b.positive("per_app_daily_calls").toInt(), b.positive("est_call_usd"),
            )
            val s = d.optJSONObject("suppress") ?: throw IllegalArgumentException("suppress is missing")
            val suppress = SuppressRules(s.optBoolean("metered", true), s.optBoolean("offline", true), s.optBoolean("battery_saver", true))
            val br = d.optJSONObject("breaker") ?: throw IllegalArgumentException("breaker is missing")
            val breaker = BreakerRules(br.positive("failures").toInt(), br.positive("open_s").toLong())
            val rd = d.optJSONObject("redact") ?: throw IllegalArgumentException("redact is missing")
            val redact = RedactRules(
                maxChars = rd.positive("max_chars").toInt(),
                mask = rd.optString("mask").also { require(it.isNotEmpty()) { "redact.mask is empty" } },
                patterns = rd.optJSONArray("patterns").objects().map { Regex(it.getString("re")) to it.getString("sub") },
                dropKeys = rd.optJSONArray("drop_keys").strings().map { it.lowercase() }.toSet(),
                secretKey = Regex(rd.optString("secret_key").also { require(it.isNotEmpty()) { "redact.secret_key is empty" } }),
            )
            val sensitive = d.optJSONArray("sensitive_content").strings().toSet()
            val uses = LinkedHashMap<String, UseDecl>()
            val rejected = LinkedHashMap<String, String>()
            val declared = d.optJSONObject("uses") ?: JSONObject()
            for (name in declared.keys().asSequence().filter { !it.startsWith("_") }) {
                val u = declared.optJSONObject(name)
                val problem = if (u == null) "is not an object" else check(u, sensitive)
                if (problem != null) rejected[name] = problem else uses[name] = decl(name, u!!)
            }
            // #1799 an image block that does not validate serves no image use; the decisions are not affected.
            val imageBlock = d.optJSONObject("image")
            val image = imageBlock?.let { runCatching { ImagePolicy.parse(it, sensitive) } }
            return Policy(
                endpoint, model, provider, timeout, budget, suppress, breaker, redact,
                d.optJSONArray("consent_setters").strings().toSet(), sensitive,
                d.optInt("cache_max", 128), d.optInt("journal_max", 500), uses, rejected,
                image?.getOrNull(), image?.exceptionOrNull()?.let { it.message ?: it.javaClass.simpleName },
            )
        }

        /** Null when the use is servable, else why not. */
        private fun check(u: JSONObject, sensitive: Set<String>): String? {
            val cls = UseClass.of(u.optString("class")) ?: return "class must be user_facing, background or gating"
            val t = u.optDouble("threshold", Double.NaN)
            if (!(t > 0.0 && t <= 1.0)) return "threshold must be in (0, 1]"
            if (u.optInt("max_calls_per_hour", 0) < 1) return "max_calls_per_hour must be at least 1"
            val ttl = u.optLong("ttl_s", 0)
            if (ttl < 0) return "ttl_s must not be negative"
            if (cls == UseClass.BACKGROUND && ttl < 1) return "a background use must declare ttl_s: it caches and never re-asks"
            if (u.optJSONArray("apps").strings().isEmpty()) return "apps names no app, so nothing may call it"
            val consent = u.optString("consent", "required")
            if (consent != "required" && consent != "implicit") return "consent must be required or implicit"
            if (consent == "implicit" && u.optString("content") in sensitive) return "content ${u.optString("content")} always needs consent: required"
            u.optJSONObject("gate")?.let { g ->
                if (g.optJSONArray("after").strings().isEmpty()) return "gate.after names no use"
                if (g.optInt("min_labelled", 0) < 1) return "gate.min_labelled must be at least 1"
                val mp = g.optDouble("min_precision", Double.NaN)
                if (!(mp > 0.0 && mp <= 1.0)) return "gate.min_precision must be in (0, 1]"
            }
            return null
        }

        private fun decl(name: String, u: JSONObject) = UseDecl(
            name = name,
            enabled = u.optBoolean("enabled", false),
            threshold = u.getDouble("threshold"),
            allowed = u.optJSONArray("allowed").strings().toSet(),
            cls = UseClass.of(u.getString("class"))!!,
            ttlS = u.optLong("ttl_s", 0),
            maxCallsPerHour = u.getInt("max_calls_per_hour"),
            consentRequired = u.optString("consent", "required") == "required",
            content = u.optString("content"),
            apps = u.optJSONArray("apps").strings().toSet(),
            gate = u.optJSONObject("gate")?.let {
                GateDecl(it.optJSONArray("after").strings(), it.getInt("min_labelled"), it.getDouble("min_precision"))
            },
        )

        private fun JSONObject.positive(key: String): Double {
            val v = optDouble(key, Double.NaN)
            require(v > 0.0) { "$key must be a positive number" }
            return v
        }
    }
}

internal fun JSONArray?.strings(): List<String> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotEmpty() } }

internal fun JSONArray?.objects(): List<JSONObject> =
    if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
