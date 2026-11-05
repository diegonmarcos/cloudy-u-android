package com.diegonmarcos.superapp.perms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #1519 what blocks a Wi-Fi scan, named one by one with the Perms row that fixes it. */
class PermsWifiScanTest {
    private val ok = PermsWifiScan.Snapshot(sdk = 36, fine = true, coarse = true, nearby = true, locationOn = true)

    @Test fun `nothing blocks a scan when every need is met`() = assertEquals(emptyList<PermsWifiScan.Block>(), PermsWifiScan.detect(ok))

    @Test fun `each blocker is named with its Perms row`() {
        assertEquals(listOf("fine_location"), PermsWifiScan.detect(ok.copy(fine = false)).map { it.entryId })
        assertEquals(listOf("nearby_wifi"), PermsWifiScan.detect(ok.copy(nearby = false)).map { it.entryId })
        assertEquals(listOf("location_services"), PermsWifiScan.detect(ok.copy(locationOn = false)).map { it.entryId })
        assertEquals(listOf("fine_location", "nearby_wifi", "location_services"),
            PermsWifiScan.detect(ok.copy(fine = false, nearby = false, locationOn = false)).map { it.entryId })
    }

    @Test fun `approximate location alone does not satisfy a scan`() {
        val b = PermsWifiScan.detect(ok.copy(fine = false, coarse = true)).single()
        assertEquals("fine_location", b.entryId); assertTrue(b.text.contains("approximate"))
    }

    @Test fun `NEARBY_WIFI_DEVICES only blocks on API 33 and above`() {
        assertTrue(PermsWifiScan.detect(ok.copy(sdk = 32, nearby = false)).isEmpty())
        assertEquals("nearby_wifi", PermsWifiScan.detect(ok.copy(sdk = 33, nearby = false)).single().entryId)
    }

    @Test fun `the scan throttle is a soft blocker that shows only when the budget is spent`() {
        assertTrue(PermsWifiScan.detect(ok.copy(throttleRemaining = 2)).isEmpty())
        val t = PermsWifiScan.detect(ok.copy(throttleRemaining = 0)).single()
        assertEquals("wifi_scan_throttle", t.entryId); assertFalse(t.hard)
        assertTrue(PermsWifiScan.detect(ok.copy(throttleOn = false, throttleRemaining = 0)).isEmpty())
        assertTrue(PermsWifiScan.hard(PermsWifiScan.detect(ok.copy(throttleRemaining = 0))).isEmpty())
        assertEquals("Fix in Perms ›", PermsWifiScan.FIX)
    }
}
