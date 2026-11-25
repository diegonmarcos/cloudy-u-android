package com.diegonmarcos.superapp.fleetendpoints.errors

/**
 * #1900/#1925 THE plain-text to Markdown export helper: the title line, one `##` heading per `== ` section, the rows fenced
 * so the columns stay aligned. The VPN page's MeshReport and Cloud Mail's error export both call this one function.
 */
object ReportMarkdown {
    fun fromPlain(plain: String): String {
        val out = StringBuilder()
        var open = false
        fun close() { if (open) { out.append("```\n\n"); open = false } }
        plain.lines().forEachIndexed { i, l ->
            when {
                i == 0 -> out.append("# ").append(l).append("\n\n")
                l.startsWith("== ") -> { close(); out.append("## ").append(l.removePrefix("== ")).append("\n\n```\n"); open = true }
                else -> { if (!open) { out.append("```\n"); open = true }; out.append(l).append('\n') }
            }
        }
        close()
        return out.toString().trimEnd() + "\n"
    }
}
