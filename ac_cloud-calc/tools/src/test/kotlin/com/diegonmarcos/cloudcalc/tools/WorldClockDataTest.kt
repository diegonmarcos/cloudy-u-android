package com.diegonmarcos.cloudcalc.tools

import com.diegonmarcos.cloudcalc.tools.WorldClockData as W
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.abs

/**
 * #1423 the World Clock catalogue and map maths. Every catalogue rule is checked green on the REAL asset and then proven
 * red by its own data mutation (a capital removed, a big country cut to two cities, a zone java.time does not know), so a
 * rule that stops checking fails here; PIT mutates the code itself (tools/build.gradle).
 */
class WorldClockDataTest {
    private val cat = W.parse(File("../../data/fleet/app/com.diegonmarcos.cloudcalc/worldmap/cities.json").readText())
    private val iso = Locale.getISOCountries().toList()
    private val valid = ZoneId.getAvailableZoneIds()
    private fun problems(c: W.Catalogue) = W.problems(c, iso, valid)
    private fun approx(e: Double, a: Double, tol: Double) = assertTrue("expected $e got $a", abs(e - a) <= tol)
    private fun t(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun `the real catalogue keeps every rule`() {
        assertEquals(emptyList<String>(), problems(cat))
        assertTrue(cat.cities.size > 250)
        assertTrue(cat.zones.size > 400)
        assertTrue(cat.countries.size > 230)
    }

    @Test fun `every country has its capital - proven by removing one`() {
        assertTrue(cat.cities.any { it.capital && it.country == "FR" && it.name == "Paris" })
        val m = cat.copy(cities = cat.cities.filterNot { it.capital && it.country == "FR" })
        assertEquals(listOf("no capital: FR"), problems(m))
        // a country java.time knows but the catalogue neither lists nor exempts is caught too
        val n = cat.copy(cities = cat.cities.filterNot { it.country == "PT" }, countries = cat.countries - "PT")
        assertEquals(listOf("no capital: PT"), problems(n))
        // and an exemption is honoured
        assertEquals(emptyList<String>(), problems(n.copy(noCapital = n.noCapital + "PT")))
    }

    @Test fun `big countries have 3 or more cities - proven by cutting one to 2`() {
        assertEquals(listOf("US", "CA", "BR", "RU", "CN", "IN", "AU", "MX", "ID", "AR", "KZ", "DZ", "CD", "SA"), cat.big)
        assertEquals(3, cat.minBig)
        for (cc in cat.big) assertTrue(cc, cat.cities.count { it.country == cc } >= 3)
        val cd = cat.cities.filter { it.country == "CD" }
        val m = cat.copy(cities = cat.cities - cd.drop(2).toSet())
        assertEquals(listOf("big country CD has 2 cities"), problems(m))
        assertEquals(emptyList<String>(), problems(cat.copy(cities = cat.cities - cd.drop(3).toSet())))
    }

    @Test fun `zones are valid against java time - proven by a bogus one`() {
        for (z in cat.zones + cat.cities.map { it.zone }) ZoneId.of(z)
        assertTrue("Europe/Madrid" in cat.zones && "America/Argentina/Buenos_Aires" in cat.zones)
        assertEquals(listOf("invalid zone: Mars/Olympus"), problems(cat.copy(zones = cat.zones + "Mars/Olympus")))
        val c = cat.cities[0]
        assertEquals(listOf("invalid zone: Nowhere/X"), problems(cat.copy(cities = listOf(c.copy(zone = "Nowhere/X")) + cat.cities.drop(1))))
        assertEquals(listOf("off the globe: ${c.name}"), problems(cat.copy(cities = listOf(c.copy(lat = 91.0)) + cat.cities.drop(1))))
        assertEquals(listOf("off the globe: ${c.name}"), problems(cat.copy(cities = listOf(c.copy(lon = -181.0)) + cat.cities.drop(1))))
        assertEquals(emptyList<String>(), problems(cat.copy(cities = listOf(c.copy(lat = 90.0, lon = -180.0)) + cat.cities.drop(1))))
    }

    @Test fun `search ignores case and accents, capitals first`() {
        assertEquals("Paris", W.search(cat, "PARIS").first().name)
        assertTrue(W.search(cat, "sao paulo").any { it.name == "São Paulo" })
        val br = W.search(cat, "br")
        assertTrue(br.all { it.country == "BR" || "br" in it.name.lowercase() || "br" in it.zone.lowercase() || "br" in (cat.countries[it.country] ?: "").lowercase() })
        assertTrue(W.search(cat, "brazil").first().let { it.capital && it.country == "BR" })
        assertTrue(W.search(cat, "Asia/Tokyo").any { it.name == "Tokyo" })
        assertEquals(cat.cities.size, W.search(cat, "  ").size)
        assertTrue(W.search(cat, "").first().capital)
        assertEquals(emptyList<W.City>(), W.search(cat, "zzzqqq"))
        assertEquals(listOf("America/New_York"), W.searchZones(cat, "new york"))
        assertTrue("America/New_York" in W.searchZones(cat, "New_York"))
        assertEquals(cat.zones.size, W.searchZones(cat, "").size)
    }

    @Test fun `pins and taps`() {
        assertEquals("Tokyo", W.pinFor(cat, "Asia/Tokyo")?.name)
        assertEquals("New York City", W.pinFor(cat, "America/New_York")?.name) // named by prefix, before the zone's capital Washington
        assertEquals("Washington", W.pinFor(cat.copy(cities = cat.cities.filterNot { it.name == "New York City" }), "America/New_York")?.name)
        assertEquals("Toronto", W.pinFor(cat, "America/Toronto")?.name)
        assertEquals("Ottawa", W.pinFor(cat.copy(cities = cat.cities.filterNot { it.name == "Toronto" }), "America/Toronto")?.name) // else the capital
        assertEquals("Montréal", W.pinFor(cat.copy(cities = cat.cities.filterNot { it.name == "Toronto" || it.capital }), "America/Toronto")?.name) // else any
        assertEquals("Madrid", W.pinFor(cat, "Europe/Madrid")?.name)
        assertNull(W.pinFor(cat, "Antarctica/Troll"))
        assertEquals("Madrid", W.nearest(cat, 40.5, -3.6, 2.0)?.name)
        assertNull(W.nearest(cat, 0.0, -140.0, 1.0))
        // the date line wraps: Suva (178.4 E) is 2.6 degrees from 179 W
        assertEquals("Suva", W.nearest(cat, -18.1, -179.0, 3.0)?.name)
        assertNull(W.nearest(cat, -18.1, -179.0, 2.5))
        approx(2.0, W.dist(0.0, 179.0, 0.0, -179.0), 1e-9)
    }

    @Test fun `projection and bands`() {
        approx(0.0, W.x(-180.0, 360.0), 1e-9); approx(360.0, W.x(180.0, 360.0), 1e-9); approx(180.0, W.x(0.0, 360.0), 1e-9)
        approx(0.0, W.y(90.0, 180.0), 1e-9); approx(180.0, W.y(-90.0, 180.0), 1e-9); approx(45.0, W.y(45.0, 180.0), 1e-9)
        approx(-3.7, W.lon(W.x(-3.7, 1000.0), 1000.0), 1e-9); approx(40.4, W.lat(W.y(40.4, 500.0), 500.0), 1e-9)
        val b = W.bands()
        assertEquals(25, b.size)
        assertEquals(W.Band(-12, -180.0, -172.5), b.first()); assertEquals(W.Band(12, 172.5, 180.0), b.last())
        assertEquals(W.Band(0, -7.5, 7.5), b[12]); assertEquals("UTC+0", b[12].label)
        assertEquals("UTC−5", b[7].label); assertEquals("UTC+9", b[21].label)
        assertEquals(0, W.nominalOffset(7.0)); assertEquals(1, W.nominalOffset(8.0)); assertEquals(-5, W.nominalOffset(-75.0))
        assertEquals(-12, W.nominalOffset(-180.0)); assertEquals(-12, W.nominalOffset(180.0)); assertEquals(-11, W.nominalOffset(-170.0))
        approx(-170.0, W.norm180(190.0), 1e-9); approx(170.0, W.norm180(-190.0), 1e-9); approx(10.0, W.norm180(370.0), 1e-9)
    }

    @Test fun `land polygons parse`() {
        val land = W.parseLand(File("../../data/fleet/app/com.diegonmarcos.cloudcalc/worldmap/land.json").readText())
        assertTrue(land.size > 100)
        assertTrue(land.all { it.size >= 6 && it.size % 2 == 0 })
        assertTrue(land.all { p -> p.indices.all { i -> if (i % 2 == 0) abs(p[i]) <= 180.0 else abs(p[i]) <= 90.0 } })
        assertEquals(listOf(1.0, 2.0), W.parseLand("""{"polygons":[[1,2]]}""")[0].toList())
    }

    @Test fun `subsolar point at equinox and solstices`() {
        // 2024 March equinox 03:06 UTC, June solstice 20:51 UTC, December solstice 2024-12-21 09:21 UTC
        approx(0.0, W.sun(t("2024-03-20T03:06:00Z")).decl, 0.02)
        approx(23.44, W.sun(t("2024-06-20T20:51:00Z")).decl, 0.02)
        approx(-23.44, W.sun(t("2024-12-21T09:21:00Z")).decl, 0.02)
        // noon UTC: the sun is over the Greenwich meridian give or take the equation of time
        // (2024-02-11: solar noon about 12:14, so at 12:00 the sun is 3.55 deg east; 2024-11-03: about 11:44, 4.1 deg west)
        approx(3.55, W.sun(t("2024-02-11T12:00:00Z")).lon, 0.1)
        approx(-4.1, W.sun(t("2024-11-03T12:00:00Z")).lon, 0.1)
        // 18:00 UTC: a quarter turn west
        approx(-90.0 - 4.1, W.sun(t("2024-11-03T18:00:00Z")).lon, 0.15)
    }

    @Test fun `terminator maths`() {
        val june = W.sun(t("2024-06-20T12:00:00Z"))
        // every terminator point has the sun on the horizon
        for (lon in listOf(-180.0, -120.0, -45.0, 0.0, 30.0, 90.0, 179.0)) approx(0.0, W.elevation(june, W.terminatorLat(june, lon), lon), 1e-6)
        // under the sun the terminator lies at decl − 90 (south), and opposite it at 90 − decl (north)
        approx(june.decl - 90.0, W.terminatorLat(june, june.lon), 1e-6)
        approx(90.0 - june.decl, W.terminatorLat(june, june.lon + 180.0), 1e-6)
        approx(0.0, W.terminatorLat(june, june.lon + 90.0), 1e-6)
        // northern summer: the north pole is lit, the south dark; noon in Madrid lit, midnight dark
        assertTrue(W.isDaylight(june, 89.0, 0.0)); assertFalse(W.isDaylight(june, -89.0, 0.0))
        assertTrue(W.isDaylight(W.sun(t("2024-06-20T12:00:00Z")), 40.4, -3.7))
        assertFalse(W.isDaylight(W.sun(t("2024-06-20T00:00:00Z")), 40.4, -3.7))
        approx(90.0 - abs(40.4 - june.decl), W.elevation(june, 40.4, june.lon), 1e-6)
        // the night polygon closes over the pole in darkness
        val n = W.nightPolygon(june, 90.0)
        assertEquals(listOf(-180.0, 180.0, -180.0), listOf(n[0], n[n.size - 4], n[n.size - 2]))
        assertEquals(-90.0, n[n.size - 1], 0.0); assertEquals(-90.0, n[n.size - 3], 0.0)
        assertEquals(2 * 5 + 4, n.size)
        approx(W.terminatorLat(june, 0.0), n[5], 1e-9)
        val dec = W.sun(t("2024-12-21T12:00:00Z"))
        assertEquals(90.0, W.nightPolygon(dec).last(), 0.0)
        assertTrue(W.isDaylight(dec, -89.0, 0.0))
        // the equinox: declination exactly 0 still yields a finite, near-meridian terminator
        val eq = W.Sun(0.0, 0.0)
        assertTrue(W.terminatorLat(eq, 90.0).isFinite())
        approx(-90.0, W.terminatorLat(eq, 0.0), 1e-3)
    }
}
