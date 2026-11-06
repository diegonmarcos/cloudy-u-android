package com.diegonmarcos.cloudlib.editorcore

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import com.diegonmarcos.superapp.uikit.KitDensity
import java.io.File
import java.io.RandomAccessFile

object HexTags {
    const val VIEW = "editor_hex"
    const val ROW = "editor_hex_row"
}

/** The pure formatting of a hex dump: `00000010  48 65 6c 6c 6f 20 77 6f  72 6c 64 0a              |Hello world.|`. */
object HexDump {
    const val BYTES_PER_LINE = 16

    fun line(offset: Long, bytes: ByteArray, n: Int = bytes.size): String = buildString {
        append("%08x  ".format(offset))
        for (i in 0 until BYTES_PER_LINE) {
            if (i < n) append("%02x ".format(bytes[i].toInt() and 0xFF)) else append("   ")
            if (i == BYTES_PER_LINE / 2 - 1) append(' ')
        }
        append(" |")
        for (i in 0 until n) { val c = bytes[i].toInt() and 0xFF; append(if (c in 0x20..0x7E) c.toChar() else '.') }
        append('|')
    }

    fun lineCount(length: Long): Long = (length + BYTES_PER_LINE - 1) / BYTES_PER_LINE

    /** The whole of [bytes] as dump lines. */
    fun lines(bytes: ByteArray): List<String> = (0 until lineCount(bytes.size.toLong()).toInt()).map { i ->
        val from = i * BYTES_PER_LINE
        val n = minOf(BYTES_PER_LINE, bytes.size - from)
        line(from.toLong(), bytes.copyOfRange(from, from + n), n)
    }
}

/** #1537 the binary fallback: a read-only hex dump of [file], read a line at a time so a large file costs nothing until it is scrolled. */
@Composable
fun HexView(file: File, modifier: Modifier = Modifier, title: String = "Binary file · hex view") {
    val raf = remember(file) { runCatching { RandomAccessFile(file, "r") }.getOrNull() }
    DisposableEffect(raf) { onDispose { runCatching { raf?.close() } } }
    val length = remember(file) { file.length() }
    val total = HexDump.lineCount(length).coerceAtMost(MAX_LINES.toLong()).toInt()
    Column(modifier.fillMaxSize().testTag(HexTags.VIEW)) {
        Text(
            "$title · $length B", Modifier.padding(horizontal = KitDensity.rowPadH, vertical = KitDensity.rowPadV / 2),
            fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(Modifier.fillMaxSize().horizontalScroll(rememberScrollState())) {
            items((0 until total).toList(), key = { it }) { i ->
                val offset = i.toLong() * HexDump.BYTES_PER_LINE
                val text = remember(raf, i) {
                    val buf = ByteArray(HexDump.BYTES_PER_LINE)
                    val n = runCatching { synchronized(raf!!) { raf.seek(offset); raf.read(buf, 0, buf.size) } }.getOrDefault(0).coerceAtLeast(0)
                    HexDump.line(offset, buf, n)
                }
                Text(
                    text, Modifier.padding(horizontal = KitDensity.rowPadH).testTag(HexTags.ROW),
                    fontFamily = FontFamily.Monospace, fontSize = KitDensity.caption, color = MaterialTheme.colorScheme.onSurface, softWrap = false,
                )
            }
        }
    }
}

private const val MAX_LINES = 1_048_576
