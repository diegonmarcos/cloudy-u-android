package com.diegonmarcos.cloudtoolssmallllms.core

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The exhaustive Inbuilt sensor catalogue (#1538) on its data: every category has its full member list, each member resolves present or absent
 * against a fake SensorManager / PackageManager, nothing a vendor reports is missed, and the List-all filters. The expected member lists below ARE the
 * contract: a member removed from the data fails here.
 */
class SensorTreeTest {
    private val json = File("../../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors.json").readText()
    private val cat = SensorCatalogue.parse(json)
    private val tree = cat.tree

    private val expected = linkedMapOf(
        "motion" to listOf("accelerometer", "accelerometer_uncalibrated", "accelerometer_limited_axes", "accelerometer_limited_axes_uncalibrated", "gyroscope", "gyroscope_uncalibrated",
            "gyroscope_limited_axes", "gyroscope_limited_axes_uncalibrated", "gravity", "linear_acceleration", "rotation_vector", "game_rotation_vector", "geomagnetic_rotation_vector",
            "significant_motion", "step_detector", "step_counter", "stationary_detect", "motion_detect", "tilt_detector", "wake_gesture", "pick_up_gesture", "glance_gesture", "wrist_tilt",
            "double_tap", "pose_6dof", "head_tracker", "heading", "device_orientation"),
        "position" to listOf("magnetometer", "magnetometer_uncalibrated", "orientation", "proximity", "hinge_angle", "hall", "grip", "hover"),
        "environment" to listOf("ambient_light", "barometer", "ambient_temperature", "relative_humidity", "device_temperature", "thermistors", "thermal_status", "flicker", "spectral"),
        "location" to listOf("gps", "glonass", "galileo", "beidou", "qzss", "navic", "dual_frequency", "fused_location", "network_location"),
        "imaging" to listOf("camera_wide", "camera_ultrawide", "camera_tele", "camera_front", "cameras_all", "tof_depth", "lidar", "laser_autofocus", "pdaf", "autofocus", "color_sensor"),
        "connectivity" to listOf("nfc", "bluetooth", "ble", "wifi", "wifi_rtt", "uwb", "ir_blaster", "cellular"),
        "audio" to listOf("microphones"),
        "biometric" to listOf("fingerprint", "fingerprint_optical", "fingerprint_ultrasonic", "fingerprint_capacitive", "face", "face_3d", "face_tof", "iris", "biometric_status",
            "heart_rate", "heart_beat", "spo2", "skin_thermometer", "body_thermometer", "off_body", "low_latency_offbody"),
        "hardware" to listOf("battery_voltage", "battery_current", "battery_charge_counter", "battery_capacity", "battery_temperature", "battery_health", "touch_multitouch", "touch_distinct", "touch_force"),
    )

    private fun sensor(id: Int, type: Int, name: String = "s$id", stringType: String = "", wake: Boolean = false, mode: Int = 0, vendor: String = "Acme", power: Float = 0.25f) =
        SensorRaw(id, name, vendor, type, stringType, 3, power, 0.01f, 8f, 5000, 200000, mode, wake, 100, false)

    private class Facts(val features: Set<String> = emptySet(), val probes: Map<String, ProbeResult> = emptyMap()) : DeviceFacts {
        override fun feature(name: String) = name in features
        override fun probe(id: String) = probes[id] ?: ProbeResult(false)
    }

    private fun rowsOf(vararg s: SensorRaw) = cat.rows(SensorSource { s.toList() })
    private fun entry(r: List<ResolvedCategory>, id: String) = r.flatMap { it.entries }.first { it.entry.id == id }

    @Test fun `every category has its full member list, in order, and nothing else`() {
        val have = tree.categories.associate { it.id to it.entries.map { e -> e.id } }
        assertEquals(expected.keys.toList(), have.keys.toList())
        for ((c, members) in expected) assertEquals("category $c", members, have.getValue(c))
        assertEquals(expected.values.sumOf { it.size }, tree.categories.sumOf { it.entries.size })
        for (c in tree.categories) { assertTrue(c.label.isNotBlank()); for (e in c.entries) assertTrue("${e.id} has a label", e.label.isNotBlank()) }
    }

    @Test fun `every standard Android sensor type is claimed by a member, except the two meta types`() {
        val root = JSONObject(json)
        val ids = (0 until root.getJSONArray("types").length()).map { root.getJSONArray("types").getJSONObject(it).getInt("id") }
        val claimed = tree.categories.flatMap { it.entries }.mapNotNull { it.type }.toSet()
        for (t in ids) if (t != 32 && t != 33) assertTrue("type $t is claimed by no member", t in claimed)
        for (t in claimed) assertTrue("member type $t is not in the types table", t in ids)
    }

    @Test fun `members resolve present or absent against a fake SensorManager and PackageManager`() {
        val rows = rowsOf(sensor(1, 1), sensor(2, 4), sensor(3, 8), sensor(4, 8, wake = true), sensor(5, 65537, "OEM Hall Sensor", "com.oem.hall"))
        val facts = Facts(setOf("android.hardware.nfc", "android.hardware.location.gps"), mapOf(
            "cameras" to ProbeResult(true, listOf("Camera 0 · back", "Camera 1 · front")), "battery:voltage" to ProbeResult(true, listOf("4100 mV")), "camera_role:front" to ProbeResult(true)))
        val r = tree.resolve(rows, facts)
        assertTrue(entry(r, "accelerometer").present); assertTrue(entry(r, "gyroscope").present)
        assertFalse(entry(r, "gyroscope_uncalibrated").present); assertFalse(entry(r, "magnetometer").present)
        assertEquals("both the wake-up and the non-wake-up proximity answer the member", 2, entry(r, "proximity").sensors.size)
        assertTrue("a vendor sensor answers a name match", entry(r, "hall").present)
        assertTrue(entry(r, "nfc").present); assertFalse(entry(r, "uwb").present)
        assertTrue(entry(r, "gps").present); assertTrue("a constellation follows the receiver", entry(r, "galileo").present); assertTrue(entry(r, "galileo").entry.liveOnly)
        assertEquals(listOf("Camera 0 · back", "Camera 1 · front"), entry(r, "cameras_all").lines)
        assertTrue(entry(r, "camera_front").present); assertFalse(entry(r, "camera_tele").present)
        assertTrue(entry(r, "battery_voltage").present); assertFalse(entry(r, "battery_current").present)
        assertTrue(entry(r, "orientation").entry.deprecated); assertEquals("derived", entry(r, "gravity").entry.kind)
    }

    @Test fun `a phone with nothing reports every member absent and still returns the whole tree`() {
        val r = tree.resolve(emptyList(), Facts())
        assertEquals(expected.keys.toList() + "other", r.map { it.id })
        assertTrue(r.flatMap { it.entries }.none { it.present })
        assertEquals(expected.values.sumOf { it.size }, r.dropLast(1).sumOf { it.entries.size })
        assertTrue(r.last().entries.isEmpty())
    }

    @Test fun `a sensor no member claims is listed under other, a vendor private type is enumerated even when a member claims it`() {
        val rows = rowsOf(sensor(1, 1), sensor(7, 65540, "Vendor Magic", "com.vendor.magic"), sensor(8, 65537, "OEM Hall Sensor", "com.oem.hall"), sensor(9, 33, "Additional info"))
        val r = tree.resolve(rows, Facts())
        val other = r.last().entries.map { it.entry.label }
        assertTrue("Vendor Magic" in other); assertTrue("Additional info" in other)
        assertFalse("a claimed sensor is not duplicated under other", "OEM Hall Sensor" in other)
        assertEquals(listOf("OEM Hall Sensor", "Vendor Magic"), tree.vendorSensors(rows).map { it.raw.name }.sorted())
        assertEquals("TYPE_DEVICE_PRIVATE_BASE + 4", rows.first { it.raw.id == 7 }.typeConst)
        assertTrue(r.last().entries.all { it.present && it.sensors.size == 1 })
    }

    @Test fun `derived types are classified as virtual sensors and the rest as hardware`() {
        val rows = rowsOf(sensor(1, 1), sensor(2, 9), sensor(3, 11), sensor(4, 3), sensor(5, 42))
        assertEquals(listOf("hardware", "derived", "derived", "derived", "derived"), rows.sortedBy { it.raw.id }.map { tree.kindOf(it) })
    }

    @Test fun `the List-all filters narrow by category, presence, type, wake-up, reporting mode, vendor, power and text`() {
        val rows = rowsOf(
            sensor(1, 1, vendor = "Acme", power = 0.2f), sensor(2, 4, vendor = "Acme", power = 3f), sensor(3, 8, wake = true, mode = 1, vendor = "Bosch", power = 0.1f),
            sensor(4, 9, vendor = "Google", power = 0.3f), sensor(5, 19, mode = 1, vendor = "Bosch", power = 0.05f),
        )
        val r = tree.resolve(rows, Facts(setOf("android.hardware.nfc")))
        val all = tree.filter(r, SensorFilter())
        assertEquals(expected.values.sumOf { it.size } + 0, all.size)
        val present = tree.filter(r, SensorFilter(presence = Presence.PRESENT)).map { it.entry.id }.toSet()
        assertEquals(setOf("accelerometer", "gyroscope", "proximity", "gravity", "step_counter", "nfc"), present)
        assertEquals(all.size - present.size, tree.filter(r, SensorFilter(presence = Presence.ABSENT)).size)
        assertEquals(expected.getValue("motion").size, tree.filter(r, SensorFilter(category = "motion")).size)
        assertEquals(setOf("gravity"), tree.filter(r, SensorFilter(presence = Presence.PRESENT, kind = "derived")).map { it.entry.id }.toSet())
        assertEquals(setOf("nfc"), tree.filter(r, SensorFilter(presence = Presence.PRESENT, kind = "feature")).map { it.entry.id }.toSet())
        assertEquals(setOf("proximity"), tree.filter(r, SensorFilter(wake = true)).map { it.entry.id }.toSet())
        assertEquals(setOf("proximity", "step_counter"), tree.filter(r, SensorFilter(reporting = 1)).map { it.entry.id }.toSet())
        assertEquals(setOf("proximity", "step_counter"), tree.filter(r, SensorFilter(vendor = "bosch")).map { it.entry.id }.toSet())
        assertEquals(setOf("accelerometer", "proximity", "gravity", "step_counter"), tree.filter(r, SensorFilter(maxPowerMa = 0.5f)).map { it.entry.id }.toSet())
        assertEquals(setOf("gyroscope", "gyroscope_uncalibrated", "gyroscope_limited_axes", "gyroscope_limited_axes_uncalibrated"), tree.filter(r, SensorFilter(text = "gyro")).map { it.entry.id }.filter { it.startsWith("gyro") }.toSet())
        assertEquals(listOf("Acme", "Bosch", "Google"), tree.vendors(rows))
    }

    @Test fun `cameras are split into wide, ultra-wide, tele, front and depth by their equivalent focal length`() {
        val sensorW = 7.0f; val sensorH = 5.2f   // about a 1/1.3 inch sensor
        fun back(focal: Float) = CameraFacts(false, focal, sensorW, sensorH, false)
        val roles = CameraRoles.roles(listOf(back(6.2f), back(2.2f), back(15f), CameraFacts(true, 2.7f, 4f, 3f, false), CameraFacts(false, 0f, 0f, 0f, true)), tree.cameraUltraMax, tree.cameraWideMax)
        assertEquals(setOf("wide", "ultrawide", "tele", "front", "depth"), roles)
        assertEquals(setOf("front"), CameraRoles.roles(listOf(CameraFacts(true, 2.7f, 4f, 3f, false)), tree.cameraUltraMax, tree.cameraWideMax))
        assertTrue(CameraRoles.roles(emptyList(), 20f, 45f).isEmpty())
    }

    @Test fun `a catalogue member that cannot be found, a duplicate id or an unknown kind is an error`() {
        fun parse(entry: String) = SensorTree.parse(JSONObject(json).also {
            it.put("catalogue", JSONObject("""{"categories":[{"id":"c","label":"C","entries":[$entry]}],"other":{"id":"o","label":"O"},"camera_roles":{"ultrawide_max_mm":20,"wide_max_mm":45}}"""))
        }, 65536)
        assertEquals(1, parse("""{"id":"a","label":"A","kind":"hardware","type":1}""").categories.single().entries.size)
        for (bad in listOf("""{"id":"a","label":"A","kind":"hardware"}""", """{"id":"a","label":"A","kind":"magic","type":1}""",
            """{"id":"a","label":"A","kind":"hardware","type":1},{"id":"a","label":"B","kind":"hardware","type":2}""")) {
            try { parse(bad); org.junit.Assert.fail("accepted: $bad") } catch (_: IllegalArgumentException) { }
        }
    }
}
