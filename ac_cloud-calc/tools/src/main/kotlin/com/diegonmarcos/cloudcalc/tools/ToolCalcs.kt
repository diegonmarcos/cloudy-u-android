package com.diegonmarcos.cloudcalc.tools

import com.diegonmarcos.cloudcalc.sound.Air
import com.diegonmarcos.cloudcalc.sound.Notes
import java.time.LocalDate
import java.util.Locale
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * #1377 the Tools forms' counted outputs: a build.json output with `calc` (and `metric`) instead of an engine `expr`
 * is answered here. [get] reads a field by its role (the form's `bind`, else the field id); [currency] is the code the
 * user picked for money outputs. [eval] returns null for a calc it does not know (the date-range calcs stay in
 * the app's DateForms) and throws IllegalArgumentException with a short reason for a value it cannot use.
 */
object ToolCalcs {
    val CALCS = setOf("date", "loan", "compound", "pv", "npv", "savings", "cagr", "inflation", "note", "freq", "db", "spl", "bpm", "wave", "filesize")

    fun eval(calc: String, metric: String, get: (String) -> String, currency: String, today: LocalDate): String? {
        if (calc !in CALCS) return null
        fun n(role: String): Double = num(get(role)) ?: throw IllegalArgumentException("$role: '${get(role)}'")
        fun pct(role: String) = n(role) / 100
        fun money(x: Double) = money(x, currency)
        fun bad(): Nothing = throw IllegalArgumentException("metric $metric")
        return when (calc) {
            "date" -> {
                val d = get("date").trim().let { if (it.isEmpty()) today else runCatching { LocalDate.parse(it) }.getOrElse { throw IllegalArgumentException("date: '$it'") } }
                when (metric) {
                    "full" -> DateFacts.full(d); "doy" -> DateFacts.dayOfYear(d); "week" -> DateFacts.isoWeek(d)
                    "quarter" -> DateFacts.quarter(d); "left" -> DateFacts.daysLeft(d).toString(); "leap" -> DateFacts.leap(d)
                    else -> bad()
                }
            }
            "loan" -> {
                val p = n("principal"); val i = pct("rate") / 12; val months = (n("years") * 12).roundToInt()
                require(months > 0) { "years must be positive" }
                val pmt = Finance.payment(p, i, months)
                when (metric) {
                    "payment" -> money(pmt)
                    "total" -> money(pmt * months)
                    "interest" -> money(pmt * months - p)
                    "schedule" -> Finance.yearly(Finance.schedule(p, i, months), 12).joinToString("\n") {
                        "Y%-3d %s int  %s bal".format(it.period, money(it.interest), money(it.balance))
                    }
                    else -> bad()
                }
            }
            "compound" -> {
                val per = n("n"); require(per > 0) { "compounds per year must be positive" }
                val periods = per * n("y"); val dep = num(get("deposit")) ?: 0.0
                val fv = Finance.futureValue(n("p"), pct("r") / per, periods, dep)
                when (metric) { "fv" -> money(fv); "interest" -> money(fv - n("p") - dep * periods); else -> bad() }
            }
            "pv" -> money(Finance.presentValue(n("fv"), pct("rate"), n("years")))
            "npv" -> {
                val flows = get("flows").split(';', ' ', '\n').filter { it.isNotBlank() }.map { num(it) ?: throw IllegalArgumentException("cash flow '$it'") }
                require(flows.isNotEmpty()) { "no cash flows" }
                when (metric) {
                    "npv" -> money(Finance.npv(pct("rate"), flows))
                    "irr" -> Finance.irr(flows)?.let { percent(it) } ?: "no IRR (cash flows need a sign change)"
                    else -> bad()
                }
            }
            "savings" -> {
                val months = (n("years") * 12).roundToInt(); require(months > 0) { "years must be positive" }
                val dep = Finance.savingsDeposit(n("goal"), n("current"), pct("rate") / 12, months)
                when (metric) {
                    "monthly" -> money(dep)
                    "deposited" -> money(dep * months)
                    "interest" -> money(n("goal").coerceAtLeast(Finance.futureValue(n("current"), pct("rate") / 12, months.toDouble())) - n("current") - dep * months)
                    else -> bad()
                }
            }
            "cagr" -> when (metric) {
                "rate" -> percent(Finance.cagr(n("start"), n("end"), n("years")))
                "multiple" -> fmt(n("end") / n("start")) + "×"
                else -> bad()
            }
            "inflation" -> when (metric) {
                "future" -> money(Finance.inflate(n("amount"), pct("rate"), n("years")))
                "real" -> money(Finance.deflate(n("amount"), pct("rate"), n("years")))
                else -> bad()
            }
            "note" -> {
                val note = Notes.of(n("f"), n("a4")) ?: throw IllegalArgumentException("frequency must be positive")
                when (metric) {
                    "note" -> "${note.label} %+.1f¢".format(Locale.ROOT, note.cents)
                    "midi" -> note.midi.toString()
                    "nearest" -> fmt(note.hz) + " Hz"
                    else -> bad()
                }
            }
            "freq" -> fmt(noteHz(get("note"), n("a4")) * 2.0.pow((num(get("cents")) ?: 0.0) / 1200)) + " Hz"
            "db" -> {
                val a = n("l1"); val b = n("l2")
                when (metric) {
                    "sum" -> fmt(dbAdd(a, b)) + " dB"
                    "diff" -> dbSubtract(a, b)?.let { fmt(it) + " dB" } ?: "needs L1 > L2"
                    else -> bad()
                }
            }
            "spl" -> when (metric) {
                "spl" -> fmt(splOf(n("pa"))) + " dB SPL"
                "pa" -> fmt(paOf(n("level"))) + " Pa"
                else -> bad()
            }
            "bpm" -> if (metric == "from_ms") fmt(beatMs(n("ms"))) + " BPM" else {
                val beat = beatMs(n("bpm"))
                when (metric) {
                    "whole" -> ms(beat * 4); "half" -> ms(beat * 2); "quarter" -> ms(beat); "eighth" -> ms(beat / 2)
                    "sixteenth" -> ms(beat / 4); "dotted" -> ms(beat / 2 * 1.5); "triplet" -> ms(beat / 3); "hz" -> fmt(1000 / beat) + " Hz"
                    else -> bad()
                }
            }
            "wave" -> {
                val c = Air.speedOfSound(n("t"))
                when (metric) {
                    "speed" -> fmt(c) + " m/s"
                    "wavelength" -> fmt(Air.wavelength(n("f"), c) ?: throw IllegalArgumentException("frequency must be positive")) + " m"
                    "frequency" -> n("lambda").let { require(it > 0) { "wavelength must be positive" }; fmt(c / it) + " Hz" }
                    else -> bad()
                }
            }
            "filesize" -> {
                val bps = n("rate") * n("bits") * n("channels")
                when (metric) {
                    "size" -> bytes(bps / 8 * n("seconds"))
                    "bitrate" -> fmt(bps / 1000) + " kbit/s"
                    "minute" -> bytes(bps / 8 * 60)
                    else -> bad()
                }
            }
            else -> null
        }
    }

    // ── sound formulas ────────────────────────────────────────────────────────────────────────
    private val NOTE = Regex("""^\s*([A-Ga-g])([#♯b♭]?)(-?\d+)\s*$""")
    private val SEMIS = mapOf('C' to 0, 'D' to 2, 'E' to 4, 'F' to 5, 'G' to 7, 'A' to 9, 'B' to 11)
    /** "A4", "C#5", "Bb3" at tuning [a4]. */
    fun noteHz(name: String, a4: Double): Double {
        val m = NOTE.matchEntire(name) ?: throw IllegalArgumentException("note '$name' (e.g. A4, C#5, Bb3)")
        val (l, acc, oct) = m.destructured
        val semi = SEMIS.getValue(l.uppercase()[0]) + when (acc) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
        val midi = (oct.toInt() + 1) * 12 + semi
        return a4 * 2.0.pow((midi - 69) / 12.0)
    }
    fun dbAdd(a: Double, b: Double): Double = 10 * log10(10.0.pow(a / 10) + 10.0.pow(b / 10))
    /** The level left when [b] is taken out of a total [a] (a source over background); null unless a > b. */
    fun dbSubtract(a: Double, b: Double): Double? = if (a > b) 10 * log10(10.0.pow(a / 10) - 10.0.pow(b / 10)) else null
    const val P_REF = 20e-6
    fun splOf(pa: Double): Double { require(pa > 0) { "pressure must be positive" }; return 20 * log10(pa / P_REF) }
    fun paOf(db: Double): Double = P_REF * 10.0.pow(db / 20)
    /** ms per beat at [bpm]; the same formula turns a beat in ms back into BPM. */
    fun beatMs(bpm: Double): Double { require(bpm > 0) { "BPM and delay must be positive" }; return 60000 / bpm }

    // ── numbers in and out ────────────────────────────────────────────────────────────────────
    /** "1 234,5", "1,234.5", "5,5", "12_000" → numbers; a lone comma is a decimal comma. */
    fun num(s: String): Double? {
        var t = s.trim().replace(" ", "").replace("_", "").replace(" ", "")
        if (t.isEmpty()) return null
        t = if (',' in t && '.' !in t && t.count { it == ',' } == 1) t.replace(',', '.') else t.replace(",", "")
        return t.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
    fun fmt(x: Double): String = when {
        x == 0.0 -> "0"
        abs(x) >= 1e9 || abs(x) < 1e-3 -> "%.4g".format(Locale.ROOT, x)
        else -> "%.4f".format(Locale.ROOT, x).trimEnd('0').trimEnd('.')
    }
    fun money(x: Double, currency: String): String = "%,.2f".format(Locale.ROOT, x) + if (currency.isBlank()) "" else " $currency"
    fun percent(x: Double): String = "%.2f %%".format(Locale.ROOT, x * 100)
    private fun ms(x: Double) = fmt(x) + " ms"
    fun bytes(b: Double): String = when {
        b >= 1e9 -> "%.2f GB".format(Locale.ROOT, b / 1e9)
        b >= 1e6 -> "%.2f MB".format(Locale.ROOT, b / 1e6)
        b >= 1e3 -> "%.1f kB".format(Locale.ROOT, b / 1e3)
        else -> fmt(b) + " B"
    } + " (" + "%,d".format(Locale.ROOT, b.toLong()) + " bytes)"
}
