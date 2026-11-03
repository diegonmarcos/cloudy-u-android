package com.diegonmarcos.superapp.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import java.util.UUID

/**
 * #1407 a byte-stream link over Bluetooth for serial-style devices (an LF-RFID reader's command line): classic RFCOMM on the Serial
 * Port Profile, or a BLE UART (a service with a write characteristic and a notify characteristic, such as Nordic UART). The radios
 * stay here, in libs:bluetooth; an app only passes the device, the UUIDs and a chunker, and gets bytes in and out.
 */
interface BtSerialLink {
    fun write(bytes: ByteArray)
    fun close()
}

@SuppressLint("MissingPermission")
object BtSerial {
    /** Classic Bluetooth serial. Blocks until connected; throws when the device refuses. [onClosed] runs when the stream ends. */
    fun openSpp(device: BluetoothDevice, uuid: UUID, onBytes: (ByteArray) -> Unit, onClosed: () -> Unit): BtSerialLink {
        val s = device.createRfcommSocketToServiceRecord(uuid)
        s.connect()
        Thread {
            val b = ByteArray(512)
            runCatching { while (true) { val n = s.inputStream.read(b); if (n < 0) break; if (n > 0) onBytes(b.copyOf(n)) } }
            onClosed()
        }.apply { isDaemon = true; start() }
        return object : BtSerialLink {
            override fun write(bytes: ByteArray) { Thread { runCatching { s.outputStream.write(bytes); s.outputStream.flush() } }.start() }
            override fun close() { runCatching { s.close() } }
        }
    }

    /** BLE UART. [onReady] gets the link once the service is found and notifications are on, or null when the device has no such service. */
    fun openBleUart(
        ctx: Context, device: BluetoothDevice, service: UUID, rx: UUID, tx: UUID, cccd: UUID,
        chunker: (ByteArray, Int) -> List<ByteArray>, onBytes: (ByteArray) -> Unit, onClosed: () -> Unit, onReady: (BtSerialLink?) -> Unit,
    ) {
        var rxChar: BluetoothGattCharacteristic? = null
        var mtu = 23
        val queue = java.util.ArrayDeque<ByteArray>()
        var busy = false
        lateinit var gatt: BluetoothGatt
        fun pump() {
            val next = synchronized(queue) { if (busy) null else queue.pollFirst()?.also { busy = true } } ?: return
            val c = rxChar ?: return
            c.value = next
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            @Suppress("DEPRECATION") if (!gatt.writeCharacteristic(c)) synchronized(queue) { busy = false }
        }
        val link = object : BtSerialLink {
            override fun write(bytes: ByteArray) { synchronized(queue) { chunker(bytes, mtu).forEach { queue.addLast(it) } }; pump() }
            override fun close() { runCatching { gatt.disconnect(); gatt.close() } }
        }
        gatt = device.connectGatt(ctx, false, object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, state: Int) {
                if (state == BluetoothProfile.STATE_CONNECTED) g.requestMtu(247) else if (state == BluetoothProfile.STATE_DISCONNECTED) onClosed()
            }
            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) { mtu = m; g.discoverServices() }
            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                val svc = g.getService(service)
                val r = svc?.getCharacteristic(rx)
                val t = svc?.getCharacteristic(tx)
                if (r == null || t == null) { onReady(null); return }
                rxChar = r
                g.setCharacteristicNotification(t, true)
                t.getDescriptor(cccd)?.let { it.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(it) }
                onReady(link)
            }
            @Deprecated("API < 33 path")
            override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) { ch.value?.let(onBytes) }
            override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) { synchronized(queue) { busy = false }; pump() }
        })
    }
}
