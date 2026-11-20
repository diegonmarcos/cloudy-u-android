package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/**
 * #1832 CB > Yield Curves: the major emerging-market curves (India, Brazil, Indonesia, South Africa, Mexico), each read from its own publisher
 * (data/sources.json: CCIL, ANBIMA, PHEI, SARB, Banxico) through the declared panel (data/dashboards.json yield_curves), with the OECD monthly rates on
 * DBnomics as the key-free fallback. The answers are the publishers' own pages and APIs, cut to the curve table (src/test/resources/cb/em, 2026-11-20).
 */
class EmCurvesTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val dash = Dashboards.byId(dashboards, "yield_curves")!!
    private val today = LocalDate.of(2026, 11, 20)
    private fun fx(name: String): String = DataFiles.resource("cb/em/$name")
    private fun panel(id: String) = dash.panels.first { it.id == id }
    private fun src(id: String) = Catalog.byId(sources, id)!!

    /** A fake network answering by a predicate on the address, recording every address asked. */
    private class Fake(val answer: (String) -> String?) : Http {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun get(url: String): String {
            asked += url
            return answer(url) ?: throw IOException("no route")
        }

        override fun post(url: String): String = throw IOException("no post")
    }

    private fun load(id: String, http: Http): PanelData {
        val p = panel(id)
        return Engine.load(p, src(p.source), http, today, model = ModelCtx(sources, dash))
    }

    @Test fun theFiveCurvesAreDeclaredUnderCbYieldCurvesEachFromItsPublisher() {
        val want = mapOf("india" to "ccil", "brazil" to "anbima", "indonesia" to "phei", "south_africa" to "sarb", "mexico" to "banxico")
        assertEquals("cb", dash.tab)
        for ((id, s) in want) {
            val p = panel(id)
            assertEquals(id, s, p.source)
            assertEquals(id, Dashboards.VIEW_CURVE, p.view)
            assertEquals(id, "points", p.derive)
            assertEquals(id, "%", p.unit)
            val source = src(s)
            assertEquals(id, source.connector, p.fetch!!.optString("provider"))
            assertTrue("$id: $s does not feed Yield Curves", source.feeds("cb", "Yield Curves"))
            assertTrue("$id: $s is keyed", !source.needsKey)
            assertTrue("$id: the endpoint is the catalog's", source.endpoint.startsWith("https://"))
            // the container first, the OECD monthly rates on DBnomics last
            assertEquals(id, "cloud_fincept", p.fetch.getJSONObject("primary").getString("source"))
            assertEquals(id, "dbnomics", p.fetch.getJSONArray("fallbacks").getJSONObject(0).getString("source"))
            assertTrue(id, src("dbnomics").feeds("cb", "Yield Curves"))
        }
        // the existing curves and the premium placeholder are still there, the EM curves come after the euro area
        assertEquals(listOf("ust", "ust_official", "euro", "india", "brazil", "indonesia", "south_africa", "mexico", "japan", "sovereigns"), dash.panels.map { it.id })
    }

    @Test fun indiaIsTheCcilGovernmentCurveWithoutTheStateLoans() {
        val http = Fake { if (it.startsWith(src("ccil").endpoint + "/tenorwise-indicative-yields")) fx("ccil-tenorwise-indicative-yields.html") else null }
        val d = load("india", http)
        assertEquals(listOf("91D", "182D", "364D", "1Y-2Y", "4Y-5Y", "9Y-10Y", "13Y-15Y", "28Y-30Y"), d.rows.map { it[0] })
        assertEquals(listOf("91D", "5.86", FinFormat.DASH, "2026-11-19"), d.rows.first())
        assertEquals(listOf("28Y-30Y", "7.76", FinFormat.DASH, "2026-11-19"), d.rows.last())
        assertEquals(listOf(0.25, 0.5, 1.0, 2.0, 5.0, 10.0, 15.0, 30.0), d.chart.map { it.x })
        assertEquals(7.2752, d.chart.first { it.label == "9Y-10Y" }.y, 1e-9)
        assertEquals("2026-11-19", d.asOf)
        assertEquals("", d.servedBy)
        val keys = Feeds.ccil(fx("ccil-tenorwise-indicative-yields.html")).points.map { it.key }
        assertTrue(keys.containsAll(listOf("SDL 5Y", "SDL 10Y", "SDL 15Y")))
    }

    @Test fun brazilIsTheAnbimaPrefixedZeroCurveAtTheCircularVertices() {
        val http = Fake { if (it.startsWith(src("anbima").endpoint + "/CZ.asp")) fx("anbima-ettj.html") else null }
        val d = load("brazil", http)
        assertEquals(listOf("1M", "2M", "3M", "6M", "1Y", "2Y", "3Y", "4Y", "5Y", "10Y"), d.rows.map { it[0] })
        assertEquals(listOf("1M", "13.62", FinFormat.DASH, "2026-11-19"), d.rows.first())
        assertEquals(15.7016, d.chart.first { it.label == "4Y" }.y, 1e-9)
        assertEquals(4.0, d.chart.first { it.label == "4Y" }.x, 1e-9)
        assertEquals(15.5733, d.chart.last().y, 1e-9)
        assertEquals(10.0, d.chart.last().x, 1e-9)
        assertEquals("2026-11-19", d.asOf)
    }

    @Test fun indonesiaIsThePheiCurveWithYesterdayAsTheChange() {
        val http = Fake { if (it.startsWith(src("phei").endpoint + "/Data/HPW-dan-Imbal-Hasil")) fx("phei-hpw.html") else null }
        val d = load("indonesia", http)
        assertEquals(15, d.rows.size)
        assertEquals(listOf("0.1Y", "6.14", "-1.5", "2026-11-20"), d.rows.first())
        assertEquals(listOf("10Y", "7.75", "+1.2", "2026-11-20"), d.rows.first { it[0] == "10Y" })
        assertEquals(listOf("30Y", "7.75", "+1.1", "2026-11-20"), d.rows.last())
        assertEquals(0.1, d.chart.first().x, 1e-9)
        assertEquals(30.0, d.chart.last().x, 1e-9)
        // the page publishes every tenor to 40 years: the parser reads them all, the panel shows the declared ones
        assertEquals(41, Feeds.phei(fx("phei-hpw.html")).points.size)
    }

    @Test fun southAfricaIsTheSarbBillsBondsAndBands() {
        val http = Fake { if (it.startsWith(src("sarb").endpoint + "/WebIndicators/CurrentMarketRates")) fx("sarb-current-market-rates.json") else null }
        val d = load("south_africa", http)
        assertEquals(listOf("91D T-bill", "182D T-bill", "273D T-bill", "364D T-bill", "R2030", "5-10Y band", "R209 (2036)", "10Y+ band"), d.rows.map { it[0] })
        assertEquals(listOf("91D T-bill", "7.12", FinFormat.DASH, "2026-11-19"), d.rows.first())
        assertEquals(7.975, d.chart.first { it.label == "R2030" }.y, 1e-9)
        assertEquals(8.92, d.chart.last().y, 1e-9)
        // the policy rate, FX and gold the same answer carries are not points of the curve
        assertFalse(d.rows.any { it[0].contains("Policy") || it[0].contains("Rand") })
    }

    @Test fun mexicoIsOneBanxicoRequestPerAuctionSeries() {
        val bx = src("banxico")
        val http = Fake { url ->
            val id = Regex("[?&]s=(SF\\d+)%2CCF107%2C0").find(url)?.groupValues?.get(1)
            if (url.startsWith(bx.endpoint + "/consultaSerieGrafica.do") && id != null) runCatching { fx("banxico-$id.json") }.getOrNull() else null
        }
        val d = load("mexico", http)
        assertEquals(10, http.asked.size)
        assertTrue(http.asked.all { it.contains("versionSerie=LA-MAS-RECIENTE") })
        assertEquals(listOf("28D", "91D", "182D", "364D", "2Y", "3Y", "5Y", "10Y", "20Y", "30Y"), d.rows.map { it[0] })
        // the change is against the series' previous auction; N/E (-989898) observations are skipped
        assertEquals(listOf("28D", "6.19", "-1.0", "2026-11-19"), d.rows.first())
        assertEquals(listOf("30Y", "10.15", "+28.0", "2026-10-01"), d.rows.last())
        assertEquals(9.279921731033, d.chart.first { it.label == "10Y" }.y, 1e-9)
        assertEquals("2026-11-19", d.asOf)
    }

    @Test fun anUnreachablePublisherFallsBackToTheOecdRatesOnDbnomics() {
        val dbn = src("dbnomics")
        val files = mapOf("india" to "IND", "brazil" to "BRA", "indonesia" to "IDN", "south_africa" to "ZAF", "mexico" to "MEX")
        for ((id, a3) in files) {
            val http = Fake { url -> if (url.startsWith(dbn.endpoint + "/series?") && url.contains("series_ids=") && url.contains(a3 + ".M.IRLT")) fx("dbnomics-oecd-kei-$a3.json") else null }
            val d = load(id, http)
            assertEquals(id, dbn.name, d.servedBy)
            assertTrue(id, d.rows.any { it[0] == "10Y (OECD, monthly)" })
            assertTrue(id, d.chart.zipWithNext().all { (a, b) -> a.x <= b.x })
        }
        val mx = load("mexico", Fake { url -> if (url.startsWith(dbn.endpoint)) fx("dbnomics-oecd-kei-MEX.json") else null })
        assertEquals(listOf("O/N (OECD, monthly)", "5.36", "-7.0", "2026-05"), mx.rows.first())
        assertEquals(listOf("10Y (OECD, monthly)", "9.45", "+57.0", "2026-05"), mx.rows.last())
    }

    /** #1832 the placeholder "Other sovereign curves" area: Japan's curve is read key-free from the Ministry of Finance (the BoE's ships as a zipped workbook and ChinaBond's needs a POST session, so those two stay on the container's route of the CB Homepage). */
    @Test fun japanIsTheMinistryOfFinanceCurveWithTheHistoryFileWhenTheMonthHasNoRow() {
        val mof = src("mof_japan")
        val now = load("japan", Fake { if (it == mof.endpoint + "/jgbcme.csv") fx("mof-jgbcme.csv") else null })
        assertEquals(listOf("1Y", "2Y", "3Y", "4Y", "5Y", "6Y", "7Y", "8Y", "9Y", "10Y", "15Y", "20Y", "25Y", "30Y", "40Y"), now.rows.map { it[0] })
        assertEquals(listOf("10Y", "2.82", FinFormat.DASH, "2026-11-02"), now.rows.first { it[0] == "10Y" })
        assertEquals(40.0, now.chart.last().x, 0.0)
        assertEquals("", now.servedBy)
        // the month's file does not answer: the history file of the same shape, its last two days
        val hist = load("japan", Fake { if (it == mof.endpoint + "/historical/jgbcme_all.csv") fx("mof-jgbcme-all-tail.csv") else null })
        assertEquals(listOf("10Y", "2.82", "-5.9", "2026-10-30"), hist.rows.first { it[0] == "10Y" })
        // neither: the OECD rates
        val oecd = load("japan", Fake { if (it.startsWith(src("dbnomics").endpoint)) fx("dbnomics-oecd-kei-JPN.json") else null })
        assertEquals(src("dbnomics").name, oecd.servedBy)
        assertTrue(Feeds.mofJgb("Date,1Y\n2026/11/2,-\n").isEmpty)
    }

    @Test fun nothingAnsweringIsAnErrorNotAnEmptyCurve() {
        for (id in listOf("india", "brazil", "indonesia", "south_africa", "mexico", "japan")) {
            val r = runCatching { load(id, Fake { null }) }
            assertTrue(id, r.exceptionOrNull() is IOException)
        }
    }

    @Test fun theParsersReadNothingFromAnotherPage() {
        assertTrue(Feeds.parse("ccil_yields", "<html>maintenance</html>", Feeds.Opt(org.json.JSONObject())).isEmpty)
        assertTrue(Feeds.parse("anbima_ettj", "<html>sem dados</html>", Feeds.Opt(org.json.JSONObject())).isEmpty)
        assertTrue(Feeds.parse("phei_igsyc", "<html></html>", Feeds.Opt(org.json.JSONObject())).isEmpty)
        assertTrue(Feeds.parse("sarb_rates", "{\"error\":1}", Feeds.Opt(org.json.JSONObject())).isEmpty)
        assertTrue(Feeds.parse("banxico_sie", "{\"valores\":[[\"2026-11-19\",-989898.0]]}", Feeds.Opt(org.json.JSONObject(), "SF43936")).isEmpty)
        assertTrue(Feeds.parse("dbnomics_series", "{}", Feeds.Opt(org.json.JSONObject())).isEmpty)
        assertEquals(1008.0, Feeds.commaNum("1.008")!!, 0.0)
        assertEquals(13.6239, Feeds.commaNum("13,6239")!!, 1e-12)
        assertNotNull(Feeds.htmlText("<td>a&nbsp;b</td>").takeIf { it == "a b" })
    }
}
