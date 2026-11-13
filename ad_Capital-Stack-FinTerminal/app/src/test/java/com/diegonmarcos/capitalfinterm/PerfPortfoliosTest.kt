package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #1722 the performance portfolios (EQ > Performance, the FI bond groups): the declaration against the navigation, the runtime dataset's weights under
 * every declared scheme, the two modes, and the page's arithmetic (performance, contribution, totals, sort, attribution waterfall, bubbles).
 */
class PerfPortfoliosTest {
    private val decl = PerfPortfolios.parseDecl(DataFiles.portfoliosJson)!!
    private val baskets = PerfPortfolios.parse(DataFiles.perfPortfoliosJson)
    private val stacks = JSONObject(DataFiles.buildJson).getJSONObject("ui").getJSONObject("stacks").toString()

    private fun q(pct: Double? = null, w1: Double? = null, m1: Double? = null, ytd: Double? = null) = Quote(1.0, 0L, "", "", "", null, null, pct, w1, m1, ytd, null)
    private fun c(t: String, w: Double, bucket: String = "", symbol: String = t, basis: String = "") = PerfConstituent(t, symbol, t, bucket, mapOf("s" to w), basis, "", "")
    private fun basket(vararg cs: PerfConstituent) = PerfBasket("k", "K", "etf", "src", "", "2026-11-12", cs.size, cs.toList(), emptyMap())

    // ── the declaration and the navigation ──

    @Test fun everyPerformancePageOfTheNavIsADeclaredPortfolioAndBack() {
        val pages = listOf("eq", "fi").flatMap { s -> Pages.entries(stacks, s).filter { it.kind == Pages.KIND_PERF_PORTFOLIO }.map { s to it } }
        assertEquals(17, pages.size)
        for ((section, e) in pages) {
            val g = decl.group(e.view)
            assertNotNull("${e.title}: no portfolio ${e.view} in data/portfolios.json", g)
            assertEquals(e.title, section, g!!.section)
            assertEquals(e.view, e.title, g.label)
            assertEquals("a page's id is its portfolio's key", e.view, e.page)
            assertTrue("${e.title} has no function mnemonic", e.fn.isNotBlank())
        }
        assertEquals(decl.groups.map { it.key }.toSet(), pages.map { it.second.view }.toSet())
    }

    @Test fun eqPerformanceIsTheTenFactorPortfoliosAndFiIsTheBondGroups() {
        assertEquals(listOf("Growth", "Value", "Quality", "Vol", "Top", "Cyclical", "Defensive", "Dividend", "Momentum", "Small"), decl.groups.filter { it.section == "eq" }.map { it.label })
        assertEquals(setOf("All", "Gov Bonds DM", "Gov Bonds EM", "AAA Bonds", "AA-A Bonds", "BBB Bonds", "Junk"), decl.groups.filter { it.section == "fi" }.map { it.label }.toSet())
        // a factor portfolio is a fund's published holdings: it names the fund, and the fund is its first benchmark (Top is the S&P 500 itself)
        for (g in decl.groups.filter { it.section == "eq" }) {
            assertEquals(g.key, "etf", g.kind)
            assertTrue(g.key, g.etf.isNotBlank() && g.etfName.isNotBlank())
            assertEquals(g.key, g.etf, g.benchmarks.first().symbol)
        }
    }

    @Test fun bothModesAreDeclaredAndThePageOpensOnTheTable() {
        assertEquals(listOf(PerfPortfolios.MODE_TABLE, PerfPortfolios.MODE_GRAPHIC), decl.modes)
        assertEquals(PerfPortfolios.MODE_TABLE, decl.defaultMode)
        assertEquals("Table", decl.word("table")); assertEquals("Graphic", decl.word("graphic")); assertEquals("See all", decl.word("see_all"))
        assertEquals(listOf("1D", "1W", "1M", "YTD"), decl.periods)
        assertTrue(decl.defaultPeriod in decl.periods && decl.bubbleX in decl.periods && decl.bubbleY in decl.periods)
        assertTrue(decl.defaultTop in decl.tops && 0 in decl.tops)
        assertTrue(decl.cardTop in 3..6)
    }

    @Test fun everySchemeIsDeclaredWithALabelAndAMethodAndTheDefaultIsOneOfThem() {
        for (g in decl.groups) {
            assertTrue(g.key, g.schemes.isNotEmpty())
            assertNotNull("${g.key}: default scheme ${g.defaultScheme}", g.scheme(g.defaultScheme))
            for (s in g.schemes) assertTrue("${g.key}/${s.id}", s.id.isNotBlank() && s.label.isNotBlank() && s.method.length > 20)
            assertEquals(g.defaultScheme, PerfPortfolios.schemeOf(g, null))
            assertEquals(g.defaultScheme, PerfPortfolios.schemeOf(g, "no-such-scheme"))
            assertEquals(g.schemes.last().id, PerfPortfolios.schemeOf(g, g.schemes.last().id))
        }
    }

    // ── the runtime dataset: weights ──

    @Test fun theDatasetCarriesEveryDeclaredPortfolioWithWeightsForEveryDeclaredScheme() {
        assertEquals(decl.groups.map { it.key }.toSet(), baskets.keys)
        for (g in decl.groups) {
            val b = baskets.getValue(g.key)
            assertTrue(g.key, b.source.isNotBlank() && b.asOf.isNotBlank())
            assertEquals("${g.key}: the dataset's schemes are the declared ones", g.schemes.map { it.id }.toSet(), b.schemeIds)
            for (s in g.schemes) {
                val sum = b.constituents.sumOf { it.weights[s.id] ?: 0.0 }
                assertTrue("${g.key}/${s.id} weights sum to $sum", sum > 20.0 && sum <= 100.5)
                assertTrue(b.constituents.all { (it.weights[s.id] ?: 0.0) >= 0.0 })
            }
            assertEquals("${g.key} repeats a constituent", b.constituents.size, b.constituents.map { it.ticker + "|" + it.bucket }.toSet().size)
        }
    }

    @Test fun theDatasetStoresNoPriceAndNoPerformance() {
        val text = DataFiles.perfPortfoliosJson
        for (k in listOf("\"price\"", "\"last\"", "\"perf\"", "\"return\"", "\"pct\"", "\"ytd\"")) assertFalse("the dataset stores $k", text.contains(k))
        assertEquals(1, JSONObject(text).getInt("schemaVersion"))
    }

    @Test fun govDmIsGdpWeightedByDefaultWithTheUsdIndexAndLiquiditySchemesAsOptions() {
        val g = decl.group("fi-gov-dm")!!
        assertEquals(listOf("gdp", "usd_index", "liquidity"), g.schemes.map { it.id })
        assertEquals("gdp", g.defaultScheme)
        val b = baskets.getValue("fi-gov-dm")
        fun w(cc: String, s: String) = b.constituents.first { it.ticker == cc }.weights[s]
        for (s in g.schemes) assertEquals(s.id, 100.0, b.constituents.sumOf { it.weights[s.id] ?: 0.0 }, 0.01)
        // GDP: the US is the largest economy of the group, then the next ones in the order of their GDP
        val gdp = PerfPortfolios.rows(b, "gdp", emptyMap(), "1D", 0)
        assertEquals("US", gdp.first().c.ticker)
        assertTrue(w("US", "gdp")!! > 40.0)
        assertEquals(gdp.map { it.weight }, gdp.map { it.weight }.sortedDescending())
        // USD index: the declared DXY basket, the dollar itself has no weight, the euro's 57.6 is split between the euro members
        assertNull(w("US", "usd_index"))
        assertEquals(13.6, w("JP", "usd_index")!!, 1e-6); assertEquals(11.9, w("GB", "usd_index")!!, 1e-6); assertEquals(9.1, w("CA", "usd_index")!!, 1e-6)
        assertEquals(4.2, w("SE", "usd_index")!!, 1e-6); assertEquals(3.6, w("CH", "usd_index")!!, 1e-6)
        assertEquals(57.6, listOf("DE", "FR", "IT", "ES").sumOf { w(it, "usd_index")!! }, 1e-3)
        assertTrue(w("DE", "usd_index")!! > w("FR", "usd_index")!! && w("FR", "usd_index")!! > w("IT", "usd_index")!!)
        // liquidity: the international treasury fund's market values; the US is not in that index
        assertNull(w("US", "liquidity"))
        assertEquals("JP", PerfPortfolios.rows(b, "liquidity", emptyMap(), "1D", 0).first().c.ticker)
        // each scheme says where its weights come from
        assertTrue(PerfPortfolios.sourceOf(b, "gdp").source.contains("IMF"))
        assertTrue(PerfPortfolios.sourceOf(b, "liquidity").source.contains("BWX"))
        assertTrue(PerfPortfolios.sourceOf(b, "usd_index").source.contains("Dollar Index"))
        // a country's figure is a listed fund of its government bonds, Sweden included (#1727: SovereignFlowTest)
        assertTrue(b.constituents.all { it.symbol.isNotBlank() && it.proxy.isNotBlank() })
    }

    @Test fun govEmUsesTheWeightsOfTheEmGovernmentBondFunds() {
        val g = decl.group("fi-gov-em")!!
        assertEquals(listOf("emb", "local"), g.schemes.map { it.id })
        assertEquals("emb", g.defaultScheme)
        assertTrue(g.scheme("emb")!!.method.contains("EMB")); assertTrue(g.scheme("local")!!.method.contains("EBND"))
        assertEquals(listOf("EMB", "EMLC", "EBND"), g.benchmarks.map { it.symbol })
        val b = baskets.getValue("fi-gov-em")
        assertTrue(PerfPortfolios.sourceOf(b, "emb").source.contains("EMB")); assertTrue(PerfPortfolios.sourceOf(b, "emb").sourceUrl.startsWith("https://www.ishares.com/"))
        assertTrue(PerfPortfolios.sourceOf(b, "local").source.contains("EBND")); assertTrue(PerfPortfolios.sourceOf(b, "local").sourceUrl.startsWith("https://www.ssga.com/"))
        // the fund's own country weights: many countries, none dominant (the EMB index caps a country), what the fund lists outside them is declared
        val emb = PerfPortfolios.rows(b, "emb", emptyMap(), "1D", 0)
        assertTrue(emb.size >= 20)
        assertTrue(emb.first().weight < 12.0)
        val listed = PerfPortfolios.weightSum(emb)
        assertEquals(100.0, listed + PerfPortfolios.sourceOf(b, "emb").unlisted!!, 0.6)
        assertTrue(PerfPortfolios.rows(b, "local", emptyMap(), "1D", 0).size >= 15)
        // no developed market is counted as an EM sovereign
        val dm = baskets.getValue("fi-gov-dm").constituents.map { it.ticker }.toSet()
        assertTrue(b.constituents.none { it.ticker in dm })
        // #1727 a country's figure is the dollar return of its government bonds, with the currency as its own leg (SovereignFlowTest)
        assertTrue(g.perfNote.contains("currency"))
        assertTrue(b.constituents.all { it.basis == PerfPortfolios.BASIS_SOV })
    }

    @Test fun topIsTheTwentyLargestOfTheSp500RescaledAndAFactorPortfolioIsLargestFirst() {
        val top = baskets.getValue("eq-top")
        assertEquals(20, top.constituents.size)
        assertEquals(100.0, top.constituents.sumOf { it.weights.getValue("etf") }, 0.01)
        assertTrue(top.count > 400)
        for (g in decl.groups.filter { it.section == "eq" }) {
            val b = baskets.getValue(g.key)
            val ws = b.constituents.map { it.weights.getValue("etf") }
            assertEquals(g.key, ws.sortedDescending(), ws)
            assertTrue(g.key, b.constituents.size <= 100 && b.count >= b.constituents.size)
            assertTrue("${g.key} names its fund", b.source.contains("(" + g.etf + ")"))
        }
    }

    @Test fun theRatingGroupsAreEqualWeightAndAllIsTheSixGroupsAtOneSixthEach() {
        for (k in listOf("fi-aaa", "fi-aa-a", "fi-bbb", "fi-junk")) {
            val b = baskets.getValue(k)
            val ws = b.constituents.map { it.weights.getValue("equal") }
            assertEquals(k, 100.0, ws.sum(), 0.01)
            assertEquals(k, ws.max(), ws.min(), 1e-9)
            assertTrue(b.constituents.all { it.symbol == it.ticker && it.bucket.isNotBlank() })
        }
        val all = baskets.getValue("fi-all")
        val byGroup = all.constituents.groupBy { it.bucket }.mapValues { (_, v) -> v.sumOf { it.weights.getValue("equal_groups") } }
        assertEquals(setOf("Gov Bonds DM", "Gov Bonds EM", "AAA Bonds", "AA-A Bonds", "BBB Bonds", "Junk"), byGroup.keys)
        for ((k, v) in byGroup) assertEquals(k, 100.0 / 6.0, v, 0.01)
    }

    // ── the page's arithmetic ──

    @Test fun performanceIsTheInstrumentsMoveAndACurrencysMoveIsTheInverseOfItsDollarRate() {
        val quotes = mapOf("AAA" to q(pct = 1.5, ytd = 20.0), "BRL=X" to q(pct = 25.0), "BAD=X" to q(pct = -100.0))
        assertEquals(1.5, PerfPortfolios.perf(c("AAA", 1.0), quotes, "1D")!!, 1e-12)
        assertEquals(20.0, PerfPortfolios.perf(c("AAA", 1.0), quotes, "YTD")!!, 1e-12)
        assertNull("the source gave no 1W", PerfPortfolios.perf(c("AAA", 1.0), quotes, "1W"))
        assertNull("no quote", PerfPortfolios.perf(c("ZZZ", 1.0), quotes, "1D"))
        assertNull("no listed instrument", PerfPortfolios.perf(c("SE", 1.0, symbol = ""), quotes, "1D"))
        // USD/BRL up 25 % = the real down 20 % against the dollar
        assertEquals(-20.0, PerfPortfolios.perf(c("BR", 1.0, symbol = "BRL=X", basis = PerfPortfolios.BASIS_FX), quotes, "1D")!!, 1e-9)
        assertNull(PerfPortfolios.perf(c("XX", 1.0, symbol = "BAD=X", basis = PerfPortfolios.BASIS_FX), quotes, "1D"))
        assertEquals(0.0, PerfPortfolios.perf(c("PA", 1.0, symbol = "", basis = PerfPortfolios.BASIS_USD), quotes, "1D")!!, 0.0)
    }

    @Test fun rowsTotalsAndThePortfolioReturn() {
        val b = basket(c("A", 50.0), c("B", 30.0), c("C", 20.0), PerfConstituent("Z", "Z", "Z", "", mapOf("other" to 9.0), "", "", ""))
        val quotes = mapOf("A" to q(pct = 2.0), "B" to q(pct = -1.0))
        val rows = PerfPortfolios.rows(b, "s", quotes, "1D", 0)
        assertEquals(listOf("A", "B", "C"), rows.map { it.c.ticker })   // Z carries no weight under this scheme
        assertEquals(listOf(1.0, -0.3, null), rows.map { it.contribution })
        assertEquals(100.0, PerfPortfolios.weightSum(rows), 1e-12)
        val (contrib, covered) = PerfPortfolios.total(rows)!!
        assertEquals(0.7, contrib, 1e-12); assertEquals(80.0, covered, 1e-12)
        // the portfolio's return is the weighted mean of what is quoted: 0.7 pp on 80 % of the weight
        assertEquals(0.875, PerfPortfolios.portfolioReturn(rows)!!, 1e-12)
        assertEquals(1 to 1, PerfPortfolios.advDec(rows))
        assertEquals(listOf("A", "B"), PerfPortfolios.rows(b, "s", quotes, "1D", 2).map { it.c.ticker })
        assertEquals(listOf("A", "B"), PerfPortfolios.symbols(PerfPortfolios.rows(b, "s", quotes, "1D", 2)))
        assertNull(PerfPortfolios.total(PerfPortfolios.rows(b, "s", emptyMap(), "1D", 0)))
        assertNull(PerfPortfolios.portfolioReturn(PerfPortfolios.rows(b, "s", emptyMap(), "1D", 0)))
        assertEquals(0.5, PerfPortfolios.active(1.5, 1.0)!!, 1e-12); assertNull(PerfPortfolios.active(null, 1.0)); assertNull(PerfPortfolios.active(1.0, null))
        // the totals line: label, weight, a return per period, the contribution
        val totals = PerfPortfolios.totals(b, "s", quotes, listOf("1D", "YTD"), "1D", 0, "Total")
        assertEquals(listOf("Total", "", "", "100.00", "+0.88", FinFormat.DASH, "+0.70"), totals)
        val cells = PerfPortfolios.cells(rows, quotes, listOf("1D", "YTD"))
        assertEquals(listOf("A", "A", "", "50.00", "+2.00", FinFormat.DASH, "+1.00"), cells[0])
        assertEquals(1f, PerfPortfolios.barShare(1.0, rows), 1e-6f); assertEquals(0.3f, PerfPortfolios.barShare(-0.3, rows), 1e-6f); assertEquals(0f, PerfPortfolios.barShare(null, rows), 0f)
    }

    @Test fun theTableSortsByAnyColumnAndALineWithoutAFigureSortsLast() {
        val b = basket(c("B", 30.0, "y"), c("A", 50.0, "z"), c("C", 20.0, "x"))
        val rows = PerfPortfolios.rows(b, "s", mapOf("A" to q(pct = -2.0), "C" to q(pct = 5.0)), "1D", 0)
        fun order(key: String, desc: Boolean) = PerfPortfolios.sort(rows, key, desc).map { it.c.ticker }
        assertEquals(listOf("A", "B", "C"), order(PerfPortfolios.SORT_WEIGHT, true)); assertEquals(listOf("C", "B", "A"), order(PerfPortfolios.SORT_WEIGHT, false))
        assertEquals(listOf("C", "A", "B"), order(PerfPortfolios.SORT_PERF, true)); assertEquals(listOf("A", "C", "B"), order(PerfPortfolios.SORT_PERF, false))
        assertEquals(listOf("C", "A", "B"), order(PerfPortfolios.SORT_CONTRIB, true))
        assertEquals(listOf("A", "B", "C"), order(PerfPortfolios.SORT_TICKER, false)); assertEquals(listOf("C", "B", "A"), order(PerfPortfolios.SORT_TICKER, true))
        assertEquals(listOf("C", "B", "A"), order(PerfPortfolios.SORT_BUCKET, false))
        assertEquals(6, PerfPortfolios.SORTS.size)
    }

    @Test fun theWaterfallChainsTheBucketsToTheTotal() {
        val b = basket(c("A", 40.0, "Tech"), c("B", 20.0, "Tech"), c("C", 25.0, "Energy"), c("D", 15.0, "Banks"))
        val quotes = mapOf("A" to q(pct = 5.0), "B" to q(pct = -5.0), "C" to q(pct = -4.0), "D" to q(pct = 2.0))
        val steps = PerfPortfolios.attribution(PerfPortfolios.rows(b, "s", quotes, "1D", 0), 10, "Rest", "Total")
        // Tech +2.0 - 1.0 = +1.0, Banks +0.3, Energy -1.0; the largest gain first, then the total from zero
        assertEquals(listOf("Tech", "Banks", "Energy", "Total"), steps.map { it.label })
        assertEquals(listOf(1.0, 0.3, -1.0, 0.3), steps.map { Math.round(it.value * 1e9) / 1e9 })
        for (i in 1 until steps.size - 1) assertEquals("each bar starts where the previous one ended", steps[i - 1].end, steps[i].start, 1e-12)
        assertEquals(0.0, steps.first().start, 0.0)
        val total = steps.last()
        assertTrue(total.total); assertEquals(0.0, total.start, 0.0); assertEquals(steps[steps.size - 2].end, total.end, 1e-12)
        val (lo, hi) = PerfPortfolios.span(steps)
        assertEquals(0.0, lo, 1e-12); assertEquals(1.3, hi, 1e-9)
        // too many bars: the smallest moves fold into the rest, the total is unchanged
        val folded = PerfPortfolios.attribution(PerfPortfolios.rows(b, "s", quotes, "1D", 0), 3, "Rest", "Total")
        assertEquals(listOf("Tech", "Energy", "Rest", "Total"), folded.map { it.label })
        assertEquals(total.end, folded.last().end, 1e-12)
        // one bucket only: the bars are the holdings themselves
        val flat = basket(c("A", 60.0, "Other"), c("B", 40.0, "Other"))
        assertEquals(listOf("A", "B", "Total"), PerfPortfolios.attribution(PerfPortfolios.rows(flat, "s", quotes, "1D", 0), 10, "Rest", "Total").map { it.label })
        assertTrue(PerfPortfolios.attribution(PerfPortfolios.rows(b, "s", emptyMap(), "1D", 0), 10, "Rest", "Total").isEmpty())
    }

    @Test fun aBubbleNeedsBothPeriodsAndTheMedianIsTheMiddle() {
        val b = basket(c("A", 60.0), c("B", 30.0), c("C", 10.0))
        val quotes = mapOf("A" to q(m1 = 3.0, ytd = 12.0), "B" to q(m1 = -1.0), "C" to q(m1 = 2.0, ytd = -4.0))
        val bubbles = PerfPortfolios.bubbles(b, "s", quotes, "1M", "YTD", 0)
        assertEquals(listOf("A", "C"), bubbles.map { it.ticker })
        assertEquals(PerfBubble("A", 3.0, 12.0, 60.0), bubbles.first())
        assertEquals(4.0, PerfPortfolios.median(bubbles.map { it.y })!!, 1e-12)
        assertEquals(2.0, PerfPortfolios.median(listOf(5.0, 1.0, 2.0))!!, 1e-12)
        assertNull(PerfPortfolios.median(emptyList()))
    }

    @Test fun aBrokenBodyIsEmptyNeverACrash() {
        assertNull(PerfPortfolios.parseDecl("not json")); assertNull(PerfPortfolios.parseDecl("{}"))
        assertTrue(PerfPortfolios.parse("not json").isEmpty()); assertTrue(PerfPortfolios.parse("{}").isEmpty())
        assertTrue(PerfPortfolios.parse("""{"portfolios":{"x":{"constituents":[{"ticker":"","weights":{"s":1}},{"ticker":"A","weights":{"s":0}},{"ticker":"B"}]}}}""").isEmpty())
        val one = PerfPortfolios.parse("""{"portfolios":{"x":{"constituents":[{"ticker":"A","weights":{"s":2.5,"t":-1}}]}}}""").getValue("x")
        assertEquals(mapOf("s" to 2.5), one.constituents.single().weights)
        assertEquals("A", one.constituents.single().name)
    }

    @Test fun aHomepageCardListsTheLargestConstituentsOfTheDefaultScheme() {
        for (g in decl.groups) {
            val b = baskets.getValue(g.key)
            val rows = PerfPortfolios.rows(b, PerfPortfolios.schemeOf(g, null), emptyMap(), decl.defaultPeriod, decl.cardTop)
            assertTrue(g.key, rows.isNotEmpty() && rows.size <= decl.cardTop)
            assertEquals(g.key, rows.map { it.weight }.sortedDescending(), rows.map { it.weight })
        }
    }
}
