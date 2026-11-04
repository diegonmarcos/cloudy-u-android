package org.fossify.phone.sim

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class SimConfigsTest {
    private val card = SimCard(
        slot = 0, subscriptionId = 3, carrier = "Acme", displayName = "Work", mcc = "214", mnc = "07", country = "es",
        number = null, iccid = "8934071100001234567", embedded = true, roaming = false, defaultData = true,
        defaultVoice = false, defaultSms = true, networkType = "LTE", signalLevel = 3, eid = "89049032000001000000000000001234",
    )
    private val manual = SimManual(provider = "Acme", plan = "Max", monthlyPrice = "9.99", currency = "EUR",
        dataAllowance = "20 GB", renewalDate = "15", number = "+34600000000", pin = "1234", puk = "87654321")

    private fun row(rows: List<SimRow>, l: String) = rows.first { it.label == l }.value

    @Test fun mapsFields() {
        val r = SimFields.rows(card, manual)
        assertEquals("eSIM", row(r, "Type"))
        assertEquals("214 / 07", row(r, "MCC / MNC"))
        assertEquals("ES", row(r, "Country"))
        assertEquals("+34600000000", row(r, "Number"))
        assertEquals("Data, SMS", row(r, "Defaults"))
        assertEquals("9.99 EUR", row(r, "Monthly price"))
        assertEquals("3/4", row(r, "Signal"))
    }

    @Test fun masksIdsUnlessRevealed() {
        val r = SimFields.rows(card, manual)
        assertEquals("•••••••••••••••4567", row(r, "ICCID"))
        assertFalse(row(r, "ICCID").contains("8934"))
        assertEquals(card.iccid, row(SimFields.rows(card, manual, reveal = true), "ICCID"))
        assertEquals("•", row(r, "Subscription id"))
    }

    @Test fun pinPukAlwaysMasked() {
        val r = SimFields.rows(card, manual, reveal = true)
        assertEquals("••••", row(r, "PIN"))
        assertEquals("••••••••", row(r, "PUK"))
        assertTrue(r.none { it.value.contains("1234") && it.label in setOf("PIN", "PUK") })
        assertEquals(setOf("pin", "puk"), SimFields.SECRET_KEYS)
    }

    @Test fun statusFormatting() {
        assertEquals("In service", StatusFormat.service(0))
        assertEquals("Connected", StatusFormat.data(2))
        assertEquals("Off hook", StatusFormat.call(2))
        assertEquals("5G NR", StatusFormat.rat(20))
        assertEquals("LTE", StatusFormat.rat(13))
        assertEquals("-", StatusFormat.dbm(Int.MAX_VALUE))
        assertEquals("1.5 GB", StatusFormat.bytes(1610612736L))
        assertEquals("-", StatusFormat.bytes(null))
        val s = SimStatus(0, "A", 0, "Op", "LTE", -95, -100, -10, Int.MAX_VALUE, "1", 2, listOf("10.0.0.2"), emptyList(),
            listOf("10.0.0.1"), null, false, true, 0, null, null, 1024L)
        assertEquals("-95 dBm · RSRP -100 dBm · RSRQ -10 dB", StatusFormat.signal(s))
        assertEquals("-", StatusFormat.rows(s).first { it.label == "IPv6" }.value)
    }

    @Test fun usagePeriodsAndTotals() {
        val today = LocalDate.of(2026, 11, 1)
        assertEquals(LocalDate.of(2026, 10, 15), SimUsage.cycleStart(today, "15"))
        assertEquals(LocalDate.of(2026, 11, 1), SimUsage.cycleStart(today, ""))
        assertEquals(LocalDate.of(2026, 2, 28), SimUsage.cycleStart(LocalDate.of(2026, 3, 1), "2026-01-31"))
        assertEquals(31, SimUsage.renewalDay("2026-01-31"))
        assertNull(SimUsage.renewalDay("soon"))
        val p = SimUsage.periods(today, "15", months = 3)
        assertEquals(5, p.size)
        val ev = listOf(
            UsageEvent(p[0].startMillis + 1, UsageEvent.Kind.CALL, true, 61),
            UsageEvent(p[0].startMillis + 2, UsageEvent.Kind.CALL, false, 30),
            UsageEvent(p[0].startMillis + 3, UsageEvent.Kind.SMS, true),
            UsageEvent(p[0].startMillis - 1, UsageEvent.Kind.SMS, false),
        )
        val t = SimUsage.totals(ev, p[0], 5L shl 30)
        assertEquals(1, t.callsOut); assertEquals(2, t.minutesOut); assertEquals(1, t.minutesIn)
        assertEquals(1, t.smsOut); assertEquals(0, t.smsIn)
        assertEquals(1, SimUsage.totals(ev, p[1]).smsIn)
        assertEquals("5.0 GB of 20.0 GB (25%)", SimUsage.againstAllowance(t.dataBytes, "20 GB"))
        assertEquals("5.0 GB", SimUsage.againstAllowance(t.dataBytes, "unlimited"))
    }
}
