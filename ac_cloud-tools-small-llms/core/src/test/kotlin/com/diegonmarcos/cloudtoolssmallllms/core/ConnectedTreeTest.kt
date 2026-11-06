package com.diegonmarcos.cloudtoolssmallllms.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The Connected (external) sensor catalogue (#1538): every category has its full member list, each class resolves connected / paired / available / absent against fake managers, and the filters. */
class ConnectedTreeTest {
    private val cat = SensorCatalogue.parse(File("../../data/fleet/app/com.diegonmarcos.cloudtoolssmallllms/tools/sensors.json").readText())
    private val c = cat.connected
    private fun u(short: String) = Uuid.normalize(short)!!

    private val expected = linkedMapOf(
        "wearables_health" to listOf("hrs", "bps", "gls", "hts", "wss", "plx", "cgm", "body_comp", "ecg_strap", "resp_belt", "ring", "watch_hs", "health_connect"),
        "fitness_motion" to listOf("csc", "cp", "rsc", "imu", "ftms", "ant_hr", "ant_power", "ant_sc", "jump_rope", "racket_golf"),
        "environment_iot" to listOf("ess", "airq", "soil", "thermo_hygro", "co2", "pm25", "voc", "geiger", "uv", "water_leak", "smart_home", "weather_station"),
        "positioning" to listOf("beacon", "gps_usb", "tag", "rtk", "uwb_tag", "wifi_rtt_ap", "finder", "classic_gps"),
        "input_peripheral" to listOf("gamepad", "stylus", "keyboard", "card_reader", "barcode", "rfid"),
        "audio_imaging" to listOf("thermal_cam", "microscope", "webcam", "usb_mic", "endoscope", "depth_ext", "thermal_ble"),
        "automotive_other" to listOf("scope", "serial", "obd2", "drone", "multimeter", "smart_plug", "obd2_classic"),
    )

    private class Facts(
        val bonded: Set<String> = emptySet(), val connected: Set<String> = emptySet(), val scanned: Set<String> = emptySet(), val mfr: Set<Int> = emptySet(),
        val usb: Set<Int> = emptySet(), val features: Set<String> = emptySet(), val packages: Set<String> = emptySet(), val inputs: Set<String> = emptySet(),
    ) : ConnectedFacts {
        override fun bondedServiceUuids() = bonded
        override fun connectedServiceUuids() = connected
        override fun scannedServiceUuids() = scanned
        override fun scannedManufacturerIds() = mfr
        override fun usbClasses() = usb
        override fun feature(name: String) = name in features
        override fun packageInstalled(name: String) = name in packages
        override fun input(kind: String) = kind in inputs
    }

    private fun state(r: List<ResolvedProfile>, id: String) = r.first { it.profile.id == id }.state

    @Test fun `every connected category has its full member list, in order, and every transport is declared`() {
        assertEquals(expected.keys.toList(), c.categories.map { it.first })
        val grouped = ConnectedTree.grouped(c, ConnectedTree.resolve(c, Facts()))
        for ((cat, members) in grouped) assertEquals("category ${cat.first}", expected.getValue(cat.first), members.map { it.profile.id })
        assertEquals(63, c.profiles.size)
        assertTrue("at least 20 added to the original 20", c.profiles.size - 20 >= 20)
        val known = setOf("ble", "classic", "usb", "uwb", "ant", "wifi", "matter", "health_services")
        assertTrue(ConnectedTree.transports(c).all { it in known })
        assertEquals(known, ConnectedTree.transports(c).toSet())
    }

    @Test fun `a profile is connected, paired, available or absent by what the phone sees`() {
        val f = Facts(
            connected = setOf(u("180D")), bonded = setOf(u("1810")), scanned = setOf(u("1822")), mfr = setOf(76), usb = setOf(14),
            features = setOf("android.hardware.uwb"), packages = setOf("com.dsi.ant.service.socket"), inputs = setOf("stylus"),
        )
        val r = ConnectedTree.resolve(c, f)
        assertEquals(ConnState.CONNECTED, state(r, "hrs")); assertEquals(ConnState.CONNECTED, state(r, "resp_belt"))
        assertEquals(ConnState.PAIRED, state(r, "bps"))
        assertEquals(ConnState.AVAILABLE, state(r, "plx")); assertEquals(ConnState.AVAILABLE, state(r, "ring"))
        assertEquals("a manufacturer-data match is available", ConnState.AVAILABLE, state(r, "beacon"))
        assertEquals(ConnState.CONNECTED, state(r, "thermal_cam")); assertEquals(ConnState.CONNECTED, state(r, "webcam"))
        assertEquals(ConnState.AVAILABLE, state(r, "uwb_tag")); assertEquals(ConnState.AVAILABLE, state(r, "ant_hr"))
        assertEquals(ConnState.CONNECTED, state(r, "stylus")); assertEquals(ConnState.ABSENT, state(r, "gamepad"))
        assertEquals(ConnState.ABSENT, state(r, "cgm")); assertEquals(ConnState.ABSENT, state(r, "wifi_rtt_ap")); assertEquals(ConnState.ABSENT, state(r, "smart_home"))
    }

    @Test fun `a phone that sees nothing reports every class absent`() {
        assertTrue(ConnectedTree.resolve(c, Facts()).all { it.state == ConnState.ABSENT })
    }

    @Test fun `the best state wins - connected over paired over available`() {
        val f = Facts(connected = setOf(u("1822")), bonded = setOf(u("1822")), scanned = setOf(u("1822")))
        assertEquals(ConnState.CONNECTED, state(ConnectedTree.resolve(c, f), "plx"))
        assertEquals(ConnState.PAIRED, state(ConnectedTree.resolve(c, Facts(bonded = setOf(u("1822")), scanned = setOf(u("1822")))), "plx"))
    }

    @Test fun `the filters narrow by category, transport, presence and text`() {
        val r = ConnectedTree.resolve(c, Facts(connected = setOf(u("180D")), usb = setOf(14), features = setOf("android.hardware.uwb")))
        assertEquals(expected.getValue("positioning").size, ConnectedTree.filter(r, ConnectedFilter(category = "positioning")).size)
        assertEquals(c.profiles.count { "usb" in it.transport }, ConnectedTree.filter(r, ConnectedFilter(transport = "usb")).size)
        assertEquals(c.profiles.count { "ant" in it.transport }, ConnectedTree.filter(r, ConnectedFilter(transport = "ant")).size)
        assertEquals(setOf("hrs", "resp_belt", "thermal_cam", "microscope", "webcam", "endoscope", "uwb_tag"), ConnectedTree.filter(r, ConnectedFilter(present = Presence.PRESENT)).map { it.profile.id }.toSet().let { it })
        assertEquals(c.profiles.size - 7, ConnectedTree.filter(r, ConnectedFilter(present = Presence.ABSENT)).size)
        assertEquals(setOf("webcam"), ConnectedTree.filter(r, ConnectedFilter(text = "UVC webcam")).map { it.profile.id }.toSet())
        assertEquals(setOf("uwb_tag"), ConnectedTree.filter(r, ConnectedFilter(transport = "uwb", present = Presence.PRESENT)).map { it.profile.id }.toSet())
    }
}
