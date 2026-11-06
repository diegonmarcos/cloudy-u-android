package com.diegonmarcos.superapp.headless

import org.json.JSONArray
import org.json.JSONObject

/** #1498 headless-api v1 (libs/headless-api/headless-api.json): the command set, validation and reply shapes shared by every backend. */
object HeadlessApi {
    val CMDS = listOf("open", "close", "wait", "eval", "click", "type", "scroll", "html", "text", "readable", "screenshot", "cookies", "storage", "network", "content", "pdf", "links", "scrape", "snapshot")
    const val MAX_TIMEOUT_MS = 120_000
    const val MAX_STEPS = 50
    private val ID = Regex("^[A-Za-z0-9_-]{1,64}$")

    class Err(val code: String, message: String) : Exception(message)

    /** Throws [Err] (bad_request) unless [c] is a valid Command. */
    fun validate(c: JSONObject): JSONObject {
        val cmd = c.optString("cmd")
        if (cmd !in CMDS) throw Err("bad_request", "unknown cmd $cmd")
        if (c.has("session") && !ID.matches(c.optString("session"))) throw Err("bad_request", "bad session id")
        if (c.has("timeoutMs")) { val t = c.optInt("timeoutMs", -1); if (t < 1 || t > MAX_TIMEOUT_MS) throw Err("bad_request", "bad timeoutMs") }
        fun need(k: String) { if (c.optString(k).isEmpty()) throw Err("bad_request", "$cmd needs $k") }
        when (cmd) {
            "open" -> { need("url"); if (!Regex("^https?://", RegexOption.IGNORE_CASE).containsMatchIn(c.optString("url"))) throw Err("bad_request", "url must be http(s)") }
            "screenshot", "snapshot" -> {
                if (c.has("format") && c.optString("format") !in listOf("png", "jpeg")) throw Err("bad_request", "format png|jpeg")
                if (c.has("quality")) { val q = c.optInt("quality", -1); if (q < 1 || q > 100) throw Err("bad_request", "quality 1..100") }
            }
            "scrape" -> {
                val els = c.optJSONArray("elements")
                if (els == null || els.length() < 1 || els.length() > 20) throw Err("bad_request", "scrape needs elements[1..20] with selector")
                for (i in 0 until els.length()) if (els.optJSONObject(i)?.optString("selector").isNullOrEmpty()) throw Err("bad_request", "scrape needs elements[1..20] with selector")
                if (c.has("limit")) { val l = c.optInt("limit", -1); if (l < 1 || l > 1000) throw Err("bad_request", "limit 1..1000") }
            }
            "eval" -> need("js")
            "readable" -> HeadlessReadable.validate(c)
            "click" -> need("selector")
            "type" -> { need("selector"); if (!c.has("text")) throw Err("bad_request", "type needs text") }
            "wait" -> if (listOf("selector", "idle", "ms").count { c.has(it) } != 1) throw Err("bad_request", "wait needs exactly one of selector|idle|ms")
            "storage" -> if (c.optString("area") !in listOf("local", "session")) throw Err("bad_request", "storage area local|session")
        }
        return c
    }

    /** Throws [Err] unless [r] is a valid Run (1..50 valid steps). */
    fun validateRun(r: JSONObject): JSONObject {
        val steps = r.optJSONArray("steps") ?: throw Err("bad_request", "steps 1..$MAX_STEPS")
        if (steps.length() < 1 || steps.length() > MAX_STEPS) throw Err("bad_request", "steps 1..$MAX_STEPS")
        if (r.has("stepTimeoutMs")) { val t = r.optInt("stepTimeoutMs", -1); if (t < 1 || t > MAX_TIMEOUT_MS) throw Err("bad_request", "bad stepTimeoutMs") }
        for (i in 0 until steps.length()) validate(steps.getJSONObject(i))
        return r
    }

    fun ok(session: String?, result: Any?): JSONObject = JSONObject().put("ok", true).apply { if (session != null) put("session", session); put("result", result ?: JSONObject.NULL) }
    fun fail(code: String, message: String): JSONObject = JSONObject().put("ok", false).put("error", JSONObject().put("code", code).put("message", message))
    fun fail(e: Throwable): JSONObject = if (e is Err) fail(e.code, e.message ?: "") else fail("internal", e.message ?: e.javaClass.simpleName)

    /** HTTP status of an error code, as the container maps it. */
    fun httpStatus(code: String): Int = when (code) { "bad_request" -> 400; "unauthorized" -> 401; "not_found" -> 404; "timeout" -> 504; "limit" -> 429; "denied" -> 403; "unsupported" -> 501; else -> 500 }

    fun stepsOf(r: JSONObject): List<JSONObject> = r.getJSONArray("steps").let { a: JSONArray -> (0 until a.length()).map { a.getJSONObject(it) } }
}
