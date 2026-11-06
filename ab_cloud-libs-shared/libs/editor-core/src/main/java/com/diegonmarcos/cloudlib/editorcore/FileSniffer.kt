package com.diegonmarcos.cloudlib.editorcore

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction

/** One magic-number rule of editor-types.json `sniff.magic`: every (offset, bytes) part must match. */
class Magic(val name: String, val viewer: String, val format: String, val match: List<Pair<Int, ByteArray>>) {
    fun matches(head: ByteArray, n: Int): Boolean = match.isNotEmpty() && match.all { (off, bytes) ->
        off >= 0 && off + bytes.size <= n && bytes.indices.all { head[off + it] == bytes[it] }
    }
}

/** The declared sniff rules (editor-types.json `sniff`): how much to read, the text thresholds, where text and binary go, the magic table. */
class SniffRules(
    val sampleBytes: Int,
    val maxControlRatio: Double,
    val minPrintableAscii: Double,
    val textViewer: String,
    val textFormat: String,
    val binaryViewer: String,
    val binaryFormat: String,
    val magic: List<Magic>,
)

/**
 * #1537 what a file with no (or an unknown) extension is, from its first bytes: a known container by magic number, else text
 * (UTF-8 or an ASCII-ish single-byte encoding, no NUL, hardly any control bytes), else binary. Pure: bytes in, verdict out,
 * the JVM suite drives it. The thresholds and the magic table are DATA ([SniffRules]).
 */
object FileSniffer {
    enum class Kind { MAGIC, TEXT, BINARY }

    data class Result(val viewer: String, val format: String, val kind: Kind, val name: String)

    fun sniff(head: ByteArray, rules: SniffRules): Result {
        val n = minOf(head.size, rules.sampleBytes)
        for (m in rules.magic) if (m.matches(head, n)) return Result(m.viewer, m.format, Kind.MAGIC, m.name)
        return if (isText(head, n, rules)) Result(rules.textViewer, rules.textFormat, Kind.TEXT, "text")
        else Result(rules.binaryViewer, rules.binaryFormat, Kind.BINARY, "binary")
    }

    /** Empty and BOM-marked files are text; a NUL byte is binary; else control bytes stay under the ratio and the bytes are UTF-8 or mostly printable ASCII. */
    fun isText(b: ByteArray, n: Int, rules: SniffRules): Boolean {
        if (n == 0) return true
        if (hasBom(b, n)) return true
        var control = 0
        var printable = 0
        for (i in 0 until n) {
            val c = b[i].toInt() and 0xFF
            if (c == 0) return false
            if (c < 0x20 && c != 0x09 && c != 0x0A && c != 0x0D && c != 0x0C && c != 0x1B) control++
            if (c in 0x20..0x7E || c == 0x09 || c == 0x0A || c == 0x0D) printable++
        }
        if (control.toDouble() / n > rules.maxControlRatio) return false
        if (validUtf8(b, n)) return true
        return printable.toDouble() / n >= rules.minPrintableAscii
    }

    private fun hasBom(b: ByteArray, n: Int): Boolean {
        if (n >= 3 && b[0] == 0xEF.toByte() && b[1] == 0xBB.toByte() && b[2] == 0xBF.toByte()) return true
        if (n >= 2 && ((b[0] == 0xFE.toByte() && b[1] == 0xFF.toByte()) || (b[0] == 0xFF.toByte() && b[1] == 0xFE.toByte()))) return true
        return false
    }

    /** UTF-8 validity of the sample; a multi-byte sequence cut off by the end of the sample is not an error. */
    internal fun validUtf8(b: ByteArray, n: Int): Boolean {
        val dec = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        return !dec.decode(ByteBuffer.wrap(b, 0, n), CharBuffer.allocate(n + 1), false).isError
    }
}
