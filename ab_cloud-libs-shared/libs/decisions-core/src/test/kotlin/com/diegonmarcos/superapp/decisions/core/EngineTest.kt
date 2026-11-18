package com.diegonmarcos.superapp.decisions.core

import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EngineTest {

    private fun reason(r: JSONObject): String = if (r.getBoolean("ok")) "answered" else r.getString("reason")

    // ── an answer ─────────────────────────────────────────────────────────────────────────

    @Test fun anAnswerCarriesTheUsesClassThresholdAndTypedResults() {
        val rig = Rig()
        val r = rig.decide("ui")
        assertTrue(r.getBoolean("ok"))
        assertEquals("ui", r.getString("use"))
        assertEquals("user_facing", r.getString("class"))
        assertFalse(r.getBoolean("cached"))
        assertEquals(0.8, r.getDouble("threshold"), 0.0)
        assertFalse(r.has("advice_only"))
        val ok = r.getJSONObject("results").getJSONObject("ok")
        assertEquals(0.95, ok.getDouble("p"), 0.0)
        assertTrue(ok.getBoolean("confident"))
    }

    @Test fun theRequestIsTheDeclaredEndpointModelStateAndQuestions() {
        val rig = Rig()
        rig.decide("ui", JSONObject().put("n", 1), Fixtures.noulQ("q1"))
        assertEquals(listOf("https://example.test/decisions"), rig.http.endpoints)
        val req = rig.http.sent.single()
        assertEquals("m/1", req.getString("model"))
        assertEquals(1, req.getJSONObject("state").getInt("n"))
        assertEquals("noul", req.getJSONObject("questions").getJSONObject("q1").getString("type"))
    }

    @Test fun theTokenReachesThePostAndNothingElse() {
        val rig = Rig(token = Fixtures.TOKEN)
        val r = rig.decide("ui", JSONObject().put("marker", "state-marker-xyz"))
        assertEquals(listOf<String?>(Fixtures.TOKEN), rig.http.tokens)
        assertFalse(rig.http.sent.single().toString().contains(Fixtures.TOKEN))
        assertFalse(r.toString().contains(Fixtures.TOKEN))
        rig.decide("ui", JSONObject().put("marker", "state-marker-xyz-2"), Fixtures.noulQ("other"))
        rig.decide("nope")
        for (line in rig.sink.lines) {
            assertFalse(line, line.contains(Fixtures.TOKEN))
            assertFalse(line, line.contains("state-marker"))
            assertFalse(line, line.contains("Is it fine?"))
        }
        assertFalse(rig.engine.status(Fixtures.APP).toString().contains(Fixtures.TOKEN))
    }

    @Test fun theStateIsRedactedBeforeItLeaves() {
        val rig = Rig()
        val state = JSONObject().put("note", "key ${Fixtures.SECRET}").put("Body", "private mail text")
            .put("env", JSONObject().put("MY_TOKEN", "abcdef")).put("n", 1)
        rig.decide("ui", state)
        val sent = rig.http.sent.single().getJSONObject("state")
        assertEquals("key [X]", sent.getString("note"))
        assertFalse(sent.has("Body"))
        assertEquals("[X]", sent.getJSONObject("env").getString("MY_TOKEN"))
        assertEquals(1, sent.getInt("n"))
        assertFalse(rig.http.sent.single().toString().contains("private mail text"))
        val line = JSONObject(rig.sink.lines.last())
        assertEquals(2, line.getInt("masked"))
        assertEquals(1, line.getInt("dropped"))
    }

    @Test fun aStringStateIsRedactedToo() {
        val rig = Rig()
        rig.decide("ui", "Bearer abc123")
        assertEquals("Bearer [X]", rig.http.sent.single().getString("state"))
    }

    @Test fun gatingAnswersAdviceOnlyAndNothingElseDoes() {
        val rig = Rig()
        assertTrue(rig.decide("gate").getBoolean("advice_only"))
        assertFalse(rig.decide("bg", JSONObject().put("n", 2)).has("advice_only"))
        assertFalse(rig.decide("ui", JSONObject().put("n", 3)).has("advice_only"))
    }

    @Test fun confidenceIsTheUsesThreshold() {
        val rig = Rig()
        rig.http.reply = { Http.Response(200, JSONObject().put("answers", JSONObject().put("ok", JSONObject().put("noul", 0.5))).toString()) }
        assertFalse(rig.decide("ui").getJSONObject("results").getJSONObject("ok").getBoolean("confident"))
    }

    // ── the declaration ───────────────────────────────────────────────────────────────────

    @Test fun anUndeclaredUseIsRefusedWithoutACall() {
        val rig = Rig()
        assertEquals("unknown_use", reason(rig.decide("nope")))
        assertEquals("unknown_use", reason(rig.engine.decide(Fixtures.APP, JSONObject())))
        assertTrue(rig.http.sent.isEmpty())
        assertEquals(0, rig.tokenCalls)
    }

    @Test fun aUseThatDidNotValidateIsMisconfiguredNotUnknown() {
        val rig = Rig(Fixtures.block { it.getJSONObject("uses").getJSONObject("ui").put("threshold", 5) })
        assertEquals("misconfigured", reason(rig.decide("ui")))
        assertTrue(rig.http.sent.isEmpty())
    }

    @Test fun onlyTheAppsAUseNamesMayCallIt() {
        val rig = Rig()
        assertEquals("app_not_allowed", reason(rig.decide("ui", app = "com.other.app")))
        assertTrue(rig.http.sent.isEmpty())
        assertTrue(rig.decide("ui", app = Fixtures.APP).getBoolean("ok"))
    }

    @Test fun aDisabledUseIsNotServed() {
        val rig = Rig()
        assertEquals("disabled", reason(rig.decide("off")))
        assertTrue(rig.http.sent.isEmpty())
    }

    @Test fun badQuestionsAreRefusedBeforeAnythingIsSent() {
        val rig = Rig()
        assertEquals("bad_questions", reason(rig.engine.decide(Fixtures.APP, JSONObject().put("use", "ui").put("state", 1))))
        assertEquals("bad_questions", reason(rig.decide("ui", questions = JSONObject())))
        assertEquals("option_not_allowed", reason(rig.decide("ui", questions = Fixtures.choiceQ("a", "z"))))
        assertEquals("bad_request", reason(rig.engine.decide(Fixtures.APP, JSONObject().put("use", "ui").put("questions", Fixtures.noulQ()))))
        assertTrue(rig.http.sent.isEmpty())
        assertEquals(0, rig.tokenCalls)
    }

    @Test fun aStateOverTheCapIsRefusedNotTruncated() {
        val rig = Rig()
        assertEquals("state_too_large", reason(rig.decide("ui", JSONObject().put("s", "x".repeat(193)))))
        assertTrue(rig.http.sent.isEmpty())
        assertTrue(rig.decide("ui", JSONObject().put("s", "x".repeat(192))).getBoolean("ok"))
    }

    @Test fun theCapAppliesAfterRedactionSoDroppedBodiesDoNotCountAgainstIt() {
        val rig = Rig()
        val r = rig.decide("ui", JSONObject().put("Body", "x".repeat(5000)).put("n", 1))
        assertTrue(r.getBoolean("ok"))
    }

    // ── consent ───────────────────────────────────────────────────────────────────────────

    @Test fun aUseThatReadsMailIsOffUntilTheUserGrantsIt() {
        val rig = Rig()
        assertEquals("no_consent", reason(rig.decide("mailuse")))
        assertTrue(rig.http.sent.isEmpty())
        assertEquals(0, rig.tokenCalls)
        val g = rig.engine.setConsent(Fixtures.SETTER, JSONObject().put("app", Fixtures.APP).put("use", "mailuse").put("granted", true))
        assertTrue(g.getBoolean("ok"))
        assertEquals("mailuse", g.getString("use"))
        assertEquals(Fixtures.APP, g.getString("app"))
        assertTrue(g.getBoolean("granted"))
        assertTrue(rig.decide("mailuse").getBoolean("ok"))
        rig.engine.setConsent(Fixtures.SETTER, JSONObject().put("app", Fixtures.APP).put("use", "mailuse").put("granted", false))
        assertEquals("no_consent", reason(rig.decide("mailuse", JSONObject().put("n", 9))))
    }

    @Test fun aConsentIsPerAppAndNeverImpliedByAnotherUse() {
        val rig = Rig(Fixtures.block { d ->
            val u = JSONObject(d.getJSONObject("uses").getJSONObject("mailuse").toString())
            u.put("apps", JSONArray().put(Fixtures.APP).put("com.other.app"))
            d.getJSONObject("uses").put("mailuse", u)
        })
        rig.engine.setConsent(Fixtures.SETTER, JSONObject().put("app", Fixtures.APP).put("use", "mailuse").put("granted", true))
        assertTrue(rig.decide("mailuse").getBoolean("ok"))
        assertEquals("no_consent", reason(rig.decide("mailuse", app = "com.other.app")))
        assertEquals("no_consent", reason(rig.decide("mailuse", JSONObject().put("n", 5), app = "com.other.app")))
    }

    @Test fun onlyAConsentSetterMayGrantAndOnlyForADeclaredUseAndApp() {
        val rig = Rig()
        fun grant(caller: String, app: String, use: String, granted: Any? = true) = rig.engine.setConsent(
            caller, JSONObject().put("app", app).put("use", use).also { if (granted != null) it.put("granted", granted) })
        assertEquals("not_a_consent_setter", reason(grant(Fixtures.APP, Fixtures.APP, "mailuse")))
        assertEquals("unknown_use", reason(grant(Fixtures.SETTER, Fixtures.APP, "nope")))
        assertEquals("app_not_allowed", reason(grant(Fixtures.SETTER, "com.other.app", "mailuse")))
        assertEquals("bad_request", reason(grant(Fixtures.SETTER, Fixtures.APP, "mailuse", null)))
        assertEquals("bad_request", reason(grant(Fixtures.SETTER, Fixtures.APP, "mailuse", "yes")))
        assertEquals("no_consent", reason(rig.decide("mailuse")))
    }

    // ── the cache ─────────────────────────────────────────────────────────────────────────

    @Test fun aBackgroundUseAsksOnceAndServesTheRestFromItsCache() {
        val rig = Rig()
        val first = rig.decide("bg")
        val second = rig.decide("bg")
        assertFalse(first.getBoolean("cached"))
        assertTrue(second.getBoolean("cached"))
        assertEquals(first.getJSONObject("results").toString(), second.getJSONObject("results").toString())
        assertEquals(1, rig.http.sent.size)
        assertEquals(1, rig.tokenCalls)
        assertEquals(1, rig.ledger.callsToday(Fixtures.APP))
        assertEquals("cached", JSONObject(rig.sink.lines.last()).getString("outcome"))
    }

    private fun roomy() = Rig(Fixtures.block { it.getJSONObject("budget").put("per_app_daily_calls", 50) })

    @Test fun theCacheKeyIsTheUseTheStateAndTheQuestionsNotTheOrderOfKeys() {
        val rig = roomy()
        rig.decide("bg", JSONObject().put("a", 1).put("b", JSONObject().put("x", 1).put("y", 2)))
        assertTrue(rig.decide("bg", JSONObject().put("b", JSONObject().put("y", 2).put("x", 1)).put("a", 1)).getBoolean("cached"))
        assertFalse(rig.decide("bg", JSONObject().put("a", 2).put("b", JSONObject().put("x", 1).put("y", 2))).getBoolean("cached"))
        assertFalse(rig.decide("bg", JSONObject().put("a", 1).put("b", JSONObject().put("x", 1).put("y", 2)), Fixtures.noulQ("other")).getBoolean("cached"))
        assertFalse(rig.decide("gate", JSONObject().put("a", 1).put("b", JSONObject().put("x", 1).put("y", 2))).getBoolean("cached"))
        assertEquals(4, rig.http.sent.size)
    }

    @Test fun arraysAndStringsAreDistinguishedInTheKey() {
        val rig = roomy()
        rig.decide("bg", JSONObject().put("v", JSONArray().put(1).put(2)))
        assertTrue(rig.decide("bg", JSONObject().put("v", JSONArray().put(1).put(2))).getBoolean("cached"))
        assertFalse(rig.decide("bg", JSONObject().put("v", JSONArray().put(2).put(1))).getBoolean("cached"))
        assertFalse(rig.decide("bg", JSONObject().put("v", "[1,2]")).getBoolean("cached"))
        assertFalse(rig.decide("bg", JSONObject().put("v", JSONObject.NULL)).getBoolean("cached"))
    }

    @Test fun aCachedAnswerIsServedWhileOfflineOnAMeteredNetworkOrInBatterySaver() {
        val rig = Rig()
        rig.decide("bg")
        rig.env.online = false
        rig.env.metered = true
        rig.env.saver = true
        assertTrue(rig.decide("bg").getBoolean("cached"))
    }

    @Test fun aCachedAnswerExpiresWithItsTtl() {
        val rig = Rig()
        rig.decide("bg")
        rig.clock.now += 59_999
        assertTrue(rig.decide("bg").getBoolean("cached"))
        rig.clock.now += 1
        assertFalse(rig.decide("bg").getBoolean("cached"))
        assertEquals(2, rig.http.sent.size)
    }

    @Test fun aUseWithoutATtlIsNeverCached() {
        val rig = Rig()
        rig.decide("gate")
        rig.decide("gate")
        assertEquals(2, rig.http.sent.size)
        assertEquals(0, rig.cache.size())
    }

    @Test fun aRefusalAndAFailureAreNotCached() {
        val rig = Rig()
        rig.http.reply = { Http.Response(500, "{}") }
        rig.decide("bg")
        rig.http.reply = { req -> Http.Response(200, rig.http.ok(req)) }
        assertFalse(rig.decide("bg").getBoolean("cached"))
        assertEquals(1, rig.cache.size())
    }

    // ── the phone ─────────────────────────────────────────────────────────────────────────

    @Test fun nothingIsSentOfflineOnAMeteredNetworkOrInBatterySaver() {
        val rig = Rig()
        rig.env.online = false
        assertEquals("offline", reason(rig.decide("ui")))
        rig.env.online = true
        rig.env.metered = true
        assertEquals("metered", reason(rig.decide("ui")))
        rig.env.metered = false
        rig.env.saver = true
        assertEquals("battery_saver", reason(rig.decide("ui")))
        assertTrue(rig.http.sent.isEmpty())
        assertEquals(0, rig.tokenCalls)
        rig.env.saver = false
        assertTrue(rig.decide("ui").getBoolean("ok"))
    }

    @Test fun eachSuppressionCanBeSwitchedOffInThePolicy() {
        val off = { name: String -> Rig(Fixtures.block { it.getJSONObject("suppress").put(name, false) }) }
        off("offline").let { it.env.online = false; assertTrue(it.decide("ui").getBoolean("ok")) }
        off("metered").let { it.env.metered = true; assertTrue(it.decide("ui").getBoolean("ok")) }
        off("battery_saver").let { it.env.saver = true; assertTrue(it.decide("ui").getBoolean("ok")) }
        val keepOthers = Rig(Fixtures.block { it.getJSONObject("suppress").put("metered", false) })
        keepOthers.env.online = false
        assertEquals("offline", reason(keepOthers.decide("ui")))
    }

    // ── the key ───────────────────────────────────────────────────────────────────────────

    @Test fun noTokenIsNoOpinionAndCostsNoQuota() {
        val rig = Rig(token = null)
        assertEquals("no_key", reason(rig.decide("ui")))
        rig.tokenValue = "  "
        assertEquals("no_key", reason(rig.decide("ui")))
        assertTrue(rig.http.sent.isEmpty())
        assertEquals(0, rig.ledger.callsToday(Fixtures.APP))
    }

    @Test fun aTokenThatThrowsIsAnExceptionNotACrash() {
        val rig = Rig()
        val engine = Engine(rig.policy, rig.http, { throw IllegalStateException("binder died") }, rig.env, rig.ledger, rig.cache, rig.breaker, rig.consent, rig.journal, rig.clock)
        assertEquals("exception", reason(engine.decide(Fixtures.APP, Fixtures.request("ui"))))
        assertEquals("exception", JSONObject(rig.sink.lines.last()).getString("reason"))
    }

    // ── the spend ledger ──────────────────────────────────────────────────────────────────

    @Test fun theFleetCapStopsTheCallsAfterSpendReachesIt() {
        val rig = Rig()
        rig.http.cost = 0.006
        assertTrue(rig.decide("gate", JSONObject().put("n", 1)).getBoolean("ok"))
        assertTrue(rig.decide("gate", JSONObject().put("n", 2)).getBoolean("ok"))
        assertEquals("over_budget", reason(rig.decide("gate", JSONObject().put("n", 3))))
        assertEquals("over_budget", reason(rig.decide("ui", JSONObject().put("n", 4), app = Fixtures.APP)))
        assertEquals(2, rig.http.sent.size)
        rig.clock.now += 86_400_000L
        assertTrue(rig.decide("gate", JSONObject().put("n", 5)).getBoolean("ok"))
    }

    @Test fun anAnswerWithNoCostIsChargedTheEstimate() {
        val rig = Rig()
        rig.http.cost = null
        rig.decide("gate", JSONObject().put("n", 1))
        assertEquals(0.001, rig.ledger.spentToday(), 1e-12)
    }

    @Test fun aRefusedOrFailedCallIsNotCharged() {
        val rig = Rig()
        rig.http.reply = { Http.Response(500, "{}") }
        rig.decide("gate")
        assertEquals(0.0, rig.ledger.spentToday(), 0.0)
        rig.decide("nope")
        assertEquals(0.0, rig.ledger.spentToday(), 0.0)
    }

    @Test fun anAppHasADailyQuota() {
        val rig = Rig()
        repeat(3) { assertTrue(rig.decide("gate", JSONObject().put("n", it)).getBoolean("ok")) }
        assertEquals("app_quota", reason(rig.decide("gate", JSONObject().put("n", 9))))
        assertEquals(3, rig.http.sent.size)
    }

    @Test fun aUseIsLimitedPerHour() {
        val rig = Rig()
        assertTrue(rig.decide("ui", JSONObject().put("n", 1)).getBoolean("ok"))
        assertTrue(rig.decide("ui", JSONObject().put("n", 2)).getBoolean("ok"))
        assertEquals("rate_limited", reason(rig.decide("ui", JSONObject().put("n", 3))))
        rig.clock.now += 3_600_001
        assertTrue(rig.decide("ui", JSONObject().put("n", 4)).getBoolean("ok"))
    }

    // ── failures ──────────────────────────────────────────────────────────────────────────

    @Test fun aTransportFailureIsNetworkAndAnHttpErrorIsHttp() {
        val rig = Rig()
        rig.http.throwing = java.io.IOException("no route")
        assertEquals("network", reason(rig.decide("ui", JSONObject().put("n", 1))))
        rig.http.throwing = null
        rig.http.reply = { Http.Response(503, """{"error":{"message":"busy"}}""") }
        assertEquals("http", reason(rig.decide("ui", JSONObject().put("n", 2))))
    }

    @Test fun anAnswerThatCannotBeReadIsMalformedAndIsChargedBecauseItWasPaidFor() {
        val rig = Rig()
        rig.http.reply = { Http.Response(200, JSONObject().put("answers", JSONObject().put("ok", JSONObject().put("noul", 7))).toString()) }
        assertEquals("malformed", reason(rig.decide("gate")))
        assertTrue(rig.ledger.spentToday() > 0)
        rig.http.reply = { Http.Response(200, "not json") }
        assertEquals("http", reason(rig.decide("gate", JSONObject().put("n", 2))))
    }

    @Test fun theBreakerOpensAfterRepeatedFailuresThenTriesOnceAfterItsPeriod() {
        val rig = roomy()
        rig.http.reply = { Http.Response(500, "{}") }
        assertEquals("http", reason(rig.decide("gate", JSONObject().put("n", 1))))
        assertEquals("http", reason(rig.decide("gate", JSONObject().put("n", 2))))
        assertEquals("breaker_open", reason(rig.decide("gate", JSONObject().put("n", 3))))
        assertEquals(2, rig.http.sent.size)
        assertTrue(rig.engine.status(Fixtures.APP).getBoolean("breaker_open"))
        rig.clock.now += 60_000
        rig.http.reply = { req -> Http.Response(200, rig.http.ok(req)) }
        assertTrue(rig.decide("gate", JSONObject().put("n", 4)).getBoolean("ok"))
        assertFalse(rig.engine.status(Fixtures.APP).getBoolean("breaker_open"))
        assertTrue(rig.decide("gate", JSONObject().put("n", 5)).getBoolean("ok"))
    }

    @Test fun aMalformedAnswerCountsTowardTheBreaker() {
        val rig = roomy()
        rig.http.reply = { Http.Response(200, JSONObject().put("answers", JSONObject()).toString()) }
        rig.decide("gate", JSONObject().put("n", 1))
        rig.decide("gate", JSONObject().put("n", 2))
        assertEquals("breaker_open", reason(rig.decide("gate", JSONObject().put("n", 3))))
    }

    // ── the journal and the status ────────────────────────────────────────────────────────

    @Test fun everyCallLeavesOneJournalLineWithNoState() {
        val rig = Rig()
        rig.decide("ui", JSONObject().put("n", 1))
        rig.decide("nope")
        rig.decide("ui", JSONObject().put("n", 2), app = "com.other.app")
        val lines = rig.sink.lines.map { JSONObject(it) }
        assertEquals(3, lines.size)
        assertEquals("answered", lines[0].getString("outcome"))
        assertEquals(Fixtures.APP, lines[0].getString("app"))
        assertEquals("user_facing", lines[0].getString("class"))
        assertEquals(0.0001, lines[0].getDouble("cost"), 0.0)
        assertEquals(0.95, lines[0].getJSONObject("scores").getDouble("ok"), 0.0)
        assertEquals("refused", lines[1].getString("outcome"))
        assertEquals("unknown_use", lines[1].getString("reason"))
        assertEquals("app_not_allowed", lines[2].getString("reason"))
    }

    @Test fun theJournalRecordsALatencyForACallThatWasMade() {
        val rig = Rig()
        rig.http.reply = { req -> rig.clock.now += 42; Http.Response(200, rig.http.ok(req)) }
        rig.decide("ui")
        assertEquals(42L, JSONObject(rig.sink.lines.last()).getLong("latency_ms"))
        rig.http.reply = { rig.clock.now += 7; Http.Response(500, "{}") }
        rig.decide("ui", JSONObject().put("n", 2))
        assertEquals(7L, JSONObject(rig.sink.lines.last()).getLong("latency_ms"))
    }

    @Test fun theStatusIsNamesAndFlagsOnly() {
        val rig = Rig()
        rig.env.metered = true
        val s = rig.engine.status(Fixtures.APP)
        assertTrue(s.getBoolean("ok"))
        assertEquals(2, s.getInt("contract"))
        assertTrue(s.getBoolean("token"))
        assertFalse(s.getBoolean("breaker_open"))
        assertTrue(s.getBoolean("online"))
        assertTrue(s.getBoolean("metered"))
        assertFalse(s.getBoolean("battery_saver"))
        assertEquals(0.0, s.getDouble("spent_today_usd"), 0.0)
        assertEquals(0.01, s.getDouble("daily_usd_cap"), 0.0)
        val ui = s.getJSONObject("uses").getJSONObject("ui")
        assertTrue(ui.getBoolean("enabled"))
        assertEquals("user_facing", ui.getString("class"))
        assertTrue(ui.getBoolean("allowed_for_app"))
        assertTrue(ui.getBoolean("consent"))
        assertFalse(s.getJSONObject("uses").getJSONObject("mailuse").getBoolean("consent"))
        assertFalse(s.getJSONObject("uses").getJSONObject("off").getBoolean("enabled"))
        assertFalse(rig.engine.status("com.other.app").getJSONObject("uses").getJSONObject("ui").getBoolean("allowed_for_app"))
        assertEquals(0, s.getJSONArray("rejected").length())
        assertFalse(Rig(token = null).engine.status(Fixtures.APP).getBoolean("token"))
        val withBad = Rig(Fixtures.block { it.getJSONObject("uses").getJSONObject("ui").put("threshold", 9) })
        assertEquals("ui", withBad.engine.status(Fixtures.APP).getJSONArray("rejected").getString(0))
    }

    @Test fun theRealPolicyServesItsProbeEndToEnd() {
        val rig = Rig(Fixtures.realManifest())
        val probeApp = "com.diegonmarcos.cloudlib.decisionsengine"
        val r = rig.engine.decide(probeApp, Fixtures.request("probe", JSONObject().put("statement", "2+2=4"), Fixtures.noulQ("correct")))
        assertTrue(r.toString(), r.getBoolean("ok"))
        assertEquals("background", r.getString("class"))
        assertEquals(Fixtures.TOKEN, rig.http.tokens.single())
        assertEquals("https://openrouter.ai/api/alpha/decisions", rig.http.endpoints.single())
        assertEquals("typesafe/jev-1.13", rig.http.sent.single().getString("model"))
        assertTrue(rig.engine.decide(probeApp, Fixtures.request("probe", JSONObject().put("statement", "2+2=4"), Fixtures.noulQ("correct"))).getBoolean("cached"))
        assertEquals("app_not_allowed", reason(rig.engine.decide(Fixtures.APP, Fixtures.request("probe"))))
        assertNotNull(rig.policy.uses["probe"])
        assertNull(rig.policy.rejected["probe"])
    }
}
