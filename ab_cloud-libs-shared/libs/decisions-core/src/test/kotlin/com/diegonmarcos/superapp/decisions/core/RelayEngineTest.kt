package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException

/** #1801 the relay: the declaration, every refusal in order, the key added here and only here, the ledger, the breaker, the journal. */
class RelayEngineTest {

    private val app = Fixtures.APP
    private val body = """{"model":"m/x","messages":[{"role":"user","content":"hi"}]}"""
    private val answer = """{"choices":[{"message":{"content":"yo"}}],"usage":{"cost":0.003}}"""

    inner class RelHttp : Http {
        val urls = ArrayList<String>()
        val tokens = ArrayList<String?>()
        val bodies = ArrayList<String?>()
        val timeouts = ArrayList<Int>()
        var reply: () -> Http.Response = { Http.Response(200, answer) }
        var throwing: Exception? = null
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            urls.add(url); tokens.add(token); bodies.add(body); timeouts.add(timeoutMs)
            throwing?.let { throw it }
            return reply()
        }
    }

    private fun block(tweak: (JSONObject) -> Unit = {}): JSONObject = Fixtures.block { d ->
        d.put("relay", JSONObject("""
        {"providers":{"openrouter":{"label":"OpenRouter","endpoints":{"chat":"https://example.test/chat","decisions":"https://example.test/dec"}},
                      "_doc":"skipped"},
         "timeout_ms":90000,"max_body_bytes":200,"max_answer_bytes":120,
         "budget":{"daily_usd_cap":0.1,"per_app_daily_calls":3,"est_call_usd":0.05},
         "suppress":{"metered":false,"offline":true,"battery_saver":true},
         "breaker":{"failures":2,"open_s":60},
         "uses":{
          "chat":{"enabled":true,"max_calls_per_hour":2,"consent":"implicit","content":"text","provider":"openrouter","endpoints":["chat"],"apps":["$app"]},
          "priv":{"enabled":true,"max_calls_per_hour":2,"consent":"required","content":"text","provider":"openrouter","endpoints":["chat"],"apps":["$app"]},
          "off":{"enabled":false,"max_calls_per_hour":2,"consent":"implicit","provider":"openrouter","endpoints":["chat"],"apps":["$app"]},
          "broken":{"enabled":true,"max_calls_per_hour":0,"provider":"openrouter","endpoints":["chat"],"apps":["$app"]},
          "_doc":"skipped"
         }}"""))
        tweak(d)
    }

    inner class RRig(root: JSONObject = block(), key: String? = Fixtures.TOKEN) {
        val policy = Policy.parse(root)
        val http = RelHttp()
        val env = FakeEnv()
        val clock = Clock()
        val keys = KeyBook(MemoryStore()).also { if (key != null) it.set("openrouter", key) }
        val sink = MemorySink()
        val consent = ConsentBook(MemoryStore())
        val journal = Journal(sink, 50, clock)
        val ledger = policy.relay?.let { Ledger(MemoryStore(), it.budget, clock) }
        val breaker = policy.relay?.let { CircuitBreaker(it.breaker.failures, it.breaker.openS * 1000, clock) }
        val relay = RelayEngine(policy.relay, http, keys, env, ledger, breaker, consent, journal, clock)
        fun run(use: String = "chat", endpoint: String = "chat", b: String = body, who: String = app) =
            relay.run(who, JSONObject().put("use", use).put("endpoint", endpoint), b.toByteArray())
    }

    private fun reason(a: RelayAnswer) = a.answer.optString("reason")

    @Test
    fun `the declaration parses its providers, limits and uses`() {
        val r = Policy.parse(block()).relay!!
        assertEquals(setOf("openrouter"), r.providers.keys)
        assertEquals(mapOf("chat" to "https://example.test/chat", "decisions" to "https://example.test/dec"), r.providers["openrouter"]!!.endpoints)
        assertEquals(mapOf("openrouter" to "OpenRouter"), r.labels())
        assertEquals(90000, r.timeoutMs)
        assertEquals(200, r.maxBodyBytes)
        assertEquals(120, r.maxAnswerBytes)
        assertEquals(0.1, r.budget.dailyUsdCap, 0.0)
        assertEquals(3, r.budget.perAppDailyCalls)
        assertEquals(0.05, r.budget.estCallUsd, 0.0)
        assertFalse(r.suppress.metered); assertTrue(r.suppress.offline); assertTrue(r.suppress.batterySaver)
        assertEquals(2, r.breaker.failures); assertEquals(60L, r.breaker.openS)
        assertEquals(setOf("chat", "priv", "off"), r.uses.keys)
        assertEquals(setOf("broken"), r.rejected.keys)
        val u = r.uses.getValue("chat")
        assertTrue(u.enabled); assertFalse(u.consentRequired)
        assertEquals("openrouter", u.provider); assertEquals(setOf("chat"), u.endpoints); assertEquals(setOf(app), u.apps)
        assertEquals("text", u.content); assertEquals(2, u.maxCallsPerHour)
        assertTrue(r.uses.getValue("priv").consentRequired)
        assertFalse(r.uses.getValue("off").enabled)
        val asUse = u.asUse()
        assertEquals("chat", asUse.name); assertEquals(setOf(app), asUse.apps); assertFalse(asUse.consentRequired)
        assertNull(Policy.parse(block()).relayProblem)
        val label = Policy.parse(block { it.getJSONObject("relay").getJSONObject("providers").getJSONObject("openrouter").remove("label") }).relay!!
        assertEquals("openrouter", label.labels()["openrouter"])
    }

    @Test
    fun `no relay block serves nothing and a bad block is reported`() {
        assertNull(Policy.parse(Fixtures.block()).relay)
        assertNull(Policy.parse(Fixtures.block()).relayProblem)
        val bad = listOf<(JSONObject) -> Unit>(
            { it.remove("providers") },
            { it.put("providers", JSONObject()) },
            { it.getJSONObject("providers").put("openrouter", "x") },
            { it.getJSONObject("providers").getJSONObject("openrouter").remove("endpoints") },
            { it.getJSONObject("providers").getJSONObject("openrouter").put("endpoints", JSONObject()) },
            { it.getJSONObject("providers").getJSONObject("openrouter").getJSONObject("endpoints").put("chat", "http://plain.test") },
            { it.put("timeout_ms", 999) },
            { it.put("timeout_ms", 300001) },
            { it.put("max_body_bytes", 0) },
            { it.put("max_answer_bytes", 0) },
            { it.remove("budget") },
            { it.getJSONObject("budget").put("daily_usd_cap", 0) },
            { it.remove("suppress") },
            { it.remove("breaker") },
            { it.getJSONObject("breaker").put("failures", 0) },
        )
        for (t in bad) {
            val p = Policy.parse(block { t(it.getJSONObject("relay")) })
            assertNull(p.relay)
            assertTrue(p.relayProblem!!.isNotBlank())
            assertTrue("decisions are not affected", p.uses.isNotEmpty())
        }
        assertEquals(1000, Policy.parse(block { it.getJSONObject("relay").put("timeout_ms", 1000) }).relay!!.timeoutMs)
        assertEquals(300000, Policy.parse(block { it.getJSONObject("relay").put("timeout_ms", 300000) }).relay!!.timeoutMs)
    }

    @Test
    fun `a use that does not validate is rejected with why`() {
        fun rejected(u: String): String? = Policy.parse(block { it.getJSONObject("relay").getJSONObject("uses").put("x", JSONObject(u)) }).relay!!.rejected["x"]
        val base = """"max_calls_per_hour":1,"consent":"implicit","provider":"openrouter","endpoints":["chat"],"apps":["$app"]"""
        assertNull(rejected("{$base}"))
        assertEquals("is not an object", Policy.parse(block { it.getJSONObject("relay").getJSONObject("uses").put("x", 1) }).relay!!.rejected["x"])
        assertTrue(rejected("""{$base,"max_calls_per_hour":0}""")!!.contains("max_calls_per_hour"))
        assertTrue(rejected("""{$base,"apps":[]}""")!!.contains("apps"))
        assertTrue(rejected("""{$base,"consent":"maybe"}""")!!.contains("consent"))
        assertTrue(rejected("""{$base,"content":"mail"}""")!!.contains("mail"))
        assertNull(rejected("""{$base,"content":"mail","consent":"required"}"""))
        assertTrue(rejected("""{$base,"provider":"nobody"}""")!!.contains("provider"))
        assertTrue(rejected("""{$base,"endpoints":[]}""")!!.contains("endpoints"))
        assertTrue(rejected("""{$base,"endpoints":["chat","models"]}""")!!.contains("models"))
        val d = Policy.parse(block { it.getJSONObject("relay").getJSONObject("uses").put("x", JSONObject("""{"max_calls_per_hour":1,"provider":"openrouter","endpoints":["chat"],"apps":["$app"]}""")) }).relay!!
        assertTrue("consent defaults to required", d.uses.getValue("x").consentRequired)
        assertFalse("enabled defaults to off", d.uses.getValue("x").enabled)
    }

    @Test
    fun `an answer carries the provider's code and body and the key went only to the declared URL`() {
        val r = RRig()
        val a = r.run()
        assertTrue(a.answer.getBoolean("ok"))
        assertEquals(200, a.answer.getInt("code"))
        assertEquals("chat", a.answer.getString("use"))
        assertEquals(answer.toByteArray().size, a.answer.getInt("bytes"))
        assertEquals(0.003, a.answer.getDouble("cost"), 0.0)
        assertTrue(a.answer.has("latency_ms"))
        assertArrayEquals(answer.toByteArray(), a.body)
        assertEquals(listOf("https://example.test/chat"), r.http.urls)
        assertEquals(listOf<String?>(Fixtures.TOKEN), r.http.tokens)
        assertEquals(listOf<String?>(body), r.http.bodies)
        assertEquals(listOf(90000), r.http.timeouts)
        assertFalse("the answer JSON carries no key", a.answer.toString().contains(Fixtures.TOKEN))
        assertFalse("the journal carries no key or body", r.sink.lines.joinToString().let { it.contains(Fixtures.TOKEN) || it.contains("hi") })
        assertEquals(0.003, r.ledger!!.spentToday(), 1e-9)
        assertTrue(r.sink.lines.last().contains("answered"))
    }

    @Test
    fun `a provider error is an answer with its code, and a 5xx counts against the breaker`() {
        val r = RRig()
        r.http.reply = { Http.Response(401, """{"error":{"message":"bad"}}""") }
        val a = r.run()
        assertTrue(a.answer.getBoolean("ok"))
        assertEquals(401, a.answer.getInt("code"))
        assertTrue(r.sink.lines.last().contains("\"http\""))
        assertEquals("no usage.cost charges the estimate", 0.05, r.ledger!!.spentToday(), 1e-9)
        assertTrue(a.answer.isNull("cost"))
        assertFalse("a 4xx is the caller's problem, not the provider being down", r.breaker!!.isOpen())
        val down = RRig()
        down.http.reply = { Http.Response(503, "{}") }
        assertEquals(503, down.run().answer.getInt("code"))
        down.run()
        assertTrue(down.breaker!!.isOpen())
    }

    @Test
    fun `the per-app override is spent before the fleet key`() {
        val r = RRig()
        r.keys.set("openrouter", Fixtures.SECRET, app)
        r.run()
        assertEquals(listOf<String?>(Fixtures.SECRET), r.http.tokens)
    }

    @Test
    fun `the decisions endpoint is reached by its name`() {
        val r = RRig(block { it.getJSONObject("relay").getJSONObject("uses").getJSONObject("chat").put("endpoints", org.json.JSONArray().put("chat").put("decisions")) })
        assertTrue(r.run(endpoint = "decisions").answer.getBoolean("ok"))
        assertEquals(listOf("https://example.test/dec"), r.http.urls)
    }

    @Test
    fun `every refusal in order, and nothing is sent`() {
        assertEquals("no_relay_policy", reason(RRig(Fixtures.block()).run()))
        assertEquals("misconfigured", reason(RRig().run(use = "broken")))
        assertEquals("unknown_use", reason(RRig().run(use = "nope")))
        assertEquals("app_not_allowed", reason(RRig().run(who = "com.x.other")))
        assertEquals("disabled", reason(RRig().run(use = "off")))
        assertEquals("bad_endpoint", reason(RRig().run(endpoint = "decisions")))
        assertEquals("bad_endpoint", reason(RRig().run(endpoint = "https://evil.test/x")))
        assertEquals("bad_request", reason(RRig().run(b = "")))
        assertEquals("bad_request", reason(RRig().run(b = "not json")))
        assertEquals("body_too_large", reason(RRig().run(b = "{\"x\":\"" + "a".repeat(200) + "\"}")))
        assertEquals("no_consent", reason(RRig().run(use = "priv")))
        val off = RRig().apply { env.online = false }
        val o = off.run()
        assertEquals("offline", reason(o)); assertEquals(RelayEngine.NO_NETWORK, o.answer.getString("message"))
        assertEquals("battery_saver", reason(RRig().apply { env.saver = true }.run()))
        assertTrue("metered does not stop a relay here", RRig().apply { env.metered = true }.run().answer.getBoolean("ok"))
        val nokey = RRig(key = null).run()
        assertEquals("no_key", reason(nokey))
        assertTrue(nokey.answer.getString("message").contains("OpenRouter"))
        for (r in listOf(RRig(Fixtures.block()), RRig().also { it.run(use = "nope") })) assertTrue(r.http.urls.isEmpty())
        val one = RRig(); one.run(use = "off"); one.run(use = "priv"); one.run(b = "")
        assertTrue("refused calls send nothing", one.http.urls.isEmpty())
        assertTrue(one.sink.lines.all { it.contains("refused") && it.contains("relay") })
    }

    @Test
    fun `a GET sends no body and still adds the key`() {
        val r = RRig(block { it.getJSONObject("relay").getJSONObject("uses").getJSONObject("chat").put("endpoints", org.json.JSONArray().put("chat").put("decisions")) })
        val a = r.relay.run(app, JSONObject().put("use", "chat").put("endpoint", "decisions").put("method", "GET"), ByteArray(0))
        assertTrue(a.answer.toString(), a.answer.getBoolean("ok"))
        assertEquals(listOf<String?>(null), r.http.bodies)
        assertEquals(listOf<String?>(Fixtures.TOKEN), r.http.tokens)
        assertEquals(listOf("https://example.test/dec"), r.http.urls)
        assertTrue(r.sink.lines.last().contains("\"bytes_in\":0"))
        val post = r.relay.run(app, JSONObject().put("use", "chat").put("endpoint", "chat").put("method", "POST"), ByteArray(0))
        assertEquals("bad_request", reason(post))
    }

    @Test
    fun `consent granted lets a required use through`() {
        val r = RRig()
        r.consent.set(app, "priv", true)
        assertTrue(r.run(use = "priv").answer.getBoolean("ok"))
    }

    @Test
    fun `metered is honoured when the declaration asks`() {
        val r = RRig(block { it.getJSONObject("relay").getJSONObject("suppress").put("metered", true) }).apply { env.metered = true }
        assertEquals("metered", reason(r.run()))
        val off = RRig(block { it.getJSONObject("relay").getJSONObject("suppress").put("offline", false) }).apply { env.online = false }
        assertTrue(off.run().answer.getBoolean("ok"))
    }

    @Test
    fun `the ledger limits the hour and the app's day`() {
        val r = RRig()
        assertTrue(r.run().answer.getBoolean("ok"))
        assertTrue(r.run().answer.getBoolean("ok"))
        assertEquals("rate_limited", reason(r.run()))
        assertEquals(2, r.http.urls.size)
    }

    @Test
    fun `the breaker opens on transport failures and stops sending`() {
        val r = RRig()
        r.http.throwing = UnknownHostException("x")
        val a = r.run()
        assertEquals("network", reason(a))
        assertEquals(RelayEngine.NO_NETWORK, a.answer.getString("message"))
        r.http.throwing = IOException("boom")
        val b = r.run()
        assertTrue(b.answer.getString("message").contains("IOException"))
        r.clock.now += 1
        assertEquals("breaker_open", reason(r.run()))
        assertEquals(2, r.http.urls.size)
    }

    @Test
    fun `a 2xx closes the breaker again`() {
        val r = RRig()
        r.http.reply = { Http.Response(500, "{}") }
        r.run()
        r.http.reply = { Http.Response(200, answer) }
        r.run()
        r.http.reply = { Http.Response(500, "{}") }
        r.clock.now += 3_600_001
        r.run()
        assertFalse("one failure after a success does not open it", r.breaker!!.isOpen())
    }

    @Test
    fun `an answer past the limit is refused and still charged`() {
        val r = RRig()
        r.http.reply = { Http.Response(200, "{\"x\":\"" + "b".repeat(200) + "\"}") }
        val a = r.run()
        assertEquals("answer_too_large", reason(a))
        assertNull(a.body)
        assertEquals(0.05, r.ledger!!.spentToday(), 1e-9)
    }

    @Test
    fun `an exception inside is a refusal`() {
        val r = RRig()
        r.http.reply = { throw IllegalStateException("x") }
        assertEquals("network", reason(r.run()))
        val bad = RelayEngine(Policy.parse(block()).relay, r.http, r.keys, r.env, null, null, r.consent, r.journal, r.clock)
        assertEquals("exception", reason(bad.run(app, JSONObject().put("use", "chat").put("endpoint", "chat"), body.toByteArray())))
    }

    @Test
    fun `status names uses and flags, never content or a key`() {
        val r = RRig()
        r.run()
        val s = r.relay.status(app)
        assertTrue(s.getBoolean("served"))
        assertFalse(s.getBoolean("breaker_open"))
        assertEquals(0.003, s.getDouble("spent_today_usd"), 1e-9)
        assertEquals(0.1, s.getDouble("daily_usd_cap"), 0.0)
        val u = s.getJSONObject("uses").getJSONObject("chat")
        assertTrue(u.getBoolean("enabled")); assertTrue(u.getBoolean("allowed_for_app")); assertTrue(u.getBoolean("consent"))
        assertEquals("openrouter", u.getString("provider")); assertTrue(u.getBoolean("key_present"))
        assertFalse(s.getJSONObject("uses").getJSONObject("priv").getBoolean("consent"))
        assertFalse(r.relay.status("com.x.other").getJSONObject("uses").getJSONObject("chat").getBoolean("allowed_for_app"))
        assertEquals("broken", s.getJSONArray("rejected").getString(0))
        assertFalse(s.toString().contains(Fixtures.TOKEN))
        assertFalse(RRig(key = null).relay.status(app).getJSONObject("uses").getJSONObject("chat").getBoolean("key_present"))
        assertFalse(RRig(Fixtures.block()).relay.status(app).getBoolean("served"))
    }

    @Test
    fun `providers, apps and the body limit come from the declaration`() {
        val r = RRig()
        assertEquals(mapOf("openrouter" to "OpenRouter"), r.relay.providers())
        assertEquals(listOf(app), r.relay.apps())
        assertEquals(200, r.relay.maxBodyBytes)
        val none = RRig(Fixtures.block())
        assertTrue(none.relay.providers().isEmpty())
        assertTrue(none.relay.apps().isEmpty())
        assertEquals(0, none.relay.maxBodyBytes)
    }

    @Test
    fun `costOf, noKey and transport`() {
        assertEquals(0.5, RelayEngine.costOf("""{"usage":{"cost":0.5}}""")!!, 0.0)
        assertNull(RelayEngine.costOf("""{"usage":{"cost":"x"}}"""))
        assertNull(RelayEngine.costOf("""{}"""))
        assertNull(RelayEngine.costOf("not json"))
        assertTrue(RelayEngine.noKey("OpenRouter").startsWith("No OpenRouter key"))
        assertEquals(RelayEngine.NO_NETWORK, RelayEngine.transport(UnknownHostException()))
        assertTrue(RelayEngine.transport(IOException()).contains("IOException"))
    }

    @Test
    fun `the shipped declaration serves every relay use`() {
        val p = Policy.parse(Fixtures.realManifest())
        assertNull(p.relayProblem)
        val r = p.relay!!
        assertTrue(r.rejected.toString(), r.rejected.isEmpty())
        assertEquals(setOf("search_ai_chat", "browser_search_ai", "calc_jev", "writer_routes", "image_scan_recognize", "keyboard_text_tools"), r.uses.keys)
        assertTrue(r.uses.values.all { it.enabled && it.provider == "openrouter" })
        assertEquals(setOf("decisions", "chat", "key"), r.uses.getValue("calc_jev").endpoints)
        assertEquals(setOf("decisions"), r.uses.getValue("image_scan_recognize").endpoints)
        assertTrue(r.providers.getValue("openrouter").endpoints.values.all { it.startsWith("https://openrouter.ai/") })
    }
}
