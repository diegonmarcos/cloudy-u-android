package com.diegonmarcos.clouddrive.usb

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import me.jahnen.libaums.core.usb.PipeException
import me.jahnen.libaums.core.usb.UsbCommunication
import me.jahnen.libaums.core.usb.UsbCommunicationCreator
import me.jahnen.libaums.core.usb.UsbCommunicationFactory
import java.io.IOException
import java.nio.ByteBuffer

/**
 * 16 KB page size: libaums' default transport (JellyBeanMr2Communication over AndroidUsbCommunication) loads two JNI
 * helpers, libusb-lib.so (USBDEVFS_CLEAR_HALT / USBDEVFS_RESET ioctls) and liberrno-lib.so (errno, to tell a stall from
 * other errors). Both ship in core 0.10.0 (the latest release) linked with 4 KB LOAD segments, so a 16 KB phone flags the
 * whole app. app/build.gradle drops the two .so files (build.json::usb_drives.raw_library.native_excludes) and this
 * transport does the same work through UsbDeviceConnection alone:
 *  - clear halt = the standard CLEAR_FEATURE(ENDPOINT_HALT) control request, which is what the ioctl sends;
 *  - stall detection = GET_STATUS on the endpoint after a failed bulk transfer (bit 0 = halted) instead of errno == EPIPE,
 *    so ScsiBlockDevice still gets a PipeException and runs its bulk-only reset + clear-halt recovery.
 * resetDevice has no Java equivalent of USBDEVFS_RESET; libaums 0.10.0 never calls it, so it re-claims the interface.
 */
class JavaUsbCommunication(
    usbManager: UsbManager,
    usbDevice: UsbDevice,
    override val usbInterface: UsbInterface,
    override val outEndpoint: UsbEndpoint,
    override val inEndpoint: UsbEndpoint,
) : UsbCommunication {

    private val conn: UsbDeviceConnection = usbManager.openDevice(usbDevice) ?: throw IOException("deviceConnection is null!")
    @Volatile private var closed = false

    init {
        if (!conn.claimInterface(usbInterface, true)) { conn.close(); throw IOException("could not claim interface!") }
    }

    private fun bulk(ep: UsbEndpoint, buf: ByteBuffer): Int {
        check(!closed) { "device is closed" }
        val n = conn.bulkTransfer(ep, buf.array(), buf.arrayOffset() + buf.position(), buf.remaining(), UsbCommunication.TRANSFER_TIMEOUT)
        if (n < 0) {
            if (isHalted(ep)) throw PipeException()
            throw IOException("bulk transfer failed on endpoint 0x%02x".format(ep.address))
        }
        buf.position(buf.position() + n)
        return n
    }

    override fun bulkOutTransfer(src: ByteBuffer): Int = bulk(outEndpoint, src)
    override fun bulkInTransfer(dest: ByteBuffer): Int = bulk(inEndpoint, dest)

    override fun controlTransfer(requestType: Int, request: Int, value: Int, index: Int, buffer: ByteArray, length: Int): Int {
        check(!closed) { "device is closed" }
        return conn.controlTransfer(requestType, request, value, index, buffer, length, UsbCommunication.TRANSFER_TIMEOUT)
    }

    /** GET_STATUS(endpoint): bit 0 of the two status bytes is the HALT feature. */
    private fun isHalted(ep: UsbEndpoint): Boolean {
        val st = ByteArray(2)
        val n = conn.controlTransfer(UsbConstants.USB_DIR_IN or REQ_RECIPIENT_ENDPOINT, REQ_GET_STATUS, 0, ep.address, st, 2, UsbCommunication.TRANSFER_TIMEOUT)
        return n == 2 && (st[0].toInt() and 1) == 1
    }

    override fun clearFeatureHalt(endpoint: UsbEndpoint) {
        check(!closed) { "device is closed" }
        val r = conn.controlTransfer(REQ_RECIPIENT_ENDPOINT, REQ_CLEAR_FEATURE, FEATURE_ENDPOINT_HALT, endpoint.address, null, 0, UsbCommunication.TRANSFER_TIMEOUT)
        if (r < 0) android.util.Log.e(TAG, "clear halt failed on endpoint 0x%02x".format(endpoint.address))
    }

    override fun resetDevice() {
        check(!closed) { "device is closed" }
        conn.releaseInterface(usbInterface)
        if (!conn.claimInterface(usbInterface, true)) throw IOException("could not re-claim interface")
    }

    override fun close() {
        if (closed) return
        closed = true
        runCatching { conn.releaseInterface(usbInterface) }
        conn.close()
    }

    companion object {
        private const val TAG = "JavaUsbCommunication"
        private const val REQ_RECIPIENT_ENDPOINT = 0x02
        private const val REQ_GET_STATUS = 0x00
        private const val REQ_CLEAR_FEATURE = 0x01
        private const val FEATURE_ENDPOINT_HALT = 0x00

        @Volatile private var installed = false

        /** Makes libaums build every transport through this class (OTHER mode); idempotent. */
        @Synchronized fun install() {
            if (installed) return
            UsbCommunicationFactory.registerCommunication(object : UsbCommunicationCreator {
                override fun create(usbManager: UsbManager, usbDevice: UsbDevice, usbInterface: UsbInterface,
                                    outEndpoint: UsbEndpoint, inEndpoint: UsbEndpoint): UsbCommunication =
                    JavaUsbCommunication(usbManager, usbDevice, usbInterface, outEndpoint, inEndpoint)
            })
            UsbCommunicationFactory.underlyingUsbCommunication = UsbCommunicationFactory.UnderlyingUsbCommunication.OTHER
            installed = true
        }
    }
}
