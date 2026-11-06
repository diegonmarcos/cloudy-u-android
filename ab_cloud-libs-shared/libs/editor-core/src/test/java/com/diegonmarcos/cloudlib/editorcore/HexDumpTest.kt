package com.diegonmarcos.cloudlib.editorcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HexDumpTest {
    @Test fun `a full line shows offset, hex pairs and ascii`() {
        val line = HexDump.line(0x10, "Hello, hex view!".toByteArray())
        assertEquals("00000010  48 65 6c 6c 6f 2c 20 68  65 78 20 76 69 65 77 21  |Hello, hex view!|", line)
    }

    @Test fun `a short last line pads the hex column and keeps the ascii aligned`() {
        val full = HexDump.line(0, ByteArray(16) { 0x41 })
        val short = HexDump.line(16, byteArrayOf(0x41, 0x00, 0x7F, 0xFF.toByte()), 4)
        assertEquals(full.indexOf('|'), short.indexOf('|'))
        assertTrue(short.endsWith("|A...|"))
    }

    @Test fun `lines cover the whole input and the count rounds up`() {
        assertEquals(0L, HexDump.lineCount(0)); assertEquals(1L, HexDump.lineCount(1)); assertEquals(1L, HexDump.lineCount(16)); assertEquals(2L, HexDump.lineCount(17))
        val lines = HexDump.lines(ByteArray(40) { it.toByte() })
        assertEquals(3, lines.size)
        assertTrue(lines[2].startsWith("00000020  20 21 22 23"))
        assertEquals(emptyList<String>(), HexDump.lines(ByteArray(0)))
    }
}
