package com.diegonmarcos.hardtechbiomed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Holdings: the local list is validated, round-trips through its JSON, and its cost basis is quantity x cost. */
class HoldingsTest {
    @Test fun addValidatesAndUppercases() {
        val one = Holdings.add(emptyList(), " aapl ", "10", "150.5")!!
        assertEquals(listOf(Holding("AAPL", 10.0, 150.5)), one)
        assertNull(Holdings.add(one, "", "1", "1"))
        assertNull(Holdings.add(one, "X", "0", "1"))
        assertNull(Holdings.add(one, "X", "-2", "1"))
        assertNull(Holdings.add(one, "X", "1", "-1"))
        assertNull(Holdings.add(one, "X", "abc", "1"))
        assertNull(Holdings.add(one, "X", "1", ""))
        assertNull(Holdings.add(one, "X", "NaN", "1"))
        assertNull(Holdings.add(one, "X", "Infinity", "1"))
    }

    @Test fun roundTripsThroughJson() {
        val list = listOf(Holding("A", 2.0, 3.0), Holding("B", 0.5, 0.0))
        assertEquals(list, Holdings.parse(Holdings.toJson(list)))
        assertEquals(emptyList<Holding>(), Holdings.parse("not json"))
        assertEquals(emptyList<Holding>(), Holdings.parse("""[{"quantity":1}]"""))
    }

    @Test fun removeAndCostBasis() {
        val list = listOf(Holding("A", 2.0, 3.0), Holding("B", 4.0, 0.5))
        assertEquals(8.0, Holdings.costBasis(list), 1e-9)
        assertEquals(listOf(Holding("B", 4.0, 0.5)), Holdings.remove(list, 0))
        assertEquals(list, Holdings.remove(list, 7))
        assertEquals(0.0, Holdings.costBasis(emptyList()), 0.0)
    }
}
