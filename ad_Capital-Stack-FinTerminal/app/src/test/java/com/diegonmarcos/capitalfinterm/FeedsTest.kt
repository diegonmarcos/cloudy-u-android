package com.diegonmarcos.capitalfinterm

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Every model connector on a fixture cut from the provider's live answer (probed 2026-10-30): the normalised model it yields. */
class FeedsTest {
    private fun opt(spec: String = "{}", each: String = "", paths: Map<String, String> = emptyMap()) = Feeds.Opt(JSONObject(spec), each, paths)

    @Test fun nyfedReadsEveryRateAndSplitsTheSofrAverages() {
        val body = """{"refRates":[{"effectiveDate":"2026-10-30","type":"SOFRAI","average30day":3.89857,"average90day":3.77532,"average180day":3.71759,"index":1.2649531},
            {"effectiveDate":"2026-10-29","type":"EFFR","percentRate":3.88},{"effectiveDate":"2026-10-29","type":"SOFR","percentRate":3.91}]}"""
        val g = Feeds.parse("nyfed_rates", body, opt())
        assertEquals(listOf("SOFRAI_30D", "SOFRAI_90D", "SOFRAI_180D", "SOFRAI_INDEX", "EFFR", "SOFR"), g.points.map { it.key })
        assertEquals(3.89857, g.points[0].value, 1e-9)
        assertEquals(3.91, g.points.last().value, 1e-9)
        assertEquals("2026-10-29", g.points.last().date)
    }

    @Test fun treasuryTakesTheLastDayAndTheDayBefore() {
        fun day(d: String, ten: String, two: String) = "<m:properties><d:NEW_DATE m:type=\"Edm.DateTime\">${d}T00:00:00</d:NEW_DATE>" +
            "<d:BC_2YEAR m:type=\"Edm.Double\">$two</d:BC_2YEAR><d:BC_10YEAR m:type=\"Edm.Double\">$ten</d:BC_10YEAR></m:properties>"
        val body = "<feed>" + day("2026-10-29", "4.10", "3.50") + day("2026-10-01", "4.00", "3.40") + day("2026-10-30", "4.15", "3.52") + "</feed>"
        val g = Feeds.parse("treasury_xml", body, opt())
        assertEquals(listOf("BC_2YEAR", "BC_10YEAR"), g.points.map { it.key })
        assertEquals(4.15, g.points[1].value, 1e-9)
        assertEquals(4.10, g.points[1].prev!!, 1e-9)
        assertEquals("2026-10-30", g.points[1].date)
    }

    @Test fun sdmxNamesEachSeriesByItsKeyDimensionTopLevelOrUnderData() {
        val inner = """{"structure":{"dimensions":{"series":[{"id":"FREQ","values":[{"id":"B"}]},{"id":"DATA_TYPE_FM","values":[{"id":"SR_2Y"},{"id":"SR_10Y"}]}],
            "observation":[{"id":"TIME_PERIOD","values":[{"id":"2026-10-30"},{"id":"2026-10-29"}]}]}},
            "dataSets":[{"series":{"0:0":{"observations":{"0":[2.10,0],"1":[2.05,0]}},"0:1":{"observations":{"0":[2.80],"1":[2.70]}}}}]}"""
        for (body in listOf(inner, """{"data":$inner}""")) {
            val g = Feeds.parse("sdmx_json", body, opt("""{"key_dim":"DATA_TYPE_FM"}"""))
            assertEquals(listOf("SR_2Y", "SR_10Y"), g.points.map { it.key })
            assertEquals(2.80, g.points[1].value, 1e-9)
            assertEquals(2.70, g.points[1].prev!!, 1e-9)
            assertEquals("2026-10-30", g.points[1].date)
        }
        assertTrue(Feeds.parse("sdmx_json", inner, opt("""{"key_dim":"NOPE"}""")).isEmpty)
    }

    @Test fun jsonstatWalksTheFlatIndexByDimensionSizes() {
        // geo (2) x time (2), the value keyed by flat index: geo-major
        val body = """{"id":["freq","geo","time"],"size":[1,2,2],"dimension":{"freq":{"category":{"index":{"M":0}}},
            "geo":{"category":{"index":{"EA":0,"DE":1}}},"time":{"category":{"index":{"2026-08":0,"2026-09":1}}}},"value":{"0":2.0,"1":2.1,"2":2.3,"3":2.4}}"""
        val g = Feeds.parse("jsonstat", body, opt("""{"key_dim":"geo"}"""))
        assertEquals(listOf("EA", "DE"), g.points.map { it.key })
        assertEquals(2.1, g.points[0].value, 1e-9)
        assertEquals(2.0, g.points[0].prev!!, 1e-9)
        assertEquals(2.4, g.points[1].value, 1e-9)
        assertEquals("2026-09", g.points[1].date)
    }

    @Test fun blsReadsTheNewestPrintAndTheOneBefore() {
        val body = """{"status":"REQUEST_SUCCEEDED","Results":{"series":[{"seriesID":"LNS14000000","data":[
            {"year":"2026","period":"M08","value":"4.3"},{"year":"2026","period":"M09","value":"4.2","latest":"true"},{"year":"2026","period":"M07","value":"-"}]}]}}"""
        val p = Feeds.parse("bls_series", body, opt()).points.single()
        assertEquals("LNS14000000", p.key)
        assertEquals(4.2, p.value, 1e-9)
        assertEquals(4.3, p.prev!!, 1e-9)
        assertEquals("2026-09", p.date)
    }

    @Test fun frankfurterReadsLatestAndARange() {
        val latest = Feeds.parse("frankfurter_rates", """{"amount":1.0,"base":"USD","date":"2026-10-30","rates":{"EUR":0.89509,"JPY":157.25}}""", opt())
        assertEquals(listOf("EUR", "JPY"), latest.points.map { it.key })
        assertNull(latest.points[0].prev)
        val range = Feeds.parse("frankfurter_rates", """{"base":"USD","rates":{"2026-10-30":{"EUR":0.90},"2026-10-28":{"EUR":0.88},"2026-10-29":{"EUR":0.89}}}""", opt())
        assertEquals(0.90, range.points.single().value, 1e-9)
        assertEquals(0.89, range.points.single().prev!!, 1e-9)
        assertEquals("2026-10-30", range.points.single().date)
    }

    @Test fun coinbaseStatsAreAQuoteAndCandlesAreBarsOldestFirst() {
        val q = Feeds.parse("coinbase_exchange", """{"open":"77782.75","high":"78634.8","low":"76141.02","last":"77026.93"}""", opt(each = "BTC-USD")).points.single()
        assertEquals("BTC-USD", q.key)
        assertEquals(77026.93, q.value, 1e-9)
        assertEquals(77782.75, q.prev!!, 1e-9)
        val bars = Feeds.parse("coinbase_exchange", "[[1793318400,76141.02,78634.8,77782.75,77048.02,13535.1],[1793232000,77699,81890,81249.32,77780.33,9933.6]]", opt()).bars
        assertEquals(2, bars.size)
        assertTrue(bars[0].date < bars[1].date)
        assertEquals(77048.02, bars[1].c, 1e-9)
        assertEquals(78634.8, bars[1].h, 1e-9)
        assertEquals(76141.02, bars[1].l, 1e-9)
        assertEquals(77782.75, bars[1].o, 1e-9)
    }

    @Test fun cboeReadsAQuoteAndTheDailyHistory() {
        val q = Feeds.parse("cboe_quotes", """{"timestamp":"2026-10-30 23:21:49","data":{"symbol":"^SPX","current_price":7580.0698,"prev_day_close":7720.3901,"last_trade_time":"2026-10-30T16:14:58"}}""", opt(each = "_SPX")).points.single()
        assertEquals("_SPX", q.key)
        assertEquals(7580.0698, q.value, 1e-9)
        assertEquals(7720.3901, q.prev!!, 1e-9)
        assertEquals("2026-10-30 16:14", q.date)
        val h = Feeds.parse("cboe_quotes", """{"data":[{"date":"2026-10-29","open":"1","high":"3","low":"0.5","close":"2"},{"date":"2026-10-28","open":"1","high":"2","low":"1","close":"1.5"},{"date":"2026-10-30","open":"2","high":"4","low":"2","close":"3"}]}""",
            opt("""{"last":2}""")).bars
        assertEquals(listOf("2026-10-29", "2026-10-30"), h.map { it.date })
    }

    /** Real Cboe _DJX quote after the close (2026-10-31 capture): prev_day_close has rolled to the price, so the previous close
     *  must come from price_change (-5.73), not from prev_day_close (which would read 0.00 / +0.00 %). */
    @Test fun cboeAfterTheCloseTakesThePreviousCloseFromPriceChange() {
        val body = """{"timestamp": "2026-10-31 12:34:23", "data": {"symbol": "^DJX", "security_type": "index", "exchange_id": 5, "current_price": 517.7, "price_change": -5.73, "price_change_percent": -1.1068, "bid": 0.0, "ask": 0.0, "open": 523.84, "high": 523.84, "low": 515.64, "close": 517.7, "prev_day_close": 517.7, "volume": 0, "last_trade_time": "2026-10-30T16:47:53", "tick": "down"}, "symbol": "_DJX"}"""
        val q = Feeds.parse("cboe_quotes", body, opt(each = "_DJX")).points.single()
        assertEquals(523.43, q.prev!!, 1e-9)
        assertEquals(-1.0947, (q.value / q.prev!! - 1.0) * 100.0, 5e-4) // % from the previous close; Cboe's price_change_percent (-1.1068) divides by the current price
        assertEquals(null, Feeds.cboePrev(517.7, null, 517.7))
        assertEquals(520.0, Feeds.cboePrev(517.7, null, 520.0)!!, 1e-9)
        val v1 = Feeds.parse("finterm_v1", """{"quotes":[{"symbol":"^DJX","price":517.7,"prev_close":517.7,"change":-5.73,"ts":"2026-10-30T16:47"},{"symbol":"^RUT","price":2400.0,"prev_close":2400.0}]}""", opt()).points
        assertEquals(523.43, v1[0].prev!!, 1e-9)
        assertEquals(null, v1[1].prev)
    }

    @Test fun forexFactoryEventsAreUtcWithCountryAndImportance() {
        val body = """[{"title":"Non-Farm Employment Change","country":"USD","date":"2026-11-06T08:30:00-05:00","impact":"High","forecast":"180K","previous":"254K"},
            {"title":"German Prelim CPI m/m","country":"EUR","date":"2026-10-30T09:00:00+01:00","impact":"Medium","forecast":"0.2%","previous":"0.1%"},
            {"title":"Bank Holiday","country":"JPY","date":"2026-11-03T00:00:00+09:00","impact":"Holiday","forecast":"","previous":""}]"""
        val e = Feeds.parse("ff_calendar", body, opt()).events
        assertEquals("2026-11-06T13:30Z", e[0].ts)
        assertEquals("US", e[0].country)
        assertEquals(3, e[0].importance)
        assertEquals("180K", e[0].consensus)
        assertEquals("254K", e[0].previous)
        assertEquals("2026-10-30T08:00Z", e[1].ts)
        assertEquals("EA", e[1].country)
        assertEquals(2, e[1].importance)
        assertEquals("2026-11-02T15:00Z", e[2].ts)
        assertEquals(0, e[2].importance)
        assertEquals(1, Feeds.importance("Low"))
        assertEquals("GB", Feeds.country("gbp"))
    }

    @Test fun icsEventsConvertEasternTimeAcrossTheDstChange() {
        val body = "BEGIN:VCALENDAR\r\nBEGIN:VEVENT\r\nDTSTART;TZID=US-Eastern:20261030T083000\r\nSUMMARY:Employment Cost Index\r\nEND:VEVENT\r\n" +
            "BEGIN:VEVENT\r\nDTSTART;TZID=US-Eastern:20261110T083000\r\nSUMMARY:Consumer Price Index\r\nEND:VEVENT\r\nEND:VCALENDAR\r\n"
        val e = Feeds.parse("ics_calendar", body, opt("""{"importance":{"Consumer Price Index":3,"Employment Cost Index":2}}""")).events
        assertEquals("2026-10-30T12:30Z", e[0].ts) // EDT, UTC-4
        assertEquals("2026-11-10T13:30Z", e[1].ts) // EST, UTC-5
        assertEquals(listOf(2, 3), e.map { it.importance })
        assertEquals("2026-11-10", Feeds.icsTs("DTSTART;VALUE=DATE:20261110"))
        assertEquals(1, Feeds.parse("ics_calendar", body, opt()).events[1].importance)
    }

    @Test fun declaredCentralBankDecisionsAreImportanceThree() {
        val e = Feeds.parse("declared_events", """{"banks":[{"id":"fed","country":"US","events":[{"date":"2026-12-09","ts":"2026-12-09T19:00Z","title":"Fed: rate decision"}]}]}""", opt()).events.single()
        assertEquals("2026-12-09T19:00Z", e.ts)
        assertEquals("US", e.country)
        assertEquals(3, e.importance)
        assertEquals("central_bank", e.kind)
    }

    @Test fun theDeclaredCentralBankFileParses() {
        val e = Feeds.parse("declared_events", java.io.File(listOf("../data/central-banks.json", "data/central-banks.json").first { java.io.File(it).isFile }).readText(), opt()).events
        assertTrue(e.size > 40)
        assertTrue(e.all { Regex("^\\d{4}-\\d{2}-\\d{2}(T\\d{2}:\\d{2}Z)?$").matches(it.ts) })
        assertEquals(setOf("US", "EA", "GB", "JP"), e.map { it.country }.toSet())
    }

    @Test fun nasdaqEarningsRankByMarketCap() {
        val body = """{"data":{"rows":[{"symbol":"AAPL","name":"Apple Inc.","time":"time-after-hours","epsForecast":"$1.75","lastYearEPS":"$1.64","marketCap":"$3,900,000,000,000"},
            {"symbol":"XYZ","name":"Small Co","time":"time-pre-market","epsForecast":"$0.10","lastYearEPS":"","marketCap":"$12,000,000,000"},{"symbol":"T","name":"Tiny","time":"","marketCap":"N/A"}]}}"""
        val e = Feeds.parse("nasdaq_earnings", body, opt(each = "2026-10-30")).events
        assertEquals(listOf(3, 2, 1), e.map { it.importance })
        assertEquals("2026-10-30", e[0].ts)
        assertEquals("AAPL Apple Inc. (after close)", e[0].title)
        assertEquals("$1.75", e[0].consensus)
        assertEquals("$1.64", e[0].previous)
        assertEquals("earnings", e[0].kind)
    }

    @Test fun polymarketReadsOpenMarketsAsPercent() {
        val body = """[{"title":"How many Fed rate cuts in 2026?","endDate":"2027-01-01T04:59:00Z","markets":[
            {"question":"Will no Fed rate cuts happen in 2026?","groupItemTitle":"0 (0 bps)","slug":"zero","outcomePrices":"[\"0.968\", \"0.032\"]"},
            {"question":"Closed one","groupItemTitle":"1","slug":"one","closed":true,"outcomePrices":"[\"0.5\", \"0.5\"]"}]}]"""
        val p = Feeds.parse("polymarket_events", body, opt()).points.single()
        assertEquals(96.8, p.value, 1e-9)
        assertEquals("How many Fed rate cuts in 2026?: 0 (0 bps)", p.label)
        assertEquals("2027-01-01", p.date)
    }

    @Test fun secSubmissionsGiveTheProfileAndLinkedFilingsOfTheDeclaredForms() {
        val body = """{"cik":"0000320193","name":"Apple Inc.","tickers":["AAPL"],"exchanges":["Nasdaq"],"sicDescription":"Electronic Computers","fiscalYearEnd":"0926",
            "filings":{"recent":{"accessionNumber":["0001140361-26-041201","0000320193-26-000020"],"filingDate":["2026-10-29","2026-07-31"],"form":["4","10-Q"],
            "primaryDocument":["form4.xml","aapl-20260627.htm"],"primaryDocDescription":["",""]}}}"""
        val g = Feeds.parse("sec_submissions", body, opt("""{"forms":["10-Q"]}""", paths = mapOf("filing" to "base/{cik}/{accession}/{document}")))
        assertTrue(("Fiscal year end" to "09-26") in g.kv)
        assertTrue(("Tickers" to "AAPL") in g.kv)
        assertEquals(listOf(listOf("10-Q", "10-Q", "2026-07-31")), g.rows)
        assertEquals(listOf("base/320193/000032019326000020/aapl-20260627.htm"), g.links)
    }

    @Test fun secConceptKeepsFullYearFramesWithTheYearBefore() {
        val body = """{"units":{"USD":[{"end":"2024-09-28","val":93736000000,"fy":2024,"fp":"FY","form":"10-K","frame":"CY2024"},
            {"end":"2025-09-27","val":112010000000,"fy":2025,"fp":"FY","form":"10-K","frame":"CY2025"},
            {"end":"2026-06-27","val":29789000000,"fy":2026,"fp":"Q3","form":"10-Q","frame":"CY2026Q2"}]}}"""
        val p = Feeds.parse("sec_concept", body, opt()).points
        assertEquals(listOf("2024", "2025"), p.map { it.key })
        val s = Feeds.parse("sec_concept", body, opt("""{"scale":1e-9,"last":1}""")).points.single()
        assertEquals(112.01, s.value, 1e-9)
        assertEquals(93.736, s.prev!!, 1e-9)
        assertEquals(0.0, s.x, 0.0)
    }

    @Test fun theContainerAnswersEveryNormalisedShape() {
        val body = """{"asof":"2026-10-30","series":[{"id":"nyfed:SOFR","title":"SOFR","obs":[{"date":"2026-10-29","value":3.9},{"date":"2026-10-30","value":3.91}]}],
            "quotes":[{"symbol":"^SPX","name":"S&P 500","price":7580.07,"prev_close":7720.39,"ts":"2026-10-30T20:00:00Z"}],
            "points":[{"label":"10Y","tenor_years":10,"value":4.15}],"prev":[{"label":"10Y","value":4.10}],
            "events":[{"ts":"2026-11-06T13:30Z","country":"US","title":"NFP","importance":3,"consensus":"180K","previous":"254K"}],
            "bars":[{"t":"2026-10-30","o":1,"h":2,"l":0.5,"c":1.5}],"profile":{"Name":"Apple Inc."},"filings":[{"title":"10-K 2026","form":"10-K","filed":"2026-10-31","url":"u"}]}"""
        val g = Feeds.parse("finterm_v1", body, opt())
        assertEquals(listOf("nyfed:SOFR", "^SPX", "10Y"), g.points.map { it.key })
        assertEquals(3.9, g.points[0].prev!!, 1e-9)
        assertEquals(7720.39, g.points[1].prev!!, 1e-9)
        assertEquals(10.0, g.points[2].x, 0.0)
        assertEquals(4.10, g.points[2].prev!!, 1e-9)
        assertEquals("180K", g.events.single().consensus)
        assertEquals(1.5, g.bars.single().c, 0.0)
        assertEquals(listOf("Name" to "Apple Inc."), g.kv)
        assertEquals(listOf("u"), g.links)
    }

    @Test fun anythingUnrecognisedIsEmptyNotAnError() {
        for (c in listOf("nyfed_rates", "treasury_xml", "sdmx_json", "jsonstat", "bls_series", "ff_calendar", "frankfurter_rates", "coinbase_exchange", "cboe_quotes", "finterm_v1", "nope"))
            assertTrue(c, Feeds.parse(c, "<html>rate limited</html>", opt()).isEmpty)
    }

    @Test fun coinbaseDailyCandlesPerItemAreAQuoteRowWithASevenDaySparkline() {
        // newest first, as Coinbase sends: [time, low, high, open, close, volume]
        val body = (8 downTo 0).joinToString(",", "[", "]") { d -> val t = java.time.LocalDate.of(2026, 10, 20 + d).atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond(); "[$t,${99 + d},${101 + d},${100 + d},${100 + d},1]" }
        val p = Feeds.parse("coinbase_exchange", body, opt(each = "BTC-USD")).points.single()
        assertEquals(108.0, p.value, 0.0)
        assertEquals(107.0, p.prev!!, 0.0)
        assertEquals("2026-10-28", p.date)
        assertEquals(listOf(102.0, 103.0, 104.0, 105.0, 106.0, 107.0, 108.0), p.hist)
        assertTrue(Feeds.parse("coinbase_exchange", body, opt()).points.isEmpty())
    }
}
