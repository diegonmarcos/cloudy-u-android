package com.diegonmarcos.cloudsuperintel.engine

import com.diegonmarcos.cloudsuperintel.OpenRouterModels
import com.diegonmarcos.superapp.ai.AiBackend
import com.diegonmarcos.superapp.ai.AiModel
import com.diegonmarcos.superapp.ai.AiReply
import com.diegonmarcos.superapp.ai.AiRequest
import com.diegonmarcos.superapp.ai.AiUsage
import com.diegonmarcos.superapp.aiengine.AiEngine
import com.diegonmarcos.superapp.modelcatalogue.AgentCatalogue
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** #1855 P2 the pure side of the AI engine: request mapping, row encoding, provider and model lists, the run of a chat, the usage ledger. */
class EngineCoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cat = AgentCatalogue.parse(File("../../ab_cloud-libs-shared/libs/model-catalogue/src/main/assets/agents/agents.json").readText())
    private val or = OpenRouterModels.parse(File("src/test/resources/openrouter-models.json").readText())

    private fun req(vararg kv: Pair<String, Any?>) = JSONObject().apply {
        put("id", "r1"); put("provider", "gateway")
        put("messages", org.json.JSONArray("""[{"role":"user","content":"hi"}]"""))
        kv.forEach { (k, v) -> put(k, v) }
    }.toString()

    // ── request mapping ──
    @Test fun aGatewayRequestMapsToAnAgentWithTheModeAndTheWireMessages() {
        val (p, why) = EngineRequest.parse(req("mode" to "hermes", "model" to "m/x", "effort" to "high", "headers" to JSONObject().put("x-opt", "1")))
        assertEquals("", why)
        val ai = EngineRequest.toAi(p!!)
        assertEquals("hermes", ai.agent?.mode)
        assertEquals("m/x", ai.model)
        assertEquals("high", ai.effort)
        assertEquals(mapOf("x-opt" to "1"), ai.headers)
        assertEquals(1, ai.wire?.length())
        assertEquals("m/x", ai.agent?.modelFor?.invoke("m/x"))
    }

    @Test fun claudeCliIsTheGatewayWithTheCatalogueClaudeMode() {
        val (p, _) = EngineRequest.parse(req("provider" to "claude-cli", "mode" to "ignored", "model" to "anthropic/claude-sonnet-4-5"))
        val ai = EngineRequest.toAi(p!!, EngineProviders.claudeMode(cat))
        assertEquals("claude-cli", ai.agent?.mode)
    }

    @Test fun openRouterCarriesNoAgentAndNoHeaders() {
        val (p, _) = EngineRequest.parse(req("provider" to "openrouter", "model" to "openrouter/auto", "mode" to "x", "headers" to JSONObject().put("x-a", "b")))
        val ai = EngineRequest.toAi(p!!)
        assertNull(ai.agent); assertEquals("openrouter/auto", ai.model); assertTrue(ai.headers.isEmpty())
    }

    @Test fun aBlankModelIsTheBackendsOwnAndABlankGatewayModeSendsNone() {
        val (p, _) = EngineRequest.parse(req("model" to "  "))
        val ai = EngineRequest.toAi(p!!)
        assertNull(ai.model); assertNull(ai.agent)
    }

    @Test fun aCallerCannotSetCredentialHeaders() {
        val h = JSONObject().put("Authorization", "Bearer x").put("Cookie", "c").put("x-api-key", "k").put("X-Keep", "1").put("bad name", "v")
        val (p, _) = EngineRequest.parse(req("headers" to h))
        assertEquals(mapOf("X-Keep" to "1"), p!!.headers)
    }

    @Test fun badRequestsAreRefusedWithAReason() {
        assertEquals("the request is not JSON", EngineRequest.parse("nope").second)
        assertTrue(EngineRequest.parse(req("id" to "")).second.contains("no id"))
        assertTrue(EngineRequest.parse(req("provider" to "local")).second.contains("unknown provider"))
        assertTrue(EngineRequest.parse(JSONObject().put("id", "a").put("provider", "gateway").toString()).second.contains("no messages"))
        assertTrue(EngineRequest.parse(JSONObject().put("id", "a").put("provider", "gateway").put("messages", org.json.JSONArray("[{\"content\":\"x\"}]")).toString()).second.contains("no role"))
        assertNull(EngineRequest.parse(null).first)
    }

    // ── row encoding ──
    @Test fun providerAndModelRowsRoundTripAndSurviveHostileText() {
        val p = AiEngine.ProviderInfo("gateway", "Fleet\tgateway\n", "gateway", AiEngine.HEALTH_UP, "m", "d")
        val d = AiEngine.ProviderInfo.decode(p.encode())!!
        assertEquals("Fleet gateway ", d.label); assertTrue(d.reachable); assertEquals(6, p.encode().split('\t').size)
        val m = AiEngine.ModelInfo("a/b", "B", "openrouter", 128000, "0.000003", "0.000015", "text->text")
        assertEquals(m, AiEngine.ModelInfo.decode(m.encode()))
        assertNull(AiEngine.ProviderInfo.decode("")); assertNull(AiEngine.ModelInfo.decode(null))
        assertEquals(AiEngine.HEALTH_UNKNOWN, AiEngine.ProviderInfo.decode("x")!!.health)
        assertEquals(0L, AiEngine.ModelInfo.decode("x\tX\tg\tnotanumber")!!.contextLength)
        assertEquals(listOf("a"), AiEngine.ProviderInfo.decodeAll(arrayOf("a", "")).map { it.id })
    }

    // ── provider list ──
    private val health = """{"modes":[{"id":"hermes","label":"Hermes","model":"nous/hermes"},{"id":"claude-cli","available":false},{"id":"extra","label":"Extra"}]}"""

    @Test fun providersReflectTheRealState() {
        val rows = EngineProviders.rows(GatewayState.parse(health), cat, true, true, "openrouter/auto").associateBy { it.id }
        assertEquals(listOf("gateway", "claude-cli", "openrouter"), EngineProviders.IDS)
        assertEquals(AiEngine.HEALTH_UP, rows["gateway"]!!.health)
        assertTrue(rows["gateway"]!!.detail.contains("hermes") && rows["gateway"]!!.detail.contains("extra") && !rows["gateway"]!!.detail.contains("claude-cli"))
        assertEquals(AiEngine.HEALTH_DOWN, rows["claude-cli"]!!.health)   // the gateway answers but reports claude-cli unavailable
        assertEquals(AiEngine.HEALTH_UP, rows["openrouter"]!!.health)
        assertEquals("openrouter/auto", rows["openrouter"]!!.model)
    }

    @Test fun aDownGatewayTakesClaudeDownAndAnUnaskedOneIsUnknown() {
        val down = EngineProviders.rows(GatewayState.DOWN, cat, true, false, "m").associateBy { it.id }
        assertEquals(AiEngine.HEALTH_DOWN, down["gateway"]!!.health); assertEquals(AiEngine.HEALTH_DOWN, down["claude-cli"]!!.health)
        assertEquals(AiEngine.HEALTH_DOWN, down["openrouter"]!!.health)   // a key, but no catalogue could be read
        val unk = EngineProviders.rows(GatewayState.UNKNOWN, cat, null, null, "m").associateBy { it.id }
        assertTrue(unk.values.all { it.health == AiEngine.HEALTH_UNKNOWN })
    }

    @Test fun noKeyIsSaidAndNeverAKeyShown() {
        val r = EngineProviders.rows(GatewayState(true), cat, false, true, "m").first { it.id == "openrouter" }
        assertEquals(AiEngine.HEALTH_NOKEY, r.health); assertFalse(r.reachable)
        assertEquals(AiEngine.HEALTH_UNKNOWN, EngineProviders.rows(GatewayState(true), cat, true, true, "m").first { it.id == "claude-cli" }.health)
    }

    // ── model lists ──
    @Test fun openRouterModelsCarryTheCataloguePrices() {
        val rows = EngineModels.openRouter(or)
        assertEquals(or.size, rows.size)
        val s = rows.first { it.id == "anthropic/claude-sonnet-4.5" }
        assertEquals("0.000003", s.pricePrompt); assertEquals("0.000015", s.priceCompletion); assertEquals(1_000_000L, s.contextLength)
        assertEquals("openrouter", s.group)
        assertEquals("", rows.first { it.id == "acme/bare-model" }.pricePrompt)
    }

    @Test fun claudeCliListsTheAnthropicRowsOnly() {
        val rows = EngineModels.claude(or, cat)
        assertTrue(rows.isNotEmpty() && rows.all { it.id.startsWith("anthropic/") && it.group == "anthropic" })
    }

    @Test fun gatewayListsItsModesThenItsModels() {
        val rows = EngineModels.gateway(cat, GatewayState.parse(health), listOf(AiModel("g/m", "GM", context = 8000, free = true)))
        val modes = rows.filter { it.group == "mode" }.associateBy { it.id }
        assertTrue("hermes" in modes && "goose" in modes && "claude-cli" !in modes)
        assertEquals("nous/hermes", modes["hermes"]!!.note)
        assertEquals("not served now", modes["goose"]!!.note)
        assertEquals("Extra", modes["extra"]!!.name)   // a live mode the catalogue lacks is listed too
        assertEquals(listOf("model"), rows.filter { it.group == "model" }.map { it.group }); assertEquals("free", rows.last().note)
        assertEquals("openclaw", EngineModels.gateway(cat, GatewayState.UNKNOWN, emptyList()).first { it.id == "openclaw" }.id)
    }

    @Test fun gatewayHealthParsing() {
        assertTrue(GatewayState.parse("not json").up == true && GatewayState.parse("not json").modes.isEmpty())
        assertEquals(3, GatewayState.parse(health).modes.size)
        assertFalse(GatewayState.parse(health).mode("claude-cli")!!.available)
    }

    // ── the run of a chat ──
    private class Out : AiEngine.Listener {
        val chunks = mutableListOf<String>(); var done: String? = null; var error: String? = null
        override fun onChunk(text: String) { chunks += text }
        override fun onDone(usageJson: String) { done = usageJson }
        override fun onError(reason: String) { error = reason }
    }

    private class Fake(val pieces: List<String>, val reply: AiReply) : AiBackend {
        override val id = "fake"
        override fun models() = emptyList<AiModel>()
        override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply = try { pieces.forEach(onDelta); reply } catch (e: Exception) { AiReply(null, error = e.message, transport = true) }
    }

    private val parsed = EngineRequest.parse(req()).first!!
    private fun run(b: AiBackend, cancelled: () -> Boolean = { false }, out: Out = Out(), meter: (Long, Long, Double?) -> Unit = { _, _, _ -> }): Out {
        EngineChat.run(parsed, EngineRequest.toAi(parsed), b, cancelled, out, meter); return out
    }

    @Test fun aStreamedAnswerArrivesPieceByPieceThenDoneWithUsage() {
        var metered: Triple<Long, Long, Double?>? = null
        val o = run(Fake(listOf("he", "llo"), AiReply("hello", usage = AiUsage(10, 5, 0.01)))) { a, b, c -> metered = Triple(a, b, c) }
        assertEquals(listOf("he", "llo"), o.chunks); assertNull(o.error)
        assertEquals(10, JSONObject(o.done!!).getLong("prompt")); assertEquals(0.01, JSONObject(o.done!!).getDouble("cost"), 1e-9)
        assertEquals(Triple(10L, 5L, 0.01), metered)
    }

    @Test fun aWholeAnswerIsOneChunk() {
        val o = run(Fake(emptyList(), AiReply("whole")))
        assertEquals(listOf("whole"), o.chunks); assertEquals("", o.done)
    }

    @Test fun aBackendErrorIsOnErrorAndNeverDone() {
        val o = run(Fake(emptyList(), AiReply(null, error = "HTTP 502")))
        assertEquals("HTTP 502", o.error); assertNull(o.done); assertTrue(o.chunks.isEmpty())
        assertEquals("no answer", run(Fake(emptyList(), AiReply(""))).error)
    }

    @Test fun cancelStopsAStreamAtTheNextPieceAndDropsAWholeAnswer() {
        var n = 0
        val o = run(Fake(listOf("a", "b", "c"), AiReply("abc")), { n++ >= 1 })
        assertEquals(listOf("a"), o.chunks); assertEquals(AiEngine.CANCELLED, o.error); assertNull(o.done)
        val w = run(Fake(emptyList(), AiReply("whole")), { true })
        assertEquals(AiEngine.CANCELLED, w.error); assertTrue(w.chunks.isEmpty())
    }

    @Test fun exactlyOneTerminalCallback() {
        val o = run(Fake(listOf("x"), AiReply("x")))
        assertTrue((o.done != null) != (o.error != null))
    }

    // ── the usage ledger ──
    @Test fun usageIsSummedPerDayProviderAndModelAndPersists() {
        var now = 1_800_000_000_000L   // 2027-01-15 UTC
        val f = File(tmp.root, "u.json")
        val u = EngineUsage(f) { now }
        assertEquals("{}", u.summary())
        u.record("openrouter", "a/b", 100, 50, 0.25)
        u.record("openrouter", "a/b", 10, 5, 0.05)
        u.record("gateway", null, 0, 0, null)
        now += 86_400_000L
        u.record("gateway", "hermes", 7, 3, null)
        val s = JSONObject(EngineUsage(f) { now }.summary())   // a fresh instance reads the file
        assertEquals(4, s.getJSONObject("all").getLong("requests"))
        assertEquals(1, s.getJSONObject("today").getLong("requests"))
        assertEquals(4, s.getJSONObject("month").getLong("requests"))
        assertEquals(0.30, s.getJSONObject("all").getDouble("cost"), 1e-9)
        assertFalse(s.getJSONObject("today").has("cost"))   // nothing reported a cost today: none is invented
        assertEquals(2, s.getJSONArray("days").length()); assertEquals("2027-01-16", s.getJSONArray("days").getJSONObject(0).getString("day"))
        val m = (0 until s.getJSONArray("models").length()).map { s.getJSONArray("models").getJSONObject(it) }.first { it.getString("model") == "a/b" }
        assertEquals(110, m.getLong("prompt")); assertEquals(165, m.getLong("total"))
        assertNotNull(s.getJSONArray("models").getJSONObject(0).opt("provider"))
    }

    @Test fun oldDaysArePruned() {
        var now = 1_800_000_000_000L
        val u = EngineUsage(File(tmp.root, "p.json")) { now }
        u.record("gateway", "m", 1, 1, null)
        now += 70L * 86_400_000L
        u.record("gateway", "m", 1, 1, null)
        assertEquals(1, JSONObject(u.summary()).getJSONArray("days").length())
    }
}
