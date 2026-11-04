package com.diegonmarcos.superapp.usbdrive

import android.content.Context
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.driver.BlockDeviceDriver
import me.jahnen.libaums.core.driver.BlockDeviceDriverFactory
import me.jahnen.libaums.core.usb.UsbCommunication
import java.io.Closeable
import java.io.IOException
import java.nio.ByteBuffer

/**
 * #1444 the WHOLE disk of a USB mass-storage drive as a block device (SCSI READ/WRITE(10) over the bulk endpoints,
 * through Android's UsbManager grant: no root). [RawUsbDrive] only reaches a FAT32 partition; this reaches LBA 0, which
 * a partition table and boot code (Ventoy) need. Opening claims the interface with force, so Android unmounts the drive;
 * [close] releases it and Android mounts the new layout. 512-byte logical sectors only: the MBR's LBAs are in sectors.
 */
class RawDiskWriter private constructor(private val comm: UsbCommunication, private val bd: BlockDeviceDriver) : Closeable {

    val sectors: Long get() = bd.blocks
    val capacityBytes: Long get() = bd.blocks * bd.blockSize

    /** Writes [len] bytes of [data] at [byteOffset]; both must be multiples of the sector size. */
    fun write(byteOffset: Long, data: ByteArray, off: Int = 0, len: Int = data.size - off) {
        require(byteOffset % SECTOR == 0L && len % SECTOR == 0) { "writes are whole sectors" }
        require(byteOffset >= 0 && byteOffset + len <= capacityBytes) { "write past the end of the drive" }
        var done = 0
        while (done < len) {
            val n = minOf(len - done, CHUNK)
            bd.write((byteOffset + done) / SECTOR, ByteBuffer.wrap(data, off + done, n))
            done += n
        }
    }

    fun read(byteOffset: Long, len: Int): ByteArray {
        require(byteOffset % SECTOR == 0L && len % SECTOR == 0) { "reads are whole sectors" }
        val out = ByteArray(len)
        var done = 0
        while (done < len) {
            val n = minOf(len - done, CHUNK)
            bd.read((byteOffset + done) / SECTOR, ByteBuffer.wrap(out, done, n))
            done += n
        }
        return out
    }

    override fun close() { runCatching { comm.close() } }

    companion object {
        const val SECTOR = 512
        const val CHUNK = 64 * 1024

        /** Opens [device] (USB permission already granted: PermsUsb.request). Blocking. */
        fun open(ctx: Context, device: UsbDevice): RawDiskWriter {
            val um = ctx.getSystemService(Context.USB_SERVICE) as UsbManager
            check(um.hasPermission(device)) { "no USB permission for ${device.deviceName}" }
            val iface = (0 until device.interfaceCount).map { device.getInterface(it) }.firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_MASS_STORAGE && it.interfaceSubclass == 6 && it.interfaceProtocol == 80
            } ?: throw IOException("not a SCSI bulk-only mass-storage drive")
            var out: UsbEndpoint? = null
            var inn: UsbEndpoint? = null
            for (i in 0 until iface.endpointCount) iface.getEndpoint(i).let { e ->
                if (e.type == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (e.direction == UsbConstants.USB_DIR_OUT) out = e else inn = e }
            }
            val comm = JavaUsbCommunication(um, device, iface, out ?: throw IOException("no bulk OUT endpoint"), inn ?: throw IOException("no bulk IN endpoint"))
            try {
                val bd = BlockDeviceDriverFactory.createBlockDevice(comm, 0)
                bd.init()
                if (bd.blockSize != SECTOR) throw IOException("the drive has ${bd.blockSize}-byte sectors; Ventoy's MBR layout needs 512")
                return RawDiskWriter(comm, bd)
            } catch (t: Throwable) {
                runCatching { comm.close() }
                throw t
            }
        }
    }
}
