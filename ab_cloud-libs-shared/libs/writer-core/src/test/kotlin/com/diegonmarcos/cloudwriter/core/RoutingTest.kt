package com.diegonmarcos.cloudwriter.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingTest {
    private var modelCalls = 0
    private var mlCalls = 0
    private fun model(o: Outcome): () -> Outcome = { modelCalls++; o }
    private fun ml(o: Outcome): () -> Outcome = { mlCalls++; o }

    @Test fun `route ids parse, unknown and null fall back`() {
        assertEquals(Route.ML, Route.of("ml", Route.MODEL))
        assertEquals(Route.MODEL, Route.of("model", Route.ML))
        assertEquals(Route.ML, Route.of(null, Route.ML))
        assertEquals(Route.MODEL, Route.of("jev", Route.MODEL))
        assertEquals("model", Route.MODEL.id)
        assertEquals("ml", Route.ML.id)
    }

    @Test fun `an empty transcript is an answer, a failure is not`() {
        assertTrue(Outcome.ok("").ok)
        assertFalse(Outcome.failed("x").ok)
        assertEquals("x", Outcome.failed("x").error)
        assertNull(Outcome.ok("t").error)
    }

    @Test fun `the model route is blocked offline, without a token, or without a model`() {
        assertEquals(Routing.OFFLINE, Routing.modelBlocker(online = false, hasToken = true, model = "m"))
        assertEquals(Routing.OFFLINE, Routing.modelBlocker(online = false, hasToken = false, model = null))
        assertEquals(Routing.NO_TOKEN, Routing.modelBlocker(online = true, hasToken = false, model = "m"))
        assertEquals(Routing.NO_MODEL, Routing.modelBlocker(online = true, hasToken = true, model = ""))
        assertEquals(Routing.NO_MODEL, Routing.modelBlocker(online = true, hasToken = true, model = null))
        assertNull(Routing.modelBlocker(online = true, hasToken = true, model = "m"))
    }

    @Test fun `on-device answers on-device and never spends the model`() {
        val a = Routing.run(Route.ML, null, model(Outcome.ok("m")), ml(Outcome.ok("d")))
        assertEquals("d", a.text)
        assertEquals(Route.ML, a.route)
        assertEquals(Route.ML, a.requested)
        assertFalse(a.fellBack)
        assertNull(a.fallbackReason)
        assertNull(a.error)
        assertEquals(0, modelCalls)
        assertEquals(1, mlCalls)
    }

    @Test fun `an on-device failure is reported, not billed to a provider`() {
        val a = Routing.run(Route.ML, null, model(Outcome.ok("m")), ml(Outcome.failed("no engine")))
        assertFalse(a.ok)
        assertNull(a.route)
        assertEquals("on-device: no engine", a.error)
        assertEquals(0, modelCalls)
        val b = Routing.run(Route.ML, null, model(Outcome.ok("m")), ml(Outcome(null, null)))
        assertEquals("on-device: " + Routing.EMPTY, b.error)
    }

    @Test fun `the model answers when it can, and on-device is not touched`() {
        val a = Routing.run(Route.MODEL, null, model(Outcome.ok("m")), ml(Outcome.ok("d")))
        assertEquals("m", a.text)
        assertEquals(Route.MODEL, a.route)
        assertFalse(a.fellBack)
        assertNull(a.fallbackReason)
        assertEquals(1, modelCalls)
        assertEquals(0, mlCalls)
    }

    @Test fun `a blocked model falls back without being called, and says why`() {
        val a = Routing.run(Route.MODEL, Routing.NO_TOKEN, model(Outcome.ok("m")), ml(Outcome.ok("d")))
        assertEquals("d", a.text)
        assertEquals(Route.ML, a.route)
        assertEquals(Route.MODEL, a.requested)
        assertTrue(a.fellBack)
        assertEquals(Routing.NO_TOKEN, a.fallbackReason)
        assertNull(a.error)
        assertEquals(0, modelCalls)
        assertEquals(1, mlCalls)
    }

    @Test fun `a failing model falls back with the reason the model gave`() {
        val a = Routing.run(Route.MODEL, null, model(Outcome.failed("HTTP 500: x")), ml(Outcome.ok("d")))
        assertEquals("d", a.text)
        assertEquals(Route.ML, a.route)
        assertEquals("HTTP 500: x", a.fallbackReason)
        assertEquals(1, modelCalls)
        val b = Routing.run(Route.MODEL, null, model(Outcome(null, null)), ml(Outcome.ok("d")))
        assertEquals(Routing.EMPTY, b.fallbackReason)
    }

    @Test fun `both routes failing names both reasons`() {
        val a = Routing.run(Route.MODEL, Routing.OFFLINE, model(Outcome.ok("m")), ml(Outcome.failed("no engine")))
        assertFalse(a.ok)
        assertFalse(a.fellBack)
        assertNull(a.route)
        assertEquals(Routing.OFFLINE, a.fallbackReason)
        assertEquals("model: offline; on-device: no engine", a.error)
        val b = Routing.run(Route.MODEL, null, model(Outcome.failed("t")), ml(Outcome(null, null)))
        assertEquals("model: t; on-device: " + Routing.EMPTY, b.error)
    }

    @Test fun `the json carries the answering route and nulls for the absent`() {
        val ok = Routing.run(Route.MODEL, Routing.OFFLINE, model(Outcome.ok("m")), ml(Outcome.ok("d"))).toJson()
        assertTrue(ok.getBoolean("ok"))
        assertEquals("d", ok.getString("text"))
        assertEquals("ml", ok.getString("route"))
        assertEquals("model", ok.getString("requested"))
        assertTrue(ok.getBoolean("fell_back"))
        assertEquals("offline", ok.getString("fallback_reason"))
        assertTrue(ok.isNull("error"))
        val bad = Routing.run(Route.ML, null, model(Outcome.ok("m")), ml(Outcome.failed("e"))).toJson()
        assertFalse(bad.getBoolean("ok"))
        assertTrue(bad.isNull("text"))
        assertTrue(bad.isNull("route"))
        assertTrue(bad.isNull("fallback_reason"))
        assertEquals("on-device: e", bad.getString("error"))
    }
}
