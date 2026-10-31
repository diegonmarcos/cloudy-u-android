package com.diegonmarcos.capitalfinterm

import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/**
 * The pure rules behind the FinTerm components (UI spec 2.x): typed table columns, sort, importance, chart ranges and axis
 * ticks, heat fill, the derived spread tile, flags and day headers. No Compose here, so every rule is a JVM test.
 */
object FinLogic {
    /** Column types of a table (spec 2.3): declared per panel (`col_types`), else read from the header and the cells. */
    /** HEAT (declared only, col_types "heat"): a number whose cell is tinted by its size against the column's largest (World Bonds heatmap, #1370). */
    enum class Col { TEXT, NUM, CHG, PCT, DATE, LINK, HEAT }

    fun missing(s: String?): Boolean = s == null || s.isBlank() || s == "-" || s == FinFormat.DASH || s == "n/a"

    private val DATE = Regex("""^\d{4}-\d{2}(-\d{2})?""")

    fun colTypes(declared: List<String>, columns: List<String>, rows: List<List<String>>): List<Col> {
        val n = maxOf(columns.size, rows.maxOfOrNull { it.size } ?: 0)
        return (0 until n).map { i ->
            declared.getOrNull(i)?.let { d -> Col.entries.firstOrNull { it.name.equals(d, ignoreCase = true) } } ?: run {
                val head = columns.getOrElse(i) { "" }.lowercase(Locale.ROOT)
                val cells = rows.map { it.getOrElse(i) { "" } }.filterNot { missing(it) }
                when {
                    i == 0 -> Col.TEXT
                    cells.isNotEmpty() && cells.all { DATE.containsMatchIn(it) } -> Col.DATE
                    Regex("""chg|change|\bbp\b|1d|1w|1m|diff|move""").containsMatchIn(head) && cells.all { FinFormat.parseNum(it) != null } -> Col.CHG
                    Regex("""%\s*chg|chg\s*%|pct""").containsMatchIn(head) -> Col.PCT
                    cells.isNotEmpty() && cells.all { FinFormat.parseNum(it) != null && it.trim().length < 18 } -> Col.NUM
                    else -> Col.TEXT
                }
            }
        }
    }

    /** A table's column widths (dp) and whether they fit without scrolling. */
    data class Layout(val widths: List<Float>, val fits: Boolean)

    /** Per-character advance (dp) of the body sans (name column, 12sp) and of the tabular mono numbers (13sp), and the cell padding. */
    const val CHAR_TEXT = 6.6f
    const val CHAR_NUM = 7.9f
    const val CELL_PAD = 16f

    /**
     * Content-sized columns (the wide-table fix): every number column is as wide as its longest cell or header (never clipped, no
     * fixed 64dp), the pinned name column fits its longest one-line name within 96dp .. 45 % of [width]. When the sum fits, the
     * spare width goes to the number columns; otherwise the number columns scroll sideways under the pinned name.
     */
    fun layout(columns: List<String>, rows: List<List<String>>, width: Float): Layout {
        val n = maxOf(columns.size, rows.maxOfOrNull { it.size } ?: 0)
        if (n == 0) return Layout(emptyList(), true)
        fun chars(i: Int) = maxOf(columns.getOrElse(i) { "" }.length, rows.maxOfOrNull { cell(it.getOrNull(i)).length } ?: 0)
        val first = (chars(0) * CHAR_TEXT + CELL_PAD).coerceIn(96f, maxOf(96f, width * 0.45f))
        val rest = (1 until n).map { i -> maxOf(44f, chars(i) * CHAR_NUM + CELL_PAD) }
        val total = first + rest.sum()
        if (total > width || rest.isEmpty()) return Layout(listOf(first) + rest, total <= width)
        val spare = (width - total) / rest.size
        return Layout(listOf(first) + rest.map { it + spare }, true)
    }

    /**
     * The row order of a table sorted on [col] at [step] (spec 2.3): 0 = the source order; 1 = numbers descending / text
     * ascending; 2 = the reverse. Missing values always sort last.
     */
    fun sortOrder(rows: List<List<String>>, col: Int, type: Col, step: Int): List<Int> {
        val idx = rows.indices.toList()
        if (step % 3 == 0 || col < 0) return idx
        val numeric = type != Col.TEXT && type != Col.LINK && type != Col.DATE
        val present = idx.filterNot { missing(rows[it].getOrNull(col)) }
        val absent = idx.filter { missing(rows[it].getOrNull(col)) }
        val first = if (numeric) present.sortedByDescending { FinFormat.parseNum(rows[it][col]) ?: Double.NEGATIVE_INFINITY }
            else present.sortedBy { rows[it][col].lowercase(Locale.ROOT) }
        return (if (step % 3 == 1) first else first.reversed()) + absent
    }

    /** Importance 0..3 from a cell: filled dots (●) or a number. */
    fun importance(cell: String?): Int {
        if (cell == null) return 0
        val dots = cell.count { it == '●' }
        if (dots > 0) return dots.coerceAtMost(3)
        return (cell.trim().toIntOrNull() ?: 0).coerceIn(0, 3)
    }

    /** Points of a dated series inside [range] (1D 5D 1M 6M 1Y 5Y MAX), counted back from the last point's date. */
    fun range(points: List<ChartPoint>, range: String): List<ChartPoint> {
        if (points.size < 3) return points
        val days = when (range) { "1D" -> 1L; "5D" -> 5L; "1M" -> 31L; "6M" -> 183L; "1Y" -> 366L; "5Y" -> 1827L; else -> return points }
        val last = runCatching { LocalDate.parse(points.last().label.take(10)) }.getOrNull() ?: return points.takeLast(rangeCount(range))
        val from = last.minusDays(days)
        val out = points.filter { p -> runCatching { !LocalDate.parse(p.label.take(10)).isBefore(from) }.getOrDefault(true) }
        return if (out.size >= 2) out else points.takeLast(2)
    }

    private fun rangeCount(range: String): Int = when (range) { "1D" -> 2; "5D" -> 5; "1M" -> 22; "6M" -> 126; "1Y" -> 252; else -> 1260 }

    /** Ranges offered by a chart card: none for a curve (its x axis is tenors), else those the series is long enough for. */
    fun ranges(points: List<ChartPoint>, curve: Boolean): List<String> {
        if (curve || points.size < 3) return emptyList()
        val all = listOf("1M", "6M", "1Y", "5Y")
        val first = runCatching { LocalDate.parse(points.first().label.take(10)) }.getOrNull()
        val last = runCatching { LocalDate.parse(points.last().label.take(10)) }.getOrNull()
        if (first == null || last == null) return emptyList()
        val span = java.time.temporal.ChronoUnit.DAYS.between(first, last)
        val need = mapOf("1M" to 0L, "6M" to 40L, "1Y" to 190L, "5Y" to 370L)
        return all.filter { span >= need.getValue(it) }
    }

    /** "Nice" axis ticks (1, 2, 2.5, 5 x 10^n) covering [lo]..[hi], about [n] of them. */
    fun ticks(lo: Double, hi: Double, n: Int = 4): List<Double> {
        if (!lo.isFinite() || !hi.isFinite()) return emptyList()
        val span = if (hi - lo > 1e-12) hi - lo else (abs(hi).takeIf { it > 0 } ?: 1.0) * 0.02
        val raw = span / n.coerceAtLeast(1)
        val mag = 10.0.pow(floor(log10(raw)))
        val step = listOf(1.0, 2.0, 2.5, 5.0, 10.0).map { it * mag }.first { it >= raw }
        val start = floor(lo / step) * step
        val end = ceil(hi / step) * step
        val out = ArrayList<Double>()
        var v = start
        while (v <= end + step / 2 && out.size < 12) { out += (Math.round(v / step) * step); v += step }
        return out
    }

    /** Heat fill of a tile from its change %: -1 (full down) .. +1 (full up), clamped at +-[scale] %. */
    fun heat(chgPct: Double?, scale: Double = 3.0): Float {
        if (chgPct == null || !chgPct.isFinite() || scale <= 0) return 0f
        return (chgPct / scale).coerceIn(-1.0, 1.0).toFloat()
    }

    /** Muted heat scale: even a clamped tile blends only this far from surfaceRaised toward up/down, so a quiet FX day never paints solid red. */
    const val HEAT_MAX_BLEND: Float = 0.55f


    /** The derived spread tile (`spread: {label, of: [a, b]}`): a - b of two rows' values, in bp when [bp]. */
    fun spread(rows: List<List<String>>, a: String, b: String, bp: Boolean = true): Double? {
        fun v(l: String) = rows.firstOrNull { it.getOrNull(0).equals(l, ignoreCase = true) }?.getOrNull(1)?.let { FinFormat.parseNum(it) }
        val x = v(a) ?: return null
        val y = v(b) ?: return null
        return if (bp) Math.round((x - y) * 100.0).toDouble() else x - y
    }

    /** The flag emoji of an ISO-2 country code (regional indicators), or null when it is not two letters. */
    fun flag(iso2: String?): String? {
        val c = iso2?.trim()?.uppercase(Locale.ROOT) ?: return null
        if (c.length != 2 || !c.all { it in 'A'..'Z' }) return null
        val code = if (c == "UK") "GB" else c
        return code.map { String(Character.toChars(0x1F1E6 + (it - 'A'))) }.joinToString("")
    }

    /** Day header of a calendar group: `Thu 05 Nov`. */
    /** Central-bank next-decision tiles (#1370): the first decision on or after today per country, in calendar order, with days to go. */
    data class NextDecision(val country: String, val date: LocalDate, val days: Long)
    fun nextDecisions(events: List<Pair<String, String>>, today: LocalDate): List<NextDecision> {
        val seen = LinkedHashMap<String, NextDecision>()
        for ((ts, cc) in events) {
            val d = runCatching { LocalDate.parse(ts.take(10)) }.getOrNull() ?: continue
            if (d.isBefore(today) || cc.isBlank() || cc in seen) continue
            seen[cc] = NextDecision(cc, d, java.time.temporal.ChronoUnit.DAYS.between(today, d))
        }
        return seen.values.sortedBy { it.date }
    }

    /** Calendar window chips (#1370): All, Today, This week (Monday to Sunday of today, UTC dates). */
    val CAL_CHIPS = listOf("All", "Today", "This week")
    fun inCalWindow(ts: String, chip: Int, today: LocalDate): Boolean {
        if (chip == 0) return true
        val d = runCatching { LocalDate.parse(ts.take(10)) }.getOrNull() ?: return false
        if (chip == 1) return d == today
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return !d.isBefore(monday) && !d.isAfter(monday.plusDays(6))
    }

    fun dayHeader(isoDate: String): String = runCatching {
        val d = LocalDate.parse(isoDate.take(10))
        d.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.US) + " " + String.format(Locale.US, "%02d", d.dayOfMonth) + " " +
            d.month.getDisplayName(TextStyle.SHORT, Locale.US)
    }.getOrDefault(isoDate)

    /** The cells of a calendar row by their column names: actual, forecast (consensus), previous. */
    data class Afp(val actual: String?, val forecast: String?, val previous: String?)

    fun afp(columns: List<String>, row: List<String>): Afp {
        fun at(re: Regex): String? = columns.indexOfFirst { re.containsMatchIn(it.lowercase(Locale.ROOT)) }.takeIf { it >= 0 }
            ?.let { row.getOrNull(it) }?.takeUnless { missing(it) }
        return Afp(at(Regex("actual|eps act|reported")), at(Regex("forecast|cons|estimate|eps est")), at(Regex("prev|prior|eps last")))
    }

    /** A cell for display: missing values are the em dash (spec 1.2), never a bare "-". */
    fun cell(s: String?): String = if (missing(s)) FinFormat.DASH else s!!.trim()

    /** A change cell: signed when the source gave a bare positive number, the em dash when missing. */
    fun change(s: String?): String {
        if (missing(s)) return FinFormat.DASH
        val t = s!!.trim()
        val n = FinFormat.parseNum(t) ?: return t
        return if (n > 0 && !t.startsWith("+")) "+$t" else t
    }

    /** "Show all N" collapses a card table after [limit] rows (spec 2.3). */
    fun visibleRows(total: Int, expanded: Boolean, limit: Int = 8): Int = if (expanded || total <= limit) total else limit
}
