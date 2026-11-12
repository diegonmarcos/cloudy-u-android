package com.diegonmarcos.cloudcalc

import android.content.ComponentName
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.diegonmarcos.clocktools.ClockEngine
import com.diegonmarcos.cloudcalc.decide.JevFlow
import com.diegonmarcos.cloudcalc.decide.JevStore
import com.diegonmarcos.cloudcalc.engine.CalcApi
import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.cloudcalc.jev.JevConfig
import com.diegonmarcos.cloudcalc.ui.CalcShell
import com.diegonmarcos.cloudcalc.ui.CalcState
import com.diegonmarcos.cloudcalc.ui.CalcTags
import com.diegonmarcos.cloudcalc.ui.CalcTheme
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.junit.runners.model.Statement
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The Jev section in the real app (#770), against a FAKE Decisions transport and a fake engine:
 * the route → the app's own engine computes → history; low confidence → candidates; every
 * failure (no token, HTTP error) → the calculator still answers, offline; the routing table
 * and the token override round-trip through their stores; a result's follow-up question is kept.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class JevAppTest {
    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val rules: RuleChain = RuleChain.outerRule(TestRule { base, _ ->
        object : Statement() {
            override fun evaluate() {
                val app = RuntimeEnvironment.getApplication()
                shadowOf(app.packageManager).addActivityIfNotPresent(ComponentName(app, ComponentActivity::class.java))
                base.evaluate()
            }
        }
    }).around(compose)

    private class FakeEngine : CalcApi {
        val evals = mutableListOf<String>()
        override fun info() = """{"ok":true}"""
        override fun eval(expr: String, options: String): String {
            synchronized(evals) { evals += expr }
            return JSONObject().put("ok", true).put("result", if (expr == "2+2") "4" else "42").put("messages", JSONArray()).toString()
        }
        override fun plot(expr: String, xmin: Double, xmax: Double, steps: Int) = """{"ok":true,"x":[],"y":[]}"""
        override fun complete(prefix: String, max: Int) = "[]"
        override fun items(kind: String, category: String, max: Int) = "[]"
        override fun ratesInfo() = "{}"
        override fun fetchRates() = "{}"
    }

    /** Records each request; answers what the test scripted per URL. */
    private class FakeHttp : Http {
        val sent = mutableListOf<Triple<String, String?, String?>>()
        @Volatile var reply: (String, JSONObject?) -> Http.Response = { _, _ -> Http.Response(500, "{}") }
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            synchronized(sent) { sent += Triple(url, token, body) }
            return reply(url, body?.let { JSONObject(it) })
        }
    }

    private val engine = FakeEngine()
    private val http = FakeHttp()
    private val state = CalcState(null)
    private val app: Context get() = RuntimeEnvironment.getApplication()
    /** Fake keys are assembled at run time, so no key-shaped literal trips the leak scan. */
    private fun fakeKey(tail: String) = listOf("sk", "or", tail).joinToString("-")
    private val accountKey = fakeKey("account-0000aaaa")
    private val manualKey = fakeKey("manual-1111bbbb")
    private var accountToken: String? = accountKey
    private val plainSecrets by lazy { app.getSharedPreferences("test_secrets", Context.MODE_PRIVATE) }

    private lateinit var savedHttp: Http
    private lateinit var savedAccount: (Context, String) -> Pair<String?, String>
    private lateinit var savedSecrets: (Context) -> android.content.SharedPreferences?

    @Before fun up() {
        savedHttp = JevStore.http; savedAccount = JevStore.account; savedSecrets = JevStore.secrets
        JevStore.http = http
        JevStore.account = { _, provider -> (if (provider == "openrouter") accountToken else null) to "fleet Account: none" }
        JevStore.secrets = { plainSecrets }
    }

    @After fun down() {
        JevStore.http = savedHttp; JevStore.account = savedAccount; JevStore.secrets = savedSecrets
    }

    private fun route(choice: String, probs: Map<String, Double>) = Http.Response(200, JSONObject()
        .put("id", "gen-x")
        .put("answers", JSONObject().put("route", JSONObject().put("type", "choice").put("choice", choice).put("probabilities", JSONObject(probs))))
        .put("usage", JSONObject().put("cost", 0.00001)).toString())

    private val decisionsUrl get() = JevStore.defaults.endpoint

    private fun openJev() {
        compose.setContent { CalcTheme { CalcShell(engine, state) } }
        val jev = Declarations.modes.first { it.kind == "jev" }
        compose.runOnIdle { state.tab = jev.tab; state.modeByTab[jev.tab] = jev.id }
        compose.waitForIdle()
    }

    /** Typed through state.pending, not performTextInput: a focused field's blinking cursor never lets Compose idle (#768). */
    private fun ask(text: String) {
        val jev = Declarations.modes.first { it.kind == "jev" }
        compose.runOnIdle { state.pending = jev.id to text }
        compose.waitForIdle()
        compose.onNodeWithTag(CalcTags.JEV_INPUT).assertTextContains(text)
        compose.onNodeWithTag(CalcTags.JEV_ASK).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag(CalcTags.JEV_VERDICT).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
    }

    private fun evaluated(expr: String) = synchronized(engine.evals) { expr in engine.evals }

    @Test fun `a confident route runs the tool on the app's engine and keeps it in history`() {
        http.reply = { url, body ->
            assertEquals(decisionsUrl, url)
            assertEquals(JevStore.defaults.model("route"), body!!.getString("model"))
            route("units", mapOf("units" to 0.93, "arithmetic" to 0.05, "none" to 0.02))
        }
        openJev()
        ask("convert 3 ft to cm")
        compose.onNodeWithTag(CalcTags.JEV_VERDICT).assertTextContains("units", substring = true)
        assertTrue(evaluated("3 ft to cm"))
        // The run block is a Column of Texts; its own node carries no text, so find the line itself.
        compose.onNodeWithText("= 42").assertExists()
        assertEquals(Logic.Entry("jev", "convert 3 ft to cm", "42"), state.history.first().copy(ts = 0L))
        // The account token went out as the bearer, and nowhere into a body.
        val (_, token, body) = http.sent.first()
        assertEquals(accountKey, token)
        assertFalse(body!!.contains("sk-or-account"))
    }

    @Test fun `low confidence offers candidates and a tap computes the picked one`() {
        http.reply = { _, _ -> route("tip", mapOf("tip" to 0.4, "units" to 0.3, "none" to 0.3)) }
        openJev()
        ask("convert 3 ft to cm")
        compose.onNodeWithTag(CalcTags.JEV_VERDICT).assertTextContains("Pick one", substring = true)
        assertFalse(evaluated("3 ft to cm"))
        compose.onNodeWithTag(CalcTags.jevCandidate("units")).performClick()
        compose.waitUntil(10_000) { evaluated("3 ft to cm") }
    }

    @Test fun `no token still answers offline, as typed`() {
        accountToken = null
        openJev()
        ask("convert 3 ft to cm")
        compose.onNodeWithTag(CalcTags.JEV_VERDICT).assertTextContains("unavailable", substring = true)
        assertTrue(evaluated("3 ft to cm"))
        assertTrue(http.sent.none { it.first == decisionsUrl })
    }

    @Test fun `an HTTP error still answers offline, as typed`() {
        http.reply = { _, _ -> Http.Response(503, """{"error":{"message":"down for maintenance"}}""") }
        openJev()
        ask("convert 3 ft to cm")
        compose.onNodeWithTag(CalcTags.JEV_VERDICT).assertTextContains("down for maintenance", substring = true)
        assertTrue(evaluated("3 ft to cm"))
    }

    @Test fun `a timer request starts a real Clock timer, and the debug path does not`() {
        http.reply = { _, _ -> route("timer", mapOf("timer" to 0.97, "none" to 0.03)) }
        val dry = JevFlow.ask(app, engine, "start a 10 min timer", startTimer = false)
        assertEquals("timer", dry.outcome.tool!!.id)
        assertTrue(ClockEngine.load(app).timers.isEmpty())
        val run = JevFlow.ask(app, engine, "start a 10 min timer", startTimer = true)
        assertEquals("", run.run!!.error)
        assertEquals(600_000L, ClockEngine.load(app).timers.single().durationMs)
    }

    @Test fun `a form tool asks which number is which field, then the engine fills the form`() {
        http.reply = { _, body ->
            val qs = body!!.getJSONObject("questions")
            if (qs.has("route")) route("tip", mapOf("tip" to 0.9, "none" to 0.1))
            else Http.Response(200, JSONObject().put("answers", JSONObject().apply {
                // "what's 15% tip on 42": n1 = 15, n2 = 42.
                for ((field, pick) in mapOf("bill" to "n2", "tip" to "n1", "people" to "none")) {
                    put(field, JSONObject().put("type", "choice").put("choice", pick).put("probabilities", JSONObject().put(pick, 0.9)))
                }
            }).toString())
        }
        val a = JevFlow.ask(app, engine, "what's 15% tip on 42", startTimer = false)
        val tip = Declarations.mode("finance")!!.forms.first { it.id == "tip" }
        val first = Logic.fill(tip.outputs.first().expr, mapOf("bill" to "42", "tip" to "15", "people" to tip.fields.first { it.id == "people" }.default))
        assertTrue("engine asked ${engine.evals}", evaluated(first))
        assertTrue(a.run!!.slots!!.ok)
    }

    @Test fun `the routing table round-trips through its store, and a broken edit is refused`() {
        val cfg = JevStore.config(app)
        assertFalse(JevStore.isEdited(app))
        val edited = JSONObject(JevStore.configJson(app)).apply { getJSONObject("route").put("threshold", 0.9) }.toString()
        assertNull(JevStore.saveConfig(app, edited))
        assertTrue(JevStore.isEdited(app))
        assertEquals(0.9, JevStore.config(app).route.threshold, 0.0)
        assertEquals(edited, JevStore.configJson(app))
        // Refused: not JSON, a bad threshold, a tool naming a mode this app does not have.
        assertNotNull(JevStore.saveConfig(app, "{"))
        assertNotNull(JevStore.saveConfig(app, JSONObject(edited).apply { getJSONObject("route").put("threshold", 2) }.toString()))
        val ghost = JSONObject(edited).apply {
            getJSONObject("route").getJSONObject("tools").put("ghost", JSONObject().put("criterion", "x").put("action", "open").put("mode", "nowhere"))
        }.toString()
        assertTrue(JevStore.saveConfig(app, ghost)!!.contains("nowhere"))
        assertEquals(0.9, JevStore.config(app).route.threshold, 0.0)
        JevStore.resetConfig(app)
        assertFalse(JevStore.isEdited(app))
        assertEquals(cfg, JevStore.config(app))
    }

    @Test fun `the shipped routing table names only this app's modes and forms`() {
        assertEquals(emptyList<String>(), JevStore.appErrors(JevStore.defaults))
    }

    @Test fun `the manual token overrides the Account's while set, and the screen masks it`() {
        assertEquals(JevStore.Token(accountKey, JevStore.SOURCE_ACCOUNT), JevStore.token(app))
        assertTrue(JevStore.setManualToken(app, "  $manualKey  "))
        assertEquals(JevStore.Token(manualKey, JevStore.SOURCE_MANUAL), JevStore.token(app))
        assertTrue(JevStore.setManualToken(app, null))
        assertEquals(JevStore.SOURCE_ACCOUNT, JevStore.token(app).source)
        accountToken = null
        assertNull(JevStore.token(app).value)
        JevStore.secrets = { null }
        assertFalse(JevStore.setManualToken(app, "x"))

        JevStore.secrets = { plainSecrets }
        JevStore.setManualToken(app, manualKey)
        compose.setContent { CalcTheme { CalcShell(engine, state) } }
        val tokenMode = Declarations.modes.first { it.kind == "jev_token" }
        compose.runOnIdle { state.tab = tokenMode.tab; state.modeByTab[tokenMode.tab] = tokenMode.id }
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(CalcTags.JEV_TOKEN).assertTextContains("sk-or-…bbbb", substring = true) }.isSuccess
        }
        compose.onNodeWithTag(CalcTags.JEV_TOKEN).assertTextContains(JevStore.SOURCE_MANUAL, substring = true)
    }

    @Test fun `Test reports what OpenRouter says about the key, never the key`() {
        http.reply = { url, _ ->
            if (url == JevStore.defaults.keyUrl) Http.Response(200, JSONObject().put("data", JSONObject()
                .put("label", fakeKey("v1-abc...xyz")).put("usage", 0.25).put("limit", JSONObject.NULL).put("is_free_tier", false)).toString())
            else Http.Response(500, "{}")
        }
        val said = JevFlow.testToken(app)
        assertTrue(said, said.startsWith("OK"))
        assertTrue(said, said.contains("usage $0.25") && said.contains("no limit") && said.contains("paid tier"))
        assertFalse(said.contains("sk-or"))
        http.reply = { _, _ -> Http.Response(401, """{"error":{"message":"No auth credentials found"}}""") }
        assertTrue(JevFlow.testToken(app).contains("No auth credentials found"))
        accountToken = null
        assertTrue(JevFlow.testToken(app).startsWith("No token"))
    }

    @Test fun `a result's follow-up question is scored and kept with it in history`() {
        http.reply = { _, body ->
            val q = body!!.getJSONObject("questions")
            assertEquals("42", body.getJSONObject("state").getString("result"))
            val id = q.keys().next()
            Http.Response(200, JSONObject().put("answers", JSONObject().put(id, JSONObject().put("type", "score").put("score", 2.0)
                .put("probabilities", JSONObject().put("0", 0.1).put("1", 0.1).put("2", 0.7).put("3", 0.1))
                .put("legend", JSONObject().put("0", "Implausible").put("1", "Unusual").put("2", "Plausible").put("3", "Exactly")))).toString())
        }
        val q = JevStore.config(app).questionsFor("standard").first { it.type == JevConfig.SCORE }
        val d = JevFlow.askAbout(app, q, "standard", "6*7", "42", "")
        assertTrue(d.ok)
        assertEquals("Plausible", d.options(q.id).first().label)
        val record = JevFlow.decisionRecord(q, d)
        assertTrue(JevFlow.summary(record), JevFlow.summary(record).contains("Plausible 70%"))
        // The scoring model is the score use's, resolved — not the route's.
        assertEquals(JevStore.model(app, JevFlow.SCORE_USE), d.model)
    }

    @Test fun `the expression mode offers no follow-up box, so nothing result-dependent may move its keypad`() {
        http.reply = { _, body ->
            val id = body!!.getJSONObject("questions").keys().next()
            Http.Response(200, JSONObject().put("answers", JSONObject().put(id, JSONObject().put("type", "noul").put("noul", 0.8))).toString())
        }
        compose.setContent { CalcTheme { CalcShell(engine, state) } }
        val mode = Declarations.modes.first { it.kind == "expression" }
        compose.runOnIdle { state.tab = mode.tab; state.modeByTab[mode.tab] = mode.id }
        listOf("2", "+", "2").forEach { compose.onNodeWithTag(CalcTags.key(it)).performClick() }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodesWithTag(CalcTags.ASK_TOGGLE).fetchSemanticsNodes().size)
    }

    @Test fun `the debug route answers the decision and the result without the token`() {
        http.reply = { _, _ -> route("units", mapOf("units" to 0.93, "none" to 0.07)) }
        val j = JevFlow.ask(app, engine, "convert 3 ft to cm", startTimer = false).toJson()
        assertEquals("routed", j.getJSONObject("outcome").getString("kind"))
        assertEquals("units", j.getJSONObject("outcome").getString("tool"))
        assertEquals("42", j.getJSONObject("run").getString("result"))
        assertFalse(j.toString().contains("sk-or-account"))
        val c = com.diegonmarcos.cloudcalc.debugapi.JevDebugApi.config(app)
        assertFalse(c.toString().contains("sk-or-account"))
        assertEquals("sk-or-…aaaa", c.getJSONObject("token").getString("masked"))
        assertTrue(c.getJSONObject("config").has("route"))
    }
}
