package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** UI spec 2.10: prefix on the symbol, substring on the name, the groups in the declared order, at most 30. */
class FinSearchIndexTest {
    private fun item(group: String, sym: String, name: String = "", kind: String = FinSearchIndex.KIND_SYMBOL) = FinSearchIndex.Item(group, "EQ", sym, name, kind, "")

    @Test fun groupsComeInTheDeclaredOrderAndPrefixBeatsSubstring() {
        val all = listOf(item("Dashboards", "US Equity", "EQ", FinSearchIndex.KIND_DASHBOARD), item("Rates & yields", "UST 10Y"), item("Watchlist", "USD"), item("FX", "EURUSD", "euro per usd"))
        assertEquals(listOf("USD", "EURUSD", "UST 10Y", "US Equity"), FinSearchIndex.search(all, "us").map { it.symbol })
        assertEquals(emptyList<String>(), FinSearchIndex.search(all, "  ").map { it.symbol })
        assertEquals(30, FinSearchIndex.search((1..50).map { item("FX", "X$it") }, "x").size)
    }

    @Test fun aSymbolIsGroupedByWhereItWasSeen() {
        assertEquals("Crypto" to "CR", FinSearchIndex.groupOf("hg", "crypto", "spot"))
        assertEquals("FX" to "FX", FinSearchIndex.groupOf("hg", "fx", "ecb"))
        assertEquals("Rates & yields" to "FI", FinSearchIndex.groupOf("fi", "market_monitor", "ust"))
        // #1722 the rates dashboards sit under CB: their symbols are still rates
        assertEquals("Rates & yields" to "FI", FinSearchIndex.groupOf("cb", "world_bonds", "merged"))
        assertEquals("Equities & indices" to "EQ", FinSearchIndex.groupOf("eq", "world_equity_indices", "cboe"))
        assertEquals("Equities & indices" to "EQ", FinSearchIndex.groupOf("fi", "market_monitor", "equity"))
    }

    @Test fun remoteSearchRanksTickerPrefixThenCompanyName() {
        val tickers = FinSearchIndex.parseTickers("""{"0":{"cik_str":1,"ticker":"TSLA","title":"Tesla, Inc."},"1":{"cik_str":2,"ticker":"TSM","title":"TAIWAN SEMICONDUCTOR"},"2":{"cik_str":3,"ticker":"XYZ","title":"Tesla Motors Holdings"}}""")
        assertEquals(3, tickers.size)
        assertEquals(setOf("TSLA", "XYZ"), FinSearchIndex.remote(tickers, "tesla").map { it.symbol }.toSet())
        assertEquals(listOf("TSM", "TSLA"), FinSearchIndex.remote(tickers, "ts").map { it.symbol })
        assertTrue(FinSearchIndex.remote(tickers, "t").isEmpty())
        assertTrue(FinSearchIndex.parseTickers("not json").isEmpty())
    }
}
