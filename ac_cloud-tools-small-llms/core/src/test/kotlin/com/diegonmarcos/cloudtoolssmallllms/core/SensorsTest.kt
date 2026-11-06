package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The Sensors section on its data (app/src/main/assets/sensors.json) and its logic: the sensor list model over a fake
 * SensorManager source, the type-name mapping, the listener lifecycle, the 20 declared external sensors and the GATT parsing.
 */
class SensorsTest {
    private val json = File("../app/src/main/assets/sensors.json").readText()
    private val cat = SensorCatalogue.parse(json)

    private fun sensor(id: Int, type: Int, name: String = "s$id", stringType: String = "", wake: Boolean = false, dynamic: Boolean = false, mode: Int = 0) =
        SensorRaw(id, name, "Vendor", type, stringType, 3, 0.25f, 0.01f, 8f, 5000, 200000, mode, wake, 100, dynamic)

    private class FakeSensorManager(val list: List<SensorRaw>, val dynamicList: List<SensorRaw>) : SensorSource {
        override fun sensors() = list + dynamicList
    }

    // ── the sensor list model ──────────────────────────────────────────────────

    @Test fun `the list holds every sensor the manager reports, wake-up, dynamic, uncalibrated and OEM ones included`() {
        val sm = FakeSensorManager(
            listOf(
                sensor(1, 1, "Accel"), sensor(2, 1, "Accel wake-up", wake = true), sensor(3, 16, "Gyro uncal"), sensor(4, 35, "Accel uncal"),
                sensor(5, 33171006, "OEM thing", "com.oem.sensor.foo"), sensor(6, 999, "Mystery"), sensor(7, 19, "Steps", mode = 1), sensor(8, 17, "Sig motion", mode = 2),
            ),
            listOf(sensor(9, 21, "Strap HR", dynamic = true), sensor(1, 1, "Accel")),
        )
        val rows = cat.rows(sm)
        assertEquals(9, rows.size)
        assertEquals((1..9).toSet(), rows.map { it.raw.id }.toSet())
        assertTrue(rows.first { it.raw.id == 2 }.raw.wakeUp)
        assertTrue(rows.first { it.raw.id == 9 }.raw.dynamic)
        assertFalse(rows.first { it.raw.id == 1 }.raw.dynamic)
        assertEquals(listOf(1, 1, 16, 17, 19, 21, 35, 999, 33171006), rows.map { it.raw.type })
    }

    @Test fun `a row carries every field the page shows, and the reporting mode name`() {
        val r = cat.rows(SensorSource { listOf(sensor(7, 19, "Steps", mode = 1)) }).single()
        assertEquals("Steps", r.raw.name); assertEquals("Vendor", r.raw.vendor); assertEquals(3, r.raw.version)
        assertEquals(0.25f, r.raw.powerMa); assertEquals(0.01f, r.raw.resolution); assertEquals(8f, r.raw.maxRange)
        assertEquals(5000, r.raw.minDelayUs); assertEquals(200000, r.raw.maxDelayUs); assertEquals(100, r.raw.fifoMax)
        assertEquals("On change", r.mode)
        assertEquals("TYPE_STEP_COUNTER", r.typeConst); assertEquals("Step counter", r.typeLabel)
        assertEquals("Continuous", cat.modeOf(0)); assertEquals("One-shot", cat.modeOf(2)); assertEquals("Special trigger", cat.modeOf(3))
        assertEquals("Mode 9", cat.modeOf(9))
    }

    @Test fun `an empty manager gives an empty list`() = assertTrue(cat.rows(SensorSource { emptyList() }).isEmpty())

    // ── the type-name mapping ──────────────────────────────────────────────────

    @Test fun `every standard type has a constant name and a readable name`() {
        val types = JSONObject(json).getJSONArray("types")
        assertEquals(42, types.length())
        val consts = (0 until types.length()).map { types.getJSONObject(it) }
        assertEquals((1..42).toList(), consts.map { it.getInt("id") })
        assertEquals(42, consts.map { it.getString("const") }.toSet().size)
        for (t in consts) {
            assertTrue(t.getString("const").startsWith("TYPE_"))
            assertTrue(t.getString("label").isNotBlank())
            assertEquals(t.getString("const") to t.getString("label"), cat.typeOf(t.getInt("id")))
        }
        assertEquals("TYPE_ACCELEROMETER" to "Accelerometer", cat.typeOf(1))
        assertEquals("TYPE_GYROSCOPE_UNCALIBRATED" to "Gyroscope (uncalibrated)", cat.typeOf(16))
        assertEquals("TYPE_HEADING" to "Heading", cat.typeOf(42))
    }

    @Test fun `a vendor type and an unknown type fall back to the data's templates`() {
        assertEquals("TYPE_DEVICE_PRIVATE_BASE + 6" to "Vendor sensor com.oem.x", cat.typeOf(65536 + 6, "com.oem.x"))
        assertEquals("TYPE_DEVICE_PRIVATE_BASE + 0" to "Vendor sensor ", cat.typeOf(65536))
        assertEquals("TYPE_999" to "Unknown sensor type 999", cat.typeOf(999, "ignored"))
        assertEquals("TYPE_43" to "Unknown sensor type 43", cat.typeOf(43))
        assertEquals("TYPE_0" to "Unknown sensor type 0", cat.typeOf(0))
    }

    @Test fun `numbers print without trailing zeros`() {
        assertEquals("0.0012", Num.short(0.0012f)); assertEquals("1000", Num.short(1000f)); assertEquals("-2.5", Num.short(-2.5f))
        assertEquals("0", Num.short(0f)); assertEquals("-", Num.short(Float.NaN)); assertEquals("-", Num.short(Float.POSITIVE_INFINITY))
    }

    // ── the listener lifecycle ─────────────────────────────────────────────────

    private class FakeRegistrar(val accept: Boolean = true) : SensorRegistrar {
        var registers = 0; var unregisters = 0; var live: ((FloatArray) -> Unit)? = null; var ids = ArrayList<Int>()
        override fun register(sensorId: Int, onValues: (FloatArray) -> Unit): Boolean { registers++; ids += sensorId; live = if (accept) onValues else null; return accept }
        override fun unregister() { unregisters++; live = null }
    }

    @Test fun `no listener exists before show or after hide`() {
        val reg = FakeRegistrar()
        val live = LiveReading(reg, 5)
        assertEquals(0, reg.registers); assertNull(live.sensorId)
        live.hide()
        assertEquals("hide with nothing shown unregisters nothing", 0, reg.unregisters)
        live.show(7)
        assertEquals(1, reg.registers); assertEquals(7, live.sensorId); assertNotNull(reg.live)
        live.hide()
        assertEquals(1, reg.unregisters); assertNull(live.sensorId); assertNull(reg.live)
        live.hide()
        assertEquals("a second hide is a no-op", 1, reg.unregisters)
    }

    @Test fun `showing another sensor first unregisters the previous one, and a hidden sensor's late event is dropped`() {
        val reg = FakeRegistrar()
        val live = LiveReading(reg, 5)
        live.show(1); val first = reg.live!!
        live.show(2)
        assertEquals(listOf(1, 2), reg.ids); assertEquals(1, reg.unregisters); assertEquals(2, live.sensorId)
        live.hide()
        first(floatArrayOf(9f))
        assertTrue(live.samples.isEmpty()); assertEquals(0, live.last.size)
    }

    @Test fun `samples are kept up to the history and the range spans the first series`() {
        var changes = 0
        val reg = FakeRegistrar()
        val live = LiveReading(reg, 3) { changes++ }
        live.show(1)
        val before = changes
        val buf = floatArrayOf(0f, 100f)
        for (v in listOf(1f, 5f, -3f, 2f)) { buf[0] = v; reg.live!!(buf) }
        assertEquals(before + 4, changes)
        assertEquals(listOf(5f, -3f, 2f), live.samples.map { it[0] })
        assertEquals("the sensor reuses its buffer: samples are copies", 2f, live.last[0]); assertEquals(100f, live.last[1])
        assertEquals(-3f to 5f, live.range(1))
        assertEquals(-3f to 100f, live.range(2))
        assertTrue(live.supported)
    }

    @Test fun `an empty or flat trace gets a usable range`() {
        val reg = FakeRegistrar()
        val live = LiveReading(reg, 3)
        assertEquals(0f to 1f, live.range(3))
        live.show(1); reg.live!!(floatArrayOf(4f)); reg.live!!(floatArrayOf(4f))
        assertEquals(3.5f to 4.5f, live.range(3))
    }

    @Test fun `a refused registration is reported and leaves nothing registered`() {
        val reg = FakeRegistrar(accept = false)
        val live = LiveReading(reg, 3)
        live.show(4)
        assertFalse(live.supported); assertNull(live.sensorId)
        live.hide()
        assertEquals(0, reg.unregisters)
    }

    // ── the Connected profiles ─────────────────────────────────────────────────

    private val connected get() = cat.connected

    @Test fun `twenty external sensors are declared, each with a valid GATT UUID or USB class`() {
        val ps = connected.profiles
        assertEquals(20, ps.size)
        assertEquals(20, ps.map { it.id }.toSet().size)
        assertEquals(10000L, connected.scanTimeoutMs)
        val uuid = Regex("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
        for (p in ps) {
            assertTrue("${p.id} has neither", p.uuid != null || p.usbClass != null)
            p.uuid?.let { assertTrue("${p.id}: $it", uuid.matches(it)) }
            p.usbClass?.let { assertTrue("${p.id}: $it", it in 0..255) }
            assertTrue(p.label.isNotBlank() && p.category.isNotBlank() && p.typicalUse.isNotBlank())
            assertTrue(p.connection in listOf("BLE", "USB-C", "BLE + USB-C"))
            assertEquals(p.connection.contains("BLE"), p.ble)
            assertEquals(p.connection.contains("USB"), p.usb)
        }
        val byId = ps.associateBy { it.id }
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", byId.getValue("hrs").uuid)
        assertEquals("0x180D", Uuid.short(byId.getValue("hrs").uuid!!))
        for ((id, short) in mapOf("csc" to "1816", "cp" to "1818", "rsc" to "1814", "bps" to "1810", "gls" to "1808", "hts" to "1809", "wss" to "181d", "plx" to "1822", "ess" to "181a"))
            assertEquals(id, "0000$short-0000-1000-8000-00805f9b34fb", byId.getValue(id).uuid)
        assertEquals(14, byId.getValue("thermal_cam").usbClass); assertEquals(14, byId.getValue("microscope").usbClass)
        assertEquals(2, byId.getValue("gps_usb").usbClass); assertEquals(255, byId.getValue("scope").usbClass)
        assertEquals(76, byId.getValue("beacon").manufacturerId)
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", byId.getValue("serial").uuid)
        assertEquals(setOf("Health", "Sport", "Environment", "Location", "Imaging", "Test", "Maker", "Motion"), ps.map { it.category }.toSet())
    }

    @Test fun `every GATT read names a valid characteristic, a mode and a parser whose keys are declared`() {
        val keys = cat.gattKeys.keys
        for (p in connected.profiles) for (r in p.reads) {
            assertNotNull(Uuid.normalize(r.char)); assertTrue(r.mode == "notify" || r.mode == "read"); assertTrue(p.ble)
            if (r.parser != "hex") {
                val sample = GattParse.parse(r.parser, ByteArray(8))
                assertNotNull("${p.id}: ${r.parser}", sample)
                assertTrue(keys.containsAll(sample!!.map { it.key }))
            }
        }
        assertEquals("Heart rate" to "bpm", cat.gattKeys.getValue("bpm").let { it.label to it.unit })
        assertTrue(connected.profiles.first { it.id == "hrs" }.reads.single().let { it.char == "00002a37-0000-1000-8000-00805f9b34fb" && it.parser == "heart_rate" && it.mode == "notify" })
        assertEquals(3, connected.profiles.first { it.id == "ess" }.reads.size)
    }

    @Test fun `UUIDs normalize from 16, 32 and 128-bit forms and reject the rest`() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", Uuid.normalize("180D"))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", Uuid.normalize(" 0x180d "))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", Uuid.normalize("0X180D"))
        assertEquals("1234abcd-0000-1000-8000-00805f9b34fb", Uuid.normalize("1234ABCD"))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", Uuid.normalize("6E400001-B5A3-F393-E0A9-E50E24DCCA9E"))
        for (bad in listOf("", "18", "180", "18G0", "12345", "6E400001-B5A3-F393-E0A9", "zzzzzzzz-zzzz-zzzz-zzzz-zzzzzzzzzzzz")) assertNull(bad, Uuid.normalize(bad))
        assertEquals("0x180D", Uuid.short("0000180d-0000-1000-8000-00805f9b34fb"))
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", Uuid.short("6e400001-b5a3-f393-e0a9-e50e24dcca9e"))
        assertEquals("1234abcd-0000-1000-8000-00805f9b34fb", Uuid.short("1234abcd-0000-1000-8000-00805f9b34fb"))
    }

    @Test fun `a profile with no UUID and no USB class, a bad UUID or a bad class is an error`() {
        fun one(extra: String) = JSONObject("""{"scan_timeout_ms":1,"profiles":[{"id":"x","label":"X","category":"C","connection":"BLE","typical_use":"u"$extra}]}""")
        assertEquals(1, ConnectedCatalogue.parse(one(""","uuid":"180D"""")).profiles.size)
        assertEquals(1, ConnectedCatalogue.parse(one(""","usb_class":0""")).profiles.size)
        assertEquals(1, ConnectedCatalogue.parse(one(""","usb_class":255""")).profiles.size)
        for (bad in listOf("", ""","uuid":"18"""", ""","usb_class":256""", ""","usb_class":-1""")) {
            try { ConnectedCatalogue.parse(one(bad)); org.junit.Assert.fail("accepted: $bad") } catch (_: IllegalArgumentException) { }
        }
        val dup = JSONObject("""{"scan_timeout_ms":1,"profiles":[{"id":"x","label":"X","category":"C","connection":"BLE","typical_use":"u","uuid":"180D"},{"id":"x","label":"Y","category":"C","connection":"BLE","typical_use":"u","uuid":"180F"}]}""")
        try { ConnectedCatalogue.parse(dup); org.junit.Assert.fail("duplicate id accepted") } catch (_: IllegalArgumentException) { }
    }

    @Test fun `a BLE advertisement matches by service UUID or by manufacturer data, a USB device by any class`() {
        assertEquals(listOf("hrs"), connected.bleMatches(listOf("0000180d-0000-1000-8000-00805f9b34fb"), emptyMap()).map { it.id })
        assertEquals(listOf("hrs", "ess"), connected.bleMatches(listOf("180D", "0000181A-0000-1000-8000-00805F9B34FB"), emptyMap()).map { it.id })
        assertTrue(connected.bleMatches(listOf("0000ffff-0000-1000-8000-00805f9b34fb"), emptyMap()).isEmpty())
        assertEquals(listOf("beacon"), connected.bleMatches(emptyList(), mapOf(76 to byteArrayOf(2, 21, 1, 2, 3))).map { it.id })
        assertTrue("wrong prefix", connected.bleMatches(emptyList(), mapOf(76 to byteArrayOf(16, 5, 1))).isEmpty())
        assertTrue("too short", connected.bleMatches(emptyList(), mapOf(76 to byteArrayOf(2))).isEmpty())
        assertTrue("other maker", connected.bleMatches(emptyList(), mapOf(6 to byteArrayOf(2, 21))).isEmpty())
        assertEquals(listOf("thermal_cam", "microscope"), connected.usbMatches(listOf(14)).map { it.id })
        assertEquals(listOf("serial", "gps_usb", "soil"), connected.usbMatches(setOf(9, 2)).map { it.id })
        assertTrue(connected.usbMatches(listOf(8, 3)).isEmpty())
    }

    @Test fun `the hardware list declares the non-SensorManager items with their probes`() {
        val hw = cat.hardware
        assertEquals(listOf("gps", "cameras", "mics", "barometer", "step_counter", "nfc", "uwb", "biometric", "ir", "hall", "battery", "thermal"), hw.map { it.id })
        assertEquals(listOf("gnss", "cameras", "microphones", "sensor_type", "sensor_type", "feature", "feature", "biometric", "ir", "sensor_name", "battery", "thermal"), hw.map { it.probe })
        assertEquals(6, hw.first { it.id == "barometer" }.sensorType); assertEquals(19, hw.first { it.id == "step_counter" }.sensorType)
        assertEquals("android.hardware.nfc", hw.first { it.id == "nfc" }.feature); assertNull(hw.first { it.id == "battery" }.feature)
        assertEquals(listOf("hall"), hw.first { it.id == "hall" }.match); assertTrue(hw.first { it.id == "nfc" }.match.isEmpty())
        assertEquals(listOf(0, 10, 30), hw.first { it.id == "thermal" }.forecastS); assertTrue(hw.first { it.id == "nfc" }.forecastS.isEmpty())
        assertTrue(hw.all { it.label.isNotBlank() })
        assertEquals(6, cat.maxSeries); assertEquals(120, cat.history)
    }

    // ── GATT parsing ───────────────────────────────────────────────────────────

    private fun hr(vararg b: Int) = GattParse.heartRate(ByteArray(b.size) { b[it].toByte() })
    private fun v(l: List<GattParse.Value>, k: String) = l.filter { it.key == k }.map { it.value }

    @Test fun `heart rate flags bit0 picks uint8 or uint16`() {
        assertEquals(listOf(GattParse.Value("bpm", 72.0)), hr(0x00, 72))
        assertEquals(listOf(GattParse.Value("bpm", 255.0)), hr(0x00, 0xFF))
        assertEquals(listOf(GattParse.Value("bpm", 300.0)), hr(0x01, 0x2C, 0x01))
        assertEquals(listOf(GattParse.Value("bpm", 65535.0)), hr(0x01, 0xFF, 0xFF))
    }

    @Test fun `heart rate sensor contact, energy expended and R-R intervals`() {
        assertEquals(listOf(1.0), v(hr(0x06, 60), "contact"))
        assertEquals(listOf(0.0), v(hr(0x04, 60), "contact"))
        assertTrue("contact not supported: no value", v(hr(0x02, 60), "contact").isEmpty())
        assertEquals(listOf(500.0), v(hr(0x08, 60, 0xF4, 0x01), "energy_kj"))
        val all = hr(0x1E, 61, 0x10, 0x00, 0x00, 0x04, 0x00, 0x02)
        assertEquals(listOf(61.0), v(all, "bpm")); assertEquals(listOf(1.0), v(all, "contact")); assertEquals(listOf(16.0), v(all, "energy_kj"))
        assertEquals(listOf(1000.0, 500.0), v(all, "rr_ms"))
        // uint16 rate shifts the later fields by one byte
        val wide = hr(0x19, 0x2C, 0x01, 0x05, 0x00, 0x00, 0x04)
        assertEquals(listOf(300.0), v(wide, "bpm")); assertEquals(listOf(5.0), v(wide, "energy_kj")); assertEquals(listOf(1000.0), v(wide, "rr_ms"))
        assertTrue("a trailing odd byte is not an interval", v(hr(0x10, 60, 0x00, 0x04, 0x07), "rr_ms") == listOf(1000.0))
    }

    @Test fun `a payload too short for its flags is not parsed`() {
        assertNull(GattParse.parse("heart_rate", byteArrayOf()))
        assertNull(GattParse.parse("heart_rate", byteArrayOf(0x01, 0x2C)))
        assertNull(GattParse.parse("heart_rate", byteArrayOf(0x08, 60, 1)))
        assertNull(GattParse.parse("nonsense", byteArrayOf(0, 1, 2, 3, 4, 5)))
        assertNull(GattParse.parse("hex", byteArrayOf(0, 1)))
        assertEquals(listOf(GattParse.Value("bpm", 72.0)), GattParse.parse("heart_rate", byteArrayOf(0, 72)))
    }

    @Test fun `IEEE-11073 floats`() {
        assertEquals(36.5, GattParse.float32(byteArrayOf(0x6D, 0x01, 0x00, 0xFF.toByte()), 0), 1e-9)   // 365 * 10^-1
        assertEquals(-1.5, GattParse.float32(byteArrayOf(0xF1.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()), 0), 1e-9)   // -15 * 10^-1
        assertEquals(100.0, GattParse.float32(byteArrayOf(0x01, 0x00, 0x00, 0x02), 0), 1e-9)
        assertTrue(GattParse.float32(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00), 0).isNaN())
        assertTrue(GattParse.float32(byteArrayOf(0x00, 0x00, 0x80.toByte(), 0x00), 0).isNaN())
        assertTrue(GattParse.float32(byteArrayOf(0x01, 0x00, 0x80.toByte(), 0x00), 0).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, GattParse.float32(byteArrayOf(0xFE.toByte(), 0xFF.toByte(), 0x7F, 0x00), 0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, GattParse.float32(byteArrayOf(0x02, 0x00, 0x80.toByte(), 0x00), 0), 0.0)
        assertEquals(120.0, GattParse.sfloat(byteArrayOf(0x78, 0x00), 0), 1e-9)
        assertEquals(37.5, GattParse.sfloat(byteArrayOf(0x77, 0xF1.toByte()), 0), 1e-9)   // 375 * 10^-1
        assertEquals(-2.0, GattParse.sfloat(byteArrayOf(0xFE.toByte(), 0x0F), 0), 1e-9)
        assertEquals(1000.0, GattParse.sfloat(byteArrayOf(0x0A, 0x20), 0), 1e-9)   // 10 * 10^2
        assertTrue(GattParse.sfloat(byteArrayOf(0xFF.toByte(), 0x07), 0).isNaN())
        assertTrue(GattParse.sfloat(byteArrayOf(0x00, 0x08), 0).isNaN())
        assertTrue(GattParse.sfloat(byteArrayOf(0x01, 0x08), 0).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, GattParse.sfloat(byteArrayOf(0xFE.toByte(), 0x07), 0), 0.0)
        assertEquals(Double.NEGATIVE_INFINITY, GattParse.sfloat(byteArrayOf(0x02, 0x08), 0), 0.0)
    }

    private fun p(parser: String, vararg b: Int) = GattParse.parse(parser, ByteArray(b.size) { b[it].toByte() })!!.associate { it.key to it.value }

    @Test fun `the other standard characteristics decode to their units`() {
        assertEquals(36.5, p("health_temperature", 0x00, 0x6D, 0x01, 0x00, 0xFF).getValue("temp_c"), 1e-9)
        assertEquals(37.0, p("health_temperature", 0x01, 0xDA, 0x03, 0x00, 0xFF).getValue("temp_c"), 1e-6)   // 98.6 F
        assertEquals(21.5, p("env_temperature", 0x66, 0x08).getValue("temp_c"), 1e-9)
        assertEquals(-5.0, p("env_temperature", 0x0C, 0xFE).getValue("temp_c"), 1e-9)
        assertEquals(45.5, p("env_humidity", 0xC6, 0x11).getValue("humidity"), 1e-9)
        assertEquals(1013.2, p("env_pressure", 0xD0, 0x75, 0x0F, 0x00).getValue("pressure_hpa"), 1e-9)   // 1013200 units of 0.1 Pa
        assertEquals(70.0, p("weight", 0x00, 0xB0, 0x36).getValue("weight_kg"), 1e-9)
        assertEquals(10000 * 0.01 * 0.45359237, p("weight", 0x01, 0x10, 0x27).getValue("weight_kg"), 1e-9)
        val ox = p("pulse_oximeter", 0x00, 0x61, 0x00, 0x48, 0x00)
        assertEquals(97.0, ox.getValue("spo2"), 1e-9); assertEquals(72.0, ox.getValue("bpm"), 1e-9)
        val bp = p("blood_pressure", 0x00, 0x78, 0x00, 0x50, 0x00, 0x5C, 0x00)
        assertEquals(120.0, bp.getValue("bp_sys"), 1e-9); assertEquals(80.0, bp.getValue("bp_dia"), 1e-9)
        assertEquals(16.0 * 7.50062, p("blood_pressure", 0x01, 0x10, 0x00, 0x0A, 0x00, 0x0D, 0x00).getValue("bp_sys"), 1e-9)
        assertEquals(10.0 * 7.50062, p("blood_pressure", 0x01, 0x10, 0x00, 0x0A, 0x00, 0x0D, 0x00).getValue("bp_dia"), 1e-9)
    }
}
