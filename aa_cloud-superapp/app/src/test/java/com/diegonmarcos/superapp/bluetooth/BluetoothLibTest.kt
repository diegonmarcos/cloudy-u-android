package com.diegonmarcos.superapp.bluetooth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** A scriptable radio: records what the session asked of it and lets the test answer. */
class FakeAdapter : BleAdapter {
    override val supported = true
    override var enabled = true
    override var listener: GattListener? = null
    val calls = ArrayList<String>()
    var scanSink: ((BleDevice) -> Unit)? = null
    var refuseConnect = false

    override fun startScan(onResult: (BleDevice) -> Unit, onFailed: (String) -> Unit): Boolean { scanSink = onResult; calls += "scan"; return true }
    override fun stopScan() { scanSink = null; calls += "stopScan" }
    override fun bonded(): List<BleDevice> = listOf(BleDevice("AA:00", "Paired", -127, bonded = true))
    override fun connect(address: String): Boolean { calls += "connect $address"; return !refuseConnect }
    override fun disconnect(address: String) { calls += "disconnect $address"; listener?.onConnectionState(address, ConnState.Disconnected, 0) }
    override fun discover(address: String): Boolean { calls += "discover"; return true }
    override fun read(address: String, service: String, char: String): Boolean { calls += "read ${Uuids.short(char)}"; return true }
    override fun write(address: String, service: String, char: String, value: ByteArray, withResponse: Boolean): Boolean {
        calls += "write ${Uuids.short(char)} ${Hex.format(value)} ${if (withResponse) "resp" else "noresp"}"; return true
    }
    override fun setNotify(address: String, service: String, char: String, mode: NotifyMode): Boolean { calls += "notify ${Uuids.short(char)} $mode"; return true }
    override fun readDescriptor(address: String, service: String, char: String, desc: String): Boolean { calls += "rdesc"; return true }
    override fun writeDescriptor(address: String, service: String, char: String, desc: String, value: ByteArray): Boolean { calls += "wdesc"; return true }
    override fun requestMtu(address: String, mtu: Int): Boolean { calls += "mtu $mtu"; return true }
    override fun readRssi(address: String): Boolean { calls += "rssi"; return true }
    override fun bond(address: String): Boolean { calls += "bond"; return true }
}

/** #1221 libs:bluetooth: the UUID catalog, the decoders, the filters, the serial GATT session and the controller, over a fake radio. */
class BluetoothLibTest {
    private val catalogFile = File("../../data/fleet/gatt-catalog.json")
    private val catalog by lazy { GattCatalog.parse(catalogFile.readText()) }

    private val HR = Uuids.normalize("180d")!!
    private val HR_CHAR = Uuids.normalize("2a37")!!
    private val BATT = Uuids.normalize("180f")!!
    private val BATT_CHAR = Uuids.normalize("2a19")!!
    private val NUS = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
    private val NUS_RX = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"

    // ---- UUIDs and hex ----

    @Test fun `uuid short forms normalise onto the SIG base and back`() {
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", Uuids.normalize("180D"))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", Uuids.normalize("0x180d"))
        assertEquals("0000180d-0000-1000-8000-00805f9b34fb", Uuids.normalize("0000180d"))
        assertEquals("180d", Uuids.short("0000180d-0000-1000-8000-00805f9b34fb"))
        assertEquals(NUS, Uuids.short(NUS))
        assertEquals(0x180D, Uuids.assigned("180d"))
        assertNull(Uuids.assigned(NUS))
        assertNull(Uuids.normalize("heart_rate"))
        assertNull(Uuids.normalize("12345"))
        assertNull(Uuids.normalize(null))
    }

    @Test fun `hex parses the spellings a dev types and refuses the rest`() {
        assertEquals("AB 00 04 31", Hex.format(Hex.parse("AB 00 04 31")!!))
        assertEquals("AB 00", Hex.format(Hex.parse("0xAB,0x00")!!))
        assertEquals("AB 00 04", Hex.format(Hex.parse("ab0004")!!))
        assertNull(Hex.parse("A"))
        assertNull(Hex.parse("ZZ"))
        assertNull(Hex.parse(""))
    }

    // ---- the catalog is data ----

    @Test fun `the catalog parses and every row is well formed and unique`() {
        assertTrue(catalogFile.exists())
        assertTrue(catalog.services.size >= 15)
        assertEquals(catalog.services.size, catalog.services.map { it.uuid }.toSet().size)
        assertEquals(catalog.characteristics.size, catalog.characteristics.map { it.uuid }.toSet().size)
        catalog.services.forEach { assertTrue("category of ${it.name}", it.category in catalog.categories) }
        assertEquals("Battery", catalog.serviceName(BATT))
        assertEquals("Battery Level", catalog.characteristicName("2a19"))
        assertEquals("Unknown service", catalog.serviceName("1234"))
        assertEquals("Client Characteristic Configuration", catalog.descriptorName("2902"))
    }

    @Test fun `web bluetooth names resolve through the aliases and the short forms`() {
        assertEquals(HR, catalog.resolveService("heart_rate"))
        assertEquals(HR, catalog.resolveService("0x180d"))
        assertEquals(NUS, catalog.resolveService(NUS))
        assertNull(catalog.resolveService("no_such_service"))
        assertEquals(BATT_CHAR, catalog.resolveCharacteristic("battery_level"))
    }

    @Test fun `every decoder the catalog names is implemented`() {
        val samples = mapOf("battery_level" to byteArrayOf(55), "heart_rate" to byteArrayOf(0, 72), "sensor_location" to byteArrayOf(1),
            "temperature_measurement" to byteArrayOf(0, 0x6C, 0x0E, 0x00, 0xFE.toByte()), "temperature_c" to byteArrayOf(0x34, 0x08),
            "humidity" to byteArrayOf(0x10, 0x27), "pressure" to byteArrayOf(0x20, 0x8D.toByte(), 0x01, 0x00),
            "csc" to byteArrayOf(2, 10, 0, 0, 4), "utf8" to "hi".toByteArray(), "uint" to byteArrayOf(0x41, 0x03), "cccd" to byteArrayOf(1, 0))
        val named = (catalog.characteristics.map { it.decoder } + listOf("cccd")).filter { it.isNotEmpty() }.toSet()
        assertTrue(named.isNotEmpty())
        named.forEach { d -> assertNotNull("decoder $d has no sample", samples[d]); assertNotNull("decoder $d is not implemented", GattDecoders.decode(d, samples[d]!!)) }
    }

    // ---- decoders ----

    @Test fun `battery and heart rate decode`() {
        assertEquals("87 %", GattDecoders.decode("battery_level", byteArrayOf(87))!!.text)
        val hr8 = GattDecoders.decode("heart_rate", byteArrayOf(0x00, 72))!!
        assertEquals(72.0, hr8.value!!, 0.0)
        val hr16 = GattDecoders.decode("heart_rate", byteArrayOf(0x01, 0x2C, 0x01))!!
        assertEquals(300.0, hr16.value!!, 0.0)
        // flags: contact supported+detected (6), RR present (0x10); RR 1024 units = 1000 ms
        val rr = GattDecoders.decode("heart_rate", byteArrayOf(0x16, 60, 0x00, 0x04))!!
        assertEquals("1000 ms", rr.fields["rr"])
        assertEquals("contact", rr.fields["contact"])
        assertNull(GattDecoders.decode("heart_rate", byteArrayOf(0x01, 0x2C)))
        assertNull(GattDecoders.decode("battery_level", ByteArray(0)))
    }

    @Test fun `environment and cycling decode`() {
        // mantissa 3700 (0x000E74), exponent -2 -> 37.00 C
        val t = GattDecoders.decode("temperature_measurement", byteArrayOf(0, 0x74, 0x0E, 0x00, 0xFE.toByte()))!!
        assertEquals(37.0, t.value!!, 0.001); assertEquals("C", t.unit)
        assertEquals(21.0, GattDecoders.decode("temperature_c", byteArrayOf(0x34, 0x08))!!.value!!, 0.001)
        assertEquals(50.0, GattDecoders.decode("humidity", byteArrayOf(0x88.toByte(), 0x13))!!.value!!, 0.001)
        val csc = GattDecoders.decode("csc", byteArrayOf(0x02, 0x0A, 0x00, 0x00, 0x04))!!
        assertEquals("10", csc.fields["crank revs"]); assertEquals("1.000 s", csc.fields["crank event"])
    }

    @Test fun `describe falls back to ascii and then hex`() {
        assertEquals("Acme", GattDecoders.describe(catalog, "2a29", "Acme".toByteArray()).text)
        assertEquals("\"OK\"", GattDecoders.describe(catalog, "ffff", "OK".toByteArray()).text)
        assertEquals("01 02", GattDecoders.describe(catalog, "ffff", byteArrayOf(1, 2)).text)
    }

    // ---- filters ----

    private fun dev(name: String?, rssi: Int, vararg services: String, connectable: Boolean = true) =
        BleDevice("00:11:22:33:44:" + "%02X".format(abs(name.hashCode()) % 255), name, rssi, services.map { Uuids.normalize(it)!! }, connectable = connectable)
    private fun abs(i: Int) = if (i < 0) -i else i

    @Test fun `presets, name, signal and switches filter the list, strongest first`() {
        val strap = dev("Polar H10", -60, "180d")
        val ring = dev("PSR 100", -70)
        val anon = dev(null, -50)
        val far = dev("Garmin", -95, "180d")
        val all = listOf(strap, ring, anon, far)
        assertEquals(listOf(anon, strap, ring, far), BleFilters.apply(all, ScanQuery(), catalog))
        assertEquals(listOf(strap, far), BleFilters.apply(all, ScanQuery(filterId = "hrm"), catalog))
        assertEquals(listOf(ring), BleFilters.apply(all, ScanQuery(filterId = "ring"), catalog))
        assertEquals(listOf(strap), BleFilters.apply(all, ScanQuery(filterId = "hrm", minRssi = -80), catalog))
        assertEquals(listOf(strap), BleFilters.apply(all, ScanQuery(nameContains = "polar"), catalog))
        assertFalse(BleFilters.apply(all, ScanQuery(hideUnnamed = true), catalog).contains(anon))
        assertFalse(BleFilters.matches(dev("X", -40, connectable = false), ScanQuery(connectableOnly = true), catalog))
        assertEquals(4, BleFilters.bars(-50)); assertEquals(0, BleFilters.bars(-95))
    }

    @Test fun `web filters match any entry, all fields within an entry`() {
        val d = dev("Polar H10", -60, "180d")
        assertTrue(BleFilters.matchesWeb(d, emptyList()))
        assertTrue(BleFilters.matchesWeb(d, listOf(WebFilter(services = listOf(HR)))))
        assertTrue(BleFilters.matchesWeb(d, listOf(WebFilter(namePrefix = "Nope"), WebFilter(namePrefix = "Polar"))))
        assertFalse(BleFilters.matchesWeb(d, listOf(WebFilter(services = listOf(HR), name = "Other"))))
        assertFalse(BleFilters.matchesWeb(d, listOf(WebFilter(services = listOf(BATT)))))
    }

    // ---- permissions ----

    @Test fun `api 31 asks scan and connect, older asks location`() {
        assertEquals(listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT"), BlePermissions.required(34))
        assertEquals(listOf("android.permission.ACCESS_FINE_LOCATION"), BlePermissions.required(30))
    }

    // ---- session ----

    private class Clock { val timers = ArrayList<Pair<Long, () -> Unit>>() }

    private fun session(a: FakeAdapter = FakeAdapter(), clock: Clock = Clock()) =
        GattSession(a, BleLog(clock = { 0L }), { ms, f -> clock.timers += ms to f })

    private fun connected(s: GattSession, a: FakeAdapter, address: String = "AA:01"): Boolean {
        var ok = false
        s.connect(address) { ok = it.ok }
        a.listener!!.onConnectionState(address, ConnState.Connected, 0)
        return ok
    }

    @Test fun `operations run one at a time in order and the next starts when the last answers`() {
        val a = FakeAdapter(); val s = session(a)
        assertTrue(connected(s, a))
        val got = ArrayList<String>()
        s.read("AA:01", HR, HR_CHAR) { got += "r1:" + Hex.format(it.value) }
        s.read("AA:01", BATT, BATT_CHAR) { got += "r2:" + Hex.format(it.value) }
        assertEquals(listOf("connect AA:01", "read 2a37"), a.calls)          // the second read waits
        a.listener!!.onRead("AA:01", HR, HR_CHAR, byteArrayOf(1), 0)
        assertEquals(listOf("connect AA:01", "read 2a37", "read 2a19"), a.calls)
        a.listener!!.onRead("AA:01", BATT, BATT_CHAR, byteArrayOf(2), 0)
        assertEquals(listOf("r1:01", "r2:02"), got)
    }

    @Test fun `an operation that never answers times out and releases the queue`() {
        val a = FakeAdapter(); val clock = Clock(); val s = session(a, clock)
        connected(s, a)
        var first: OpResult? = null; var second: OpResult? = null
        s.read("AA:01", HR, HR_CHAR) { first = it }
        s.requestMtu("AA:01", 247) { second = it }
        clock.timers.first().second()                                           // the read's timeout fires
        assertFalse(first!!.ok); assertTrue(first!!.error.contains("timed out"))
        assertEquals("mtu 247", a.calls.last())
        a.listener!!.onMtu("AA:01", 247, 0)
        assertEquals(247, second!!.int)
        clock.timers.first().second()                                           // the first timer firing late is a no-op
    }

    @Test fun `operations are refused while not connected and a drop fails what was queued`() {
        val a = FakeAdapter(); val s = session(a)
        var r: OpResult? = null
        s.read("AA:01", HR, HR_CHAR) { r = it }
        assertFalse(r!!.ok); assertEquals("not connected", r!!.error)
        connected(s, a)
        var q: OpResult? = null
        s.read("AA:01", HR, HR_CHAR) { }
        s.read("AA:01", BATT, BATT_CHAR) { q = it }
        a.listener!!.onConnectionState("AA:01", ConnState.Disconnected, 8)
        assertFalse(q!!.ok); assertEquals(ConnState.Disconnected, s.state("AA:01"))
    }

    @Test fun `a refused connect answers failed once`() {
        val a = FakeAdapter().apply { refuseConnect = true }; val s = session(a)
        val answers = ArrayList<Boolean>()
        s.connect("AA:01") { answers += it.ok }
        assertEquals(listOf(false), answers)
        assertEquals(ConnState.Disconnected, s.state("AA:01"))
    }

    @Test fun `writes and notifications reach the log`() {
        val a = FakeAdapter(); val s = session(a)
        connected(s, a)
        s.write("AA:01", NUS, NUS_RX, byteArrayOf(0x41, 0x54), false) { }
        a.listener!!.onWrite("AA:01", NUS, NUS_RX, 0)
        a.listener!!.onChanged("AA:01", HR, HR_CHAR, byteArrayOf(0, 70))
        val text = s.log.export()
        assertTrue(text, text.contains("TX") && text.contains("41 54"))
        assertTrue(text, text.contains("NOTIFY") && text.contains("00 46"))
    }

    // ---- controller ----

    private fun controller(a: FakeAdapter = FakeAdapter()): Pair<BleController, FakeAdapter> {
        val s = session(a)
        return BleController(s, catalog, clock = { 1L }) to a
    }

    private val strapServices = listOf(
        GattService(HR, listOf(GattCharacteristic(HR, HR_CHAR, GattProps.NOTIFY, listOf(GattDescriptor(Uuids.normalize("2902")!!))))),
        GattService(BATT, listOf(GattCharacteristic(BATT, BATT_CHAR, GattProps.READ or GattProps.NOTIFY))),
        GattService(NUS, listOf(GattCharacteristic(NUS, NUS_RX, GattProps.WRITE or GattProps.WRITE_NO_RESPONSE))),
    )

    @Test fun `scan, pick, connect, discover and the telemetry feed`() {
        val (c, a) = controller()
        c.startScan()
        a.scanSink!!(BleDevice("AA:01", "Polar H10", -61, listOf(HR)))
        a.scanSink!!(BleDevice("AA:01", null, -58, listOf(BATT)))                  // a later sighting merges, keeps the name
        assertEquals(1, c.state.value.seen.size)
        assertEquals("Polar H10", c.state.value.seen["AA:01"]!!.name)
        assertEquals(listOf(HR, BATT), c.state.value.seen["AA:01"]!!.serviceUuids)
        assertEquals(-58, c.state.value.seen["AA:01"]!!.rssi)
        c.select("AA:01"); c.connect()
        assertFalse(c.state.value.scanning)
        a.listener!!.onConnectionState("AA:01", ConnState.Connected, 0)
        assertEquals(ConnState.Connected, c.state.value.conn)
        assertTrue(a.calls.contains("discover"))
        a.listener!!.onServices("AA:01", strapServices, 0)
        assertEquals(3, c.state.value.services.size)
        a.listener!!.onChanged("AA:01", HR, HR_CHAR, byteArrayOf(0, 71))
        a.listener!!.onChanged("AA:01", HR, HR_CHAR, byteArrayOf(0, 74))
        assertEquals(listOf(71.0, 74.0), c.state.value.telemetry)
        assertEquals("74 bpm", c.state.value.values["$HR/$HR_CHAR"]!!.decoded!!.text)
        assertTrue(c.state.value.values["$HR/$HR_CHAR"]!!.notified)
    }

    @Test fun `dev writes parse hex, choose the write type and refuse bad input`() {
        val (c, a) = controller()
        c.select("AA:01"); c.connect(); a.listener!!.onConnectionState("AA:01", ConnState.Connected, 0)
        a.listener!!.onServices("AA:01", strapServices, 0)
        a.listener!!.onRssi("AA:01", -60, 0)                                   // the connect flow's RSSI read
        val rx = strapServices[2].characteristics[0]
        c.writeHex(rx, "ZZ", true)
        assertTrue(c.state.value.message.startsWith("Not a hex"))
        c.writeHex(rx, "AB 00 04 31", true)
        assertTrue(a.calls.toString(), a.calls.contains("write $NUS_RX AB 00 04 31 resp"))
    }

    @Test fun `notify toggles are remembered and cleared on disconnect`() {
        val (c, a) = controller()
        c.select("AA:01"); c.connect(); a.listener!!.onConnectionState("AA:01", ConnState.Connected, 0)
        a.listener!!.onServices("AA:01", strapServices, 0)
        a.listener!!.onRssi("AA:01", -60, 0)                                   // the connect flow's RSSI read
        val hr = strapServices[0].characteristics[0]
        c.setNotify(hr, NotifyMode.Notify)
        a.listener!!.onNotifySet("AA:01", HR, HR_CHAR, NotifyMode.Notify, 0)
        assertEquals(NotifyMode.Notify, c.state.value.notifying[hr.key])
        a.listener!!.onConnectionState("AA:01", ConnState.Disconnected, 0)
        assertTrue(c.state.value.notifying.isEmpty()); assertTrue(c.state.value.services.isEmpty())
    }

    @Test fun `the device type comes from the catalog families`() {
        assertEquals("hrm", catalog.deviceCategory(listOf(BATT, HR)))
        assertEquals("ring", catalog.deviceCategory(listOf(HR, "4cdabaa0-2cea-c0c1-b38d-a0481ae60a97")))
        assertEquals("info", catalog.deviceCategory(listOf(BATT)))
        assertEquals("unknown", catalog.deviceCategory(listOf("ffff")))
    }
}
