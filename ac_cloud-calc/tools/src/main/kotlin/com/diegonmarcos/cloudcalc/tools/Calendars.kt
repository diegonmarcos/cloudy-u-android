package com.diegonmarcos.cloudcalc.tools

import java.time.LocalDate
import java.time.chrono.HijrahDate
import java.time.format.TextStyle
import java.time.temporal.ChronoField
import java.time.temporal.IsoFields
import java.util.Locale
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * #1377 Calc ▸ Tools ▸ Time & Date ▸ Calendars: one Gregorian date in every calendar this app knows, offline.
 * Day counts go through the Julian Day Number (JDN, the day number at noon; 1970-01-01 is 2440588) and Rata Die
 * (RD, 0001-01-01 Gregorian is 1), the fixed-day arithmetic of Reingold & Dershowitz's Calendrical Calculations.
 * Umm al-Qura is java.time's HijrahDate; the Chinese lunisolar date needs an astronomical table, so the app reads it
 * from android.icu and hands it in as [Chinese] ([chinese] formats it).
 */
object Calendars {
    data class Entry(val id: String, val name: String, val value: String, val note: String)

    /** A Chinese date as android.icu's ChineseCalendar reads it: [cycleYear] 1..60 in the sexagenary cycle, [month] 1..12. */
    data class Chinese(val cycleYear: Int, val month: Int, val leapMonth: Boolean, val day: Int)

    /** A date in a month-based calendar; [month] is 1-based in that calendar's own order. */
    data class Ymd(val year: Long, val month: Int, val day: Int)

    const val UNIX_JDN = 2440588L
    private const val RD_OFFSET = 719163L // RD of 1970-01-01

    fun jdn(d: LocalDate): Long = d.toEpochDay() + UNIX_JDN
    fun rd(d: LocalDate): Long = d.toEpochDay() + RD_OFFSET
    private fun fromRd(rd: Long): LocalDate = LocalDate.ofEpochDay(rd - RD_OFFSET)
    private fun fd(a: Long, b: Long) = Math.floorDiv(a, b)
    private fun fm(a: Long, b: Long) = Math.floorMod(a, b)

    // ── Julian ────────────────────────────────────────────────────────────────────────────────
    fun julian(d: LocalDate): Ymd {
        val c = jdn(d) + 32082
        val dd = fd(4 * c + 3, 1461)
        val e = c - fd(1461 * dd, 4)
        val m = fd(5 * e + 2, 153)
        return Ymd(dd - 4800 + fd(m, 10), (m + 3 - 12 * fd(m, 10)).toInt(), (e - fd(153 * m + 2, 5) + 1).toInt())
    }

    fun julianToJdn(y: Long, m: Int, d: Int): Long {
        val a = fd((14 - m).toLong(), 12)
        val yy = y + 4800 - a
        val mm = m + 12 * a - 3
        return d + fd(153 * mm + 2, 5) + 365 * yy + fd(yy, 4) - 32083
    }

    // ── Hebrew ────────────────────────────────────────────────────────────────────────────────
    private const val HEBREW_EPOCH = -1373427L
    val HEBREW_MONTHS = listOf("Nisan", "Iyar", "Sivan", "Tammuz", "Av", "Elul", "Tishrei", "Cheshvan", "Kislev", "Tevet", "Shevat", "Adar", "Adar II")

    fun hebrewLeap(y: Long): Boolean = fm(7 * y + 1, 19) < 7
    private fun hebrewElapsed(y: Long): Long {
        val months = fd(235 * y - 234, 19)
        val parts = 12084 + 13753 * months
        val days = 29 * months + fd(parts, 25920)
        return if (fm(3 * (days + 1), 7) < 3) days + 1 else days
    }
    private fun hebrewCorrection(y: Long): Long {
        val ny0 = hebrewElapsed(y - 1); val ny1 = hebrewElapsed(y); val ny2 = hebrewElapsed(y + 1)
        return when { ny2 - ny1 == 356L -> 2; ny1 - ny0 == 382L -> 1; else -> 0 }
    }
    fun hebrewNewYear(y: Long): Long = HEBREW_EPOCH + hebrewElapsed(y) + hebrewCorrection(y)
    fun hebrewYearDays(y: Long): Long = hebrewNewYear(y + 1) - hebrewNewYear(y)
    fun hebrewMonthDays(m: Int, y: Long): Int {
        val len = hebrewYearDays(y)
        val short = m in setOf(2, 4, 6, 10, 13) || (m == 12 && !hebrewLeap(y)) ||
            (m == 8 && len % 10 != 5L) || (m == 9 && len % 10 == 3L)
        return if (short) 29 else 30
    }
    fun hebrewToRd(y: Long, m: Int, d: Int): Long {
        val last = if (hebrewLeap(y)) 13 else 12
        var days = hebrewNewYear(y) + d - 1
        if (m < 7) {
            for (mm in 7..last) days += hebrewMonthDays(mm, y)
            for (mm in 1 until m) days += hebrewMonthDays(mm, y)
        } else {
            for (mm in 7 until m) days += hebrewMonthDays(mm, y)
        }
        return days
    }
    fun hebrew(date: LocalDate): Ymd {
        val r = rd(date)
        var year = floor((r - HEBREW_EPOCH) / (35975351.0 / 98496)).toLong()
        while (hebrewNewYear(year + 1) <= r) year++
        while (hebrewNewYear(year) > r) year--
        val last = if (hebrewLeap(year)) 13 else 12
        val order = (7..last) + (1..6)
        val month = order.first { r <= hebrewToRd(year, it, hebrewMonthDays(it, year)) }
        return Ymd(year, month, (r - hebrewToRd(year, month, 1) + 1).toInt())
    }
    fun hebrewMonthName(m: Int, y: Long): String = if (m == 12 && hebrewLeap(y)) "Adar I" else HEBREW_MONTHS[m - 1]

    // ── Islamic (arithmetic civil) and Umm al-Qura ────────────────────────────────────────────
    private const val ISLAMIC_EPOCH = 227015L
    val ISLAMIC_MONTHS = listOf("Muharram", "Safar", "Rabi' al-Awwal", "Rabi' al-Thani", "Jumada al-Ula", "Jumada al-Akhirah",
        "Rajab", "Sha'ban", "Ramadan", "Shawwal", "Dhu al-Qa'dah", "Dhu al-Hijjah")

    fun islamicToRd(y: Long, m: Int, d: Int): Long = ISLAMIC_EPOCH - 1 + (y - 1) * 354 + fd(3 + 11 * y, 30) + 29L * (m - 1) + fd(m.toLong(), 2) + d
    fun islamicCivil(date: LocalDate): Ymd {
        val r = rd(date)
        val year = fd(30 * (r - ISLAMIC_EPOCH) + 10646, 10631)
        val month = (fd(11 * (r - islamicToRd(year, 1, 1)) + 330, 325)).toInt()
        return Ymd(year, month, (r - islamicToRd(year, month, 1) + 1).toInt())
    }
    /** Saudi Arabia's Umm al-Qura calendar (java.time's table, 1300–1600 AH); null outside it. */
    fun ummAlQura(date: LocalDate): Ymd? = runCatching {
        val h = HijrahDate.from(date)
        Ymd(h.get(ChronoField.YEAR_OF_ERA).toLong(), h.get(ChronoField.MONTH_OF_YEAR), h.get(ChronoField.DAY_OF_MONTH))
    }.getOrNull()

    // ── Persian (Solar Hijri; Borkowski's break years, exact for 1–3177 AP) ────────────────────
    val PERSIAN_MONTHS = listOf("Farvardin", "Ordibehesht", "Khordad", "Tir", "Mordad", "Shahrivar", "Mehr", "Aban", "Azar", "Dey", "Bahman", "Esfand")
    private val BREAKS = longArrayOf(-61, 9, 38, 199, 426, 686, 756, 818, 1111, 1181, 1210, 1635, 2060, 2097, 2192, 2262, 2324, 2394, 2456, 3178)
    private fun tdiv(a: Long, b: Long) = a / b
    private fun tmod(a: Long, b: Long) = a % b

    /** The Persian year [jy]: its leap position (0 = leap), and the March day of Nowruz in Gregorian year jy + 621. */
    fun persianYear(jy: Long): Pair<Long, Int> {
        require(jy >= BREAKS.first() && jy < BREAKS.last()) { "Persian year out of range: $jy" }
        val gy = jy + 621
        var leapJ = -14L
        var jp = BREAKS[0]
        var jump = 0L
        for (i in 1 until BREAKS.size) {
            val jm = BREAKS[i]
            jump = jm - jp
            if (jy < jm) break
            leapJ += tdiv(jump, 33) * 8 + tdiv(tmod(jump, 33), 4)
            jp = jm
        }
        var n = jy - jp
        leapJ += tdiv(n, 33) * 8 + tdiv(tmod(n, 33) + 3, 4)
        if (tmod(jump, 33) == 4L && jump - n == 4L) leapJ += 1
        val leapG = tdiv(gy, 4) - tdiv((tdiv(gy, 100) + 1) * 3, 4) - 150
        val march = (20 + leapJ - leapG).toInt()
        if (jump - n < 6) n = n - jump + tdiv(jump + 4, 33) * 33
        var leap = tmod(tmod(n + 1, 33) - 1, 4)
        if (leap == -1L) leap = 4
        return leap to march
    }
    fun persian(date: LocalDate): Ymd {
        var jy = date.year - 621L
        val (leap, march) = persianYear(jy)
        var k = date.toEpochDay() - LocalDate.of(date.year, 3, march).toEpochDay()
        if (k < 0) {
            // Before Nowruz: the last 6 months of the year before, 30 Esfand when that year was leap (this one's position 1).
            jy -= 1
            k += 179 + if (leap == 1L) 1 else 0
        } else if (k <= 185) {
            return Ymd(jy, (1 + k / 31).toInt(), (k % 31 + 1).toInt())
        } else k -= 186
        return Ymd(jy, (7 + k / 30).toInt(), (k % 30 + 1).toInt())
    }
    fun persianLeap(jy: Long): Boolean = persianYear(jy).first == 0L

    // ── Coptic and Ethiopian (13 months: 12 × 30 + 5 or 6) ─────────────────────────────────────
    private const val COPTIC_EPOCH = 103605L
    private const val ETHIOPIC_EPOCH = 2796L
    val COPTIC_MONTHS = listOf("Thout", "Paopi", "Hathor", "Koiak", "Tobi", "Meshir", "Paremhat", "Parmouti", "Pashons", "Paoni", "Epip", "Mesori", "Pi Kogi Enavot")
    val ETHIOPIAN_MONTHS = listOf("Meskerem", "Tikimt", "Hidar", "Tahsas", "Tir", "Yekatit", "Megabit", "Miyazya", "Ginbot", "Sene", "Hamle", "Nehase", "Pagume")

    private fun copticToRd(epoch: Long, y: Long, m: Int, d: Int): Long = epoch - 1 + 365 * (y - 1) + fd(y, 4) + 30L * (m - 1) + d
    private fun alexandrian(epoch: Long, date: LocalDate): Ymd {
        val r = rd(date)
        val year = fd(4 * (r - epoch) + 1463, 1461)
        val month = (fd(r - copticToRd(epoch, year, 1, 1), 30) + 1).toInt()
        return Ymd(year, month, (r + 1 - copticToRd(epoch, year, month, 1)).toInt())
    }
    fun coptic(date: LocalDate): Ymd = alexandrian(COPTIC_EPOCH, date)
    fun ethiopian(date: LocalDate): Ymd = alexandrian(ETHIOPIC_EPOCH, date)

    // ── Indian national (Saka) ────────────────────────────────────────────────────────────────
    val SAKA_MONTHS = listOf("Chaitra", "Vaishakha", "Jyeshtha", "Ashadha", "Shravana", "Bhadra", "Ashvin", "Kartika", "Agrahayana", "Pausha", "Magha", "Phalguna")
    private fun chaitra1(gy: Int): LocalDate = LocalDate.of(gy, 3, if (java.time.Year.isLeap(gy.toLong())) 21 else 22)
    fun saka(date: LocalDate): Ymd {
        val gy = if (date < chaitra1(date.year)) date.year - 1 else date.year
        val leap = java.time.Year.isLeap(gy.toLong())
        var k = (date.toEpochDay() - chaitra1(gy).toEpochDay()).toInt()
        val lengths = listOf(if (leap) 31 else 30, 31, 31, 31, 31, 31, 30, 30, 30, 30, 30, 30)
        var m = 0
        while (k >= lengths[m]) { k -= lengths[m]; m++ }
        return Ymd(gy - 78L, m + 1, k + 1)
    }

    // ── Japanese era, Buddhist, Holocene ──────────────────────────────────────────────────────
    private val ERAS = listOf(
        Triple("Reiwa", "令和", LocalDate.of(2019, 5, 1)), Triple("Heisei", "平成", LocalDate.of(1989, 1, 8)),
        Triple("Shōwa", "昭和", LocalDate.of(1926, 12, 25)), Triple("Taishō", "大正", LocalDate.of(1912, 7, 30)),
        Triple("Meiji", "明治", LocalDate.of(1868, 10, 23)),
    )
    /** The era name, its kanji and the era year; null before Meiji. */
    fun japanese(date: LocalDate): Triple<String, String, Int>? =
        ERAS.firstOrNull { date >= it.third }?.let { Triple(it.first, it.second, date.year - it.third.year + 1) }
    fun buddhist(date: LocalDate): Int = date.year + 543
    fun holocene(date: LocalDate): Int = date.year + 10000

    // ── Mayan and Aztec ───────────────────────────────────────────────────────────────────────
    /** GMT correlation: the Long Count 0.0.0.0.0 (4 Ajaw 8 Kumk'u) is JDN 584283. */
    const val MAYA_JDN = 584283L
    val TZOLKIN = listOf("Imix", "Ik'", "Ak'b'al", "K'an", "Chikchan", "Kimi", "Manik'", "Lamat", "Muluk", "Ok", "Chuwen", "Eb'", "B'en", "Ix", "Men", "Kib'", "Kab'an", "Etz'nab'", "Kawak", "Ajaw")
    val HAAB = listOf("Pop", "Wo'", "Sip", "Sotz'", "Sek", "Xul", "Yaxk'in", "Mol", "Ch'en", "Yax", "Sak'", "Keh", "Mak", "K'ank'in", "Muwan", "Pax", "K'ayab", "Kumk'u", "Wayeb'")

    fun longCount(date: LocalDate): List<Long> {
        val days = jdn(date) - MAYA_JDN
        return listOf(fd(days, 144000), fd(fm(days, 144000), 7200), fd(fm(days, 7200), 360), fd(fm(days, 360), 20), fm(days, 20))
    }
    fun tzolkin(date: LocalDate): Pair<Int, String> {
        val days = jdn(date) - MAYA_JDN
        return (fm(days + 3, 13) + 1).toInt() to TZOLKIN[fm(days + 19, 20).toInt()]
    }
    fun haab(date: LocalDate): Pair<Int, String> {
        val h = fm(jdn(date) - MAYA_JDN + 348, 365)
        return (h % 20).toInt() to HAAB[(h / 20).toInt()]
    }

    /** Caso's correlation: the fall of Tenochtitlan, Julian 1521-08-13, was 1 Cōātl in the 2nd day of Xōcotl Huetzi. */
    val AZTEC_JDN = julianToJdn(1521, 8, 13)
    val TONALLI = listOf("Cipactli", "Ehēcatl", "Calli", "Cuetzpalin", "Cōātl", "Miquiztli", "Mazātl", "Tōchtli", "Ātl", "Itzcuintli",
        "Ozomahtli", "Malīnalli", "Ācatl", "Ocēlōtl", "Cuāuhtli", "Cōzcacuāuhtli", "Olīn", "Tecpatl", "Quiyahuitl", "Xōchitl")
    val XIUHPOHUALLI = listOf("Izcalli", "Ātlcahualo", "Tlācaxīpēhualiztli", "Tōzōztōntli", "Huēyi Tōzōztli", "Tōxcatl", "Etzalcualiztli",
        "Tēcuilhuitōntli", "Huēyi Tēcuilhuitl", "Tlaxōchimaco", "Xōcotl Huetzi", "Ochpaniztli", "Teōtl Eco", "Tepēilhuitl", "Quecholli",
        "Panquetzaliztli", "Ātemoztli", "Tititl", "Nēmontēmi")
    fun tonalpohualli(date: LocalDate): Pair<Int, String> {
        val c = fm(jdn(date) - AZTEC_JDN + 104, 260)
        return (c % 13 + 1).toInt() to TONALLI[(c % 20).toInt()]
    }
    fun xiuhpohualli(date: LocalDate): Pair<Int, String> {
        val c = fm(jdn(date) - AZTEC_JDN + 201, 365)
        return (c % 20 + 1).toInt() to XIUHPOHUALLI[(c / 20).toInt()]
    }

    // ── 13-Moon / Dreamspell ──────────────────────────────────────────────────────────────────
    val MOONS = listOf("Magnetic", "Lunar", "Electric", "Self-Existing", "Overtone", "Rhythmic", "Resonant", "Galactic", "Solar", "Planetary", "Spectral", "Crystal", "Cosmic")
    val SEALS = listOf("Red Dragon", "White Wind", "Blue Night", "Yellow Seed", "Red Serpent", "White World-Bridger", "Blue Hand", "Yellow Star",
        "Red Moon", "White Dog", "Blue Monkey", "Yellow Human", "Red Skywalker", "White Wizard", "Blue Eagle", "Yellow Warrior", "Red Earth",
        "White Mirror", "Blue Storm", "Yellow Sun")
    private val DREAMSPELL_ANCHOR = LocalDate.of(1987, 7, 26) // Kin 34, White Galactic Wizard
    private fun isLeapDay(d: LocalDate) = d.monthValue == 2 && d.dayOfMonth == 29
    /** Days from [a] to [b] (a ≤ b) with every 29 February skipped, as the 13-Moon count does. */
    fun daysSkippingLeap(a: LocalDate, b: LocalDate): Long {
        var n = b.toEpochDay() - a.toEpochDay()
        for (y in a.year..b.year) if (java.time.Year.isLeap(y.toLong())) {
            val l = LocalDate.of(y, 2, 29)
            if (l > a && l <= b) n--
        }
        return n
    }
    /** (moon 1..13, day 1..28); (0, 0) for the Day Out of Time (25 July), (0, -1) for 0.0 Hunab Ku (29 February). */
    fun thirteenMoon(date: LocalDate): Pair<Int, Int> {
        if (isLeapDay(date)) return 0 to -1
        val start = LocalDate.of(if (date >= LocalDate.of(date.year, 7, 26)) date.year else date.year - 1, 7, 26)
        val d = daysSkippingLeap(start, date).toInt()
        if (d >= 364) return 0 to 0
        return (d / 28 + 1) to (d % 28 + 1)
    }
    /** The Dreamspell kin 1..260 (29 February shares 28 February's kin). */
    fun kin(date: LocalDate): Int {
        val d = if (isLeapDay(date)) date.minusDays(1) else date
        val n = if (d >= DREAMSPELL_ANCHOR) daysSkippingLeap(DREAMSPELL_ANCHOR, d) else -daysSkippingLeap(d, DREAMSPELL_ANCHOR)
        return (fm(33 + n, 260) + 1).toInt()
    }
    fun kinName(k: Int): String = MOONS[(k - 1) % 13] + " " + SEALS[(k - 1) % 20]
    fun kinLabel(k: Int): String = SEALS[(k - 1) % 20].substringBefore(' ') + " " + MOONS[(k - 1) % 13] + " " + SEALS[(k - 1) % 20].substringAfter(' ')

    // ── International Fixed (Cotsworth) ───────────────────────────────────────────────────────
    val IFC_MONTHS = listOf("January", "February", "March", "April", "May", "June", "Sol", "July", "August", "September", "October", "November", "December")
    /** (month 1..13, day 1..28); (0, 1) Leap Day (after 28 June), (0, 2) Year Day (after 28 December). */
    fun fixed(date: LocalDate): Pair<Int, Int> {
        var doy = date.dayOfYear
        if (date.isLeapYear) {
            if (doy == 169) return 0 to 1
            if (doy > 169) doy--
        }
        if (doy == 365) return 0 to 2
        return ((doy - 1) / 28 + 1) to ((doy - 1) % 28 + 1)
    }

    // ── French Republican: years I–XIV as kept (sextile III, VII, XI), then Romme's rule (leap like Gregorian, not every 4000) ──
    private const val FRENCH_EPOCH = 654415L
    val FRENCH_MONTHS = listOf("Vendémiaire", "Brumaire", "Frimaire", "Nivôse", "Pluviôse", "Ventôse", "Germinal", "Floréal", "Prairial", "Messidor", "Thermidor", "Fructidor", "Sansculottides")
    /** RD of 1 Vendémiaire of year [y]; both rules hold three leap years before year XV, so they meet there. */
    fun frenchNewYear(y: Long): Long = FRENCH_EPOCH + 365 * (y - 1) +
        if (y in 1..14) listOf(3, 7, 11).count { it < y }.toLong() else fd(y - 1, 4) - fd(y - 1, 100) + fd(y - 1, 400) - fd(y - 1, 4000)
    fun french(date: LocalDate): Ymd {
        val r = rd(date)
        var year = floor((r - FRENCH_EPOCH) / (1460969.0 / 4000)).toLong() + 1
        while (frenchNewYear(year + 1) <= r) year++
        while (frenchNewYear(year) > r) year--
        val doy = r - frenchNewYear(year)
        return Ymd(year, (doy / 30 + 1).toInt(), (doy % 30 + 1).toInt())
    }
    fun romanNumeral(n: Long): String {
        if (n <= 0 || n >= 4000) return n.toString()
        val v = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
        val s = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
        var x = n.toInt(); val b = StringBuilder()
        for (i in v.indices) while (x >= v[i]) { b.append(s[i]); x -= v[i] }
        return b.toString()
    }

    // ── Discordian ────────────────────────────────────────────────────────────────────────────
    val SEASONS = listOf("Chaos", "Discord", "Confusion", "Bureaucracy", "The Aftermath")
    val DISCORDIAN_DAYS = listOf("Sweetmorn", "Boomtime", "Pungenday", "Prickle-Prickle", "Setting Orange")
    /** "St. Tib's Day, 3190 YOLD" or "Prickle-Prickle, The Aftermath 12, 3192 YOLD". */
    fun discordian(date: LocalDate): String {
        val yold = date.year + 1166
        if (isLeapDay(date)) return "St. Tib's Day, $yold YOLD"
        var doy = date.dayOfYear
        if (date.isLeapYear && doy > 60) doy--
        return "${DISCORDIAN_DAYS[(doy - 1) % 5]}, ${SEASONS[(doy - 1) / 73]} ${(doy - 1) % 73 + 1}, $yold YOLD"
    }

    // ── Chinese (sexagenary cycle) ────────────────────────────────────────────────────────────
    val STEMS = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")
    val BRANCHES = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")
    val ANIMALS = listOf("Rat", "Ox", "Tiger", "Rabbit", "Dragon", "Snake", "Horse", "Goat", "Monkey", "Rooster", "Dog", "Pig")
    val ELEMENTS = listOf("Wood", "Fire", "Earth", "Metal", "Water")
    fun chinese(c: Chinese): String {
        val i = c.cycleYear - 1
        return "${STEMS[i % 10]}${BRANCHES[i % 12]} year of the ${ELEMENTS[i % 10 / 2]} ${ANIMALS[i % 12]}, " +
            (if (c.leapMonth) "leap " else "") + "month ${c.month}, day ${c.day}"
    }

    // ── Moon ──────────────────────────────────────────────────────────────────────────────────
    const val SYNODIC = 29.530588853
    private const val NEW_MOON_JD = 2451550.1 // 2000-01-06 ~14:24 UT
    val PHASES = listOf("New moon", "Waxing crescent", "First quarter", "Waxing gibbous", "Full moon", "Waning gibbous", "Last quarter", "Waning crescent")
    /** Moon age in days at noon UT of [date] (mean lunation; within about a day of the true phase). */
    fun moonAge(date: LocalDate): Double {
        val a = (jdn(date) - NEW_MOON_JD) % SYNODIC
        return if (a < 0) a + SYNODIC else a
    }
    fun moonPhase(age: Double): String = PHASES[(age / SYNODIC * 8).roundToInt() % 8]
    fun illumination(age: Double): Double = (1 - cos(2 * Math.PI * age / SYNODIC)) / 2

    // ── All of them ───────────────────────────────────────────────────────────────────────────
    private fun weekday(d: LocalDate) = d.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)

    fun all(d: LocalDate, chinese: Chinese?): List<Entry> {
        val out = ArrayList<Entry>()
        fun add(id: String, name: String, value: String, note: String) = out.add(Entry(id, name, value, note))
        add("gregorian", "Gregorian", "${weekday(d)}, ${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${d.year}",
            "Civil calendar since 1582: 365 days, leap every 4 years except centuries not divisible by 400.")
        val j = julian(d)
        add("julian", "Julian", "${j.day} ${java.time.Month.of(j.month).getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${j.year}",
            "Julius Caesar's 46 BC calendar, a leap year every 4 years; ${jdn(d) - julianToJdn(d.year.toLong(), d.monthValue, d.dayOfMonth)} days behind the Gregorian now; Orthodox church dates.")
        add("iso", "ISO week", "%d-W%02d-%d".format(d.get(IsoFields.WEEK_BASED_YEAR), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR), d.dayOfWeek.value),
            "ISO 8601: weeks start Monday; week 1 holds the year's first Thursday.")
        val h = hebrew(d)
        add("hebrew", "Hebrew", "${h.day} ${hebrewMonthName(h.month, h.year)} ${h.year} AM",
            "Lunisolar, 19-year cycle with 7 leap months (Adar I); years counted from creation (Anno Mundi); days begin at sunset.")
        val ic = islamicCivil(d)
        add("islamic", "Islamic (civil)", "${ic.day} ${ISLAMIC_MONTHS[ic.month - 1]} ${ic.year} AH",
            "Purely lunar, arithmetic tabular rule (11 leap years in 30); may differ a day from sighting.")
        ummAlQura(d)?.let { u ->
            add("umm_al_qura", "Islamic (Umm al-Qura)", "${u.day} ${ISLAMIC_MONTHS[u.month - 1]} ${u.year} AH",
                "Saudi Arabia's official astronomical Hijri calendar.")
        }
        val p = persian(d)
        add("persian", "Persian (Solar Hijri)", "${p.day} ${PERSIAN_MONTHS[p.month - 1]} ${p.year} AP",
            "Iran and Afghanistan; the year starts at Nowruz, the March equinox; 6 months of 31 days, then 30.")
        add("chinese", "Chinese", chinese?.let { chinese(it) } ?: "—",
            "Lunisolar: months start at new moon, a leap month keeps the winter solstice in month 11; 60-year stem-branch cycle and 12 zodiac animals.")
        val s = saka(d)
        add("saka", "Indian national (Saka)", "${s.day} ${SAKA_MONTHS[s.month - 1]} ${s.year} Saka",
            "India's civil calendar since 1957; the year starts on 22 March (21 in leap years).")
        add("buddhist", "Buddhist", "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${buddhist(d)} BE",
            "Thai solar calendar: Gregorian months, years from the Buddha's parinirvana (543 BC).")
        japanese(d)?.let { (era, kanji, y) ->
            add("japanese", "Japanese era", "$era $y ($kanji${y}年), ${d.monthValue}/${d.dayOfMonth}",
                "Gregorian months, years counted from the reigning emperor's accession (gengō).")
        }
        val c = coptic(d)
        add("coptic", "Coptic", "${c.day} ${COPTIC_MONTHS[c.month - 1]} ${c.year} AM",
            "Egyptian church calendar: 12 months of 30 days plus 5 or 6 epagomenal days; Era of Martyrs (AD 284).")
        val e = ethiopian(d)
        add("ethiopian", "Ethiopian", "${e.day} ${ETHIOPIAN_MONTHS[e.month - 1]} ${e.year} EC",
            "Ethiopia's civil calendar: Coptic structure, about 7–8 years behind the Gregorian; New Year (Enkutatash) 11 September.")
        val lc = longCount(d); val tz = tzolkin(d); val hb = haab(d)
        add("maya", "Mayan", lc.joinToString(".") + "  ·  ${tz.first} ${tz.second}  ·  ${hb.first} ${hb.second}",
            "Long Count (baktun.katun.tun.winal.kin) from 11 August 3114 BC (GMT correlation 584283), Tzolk'in 260-day and Haab' 365-day rounds.")
        val tn = tonalpohualli(d); val xi = xiuhpohualli(d)
        add("aztec", "Aztec", "${tn.first} ${tn.second}  ·  ${xi.first} ${xi.second}",
            "Tōnalpōhualli (260 days) and Xiuhpōhualli (18 × 20 + 5 Nēmontēmi), Caso's correlation.")
        val tm = thirteenMoon(d); val k = kin(d)
        val moonText = when (tm.second) { -1 -> "0.0 Hunab Ku"; 0 -> "Day Out of Time"; else -> "${MOONS[tm.first - 1]} Moon ${tm.second}" }
        add("thirteen_moon", "13-Moon (Dreamspell)", "$moonText  ·  Kin $k ${kinLabel(k)}",
            "Law of Time: 13 moons × 28 days from 26 July, 25 July is the Day Out of Time; 29 February is not counted.")
        val f = fixed(d)
        add("fixed", "International Fixed", when (f.first) { 0 -> if (f.second == 1) "Leap Day ${d.year}" else "Year Day ${d.year}"; else -> "${f.second} ${IFC_MONTHS[f.first - 1]} ${d.year}" },
            "Cotsworth's 13 months of 28 days (Sol between June and July); Year Day and Leap Day belong to no week.")
        val fr = french(d)
        add("french", "French Republican", (if (fr.month == 13) "Sansculottide ${fr.day}" else "${fr.day} ${FRENCH_MONTHS[fr.month - 1]}") + " an ${romanNumeral(fr.year)}",
            "1793–1805: 12 months of 30 days in 3 décades plus 5–6 sansculottides, years from 22 September 1792 (as kept to year XIV, Romme's leap rule after).")
        add("discordian", "Discordian", discordian(d), "Erisian: 5 seasons of 73 days, 5-day week, Year of Our Lady of Discord = year + 1166.")
        add("holocene", "Holocene (Human Era)", "${d.dayOfMonth} ${d.month.getDisplayName(TextStyle.FULL, Locale.ENGLISH)} ${holocene(d)} HE",
            "Emiliani's era: the Gregorian year + 10000, from the start of the Holocene.")
        add("unix", "Unix time", (d.toEpochDay() * 86400).toString(), "Seconds since 1970-01-01 00:00 UTC (at 00:00 UTC of this date).")
        add("jdn", "Julian Day Number", jdn(d).toString(), "Days since noon 1 January 4713 BC (proleptic Julian); astronomy's day count, at noon UT.")
        val age = moonAge(d)
        add("moon", "Moon", "${moonPhase(age)}, age %.1f d, %d%% lit".format(Locale.ROOT, age, (illumination(age) * 100).roundToInt()),
            "Mean synodic month 29.53 days from the new moon of 6 January 2000; within about a day of the true phase.")
        return out
    }
}
