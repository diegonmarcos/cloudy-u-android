package com.diegonmarcos.superapp.decisions.link

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1801 the relay's reply, read without trusting a shape that crossed a process. */
class RelayReplyTest {
    @Test fun anAnswerCarriesTheProvidersCodeAndBody() {
        val r = RelayReply.parse(JSONObject().put("ok", true).put("code", 401).put("cost", 0.002), "{\"error\":{}}")
        assertTrue(r.ok); assertEquals(401, r.code); assertEquals("{\"error\":{}}", r.body); assertEquals(0.002, r.cost!!, 0.0)
        assertEquals("", r.reason)
    }

    @Test fun aRefusalCarriesTheReasonAndItsSentence() {
        val r = RelayReply.parse(JSONObject().put("ok", false).put("reason", "no_key").put("message", "No OpenRouter key"), null)
        assertFalse(r.ok); assertEquals("no_key", r.reason); assertEquals("No OpenRouter key", r.why); assertEquals("", r.body)
        val d = RelayReply.parse(JSONObject().put("ok", false).put("reason", Outcome.NO_ENGINE).put("detail", "install it"), null)
        assertEquals("install it", d.why)
        assertEquals("x", RelayReply.parse(JSONObject().put("ok", false).put("reason", "x"), null).why)
    }

    @Test fun anOkWithoutABodyOrCodeIsMalformed() {
        assertEquals(Outcome.MALFORMED, RelayReply.parse(JSONObject().put("ok", true).put("code", 200), null).reason)
        assertEquals(Outcome.MALFORMED, RelayReply.parse(JSONObject().put("ok", true), "{}").reason)
        assertNull(RelayReply.parse(JSONObject(), null).message)
    }

    @Test fun endpointNamesNotUrls() {
        assertEquals("decisions", RelayReply.endpointOf("https://openrouter.ai/api/alpha/decisions"))
        assertEquals("chat", RelayReply.endpointOf("https://openrouter.ai/api/v1/chat/completions"))
    }
}
