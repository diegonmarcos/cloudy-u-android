package com.diegonmarcos.superapp.launcher

import com.diegonmarcos.superapp.network.LocalDoors
import com.diegonmarcos.superapp.network.UsbDataModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/** #1684 the three new / renamed indicators' state mapping: WebS, SSHd (on / off / unknown) and TypC (data / charge / none). */
class StripDoorsTest {
    private val on = 0xFFFFFFFF.toInt(); private val off = 0x44FFFFFF
    private fun probe(s: LocalDoors.State) = LocalDoors.Probe(LocalDoors.Door("x", "X", 1, null), s, "", null, 0)

    @Test fun `WebS and SSHd tints and dots`() {
        assertEquals(LocalDoors.State.UNKNOWN, StripDoors.websState(null))
        assertEquals(on, StripDoors.tint(StripDoors.websState(probe(LocalDoors.State.ON)), on, off))
        assertEquals(off, StripDoors.tint(StripDoors.websState(probe(LocalDoors.State.OFF)), on, off))
        assertEquals(StripDoors.UNKNOWN_TINT, StripDoors.tint(LocalDoors.State.UNKNOWN, on, off))
        assertEquals(LocalDoors.State.ON, StripDoors.sshdState(listOf(probe(LocalDoors.State.OFF), probe(LocalDoors.State.ON))))
        assertEquals(LocalDoors.State.UNKNOWN, StripDoors.sshdState(emptyList()))
        assertEquals(AdbPeerDots.UP, StripDoors.dot(LocalDoors.State.ON))
        assertEquals(AdbPeerDots.DOWN, StripDoors.dot(LocalDoors.State.OFF))
        assertEquals(AdbPeerDots.OFF, StripDoors.dot(LocalDoors.State.UNKNOWN))
    }

    @Test fun `TypC - none, charge-only, data`() {
        fun cable(extras: Map<String, Boolean>, plugged: Int, otg: List<String> = emptyList()) = UsbDataModel.state(extras, plugged, otg).cable
        assertEquals(UsbDataModel.Cable.NONE, cable(emptyMap(), 0))
        assertEquals(UsbDataModel.Cable.NONE, cable(emptyMap(), 4))                         // wireless charging is no cable
        assertEquals(UsbDataModel.Cable.CHARGE, cable(emptyMap(), 1))                       // wall charger
        assertEquals(UsbDataModel.Cable.CHARGE, cable(mapOf("connected" to true, "adb" to true), 2))  // computer, no data function
        assertEquals(UsbDataModel.Cable.DATA, cable(mapOf("connected" to true, "mtp" to true), 2))
        assertEquals(UsbDataModel.Cable.DATA, cable(emptyMap(), 0, listOf("Key (1:2)")))     // OTG host
        val tints = UsbDataModel.Cable.values().map { StripDoors.cableTint(it, on, off) }
        assertEquals(3, tints.toSet().size)
        assertNotEquals(on, StripDoors.cableTint(UsbDataModel.Cable.CHARGE, on, off))
    }
}
