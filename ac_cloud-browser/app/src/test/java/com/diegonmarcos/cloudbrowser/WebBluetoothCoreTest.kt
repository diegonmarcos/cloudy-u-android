package com.diegonmarcos.cloudbrowser

import com.diegonmarcos.superapp.bluetooth.BleAdapter
import com.diegonmarcos.superapp.bluetooth.BleDevice
import com.diegonmarcos.superapp.bluetooth.BleLog
import com.diegonmarcos.superapp.bluetooth.ConnState
import com.diegonmarcos.superapp.bluetooth.GattCatalog
import com.diegonmarcos.superapp.bluetooth.GattCharacteristic
import com.diegonmarcos.superapp.bluetooth.GattListener
import com.diegonmarcos.superapp.bluetooth.GattProps
import com.diegonmarcos.superapp.bluetooth.GattService
import com.diegonmarcos.superapp.bluetooth.GattSession
import com.diegonmarcos.superapp.bluetooth.NotifyMode
import com.diegonmarcos.superapp.browser.ChooserRequest
import com.diegonmarcos.superapp.browser.WebBleChooser
import com.diegonmarcos.superapp.browser.WebBleGrants
import com.diegonmarcos.superapp.browser.WebBluetoothCore
import com.diegonmarcos.superapp.browser.WebBlePermissions
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/** #1222 the native half of Web Bluetooth, over a fake radio, a fake chooser and the real GattSession; replies are held to the contract file. */
class WebBluetoothCoreTest {
    private class FakeRadio : BleAdapter {
        override val supported = true
        override var enabled = true
        override var listener: GattListener? = null
        val calls = ArrayList<String>()
        var sink: ((BleDevice) -> Unit)? = null
        override fun startScan(onResult: (BleDevice) -> Unit, onFailed: (String) -> Unit): Boolean { sink = onResult; calls += "scan"; return true }
        override fun stopScan() { sink = null; calls += "stopScan" }
        override fun bonded() = emptyList<BleDevice>()
        override fun connect(address: String): Boolean { calls += "connect"; return true }
        override fun disconnect(address: String) { calls += "disconnect"; listener?.onConnectionState(address, ConnState.Disconnected, 0) }
        override fun discover(address: String): Boolean { calls += "discover"; return true }
        override fun read(address: String, service: String, char: String): Boolean { calls += "read"; return true }
        override fun write(address: String, service: String, char: String, value: ByteArray, withResponse: Boolean): Boolean {
            calls += "write ${value.joinToString("") { "%02x".format(it) }} ${if (withResponse) "resp" else "noresp"}"; return true
        }
        override fun setNotify(address: String, service: String, char: String, mode: NotifyMode): Boolean { calls += "notify $mode"; return true }
        override fun readDescriptor(address: String, service: String, char: String, desc: String) = true
        override fun writeDescriptor(address: String, service: String, char: String, desc: String, value: ByteArray) = true
        override fun requestMtu(address: String, mtu: Int) = true
        override fun readRssi(address: String): Boolean { calls += "rssi"; return true }
        override fun bond(address: String) = true
    }

    private class MemKv : WebBleGrants.KeyValue {
        val m = HashMap<String, String>()
        override fun get(key: String) = m[key]
        override fun put(key: String, value: String?) { if (value == null) m.remove(key) else m[key] = value }
        override fun keys(): Set<String> = m.keys.toSet()
    }

    private val BASE = "-0000-1000-8000-00805f9b34fb"
    private val HR = "0000180d$BASE"; private val HRM = "00002a37$BASE"
    private val BATT = "0000180f$BASE"; private val BATT_L = "00002a19$BASE"
    private val INFO = "0000180a$BASE"
    private val ORIGIN = "https://hr.example.test"
    private val ADDR = "AA:BB:CC:00:00:01"

    private val contract = JSONObject(File("../../ab_cloud-libs-shared/libs/browser/contract/web-bluetooth-contract.json").readText())
    private val catalog = GattCatalog.parse(File("../../data/fleet/gatt-catalog.json").readText())

    private lateinit var radio: FakeRadio
    private lateinit var session: GattSession
    private lateinit var grants: WebBleGrants
    private lateinit var core: WebBluetoothCore
    private val sent = ArrayList<JSONObject>()
    private var pick: ((ChooserRequest) -> BleDevice?) = { it.devices.firstOrNull() }
    private var denyPermission = false
    private var chooserShown = 0
    private var id = 0

    private val chooser = object : WebBleChooser {
        override fun show(request: ChooserRequest, onPick: (BleDevice) -> Unit, onCancel: () -> Unit) {
            chooserShown++
            val d = pick(request)
            if (d == null) onCancel() else onPick(d)
        }
    }
    private val permissions = object : WebBlePermissions {
        var asked = 0
        override fun missing(): List<String> = if (asked == 0 && denyPermission) listOf("android.permission.BLUETOOTH_CONNECT") else emptyList()
        override fun request(perms: List<String>, done: (Boolean) -> Unit) { asked++; done(!denyPermission) }
    }

    private val strap = BleDevice(ADDR, "Fake Strap", -55, listOf(HR))
    private val services = listOf(
        GattService(HR, listOf(GattCharacteristic(HR, HRM, GattProps.NOTIFY or GattProps.READ))),
        GattService(BATT, listOf(GattCharacteristic(BATT, BATT_L, GattProps.READ or GattProps.NOTIFY or GattProps.WRITE or GattProps.WRITE_NO_RESPONSE))),
        GattService(INFO, listOf(GattCharacteristic(INFO, "00002a29$BASE", GattProps.READ))),
    )

    @Before fun setUp() {
        radio = FakeRadio()
        session = GattSession(radio, BleLog(clock = { 0L }), { _, _ -> })
        grants = WebBleGrants(MemKv())
        denyPermission = false
        sent.clear(); chooserShown = 0; permissions.asked = 0
        pick = { it.devices.firstOrNull() }
        core = WebBluetoothCore(session, catalog, grants, chooser, permissions) { sent += JSONObject(it) }
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

    private fun ok(r: JSONObject?): JSONObject { assertTrue(r.toString(), r != null && r.getBoolean("ok")); return r!!.getJSONObject("result") }
    private fun errName(r: JSONObject?): String { assertTrue(r.toString(), r != null && !r.getBoolean("ok")); return r!!.getJSONObject("error").getString("name") }

    private fun requestStrap(optional: List<String> = listOf(BATT), origin: String = ORIGIN): String {
        val args = JSONObject().put("filters", JSONArray().put(JSONObject().put("services", JSONArray(listOf(HR))))).put("optionalServices", JSONArray(optional))
        // the chooser callback fires while startScan has registered its sink: feed the strap first
        pick = { req -> radio.sink?.invoke(strap); req.devices.firstOrNull() }
        return ok(call("requestDevice", args, gesture = true, origin = origin)).getJSONObject("device").getString("id")
    }

    private fun connect(dev: String, origin: String = ORIGIN) {
        val before = sent.size
        core.handle(origin, JSONObject().put("v", 1).put("id", ++id).put("op", "gattConnect").put("args", JSONObject().put("device", dev)).toString())
        radio.listener!!.onConnectionState(ADDR, ConnState.Connected, 0)
        radio.listener!!.onServices(ADDR, services, 0)
        assertTrue(sent.drop(before).toString(), sent.drop(before).any { it.optBoolean("ok") })
    }

    private fun charArgs(dev: String, s: String, c: String) = JSONObject().put("device", dev).put("service", s).put("characteristic", c)

    /** Held to web-bluetooth-contract.json: a result carries exactly the declared fields (optional ones aside). */
    private fun assertContract(op: String, result: JSONObject) {
        val declared = contract.getJSONObject("ops").getJSONObject(op).getJSONObject("result")
        val names = declared.keys().asSequence().map { it.removeSuffix("?") }.toSet()
        assertEquals("fields of $op", names, result.keys().asSequence().toSet())
    }

    // ---- origin rules ----

    @Test fun `secure origins are https and loopback http only`() {
        assertTrue(WebBluetoothCore.isSecure("https://a.example"))
        assertTrue(WebBluetoothCore.isSecure("http://localhost:8080"))
        assertTrue(WebBluetoothCore.isSecure("http://127.0.0.1"))
        assertTrue(WebBluetoothCore.isSecure("http://[::1]:3000"))
        assertTrue(WebBluetoothCore.isSecure("http://dev.localhost"))
        assertFalse(WebBluetoothCore.isSecure("http://example.com"))
        assertFalse(WebBluetoothCore.isSecure("http://localhost.evil.com"))
        assertFalse(WebBluetoothCore.isSecure("file://"))
        assertFalse(WebBluetoothCore.isSecure(""))
    }

    @Test fun `origin of a url is scheme host port in lower case, empty for non-web urls`() {
        assertEquals("https://hr.example.test", WebBluetoothCore.originOf("https://HR.example.test/a/b?c#d"))
        assertEquals("http://localhost:8080", WebBluetoothCore.originOf("http://localhost:8080/x"))
        assertEquals("https://evil.test", WebBluetoothCore.originOf("https://user:pw@evil.test/x"))
        assertEquals("", WebBluetoothCore.originOf("about:blank"))
        assertEquals("", WebBluetoothCore.originOf("file:///sdcard/x.html"))
        assertEquals("", WebBluetoothCore.originOf(null))
    }

    @Test fun `an insecure origin gets SecurityError for everything`() {
        assertEquals("SecurityError", errName(call("getAvailability", origin = "http://example.com")))
        assertEquals("SecurityError", errName(call("getAvailability", origin = "")))
    }

    // ---- requestDevice ----

    @Test fun `requestDevice needs a user gesture and never scans without one`() {
        val args = JSONObject().put("acceptAllDevices", true).put("optionalServices", JSONArray())
        assertEquals("SecurityError", errName(call("requestDevice", args, gesture = false)))
        assertEquals("SecurityError", errName(call("requestDevice", args)))
        assertEquals(0, chooserShown); assertFalse(radio.calls.contains("scan"))
    }

    @Test fun `filters xor acceptAllDevices, else TypeError`() {
        val both = JSONObject().put("acceptAllDevices", true).put("filters", JSONArray().put(JSONObject().put("name", "x"))).put("optionalServices", JSONArray())
        assertEquals("TypeError", errName(call("requestDevice", both, gesture = true)))
        val none = JSONObject().put("optionalServices", JSONArray())
        assertEquals("TypeError", errName(call("requestDevice", none, gesture = true)))
        val empty = JSONObject().put("filters", JSONArray()).put("optionalServices", JSONArray())
        assertEquals("TypeError", errName(call("requestDevice", empty, gesture = true)))
    }

    @Test fun `the chooser lists only devices that match the filters, and a pick becomes an origin grant`() {
        val other = BleDevice("AA:BB:CC:00:00:02", "Thermometer", -40, listOf("0000181a$BASE"))
        var listed: List<String> = emptyList()
        pick = { req -> radio.sink?.invoke(other); radio.sink?.invoke(strap); listed = req.devices.map { it.address }; req.devices.firstOrNull() }
        val args = JSONObject().put("filters", JSONArray().put(JSONObject().put("services", JSONArray(listOf(HR))))).put("optionalServices", JSONArray(listOf(BATT)))
        val dev = ok(call("requestDevice", args, gesture = true)).also { assertContract("requestDevice", it) }.getJSONObject("device")
        assertEquals(listOf(ADDR), listed)
        assertEquals("Fake Strap", dev.getString("name"))
        assertEquals(WebBluetoothCore.deviceId(ORIGIN, ADDR), dev.getString("id"))
        assertNotEquals("device ids differ across origins", WebBluetoothCore.deviceId(ORIGIN, ADDR), WebBluetoothCore.deviceId("https://other.test", ADDR))
        assertEquals(setOf(HR, BATT), grants.forOrigin(ORIGIN).single().services)
        assertTrue(radio.calls.contains("stopScan"))
    }

    @Test fun `cancelling the chooser is NotFoundError and grants nothing`() {
        pick = { null }
        val args = JSONObject().put("acceptAllDevices", true).put("optionalServices", JSONArray())
        assertEquals("NotFoundError", errName(call("requestDevice", args, gesture = true)))
        assertTrue(grants.forOrigin(ORIGIN).isEmpty()); assertTrue(radio.calls.contains("stopScan"))
    }

    @Test fun `a denied Android permission is NotAllowedError before any scan`() {
        denyPermission = true
        val args = JSONObject().put("acceptAllDevices", true).put("optionalServices", JSONArray())
        assertEquals("NotAllowedError", errName(call("requestDevice", args, gesture = true)))
        assertFalse(radio.calls.contains("scan"))
    }

    // ---- permission memory ----

    @Test fun `grants are remembered per origin and listed by getDevices, forget ends them`() {
        val dev = requestStrap()
        val listed = ok(call("getDevices")).also { assertContract("getDevices", it) }.getJSONArray("devices")
        assertEquals(1, listed.length()); assertEquals(dev, listed.getJSONObject(0).getString("id"))
        assertEquals(0, ok(call("getDevices", origin = "https://other.test")).getJSONArray("devices").length())
        // another origin cannot use this origin's device id
        assertEquals("NotAllowedError", errName(call("gattConnect", JSONObject().put("device", dev), origin = "https://other.test")))
        // a restart: a new core over the same store still knows the grant
        val again = WebBluetoothCore(session, catalog, grants, chooser, permissions) { sent += JSONObject(it) }
        val n = ++id
        again.handle(ORIGIN, JSONObject().put("v", 1).put("id", n).put("op", "getDevices").put("args", JSONObject()).toString())
        assertEquals(1, sent.last().getJSONObject("result").getJSONArray("devices").length())
        ok(call("forget", JSONObject().put("device", dev)))
        assertEquals(0, ok(call("getDevices")).getJSONArray("devices").length())
        assertEquals(emptyList<String>(), grants.origins())
    }

    @Test fun `granting the same device again keeps the services of the first grant`() {
        requestStrap(optional = listOf(BATT))
        requestStrap(optional = listOf(INFO))
        assertEquals(setOf(HR, BATT, INFO), grants.forOrigin(ORIGIN).single().services)
    }

    // ---- gatt ----

    @Test fun `connect, services limited to what the page named, characteristics, read, write, notify`() {
        val dev = requestStrap()
        assertEquals("NetworkError", errName(call("getPrimaryServices", JSONObject().put("device", dev))))   // not connected yet
        connect(dev)
        val svcs = ok(call("getPrimaryServices", JSONObject().put("device", dev))).also { assertContract("getPrimaryServices", it) }.getJSONArray("services")
        assertEquals(listOf(HR, BATT), List(svcs.length()) { svcs.getString(it) })                              // device information was not named
        assertEquals("SecurityError", errName(call("getPrimaryServices", JSONObject().put("device", dev).put("service", INFO))))
        assertEquals("SecurityError", errName(call("readValue", charArgs(dev, INFO, "00002a29$BASE"))))
        val chars = ok(call("getCharacteristics", JSONObject().put("device", dev).put("service", HR))).also { assertContract("getCharacteristics", it) }.getJSONArray("characteristics")
        assertEquals(HRM, chars.getJSONObject(0).getString("uuid"))
        assertTrue(chars.getJSONObject(0).getJSONObject("properties").getBoolean("notify"))

        // read
        val before = sent.size
        core.handle(ORIGIN, JSONObject().put("v", 1).put("id", ++id).put("op", "readValue").put("args", charArgs(dev, BATT, BATT_L)).toString())
        radio.listener!!.onRead(ADDR, BATT, BATT_L, byteArrayOf(0x57), 0)
        val read = sent.drop(before).first { it.optBoolean("ok") }.getJSONObject("result").also { assertContract("readValue", it) }
        assertEquals("57", read.getString("value"))

        // writes: auto picks with-response when allowed; "without" picks write-without-response; a read-only characteristic refuses
        fun write(c: String, s: String, hex: String, mode: String): JSONObject? {
            val b = sent.size; val n = ++id
            core.handle(ORIGIN, JSONObject().put("v", 1).put("id", n).put("op", "writeValue").put("args", charArgs(dev, s, c).put("value", hex).put("response", mode)).toString())
            if (radio.calls.last().startsWith("write")) radio.listener!!.onWrite(ADDR, s, c, 0)
            return sent.drop(b).firstOrNull { it.optInt("id") == n }
        }
        assertTrue(write(BATT_L, BATT, "ab00", "auto")!!.getBoolean("ok")); assertEquals("write ab00 resp", radio.calls.last())
        assertTrue(write(BATT_L, BATT, "01", "without")!!.getBoolean("ok")); assertEquals("write 01 noresp", radio.calls.last())
        assertEquals("NotSupportedError", errName(write(HRM, HR, "01", "auto")))
        assertEquals("TypeError", errName(write(BATT_L, BATT, "xyz", "auto")))

        // notifications: the event names the page's device id, and stops after stopNotifications
        val n = ++id
        core.handle(ORIGIN, JSONObject().put("v", 1).put("id", n).put("op", "startNotifications").put("args", charArgs(dev, HR, HRM)).toString())
        radio.listener!!.onNotifySet(ADDR, HR, HRM, NotifyMode.Notify, 0)
        assertTrue(sent.any { it.optInt("id") == n && it.getBoolean("ok") })
        radio.listener!!.onChanged(ADDR, HR, HRM, byteArrayOf(0, 72))
        val ev = sent.last()
        assertEquals("characteristicvaluechanged", ev.getString("event")); assertEquals(dev, ev.getString("device"))
        assertEquals("0048", ev.getString("value")); assertEquals(HRM, ev.getString("characteristic"))
        val m = ++id
        core.handle(ORIGIN, JSONObject().put("v", 1).put("id", m).put("op", "stopNotifications").put("args", charArgs(dev, HR, HRM)).toString())
        radio.listener!!.onNotifySet(ADDR, HR, HRM, NotifyMode.Off, 0)
        val count = sent.size
        radio.listener!!.onChanged(ADDR, HR, HRM, byteArrayOf(0, 73))
        assertEquals("no event after stopNotifications", count, sent.size)
    }

    @Test fun `a dropped link emits gattserverdisconnected once and a reset disconnects what the page connected`() {
        val dev = requestStrap()
        connect(dev)
        radio.listener!!.onConnectionState(ADDR, ConnState.Disconnected, 8)
        val ev = sent.last()
        assertEquals("gattserverdisconnected", ev.getString("event")); assertEquals(dev, ev.getString("device"))
        val count = sent.size
        radio.listener!!.onConnectionState(ADDR, ConnState.Disconnected, 8)
        assertEquals(count, sent.size)

        connect(dev)
        core.reset()
        assertEquals("disconnect", radio.calls.last())
    }

    @Test fun `unknown ops and garbage are refused without a crash`() {
        assertEquals("NotSupportedError", errName(call("pairWithEverything")))
        core.handle(ORIGIN, "not json"); core.handle(ORIGIN, "{\"v\":2,\"id\":1,\"op\":\"getAvailability\"}")
        assertTrue(sent.none { it.optInt("id") == 1 && it.has("result") })
    }

    @Test fun `every reply is an envelope of the contract`() {
        val r = call("getAvailability")!!
        assertEquals(contract.getInt("version"), r.getInt("v"))
        val keys = r.keys().asSequence().toSet()
        assertEquals(setOf("v", "id", "ok", "result"), keys)
        assertContract("getAvailability", r.getJSONObject("result"))
        val e = call("pairWithEverything", origin = "http://example.com")!!
        assertEquals(setOf("v", "id", "ok", "error"), e.keys().asSequence().toSet())
        val names = contract.getJSONArray("errors").let { a -> List(a.length()) { a.getString(it) } }
        assertTrue(e.getJSONObject("error").getString("name") in names)
    }
}
