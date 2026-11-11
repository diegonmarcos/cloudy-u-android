package com.diegonmarcos.superapp.notificationcenter

import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Env
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Fix
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.NETWORK
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Reason
import com.diegonmarcos.superapp.notificationcenter.WeatherLocation.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Coarse-only, one-fix-per-4h rule, persistence rule and fallback of the Weather badge's position (#1654). Pure JVM. */
class WeatherLocationTest {
    private val GPS = "gps"
    private val PASSIVE = "passive"
    private val now = 10_000_000_000L
    private val min = 60_000L
    private val all = Env(coarse = true, fine = true, background = true, anyProviderOn = true)

    private fun fix(src: String, ageMin: Long, lat: Double = 40.4) = Fix(lat, -3.7, 30f, now - ageMin * min, src)

    private class MemStore(var fix: Fix? = null) : WeatherLocation.FixStore {
        override fun load() = fix
        override fun save(fix: Fix): Boolean {
            if (!fix.valid || (this.fix != null && fix.timeMs <= this.fix!!.timeMs)) return false
            this.fix = fix; return true
        }
    }

    private class FakeProbe(
        val on: Set<String> = setOf(NETWORK, PASSIVE, GPS),
        val cached: Map<String, Fix> = emptyMap(),
        val fresh: Map<String, Fix> = emptyMap(),
    ) : WeatherLocation.Probe {
        val peeks = ArrayList<String>()
        val requests = ArrayList<String>()
        override fun enabled(provider: String) = provider in on
        override fun lastKnown(provider: String): Fix? { peeks += provider; return cached[provider] }
        override fun request(provider: String, timeoutMs: Long): Fix? { requests += provider; return fresh[provider] }
    }

    @Test fun onlyTheCoarseNetworkProviderIsEverUsed() {
        assertEquals(listOf(NETWORK), WeatherLocation.PEEK_ORDER)
        assertEquals(listOf(NETWORK), WeatherLocation.REQUEST_ORDER)
    }

    @Test fun refreshIsGatedToFourHours() {
        assertEquals(4 * 60 * 60_000L, WeatherLocation.REFRESH_MS)
        assertEquals(WeatherLocation.REFRESH_MS, WeatherLocation.FRESH_MS)
        // 3h59 old: reused, nothing asked. 4h01 old: one network request.
        val p1 = FakeProbe(); WeatherLocation.resolve(now, all, p1, MemStore(fix("network", 239)))
        assertTrue(p1.peeks.isEmpty() && p1.requests.isEmpty())
        val p2 = FakeProbe(); WeatherLocation.resolve(now, all, p2, MemStore(fix("network", 241)))
        assertEquals(listOf(NETWORK), p2.requests)
    }

    @Test fun freshPersistedFixUsesNoProviderAtAll() {
        val p = FakeProbe(); val s = MemStore(fix("network", 10))
        val r = WeatherLocation.resolve(now, all, p, s) as Result.Found
        assertTrue(r.fresh); assertEquals("network", r.fix.source)
        assertTrue(p.peeks.isEmpty() && p.requests.isEmpty())
    }

    @Test fun cachedNetworkFixIsUsedAndPassiveGpsAreNeverPeeked() {
        val p = FakeProbe(cached = mapOf(NETWORK to fix(NETWORK, 30), PASSIVE to fix(PASSIVE, 1), GPS to fix(GPS, 1)))
        val s = MemStore()
        val r = WeatherLocation.resolve(now, all, p, s) as Result.Found
        assertEquals(NETWORK, r.fix.source)
        assertEquals(listOf(NETWORK), p.peeks)
        assertTrue(p.requests.isEmpty())
        assertEquals(NETWORK, s.fix?.source)
    }

    @Test fun gpsIsNeverRequestedEvenWhenNetworkFails() {
        val p = FakeProbe(fresh = mapOf(GPS to fix(GPS, 0)))
        assertTrue(WeatherLocation.resolve(now, all, p, MemStore()) is Result.None)
        assertEquals(listOf(NETWORK), p.requests)
    }

    @Test fun coarsePermissionAloneIsEnoughAndDisabledProvidersAreNotAsked() {
        val coarseOnly = all.copy(fine = false)
        val p = FakeProbe(fresh = mapOf(NETWORK to fix(NETWORK, 0)))
        assertTrue(WeatherLocation.resolve(now, coarseOnly, p, MemStore()) is Result.Found)
        assertEquals(listOf(NETWORK), p.requests)
        val noNet = FakeProbe(on = setOf(PASSIVE, GPS), fresh = mapOf(GPS to fix(GPS, 0)))
        assertTrue(WeatherLocation.resolve(now, all, noNet, MemStore()) is Result.None)
        assertTrue(noNet.requests.isEmpty())
    }

    @Test fun failedRefreshNeverClearsTheStoredFix() {
        val old = fix("network", 300)               // stale: a refresh is attempted
        val s = MemStore(old)
        val p = FakeProbe()                         // every provider answers nothing
        val r = WeatherLocation.resolve(now, all, p, s) as Result.Found
        assertFalse(r.fresh); assertEquals(old, r.fix)
        assertEquals(old, s.fix)
        assertEquals(listOf(NETWORK), p.requests)
    }

    @Test fun staleNetworkFixIsUsedAndPersistedWhenNothingElseExists() {
        val p = FakeProbe(cached = mapOf(NETWORK to fix(NETWORK, 600)))
        val s = MemStore()
        val r = WeatherLocation.resolve(now, all, p, s) as Result.Found
        assertFalse(r.fresh); assertEquals(NETWORK, r.fix.source); assertEquals(r.fix, s.fix)
    }

    @Test fun onlyANewerFixReplacesTheStoredOne() {
        val s = MemStore(fix("network", 10))
        assertFalse(s.save(fix("gps", 20, lat = 50.0)))
        assertEquals(40.4, s.fix!!.lat, 0.0)
        assertTrue(s.save(fix("gps", 1, lat = 50.0)))
        assertEquals(50.0, s.fix!!.lat, 0.0)
        assertFalse(s.save(Fix(0.0, 0.0, 1f, now, "gps")))      // null island = no fix
        assertFalse(s.save(Fix(Double.NaN, 1.0, 1f, now, "gps")))
    }

    @Test fun revokedPermissionStillAnswersFromTheStoredFix() {
        val denied = all.copy(coarse = false, fine = false)
        assertTrue(WeatherLocation.resolve(now, denied, FakeProbe(), MemStore(fix("network", 500))) is Result.Found)
        assertEquals(Reason.PERMISSION, (WeatherLocation.resolve(now, denied, FakeProbe(), MemStore()) as Result.None).reason)
    }

    @Test fun noFixSaysTheActualReason() {
        fun why(e: Env) = (WeatherLocation.resolve(now, e, FakeProbe(), MemStore()) as Result.None).reason
        assertEquals(Reason.PERMISSION, why(all.copy(coarse = false, fine = false)))
        assertEquals(Reason.LOCATION_OFF, why(all.copy(anyProviderOn = false)))
        assertEquals(Reason.BACKGROUND, why(all.copy(background = false)))
        assertEquals(Reason.NO_FIX, why(all))
    }
}
