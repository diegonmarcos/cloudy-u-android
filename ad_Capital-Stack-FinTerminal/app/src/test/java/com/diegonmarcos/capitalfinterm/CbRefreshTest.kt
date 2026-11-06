package com.diegonmarcos.capitalfinterm

import java.io.IOException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1539 the in-app refresh against fixtures: the declared rules, the IMF answer, no value invented for a source that did not answer. */
class CbRefreshTest {
    private val decl = CbDecl.parse(DataFiles.cbCountriesJson, DataFiles.cbIndicatorsJson)

    private class Fake(val answers: Map<String, String>) : Http {
        val asked = ArrayList<String>()
        override fun get(url: String): String = send(url, emptyMap(), null)
        override fun post(url: String): String = throw IOException("no post")
        override fun send(url: String, headers: Map<String, String>, body: String?): String {
            synchronized(asked) { asked += url + "|" + headers["User-Agent"].orEmpty() }
            return answers.entries.firstOrNull { url.contains(it.key) }?.value ?: throw IOException("HTTP 404")
        }
    }

    private val imf = DataFiles.cbFixture("imf-datamapper.json")

    @Test fun anImfAnswerBecomesSeriesWithSourceReleaseAndVintageAndNothingElseIsInvented() {
        val http = Fake(mapOf("datamapper/api/v1/NGDP_RPCH" to imf))
        val snap = CbSnapshot.parse(CbRefresh(http, decl, "2026-11-06", 2026).run())
        val us = snap.of("US", "real_gdp")!!
        assertEquals(2.3, us.at(2026)!!, 1e-9); assertEquals(3.0, us.at(2024)!!, 1e-9); assertTrue(us.isForecast(2027))
        assertEquals("IMF World Economic Outlook (DataMapper)", us.source); assertEquals("2026-11-06", us.vintage); assertTrue(us.release.startsWith("IMF World Economic Outlook"))
        assertEquals("the euro area is read from the IMF EURO group", 1.1, snap.of("EA", "real_gdp")!!.at(2026)!!, 1e-9)
        assertNull("no source answered for Brazil's real GDP in this run: absent, not typed", snap.of("BR", "real_gdp"))
        assertNull("nothing answered for a latent variable", snap.of("US", "tobins_q"))
        assertTrue("a source that did not answer leaves no series at all", snap.series.getValue("US").keys == setOf("real_gdp"))
    }

    @Test fun theImfIsAskedWithItsDeclaredUserAgentOthersWithTheDefault() {
        val http = Fake(mapOf("datamapper/api/v1/NGDP_RPCH" to imf))
        CbRefresh(http, decl, "2026-11-06", 2026).run()
        val imfCalls = http.asked.filter { it.contains("datamapper") }
        assertTrue(imfCalls.isNotEmpty()); assertTrue(imfCalls.all { it.endsWith("|" + decl.sources.getValue("imf").userAgent) })
        assertTrue(http.asked.filter { !it.contains("datamapper") }.all { it.endsWith("|") })
    }

    @Test fun nothingAnsweredMeansAnEmptySnapshotNotAFabricatedOne() {
        val snap = CbSnapshot.parse(CbRefresh(Fake(emptyMap()), decl, "2026-11-06", 2026).run())
        assertEquals(0, snap.series.values.sumOf { it.size })
    }
}
