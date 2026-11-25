package com.diegonmarcos.cloudlib.scanner

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * #1908 file naming templates: {date} 2026-11-25, {time} 134502, {n} the page or file number (zero padded to three), {title} the
 * document title, {type} the file type (pdf, jpg ...). A token it does not know is kept as typed; the result is made file-name safe.
 */
object NameTemplate {
    const val DEFAULT = "{title}_{date}"
    val TOKENS = listOf("{date}", "{time}", "{n}", "{title}", "{type}")

    fun render(template: String, title: String, type: String, n: Int, now: LocalDateTime): String {
        val t = template.ifBlank { DEFAULT }
        val out = t.replace("{date}", now.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")))
            .replace("{time}", now.format(DateTimeFormatter.ofPattern("HHmmss")))
            .replace("{n}", n.toString().padStart(3, '0'))
            .replace("{title}", title.ifBlank { DEFAULT_EXPORT_NAME })
            .replace("{type}", type)
        return ScannerExport.safeName(out)
    }
}
