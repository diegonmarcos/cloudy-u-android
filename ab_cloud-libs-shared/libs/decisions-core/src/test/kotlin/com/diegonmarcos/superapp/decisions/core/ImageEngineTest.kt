package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.UnknownHostException
import java.util.Base64

/** #1799 the engine's image half: the declaration, every refusal in order, the wire, the ledger, the breaker and the journal. */
class ImageEngineTest {

    private val out = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 7, 7, 7)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3)
    private val jpg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 1)

    private fun imageOk(bytes: ByteArray = out, cost: Double? = 0.04, mime: String = "image/png"): String {
        val o = JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject()
            .put("images", org.json.JSONArray().put(JSONObject().put("image_url", JSONObject()
                .put("url", "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes))))))))
        cost?.let { o.put("usage", JSONObject().put("cost", it)) }
        return o.toString()
    }

    inner class ImgHttp : Http {
        val bodies = ArrayList<JSONObject>()
        val tokens = ArrayList<String?>()
        val urls = ArrayList<String>()
        val timeouts = ArrayList<Int>()
        var reply: () -> Http.Response = { Http.Response(200, imageOk()) }
        var throwing: Exception? = null
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            urls.add(url); tokens.add(token); timeouts.add(timeoutMs)
            bodies.add(JSONObject(body!!))
            throwing?.let { throw it }
            return reply()
        }
    }

    private fun block(tweak: (JSONObject) -> Unit = {}): JSONObject = Fixtures.block { d ->
        d.put("image", JSONObject("""
        {"endpoint":"https://example.test/chat","timeout_ms":90000,"max_input_bytes":64,"max_output_bytes":32,"max_prompt_chars":40,
         "model_re":"[a-z0-9-]+/[a-z0-9.-]+",
         "budget":{"daily_usd_cap":0.1,"per_app_daily_calls":3,"est_call_usd":0.05},
         "suppress":{"metered":false,"offline":true,"battery_saver":true},
         "breaker":{"failures":2,"open_s":60},
         "uses":{
          "enh":{"enabled":true,"max_calls_per_hour":2,"consent":"implicit","content":"image","apps":["${Fixtures.APP}"]},
          "priv":{"enabled":true,"max_calls_per_hour":2,"consent":"required","content":"image","apps":["${Fixtures.APP}"]},
          "off":{"enabled":false,"max_calls_per_hour":2,"consent":"implicit","apps":["${Fixtures.APP}"]},
          "broken":{"enabled":true,"max_calls_per_hour":0,"apps":["${Fixtures.APP}"]}
         }}"""))
        tweak(d)
    }

    inner class ImgRig(root: JSONObject = block(), token: String? = Fixtures.TOKEN) {
        val policy = Policy.parse(root)
        val http = ImgHttp()
        val env = FakeEnv()
        val clock = Clock()
        var tokenValue: String? = token
        var tokenCalls = 0
        val ledgerStore = MemoryStore()
        val sink = MemorySink()
        val journal = Journal(sink, 100, clock)
        val consent = ConsentBook(MemoryStore())
        val ledger = policy.image?.let { Ledger(ledgerStore, it.budget, clock) }
        val breaker = policy.image?.let { CircuitBreaker(it.breaker.failures, it.breaker.openS * 1000, clock) }
        val engine = ImageEngine(policy.image, http, { tokenCalls++; tokenValue }, env, ledger, breaker, consent, journal, clock)
        val decisions = Engine(policy, FakeHttp(), { tokenValue }, env, Ledger(MemoryStore(), policy.budget, clock),
            AnswerCache(MemoryStore(), policy.cacheMax, clock), CircuitBreaker(2, 1000, clock), consent, journal, clock)

        fun run(use: String = "enh", model: String = "q/img-1", prompt: String = "make it better", input: ByteArray = png, app: String = Fixtures.APP) =
            engine.run(app, JSONObject().put("use", use).put("model", model).put("prompt", prompt), input)

        fun lastLine(): JSONObject = JSONObject(sink.lines.last())
    }

    private fun reason(a: ImageAnswer): String = if (a.answer.getBoolean("ok")) "answered" else a.answer.getString("reason")

    // ── an answer ─────────────────────────────────────────────────────────────────────────

    @Test fun aRunSendsTheDeclaredEndpointTheTokenAndOneMultimodalMessageAndReturnsThePicture() {
        val rig = ImgRig()
        val a = rig.run()
        assertTrue(a.answer.toString(), a.answer.getBoolean("ok"))
        assertArrayEquals(out, a.image)
        assertEquals("enh", a.answer.getString("use"))
        assertEquals("image/png", a.answer.getString("mime"))
        assertEquals(out.size, a.answer.getInt("bytes"))
        assertEquals(0.04, a.answer.getDouble("cost"), 0.0)
        assertTrue(a.answer.has("latency_ms"))
        assertEquals(listOf("https://example.test/chat"), rig.http.urls)
        assertEquals(listOf(90000), rig.http.timeouts)
        assertEquals(listOf<String?>(Fixtures.TOKEN), rig.http.tokens)
        val body = rig.http.bodies.single()
        assertEquals("q/img-1", body.getString("model"))
        assertFalse(body.getBoolean("stream"))
        assertEquals("image", body.getJSONArray("modalities").getString(0))
        assertEquals("text", body.getJSONArray("modalities").getString(1))
        assertTrue(body.getJSONObject("usage").getBoolean("include"))
        val msg = body.getJSONArray("messages").getJSONObject(0)
        assertEquals("user", msg.getString("role"))
        val content = msg.getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("make it better", content.getJSONObject(0).getString("text"))
        assertEquals("image_url", content.getJSONObject(1).getString("type"))
        val url = content.getJSONObject(1).getJSONObject("image_url").getString("url")
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(png), url)
        assertFalse("the token is never in the body", body.toString().contains(Fixtures.TOKEN))
    }

    @Test fun aJpegIsSentAsJpegAndTheAnswersMimeIsRead() {
        val rig = ImgRig()
        rig.http.reply = { Http.Response(200, imageOk(mime = "image/webp")) }
        val a = rig.run(input = jpg)
        assertEquals("image/webp", a.answer.getString("mime"))
        val url = rig.http.bodies.single().getJSONArray("messages").getJSONObject(0).getJSONArray("content")
            .getJSONObject(1).getJSONObject("image_url").getString("url")
        assertTrue(url.startsWith("data:image/jpeg;base64,"))
        assertEquals("image/png", ImageWire.mimeOf(png))
        assertEquals("image/jpeg", ImageWire.mimeOf(jpg))
        assertEquals("image/jpeg", ImageWire.mimeOf(byteArrayOf(0x89.toByte(), 1, 2)))
    }

    @Test fun theJournalNamesTheCallAndNeverThePromptThePictureOrTheToken() {
        val rig = ImgRig()
        rig.run(prompt = "secret prompt words")
        val line = rig.lastLine()
        assertEquals("answered", line.getString("outcome"))
        assertEquals(Fixtures.APP, line.getString("app"))
        assertEquals("enh", line.getString("use"))
        assertEquals("image", line.getString("kind"))
        assertEquals("q/img-1", line.getString("model"))
        assertEquals(png.size, line.getInt("bytes_in"))
        assertEquals(out.size, line.getInt("bytes_out"))
        assertEquals(0.04, line.getDouble("cost"), 0.0)
        val all = rig.sink.lines.joinToString("\n")
        assertFalse(all.contains("secret prompt"))
        assertFalse(all.contains(Fixtures.TOKEN))
        assertFalse(all.contains(Base64.getEncoder().encodeToString(png)))
        rig.tokenValue = null
        rig.run()
        assertEquals("refused", rig.lastLine().getString("outcome"))
        assertEquals("no_key", rig.lastLine().getString("reason"))
        assertEquals("image", rig.lastLine().getString("kind"))
    }

    // ── the refusals, in order ────────────────────────────────────────────────────────────

    @Test fun theDeclarationRefusesFirst() {
        val rig = ImgRig()
        assertEquals("misconfigured", reason(rig.run("broken")))
        assertEquals("unknown_use", reason(rig.run("nope")))
        assertEquals("app_not_allowed", reason(rig.run(app = "com.other")))
        assertEquals("disabled", reason(rig.run("off")))
        assertEquals(0, rig.http.bodies.size)
        assertEquals(0, rig.tokenCalls)
        val none = ImageEngine(null, rig.http, { "t" }, rig.env, null, null, rig.consent, rig.journal, rig.clock)
        assertEquals("no_image_policy", reason(none.run(Fixtures.APP, JSONObject().put("use", "enh"), png)))
        assertEquals(0, none.maxInputBytes)
        assertEquals(64, rig.engine.maxInputBytes)
        assertFalse(none.status(Fixtures.APP).getBoolean("served"))
    }

    @Test fun theRequestIsCheckedBeforeAnythingIsSent() {
        val rig = ImgRig()
        assertEquals("bad_request", reason(rig.run(prompt = " ")))
        assertEquals("bad_request", reason(rig.run(input = ByteArray(0))))
        assertEquals("bad_model", reason(rig.run(model = "")))
        assertEquals("bad_model", reason(rig.run(model = "noslash")))
        assertEquals("bad_model", reason(rig.run(model = "q/img 1; drop")))
        assertEquals("prompt_too_large", reason(rig.run(prompt = "x".repeat(41))))
        assertEquals("answered", reason(rig.run(prompt = "x".repeat(40))))
        assertEquals("image_too_large", reason(rig.run(input = ByteArray(65) { 1 })))
        assertEquals("answered", reason(rig.run(input = ByteArray(64) { 1 })))
        assertEquals(2, rig.http.bodies.size)
    }

    @Test fun consentIsTheUsersAndTheSwitchHostRecordsIt() {
        val rig = ImgRig()
        assertEquals("no_consent", reason(rig.run("priv")))
        assertEquals(0, rig.http.bodies.size)
        val denied = rig.decisions.setConsent("com.not.a.setter", JSONObject().put("app", Fixtures.APP).put("use", "priv").put("granted", true))
        assertFalse(denied.getBoolean("ok"))
        val granted = rig.decisions.setConsent(Fixtures.SETTER, JSONObject().put("app", Fixtures.APP).put("use", "priv").put("granted", true))
        assertTrue(granted.toString(), granted.getBoolean("ok"))
        assertEquals("answered", reason(rig.run("priv")))
        rig.decisions.setConsent(Fixtures.SETTER, JSONObject().put("app", Fixtures.APP).put("use", "enh").put("granted", false))
        assertEquals("no_consent", reason(rig.run("enh")))
        assertEquals("app_not_allowed", reason(ImageAnswer(rig.decisions.setConsent(Fixtures.SETTER,
            JSONObject().put("app", "com.other").put("use", "enh").put("granted", true)), null)))
    }

    @Test fun thePhonesStateFollowsTheImageBlocksOwnSuppression() {
        val rig = ImgRig()
        rig.env.metered = true
        assertEquals("answered", reason(rig.run()))   // metered is not suppressed for images
        rig.env.saver = true
        assertEquals("battery_saver", reason(rig.run()))
        rig.env.saver = false
        rig.env.online = false
        val off = rig.run()
        assertEquals("offline", reason(off))
        assertEquals("OFFLINE", off.answer.getString("kind"))
        assertEquals(ImageWire.NO_NETWORK, off.answer.getString("message"))
        assertEquals(1, rig.http.bodies.size)
        val strict = ImgRig(block { it.getJSONObject("image").getJSONObject("suppress").put("metered", true) })
        strict.env.metered = true
        assertEquals("metered", reason(strict.run()))
        val lax = ImgRig(block { it.getJSONObject("image").getJSONObject("suppress").put("offline", false).put("battery_saver", false) })
        lax.env.online = false
        lax.env.saver = true
        assertEquals("answered", reason(lax.run()))
    }

    @Test fun noKeyIsRefusedAfterTheChecksAndBeforeTheLedger() {
        val rig = ImgRig(token = null)
        assertEquals("no_key", reason(rig.run()))
        rig.tokenValue = ""
        assertEquals("no_key", reason(rig.run()))
        assertEquals(0, rig.ledger!!.callsToday(Fixtures.APP))
        assertEquals(0, rig.http.bodies.size)
    }

    @Test fun theImageLedgerHoldsTheHourlyLimitTheQuotaAndTheDailyCap() {
        val rig = ImgRig()
        assertEquals("answered", reason(rig.run()))
        assertEquals("answered", reason(rig.run()))
        assertEquals("rate_limited", reason(rig.run()))
        assertEquals(0.08, rig.ledger!!.spentToday(), 1e-9)
        rig.clock.now += 3_600_001L
        assertEquals("answered", reason(rig.run()))
        assertEquals("over_budget", reason(rig.run()))   // 0.12 >= 0.1
        val quota = ImgRig(block { it.getJSONObject("image").getJSONObject("budget").put("daily_usd_cap", 10.0).put("per_app_daily_calls", 1) })
        assertEquals("answered", reason(quota.run()))
        assertEquals("no_consent", reason(quota.run("priv")))   // refused before the ledger: it spends no quota
        assertEquals("app_quota", reason(quota.run()))
        // an answer with no cost is charged the estimate; a failed call is not charged
        val est = ImgRig()
        est.http.reply = { Http.Response(200, imageOk(cost = null)) }
        assertTrue(est.run().answer.isNull("cost"))
        assertEquals(0.05, est.ledger!!.spentToday(), 1e-9)
        est.http.reply = { Http.Response(500, "") }
        assertEquals("http", reason(est.run()))
        assertEquals(0.05, est.ledger!!.spentToday(), 1e-9)
        assertEquals(2, est.ledger!!.callsToday(Fixtures.APP))
    }

    @Test fun theImageBudgetIsNotTheDecisionsBudget() {
        val rig = ImgRig()
        rig.run()
        assertEquals(0.0, Ledger(MemoryStore(), rig.policy.budget, rig.clock).spentToday(), 0.0)
        assertEquals(0.1, rig.policy.image!!.budget.dailyUsdCap, 0.0)
        assertEquals(0.01, rig.policy.budget.dailyUsdCap, 0.0)
    }

    // ── the call's own failures ───────────────────────────────────────────────────────────

    @Test fun theApisFailuresComeBackWithTheirKindAndMessage() {
        for ((code, kind) in listOf(401 to "AUTH", 403 to "AUTH", 402 to "LIMIT", 429 to "LIMIT", 400 to "MODEL", 404 to "MODEL", 500 to "SERVER", 503 to "SERVER")) {
            val rig = ImgRig()
            rig.http.reply = { Http.Response(code, """{"error":{"message":"why $code"}}""") }
            val a = rig.run()
            assertEquals("http", reason(a))
            assertEquals("HTTP $code", kind, a.answer.getString("kind"))
            assertEquals("why $code", a.answer.getString("message"))
            assertNull(a.image)
            assertEquals("http", rig.lastLine().getString("reason"))
            assertEquals("q/img-1", rig.lastLine().getString("model"))
        }
        val bare = ImgRig()
        bare.http.reply = { Http.Response(502, "<html>") }
        assertEquals("HTTP 502", bare.run().answer.getString("message"))
    }

    @Test fun anAnswerWithNoPictureIsEmpty() {
        for (body in listOf("""{"choices":[{"message":{}}]}""", """{"choices":[{"message":{"images":[{"image_url":{"url":"https://x/y.png"}}]}}]}""")) {
            val rig = ImgRig()
            rig.http.reply = { Http.Response(200, body) }
            val a = rig.run()
            assertEquals("empty", reason(a))
            assertEquals("EMPTY", a.answer.getString("kind"))
            assertEquals(ImageWire.NO_IMAGE, a.answer.getString("message"))
        }
        val junk = ImgRig()
        junk.http.reply = { Http.Response(200, "not json") }
        assertEquals(ImageWire.UNREADABLE, junk.run().answer.getString("message"))
        val err = ImgRig()
        err.http.reply = { Http.Response(200, """{"error":{"message":"moderated"}}""") }
        assertEquals("moderated", err.run().answer.getString("message"))
    }

    @Test fun aTransportFailureIsOfflineInTheUsersWords() {
        val rig = ImgRig()
        rig.http.throwing = UnknownHostException("openrouter.ai")
        val a = rig.run()
        assertEquals("network", reason(a))
        assertEquals("OFFLINE", a.answer.getString("kind"))
        assertEquals(ImageWire.NO_NETWORK, a.answer.getString("message"))
        rig.http.throwing = IOException("reset")
        assertEquals("The request did not complete (IOException).", rig.run().answer.getString("message"))
    }

    @Test fun aPictureOverTheOutputLimitIsRefusedButCharged() {
        val rig = ImgRig()
        rig.http.reply = { Http.Response(200, imageOk(bytes = ByteArray(33) { 2 })) }
        val a = rig.run()
        assertEquals("output_too_large", reason(a))
        assertNull(a.image)
        assertEquals(0.04, rig.ledger!!.spentToday(), 1e-9)
        rig.http.reply = { Http.Response(200, imageOk(bytes = ByteArray(32) { 2 })) }
        assertEquals("answered", reason(rig.run()))
    }

    @Test fun serverAndNetworkFailuresOpenTheImageBreakerAndTheUsersOwnMistakesDoNot() {
        val rig = ImgRig(block { it.getJSONObject("image").getJSONObject("budget").put("daily_usd_cap", 10.0).put("per_app_daily_calls", 50) }
            .also { it.getJSONObject("image").getJSONObject("uses").getJSONObject("enh").put("max_calls_per_hour", 50) })
        rig.http.reply = { Http.Response(404, "") }
        repeat(3) { assertEquals("http", reason(rig.run())) }
        rig.http.reply = { Http.Response(200, """{"choices":[{"message":{}}]}""") }
        repeat(3) { assertEquals("empty", reason(rig.run())) }
        assertFalse(rig.breaker!!.isOpen())
        rig.http.reply = { Http.Response(500, "") }
        rig.run()
        rig.http.throwing = IOException("x")
        rig.run()
        assertTrue(rig.breaker!!.isOpen())
        val sent = rig.http.bodies.size
        assertEquals("breaker_open", reason(rig.run()))
        assertEquals(sent, rig.http.bodies.size)
        assertTrue(rig.engine.status(Fixtures.APP).getBoolean("breaker_open"))
        rig.clock.now += 60_001L
        rig.http.throwing = null
        rig.http.reply = { Http.Response(200, imageOk()) }
        assertEquals("answered", reason(rig.run()))
        assertFalse(rig.breaker!!.isOpen())
    }

    @Test fun anExceptionIsARefusalNotACrash() {
        val rig = ImgRig()
        rig.http.reply = { throw IllegalStateException("boom") }
        assertEquals("network", reason(rig.run()))
        val bad = ImageEngine(rig.policy.image, rig.http, { throw IllegalStateException("vault") }, rig.env, rig.ledger, rig.breaker, rig.consent, rig.journal, rig.clock)
        assertEquals("exception", reason(bad.run(Fixtures.APP, JSONObject().put("use", "enh").put("model", "q/m").put("prompt", "p"), png)))
    }

    // ── status ────────────────────────────────────────────────────────────────────────────

    @Test fun statusNamesTheImageUsesAndTheSpendNeverContent() {
        val rig = ImgRig()
        rig.run()
        val s = rig.engine.status(Fixtures.APP)
        assertTrue(s.getBoolean("served"))
        assertFalse(s.getBoolean("breaker_open"))
        assertEquals(0.04, s.getDouble("spent_today_usd"), 1e-9)
        assertEquals(0.1, s.getDouble("daily_usd_cap"), 0.0)
        val enh = s.getJSONObject("uses").getJSONObject("enh")
        assertTrue(enh.getBoolean("enabled"))
        assertTrue(enh.getBoolean("allowed_for_app"))
        assertTrue(enh.getBoolean("consent"))
        assertFalse(s.getJSONObject("uses").getJSONObject("priv").getBoolean("consent"))
        assertFalse(s.getJSONObject("uses").getJSONObject("off").getBoolean("enabled"))
        assertFalse(rig.engine.status("com.other").getJSONObject("uses").getJSONObject("enh").getBoolean("allowed_for_app"))
        assertEquals("broken", s.getJSONArray("rejected").getString(0))
        assertFalse(s.toString().contains("make it better"))
    }

    // ── the declaration ───────────────────────────────────────────────────────────────────

    @Test fun anImageUseIsValidatedLikeADecisionUse() {
        val p = Policy.parse(block { d ->
            val u = d.getJSONObject("image").getJSONObject("uses")
            u.put("noapps", JSONObject().put("enabled", true).put("max_calls_per_hour", 1))
            u.put("weird", JSONObject().put("enabled", true).put("max_calls_per_hour", 1).put("consent", "sometimes").put("apps", org.json.JSONArray().put("a")))
            u.put("mail", JSONObject().put("enabled", true).put("max_calls_per_hour", 1).put("consent", "implicit").put("content", "mail").put("apps", org.json.JSONArray().put("a")))
            u.put("mailok", JSONObject().put("enabled", true).put("max_calls_per_hour", 1).put("consent", "required").put("content", "mail").put("apps", org.json.JSONArray().put("a")))
            u.put("notobj", 3)
            u.put("_doc", "ignored")
        }).image!!
        assertEquals("max_calls_per_hour must be at least 1", p.rejected["broken"])
        assertEquals("apps names no app, so nothing may call it", p.rejected["noapps"])
        assertEquals("consent must be required or implicit", p.rejected["weird"])
        assertEquals("content mail always needs consent: required", p.rejected["mail"])
        assertEquals("is not an object", p.rejected["notobj"])
        assertFalse(p.rejected.containsKey("_doc"))
        assertTrue(p.uses.getValue("mailok").consentRequired)
        val enh = p.uses.getValue("enh")
        assertFalse(enh.consentRequired)
        assertEquals("image", enh.content)
        assertEquals(2, enh.maxCallsPerHour)
        assertEquals(setOf(Fixtures.APP), enh.apps)
        assertTrue(enh.enabled)
        assertFalse(p.uses.getValue("off").enabled)
        val view = enh.asUse()
        assertEquals("enh", view.name)
        assertEquals(enh.apps, view.apps)
        assertFalse(view.consentRequired)
        assertTrue(p.uses.getValue("priv").asUse().consentRequired)
        assertEquals(64, p.maxInputBytes)
        assertEquals(32, p.maxOutputBytes)
        assertEquals(40, p.maxPromptChars)
        assertEquals(90000, p.timeoutMs)
        assertEquals(2, p.breaker.failures)
        assertEquals(60L, p.breaker.openS)
        assertEquals(3, p.budget.perAppDailyCalls)
        assertEquals(0.05, p.budget.estCallUsd, 0.0)
        assertFalse(p.suppress.metered)
        assertTrue(p.suppress.offline)
        assertTrue(p.suppress.batterySaver)
    }

    @Test fun anImageBlockThatDoesNotValidateServesNoImageAndKeepsTheDecisions() {
        val bad = listOf<(JSONObject) -> Unit>(
            { it.put("endpoint", "http://plain") },
            { it.put("timeout_ms", 500) },
            { it.put("timeout_ms", 300001) },
            { it.put("max_input_bytes", 0) },
            { it.put("max_output_bytes", 0) },
            { it.put("max_prompt_chars", 0) },
            { it.put("model_re", "") },
            { it.put("model_re", "([") },
            { it.remove("budget") },
            { it.getJSONObject("budget").put("daily_usd_cap", 0) },
            { it.getJSONObject("budget").put("per_app_daily_calls", -1) },
            { it.getJSONObject("budget").remove("est_call_usd") },
            { it.remove("suppress") },
            { it.remove("breaker") },
            { it.getJSONObject("breaker").put("failures", 0) },
            { it.getJSONObject("breaker").put("open_s", 0) },
        )
        for ((i, t) in bad.withIndex()) {
            val p = Policy.parse(block { t(it.getJSONObject("image")) })
            assertNull("case $i", p.image)
            assertNotNull("case $i", p.imageProblem)
            assertTrue("case $i", p.uses.containsKey("ui"))
        }
        assertEquals("image.endpoint must be an https URL", Policy.parse(block { it.getJSONObject("image").put("endpoint", "x") }).imageProblem)
        val absent = Policy.parse(Fixtures.block())
        assertNull(absent.image)
        assertNull(absent.imageProblem)
        val ok = Policy.parse(block())
        assertNotNull(ok.image)
        assertNull(ok.imageProblem)
        val bounds = Policy.parse(block { it.getJSONObject("image").put("timeout_ms", 1000) })
        assertEquals(1000, bounds.image!!.timeoutMs)
        assertEquals(300000, Policy.parse(block { it.getJSONObject("image").put("timeout_ms", 300000) }).image!!.timeoutMs)
        val noUses = Policy.parse(block { it.getJSONObject("image").remove("uses") }).image!!
        assertTrue(noUses.uses.isEmpty())
        val defaults = Policy.parse(block { it.getJSONObject("image").put("suppress", JSONObject()) }).image!!
        assertTrue(defaults.suppress.metered && defaults.suppress.offline && defaults.suppress.batterySaver)
        val noConsent = Policy.parse(block { it.getJSONObject("image").getJSONObject("uses").getJSONObject("enh").remove("consent") }).image!!
        assertTrue(noConsent.uses.getValue("enh").consentRequired)
        val noEnabled = Policy.parse(block { it.getJSONObject("image").getJSONObject("uses").getJSONObject("enh").remove("enabled") }).image!!
        assertFalse(noEnabled.uses.getValue("enh").enabled)
    }

    @Test fun theShippedDeclarationServesTheCanvasEnhancerForCanvasOnly() {
        val p = Policy.parse(Fixtures.realManifest())
        assertNull(p.imageProblem)
        val img = p.image!!
        assertTrue(img.rejected.toString(), img.rejected.isEmpty())
        val use = img.uses.getValue("canvas_enhance")
        assertTrue(use.enabled)
        assertEquals(setOf("com.diegonmarcos.cloudcanvas"), use.apps)
        assertTrue(img.endpoint.startsWith("https://"))
        assertTrue(img.model.matches("google/gemini-2.5-flash-image"))
        assertTrue(img.model.matches("qwen/qwen-image-3"))
        assertFalse(img.model.matches("x"))
        assertTrue(img.budget.dailyUsdCap > 0)
        assertFalse(img.suppress.metered)
        assertTrue(img.suppress.offline)
        assertEquals(3, Policy.CONTRACT)
        assertEquals(Policy.CONTRACT, Fixtures.realManifest().getInt("contract"))
    }
}
