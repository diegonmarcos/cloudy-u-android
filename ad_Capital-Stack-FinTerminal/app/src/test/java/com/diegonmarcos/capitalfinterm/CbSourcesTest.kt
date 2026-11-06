package com.diegonmarcos.capitalfinterm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1539 the parsing of each API's response fixture (trimmed real answers in src/test/resources/cb) and the annualisation of a sub-annual series. */
class CbSourcesTest {
    @Test fun imfDataMapperAnswerIsIso3ByYear() {
        val m = CbSources.imf(DataFiles.cbFixture("imf-datamapper.json"), "NGDP_RPCH")
        assertEquals(listOf("EURO", "JPN", "USA"), m.keys.sorted())
        assertEquals(2.3, m.getValue("USA").getValue(2026), 1e-9)
        assertEquals(listOf(2024, 2025, 2026, 2027), m.getValue("USA").keys.sorted())
        assertTrue("an unknown series is empty, not an error", CbSources.imf(DataFiles.cbFixture("imf-datamapper.json"), "NOPE").isEmpty())
    }

    @Test fun oecdEconomicOutlookCsvKeepsAreaMeasureYearAndTheRelease() {
        val (m, rel) = CbSources.oecd(DataFiles.cbFixture("oecd-eo.csv"))
        assertEquals("Economic Outlook 119", rel)
        assertEquals(0.143465786428121, m.getValue("USA" to "GAP").getValue(2024), 1e-12)
        assertEquals(listOf(2024, 2025, 2026), m.getValue("USA" to "UNR").keys.sorted())
    }

    @Test fun worldBankAnswerKeepsIso3YearAndLastUpdated() {
        val (m, upd) = CbSources.worldBank(DataFiles.cbFixture("worldbank.json"))
        assertEquals("2026-10-08", upd)
        assertEquals(listOf("ARE", "EMU"), m.keys.sorted())
        assertEquals(1.250865245598, m.getValue("ARE").getValue(2025), 1e-12)
        assertTrue("not JSON is empty", CbSources.worldBank("<html>").first.isEmpty())
    }

    @Test fun bisCsvKeepsAreaAndMonth() {
        val m = CbSources.bis(DataFiles.cbFixture("bis-cbpol.csv"))
        assertEquals(listOf("GB", "US"), m.keys.sorted())
        assertEquals(3.875, m.getValue("US").getValue(2026 to 10), 1e-9)
        assertEquals(2025 to 12, CbSources.period("2025-Q4"))
        assertEquals(2026 to 3, CbSources.period("2026-03-31"))
    }

    @Test fun fredCsvGroupsDaysByMonthAndSkipsMissingDays() {
        val m = CbSources.fred(DataFiles.cbFixture("fred.csv"))
        assertEquals(listOf(4.19, 4.17, 4.18, 4.15, 4.19, 4.18), m.getValue(2026 to 1))
        assertEquals(mapOf((2026 to 1) to listOf(1.5)), CbSources.fred("observation_date,X\n2026-01-02,.\n2026-01-05,1.5\n"))
        assertTrue("a page that is not a FRED csv is empty", CbSources.fred("<html>not found</html>").isEmpty())
    }

    @Test fun ecbProjectionCsvIsYearByValue() {
        val (m, _) = CbSources.ecb(DataFiles.cbFixture("ecb-mpd.csv"))
        assertEquals(mapOf(2026 to 0.9, 2027 to 1.4, 2028 to 1.5), m)
    }

    @Test fun quotedCsvFieldsKeepTheirCommas() {
        assertEquals(listOf("a", "b,c", "d\"e"), CbSources.split("a,\"b,c\",\"d\"\"e\""))
    }

    @Test fun annualiseAveragesCompleteYearsAndKeepsTheYearInProgressApart() {
        val (a, ytd) = CbSources.annualise(mapOf((2024 to 1) to listOf(2.0), (2024 to 2) to listOf(4.0), (2025 to 1) to listOf(3.0), (2025 to 2) to listOf(3.0), (2026 to 1) to listOf(6.0)), "avg", "")
        assertEquals(mapOf(2024 to 3.0, 2025 to 3.0), a)
        assertEquals(CbYtd(2026, 6.0, "2026-01"), ytd)
    }

    @Test fun annualiseYoyAndLast() {
        val (a, ytd) = CbSources.annualise(mapOf((2024 to 1) to listOf(100.0), (2025 to 1) to listOf(110.0), (2026 to 1) to listOf(121.0)), "avg", "yoy")
        assertEquals(1, a.size); assertEquals(10.0, a.getValue(2025), 1e-9); assertEquals(10.0, ytd!!.value, 1e-9)
        val (b, _) = CbSources.annualise(mapOf((2023 to 12) to listOf(100.0), (2024 to 12) to listOf(110.0), (2025 to 12) to listOf(121.0), (2026 to 3) to listOf(1.0)), "last", "yoy")
        assertEquals(10.0, b.getValue(2024), 1e-9); assertEquals(10.0, b.getValue(2025), 1e-9)
        assertNull(CbSources.yoy(mapOf(2024 to 0.0, 2025 to 1.0))[2025])
    }
}
