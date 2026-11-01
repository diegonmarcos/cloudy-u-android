package com.diegonmarcos.cloudcalc.measure

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

// #1407 LF-RFID decoders and command builders: golden values, so PIT's mutants turn these red.
class LfRfidTest {
    @Test fun `EM4100 frame golden, round trip, and every parity checked`() {
        assertEquals(0xFF80000000000000uL.toLong(), Em4100.encodeFrame("0000000000"))
        // 0000000001: last row 0001+1, column parity 0001, stop 0
        assertEquals(0xFF80000000000000uL.toLong() or 0x0000000000000062L, Em4100.encodeFrame("0000000001"))
        for (id in listOf("0F0368568B", "FFFFFFFFFF", "1234567890", "A5A5A5A5A5")) assertEquals(id, Em4100.decodeFrame(Em4100.encodeFrame(id)))
        val f = Em4100.encodeFrame("0F0368568B")
        for (bit in 0 until 64) assertNull("flip $bit", Em4100.decodeFrame(f xor (1L shl bit)))
        assertTrue(Em4100.valid("0f0368568b")); assertFalse(Em4100.valid("0F0368568")); assertFalse(Em4100.valid("0F0368568G"))
        assertThrows(IllegalArgumentException::class.java) { Em4100.encodeFrame("XYZ") }
    }

    @Test fun `EM4100 fields and RDM6300 frame`() {
        assertEquals(mapOf("Version" to "0F", "Decimal" to "57169547", "Wiegand 26" to "104,22155"), Em4100.fields("0F0368568B"))
        fun frame(body: String) = byteArrayOf(0x00, 0x02) + body.toByteArray() + byteArrayOf(0x03)
        // XOR of 0F 03 68 56 8B = 0xB9
        assertEquals("0F0368568B", Em4100.parseRdm6300(frame("0F0368568BB9")))
        assertNull(Em4100.parseRdm6300(frame("0F0368568BB8")))
        assertNull(Em4100.parseRdm6300(frame("0F0368568BB")))
        assertNull(Em4100.parseRdm6300(frame("0F0368568BZ7")))
        assertNull(Em4100.parseRdm6300(byteArrayOf(0x02) + "0F0368568BB9".toByteArray() + byteArrayOf(0x04)))
    }

    @Test fun `HID H10301 parity golden and decode`() {
        assertEquals(0x2020002, Hid26.encode(1, 1))
        assertEquals(0x0000001, Hid26.encode(0, 0))
        assertEquals(1 to 1, Hid26.decode(0x2020002))
        for (fc in listOf(0, 7, 118, 255)) for (cn in listOf(0, 1, 1603, 65535)) assertEquals(fc to cn, Hid26.decode(Hid26.encode(fc, cn)))
        val raw = Hid26.encode(118, 1603)
        for (bit in 0 until 26) if (bit == 0 || bit == 25) assertNull(Hid26.decode(raw xor (1 shl bit)))
        assertNull(Hid26.decode(raw or (1 shl 26)))
        assertThrows(IllegalArgumentException::class.java) { Hid26.encode(256, 0) }
        assertThrows(IllegalArgumentException::class.java) { Hid26.encode(0, 65536) }
        assertThrows(IllegalArgumentException::class.java) { Hid26.encode(-1, 0) }
    }

    @Test fun `FDX-B country and national ID`() {
        val code = FdxB.encode(999, 112233)
        assertEquals(Long.MIN_VALUE or (999L shl 38) or 112233L, code)
        assertEquals(999 to 112233L, FdxB.decode(code))
        assertEquals(999L shl 38, FdxB.encode(999, 0, animal = false))
        assertEquals(1023 to 274877906943L, FdxB.decode(-1L))
        assertEquals("999-000000112233", FdxB.format(999, 112233))
        assertThrows(IllegalArgumentException::class.java) { FdxB.encode(1000, 0) }
        assertThrows(IllegalArgumentException::class.java) { FdxB.encode(0, 274877906944L) }
    }

    @Test fun `T5577 block 0 config parse`() {
        assertEquals(T5577Config(64, "Manchester", 2, false, false, false, false, 0), T5577.parseConfig("00148040"))
        assertEquals(T5577Config(50, "FSK2a", 3, false, false, false, false, 0), T5577.parseConfig("00107060"))
        assertEquals(T5577Config(8, "Direct", 7, true, true, true, true, 6), T5577.parseConfig("600003F8"))
        assertEquals("Unknown(31)", T5577.parseConfig("0001F000").modulation)
        assertEquals(128, T5577.parseConfig("001C0000").rate)
        assertThrows(IllegalArgumentException::class.java) { T5577.block("0014804") }
    }

    @Test fun `T5577 dump parse and EM4100 clone blocks`() {
        val dump = """
            [=] blk | hex data | binary
            [=] ----+----------+---------
            [=]  00 | 00148040 | 00000000000101001000000001000000 | ..@
            [=]  01 | FF8C65B0 | 1111 | x
            [=]  02 | 2D8F3A28 | 0010 | x
            [=]  01 | 00000000 | page 1 repeat
            [=]  08 | 00000000 | out of page
        """.trimIndent()
        assertEquals(listOf("00148040", "FF8C65B0", "2D8F3A28"), T5577.parseDump(dump))
        assertEquals(emptyList<String>(), T5577.parseDump("nothing here"))
        val b = T5577.em4100Blocks("0F0368568B")
        val f = Em4100.encodeFrame("0F0368568B")
        assertEquals(listOf("00148040", "%08X".format(f ushr 32), "%08X".format(f and 0xFFFFFFFFL)), b)
        assertEquals("FF800000", T5577.em4100Blocks("0000000000")[1])
        assertEquals("00000000", T5577.em4100Blocks("0000000000")[2])
    }

    @Test fun `command builders`() {
        assertEquals("lf search", LfCommands.search(LfReader.PROXMARK3))
        assertEquals("rfid read", LfCommands.search(LfReader.FLIPPER))
        assertEquals("", LfCommands.search(LfReader.EM_SERIAL))
        val em = LfParse.em("0f0368568b"); val hid = LfParse.hid(118, 1603); val fdx = LfParse.fdx(999, 112233)
        assertEquals("lf em 410x clone --id 0F0368568B", LfCommands.clone(LfReader.PROXMARK3, em))
        assertEquals("lf hid clone -w H10301 --fc 118 --cn 1603", LfCommands.clone(LfReader.PROXMARK3, hid))
        assertEquals("lf fdxb clone --country 999 --national 112233", LfCommands.clone(LfReader.PROXMARK3, fdx))
        assertEquals("rfid write EM4100 0F0368568B", LfCommands.clone(LfReader.FLIPPER, em))
        assertEquals("rfid write H10301 760643", LfCommands.clone(LfReader.FLIPPER, hid))
        assertEquals("rfid emulate EM4100 0F0368568B", LfCommands.emulate(em))
        assertEquals("rfid emulate H10301 760643", LfCommands.emulate(hid))
        assertThrows(IllegalStateException::class.java) { LfCommands.emulate(fdx) }
        assertThrows(IllegalStateException::class.java) { LfCommands.clone(LfReader.FLIPPER, fdx) }
        assertThrows(IllegalStateException::class.java) { LfCommands.clone(LfReader.EM_SERIAL, em) }
        assertThrows(IllegalStateException::class.java) { LfCommands.clone(LfReader.PROXMARK3, LfParse.t5577(listOf("00148040"))) }
        assertEquals("lf t55xx write -b 0 -d 00148040", LfCommands.t55xxWrite(0, "00148040"))
        assertEquals("lf t55xx write -b 7 -d DEADBEEF -p 51243648", LfCommands.t55xxWrite(7, "deadbeef", "51243648"))
        assertThrows(IllegalArgumentException::class.java) { LfCommands.t55xxWrite(8, "00148040") }
        assertThrows(IllegalArgumentException::class.java) { LfCommands.t55xxWrite(-1, "00148040") }
        assertThrows(IllegalArgumentException::class.java) { LfCommands.t55xxWrite(1, "0014804G") }
        assertThrows(IllegalArgumentException::class.java) { LfCommands.t55xxWrite(1, "00148040", "123") }
        assertEquals(listOf(true, true, false), LfReader.values().map { it.canWrite })
        assertEquals(listOf("\n", "\r\n", ""), LfReader.values().map { it.eol })
    }

    @Test fun `reader output parse`() {
        assertEquals(LfParse.em("0F0368568B"), LfParse.parse("[+] EM 410x ID 0F0368568B\n"))
        assertEquals(LfParse.em("0F0368568B"), LfParse.parse("EM4100 0F 03 68 56 8B"))
        assertEquals(LfParse.hid(118, 1603), LfParse.parse("[+] [H10301  ] HID H10301 26-bit  FC: 118  CN: 1603  parity ( ok )"))
        assertEquals(LfParse.hid(118, 1603), LfParse.parse("H10301 76 06 43"))
        assertEquals(LfParse.fdx(999, 112233), LfParse.parse("[+] Animal ID........... 999-000000112233"))
        val t = LfParse.parse("[=]  00 | 00148040 | x\n[=]  01 | FF8C65B0 | x")!!
        assertEquals(LfType.T5577, t.type)
        assertEquals(listOf("00148040", "FF8C65B0"), t.blocks)
        assertEquals(mapOf("Rate" to "RF/64", "Modulation" to "Manchester", "Max block" to "2", "Password" to "off"), t.fields)
        assertEquals("on", LfParse.t5577(listOf("00000010")).fields["Password"])
        assertNull(LfParse.parse("[-] No known 125/134 kHz tags found!"))
        assertEquals("118:01603", LfParse.hid(118, 1603).id)
        assertEquals("2EC0C86", LfParse.hid(118, 1603).fields["Raw"])
    }

    @Test fun `library JSON and CSV round trip`() {
        val tags = listOf(LfParse.em("0F0368568B").copy(name = "Office, \"main\""), LfParse.hid(118, 1603).copy(name = "Gym"),
            LfParse.fdx(999, 112233).copy(name = "Cat"), LfParse.t5577(listOf("00148040", "FF8C65B0")).copy(name = "Blank"))
        assertEquals(tags, LfLibrary.fromJson(LfLibrary.toJson(tags)))
        assertEquals(tags, LfLibrary.fromCsv(LfLibrary.toCsv(tags)))
        assertEquals("name,type,id,blocks\nGym,HID26,118:01603,", LfLibrary.toCsv(listOf(tags[1])))
        assertEquals("\"Office, \"\"main\"\"\",EM4100,0F0368568B,", LfLibrary.toCsv(listOf(tags[0])).lines()[1])
        assertEquals(emptyList<LfTag>(), LfLibrary.fromCsv("name,type,id,blocks\n\n"))
    }
}
