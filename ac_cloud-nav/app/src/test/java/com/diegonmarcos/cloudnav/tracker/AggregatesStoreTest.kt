package com.diegonmarcos.cloudnav.tracker

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1851 the persisted aggregates keep every field the Explored tabs draw, and the steps of a date have one answer. */
class AggregatesStoreTest {
    @Test fun `places cities and world rows keep every field the tabs draw`() {
        val p = JSONArray(AggregatesStore.placesJson(listOf(PlaceTotal("Cafe Mitte", "Berlin", "Deutschland", 3, 5_400_000L))).toString()).getJSONObject(0)
        assertEquals("Cafe Mitte", p.getString("label")); assertEquals("Berlin", p.getString("city")); assertEquals("Deutschland", p.getString("country"))
        assertEquals(3, p.getInt("visits")); assertEquals(5_400_000L, p.getLong("total_ms"))
        val c = JSONArray(AggregatesStore.citiesJson(listOf(CityTotal("Berlin", "Deutschland", 12, 99L))).toString()).getJSONObject(0)
        assertEquals(12, c.getInt("days")); assertEquals("Berlin", c.getString("city"))
        val w = JSONArray(AggregatesStore.worldJson(listOf(CountryTotal("Deutschland", 20, 7L))).toString()).getJSONObject(0)
        assertEquals("Deutschland", w.getString("country")); assertEquals(20, w.getInt("days"))
    }

    @Test fun `the five aggregate kinds are persisted, trips and insights in the fleet door contract`() {
        val d = LocalDate.of(2026, 11, 20)
        val stats = listOf(DayStats(d, 8 * 3_600_000L, "Berlin", DaySummary(4, emptyMap())))
        val rows = AggregatesStore.rows(stats, TripsResult("Berlin", false, emptyList()), emptyList(), emptyList(), emptyList())
        assertEquals(setOf("insights", "trips", "places", "cities", "world"), rows.keys)
        assertEquals(8.0, JSONObject(rows.getValue("insights")).getJSONArray("days").getJSONObject(0).getDouble("sleep_h"), 0.01)
        assertEquals("Berlin", JSONObject(rows.getValue("trips")).getString("home"))
    }

    @Test fun `a date has one step answer, the stored Health Connect total first, then the rollup`() {
        assertEquals(9910L, StepsFromStore.forDay("2026-11-21", mapOf("2026-11-21" to 9910L), mapOf("2026-11-21" to 594L)))
        assertEquals(594L, StepsFromStore.forDay("2026-11-21", emptyMap(), mapOf("2026-11-21" to 594L)))
        org.junit.Assert.assertNull(StepsFromStore.forDay("2026-11-21", emptyMap(), emptyMap()))
    }
}
