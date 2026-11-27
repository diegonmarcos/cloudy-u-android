package com.diegonmarcos.cloudcalc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.math.BigDecimal
import java.util.Locale

/** #1963 Finance > Exchange: every box is an input, the others are outputs of it, A -> EUR -> B in exact decimals, no drift on round trips. */
@RunWith(RobolectricTestRunner::class)
class ExchangeInputTest {
    private val perEur = mapOf("EUR" to 1.0, "INR" to 100.3, "USD" to 1.0831, "GBP" to 0.8417, "JPY" to 162.77)
    private val base = Exchange.State("INR", "858.02", listOf("USD", "EUR", "GBP"))
    private val en = Locale.US
    private fun v(s: Exchange.State, typed: String) = Exchange.values(s, perEur, BigDecimal(typed))
    private fun two(x: BigDecimal?) = x!!.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()

    @Test fun `the first box as input gives the outputs of the others`() {
        val out = v(base, "858.02")
        assertEquals(listOf("858.02", "9.27", "8.55", "7.20"), out.map { two(it) })
    }

    @Test fun `any box as input recomputes every other box from it with the same rates`() {
        for (i in base.boxes.indices) {
            val s = Exchange.typed(base, i, "250")
            val out = v(s, "250")
            assertEquals("the input box shows what was typed", "250.00", two(out[i]))
            for (j in base.boxes.indices) if (j != i) {
                val want = BigDecimal("250").multiply(BigDecimal(perEur.getValue(base.boxes[j]))).divide(BigDecimal(perEur.getValue(base.boxes[i])), java.math.MathContext.DECIMAL128)
                assertEquals("box $j from input box $i", two(want), two(out[j]))
            }
        }
    }

    @Test fun `typed makes only a real box the input`() {
        assertEquals(2, Exchange.typed(base, 2, "5").active)
        assertEquals("a box out of range changes nothing", base, Exchange.typed(base, 9, "5"))
        assertEquals("5", Exchange.typed(base, 3, "5").amount)
    }

    @Test fun `switching the input keeps every value consistent`() {
        val before = v(base, base.amount)
        for (i in base.boxes.indices) {
            val s = Exchange.activate(base, perEur, i)
            assertEquals(i, s.active)
            val after = Exchange.values(s, perEur, BigDecimal(s.amount))
            assertEquals("input $i keeps the value it showed", two(before[i]), two(after[i]))
            for (j in base.boxes.indices) assertEquals("box $j after switching to $i", two(before[j]), two(after[j]))
        }
        assertEquals("activating the input is a no-op", base, Exchange.activate(base, perEur, 0))
    }

    @Test fun `a round trip does not drift`() {
        // type 100 in USD, switch the input to INR, read USD back
        val usd = Exchange.typed(base, 1, "100")
        val inr = Exchange.activate(usd, perEur, 0)
        val backUsd = Exchange.values(inr, perEur, BigDecimal(inr.amount))[1]!!
        assertEquals("100", backUsd.setScale(10, java.math.RoundingMode.HALF_UP).stripTrailingZeros().toPlainString())
        assertEquals("100.00", Exchange.amountText(backUsd, en))
        // ten hops around every box stay put
        var s = usd
        repeat(10) { n -> s = Exchange.activate(s, perEur, (n + 1) % s.boxes.size) }
        s = Exchange.activate(s, perEur, 1)
        assertEquals("100.00", Exchange.amountText(BigDecimal(s.amount), en))
        // A -> B -> A in one step pair
        val x = Exchange.convertExact(BigDecimal("100"), perEur, "USD", "JPY")!!
        assertEquals(0, BigDecimal("100").compareTo(Exchange.convertExact(x, perEur, "JPY", "USD")!!.setScale(20, java.math.RoundingMode.HALF_UP)))
    }

    @Test fun `conversion goes A to EUR to B`() {
        val viaEur = Exchange.convertExact(Exchange.convertExact(BigDecimal("7"), perEur, "USD", "EUR")!!, perEur, "EUR", "GBP")!!
        val direct = Exchange.convertExact(BigDecimal("7"), perEur, "USD", "GBP")!!
        assertEquals(0, viaEur.setScale(20, java.math.RoundingMode.HALF_UP).compareTo(direct.setScale(20, java.math.RoundingMode.HALF_UP)))
        assertEquals("EUR base: 1 EUR is the table's own units", "108.31", two(Exchange.convertExact(BigDecimal("100"), perEur, "EUR", "USD")))
        assertNull(Exchange.convertExact(BigDecimal.ONE, perEur, "USD", "ZZZ"))
        assertNull(Exchange.values(base.copy(targets = listOf("ZZZ")), perEur, BigDecimal("5"))[1])
    }

    @Test fun `an unparsable input leaves the outputs unknown`() {
        assertEquals(base.boxes.map { null }, Exchange.values(base, perEur, null))
    }

    @Test fun `removing a box keeps the input index pointing at the same currency`() {
        val s = Exchange.typed(base, 3, "5")                    // GBP is the input
        assertEquals("GBP", Exchange.removeTarget(s, "USD").let { it.boxes[it.active] })
        assertEquals("input removed: the first box takes over", 0, Exchange.removeTarget(s, "GBP").active)
        assertEquals(s.active, Exchange.removeTarget(s, "ZZZ").active)
    }

    @Test fun `parseDecimal reads the typed text exactly and amountText rounds half up`() {
        assertEquals(BigDecimal("1234.5"), Exchange.parseDecimal("1,234.5", en))
        assertEquals(BigDecimal("1234.5"), Exchange.parseDecimal("1.234,5", Locale.GERMANY))
        assertNull(Exchange.parseDecimal("", en))
        assertEquals("0.13", Exchange.amountText(BigDecimal("0.125"), en))
        assertEquals("—", Exchange.amountText(null as BigDecimal?, en))
    }

    @Test fun `the input box persists`() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("exchange_input_test", android.content.Context.MODE_PRIVATE)
        Exchange.Store(prefs).save(Exchange.typed(base, 2, "5"))
        assertEquals(2, Exchange.Store(prefs).load(base).active)
    }
}
