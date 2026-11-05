package com.diegonmarcos.superapp.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1504 The parsers behind the suspects: durations, uid names, the power block, per-uid counters. */
class XrayAnalyzeTest {
    @Test fun `durations and uid names`() {
        assertEquals(3_723_004L, XrayAnalyze.durMs("1h 2m 3s 4ms"))
        assertEquals(1_234L, XrayAnalyze.durMs("+1s234ms"))
        assertEquals(10_123, XrayAnalyze.uidOf("u0a123")); assertEquals(1_010_005, XrayAnalyze.uidOf("u10a5")); assertEquals(1000, XrayAnalyze.uidOf("1000"))
        assertNull(XrayAnalyze.uidOf("nonsense"))
    }

    @Test fun `the start reasons are counted per process and reason`() {
        val m = XrayAnalyze.startReasons("I/ActivityManager: Start proc 1:com.a/u0a1 for content provider {com.a/.P}\nI/ActivityManager: Start proc 2:com.a/u0a1 for content provider {com.a/.P}\n")
        assertEquals(2, m[("com.a" to "content provider")])
    }

    @Test fun `a uid block ends at the next two-space header`() {
        val s = XrayAnalyze.uidStats("  u0a1:\n    Wake lock w: 1m 0s 0ms (2 times)\n  Statistics:\n    Wake lock z: 9m 0s 0ms\n")
        assertEquals(60_000L, s.getValue("u0a1").wakeMs)
        assertEquals("w", s.getValue("u0a1").topWake)
    }

    @Test fun `a dump with no power block ranks nothing`() {
        assertEquals(emptyList<Suspect>(), XrayAnalyze.suspects(mapOf("batterystats" to "nothing here")))
    }
}
