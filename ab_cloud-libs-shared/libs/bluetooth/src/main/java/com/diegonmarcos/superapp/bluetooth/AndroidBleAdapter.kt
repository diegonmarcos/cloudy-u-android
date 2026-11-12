package com.diegonmarcos.superapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import java.util.UUID

/**
 * The phone's radio behind the [BleAdapter] port: the LE scanner, one [BluetoothGatt] per device, bonding, and the bonded list
 * (classic devices included). Every call is guarded: a missing permission or a dead adapter answers false (or an empty list)
 * instead of throwing, and the page shows the permission row. Callbacks arrive on binder threads; [GattSession] is thread-safe.
 */
@SuppressLint("MissingPermission")
class AndroidBleAdapter(context: Context) : BleAdapter {
    /** #1666 the app's location-use ledger (scans run only while the Bluetooth page or a web page's chooser is on screen). */
    private val ctxForLedger: Context = context.applicationContext
    companion object { const val SITE = "libs:bluetooth AndroidBleAdapter (Bluetooth page / Web Bluetooth chooser)" }
    private val ctx = context.applicationContext
    private val manager = ctx.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val bt: BluetoothAdapter? get() = manager?.adapter
    private val gatts = HashMap<String, BluetoothGatt>()
    private var scanCb: ScanCallback? = null
    override var listener: GattListener? = null

    override val supported: Boolean get() = bt != null && ctx.packageManager.hasSystemFeature("android.hardware.bluetooth_le")
    override val enabled: Boolean get() = runCatching { bt?.isEnabled == true }.getOrDefault(false)

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, i: Intent?) {
            val dev = i?.let { deviceOf(it) } ?: return
            listener?.onBond(dev.address, i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE))
        }
    }
    private var bondRegistered = false

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? =
        if (Build.VERSION.SDK_INT >= 33) i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
        else i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun ensureBondReceiver() {
        if (bondRegistered) return
        bondRegistered = true
        val f = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(bondReceiver, f, Context.RECEIVER_NOT_EXPORTED) else ctx.registerReceiver(bondReceiver, f)
    }

    // ---- scan ----

    override fun startScan(onResult: (BleDevice) -> Unit, onFailed: (String) -> Unit): Boolean {
        val scanner = runCatching { bt?.bluetoothLeScanner }.getOrNull() ?: return false
        stopScan()
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) { onResult(toDevice(result)) }
            override fun onBatchScanResults(results: MutableList<ScanResult>) { results.forEach { onResult(toDevice(it)) } }
            override fun onScanFailed(errorCode: Int) { onFailed("scan failed, code $errorCode") }
        }
        return runCatching {
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            // #1264 extended advertising (BT 5) when the controller has it: the analyzer sees long payloads and coded-PHY devices.
            if (Build.VERSION.SDK_INT >= 26 && runCatching { bt?.isLeExtendedAdvertisingSupported == true }.getOrDefault(false)) {
                settings.setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED)
            }
            com.diegonmarcos.superapp.perms.LocationUse.note(ctxForLedger, SITE, "BluetoothLeScanner.startScan (BLUETOOTH_SCAN neverForLocation)",
                com.diegonmarcos.superapp.perms.LocationUse.Trigger.SCREEN)
            scanner.startScan(null, settings.build(), cb)
            scanCb = cb
            true
        }.getOrDefault(false)
    }

    override fun stopScan() {
        val cb = scanCb ?: return
        scanCb = null
        runCatching { bt?.bluetoothLeScanner?.stopScan(cb) }
    }

    private fun toDevice(r: ScanResult): BleDevice {
        val rec = r.scanRecord
        val manu = HashMap<Int, String>()
        rec?.manufacturerSpecificData?.let { sp -> for (i in 0 until sp.size()) manu[sp.keyAt(i)] = Hex.format(sp.valueAt(i)) }
        val connectable = if (Build.VERSION.SDK_INT >= 26) r.isConnectable else true
        return BleDevice(
            address = r.device.address,
            name = runCatching { r.device.name }.getOrNull() ?: rec?.deviceName,
            rssi = r.rssi,
            serviceUuids = rec?.serviceUuids?.mapNotNull { Uuids.normalize(it.uuid.toString()) }.orEmpty(),
            manufacturer = manu,
            txPower = rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE },
            connectable = connectable,
            bonded = r.device.bondState == BluetoothDevice.BOND_BONDED,
            lastSeenMs = System.currentTimeMillis(),
            raw = rec?.bytes?.let { Hex.format(it, "") }.orEmpty(),
            phy = if (Build.VERSION.SDK_INT >= 26) phyName(r.primaryPhy) + (if (r.secondaryPhy != ScanResult.PHY_UNUSED) "/" + phyName(r.secondaryPhy) else "") +
                (if (!r.isLegacy) " ext" else "") else "",
        )
    }

    private fun phyName(p: Int): String = when (p) { 1 -> "1M"; 2 -> "2M"; 3 -> "Coded"; else -> "-" }

    // ---- classic discovery (#1264) ----

    private var discoveryReceiver: BroadcastReceiver? = null

    @Suppress("DEPRECATION")
    override fun startDiscovery(onFound: (BleDevice) -> Unit, onDone: () -> Unit): Boolean = runCatching {
        val a = bt ?: return false
        cancelDiscovery()
        com.diegonmarcos.superapp.perms.LocationUse.note(ctxForLedger, SITE, "BluetoothAdapter.startDiscovery", com.diegonmarcos.superapp.perms.LocationUse.Trigger.SCREEN)
        val rx = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, i: Intent?) {
                when (i?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val dev = deviceOf(i) ?: return
                        val rssi = i.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val cls: android.bluetooth.BluetoothClass? = if (Build.VERSION.SDK_INT >= 33)
                            i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, android.bluetooth.BluetoothClass::class.java)
                        else i.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
                        onFound(BleDevice(dev.address, runCatching { dev.name }.getOrNull() ?: i.getStringExtra(BluetoothDevice.EXTRA_NAME),
                            if (rssi == Short.MIN_VALUE.toInt()) -127 else rssi, bonded = runCatching { dev.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false),
                            classic = runCatching { dev.type != BluetoothDevice.DEVICE_TYPE_LE }.getOrDefault(true), lastSeenMs = System.currentTimeMillis(), deviceClass = cls?.deviceClass))
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> { onDone(); cancelDiscovery() }
                }
            }
        }
        val f = IntentFilter(BluetoothDevice.ACTION_FOUND).apply { addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED) }
        // ACTION_FOUND comes from the system Bluetooth process: the receiver must be exported to hear it.
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(rx, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(rx, f)
        discoveryReceiver = rx
        if (!a.startDiscovery()) { cancelDiscovery(); return false }
        true
    }.getOrDefault(false)

    override fun cancelDiscovery() {
        runCatching { bt?.cancelDiscovery() }
        discoveryReceiver?.let { runCatching { ctx.unregisterReceiver(it) } }
        discoveryReceiver = null
    }

    override fun bonded(): List<BleDevice> = runCatching {
        bt?.bondedDevices.orEmpty().map {
            BleDevice(it.address, it.name, rssi = -127, bonded = true, classic = it.type == BluetoothDevice.DEVICE_TYPE_CLASSIC,
                deviceClass = runCatching { it.bluetoothClass?.deviceClass }.getOrNull())
        }
    }.getOrDefault(emptyList())

    // ---- gatt ----

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            val a = gatt.device.address
            val s = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> ConnState.Connected
                BluetoothProfile.STATE_CONNECTING -> ConnState.Connecting
                BluetoothProfile.STATE_DISCONNECTING -> ConnState.Disconnecting
                else -> ConnState.Disconnected
            }
            if (s == ConnState.Disconnected) { synchronized(gatts) { gatts.remove(a) }; runCatching { gatt.close() } }
            listener?.onConnectionState(a, s, status)
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val services = gatt.services.map { s ->
                GattService(norm(s.uuid), s.characteristics.map { c ->
                    GattCharacteristic(norm(s.uuid), norm(c.uuid), c.properties, c.descriptors.map { GattDescriptor(norm(it.uuid)) })
                })
            }
            listener?.onServices(gatt.device.address, services, status)
        }

        @Deprecated("API 33 replaced it")
        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            if (Build.VERSION.SDK_INT < 33) read(gatt, c, c.value ?: ByteArray(0), status)
        }
        override fun onCharacteristicRead(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) = read(gatt, c, value, status)
        private fun read(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray, status: Int) =
            listener?.onRead(g.device.address, norm(c.service.uuid), norm(c.uuid), v, status) ?: Unit

        override fun onCharacteristicWrite(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            listener?.onWrite(gatt.device.address, norm(c.service.uuid), norm(c.uuid), status)
        }

        @Deprecated("API 33 replaced it")
        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) changed(gatt, c, c.value ?: ByteArray(0))
        }
        override fun onCharacteristicChanged(gatt: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) = changed(gatt, c, value)
        private fun changed(g: BluetoothGatt, c: BluetoothGattCharacteristic, v: ByteArray) =
            listener?.onChanged(g.device.address, norm(c.service.uuid), norm(c.uuid), v) ?: Unit

        @Deprecated("API 33 replaced it")
        override fun onDescriptorRead(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            if (Build.VERSION.SDK_INT < 33) descRead(gatt, d, d.value ?: ByteArray(0), status)
        }
        override fun onDescriptorRead(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int, value: ByteArray) = descRead(gatt, d, value, status)
        private fun descRead(g: BluetoothGatt, d: BluetoothGattDescriptor, v: ByteArray, status: Int) =
            listener?.onDescriptorRead(g.device.address, norm(d.characteristic.service.uuid), norm(d.characteristic.uuid), norm(d.uuid), v, status) ?: Unit

        override fun onDescriptorWrite(gatt: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            val a = gatt.device.address
            val c = d.characteristic
            if (Uuids.assigned(norm(d.uuid)) == 0x2902 && pendingNotify.remove(a + "|" + norm(c.uuid)) != null) {
                val mode = pendingMode.remove(a + "|" + norm(c.uuid)) ?: NotifyMode.Off
                listener?.onNotifySet(a, norm(c.service.uuid), norm(c.uuid), mode, status)
            } else listener?.onDescriptorWrite(a, norm(c.service.uuid), norm(c.uuid), norm(d.uuid), status)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) { listener?.onMtu(gatt.device.address, mtu, status) }
        override fun onReadRemoteRssi(gatt: BluetoothGatt, rssi: Int, status: Int) { listener?.onRssi(gatt.device.address, rssi, status) }
    }

    private val pendingNotify = HashMap<String, Boolean>()
    private val pendingMode = HashMap<String, NotifyMode>()

    private fun norm(u: UUID): String = u.toString().lowercase()
    private fun gatt(a: String): BluetoothGatt? = synchronized(gatts) { gatts[a] }

    private fun characteristicOf(a: String, service: String, char: String): BluetoothGattCharacteristic? =
        gatt(a)?.getService(UUID.fromString(service))?.getCharacteristic(UUID.fromString(char))

    override fun connect(address: String): Boolean = runCatching {
        val dev = bt?.getRemoteDevice(address) ?: return false
        ensureBondReceiver()
        val g = if (Build.VERSION.SDK_INT >= 23) dev.connectGatt(ctx, false, callback, BluetoothDevice.TRANSPORT_LE) else dev.connectGatt(ctx, false, callback)
        synchronized(gatts) { gatts[address] = g }
        true
    }.getOrDefault(false)

    override fun disconnect(address: String) {
        val g = gatt(address)
        if (g == null) { listener?.onConnectionState(address, ConnState.Disconnected, 0); return }
        runCatching { g.disconnect() }
    }

    override fun discover(address: String): Boolean = runCatching { gatt(address)?.discoverServices() == true }.getOrDefault(false)

    override fun read(address: String, service: String, char: String): Boolean = runCatching {
        val c = characteristicOf(address, service, char) ?: return false
        gatt(address)?.readCharacteristic(c) == true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun write(address: String, service: String, char: String, value: ByteArray, withResponse: Boolean): Boolean = runCatching {
        val g = gatt(address) ?: return false
        val c = characteristicOf(address, service, char) ?: return false
        val type = if (withResponse) BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT else BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        val started = if (Build.VERSION.SDK_INT >= 33) g.writeCharacteristic(c, value, type) == 0
        else { c.writeType = type; c.value = value; g.writeCharacteristic(c) }
        // A write without response has no callback: tell the session now that it left.
        if (started && !withResponse) listener?.onWrite(address, service, char, 0)
        started
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun setNotify(address: String, service: String, char: String, mode: NotifyMode): Boolean = runCatching {
        val g = gatt(address) ?: return false
        val c = characteristicOf(address, service, char) ?: return false
        if (!g.setCharacteristicNotification(c, mode != NotifyMode.Off)) return false
        val cccd = c.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: run {
            // No CCCD to write: local delivery is all there is.
            listener?.onNotifySet(address, service, char, mode, 0); return true
        }
        val v = when (mode) {
            NotifyMode.Off -> BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            NotifyMode.Notify -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            NotifyMode.Indicate -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        pendingNotify[address + "|" + char] = true
        pendingMode[address + "|" + char] = mode
        val started = if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, v) == 0 else { cccd.value = v; g.writeDescriptor(cccd) }
        if (!started) { pendingNotify.remove(address + "|" + char); pendingMode.remove(address + "|" + char) }
        started
    }.getOrDefault(false)

    override fun readDescriptor(address: String, service: String, char: String, desc: String): Boolean = runCatching {
        val d = characteristicOf(address, service, char)?.getDescriptor(UUID.fromString(desc)) ?: return false
        gatt(address)?.readDescriptor(d) == true
    }.getOrDefault(false)

    @Suppress("DEPRECATION")
    override fun writeDescriptor(address: String, service: String, char: String, desc: String, value: ByteArray): Boolean = runCatching {
        val g = gatt(address) ?: return false
        val d = characteristicOf(address, service, char)?.getDescriptor(UUID.fromString(desc)) ?: return false
        if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(d, value) == 0 else { d.value = value; g.writeDescriptor(d) }
    }.getOrDefault(false)

    override fun requestMtu(address: String, mtu: Int): Boolean = runCatching { gatt(address)?.requestMtu(mtu) == true }.getOrDefault(false)
    override fun readRssi(address: String): Boolean = runCatching { gatt(address)?.readRemoteRssi() == true }.getOrDefault(false)

    override fun bond(address: String): Boolean = runCatching {
        ensureBondReceiver()
        bt?.getRemoteDevice(address)?.createBond() == true
    }.getOrDefault(false)

    /** Drops every connection and receiver (the host calls it when it goes away). */
    fun close() {
        stopScan()
        cancelDiscovery()
        synchronized(gatts) { gatts.values.forEach { runCatching { it.close() } }; gatts.clear() }
        if (bondRegistered) { runCatching { ctx.unregisterReceiver(bondReceiver) }; bondRegistered = false }
    }
}
