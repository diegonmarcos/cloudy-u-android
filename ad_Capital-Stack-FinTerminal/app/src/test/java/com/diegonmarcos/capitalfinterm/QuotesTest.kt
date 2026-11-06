package com.diegonmarcos.capitalfinterm

import java.io.IOException
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1539 the price boards: the source's response fixture, the FX cross-rate math, expiry rules, contracts, term structure, implied rate, hedge math, and n/a instead of invented values. */
class QuotesTest {
    private val decl = QuotesDecl.parse(DataFiles.quotesJson)
    private val today = LocalDate.of(2026, 11, 6)
    private fun q(last: Double, pct: Double? = null, w1: Double? = null, vol: Long? = null) = Quote(last, 1793973020L, "X", "USD", "n", null, null, pct, w1, null, null, vol)
    private fun snap(vararg e: Pair<String, Quote>) = QuotesSnapshot("2026-11-06", "src", "delayed", mapOf(*e))
    private fun spec(id: String) = decl.spec(id)!!

    @Test fun theChartAnswerBecomesAQuoteWithTheSourcesTimestampAndTheChangeAgainstThePreviousClose() {
        val x = Quotes.parseChart(DataFiles.quotesFixture("yahoo-chart.json"))!!
        assertEquals(91.09, x.last, 1e-9); assertEquals(1793973020L, x.asof); assertEquals("NY Mercantile", x.exchange); assertEquals("USD", x.currency); assertEquals("Crude Oil Dec 26", x.name)
        assertEquals(90.52, x.prev!!, 1e-5); assertEquals(x.last - x.prev!!, x.chg!!, 1e-5); assertEquals((x.last / x.prev!! - 1) * 100, x.pct!!, 1e-4); assertEquals(105147L, x.vol)
        assertNull("an error answer is no quote", Quotes.parseChart("""{"chart":{"result":null,"error":{"code":"Not Found"}}}""")); assertNull(Quotes.parseChart("not json"))
    }

    @Test fun crossRatesAreTheRatioOfTheTwoUsdLegsAndTheirChangesAreTheRatioOfTheFactors() {
        val s = snap("EURUSD=X" to q(1.25, pct = 10.0), "USDJPY=X" to q(150.0, pct = 5.0))
        assertEquals(0.8, Quotes.cross(decl, s, "USD", "EUR")!!, 1e-12); assertEquals(150.0, Quotes.cross(decl, s, "USD", "JPY")!!, 1e-12)
        assertEquals(187.5, Quotes.cross(decl, s, "EUR", "JPY")!!, 1e-12); assertEquals(1.0, Quotes.cross(decl, s, "EUR", "EUR")!!, 1e-12)
        assertEquals((1.0 / 1.1 - 1.0) * 100.0, Quotes.crossChange(decl, s, "USD", "EUR") { it.pct }!!, 1e-9)
        assertEquals((1.05 * 1.1 - 1.0) * 100.0, Quotes.crossChange(decl, s, "EUR", "JPY") { it.pct }!!, 1e-9)
        assertNull("a currency with no quote has no cross, never a guess", Quotes.cross(decl, s, "USD", "GBP"))
    }

    @Test fun theFxTableAndMatrixShowNaWhereThereIsNoQuote() {
        val s = snap("EURUSD=X" to q(1.25, pct = 10.0))
        val t = Quotes.fxTable(decl, s, "USD")
        assertEquals(decl.ccys.size - 1, t.rows.size); assertEquals("0.80000", t.rows.first { it[0].startsWith("EUR") }[1])
        assertTrue(t.rows.first { it[0].startsWith("GBP") }.drop(1).all { it == decl.word("na") })
        val m = Quotes.fxMatrix(decl, s); assertEquals(decl.ccys.size, m.rows.size); assertEquals("1.0000", m.rows[1][2])
    }

    @Test fun expiryRulesFollowTheContractCalendarsOnAWeekdayCalendar() {
        fun e(rule: String, y: Int, m: Int) = Expiry.of(decl.rules.getValue(rule), y, m)
        assertEquals(LocalDate.of(2026, 11, 20), e("cl", 2026, 12))       // three business days before the 25th of the month before delivery
        assertEquals(LocalDate.of(2026, 12, 18), e("third_friday", 2026, 12))
        assertEquals(LocalDate.of(2026, 12, 14), e("imm", 2026, 12))       // two business days before the third Wednesday (Dec 16)
        assertEquals(LocalDate.of(2026, 12, 29), e("metal", 2026, 12))     // third last business day
        assertEquals(LocalDate.of(2026, 12, 31), e("last_bday_month", 2026, 12))
        assertEquals(LocalDate.of(2026, 12, 14), e("grain", 2026, 12))     // business day before the 15th (Tue 15th -> Mon 14th)
        assertEquals(LocalDate.of(2026, 12, 14), e("tenth_bday", 2026, 12))
        assertEquals(LocalDate.of(2026, 12, 25), e("last_friday", 2026, 12))
        assertEquals(LocalDate.of(2026, 12, 16), e("vix", 2026, 12))       // the Wednesday 30 days before the third Friday of the following month (Jan 15)
    }

    @Test fun theListedContractsStartAtTheFirstUnexpiredMonthAndUseTheDeclaredSymbolPattern() {
        val cl = Quotes.contracts(decl, spec("CL"), today)
        assertEquals(spec("CL").listed, cl.size); assertEquals("CLZ26.NYM", cl.first().symbol); assertEquals(LocalDate.of(2026, 11, 20), cl.first().expiry); assertEquals("CLZ26", cl.first().label("CL"))
        assertTrue(cl.zipWithNext().all { (a, b) -> a.expiry.isBefore(b.expiry) })
        val es = Quotes.contracts(decl, spec("ES"), today); assertEquals("ESZ26.CME", es.first().symbol); assertEquals("ESH27.CME", es[1].symbol)
        assertTrue("a root with no free source lists nothing", Quotes.contracts(decl, spec("FDAX"), today).isEmpty())
        assertTrue(Quotes.contracts(decl, spec("CL"), LocalDate.of(2026, 11, 21)).first().expiry.isAfter(LocalDate.of(2026, 11, 20)))
    }

    @Test fun theBoardShowsNaForARootWithNoFreeSourceAndForEveryFigureTheSourceDoesNotCarry() {
        val cls = decl.classes.first { it.id == "equity" }
        val s = snap("ESZ26.CME" to q(7400.0, pct = 1.5, vol = 1000))
        val t = Quotes.board(decl, s, cls, today)
        val es = t.rows[cls.roots.indexOfFirst { it.id == "ES" }]
        assertEquals("7400.0", es[2]); assertEquals("+1.50", es[4]); assertEquals("1000", es[5]); assertEquals(decl.word("na"), es[6]); assertEquals(decl.word("na"), es[7])
        assertTrue(es[0].contains("ESZ26") && es[0].contains("2026-12-18"))
        val dax = t.rows[cls.roots.indexOfFirst { it.id == "FDAX" }]
        assertTrue(dax.drop(2).all { it == decl.word("na") })
    }

    @Test fun termStructureFlagsContangoBackwardationAndFlatAndGivesTheCalendarSpreadsAndTheImpliedRate() {
        assertEquals("contango", Quotes.shape(listOf(90.0, 91.0, 92.5))); assertEquals("backwardation", Quotes.shape(listOf(92.5, 91.0, 90.0))); assertEquals("flat", Quotes.shape(listOf(90.0, 90.05)))
        assertNull(Quotes.shape(listOf(90.0)))
        val s = snap("CLZ26.NYM" to q(90.0), "CLF27.NYM" to q(91.0), "CLG27.NYM" to q(93.0))
        val t = Quotes.term(decl, s, spec("CL"), today)
        assertEquals(listOf("CLZ26", "CLF27", "CLG27"), t.labels); assertEquals("contango", t.shape); assertEquals(decl.word("na"), t.rows[0][3]); assertEquals("+1.00", t.rows[1][3]); assertEquals("+2.00", t.rows[2][3])
        assertEquals(4.15, Quotes.implied(95.85), 1e-9)
        val r = Quotes.term(decl, snap("SR3Z26.CME" to q(95.85)), spec("SR3"), today); assertEquals("4.150", r.rows[0][4])
    }

    @Test fun hedgeMathContractsFromExposureRatioAndTheDeclaredNotional() {
        val eur = spec("6E"); val h = Quotes.hedge(eur, 1.1162, 1_000_000.0, 100.0)
        assertEquals(125000.0, Quotes.notionalPerContract(eur, 1.1162), 0.0); assertEquals(8.0, h.exact, 1e-9); assertEquals(8L, h.contracts); assertEquals(0.0, h.residual, 1e-6)
        val half = Quotes.hedge(eur, 1.1162, 1_000_000.0, 50.0); assertEquals(4L, half.contracts)
        val es = spec("ES"); assertEquals(50.0 * 7400.0, Quotes.notionalPerContract(es, 7400.0), 1e-6)
        val e = Quotes.hedge(es, 7400.0, 1_000_000.0, 100.0); assertEquals(3L, e.contracts); assertEquals(1_000_000.0 - 3 * 370000.0, e.residual, 1e-6)
        assertEquals(1000000.0, Quotes.notionalPerContract(spec("SR3"), 95.85), 0.0)
        assertEquals(2500.0 * 0.0025, Quotes.hedge(spec("SR3"), 95.85, 1.0, 100.0).tickValue, 1e-9)
    }

    @Test fun theBakedSnapshotCarriesTheSourcesTimestampOnEveryQuoteAndOnlyDeclaredSymbols() {
        val s = QuotesSnapshot.parse(DataFiles.quotesSnapshotJson)
        assertTrue(s.quotes.size > 150); assertTrue(s.source.isNotBlank()); assertTrue(s.delay.isNotBlank())
        val declared = HashSet<String>()
        decl.ccys.mapNotNullTo(declared) { it.symbol }
        decl.classes.forEach { c -> c.roots.forEach { r -> r.front?.let { declared += it }; r.suffix?.let { suf -> declared += s.quotes.keys.filter { k -> k.startsWith(r.yahooRoot) && k.endsWith(".$suf") } } } }
        for ((k, v) in s.quotes) { assertTrue("$k is a declared symbol", k in declared); assertTrue("$k has the source's timestamp", v.asof > 1_700_000_000L); assertTrue(v.last.isFinite()) }
        val commodities = Quotes.commodityTable(decl, s)
        assertEquals(16, commodities.rows.size)
        assertTrue("every commodity reads the same front symbol as the HG board", decl.commodities.all { c -> decl.spec(c.root)?.front != null })
    }

    @Test fun aRefreshAsksTheDeclaredSymbolsWithTheDeclaredUserAgentOnceWithinTheTtlAndKeepsTheLaterTimestamp() {
        val asked = ArrayList<String>()
        val http = object : Http {
            override fun get(url: String): String = send(url, emptyMap(), null)
            override fun post(url: String): String = throw IOException("no")
            override fun send(url: String, headers: Map<String, String>, body: String?): String {
                synchronized(asked) { asked += url + "|" + headers["User-Agent"] }
                return if (url.contains("CL%3DF")) DataFiles.quotesFixture("yahoo-chart.json") else throw IOException("HTTP 404")
            }
        }
        var clock = 1_000_000L
        val n = QuotesStore.refresh(decl, listOf("CL=F", "NOPE=F"), true, http) { clock }
        assertEquals(1, n); assertTrue(asked.all { it.endsWith("|" + decl.source.userAgent) }); assertTrue(asked.any { it.contains("query1") })
        val before = asked.size
        assertEquals(0, QuotesStore.refresh(decl, listOf("CL=F", "NOPE=F"), false, http) { clock + 1000 }); assertEquals(before, asked.size)
        val old = snap("CL=F" to q(50.0).copy(asof = 1L)); val live = mapOf("CL=F" to q(91.09))
        assertEquals(91.09, old.overlay(live).quotes.getValue("CL=F").last, 0.0)
        assertEquals(91.09, snap("CL=F" to q(91.09)).overlay(mapOf("CL=F" to q(50.0).copy(asof = 1L))).quotes.getValue("CL=F").last, 0.0)
        assertFalse(old.overlay(emptyMap()).quotes.isEmpty())
        assertNotNull(spec("CL"))
    }
}
