package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** #1416 the free stack: one parse test per store format, the pacing gate, the key-missing state, Yahoo as the flagged last fallback. */
class FreeStackTest {
    private val decl = FreeStack.parse(DataFiles.universeJson)
    private fun src(id: String) = decl.sources.first { it.id == id }

    @Test fun theDeclarationHoldsTheFiveSourcesAndAGenericContact() {
        assertEquals(listOf("fred", "openfigi", "fmp", "sec_edgar", "yahoo"), decl.sources.map { it.id })
        assertTrue(decl.contact.startsWith("admin@"))
        val fred = src("fred")
        for (g in listOf("treasury", "policy", "aggregates", "inflation", "ice_bofa")) assertTrue(g, fred.groups[g].orEmpty().isNotEmpty())
        assertTrue("DGS30" in fred.groups["treasury"]!! && "BAMLH0A0HYM2" in fred.groups["ice_bofa"]!!)
        assertEquals(setOf("sec_filings", "sec_facts"), src("sec_edgar").formats.keys)
        val used = decl.sources.flatMap { listOf(it.format) + it.formats.keys }.filter { it.isNotEmpty() }.toSet()
        assertEquals(FreeStack.FORMATS.toSet(), used)
    }

    @Test fun fredCsvParsesValuesAndSkipsMissingDays() {
        val o = FreeStack.fredCsv("observation_date,DGS10\n2026-10-30,4.12\n2026-10-31,.\n2026-11-02,4.20\n", "DGS10")
        assertEquals(listOf(FreeObs("fred:DGS10", "2026-10-30", 4.12), FreeObs("fred:DGS10", "2026-11-02", 4.20)), o)
    }

    @Test fun openFigiMapsEachJobInOrderAndKeepsTheWarning() {
        val jobs = src("openfigi").jobs.take(2)
        val body = """[{"data":[{"figi":"BBG000B9XRY4","name":"APPLE INC","ticker":"AAPL","exchCode":"US","marketSector":"Equity","securityType":"Common Stock"}]},{"warning":"No identifier found."}]"""
        val f = FreeStack.openFigi(body, jobs)
        assertEquals("BBG000B9XRY4", f[0].figi)
        assertEquals("Equity", f[0].sector)
        assertEquals("TICKER:AAPL", f[0].query)
        assertNull(f[1].figi)
        assertEquals("No identifier found.", f[1].error)
    }

    @Test fun fmpRatiosKeepsEveryNumericFieldPerDate() {
        val body = """[{"symbol":"AAPL","date":"2025-09-27","fiscalYear":"2025","grossProfitMargin":0.469,"currentRatio":0.87,"period":"FY"}]"""
        val o = FreeStack.fmpRatios(body, "AAPL").associate { it.id to it.value }
        assertEquals(0.469, o["ratio:AAPL:grossProfitMargin"]!!, 1e-12)
        assertEquals(0.87, o["ratio:AAPL:currentRatio"]!!, 1e-12)
        assertFalse("ratio:AAPL:period" in o)
        assertEquals(2, o.size)
    }

    @Test fun secFilingsKeepsOnlyTheDeclaredFormsNewestFirst() {
        val body = """{"filings":{"recent":{"form":["4","10-Q","8-K","10-K"],"filingDate":["2026-10-30","2026-08-01","2026-10-01","2026-02-01"],"accessionNumber":["a","b","c","d"],"primaryDocument":["x","q.htm","k8.htm","k.htm"]}}}"""
        val f = FreeStack.secFilings(body, listOf("10-K", "10-Q", "8-K"), 2)
        assertEquals(listOf("8-K", "10-Q"), f.map { it.form })
        assertEquals("k8.htm", f[0].document)
    }

    @Test fun secFactsReadsDeclaredConceptsFromDeclaredForms() {
        val body = """{"facts":{"us-gaap":{"NetIncomeLoss":{"units":{"USD":[{"end":"2025-09-27","val":112010000000,"form":"10-K"},{"end":"2025-06-28","val":23434000000,"form":"10-Q"},{"end":"2025-06-28","val":1,"form":"S-1"}]}},"Other":{"units":{"USD":[{"end":"2025-09-27","val":5,"form":"10-K"}]}}}}}"""
        val o = FreeStack.secFacts(body, "0000320193", listOf("NetIncomeLoss"), listOf("10-K", "10-Q"))
        assertEquals(2, o.size)
        assertEquals(FreeObs("fact:0000320193:NetIncomeLoss", "2025-09-27", 112010000000.0), o[0])
        assertEquals(23434000000.0, o[1].value, 0.0)
    }

    @Test fun yahooChartReadsClosesDividendsAndSplits() {
        val body = """{"chart":{"result":[{"timestamp":[1730419200,1730505600],"indicators":{"quote":[{"close":[225.5,null]}]},"events":{"dividends":{"1730419200":{"amount":0.25,"date":1730419200}},"splits":{"1598832000":{"numerator":4,"denominator":1,"date":1598832000}}}}]}}"""
        val o = FreeStack.yahooChart(body, "AAPL")
        assertEquals(listOf(FreeObs("px:AAPL", "2024-11-01", 225.5)), o.filter { it.id.startsWith("px:") })
        assertEquals(0.25, o.first { it.id == "div:AAPL" }.value, 0.0)
        assertEquals(FreeObs("split:AAPL", "2020-08-31", 4.0), o.first { it.id == "split:AAPL" })
    }

    @Test fun theGateSpacesCallsByTheDeclaredBudget() {
        assertEquals(100L, FreeStack.gapMs(src("sec_edgar").perMin))
        assertTrue("SEC is held to at most 10 a second", src("sec_edgar").perMin <= 600)
        var now = 1_000L
        val slept = ArrayList<Long>()
        val gate = FreeStack.RateGate(600, clock = { now }, sleep = { slept += it; now += it })
        gate.acquire(); now += 30; gate.acquire(); gate.acquire()
        assertEquals(listOf(70L, 100L), slept)
        // over one simulated second of back-to-back calls, no more than 10 go out
        val t0 = now; var n = 0
        while (now - t0 < 1000) { gate.acquire(); n++ }
        assertTrue("$n calls in one second", n <= 10)
    }

    @Test fun aKeyedSourceWithoutItsKeyNeedsOneAndNeverSendsARequest() {
        val fmp = src("fmp")
        assertEquals(FreeState.NEEDS_KEY, FreeStack.state(fmp, hasKey = false))
        assertEquals(FreeState.READY, FreeStack.state(fmp, hasKey = true))
        assertTrue(FreeStack.statusText(fmp, FreeState.NEEDS_KEY).startsWith("needs key: FMP needs a free API key"))
        val http = object : Http {
            override fun get(url: String): String { fail("no request without a key"); return "" }
            override fun post(url: String): String { fail("no request without a key"); return "" }
        }
        try {
            FreeStack.fetch(fmp, fmp.format, "AAPL", http, FreeStack.RateGate(60), decl.contact, null)
            fail("a missing key must stop the fetch")
        } catch (e: NeedsKey) {
            assertEquals(fmp.key!!.reason, e.message)
        }
        val h = FreeStack.headers(fmp, decl.contact, "k-123")
        assertEquals("k-123", h["apikey"])
        assertFalse("the key never goes into the address", FreeStack.url(fmp, fmp.path, mapOf("code" to "AAPL")).contains("k-123"))
    }

    @Test fun theUserAgentIsOursWithTheFleetContactNeverABrowser() {
        val ua = FreeStack.headers(src("sec_edgar"), decl.contact, null)["User-Agent"]!!
        assertTrue(ua.startsWith(UrlHttp.AGENT) && ua.contains(decl.contact))
        assertFalse(ua.contains("Mozilla"))
    }

    @Test fun yahooIsOffByDefaultAndOnlyTheLastFallback() {
        val y = src("yahoo")
        assertEquals(listOf("cboe", "fred", "coinbase"), y.flag!!.after)
        assertEquals(FreeState.FLAG_OFF, FreeStack.state(y, hasKey = true))
        val px = listOf(FreeObs("px:X", "2026-11-02", 1.0))
        var asked = 0
        val yf = { asked++; listOf(FreeObs("px:X", "2026-11-02", 9.0)) }
        assertEquals("fred", FreeStack.eod(y, mapOf("cboe" to emptyList(), "fred" to px), flagOn = true, fetchYahoo = yf)!!.first)
        assertEquals(0, asked)
        assertNull(FreeStack.eod(y, mapOf("cboe" to emptyList()), flagOn = false, fetchYahoo = yf))
        assertEquals(0, asked)
        assertEquals("yahoo", FreeStack.eod(y, emptyMap(), flagOn = true, fetchYahoo = yf)!!.first)
        assertEquals(1, asked)
    }
}
