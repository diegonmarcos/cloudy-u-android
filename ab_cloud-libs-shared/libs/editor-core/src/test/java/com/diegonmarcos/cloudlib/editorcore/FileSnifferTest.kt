package com.diegonmarcos.cloudlib.editorcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FileSnifferTest {
    private val rules = SniffRules(
        4096, 0.02, 0.95, Viewers.WYSIWYG, "text", Viewers.HEX, "hex",
        listOf(
            Magic("png", "media", "image", listOf(0 to byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47))),
            Magic("riff-webp", "media", "image", listOf(0 to "RIFF".toByteArray(), 8 to "WEBP".toByteArray())),
            Magic("riff-wav", "media", "audio", listOf(0 to "RIFF".toByteArray(), 8 to "WAVE".toByteArray())),
            Magic("ftyp-heic", "media", "image", listOf(4 to "ftyp".toByteArray(), 8 to "heic".toByteArray())),
            Magic("ftyp", "media", "video", listOf(4 to "ftyp".toByteArray())),
        ),
    )

    private fun kind(b: ByteArray) = FileSniffer.sniff(b, rules).kind
    private fun riff(tag: String) = "RIFF".toByteArray() + byteArrayOf(1, 2, 3, 4) + tag.toByteArray() + ByteArray(8)

    @Test fun `ascii and utf8 text are text`() {
        assertEquals(FileSniffer.Kind.TEXT, kind("#!/bin/sh\necho hi\n".toByteArray()))
        assertEquals(FileSniffer.Kind.TEXT, kind("naïve café – 日本語 ✓\n".toByteArray(Charsets.UTF_8)))
        assertEquals(FileSniffer.Kind.TEXT, kind(ByteArray(0)))
        assertEquals(FileSniffer.Kind.TEXT, kind("tab\tseparated\r\nlines\u001b[0m\n".toByteArray()))
    }

    @Test fun `a utf8 sequence cut by the end of the sample is still text`() {
        val s = "é".repeat(3000).toByteArray(Charsets.UTF_8)
        assertTrue(s.size > 4096)
        val cutAtOddByte = s.copyOf(4095)
        assertEquals(FileSniffer.Kind.TEXT, kind(cutAtOddByte))
        assertEquals(FileSniffer.Kind.TEXT, kind(s))
    }

    @Test fun `a single byte encoding that is mostly ascii is text`() {
        val latin1 = "Café crème, naïve façade, plain ascii for the rest of this line so the ratio holds, and a few more plain words to be safe, nothing exotic in here at all.\n".toByteArray(Charsets.ISO_8859_1)
        assertFalse(FileSniffer.validUtf8(latin1, latin1.size))
        assertEquals(FileSniffer.Kind.TEXT, kind(latin1))
    }

    @Test fun `a BOM marks utf16 text even though it holds NUL bytes`() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "hi there".toByteArray(Charsets.UTF_16LE)
        assertEquals(FileSniffer.Kind.TEXT, kind(utf16))
        val bom8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "x".toByteArray()
        assertEquals(FileSniffer.Kind.TEXT, kind(bom8))
    }

    @Test fun `NUL bytes and control or high bytes are binary`() {
        assertEquals(FileSniffer.Kind.BINARY, kind(byteArrayOf(0x7F, 0x45, 0x4C, 0x46, 2, 1, 1, 0, 0, 0)))
        assertEquals(FileSniffer.Kind.BINARY, kind("text then a NUL\u0000 inside".toByteArray()))
        assertEquals(FileSniffer.Kind.BINARY, kind(ByteArray(512) { (it * 31 + 200).toByte() }.also { for (i in it.indices) if (it[i] == 0.toByte()) it[i] = 1 }))
        assertEquals(FileSniffer.Kind.BINARY, kind(ByteArray(100) { 0x01 }))
    }

    @Test fun `magic numbers claim known containers before the text test`() {
        val png = FileSniffer.sniff(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0, 0), rules)
        assertEquals(FileSniffer.Kind.MAGIC, png.kind); assertEquals("image", png.format); assertEquals("png", png.name)
        assertEquals("image", FileSniffer.sniff(riff("WEBP"), rules).format)
        assertEquals("audio", FileSniffer.sniff(riff("WAVE"), rules).format)
        assertEquals("a RIFF of another kind is not claimed", FileSniffer.Kind.BINARY, kind(riff("ZZZZ").also { it[5] = 0 }))
        val mp4 = byteArrayOf(0, 0, 0, 0x18) + "ftypisom".toByteArray() + ByteArray(8)
        assertEquals("video", FileSniffer.sniff(mp4, rules).format)
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + ByteArray(8)
        assertEquals("heic is matched before the generic ftyp", "image", FileSniffer.sniff(heic, rules).format)
    }

    @Test fun `a magic part past the end of the sample never matches`() {
        assertEquals(FileSniffer.Kind.TEXT, kind("RIFF".toByteArray() + "abcd".toByteArray()))
    }
}
