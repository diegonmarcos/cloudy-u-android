package com.diegonmarcos.cloudcalc.decide

import com.diegonmarcos.superapp.decisions.Decisions
import com.diegonmarcos.superapp.decisions.EngineKeyedHttp
import com.diegonmarcos.superapp.decisions.Http
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1801 Calc's Jev section holds no key: its transport is an EngineKeyedHttp, so a Decisions call goes out with no token
 * (the AI-key owner engine adds it, use calc_jev), and an engine refusal is the call's failure, the reason kept, so the
 * section falls back to today's on-device behaviour (JevAppTest `no token still answers offline, as typed`).
 */
class JevRelayTest {

    @Test
    fun `the default transport is keyed by the engine`() {
        assertTrue(JevStore.http is EngineKeyedHttp)
        assertEquals("calc_jev", JevStore.USE)
    }

    @Test
    fun `a Decisions call through a keyed transport sends no token, and a refusal is a failure with its reason`() {
        val tokens = ArrayList<String?>()
        val ok = object : EngineKeyedHttp {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response {
                tokens.add(token)
                return Http.Response(200, """{"answers":{"q":{"type":"noul","noul":0.9}}}""")
            }
        }
        val q = JSONObject().put("q", JSONObject().put("type", "noul").put("instructions", "x"))
        val d = Decisions.post("https://openrouter.ai/api/alpha/decisions", 1000, ok, null, "m/x", JSONObject(), q)
        assertNull(d.error.ifBlank { null })
        assertEquals(listOf<String?>(null), tokens)
        val refused = object : EngineKeyedHttp {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response =
                throw java.io.IOException("No OpenRouter key in the AI keys engine")
        }
        val f = Decisions.post("https://openrouter.ai/api/alpha/decisions", 1000, refused, null, "m/x", JSONObject(), q)
        assertTrue(f.error, f.error.contains("No OpenRouter key"))
        val plain = object : Http {
            override fun send(url: String, token: String?, body: String?, timeoutMs: Int): Http.Response = throw AssertionError("not sent")
        }
        assertFalse("a plain transport still refuses to send without a token",
            Decisions.post("https://x.test", 1000, plain, null, "m/x", JSONObject(), q).error.isBlank())
    }

    @Test
    fun `the engine serves Calc's use on the endpoints it names`() {
        val u = JSONObject(File("../../fleet-private-data/decisions.json").readText())
            .getJSONObject("relay").getJSONObject("uses").getJSONObject(JevStore.USE)
        assertTrue(u.getJSONArray("apps").toString().contains("com.diegonmarcos.cloudcalc"))
        for (e in listOf("decisions", "chat", "key")) assertTrue(e, u.getJSONArray("endpoints").toString().contains(e))
    }
}
