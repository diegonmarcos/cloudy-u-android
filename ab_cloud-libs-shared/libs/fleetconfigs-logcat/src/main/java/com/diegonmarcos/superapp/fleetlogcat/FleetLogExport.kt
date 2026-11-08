package com.diegonmarcos.superapp.fleetlogcat

/**
 * Formats a per-app logcat snapshot for export, the same two-way style the X-Ray tab uses: an HTML page a human reads and a
 * token-optimized Markdown an agent reads. Pure (no Android), so it is covered by a plain JVM test and the on-device page only
 * gathers the lines and hands them here.
 */
public object FleetLogExport {

    /** The captured logcat of one app: its identity, when and how it was read, and the raw `-v threadtime` lines. */
    public data class Snapshot(
        val app: String,
        val pkg: String,
        val sha: String,
        val capturedAt: String,
        val health: String,
        val errorsOnly: Boolean,
        val lines: List<String>,
    )

    /** A `MM-DD HH:MM:SS.mmm UID PID TID L TAG: msg` record split into the parts the exporters group by. */
    public data class Record(val time: String, val level: Char, val tag: String, val message: String, val raw: String)

    private val WS = Regex("\\s+")

    /** Parse one line; a separator or malformed line keeps its whole text as the message with level ' '. */
    public fun parse(line: String): Record {
        val f = line.trim().split(WS, limit = 7)
        if (f.size < 7 || f[5].length != 1) return Record("", ' ', "", line, line)
        val tag = f[6].substringBefore(':').trim()
        val msg = f[6].substringAfter(':', f[6]).trim()
        return Record(f[1], f[5][0], tag, msg, line)
    }

    private fun esc(s: String): String =
        s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun levelName(c: Char): String = when (c) {
        'V' -> "verbose"; 'D' -> "debug"; 'I' -> "info"; 'W' -> "warn"; 'E' -> "error"; 'F' -> "fatal"; else -> "raw"
    }

    /** A self-contained HTML page: header with identity/health and a coloured, monospaced log table. */
    public fun html(s: Snapshot): String {
        val rows = s.lines.joinToString("\n") {
            val r = parse(it)
            "<tr class=\"l-${levelName(r.level)}\"><td class=\"t\">${esc(r.time)}</td><td class=\"v\">${r.level}</td>" +
                "<td class=\"g\">${esc(r.tag)}</td><td class=\"m\">${esc(r.message)}</td></tr>"
        }
        return """<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Logcat — ${esc(s.app)}</title>
<style>
 :root{color-scheme:dark light}
 body{font:13px/1.5 system-ui,sans-serif;margin:0;background:#0d1117;color:#e6edf3}
 header{padding:14px 16px;border-bottom:1px solid #30363d}
 h1{font-size:16px;margin:0 0 6px}
 .meta{color:#8b949e;font-size:12px}
 table{width:100%;border-collapse:collapse;font-family:ui-monospace,monospace;font-size:12px}
 td{padding:2px 8px;vertical-align:top;border-bottom:1px solid #161b22}
 .t{color:#8b949e;white-space:nowrap}.v{text-align:center;font-weight:600}.g{color:#58a6ff;white-space:nowrap}
 .m{white-space:pre-wrap;word-break:break-word}
 .l-error .v,.l-fatal .v{color:#f85149}.l-warn .v{color:#d29922}.l-info .v{color:#3fb950}
</style></head><body>
<header><h1>Logcat — ${esc(s.app)}</h1>
<div class="meta">${esc(s.pkg)} · build ${esc(s.sha.ifBlank { "local" })} · ${esc(s.capturedAt)} · ${s.lines.size} line(s)${if (s.errorsOnly) " · errors only" else ""}</div>
<div class="meta">${esc(s.health)}</div></header>
<table>
$rows
</table></body></html>
"""
    }

    /**
     * Token-optimized Markdown: one fenced block, a compact header, and `HH:MM:SS L/TAG msg` per line — the redundant `MM-DD `
     * date every line repeats is dropped (the capture time is in the header), which is the cheap, lossless trim that matters at
     * scale. Separators and malformed lines pass through verbatim.
     */
    public fun markdown(s: Snapshot): String {
        val head = buildString {
            append("# Logcat — ").append(s.app).append('\n')
            append("- pkg: `").append(s.pkg).append("`\n")
            append("- build: `").append(s.sha.ifBlank { "local" }).append("`\n")
            append("- captured: ").append(s.capturedAt).append('\n')
            append("- health: ").append(s.health).append('\n')
            append("- lines: ").append(s.lines.size)
            if (s.errorsOnly) append(" (errors only)")
            append('\n')
        }
        val body = s.lines.joinToString("\n") {
            val r = parse(it)
            if (r.level == ' ') r.raw.trim()
            else "${r.time} ${r.level}/${r.tag} ${r.message}"
        }
        return head + "\n```log\n" + body + "\n```\n"
    }
}
