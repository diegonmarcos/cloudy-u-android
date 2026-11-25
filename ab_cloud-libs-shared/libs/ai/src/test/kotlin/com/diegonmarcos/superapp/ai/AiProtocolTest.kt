package com.diegonmarcos.superapp.ai

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.ZoneId

/**
 * The shared protocol cases (assets/ai/protocol-cases.json), the same file Cloud Code's test/chat.mjs runs, fed to
 * this lib's implementation; plus the contract (ai.json) and the standard host on fake backends. Plain JVM.
 */
class AiProtocolTest {
    private val dir = File("src/main/assets/ai")
    private val cases = JSONObject(File(dir, "protocol-cases.json").readText())
    private val cfg = AiConfig.parse(File("../../../fleet-private-data/ai.json").readText())

    private fun JSONArray.objs() = (0 until length()).map { getJSONObject(it) }
    private fun JSONObject.strOrNull(k: String): String? = if (isNull(k)) null else getString(k)

    @Test fun sseCasesAgreeWithTheSharedFile() {
        for (c in cases.getJSONArray("sse").objs()) {
            val p = AiProtocol.Sse()
            val text = StringBuilder()
            val chunks = c.getJSONArray("chunks")
            for (i in 0 until chunks.length()) p.push(chunks.getString(i)).forEach { text.append(it) }
            assertEquals(c.getString("name"), c.getString("text"), text.toString())
            assertEquals(c.getString("name"), c.getBoolean("done"), p.done)
            assertEquals(c.getString("name"), c.strOrNull("error"), p.error)
        }
    }

    @Test fun wholeCasesAgreeWithTheSharedFile() {
        for (c in cases.getJSONArray("whole").objs()) {
            val r = AiProtocol.parseWhole(c.getInt("status"), c.getString("body"))
            assertEquals(c.getString("name"), c.strOrNull("text"), r.text)
            assertEquals(c.getString("name"), c.strOrNull("error"), r.error)
            if (c.has("citations")) assertEquals(c.getString("name"), c.getJSONArray("citations").let { a -> (0 until a.length()).map { a.getString(it) } }, r.citations)
        }
    }

    /** Records what a backend puts on the wire and answers with a fixed whole answer. */
    private class RecordingHttp : AiHttp {
        var url = ""
        var headers: Map<String, String> = emptyMap()
        var body = ""
        override fun get(url: String, headers: Map<String, String>, timeoutMs: Int) = AiHttp.Response(200, "{\"data\":[]}")
        override fun post(url: String, headers: Map<String, String>, body: String, timeoutMs: Int, onChunk: ((String) -> Unit)?): AiHttp.Response {
            this.url = url; this.headers = headers; this.body = body
            return AiHttp.Response(200, "{\"choices\":[{\"message\":{\"content\":\"ok\"}}]}")
        }
    }

    /** Structural equality (Android's org.json stub has no `similar`, and may order keys differently). */
    private fun same(a: Any?, b: Any?): Boolean = when {
        a is JSONObject && b is JSONObject -> a.length() == b.length() && a.keys().asSequence().all { b.has(it) && same(a.get(it), b.get(it)) }
        a is JSONArray && b is JSONArray -> a.length() == b.length() && (0 until a.length()).all { same(a.get(it), b.get(it)) }
        else -> a.toString() == b.toString()
    }

    private fun subset(want: JSONObject, got: JSONObject, name: String) {
        for (k in want.keys()) assertTrue("$name: $k is ${got.opt(k)}, wanted ${want.get(k)}", got.has(k) && same(want.get(k), got.get(k)))
    }

    @Test fun requestCasesAgreeWithTheSharedFile() {
        for (c in cases.getJSONArray("request").objs()) {
            val name = c.getString("name")
            val http = RecordingHttp()
            val msgs = c.getJSONArray("messages").objs().map { AiMsg(it.getString("role"), it.getString("content")) }
            val mode = c.strOrNull("mode")
            val agent = mode?.let { m -> AiAgent(m, m, "", m) { picked -> if (c.isNull("model")) null else picked } }
            val backend: AiBackend = if (c.getString("backend") == "gateway") AiGatewayBackend(cfg, "http://gw.test:1", http)
                else AiOpenRouterBackend(cfg, "test", AiTokenSource { AiToken("tok") }, http)
            val reply = backend.chat(AiRequest(c.strOrNull("model"), msgs, agent = agent), {})
            assertEquals(name, "ok", reply.text)
            val h = c.getJSONObject("headers")
            for (k in h.keys()) assertEquals("$name: header $k", h.getString(k), http.headers[k])
            val body = JSONObject(http.body)
            // The shared cases say stream:true; a client that cannot stream (this fake) asks for the whole answer.
            val want = JSONObject(c.getJSONObject("body").toString()).also { it.remove("stream") }
            subset(want, body, name)
            val absent = c.getJSONArray("absent")
            for (i in 0 until absent.length()) assertFalse("$name: no ${absent.getString(i)}", body.has(absent.getString(i)))
            val expectUrl = if (c.getString("backend") == "gateway") "http://gw.test:1" + cfg.gatewayChatPath else cfg.chatUrl
            assertEquals(name, expectUrl, http.url)
        }
    }

    @Test fun theBodyStreamsWhenAsked() {
        val b = JSONObject(AiProtocol.body(cfg, AiRequest("m", listOf(AiMsg("user", "hi"))), "m", true, true))
        assertTrue(b.getBoolean("stream"))
        assertEquals("m", b.getString("model"))
    }

    @Test fun openRouterAddsItsWebFieldsAndTheGatewayDoesNot() {
        val web = AiRequest("m", listOf(AiMsg("user", "hi")), web = true)
        val plugin = JSONObject(AiProtocol.body(cfg, web, "m", false, true))
        assertEquals(cfg.webPlugin, plugin.getJSONArray("plugins").getJSONObject(0).getString("id"))
        val native = JSONObject(AiProtocol.body(cfg, AiRequest("m", web.messages, web = true, nativeWeb = true), "m", false, true))
        assertTrue(native.has(cfg.nativeWebParam))
        assertFalse(native.has("plugins"))
        val gw = JSONObject(AiProtocol.body(cfg, web, "m", false, false))
        assertFalse(gw.has("plugins"))
        assertFalse(gw.has(cfg.nativeWebParam))
    }

    @Test fun noTokenSendsNothingAndSaysWhy() {
        val http = RecordingHttp()
        val b = AiOpenRouterBackend(cfg, "test", AiTokenSource { AiToken(null, "no openrouter token in the fleet Account") }, http)
        val r = b.chat(AiRequest("m", listOf(AiMsg("user", "hi"))), {})
        assertNull(r.text)
        assertEquals("no openrouter token in the fleet Account", r.error)
        assertEquals("", http.url)
    }

    @Test fun theContractIsTheOneTheAppsRead() {
        assertEquals("/v1/chat/completions", cfg.gatewayChatPath)
        assertEquals("X-Agent-Mode", cfg.modeHeader)
        assertEquals("openrouter", cfg.accountProvider)
        assertTrue(cfg.chatUrl.startsWith("https://"))
        assertEquals("protocol-cases.json", JSONObject(File("../../../fleet-private-data/ai.json").readText()).getJSONObject("protocol").getString("cases"))
    }

    @Test fun titlesAreOneLineAndCut() {
        assertEquals("hello world", AiProtocol.title("  hello\n world ", 40))
        assertEquals("abc…", AiProtocol.title("abcdef", 3))
    }

    @Test fun modelsAreParsedFlaggedAndSorted() {
        val json = """{"data":[
            {"id":"b/model","name":"Beta","supported_parameters":["web_search_options"],"pricing":{"prompt":"0","completion":"0"}},
            {"id":"a/model","name":"alpha","architecture":{"output_modalities":["text"]}},
            {"id":"img/model","name":"Img","architecture":{"output_modalities":["image"]}}]}"""
        val ms = AiProtocol.parseModels(json, "web_search_options")
        assertEquals(listOf("a/model", "b/model"), ms.map { it.id })
        assertTrue(ms[1].nativeWeb && ms[1].free)
        assertFalse(ms[0].nativeWeb)
    }

    @Test fun sessionsGroupByDay() {
        val zone = ZoneId.of("UTC")
        val now = 10L * 24 * 3600 * 1000 + 3600_000
        val day = 24L * 3600 * 1000
        val s = listOf(AiSession("a", "", "m", now, emptyList()), AiSession("b", "", "m", now - 3 * day, emptyList()), AiSession("c", "", "m", now - 30 * day, emptyList()))
        val g = AiSessions.group(s, now, zone)
        assertEquals(listOf(AiBucket.TODAY, AiBucket.PREVIOUS_7_DAYS, AiBucket.OLDER), g.map { it.first })
    }

    /** A backend that answers with a script. */
    private class Scripted(val replies: MutableList<AiReply>) : AiBackend {
        override val id = "scripted"
        val asked = mutableListOf<AiRequest>()
        override fun models() = listOf(AiModel("m", "M"))
        override fun chat(req: AiRequest, onDelta: (String) -> Unit): AiReply { asked += req; return replies.removeAt(0) }
    }

    private fun host(b: AiBackend, tools: List<AiTool> = emptyList(), store: AiSessionStore = AiMemorySessions(), gateway: AiBackend? = null, agents: List<AiAgent> = emptyList(), prefs: AiPrefs = AiMemoryPrefs("m")) =
        AiStandardHost(cfg, store, prefs, direct = b, gateway = gateway, agents = agents, tools = tools)

    @Test fun aSendStoresTheQuestionBeforeTheAnswerAndTheAnswerAfter() {
        val store = AiMemorySessions()
        val h = host(Scripted(mutableListOf(AiReply("hello", listOf("https://x")))), store = store)
        val out = h.send(h.newSession("m", 1L), "hi there", "m", false, 2L, AiSink.None)
        assertNull(out.error)
        assertEquals(listOf("user", "assistant"), out.session.messages.map { it.role })
        assertEquals("hi there", out.session.title)
        assertEquals(listOf("https://x"), out.citations)
        assertEquals(out.session, store.all().single())
    }

    @Test fun aFailedSendKeepsTheQuestion() {
        val store = AiMemorySessions()
        val h = host(Scripted(mutableListOf(AiReply(null, error = "no credit"))), store = store)
        val out = h.send(h.newSession("m", 1L), "hi", "m", false, 2L, AiSink.None)
        assertEquals("no credit", out.error)
        assertEquals(listOf("user"), store.all().single().messages.map { it.role })
    }

    @Test fun anAgentRoutesThroughTheGatewayAndNamesItselfWhenTheTransportFails() {
        val gw = Scripted(mutableListOf(AiReply(null, error = "timeout", transport = true)))
        val direct = Scripted(mutableListOf())
        val agent = AiAgent("goose", "Goose", "", "goose") { null }
        val h = AiStandardHost(cfg, AiMemorySessions(), AiMemoryPrefs("m", agent = "goose"), direct = direct, gateway = gw, agents = listOf(agent))
        val out = h.send(h.newSession("m", 1L), "hi", "m", false, 2L, AiSink.None)
        assertEquals("Goose (fleet agent gateway, mesh only): timeout", out.error)
        assertEquals(1, gw.asked.size)
        assertEquals(0, direct.asked.size)
        assertEquals("goose", gw.asked.single().agent?.id)
    }

    private class Echo(override val mutating: Boolean) : AiTool {
        override val name = "echo"
        override val description = "echoes"
        override val schema = """{"type":"object","properties":{"t":{"type":"string"}}}"""
        var ran = 0
        override fun sentence(args: JSONObject) = "Echo " + args.optString("t")
        override fun run(args: JSONObject): String { ran++; return "echoed " + args.optString("t") }
    }

    private fun toolRound() = AiReply("", toolCalls = listOf(AiToolCall("c1", "echo", """{"t":"x"}""")))

    @Test fun aMutatingToolWaitsForConsentAndDenyIsToldToTheModel() {
        val tool = Echo(mutating = true)
        val b = Scripted(mutableListOf(toolRound(), AiReply("done")))
        val asked = mutableListOf<AiConsentRequest>()
        val sink = object : AiSink { override fun consent(request: AiConsentRequest): Boolean { asked += request; return false } }
        val h = host(b, tools = listOf(tool))
        val out = h.send(h.newSession("m", 1L), "go", "m", false, 2L, sink)
        assertNull(out.error)
        assertEquals(0, tool.ran)
        assertEquals("Echo x", asked.single().sentence)
        val wire = b.asked.last().wire!!
        assertTrue(wire.getJSONObject(wire.length() - 1).getString("content").startsWith("denied"))
    }

    @Test fun anAllowedToolRunsAndItsResultGoesBack() {
        val tool = Echo(mutating = true)
        val b = Scripted(mutableListOf(toolRound(), AiReply("done")))
        val h = host(b, tools = listOf(tool))
        val out = h.send(h.newSession("m", 1L), "go", "m", false, 2L, object : AiSink { override fun consent(request: AiConsentRequest) = true })
        assertEquals("done", out.session.messages.last().content)
        assertEquals(1, tool.ran)
        val wire = b.asked.last().wire!!
        assertEquals("echoed x", wire.getJSONObject(wire.length() - 1).getString("content"))
    }

    @Test fun aReadOnlyToolNeverAsks() {
        val tool = Echo(mutating = false)
        val b = Scripted(mutableListOf(toolRound(), AiReply("done")))
        val h = host(b, tools = listOf(tool))
        h.send(h.newSession("m", 1L), "go", "m", false, 2L, AiSink.None)
        assertEquals(1, tool.ran)
    }
}
