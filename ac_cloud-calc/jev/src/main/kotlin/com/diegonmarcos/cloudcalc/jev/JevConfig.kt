package com.diegonmarcos.cloudcalc.jev

import org.json.JSONArray
import org.json.JSONObject

/**
 * build.json::jev — THE ONE declaration of how Cloud Calc talks to OpenRouter's Decisions API:
 * where, which model per use, the routing table offered to the model in Jev mode (tools,
 * questions, thresholds, fallback) and the preset follow-up questions per mode. The router, the
 * Configs screens and /api/jev/config all read this; the user's edit (Configs › Routing) is a
 * full replacement of the same JSON, validated by [parse] before it is stored.
 */
data class JevConfig(
    val endpoint: String,
    val modelsUrl: String,
    val keyUrl: String,
    /** The provider id whose key the AI-key owner engine spends for this app (#1801: DecisionsLink.relay; no key is read here). */
    val accountProvider: String,
    val timeoutMs: Int,
    val catalogTtlHours: Int,
    /** Use id ("route", "score") → a model slug, or [CHEAPEST]. */
    val uses: Map<String, String>,
    /** The model a use falls back to when nothing is cached and the use says [CHEAPEST]. */
    val fallbackModel: String,
    /** What Configs › Models calls a use; [useLabel] falls back to the id. */
    val useLabels: Map<String, String> = emptyMap(),
    val route: Route,
    /** Mode id → its preset follow-up questions; [ANY_MODE] is offered under every mode. */
    val ask: Map<String, List<Question>>,
    /** #772 "What is this?" per measurement kind ("sound"): one choice over [Identify.classes] plus extras. */
    val identify: Map<String, Identify> = emptyMap(),
) {
    /**
     * A Decisions question as the API takes it: noul (criteria true/false), choice (criteria
     * key → text) or score (criteria = ordered levels). [label] is what a chip shows.
     */
    data class Question(
        val id: String,
        val label: String,
        val type: String,
        val instructions: String,
        val choices: Map<String, String> = emptyMap(),
        val levels: List<String> = emptyList(),
    ) {
        fun wire(): JSONObject {
            val o = JSONObject().put("type", type).put("instructions", instructions)
            // A noul may carry {true, false} criteria too; a choice always carries its options.
            if (type == SCORE) o.put("criteria", JSONArray(levels))
            else if (type == CHOICE || choices.isNotEmpty()) o.put("criteria", JSONObject(choices))
            return o
        }
    }

    /** One routing option: [action] is expression | form | timer | open, run by the app. */
    data class Tool(val id: String, val criterion: String, val action: String, val mode: String, val form: String)

    data class Route(
        val instructions: String,
        val noneCriterion: String,
        val threshold: Double,
        val candidates: Int,
        val candidateMinP: Double,
        /** expression = evaluate the request text as typed in [fallbackMode]; none = say so. */
        val onError: String,
        val fallbackMode: String,
        val slotThreshold: Double,
        val slotInstructions: String,
        val strip: List<String>,
        val durationUnits: Map<String, Int>,
        val tools: List<Tool>,
        /** Extra questions asked alongside the route; shown with their probabilities. */
        val extra: List<Question>,
    )

    /**
     * #772 a decision model cannot hear or measure: it is sent what the app measured, and asked
     * which of [classes] it is (a choice), plus [extra] noul/score questions about the same thing.
     */
    data class Identify(val instructions: String, val classes: Map<String, String>, val extra: List<Question>)

    fun model(use: String): String = uses[use] ?: CHEAPEST

    fun useLabel(use: String): String = useLabels[use] ?: use

    fun questionsFor(mode: String): List<Question> = ask[mode].orEmpty() + ask[ANY_MODE].orEmpty()

    companion object {
        const val CHEAPEST = "cheapest"
        const val ANY_MODE = "*"
        const val NOUL = "noul"
        const val CHOICE = "choice"
        const val SCORE = "score"
        const val NONE = "none"
        /** The id of the identify choice question in a request and its answers. */
        const val IDENTIFY_CLASS = "class"
        val ACTIONS = setOf("expression", "form", "timer", "open")
        val ON_ERROR = setOf("expression", NONE)

        /** The declaration, or IllegalArgumentException naming what is wrong — never half a config. */
        fun parse(json: String): JevConfig =
            try {
                read(json).also { validate(it) }
            } catch (e: org.json.JSONException) {
                throw IllegalArgumentException(e.message ?: "malformed declaration")
            }

        private fun read(json: String): JevConfig {
            val o = runCatching { JSONObject(json) }.getOrElse { throw IllegalArgumentException("not a JSON object: ${it.message}") }
            val r = o.req("route")
            val cfg = JevConfig(
                endpoint = o.reqString("endpoint"),
                modelsUrl = o.reqString("models_url"),
                keyUrl = o.reqString("key_url"),
                accountProvider = o.reqString("account_provider"),
                timeoutMs = o.optInt("timeout_ms", 8000),
                catalogTtlHours = o.optInt("catalog_ttl_hours", 24),
                uses = o.req("uses").let { u -> u.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { u.getString(it) } },
                fallbackModel = o.reqString("fallback_model"),
                useLabels = (o.optJSONObject("use_labels") ?: JSONObject()).let { u -> u.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { u.getString(it) } },
                route = Route(
                    instructions = r.reqString("instructions"),
                    noneCriterion = r.reqString("none_criterion"),
                    threshold = r.getDouble("threshold"),
                    candidates = r.optInt("candidates", 3),
                    candidateMinP = r.optDouble("candidate_min_p", 0.05),
                    onError = r.optString("on_error", "expression"),
                    fallbackMode = r.optString("fallback_mode"),
                    slotThreshold = r.optDouble("slot_threshold", 0.6),
                    slotInstructions = r.reqString("slot_instructions"),
                    strip = (r.optJSONArray("strip") ?: JSONArray()).let { a -> (0 until a.length()).map { a.getString(it) } },
                    durationUnits = (r.optJSONObject("duration_units") ?: JSONObject()).let { d -> d.keys().asSequence().associateWith { d.getInt(it) } },
                    tools = r.req("tools").let { t ->
                        t.keys().asSequence().filterNot { it.startsWith("_") }.map { id ->
                            val x = t.getJSONObject(id)
                            Tool(id, x.reqString("criterion"), x.reqString("action"), x.optString("mode"), x.optString("form"))
                        }.toList()
                    },
                    extra = questions(r.optJSONObject("questions")),
                ),
                ask = (o.optJSONObject("ask") ?: JSONObject()).let { a ->
                    a.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { m ->
                        val list = a.getJSONArray(m)
                        (0 until list.length()).map { question(list.getJSONObject(it).optString("id", "q$it"), list.getJSONObject(it)) }
                    }
                },
                identify = (o.optJSONObject("identify") ?: JSONObject()).let { i ->
                    i.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { k ->
                        val x = i.getJSONObject(k)
                        val c = x.req("classes")
                        Identify(
                            x.reqString("instructions"),
                            c.keys().asSequence().filterNot { it.startsWith("_") }.associateWith { c.getString(it) },
                            questions(x.optJSONObject("questions")),
                        )
                    }
                },
            )
            return cfg
        }

        private fun validate(c: JevConfig) {
            fun need(ok: Boolean, why: String) { if (!ok) throw IllegalArgumentException(why) }
            for (u in listOf(c.endpoint, c.modelsUrl, c.keyUrl)) need(u.startsWith("https://"), "$u is not https — the token would travel in clear")
            need(c.timeoutMs in 1000..60000, "timeout_ms must be 1000..60000")
            need(c.catalogTtlHours >= 1, "catalog_ttl_hours must be at least 1")
            need("route" in c.uses && "score" in c.uses, "uses must name a model for route and for score")
            val r = c.route
            for ((name, p) in listOf("threshold" to r.threshold, "candidate_min_p" to r.candidateMinP, "slot_threshold" to r.slotThreshold))
                need(p in 0.0..1.0, "route.$name must be within 0..1")
            need(r.candidates >= 1, "route.candidates must be at least 1")
            need(r.onError in ON_ERROR, "route.on_error must be one of $ON_ERROR")
            need(r.onError != "expression" || r.fallbackMode.isNotBlank(), "route.on_error=expression needs route.fallback_mode")
            need(r.tools.isNotEmpty(), "route.tools offers nothing to pick")
            need(r.tools.none { it.id == NONE }, "route.tools may not define \"$NONE\": it is always offered")
            for (t in r.tools) {
                need(t.action in ACTIONS, "tool ${t.id}: action must be one of $ACTIONS")
                need(t.action == "timer" || t.mode.isNotBlank(), "tool ${t.id}: action ${t.action} needs a mode")
                need(t.action != "form" || t.form.isNotBlank(), "tool ${t.id}: action form needs a form")
            }
            need(r.action("timer") == null || r.durationUnits.isNotEmpty(), "a timer tool needs route.duration_units")
            for (s in r.strip) runCatching { Regex(s) }.onFailure { throw IllegalArgumentException("route.strip: bad pattern $s") }
            for ((k, i) in c.identify) {
                need(i.classes.size >= 2, "identify.$k needs at least two classes")
                need(i.extra.none { it.id == IDENTIFY_CLASS }, "identify.$k: a question may not be named \"$IDENTIFY_CLASS\"")
            }
            for (q in r.extra + c.ask.values.flatten() + c.identify.values.flatMap { it.extra }) {
                need(q.type in setOf(NOUL, CHOICE, SCORE), "question ${q.id}: type must be noul, choice or score")
                need(q.type != CHOICE || q.choices.size >= 2, "question ${q.id}: a choice needs at least two criteria")
                need(q.type != SCORE || q.levels.size >= 2, "question ${q.id}: a score needs at least two levels")
            }
        }

        private fun Route.action(a: String) = tools.firstOrNull { it.action == a }

        private fun questions(o: JSONObject?): List<Question> =
            (o ?: JSONObject()).let { q -> q.keys().asSequence().filterNot { it.startsWith("_") }.map { question(it, q.getJSONObject(it)) }.toList() }

        fun question(id: String, q: JSONObject): Question {
            val type = q.reqString("type")
            val crit = q.opt("criteria")
            return Question(
                id = id,
                label = q.optString("label", id),
                type = type,
                instructions = q.reqString("instructions"),
                choices = (crit as? JSONObject)?.let { c -> c.keys().asSequence().associateWith { c.getString(it) } } ?: emptyMap(),
                levels = (crit as? JSONArray)?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
            )
        }

        private fun JSONObject.req(k: String): JSONObject = optJSONObject(k) ?: throw IllegalArgumentException("$k is missing")
        private fun JSONObject.reqString(k: String): String =
            optString(k).takeIf { it.isNotBlank() } ?: throw IllegalArgumentException("$k is missing")
    }
}
