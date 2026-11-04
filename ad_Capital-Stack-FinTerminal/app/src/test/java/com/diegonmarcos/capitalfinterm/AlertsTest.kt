package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Watchlist and alert rules: what is accepted, how it is stored, when a rule fires. */
class AlertsTest {
    @Test fun symbolsAreUpperCasedAndCheckedAndTheListKeepsOrderOnce() {
        assertEquals("BRK.B", Alerts.symbol(" brk.b "))
        assertEquals("^SPX", Alerts.symbol("^spx"))
        assertNull(Alerts.symbol("AAPL MSFT"))
        assertNull(Alerts.symbol(""))
        assertEquals(listOf("AAPL", "MSFT"), Alerts.watchlist("aapl,MSFT,,aapl,bad sym"))
        assertEquals("AAPL,MSFT", Alerts.watchlistText(listOf("AAPL", "MSFT", "AAPL")))
    }

    @Test fun rulesParseAndRoundTripThroughTheStoredLine() {
        assertEquals(AlertRule(Alerts.ABOVE, "AAPL", 230.0), Alerts.rule("aapl > 230"))
        assertEquals(AlertRule(Alerts.BELOW, "BTC-USD", 50000.5), Alerts.rule("BTC-USD<50000.5"))
        assertEquals(AlertRule(Alerts.EVENTS, "", 3.0), Alerts.rule("Events >= 3"))
        assertNull(Alerts.rule("events >= 4"))
        assertNull(Alerts.rule("AAPL = 3"))
        assertNull(Alerts.rule("AAPL >"))
        val list = listOf(Alerts.rule("AAPL > 230")!!, Alerts.rule("events >= 2")!!, Alerts.rule("^SPX < 6000")!!)
        val stored = Alerts.rulesText(list)
        assertTrue("no space in the stored line", ' ' !in stored)
        assertEquals(list, Alerts.rules(stored))
    }

    @Test fun priceRulesFireOnTheMatchingSymbolOnlyAndOnTheRightSide() {
        val pts = listOf(Pt("_SPX", "S&P 500", 0.0, 5900.0, null, ""), Pt("AAPL", "AAPL", 1.0, 229.0, null, ""))
        val rules = listOf(Alerts.rule("^SPX < 6000")!!, Alerts.rule("AAPL > 230")!!, Alerts.rule("AAPL < 230")!!, Alerts.rule("events >= 1")!!)
        assertEquals(listOf("ALERT ^SPX 5900.00 < 6000.00", "ALERT AAPL 229.00 < 230.00"), Alerts.priceHits(rules, pts))
        assertTrue(Alerts.same("^SPX", "_SPX") && Alerts.same("nyfed:SOFR", "sofr"))
    }

    @Test fun eventRulesFlagTheNext24HoursAtTheLowestDeclaredLevel() {
        val ev = listOf(
            Ev("2026-10-30T11:59Z", "US", "past", 3),
            Ev("2026-10-30T12:00Z", "US", "now", 2),
            Ev("2026-10-31T12:00Z", "EA", "edge", 3),
            Ev("2026-10-31T12:01Z", "GB", "late", 3),
            Ev("2026-10-30", "JP", "holiday today", 3),
            Ev("2026-10-30T15:00Z", "CA", "low", 1),
        )
        val three = Alerts.eventHits(listOf(Alerts.rule("events >= 3")!!), ev, "2026-10-30T12:00Z")
        assertEquals(listOf("ALERT 2026-10-31 12:00Z EA edge", "ALERT 2026-10-30 JP holiday today"), three)
        val two = Alerts.eventHits(listOf(Alerts.rule("events >= 3")!!, Alerts.rule("events >= 2")!!), ev, "2026-10-30T12:00Z")
        assertEquals(3, two.size)
        assertTrue(Alerts.eventHits(listOf(Alerts.rule("AAPL > 1")!!), ev, "2026-10-30T12:00Z").isEmpty())
    }

    @Test fun watchlistMoveReordersAndClamps() {
        val l = listOf("AAPL", "MSFT", "NVDA")
        assertEquals(listOf("MSFT", "AAPL", "NVDA"), Alerts.move(l, "msft", -1))
        assertEquals(listOf("AAPL", "NVDA", "MSFT"), Alerts.move(l, "MSFT", 1))
        assertEquals(l, Alerts.move(l, "AAPL", -1))
        assertEquals(l, Alerts.move(l, "NVDA", 1))
        assertEquals(l, Alerts.move(l, "TSLA", 1))
    }
}
