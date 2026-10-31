package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.LocalDate

/** The engine end to end on a fake network: requests are built from the declared endpoint, answers are derived into rows and charts. */
class EngineTest {
    private val sources = Catalog.parse(DataFiles.sourcesJson)
    private val dashboards = Dashboards.parse(DataFiles.dashboardsJson)
    private val today = LocalDate.of(2026, 10, 30)

    private fun panel(dash: String, id: String) = Dashboards.byId(dashboards, dash)!!.panels.first { it.id == id }

    /** A fake network answering by a predicate on the address, recording every address asked. */
    private class Fake(val answer: (String) -> String?) : Http {
        val asked: MutableList<String> = java.util.Collections.synchronizedList(ArrayList<String>())
        override fun get(url: String): String {
            asked += url
            return answer(url) ?: throw IOException("no route")
        }

        override fun post(url: String): String = throw IOException("no post")
    }

    private val yields = mapOf("DGS1MO" to 4.0, "DGS3MO" to 4.1, "DGS6MO" to 4.2, "DGS1" to 4.3, "DGS2" to 4.4, "DGS3" to 4.5, "DGS5" to 4.6,
        "DGS7" to 4.7, "DGS10" to 4.8, "DGS20" to 5.2, "DGS30" to 5.1)

    private fun fredAnswer(url: String): String? {
        val id = Regex("[?&]id=([A-Z0-9]+)").find(url)?.groupValues?.get(1) ?: return null
        val v = yields[id] ?: return null
        return "observation_date,$id\n2026-10-27,${v - 0.1}\n2026-10-28,$v\n"
    }

    @Test fun yieldCurveIsBuiltFromOneRequestPerSeriesAtTheDeclaredEndpoint() {
        val fred = Catalog.byId(sources, "fred")!!
        val http = Fake(::fredAnswer)
        val d = Engine.load(panel("yield_curves", "ust"), fred, http, today)
        assertEquals(11, http.asked.size)
        assertTrue(http.asked.all { it.startsWith(fred.endpoint + "?id=DGS") && it.contains("cosd=2026-") })
        assertEquals(11, d.chart.size)
        assertEquals(1.0 / 12, d.chart.first().x, 1e-5)
        assertEquals(30.0, d.chart.last().x, 0.0)
        assertEquals("2026-10-28", d.asOf)
        assertEquals(listOf("1M", "4.00", "+10.0", "2026-10-28"), d.rows.first())
        assertEquals("30Y", d.rows.last()[0])
        assertEquals(5.1, d.chart.last().y, 1e-9)
    }

    @Test fun aSeriesThatFailsIsNotedAndTheRestStillDraw() {
        val fred = Catalog.byId(sources, "fred")!!
        val d = Engine.load(panel("yield_curves", "ust"), fred, Fake { if ("DGS30" in it) null else fredAnswer(it) }, today)
        assertEquals(10, d.chart.size)
        assertEquals("1 series unavailable", d.note)
    }

    @Test fun nothingBackIsAnErrorNotAnEmptyChart() {
        val fred = Catalog.byId(sources, "fred")!!
        try {
            Engine.load(panel("yield_curves", "ust"), fred, Fake { null }, today)
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("no data", e.message)
        }
    }

    @Test fun forwardRatesAreDerivedFromTheFredCurve() {
        val fred = Catalog.byId(sources, "fred")!!
        val d = Engine.load(panel("forward_rates", "fwd"), fred, Fake(::fredAnswer), today)
        val curve = yields.map { (k, v) -> (if (k.endsWith("MO")) k.removePrefix("DGS").removeSuffix("MO").toDouble() / 12 else k.removePrefix("DGS").toDouble()) to v }.sortedBy { it.first }
        // spec 9 table (table_start 1): per tenor the spot, the 1y-forward over the same tenor and the difference in bp
        val spot5 = curve.first { it.first == 5.0 }.second
        val fwd5 = Forward.rate(curve, 1.0, 5.0)
        val row = d.rows.first { it[0] == "5Y" }
        assertEquals(listOf("5Y", String.format(java.util.Locale.ROOT, "%.2f", spot5), String.format(java.util.Locale.ROOT, "%.2f", fwd5), FinFormat.signed((fwd5 - spot5) * 100, 0)), row)
        assertEquals(curve.size, d.rows.size)
        assertEquals(30, d.chart.size)
    }

    @Test fun fxCrossMatrixIsConsistentWithTheSpotRates() {
        val fred = Catalog.byId(sources, "fred")!!
        val spot = mapOf("DEXUSEU" to 1.12, "DEXUSUK" to 1.34, "DEXUSAL" to 0.70, "DEXJPUS" to 150.0, "DEXSZUS" to 0.80, "DEXCAUS" to 1.40, "DEXCHUS" to 6.7)
        val http = Fake { u -> Regex("[?&]id=([A-Z]+)").find(u)?.groupValues?.get(1)?.let { id -> spot[id]?.let { "observation_date,$id\n2026-10-22,$it\n2026-10-23,$it\n" } } }
        val d = Engine.load(panel("fx", "cross"), fred, http, today)
        assertEquals(listOf("1 of", "USD", "EUR", "GBP", "AUD", "JPY", "CHF", "CAD", "CNY"), d.columns)
        val row = d.rows.first { it[0] == "EUR" }
        assertEquals("1.1200", row[1])
        assertEquals(FinFormat.DASH, row[2])
        assertEquals("0.00667", d.rows.first { it[0] == "JPY" }[1])
        assertEquals("168.00", d.rows.first { it[0] == "EUR" }[5])
    }

    @Test fun worldBondsReadDbnomicsThroughTheDeclaredPathAndParams() {
        val dbn = Catalog.byId(sources, "dbnomics")!!
        val body = """{"series":{"docs":[{"series_code":"USA.M.IRLT.PA._Z._Z._Z","series_name":"US","period":["2026-04","2026-05"],"value":[4.1,4.3]},
            {"series_code":"JPN.M.IRLT.PA._Z._Z._Z","series_name":"JP","period":["2026-05"],"value":[1.7]}]}}"""
        val http = Fake { body }
        val d = Engine.load(panel("world_bonds", "yields"), dbn, http, today)
        assertTrue(http.asked.single().startsWith(dbn.endpoint + "/series/OECD/DSD_KEI@DF_KEI?"))
        assertEquals(listOf("United States", "4.30", "2026-05"), d.rows.first())
        assertEquals(2, d.chart.size)
        assertEquals("2026-05", d.asOf)
    }

    @Test fun newsRowsCarryTheirLinks() {
        val gd = Catalog.byId(sources, "gdelt")!!
        val body = """{"articles":[{"url":"https://a.example/x","title":"Yields rise","domain":"a.example","seendate":"20261030T120500Z"}]}"""
        val d = Engine.load(panel("news", "gdelt"), gd, Fake { body }, today)
        assertEquals(listOf(listOf("Yields rise", "a.example", "2026-10-30 12:05")), d.rows)
        assertEquals(listOf("https://a.example/x"), d.links)
    }

    @Test fun requestsAreEndpointPathAndEncodedParams() {
        assertEquals("https://h.example/p/x?a=1&b=a%20b%26c", Requests.url("https://h.example/", "/p/{k}", listOf("a" to "1", "b" to "a b&c"), mapOf("k" to "x")))
        assertEquals("https://h.example?id=S&cosd=2026-10-09", Requests.fred("https://h.example", "S", today, 21))
    }

    @Test fun modelPortfolioAllocationIsStatic() {
        val d = Dashboards.byId(dashboards, "mp_70_30_DM_EM")!!
        val a = Engine.alloc(d.panels.first(), d.legs)
        assertEquals(listOf(listOf("Developed Markets", "70%"), listOf("Emerging Markets", "30%")), a.rows)
        assertEquals(70.0, a.chart.first().y, 0.0)
    }

    @Test fun theCacheAnswersTwiceFromOneRequest() {
        var now = 0L
        val inner = Fake { "x" }
        val c = CachedHttp(inner, 1000L) { now }
        c.get("u"); c.get("u")
        assertEquals(1, inner.asked.size)
        now = 2000L
        c.get("u")
        assertEquals(2, inner.asked.size)
    }
}
