package com.diegonmarcos.cloudcalc.measure

import java.util.UUID

// #1407 the LF readers' links, no Android: which USB-serial chip a reader carries and the control transfers that open it
// (CDC-ACM for Proxmark3 / Flipper Zero, the vendor protocols of WCH CH340/CH341 and Silicon Labs CP210x for RDM6300-style
// EM4100 boards), written from the chips' public register maps (the same sequences Linux's ch341.c and cp210x.c send), and
// the Bluetooth profiles a reader answers on. The app's LfLink replays these on a UsbDeviceConnection / BluetoothSocket / GATT.

enum class UsbChip { CDC_ACM, CH340, CP210X }

/** One USB control transfer: bmRequestType, bRequest, wValue, wIndex, data (OUT only here). */
data class Ctrl(val type: Int, val request: Int, val value: Int, val index: Int, val data: List<Byte> = emptyList())

object LfSerial {
    fun chip(vendorId: Int): UsbChip = when (vendorId) { 0x1A86, 0x4348 -> UsbChip.CH340; 0x10C4 -> UsbChip.CP210X; else -> UsbChip.CDC_ACM }

    fun reader(vendorId: Int, productId: Int, name: String?): LfReader = when {
        vendorId == 0x9AC4 || vendorId == 0x2D2D -> LfReader.PROXMARK3
        name?.contains("proxmark", true) == true || name?.contains("PM3", false) == true -> LfReader.PROXMARK3
        vendorId == 0x0483 && (productId == 0x5740 || name?.contains("flip", true) == true) -> LfReader.FLIPPER
        name?.contains("flip", true) == true -> LfReader.FLIPPER
        else -> LfReader.EM_SERIAL
    }

    /** RDM6300 and its USB clones speak 9600 baud; Proxmark3 and Flipper ignore the rate over CDC, 115200 is their convention. */
    fun baud(r: LfReader) = if (r == LfReader.EM_SERIAL) 9600 else 115200

    private fun le32(v: Int) = listOf(v, v shr 8, v shr 16, v shr 24).map { it.toByte() }

    /** CDC-ACM SET_LINE_CODING (baud, 1 stop bit, no parity, 8 data bits), then SET_CONTROL_LINE_STATE DTR + RTS. */
    fun cdc(baud: Int, iface: Int = 0) = listOf(Ctrl(0x21, 0x20, 0, iface, le32(baud) + listOf<Byte>(0, 0, 8)), Ctrl(0x21, 0x22, 0x03, iface))

    /** CP210x: IFC_ENABLE, SET_BAUDRATE (32-bit LE), SET_LINE_CTL 8N1, SET_MHS with DTR + RTS and their write masks. */
    fun cp210x(baud: Int, iface: Int = 0) = listOf(Ctrl(0x41, 0x00, 0x0001, iface), Ctrl(0x41, 0x1E, 0, iface, le32(baud)),
        Ctrl(0x41, 0x03, 0x0800, iface), Ctrl(0x41, 0x07, 0x0303, iface))

    /** CH340 baud registers 0x1312 (factor high byte | prescaler with bit 7) and 0x0F2C (factor low byte). */
    fun ch340Divisor(baud: Int): Pair<Int, Int> {
        require(baud in 50..2_000_000) { "baud 50-2000000" }
        var factor = 1_532_620_800L / baud
        var divisor = 3
        while (factor > 0xFFF0 && divisor > 0) { factor = factor shr 3; divisor-- }
        require(factor <= 0xFFF0) { "baud $baud too low for a CH340" }
        val f = 0x10000 - factor.toInt()
        return ((f and 0xFF00) or divisor or 0x80) to (f and 0xFF)
    }

    /** CH340: serial init (0xA1), 8N1 enabled (LCR 0xC3 into 0x2518), the baud registers, then DTR + RTS (0xA4, active low). */
    fun ch340(baud: Int): List<Ctrl> {
        val (a, b) = ch340Divisor(baud)
        return listOf(Ctrl(0x40, 0xA1, 0, 0), Ctrl(0x40, 0x9A, 0x2518, 0x00C3), Ctrl(0x40, 0x9A, 0x1312, a), Ctrl(0x40, 0x9A, 0x0F2C, b),
            Ctrl(0x40, 0xA4, (0x20 or 0x40).inv() and 0xFF, 0))
    }

    fun init(chip: UsbChip, baud: Int, iface: Int = 0) = when (chip) { UsbChip.CDC_ACM -> cdc(baud, iface); UsbChip.CH340 -> ch340(baud); UsbChip.CP210X -> cp210x(baud, iface) }

    /** Classic Bluetooth serial (Proxmark3 RDV4 Blue Shark, HC-05/06 boards): RFCOMM on the Serial Port Profile UUID. */
    val SPP: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    /** BLE UART (Nordic UART Service): the phone writes RX, the reader notifies TX. */
    val NUS_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
    val NUS_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
    val NUS_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /** A BLE write carries at most mtu - 3 bytes; a command longer than that goes out in order, in chunks. */
    fun chunks(bytes: ByteArray, mtu: Int): List<ByteArray> {
        val n = (mtu - 3).coerceAtLeast(1)
        return (bytes.indices step n).map { bytes.copyOfRange(it, minOf(it + n, bytes.size)) }
    }
}
