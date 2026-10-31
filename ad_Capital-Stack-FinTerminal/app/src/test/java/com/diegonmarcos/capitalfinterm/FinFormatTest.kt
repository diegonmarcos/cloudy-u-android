package com.diegonmarcos.capitalfinterm

import com.diegonmarcos.capitalfinterm.FinFormat.Dir
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinFormatTest {
    @Test fun changesAreSignedZeroIsUnsignedMissingIsTheEmDash() {
        assertEquals("+0.12", FinFormat.signed(0.123))
        assertEquals("-0.12", FinFormat.signed(-0.123))
        assertEquals("0.00", FinFormat.signed(0.0))
        assertEquals("0.00", FinFormat.signed(-0.001))
        assertEquals(FinFormat.DASH, FinFormat.signed(null))
        assertEquals("+1.23%", FinFormat.signed(1.234, 2, "%"))
    }

    @Test fun pricesFollowTheAssetClass() {
        assertEquals("1.0842", FinFormat.price(1.08423, FinFormat.Kind.FX))
        assertEquals("157.25", FinFormat.price(157.25, FinFormat.Kind.FX))
        assertEquals("101,235", FinFormat.price(101234.6, FinFormat.Kind.CRYPTO))
        assertEquals("6,812.10", FinFormat.price(6812.1))
        assertEquals(FinFormat.DASH, FinFormat.price(Double.NaN))
    }

    @Test fun numbersAndDirectionAreReadFromSourceStrings() {
        assertEquals(180.0, FinFormat.parseNum("180K")!!, 0.0)
        assertEquals(1234.5, FinFormat.parseNum("1,234.5")!!, 0.0)
        assertNull(FinFormat.parseNum("n/a"))
        assertEquals(Dir.UP, FinFormat.dirOf("+0.40"))
        assertEquals(Dir.DOWN, FinFormat.dirOf("-5.0"))
        assertEquals(Dir.FLAT, FinFormat.dirOf(FinFormat.DASH))
    }

    @Test fun beatOrMissDependsOnWhichWayIsBetter() {
        assertTrue(FinFormat.betterHigher("Non-Farm Employment Change"))
        assertFalse(FinFormat.betterHigher("Unemployment Rate"))
        assertFalse(FinFormat.betterHigher("Core CPI m/m"))
        assertEquals(Dir.UP, FinFormat.beatMiss("254K", "180K"))
        assertEquals(Dir.DOWN, FinFormat.beatMiss("150K", "180K"))
        assertEquals(Dir.DOWN, FinFormat.beatMiss("4.3%", "4.1%", higherIsBetter = false))
        assertEquals(Dir.UP, FinFormat.beatMiss("4.0%", "4.1%", higherIsBetter = false))
        assertEquals(Dir.FLAT, FinFormat.beatMiss("4.1%", "4.1%", higherIsBetter = false))
        assertNull(FinFormat.beatMiss("", "4.1%"))
    }

    @Test fun refreshCadenceJitterBackoffAndStaleness() {
        assertEquals(15_000L, FinFormat.policy("coinbase").everyMs)
        assertEquals(60_000L, FinFormat.policy("cboe").everyMs)
        assertEquals(300_000L, FinFormat.policy("nyfed").everyMs)
        assertEquals(900_000L, FinFormat.policy("forexfactory").everyMs)
        assertEquals(21_600_000L, FinFormat.policy("bls").everyMs)
        assertEquals(1_800_000L, FinFormat.policy("unknown").everyMs)
        assertEquals(54_000L, FinFormat.nextDelay(60_000, 0, 0.0))
        assertEquals(66_000L, FinFormat.nextDelay(60_000, 0, 1.0))
        assertEquals(120_000L, FinFormat.nextDelay(60_000, 1, 0.5))
        assertEquals(600_000L, FinFormat.nextDelay(300_000, 3, 0.5))
        assertFalse(FinFormat.isStale(0, 59_000, "coinbase"))
        assertTrue(FinFormat.isStale(0, 61_000, "coinbase"))
        assertFalse(FinFormat.isStale(0, 61_000, "fred"))
    }

    @Test fun aContainerOnlyPanelOffTheMeshSaysMeshOffNotItsPublicSource() {
        val off = FinFormat.failureReason(java.io.IOException("mesh off: the EM USD bond table is served by the FinTerm container only"))
        assertEquals("mesh off", off)
        assertEquals("no answer", FinFormat.failureReason(java.io.IOException("HTTP 500")))
        assertEquals("timeout", FinFormat.failureReason(java.net.SocketTimeoutException("t")))
        val text = FinFormat.failureText("US Treasury", off)
        assertTrue(text.startsWith("Mesh off"))
        assertFalse(text.contains("US Treasury"))
        assertEquals("US Treasury did not answer (no answer)", FinFormat.failureText("US Treasury", "no answer"))
    }

    @Test fun footerSourceNamesDropTheirParentheticalAndSlashDetail() {
        assertEquals("Cloud-Fincept", FinFormat.shortSource("Cloud-Fincept / fin_api (private mesh)"))
        assertEquals("US Treasury par yield curve", FinFormat.shortSource("US Treasury par yield curve (XML)"))
        assertEquals("FRED + Cloud-Fincept", FinFormat.shortSource("FRED (St. Louis Fed) + Cloud-Fincept / fin_api (private mesh)"))
        assertEquals("Eurostat", FinFormat.shortSource("Eurostat"))
    }

    @Test fun calendarChipsKeepAllTodayOrThisMondayToSundayWeek() {
        val today = java.time.LocalDate.parse("2026-10-29") // a Thursday
        assertTrue(FinLogic.inCalWindow("2026-12-01 08:30", 0, today))
        assertTrue(FinLogic.inCalWindow("2026-10-29 14:00", 1, today))
        assertFalse(FinLogic.inCalWindow("2026-10-30 14:00", 1, today))
        assertTrue(FinLogic.inCalWindow("2026-10-26", 2, today))
        assertTrue(FinLogic.inCalWindow("2026-11-01 23:00", 2, today))
        assertFalse(FinLogic.inCalWindow("2026-10-25 23:00", 2, today))
        assertFalse(FinLogic.inCalWindow("2026-11-02 00:00", 2, today))
    }

    @Test fun creditAndMacroMovesAreColouredByWhatIsBetter() {
        assertTrue(FinFormat.lowerBetter("", "US Unemployment rate"))
        assertTrue(FinFormat.lowerBetter("", "ICE BofA US HY OAS"))
        assertFalse(FinFormat.lowerBetter("", "S&P 500"))
        assertTrue(FinFormat.lowerBetter("lower", "S&P 500"))
        assertFalse(FinFormat.lowerBetter("higher", "CPI YoY"))
        assertEquals(Dir.DOWN, FinFormat.tone(Dir.UP, true))
        assertEquals(Dir.UP, FinFormat.tone(Dir.DOWN, true))
        assertEquals(Dir.UP, FinFormat.tone(Dir.UP, false))
        assertEquals(Dir.FLAT, FinFormat.tone(Dir.FLAT, true))
    }

    @Test fun nextDecisionTilesTakeTheFirstUpcomingDecisionPerCountry() {
        val today = java.time.LocalDate.parse("2026-10-31")
        val ev = listOf("2026-10-29T18:00Z" to "US", "2026-12-09T19:00Z" to "US", "2027-01-27T19:00Z" to "US", "2026-11-05T12:00Z" to "GB", "2026-10-31T11:00Z" to "JP", "bad" to "EU")
        val n = FinLogic.nextDecisions(ev, today)
        assertEquals(listOf("JP", "GB", "US"), n.map { it.country })
        assertEquals(listOf(0L, 5L, 39L), n.map { it.days })
    }

    @Test fun aDeclaredHeatColumnIsTintedAgainstItsLargestValue() {
        val t = FinLogic.colTypes(listOf("text", "num", "heat"), listOf("Country", "10Y", "vs Bund bp"), listOf(listOf("Italy", "3.75", "+115"), listOf("Germany", "2.60", "0")))
        assertEquals(FinLogic.Col.HEAT, t[2])
        assertEquals(1f, FinLogic.heat(115.0, 115.0), 0f)
        assertEquals(-0.5f, FinLogic.heat(-57.5, 115.0), 1e-6f)
    }
}
