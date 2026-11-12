package com.diegonmarcos.cloudnav.tracker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1690 tracker/location contract v1: the exact fields the SuperApp's NavLocation parses. */
class NavLocationWireTest {
    private val now = 1_800_000_000_000L

    @Test fun `newest measured fix wins over a newer predicted one`() {
        val j = NavLocationWire.toJson(listOf(
            PlaceFix(now - 60_000, 40.4, -3.7, false, 12.0, "gps"),
            PlaceFix(now - 1_000, 41.0, -3.0, true, 0.0, ""),
            PlaceFix(now - 120_000, 40.0, -3.0, false, 30.0, "net"),
        ), now)
        for (k in listOf("v", "owner", "fix", "lat", "lon", "accuracy_m", "ts", "age_ms", "source", "predicted")) assertTrue(k, j.has(k))
        assertEquals(1, j.getInt("v")); assertEquals("cloud-nav", j.getString("owner")); assertTrue(j.getBoolean("fix"))
        assertEquals(40.4, j.getDouble("lat"), 0.0); assertEquals(12.0, j.getDouble("accuracy_m"), 0.0)
        assertEquals(60_000L, j.getLong("age_ms")); assertEquals("gps", j.getString("source")); assertFalse(j.getBoolean("predicted"))
    }

    @Test fun `predicted only when nothing was measured, blank source is unknown`() {
        val j = NavLocationWire.toJson(listOf(PlaceFix(now - 5_000, 41.0, -3.0, true)), now)
        assertTrue(j.getBoolean("fix")); assertTrue(j.getBoolean("predicted")); assertEquals("unknown", j.getString("source"))
    }

    @Test fun `no fix carries the reason, invalid fixes are ignored`() {
        val j = NavLocationWire.toJson(listOf(PlaceFix(now, 0.0, 0.0), PlaceFix(now, Double.NaN, 1.0), PlaceFix(0, 1.0, 1.0)), now)
        assertFalse(j.getBoolean("fix")); assertTrue(j.getString("reason").isNotBlank())
    }
}
