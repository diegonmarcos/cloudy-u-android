package com.diegonmarcos.cloudcalc.measure

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

// #1407 the reader links: chip and reader detection, the CDC / CP210x / CH340 init transfers, BLE chunking. Golden bytes.
class LfSerialTest {
    private fun b(vararg v: Int) = v.map { it.toByte() }

    @Test fun `chip and reader by USB ids`() {
        assertEquals(UsbChip.CH340, LfSerial.chip(0x1A86)); assertEquals(UsbChip.CH340, LfSerial.chip(0x4348))
        assertEquals(UsbChip.CP210X, LfSerial.chip(0x10C4)); assertEquals(UsbChip.CDC_ACM, LfSerial.chip(0x9AC4))
        assertEquals(LfReader.PROXMARK3, LfSerial.reader(0x9AC4, 0x4B8F, null))
        assertEquals(LfReader.PROXMARK3, LfSerial.reader(0x2D2D, 0x504D, null))
        assertEquals(LfReader.PROXMARK3, LfSerial.reader(0, 0, "Proxmark3 RDV4"))
        assertEquals(LfReader.PROXMARK3, LfSerial.reader(0, 0, "PM3_RDV4.0"))
        assertEquals(LfReader.EM_SERIAL, LfSerial.reader(0, 0, "pm3"))
        assertEquals(LfReader.FLIPPER, LfSerial.reader(0x0483, 0x5740, null))
        assertEquals(LfReader.FLIPPER, LfSerial.reader(0x0483, 1, "Flipper Zero"))
        assertEquals(LfReader.FLIPPER, LfSerial.reader(0, 0, "Flipper Abc"))
        assertEquals(LfReader.EM_SERIAL, LfSerial.reader(0x0483, 1, "STM32 VCP"))
        assertEquals(LfReader.EM_SERIAL, LfSerial.reader(0x1A86, 0x7523, null))
        assertEquals(9600, LfSerial.baud(LfReader.EM_SERIAL)); assertEquals(115200, LfSerial.baud(LfReader.PROXMARK3)); assertEquals(115200, LfSerial.baud(LfReader.FLIPPER))
    }

    @Test fun `CDC-ACM line coding and DTR RTS`() {
        assertEquals(listOf(Ctrl(0x21, 0x20, 0, 0, b(0x00, 0xC2, 0x01, 0x00, 0, 0, 8)), Ctrl(0x21, 0x22, 3, 0)), LfSerial.cdc(115200))
        assertEquals(b(0x80, 0x25, 0, 0, 0, 0, 8), LfSerial.cdc(9600, 2)[0].data)
        assertEquals(2, LfSerial.cdc(9600, 2)[1].index)
        assertEquals(LfSerial.cdc(9600, 1), LfSerial.init(UsbChip.CDC_ACM, 9600, 1))
    }

    @Test fun `CP210x enable, baud, 8N1, modem lines`() {
        assertEquals(listOf(Ctrl(0x41, 0x00, 1, 0), Ctrl(0x41, 0x1E, 0, 0, b(0x80, 0x25, 0, 0)), Ctrl(0x41, 0x03, 0x0800, 0), Ctrl(0x41, 0x07, 0x0303, 0)), LfSerial.cp210x(9600))
        assertEquals(b(0x00, 0x12, 0x7A, 0x00), LfSerial.cp210x(8_000_000 / 1)[1].data)
        assertEquals(LfSerial.cp210x(9600, 1), LfSerial.init(UsbChip.CP210X, 9600, 1))
    }

    @Test fun `CH340 divisor golden and init sequence`() {
        assertEquals(0xCC83 to 0x08, LfSerial.ch340Divisor(115200))
        assertEquals(0xB282 to 0x0C, LfSerial.ch340Divisor(9600))
        assertEquals(0x1680 to 0x24, LfSerial.ch340Divisor(50))
        assertEquals(0xFD83 to 0x02, LfSerial.ch340Divisor(2_000_000))
        assertThrows(IllegalArgumentException::class.java) { LfSerial.ch340Divisor(49) }
        assertThrows(IllegalArgumentException::class.java) { LfSerial.ch340Divisor(2_000_001) }
        assertEquals(listOf(Ctrl(0x40, 0xA1, 0, 0), Ctrl(0x40, 0x9A, 0x2518, 0xC3), Ctrl(0x40, 0x9A, 0x1312, 0xB282), Ctrl(0x40, 0x9A, 0x0F2C, 0x0C), Ctrl(0x40, 0xA4, 0x9F, 0)), LfSerial.ch340(9600))
        assertEquals(LfSerial.ch340(9600), LfSerial.init(UsbChip.CH340, 9600, 3))
    }

    @Test fun `BLE chunks and UUIDs`() {
        val bytes = "lf t55xx write -b 1 -d 00148040\n".toByteArray()
        val c = LfSerial.chunks(bytes, 23)
        assertEquals(listOf(20, 12), c.map { it.size })
        assertArrayEquals(bytes, c.fold(ByteArray(0)) { a, x -> a + x })
        assertEquals(listOf(1, 1), LfSerial.chunks(byteArrayOf(1, 2), 2).map { it.size })
        assertEquals(1, LfSerial.chunks(bytes, 247).size)
        assertEquals(0, LfSerial.chunks(ByteArray(0), 23).size)
        assertEquals("00001101-0000-1000-8000-00805f9b34fb", LfSerial.SPP.toString())
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", LfSerial.NUS_RX.toString())
        assertEquals("6e400003-b5a3-f393-e0a9-e50e24dcca9e", LfSerial.NUS_TX.toString())
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", LfSerial.NUS_SERVICE.toString())
        assertEquals("00002902-0000-1000-8000-00805f9b34fb", LfSerial.CCCD.toString())
    }
}
