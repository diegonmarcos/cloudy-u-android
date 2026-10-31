package com.diegonmarcos.cloudcalc

import com.diegonmarcos.cloudcalc.tools.ToolCalcs
import java.time.format.TextStyle
import java.util.Locale

/**
 * #1329 Date & Time: the outputs a form declares with `calc` instead of an engine `expr`. The form's `bind` names which of its
 * fields play which role (from, to, end = the inclusive toggle, weekend, region, date, count), so no field id is spelled here.
 * A `calc` is `span` (metric days | working | weekend | holidays) or `add_working`. Pure: [Logic.Result] in, out.
 */
object DateForms {
    /** The output's label: a toggle-driven output names the convention in force (end included or excluded). */
    fun label(o: Declarations.Output, values: Map<String, String>, bind: Map<String, String>): String {
        if (o.labelOn.isEmpty() || o.labelOff.isEmpty()) return o.label
        return if (values[bind["end"].orEmpty()] == "true") o.labelOn else o.labelOff
    }

    fun result(book: WorkingDays.Book, o: Declarations.Output, bind: Map<String, String>, values: Map<String, String>): Logic.Result {
        fun v(role: String) = values[bind[role].orEmpty()].orEmpty()
        val cal = book.calendar(v("region").ifEmpty { WorkingDays.NONE }, WorkingDays.weekendOf(v("weekend")))
        return when (o.calc) {
            "span" -> {
                val from = WorkingDays.date(v("from")) ?: return bad("From: " + v("from"))
                val to = WorkingDays.date(v("to")) ?: return bad("To: " + v("to"))
                val span = cal.between(from, to, inclusive = v("end") == "true")
                ok(when (o.metric) { "working" -> span.working; "weekend" -> span.weekend; "holidays" -> span.holidays; else -> span.days }.toString())
            }
            "add_working" -> {
                val date = WorkingDays.date(v("date")) ?: return bad("Date: " + v("date"))
                val n = v("count").trim().toIntOrNull() ?: return bad("Days: " + v("count"))
                val r = cal.add(date, n) ?: return bad("Out of range")
                ok("$r (" + r.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()) + ")")
            }
            else -> bad("calc " + o.calc)
        }
    }

    /**
     * #1377 an output the :tools module counts (ToolCalcs.CALCS): null for any other calc. A field is read by its role in the
     * form's bind, else by its own id; money prints in the form's currency field (options_from `currencies`), if it has one.
     */
    fun toolResult(o: Declarations.Output, form: Declarations.Form, values: Map<String, String>, today: java.time.LocalDate = java.time.LocalDate.now()): Logic.Result? {
        val currency = form.fields.firstOrNull { it.optionsFrom == CURRENCIES }?.let { values[it.id] }.orEmpty()
        return try {
            ToolCalcs.eval(o.calc, o.metric, { role -> values[form.bind[role] ?: role].orEmpty() }, currency, today)?.let { ok(it) }
        } catch (e: IllegalArgumentException) {
            bad(e.message.orEmpty())
        }
    }

    /** A choice field's options_from that lists the device's ISO 4217 currencies. */
    const val CURRENCIES = "currencies"

    private fun ok(text: String) = Logic.Result(true, text, emptyList(), "")
    private fun bad(why: String) = Logic.Result(false, "", emptyList(), "Not a valid value - $why")
}
