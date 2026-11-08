package com.diegonmarcos.superapp.xray

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    private val DUMP = """
  Estimated power use (mAh):
    Capacity: 5000, Computed drain: 100, actual drain: 100-110
    Uid u0a150: 10.0 ( cpu=10.0 )
    Uid u0a151: 20.0 ( cpu=20.0 )

  u0a150:
    Wake lock Spin: 1m 0s 0ms (5000 times) realtime
    Wake lock Quiet: 30m 0s 0ms (2 times) realtime
    Cpu: 1m 0s 0ms usr + 0s 0ms krn
    Foreground activities: 0s 500ms
  u0a151:
    Wake lock Long: 40m 0s 0ms (3 times) realtime
    Cpu: 3m 0s 0ms usr + 1m 0s 0ms krn
"""
    private val PK = "package:com.spin uid:10150\npackage:com.heavy uid:10151\n"

    @Test fun `the wakelock acquire count counts, not only the held time`() {
        val w = XrayAnalyze.wakelocks(DUMP)
        assertEquals(listOf("Spin", "Quiet", "Long"), w.map { it.name })
        assertEquals(5000L, w.first().count)
        val s = XrayAnalyze.uidStats(DUMP).getValue("u0a150")
        assertEquals(5000L, s.topWakeCount); assertEquals("Spin", s.topWakeByCount); assertEquals("Quiet", s.topWake)
        assertEquals(59_500L, s.bgCpuMs)
    }

    @Test fun `a low-power wakelock spinner outranks a heavier power user and says why`() {
        val sus = XrayAnalyze.suspects(mapOf("batterystats" to DUMP, "packages" to PK))
        assertEquals(listOf("com.spin", "com.heavy"), sus.map { it.pkg })
        assertTrue("heavier on power, lower overall", sus[1].mAh > sus[0].mAh)
        val spin = sus.first { it.pkg == "com.spin" }
        assertEquals("wakelock count", spin.cause)
        assertTrue(spin.detail.startsWith("why: 5000 wakelock acquires (top: Spin x5000)"))
        assertEquals(10.0, spin.mAh, 0.001)
        assertEquals("pts", spin.unit)
    }

    @Test fun `a saturated notification storm scores 80 and families stack`() {
        val one = XrayAnalyze.suspects(mapOf("notification" to "AggregatedStats{ key='com.n.app', numPostedByApp=5000, numUpdatedByApp=0 }")).single()
        assertEquals(80.0, one.score, 0.001)
        val two = XrayAnalyze.suspects(mapOf("batterystats" to DUMP, "packages" to PK, "notification" to "AggregatedStats{ key='com.spin', numPostedByApp=5000, numUpdatedByApp=0 }"))
        val spin = two.first { it.pkg == "com.spin" }
        assertTrue("stacked ${spin.score}", spin.score > 80.0)
        assertEquals(1, spin.rank)
    }
}
