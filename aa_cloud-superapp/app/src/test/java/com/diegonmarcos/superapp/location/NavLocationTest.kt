package com.diegonmarcos.superapp.location

import com.diegonmarcos.superapp.location.NavLocation.Answer
import com.diegonmarcos.superapp.location.NavLocation.Why
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1690 the SuperApp's location comes from Cloud Nav over the fleet IPC door: contract, reasons, Weather fed from it. */
class NavLocationTest {
    private val now = 1_800_000_000_000L
    private val body = """{"v":1,"owner":"cloud-nav","fix":true,"lat":40.4168,"lon":-3.7038,"accuracy_m":12.5,"ts":${now - 60_000},"age_ms":60000,"source":"gps","predicted":false}"""

    @Test fun `contract v1 fields parse into the fix every view shows`() {
        val a = NavLocation.parse(body) as Answer.Ok
        assertEquals(40.4168, a.fix.lat, 0.0); assertEquals(-3.7038, a.fix.lon, 0.0)
        assertEquals(12.5f, a.fix.accuracyM); assertEquals(now - 60_000, a.fix.timeMs)
        assertEquals(60_000L, a.fix.ageMs); assertEquals("gps", a.fix.source); assertEquals(false, a.fix.predicted)
    }

    @Test fun `no fix and unreadable bodies carry a reason`() {
        val n = NavLocation.parse("""{"v":1,"fix":false,"reason":"turn its Tracker on"}""") as Answer.Unavailable
        assertEquals(Why.NO_FIX, n.why); assertEquals("turn its Tracker on", n.detail)
        assertEquals(Why.OUTDATED, (NavLocation.parse("not json") as Answer.Unavailable).why)
        assertEquals(Why.NO_FIX, (NavLocation.parse("""{"fix":true,"lat":0,"lon":200,"ts":5}""") as Answer.Unavailable).why)
    }

    @Test fun `Cloud Nav missing, silent, refusing or old each names the exact reason`() {
        assertEquals(Why.NOT_INSTALLED, (NavLocation.classify(false, false, 0, "", "") as Answer.Unavailable).why)
        assertEquals(Why.DENIED, (NavLocation.classify(true, false, 403, "", "") as Answer.Unavailable).why)
        assertEquals(Why.OUTDATED, (NavLocation.classify(true, false, 404, "", "") as Answer.Unavailable).why)
        assertEquals(Why.NOT_RUNNING, (NavLocation.classify(true, false, 503, "", "timeout") as Answer.Unavailable).why)
        assertTrue(NavLocation.classify(true, true, 200, body, "") is Answer.Ok)
        for (w in Why.values()) assertTrue(w.name, NavLocation.reasonLine(w).contains("Cloud Nav"))
    }

    private class Mem(var f: WeatherLocation.Fix? = null) : WeatherLocation.FixStore {
        override fun load() = f
        override fun save(fix: WeatherLocation.Fix): Boolean { if (!fix.valid || (f != null && fix.timeMs <= f!!.timeMs)) return false; f = fix; return true }
    }

    @Test fun `Weather is fed by Cloud Nav and persists the fix`() {
        val store = Mem()
        val r = WeatherLocation.resolveFromNav(now, store) { NavLocation.parse(body) } as WeatherLocation.Result.Found
        assertEquals(40.4168, r.fix.lat, 0.0); assertTrue(r.fresh)
        assertTrue(r.fix.source.startsWith(WeatherLocation.NAV)); assertEquals(r.fix, store.f)
    }

    @Test fun `a fresh persisted fix does not even ask Cloud Nav`() {
        val store = Mem(WeatherLocation.Fix(41.0, 2.0, 30f, now - 60_000, "cloud-nav · net"))
        var asked = 0
        val r = WeatherLocation.resolveFromNav(now, store) { asked++; NavLocation.parse(body) }
        assertTrue(r is WeatherLocation.Result.Found); assertEquals(0, asked)
    }

    @Test fun `Cloud Nav missing with no fix says so, never tracks here`() {
        val r = WeatherLocation.resolveFromNav(now, Mem()) { NavLocation.classify(false, false, 0, "", "") }
        assertEquals(WeatherLocation.Result.None(WeatherLocation.Reason.NAV_UNAVAILABLE), r)
        val stale = Mem(WeatherLocation.Fix(41.0, 2.0, 30f, now - 10 * 3_600_000L, "cloud-nav · net"))
        val s = WeatherLocation.resolveFromNav(now, stale) { NavLocation.classify(false, false, 0, "", "") } as WeatherLocation.Result.Found
        assertEquals(false, s.fresh)   // the newest stale fix still answers when Cloud Nav is gone
        assertEquals(WeatherLocation.Result.None(WeatherLocation.Reason.NO_FIX),
            WeatherLocation.resolveFromNav(now, Mem()) { NavLocation.parse("""{"fix":false}""") })
    }

    @Test fun `field parity - every Weather reason the card showed before still exists`() {
        val before = listOf("PERMISSION", "LOCATION_OFF", "BACKGROUND", "NO_FIX", "SEARCHING")
        assertTrue(WeatherLocation.Reason.values().map { it.name }.containsAll(before))
    }
}
