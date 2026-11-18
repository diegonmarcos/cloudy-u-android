package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1786 the CB Homepage's live sovereign curves (data/cb-indicators.json source finterm_sov, GET /v1/rates/sovereign-curves): the route's URL from the
 * declared rules, the parser (the live sample, then a stale curve, an unavailable country, a null value and a malformed point), each value's date and
 * source, the snapshot fallback for the countries and tenors the route does not answer, and the status a row shows.
 */
class SovCurvesTest {
    private val cb = CbDecl.parse(DataFiles.cbCountriesJson, DataFiles.cbIndicatorsJson)
    private val box = HomeBoxes.parse(java.io.File(listOf("../data/home-boxes.json", "data/home-boxes.json").first { java.io.File(it).isFile }).readText())!!
    private val yields = box.cbBoxes.first { it.id == "cb-yields" }
    private val live = SovCurvesApi.parse(DataFiles.cbFixture("sovereign-curves.json"))!!
    private val degraded = SovCurvesApi.parse(DataFiles.cbFixture("sovereign-curves-degraded.json"))!!
    private fun col(label: String) = yields.columns.first { it.label == label }

    @Test fun theLiveSourceIsDeclaredAndAskedForTheMajorsAtTheFourTenors() {
        val src = cb.sources.getValue("finterm_sov")
        assertTrue(src.live)
        assertEquals("cloud_fincept", src.fleetSource)
        assertTrue("the fleet source is declared in data/sources.json", Catalog.parse(DataFiles.sourcesJson).any { it.id == src.fleetSource })
        val rules = SovCurvesApi.liveRules(cb, yields.columns.map { it.indicator })
        assertEquals(4, rules.size)
        for (r in rules) assertEquals(listOf("GB", "JP", "CN"), r.countries)
        assertEquals("/v1/rates/sovereign-curves?countries=GB,JP,CN&tenors=2,5,10,30", SovCurvesApi.path(src, rules))
        // the route sits under the fleet source's /v1 surface, like /v1/curves/ust_par
        val fleet = Catalog.parse(DataFiles.sourcesJson).first { it.id == src.fleetSource }
        assertTrue(SovCurvesApi.path(src, rules).startsWith(fleet.paths.getValue("v1")))
        // US and EA keep their own rules (FRED, the ECB curve): no live rule covers them
        for (c in listOf("US", "EA")) assertFalse(SovCurvesApi.covers(cb, yields.columns.map { it.indicator }, c))
        // the live rules come after every rule already declared, so no snapshot series changes its rule index
        for (r in rules) { val ind = cb.indicators.values.first { r in it.rules }; assertEquals(r, ind.rules.last()) }
    }

    @Test fun theLiveSampleParsesWithItsDatesAndSources() {
        assertEquals("2026-11-18", live.asOf)
        assertEquals(listOf("2", "5", "10", "30"), live.tenors)
        assertEquals(SovPoint(4.771, "2026-11-16", "Bank of England"), live.point("GB", "2"))
        assertEquals(5.334, live.point("GB", "10Y")!!.value!!, 1e-9)
        assertEquals(SovPoint(1.878, "2026-11-17", "Ministry of Finance Japan"), live.point("JP", "2y"))
        assertEquals(2.925, live.point("JP", "10")!!.value!!, 1e-9)
        assertEquals(SovPoint(1.2301, "2026-11-18", "ChinaBond"), live.point("CN", "2"))
        assertEquals(1.5892, live.point("CN", "10")!!.value!!, 1e-9)
        assertNull(live.point("GB", "5"))
        assertEquals(listOf("5", "30"), live.meta.getValue("GB").missingTenors)
        assertEquals("OGL", live.meta.getValue("GB").licence)
        assertTrue(live.unavailable.isEmpty())
    }

    @Test fun aCbHomeCellShowsTheLivePrintWithItsDateAndSourceElseTheSnapshot() {
        val snap = CbSnapshot.parse(DataFiles.cbSnapshotJson)
        val gb10 = HomeBoxes.cbResolve(cb, snap, live, "GB", col("10Y"))
        assertEquals(CbCell(5.334, "2026-11-16", "Bank of England", stale = false, live = true), gb10)
        assertEquals("5.33", HomeBoxes.cbText(gb10))
        assertEquals("2026-11-16 · Bank of England", HomeBoxes.cbWhere(gb10, "stale"))
        assertEquals("11-16", HomeBoxes.cbShortDate(gb10.asOf))
        assertEquals("2026-11", HomeBoxes.cbShortDate("2026-11")); assertEquals("2025", HomeBoxes.cbShortDate("2025"))
        assertEquals(1.2301, HomeBoxes.cbResolve(cb, snap, live, "CN", col("2Y")).value!!, 1e-9)
        // US reads its snapshot (FRED), with the snapshot series' source and its month
        val us2 = HomeBoxes.cbResolve(cb, snap, live, "US", col("2Y"))
        assertFalse(us2.live)
        assertNotNull(us2.value)
        assertEquals(snap.of("US", "yield_2y")!!.source, us2.source)
        assertEquals(HomeBoxes.cbCell(snap.of("US", "yield_2y"), "latest", snap.currentYear).copy(source = us2.source), us2)
        // a tenor the route does not answer falls back to the snapshot (GB 5Y has no snapshot rule: n/a, never invented)
        val gb5 = HomeBoxes.cbResolve(cb, snap, live, "GB", col("5Y"))
        assertFalse(gb5.live)
        assertEquals(HomeBoxes.cbCell(snap.of("GB", "yield_5y"), "latest", snap.currentYear).value, gb5.value)
        // nothing answered (off the mesh): every cell is the snapshot's, exactly as before #1786's live source
        for (c in listOf("US", "EA", "GB", "JP", "CN")) for (k in yields.columns) {
            val was = HomeBoxes.cbCell(snap.of(c, k.indicator), k.mode, snap.currentYear)
            val now = HomeBoxes.cbResolve(cb, snap, null, c, k)
            assertEquals("$c ${k.label}", was.value, now.value); assertEquals(was.asOf, now.asOf)
        }
    }

    @Test fun aStaleCurveIsMarkedAndAnUnavailableCountryShowsItsReason() {
        // stale: JP's value is still shown, marked stale with the day it is for
        assertTrue(degraded.stale("JP"))
        val jp2 = HomeBoxes.cbResolve(cb, null, degraded, "JP", col("2Y"))
        assertEquals(CbCell(1.878, "2026-11-10", "Ministry of Finance Japan", stale = true, live = true), jp2)
        assertEquals("2026-11-10 · Ministry of Finance Japan · stale", HomeBoxes.cbWhere(jp2, "stale"))
        assertEquals("stale (2026-11-12T09:00:00Z)  ·  no 10Y", SovCurvesApi.status(degraded, "JP", "off", "stale", "no"))
        // unavailable: CN has no cell from the route and its row says why
        assertNull(SovCurvesApi.cell(cb, degraded, "CN", "yield_2y"))
        assertEquals(CbCell(null, ""), HomeBoxes.cbResolve(cb, null, degraded, "CN", col("2Y")))
        assertEquals("ChinaBond did not answer (HTTP 503)", SovCurvesApi.status(degraded, "CN", "off", "stale", "no"))
        // a null value is n/a (never 0), a malformed point is skipped; "2Y" keys read as "2"
        assertEquals(SovPoint(null, "", ""), degraded.point("GB", "10"))
        assertNull(SovCurvesApi.cell(cb, degraded, "GB", "yield_10y"))
        assertNull(degraded.point("JP", "10"))
        assertEquals(4.771, degraded.point("GB", "2")!!.value!!, 1e-9)
        assertEquals(listOf("2", "10"), degraded.tenors)
        assertEquals("no 10Y", SovCurvesApi.status(degraded, "GB", "off", "stale", "no"))
        // the live sample: nothing stale, only the tenors it lacks
        assertEquals("no 5Y, 30Y", SovCurvesApi.status(live, "GB", "off", "stale", "no"))
        // the route did not answer at all: the reason the store gives
        assertEquals("live curves not reachable", SovCurvesApi.status(null, "GB", "live curves not reachable", "stale", "no"))
        assertNull(SovCurvesApi.status(null, "GB", "", "stale", "no"))
        // not the route's shape
        assertNull(SovCurvesApi.parse("<html/>"))
        assertNull(SovCurvesApi.parse("{}"))
        assertNotNull(SovCurvesApi.parse("""{"unavailable":{"GB":"BoE down"}}"""))
        assertEquals("", HomeBoxes.cbWhere(CbCell(null, ""), "stale"))
    }

    @Test fun theDeclaredWordsOfTheLiveRowsExist() {
        for (k in listOf("live_stale", "live_missing", "live_off_mesh", "live_no_answer")) assertTrue("home-boxes ui lacks $k", box.word(k).isNotEmpty())
    }
}
