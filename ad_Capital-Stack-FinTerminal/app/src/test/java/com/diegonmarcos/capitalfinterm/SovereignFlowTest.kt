package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1727 the follow-ups of the performance portfolios: a sovereign's dollar return in its bond and currency legs, the sovereign-returns arithmetic and
 * its two answers (the container's route, the public yield series), the flow panel's layout, and the fund a factor portfolio is meant to follow.
 */
class SovereignFlowTest {
    private val decl = PerfPortfolios.parseDecl(DataFiles.portfoliosJson)!!
    private val baskets = PerfPortfolios.parse(DataFiles.perfPortfoliosJson)

    private fun q(pct: Double? = null, ytd: Double? = null) = Quote(1.0, 0L, "", "", "", null, null, pct, null, null, ytd, null)
    private fun c(t: String, w: Double, bucket: String) = PerfConstituent(t, t, t, bucket, mapOf("s" to w), "", "", "")
    private fun sov(t: String, symbol: String, fx: String, ccy: String) = PerfConstituent(t, symbol, t, "", mapOf("s" to 10.0), PerfPortfolios.BASIS_SOV, "", "", fx, ccy)
    private fun basket(vararg cs: PerfConstituent) = PerfBasket("k", "K", "etf", "src", "", "2026-11-12", cs.size, cs.toList(), emptyMap())

    @Test fun aSovereignsDollarReturnIsItsBondReturnCompoundedWithItsCurrency() {
        // USD/BRL up 25 % (the real down 20 %), the local bond fund up 10 %: 1.10 x 0.80 - 1 = -12 % in dollars
        val quotes = mapOf("BRL=X" to q(ytd = 25.0), "BRFUND" to q(ytd = 10.0), "INFUND" to q(ytd = -12.0), "INR=X" to q(ytd = 25.0))
        val br = PerfPortfolios.parts(sov("BR", "BRFUND", "BRL=X", "local"), quotes, "YTD")
        assertEquals(10.0, br.bond!!, 1e-9); assertEquals(-20.0, br.fx!!, 1e-9); assertEquals(-12.0, br.usd!!, 1e-9); assertEquals(false, br.approx)
        assertEquals(-12.0, PerfPortfolios.perf(sov("BR", "BRFUND", "BRL=X", "local"), quotes, "YTD")!!, 1e-9)
        // a fund priced in dollars already is the dollar return: the local return is that with the currency taken out
        val ind = PerfPortfolios.parts(sov("IN", "INFUND", "INR=X", PerfPortfolios.PROXY_USD), quotes, "YTD")
        assertEquals(-12.0, ind.usd!!, 1e-9); assertEquals(10.0, ind.bond!!, 1e-9); assertEquals(-20.0, ind.fx!!, 1e-9)
        // no fund: the sovereign-returns source's local return, flagged approximate; a period it does not answer has no figure
        val mx = sov("MX", "", "BRL=X", "")
        val src = mapOf("MX" to mapOf("YTD" to 5.0))
        val viaYield = PerfPortfolios.parts(mx, quotes, "YTD", src)
        assertEquals(5.0, viaYield.bond!!, 1e-9); assertEquals(-16.0, viaYield.usd!!, 1e-9); assertTrue(viaYield.approx)
        assertNull(PerfPortfolios.perf(mx, quotes, "1D", src)); assertNull(PerfPortfolios.perf(mx, quotes, "YTD"))
        // a dollar country has no currency leg; a currency that did not answer leaves the dollar return unknown but keeps the bond's
        val pa = PerfPortfolios.parts(sov("PA", "", "", ""), quotes, "YTD", mapOf("PA" to mapOf("YTD" to 3.0)))
        assertEquals(0.0, pa.fx!!, 0.0); assertEquals(3.0, pa.usd!!, 1e-9)
        val noFx = PerfPortfolios.parts(sov("ZZ", "BRFUND", "ZZZ=X", "local"), quotes, "YTD")
        assertEquals(10.0, noFx.bond!!, 1e-9); assertNull(noFx.fx); assertNull(noFx.usd)
        // the quote source is asked for the fund and the currency
        val b = basket(sov("BR", "BRFUND", "BRL=X", "local"), sov("MX", "", "MXN=X", ""))
        assertEquals(listOf("BRFUND", "BRL=X", "MXN=X"), PerfPortfolios.symbols(PerfPortfolios.rows(b, "s", quotes, "YTD", 0)))
    }

    @Test fun govEmCountriesAreSovereignsWithAFundOrTheYieldSource() {
        val g = decl.group("fi-gov-em")!!
        val b = baskets.getValue("fi-gov-em")
        assertTrue(g.perfNote.contains("dollar return") && g.perfNote.contains("FX %"))
        assertTrue(PerfPortfolios.isSovereign(b))
        assertTrue(b.constituents.all { it.basis == PerfPortfolios.BASIS_SOV && (it.fx.endsWith("=X") || it.fx.isEmpty() && it.bucket == "USD") })
        val funded = b.constituents.filter { it.symbol.isNotBlank() }
        assertEquals(setOf("CN", "IN", "BR", "KR", "ZA", "PL", "TH", "MY", "SA"), funded.map { it.ticker }.toSet())
        assertTrue(funded.all { it.proxy.isNotBlank() && it.proxyCcy in setOf("local", PerfPortfolios.PROXY_USD) && !it.symbol.endsWith("=X") })
        val s = SovereignReturns.parseDecl(DataFiles.portfoliosJson)!!
        val approximated = b.constituents.filter { it.symbol.isBlank() && it.ticker in s.a3 }.map { it.ticker }.toSet()
        assertTrue("the yield source covers countries without a fund: $approximated", approximated.containsAll(setOf("MX", "CO", "CL", "HU")))
        // Sweden (Gov Bonds DM) has its listed fund like every other developed market
        val dm = baskets.getValue("fi-gov-dm")
        assertTrue(dm.constituents.all { it.symbol.isNotBlank() && it.proxy.isNotBlank() && it.basis.isEmpty() })
        assertEquals("XACT-OBLIGATION.ST", dm.constituents.first { it.ticker == "SE" }.symbol)
        assertTrue(!PerfPortfolios.isSovereign(dm))
    }

    @Test fun theFlowKeepsEveryWeightFromThePortfolioToTheConstituents() {
        val b = basket(c("A", 30.0, "Tech"), c("B", 20.0, "Tech"), c("C", 25.0, "Energy"), c("D", 15.0, "Banks"), c("E", 6.0, "Retail"), c("F", 4.0, "Mining"))
        val rows = PerfPortfolios.rows(b, "s", emptyMap(), "1D", 0)
        val f = PerfPortfolios.flow(rows, 3, 3, "K", "Other", "Rest")
        val col = { n: Int -> f.nodes.filter { it.col == n } }
        assertEquals(listOf("K"), col(0).map { it.label }); assertEquals(100.0, col(0).single().value, 1e-9)
        // the three heaviest buckets, the others folded; the three largest constituents in the order of their buckets, everything else in Rest
        assertEquals(listOf("Tech", "Energy", "Banks", "Other"), col(1).map { it.label })
        assertEquals(listOf(50.0, 25.0, 15.0, 10.0), col(1).map { it.value })
        assertEquals(listOf("A", "B", "C", "Rest"), col(2).map { it.label })
        assertEquals(25.0, col(2).last().value, 1e-9)
        // conservation: every column carries the whole weight, and what enters or leaves a node is the node's weight
        for (n in 0..2) assertEquals(100.0, col(n).sumOf { it.value }, 1e-9)
        f.nodes.forEachIndexed { i, node ->
            if (node.col < 2) assertEquals(node.label, node.value, f.links.filter { it.from == i }.sumOf { it.value }, 1e-9)
            if (node.col > 0) assertEquals(node.label, node.value, f.links.filter { it.to == i }.sumOf { it.value }, 1e-9)
        }
        // a ribbon stays inside its two nodes, the nodes of a column do not overlap and fit the height
        for (l in f.links) {
            val s = f.nodes[l.from]; val t = f.nodes[l.to]
            assertTrue(l.sy0 >= s.y0 - 1e-9 && l.sy1 <= s.y1 + 1e-9 && l.ty0 >= t.y0 - 1e-9 && l.ty1 <= t.y1 + 1e-9)
        }
        for (n in 1..2) { val ns = col(n); for (i in 1 until ns.size) assertTrue(ns[i].y0 >= ns[i - 1].y1); assertTrue(ns.last().y1 <= 1.0 + 1e-9) }
        assertTrue(PerfPortfolios.flow(emptyList(), 3, 3, "K", "Other", "Rest").nodes.isEmpty())
        assertTrue(decl.sankeyBuckets in 3..7 && decl.sankeyHoldings in 5..15)
        // every declared portfolio lays out: one portfolio node, at least one bucket, the whole weight in the last column
        for (g in decl.groups) {
            val rs = PerfPortfolios.rows(baskets.getValue(g.key), PerfPortfolios.schemeOf(g, null), emptyMap(), "1D", decl.defaultTop)
            val fl = PerfPortfolios.flow(rs, decl.sankeyBuckets, decl.sankeyHoldings, g.label, "Other", "Rest")
            assertEquals(g.key, PerfPortfolios.weightSum(rs), fl.nodes.filter { it.col == 2 }.sumOf { it.value }, 1e-6)
            assertTrue(g.key, fl.nodes.count { it.col == 1 } in 1..decl.sankeyBuckets + 1 && fl.nodes.count { it.col == 2 } <= decl.sankeyHoldings + 1)
        }
    }

    @Test fun sovereignReturnsFollowTheDeclaredYieldArithmetic() {
        // a flat 12 % yield: each month returns the carry, 1 %
        val flat = (1..12).associate { String.format("2025-%02d", it) to 12.0 }
        val mo = SovereignReturns.monthly(flat, 10.0)
        assertEquals(11, mo.size); assertEquals(0.01, mo.getValue("2025-12"), 1e-12)
        assertEquals(1.0, SovereignReturns.window(mo, 1)!!, 1e-9)
        assertNull("a window with a missing month has no figure", SovereignReturns.window(mo, 12))
        // a 10-year par bond at 5 % has a modified duration of about 7.79; a 1 point rise in a month costs about that much, less the carry
        assertEquals(7.794, SovereignReturns.parDuration(0.05, 10.0), 0.01); assertEquals(10.0, SovereignReturns.parDuration(0.0, 10.0), 0.0)
        val up = SovereignReturns.monthly(mapOf("2025-12" to 5.0, "2026-01" to 6.0, "2026-02" to 6.0), 10.0)
        assertEquals(0.05 / 12 - 7.794 * 0.01, up.getValue("2026-01"), 1e-4)
        val w = SovereignReturns.windows(up)
        assertEquals(0.5, w.getValue(SovereignReturns.M1), 1e-9)                       // February: the carry of a 6 % yield
        assertEquals(((1 + up.getValue("2026-01")) * 1.005 - 1) * 100, w.getValue(SovereignReturns.YTD), 1e-9)   // January and February
        assertNull(w[SovereignReturns.Y1])
        assertEquals("2026-01", SovereignReturns.nextMonth("2025-12")); assertEquals("2025-07", SovereignReturns.nextMonth("2025-06"))
    }

    @Test fun theDeclarationThePublicYieldAnswerAndTheContainersAnswerAreRead() {
        val d = SovereignReturns.parseDecl(DataFiles.portfoliosJson)!!
        assertEquals("cloud_fincept", d.primarySource); assertEquals("/v1/bonds/sovereign-returns", d.api); assertEquals("dbnomics", d.fallbackSource)
        assertEquals(mapOf("1M" to "m1", "YTD" to "ytd"), d.periods); assertEquals("SWE", d.a3["SE"]); assertEquals(10.0, d.tenorYears, 0.0)
        // the two sources are declared in the catalog, and the container's route is the one universe.json declares for it
        val sources = Catalog.parse(DataFiles.sourcesJson)
        assertTrue(sources.any { it.id == d.primarySource && it.fleetEndpoint.isNotBlank() } && sources.any { it.id == d.fallbackSource && it.endpoint.startsWith("https://") })
        assertEquals(d.api, org.json.JSONObject(DataFiles.universeJson).getJSONObject("sovereign_returns").getString("route"))
        val url = SovereignReturns.fallbackUrl(d, "https://h.test/v22/")
        assertTrue(url, url.startsWith("https://h.test/v22/series?series_ids=OECD%2FDSD_KEI%40DF_KEI%2F") && url.contains("%2FSWE.M.IRLT.PA._Z._Z._Z") && url.contains("%2C") && url.contains("&observations=1"))
        val body = "{\"series\":{\"docs\":[{\"series_code\":\"MEX.M.IRLT.PA._Z._Z._Z\",\"period\":[\"2025-12\",\"2026-01\",\"2026-02\"],\"value\":[5.0,6.0,6.0]}," +
            "{\"series_code\":\"SWE.M.IRLT.PA._Z._Z._Z\",\"period\":[\"2026-01\"],\"value\":[2.0]},{\"series_code\":\"XXX.M.IRLT.PA._Z._Z._Z\",\"period\":[\"2026-01\",\"2026-02\"],\"value\":[1,1]}," +
            "{\"series_code\":\"COL.M.IRLT.PA._Z._Z._Z\",\"period\":[\"2026-01\",\"2026-02\",\"2026-03\"],\"value\":[10.0,\"NA\",10.0]}]}}"
        val rows = SovereignReturns.parseYields(body, d, "DBnomics")
        assertEquals(setOf("MX"), rows.keys)                                           // one month, an undeclared country and a gap give no return
        assertEquals("2026-02", rows.getValue("MX").asOf)
        val by = SovereignReturns.byPeriod(rows, d)
        assertEquals(0.5, by.getValue("MX").getValue("1M"), 1e-9); assertTrue(by.getValue("MX").containsKey("YTD"))
        val api = SovereignReturns.parseApi("{\"rows\":[{\"country\":\"SE\",\"last_month\":\"2026-10\",\"local\":{\"m1\":0.004,\"ytd\":null,\"y1\":0.031}},{\"country\":\"ZZ\",\"local\":{}}]}", "API")
        assertEquals(setOf("SE"), api.keys); assertEquals(0.4, api.getValue("SE").local.getValue("m1"), 1e-9); assertEquals(3.1, api.getValue("SE").local.getValue("y1"), 1e-9)
        assertNull(api.getValue("SE").local["ytd"])
        assertTrue(SovereignReturns.parseYields("not json", d, "x").isEmpty()); assertTrue(SovereignReturns.parseApi("{}", "x").isEmpty()); assertNull(SovereignReturns.parseDecl("{}"))
    }

    @Test fun aFactorPortfolioNamesTheFundItIsMeantToFollow() {
        for (g in decl.groups.filter { it.section == "eq" }) assertTrue(g.key, g.intended.isNotBlank() && g.intended != g.etf)
        assertEquals("MTUM", decl.group("eq-momentum")!!.intended); assertEquals("", decl.group("fi-junk")!!.intended)
    }
}
