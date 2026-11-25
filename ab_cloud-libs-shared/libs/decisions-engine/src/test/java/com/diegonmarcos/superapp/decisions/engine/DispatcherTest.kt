package com.diegonmarcos.superapp.decisions.engine

import com.diegonmarcos.superapp.decisions.Http
import com.diegonmarcos.superapp.decisions.core.AnswerCache
import com.diegonmarcos.superapp.decisions.core.CircuitBreaker
import com.diegonmarcos.superapp.decisions.core.ConsentBook
import com.diegonmarcos.superapp.decisions.core.Engine
import com.diegonmarcos.superapp.decisions.core.Env
import com.diegonmarcos.superapp.decisions.core.ImageEngine
import com.diegonmarcos.superapp.decisions.core.Journal
import com.diegonmarcos.superapp.decisions.core.KeyBook
import com.diegonmarcos.superapp.decisions.core.RelayEngine
import com.diegonmarcos.superapp.decisions.core.Ledger
import com.diegonmarcos.superapp.decisions.core.MemorySink
import com.diegonmarcos.superapp.decisions.core.MemoryStore
import com.diegonmarcos.superapp.decisions.core.Policy
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Base64

/**
 * The service's three methods over the engine that ships: the REAL decisions.json (so a declaration that
 * stops parsing, or a probe that stops being servable, turns this red), a fake network and no Android.
 */
class DispatcherTest {
    private val self = "com.diegonmarcos.cloudlib.decisionsengine"
    private val policy = Policy.parse(JSONObject(File("../../../fleet-private-data/decisions.json").readText()))
    private var posts = 0
    private val http = object : Http {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            posts++
            val q = JSONObject(body!!).getJSONObject("questions")
            val answers = JSONObject()
            for (id in q.keys()) answers.put(id, JSONObject().put("type", "noul").put("noul", 0.97))
            return Http.Response(200, JSONObject().put("answers", answers).toString())
        }
    }
    private val env = object : Env {
        override fun online() = true
        override fun metered() = false
        override fun batterySaver() = false
    }
    private val clock = { 1_800_000_000_000L }
    private val engine = Engine(policy, http, { "token" }, env, Ledger(MemoryStore(), policy.budget, clock),
        AnswerCache(MemoryStore(), policy.cacheMax, clock), CircuitBreaker(3, 1000, clock), ConsentBook(MemoryStore()),
        Journal(MemorySink(), policy.journalMax, clock), clock)
    private val d = Dispatcher { engine }

    private val probe = JSONObject().put("use", "probe").put("state", JSONObject().put("statement", "2 + 2 = 4"))
        .put("questions", JSONObject().put("correct", JSONObject().put("type", "noul").put("instructions", "It is correct.")))

    @Test fun decideRunsTheEngineForTheCallerTheBinderNamed() {
        val ok = JSONObject(d.decide(self, probe.toString()))
        assertTrue(ok.toString(), ok.getBoolean("ok"))
        assertEquals(1, posts)
        val other = JSONObject(d.decide("com.some.other.app", probe.toString()))
        assertFalse(other.getBoolean("ok"))
        assertEquals("app_not_allowed", other.getString("reason"))
        assertEquals("app_not_allowed", JSONObject(d.decide("", probe.toString())).getString("reason"))
        assertEquals(1, posts)
    }

    @Test fun statusIsAnsweredForTheCaller() {
        val s = JSONObject(d.status(self))
        assertTrue(s.getBoolean("ok"))
        assertTrue(s.getJSONObject("uses").getJSONObject("probe").getBoolean("allowed_for_app"))
        assertFalse(JSONObject(d.status("com.some.other.app")).getJSONObject("uses").getJSONObject("probe").getBoolean("allowed_for_app"))
    }

    @Test fun consentIsAcceptedFromTheSettingAppOnly() {
        val req = JSONObject().put("app", self).put("use", "probe").put("granted", false).toString()
        assertEquals("not_a_consent_setter", JSONObject(d.consent(self, req)).getString("reason"))
        assertTrue(JSONObject(d.consent("com.diegonmarcos.superapp", req)).getBoolean("ok"))
        // the probe is implicit-consent, but a recorded NO is respected
        assertEquals("no_consent", JSONObject(d.decide(self, probe.toString())).getString("reason"))
    }

    @Test fun withoutAPolicyEveryMethodIsNoPolicy() {
        val none = Dispatcher { null }
        for (reply in listOf(none.decide(self, "{}"), none.status(self), none.consent(self, "{}"))) {
            val r = JSONObject(reply)
            assertFalse(r.getBoolean("ok"))
            assertEquals("no_policy", r.getString("reason"))
        }
    }

    @Test fun outcomeRunsTheEngineForTheCallerTheBinderNamed() {
        val r = JSONObject().put("use", "probe").put("call", "abc123").put("label", true)
        assertTrue(JSONObject(d.outcome(self, r.toString())).getBoolean("ok"))
        assertEquals("app_not_allowed", JSONObject(d.outcome("com.some.other.app", r.toString())).getString("reason"))
        assertEquals("no_policy", JSONObject(Dispatcher { null }.outcome(self, r.toString())).getString("reason"))
    }

    // ── #1799 the image method over the shipped declaration ──────────────────────────────

    private val canvas = "com.diegonmarcos.cloudcanvas"
    private val picture = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 5, 6)
    private val returned = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 8, 8, 8)
    private var imagePosts = 0
    private var imageToken: String? = null
    private val imageHttp = object : Http {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            imagePosts++
            imageToken = token
            val url64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(returned)
            return Http.Response(200, JSONObject().put("choices", org.json.JSONArray().put(JSONObject().put("message", JSONObject()
                .put("images", org.json.JSONArray().put(JSONObject().put("image_url", JSONObject().put("url", url64)))))))
                .put("usage", JSONObject().put("cost", 0.04)).toString())
        }
    }
    private val consent = ConsentBook(MemoryStore())
    private val journal = Journal(MemorySink(), policy.journalMax, clock)
    private val images = ImageEngine(policy.image, imageHttp, { "token" }, env,
        Ledger(MemoryStore(), policy.image!!.budget, clock), CircuitBreaker(3, 1000, clock), consent, journal, clock)
    private val di = Dispatcher({ engine }, { images })
    private val enhance = JSONObject().put("use", "canvas_enhance").put("model", "google/gemini-2.5-flash-image").put("prompt", "Make it a watercolour.")

    @Test fun imageRunsTheCanvasEnhancerForCanvasOnlyWithTheTokenHeldHere() {
        val a = di.image(canvas, enhance.toString(), picture)
        assertTrue(a.answer.toString(), a.answer.getBoolean("ok"))
        assertArrayEquals(returned, a.image)
        assertEquals("canvas_enhance", a.answer.getString("use"))
        assertEquals(returned.size, a.answer.getInt("bytes"))
        assertEquals("token", imageToken)
        assertFalse("the answer never carries the token", a.answer.toString().contains("token"))
        assertEquals(1, imagePosts)
        assertEquals("app_not_allowed", di.image("com.some.other.app", enhance.toString(), picture).answer.getString("reason"))
        assertEquals("app_not_allowed", di.image("", enhance.toString(), picture).answer.getString("reason"))
        assertEquals(1, imagePosts)
        assertNull(di.image("com.some.other.app", enhance.toString(), picture).image)
    }

    @Test fun imageThroughTheJsonCallIsRefusedAndWithoutAnImagePolicyIsNoPolicy() {
        assertEquals("bad_request", JSONObject(di.imageWithoutFiles()).getString("reason"))
        val none = Dispatcher({ engine })
        assertEquals("no_policy", none.image(canvas, enhance.toString(), picture).answer.getString("reason"))
        assertEquals("no_policy", Dispatcher({ null }, { null }).image(canvas, enhance.toString(), picture).answer.getString("reason"))
        assertEquals(0, imagePosts)
    }

    @Test fun statusCarriesTheImageUsesForTheCaller() {
        val s = JSONObject(di.status(canvas))
        val img = s.getJSONObject("image")
        assertTrue(img.getBoolean("served"))
        val u = img.getJSONObject("uses").getJSONObject("canvas_enhance")
        assertTrue(u.getBoolean("enabled"))
        assertTrue(u.getBoolean("allowed_for_app"))
        assertTrue(u.getBoolean("consent"))
        assertFalse(JSONObject(di.status(self)).getJSONObject("image").getJSONObject("uses").getJSONObject("canvas_enhance").getBoolean("allowed_for_app"))
        assertFalse(JSONObject(d.status(canvas)).has("image"))
        assertEquals(3, s.getInt("contract"))
    }

    @Test fun theManifestDeclaresTheContractThePolicyServes() {
        val mf = File("src/main/AndroidManifest.xml").readText()
        assertTrue(mf.contains("android:name=\"com.diegonmarcos.cloud.engine.CONTRACT\"\n                android:value=\"${Policy.CONTRACT}\""))
        val svc = File("src/main/java/com/diegonmarcos/superapp/decisions/engine/DecisionsEngineService.kt").readText()
        assertTrue(svc.contains("arrayOf(DECIDE, STATUS, CONSENT, OUTCOME, IMAGE, RELAY, KEYS, IMPORT)"))
        assertTrue(svc.contains("const val IMAGE = \"image\""))
        assertTrue(svc.contains("const val RELAY = \"relay\""))
        assertTrue(svc.contains("const val KEYS = \"keys\""))
        assertTrue(svc.contains("const val IMPORT = \"import\""))
        // #1801 the AI keys screen every consumer links to, under the action DecisionsLink opens.
        assertTrue(mf.contains("android:name=\"com.diegonmarcos.superapp.decisions.engine.KeysActivity\""))
        assertTrue(mf.contains("<action android:name=\"\${applicationId}.AI_KEYS\" />"))
    }

    // ── #1801 the relay and the key book ──

    private val calc = "com.diegonmarcos.cloudcalc"
    private val keyBook = KeyBook(MemoryStore())
    private var relayToken: String? = null
    private var relayUrl: String? = null
    private val relayHttp = object : Http {
        override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
            relayToken = token
            relayUrl = url
            return Http.Response(200, "{\"answers\":{}}")
        }
    }
    private val relayEngine = RelayEngine(policy.relay, relayHttp, keyBook, env,
        Ledger(MemoryStore(), policy.relay!!.budget, clock), CircuitBreaker(3, 1000, clock), consent, journal, clock)
    private val dr = Dispatcher({ engine }, { images }, { relayEngine }, { keyBook })
    private val jev = JSONObject().put("use", "calc_jev").put("endpoint", "decisions").toString()

    @Test fun relayAddsTheKeyHeldHereForTheDeclaredAppOnly() {
        assertEquals("no_key", dr.relay(calc, jev, "{}".toByteArray()).answer.getString("reason"))
        keyBook.set("openrouter", "sk-test-0123456789abcdef")
        val a = dr.relay(calc, jev, "{\"model\":\"m\"}".toByteArray())
        assertTrue(a.answer.toString(), a.answer.getBoolean("ok"))
        assertEquals(200, a.answer.getInt("code"))
        assertEquals("sk-test-0123456789abcdef", relayToken)
        assertEquals("https://openrouter.ai/api/alpha/decisions", relayUrl)
        assertFalse(a.answer.toString().contains("sk-test"))
        assertEquals("app_not_allowed", dr.relay("com.some.other.app", jev, "{}".toByteArray()).answer.getString("reason"))
        assertEquals("bad_request", JSONObject(dr.relayWithoutFiles()).getString("reason"))
        assertEquals("no_policy", di.relay(calc, jev, "{}".toByteArray()).answer.getString("reason"))
        assertTrue(JSONObject(dr.status(calc)).getJSONObject("relay").getBoolean("served"))
        assertFalse(JSONObject(di.status(calc)).has("relay"))
    }

    @Test fun keysSaysPresenceAndHintNeverTheKey() {
        keyBook.set("openrouter", "sk-test-0123456789abcdef")
        val k = JSONObject(dr.keys("pkg.AI_KEYS"))
        assertTrue(k.getBoolean("ok"))
        assertEquals("pkg.AI_KEYS", k.getString("settings_action"))
        val p = k.getJSONArray("providers").getJSONObject(0)
        assertEquals("openrouter", p.getString("id"))
        assertEquals("OpenRouter", p.getString("label"))
        assertTrue(p.getBoolean("key_present"))
        assertEquals("cdef", p.getString("key_hint"))
        assertFalse(k.toString().contains("sk-test"))
        assertEquals("no_policy", JSONObject(di.keys("x")).getString("reason"))
        val noRelay = JSONObject(Dispatcher({ engine }, { images }, { null }, { keyBook }).keys("x"))
        assertEquals("openrouter", noRelay.getJSONArray("providers").getJSONObject(0).getString("id"))
    }

    @Test fun importMigratesAFormerCopyOnceTheEngineKeyWinsAndTheJournalCarriesNoValue() {
        val sink = MemorySink()
        val j = Journal(sink, 50, clock)
        val k = "sk-test-0123456789abcdef"
        val fleet = JSONObject(dr.importKey("com.diegonmarcos.cloudkeyboard", JSONObject().put("provider", "openrouter").put("value", k).put("scope", "fleet").toString(), j))
        assertTrue(fleet.getBoolean("ok")); assertEquals("stored", fleet.getString("result"))
        assertEquals(k, keyBook.get("openrouter"))
        val again = JSONObject(dr.importKey("com.diegonmarcos.cloudkeyboard", JSONObject().put("provider", "openrouter").put("value", "sk-other-0123456789abcdef").toString(), j))
        assertEquals("kept_engine", again.getString("result"))
        assertEquals(k, keyBook.get("openrouter"))
        val mine = JSONObject(dr.importKey(calc, JSONObject().put("provider", "openrouter").put("value", "sk-calc-0123456789abcdef").put("scope", "app").toString(), j))
        assertEquals("stored", mine.getString("result"))
        assertEquals("sk-calc-0123456789abcdef", keyBook.get("openrouter", calc))
        assertEquals(k, keyBook.get("openrouter", "com.some.other.app"))
        assertEquals("unknown_provider", JSONObject(dr.importKey(calc, JSONObject().put("provider", "nobody").put("value", k).toString(), j)).getString("reason"))
        assertEquals("app_not_allowed", JSONObject(dr.importKey("", JSONObject().put("provider", "openrouter").put("value", k).toString(), j)).getString("reason"))
        assertEquals("bad_request", JSONObject(dr.importKey(calc, JSONObject().put("provider", "openrouter").put("value", k).put("scope", "x").toString(), j)).getString("reason"))
        assertEquals("bad_request", JSONObject(dr.importKey(calc, JSONObject().put("provider", "openrouter").put("value", "").put("scope", "app").put("x", 1).toString(), j)).optString("reason", "kept"))
        assertEquals("no_policy", JSONObject(di.importKey(calc, "{}", j)).getString("reason"))
        assertTrue(sink.lines.isNotEmpty())
        assertFalse("the journal never carries the value", sink.lines.any { it.contains("sk-") })
    }
}
