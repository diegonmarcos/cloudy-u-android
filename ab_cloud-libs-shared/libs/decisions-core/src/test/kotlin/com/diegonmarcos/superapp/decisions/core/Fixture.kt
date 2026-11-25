package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** The test doubles and the declaration every suite builds its engine from. */
class FakeHttp : Http {
    val sent = ArrayList<JSONObject>()
    val tokens = ArrayList<String?>()
    var reply: (JSONObject) -> Http.Response = { req -> Http.Response(200, ok(req)) }
    var throwing: Exception? = null
    var cost: Double? = 0.0001

    override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
        throwing?.let { throw it }
        val req = JSONObject(body!!)
        sent.add(req)
        tokens.add(token)
        endpoints.add(url)
        return reply(req)
    }

    val endpoints = ArrayList<String>()

    /** A well formed answer to every question: noul 0.95, the first option of a choice at 0.9. */
    fun ok(req: JSONObject): String {
        val answers = JSONObject()
        val qs = req.getJSONObject("questions")
        for (id in qs.keys()) {
            val q = qs.getJSONObject(id)
            answers.put(id, when (q.getString("type")) {
                "noul" -> JSONObject().put("type", "noul").put("noul", 0.95)
                "choice" -> {
                    val keys = q.getJSONObject("criteria").keys().asSequence().toList()
                    val probs = JSONObject()
                    keys.forEachIndexed { i, k -> probs.put(k, if (i == 0) 0.9 else 0.1 / (keys.size - 1)) }
                    JSONObject().put("type", "choice").put("choice", keys[0]).put("probabilities", probs)
                }
                else -> JSONObject().put("type", "score").put("probabilities", JSONObject().put("low", 0.2).put("high", 0.8))
            })
        }
        val o = JSONObject().put("id", "gen-1").put("answers", answers)
        cost?.let { o.put("usage", JSONObject().put("cost", it)) }
        return o.toString()
    }
}

class FakeEnv(var online: Boolean = true, var metered: Boolean = false, var saver: Boolean = false) : Env {
    override fun online() = online
    override fun metered() = metered
    override fun batterySaver() = saver
}

class Clock(var now: Long = 1_800_000_000_000L) : () -> Long {
    override fun invoke() = now
}

object Fixtures {
    const val APP = "com.diegonmarcos.cloudcalc"
    const val SETTER = "com.diegonmarcos.superapp"
    // Assembled at run time: a secret-shaped literal in a source file is exactly what the leak scan hunts.
    private fun join(vararg p: String) = p.joinToString("")
    val SECRET = join("sk-or", "-v1-", "0123456789abcdef0123456789abcdef")
    val SHORT_SECRET = join("sk-or", "-v1-", "0123456789abcdef")
    val TOKEN = join("tok", "-", "value", "-", "1234")
    val SAMPLES = listOf(
        join("key ", SECRET, " end"),
        join("-----BEGIN OPENSSH ", "PRIVATE KEY-----\nabc\n-----END OPENSSH PRIVATE KEY-----"),
        join("AGE-", "SECRET-KEY-1ABCDEF0123456789"),
        join("ENC[AES256", "_GCM,data:abc,iv:def]"),
        join("gh", "p_0123456789abcdefghijABCDEFGHIJ"),
        join("AK", "IAABCDEFGHIJKLMNOP"),
        join("xox", "b-1234567890-abcdef"),
        join("ey", "JhbGciOiJIUzI1NiJ9", ".", "eyJzdWIiOiIxMjM0NTY3ODkwIn0", ".", "abcdefghijklmnop"),
    )

    /** A decisions document that parses; [tweak] edits it before the parse. */
    fun block(tweak: (JSONObject) -> Unit = {}): JSONObject {
        val d = JSONObject("""
        {"contract":1,"endpoint":"https://example.test/decisions","model":"m/1","provider":"openrouter","timeout_ms":8000,
         "budget":{"daily_usd_cap":0.01,"per_app_daily_calls":3,"est_call_usd":0.001},
         "suppress":{"metered":true,"offline":true,"battery_saver":true},
         "breaker":{"failures":2,"open_s":60},
         "redact":{"max_chars":200,"mask":"[X]","drop_keys":["Body","html"],"secret_key":"(?i)(token|passw)",
           "patterns":[{"re":"sk-[A-Za-z0-9-]{16,}","sub":"[X]"},{"re":"(?i)(bearer\\s+)\\S+","sub":"${'$'}1[X]"}]},
         "sensitive_content":["mail","git"],"consent_setters":["$SETTER"],"cache_max":2,"journal_max":4,
         "uses":{
          "ui":{"enabled":true,"class":"user_facing","threshold":0.8,"allowed":["a","b"],"max_calls_per_hour":2,"consent":"implicit","content":"none","apps":["$APP"]},
          "bg":{"enabled":true,"class":"background","threshold":0.8,"ttl_s":60,"max_calls_per_hour":5,"consent":"implicit","content":"none","apps":["$APP"]},
          "gate":{"enabled":true,"class":"gating","threshold":0.8,"max_calls_per_hour":5,"consent":"implicit","apps":["$APP"]},
          "mailuse":{"enabled":true,"class":"user_facing","threshold":0.8,"max_calls_per_hour":5,"consent":"required","content":"mail","apps":["$APP"]},
          "off":{"enabled":false,"class":"user_facing","threshold":0.8,"max_calls_per_hour":5,"consent":"implicit","apps":["$APP"]}
         }}""".trimIndent())
        tweak(d)
        return d
    }

    /** The real declaration, so the shipped file is parsed and exercised by the same suite. */
    fun realManifest(): JSONObject = JSONObject(File("../../../fleet-private-data/decisions.json").readText())

    fun noulQ(id: String = "ok") = JSONObject().put(id, JSONObject().put("type", "noul").put("instructions", "Is it fine?"))

    fun choiceQ(vararg opts: String): JSONObject {
        val crit = JSONObject()
        opts.forEach { crit.put(it, "$it option") }
        return JSONObject().put("pick", JSONObject().put("type", "choice").put("instructions", "Which?").put("criteria", crit))
    }

    fun request(use: String, state: Any? = JSONObject().put("n", 1), questions: JSONObject = noulQ()): JSONObject =
        JSONObject().put("use", use).put("state", state).put("questions", questions)
}

/** A whole engine over fakes. */
class Rig(policyRoot: JSONObject = Fixtures.block(), val token: String? = Fixtures.TOKEN) {
    val policy = Policy.parse(policyRoot)
    val http = FakeHttp()
    val env = FakeEnv()
    val clock = Clock()
    val ledgerStore = MemoryStore()
    val cacheStore = MemoryStore()
    val consentStore = MemoryStore()
    val sink = MemorySink()
    var tokenValue: String? = token
    var tokenCalls = 0
    val ledger = Ledger(ledgerStore, policy.budget, clock)
    val cache = AnswerCache(cacheStore, policy.cacheMax, clock)
    val breaker = CircuitBreaker(policy.breaker.failures, policy.breaker.openS * 1000, clock)
    val consent = ConsentBook(consentStore)
    val journal = Journal(sink, policy.journalMax, clock)
    val engine = Engine(policy, http, { tokenCalls++; tokenValue }, env, ledger, cache, breaker, consent, journal, clock)

    fun decide(use: String, state: Any? = JSONObject().put("n", 1), questions: JSONObject = Fixtures.noulQ(), app: String = Fixtures.APP) =
        engine.decide(app, Fixtures.request(use, state, questions))
}

fun JSONObject.strings(key: String): List<String> = getJSONArray(key).let { a: JSONArray -> (0 until a.length()).map { a.getString(it) } }
