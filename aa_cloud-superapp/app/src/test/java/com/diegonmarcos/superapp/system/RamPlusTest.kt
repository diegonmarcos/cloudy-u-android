package com.diegonmarcos.superapp.system

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** #1508 Samsung RAM Plus: the read-only detection (ram_expand_size, then SwapTotal) and the declared desired state. */
class RamPlusTest {
    @Test fun samsungKeyDecidesFirst() {
        assertEquals(true, RamPlus.decide("4", 0L))
        assertEquals(true, RamPlus.decide(" 4096 ", null))
        assertEquals(false, RamPlus.decide("0", 8_000_000L))      // the key wins over SwapTotal
    }

    @Test fun swapTotalIsTheFallback() {
        assertEquals(true, RamPlus.decide(null, 4_194_300L))
        assertEquals(true, RamPlus.decide("null", 1L))             // `settings get` prints "null" for an absent key
        assertEquals(false, RamPlus.decide("", 0L))
        assertNull(RamPlus.decide(null, null))
    }

    @Test fun meminfoSwapTotal() {
        assertEquals(4_194_300L, RamPlus.parseSwapTotalKb("MemTotal: 1 kB\nSwapTotal:      4194300 kB\nSwapFree: 2 kB\n"))
        assertNull(RamPlus.parseSwapTotalKb("MemTotal: 1 kB\n"))
    }

    @Test fun desiredStateIsData_defaultOn() {
        assertEquals(true, RamPlus.satisfied(true, "on"))
        assertEquals(false, RamPlus.satisfied(false, "on"))
        assertEquals(false, RamPlus.satisfied(false, ""))          // no `want` = on
        assertEquals(true, RamPlus.satisfied(false, "off"))
        assertNull(RamPlus.satisfied(null, "on"))
    }

    @Test fun deepLinkRungs() {
        assertEquals(RamPlus.Target("com.samsung.android.lool", "com.samsung.android.sm.ui.ram.RamActivity"),
            RamPlus.target("com.samsung.android.lool/com.samsung.android.sm.ui.ram.RamActivity"))
        assertEquals(RamPlus.Target("com.samsung.android.lool", null), RamPlus.target("com.samsung.android.lool"))
    }
}
