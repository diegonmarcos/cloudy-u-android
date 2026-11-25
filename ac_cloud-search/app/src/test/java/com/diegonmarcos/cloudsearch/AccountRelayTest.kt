package com.diegonmarcos.cloudsearch

import com.diegonmarcos.cloudsearch.core.agents.LlmRequest
import com.diegonmarcos.cloudsearch.core.agents.RelayLlm
import com.diegonmarcos.cloudsearch.data.Account
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * #1801 Cloud Search holds no key: the agents' completion goes through the relay (use search_ai_chat) with a body that
 * carries none, the provider's answer is read as before, and an engine refusal (no engine, no key) is the run's error in
 * the engine's words, where the missing token used to be reported. The chat's relay is SearchShellTest's
 * chatWithoutATokenSaysWhyAndSendsNothing.
 */
class AccountRelayTest {

    private val req = LlmRequest("m/x", "be brief", "hello", 64)

    @Test
    fun `a relayed completion carries no key and reads the answer`() {
        val bodies = ArrayList<String>()
        val r = RelayLlm { b -> bodies.add(b); RelayLlm.Relayed(200, """{"choices":[{"message":{"content":"hi"}}],"usage":{"prompt_tokens":3,"completion_tokens":1,"cost":0.001}}""") }
            .complete(req)
        assertEquals("hi", r.text)
        assertNull(r.error)
        assertEquals(3, r.promptTokens)
        assertEquals(1, r.completionTokens)
        assertEquals(0.001, r.costUsd!!, 0.0)
        val b = JSONObject(bodies.single())
        assertEquals("m/x", b.getString("model"))
        assertEquals(64, b.getInt("max_tokens"))
        assertFalse(bodies.single().contains("Bearer") || bodies.single().contains("Authorization"))
    }

    @Test
    fun `a refusal is the run's error and a provider error is read as before`() {
        assertEquals("No OpenRouter key in the AI keys engine",
            RelayLlm { RelayLlm.Relayed(0, "", "No OpenRouter key in the AI keys engine") }.complete(req).error)
        val e = RelayLlm { RelayLlm.Relayed(402, """{"error":{"message":"Insufficient credits"}}""") }.complete(req)
        assertNull(e.text)
        assertTrue(e.error.orEmpty(), e.error.orEmpty().contains("Insufficient credits"))
        assertTrue(RelayLlm { throw IllegalStateException("x") }.complete(req).error!!.contains("IllegalStateException"))
    }

    @Test
    fun `the declared use is the one the engine serves Search`() {
        assertEquals("search_ai_chat", Account.USE)
        assertEquals("chat", Account.ENDPOINT)
        val u = JSONObject(File("../../fleet-private-data/decisions.json").readText())
            .getJSONObject("relay").getJSONObject("uses").getJSONObject(Account.USE)
        assertTrue(u.getJSONArray("apps").toString().contains("com.diegonmarcos.cloudsearch"))
        assertTrue(u.getJSONArray("endpoints").toString().contains(Account.ENDPOINT))
    }
}
