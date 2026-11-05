package com.diegonmarcos.superapp.xray

import com.diegonmarcos.superapp.battery.BatteryArchive
import com.diegonmarcos.superapp.battery.BatteryCycles
import com.diegonmarcos.superapp.battery.BatterySession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.TimeZone

/** #1504 The 90-day battery archive: trend with spikes, merge and prune, the storage codec, and the report sections. */
class BatteryArchiveTest {
    private val utc = TimeZone.getTimeZone("UTC")
    private val day = 86_400_000L
    private val t0 = 1_790_000_000_000L - (1_790_000_000_000L % day)

    /** A discharge of [pct] over [hours] starting [d] days after t0. */
    private fun dis(d: Int, pct: Int, hours: Double, h0: Int = 8) = BatterySession(false, t0 + d * day + h0 * 3_600_000L, t0 + d * day + h0 * 3_600_000L + (hours * 3_600_000).toLong(),
        80, 80 - pct, 0, null, null, false)
    private fun chg(d: Int) = BatterySession(true, t0 + d * day, t0 + d * day + 3_600_000L, 20, 80, 1, 10.0, 31.0, false)

    @Test fun `the spike day stands out from a 7 to 9 percent per hour baseline`() {
        val s = listOf(dis(0, 8, 1.0), dis(1, 9, 1.0), dis(2, 7, 1.0), dis(3, 18, 1.0), dis(4, 8, 1.0))
        val t = BatteryArchive.trend(s, utc)
        assertEquals(5, t.size)
        assertEquals(listOf(false, false, false, true, false), t.map { it.spike })
        assertEquals(18.0, t[3].avgPctPerHour, 0.01)
    }

    @Test fun `a day is the weighted rate of its discharges and a charge or a blip is ignored`() {
        val t = BatteryArchive.trend(listOf(dis(0, 6, 1.0, 8), dis(0, 4, 3.0, 12), chg(0), dis(1, 1, 0.5)), utc)
        assertEquals(10.0 / 4.0, t[0].avgPctPerHour, 0.01)
        assertFalse(t.single { it.day == t[1].day }.spike)
    }

    @Test fun `ninety days merge, the live session wins and older ones are pruned`() {
        val now = t0 + 100 * day
        val old = dis(1, 5, 1.0)
        val a = dis(95, 5, 1.0); val live = a.copy(endPct = 10)
        val m = BatteryArchive.merge(listOf(old, a), listOf(live), now)
        assertEquals(listOf(live), m)
        assertEquals(90L * day, BatteryArchive.RETENTION_MS)
    }

    @Test fun `a session survives the storage codec`() {
        val s = chg(3).copy(avgW = 12.34, maxTempC = 40.5)
        val back = BatteryArchive.decode(BatteryArchive.encode(s))!!
        assertEquals(s.copy(avgW = 12.34, maxTempC = 40.5), back)
        assertNull(BatteryArchive.decode("garbage"))
    }

    @Test fun `per-app daily energy comes from the cycles, heaviest first`() {
        val c = listOf(BatteryCycles.Cycle(t0, t0 + 1000, 90, 50, mapOf(10001 to 5.0, 10002 to 9.0)), BatteryCycles.Cycle(t0 + 2000, null, 50, 40, mapOf(10001 to 6.0)))
        val d = BatteryArchive.dailyApps(c, utc).values.single()
        assertEquals(listOf(10001 to 11.0, 10002 to 9.0), d)
    }

    @Test fun `the report carries the whole history with the spike flag and the screen-off rate`() {
        val s = listOf(dis(0, 8, 1.0), dis(1, 8, 1.0), dis(2, 18, 1.0), chg(2))
        val secs = XrayBattery.sections(s, emptyList(), { "app$it" }, utc)
        assertEquals(4, secs.size)
        assertTrue(secs[0].text.contains("SPIKE"))
        assertTrue(secs[1].text.lines().count { it.contains("discharge") } == 3 && secs[1].text.contains("charge |"))
        assertTrue(secs.all { it.group == XrayProbes.G_BATTERY && it.source == SRC_APP })
    }
}
