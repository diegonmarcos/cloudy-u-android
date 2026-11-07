package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.browser.CdcAcmSerial
import com.diegonmarcos.superapp.browser.HwChoiceRequest
import com.diegonmarcos.superapp.browser.HwUsbAlt
import com.diegonmarcos.superapp.browser.HwUsbConfig
import com.diegonmarcos.superapp.browser.HwUsbDevice
import com.diegonmarcos.superapp.browser.HwUsbEndpoint
import com.diegonmarcos.superapp.browser.HwUsbInterface
import com.diegonmarcos.superapp.browser.HwUsbListener
import com.diegonmarcos.superapp.browser.NdefCodec
import com.diegonmarcos.superapp.browser.NdefRec
import com.diegonmarcos.superapp.browser.NfcBackend
import com.diegonmarcos.superapp.browser.NfcListener
import com.diegonmarcos.superapp.browser.NfcWriteResult
import com.diegonmarcos.superapp.browser.RawNdef
import com.diegonmarcos.superapp.browser.UsbBackend
import com.diegonmarcos.superapp.browser.UsbTransfer
import com.diegonmarcos.superapp.browser.WebBleGrants
import com.diegonmarcos.superapp.browser.WebHardwareCore
import com.diegonmarcos.superapp.browser.WebHwChooser
import com.diegonmarcos.superapp.browser.WebHwGrants
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }

/**
 * #1225 the native half of Web Serial, WebUSB and Web NFC, over fake backends (a USB host with scripted devices and transfers, an NFC
 * radio, a chooser that picks what the test says) and the real CdcAcmSerial; replies are held to web-hardware-contract.json.
 * The fakes are test doubles for the radio and the cable only: the product code carries no sample devices.
 */
class WebHardwareCoreTest {
    private fun ep(n: Int, dirIn: Boolean, type: String = "bulk", size: Int = 64) = HwUsbEndpoint(n, dirIn, type, size)

    // An Arduino-style CDC device: control interface 0 (ACM, interrupt IN), data interface 1 (bulk IN 1, bulk OUT 2).
    private val ARDUINO = HwUsbDevice("2341:0043", 0x2341, 0x0043, 0x02, 0, 0, "Arduino", "Arduino Uno R4", "SN123", listOf(HwUsbConfig(1, listOf(
        HwUsbInterface(0, listOf(HwUsbAlt(0, 0x02, 0x02, 0x01, listOf(ep(3, true, "interrupt", 16))))),
        HwUsbInterface(1, listOf(HwUsbAlt(0, 0x0A, 0, 0, listOf(ep(1, true), ep(2, false)))))))))
    // A vendor-specific board: interface 0 with two alternate settings.
    private val VENDOR = HwUsbDevice("1209:abcd", 0x1209, 0xabcd, 0xFF, 0, 0, null, "Dev Board", null, listOf(HwUsbConfig(1, listOf(
        HwUsbInterface(0, listOf(HwUsbAlt(0, 0xFF, 0, 0, listOf(ep(1, true), ep(1, false))), HwUsbAlt(1, 0xFF, 0, 0, listOf(ep(2, true)))))))))
    // A keyboard: every interface is HID, a protected class.
    private val KEYBOARD = HwUsbDevice("046d:c31c", 0x046d, 0xc31c, 0, 0, 0, "Logi", "Keyboard", null, listOf(HwUsbConfig(1, listOf(
        HwUsbInterface(0, listOf(HwUsbAlt(0, 0x03, 1, 1, listOf(ep(1, true, "interrupt", 8)))))))))
    // A composite device with a HID interface next to a vendor one: offered, but the HID interface cannot be claimed.
    private val COMPOSITE = HwUsbDevice("0483:5740", 0x0483, 0x5740, 0, 0, 0, "ST", "Composite", null, listOf(HwUsbConfig(1, listOf(
        HwUsbInterface(0, listOf(HwUsbAlt(0, 0x03, 0, 0, listOf(ep(1, true, "interrupt", 8))))),
        HwUsbInterface(1, listOf(HwUsbAlt(0, 0xFF, 0, 0, listOf(ep(2, true), ep(3, false)))))))))

    private class FakeUsb : UsbBackend {
        override var supported = true
        val devs = ArrayList<HwUsbDevice>()
        val observers = ArrayList<HwUsbListener>()
        val calls = ArrayList<String>()
        val permitted = HashSet<String>()
        var grantOnRequest = true
        var asked = 0
        val bulkInQueue = ArrayDeque<UsbTransfer>()
        var controlIn = UsbTransfer("ok", byteArrayOf(0x12, 0x01))
        var failTransfers = false
        override fun observe(l: HwUsbListener) { observers += l }
        override fun unobserve(l: HwUsbListener) { observers -= l }
        override fun attached(): List<HwUsbDevice> = devs.toList()
        override fun hasPermission(key: String) = key in permitted
        override fun requestPermission(key: String, done: (Boolean) -> Unit) { asked++; if (grantOnRequest) permitted += key; done(grantOnRequest) }
        override fun open(key: String): Boolean { calls += "open"; return key in permitted }
        override fun close(key: String) { calls += "close" }
        override fun selectConfiguration(key: String, value: Int): Boolean { calls += "config $value"; return true }
        override fun claim(key: String, number: Int): Boolean { calls += "claim $number"; return true }
        override fun release(key: String, number: Int): Boolean { calls += "release $number"; return true }
        override fun setAlternate(key: String, number: Int, setting: Int): Boolean { calls += "alt $number $setting"; return true }
        override fun control(key: String, requestType: Int, request: Int, value: Int, index: Int, data: ByteArray?, length: Int): UsbTransfer {
            calls += "control %02x %02x %d %d %s".format(requestType, request, value, index, hex(data ?: ByteArray(0))).trimEnd()
            if (failTransfers) return UsbTransfer("error")
            return if (requestType and 0x80 != 0) controlIn else UsbTransfer("ok", written = length)
        }
        override fun bulkIn(key: String, endpoint: Int, length: Int, timeoutMs: Int): UsbTransfer {
            if (failTransfers) return UsbTransfer("error")
            return bulkInQueue.removeFirstOrNull() ?: UsbTransfer("ok")
        }
        override fun bulkOut(key: String, endpoint: Int, data: ByteArray, timeoutMs: Int): UsbTransfer {
            calls += "out $endpoint ${hex(data)}"
            return if (failTransfers) UsbTransfer("error") else UsbTransfer("ok", written = data.size)
        }
        override fun reset(key: String) = false
        fun plug(d: HwUsbDevice) { devs += d; observers.toList().forEach { it.attached(d) } }
        fun unplug(key: String) { devs.removeAll { it.key == key }; observers.toList().forEach { it.detached(key) } }
    }

    private class FakeNfc : NfcBackend {
        override var supported = true
        override var enabled = true
        var startOk = true
        var listener: NfcListener? = null
        var writeDone: ((NfcWriteResult) -> Unit)? = null
        var written: List<NdefRec>? = null
        var overwrite: Boolean? = null
        var cancelled = 0
        override fun start(l: NfcListener): Boolean { if (!startOk) return false; listener = l; return true }
        override fun stop(l: NfcListener) { if (listener === l) listener = null }
        override fun writeNext(records: List<NdefRec>, overwrite: Boolean, done: (NfcWriteResult) -> Unit) { written = records; this.overwrite = overwrite; writeDone = done }
        override fun cancelWrite() { cancelled++; writeDone = null }
    }

    private class MemKv : WebBleGrants.KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    private class FakeChooser : WebHwChooser {
        var pickKey: (HwChoiceRequest) -> String? = { it.items.firstOrNull()?.key }
        var nfcAllow = true
        val shown = ArrayList<List<String>>()
        var nfcPrompts = 0
        var tapShown = 0
        var tapDismissed = 0
        var tapCancel: (() -> Unit)? = null
        override fun pick(request: HwChoiceRequest, onPick: (String) -> Unit, onCancel: () -> Unit) {
            shown += request.items.map { it.key }
            val k = pickKey(request)
            if (k == null) onCancel() else onPick(k)
        }
        override fun allowNfc(origin: String, writing: Boolean, onAllow: () -> Unit, onDeny: () -> Unit) { nfcPrompts++; if (nfcAllow) onAllow() else onDeny() }
        override fun tapTag(origin: String, onCancel: () -> Unit): () -> Unit { tapShown++; tapCancel = onCancel; return { tapDismissed++ } }
    }

    private val ORIGIN = "https://lab.example.test"
    private val contract = JSONObject(File("../../ab_cloud-libs-shared/libs/browser/contract/web-hardware-contract.json").readText())

    private lateinit var usb: FakeUsb
    private lateinit var serial: CdcAcmSerial
    private lateinit var nfc: FakeNfc
    private lateinit var chooser: FakeChooser
    private lateinit var serialGrants: WebHwGrants
    private lateinit var usbGrants: WebHwGrants
    private lateinit var nfcGrants: WebHwGrants
    private lateinit var core: WebHardwareCore
    private val sent = ArrayList<JSONObject>()
    private var id = 0

    @Before fun setUp() {
        usb = FakeUsb(); usb.devs += listOf(ARDUINO, VENDOR, KEYBOARD, COMPOSITE)
        serial = CdcAcmSerial(usb) { /* the read loop is driven by the test through pump() */ }
        nfc = FakeNfc(); chooser = FakeChooser()
        serialGrants = WebHwGrants(MemKv(), "serial"); usbGrants = WebHwGrants(MemKv(), "usb"); nfcGrants = WebHwGrants(MemKv(), "nfc")
        sent.clear(); id = 0
        core = WebHardwareCore(usb, serial, nfc, serialGrants, usbGrants, nfcGrants, chooser,
            object : WebHardwareCore.UsbPermission { override fun ensure(key: String, done: (Boolean) -> Unit) = usb.requestPermission(key, done) },
            { it() }) { sent += JSONObject(it) }
    }

    // ---- helpers ----

    private fun call(op: String, args: JSONObject = JSONObject(), gesture: Boolean? = null, origin: String = ORIGIN): JSONObject? {
        val n = ++id
        val m = JSONObject().put("v", 1).put("id", n).put("op", op).put("args", args)
        if (gesture != null) m.put("gesture", gesture)
        val before = sent.size
        core.handle(origin, m.toString())
        return sent.drop(before).firstOrNull { it.optInt("id", -1) == n }
    }

    private fun ok(op: String, r: JSONObject?): JSONObject {
        assertTrue(r.toString(), r != null && r.getBoolean("ok"))
        val result = r!!.getJSONObject("result")
        val declared = contract.getJSONObject("ops").getJSONObject(op).getJSONObject("result")
        val all = declared.keys().asSequence().map { it.removeSuffix("?") }.toSet()
        val required = declared.keys().asSequence().filter { !it.endsWith("?") }.toSet()
        val actual = result.keys().asSequence().toSet()
        assertTrue("$op result $actual lacks ${required - actual}", actual.containsAll(required))
        assertTrue("$op result $actual has fields outside the contract $all", all.containsAll(actual))
        return result
    }

    private fun errName(r: JSONObject?): String { assertTrue(r.toString(), r != null && !r.getBoolean("ok")); return r!!.getJSONObject("error").getString("name") }
    private fun events(name: String) = sent.filter { it.optString("event") == name }
    private fun obj(vararg p: Pair<String, Any>) = JSONObject().also { o -> p.forEach { o.put(it.first, it.second) } }

    private fun portId(): String {
        chooser.pickKey = { "2341:0043" }
        return ok("serial.requestPort", call("serial.requestPort", gesture = true)).getJSONObject("port").getString("id")
    }

    private fun usbId(key: String): String {
        chooser.pickKey = { key }
        return ok("usb.requestDevice", call("usb.requestDevice", obj("filters" to JSONArray()), gesture = true)).getJSONObject("device").getString("id")
    }

    private fun openSerial(p: String, baud: Int = 115200) =
        ok("serial.open", call("serial.open", obj("port" to p, "baudRate" to baud, "dataBits" to 8, "stopBits" to 1, "parity" to "none", "flowControl" to "none")))

    private fun openUsb(key: String): String {
        val d = usbId(key); ok("usb.open", call("usb.open", obj("device" to d))); return d
    }

    // ---- origin and gesture rules, shared by the three APIs ----

    @Test fun `an insecure origin gets SecurityError for serial, usb and nfc`() {
        for (op in listOf("serial.getPorts", "usb.getDevices", "nfc.scan")) {
            assertEquals(op, "SecurityError", errName(call(op, origin = "http://example.com")))
            assertEquals(op, "SecurityError", errName(call(op, origin = "")))
        }
        assertEquals(0, chooser.shown.size)
    }

    @Test fun `every op of the contract is handled by the core`() {
        for (op in contract.getJSONObject("ops").keys()) {
            val r = call(op, gesture = true)
            assertNotNull(op, r)
            val msg = r!!.optJSONObject("error")?.optString("message") ?: ""
            assertFalse("$op is not handled", msg.startsWith("Unknown operation"))
        }
    }

    @Test fun `requestPort and requestDevice need a user gesture and never show a chooser without one`() {
        assertEquals("SecurityError", errName(call("serial.requestPort", gesture = false)))
        assertEquals("SecurityError", errName(call("serial.requestPort")))
        assertEquals("SecurityError", errName(call("usb.requestDevice", obj("filters" to JSONArray()))))
        assertEquals(0, chooser.shown.size); assertEquals(0, usb.asked)
    }

    @Test fun `a request is for a bridge version the core knows, anything else is dropped`() {
        core.handle(ORIGIN, JSONObject().put("v", 2).put("id", 1).put("op", "serial.getPorts").toString())
        core.handle(ORIGIN, "not json")
        assertTrue(sent.isEmpty())
    }

    // ---- Web Serial ----

    @Test fun `the serial chooser lists only CDC ports and a filter narrows it`() {
        chooser.pickKey = { null }
        assertEquals("NotFoundError", errName(call("serial.requestPort", gesture = true)))
        assertEquals(listOf(listOf("2341:0043")), chooser.shown)
        chooser.shown.clear()
        val filters = JSONArray().put(obj("usbVendorId" to 0x1209))
        assertEquals("NotFoundError", errName(call("serial.requestPort", obj("filters" to filters), gesture = true)))
        assertEquals(listOf(emptyList<String>()), chooser.shown)
        assertEquals("TypeError", errName(call("serial.requestPort", obj("filters" to JSONArray().put(obj("usbProductId" to 1))), gesture = true)))
    }

    @Test fun `a picked port becomes an origin grant after Android's USB permission, a denial grants nothing`() {
        usb.grantOnRequest = false
        chooser.pickKey = { "2341:0043" }
        assertEquals("NotAllowedError", errName(call("serial.requestPort", gesture = true)))
        assertTrue(serialGrants.forOrigin(ORIGIN).isEmpty())
        usb.grantOnRequest = true
        val p = portId()
        val info = ok("serial.getPorts", call("serial.getPorts")).getJSONArray("ports")
        assertEquals(1, info.length()); assertEquals(p, info.getJSONObject(0).getString("id"))
        assertEquals(0x2341, info.getJSONObject(0).getInt("usbVendorId")); assertEquals(0x0043, info.getJSONObject(0).getInt("usbProductId"))
        assertEquals("another origin sees none", 0, ok("serial.getPorts", call("serial.getPorts", origin = "https://other.example.test")).getJSONArray("ports").length())
        assertNotEquals("ids differ across origins", p, serialGrants.idOf("https://other.example.test", "2341:0043"))
        assertNotEquals("ids differ across APIs", p, usbGrants.idOf(ORIGIN, "2341:0043"))
    }

    @Test fun `a port the user never picked is NotAllowedError`() {
        assertEquals("NotAllowedError", errName(call("serial.open", obj("port" to "deadbeef", "baudRate" to 9600))))
        val p = portId()
        assertEquals("NotAllowedError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600), origin = "https://other.example.test")))
    }

    @Test fun `open sets the line coding and raises DTR and RTS, with the settings the page gave`() {
        val p = portId(); usb.calls.clear()
        openSerial(p, 115200)
        assertEquals(listOf("open", "claim 1", "claim 0",
            "control 21 20 0 0 00c2010000" + "0008", "control 21 22 3 0"), usb.calls)
    }

    @Test fun `open validates its arguments and refuses what CDC cannot do`() {
        val p = portId()
        assertEquals("TypeError", errName(call("serial.open", obj("port" to p, "baudRate" to 0))))
        assertEquals("TypeError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600, "dataBits" to 5))))
        assertEquals("TypeError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600, "stopBits" to 3))))
        assertEquals("NetworkError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600, "flowControl" to "hardware"))))
        openSerial(p)
        assertEquals("InvalidStateError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600))))
    }

    @Test fun `a USB permission denial on open is NotAllowedError and nothing is claimed`() {
        val p = portId(); usb.permitted.clear(); usb.grantOnRequest = false; usb.calls.clear()
        assertEquals("NotAllowedError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600))))
        assertTrue(usb.calls.isEmpty())
    }

    @Test fun `bytes the device sends reach the page as serial data events, and only while the port is open`() {
        val p = portId(); openSerial(p)
        usb.bulkInQueue += UsbTransfer("ok", byteArrayOf(0x68, 0x69))
        assertTrue(serial.pump("2341:0043"))
        val ev = events("serial.data")
        assertEquals(1, ev.size); assertEquals(p, ev[0].getString("port")); assertEquals("6869", ev[0].getString("value"))
        assertTrue("a quiet bus keeps the loop going", serial.pump("2341:0043"))
        assertEquals(1, events("serial.data").size)
        ok("serial.close", call("serial.close", obj("port" to p)))
        usb.bulkInQueue += UsbTransfer("ok", byteArrayOf(1))
        assertFalse(serial.pump("2341:0043")); assertEquals(1, events("serial.data").size)
    }

    @Test fun `write sends the bytes out the bulk OUT endpoint in order, and needs an open port`() {
        val p = portId()
        assertEquals("InvalidStateError", errName(call("serial.write", obj("port" to p, "value" to "01"))))
        openSerial(p); usb.calls.clear()
        ok("serial.write", call("serial.write", obj("port" to p, "value" to "48656c6c6f")))
        assertEquals(listOf("out 2 48656c6c6f"), usb.calls)
        assertEquals("TypeError", errName(call("serial.write", obj("port" to p, "value" to "zz"))))
        usb.failTransfers = true
        assertEquals("NetworkError", errName(call("serial.write", obj("port" to p, "value" to "01"))))
    }

    @Test fun `setSignals sends the control line state and a break`() {
        val p = portId(); openSerial(p); usb.calls.clear()
        ok("serial.setSignals", call("serial.setSignals", obj("port" to p, "dataTerminalReady" to false)))
        ok("serial.setSignals", call("serial.setSignals", obj("port" to p, "requestToSend" to false)))
        ok("serial.setSignals", call("serial.setSignals", obj("port" to p, "break" to true)))
        assertEquals(listOf("control 21 22 2 0", "control 21 22 0 0", "control 21 23 65535 0"), usb.calls)
    }

    @Test fun `close releases both interfaces and the device, and forget revokes the grant`() {
        val p = portId(); openSerial(p); usb.calls.clear()
        ok("serial.close", call("serial.close", obj("port" to p)))
        assertEquals(listOf("release 1", "release 0", "close"), usb.calls)
        ok("serial.forget", call("serial.forget", obj("port" to p)))
        assertTrue(serialGrants.forOrigin(ORIGIN).isEmpty())
        assertEquals("NotAllowedError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600))))
    }

    @Test fun `unplugging an open port tells the page and ends the stream`() {
        val p = portId(); openSerial(p)
        usb.unplug("2341:0043")
        assertTrue(events("serial.disconnect").all { it.getString("port") == p } && events("serial.disconnect").isNotEmpty())
        usb.plug(ARDUINO)
        assertEquals("the grant outlives a replug", 1, ok("serial.getPorts", call("serial.getPorts")).getJSONArray("ports").length())
    }

    @Test fun `a read error on a device that is gone closes the port, on one still attached it keeps trying`() {
        val p = portId(); openSerial(p)
        usb.failTransfers = true
        assertTrue(serial.pump("2341:0043"))
        usb.devs.removeAll { it.key == "2341:0043" }
        assertFalse(serial.pump("2341:0043"))
        assertEquals(1, events("serial.disconnect").count { it.getString("port") == p })
    }

    @Test fun `a device without CDC interfaces is not a serial port`() {
        assertEquals(listOf(ARDUINO), serial.ports())
        assertEquals("This device is not a USB CDC serial port.", serial.open("1209:abcd", com.diegonmarcos.superapp.browser.SerialOptions(9600, 8, 1, "none", "none")))
    }

    @Test fun `WebUSB and Web Serial do not share an open device`() {
        val p = portId(); openSerial(p)
        val d = usbId("2341:0043")
        assertEquals("InvalidStateError", errName(call("usb.open", obj("device" to d))))
        ok("serial.close", call("serial.close", obj("port" to p)))
        ok("usb.open", call("usb.open", obj("device" to d)))
        assertEquals("InvalidStateError", errName(call("serial.open", obj("port" to p, "baudRate" to 9600))))
    }

    // ---- WebUSB ----

    @Test fun `the USB chooser hides devices that offer nothing a page may claim and honours filters`() {
        chooser.pickKey = { null }
        call("usb.requestDevice", obj("filters" to JSONArray()), gesture = true)
        assertEquals(listOf(listOf("2341:0043", "1209:abcd", "0483:5740")), chooser.shown)
        chooser.shown.clear()
        call("usb.requestDevice", obj("filters" to JSONArray().put(obj("vendorId" to 0x1209))), gesture = true)
        call("usb.requestDevice", obj("filters" to JSONArray().put(obj("classCode" to 0x0A))), gesture = true)
        call("usb.requestDevice", obj("filters" to JSONArray().put(obj("vendorId" to 0x2341, "productId" to 0x0043, "serialNumber" to "SN123"))), gesture = true)
        call("usb.requestDevice", obj("filters" to JSONArray().put(obj("vendorId" to 0x2341, "serialNumber" to "other"))), gesture = true)
        assertEquals(listOf(listOf("1209:abcd"), listOf("2341:0043"), listOf("2341:0043"), emptyList()), chooser.shown)
        assertEquals("TypeError", errName(call("usb.requestDevice", gesture = true)))
    }

    @Test fun `a plugged or unplugged device updates the open chooser`() {
        var seen = emptyList<String>()
        chooser.pickKey = { req ->
            req.onUpdate = { seen = req.items.map { it.key } }
            usb.unplug("1209:abcd")
            null
        }
        call("usb.requestDevice", obj("filters" to JSONArray()), gesture = true)
        assertEquals(listOf("2341:0043", "0483:5740"), seen)
    }

    @Test fun `requestDevice returns the descriptors the page needs, under an origin-scoped id`() {
        val r = ok("usb.requestDevice", run { chooser.pickKey = { "1209:abcd" }; call("usb.requestDevice", obj("filters" to JSONArray()), gesture = true) }).getJSONObject("device")
        assertEquals(usbGrants.idOf(ORIGIN, "1209:abcd"), r.getString("id"))
        assertEquals(0x1209, r.getInt("vendorId")); assertEquals("Dev Board", r.getString("productName")); assertFalse(r.has("serialNumber"))
        val alts = r.getJSONArray("configurations").getJSONObject(0).getJSONArray("interfaces").getJSONObject(0).getJSONArray("alternates")
        assertEquals(2, alts.length())
        assertEquals("in", alts.getJSONObject(0).getJSONArray("endpoints").getJSONObject(0).getString("direction"))
        assertEquals("out", alts.getJSONObject(0).getJSONArray("endpoints").getJSONObject(1).getString("direction"))
    }

    @Test fun `nothing works on a device before it is opened`() {
        val d = usbId("1209:abcd")
        for (op in listOf("usb.selectConfiguration", "usb.claimInterface", "usb.transferIn", "usb.clearHalt"))
            assertEquals(op, "InvalidStateError", errName(call(op, obj("device" to d, "configurationValue" to 1, "interfaceNumber" to 0, "endpointNumber" to 1, "length" to 8, "direction" to "in"))))
    }

    @Test fun `open asks Android for the device permission and reports a refusal`() {
        val d = usbId("1209:abcd"); usb.permitted.clear(); usb.grantOnRequest = false
        assertEquals("NotAllowedError", errName(call("usb.open", obj("device" to d))))
        usb.grantOnRequest = true
        ok("usb.open", call("usb.open", obj("device" to d)))
        assertEquals("opening twice is fine", 0, ok("usb.open", call("usb.open", obj("device" to d))).length())
    }

    @Test fun `claim, transfer in, transfer out, control, halt, the whole WebUSB flow over the real endpoints`() {
        val d = openUsb("1209:abcd"); usb.calls.clear()
        ok("usb.selectConfiguration", call("usb.selectConfiguration", obj("device" to d, "configurationValue" to 1)))
        ok("usb.claimInterface", call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0)))
        usb.bulkInQueue += UsbTransfer("ok", byteArrayOf(0x0a, 0x0b))
        val tin = ok("usb.transferIn", call("usb.transferIn", obj("device" to d, "endpointNumber" to 1, "length" to 64)))
        assertEquals("ok", tin.getString("status")); assertEquals("0a0b", tin.getString("data"))
        val tout = ok("usb.transferOut", call("usb.transferOut", obj("device" to d, "endpointNumber" to 1, "data" to "c0ffee")))
        assertEquals(3, tout.getInt("bytesWritten"))
        val setup = obj("requestType" to "vendor", "recipient" to "device", "request" to 6, "value" to 0x0100, "index" to 0)
        val cin = ok("usb.controlTransferIn", call("usb.controlTransferIn", obj("device" to d, "setup" to setup, "length" to 18)))
        assertEquals("1201", cin.getString("data"))
        ok("usb.controlTransferOut", call("usb.controlTransferOut", obj("device" to d, "setup" to setup, "data" to "0102")))
        ok("usb.clearHalt", call("usb.clearHalt", obj("device" to d, "direction" to "in", "endpointNumber" to 1)))
        assertEquals(listOf("config 1", "claim 0", "out 1 c0ffee", "control c0 06 256 0", "control 40 06 256 0 0102", "control 02 01 0 129"), usb.calls)
    }

    @Test fun `an endpoint of an unclaimed interface, of the wrong direction, or after an alternate switch is NotFoundError`() {
        val d = openUsb("1209:abcd")
        assertEquals("NotFoundError", errName(call("usb.transferIn", obj("device" to d, "endpointNumber" to 1, "length" to 8))))
        ok("usb.claimInterface", call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0)))
        assertEquals("NotFoundError", errName(call("usb.transferIn", obj("device" to d, "endpointNumber" to 9, "length" to 8))))
        assertEquals("NotFoundError", errName(call("usb.transferIn", obj("device" to d, "endpointNumber" to 2, "length" to 8))))
        ok("usb.selectAlternateInterface", call("usb.selectAlternateInterface", obj("device" to d, "interfaceNumber" to 0, "alternateSetting" to 1)))
        assertEquals("NotFoundError", errName(call("usb.transferOut", obj("device" to d, "endpointNumber" to 1, "data" to "00"))))
        ok("usb.transferIn", run { usb.bulkInQueue += UsbTransfer("ok"); call("usb.transferIn", obj("device" to d, "endpointNumber" to 2, "length" to 8)) })
        assertEquals("NotFoundError", errName(call("usb.selectAlternateInterface", obj("device" to d, "interfaceNumber" to 0, "alternateSetting" to 7))))
    }

    @Test fun `protected classes cannot be claimed, other interfaces of the same device can`() {
        val d = openUsb("0483:5740")
        assertEquals("SecurityError", errName(call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0))))
        ok("usb.claimInterface", call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 1)))
        assertEquals("NotFoundError", errName(call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 5))))
        assertTrue(WebHardwareCore.onlyProtected(KEYBOARD)); assertFalse(WebHardwareCore.onlyProtected(COMPOSITE)); assertFalse(WebHardwareCore.onlyProtected(ARDUINO))
    }

    @Test fun `a control transfer to an unclaimed interface or endpoint is refused, a bad setup is a TypeError`() {
        val d = openUsb("1209:abcd")
        val toIface = obj("requestType" to "class", "recipient" to "interface", "request" to 1, "value" to 0, "index" to 0)
        assertEquals("NotFoundError", errName(call("usb.controlTransferIn", obj("device" to d, "setup" to toIface, "length" to 4))))
        ok("usb.claimInterface", call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0)))
        ok("usb.controlTransferIn", call("usb.controlTransferIn", obj("device" to d, "setup" to toIface, "length" to 4)))
        assertEquals("NotFoundError", errName(call("usb.controlTransferIn", obj("device" to d, "setup" to obj("requestType" to "standard", "recipient" to "endpoint", "request" to 0, "value" to 0, "index" to 0x85), "length" to 2))))
        assertEquals("TypeError", errName(call("usb.controlTransferIn", obj("device" to d, "setup" to obj("requestType" to "bogus", "recipient" to "device", "request" to 0, "value" to 0, "index" to 0), "length" to 2))))
        assertEquals("TypeError", errName(call("usb.transferIn", obj("device" to d, "endpointNumber" to 1, "length" to -1))))
        assertEquals("TypeError", errName(call("usb.transferIn", obj("device" to d, "endpointNumber" to 1, "length" to 99999999))))
    }

    @Test fun `a failed transfer is NetworkError, reset is not available, release works, close drops the device`() {
        val d = openUsb("1209:abcd")
        ok("usb.claimInterface", call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0)))
        usb.failTransfers = true
        assertEquals("NetworkError", errName(call("usb.transferIn", obj("device" to d, "endpointNumber" to 1, "length" to 8))))
        assertEquals("NotSupportedError", errName(call("usb.reset", obj("device" to d))))
        usb.failTransfers = false
        ok("usb.releaseInterface", call("usb.releaseInterface", obj("device" to d, "interfaceNumber" to 0)))
        assertEquals("InvalidStateError", errName(call("usb.releaseInterface", obj("device" to d, "interfaceNumber" to 0))))
        ok("usb.close", call("usb.close", obj("device" to d)))
        assertEquals("InvalidStateError", errName(call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0))))
    }

    @Test fun `a configuration cannot change while an interface is claimed`() {
        val d = openUsb("1209:abcd")
        ok("usb.claimInterface", call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0)))
        assertEquals("InvalidStateError", errName(call("usb.selectConfiguration", obj("device" to d, "configurationValue" to 1))))
        assertEquals("NotFoundError", errName(call("usb.selectConfiguration", obj("device" to d, "configurationValue" to 9))))
    }

    @Test fun `getDevices lists only granted, attached devices and forget revokes`() {
        val d = usbId("1209:abcd")
        var list = ok("usb.getDevices", call("usb.getDevices")).getJSONArray("devices")
        assertEquals(1, list.length()); assertEquals(d, list.getJSONObject(0).getString("id"))
        assertEquals(0, ok("usb.getDevices", call("usb.getDevices", origin = "https://other.example.test")).getJSONArray("devices").length())
        usb.unplug("1209:abcd")
        assertEquals(0, ok("usb.getDevices", call("usb.getDevices")).getJSONArray("devices").length())
        usb.plug(VENDOR)
        ok("usb.forget", call("usb.forget", obj("device" to d)))
        list = ok("usb.getDevices", call("usb.getDevices")).getJSONArray("devices")
        assertEquals(0, list.length())
    }

    @Test fun `connect and disconnect events reach a page that was granted the device, not one that was not`() {
        val d = usbId("1209:abcd")
        usb.unplug("1209:abcd")
        assertEquals(d, events("usb.disconnect").single().getString("device"))
        usb.plug(VENDOR)
        assertEquals(d, events("usb.connect").single().getJSONObject("device").getString("id"))
        sent.clear()
        usb.plug(COMPOSITE.copy(key = "0483:9999", productId = 0x9999)); usb.unplug("0483:9999")
        assertTrue(events("usb.connect").isEmpty() && events("usb.disconnect").isEmpty())
    }

    @Test fun `leaving the page closes what it opened and stops the NFC scan`() {
        val p = portId(); openSerial(p)
        val d = openUsb("1209:abcd")
        ok("nfc.scan", call("nfc.scan", gesture = true))
        usb.calls.clear()
        core.reset()
        assertTrue(usb.calls.containsAll(listOf("release 1", "release 0", "close")))
        assertEquals("the second close is the WebUSB device", 2, usb.calls.count { it == "close" })
        assertNull("the scan is stopped", nfc.listener)
        assertEquals("InvalidStateError", errName(call("usb.claimInterface", obj("device" to d, "interfaceNumber" to 0))))
        assertEquals("InvalidStateError", errName(call("serial.write", obj("port" to p, "value" to "00"))))
    }

    // ---- Web NFC ----

    @Test fun `NFC is reported absent or off rather than faked`() {
        nfc.supported = false
        assertEquals("NotSupportedError", errName(call("nfc.scan", gesture = true)))
        nfc.supported = true; nfc.enabled = false
        assertEquals("NotReadableError", errName(call("nfc.scan", gesture = true)))
        assertEquals("NotReadableError", errName(call("nfc.write", obj("records" to JSONArray(), "overwrite" to true), gesture = true)))
        assertEquals(0, chooser.nfcPrompts)
    }

    @Test fun `the first scan asks the user, needs a gesture for that, and is remembered per origin`() {
        assertEquals("NotAllowedError", errName(call("nfc.scan")))
        assertEquals(0, chooser.nfcPrompts)
        chooser.nfcAllow = false
        assertEquals("NotAllowedError", errName(call("nfc.scan", gesture = true)))
        assertTrue(nfcGrants.forOrigin(ORIGIN).isEmpty())
        chooser.nfcAllow = true
        ok("nfc.scan", call("nfc.scan", gesture = true))
        assertEquals(2, chooser.nfcPrompts)
        core.reset()
        ok("nfc.scan", call("nfc.scan"))
        assertEquals("a granted origin is not asked again", 2, chooser.nfcPrompts)
        core.reset()
        assertEquals("NotAllowedError", errName(call("nfc.scan", origin = "https://other.example.test")))
    }

    @Test fun `a tag reaches the page as a reading event with its serial and records, only while scanning`() {
        nfc.listener?.tag("x", emptyList())
        ok("nfc.scan", call("nfc.scan", gesture = true))
        val text = NdefRec("text", null, null, "hello".toByteArray(), "utf-8", "en")
        val url = NdefRec("url", null, null, "https://example.test/a".toByteArray(), null, null)
        nfc.listener!!.tag("04:7a:2b:11", listOf(text, url))
        val ev = events("nfc.reading").single().getJSONObject("message")
        assertEquals("04:7a:2b:11", ev.getString("serialNumber"))
        assertEquals("text", ev.getJSONArray("records").getJSONObject(0).getString("recordType"))
        assertEquals("68656c6c6f", ev.getJSONArray("records").getJSONObject(0).getString("data"))
        assertEquals("en", ev.getJSONArray("records").getJSONObject(0).getString("lang"))
        assertEquals("url", ev.getJSONArray("records").getJSONObject(1).getString("recordType"))
        nfc.listener!!.failed("lost")
        assertEquals(1, events("nfc.readingerror").size)
        ok("nfc.stop", call("nfc.stop"))
        assertNull(nfc.listener)
    }

    @Test fun `only one page scans at a time`() {
        nfc.startOk = false
        assertEquals("NotReadableError", errName(call("nfc.scan", gesture = true)))
    }

    @Test fun `write validates records, shows the tap panel, and reports the tag's answer`() {
        val rec = obj("recordType" to "text", "data" to "68656c6c6f", "encoding" to "utf-8", "lang" to "en")
        ok("nfc.scan", call("nfc.scan", gesture = true)); core.reset()
        assertEquals("TypeError", errName(call("nfc.write", obj("overwrite" to true))))
        assertEquals("TypeError", errName(call("nfc.write", obj("records" to JSONArray().put(obj("recordType" to "text", "data" to "zz")), "overwrite" to true))))
        assertEquals("NotSupportedError", errName(call("nfc.write", obj("records" to JSONArray().put(obj("recordType" to "text", "data" to "00", "encoding" to "utf-16")), "overwrite" to true))))
        assertEquals("NotSupportedError", errName(call("nfc.write", obj("records" to JSONArray().put(obj("recordType" to "Bogus", "data" to "00")), "overwrite" to true))))
        assertEquals(0, chooser.tapShown)

        val before = sent.size
        core.handle(ORIGIN, JSONObject().put("v", 1).put("id", 900).put("op", "nfc.write").put("args", obj("records" to JSONArray().put(rec), "overwrite" to false)).toString())
        assertEquals("waiting for the tag: no reply yet", before, sent.size)
        assertEquals(1, chooser.tapShown)
        assertEquals("hello", String(nfc.written!![0].data)); assertEquals(false, nfc.overwrite)
        nfc.writeDone!!(NfcWriteResult(null, null))
        val r = sent.last()
        assertEquals(900, r.getInt("id")); assertTrue(r.getBoolean("ok"))
        assertEquals(1, chooser.tapDismissed)

        core.handle(ORIGIN, JSONObject().put("v", 1).put("id", 901).put("op", "nfc.write").put("args", obj("records" to JSONArray().put(rec), "overwrite" to false)).toString())
        nfc.writeDone!!(NfcWriteResult("NotAllowedError", "The tag already holds records and overwrite is false."))
        assertEquals("NotAllowedError", errName(sent.last()))
    }

    @Test fun `dismissing the tap panel aborts the write and tells the radio`() {
        core.handle(ORIGIN, JSONObject().put("v", 1).put("id", 1).put("op", "nfc.write").put("gesture", true)
            .put("args", obj("records" to JSONArray().put(obj("recordType" to "empty", "data" to "")), "overwrite" to true)).toString())
        chooser.tapCancel!!()
        assertEquals("AbortError", errName(sent.last())); assertEquals(1, nfc.cancelled)
    }

    // ---- NDEF codec ----

    @Test fun `text records decode with their language and encoding and write back byte for byte`() {
        val payload = byteArrayOf(0x02) + "en".toByteArray() + "hello".toByteArray()
        val r = NdefCodec.toWeb(RawNdef(NdefCodec.TNF_WELL_KNOWN, "T".toByteArray(), ByteArray(0), payload))
        assertEquals("text", r.recordType); assertEquals("en", r.lang); assertEquals("utf-8", r.encoding); assertEquals("hello", String(r.data))
        val raw = NdefCodec.toRaw(r).first!!
        assertEquals(NdefCodec.TNF_WELL_KNOWN, raw.tnf); assertArrayEquals(payload, raw.payload); assertEquals("T", String(raw.type))
        val u16 = NdefCodec.toWeb(RawNdef(NdefCodec.TNF_WELL_KNOWN, "T".toByteArray(), ByteArray(0), byteArrayOf(0x82.toByte()) + "de".toByteArray() + byteArrayOf(0, 0x41)))
        assertEquals("utf-16", u16.encoding); assertEquals("de", u16.lang)
        assertNotNull(NdefCodec.toRaw(NdefRec("text", null, null, ByteArray(1), "utf-16", "en")).second)
    }

    @Test fun `url records expand the abbreviation byte`() {
        fun url(p: Int, rest: String) = NdefCodec.toWeb(RawNdef(NdefCodec.TNF_WELL_KNOWN, "U".toByteArray(), ByteArray(0), byteArrayOf(p.toByte()) + rest.toByteArray()))
        assertEquals("https://www.example.test/a", String(url(2, "example.test/a").data))
        assertEquals("https://example.test", String(url(4, "example.test").data))
        assertEquals("tel:+34123", String(url(5, "+34123").data))
        assertEquals("mailto:a@b.test", String(url(6, "a@b.test").data))
        assertEquals("urn:nfc:ext:x", String(url(0x23, "ext:x").data))
        assertEquals("custom:x", String(url(0, "custom:x").data))
        assertEquals("url", url(4, "a").recordType)
        val back = NdefCodec.toRaw(NdefRec("url", null, null, "https://example.test".toByteArray(), null, null)).first!!
        assertEquals(0, back.payload[0].toInt()); assertEquals("https://example.test", String(back.payload, 1, back.payload.size - 1))
    }

    @Test fun `mime, external, absolute-url, empty and unknown records map both ways`() {
        val mime = NdefCodec.toWeb(RawNdef(NdefCodec.TNF_MIME, "application/json".toByteArray(), "id1".toByteArray(), "{}".toByteArray()))
        assertEquals("mime", mime.recordType); assertEquals("application/json", mime.mediaType); assertEquals("id1", mime.id); assertEquals("{}", String(mime.data))
        val ext = NdefCodec.toWeb(RawNdef(NdefCodec.TNF_EXTERNAL, "example.com:thing".toByteArray(), ByteArray(0), byteArrayOf(1)))
        assertEquals("example.com:thing", ext.recordType)
        assertEquals(NdefCodec.TNF_EXTERNAL, NdefCodec.toRaw(ext).first!!.tnf)
        val abs = NdefCodec.toWeb(RawNdef(NdefCodec.TNF_ABSOLUTE_URI, "https://a.test/x".toByteArray(), ByteArray(0), ByteArray(0)))
        assertEquals("absolute-url", abs.recordType); assertEquals("https://a.test/x", String(abs.data))
        assertEquals("empty", NdefCodec.toWeb(RawNdef(NdefCodec.TNF_EMPTY, ByteArray(0), ByteArray(0), ByteArray(0))).recordType)
        assertEquals("unknown", NdefCodec.toWeb(RawNdef(NdefCodec.TNF_UNKNOWN, ByteArray(0), ByteArray(0), byteArrayOf(9))).recordType)
        assertEquals("unknown", NdefCodec.toWeb(RawNdef(NdefCodec.TNF_WELL_KNOWN, "T".toByteArray(), ByteArray(0), ByteArray(0))).recordType)
        assertEquals("a mime record with no media type gets the generic one", "application/octet-stream",
            String(NdefCodec.toRaw(NdefRec("mime", null, null, ByteArray(1), null, null)).first!!.type))
        assertNull(NdefCodec.toRaw(NdefRec("Not A Type", null, null, ByteArray(0), null, null)).first)
    }

    // ---- grants ----

    @Test fun `grants persist per origin and api, merge by key, and clear`() {
        val kv = MemKv()
        val g = WebHwGrants(kv, "usb")
        g.grant("https://a.test", com.diegonmarcos.superapp.browser.HwGrant("1:2", "One"))
        g.grant("https://a.test", com.diegonmarcos.superapp.browser.HwGrant("1:2", "One again"))
        g.grant("https://b.test", com.diegonmarcos.superapp.browser.HwGrant("3:4", null))
        assertEquals(listOf("https://a.test", "https://b.test"), WebHwGrants(kv, "usb").origins())
        assertEquals("One again", WebHwGrants(kv, "usb").forOrigin("https://a.test").single().name)
        g.revoke("https://a.test", "1:2"); assertEquals(listOf("https://b.test"), g.origins())
        g.clear("https://b.test"); assertTrue(g.origins().isEmpty())
    }
}
