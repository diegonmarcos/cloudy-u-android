package com.diegonmarcos.cloudtoolssmallllms.core

import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.modelcatalogue.ModelCatalogue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * The app's logic against THIS build.json, the fleet's catalogue.json and RECORDED OpenRouter
 * answers (src/test/resources/fixtures). No network: [Fake] plays OpenRouter.
 */
class ToolsCoreTest {
    private val buildJson = JSONObject(File("../build.json").readText())
    private val cfgJson = buildJson.getJSONObject("tools")
    private val cfg = ToolsConfig.parse(cfgJson.toString())

    private fun fixture(name: String) = javaClass.getResource("/fixtures/$name")!!.readText()

    /** The shared catalogue, as the app prices it: each chat row's list price (the snapshot, offline). */
    private val catalogue = ModelCatalogue.parse(javaClass.getResource("/" + ModelCatalogue.ASSET)!!.readText())
    private val book = PriceBook { id ->
        catalogue.sections.filter { it.chat }.flatMap { it.rows }.firstOrNull { it.id == id }?.snapshot
            ?.takeIf { it.input != null && it.output != null }?.let { Price(it.input!!, it.output!!) }
    }
    private val registry = TaskRegistry(cfg, book)

    /** Fake keys are assembled at run time, so no key-shaped literal trips the leak scan. */
    private val key = listOf("sk", "or", "v1", "test0123456789abcdef").joinToString("-")

    private class Fake(val replies: Map<String, Pair<Int, String>>) : Http {
        val calls = mutableListOf<Triple<String, String?, String?>>()
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            calls += Triple(url, token, body)
            val r = (if (body != null) replies[modelKey(body)] else null)
                ?: replies.entries.firstOrNull { !it.key.startsWith("model:") && url.contains(it.key) }?.value
                ?: throw IOException("no route")
            return Http.Response(r.first, r.second)
        }
        companion object {
            fun modelKey(body: String?): String = "model:" + (body?.let { runCatching { JSONObject(it).optString("model") }.getOrNull() } ?: "")
        }
    }

    private fun flow(http: Http, choice: String = ToolsConfig.AUTO, listed: Boolean? = true, token: String? = key) =
        ToolsFlow(cfg, http, { token }, book, { registry }, { choice }, { listed })

    // ── the declaration ──────────────────────────────────────────────────────────────────────

    @Test fun `build json declares the fifteen tasks the owner listed, each with a prompt and a worker`() {
        assertEquals(
            listOf("translate", "summarize", "rewrite", "grammar", "extract", "classify", "reply", "explain", "math", "data", "regex", "titles", "sentiment", "keywords", "qa"),
            cfg.tasks.map { it.id },
        )
        cfg.tasks.forEach {
            assertTrue(it.id, it.system.isNotBlank() && it.example.isNotBlank() && it.blurb.isNotBlank() && it.label.isNotBlank())
            assertTrue(it.id, '/' in it.worker)
        }
        assertEquals(ToolsConfig.AUTO, cfg.router.default)
        assertEquals("typesafe/jev-1.13", cfg.router.jevModel)
        assertEquals(listOf(ToolsConfig.AUTO, ToolsConfig.JEV) + cfg.router.chatModels, cfg.router.choices)
        assertTrue(cfg.chatModels.containsAll(cfg.router.chatModels))
    }

    @Test fun `every task and chat router is a chat row of the shared catalogue, and priced`() {
        assertEquals(emptyList<String>(), registry.problems())
        val chatIds = catalogue.sections.filter { it.chat }.flatMap { it.rows }.map { it.id }.toSet()
        (cfg.tasks.map { it.worker } + cfg.router.chatModels).forEach { assertTrue("$it is not a catalogue chat row", it in chatIds) }
        registry.entries.forEach { e ->
            assertTrue(e.task.id, e.price != null && e.estimate != null)
            val p = e.price!!
            assertEquals(e.task.id, (cfg.typicalIn * p.inPerM + cfg.typicalOut * p.outPerM) / 1e6, e.estimate!!, 1e-12)
            // Small and cheap only: no worker above $1 per 1M tokens either way.
            assertTrue(e.task.id, p.inPerM <= 1.0 && p.outPerM <= 1.0)
        }
        // An override the catalogue does not price is ignored; a priced one wins.
        val r = TaskRegistry(cfg, book, mapOf("translate" to "nobody/none", "summarize" to "google/gemma-3-4b-it"))
        assertEquals(cfg.task("translate")!!.worker, r.entry("translate")!!.worker)
        assertEquals("google/gemma-3-4b-it", r.entry("summarize")!!.worker)
        assertNull(r.entry("nope"))
        // A worker the catalogue does not price is a problem, named.
        val qa = cfg.task("qa")!!.worker
        val broken = TaskRegistry(cfg, PriceBook { if (it == qa) null else Price(0.1, 0.1) })
        val sharing = cfg.tasks.filter { it.worker == qa }.map { it.id }
        assertEquals(sharing.map { "$it: worker $qa is not priced by the catalogue" }, broken.problems().filter { !it.startsWith("router:") })
        val noRouter = TaskRegistry(cfg, PriceBook { if (it == cfg.router.chatDefault) null else Price(0.1, 0.1) })
        assertTrue(noRouter.problems().any { it.startsWith("router:") })
    }

    @Test fun `the parser refuses a declaration that would misroute`() {
        fun refuses(why: String, edit: (JSONObject) -> Unit) {
            val o = JSONObject(cfgJson.toString()).also(edit)
            try { ToolsConfig.parse(o.toString()); fail("accepted: $why") } catch (_: Exception) { }
        }
        refuses("a prompt hole with no param") { it.getJSONArray("tasks").getJSONObject(1).put("system", "Summarize into {language}.") }
        refuses("a param missing from the prompt") { it.getJSONArray("tasks").getJSONObject(0).put("system", "Translate.") }
        refuses("an unknown param") { it.getJSONArray("tasks").getJSONObject(0).put("params", org.json.JSONArray().put("colour")) }
        refuses("a repeated id") { it.getJSONArray("tasks").getJSONObject(1).put("id", "translate") }
        refuses("a task called none") { it.getJSONArray("tasks").getJSONObject(1).put("id", "none") }
        refuses("an empty prompt") { it.getJSONArray("tasks").getJSONObject(1).put("system", " ") }
        refuses("a worker that is not provider/model") { it.getJSONArray("tasks").getJSONObject(1).put("worker", "llama") }
        refuses("plain http") { it.getJSONObject("openrouter").put("chat_url", "http://openrouter.ai/x") }
        refuses("threshold above 1") { it.getJSONObject("router").put("threshold", 1.5) }
        refuses("threshold 0") { it.getJSONObject("router").put("threshold", 0) }
        refuses("min p above threshold") { it.getJSONObject("router").put("candidate_min_p", 0.9) }
        refuses("chat default not a router") { it.getJSONObject("router").put("chat_default", "x/y") }
        refuses("unknown router default") { it.getJSONObject("router").put("default", "magic") }
        refuses("no tasks") { it.put("tasks", org.json.JSONArray()) }
        refuses("param default not an option") { it.getJSONObject("params").getJSONObject("tone").put("default", "angry") }
        refuses("zero typical") { it.getJSONObject("typical").put("in", 0) }
    }

    // ── the router: Jev ──────────────────────────────────────────────────────────────────────

    private fun jev(name: String) = JSONObject(fixture(name)).optJSONObject("answers")

    @Test fun `a confident Jev answer routes to the task with its worker, params and confidence`() {
        val d = Router.fromJev(cfg, registry, jev("jev-route.json"), "", 0.00005, cfg.router.jevModel) as RouterDecision.Route
        assertEquals("translate", d.task.id)
        assertEquals(cfg.task("translate")!!.worker, d.worker)
        assertEquals(mapOf("language" to "de"), d.params)
        assertEquals(0.91, d.confidence, 0.0)
        assertEquals(cfg.router.jevModel, d.routerModel)
        assertEquals(0.00005, d.routerCost, 0.0)
    }

    @Test fun `an unsure Jev answer asks, offering the candidates above the floor`() {
        val d = Router.fromJev(cfg, registry, jev("jev-lowconf.json"), "", 0.0, "m") as RouterDecision.Clarify
        assertEquals(cfg.router.clarify, d.question)
        assertEquals(listOf("summarize", "keywords", "qa"), d.candidates.map { it.first })
        assertEquals(0.38, d.candidates.first().second, 0.0)
    }

    @Test fun `a confident none asks too, never runs a task`() {
        val d = Router.fromJev(cfg, registry, jev("jev-none.json"), "", 0.0, "m") as RouterDecision.Clarify
        assertEquals(listOf("explain", "qa"), d.candidates.map { it.first })
    }

    @Test fun `a Jev answer naming no task of ours, or no answer at all, is invalid`() {
        assertTrue(Router.fromJev(cfg, registry, jev("jev-malformed.json"), "", 0.0, "m") is RouterDecision.Invalid)
        val failed = Router.fromJev(cfg, registry, null, "HTTP 401: nope", 0.0, "m") as RouterDecision.Invalid
        assertEquals("HTTP 401: nope", failed.reason)
        assertEquals("no answer", (Router.fromJev(cfg, registry, null, "", 0.0, "m") as RouterDecision.Invalid).reason)
        assertTrue(Router.fromJev(cfg, registry, JSONObject(), "", 0.0, "m") is RouterDecision.Invalid)
    }

    @Test fun `the Jev request asks one task question over every task plus none, and one per param`() {
        val q = Router.jevQuestions(cfg)
        val crit = q.getJSONObject(Router.TASK).getJSONObject("criteria")
        assertEquals(cfg.taskIds + ToolsConfig.NONE, crit.keySet())
        assertEquals("choice", q.getJSONObject(Router.TASK).getString("type"))
        cfg.params.values.forEach { p ->
            assertEquals(p.options.keys, q.getJSONObject(Router.PARAM_PREFIX + p.id).getJSONObject("criteria").keySet())
        }
        assertEquals("hi", Router.jevState("hi").getString("request"))
    }

    // ── the router: chat JSON ────────────────────────────────────────────────────────────────

    private fun content(name: String) = OpenRouterChat.reply(fixture(name)).text

    @Test fun `a chat router's JSON routes, and a fenced unsure one asks with its param defaulted`() {
        val ok = Router.fromChat(cfg, registry, content("chat-router-ok.json"), 0.00003, cfg.router.chatDefault) as RouterDecision.Route
        assertEquals("translate", ok.task.id)
        assertEquals(mapOf("language" to "de"), ok.params)
        assertEquals(0.93, ok.confidence, 0.0)
        val low = Router.fromChat(cfg, registry, content("chat-router-fenced-lowconf.json"), 0.0, "m") as RouterDecision.Clarify
        assertEquals(listOf("rewrite"), low.candidates.map { it.first })
        // A confident chat answer with an option it may not use gets the param's default.
        val r = Router.fromChat(cfg, registry, """{"task":"rewrite","confidence":0.9,"params":{"tone":"sarcastic"}}""", 0.0, "m") as RouterDecision.Route
        assertEquals(mapOf("tone" to cfg.params.getValue("tone").default), r.params)
        val none = Router.fromChat(cfg, registry, """{"task":"none","confidence":0.95}""", 0.0, "m") as RouterDecision.Clarify
        assertTrue(none.candidates.isEmpty())
    }

    @Test fun `a chat router answer that is prose, an unknown task or a bad confidence is invalid`() {
        assertTrue(Router.fromChat(cfg, registry, content("chat-router-prose.json"), 0.0, "m") is RouterDecision.Invalid)
        assertTrue(Router.fromChat(cfg, registry, content("chat-router-unknown-task.json"), 0.0, "m") is RouterDecision.Invalid)
        for (bad in listOf("""{"task":"summarize","confidence":1.4}""", """{"task":"summarize","confidence":"high"}""",
                           """{"task":"summarize","confidence":true}""", """{"task":"summarize","confidence":-0.1}""",
                           """{"confidence":0.9}""", "", "{ not json }", "}{")) {
            assertTrue(bad, Router.fromChat(cfg, registry, bad, 0.0, "m") is RouterDecision.Invalid)
        }
        assertTrue(Router.fromChat(cfg, registry, null, 0.0, "m") is RouterDecision.Invalid)
    }

    @Test fun `the chat router request is JSON mode over every task`() {
        val b = JSONObject(Router.chatBody(cfg, "x/y", "translate hi"))
        assertEquals("json_object", b.getJSONObject("response_format").getString("type"))
        assertEquals(cfg.router.maxTokens, b.getInt("max_tokens"))
        val sys = b.getJSONArray("messages").getJSONObject(0).getString("content")
        cfg.tasks.forEach { assertTrue(it.id, "- ${it.id}: " in sys) }
        assertTrue(Regex("translate\\.language: one of [a-z|]*\\bde\\b").containsMatchIn(sys))
        assertEquals("translate hi", b.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun `a forced pick routes with certainty and default params`() {
        val d = Router.forced(cfg, registry, "extract") as RouterDecision.Route
        assertEquals(1.0, d.confidence, 0.0)
        assertEquals(mapOf("target" to "all", "format" to "list"), d.params)
        val j = Router.forced(cfg, registry, "extract", mapOf("format" to "json", "target" to "bogus")) as RouterDecision.Route
        assertEquals(mapOf("target" to "all", "format" to "json"), j.params)
        assertTrue(Router.forced(cfg, registry, "nope") is RouterDecision.Invalid)
    }

    // ── costs ────────────────────────────────────────────────────────────────────────────────

    @Test fun `the cost of a call is OpenRouter's own figure, else its tokens at the catalogue price`() {
        val withCost = OpenRouterChat.reply(fixture("chat-worker-translate.json"))
        assertEquals(Usage(52, 9, 0.00000713), withCost.usage)
        assertEquals(0.00000713, Costs.actual(withCost.usage, Price(100.0, 100.0))!!, 0.0)
        val noCost = OpenRouterChat.reply(fixture("chat-worker-no-cost.json"))
        assertEquals(Usage(1000, 500, null), noCost.usage)
        assertEquals((1000 * 0.05 + 500 * 0.08) / 1e6, Costs.actual(noCost.usage, Price(0.05, 0.08))!!, 1e-15)
        assertNull(Costs.actual(noCost.usage, null))
        assertNull(Costs.actual(null, Price(1.0, 1.0)))
        assertNull(Costs.usage(null))
        assertNull(Costs.usage(JSONObject()))
        assertEquals(Usage(1180, 0, 0.0000496), Costs.usage(JSONObject(fixture("jev-route.json")).getJSONObject("usage")))
        assertEquals(0.00012, Costs.estimate(Price(0.2, 0.3), 300, 200), 1e-15)
    }

    @Test fun `cost text`() {
        assertEquals("—", Costs.usd(null))
        assertEquals("—", Costs.usd(-1.0))
        assertEquals("$0", Costs.usd(0.0))
        assertEquals("$0.0000713", Costs.usd(0.0000713))
        assertEquals("$0.00123", Costs.usd(0.001234))
        assertEquals("$0.042", Costs.usd(0.042))
        assertEquals("$1.25", Costs.usd(1.25))
        assertEquals("$2.00", Costs.usd(2.0))
    }

    // ── a whole turn ─────────────────────────────────────────────────────────────────────────

    @Test fun `auto routes with Jev, then the worker answers, and both calls are priced`() {
        val http = Fake(mapOf(
            "decisions" to (200 to fixture("jev-route.json")),
            "chat/completions" to (200 to fixture("chat-worker-translate.json")),
        ))
        val t = flow(http).send("  Translate to German: see you tomorrow at 9 ") as ToolsFlow.Turn.Answered
        val a = t.answer
        assertEquals("translate", a.task)
        assertEquals("Bis morgen um 9 Uhr.", a.text)
        assertEquals(cfg.router.jevModel, a.routerModel)
        assertEquals(0.0000496, a.routerCost, 0.0)
        assertEquals(0.00000713, a.workerCost!!, 0.0)
        assertEquals(0.0000496 + 0.00000713, a.total!!, 1e-15)
        assertEquals(2, http.calls.size)
        // Both calls carry the user's own token as the bearer, and only there.
        http.calls.forEach { assertEquals(key, it.second) }
        val jevBody = JSONObject(http.calls[0].third!!)
        assertEquals(cfg.router.jevModel, jevBody.getString("model"))
        val worker = JSONObject(http.calls[1].third!!)
        assertEquals(cfg.task("translate")!!.worker, worker.getString("model"))
        assertTrue(worker.getJSONArray("messages").getJSONObject(0).getString("content").contains("into German"))
        assertEquals("Translate to German: see you tomorrow at 9", worker.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test fun `when Jev fails or is not listed, the chat router routes`() {
        val chatOnly = mapOf(
            "model:" + cfg.router.chatDefault to (200 to fixture("chat-router-ok.json")),
            "model:" + cfg.task("translate")!!.worker to (200 to fixture("chat-worker-translate.json")),
        )
        val failing = Fake(chatOnly + ("decisions" to (401 to fixture("error-401.json"))))
        val a = (flow(failing).send("translate hi to german") as ToolsFlow.Turn.Answered).answer
        assertEquals(cfg.router.chatDefault, a.routerModel)
        assertEquals(0.0000325, a.routerCost, 1e-15)
        assertEquals(3, failing.calls.size)
        val unlisted = Fake(chatOnly)
        flow(unlisted, listed = false).send("translate hi to german") as ToolsFlow.Turn.Answered
        assertTrue(unlisted.calls.none { "decisions" in it.first })
        assertEquals(listOf(cfg.router.chatDefault), flow(unlisted, listed = false).routers())
        assertEquals(listOf(ToolsConfig.JEV, cfg.router.chatDefault), flow(unlisted, listed = null).routers())
        assertEquals(listOf(ToolsConfig.JEV, cfg.router.chatDefault), flow(unlisted, choice = ToolsConfig.JEV, listed = false).routers())
        assertEquals(listOf("google/gemma-3-4b-it"), flow(unlisted, choice = "google/gemma-3-4b-it").routers())
        assertEquals(listOf(ToolsConfig.JEV, cfg.router.chatDefault), flow(unlisted, choice = "gone/model").routers())
    }

    @Test fun `an unsure router asks instead of running a worker, and a pick then runs it`() {
        val http = Fake(mapOf("decisions" to (200 to fixture("jev-lowconf.json")), "chat/completions" to (200 to fixture("chat-worker-no-cost.json"))))
        val f = flow(http)
        val c = f.send("this long text…") as ToolsFlow.Turn.Clarify
        assertEquals(listOf("summarize", "keywords", "qa"), c.decision.candidates.map { it.first })
        assertEquals(1, http.calls.size)
        val a = (f.send("this long text…", forcedTask = "summarize") as ToolsFlow.Turn.Answered).answer
        assertEquals("summarize", a.task)
        assertEquals(0.0, a.routerCost, 0.0)
        assertEquals((1000 * 0.05 + 500 * 0.08) / 1e6, a.workerCost!!, 1e-15)
    }

    @Test fun `every router failing, no token, nothing typed or a worker error is a failed turn`() {
        val allBad = Fake(mapOf("decisions" to (200 to fixture("jev-malformed.json")), "chat/completions" to (200 to fixture("chat-router-prose.json"))))
        val f = flow(allBad).send("hello") as ToolsFlow.Turn.Failed
        assertEquals("the router did not answer JSON", f.reason)
        assertEquals(0.0000491 + 0.0000310, f.routerCost, 1e-15)
        assertEquals(OpenRouterChat.NO_TOKEN, (flow(allBad, token = null).send("hello") as ToolsFlow.Turn.Failed).reason)
        assertEquals("nothing to do", (flow(allBad).send("   ") as ToolsFlow.Turn.Failed).reason)
        val workerDown = Fake(mapOf("decisions" to (200 to fixture("jev-route.json")), "chat/completions" to (401 to fixture("error-401.json"))))
        val w = flow(workerDown).send("translate hi") as ToolsFlow.Turn.Failed
        assertEquals("HTTP 401: No auth credentials found", w.reason)
        assertEquals(0.0000496, w.routerCost, 1e-15)
        val offline = Fake(emptyMap())
        assertTrue((flow(offline, listed = false).send("x") as ToolsFlow.Turn.Failed).reason.startsWith("network: IOException"))
    }

    // ── OpenRouter replies, the key test, Jev availability ───────────────────────────────────

    @Test fun `replies that carry an error, no choices or no text are errors`() {
        assertEquals("No auth credentials found", OpenRouterChat.reply(fixture("error-401.json")).error)
        assertEquals("the answer is not JSON", OpenRouterChat.reply("<html>").error)
        assertEquals("the answer has no choices", OpenRouterChat.reply("""{"model":"a/b"}""").error)
        assertEquals("the model answered nothing", OpenRouterChat.reply("""{"choices":[{"message":{"content":"  "}}]}""").error)
        assertFalse(OpenRouterChat.reply("""{"choices":[{"message":{"content":null}}]}""").ok)
        assertTrue(OpenRouterChat.reply(fixture("chat-worker-translate.json")).ok)
    }

    @Test fun `the worker request fills the prompt with the option's words`() {
        val t = cfg.task("rewrite")!!
        val b = JSONObject(OpenRouterChat.workerBody(cfg, t, "a/b", mapOf("tone" to "shorter"), "text"))
        val sys = b.getJSONArray("messages").getJSONObject(0).getString("content")
        assertTrue(sys.contains("Style: shorter."))
        assertFalse(sys.contains("{tone}"))
        assertEquals(t.maxTokens, b.getInt("max_tokens"))
        assertEquals("Style: more formal.", OpenRouterChat.render(cfg, "Style: {tone}.", mapOf("tone" to "bogus")))
        assertEquals("x {y}", OpenRouterChat.render(cfg, "x {y}", mapOf("y" to "z")))
    }

    @Test fun `the key test reads label and usage, and fails soft`() {
        val ok = OpenRouterChat.keyInfo(Fake(mapOf("key" to (200 to fixture("key.json")))), cfg.openRouter.keyUrl, key, 1000)
        assertTrue(ok.ok)
        assertEquals("phone", ok.label)
        assertEquals(0.4213, ok.usage!!, 0.0)
        assertNull(ok.limit)
        val bad = OpenRouterChat.keyInfo(Fake(mapOf("key" to (401 to fixture("error-401.json")))), cfg.openRouter.keyUrl, key, 1000)
        assertEquals("HTTP 401: No auth credentials found", bad.error)
        assertEquals(OpenRouterChat.NO_TOKEN, OpenRouterChat.keyInfo(Fake(emptyMap()), cfg.openRouter.keyUrl, null, 1000).error)
        assertFalse(OpenRouterChat.keyInfo(Fake(mapOf("key" to (200 to "{}"))), cfg.openRouter.keyUrl, key, 1000).ok)
        assertTrue(OpenRouterChat.keyInfo(Fake(emptyMap()), cfg.openRouter.keyUrl, key, 1000).error!!.startsWith("network"))
    }

    @Test fun `Jev is used while the recorded decision catalogue lists it`() {
        assertEquals(true, OpenRouterChat.listsModel(fixture("decision-models.json"), cfg.router.jevModel))
        assertEquals(false, OpenRouterChat.listsModel(fixture("decision-models-without-jev.json"), cfg.router.jevModel))
        assertNull(OpenRouterChat.listsModel("nope", cfg.router.jevModel))
        assertNull(OpenRouterChat.listsModel(null, cfg.router.jevModel))
        assertEquals(cfg.router.jevPriceIn, OpenRouterChat.decisionPrice(fixture("decision-models.json"), cfg.router.jevModel)!!, 1e-12)
        assertNull(OpenRouterChat.decisionPrice(fixture("decision-models-without-jev.json"), cfg.router.jevModel))
    }

    // ── the token ────────────────────────────────────────────────────────────────────────────

    private class Store : SecretStore {
        var value: String? = null
        var writes = 0
        override fun read() = value
        override fun write(value: String) { this.value = value; writes++ }
        override fun clear() { value = null }
    }

    @Test fun `the token lives only in the secret store, and every printable form of it is masked`() {
        val s = Store()
        val v = TokenVault(s)
        assertFalse(v.isSet)
        assertEquals("—", v.masked())
        assertFalse(v.set("   "))
        assertFalse(v.set("two words"))
        assertEquals(0, s.writes)
        assertTrue(v.set("  $key\n"))
        assertEquals(key, s.value)
        assertEquals(key, v.token())
        assertFalse(v.masked().contains(key))
        assertTrue(v.masked().endsWith(key.takeLast(4)))
        assertFalse(v.toString().contains(key))
        assertFalse(v.toString().contains(key.substring(6, key.length - 4)))
        // An error that echoes the request never carries the key.
        val scrubbed = OpenRouterChat.scrub("bad header Bearer $key", key)
        assertFalse(scrubbed.contains(key))
        assertEquals("abc", OpenRouterChat.scrub("abc", null))
        val echo = Fake(mapOf("chat/completions" to (400 to """{"error":{"message":"invalid key $key"}}""")))
        val r = OpenRouterChat.post(echo, cfg.openRouter.chatUrl, key, "{}", 1000)
        assertFalse(r.error!!.contains(key))
        v.clear()
        assertNull(v.token())
    }
}
