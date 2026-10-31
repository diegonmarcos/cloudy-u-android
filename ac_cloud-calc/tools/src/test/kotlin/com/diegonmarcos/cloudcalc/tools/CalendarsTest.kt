package com.diegonmarcos.cloudcalc.tools

import com.diegonmarcos.cloudcalc.tools.Calendars.Ymd
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1377 every calendar against published reference dates, plus round trips over a span of days. */
class CalendarsTest {
    private fun d(s: String) = LocalDate.parse(s)

    /** Each next day is the next day: day + 1, or day 1 of the next month, or month 1 day 1 of the next year (or [first] month). */
    private fun continuous(name: String, first: Int = 1, maxMonth: Int, f: (LocalDate) -> Ymd) {
        var x = d("1990-01-01"); var prev = f(x)
        while (x < d("2040-01-01")) {
            x = x.plusDays(1); val n = f(x)
            val ok = (n.year == prev.year && n.month == prev.month && n.day == prev.day + 1) ||
                (n.day == 1 && n.year == prev.year && n.month != first && prev.day >= 28) ||
                (n.day == 1 && n.month == first && prev.day >= 5 && (n.year == prev.year + 1 || first != 1))
            assertTrue("$name $x: $prev -> $n", ok && n.month in 1..maxMonth && n.day in 1..31)
            prev = n
        }
    }

    @Test fun everyDayFollows() {
        continuous("julian", maxMonth = 12) { Calendars.julian(it) }
        continuous("hebrew", first = 7, maxMonth = 13) { Calendars.hebrew(it) }
        continuous("islamic", maxMonth = 12) { Calendars.islamicCivil(it) }
        continuous("umm al-qura", maxMonth = 12) { Calendars.ummAlQura(it)!! }
        continuous("persian", maxMonth = 12) { Calendars.persian(it) }
        continuous("coptic", maxMonth = 13) { Calendars.coptic(it) }
        continuous("ethiopian", maxMonth = 13) { Calendars.ethiopian(it) }
        continuous("saka", maxMonth = 12) { Calendars.saka(it) }
        continuous("french", maxMonth = 13) { Calendars.french(it) }
    }

    @Test fun julianDay() {
        assertEquals(2440588L, Calendars.jdn(d("1970-01-01")))
        assertEquals(2451545L, Calendars.jdn(d("2000-01-01")))
        assertEquals(2456283L, Calendars.jdn(d("2012-12-21")))
    }

    @Test fun julian() {
        assertEquals(Ymd(2026, 10, 18), Calendars.julian(d("2026-10-31")))
        assertEquals(Ymd(1582, 10, 5), Calendars.julian(d("1582-10-15"))) // the Gregorian reform
        assertEquals(Ymd(2000, 2, 29), Calendars.julian(d("2000-03-13")))
        assertEquals(Calendars.jdn(d("1582-10-15")), Calendars.julianToJdn(1582, 10, 5))
    }

    @Test fun hebrew() {
        assertEquals(Ymd(5785, 7, 1), Calendars.hebrew(d("2024-10-03"))) // Rosh Hashanah 5785
        assertEquals(Ymd(5784, 7, 1), Calendars.hebrew(d("2023-09-16"))) // Rosh Hashanah 5784
        assertEquals(Ymd(5784, 13, 1), Calendars.hebrew(d("2024-03-11"))) // 1 Adar II, a leap year
        assertEquals(Ymd(5784, 1, 15), Calendars.hebrew(d("2024-04-23"))) // Pesach
        assertEquals(Ymd(5787, 8, 20), Calendars.hebrew(d("2026-10-31")))
        assertEquals(Ymd(5780, 12, 4), Calendars.hebrew(d("2020-02-29")))
        assertTrue(Calendars.hebrewLeap(5784)); assertFalse(Calendars.hebrewLeap(5785))
        assertEquals("Adar I", Calendars.hebrewMonthName(12, 5784)); assertEquals("Adar", Calendars.hebrewMonthName(12, 5785))
        assertEquals(383L, Calendars.hebrewYearDays(5784)); assertEquals(355L, Calendars.hebrewYearDays(5785))
        var x = d("1990-01-01")
        while (x < d("2040-01-01")) { // every day round-trips
            val h = Calendars.hebrew(x)
            assertEquals(x.toString(), Calendars.rd(x), Calendars.hebrewToRd(h.year, h.month, h.day))
            x = x.plusDays(7)
        }
    }

    @Test fun islamic() {
        assertEquals(Ymd(1445, 9, 1), Calendars.islamicCivil(d("2024-03-11")))
        assertEquals(Ymd(1448, 5, 19), Calendars.islamicCivil(d("2026-10-31")))
        assertEquals(Ymd(1, 1, 1), Calendars.islamicCivil(d("0622-07-19"))) // epoch, Julian 16 July 622
        assertEquals(Ymd(1445, 9, 1), Calendars.ummAlQura(d("2024-03-11"))) // 1 Ramadan 1445
        assertEquals(Ymd(1446, 1, 1), Calendars.ummAlQura(d("2024-07-07"))) // 1 Muharram 1446
        assertNull(Calendars.ummAlQura(d("1800-01-01")))
    }

    @Test fun persian() {
        assertEquals(Ymd(1403, 1, 1), Calendars.persian(d("2024-03-20"))) // Nowruz 1403
        assertEquals(Ymd(1405, 1, 1), Calendars.persian(d("2026-03-21"))) // Nowruz 1405
        assertEquals(Ymd(1357, 11, 22), Calendars.persian(d("1979-02-11")))
        assertEquals(Ymd(1405, 8, 9), Calendars.persian(d("2026-10-31")))
        assertEquals(Ymd(1402, 12, 29), Calendars.persian(d("2024-03-19")))
        assertEquals(Ymd(1403, 12, 30), Calendars.persian(d("2025-03-20"))) // 1403 is leap: 30 Esfand
        assertEquals(Ymd(1404, 12, 29), Calendars.persian(d("2026-03-20")))
        assertTrue(Calendars.persianLeap(1403)); assertFalse(Calendars.persianLeap(1404))
        assertEquals(Ymd(1403, 6, 31), Calendars.persian(d("2024-09-21")))
        assertEquals(Ymd(1403, 7, 1), Calendars.persian(d("2024-09-22")))
    }

    @Test fun copticEthiopianSaka() {
        assertEquals(Ymd(1743, 1, 1), Calendars.coptic(d("2026-09-11")))
        assertEquals(Ymd(2019, 1, 1), Calendars.ethiopian(d("2026-09-11"))) // Enkutatash
        assertEquals(Ymd(2018, 13, 5), Calendars.ethiopian(d("2026-09-10"))) // Pagume 5
        assertEquals(Ymd(2017, 4, 29), Calendars.ethiopian(d("2025-01-07"))) // Ethiopian Christmas, Tahsas 29
        assertEquals(Ymd(1946, 1, 1), Calendars.saka(d("2024-03-21"))) // leap year: 21 March
        assertEquals(Ymd(1948, 1, 1), Calendars.saka(d("2026-03-22")))
        assertEquals(Ymd(1947, 12, 30), Calendars.saka(d("2026-03-21")))
        assertEquals(Ymd(1948, 8, 9), Calendars.saka(d("2026-10-31")))
        assertEquals(Ymd(1946, 1, 31), Calendars.saka(d("2024-04-20"))) // Chaitra has 31 days in a leap year
    }

    @Test fun eras() {
        assertEquals(Triple("Reiwa", "令和", 1), Calendars.japanese(d("2019-05-01")))
        assertEquals(Triple("Heisei", "平成", 31), Calendars.japanese(d("2019-04-30")))
        assertEquals(Triple("Reiwa", "令和", 8), Calendars.japanese(d("2026-10-31")))
        assertEquals("Shōwa", Calendars.japanese(d("1989-01-07"))?.first)
        assertNull(Calendars.japanese(d("1868-10-22")))
        assertEquals(2569, Calendars.buddhist(d("2026-10-31")))
        assertEquals(12026, Calendars.holocene(d("2026-10-31")))
    }

    @Test fun maya() {
        assertEquals(listOf(13L, 0L, 0L, 0L, 0L), Calendars.longCount(d("2012-12-21")))
        assertEquals(4 to "Ajaw", Calendars.tzolkin(d("2012-12-21")))
        assertEquals(3 to "K'ank'in", Calendars.haab(d("2012-12-21")))
        assertEquals(listOf(13L, 0L, 14L, 1L, 2L), Calendars.longCount(d("2026-10-31")))
        assertEquals(9 to "Ik'", Calendars.tzolkin(d("2026-10-31")))
        assertEquals(15 to "Sak'", Calendars.haab(d("2026-10-31")))
    }

    @Test fun aztec() {
        val fall = d("1521-08-23") // Julian 13 August 1521
        assertEquals(Calendars.AZTEC_JDN, Calendars.jdn(fall))
        assertEquals(1 to "Cōātl", Calendars.tonalpohualli(fall))
        assertEquals(2 to "Xōcotl Huetzi", Calendars.xiuhpohualli(fall))
        assertEquals(2 to "Miquiztli", Calendars.tonalpohualli(fall.plusDays(1)))
        assertEquals(1 to "Ochpaniztli", Calendars.xiuhpohualli(fall.plusDays(19)))
    }

    @Test fun thirteenMoon() {
        assertEquals(34, Calendars.kin(d("1987-07-26"))) // White Galactic Wizard
        assertEquals("White Galactic Wizard", Calendars.kinLabel(34))
        assertEquals(164, Calendars.kin(d("2013-07-26"))) // Yellow Galactic Seed
        assertEquals(Calendars.kin(d("2020-02-28")), Calendars.kin(d("2020-02-29")))
        assertEquals(Calendars.kin(d("2020-02-28")) % 260 + 1, Calendars.kin(d("2020-03-01")))
        assertEquals(33, Calendars.kin(d("1987-07-25")))
        assertEquals(1 to 1, Calendars.thirteenMoon(d("2026-07-26")))
        assertEquals(4 to 14, Calendars.thirteenMoon(d("2026-10-31")))
        assertEquals(0 to 0, Calendars.thirteenMoon(d("2026-07-25"))) // Day Out of Time
        assertEquals(13 to 28, Calendars.thirteenMoon(d("2026-07-24")))
        assertEquals(0 to -1, Calendars.thirteenMoon(d("2024-02-29")))
        assertEquals(13 to 28, Calendars.thirteenMoon(d("2024-07-24"))) // across a leap day
    }

    @Test fun internationalFixed() {
        assertEquals(11 to 24, Calendars.fixed(d("2026-10-31")))
        assertEquals(1 to 1, Calendars.fixed(d("2026-01-01")))
        assertEquals(7 to 1, Calendars.fixed(d("2026-06-18"))) // 1 Sol
        assertEquals(0 to 2, Calendars.fixed(d("2026-12-31"))) // Year Day
        assertEquals(0 to 1, Calendars.fixed(d("2024-06-17"))) // Leap Day
        assertEquals(7 to 1, Calendars.fixed(d("2024-06-18")))
        assertEquals(13 to 28, Calendars.fixed(d("2024-12-30")))
    }

    @Test fun frenchRepublican() {
        assertEquals(Ymd(1, 1, 1), Calendars.french(d("1792-09-22")))
        assertEquals(Ymd(8, 2, 18), Calendars.french(d("1799-11-09"))) // 18 Brumaire an VIII
        assertEquals(Ymd(2, 11, 9), Calendars.french(d("1794-07-27"))) // 9 Thermidor an II
        assertEquals(Ymd(3, 13, 6), Calendars.french(d("1795-09-22"))) // year III was sextile
        assertEquals(Ymd(12, 1, 1), Calendars.french(d("1803-09-24")))
        assertEquals(Ymd(15, 1, 1), Calendars.french(d("1806-09-23")))
        assertEquals(Ymd(235, 2, 10), Calendars.french(d("2026-10-31")))
        assertEquals("CCXXXV", Calendars.romanNumeral(235)); assertEquals("MCMXCIV", Calendars.romanNumeral(1994))
        assertEquals("0", Calendars.romanNumeral(0))
    }

    @Test fun discordian() {
        assertEquals("Sweetmorn, Chaos 1, 3192 YOLD", Calendars.discordian(d("2026-01-01")))
        assertEquals("Prickle-Prickle, The Aftermath 12, 3192 YOLD", Calendars.discordian(d("2026-10-31")))
        assertEquals("St. Tib's Day, 3190 YOLD", Calendars.discordian(d("2024-02-29")))
        assertEquals("Setting Orange, Chaos 60, 3190 YOLD", Calendars.discordian(d("2024-03-01")))
        assertEquals("Setting Orange, The Aftermath 73, 3192 YOLD", Calendars.discordian(d("2026-12-31")))
    }

    @Test fun chinese() {
        assertEquals("丙午 year of the Fire Horse, month 9, day 11", Calendars.chinese(Calendars.Chinese(43, 9, false, 11)))
        assertEquals("甲子 year of the Wood Rat, leap month 4, day 1", Calendars.chinese(Calendars.Chinese(1, 4, true, 1)))
        assertEquals("癸亥 year of the Water Pig, month 12, day 30", Calendars.chinese(Calendars.Chinese(60, 12, false, 30)))
    }

    @Test fun moon() {
        assertEquals("Full moon", Calendars.moonPhase(Calendars.moonAge(d("2024-04-23"))))
        assertEquals("New moon", Calendars.moonPhase(Calendars.moonAge(d("2024-10-03"))))
        assertEquals("First quarter", Calendars.moonPhase(Calendars.moonAge(d("2012-12-21"))))
        assertEquals("Waning gibbous", Calendars.moonPhase(Calendars.moonAge(d("2026-10-31"))))
        assertEquals(0.0, Calendars.illumination(0.0), 1e-9)
        assertEquals(1.0, Calendars.illumination(Calendars.SYNODIC / 2), 1e-9)
        val a = Calendars.moonAge(d("1900-01-01")); assertTrue(a >= 0 && a < Calendars.SYNODIC)
    }

    @Test fun all() {
        val list = Calendars.all(d("2026-10-31"), Calendars.Chinese(43, 9, false, 11))
        val by = list.associateBy { it.id }
        assertEquals(23, list.size)
        assertTrue(list.all { it.note.isNotBlank() && it.value.isNotBlank() })
        assertEquals("Saturday, 31 October 2026", by.getValue("gregorian").value)
        assertEquals("18 October 2026", by.getValue("julian").value)
        assertTrue(by.getValue("julian").note.contains("13 days"))
        assertEquals("2026-W44-6", by.getValue("iso").value)
        assertEquals("20 Cheshvan 5787 AM", by.getValue("hebrew").value)
        assertEquals("19 Jumada al-Ula 1448 AH", by.getValue("islamic").value)
        assertEquals("20 Jumada al-Ula 1448 AH", by.getValue("umm_al_qura").value)
        assertEquals("9 Aban 1405 AP", by.getValue("persian").value)
        assertTrue(by.getValue("chinese").value.startsWith("丙午"))
        assertEquals("9 Kartika 1948 Saka", by.getValue("saka").value)
        assertEquals("31 October 2569 BE", by.getValue("buddhist").value)
        assertEquals("Reiwa 8 (令和8年), 10/31", by.getValue("japanese").value)
        assertEquals("21 Paopi 1743 AM", by.getValue("coptic").value)
        assertEquals("21 Tikimt 2019 EC", by.getValue("ethiopian").value)
        assertEquals("13.0.14.1.2  ·  9 Ik'  ·  15 Sak'", by.getValue("maya").value)
        assertTrue(by.getValue("thirteen_moon").value.startsWith("Self-Existing Moon 14  ·  Kin "))
        assertEquals("24 October 2026", by.getValue("fixed").value)
        assertEquals("10 Brumaire an CCXXXV", by.getValue("french").value)
        assertEquals("31 October 12026 HE", by.getValue("holocene").value)
        assertEquals("1793404800", by.getValue("unix").value)
        assertEquals("2461345", by.getValue("jdn").value)
        assertTrue(by.getValue("moon").value.startsWith("Waning gibbous, age 20."))
        assertEquals("—", Calendars.all(d("2026-10-31"), null).first { it.id == "chinese" }.value)
        assertEquals("Day Out of Time  ·  Kin " + Calendars.kin(d("2026-07-25")) + " " + Calendars.kinLabel(Calendars.kin(d("2026-07-25"))),
            Calendars.all(d("2026-07-25"), null).first { it.id == "thirteen_moon" }.value)
        assertEquals("Leap Day 2024", Calendars.all(d("2024-06-17"), null).first { it.id == "fixed" }.value)
        assertEquals("Year Day 2026", Calendars.all(d("2026-12-31"), null).first { it.id == "fixed" }.value)
        assertEquals("Sansculottide 1 an CCXXXIV", Calendars.all(d("2026-09-17"), null).first { it.id == "french" }.value)
        assertEquals(21, Calendars.all(d("1800-01-01"), null).size) // no Umm al-Qura, no Japanese era
    }
}
