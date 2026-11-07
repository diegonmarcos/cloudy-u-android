package com.diegonmarcos.superapp.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** #1264 libs:bluetooth analyzer rules: AD structures, flags, UUIDs, beacons, distance, smoothing, the company table, the export. */
class AdvParserTest {
    private fun hex(s: String) = Hex.parse(s)!!
    private val beaconUuid = "E2C56DB5DFFB48D2B060D0F5A71096E0"

    @Test fun `flags, names, 16-bit uuids and tx power`() {
        val a = AdvParser.parse(hex("020106 03030D18 0509 54657374 020AF4 0319C103 00000000"))
        assertEquals(0x06, a.flags)
        assertEquals(listOf("LE General Discoverable", "BR/EDR not supported"), a.flagNames)
        assertEquals("Test", a.name)
        assertFalse(a.shortName)
        assertEquals(-12, a.txPower)
        assertEquals(-53, a.txAt1m)
        assertEquals(listOf("0000180d-0000-1000-8000-00805f9b34fb"), a.serviceUuids)
        assertEquals(0x03C1, a.appearance)
        assertEquals(5, a.structures.size)
        assertFalse(a.malformed)
    }

    @Test fun `128-bit uuids are little endian on air`() {
        val a = AdvParser.parse(hex("1107 9ECADC240EE5A9E093F3A3B50100406E"))
        assertEquals(listOf("6e400001-b5a3-f393-e0a9-e50e24dcca9e"), a.serviceUuids)
    }

    @Test fun `a length running past the record is reported as truncated`() {
        val a = AdvParser.parse(hex("020106 0909 4142"))
        assertTrue(a.malformed)
        assertEquals(1, a.structures.size)
    }

    @Test fun `iBeacon`() {
        val a = AdvParser.parse(hex("020106 1AFF4C000215${beaconUuid}00010002C5"))
        val b = a.beacons.single()
        assertEquals("iBeacon", b.kind)
        assertEquals("e2c56db5-dffb-48d2-b060-d0f5a71096e0", b.fields.toMap()["UUID"])
        assertEquals("1", b.fields.toMap()["Major"])
        assertEquals("2", b.fields.toMap()["Minor"])
        assertEquals(-59, b.txAt1m)
        assertEquals(-59, a.txAt1m)
    }

    @Test fun `AltBeacon`() {
        val a = AdvParser.parse(hex("1BFF1801BEAC${beaconUuid}0007000BC500"))
        val b = a.beacons.single()
        assertEquals("AltBeacon", b.kind)
        assertEquals("7", b.fields.toMap()["ID2"])
        assertEquals("11", b.fields.toMap()["ID3"])
        assertEquals("0x0118", b.fields.toMap()["Manufacturer"])
        assertEquals(-59, b.txAt1m)
    }

    @Test fun `Eddystone URL, UID and TLM`() {
        val url = AdvParser.parse(hex("0303AAFE 0D16AAFE10EB03676F6F676C6507")).beacons.single()
        assertEquals("Eddystone-URL", url.kind)
        assertEquals("https://google.com", url.fields.toMap()["URL"])
        assertEquals(-21 - 41, url.txAt1m)
        val uid = AdvParser.parse(hex("1716AAFE00E800112233445566778899AABBCCDDEEFF0000")).beacons.single()
        assertEquals("Eddystone-UID", uid.kind)
        assertEquals("00112233445566778899", uid.fields.toMap()["Namespace"])
        assertEquals("AABBCCDDEEFF", uid.fields.toMap()["Instance"])
        val tlm = AdvParser.parse(hex("1116AAFE20000BB818800000000A00000064")).beacons.single()
        assertEquals("Eddystone-TLM", tlm.kind)
        assertEquals("3000 mV", tlm.fields.toMap()["Battery"])
        assertEquals("24.50 °C", tlm.fields.toMap()["Temperature"])
        assertEquals("10", tlm.fields.toMap()["Advertisements"])
        assertEquals("10.0 s", tlm.fields.toMap()["Uptime"])
        assertNull(AdvParser.eddystoneUrl(hex("09"), 0))
    }

    @Test fun `distance follows the log-distance model and needs a reference`() {
        assertEquals(1.0, AdvParser.distanceMeters(-59, -59, 2.0)!!, 1e-9)
        assertEquals(10.0, AdvParser.distanceMeters(-79, -59, 2.0)!!, 1e-9)
        assertEquals(10.0, AdvParser.distanceMeters(-89, -59, 3.0)!!, 1e-9)
        assertNull(AdvParser.distanceMeters(-70, null))
        assertNull(AdvParser.distanceMeters(-127, -59))
    }

    @Test fun `smoothing and the locator`() {
        assertNull(AdvParser.smooth(emptyList()))
        assertEquals(-60.0, AdvParser.smooth(listOf(-60, -60, -60))!!, 1e-9)
        assertEquals(-57.0, AdvParser.smooth(listOf(-60, -50))!!, 1e-9)
        assertEquals(0.0, Locator.proximity(-110.0), 1e-9)
        assertEquals(1.0, Locator.proximity(-20.0), 1e-9)
        assertEquals(1500L, Locator.beepIntervalMs(-100.0))
        assertEquals(100L, Locator.beepIntervalMs(-35.0))
        assertEquals("Warmer", Locator.trend(List(5) { -80 } + List(5) { -70 }))
        assertEquals("Colder", Locator.trend(List(5) { -60 } + List(5) { -70 }))
        assertEquals("Steady", Locator.trend(List(10) { -60 }))
        assertEquals("Collecting...", Locator.trend(listOf(-60)))
    }

    @Test fun `class of device major classes`() {
        assertEquals("Phone", BtClass.major(0x5A020C))
        assertEquals("Audio / video", BtClass.major(0x240404))
        assertEquals("Uncategorized", BtClass.major(0x1F00))
    }

    @Test fun `the company table is data and names apple continuity`() {
        val f = File("../../../data/fleet/company-ids.json")
        assertTrue(f.exists())
        val c = CompanyIds.parse(f.readText())
        assertEquals("Apple", c.name(0x004C))
        assertEquals("Nordic Semiconductor", c.name(0x0059))
        assertEquals("0xBEEF", c.name(0xBEEF))
        assertEquals("Find My", c.appleType(0x004C, hex("1219")))
        assertNull(c.appleType(0x0059, hex("12")))
        assertEquals("Eddystone", c.serviceDataName("feaa"))
        assertTrue(c.companies.size >= 30)
    }

    @Test fun `merge keeps the newest raw record and the classic mark`() {
        val a = BleDevice("AA", "x", -70, raw = "0201", classic = true, deviceClass = 0x200)
        val b = BleDevice("AA", null, -60)
        val m = a.merge(b)
        assertEquals(-60, m.rssi)
        assertEquals("0201", m.raw)
        assertTrue(m.classic)
        assertEquals(0x200, m.deviceClass)
        assertEquals("x", m.name)
    }

    @Test fun `export json and csv`() {
        val d = BleDevice("AA:BB", "Tag, \"one\"", -59, raw = "020106 1AFF4C000215${beaconUuid}00010002C5".replace(" ", ""))
        val rows = BleExport.rows(listOf(d), mapOf("AA:BB" to listOf(-59, -59)), 2.0)
        val json = BleExport.json(rows, null, 42L)
        assertTrue(json, json.startsWith("{\"exported_ms\":42,\"devices\":[{\"address\":\"AA:BB\",\"name\":\"Tag, \\\"one\\\"\""))
        assertTrue(json, json.contains("\"distance_m\":1.00"))
        assertTrue(json, json.contains("\"kind\":\"iBeacon\""))
        assertTrue(json, json.contains("\"rssi_history\":[-59,-59]"))
        val csv = BleExport.csv(rows, null).lines()
        assertEquals(BleExport.CSV_HEADER.joinToString(","), csv[0])
        assertTrue(csv[1], csv[1].startsWith("AA:BB,\"Tag, \"\"one\"\"\",-59,-59.0,1.00"))
        assertNotNull(csv[1].contains("iBeacon"))
    }
}
