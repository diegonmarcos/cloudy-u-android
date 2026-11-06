package com.diegonmarcos.cloudtoolssmallllms.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import com.diegonmarcos.cloudtoolssmallllms.core.ConnectedFacts
import com.diegonmarcos.cloudtoolssmallllms.core.ConnectedProfile
import com.diegonmarcos.cloudtoolssmallllms.core.GattKey
import com.diegonmarcos.cloudtoolssmallllms.core.GattParse
import com.diegonmarcos.cloudtoolssmallllms.core.Num
import com.diegonmarcos.cloudtoolssmallllms.core.Uuid
import java.util.UUID

/** An advertiser the BLE scan found. */
data class BleFound(
    val address: String, val name: String?, val rssi: Int, val serviceUuids: List<String>,
    val manufacturerData: Map<Int, ByteArray>, val device: BluetoothDevice,
)

/** A USB device UsbManager lists: [classes] are the device class and every interface class. */
data class UsbFound(val name: String, val vidPid: String, val product: String?, val classes: Set<Int>)

/** Whether the grants a BLE scan and a GATT connection need are held; they are asked on Configs > Perms only (this never asks). */
object ConnectedGrants {
    fun ble(ctx: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) HardwareProbe.has(ctx, android.Manifest.permission.BLUETOOTH_SCAN) && HardwareProbe.has(ctx, android.Manifest.permission.BLUETOOTH_CONNECT)
        else HardwareProbe.has(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)

    fun location(ctx: Context): Boolean = HardwareProbe.has(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION)
}

/** The USB devices attached now (UsbManager.getDeviceList); a read, no permission needed. */
object UsbList {
    fun read(ctx: Context): List<UsbFound> = runCatching {
        ctx.getSystemService(UsbManager::class.java).deviceList.values.map { d ->
            UsbFound(
                d.deviceName, "%04x:%04x".format(d.vendorId, d.productId), runCatching { d.productName }.getOrNull(),
                (listOf(d.deviceClass) + (0 until d.interfaceCount).map { d.getInterface(it).interfaceClass }).toSet(),
            )
        }
    }.getOrElse { emptyList() }
}

/** One BLE scan for one profile: a ScanFilter by its service UUID (and manufacturer data), started on a tap and stopped after [timeoutMs]. */
@SuppressLint("MissingPermission")
class BleScan(private val ctx: Context, private val timeoutMs: Long, private val onResult: (BleFound) -> Unit, private val onDone: () -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private var scanner: BluetoothLeScanner? = null
    private val stopper = Runnable { stop() }
    /** Every scan ends by itself after the timeout. */
    private fun arm() { handler.postDelayed(stopper, timeoutMs) }
    private val callback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, r: ScanResult) {
            val rec = r.scanRecord
            val mfr = HashMap<Int, ByteArray>()
            rec?.manufacturerSpecificData?.let { a -> for (i in 0 until a.size()) mfr[a.keyAt(i)] = a.valueAt(i) }
            onResult(BleFound(r.device.address, rec?.deviceName ?: runCatching { r.device.name }.getOrNull(), r.rssi, rec?.serviceUuids?.map { it.toString() }.orEmpty(), mfr, r.device))
        }
        override fun onScanFailed(errorCode: Int) = stop()
    }

    /** An unfiltered scan (every advertiser) for [timeoutMs]: what the catalogue's AVAILABLE state is built from. False when Bluetooth is off or missing. */
    fun startAll(): Boolean {
        stop(notify = false)
        val sc = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        scanner = sc
        sc.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        arm()
        return true
    }

    /** False when Bluetooth is off or missing. */
    fun start(profile: ConnectedProfile): Boolean {
        stop(notify = false)
        val sc = ctx.getSystemService(BluetoothManager::class.java)?.adapter?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return false
        val filters = ArrayList<ScanFilter>()
        profile.uuid?.let { filters += ScanFilter.Builder().setServiceUuid(ParcelUuid.fromString(it)).build() }
        profile.manufacturerId?.let { filters += ScanFilter.Builder().setManufacturerData(it, profile.manufacturerPrefix).build() }
        scanner = sc
        sc.startScan(filters, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
        arm()
        return true
    }

    fun stop(notify: Boolean = true) {
        handler.removeCallbacks(stopper)
        val sc = scanner ?: return
        scanner = null
        runCatching { sc.stopScan(callback) }
        if (notify) onDone()
    }
}

/** Where a GATT read is. */
enum class GattStatus { CONNECTING, DISCOVERING, READING, CLOSED, FAILED }

/** One decoded line of a GATT read: [label] and [value] (with its unit), or the raw hex for a characteristic with no parser. */
data class GattLine(val id: String, val label: String, val value: String)

/**
 * Connects to [device], reads the profile's standard characteristics one after the other (notifications for the `notify` ones) and
 * reports decoded lines; gives up after [timeoutMs] without services. [close] ends it (the page calls it when it is hidden).
 */
@SuppressLint("MissingPermission")
class GattSession(
    private val ctx: Context, device: BluetoothDevice, private val profile: ConnectedProfile, private val keys: Map<String, GattKey>,
    private val timeoutMs: Long, private val onStatus: (GattStatus) -> Unit, private val onLines: (List<GattLine>) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())
    private val lines = LinkedHashMap<String, GattLine>()
    private val queue = ArrayDeque(profile.reads)
    private var gatt: BluetoothGatt? = null
    private var closed = false
    private val timeout = Runnable { if (!closed && queue.size == profile.reads.size && lines.isEmpty()) { post(GattStatus.FAILED); close() } }

    private fun post(s: GattStatus) { main.post { if (!closed || s == GattStatus.CLOSED) onStatus(s) } }
    @Suppress("DEPRECATION")
    private fun emit(char: BluetoothGattCharacteristic) {
        val bytes = char.value ?: return
        val read = profile.reads.firstOrNull { it.char == char.uuid.toString() } ?: return
        val values = GattParse.parse(read.parser, bytes)
        main.post {
            if (closed) return@post
            if (values == null) lines[read.char] = GattLine(read.char, Uuid.short(read.char), bytes.joinToString(" ") { "%02X".format(it) })
            else for (v in values) keys[v.key]?.let { k -> lines[v.key] = GattLine(v.key, k.label, listOf(Num.short(v.value.toFloat()), k.unit).filter { it.isNotBlank() }.joinToString(" ")) }
            onLines(lines.values.toList())
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) { post(GattStatus.DISCOVERING); g.discoverServices() }
            else if (newState == BluetoothProfile.STATE_DISCONNECTED) { post(if (lines.isEmpty()) GattStatus.FAILED else GattStatus.CLOSED); main.post { close() } }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) { post(GattStatus.READING); next(g) }

        @Deprecated("the pre-33 overload is the one every API level calls")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) { if (status == BluetoothGatt.GATT_SUCCESS) emit(c); next(g) }

        @Deprecated("the pre-33 overload is the one every API level calls")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) { emit(c) }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { next(g) }
    }

    init {
        gatt = device.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE)
        post(GattStatus.CONNECTING)
        main.postDelayed(timeout, timeoutMs)
    }

    /** GATT operations are serial: take the next declared read, skipping characteristics the device does not have. */
    @Suppress("DEPRECATION")
    private fun next(g: BluetoothGatt) {
        while (queue.isNotEmpty()) {
            val read = queue.removeFirst()
            val c = profile.uuid?.let { g.getService(UUID.fromString(it)) }?.getCharacteristic(UUID.fromString(read.char)) ?: continue
            if (read.mode == "notify") {
                g.setCharacteristicNotification(c, true)
                val d = c.getDescriptor(UUID.fromString(CCCD)) ?: continue
                d.value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                if (g.writeDescriptor(d)) return
            } else if (g.readCharacteristic(c)) return
        }
    }

    fun close() {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        gatt?.let { g -> runCatching { g.disconnect() }; runCatching { g.close() } }
        gatt = null
    }

    private companion object { const val CCCD = "00002902-0000-1000-8000-00805f9b34fb" }
}

/** What the scans of this session saw advertising (service UUIDs, manufacturer ids): the AVAILABLE state of a BLE profile. Lives for the process. */
object ScanMemory {
    val serviceUuids: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())
    val manufacturerIds: MutableSet<Int> = java.util.Collections.synchronizedSet(HashSet())

    fun remember(f: BleFound) {
        f.serviceUuids.mapNotNull { Uuid.normalize(it) }.forEach { serviceUuids += it }
        manufacturerIds += f.manufacturerData.keys
    }
}

/** The phone's side of the Connected catalogue: bonded and connected Bluetooth devices (with the Bluetooth grant), USB, input devices, features, packages. */
@SuppressLint("MissingPermission")
class AndroidConnectedFacts(private val ctx: Context) : ConnectedFacts {
    private fun bt() = if (ConnectedGrants.ble(ctx)) ctx.getSystemService(BluetoothManager::class.java) else null
    private fun uuidsOf(d: android.bluetooth.BluetoothDevice) = runCatching { d.uuids?.mapNotNull { Uuid.normalize(it.toString()) } }.getOrNull().orEmpty()

    override fun bondedServiceUuids(): Set<String> = runCatching { bt()?.adapter?.bondedDevices?.flatMap { uuidsOf(it) }?.toSet() }.getOrNull().orEmpty()
    override fun connectedServiceUuids(): Set<String> = runCatching { bt()?.getConnectedDevices(android.bluetooth.BluetoothProfile.GATT)?.flatMap { uuidsOf(it) }?.toSet() }.getOrNull().orEmpty()
    override fun scannedServiceUuids(): Set<String> = synchronized(ScanMemory.serviceUuids) { ScanMemory.serviceUuids.toSet() }
    override fun scannedManufacturerIds(): Set<Int> = synchronized(ScanMemory.manufacturerIds) { ScanMemory.manufacturerIds.toSet() }
    override fun usbClasses(): Set<Int> = UsbList.read(ctx).flatMap { it.classes }.toSet()
    override fun feature(name: String): Boolean = ctx.packageManager.hasSystemFeature(name)
    override fun packageInstalled(name: String): Boolean = runCatching { ctx.packageManager.getPackageInfo(name, 0); true }.getOrDefault(false)

    override fun input(kind: String): Boolean = runCatching {
        android.view.InputDevice.getDeviceIds().mapNotNull { android.view.InputDevice.getDevice(it) }.any { d ->
            when (kind) {
                "gamepad" -> d.supportsSource(android.view.InputDevice.SOURCE_GAMEPAD) || d.supportsSource(android.view.InputDevice.SOURCE_JOYSTICK)
                "stylus" -> d.supportsSource(android.view.InputDevice.SOURCE_STYLUS)
                "keyboard" -> d.supportsSource(android.view.InputDevice.SOURCE_KEYBOARD) && d.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC && !d.isVirtual
                else -> false
            }
        }
    }.getOrDefault(false)
}
