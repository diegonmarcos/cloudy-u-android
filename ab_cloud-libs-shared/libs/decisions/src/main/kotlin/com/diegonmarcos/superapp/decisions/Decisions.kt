package com.diegonmarcos.superapp.decisions

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * THE fleet's client of OpenRouter's Decisions API (#770, shared by #772): one POST of
 * {model, state, questions}, read back as calibrated probabilities per question. Cloud Calc's
 * Jev section and the image engine (libs:ml-l-image-mlkit's OpenRouter route) both speak it
 * through this file, so the protocol, its failure reasons and its token masking exist once.
 *
 * HttpURLConnection over Android's own resolver, so the fleet DNS menu (#741/#758, applied at
 * the SuperApp's VPN) governs every lookup — no resolver of our own (dns-resolver-guard). No URL
 * is spelled here: every endpoint is the caller's declaration.
 */
interface Http {
    data class Response(val code: Int, val body: String)

    /** [body] null = GET. Throws on a transport failure (no route, timeout, TLS). */
    fun send(url: String, token: String?, body: String?, timeoutMs: Int): Response
}

/**
 * #1801 an [Http] that does not need the token: it hands the request to the fleet's AI-key owner engine
 * (DecisionsLink.relay), which adds the key in its own process. [Decisions.post] sends through it with no token.
 */
interface EngineKeyedHttp : Http

object UrlHttp : Http {
    override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = timeoutMs
            c.readTimeout = timeoutMs
            c.setRequestProperty("Accept", "application/json")
            if (token != null) c.setRequestProperty("Authorization", "Bearer $token")
            if (body != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            }
            val code = c.responseCode
            val text = (if (code in 200..299) c.inputStream else c.errorStream)?.use { it.readBytes().toString(Charsets.UTF_8) }.orEmpty()
            return Http.Response(code, text)
        } finally {
            c.disconnect()
        }
    }
}

/** One option of an answer with its probability, in the order the screen lists them. */
data class Option(val key: String, val label: String, val p: Double)

/**
 * One Decisions call, kept whole for the screens and the debug routes: what was sent (never the
 * token), what came back, and what it cost. [answers] is null on any failure, [error] says why.
 */
data class Decision(
    val model: String,
    val request: JSONObject,
    val answers: JSONObject?,
    val status: Int,
    val latencyMs: Long,
    val id: String,
    val cost: Double?,
    val inputTokens: Int?,
    val error: String,
) {
    val ok: Boolean get() = answers != null

    /** The request as sent, for display: method, URL, the header with the token masked, the body. */
    fun shownRequest(endpoint: String): String =
        "POST $endpoint\nAuthorization: Bearer $REDACTED\nContent-Type: application/json\n\n" + request.toString(2)

    /** Every option of answer [q] with its probability (noul: yes/no; score: its levels). */
    fun options(q: String): List<Option> = Decisions.options(answers?.optJSONObject(q))

    fun toJson(): JSONObject = JSONObject()
        .put("model", model).put("ok", ok).put("status", status).put("latency_ms", latencyMs)
        .put("id", id).put("cost", cost ?: JSONObject.NULL).put("input_tokens", inputTokens ?: JSONObject.NULL)
        .put("error", error).put("request", request).put("answers", answers ?: JSONObject.NULL)

    companion object {
        const val REDACTED = "[REDACTED]"
    }
}

object Decisions {
    /** The three question types the API takes. */
    const val NOUL = "noul"
    const val CHOICE = "choice"
    const val SCORE = "score"

    /** A probability the screens may trust: a real number in [0, 1], never a boolean or NaN. */
    fun isP(x: Any?): Boolean = x is Number && x !is Boolean && x.toDouble() in 0.0..1.0

    /**
     * POST one request to [endpoint]. Never throws: no token, a transport failure, a non-2xx, a
     * body that is not JSON or carries no `answers` object all come back as a failed [Decision]
     * with the reason. [questions] is the wire map, question id → {type, instructions, criteria?}.
     */
    fun post(endpoint: String, timeoutMs: Int, http: Http, token: String?, model: String, state: Any, questions: JSONObject, clock: () -> Long = System::currentTimeMillis): Decision {
        val req = JSONObject().put("model", model).put("state", state).put("questions", questions)
        fun failed(status: Int, ms: Long, why: String) = Decision(model, req, null, status, ms, "", null, null, why)
        if (token.isNullOrBlank() && http !is EngineKeyedHttp) return failed(0, 0, "no OpenRouter token — set one in Configs › Token")
        val t0 = clock()
        val res = runCatching { http.send(endpoint, token, req.toString(), timeoutMs) }
            .getOrElse { return failed(0, clock() - t0, "network: ${it.javaClass.simpleName}${it.message?.let { m -> ": $m" }.orEmpty()}") }
        val ms = clock() - t0
        if (res.code !in 200..299) return failed(res.code, ms, "HTTP ${res.code}: ${apiError(res.body)}")
        val o = runCatching { JSONObject(res.body) }.getOrNull() ?: return failed(res.code, ms, "the answer is not JSON")
        val answers = o.optJSONObject("answers") ?: return failed(res.code, ms, "the answer carries no answers object")
        val usage = o.optJSONObject("usage")
        return Decision(
            model, req, answers, res.code, ms, o.optString("id"),
            usage?.opt("cost")?.takeIf { it is Number }?.let { (it as Number).toDouble() },
            usage?.opt("input_tokens")?.takeIf { it is Number }?.let { (it as Number).toInt() },
            "",
        )
    }

    /** OpenRouter's {"error":{"message"}} shape, else the body's head. */
    fun apiError(body: String): String =
        runCatching { JSONObject(body).getJSONObject("error").getString("message") }.getOrElse { body.take(200) }

    /** The answer's options most probable first; an answer with a bad probability yields none. */
    fun options(a: JSONObject?): List<Option> {
        if (a == null) return emptyList()
        return when (a.optString("type")) {
            NOUL -> a.opt("noul").let { p ->
                if (!isP(p)) emptyList()
                else (p as Number).toDouble().let { listOf(Option("true", "yes", it), Option("false", "no", 1 - it)) }
            }
            SCORE -> probabilities(a) { k -> a.optJSONObject("legend")?.optString(k).orEmpty().ifBlank { k } }
            else -> probabilities(a) { it }
        }.sortedByDescending { it.p }
    }

    private fun probabilities(a: JSONObject, label: (String) -> String): List<Option> {
        val p = a.optJSONObject("probabilities") ?: return emptyList()
        val all = p.keys().asSequence().map { k -> k to p.opt(k) }.toList()
        if (all.isEmpty() || !all.all { isP(it.second) }) return emptyList()
        return all.map { (k, v) -> Option(k, label(k), (v as Number).toDouble()) }
    }

    /** The picked option of a choice answer and its probability, or null when the answer is unusable. */
    fun pick(a: JSONObject?, allowed: Set<String>): Option? {
        val choice = a?.opt("choice") as? String ?: return null
        if (choice !in allowed) return null
        val opts = options(a)
        return opts.firstOrNull { it.key == choice }
    }

    /** USD per token × an estimate of the prompt's tokens (4 characters each, the usual rule of thumb). */
    fun estimateCost(request: JSONObject, pricePerToken: Double?): Double? =
        pricePerToken?.let { ((request.toString().length + 3) / 4) * it }

    /** `sk-or-…abcd`: enough to tell two keys apart, never enough to use one. */
    fun mask(token: String?): String = when {
        token.isNullOrBlank() -> "—"
        token.length <= 8 -> "…" + "•".repeat(4)
        else -> token.take(6) + "…" + token.takeLast(4)
    }

    /** A Decisions request's `questions` map serialised for history, from its options. */
    fun optionsJson(opts: List<Option>): JSONArray =
        JSONArray().apply { opts.forEach { put(JSONObject().put("key", it.key).put("label", it.label).put("p", it.p)) } }
}
