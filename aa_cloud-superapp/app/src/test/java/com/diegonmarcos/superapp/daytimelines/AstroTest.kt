package com.diegonmarcos.superapp.daytimelines

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * #1734 the astronomy behind the day timelines against KNOWN values: Berlin and Sydney sunrise / sunset (almanac), the new /
 * full / quarter moons of Nov-Dec 2026 (USNO/ephem instants), Sun / Moon / planet ecliptic longitudes of 2026-11-13 12:00 UTC
 * (ephem 4.2 reference), moon rise / set / transit over Berlin that day.
 */
class AstroTest {
    private val berlin = ZoneId.of("Europe/Berlin")
    private fun hm(ms: Long?, zone: ZoneId = berlin): Int {
        val t = Instant.ofEpochMilli(ms!!).atZone(zone)
        return t.hour * 60 + t.minute
    }
    private fun at(h: Int, m: Int) = h * 60 + m
    private fun iso(s: String) = Instant.parse(s).toEpochMilli()

    @Test fun `Berlin midsummer sunrise sunset and noon`() {
        val d = Astro.solarDay(LocalDate.of(2026, 6, 21), berlin, 52.52, 13.405)
        assertEquals(at(4, 43).toDouble(), hm(d.sunrise).toDouble(), 3.0)
        assertEquals(at(21, 33).toDouble(), hm(d.sunset).toDouble(), 3.0)
        assertEquals(at(13, 8).toDouble(), hm(d.solarNoon).toDouble(), 2.0)
        assertTrue("civil dawn before sunrise", d.dawn!! < d.sunrise!!)
        assertTrue("civil dusk after sunset", d.dusk!! > d.sunset!!)
    }

    @Test fun `Berlin midwinter sunrise sunset`() {
        val d = Astro.solarDay(LocalDate.of(2026, 12, 21), berlin, 52.52, 13.405)
        assertEquals(at(8, 15).toDouble(), hm(d.sunrise).toDouble(), 3.0)
        assertEquals(at(15, 54).toDouble(), hm(d.sunset).toDouble(), 3.0)
        assertEquals(at(12, 4).toDouble(), hm(d.solarNoon).toDouble(), 2.0)
    }

    @Test fun `Sydney winter day wraps over the UTC date line correctly`() {
        val z = ZoneId.of("Australia/Sydney")
        val d = Astro.solarDay(LocalDate.of(2026, 6, 21), z, -33.8688, 151.2093)
        assertEquals(at(6, 59).toDouble(), hm(d.sunrise, z).toDouble(), 3.0)
        assertEquals(at(16, 53).toDouble(), hm(d.sunset, z).toDouble(), 3.0)
        assertEquals(LocalDate.of(2026, 6, 21), Instant.ofEpochMilli(d.sunrise!!).atZone(z).toLocalDate())
    }

    @Test fun `polar day and polar night have no sunrise`() {
        val z = ZoneId.of("Arctic/Longyearbyen")
        val summer = Astro.solarDay(LocalDate.of(2026, 6, 21), z, 78.22, 15.63)
        assertTrue(summer.polarDay); assertNull(summer.sunrise); assertNull(summer.sunset)
        val winter = Astro.solarDay(LocalDate.of(2026, 12, 21), z, 78.22, 15.63)
        assertTrue(winter.polarNight); assertNull(winter.sunrise)
    }

    @Test fun `moon phase at the known new full and quarter moons`() {
        val full = Astro.phase(iso("2026-11-24T14:53:00Z"))
        assertTrue("full ${full.illumination}", full.illumination > 0.99); assertEquals("Full Moon", full.name)
        val new = Astro.phase(iso("2026-12-09T00:51:00Z"))
        assertTrue("new ${new.illumination}", new.illumination < 0.01); assertEquals("New Moon", new.name)
        val q = Astro.phase(iso("2026-11-17T11:47:00Z"))
        assertEquals(0.5, q.illumination, 0.03); assertTrue(q.waxing); assertEquals("First Quarter", q.name)
        val wane = Astro.phase(iso("2026-11-28T12:00:00Z"))
        assertTrue(!wane.waxing); assertEquals("Waning Gibbous", wane.name)
        assertEquals(Astro.SYNODIC_DAYS / 2, full.ageDays, 0.6)
    }

    @Test fun `zodiac sign of an ecliptic longitude`() {
        assertEquals("Aries", Astro.sign(0.0).name); assertEquals(0, Astro.sign(0.0).degree)
        assertEquals("Scorpio", Astro.sign(231.12).name); assertEquals(21, Astro.sign(231.12).degree)
        assertEquals("Pisces", Astro.sign(359.99).name); assertEquals(29, Astro.sign(359.99).degree)
        assertEquals("Pisces", Astro.sign(-1.0).name)
        assertEquals("Capricorn", Astro.sign(277.7).name)
        assertEquals("\u264F", Astro.sign(231.12).glyph)
    }

    @Test fun `sun moon and planet longitudes match the ephemeris`() {
        val jd = Astro.julianDay(iso("2026-11-13T12:00:00Z"))
        assertEquals(231.124, Astro.sunPos(jd).lonDeg, 0.1)
        assertEquals(277.670, Astro.moonEcliptic(jd).first, 0.6)
        assertEquals(215.039, Astro.planetEcliptic(Astro.Planet.MERCURY, jd).first, 0.3)
        assertEquals(202.872, Astro.planetEcliptic(Astro.Planet.VENUS, jd).first, 0.3)
        assertEquals(144.638, Astro.planetEcliptic(Astro.Planet.MARS, jd).first, 0.3)
        assertEquals(145.649, Astro.planetEcliptic(Astro.Planet.JUPITER, jd).first, 0.3)
        assertEquals(8.589, Astro.planetEcliptic(Astro.Planet.SATURN, jd).first, 0.3)
        // the March equinox 2026 (20 Mar 14:46 UTC): the Sun enters Aries
        val eq = Astro.sunPos(Astro.julianDay(iso("2026-03-20T14:46:00Z"))).lonDeg
        assertTrue("equinox lon $eq", eq > 359.8 || eq < 0.2)
    }

    @Test fun `moon rise set and transit over Berlin`() {
        val m = Astro.moonDay(LocalDate.of(2026, 11, 13), berlin, 52.52, 13.405)
        assertNotNull(m.rise); assertNotNull(m.set); assertNotNull(m.transit)
        // ephem: rise 11:05 UTC, set 17:28 UTC, transit 14:14 UTC
        fun utc(ms: Long?) = Instant.ofEpochMilli(ms!!).atZone(ZoneId.of("UTC")).let { it.hour * 60 + it.minute }
        assertEquals(at(11, 5).toDouble(), utc(m.rise).toDouble(), 12.0)
        assertEquals(at(17, 28).toDouble(), utc(m.set).toDouble(), 12.0)
        assertEquals(at(14, 14).toDouble(), utc(m.transit).toDouble(), 12.0)
        assertEquals("one above-horizon span", 1, m.above.size)
        assertEquals(m.rise!!.toDouble(), m.above[0].first.toDouble(), 1.0)
        assertEquals(m.set!!.toDouble(), m.above[0].second.toDouble(), 1.0)
    }

    @Test fun `planets transit on the local day`() {
        for (p in Astro.Planet.values()) {
            val t = Astro.transit(LocalDate.of(2026, 11, 13), berlin, 13.405) { Astro.planetEquatorial(p, it) }
            assertNotNull(p.label, t)
            assertEquals(LocalDate.of(2026, 11, 13), Instant.ofEpochMilli(t!!).atZone(berlin).toLocalDate())
        }
        // Mars is at ~145 deg, the Sun at ~231: Mars transits about 5.7 h before the Sun's solar noon (~11:55 local)
        val mars = Astro.transit(LocalDate.of(2026, 11, 13), berlin, 13.405) { Astro.planetEquatorial(Astro.Planet.MARS, it) }
        assertTrue("mars transit ${hm(mars)}", hm(mars) in at(5, 30)..at(7, 30))
    }
}
