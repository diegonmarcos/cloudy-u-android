package com.diegonmarcos.capitalfinterm

import java.util.Locale
import kotlin.math.abs
import kotlin.math.sqrt

/** #1539 the GDP-weighted aggregation of the CB page: the weighted mean of the countries that have a value, weights re-normalised over those. */
object CbAggregate {
    const val WORLD = "WORLD"

    /** [value] null = no country had a number; [countries] how many did; [coverage] their share (0..1) of the weight of every country that has a weight. */
    data class Agg(val value: Double?, val countries: Int, val coverage: Double)

    /** [pairs] are (value, weight) per country; a null value or a missing / non-positive weight drops the country out of both sums. */
    fun weightedMean(pairs: List<Pair<Double?, Double?>>): Agg {
        val weighted = pairs.filter { (_, w) -> w != null && w > 0.0 }
        val used = weighted.filter { (v, _) -> v != null }
        val all = weighted.sumOf { it.second!! }
        val wsum = used.sumOf { it.second!! }
        if (used.isEmpty() || wsum <= 0.0) return Agg(null, 0, 0.0)
        return Agg(used.sumOf { it.first!! * it.second!! } / wsum, used.size, if (all > 0.0) wsum / all else 0.0)
    }

    /** The members of an aggregate: the countries of [group] (DM / EM) with aggregate=true, or both groups for [WORLD]. */
    fun members(decl: CbDecl, group: String): List<CbCountry> = decl.countries.filter { it.aggregate && (group == WORLD || it.group == group) }

    fun of(decl: CbDecl, snap: CbSnapshot, group: String, indicator: String, year: Int, weight: String): Agg =
        weightedMean(members(decl, group).map { c -> snap.of(c.id, indicator)?.at(year) to snap.weight(weight, c.id, year) })

    /** The share (0..1) of [group] in the World GDP of [year] under [weight]; null without weights. */
    fun share(decl: CbDecl, snap: CbSnapshot, group: String, year: Int, weight: String): Double? {
        val tot = members(decl, WORLD).sumOf { snap.weight(weight, it.id, year) ?: 0.0 }
        val mine = members(decl, group).sumOf { snap.weight(weight, it.id, year) ?: 0.0 }
        return if (tot > 0.0) mine / tot else null
    }
}

/** #1539 the heat of a cell: how far a value sits from the country's own history (z-score, 2 sigma = full tint), null without five actuals. */
object CbHeat {
    fun of(history: Collection<Double>, v: Double): Float? {
        if (history.size < 5) return null
        val mean = history.average()
        val sd = sqrt(history.sumOf { (it - mean) * (it - mean) } / history.size)
        if (sd <= 1e-9) return null
        return ((v - mean) / sd / 2.0).coerceIn(-1.0, 1.0).toFloat()
    }
}

/** #1539 the central-bank model: the Taylor-rule implied policy rate from declared inputs (data/cb-countries.json model.taylor). */
object CbModel {
    /** neutral + inflation + wInf x (inflation - target) + wGap x gap; null when any input is missing (never a guessed one). */
    fun taylor(m: CbModelDecl, neutral: Double?, inflation: Double?, target: Double?, gap: Double?): Double? {
        if (neutral == null || inflation == null || target == null || gap == null) return null
        return neutral + inflation + m.inflationWeight * (inflation - target) + m.gapWeight * gap
    }

    /** +1 tightening, -1 easing, 0 hold: the implied rate against the latest policy rate, within the declared band. */
    fun bias(m: CbModelDecl, implied: Double?, policy: Double?): Int? {
        if (implied == null || policy == null) return null
        val d = implied - policy
        return if (d > m.holdBandPp) 1 else if (d < -m.holdBandPp) -1 else 0
    }

    fun implied(decl: CbDecl, snap: CbSnapshot, country: CbCountry, year: Int): Double? {
        val m = decl.model
        return taylor(m, snap.of(country.id, m.neutral)?.at(year), snap.of(country.id, m.inflation)?.at(year), country.target, snap.of(country.id, m.gap)?.at(year))
    }
}

/** A table of the CB page as strings for FinMatrix: [header] (label column first), [rows], [heat] per cell (null = none) and [ids] the row ids (a country id, an aggregate or an indicator id); the first [pinned] rows are the aggregates. */
data class CbTable(val header: List<String>, val rows: List<List<String>>, val heat: List<List<Float?>>, val ids: List<String>, val pinned: Int)

object CbTables {
    const val SIMPLE = "simple"
    const val FULL = "full"

    /** The columns of a group: the simple items (their first ref is the column) or the full indicators. */
    data class Col(val id: String, val name: String, val unit: String, val modelOnly: Boolean)

    fun columns(decl: CbDecl, full: Boolean, groupId: String): List<Col> =
        if (full) (decl.full.firstOrNull { it.id == groupId } ?: decl.full.first()).items.map { Col(it.id, it.name, it.unit, it.modelOnly) }
        else (decl.simple.firstOrNull { it.id == groupId } ?: decl.simple.first()).items.map { s ->
            val ind = decl.indicators[s.refs.first()]
            Col(s.refs.first(), s.name, ind?.unit.orEmpty(), ind?.modelOnly ?: false)
        }

    fun groups(decl: CbDecl, full: Boolean): List<Pair<String, String>> = if (full) decl.full.map { it.id to it.label } else decl.simple.map { it.id to it.label }

    /** The years the selector offers: the last actual year, the current year, +1, +2. */
    fun years(snap: CbSnapshot, decl: CbDecl): List<Int> = (snap.currentYear - 1..snap.currentYear + decl.forecastYears - 1).toList()

    /** One number as a dense cell: no decimals from 1000, one from 100, else two. */
    fun fmt(v: Double): String = when {
        abs(v) >= 1000.0 -> String.format(Locale.ROOT, "%.0f", v)
        abs(v) >= 100.0 -> String.format(Locale.ROOT, "%.1f", v)
        else -> String.format(Locale.ROOT, "%.2f", v)
    }

    fun missing(decl: CbDecl, modelOnly: Boolean): String = if (modelOnly) decl.word("model_only") else decl.word("na")

    private fun cell(decl: CbDecl, snap: CbSnapshot, c: Col, country: String, year: Int): Pair<String, Float?> {
        val s = snap.of(country, c.id)
        val v = s?.at(year)
        if (s == null || v == null) return missing(decl, c.modelOnly) to null
        return fmt(v) to CbHeat.of(s.hist.values, v)
    }

    private fun aggCell(decl: CbDecl, snap: CbSnapshot, c: Col, group: String, year: Int, weight: String): Pair<String, Float?> {
        val a = CbAggregate.of(decl, snap, group, c.id, year, weight)
        val v = a.value ?: return missing(decl, c.modelOnly) to null
        val hist = (snap.historyFrom until snap.currentYear).mapNotNull { CbAggregate.of(decl, snap, group, c.id, it, weight).value }
        return fmt(v) to CbHeat.of(hist, v)
    }

    /** A column name broken at spaces into lines of about [width] characters, three lines at most (the matrix header is as tall as its tallest cell). */
    fun wrap(name: String, width: Int = 11): String {
        val lines = ArrayList<String>()
        for (w in name.split(' ')) if (lines.isNotEmpty() && lines.last().length + 1 + w.length <= width) lines[lines.size - 1] = lines.last() + " " + w else lines += w
        return if (lines.size <= 3) lines.joinToString("\n") else lines.take(2).joinToString("\n") + "\n" + lines.drop(2).joinToString(" ")
    }

    private fun aggName(decl: CbDecl, group: String): String = when (group) { CbAggregate.WORLD -> decl.word("world"); "DM" -> decl.word("dm"); else -> decl.word("em") }

    /** Countries view: the DM / EM / World aggregates pinned on top, then every country (countries as rows, the group's indicators as columns). */
    fun countries(decl: CbDecl, snap: CbSnapshot, full: Boolean, groupId: String, year: Int, weight: String): CbTable {
        val cols = columns(decl, full, groupId)
        val pins = listOf(CbAggregate.WORLD, "DM", "EM")
        val rows = ArrayList<List<String>>(); val heat = ArrayList<List<Float?>>(); val ids = ArrayList<String>()
        for (g in pins) {
            val cells = cols.map { aggCell(decl, snap, it, g, year, weight) }
            rows += listOf(aggName(decl, g)) + cells.map { it.first }; heat += listOf<Float?>(null) + cells.map { it.second }; ids += g
        }
        for (c in decl.countries) {
            val cells = cols.map { cell(decl, snap, it, c.id, year) }
            rows += listOf(c.name) + cells.map { it.first }; heat += listOf<Float?>(null) + cells.map { it.second }; ids += c.id
        }
        return CbTable(listOf("") + cols.map { wrap(it.name) }, rows, heat, ids, pins.size)
    }

    /** Groups view: only the World / DM / EM aggregates, with their GDP share under [weight]. */
    fun groups(decl: CbDecl, snap: CbSnapshot, full: Boolean, groupId: String, year: Int, weight: String, shareLabel: String): CbTable {
        val cols = columns(decl, full, groupId)
        val rows = ArrayList<List<String>>(); val heat = ArrayList<List<Float?>>(); val ids = ArrayList<String>()
        for (g in listOf(CbAggregate.WORLD, "DM", "EM")) {
            val cells = cols.map { aggCell(decl, snap, it, g, year, weight) }
            val share = CbAggregate.share(decl, snap, g, year, weight)?.let { String.format(Locale.ROOT, "%.0f%%", it * 100) } ?: decl.word("na")
            rows += listOf(aggName(decl, g), share) + cells.map { it.first }; heat += listOf<Float?>(null, null) + cells.map { it.second }; ids += g
        }
        return CbTable(listOf("", shareLabel) + cols.map { wrap(it.name) }, rows, heat, ids, rows.size)
    }

    /** Predictions view: per country the declared forecast indicators for the current year, +1, +2, the latest policy rate and the Taylor path with its bias. */
    fun predictions(decl: CbDecl, snap: CbSnapshot): CbTable {
        val y = snap.currentYear
        val inds = decl.model.predictions.mapNotNull { decl.indicators[it] }
        val header = listOf("") + inds.flatMap { i -> (0 until decl.forecastYears).map { "${i.name}\n${y + it}" } } + listOf(decl.indicators[decl.model.policy]?.name.orEmpty()) + (0 until decl.forecastYears).map { "${decl.word("taylor")}\n${y + it}" } + listOf(decl.word("bias"))
        val rows = ArrayList<List<String>>(); val ids = ArrayList<String>()
        for (c in decl.countries) {
            val cells = ArrayList<String>()
            for (i in inds) for (k in 0 until decl.forecastYears) cells += snap.of(c.id, i.id)?.at(y + k)?.let { fmt(it) } ?: decl.word("na")
            val pol = snap.of(c.id, decl.model.policy)?.latest()?.second
            cells += pol?.let { fmt(it) } ?: decl.word("na")
            val path = (0 until decl.forecastYears).map { CbModel.implied(decl, snap, c, y + it) }
            cells += path.map { it?.let { v -> fmt(v) } ?: decl.word("na") }
            cells += when (CbModel.bias(decl.model, path.lastOrNull(), pol)) { 1 -> "+"; -1 -> "-"; 0 -> "="; else -> decl.word("na") }
            rows += listOf(c.name) + cells; ids += c.id
        }
        return CbTable(header, rows, rows.map { emptyList() }, ids, 0)
    }

    /** The table turned over: indicators become rows and the labels of the rows become columns (heat follows). */
    fun transpose(t: CbTable): CbTable {
        val n = t.header.size
        val header = listOf("") + t.rows.map { it.first() }
        val rows = (1 until n).map { j -> listOf(t.header[j].replace('\n', ' ')) + t.rows.map { it.getOrElse(j) { "" } } }
        val heat = (1 until n).map { j -> listOf<Float?>(null) + t.heat.map { it.getOrNull(j) } }
        return CbTable(header, rows, heat, (1 until n).map { t.header[it] }, 0)
    }
}
