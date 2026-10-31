package com.diegonmarcos.cloudcalc.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1377 the finance formulas against textbook values. */
class FinanceTest {
    private val e = 0.005

    @Test fun payment() {
        assertEquals(1199.10, Finance.payment(200000.0, 0.06 / 12, 360), e) // 30-year mortgage at 6 %
        assertEquals(100.0, Finance.payment(1200.0, 0.0, 12), 1e-9)
        assertEquals(1050.0, Finance.payment(1000.0, 0.05, 1), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class) fun paymentNeedsPeriods() { Finance.payment(1.0, 0.1, 0) }

    @Test fun schedule() {
        val rows = Finance.schedule(200000.0, 0.005, 360)
        assertEquals(360, rows.size)
        assertEquals(1000.0, rows[0].interest, 1e-9)
        assertEquals(199.10, rows[0].principal, e)
        assertEquals(199800.90, rows[0].balance, e)
        assertEquals(0.0, rows.last().balance, 0.0)
        assertEquals(200000.0, rows.sumOf { it.principal }, 1e-6)
        assertEquals(231676.38, rows.sumOf { it.interest }, 0.05)
        val years = Finance.yearly(rows, 12)
        assertEquals(30, years.size)
        assertEquals(1, years[0].period); assertEquals(rows[11].balance, years[0].balance, 1e-9)
        assertEquals(rows.take(12).sumOf { it.interest }, years[0].interest, 1e-9)
        assertEquals(rows.take(12).sumOf { it.payment }, years[0].payment, 1e-9)
        assertEquals(rows.take(12).sumOf { it.principal }, years[0].principal, 1e-9)
        val two = Finance.schedule(1000.0, 0.1, 2) // 576.19 twice
        assertEquals(100.0, two[0].interest, 1e-9); assertEquals(523.81, two[0].balance, e)
        assertEquals(576.19, two[1].payment, e); assertEquals(52.38, two[1].interest, e)
    }

    @Test fun futureAndPresentValue() {
        assertEquals(1628.89, Finance.futureValue(1000.0, 0.05, 10.0), e)
        assertEquals(1257.79, Finance.futureValue(0.0, 0.05, 10.0, 100.0), e)
        assertEquals(150.0, Finance.futureValue(100.0, 0.0, 5.0, 10.0), 1e-9)
        assertEquals(613.91, Finance.presentValue(1000.0, 0.05, 10.0), e)
        assertEquals(1000.0, Finance.presentValue(1000.0, 0.0, 10.0), 1e-9)
    }

    @Test fun npvIrr() {
        assertEquals(4.1322, Finance.npv(0.10, listOf(-100.0, 60.0, 60.0)), 1e-4)
        assertEquals(20.0, Finance.npv(0.0, listOf(-100.0, 60.0, 60.0)), 1e-9)
        assertEquals(0.130662, Finance.irr(listOf(-100.0, 60.0, 60.0))!!, 1e-5)
        assertEquals(0.10, Finance.irr(listOf(-100.0, 110.0))!!, 1e-7)
        assertEquals(-0.5, Finance.irr(listOf(-100.0, 50.0))!!, 1e-7)
        assertNull(Finance.irr(listOf(100.0, 50.0)))
        assertNull(Finance.irr(listOf(-100.0)))
    }

    @Test fun savings() {
        assertEquals(1000.0, Finance.savingsDeposit(10000.0, 0.0, 0.0, 10), 1e-9)
        assertEquals(64.40, Finance.savingsDeposit(10000.0, 0.0, 0.05 / 12, 120), e)
        assertEquals(0.0, Finance.savingsDeposit(1000.0, 2000.0, 0.01, 12), 0.0)
        assertEquals(500.0, Finance.savingsDeposit(10000.0, 5000.0, 0.0, 10), 1e-9)
    }

    @Test(expected = IllegalArgumentException::class) fun savingsNeedsPeriods() { Finance.savingsDeposit(1.0, 0.0, 0.0, 0) }

    @Test fun cagrInflation() {
        assertEquals(0.148698, Finance.cagr(100.0, 200.0, 5.0), 1e-6)
        assertEquals(-0.5, Finance.cagr(200.0, 100.0, 1.0), 1e-12)
        assertEquals(134.39, Finance.inflate(100.0, 0.03, 10.0), e)
        assertEquals(74.41, Finance.deflate(100.0, 0.03, 10.0), e)
    }

    @Test(expected = IllegalArgumentException::class) fun cagrNeedsPositive() { Finance.cagr(0.0, 1.0, 1.0) }
}
