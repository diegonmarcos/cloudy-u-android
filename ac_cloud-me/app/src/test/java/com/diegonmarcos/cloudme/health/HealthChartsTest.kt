package com.diegonmarcos.cloudme.health

import android.app.Application
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

/**
 * #1390 Health redesign: Health opens on Tracked, the D / W / M / Y bucketing with min / avg / max behind every chart, the heart-rate
 * zones, and which source feeds each metric. Each assertion is proven red by a planted mutation (.github/workflows/health-mutation.yml).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class HealthChartsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 10)
    private fun at(d: LocalDate, h: Int, m: Int = 0) = d.atTime(h, m).toInstant(zone)
    private val metrics = Tracked.parse(HealthTestData.text("data/health/${Tracked.ASSET}"))
    private fun m(id: String) = metrics.first { it.id == id }

    @Test fun `Health opens on Tracked - the first flat page and the first row`() {
        val sections = JSONObject(HealthTestData.text("build.json")).getJSONObject("ui").getJSONArray("sections")
        val health = (0 until sections.length()).map { sections.getJSONObject(it) }.first { it.getString("id") == "health" }
        // NavSection.page(null) = pages.firstOrNull(): where a section opens when no page is asked for
        assertEquals("tracked", health.getJSONArray("pages").getJSONObject(0).getString("id"))
        val firstRow = health.getJSONArray("groups").getJSONObject(0)
        assertEquals("trackers", firstRow.getString("id"))
        assertEquals("tracked", firstRow.getJSONArray("pages").getString(0))
    }

    @Test fun `Day is 24 hourly buckets, readings in the same hour add up, empty hours stay null`() {
        val r = listOf(Reading(at(today, 9, 10), 300.0), Reading(at(today, 9, 50), 200.0), Reading(at(today, 14, 5), 50.0))
        val b = HealthAgg.bucketize(r, TrackedRange.DAY, today, zone, avg = false)
        assertEquals(24, b.size)
        assertEquals(500.0, b[9].value!!, 1e-9)
        assertEquals(50.0, b[14].value!!, 1e-9)
        assertNull(b[10].value)
        assertEquals("09", b[9].label)
    }

    @Test fun `Week is 7 daily buckets ending today, labelled by weekday, older readings dropped`() {
        val r = listOf(Reading(at(today, 8), 1000.0), Reading(at(today, 20), 500.0), Reading(at(today.minusDays(6), 8), 70.0), Reading(at(today.minusDays(9), 8), 9999.0))
        val b = HealthAgg.bucketize(r, TrackedRange.WEEK, today, zone, avg = false)
        assertEquals(7, b.size)
        assertEquals(today.atStartOfDay(), b.last().start)
        assertEquals(1500.0, b.last().value!!, 1e-9)
        assertEquals(70.0, b.first().value!!, 1e-9)
        assertEquals("Tue", b.last().label) // 2026-11-10 is a Tuesday
        assertEquals(1570.0, b.mapNotNull { it.value }.sum(), 1e-9)
    }

    @Test fun `Month is 30 daily buckets and avg metrics take the mean of the day`() {
        val r = listOf(Reading(at(today, 7), 70.0), Reading(at(today, 21), 72.0), Reading(at(today.minusDays(29), 7), 75.0))
        val b = HealthAgg.bucketize(r, TrackedRange.MONTH, today, zone, avg = true)
        assertEquals(30, b.size)
        assertEquals(71.0, b.last().value!!, 1e-9)
        assertEquals(75.0, b.first().value!!, 1e-9)
    }

    @Test fun `Year is 12 monthly buckets holding the mean daily value of sum metrics`() {
        // October 2026 has 31 days: 3100 in it = 100 a day; November so far covers 10 days: 500 = 50 a day
        val r = listOf(Reading(at(LocalDate.of(2026, 10, 3), 9), 1000.0), Reading(at(LocalDate.of(2026, 10, 20), 9), 2100.0), Reading(at(today, 9), 500.0))
        val b = HealthAgg.bucketize(r, TrackedRange.YEAR, today, zone, avg = false)
        assertEquals(12, b.size)
        assertEquals(LocalDateTime.of(2025, 12, 1, 0, 0), b.first().start)
        assertEquals(LocalDateTime.of(2026, 11, 1, 0, 0), b.last().start)
        assertEquals("Nov", b.last().label)
        assertEquals(100.0, b[10].value!!, 1e-9)
        assertEquals(50.0, b.last().value!!, 1e-9)
        val w = HealthAgg.bucketize(listOf(Reading(at(LocalDate.of(2026, 10, 3), 7), 76.0), Reading(at(LocalDate.of(2026, 10, 30), 7), 74.0)), TrackedRange.YEAR, today, zone, avg = true)
        assertEquals(75.0, w[10].value!!, 1e-9)
    }

    @Test fun `stats are min, mean and max over the buckets that hold data, null when none does`() {
        val b = listOf(Bucket(today.atStartOfDay(), "a", 4.0), Bucket(today.atStartOfDay(), "b", null), Bucket(today.atStartOfDay(), "c", 10.0), Bucket(today.atStartOfDay(), "d", 7.0))
        val s = HealthAgg.stats(b)!!
        assertEquals(4.0, s.min, 1e-9); assertEquals(7.0, s.avg, 1e-9); assertEquals(10.0, s.max, 1e-9); assertEquals(3, s.n)
        assertNull(HealthAgg.stats(listOf(Bucket(today.atStartOfDay(), "x", null))))
        assertEquals(21.0, HealthAgg.headline(m("steps"), TrackedRange.DAY, b)!!, 1e-9)   // Day: today's total
        assertEquals(7.0, HealthAgg.headline(m("steps"), TrackedRange.WEEK, b)!!, 1e-9)   // Week: daily average
        assertEquals(7.0, HealthAgg.headline(m("weight"), TrackedRange.DAY, b)!!, 1e-9)  // avg metric: the mean
        assertEquals(3.0, HealthAgg.change(b)!!, 1e-9)
    }

    @Test fun `heart-rate zones split by tenths of max HR from 50 percent, rest counts in none`() {
        val z = HealthAgg.zones(listOf(90.0, 105.0, 125.0, 150.0, 170.0, 185.0, 199.0, 240.0), maxHr = 200.0)
        assertArrayEquals(intArrayOf(1, 1, 1, 1, 3), z)
    }

    @Test fun `every Tracked card has a source naming the record Health Connect is read from`() {
        assertEquals(TrackedHc.IDS.toSet(), metrics.map { it.id }.toSet())
        for (id in TrackedHc.IDS) {
            val s = HealthSources.of(id)
            assertNotNull("no source for $id", s)
            assertEquals("source of $id", TrackedHc.type(id).simpleName, s!!.record)
            assertTrue("$id says nothing on how to connect", s.how.contains("Health Connect") && s.writers.isNotBlank())
        }
        assertEquals("HeartRateRecord", HealthSources.of("heart")!!.record)
        assertEquals("RestingHeartRateRecord", HealthSources.of("resting_hr")!!.record)
        assertEquals("HeartRateVariabilityRmssdRecord", HealthSources.of("hrv")!!.record)
        assertEquals("BodyFatRecord", HealthSources.of("bodyfat")!!.record)
        assertNull(HealthSources.of("nope"))
    }

    @Test fun `each tracker page lists its sources, plan pages read none`() {
        assertEquals(TrackedHc.IDS, HealthSources.forPage("tracked").map { it.id })
        assertEquals(listOf("weight", "bodyfat"), HealthSources.forPage("bodycomp").map { it.id })
        assertEquals(listOf("resting_hr", "hrv", "heart"), HealthSources.forPage("hr").map { it.id })
        assertEquals(listOf("sleep"), HealthSources.forPage("sleep").map { it.id })
        for (p in listOf("activities", "workout")) assertTrue(p, HealthSources.forPage(p).any { it.id == "workouts" })
        assertTrue(HealthSources.forPage("bio").isEmpty())
    }

    @Test fun `mock series fill every range of every card`() {
        for (mm in metrics) for (r in TrackedRange.entries) {
            val b = HealthMock.series(mm, r, today, zone)
            assertEquals("${mm.id} ${r.name}", mapOf(TrackedRange.DAY to 24, TrackedRange.WEEK to 7, TrackedRange.MONTH to 30, TrackedRange.YEAR to 12)[r], b.size)
            // today may still be empty before the first waking hour: every earlier bucket is filled
            if (r != TrackedRange.DAY) assertTrue("${mm.id} ${r.name} has gaps", b.dropLast(1).all { it.value != null })
        }
        assertEquals(7, HealthMock.nights(today, 7).size)
        assertTrue(HealthMock.workouts(today, zone, 30).isNotEmpty())
    }
}
