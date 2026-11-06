package org.fossify.phone.sim

import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** One event counted by the usage tracker: a call (seconds, in/out), an SMS (in/out) or a data total. */
data class UsageEvent(val atMillis: Long, val kind: Kind, val outgoing: Boolean, val seconds: Long = 0) {
    enum class Kind { CALL, SMS }
}

data class UsageTotals(
    val callsIn: Int = 0, val callsOut: Int = 0,
    val minutesIn: Long = 0, val minutesOut: Long = 0,
    val smsIn: Int = 0, val smsOut: Int = 0,
    val dataBytes: Long? = null,
)

data class UsagePeriod(val label: String, val startMillis: Long, val endMillis: Long)

object SimUsage {
    /** Whole minutes, rounded up per call (how operators bill). */
    fun minutes(seconds: Long): Long = if (seconds <= 0) 0 else (seconds + 59) / 60

    fun totals(events: List<UsageEvent>, p: UsagePeriod, dataBytes: Long? = null): UsageTotals {
        val e = events.filter { it.atMillis >= p.startMillis && it.atMillis < p.endMillis }
        val calls = e.filter { it.kind == UsageEvent.Kind.CALL }
        val sms = e.filter { it.kind == UsageEvent.Kind.SMS }
        return UsageTotals(
            callsIn = calls.count { !it.outgoing }, callsOut = calls.count { it.outgoing },
            minutesIn = calls.filter { !it.outgoing }.sumOf { minutes(it.seconds) },
            minutesOut = calls.filter { it.outgoing }.sumOf { minutes(it.seconds) },
            smsIn = sms.count { !it.outgoing }, smsOut = sms.count { it.outgoing },
            dataBytes = dataBytes,
        )
    }

    /** Renewal day (1..31) from the manual renewal date: "15", "2026-03-15" or "15/03". Null when not parseable. */
    fun renewalDay(renewal: String): Int? {
        val t = renewal.trim()
        val d = Regex("""^\d{4}-\d{1,2}-(\d{1,2})$""").find(t)?.groupValues?.get(1)
            ?: Regex("""^(\d{1,2})(?:[/.-]\d{1,2}(?:[/.-]\d{2,4})?)?$""").find(t)?.groupValues?.get(1)
        return d?.toIntOrNull()?.takeIf { it in 1..31 }
    }

    /** The current plan cycle start: the last renewal day on or before [today] (clamped to month length); 1st when unknown. */
    fun cycleStart(today: LocalDate, renewal: String): LocalDate {
        val day = renewalDay(renewal) ?: 1
        fun on(ym: YearMonth) = ym.atDay(minOf(day, ym.lengthOfMonth()))
        val here = on(YearMonth.from(today))
        return if (!here.isAfter(today)) here else on(YearMonth.from(today).minusMonths(1))
    }

    /** Today, this cycle, then the last [months] calendar months (current first). */
    fun periods(today: LocalDate, renewal: String, months: Int = 6, zone: ZoneId = ZoneId.systemDefault()): List<UsagePeriod> {
        fun ms(d: LocalDate) = d.atStartOfDay(zone).toInstant().toEpochMilli()
        val out = mutableListOf(
            UsagePeriod("Today", ms(today), ms(today.plusDays(1))),
            UsagePeriod("This cycle (since ${cycleStart(today, renewal)})", ms(cycleStart(today, renewal)), ms(today.plusDays(1))),
        )
        for (i in 0 until months) {
            val ym = YearMonth.from(today).minusMonths(i.toLong())
            out += UsagePeriod(ym.toString(), ms(ym.atDay(1)), ms(ym.plusMonths(1).atDay(1)))
        }
        return out
    }

    /** Allowance in bytes from "20 GB", "500MB", "1.5 TB", a bare "20" (the Data (GB) field: GB), "unlimited" (null). */
    fun allowanceBytes(text: String): Long? {
        val m = Regex("""^\s*([\d.,]+)\s*([KMGT]?B)?\s*$""", RegexOption.IGNORE_CASE).find(text) ?: return null
        val n = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        val mul = when (m.groupValues[2].uppercase().ifEmpty { "GB" }) { "KB" -> 1L shl 10; "MB" -> 1L shl 20; "GB" -> 1L shl 30; "TB" -> 1L shl 40; else -> 1L }
        return (n * mul).toLong()
    }

    /** "3.2 GB of 20.0 GB (16%)" or just the used amount when no allowance was entered. */
    fun againstAllowance(used: Long?, allowance: String): String {
        val cap = allowanceBytes(allowance)
        if (used == null) return "-"
        if (cap == null || cap <= 0) return StatusFormat.bytes(used)
        return "${StatusFormat.bytes(used)} of ${StatusFormat.bytes(cap)} (${used * 100 / cap}%)"
    }

    fun rows(t: UsageTotals, allowance: String): List<SimRow> = listOf(
        SimRow("Calls in", "${t.callsIn} · ${t.minutesIn} min"),
        SimRow("Calls out", "${t.callsOut} · ${t.minutesOut} min"),
        SimRow("SMS received", t.smsIn.toString()),
        SimRow("SMS sent", t.smsOut.toString()),
        SimRow("Mobile data", againstAllowance(t.dataBytes, allowance)),
    )

    /** #1415 the Usage table: one line per period (today, this cycle, month by month). */
    val TABLE_HEADER: List<String> = listOf("Period", "Calls", "Min", "SMS", "Data")

    fun table(rows: List<Pair<UsagePeriod, UsageTotals>>): List<List<String>> = rows.map { (p, t) ->
        listOf(p.label, "${t.callsIn + t.callsOut}", "${t.minutesIn + t.minutesOut}", "${t.smsIn + t.smsOut}", StatusFormat.bytes(t.dataBytes))
    }
}
