package com.diegonmarcos.superapp.appstore

import org.junit.Assert.assertEquals
import org.junit.Test

/** The Cloud strip reads "... Tiny-ML, Fleet Data, |, Ventoy": Fleet Data before the separator, the separator immediately before Ventoy. */
class StoreStripTest {
    private val groups = listOf("Apps", "Libs", "Tiny-ML")

    @Test fun fleetDataThenSeparatorThenVentoy() {
        val s = StoreStrip.slots(groups, 3, "Ventoy", 2, true, "Fleet Data")
        assertEquals(listOf("Apps", "Libs", "Tiny-ML", "Fleet Data", "|", "Ventoy"), s.map { it.label })
        assertEquals(listOf(0, 1, 2, StoreStrip.KIND_FLEETDATA, StoreStrip.KIND_SEPARATOR, StoreStrip.KIND_VENTOY), s.map { it.kind })
    }

    @Test fun hostWithoutProviderKeepsVentoyBehindTheSeparator() {
        assertEquals(listOf("Apps", "Libs", "Tiny-ML", "|", "Ventoy"), StoreStrip.slots(groups, 3, "Ventoy", 2, false, "Fleet Data").map { it.label })
    }

    @Test fun embeddedStoreWithNeitherIsJustTheGroups() {
        assertEquals(groups, StoreStrip.slots(groups, -1, "Ventoy", 2, false, "Fleet Data").map { it.label })
    }

    @Test fun fleetDataWithoutVentoyFollowsTinyMl() {
        assertEquals(groups + "Fleet Data", StoreStrip.slots(groups, -1, "Ventoy", 2, true, "Fleet Data").map { it.label })
    }
}
