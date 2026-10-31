package com.diegonmarcos.cloudcalc.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** #1377 the Tools forms' counted outputs: what each calc/metric prints for given fields. */
class ToolCalcsTest {
    private val today = LocalDate.of(2026, 10, 31)
    private fun calc(c: String, m: String, vararg f: Pair<String, String>, cur: String = "EUR") =
        ToolCalcs.eval(c, m, { mapOf(*f)[it].orEmpty() }, cur, today)
    private fun fails(c: String, m: String, vararg f: Pair<String, String>): String =
        runCatching { calc(c, m, *f) }.exceptionOrNull()?.let { assertTrue(it is IllegalArgumentException); it.message.orEmpty() } ?: "no error"

    @Test fun date() {
        assertEquals("Saturday, 31 October 2026", calc("date", "full"))
        assertEquals("304 of 365", calc("date", "doy"))
        assertEquals("W44 of 2026", calc("date", "week"))
        assertEquals("Q4", calc("date", "quarter"))
        assertEquals("61", calc("date", "left"))
        assertEquals("No (next 2028)", calc("date", "leap"))
        assertEquals("W01 of 2025", calc("date", "week", "date" to "2024-12-30"))
        assertEquals("Yes", calc("date", "leap", "date" to "2024-02-01"))
        assertEquals("Q1", calc("date", "quarter", "date" to "2024-03-31"))
        assertEquals("Q2", calc("date", "quarter", "date" to "2024-04-01"))
        assertEquals("366 of 366", calc("date", "doy", "date" to "2024-12-31"))
        assertEquals("0", calc("date", "left", "date" to "2024-12-31"))
        assertEquals("No (next 2104)", calc("date", "leap", "date" to "2097-05-05"))
        assertTrue(fails("date", "full", "date" to "31/10/2026").startsWith("date"))
        assertTrue(fails("date", "nope").startsWith("metric"))
    }

    @Test fun loan() {
        val f = arrayOf("principal" to "200000", "rate" to "6", "years" to "30")
        assertEquals("1,199.10 EUR", calc("loan", "payment", *f))
        assertEquals("431,676.38 EUR", calc("loan", "total", *f))
        assertEquals("231,676.38 EUR", calc("loan", "interest", *f))
        val s = calc("loan", "schedule", *f)!!.lines()
        assertEquals(30, s.size)
        assertTrue(s[0], s[0].startsWith("Y1 ") && s[0].contains("11,933.19 EUR int"))
        assertTrue(s.last(), s.last().endsWith("0.00 EUR bal"))
        assertEquals("1,199.10", ToolCalcs.eval("loan", "payment", { mapOf(*f)[it].orEmpty() }, "", today))
        assertTrue(fails("loan", "payment", "principal" to "x", "rate" to "6", "years" to "30").startsWith("principal"))
        assertEquals("years must be positive", fails("loan", "payment", "principal" to "1", "rate" to "6", "years" to "0"))
    }

    @Test fun compoundAndPv() {
        val f = arrayOf("p" to "1000", "r" to "5", "y" to "10", "n" to "1")
        assertEquals("1,628.89 EUR", calc("compound", "fv", *f))
        assertEquals("628.89 EUR", calc("compound", "interest", *f))
        assertEquals("2,886.68 EUR", calc("compound", "fv", *f, "deposit" to "100"))
        assertEquals("886.68 EUR", calc("compound", "interest", *f, "deposit" to "100"))
        assertEquals("1,647.01 EUR", calc("compound", "fv", "p" to "1000", "r" to "5", "y" to "10", "n" to "12"))
        assertEquals("compounds per year must be positive", fails("compound", "fv", "p" to "1", "r" to "1", "y" to "1", "n" to "0"))
        assertEquals("613.91 EUR", calc("pv", "pv", "fv" to "1000", "rate" to "5", "years" to "10"))
    }

    @Test fun npv() {
        assertEquals("4.13 EUR", calc("npv", "npv", "rate" to "10", "flows" to "-100; 60; 60"))
        assertEquals("13.07 %", calc("npv", "irr", "rate" to "10", "flows" to "-100 60 60"))
        assertTrue(calc("npv", "irr", "rate" to "10", "flows" to "100 60")!!.startsWith("no IRR"))
        assertEquals("cash flow 'x'", fails("npv", "npv", "rate" to "1", "flows" to "-1 x"))
        assertEquals("no cash flows", fails("npv", "npv", "rate" to "1", "flows" to " "))
    }

    @Test fun savingsCagrInflation() {
        val s = arrayOf("goal" to "10000", "current" to "0", "rate" to "5", "years" to "10")
        assertEquals("64.40 EUR", calc("savings", "monthly", *s))
        assertEquals("7,727.86 EUR", calc("savings", "deposited", *s))
        assertEquals("2,272.14 EUR", calc("savings", "interest", *s))
        assertEquals("0.00 EUR", calc("savings", "monthly", "goal" to "100", "current" to "200", "rate" to "5", "years" to "1"))
        assertEquals("10.23 EUR", calc("savings", "interest", "goal" to "100", "current" to "200", "rate" to "5", "years" to "1"))
        assertEquals("years must be positive", fails("savings", "monthly", "goal" to "1", "current" to "0", "rate" to "1", "years" to "0"))
        assertEquals("14.87 %", calc("cagr", "rate", "start" to "100", "end" to "200", "years" to "5"))
        assertEquals("2×", calc("cagr", "multiple", "start" to "100", "end" to "200", "years" to "5"))
        assertEquals("134.39 EUR", calc("inflation", "future", "amount" to "100", "rate" to "3", "years" to "10"))
        assertEquals("74.41 EUR", calc("inflation", "real", "amount" to "100", "rate" to "3", "years" to "10"))
    }

    @Test fun sound() {
        assertEquals("A4 +0.0¢", calc("note", "note", "f" to "440", "a4" to "440"))
        assertEquals("C4 +0.0¢", calc("note", "note", "f" to "261.6256", "a4" to "440"))
        assertEquals("A4 +19.6¢", calc("note", "note", "f" to "445", "a4" to "440"))
        assertEquals("60", calc("note", "midi", "f" to "262", "a4" to "440"))
        assertEquals("440 Hz", calc("note", "nearest", "f" to "445", "a4" to "440"))
        assertEquals("frequency must be positive", fails("note", "note", "f" to "0", "a4" to "440"))
        assertEquals("440 Hz", calc("freq", "hz", "note" to "A4", "a4" to "440"))
        assertEquals("261.6256 Hz", calc("freq", "hz", "note" to "C4", "a4" to "440"))
        assertEquals("554.3653 Hz", calc("freq", "hz", "note" to "C#5", "a4" to "440"))
        assertEquals("233.0819 Hz", calc("freq", "hz", "note" to "Bb3", "a4" to "440"))
        assertEquals("466.1638 Hz", calc("freq", "hz", "note" to "A4", "a4" to "440", "cents" to "100"))
        assertEquals("432 Hz", calc("freq", "hz", "note" to "a4", "a4" to "432"))
        assertTrue(fails("freq", "hz", "note" to "H4", "a4" to "440").startsWith("note"))
        assertEquals("93.0103 dB", calc("db", "sum", "l1" to "90", "l2" to "90"))
        assertEquals("89.9794 dB", calc("db", "diff", "l1" to "93", "l2" to "90"))
        assertEquals("needs L1 > L2", calc("db", "diff", "l1" to "90", "l2" to "90"))
        assertEquals("93.9794 dB SPL", calc("spl", "spl", "pa" to "1"))
        assertEquals("1.0024 Pa", calc("spl", "pa", "level" to "94"))
        assertEquals("pressure must be positive", fails("spl", "spl", "pa" to "0"))
        val b = arrayOf("bpm" to "120")
        assertEquals("2000 ms", calc("bpm", "whole", *b)); assertEquals("1000 ms", calc("bpm", "half", *b))
        assertEquals("500 ms", calc("bpm", "quarter", *b)); assertEquals("250 ms", calc("bpm", "eighth", *b))
        assertEquals("125 ms", calc("bpm", "sixteenth", *b)); assertEquals("375 ms", calc("bpm", "dotted", *b))
        assertEquals("166.6667 ms", calc("bpm", "triplet", *b)); assertEquals("2 Hz", calc("bpm", "hz", *b))
        assertEquals("BPM and delay must be positive", fails("bpm", "quarter", "bpm" to "0"))
        assertEquals("120 BPM", calc("bpm", "from_ms", "ms" to "500")); assertEquals("BPM and delay must be positive", fails("bpm", "from_ms", "ms" to "-1"))
        assertTrue(fails("bpm", "nope", *b).startsWith("metric"))
        assertEquals("343.2146 m/s", calc("wave", "speed", "t" to "20"))
        assertEquals("1 m", calc("wave", "wavelength", "t" to "20", "f" to "343.2146"))
        assertEquals("343.2146 Hz", calc("wave", "frequency", "t" to "20", "lambda" to "1"))
        assertEquals("wavelength must be positive", fails("wave", "frequency", "t" to "20", "lambda" to "0"))
        val fs = arrayOf("rate" to "44100", "bits" to "16", "channels" to "2", "seconds" to "60")
        assertEquals("10.58 MB (10,584,000 bytes)", calc("filesize", "size", *fs))
        assertEquals("1411.2 kbit/s", calc("filesize", "bitrate", *fs))
        assertEquals("10.58 MB (10,584,000 bytes)", calc("filesize", "minute", *fs))
    }

    @Test fun formatting() {
        assertNull(calc("span", "days"))
        assertEquals(1234.5, ToolCalcs.num("1 234,5")!!, 0.0)
        assertEquals(1234.5, ToolCalcs.num("1,234.5")!!, 0.0)
        assertEquals(5.5, ToolCalcs.num("5,5")!!, 0.0)
        assertEquals(1234567.0, ToolCalcs.num("1,234,567")!!, 0.0)
        assertEquals(12000.0, ToolCalcs.num("12_000")!!, 0.0)
        assertNull(ToolCalcs.num("")); assertNull(ToolCalcs.num("abc")); assertNull(ToolCalcs.num("1e999"))
        assertEquals("0", ToolCalcs.fmt(0.0)); assertEquals("1.235e+09", ToolCalcs.fmt(1.2345e9)); assertEquals("0.0001000", ToolCalcs.fmt(1e-4))
        assertEquals("2.5", ToolCalcs.fmt(2.5)); assertEquals("-3", ToolCalcs.fmt(-3.0))
        assertEquals("512 B (512 bytes)", ToolCalcs.bytes(512.0))
        assertEquals("1.5 kB (1,500 bytes)", ToolCalcs.bytes(1500.0))
        assertEquals("2.00 GB (2,000,000,000 bytes)", ToolCalcs.bytes(2e9))
        assertEquals("12.50 %", ToolCalcs.percent(0.125))
        assertEquals("-1,000.00 USD", ToolCalcs.money(-1000.0, "USD"))
    }
}
